package net.mikumc.mikuxraynet.config;

import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 带宽优化配置（对应 {@code bandwidth.yml}）。
 *
 * <p>只承载「已解析的纯数据」，不含任何 Bukkit 世界引用，可安全地在工作线程读取。
 * 缺失项一律回落到内置默认值，并对明显不合理的取值（0、负数）做保守钳制。
 */
public final class BandwidthConfig {

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

    /** 旧三参构造（兼容既有调用）：widthBudget 默认开启（P0-1 新开关的默认值）。 */
    public Palette(boolean enabled, boolean reorder, boolean strictVerify) {
      this(enabled, reorder, strictVerify, true);
    }
  }

  /**
   * 实体射线剔除。
   *
   * @param enabled  模块总开关：为 {@code false} 时本模块完全不注册（零开销）。
   * @param raycast  行为开关：依据视线射线剔除被遮挡实体；仅在 {@code enabled=true} 时生效。
   * @param recheckBudget 周期复检预算：除「已隐藏实体每周期全部复检」外，每周期额外按轮转分片
   *                      复检的「可见追踪实体」条数上限（默认 12，建议 8~16）。
   *                      <p>为什么需要它：实体进入追踪范围那一刻（{@code PlayerTrackEntityEvent}）只评估一次，
   *                      若实体是「先可见、之后才被墙/地形挡住」，只复检已隐藏集合永远发现不了它。
   *                      轮转分片让复检在 {@code ceil(追踪数 / recheckBudget)} 个周期内覆盖全部追踪实体
   *                      （如 50 个实体、预算 10 → 5 个周期内全部复检一次），因此新出现的遮挡也能被隐藏。
   *                      <p>CPU 取舍：本项把「每周期射线判定」的调用数摊平为固定上限——越大越早发现新遮挡，
   *                      但每周期主线程射线次数同比上升；越小越省 CPU，但收敛更慢。
   * @param raySamples <b>每个实体最多尝试的候选顶点数</b>（射线改用 Paper 原生
   *                   {@code World#rayTraceBlocks} 后不再表示采样数）；钳制 1..8，默认 8
   *                   （包围盒至多 7 个可见顶点，故 8 即「全部顶点都试」）。
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

  private BandwidthConfig(boolean enabled, EntityPackets entityPackets, BlockChanges blockChanges, Palette palette,
      EntityCulling entityCulling, Afk afk, Latency latency, Diagnostics diagnostics) {
    this.enabled = enabled;
    this.entityPackets = entityPackets;
    this.blockChanges = blockChanges;
    this.palette = palette;
    this.entityCulling = entityCulling;
    this.afk = afk;
    this.latency = latency;
    this.diagnostics = diagnostics;
  }

  /** 从配置根节点解析；缺失项一律取默认值。 */
  public static BandwidthConfig from(ConfigurationSection root) {
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
            Math.max(0, root.getInt("block-changes.merge-radius", 2)),
            Math.max(1, root.getInt("block-changes.max-per-packet", 4096)),
            root.getBoolean("block-changes.resend-on-overflow", true),
            // 默认 20（原为 50）：50ms 的合并窗口会让玩家自己挖方块后的方块更新延迟最多 50ms，
            // 客户端预测受阻 → 出现「顿感」。收紧到 20 后改为 20ms；真正的交互延迟由
            // immediate-radius 消除（近身变更根本不进窗口）。
            Math.max(1, root.getInt("block-changes.merge-window-millis", 20)),
            Math.max(1, root.getInt("block-changes.max-pending-entries", 256)),
            Math.max(0, root.getInt("block-changes.immediate-radius", 8))),
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
            Math.max(0.0D, root.getDouble("entity-culling.force-visible-distance", 32.0D)),
            Math.max(1, root.getInt("entity-culling.update-interval-ticks", 10)),
            // 语义已变：原生射线改造后本键表示「每个实体最多尝试的候选顶点数」（不再表示采样数）。
            // 钳制 1..8，默认 8（包围盒至多 7 个可见顶点 → 8 即全部顶点都试）。
            Math.max(1, Math.min(8, root.getInt("entity-culling.ray-samples", 8))),
            // 周期复检预算默认 12：约「每 0.5 秒（10 tick）多复检 12 个可见追踪实体」，
            // 既能在数个周期内发现新遮挡，又不会让主线程每周期读方块次数失控（建议 8~16）。
            Math.max(1, root.getInt("entity-culling.recheck-budget", 12))),
        new Afk(
            root.getBoolean("afk.enabled", true),
            Math.max(1, root.getInt("afk.seconds", 300)),
            Math.max(0.0D, root.getDouble("afk.distance", 16.0D)),
            root.getBoolean("afk.drop-particles", true),
            root.getBoolean("afk.drop-block-break-animation", true)),
        new Latency(
            root.getBoolean("latency.enabled", true),
            Math.max(1, root.getInt("latency.threshold-millis", 400)),
            Math.max(1, root.getInt("latency.reduce-view-distance", 2)),
            Math.max(0, root.getInt("latency.sustain-seconds", 30)),
            Math.max(1, root.getInt("latency.min-view-distance", 4)),
            Math.max(1, root.getInt("latency.check-interval-seconds", 5))),
        new Diagnostics(
            Math.max(0, root.getInt("diagnostics.interval-seconds", 60))));
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