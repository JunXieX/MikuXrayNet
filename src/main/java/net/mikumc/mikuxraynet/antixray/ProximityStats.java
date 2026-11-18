package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 邻近显形统计计数，供 {@code /mikuxraynet status} 读取。
 *
 * <p>字段均为无锁 {@link LongAdder}：工作线程（写入索引）、主线程 / 区域线程（巡检发包）
 * 与封包线程（注销）都会累加；读取方（诊断面板）逐字段 {@code sum()} 直读，无需一致快照。
 */
public final class ProximityStats {

  /** 成功下发的显形包数。 */
  public final LongAdder revealsSent = new LongAdder();

  /** 被跳过的候选坐标数（区块未加载、读取失败、发包失败等）。 */
  public final LongAdder revealsSkipped = new LongAdder();

  /** 因服务端自行下发方块变更而从索引注销的坐标数。 */
  public final LongAdder unregistered = new LongAdder();

  /**
   * 因工作线程池队列无容量而<b>整轮跳过</b>的巡检次数（显形只延迟、不丢失）。
   *
   * <p>为什么不退化到调用线程同步算完：候选筛选（视锥 + 遮挡位图射线）是本功能最重的 CPU，
   * 本模块刻意把它搬到工作线程；队列满时若在玩家线程（Paper 主线程 / Folia 区域线程）照跑满额，
   * 恰恰会在服务器最忙（区块集中下发把改写队列塞满）的时刻制造主线程毛刺。宁可本轮不显形：
   * 坐标仍留在伪装索引里，下一巡检周期（分片轮转）会重新取到。
   */
  public final LongAdder passesSkippedNoCapacity = new LongAdder();

  /** 被视锥剔除的候选坐标数（不在玩家视野锥内）。 */
  public final LongAdder revealsFrustumCulled = new LongAdder();

  /** 因射线被遮挡而跳过显形的候选坐标数（等玩家靠近后再显形）。 */
  public final LongAdder revealsRayCulled = new LongAdder();

  /**
   * 上述「射线剔除」中，判定期间曾触碰过<b>无位图数据</b>的 section 的候选数（上界式归因）。
   *
   * <p><b>为什么必须单列</b>：位图判定的缺数据规则是「无数据 = 遮挡」（见 {@code BitmapVisibility}），
   * 它与「真的被墙挡住」在 {@link #revealsRayCulled} 里混在一起——只看总数无法判断严格规则是否在伤人。
   * 这个数持续偏高才说明「区块没建过表 / 位图 section 被降级」正在憋住本应显形的方块；
   * 若它 ≈ 0 而射线剔除很大，则剔除确实来自真实遮挡，无需处理。
   *
   * <p>属上界式归因：只统计「最终判不可见且期间触到缺数据」的候选，因此不会把纯真实遮挡混进来，
   * 但极少数「既触到缺数据、也确实被墙挡住」的候选会被多算一次。
   */
  public final LongAdder revealsRayCulledMissingData = new LongAdder();

  /**
   * 过度显形抽样中被复核的显形包数（按 1/N 抽样；默认 N=20，只计数不改行为）。
   *
   * <p><b>口径</b>：发包前若 {@link RevealedSet} 已含该坐标，说明客户端应已可见（或刚被其它路径显形过），
   * 这个显形包是浪费的。抽样计数让「过度显形」从定性描述变成可读数字：
   * {@code wasted / sampled} 即浪费占比的无偏估计（样本足够大时）。
   */
  public final LongAdder overRevealSampled = new LongAdder();

  /** 抽样复核中被判为「过度」（RevealedSet 已含该坐标）的显形包数。 */
  public final LongAdder overRevealWasted = new LongAdder();

  /**
   * 视野变化增量巡检（{@code proximity.view-change}）：执行次数。
   *
   * <p><b>为什么必须可观测</b>：这条路径由玩家移动/转向事件驱动，是唯一「频率取决于玩家行为」的显形路径；
   * 只看发包数无法知道它是否被高频触发（旋转/抖动会放大调用次数）。次数 + 耗时三者合起来才能判断
   * 「是否值得保留、预算是否该调」。
   */
  public final LongAdder viewChangeScans = new LongAdder();

  /** 视野变化增量巡检累计耗时（纳秒）；与 {@link #viewChangeScans} 相除即平均每次耗时。 */
  public final LongAdder viewChangeNanos = new LongAdder();

  /** 视野变化增量巡检单次最大耗时（纳秒，CAS 更新）；用于识别毛刺。 */
  public final AtomicLong viewChangeMaxNanos = new AtomicLong();

  /**
   * 视野变化增量巡检耗时的定长直方图（11 档，单位纳秒边界见 {@link #VIEW_CHANGE_BUCKET_UPPER_NANOS}）。
   *
   * <p><b>为什么需要它</b>：实机 dump 里「最大 63.7 ms」而预算是 0.3 ms——单次最大值天然被
   * GC / 线程调度抖动污染，用它判断「预算是否配小」会得出完全错误的结论。p99 才是判断
   * 「算法超支」的口径，而存全量样本会长期占用内存，故用定长直方图（11 个 long，共 88 字节）。
   */
  private final long[] viewChangeBuckets = new long[11];

  /** {@link #viewChangeBuckets} 各档的上界（纳秒）；最后一档为 {@link Long#MAX_VALUE}（无上界）。 */
  public static final long[] VIEW_CHANGE_BUCKET_UPPER_NANOS = {
      100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L,
      10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L, Long.MAX_VALUE
  };

  /** 记录一次视野增量巡检耗时到直方图（线程安全；与 {@link #viewChangeMaxNanos} 并列记录，互不替代）。 */
  public void recordViewChange(long nanos) {
    if (nanos < 0L) {
      return;
    }
    for (int i = 0; i < VIEW_CHANGE_BUCKET_UPPER_NANOS.length; i++) {
      if (nanos <= VIEW_CHANGE_BUCKET_UPPER_NANOS[i]) {
        synchronized (this.viewChangeBuckets) {
          this.viewChangeBuckets[i]++;
        }
        return;
      }
    }
  }

  /** 直方图快照（副本，供诊断渲染）。 */
  public long[] viewChangeBucketSnapshot() {
    synchronized (this.viewChangeBuckets) {
      return this.viewChangeBuckets.clone();
    }
  }

  /**
   * 按直方图求分位耗时（毫秒）；样本为空时返回 {@code -1}。
   *
   * <p>返回的是「命中档的上界」：{@code p=0.99} 表示 99% 的样本不超过该值。最后一档无上界时
   * 返回 {@link Double#POSITIVE_INFINITY}，渲染层据此打印「≥100 ms」，绝不伪造一个具体数字。
   */
  public static double percentileMillis(long[] buckets, double percentile) {
    if (buckets == null || buckets.length != VIEW_CHANGE_BUCKET_UPPER_NANOS.length
        || percentile <= 0.0D || percentile > 1.0D) {
      return -1.0D;
    }
    long total = 0L;
    for (long bucket : buckets) {
      if (bucket > 0L) {
        total += bucket;
      }
    }
    if (total == 0L) {
      return -1.0D;
    }
    long threshold = Math.max(1L, (long) Math.ceil(total * percentile));
    long cumulative = 0L;
    for (int i = 0; i < buckets.length; i++) {
      cumulative += Math.max(0L, buckets[i]);
      if (cumulative >= threshold) {
        long upper = VIEW_CHANGE_BUCKET_UPPER_NANOS[i];
        return upper == Long.MAX_VALUE ? Double.POSITIVE_INFINITY : upper / 1_000_000.0D;
      }
    }
    return Double.POSITIVE_INFINITY;
  }

  /** 视野变化增量巡检因时间预算耗尽而提前结束的次数（持续偏高说明预算配小或候选过多）。 */
  public final LongAdder viewChangeBudgetExhausted = new LongAdder();

  /**
   * 被「语义等价预筛」丢弃的候选数：六面全遮挡，任何射线判定下都必然不可见（见
   * {@code ProximityScanner.Prefilter}）。
   *
   * <p><b>为什么必须可观测</b>：它是「窗口名额有多少本来会浪费在全埋矿上」的唯一直接证据。
   * 该值 ≫ {@code 显形发送} 说明窗口确实长期被埋住的矿占满——这正是「看得见的矿排不进窗口、
   * 只有准星对准才还原」的成因；加预筛之后它仍会很大（预筛本身就在丢弃它们），但窗口覆盖半径
   * （{@link #windowFarthestBlocks()}）应随之明显变大。
   */
  public final LongAdder prefilterDropped = new LongAdder();
  public final LongAdder periodicVisited = new LongAdder();
  public final LongAdder periodicContinued = new LongAdder();
  public final LongAdder taskRestarts = new LongAdder();

  /**
   * 破坏方块后的邻域强制显形（{@code proximity.instant-reveal.break-sweep}）累计发包数。
   *
   * <p><b>为什么单独计</b>：这条路径与事件即时显形共用 {@code revealsSent}，但它的语义是「挖开一块，
   * 身边立刻都是真方块」。真机核对「挖矿不出错」是否成立时，只看它与破坏次数的比值就够。
   */
  public final LongAdder breakSweepSent = new LongAdder();

  /** 破坏邻域显形因每 tick 额度耗尽被截断的次数（持续偏高说明额度太小或范围工具在连破）。 */
  public final LongAdder breakSweepBudgetExhausted = new LongAdder();
  /** 等待破坏点达到每玩家上限时淘汰的条数。 */
  public final LongAdder breakSweepQueueDropped = new LongAdder();
  /** 因目标仍需同步而先纠正材质、暂停本次破坏的次数。 */
  public final LongAdder miningTargetCorrections = new LongAdder();
  /** 实时几何确认后优先还原的方块数。 */
  public final LongAdder miningLiveReveals = new LongAdder();

  /** 周期巡检窗口内最远候选的距离（格，单调取历史最大值；0 = 尚未取到候选）。 */
  private final AtomicLong windowFarthestBlocks = new AtomicLong();

  /** 记录一次窗口覆盖距离（入参是距离平方；诊断口径见 {@link #windowFarthestBlocks()}）。 */
  void recordWindowReach(long distanceSquared) {
    if (distanceSquared <= 0L) {
      return;
    }
    long blocks = (long) Math.sqrt((double) distanceSquared);
    long current = windowFarthestBlocks.get();
    while (blocks > current && !windowFarthestBlocks.compareAndSet(current, blocks)) {
      current = windowFarthestBlocks.get();
    }
  }

  /** 窗口内最远候选的距离（格）：调窗口大小 / 加预筛是否真的把覆盖推远，看这一个数就够。 */
  public long windowFarthestBlocks() {
    return windowFarthestBlocks.get();
  }

  /**
   * 生成中文可读快照（顺序稳定，便于命令输出）。
   *
   * <p>测试专用豁免：生产诊断直接逐字段读取（见 {@code Diagnostics}），
   * 本方法当前仅单测在用，保留以免破坏测试。
   */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("显形发送", revealsSent.sum());
    map.put("显形跳过", revealsSkipped.sum());
    map.put("变更注销", unregistered.sum());
    map.put("视锥剔除", revealsFrustumCulled.sum());
    map.put("射线剔除", revealsRayCulled.sum());
    map.put("射线剔除（缺数据）", revealsRayCulledMissingData.sum());
    map.put("过度显形（抽样）", overRevealWasted.sum());
    map.put("过度显形抽样数", overRevealSampled.sum());
    map.put("视野增量巡检次数", viewChangeScans.sum());
    map.put("视野增量巡检耗时(ms)", viewChangeNanos.sum() / 1_000_000L);
    return map;
  }
}