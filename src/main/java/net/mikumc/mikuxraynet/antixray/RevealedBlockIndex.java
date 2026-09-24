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
 * 全服坐标上限、单玩家坐标上限、按时间过期；任一上限触顶时<b>优先淘汰离玩家最远的区块</b>，
 * 而不是丢弃新记录——否则登录时连续下发数百个区块会让「身边坐标」被先到的远处坐标挤掉。
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

    /**
     * 该玩家最近一次被查询到的水平位置（由 {@link #candidates} 刷新）。
     *
     * <p>只用于容量淘汰的决策：满员时优先淘汰「离玩家最远」的区块，保证玩家身边的坐标一定还在索引里。
     * 主线程/区域线程写、工作线程读，volatile 足够（只求最终可见，不要求严格时序）。
     */
    private volatile int lastX;
    private volatile int lastZ;
    private volatile boolean hasPosition;
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

  /**
   * 单次 {@link #record} 里最多淘汰的区块数（防止「淘汰跟不上写入」时陷入长循环）。
   * 一个区块通常有上百个坐标，正常情况下淘汰一个区块就足以腾出空间。
   */
  private static final int MAX_EVICTIONS_PER_RECORD = 64;

  private final ConcurrentHashMap<UUID, PlayerIndex> players = new ConcurrentHashMap<>();
  private final AtomicInteger totalPositions = new AtomicInteger();
  /** 容量满时按「距玩家最远优先」淘汰掉的坐标数（属正常调优行为）。 */
  private final LongAdder evictedByCapacity = new LongAdder();
  /** 容量满且「拿不到玩家位置 / 淘汰也腾不出空间」时被直接放弃的新坐标数（属异常）。 */
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
   * <p><b>容量策略</b>：满员时<b>不再丢弃新记录</b>，而是先淘汰「离玩家最远」的区块条目来腾出空间
   * （{@link #evictFarthestChunk}）——玩家身边、离得近的坐标因此不会被新来的远处区块挤掉。
   * 只有在「拿不到玩家位置、或淘汰也腾不出空间」时才退回丢弃新记录，并累加「容量丢弃」计数。
   *
   * @return 是否真的写入（已在索引中或确实无法腾出空间时返回 false）
   */
  public boolean record(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      return false;
    }
    ChunkKey key = new ChunkKey(worldName, x >> 4, z >> 4);

    if (totalPositions.get() >= maxPositions && !makeRoomGlobally()) {
      droppedByCapacity.increment();
      return false;
    }

    PlayerIndex playerIndex = players.computeIfAbsent(playerId, uuid -> new PlayerIndex());
    if (playerIndex.positionCount.get() >= maxPositionsPerPlayer
        && !makeRoomForPlayer(playerIndex)) {
      droppedByCapacity.increment();
      return false;
    }

    ChunkEntry entry = playerIndex.chunks.computeIfAbsent(key, k -> new ChunkEntry());
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

  // ------------------------------------------------------------------ 容量淘汰（保留近处）

  /**
   * 全服上限已满：淘汰「贡献最多坐标的那个玩家」里离他自己最远的区块，直到全服低于上限。
   *
   * <p>只淘汰「已经知道位置的玩家」的条目——拿不到位置就无法判断远近，此时退回丢弃新记录，
   * 保持原有保守行为（不会误删可能就在玩家身边的坐标）。
   *
   * @return 是否已腾出空间（false 表示无法腾出，调用方丢弃新记录）
   */
  private boolean makeRoomGlobally() {
    for (int attempt = 0;
        attempt < MAX_EVICTIONS_PER_RECORD && totalPositions.get() >= maxPositions; attempt++) {
      if (!evictFarthestChunkOfLargestPlayer()) {
        break;
      }
    }
    return totalPositions.get() < maxPositions;
  }

  /** 在全部「已知位置」的玩家里挑坐标数最多的一个，淘汰其离玩家最远的区块。 */
  private boolean evictFarthestChunkOfLargestPlayer() {
    PlayerIndex largest = null;
    int largestCount = 0;
    for (PlayerIndex candidate : players.values()) {
      if (!candidate.hasPosition) {
        continue;
      }
      int count = candidate.positionCount.get();
      if (count > largestCount) {
        largestCount = count;
        largest = candidate;
      }
    }
    return largest != null && evictFarthestChunk(largest);
  }

  /** 单玩家上限已满：淘汰该玩家离自己最远的区块，直到低于上限。 */
  private boolean makeRoomForPlayer(PlayerIndex playerIndex) {
    for (int attempt = 0;
        attempt < MAX_EVICTIONS_PER_RECORD
            && playerIndex.positionCount.get() >= maxPositionsPerPlayer; attempt++) {
      if (!evictFarthestChunk(playerIndex)) {
        break;
      }
    }
    return playerIndex.positionCount.get() < maxPositionsPerPlayer;
  }

  /**
   * 淘汰玩家索引里「离玩家最远」的一个区块条目。
   *
   * <p>按区块中心与玩家的<b>水平距离</b>比较（区块是竖直列，同一列内不再区分远近）；
   * 距离并列时取先遍历到的那个，不追求稳定顺序。淘汰掉的坐标数计入「淘汰」计数，保证可观测。
   *
   * <p>若「最远的」恰好是本次正在写入的区块，也照淘汰——随后 {@link #record} 会把新坐标重新写进去。
   * 这样语义始终是「保留离玩家最近的坐标」，不会因为「不淘汰正在写的区块」而反过来把身边坐标挤掉。
   *
   * @return 是否真的淘汰掉了一个区块条目
   */
  private boolean evictFarthestChunk(PlayerIndex playerIndex) {
    if (!playerIndex.hasPosition) {
      return false;
    }
    int playerX = playerIndex.lastX;
    int playerZ = playerIndex.lastZ;

    ChunkKey victim = null;
    long farthest = -1L;
    for (ChunkKey key : playerIndex.chunks.keySet()) {
      long distance = horizontalDistanceSquared(key, playerX, playerZ);
      if (distance > farthest) {
        farthest = distance;
        victim = key;
      }
    }
    if (victim == null) {
      return false;
    }

    ChunkEntry entry = playerIndex.chunks.remove(victim);
    if (entry == null) {
      return false;
    }
    int removed;
    synchronized (entry) {
      removed = entry.size;
    }
    subtract(playerIndex.positionCount, removed);
    subtract(totalPositions, removed);
    evictedByCapacity.add(removed);
    return true;
  }

  /** 区块中心与玩家的水平距离平方（区块是竖直列，忽略 y）。 */
  private static long horizontalDistanceSquared(ChunkKey key, int x, int z) {
    long dx = (long) ((key.chunkX() << 4) + 8) - x;
    long dz = (long) ((key.chunkZ() << 4) + 8) - z;
    return dx * dx + dz * dz;
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
   * <p>顺带把玩家当前位置记到该玩家的索引上（供容量淘汰判断远近）；主线程/区域线程调用，
   * 工作线程只读，故用 volatile 字段承载。
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

    playerIndex.lastX = x;
    playerIndex.lastZ = z;
    playerIndex.hasPosition = true;

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

  /**
   * 因容量满而按「距玩家最远优先」<b>淘汰</b>掉的坐标数（诊断用；属正常调优行为，不影响玩家身边的坐标）。
   */
  public long evictedByCapacity() {
    return evictedByCapacity.sum();
  }

  /**
   * 因容量满且「拿不到玩家位置 / 淘汰也腾不出空间」而<b>直接丢弃</b>的新坐标数（诊断用；属异常）。
   *
   * <p>与 {@link #evictedByCapacity()} 分开计数：淘汰是「用远的换近的」，丢弃是「新的根本进不来」。
   */
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