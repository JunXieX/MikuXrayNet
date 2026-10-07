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

  /** 方块变更：合并批次 / 被合并掉的「原始封包个数」/ 原样放行数。
   * 注意 {@link #blockChangesMerged} 计的是<b>原包数</b>（每条 add 传入的是被合并的原包个数），
   * 不是「合并后发出的包数」（那才是 {@link #blockMergeBatches}），面板文案须写「个原包」。 */
  public final LongAdder blockMergeBatches = new LongAdder();
  public final LongAdder blockChangesMerged = new LongAdder();
  public final LongAdder blockChangesPassed = new LongAdder();

  /**
   * 方块变更合并的<b>冲刷诊断</b>：冲刷次数与累计冲刷耗时（纳秒）。
   *
   * <p><b>为什么单列</b>：合并冲刷跑在单线程 {@code MikuXrayNet-BlockMerge} 上，极端配置（大时间窗 +
   * 大邻域 + 大方块量）下「同一玩家窗口到期」的构造/发送会成为延迟瓶颈。本组计数把这条单线程路径的
   * 实际耗时暴露出来（累计耗时 / 冲刷次数 = 平均一次冲刷的成本），无需引入任何复杂并发。
   * 这是<b>纯观测</b>值，不影响任何行为。
   */
  public final LongAdder blockMergeFlushes = new LongAdder();
  public final LongAdder blockMergeFlushNanos = new LongAdder();

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

  /**
   * 实体剔除·视锥子项口径：因「视野锥外 + 超出距离门」被隐藏数 / 转头后经复检恢复数。
   *
   * <p><b>为什么单列</b>：视锥剔除与射线剔除的失效模式完全不同——射线剔除出错是「该看见的被藏」，
   * 视锥剔除出错是「转头后实体迟一步出现」。分列后管理员可以据此判断该不该调大
   * {@code entity-culling.frustum.min-distance} 或关闭该子项。
   */
  public final LongAdder frustumHidden = new LongAdder();
  public final LongAdder frustumShown = new LongAdder();

  /** AFK 降级：进入 AFK 次数 / 丢弃的低价值包数。 */
  public final LongAdder afkEntered = new LongAdder();
  public final LongAdder afkPacketsDropped = new LongAdder();

  /** 高延迟降视距：降视距次数 / 还原次数。 */
  public final LongAdder viewDistanceReduced = new LongAdder();
  public final LongAdder viewDistanceRestored = new LongAdder();
}