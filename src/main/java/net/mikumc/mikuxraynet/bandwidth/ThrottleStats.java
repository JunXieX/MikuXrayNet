package net.mikumc.mikuxraynet.bandwidth;

import java.util.concurrent.atomic.LongAdder;

/**
 * 带宽模块统计计数，供 {@code /mikuxraynet status} 读取。
 *
 * <p>全部字段为无锁 {@link LongAdder}：网络线程、工作线程、主线程都可以安全累加，
 * 读取方（诊断聚合）直接按需取 {@code sum()}。
 */
public final class ThrottleStats {

  /** 零位移实体包：取消数 / 放行数。 */
  public final LongAdder entityPacketsCancelled = new LongAdder();
  public final LongAdder entityPacketsPassed = new LongAdder();

  /** 方块变更：合并批次 / 被合并进合并包的原包数 / 原样放行数。 */
  public final LongAdder blockMergeBatches = new LongAdder();
  public final LongAdder blockChangesMerged = new LongAdder();
  public final LongAdder blockChangesPassed = new LongAdder();

  /** 实体剔除：隐藏次数 / 恢复次数。 */
  public final LongAdder entitiesHidden = new LongAdder();
  public final LongAdder entitiesShown = new LongAdder();

  /**
   * 实体剔除「周期复检」口径：复检提交数 / 复检致新隐藏数 / 复检致恢复数。
   *
   * <p><b>为什么单列</b>：{@code entitiesHidden} 混合了「入场即被遮挡」与「周期复检发现新遮挡」两条来源，
   * 只看总数无法判断「先可见后被遮挡」这类实体是否真的被收敛到隐藏。本组计数正是本次缺陷的可观测指标：
   * 复检提交数持续增长（轮转在推进）、复检致隐藏数随之 +1，即证明轮转分片生效。
   */
  public final LongAdder recheckSubmitted = new LongAdder();
  public final LongAdder recheckHidden = new LongAdder();
  public final LongAdder recheckShown = new LongAdder();

  /** AFK 降级：进入 AFK 次数 / 丢弃的低价值包数。 */
  public final LongAdder afkEntered = new LongAdder();
  public final LongAdder afkPacketsDropped = new LongAdder();

  /** 高延迟降视距：降视距次数 / 还原次数。 */
  public final LongAdder viewDistanceReduced = new LongAdder();
  public final LongAdder viewDistanceRestored = new LongAdder();
}