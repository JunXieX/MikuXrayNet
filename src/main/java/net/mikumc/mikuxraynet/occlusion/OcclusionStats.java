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
   * 单条判定在工作线程内抛异常被就地吞掉的次数（不改变实体显示状态，见 {@code OcclusionEngine}）。
   *
   * <p><b>为什么必须可见</b>：该计数持续非 0 说明射线判定链路存在会抛异常的输入（例如坐标越界被
   * {@code SectionKey.packSection} 拒绝）；它本身不改变显示状态（方向安全），但若放任异常逃逸出工作
   * 循环会直接杀死工作线程、令队列永久无人消费，故就地捕获并计数，使这类输入可被观测定位。
   */
  public final LongAdder computeFailures = new LongAdder();
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
  /**
   * 方块变更补丁中「拿不到逐状态全局 id、回落 Material 级近似」的<b>格数</b>。
   *
   * <p><b>为什么单列</b>：补丁现在优先取封包内状态的全局 id 查实体口径表（与遮挡位图逐状态一致）；
   * 只有在 id 解析不可用（如 NMS 桥接初始化失败）时才回落到旧的 Material 级近似——那种近似无法区分
   * 「同方块不同状态」（单/双台阶等），是<b>已知残余差异</b>。该计数持续非 0 就说明仍走在近似路径上，
   * 必须可见而不能静默（否则「补丁为什么和位图不一致」无从判断）。
   */
  public final LongAdder stateIdFallbacks = new LongAdder();
}
