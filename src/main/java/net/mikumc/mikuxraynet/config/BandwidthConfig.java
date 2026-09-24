package net.mikumc.mikuxraynet.config;

import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 带宽优化配置（对应 {@code bandwidth.yml}）。
 *
 * <p>本里程碑只做「解析与校验」：字段解析完整、默认值齐备，但带宽模块尚未接入生效逻辑，
 * 因此这里保持无副作用、无状态，方便后续在 M5 直接消费。
 */
public final class BandwidthConfig {

  /** 零位移实体包抑制。 */
  public record EntityPackets(boolean skipZeroMovement, Set<String> whitelist) {
  }

  /** 方块变更合并。 */
  public record BlockChanges(boolean merge, int mergeRadius, int maxPerPacket, boolean resendOnOverflow) {
  }

  /** 调色板重排。 */
  public record Palette(boolean reorder, boolean strictVerify) {
  }

  /** 实体射线剔除。 */
  public record EntityCulling(boolean raycast, double forceVisibleDistance, int threads) {
  }

  /** AFK 降级。 */
  public record Afk(int seconds, double distance) {
  }

  /** 高延迟降视距。 */
  public record Latency(int thresholdMillis, int reduceViewDistance) {
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
        root.getBoolean("enabled", false),
        new EntityPackets(
            root.getBoolean("entity-packets.skip-zero-movement", true),
            Set.copyOf(root.getStringList("entity-packets.whitelist"))),
        new BlockChanges(
            root.getBoolean("block-changes.merge", true),
            root.getInt("block-changes.merge-radius", 2),
            root.getInt("block-changes.max-per-packet", 4096),
            root.getBoolean("block-changes.resend-on-overflow", true)),
        new Palette(
            root.getBoolean("palette.reorder", true),
            root.getBoolean("palette.strict-verify", false)),
        new EntityCulling(
            root.getBoolean("entity-culling.raycast", true),
            root.getDouble("entity-culling.force-visible-distance", 32.0),
            root.getInt("entity-culling.threads", 0)),
        new Afk(
            root.getInt("afk.seconds", 300),
            root.getDouble("afk.distance", 16.0)),
        new Latency(
            root.getInt("latency.threshold-millis", 400),
            root.getInt("latency.reduce-view-distance", 2)),
        new Diagnostics(
            root.getInt("diagnostics.interval-seconds", 60)));
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