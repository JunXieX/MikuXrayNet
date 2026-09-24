package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 显形索引：记录「某玩家在某区块内、被反矿透伪装过的方块坐标」，供邻近显形与变更注销查询。
 *
 * <p><b>内存纪律</b>：键只有 UUID 与 {@code (世界名, chunkX, chunkZ)}，坐标压成一个 {@code long}；
 * 全程不持有 Player / World / Chunk / 封包 的强引用。因此它不会把世界对象钉在堆上，
 * 玩家登出、世界卸载时按 UUID / 世界名整体清理即可。三重上界保证有界：
 * 全服坐标上限、单玩家坐标上限、按时间过期。
 *
 * <p><b>线程纪律</b>：写入来自反矿透的工作线程，查询与注销来自主线程 / Folia 区域线程；
 * 内部只用并发容器与「逐区块条目锁」，纯数据结构，不触碰任何 Bukkit API，可离线单测。
 *
 * <p><b>并发取舍</b>：注销会把空区块条目从索引摘除，若此时正好有工作线程往该条目写入，
 * 该次写入会落在已摘除的对象上——效果只是「该坐标少显形一次」，不会污染其它数据。
 */
public final class RevealedBlockIndex {

  /** 世界内的绝对方块坐标。 */
  public record Position(int x, int y, int z) {
  }

  /** 区块键：只含不可变类型，不可能间接钉住世界对象。 */
  private record ChunkKey(String worldName, int chunkX, int chunkZ) {
  }

  /** 单个玩家的全部区块条目。 */
  private static final class PlayerIndex {

    private final ConcurrentHashMap<ChunkKey, ChunkEntry> chunks = new ConcurrentHashMap<>();
    private final AtomicInteger positionCount = new AtomicInteger();
  }

  /** 单个区块内被伪装过的坐标（打包为 long）；读写都在条目锁内进行。 */
  private static final class ChunkEntry {

    private long[] packed = new long[4];
    private int size;
    private long updatedAtNanos;
  }

  /** x / z 各占 26 位：支持 ±33,554,431，覆盖世界边界 ±3000 万。 */
  private static final int XZ_BITS = 26;
  private static final int XZ_MASK = (1 << XZ_BITS) - 1;
  /** y 占 12 位：支持 -2048 ~ 2047，覆盖 -64 ~ 320 的建筑高度。 */
  private static final int Y_BITS = 12;
  private static final int Y_MASK = (1 << Y_BITS) - 1;

  private final ConcurrentHashMap<UUID, PlayerIndex> players = new ConcurrentHashMap<>();
  private final AtomicInteger totalPositions = new AtomicInteger();
  private final LongAdder droppedByCapacity = new LongAdder();

  private final int maxPositions;
  private final int maxPositionsPerPlayer;
  private final long expireNanos;
  private final LongSupplier clock;

  /**
   * @param maxPositions          全服坐标上限
   * @param maxPositionsPerPlayer 单玩家坐标上限
   * @param expireSeconds         条目过期秒数
   */
  public RevealedBlockIndex(int maxPositions, int maxPositionsPerPlayer, int expireSeconds) {
    this(maxPositions, maxPositionsPerPlayer,
        TimeUnit.SECONDS.toNanos(Math.max(1L, expireSeconds)), System::nanoTime);
  }

  /** 测试用构造：可注入时钟与纳秒级过期时间。 */
  RevealedBlockIndex(int maxPositions, int maxPositionsPerPlayer, long expireNanos,
      LongSupplier clock) {
    this.maxPositions = Math.max(1, maxPositions);
    this.maxPositionsPerPlayer = Math.max(1, maxPositionsPerPlayer);
    this.expireNanos = Math.max(1L, expireNanos);
    this.clock = clock;
  }

  /**
   * 记录一个坐标被伪装过。
   *
   * <p>容量判定先于条目创建：容量已满时连区块条目都不会新建，只累加「容量丢弃」计数
   * （此时的重复坐标同样按丢弃计数，不影响已有记录）。
   *
   * @return 是否真的写入（已在索引中或超出容量上限时返回 false）
   */
  public boolean record(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      return false;
    }
    if (totalPositions.get() >= maxPositions) {
      droppedByCapacity.increment();
      return false;
    }

    PlayerIndex playerIndex = players.computeIfAbsent(playerId, uuid -> new PlayerIndex());
    if (playerIndex.positionCount.get() >= maxPositionsPerPlayer) {
      droppedByCapacity.increment();
      return false;
    }

    ChunkEntry entry = playerIndex.chunks.computeIfAbsent(
        new ChunkKey(worldName, x >> 4, z >> 4), key -> new ChunkEntry());
    long packedValue = pack(x, y, z);

    synchronized (entry) {
      // 无论是否写入成功都刷新活跃时间：区块被重新下发意味着这些坐标仍然有效
      entry.updatedAtNanos = clock.getAsLong();

      for (int index = 0; index < entry.size; index++) {
        if (entry.packed[index] == packedValue) {
          return false;
        }
      }

      if (entry.size == entry.packed.length) {
        entry.packed = Arrays.copyOf(entry.packed, entry.size << 1);
      }
      entry.packed[entry.size++] = packedValue;
      playerIndex.positionCount.incrementAndGet();
      totalPositions.incrementAndGet();
      return true;
    }
  }

  /**
   * 记录一个区块内被伪装的坐标（区块封包改写路径）。
   *
   * @param minHeight      该世界最低建筑高度
   * @param localPositions 区块内相对坐标，编码为 {@code y << 8 | z << 4 | x}，其中 y 相对最低建筑高度
   * @return 真正记录下来的坐标数
   */
  public int record(UUID playerId, String worldName, int chunkX, int chunkZ, int minHeight,
      int[] localPositions) {
    if (playerId == null || worldName == null || localPositions == null
        || localPositions.length == 0) {
      return 0;
    }

    int recorded = 0;
    for (int local : localPositions) {
      int x = (chunkX << 4) | (local & 15);
      int z = (chunkZ << 4) | (local >> 4 & 15);
      int y = minHeight + (local >> 8);
      if (record(playerId, worldName, x, y, z)) {
        recorded++;
      }
    }
    return recorded;
  }

  /**
   * 注销一个坐标（已显形，或服务端已自行下发该坐标的变更）。
   *
   * @return 是否命中并注销
   */
  public boolean unregister(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      return false;
    }
    PlayerIndex playerIndex = players.get(playerId);
    return playerIndex != null
        && remove(playerIndex, new ChunkKey(worldName, x >> 4, z >> 4), pack(x, y, z));
  }

  /**
   * 注销一个坐标，不区分世界（供出站方块变更包路径使用：封包线程不访问 Bukkit，拿不到世界名）。
   *
   * <p>只扫描区块坐标匹配的条目，因此即便不同世界存在相同区块坐标，代价也只是一个极小的子集；
   * 唯一副作用是可能顺带注销另一个世界的同名坐标（该坐标少显形一次），影响可忽略。
   *
   * @return 是否命中并注销
   */
  public boolean unregister(UUID playerId, int x, int y, int z) {
    if (playerId == null) {
      return false;
    }
    PlayerIndex playerIndex = players.get(playerId);
    if (playerIndex == null) {
      return false;
    }

    int chunkX = x >> 4;
    int chunkZ = z >> 4;
    long packedValue = pack(x, y, z);
    for (Map.Entry<ChunkKey, ChunkEntry> chunkEntry : playerIndex.chunks.entrySet()) {
      ChunkKey key = chunkEntry.getKey();
      if (key.chunkX() != chunkX || key.chunkZ() != chunkZ) {
        continue;
      }
      if (remove(playerIndex, key, packedValue, chunkEntry.getValue())) {
        return true;
      }
    }
    return false;
  }

  private boolean remove(PlayerIndex playerIndex, ChunkKey key, long packedValue) {
    ChunkEntry entry = playerIndex.chunks.get(key);
    return entry != null && remove(playerIndex, key, packedValue, entry);
  }

  private boolean remove(PlayerIndex playerIndex, ChunkKey key, long packedValue, ChunkEntry entry) {
    synchronized (entry) {
      for (int index = 0; index < entry.size; index++) {
        if (entry.packed[index] != packedValue) {
          continue;
        }
        // 交换删除：条目内坐标无序，用末项填补空洞即可
        entry.packed[index] = entry.packed[--entry.size];
        entry.packed[entry.size] = 0L;
        subtract(playerIndex.positionCount, 1);
        subtract(totalPositions, 1);
        if (entry.size == 0) {
          playerIndex.chunks.remove(key, entry);
        }
        return true;
      }
    }
    return false;
  }

  /**
   * 取出距离玩家方块坐标不超过 {@code maxDistance}（含等于）的候选坐标，按距离由近到远排序。
   *
   * <p>命中区块的条目在读取期间加锁，因此与工作线程的写入不会读到半更新的数组。
   *
   * @param limit 返回条数上限；小于等于 0 时返回空列表
   * @return 候选坐标（不超过 limit 条）；无候选时返回空列表
   */
  public List<Position> candidates(UUID playerId, String worldName, int x, int y, int z,
      double maxDistance, int limit) {
    if (playerId == null || worldName == null || limit <= 0 || maxDistance < 0.0D) {
      return List.of();
    }

    PlayerIndex playerIndex = players.get(playerId);
    if (playerIndex == null) {
      return List.of();
    }

    double maxDistanceSquared = maxDistance * maxDistance;
    List<Position> found = new ArrayList<>();
    for (Map.Entry<ChunkKey, ChunkEntry> chunkEntry : playerIndex.chunks.entrySet()) {
      if (!chunkEntry.getKey().worldName().equals(worldName)) {
        continue;
      }
      ChunkEntry entry = chunkEntry.getValue();
      synchronized (entry) {
        for (int index = 0; index < entry.size; index++) {
          long packedValue = entry.packed[index];
          int blockX = unpackX(packedValue);
          int blockY = unpackY(packedValue);
          int blockZ = unpackZ(packedValue);
          if (distanceSquared(blockX, blockY, blockZ, x, y, z) <= maxDistanceSquared) {
            found.add(new Position(blockX, blockY, blockZ));
          }
        }
      }
    }

    if (found.size() <= 1) {
      return found;
    }
    found.sort(Comparator.comparingLong((Position position) -> distanceSquared(
        position.x(), position.y(), position.z(), x, y, z)));
    return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
  }

  /** 清理超过过期时间的条目（巡检任务周期调用）。 */
  public void expire() {
    expire(clock.getAsLong());
  }

  /**
   * 清理超过过期时间的条目。
   *
   * @param nowNanos 当前时刻（纳秒），必须与构造索引时的时钟同一来源
   */
  public void expire(long nowNanos) {
    for (PlayerIndex playerIndex : players.values()) {
      for (Map.Entry<ChunkKey, ChunkEntry> chunkEntry : playerIndex.chunks.entrySet()) {
        ChunkEntry entry = chunkEntry.getValue();
        int removed;
        synchronized (entry) {
          if (nowNanos - entry.updatedAtNanos <= expireNanos) {
            continue;
          }
          removed = entry.size;
          if (!playerIndex.chunks.remove(chunkEntry.getKey(), entry)) {
            continue;
          }
        }
        subtract(playerIndex.positionCount, removed);
        subtract(totalPositions, removed);
      }
    }
  }

  /** 玩家登出时清理其全部条目。 */
  public void clearPlayer(UUID playerId) {
    if (playerId == null) {
      return;
    }
    PlayerIndex removed = players.remove(playerId);
    if (removed == null) {
      return;
    }

    int removedCount = 0;
    for (ChunkEntry entry : removed.chunks.values()) {
      synchronized (entry) {
        removedCount += entry.size;
      }
    }
    subtract(removed.positionCount, removedCount);
    subtract(totalPositions, removedCount);
    removed.chunks.clear();
  }

  /** 世界卸载时清理该世界的全部条目（键里只有世界名，因此不会钉住世界对象）。 */
  public void clearWorld(String worldName) {
    if (worldName == null) {
      return;
    }
    for (PlayerIndex playerIndex : players.values()) {
      for (ChunkKey key : playerIndex.chunks.keySet()) {
        if (!key.worldName().equals(worldName)) {
          continue;
        }
        ChunkEntry entry = playerIndex.chunks.get(key);
        if (entry == null) {
          continue;
        }
        int removed;
        synchronized (entry) {
          if (!playerIndex.chunks.remove(key, entry)) {
            continue;
          }
          removed = entry.size;
        }
        subtract(playerIndex.positionCount, removed);
        subtract(totalPositions, removed);
      }
    }
  }

  /** 清空全部条目（插件停用时调用，不留副作用）。 */
  public void clear() {
    players.clear();
    totalPositions.set(0);
  }

  /** 当前累计坐标数（诊断用）。 */
  public int size() {
    return Math.max(0, totalPositions.get());
  }

  /** 当前条目所属玩家数（诊断用）。 */
  public int playerCount() {
    return players.size();
  }

  /** 当前区块条目数（诊断用；遍历统计，非热路径）。 */
  public int chunkCount() {
    int total = 0;
    for (PlayerIndex playerIndex : players.values()) {
      total += playerIndex.chunks.size();
    }
    return total;
  }

  /** 因容量上限被丢弃的坐标数（诊断用）。 */
  public long droppedByCapacity() {
    return droppedByCapacity.sum();
  }

  private static long distanceSquared(int x, int y, int z, int otherX, int otherY, int otherZ) {
    long dx = (long) x - otherX;
    long dy = (long) y - otherY;
    long dz = (long) z - otherZ;
    return dx * dx + dy * dy + dz * dz;
  }

  /** 计数器只作容量与诊断用途：并发注销与整体清理可能重叠，故夹在 0 以上。 */
  private static void subtract(AtomicInteger counter, int amount) {
    if (amount <= 0) {
      return;
    }
    counter.updateAndGet(current -> Math.max(0, current - amount));
  }

  /** 打包为 long：{@code x(26) | y(12) | z(26)}；越界坐标会被截断，但不影响正常世界范围。 */
  private static long pack(int x, int y, int z) {
    return ((long) (x & XZ_MASK) << 38)
        | ((long) (y & Y_MASK) << 26)
        | (z & XZ_MASK);
  }

  private static int unpackX(long packed) {
    return signExtend((int) (packed >>> 38), XZ_BITS);
  }

  private static int unpackY(long packed) {
    return signExtend((int) (packed >>> 26 & Y_MASK), Y_BITS);
  }

  private static int unpackZ(long packed) {
    return signExtend((int) (packed & XZ_MASK), XZ_BITS);
  }

  private static int signExtend(int value, int bits) {
    int shift = Integer.SIZE - bits;
    return value << shift >> shift;
  }
}