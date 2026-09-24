package net.mikumc.mikuxraynet.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.mikumc.mikuxraynet.registry.OcclusionRules;
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

  /**
   * 伪装范围模式（{@code obfuscation.mode}）。
   *
   * <p><b>enclosed</b>：只伪装「6 面全被遮挡」的方块（掩埋矿）。矿洞壁上的裸露矿保持原样，
   * 因此透视端能直接看到裸露矿；好处是玩家几乎不会看到「假方块」。
   *
   * <p><b>all</b>（默认）：所有目标矿一律伪装（不看 6 面遮挡），靠邻近显形在玩家靠近且可见时还原。
   * 透视端看不到任何真实矿物（含矿洞壁上的裸露矿）；代价是矿洞壁上的矿会暂时显示为伪装方块，
   * 玩家靠近可见后才变回真实矿物——若显形不及时，可能挖到「看起来是石头、其实是矿」的方块，
   * 这是该模式的固有代价。CPU 略降（省去逐方块的 6 面遮挡判定）。
   */
  public enum ObfuscationMode {
    /** 只伪装被完全掩埋的矿（旧行为）。 */
    ENCLOSED,
    /** 所有目标矿一律伪装，靠邻近显形还原（默认，最防透视）。 */
    ALL
  }

  /** 邻区块贴边快照。 */
  public record Neighbors(boolean enabled, MissingPolicy missingPolicy, int cacheMaximumSize) {
  }

  /**
   * 遮挡判定的用户覆盖表（用户自行纠正判定的出口）。
   *
   * <p>{@code extraNonOccluding} 优先级最高（显式声明不遮挡），{@code extraOccluding} 次之
   * （可覆盖内置的空气/形状/材质/薄片规则）。名称用方块注册名（无需 {@code minecraft:} 前缀）。
   *
   * <p>该表影响启动期构建的遮挡位图，因此<b>只在启动时生效</b>（修改后需重启服务端）；
   * 但它参与配置指纹，重启后磁盘缓存也会随之失效，不会复用按旧判定写出的负载。
   */
  public record Occlusion(Set<String> extraOccluding, Set<String> extraNonOccluding) {
  }

  /**
   * 邻近显形：玩家靠近曾被伪装的坐标时，主动把该坐标的真实方块发回客户端。
   *
   * @param distance              触发显形的距离（格，三维欧氏距离，等于阈值也算命中）
   * @param intervalTicks         巡检周期（tick）
   * @param maxRevealsPerTick     单次巡检的发包上限（普通服务端为全服合计，Folia 为每玩家）
   * @param expireSeconds         显形索引条目的过期秒数
   * @param maxPositions          显形索引的全服坐标上限
   * @param maxPositionsPerPlayer 单个玩家的坐标上限
   * @param frustumEnabled        是否只显形玩家视野锥内的候选坐标
   * @param frustumFov            视野锥全张开角（度）
   * @param frustumMinDistance    该距离内的候选豁免视锥判定
   * @param raycastEnabled        是否做射线可见性判定（被墙挡住的不显形；<b>默认开启</b>）
   * @param raycastSamples        每条射线的最大采样体素数
   */
  public record Proximity(boolean enabled, double distance, int intervalTicks, int maxRevealsPerTick,
      int expireSeconds, int maxPositions, int maxPositionsPerPlayer,
      boolean frustumEnabled, double frustumFov, double frustumMinDistance,
      boolean raycastEnabled, int raycastSamples) {
  }

  /**
   * 磁盘缓存：把「已改写完成的区块负载」持久化，重启后直接复用，省掉重复 CPU 开销。
   *
   * @param enabled                    总开关（默认开启，限额保守）
   * @param maxEntries                 近似条目总数上限（超出后拒绝新写入）
   * @param maxFileSizeMb              单个区域文件（32×32 区块）的大小上限（MB）
   * @param expireSeconds              条目过期秒数（读取与后台维护都会惰性清理）
   * @param bucketCacheSize            每个打开的区域文件在内存里缓存的 bucket 数（内存上限的关键）
   * @param idleCloseSeconds           句柄闲置多久后自动落盘并关闭（释放文件描述符与内存）
   * @param maintenanceIntervalSeconds 后台维护周期（落盘 + 压缩回收 + 关闭闲置句柄）
   * @param compactPerPass             每轮维护最多压缩回收的区域文件数
   * @param queueCapacity              磁盘线程待处理任务上限（超出即丢弃，保网络路径）
   * @param generationTrackerSize      区块代次跟踪表的条目上限（有界 LRU）
   */
  public record DiskCache(boolean enabled, int maxEntries, int maxFileSizeMb, int expireSeconds,
      int bucketCacheSize, int idleCloseSeconds, int maintenanceIntervalSeconds, int compactPerPass,
      int queueCapacity, int generationTrackerSize) {
  }

  private final boolean enabled;
  private final Set<String> worlds;
  private final List<String> hideBlocks;
  private final Map<String, Integer> replacementWeights;
  private final boolean layerObfuscation;
  private final ObfuscationMode obfuscationMode;
  private final boolean removeBlockEntities;
  private final Neighbors neighbors;
  private final Occlusion occlusion;
  private final Proximity proximity;
  private final DiskCache diskCache;
  private final int cacheMaximumSize;
  private final int cacheExpireAfterAccessSeconds;
  private final int threads;
  private final int timeoutMillis;
  private final int queueCapacity;
  private final int configHash;

  private AntiXrayConfig(boolean enabled, Set<String> worlds, List<String> hideBlocks,
      Map<String, Integer> replacementWeights, boolean layerObfuscation, ObfuscationMode obfuscationMode,
      boolean removeBlockEntities, Neighbors neighbors, Occlusion occlusion, Proximity proximity,
      DiskCache diskCache, int cacheMaximumSize, int cacheExpireAfterAccessSeconds, int threads,
      int timeoutMillis, int queueCapacity) {
    this.enabled = enabled;
    this.worlds = Set.copyOf(worlds);
    this.hideBlocks = List.copyOf(hideBlocks);
    this.replacementWeights = Collections.unmodifiableMap(new LinkedHashMap<>(replacementWeights));
    this.layerObfuscation = layerObfuscation;
    this.obfuscationMode = obfuscationMode;
    this.removeBlockEntities = removeBlockEntities;
    this.neighbors = neighbors;
    this.occlusion = occlusion;
    this.proximity = proximity;
    this.diskCache = diskCache;
    this.cacheMaximumSize = Math.max(1, cacheMaximumSize);
    this.cacheExpireAfterAccessSeconds = Math.max(1, cacheExpireAfterAccessSeconds);
    this.threads = Math.max(0, threads);
    this.timeoutMillis = Math.max(100, timeoutMillis);
    this.queueCapacity = Math.max(1, queueCapacity);
    // 影响改写结果的全部配置项都参与哈希（含遮挡覆盖表与伪装模式）；顺序敏感，故用有序列表。
    // 伪装模式必须参与：否则「enclosed 写出的缓存」会在切换到 all 后被复用，导致裸露矿泄漏。
    this.configHash = Objects.hash(this.hideBlocks, new ArrayList<>(replacementWeights.entrySet()),
        layerObfuscation, obfuscationMode, neighbors.enabled(), neighbors.missingPolicy(),
        OcclusionRules.sortedList(this.occlusion.extraOccluding()),
        OcclusionRules.sortedList(this.occlusion.extraNonOccluding()));
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
        // 默认 all：用户明确要求「透视不能直接看到裸露在矿洞中的矿物」，故默认对所有目标矿一律伪装。
        obfuscationMode(root.getString("obfuscation.mode", "all")),
        root.getBoolean("obfuscation.remove-block-entities", true),
        new Neighbors(
            root.getBoolean("neighbors.enabled", true),
            missingPolicy(root.getString("neighbors.missing-policy", "hide")),
            root.getInt("neighbors.cache-maximum-size", 512)),
        new Occlusion(
            OcclusionRules.normalizeAll(root.getStringList("occlusion.extra-occluding")),
            OcclusionRules.normalizeAll(root.getStringList("occlusion.extra-non-occluding"))),
        new Proximity(
            root.getBoolean("proximity.enabled", true),
            // 默认 32（原为 12）：真机反馈「12 格太短，很影响游戏体验」。因为显形只在射线通畅（视线真能看到）
            // 时才还原，所以放大距离不会隔着墙泄露，只是把「本来就看得到的矿」更早、更远地还给玩家。
            // 取舍：距离越大越及时、观感越接近原版，但候选越多、发包量与「每 tick 上限」越相关。
            Math.max(0.0D, root.getDouble("proximity.distance", 32.0D)),
            Math.max(1, root.getInt("proximity.interval-ticks", 5)),
            // 默认 256（原为 128）：配合扩大到 32 格的距离，候选数量随之上升，单次额度也要相应放大，
            // 否则脚边的矿会被远处候选挤到后面。5 tick 一次、256 个 ≈ 1024 个/秒，仍远低于区块包流量。
            Math.max(1, root.getInt("proximity.max-reveals-per-tick", 256)),
            // 默认 300（原为 120）：登录/传送后区块一次性连续下发，玩家往往过一会儿才走到近处，
            // 窗口太短会让「还没走到就被清掉」的坐标永不还原。
            Math.max(1, root.getInt("proximity.expire-seconds", 300)),
            // 默认 2097152（原为 512000）：单玩家上限（262144）的 8 倍量级，多玩家同时在线不立刻触顶。
            // 内存量级见 max-positions-per-player 注释：本项只是「上限」，不是预分配。
            Math.max(1, root.getInt("proximity.max-positions", 2097152)),
            // 默认 262144（原为 65536）：真机日志显示视距 10 的登录规模已达 7.7 万坐标，
            // 65536 仍会在登录过程中被填满，玩家身边的坐标进不了索引。262144 约为 7.7 万的 3.4 倍，
            // 留出「视距 12+ / 矿更密集」的余量。内存量级：坐标压实为 1 个 long（8 字节）、
            // 数组按 2 倍扩容最坏约 16 字节/坐标 → 262144 × 16B ≈ 4 MB/玩家（上限，非预分配）。
            Math.max(1, root.getInt("proximity.max-positions-per-player", 262144)),
            root.getBoolean("proximity.frustum.enabled", true),
            clampFov(root.getDouble("proximity.frustum.fov", 80.0D)),
            Math.max(0.0D, root.getDouble("proximity.frustum.min-distance", 4.0D)),
            // 默认 true（原为 false）：真机 dump 显示为 false，导致「隔着墙也把矿物亮给透视客户端」。
            // 改为 true 后只在射线无遮挡时才发真实方块，代价是每个候选多几次主线程读方块。
            root.getBoolean("proximity.raycast.enabled", true),
            Math.max(2, root.getInt("proximity.raycast.samples", 16))),
        new DiskCache(
            root.getBoolean("disk-cache.enabled", true),
            Math.max(1, root.getInt("disk-cache.max-entries", 20000)),
            Math.max(1, root.getInt("disk-cache.max-file-size-mb", 16)),
            Math.max(1, root.getInt("disk-cache.expire-seconds", 1800)),
            Math.max(1, root.getInt("disk-cache.bucket-cache-size", 2)),
            Math.max(1, root.getInt("disk-cache.idle-close-seconds", 300)),
            Math.max(1, root.getInt("disk-cache.maintenance-interval-seconds", 30)),
            Math.max(1, root.getInt("disk-cache.compact-per-pass", 4)),
            Math.max(1, root.getInt("disk-cache.queue-capacity", 256)),
            Math.max(1, root.getInt("disk-cache.generation-tracker-size", 32768))),
        root.getInt("cache.maximum-size", 4096),
        root.getInt("cache.expire-after-access-seconds", 60),
        root.getInt("advanced.threads", 0),
        root.getInt("advanced.timeout-millis", 2500),
        root.getInt("advanced.queue-capacity", 2048));
  }

  /** 视野角钳制到合法区间；非法值回落到默认 80°。 */
  private static double clampFov(double value) {
    if (value <= 0.0D || value > 360.0D) {
      return 80.0D;
    }
    return value;
  }

  /** 解析缺失策略；取值非法时回落到最安全的 {@link MissingPolicy#HIDE}。 */
  private static MissingPolicy missingPolicy(String value) {
    if (value == null) {
      return MissingPolicy.HIDE;
    }
    return "expose".equals(value.trim().toLowerCase(Locale.ROOT)) ? MissingPolicy.EXPOSE
        : MissingPolicy.HIDE;
  }

  /** 解析伪装模式；只有显式填写 {@code enclosed} 才回到旧行为，其它（含非法值、null）一律取更安全的 {@link ObfuscationMode#ALL}。 */
  private static ObfuscationMode obfuscationMode(String value) {
    if (value != null && "enclosed".equals(value.trim().toLowerCase(Locale.ROOT))) {
      return ObfuscationMode.ENCLOSED;
    }
    return ObfuscationMode.ALL;
  }

  public boolean enabled() {
    return enabled;
  }

  /** 生效世界名集合；空集表示全部世界生效（供诊断输出）。 */
  public Set<String> worlds() {
    return worlds;
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

  /** 伪装范围模式（enclosed = 只藏掩埋矿；all = 所有目标矿一律伪装）。 */
  public ObfuscationMode obfuscationMode() {
    return obfuscationMode;
  }

  public boolean removeBlockEntities() {
    return removeBlockEntities;
  }

  /** 邻区块贴边快照相关配置。 */
  public Neighbors neighbors() {
    return neighbors;
  }

  /** 遮挡判定覆盖表（启动期构建位图时消费，修改需重启）。 */
  public Occlusion occlusion() {
    return occlusion;
  }

  /** 邻近显形相关配置。 */
  public Proximity proximity() {
    return proximity;
  }

  /** 磁盘缓存相关配置。 */
  public DiskCache diskCache() {
    return diskCache;
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