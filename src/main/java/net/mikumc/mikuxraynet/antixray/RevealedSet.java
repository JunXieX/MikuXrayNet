package net.mikumc.mikuxraynet.antixray;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 已显形集合（按玩家）：{@code (世界名, chunkX, chunkZ) → 玩家 UUID → 已发过显形包的坐标}。
 *
 * <p><b>为什么按玩家</b>：显形是「每个玩家各自看到什么」的私有状态——玩家 A 已显形过的坐标
 * 绝不能让玩家 B 跳过显形。因此这里只记录<b>实际发过显形包的坐标</b>，天然有界
 * （不会随「玩家探索过的历史」增长，只与「玩家身边确实解析过的坐标」相关），并随
 * 区块索引失效 / 玩家退出 / 世界卸载 / 过期而清理。
 *
 * <p><b>整块跳过</b>：{@link #sizeFor} 给出「该玩家在该区块已显形多少坐标」；邻近显形扫描时拿它与
 * 区块条目的大小比较——相等即说明整块都已显形，直接跳过该区块（稳态下每周期只处理「新进入视野的
 * 区块」）。该比较成立的前提是「已显形坐标 ⊆ 该区块当前的伪装清单」，这一不变式由三处共同保证：
 * ① 只有从区块清单里取出的坐标才会被标记；② 区块清单被覆盖（重新下发）或被摘除坐标时，
 * 本结构会同步清理（{@link #clearChunk} / {@link #removePosition}）。
 *
 * <p><b>线程纪律</b>：标记来自主线程 / Folia 区域线程（发包成功后）；清理来自主线程 / 区域线程
 * （区块卸载、玩家退出、世界卸载）与出站封包线程（方块变更注销）；读取来自区域线程 / 主线程。
 * 内部只用并发容器与「逐条目锁」，纯数据结构，不触碰任何 Bukkit API，可离线单测。
 */
public final class RevealedSet {

  /** 单个玩家在一个区块内已显形的坐标（打包为 long）；读写都在条目锁内进行。 */
  static final class Marker {

    private long[] packed = new long[4];
    private int size;
    private volatile long updatedAtNanos;
  }

  private final ConcurrentHashMap<ChunkKey, ConcurrentHashMap<UUID, Marker>> chunks =
      new ConcurrentHashMap<>();
  /** 每个玩家已显形的坐标总数（仅供安全阀判断；并发清理时夹在 0 以上）。 */
  private final ConcurrentHashMap<UUID, AtomicInteger> playerTotals = new ConcurrentHashMap<>();
  /** 安全阀：仅当单玩家已显形坐标越过上限（正常运营不该发生）时被放弃标记的坐标数。 */
  private final LongAdder droppedByCapacity = new LongAdder();

  private final int maxPositionsPerPlayer;
  private final long expireNanos;
  private final LongSupplier clock;

  /**
   * @param maxPositionsPerPlayer 单玩家已显形坐标上限（<b>安全阀</b>：正常运营下应为 0 触发）
   * @param expireSeconds         条目过期秒数（区块卸载未触发时的兜底）
   */
  public RevealedSet(int maxPositionsPerPlayer, int expireSeconds) {
    this(maxPositionsPerPlayer, TimeUnit.SECONDS.toNanos(Math.max(1L, expireSeconds)),
        System::nanoTime);
  }

  /** 测试用构造：可注入时钟与纳秒级过期时间。 */
  RevealedSet(int maxPositionsPerPlayer, long expireNanos, LongSupplier clock) {
    this.maxPositionsPerPlayer = Math.max(1, maxPositionsPerPlayer);
    this.expireNanos = Math.max(1L, expireNanos);
    this.clock = clock;
  }

  /** 该玩家在该区块已显形的坐标数（无记录返回 0）。 */
  int sizeFor(UUID playerId, ChunkKey key) {
    Marker marker = markerOf(playerId, key);
    if (marker == null) {
      return 0;
    }
    synchronized (marker) {
      return marker.size;
    }
  }

  /** 该玩家在该区块的该坐标是否已显形过。 */
  boolean contains(UUID playerId, ChunkKey key, int x, int y, int z) {
    Marker marker = markerOf(playerId, key);
    if (marker == null) {
      return false;
    }
    long packedValue = pack(x, y, z);
    synchronized (marker) {
      for (int index = 0; index < marker.size; index++) {
        if (marker.packed[index] == packedValue) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 标记一个坐标已显形（发包成功后调用）。
   *
   * <p>单玩家已显形坐标越界（安全阀）时放弃本次标记：最坏结果是该坐标下次被重复显形一次，
   * 功能不降级、更不会漏显形。
   */
  void mark(UUID playerId, ChunkKey key, int x, int y, int z) {
    if (playerId == null) {
      return;
    }
    long packedValue = pack(x, y, z);
    ConcurrentHashMap<UUID, Marker> players =
        chunks.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
    Marker marker = players.computeIfAbsent(playerId, ignored -> new Marker());

    synchronized (marker) {
      marker.updatedAtNanos = clock.getAsLong();
      for (int index = 0; index < marker.size; index++) {
        if (marker.packed[index] == packedValue) {
          return;
        }
      }
      AtomicInteger total = playerTotals.computeIfAbsent(playerId, ignored -> new AtomicInteger());
      if (total.get() >= maxPositionsPerPlayer) {
        droppedByCapacity.increment();
        return;
      }
      if (marker.size == marker.packed.length) {
        marker.packed = Arrays.copyOf(marker.packed, marker.size << 1);
      }
      marker.packed[marker.size++] = packedValue;
      total.incrementAndGet();
    }
  }

  /** 刷新该玩家在该区块的活跃时间（扫描到该区块时调用，避免身边的标记被过期清掉）。 */
  void touch(UUID playerId, ChunkKey key) {
    Marker marker = markerOf(playerId, key);
    if (marker != null) {
      marker.updatedAtNanos = clock.getAsLong();
    }
  }

  /**
   * 注销一个坐标的已显形标记（服务端自行下发了该坐标的方块变更时调用）。
   *
   * <p>与 {@link ObfuscatedChunkIndex#removePosition} 配套：两者必须同步摘除，才能维持
   * 「已显形坐标 ⊆ 区块伪装清单」这一不变式。
   */
  void removePosition(String worldName, int x, int y, int z) {
    if (worldName == null) {
      return;
    }
    ConcurrentHashMap<UUID, Marker> players = chunks.get(ChunkKey.ofBlock(worldName, x, z));
    if (players == null) {
      return;
    }
    long packedValue = pack(x, y, z);
    for (Map.Entry<UUID, Marker> entry : players.entrySet()) {
      Marker marker = entry.getValue();
      synchronized (marker) {
        for (int index = 0; index < marker.size; index++) {
          if (marker.packed[index] != packedValue) {
            continue;
          }
          // 交换删除：条目内坐标无序，用末项填补空洞即可
          marker.packed[index] = marker.packed[--marker.size];
          marker.packed[marker.size] = 0L;
          AtomicInteger total = playerTotals.get(entry.getKey());
          if (total != null) {
            subtract(total, 1);
          }
          break;
        }
      }
    }
  }

  /** 使一个区块的全部玩家的已显形标记失效（区块卸载 / 区块被重新下发时调用）。 */
  void clearChunk(ChunkKey key) {
    ConcurrentHashMap<UUID, Marker> players = chunks.remove(key);
    if (players == null) {
      return;
    }
    for (Map.Entry<UUID, Marker> entry : players.entrySet()) {
      AtomicInteger total = playerTotals.get(entry.getKey());
      if (total == null) {
        continue;
      }
      Marker marker = entry.getValue();
      synchronized (marker) {
        subtract(total, marker.size);
      }
    }
  }

  /** 玩家退出时清理其全部标记。 */
  void clearPlayer(UUID playerId) {
    if (playerId == null) {
      return;
    }
    for (Map.Entry<ChunkKey, ConcurrentHashMap<UUID, Marker>> chunk : chunks.entrySet()) {
      chunk.getValue().remove(playerId);
      if (chunk.getValue().isEmpty()) {
        chunks.remove(chunk.getKey(), chunk.getValue());
      }
    }
    playerTotals.remove(playerId);
  }

  /** 世界卸载时清理该世界的全部标记。 */
  void clearWorld(String worldName) {
    if (worldName == null) {
      return;
    }
    for (ChunkKey key : chunks.keySet()) {
      if (key.worldName().equals(worldName)) {
        clearChunk(key);
      }
    }
  }

  /** 清空全部标记（插件停用 / 热重载时调用，不留副作用）。 */
  void clear() {
    chunks.clear();
    playerTotals.clear();
  }

  /** 清理超过过期时间的标记（巡检任务周期调用）。 */
  void expire() {
    expire(clock.getAsLong());
  }

  /**
   * 清理超过过期时间的标记。
   *
   * @param nowNanos 当前时刻（纳秒），必须与构造本结构时的时钟同一来源
   */
  void expire(long nowNanos) {
    for (Map.Entry<ChunkKey, ConcurrentHashMap<UUID, Marker>> chunk : chunks.entrySet()) {
      ConcurrentHashMap<UUID, Marker> players = chunk.getValue();
      for (Map.Entry<UUID, Marker> entry : players.entrySet()) {
        Marker marker = entry.getValue();
        int removed;
        synchronized (marker) {
          if (nowNanos - marker.updatedAtNanos <= expireNanos) {
            continue;
          }
          removed = marker.size;
        }
        if (players.remove(entry.getKey(), marker)) {
          AtomicInteger total = playerTotals.get(entry.getKey());
          if (total != null) {
            subtract(total, removed);
          }
        }
      }
      if (players.isEmpty()) {
        chunks.remove(chunk.getKey(), players);
      }
    }
  }

  /** 当前「玩家 × 区块」标记条目数（诊断用）。 */
  public int markerCount() {
    int total = 0;
    for (ConcurrentHashMap<UUID, Marker> players : chunks.values()) {
      total += players.size();
    }
    return total;
  }

  /** 安全阀触发时被放弃标记的坐标数（诊断用；正常运营下应为 0，触发即说明有 bug）。 */
  public long droppedByCapacity() {
    return droppedByCapacity.sum();
  }

  private Marker markerOf(UUID playerId, ChunkKey key) {
    if (playerId == null) {
      return null;
    }
    ConcurrentHashMap<UUID, Marker> players = chunks.get(key);
    return players == null ? null : players.get(playerId);
  }

  /** 计数器只作安全阀与诊断用途：并发清理可能重叠，故夹在 0 以上。 */
  private static void subtract(AtomicInteger counter, int amount) {
    if (amount <= 0) {
      return;
    }
    counter.updateAndGet(current -> Math.max(0, current - amount));
  }

  /** x / z 各占 26 位、y 占 12 位：与旧索引同一编码，覆盖世界边界 ±3000 万。 */
  private static final int XZ_BITS = 26;
  private static final int XZ_MASK = (1 << XZ_BITS) - 1;
  private static final int Y_MASK = (1 << 12) - 1;

  private static long pack(int x, int y, int z) {
    return ((long) (x & XZ_MASK) << 38)
        | ((long) (y & Y_MASK) << 26)
        | (z & XZ_MASK);
  }
}