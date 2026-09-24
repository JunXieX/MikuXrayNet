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

  private final boolean enabled;
  private final Set<String> worlds;
  private final List<String> hideBlocks;
  private final Map<String, Integer> replacementWeights;
  private final boolean layerObfuscation;
  private final boolean removeBlockEntities;
  private final int cacheMaximumSize;
  private final int cacheExpireAfterAccessSeconds;
  private final int threads;
  private final int timeoutMillis;
  private final int queueCapacity;
  private final int configHash;

  private AntiXrayConfig(boolean enabled, Set<String> worlds, List<String> hideBlocks,
      Map<String, Integer> replacementWeights, boolean layerObfuscation, boolean removeBlockEntities,
      int cacheMaximumSize, int cacheExpireAfterAccessSeconds, int threads, int timeoutMillis,
      int queueCapacity) {
    this.enabled = enabled;
    this.worlds = Set.copyOf(worlds);
    this.hideBlocks = List.copyOf(hideBlocks);
    this.replacementWeights = Collections.unmodifiableMap(new LinkedHashMap<>(replacementWeights));
    this.layerObfuscation = layerObfuscation;
    this.removeBlockEntities = removeBlockEntities;
    this.cacheMaximumSize = Math.max(1, cacheMaximumSize);
    this.cacheExpireAfterAccessSeconds = Math.max(1, cacheExpireAfterAccessSeconds);
    this.threads = Math.max(0, threads);
    this.timeoutMillis = Math.max(100, timeoutMillis);
    this.queueCapacity = Math.max(1, queueCapacity);
    // 影响改写结果的全部配置项都参与哈希；顺序敏感，故用有序列表
    this.configHash = Objects.hash(this.hideBlocks, new ArrayList<>(replacementWeights.entrySet()),
        layerObfuscation);
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
        root.getInt("cache.maximum-size", 4096),
        root.getInt("cache.expire-after-access-seconds", 60),
        root.getInt("advanced.threads", 0),
        root.getInt("advanced.timeout-millis", 2500),
        root.getInt("advanced.queue-capacity", 2048));
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