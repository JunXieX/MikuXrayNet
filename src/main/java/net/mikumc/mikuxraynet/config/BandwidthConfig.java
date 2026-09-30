package net.mikumc.mikuxraynet.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 带宽优化配置（对应 {@code bandwidth.yml}）。
 *
 * <p>只承载「已解析的纯数据」，不含任何 Bukkit 世界引用，可安全地在工作线程读取。
 * 缺失项一律回落到内置默认值，并对明显不合理的取值（0、负数）做保守钳制。
 */
public final class BandwidthConfig {

  /**
   * 实体剔除「每实体候选顶点数」的上限：包围盒「朝向玩家一侧」的有效顶点最多就 7 个
   * （见 {@code EntityCuller#visibleVertices}，小包围盒退化为中心 1 个），配置超过它没有第 8 个点可试。
   */
  public static final int MAX_RAY_SAMPLES = 7;

  /*
   * 以下为该文件各项的「安全上限」常量：只挡明显失控的配置（多打一个 0、手滑写错），使内存/带宽占用
   * 有一个可预期的上界。默认值都落在 [下限, 上限] 内，因此新增上限不改变任何默认行为。
   * 被上限钳制时会记入 clampAdjustments 并由加载路径一次性 WARN（绝不静默改用户配置）。
   */

  /** 合并半径上限（格）：半径越大越会把「互不相关的变更」粘进同一个合并包，超过此值收益趋零。 */
  public static final int MAX_MERGE_RADIUS = 8;
  /** 单个合并包条目上限：条目再多只会放大单个包的体积与丢包重传代价。 */
  public static final int MAX_PER_PACKET_LIMIT = 16384;
  /** 合并时间窗上限（毫秒）：窗口就是「玩家能感知的方块更新延迟上界」，超过 1 秒观感明显迟滞。 */
  public static final int MAX_MERGE_WINDOW_MILLIS = 1000;
  /** 每玩家待发缓冲条目上限：条目持有方块状态引用，过大只放大单玩家内存占用。 */
  public static final int MAX_PENDING_ENTRIES_LIMIT = 4096;
  /** 立即放行半径上限（格）：半径内一律不合并，配得过大几乎等于关闭合并。 */
  public static final int MAX_IMMEDIATE_RADIUS = 32;
  /** 强制可见距离上限（格）：超过一个视距（约 512 格）等价于关闭实体剔除。 */
  public static final double MAX_FORCE_VISIBLE_DISTANCE = 512.0D;
  /** 遮挡复检周期上限（tick）：比 10 秒还长会让被遮挡的实体迟迟不隐藏。 */
  public static final int MAX_UPDATE_INTERVAL_TICKS = 200;
  /** 每周期复检预算上限：预算即每周期主线程射线次数，上限用于防止把主线程打满。 */
  public static final int MAX_RECHECK_BUDGET = 1024;
  /** AFK 判定时长上限（秒，1 天）：超过一天的判定几乎不可能触发，只是白占状态。 */
  public static final int MAX_AFK_SECONDS = 86400;
  /** AFK 丢包距离上限（格）：超过视距意味着「不论多远都丢」，会误丢玩家可能看到的远处粒子。 */
  public static final double MAX_AFK_DISTANCE = 512.0D;
  /** 延迟观察阈值上限（毫秒）：60 秒以上的延迟早已断线，阈值再高永不触发。 */
  public static final int MAX_LATENCY_THRESHOLD_MILLIS = 60000;
  /** 单次降视距上限（区块）：降幅超过常见最大视距（32）没有意义。 */
  public static final int MAX_REDUCE_VIEW_DISTANCE = 32;
  /** 超阈持续时长上限（秒，1 小时）：超过 1 小时才降视距等于永不触发。 */
  public static final int MAX_SUSTAIN_SECONDS = 3600;
  /** 视距下限上限（区块）：下限不应超过常见最大视距。 */
  public static final int MAX_MIN_VIEW_DISTANCE = 32;
  /** 延迟采样周期上限（秒，1 小时）：周期过长等于停止巡检。 */
  public static final int MAX_CHECK_INTERVAL_SECONDS = 3600;
  /** 诊断摘要周期上限（秒，1 天）：周期过长等于不再输出摘要。 */
  public static final int MAX_DIAGNOSTICS_INTERVAL_SECONDS = 86400;

  /**
   * 零位移实体包抑制。
   *
   * @param enabled           模块总开关：为 {@code false} 时本模块完全不注册（零开销）。
   * @param skipZeroMovement  行为开关：取消「位移与转向增量全为 0」的实体位置包；仅在 {@code enabled=true} 时生效。
   */
  public record EntityPackets(boolean enabled, boolean skipZeroMovement, Set<String> whitelist) {
  }

  /**
   * 方块变更合并。
   *
   * @param enabled         模块总开关：为 {@code false} 时本模块完全不注册（零开销）。
   * @param merge           行为开关：合并邻域方块变更；仅在 {@code enabled=true} 时生效。
   * @param immediateRadius 玩家周围该半径（格，欧氏距离）内的方块变更<b>立即放行、不进合并窗口</b>；
   *                        0 表示全部走合并（等价于旧行为）。默认 8——依据是玩家自己挖方块时，
   *                        目标方块距玩家自身仅 1~2 格，若被合并窗口延迟，客户端预测得不到确认会出现「顿感」。
   *                        半径内的变更多为交互/近身方块（挖掘、放置、脚下更新），实时性远比省包重要。
   */
  public record BlockChanges(boolean enabled, boolean merge, int mergeRadius, int maxPerPacket,
      boolean resendOnOverflow, int mergeWindowMillis, int maxPendingEntries, int immediateRadius) {
  }

  /**
   * 调色板重排。
   *
   * @param enabled      模块总开关：为 {@code false} 时本模块完全不生效（反矿透编码链路取 {@code DISABLED}）。
   * @param reorder      行为开关，默认 {@code false}：CI 实测表明开启重排使压缩字节普遍变大——
   *                     zlib level 6（网络封包口径）下全实心 +2.9%、稀疏矿脉 +4.9%~+6.4%、
   *                     乱序调色板 +1.4%~+2.3%；ZSTD level 3（磁盘缓存口径）下全实心 +8.3%、
   *                     稀疏矿脉 +1.6%~+11.0%、乱序调色板 +6.4%~+8.4%。仅洞穴与主世界地下略优（约 -0.3%~-3.6%），
   *                     而耗时普遍增至约 4~6 倍。故默认关闭；{@code strictVerify} 亦仅在 {@code reorder=true} 时有意义。
   * @param strictVerify 重排自检（仅在 {@code reorder=true} 时有意义）。
   * @param widthBudget  位宽预算封顶（P0-1，默认 {@code true}）：改写前把「可能引入的新状态数」与当前位宽
   *                     容量对账，优先选用已在调色板内的伪装方块避免升位；改写后裁剪引用计数为 0 的
   *                     失效条目并在可能时降位宽（位宽单调不增）。修复「4 位 section 被替换成下界岩后
   *                     升到 5 位、包体膨胀约 25%」的问题，兼得降位宽收益。关闭时走原路径（只升不降）。
   */
  public record Palette(boolean enabled, boolean reorder, boolean strictVerify, boolean widthBudget) {
  }

  /**
   * 实体射线剔除。
   *
   * @param enabled  模块总开关：为 {@code false} 时本模块完全不注册（零开销）。
   * @param raycast  行为开关：依据视线射线剔除被遮挡实体；仅在 {@code enabled=true} 时生效。
   * @param recheckBudget 周期复检预算：除「已隐藏实体每周期全部复检」外，每周期额外按轮转分片
   *                      复检的「可见追踪实体」条数上限（默认 24——保守档，取值偏高以尽快收敛；
   *                      旧建议区间 8~16、旧默认 12）。
   *                      <p>为什么需要它：实体进入追踪范围那一刻（{@code PlayerTrackEntityEvent}）只评估一次，
   *                      若实体是「先可见、之后才被墙/地形挡住」，只复检已隐藏集合永远发现不了它。
   *                      轮转分片让复检在 {@code ceil(追踪数 / recheckBudget)} 个周期内覆盖全部追踪实体
   *                      （如 50 个实体、预算 10 → 5 个周期内全部复检一次），因此新出现的遮挡也能被隐藏。
   *                      <p>CPU 取舍：本项把「每周期射线判定」的调用数摊平为固定上限——越大越早发现新遮挡，
   *                      但每周期主线程射线次数同比上升；越小越省 CPU，但收敛更慢。
   * @param raySamples <b>每个实体最多尝试的候选顶点数</b>（射线改用 Paper 原生
   *                   {@code World#rayTraceBlocks} 后不再表示采样数）；钳制 1..{@value #MAX_RAY_SAMPLES}，
   *                   默认 {@value #MAX_RAY_SAMPLES}——包围盒「朝向玩家一侧」的可见顶点最多
   *                   {@value #MAX_RAY_SAMPLES} 个，取到上限即「全部顶点都试」。
   */
  public record EntityCulling(boolean enabled, boolean raycast, double forceVisibleDistance,
      int updateIntervalTicks, int raySamples, int recheckBudget) {
  }

  /** AFK 降级。{@code enabled} 为模块总开关，关闭时本模块完全不注册（零开销）。 */
  public record Afk(boolean enabled, int seconds, double distance, boolean dropParticles,
      boolean dropBlockBreakAnimation) {
  }

  /** 高延迟降视距。{@code enabled} 为模块总开关，关闭时本模块完全不注册（零开销）。 */
  public record Latency(boolean enabled, int thresholdMillis, int reduceViewDistance, int sustainSeconds,
      int minViewDistance, int checkIntervalSeconds) {
  }

  /** 诊断输出。 */
  public record Diagnostics(int intervalSeconds) {
  }

  private final boolean enabled;
  private final EntityPackets entityPackets;
  private final BlockChanges blockChanges;
  private final Palette palette;
  private final EntityCulling entityCulling;
  private final Afk afk;
  private final Latency latency;
  private final Diagnostics diagnostics;
  /**
   * 被「安全上限」钳制过的配置键明细（空列表 = 未钳制）；加载时由 {@link #warnIfClampApplied} 一次性 WARN。
   */
  private final List<String> clampAdjustments;

  private BandwidthConfig(boolean enabled, EntityPackets entityPackets, BlockChanges blockChanges, Palette palette,
      EntityCulling entityCulling, Afk afk, Latency latency, Diagnostics diagnostics,
      List<String> clampAdjustments) {
    this.enabled = enabled;
    this.entityPackets = entityPackets;
    this.blockChanges = blockChanges;
    this.palette = palette;
    this.entityCulling = entityCulling;
    this.afk = afk;
    this.latency = latency;
    this.diagnostics = diagnostics;
    this.clampAdjustments = clampAdjustments == null ? List.of() : List.copyOf(clampAdjustments);
  }

  /**
   * 从配置根节点解析；缺失项一律取默认值。
   *
   * <p><b>为什么这里同时有下限与上限</b>：下限（{@code Math.max}）防止 0/负数让功能静默失效；
   * 上限（{@link #clampUpper} 系列）防止手滑多打一个 0 之类的失控配置把内存/带宽打满。默认值都落在
   * 区间内，因此新增上限不改变默认行为；被上限钳制的键会记入明细、由 {@link #warnIfClampApplied} 提示。
   */
  public static BandwidthConfig from(ConfigurationSection root) {
    List<String> clampAdjustments = new ArrayList<>();
    return new BandwidthConfig(
        root.getBoolean("enabled", true),
        new EntityPackets(
            // 各模块总开关均默认 true：新增开关不得改变既有行为（原样保持「全部启用」）
            root.getBoolean("entity-packets.enabled", true),
            root.getBoolean("entity-packets.skip-zero-movement", true),
            Set.copyOf(root.getStringList("entity-packets.whitelist"))),
        new BlockChanges(
            root.getBoolean("block-changes.enabled", true),
            root.getBoolean("block-changes.merge", true),
            clampUpper(Math.max(0, root.getInt("block-changes.merge-radius", 2)),
                MAX_MERGE_RADIUS, "block-changes.merge-radius", clampAdjustments),
            clampUpper(Math.max(1, root.getInt("block-changes.max-per-packet", 4096)),
                MAX_PER_PACKET_LIMIT, "block-changes.max-per-packet", clampAdjustments),
            root.getBoolean("block-changes.resend-on-overflow", true),
            // 默认 20（原为 50）：50ms 的合并窗口会让玩家自己挖方块后的方块更新延迟最多 50ms，
            // 客户端预测受阻 → 出现「顿感」。收紧到 20 后改为 20ms；真正的交互延迟由
            // immediate-radius 消除（近身变更根本不进窗口）。
            clampUpper(Math.max(1, root.getInt("block-changes.merge-window-millis", 20)),
                MAX_MERGE_WINDOW_MILLIS, "block-changes.merge-window-millis", clampAdjustments),
            clampUpper(Math.max(1, root.getInt("block-changes.max-pending-entries", 256)),
                MAX_PENDING_ENTRIES_LIMIT, "block-changes.max-pending-entries", clampAdjustments),
            clampUpper(Math.max(0, root.getInt("block-changes.immediate-radius", 8)),
                MAX_IMMEDIATE_RADIUS, "block-changes.immediate-radius", clampAdjustments)),
        new Palette(
            root.getBoolean("palette.enabled", true),
            // 默认 false：实测开启重排会使压缩字节变大且耗时增加（见 Palette 的说明）
            root.getBoolean("palette.reorder", false),
            root.getBoolean("palette.strict-verify", false),
            // P0-1 位宽预算封顶，默认 true：修复「调色板写满后替换升位 → 包体膨胀」并拿降位宽收益
            root.getBoolean("palette.width-budget", true)),
        new EntityCulling(
            root.getBoolean("entity-culling.enabled", true),
            root.getBoolean("entity-culling.raycast", true),
            // 默认 64（保守档，原 32）：覆盖绝大多数近身/视野中心实体，基本不会误藏玩家觉得该看见的实体。
            clampUpper(root.getDouble("entity-culling.force-visible-distance", 64.0D),
                64.0D, MAX_FORCE_VISIBLE_DISTANCE, "entity-culling.force-visible-distance", clampAdjustments),
            // 默认 5（保守档，原 10）：复检更勤，遮挡/可见切换更及时，玩家几乎察觉不到剔除存在。
            clampUpper(Math.max(1, root.getInt("entity-culling.update-interval-ticks", 5)),
                MAX_UPDATE_INTERVAL_TICKS, "entity-culling.update-interval-ticks", clampAdjustments),
            // 语义已变：原生射线改造后本键表示「每个实体最多尝试的候选顶点数」（不再表示采样数）。
            // 钳制 1..MAX_RAY_SAMPLES，默认即上限（包围盒至多 7 个可见顶点 → 取上限就是全部顶点都试）。
            Math.max(1, Math.min(MAX_RAY_SAMPLES, root.getInt("entity-culling.ray-samples", MAX_RAY_SAMPLES))),
            // 周期复检预算默认 24（保守档，原 12）：约「每 0.25 秒（5 tick）多复检 24 个可见追踪实体」，
            // 收敛更快、更不容易出现「明明被挡住却还在显示」；旧建议区间 8~16。
            clampUpper(Math.max(1, root.getInt("entity-culling.recheck-budget", 24)),
                MAX_RECHECK_BUDGET, "entity-culling.recheck-budget", clampAdjustments)),
        new Afk(
            root.getBoolean("afk.enabled", true),
            clampUpper(Math.max(1, root.getInt("afk.seconds", 300)),
                MAX_AFK_SECONDS, "afk.seconds", clampAdjustments),
            clampUpper(root.getDouble("afk.distance", 16.0D),
                16.0D, MAX_AFK_DISTANCE, "afk.distance", clampAdjustments),
            root.getBoolean("afk.drop-particles", true),
            // 默认 false（保守档，原 true）：破坏动画与「正在发生的事」直接相关，丢掉会让 AFK 玩家
            // 回头时看到动作中断，故默认不丢；更省带宽可改回 true。
            root.getBoolean("afk.drop-block-break-animation", false)),
        new Latency(
            root.getBoolean("latency.enabled", true),
            // 默认 1000（保守档，原 400）：只有明显卡顿才进入观察，正常网络绝不触发降视距。
            clampUpper(Math.max(1, root.getInt("latency.threshold-millis", 1000)),
                MAX_LATENCY_THRESHOLD_MILLIS, "latency.threshold-millis", clampAdjustments),
            // 默认 1（保守档，原 2）：降幅最小，玩家几乎察觉不到画面变化。
            clampUpper(Math.max(1, root.getInt("latency.reduce-view-distance", 1)),
                MAX_REDUCE_VIEW_DISTANCE, "latency.reduce-view-distance", clampAdjustments),
            // 默认 60（保守档，原 30）：要求持续一分钟才动作，几乎排除偶发抖动。
            clampUpper(Math.max(0, root.getInt("latency.sustain-seconds", 60)),
                MAX_SUSTAIN_SECONDS, "latency.sustain-seconds", clampAdjustments),
            // 默认 6（保守档，原 4）：保底视距更高，即使触发降视距也不影响正常观察。
            clampUpper(Math.max(1, root.getInt("latency.min-view-distance", 6)),
                MAX_MIN_VIEW_DISTANCE, "latency.min-view-distance", clampAdjustments),
            clampUpper(Math.max(1, root.getInt("latency.check-interval-seconds", 5)),
                MAX_CHECK_INTERVAL_SECONDS, "latency.check-interval-seconds", clampAdjustments)),
        new Diagnostics(
            clampUpper(Math.max(0, root.getInt("diagnostics.interval-seconds", 60)),
                MAX_DIAGNOSTICS_INTERVAL_SECONDS, "diagnostics.interval-seconds", clampAdjustments)),
        clampAdjustments);
  }

  /** 整数上限钳制：超过上限则取上限并记录明细（供一次性 WARN）。 */
  private static int clampUpper(int value, int max, String key, List<String> adjustments) {
    if (value <= max) {
      return value;
    }
    adjustments.add(key + "=" + value + "（上限 " + max + "）");
    return max;
  }

  /**
   * 浮点上限钳制：非有限值（NaN / ±Inf）显式回落默认值，其余先按 0 兜底再取上限。
   *
   * <p><b>为什么要拦非有限值</b>：NaN 不满足 {@code > max}，会原样穿过钳制进入配置指纹，
   * 使同一份「其实无效」的配置算出与众不同的指纹 —— 结果是<b>任何</b>进程算出的指纹都与它不等，
   * 磁盘缓存整体失效（缓存重启后命中率恒为 0）；而 {@code -Inf} 也会被 {@code Math.max} 悄悄抬成 0，
   * 让「配错了」看起来像「配成了 0」。两类都改为回落默认并记入明细，由加载路径一次性 WARN。
   */
  private static double clampUpper(double value, double defaultValue, double max, String key,
      List<String> adjustments) {
    if (!Double.isFinite(value)) {
      adjustments.add(key + "=" + value + "（非有限值，回落默认 " + defaultValue + "）");
      return defaultValue;
    }
    double floored = Math.max(0.0D, value);
    if (floored > max) {
      adjustments.add(key + "=" + value + "（上限 " + max + "）");
      return max;
    }
    return floored;
  }

  /**
   * 被安全上限钳制或非有限值回落默认时的一次性中文 WARN。
   *
   * <p>钳制不改变默认行为（默认值都在区间内），只在管理员写了失控值时触发；用进程级一次性闸门避免
   * 反复 reload 重复刷屏。两条明细都写清了「原值 → 生效值」与原因。
   */
  public void warnIfClampApplied(Logger logger, AtomicBoolean once) {
    if (clampAdjustments.isEmpty() || logger == null || !once.compareAndSet(false, true)) {
      return;
    }
    logger.warning("bandwidth.yml 的 " + String.join("、", clampAdjustments)
        + " 超出安全范围，已按安全值生效（超上限取上限、非有限值回落默认；默认值均在范围内）。"
        + "这是为了防止失控配置把内存/带宽打满，也避免无效值污染配置指纹导致磁盘缓存整体失效。");
  }

  /** 被安全上限钳制过的配置键明细（空列表 = 未钳制）；供诊断回显。 */
  public List<String> clampAdjustments() {
    return clampAdjustments;
  }

  /**
   * 配置指纹（跨进程稳定）：参与哈希的全部字段都是「有规范 hashCode 定义」的类型（字符串/数值/布尔/
   * 集合），不含枚举 identity hash，因此同一份 bandwidth.yml 在任何 JVM 上得到同一值。
   *
   * <p>热重载时用它判断「带宽侧配置是否变化」（见 {@code ReloadCoordinator}）。
   */
  public int configHash() {
    return Objects.hash(enabled, entityPackets, blockChanges, palette, entityCulling, afk, latency,
        diagnostics);
  }

  public boolean enabled() {
    return enabled;
  }

  public EntityPackets entityPackets() {
    return entityPackets;
  }

  public BlockChanges blockChanges() {
    return blockChanges;
  }

  public Palette palette() {
    return palette;
  }

  public EntityCulling entityCulling() {
    return entityCulling;
  }

  public Afk afk() {
    return afk;
  }

  public Latency latency() {
    return latency;
  }

  public Diagnostics diagnostics() {
    return diagnostics;
  }
}