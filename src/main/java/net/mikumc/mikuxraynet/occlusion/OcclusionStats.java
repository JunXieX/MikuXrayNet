package net.mikumc.mikuxraynet.occlusion;

import java.util.concurrent.atomic.LongAdder;

/**
 * 异步遮挡判定的计数容器（无锁 {@link LongAdder}，可被专用池工作线程与命令线程并发累加）。
 *
 * <p>刻意独立于 {@code bandwidth.ThrottleStats}：本模块（{@code occlusion} 包）要保持「无平台耦合、
 * 可离线单测」，而 ThrottleStats 属于带宽侧的装配面。诊断面板通过 {@code ThrottlePipeline}
 * 读取本实例（口径与 {@code RewriteStats}/{@code ProximityStats} 一致）。
 */
public final class OcclusionStats {

  /** 已提交的射线任务数（含之后被丢弃的）。 */
  public final LongAdder submitted = new LongAdder();
  /** 已完成的判定数（真正跑了射线并回投结果的）。 */
  public final LongAdder computed = new LongAdder();
  /** 因超过截止时间被丢弃的判定数（不改变实体当前显示状态；见引擎说明）。 */
  public final LongAdder timeoutDiscarded = new LongAdder();
  /** 因队列满被直接拒收（按「可见」处理，fail-open）的次数。 */
  public final LongAdder rejectedQueueFull = new LongAdder();
  /**
   * 射线路经的 section <b>查不到遮挡条目</b>的次数（= 遮挡数据缺失）。
   *
   * <p>「已知全不遮挡」（{@link EmptyOcclusion}）不计入——它是有数据且判定为不遮挡。
   * 该计数是覆盖率的核心观测点：持续偏高说明大量区块没被建过表。
   */
  public final LongAdder unknownSection = new LongAdder();
  /** 射线计算累计耗时（纳秒），用于与「时间预算」对账。 */
  public final LongAdder nanosTotal = new LongAdder();
  /**
   * 方块变更补丁：已按出站变更包改写进遮挡位图的<b>格数</b>。
   *
   * <p><b>为什么必须可观测</b>：位图只在「区块被解码 / 缓存命中恢复」时整列重建，而方块变更（挖掘、
   * 爆炸、插件编辑）不会触发重建——不补丁的话，被挖开的遮挡物会在位图里留下陈旧「遮挡」位，
   * 导致<b>本已可见的实体继续被隐藏</b>（安全方向错误的缺陷）。本计数持续为 0 而场上确有方块变更，
   * 即说明补丁没生效（例如服务端结构变更导致出站方块数据读不出来），需查日志。
   */
  public final LongAdder patched = new LongAdder();
  /**
   * 补丁失败（拿不到新方块口径）的<b>格数</b>：这些格已被置为「不遮挡」（清掉可能陈旧的「遮挡」位），
   * 且<b>不影响该段其余格</b>，因此不会出现陈旧「遮挡」位造成的误藏。
   *
   * <p><b>为什么不整段摘除</b>：摘除会一次丢掉 4096 格遮挡信息（实体侧「少藏」面被放大），
   * 并给邻近显形的位图判定（「缺数据 = 全遮挡」）制造一整段死区；单格置零在两个方向上都更保守。
   *
   * <p>持续偏高说明补丁链路拿不到方块数据（此时功能只是「少藏」，不会「误藏」），需查日志。
   */
  public final LongAdder patchFailures = new LongAdder();

  /** 中文单行摘要（供 {@code /mxnet status} 与周期摘要复用）。 */
  public String summaryLine() {
    return "遮挡判定：提交 " + submitted.sum() + "，完成 " + computed.sum()
        + "，超时丢弃 " + timeoutDiscarded.sum() + "，队列满拒收 " + rejectedQueueFull.sum()
        + "，缺遮挡数据 " + unknownSection.sum() + " 次"
        + "，方块补丁 " + patched.sum() + " 格（失败 " + patchFailures.sum() + " 格）"
        + "，射线耗时 " + (nanosTotal.sum() / 1_000_000L) + " ms";
  }
}
