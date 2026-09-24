package net.mikumc.mikuxraynet.antixray;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 伪装区块索引（按区块共享）：{@code (世界名, chunkX, chunkZ) → 该区块被伪装过的坐标}。
 *
 * <p><b>为什么按区块共享</b>：同一区块对所有玩家的伪装结果相同（改写由「区块原始字节 + 配置」决定，
 * 且改写缓存本就按区块共享），因此<b>只存一份</b>。内存从原来的
 * {@code O(玩家数 × 各自看过的区块数)} 降到 {@code O(有伪装的已加载区块数)}——100 个玩家看同一张地图
 * 也只占 1 份。
 *
 * <p><b>失效时机</b>：① 世界卸载（{@link #invalidateWorld}）；② 区块卸载（{@link #invalidateChunk}，
 * 由 {@code ChunkUnloadEvent} 驱动，另有 {@link #expire} 过期兜底）；③ 该区块观测到方块变更
 * （{@link #removePosition}，出站方块变更封包路径，只摘除变更的那一个坐标，与既有行为一致）；
 * ④ 区块被重新下发（{@link #recordChunk} 直接覆盖该区块条目）。
 *
 * <p><b>内存纪律</b>：键只有世界名与两个整数，值是 {@code int[]}（改写结果的原始数组，直接共享不复制）；
 * 全程不持有 Player / World / Chunk / 封包 的强引用。
 *
 * <p><b>线程纪律</b>：写入来自反矿透工作线程；读取来自主线程 / Folia 区域线程；失效来自主线程 /
 * 区域线程与出站封包线程。内部只用并发容器与不可变条目（活跃时间用 volatile），不触碰任何 Bukkit API。
 */
public final class ObfuscatedChunkIndex {

  /** 世界内的绝对方块坐标。 */
  public record Position(int x, int y, int z) {
  }

  /** 单个区块的伪装坐标清单：坐标不可变，活跃时间可变（扫描时刷新，供过期兜底）。 */
  static final class ChunkEntry {

    private final int minHeight;
    /** 相对坐标，编码为 {@code 区块内相对Y << 8 | 区块内z << 4 | 区块内x}。 */
    private final int[] locals;
    private volatile long updatedAtNanos;

    ChunkEntry(int minHeight, int[] locals, long updatedAtNanos) {
      this.minHeight = minHeight;
      this.locals = locals;
      this.updatedAtNanos = updatedAtNanos;
    }

    int minHeight() {
      return minHeight;
    }

    int[] locals() {
      return locals;
    }

    int size() {
      return locals.length;
    }

    long updatedAtNanos() {
      return updatedAtNanos;
    }

    void touch(long nowNanos) {
      this.updatedAtNanos = nowNanos;
    }
  }

  private final ConcurrentHashMap<ChunkKey, ChunkEntry> chunks = new ConcurrentHashMap<>();
  private final AtomicInteger totalPositions = new AtomicInteger();
  /** 安全阀：仅当坐标总量越过上限（正常运营不该发生）时按「最旧优先」淘汰掉的坐标数。 */
  private final LongAdder evictedByCapacity = new LongAdder();

  private final int maxPositions;
  private final long expireNanos;
  private final LongSupplier clock;

  /**
   * @param maxPositions 全服伪装坐标总量上限（<b>安全阀</b>：正常运营下应为 0 触发，见类注释）
   * @param expireSeconds 条目过期秒数（区块卸载未触发时的兜底）
   */
  public ObfuscatedChunkIndex(int maxPositions, int expireSeconds) {
    this(maxPositions, TimeUnit.SECONDS.toNanos(Math.max(1L, expireSeconds)), System::nanoTime);
  }

  /** 测试用构造：可注入时钟与纳秒级过期时间。 */
  ObfuscatedChunkIndex(int maxPositions, long expireNanos, LongSupplier clock) {
    this.maxPositions = Math.max(1, maxPositions);
    this.expireNanos = Math.max(1L, expireNanos);
    this.clock = clock;
  }

  /**
   * 记录/覆盖一个区块的被伪装坐标（区块封包改写路径，工作线程调用）。
   *
   * <p>无论哪个玩家触发的改写，都只是覆盖这一条——同一区块只存一份，内存不随玩家数增长。
   * 传入数组是改写结果里的数组（此后不再被修改），直接共享、不复制。
   *
   * @param minHeight      该世界最低建筑高度
   * @param localPositions 区块内相对坐标，编码为 {@code 区块内相对Y << 8 | z << 4 | x}
   * @return 真正记录下来的坐标数（空清单返回 0，不建条目）
   */
  public int recordChunk(String worldName, int chunkX, int chunkZ, int minHeight,
      int[] localPositions) {
    if (worldName == null || localPositions == null || localPositions.length == 0) {
      return 0;
    }
    // 安全阀：正常运营下永不触发；触顶时按「最旧优先」淘汰其它区块来腾位置，仍放不下才放弃本次记录
    if (!makeRoomFor(localPositions.length)) {
      evictedByCapacity.add(localPositions.length);
      return 0;
    }

    ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
    ChunkEntry entry = new ChunkEntry(minHeight, localPositions, clock.getAsLong());
    ChunkEntry previous = chunks.put(key, entry);
    int delta = localPositions.length - (previous == null ? 0 : previous.size());
    totalPositions.updateAndGet(current -> Math.max(0, current + delta));
    return localPositions.length;
  }

  /** 取出区块条目，并刷新其活跃时间（供邻近显形扫描使用；未命中返回 {@code null}）。 */
  ChunkEntry entry(ChunkKey key) {
    ChunkEntry entry = chunks.get(key);
    if (entry != null) {
      entry.touch(clock.getAsLong());
    }
    return entry;
  }

  /**
   * 注销一个坐标（服务端自行下发了该坐标的方块变更时调用）。
   *
   * <p>只从「该坐标所属区块」的清单里摘除这一个坐标，其它坐标不受影响；清单被摘空时整条移除。
   *
   * @return 是否命中并注销
   */
  public boolean removePosition(String worldName, int x, int y, int z) {
    if (worldName == null) {
      return false;
    }
    ChunkKey key = ChunkKey.ofBlock(worldName, x, z);
    boolean[] hit = {false};
    chunks.computeIfPresent(key, (ignored, entry) -> {
      int local = ((y - entry.minHeight()) << 8) | ((z & 15) << 4) | (x & 15);
      int[] locals = entry.locals();
      int found = -1;
      for (int index = 0; index < locals.length; index++) {
        if (locals[index] == local) {
          found = index;
          break;
        }
      }
      if (found < 0) {
        return entry;
      }
      hit[0] = true;
      subtract(totalPositions, 1);
      if (locals.length == 1) {
        return null;
      }
      int[] reduced = new int[locals.length - 1];
      System.arraycopy(locals, 0, reduced, 0, found);
      System.arraycopy(locals, found + 1, reduced, found, locals.length - found - 1);
      return new ChunkEntry(entry.minHeight(), reduced, entry.updatedAtNanos());
    });
    return hit[0];
  }

  /**
   * 使一个区块的伪装清单失效（区块卸载时调用）。
   *
   * @return 是否命中并移除
   */
  public boolean invalidateChunk(String worldName, int chunkX, int chunkZ) {
    if (worldName == null) {
      return false;
    }
    ChunkEntry removed = chunks.remove(new ChunkKey(worldName, chunkX, chunkZ));
    if (removed == null) {
      return false;
    }
    subtract(totalPositions, removed.size());
    return true;
  }

  /** 使某个世界的全部条目失效（世界卸载时调用）。 */
  public void invalidateWorld(String worldName) {
    if (worldName == null) {
      return;
    }
    for (ChunkKey key : chunks.keySet()) {
      if (!key.worldName().equals(worldName)) {
        continue;
      }
      ChunkEntry removed = chunks.remove(key);
      if (removed != null) {
        subtract(totalPositions, removed.size());
      }
    }
  }

  /** 清理超过过期时间且近期未被扫描到的条目（巡检任务周期调用）。 */
  public void expire() {
    expire(clock.getAsLong());
  }

  /**
   * 清理超过过期时间的条目。
   *
   * @param nowNanos 当前时刻（纳秒），必须与构造索引时的时钟同一来源
   */
  public void expire(long nowNanos) {
    for (Map.Entry<ChunkKey, ChunkEntry> entry : chunks.entrySet()) {
      ChunkEntry value = entry.getValue();
      if (nowNanos - value.updatedAtNanos() <= expireNanos) {
        continue;
      }
      if (chunks.remove(entry.getKey(), value)) {
        subtract(totalPositions, value.size());
      }
    }
  }

  /** 清空全部条目（插件停用 / 热重载时调用，不留副作用）。 */
  public void clear() {
    chunks.clear();
    totalPositions.set(0);
  }

  /** 当前伪装区块数（诊断用）。 */
  public int chunkCount() {
    return chunks.size();
  }

  /** 当前累计伪装坐标数（诊断用）。 */
  public int positionCount() {
    return Math.max(0, totalPositions.get());
  }

  /** 安全阀触发时淘汰掉的坐标数（诊断用；正常运营下应为 0，触发即说明有 bug）。 */
  public long evictedByCapacity() {
    return evictedByCapacity.sum();
  }

  /**
   * 安全阀：坐标总量越界时按「最旧优先」淘汰其它区块，直到放得下本次记录。
   *
   * <p>只在越过上限时执行（正常运营永不进入），因此这里的 O(条目数) 扫描不在热路径上。
   *
   * @return 是否已为 {@code incoming} 个坐标腾出空间
   */
  private boolean makeRoomFor(int incoming) {
    if (totalPositions.get() + incoming <= maxPositions) {
      return true;
    }
    while (totalPositions.get() + incoming > maxPositions) {
      ChunkKey oldestKey = null;
      long oldest = Long.MAX_VALUE;
      for (Map.Entry<ChunkKey, ChunkEntry> entry : chunks.entrySet()) {
        if (entry.getValue().updatedAtNanos() < oldest) {
          oldest = entry.getValue().updatedAtNanos();
          oldestKey = entry.getKey();
        }
      }
      if (oldestKey == null) {
        break;
      }
      ChunkEntry removed = chunks.remove(oldestKey);
      if (removed != null) {
        subtract(totalPositions, removed.size());
        evictedByCapacity.add(removed.size());
      }
    }
    return totalPositions.get() + incoming <= maxPositions;
  }

  /** 计数器只作诊断用途：并发失效与整体清理可能重叠，故夹在 0 以上。 */
  private static void subtract(AtomicInteger counter, int amount) {
    if (amount <= 0) {
      return;
    }
    counter.updateAndGet(current -> Math.max(0, current - amount));
  }
}