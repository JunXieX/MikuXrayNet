package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 「自己刚发出的显形包」账本：1.1.6 起显形发包改用 Paper 原生
 * {@code Player#sendMultiBlockChange} / {@code Player#sendBlockChange}，这些包<b>同样会经过</b>
 * 本插件的出站观察链路（{@link BlockChangeRevealListener}），于是「我们自己发的回显」与
 * 「服务端真实的方块变更」在封包里长得一模一样。
 *
 * <p><b>为什么必须区分</b>：观察方对真实变更要做两件事——摘除伪装坐标（两者都该做）与
 * 推进磁盘缓存区块代次（<b>只有真实变更该做</b>）。显形回显并不改变服务端内容，若也推进代次，
 * 玩家身边的区块会被自己的显形反复作废（代次永不回到旧值，旧条目永不再命中）——
 * 磁盘缓存对「玩家活动范围内的区块」等于白写。
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li>写入发生在「显形包确实发出之后」（{@link ProximityRevealer} 写回已显形标记的同一处）；</li>
 *   <li>读取是<b>一次性</b>的（{@link #consume} 命中即移除）：同坐标随后的真实变更照常推进代次
 *       （已消费的条目不再命中），因此不改变「真实变更必须推进代次」；</li>
 *   <li>条目带 TTL：超期未被消费即视为过期，宁可多推进一次代次，也不放过真实变更；</li>
 *   <li>容量有界：超出上限按插入顺序淘汰最旧条目（最坏只是少抑制一次，不影响正确性）。</li>
 * </ul>
 *
 * <p><b>线程纪律</b>：写入在主线程 / Folia 区域线程，读取在封包解析线程；全部经同一把锁保护，
 * 临界区只有一次 map 操作，不读世界、不做 IO，可离线单测（时钟可注入）。
 */
final class RevealEchoLedger {

  /** 条目上限：实测显形速率约 700/秒、TTL 200 ms ⇒ 同窗口活跃条目约 150，4096 有充分余量。 */
  private static final int DEFAULT_MAX_ENTRIES = 4096;

  /** 条目存活时间：只覆盖「发包 → 异步观察」的排队延迟，不覆盖任何玩家操作时间尺度。 */
  private static final long DEFAULT_TTL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

  /** 账本键：显形包只发给单个玩家，故必须带上玩家，避免影响别人看到的同坐标真实变更。 */
  private record Echo(UUID playerId, String worldName, int x, int y, int z) {
  }

  private final int maxEntries;
  private final long ttlNanos;
  private final LongSupplier clock;
  private final Map<Echo, Long> pending;

  RevealEchoLedger() {
    this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_NANOS, System::nanoTime);
  }

  /** 测试用构造：可注入上限、TTL 与时钟。 */
  RevealEchoLedger(int maxEntries, long ttlNanos, LongSupplier clock) {
    this.maxEntries = Math.max(1, maxEntries);
    this.ttlNanos = Math.max(1L, ttlNanos);
    this.clock = clock;
    this.pending = new LinkedHashMap<>(64, 0.75f) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Echo, Long> eldest) {
        return size() > RevealEchoLedger.this.maxEntries;
      }
    };
  }

  /** 记下「刚给该玩家发出了该坐标的显形包」。 */
  void record(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      return;
    }
    synchronized (pending) {
      pending.put(new Echo(playerId, worldName, x, y, z), clock.getAsLong() + ttlNanos);
    }
  }

  /**
   * 消费一次「该玩家该坐标的显形回显」：命中且未过期返回 {@code true}（条目同时被移除）。
   *
   * @return true 表示这次观测到的方块变更就是本插件自己刚发的显形包
   */
  boolean consume(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      return false;
    }
    synchronized (pending) {
      Long expiresAt = pending.remove(new Echo(playerId, worldName, x, y, z));
      return expiresAt != null && clock.getAsLong() <= expiresAt;
    }
  }

  /** 当前在账条目数（诊断与单测用）。 */
  int size() {
    synchronized (pending) {
      return pending.size();
    }
  }

  /** 清空账本（显形停用 / 热重载时调用，不留副作用）。 */
  void clear() {
    synchronized (pending) {
      pending.clear();
    }
  }
}