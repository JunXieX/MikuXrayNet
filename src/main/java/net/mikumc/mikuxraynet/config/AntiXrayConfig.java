package net.mikumc.mikuxraynet.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 反矿透配置（对应 {@code antixray.yml}）。
 *
 * <p>本类只承载「已解析的纯数据」，不含任何 Bukkit 世界引用，可安全地在工作线程读取。
 * 方块名称到方块状态 id 的解析在 {@code registry.BlockStateRegistry} 中完成。
 *
 * <p>若配置项缺失（例如管理员删掉了整段），会回落到本类内置的默认值，避免出现
 * 「配置损坏 → 什么都不隐藏」的静默失效。
 */
public final class AntiXrayConfig {

  /** 默认的矿透目标方块。 */
  private static final List<String> DEFAULT_HIDE_BLOCKS = List.of(
      "coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore",
      "copper_ore", "deepslate_copper_ore", "gold_ore", "deepslate_gold_ore",
      "redstone_ore", "deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore",
      "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore",
      "nether_gold_ore", "nether_quartz_ore", "ancient_debris");

  private static final Map<String, Integer> DEFAULT_REPLACEMENT_WEIGHTS;

  static {
    Map<String, Integer> weights = new LinkedHashMap<>();
    weights.put("stone", 10);
    weights.put("deepslate", 8);
    weights.put("netherrack", 6);
    DEFAULT_REPLACEMENT_WEIGHTS = Collections.unmodifiableMap(weights);
  }

  /** 邻块数据缺失（未加载、跨区域、已清理）时的降级策略。 */
  public enum MissingPolicy {
    /** 视为遮挡：宁可多伪装，也不留下沿区块边界的泄漏（默认）。 */
    HIDE,
    /** 视为暴露：保持「边界那一圈不伪装」的旧行为（存在可利用的透视口子）。 */
    EXPOSE
  }

  /** 邻区块贴边快照。 */
  public record Neighbors(boolean enabled, MissingPolicy missingPolicy, int cacheMaximumSize) {
  }

  /**
   * 邻近显形：玩家靠近曾被伪装的坐标时，主动把该坐标的真实方块发回客户端。
   *
   * @param distance             触发显形的距离（格，三维欧氏距离，等于阈值也算命中）
   * @param intervalTicks        巡检周期（tick）
   * @param maxRevealsPerTick    单次巡检的发包上限（普通服务端为全服合计，Folia 为每玩家）
   * @param expireSeconds        显形索引条目的过期秒数
   * @param maxPositions         显形索引的全服坐标上限
   * @param maxPositionsPerPlayer 单个玩家的坐标上限
   */
  public record Proximity(boolean enabled, double distance, int intervalTicks, int maxRevealsPerTick,
      int expireSeconds, int maxPositions, int maxPositionsPerPlayer) {
  }

  private final boolean enabled;
  private final Set<String> worlds;
  private final List<String> hideBlocks;
  private final Map<String, Integer> replacementWeights;
  private final boolean layerObfuscation;
  private final boolean removeBlockEntities;
  private final Neighbors neighbors;
  private final Proximity proximity;
  private final int cacheMaximumSize;
  private final int cacheExpireAfterAccessSeconds;
  private final int threads;
  private final int timeoutMillis;
  private final int queueCapacity;
  private final int configHash;

  private AntiXrayConfig(boolean enabled, Set<String> worlds, List<String> hideBlocks,
      Map<String, Integer> replacementWeights, boolean layerObfuscation, boolean removeBlockEntities,
      Neighbors neighbors, Proximity proximity, int cacheMaximumSize, int cacheExpireAfterAccessSeconds,
      int threads, int timeoutMillis, int queueCapacity) {
    this.enabled = enabled;
    this.worlds = Set.copyOf(worlds);
    this.hideBlocks = List.copyOf(hideBlocks);
    this.replacementWeights = Collections.unmodifiableMap(new LinkedHashMap<>(replacementWeights));
    this.layerObfuscation = layerObfuscation;
    this.removeBlockEntities = removeBlockEntities;
    this.neighbors = neighbors;
    this.proximity = proximity;
    this.cacheMaximumSize = Math.max(1, cacheMaximumSize);
    this.cacheExpireAfterAccessSeconds = Math.max(1, cacheExpireAfterAccessSeconds);
    this.threads = Math.max(0, threads);
    this.timeoutMillis = Math.max(100, timeoutMillis);
    this.queueCapacity = Math.max(1, queueCapacity);
    // 影响改写结果的全部配置项都参与哈希；顺序敏感，故用有序列表
    this.configHash = Objects.hash(this.hideBlocks, new ArrayList<>(replacementWeights.entrySet()),
        layerObfuscation, neighbors.enabled(), neighbors.missingPolicy());
  }

  /** 从配置根节点解析。 */
  public static AntiXrayConfig from(ConfigurationSection root) {
    List<String> hideBlocks = root.getStringList("obfuscation.hide-blocks");
    if (hideBlocks.isEmpty()) {
      hideBlocks = DEFAULT_HIDE_BLOCKS;
    }

    Map<String, Integer> weights = new LinkedHashMap<>();
    ConfigurationSection weightSection = root.getConfigurationSection("obfuscation.replacement-weights");
    if (weightSection != null) {
      for (String key : weightSection.getKeys(false)) {
        int weight = weightSection.getInt(key, 0);
        if (weight > 0) {
          weights.put(key.toLowerCase(Locale.ROOT), weight);
        }
      }
    }
    if (weights.isEmpty()) {
      weights.putAll(DEFAULT_REPLACEMENT_WEIGHTS);
    }

    return new AntiXrayConfig(
        root.getBoolean("enabled", true),
        Set.copyOf(root.getStringList("worlds")),
        hideBlocks,
        weights,
        root.getBoolean("obfuscation.layer-obfuscation", false),
        root.getBoolean("obfuscation.remove-block-entities", true),
        new Neighbors(
            root.getBoolean("neighbors.enabled", true),
            missingPolicy(root.getString("neighbors.missing-policy", "hide")),
            root.getInt("neighbors.cache-maximum-size", 512)),
        new Proximity(
            root.getBoolean("proximity.enabled", true),
            Math.max(0.0D, root.getDouble("proximity.distance", 12.0D)),
            Math.max(1, root.getInt("proximity.interval-ticks", 5)),
            Math.max(1, root.getInt("proximity.max-reveals-per-tick", 32)),
            Math.max(1, root.getInt("proximity.expire-seconds", 120)),
            Math.max(1, root.getInt("proximity.max-positions", 65536)),
            Math.max(1, root.getInt("proximity.max-positions-per-player", 2048))),
        root.getInt("cache.maximum-size", 4096),
        root.getInt("cache.expire-after-access-seconds", 60),
        root.getInt("advanced.threads", 0),
        root.getInt("advanced.timeout-millis", 2500),
        root.getInt("advanced.queue-capacity", 2048));
  }

  /** 解析缺失策略；取值非法时回落到最安全的 {@link MissingPolicy#HIDE}。 */
  private static MissingPolicy missingPolicy(String value) {
    if (value == null) {
      return MissingPolicy.HIDE;
    }
    return "expose".equals(value.trim().toLowerCase(Locale.ROOT)) ? MissingPolicy.EXPOSE
        : MissingPolicy.HIDE;
  }

  public boolean enabled() {
    return enabled;
  }

  /** 世界是否在生效范围内；世界列表为空表示全部世界生效。 */
  public boolean appliesTo(String worldName) {
    return enabled && (worlds.isEmpty() || worlds.contains(worldName));
  }

  public List<String> hideBlocks() {
    return hideBlocks;
  }

  public Map<String, Integer> replacementWeights() {
    return replacementWeights;
  }

  public boolean layerObfuscation() {
    return layerObfuscation;
  }

  public boolean removeBlockEntities() {
    return removeBlockEntities;
  }

  /** 邻区块贴边快照相关配置。 */
  public Neighbors neighbors() {
    return neighbors;
  }

  /** 邻近显形相关配置。 */
  public Proximity proximity() {
    return proximity;
  }

  public int cacheMaximumSize() {
    return cacheMaximumSize;
  }

  public int cacheExpireAfterAccessSeconds() {
    return cacheExpireAfterAccessSeconds;
  }

  public int threads() {
    return threads;
  }

  public int timeoutMillis() {
    return timeoutMillis;
  }

  public int queueCapacity() {
    return queueCapacity;
  }

  /** 配置指纹：任一影响改写结果的配置项变化都会使缓存整体失效。 */
  public int configHash() {
    return configHash;
  }
}