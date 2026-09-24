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

  /** 零位移实体包抑制。 */
  public record EntityPackets(boolean skipZeroMovement, Set<String> whitelist) {
  }

  /**
   * 方块变更合并。
   *
   * @param immediateRadius 玩家周围该半径（格，欧氏距离）内的方块变更<b>立即放行、不进合并窗口</b>；
   *                        0 表示全部走合并（等价于旧行为）。默认 8——依据是玩家自己挖方块时，
   *                        目标方块距玩家自身仅 1~2 格，若被合并窗口延迟，客户端预测得不到确认会出现「顿感」。
   *                        半径内的变更多为交互/近身方块（挖掘、放置、脚下更新），实时性远比省包重要。
   */
  public record BlockChanges(boolean merge, int mergeRadius, int maxPerPacket, boolean resendOnOverflow,
      int mergeWindowMillis, int maxPendingEntries, int immediateRadius) {
  }

  /**
   * 调色板重排。
   *
   * <p>{@code reorder} 默认 {@code false}：CI 实测（zlib level 6 压缩后字节）表明开启重排使压缩字节
   * 普遍变大（全实心 +2.9%、稀疏矿脉 +4.9%~+6.4%、乱序调色板 +1.4%~+2.3%，仅洞穴略优），且耗时明显增加。
   * {@code strictVerify} 仅在 {@code reorder=true} 时有意义。
   */
  public record Palette(boolean reorder, boolean strictVerify) {
  }

  /** 实体射线剔除。 */
  public record EntityCulling(boolean raycast, double forceVisibleDistance, int threads,
      int updateIntervalTicks, int raySamples) {
  }

  /** AFK 降级。 */
  public record Afk(int seconds, double distance, boolean dropParticles, boolean dropBlockBreakAnimation) {
  }

  /** 高延迟降视距。 */
  public record Latency(int thresholdMillis, int reduceViewDistance, int sustainSeconds,
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
            root.getBoolean("entity-packets.skip-zero-movement", true),
            Set.copyOf(root.getStringList("entity-packets.whitelist"))),
        new BlockChanges(
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
            // 默认 false：实测开启重排会使压缩字节变大且耗时增加（见 Palette 的说明）
            root.getBoolean("palette.reorder", false),
            root.getBoolean("palette.strict-verify", false)),
        new EntityCulling(
            root.getBoolean("entity-culling.raycast", true),
            Math.max(0.0D, root.getDouble("entity-culling.force-visible-distance", 32.0D)),
            Math.max(0, root.getInt("entity-culling.threads", 0)),
            Math.max(1, root.getInt("entity-culling.update-interval-ticks", 10)),
            Math.max(2, root.getInt("entity-culling.ray-samples", 24))),
        new Afk(
            Math.max(1, root.getInt("afk.seconds", 300)),
            Math.max(0.0D, root.getDouble("afk.distance", 16.0D)),
            root.getBoolean("afk.drop-particles", true),
            root.getBoolean("afk.drop-block-break-animation", true)),
        new Latency(
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