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
 * 缺失项一律回落到内置默认值（性能优先并保障玩家体验），并对失控取值做安全钳制：超过「安全上限」取上限、
 * 低于「安全下限」取下限，使任何合法配置在极限处也不影响玩家体验。
 */
public final class BandwidthConfig {

  /**
   * 实体剔除「每实体候选顶点数」的上限：包围盒「朝向玩家一侧」的有效顶点最多就 7 个
   * （见 {@code EntityCuller#visibleVertices}，小包围盒退化为中心 1 个），配置超过它没有第 8 个点可试。
   */
  public static final int MAX_RAY_SAMPLES = 7;

  /*
   * 以下为该文件各项的「安全上限 / 下限」常量：只挡明显失控的配置（多打一个 0、手滑写错），使内存/带宽
   * 占用有一个可预期的上界。取值原则是「即使调到极限也不影响玩家体验」——对「越高越安全」的键把上限
   * 放宽（正常调优撞不到），对「越高越有害」的键把上限收到安全极限；默认值都落在 [下限, 上限] 内，
   * 因此这些边界不改变任何默认行为。被钳制时会记入 clampAdjustments 并由加载路径一次性 WARN
   *（绝不静默改用户配置）。
   */

  /** 合并半径上限（格）：范围越大越省包且不影响正确性，故放宽到 16。 */
  public static final int MAX_MERGE_RADIUS = 16;
  /**
   * 合并半径下限（格，1）。
   *
   * <p><b>为什么不许取 0</b>：合并半径 0 表示「只有坐标完全相同（曼哈顿距离 0）的变更才归入同一簇」，
   * 真实场景下几乎所有变更坐标互不相同，于是 {@code BlockChangeMerger} 仍会把原包<b>入缓冲并延迟一个
   * 时间窗</b>，却几乎永远拼不出可合并的多条簇——结果是「既省不到包、又给每个变更加了延迟与缓冲开销」
   * 的纯负收益。要彻底关掉合并请用 {@code block-changes.merge=false}（连缓冲都不建），而不是把半径配 0。
   * 因此这里设硬下限 1：低于 1 会被抬到 1 并记入明细（供加载路径一次性 WARN）。
   */
  public static final int MIN_MERGE_RADIUS = 1;
  /** 单个合并包条目上限：单包容量越大越省包，故放宽到 32768。 */
  public static final int MAX_PER_PACKET_LIMIT = 32768;
  /** 合并时间窗上限（毫秒）：窗口就是「玩家可感知的方块更新延迟上界」，200ms 以上观感明显迟滞。 */
  public static final int MAX_MERGE_WINDOW_MILLIS = 200;
  /** 每玩家待发缓冲条目上限：缓冲越大越省包（内存可控），故放宽到 8192。 */
  public static final int MAX_PENDING_ENTRIES_LIMIT = 8192;
  /** 立即放行半径上限（格）：半径内一律不合并，越大越不影响手感，故放宽到 64。 */
  public static final int MAX_IMMEDIATE_RADIUS = 64;
  /** 强制可见距离上限（格）：越大越多实体不被剔除、越安全，故放宽到 1024。 */
  public static final double MAX_FORCE_VISIBLE_DISTANCE = 1024.0D;
  /** 遮挡复检周期上限（tick）：40 tick（2 秒）再长会让「误藏」持续过久，可被玩家感知。 */
  public static final int MAX_UPDATE_INTERVAL_TICKS = 40;
  /** 每周期复检预算上限：预算即每周期主线程射线次数，越大收敛越快、越安全，故放宽到 2048。 */
  public static final int MAX_RECHECK_BUDGET = 2048;
  /** AFK 判定时长上限（秒，1 天）：超过一天的判定几乎不可能触发，只是白占状态。 */
  public static final int MAX_AFK_SECONDS = 86400;
  /** AFK 丢包距离上限（格）：越大越少误丢玩家看得见的远处包，故放宽到 1024。 */
  public static final double MAX_AFK_DISTANCE = 1024.0D;
  /** 延迟观察阈值上限（毫秒）：60 秒以上的延迟早已断线，阈值再高永不触发。 */
  public static final int MAX_LATENCY_THRESHOLD_MILLIS = 60000;
  /** 单次降视距上限（区块）：一次降 32 格会让弱网玩家几乎看不见，收到安全极限 4。 */
  public static final int MAX_REDUCE_VIEW_DISTANCE = 4;
  /** 超阈持续时长上限（秒，1 小时）：超过 1 小时才降视距等于永不触发。 */
  public static final int MAX_SUSTAIN_SECONDS = 3600;
  /** 视距下限上限（区块）：不应超过常见最大视距，取 16。 */
  public static final int MAX_MIN_VIEW_DISTANCE = 16;
  /**
   * 视距下限下限（区块，6）：本项是「视距最多降到多少」的兜底，配得太低等于体验崩塌，故设安全下限；
   * 低于 6 会被抬到 6 并记入明细（供加载路径一次性 WARN）。
   */
  public static final int MIN_VIEW_DISTANCE_FLOOR = 6;
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
   *                      复检的「可见追踪实体」条数上限（默认 12——性能优先档，兼顾收敛速度与每周期
   *                      主线程射线次数；建议区间 8~24）。
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
   * 被「安全上限 / 下限」钳制过的配置键明细（空列表 = 未钳制）；加载时由 {@link #warnIfClampApplied} 一次性 WARN。
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
   * <p><b>为什么这里同时有下限与上限</b>：硬下限（{@code Math.max} 与 {@link #clampLower}）防止 0/负数
   * 让功能静默失效、或把「视距下限」之类体验兜底项配到崩；上限（{@link #clampUpper} 系列）防止手滑多打
   * 一个 0 之类的失控配置把内存/带宽打满。边界取值都遵循「调到极限也不影响玩家体验」，默认值全部落在
   * 区间内，因此这些边界不改变默认行为；被钳制的键会记入明细、由 {@link #warnIfClampApplied} 提示。
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
            // 默认 4（性能优先）：合并更多远处变更，交互半径内不受影响（见 BlockChangeMerger）。
            // 下限 1：0 是纯负收益（仍入缓冲延迟却几乎拼不出可合并簇），要关合并请用 merge=false。
            clampUpper(clampLower(root.getInt("block-changes.merge-radius", 4),
                MIN_MERGE_RADIUS, "block-changes.merge-radius", clampAdjustments),
                MAX_MERGE_RADIUS, "block-changes.merge-radius", clampAdjustments),
            clampUpper(Math.max(1, root.getInt("block-changes.max-per-packet", 4096)),
                MAX_PER_PACKET_LIMIT, "block-changes.max-per-packet", clampAdjustments),
            root.getBoolean("block-changes.resend-on-overflow", true),
            // 默认 40（性能优先）：更长时间窗合并更多变更、更省包；玩家挖/放方块的实时手感由
            // immediate-radius（默认 8 格内原包立即放行）兜底，因此窗口略长也不会出现「顿感」。
            clampUpper(Math.max(1, root.getInt("block-changes.merge-window-millis", 40)),
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
            // 默认 32（性能优先，回落到设计兜底值）：剔除更多远处实体以省包；近身/视野中心的实体
            // 仍在强制可见范围内放行，玩家不易察觉剔除在发生。
            clampUpper(root.getDouble("entity-culling.force-visible-distance", 32.0D),
                32.0D, MAX_FORCE_VISIBLE_DISTANCE, "entity-culling.force-visible-distance", clampAdjustments),
            // 默认 10（性能优先）：复检更省 CPU；遮挡/可见切换的延迟由「已隐藏实体每周期全量复检」
            // 兜底，玩家几乎察觉不到。
            clampUpper(Math.max(1, root.getInt("entity-culling.update-interval-ticks", 10)),
                MAX_UPDATE_INTERVAL_TICKS, "entity-culling.update-interval-ticks", clampAdjustments),
            // 语义已变：原生射线改造后本键表示「每个实体最多尝试的候选顶点数」（不再表示采样数）。
            // 钳制 1..MAX_RAY_SAMPLES，默认即上限（包围盒至多 7 个可见顶点 → 取上限就是全部顶点都试）。
            Math.max(1, Math.min(MAX_RAY_SAMPLES, root.getInt("entity-culling.ray-samples", MAX_RAY_SAMPLES))),
            // 周期复检预算默认 12（性能优先）：约「每 0.5 秒（10 tick）多复检 12 个可见追踪实体」，
            // 兼顾收敛速度与每周期主线程射线次数；建议区间 8~24。
            clampUpper(Math.max(1, root.getInt("entity-culling.recheck-budget", 12)),
                MAX_RECHECK_BUDGET, "entity-culling.recheck-budget", clampAdjustments)),
        new Afk(
            root.getBoolean("afk.enabled", true),
            clampUpper(Math.max(1, root.getInt("afk.seconds", 300)),
                MAX_AFK_SECONDS, "afk.seconds", clampAdjustments),
            clampUpper(root.getDouble("afk.distance", 16.0D),
                16.0D, MAX_AFK_DISTANCE, "afk.distance", clampAdjustments),
            root.getBoolean("afk.drop-particles", true),
            // 默认 true（性能优先）：破坏动画包数量可观，丢弃可省带宽；玩家体验由「AFK 判定只看真实操作」
            // 兜底——只要玩家还在交互/移动/聊天就不会被判定为 AFK，因此真在操作的人看不到动画中断。
            root.getBoolean("afk.drop-block-break-animation", true)),
        new Latency(
            root.getBoolean("latency.enabled", true),
            // 默认 400（性能优先）：更早发现卡顿并介入；仅有明显持续卡顿才会触发（见 sustain-seconds）。
            clampUpper(Math.max(1, root.getInt("latency.threshold-millis", 400)),
                MAX_LATENCY_THRESHOLD_MILLIS, "latency.threshold-millis", clampAdjustments),
            // 默认 2（性能优先）：降幅略大更省带宽；由 min-view-distance 与持续时间门槛兜底体验。
            clampUpper(Math.max(1, root.getInt("latency.reduce-view-distance", 2)),
                MAX_REDUCE_VIEW_DISTANCE, "latency.reduce-view-distance", clampAdjustments),
            // 默认 30（性能优先）：仍需持续 30 秒才动作，足以排除偶发抖动。
            clampUpper(Math.max(0, root.getInt("latency.sustain-seconds", 30)),
                MAX_SUSTAIN_SECONDS, "latency.sustain-seconds", clampAdjustments),
            // 默认 6：体验兜底——视距下限低于 6 会让弱网玩家几乎看不见，故设安全下限 6（低于下限会被抬升并 WARN）。
            clampUpper(clampLower(root.getInt("latency.min-view-distance", 6),
                MIN_VIEW_DISTANCE_FLOOR, "latency.min-view-distance", clampAdjustments),
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

  /** 整数下限钳制：低于下限则取下限并记录明细（供一次性 WARN）。 */
  private static int clampLower(int value, int min, String key, List<String> adjustments) {
    if (value >= min) {
      return value;
    }
    adjustments.add(key + "=" + value + "（下限 " + min + "）");
    return min;
  }

  /**
   * 浮点上限钳制：非有限值（NaN / ±Inf）显式回落默认值，负数显式钳到 0 并留痕，其余先按 0 兜底再取上限。
   *
   * <p><b>为什么要拦非有限值</b>：NaN 不满足 {@code > max}，会原样穿过钳制进入配置指纹，
   * 使同一份「其实无效」的配置算出与众不同的指纹 —— 结果是<b>任何</b>进程算出的指纹都与它不等，
   * 磁盘缓存整体失效（缓存重启后命中率恒为 0）；而 {@code -Inf} 也会被 {@code Math.max} 悄悄抬成 0，
   * 让「配错了」看起来像「配成了 0」。两类都改为回落默认并记入明细，由加载路径一次性 WARN。
   *
   * <p><b>有限负值同样留痕</b>：旧实现对 {@code -5.0} 这类有限负值只做 {@code Math.max(0, v)} →
   * 静默归 0、明细里看不到任何记录，管理员会以为负数已生效。现在统一记入明细（低于下限取下限）。
   */
  private static double clampUpper(double value, double defaultValue, double max, String key,
      List<String> adjustments) {
    if (!Double.isFinite(value)) {
      adjustments.add(key + "=" + value + "（非有限值，回落默认 " + defaultValue + "）");
      return defaultValue;
    }
    if (value < 0.0D) {
      adjustments.add(key + "=" + value + "（低于下限 0，按 0 生效）");
      return 0.0D;
    }
    if (value > max) {
      adjustments.add(key + "=" + value + "（上限 " + max + "）");
      return max;
    }
    return value;
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
        + " 超出安全范围，已按安全值生效（超上限取上限、低于下限取下限、非有限值回落默认；默认值均在范围内）。"
        + "这是为了让「调到极限也不影响玩家体验」：防止失控配置把内存/带宽打满，也避免影响体验的取值被直接采用，"
        + "同时避免无效值污染配置指纹导致磁盘缓存整体失效。");
  }

  /** 被安全上限 / 下限钳制过的配置键明细（空列表 = 未钳制）；供诊断回显。 */
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