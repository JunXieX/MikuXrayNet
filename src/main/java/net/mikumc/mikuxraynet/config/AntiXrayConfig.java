package net.mikumc.mikuxraynet.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
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

  /**
   * 默认的矿透目标方块。
   *
   * <p>前 21 种：19 种矿石（含深层变体）之外，另有与矿等价的两种透视目标——
   * {@code spawner}（刷怪笼，真机 PE V_26_2 映射里就叫 {@code spawner}，{@code mob_spawner} 已不存在）
   * 与 {@code mossy_cobblestone}（苔石，常被用来标记矿洞/要塞）。
   *
   * <p>后 17 种为 P1-5 扩展（对齐 Paper 默认 hidden-blocks 与 Orebfuscator 的目标面）：
   * <ul>
   *   <li><b>容器/功能方块</b>（箱子族、熔炉族、漏斗/发射器等）：多为方块实体，透视端能借此定位
   *       地下基地与矿洞入口；被伪装 section 内它们的方块实体本就会被
   *       {@code remove-block-entities} 剔除，与伪装不冲突；</li>
   *   <li><b>基岩/黑曜石/粗金属块/黏土</b>：基岩层与黏土斑块的形状特征可直接暴露坐标与地形结构，
   *       粗金属块则直接对应富矿脉。</li>
   * </ul>
   * 全部名称已在真机 PE 2.13.0（V_26_2）状态表逐一核实可解析（见 PeRealDataOcclusionTest）。
   */
  private static final List<String> DEFAULT_HIDE_BLOCKS = List.of(
      "coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore",
      "copper_ore", "deepslate_copper_ore", "gold_ore", "deepslate_gold_ore",
      "redstone_ore", "deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore",
      "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore",
      "nether_gold_ore", "nether_quartz_ore", "ancient_debris",
      "spawner", "mossy_cobblestone",
      // —— P1-5 扩展：容器/功能方块（多为方块实体，remove-block-entities 会一并剔除其数据）——
      "chest", "trapped_chest", "ender_chest", "barrel",
      "furnace", "blast_furnace", "smoker",
      "hopper", "dropper", "dispenser", "shulker_box",
      // —— P1-5 扩展：结构暴露型方块 ——
      "bedrock", "raw_iron_block", "raw_gold_block", "raw_copper_block",
      "obsidian", "clay");

  /**
   * 内置 tag 静态映射（{@code tag(...)} 语法用）。
   *
   * <p><b>为什么是静态表</b>：离线（以及启动期）无法查 Bukkit 的方块 tag 注册表，因此这里把
   * 常用 tag 按 PacketEvents 的 StateType 名单固化成映射（成员名与真机 PE 2.13.0 / V_26_2 一致，
   * 由单测逐一核实可解析）。表外 tag 名在启动时 WARN 并忽略（不猜）。
   */
  private static final Map<String, List<String>> STATIC_TAG_MEMBERS;


  private static final Map<String, Integer> DEFAULT_REPLACEMENT_WEIGHTS;

  static {
    Map<String, List<String>> tags = new LinkedHashMap<>();
    tags.put("coal_ores", List.of("coal_ore", "deepslate_coal_ore"));
    tags.put("copper_ores", List.of("copper_ore", "deepslate_copper_ore"));
    tags.put("diamond_ores", List.of("diamond_ore", "deepslate_diamond_ore"));
    tags.put("emerald_ores", List.of("emerald_ore", "deepslate_emerald_ore"));
    tags.put("gold_ores", List.of("gold_ore", "deepslate_gold_ore", "nether_gold_ore"));
    tags.put("iron_ores", List.of("iron_ore", "deepslate_iron_ore"));
    tags.put("lapis_ores", List.of("lapis_ore", "deepslate_lapis_ore"));
    tags.put("redstone_ores", List.of("redstone_ore", "deepslate_redstone_ore"));
    tags.put("ores", List.of(
        "coal_ore", "deepslate_coal_ore", "copper_ore", "deepslate_copper_ore",
        "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore",
        "gold_ore", "deepslate_gold_ore", "iron_ore", "deepslate_iron_ore",
        "lapis_ore", "deepslate_lapis_ore", "redstone_ore", "deepslate_redstone_ore",
        "nether_gold_ore", "nether_quartz_ore", "ancient_debris"));
    tags.put("base_stone_overworld",
        List.of("stone", "granite", "diorite", "andesite", "tuff", "deepslate"));
    tags.put("base_stone_nether", List.of("netherrack", "basalt", "blackstone"));
    tags.put("base_stone_end", List.of("end_stone"));
    STATIC_TAG_MEMBERS = Map.copyOf(tags);

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
   * @param expireSeconds         显形索引条目的过期秒数（区块卸载未触发时的兜底）
   * @param maxPositions          伪装区块索引的坐标总量<b>安全阀</b>（正常运营下不应触发）
   * @param maxPositionsPerPlayer 单玩家已显形坐标的<b>安全阀</b>（正常运营下不应触发）
   * @param frustumEnabled        是否只显形玩家视野锥内的候选坐标
   * @param frustumFov            视野锥<b>竖直全张开角</b>（度）——与 Minecraft 客户端 FOV 设置同义；
   *                              水平方向按 16:9 宽高比换算后更宽（约 1.4 倍），
   *                              详见 {@code ProximitySelector#withinFrustum}
   * @param frustumMinDistance    该距离内的候选豁免视锥判定
   * @param raycastEnabled        是否做射线可见性判定（被墙挡住的不显形；<b>默认开启</b>）
   * @param raycastSamples        每条射线的最大采样体素数
   * @param instantReveal         事件驱动即时显形（周期巡检的补充，见 {@link InstantReveal}）
   * @param overRevealSampling    过度显形抽样统计的抽样率分母 N（1/N 抽样，只计数不改行为；
   *                              0 表示关闭）。口径：发包前若 {@link net.mikumc.mikuxraynet.antixray.RevealedSet}
   *                              已含该坐标，说明客户端应已可见，该显形包按「过度」计数
   */
  public record Proximity(boolean enabled, double distance, int intervalTicks, int maxRevealsPerTick,
      int expireSeconds, int maxPositions, int maxPositionsPerPlayer,
      boolean frustumEnabled, double frustumFov, double frustumMinDistance,
      boolean raycastEnabled, int raycastSamples,
      InstantReveal instantReveal, int overRevealSampling) {

    /** 邻近显形相关配置的兼容读取入口缺失时的兜底值（仅供旧构造方使用）。 */
    public Proximity {
      if (instantReveal == null) {
        instantReveal = new InstantReveal(false, 2, 16);
      }
      overRevealSampling = Math.max(0, overRevealSampling);
    }
  }

  /**
   * 事件驱动即时显形：观测到玩家身边（曼哈顿距离 ≤ {@code radius}）的出站方块变更时，
   * <b>当 tick</b>（不经周期巡检）把变更邻域内「已伪装且视线可见」的坐标显形。
   *
   * <p><b>与周期巡检的关系</b>：事件触发是<b>补充</b>（把「挖开/爆炸后要等最多 0.2 秒」的窗口压到 0），
   * 周期巡检兜底不变（覆盖变更落在半径外、玩家移动等事件路径触不到的场景）。两者共用同一套显形链路
   * （射线判定、已显形标记、统计），同一坐标当 tick 只发一次，不会重复。
   *
   * @param enabled    总开关（默认开启）
   * @param radius     曼哈顿半径（格），变更必须落在玩家身边该半径内才触发（默认 2，与竞品 updateRadius 一致）
   * @param maxPerTick 每玩家每 tick 事件显形限额（防爆刷；默认 16，0 表示关闭事件显形）
   */
  public record InstantReveal(boolean enabled, int radius, int maxPerTick) {
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

  /**
   * 按 Y 分区的伪装权重段（{@code obfuscation.replacement-bands} 的一项，P0-3）。
   *
   * @param minY    覆盖高度下界（含）
   * @param maxY    覆盖高度上界（含）
   * @param weights 伪装方块名 → 权重（名称已小写归一，权重已保证为正）
   */
  public record ReplacementBand(int minY, int maxY, Map<String, Integer> weights) {
  }

  /**
   * 逐世界覆盖段（{@code world-overrides} 的一项，P0-2）。
   *
   * <p>{@code pattern} 为世界名模式：精确名或含 {@code *} 的通配（如 {@code world_*}）。
   * 各覆盖值为 {@code null} 表示「该键未覆盖，回落全局默认」。
   *
   * @param glob               由 {@code pattern} 预编译的通配匹配器（不含 {@code *} 时为 null，走精确名比较）
   * @param hideBlocks         覆盖的隐藏方块清单；null = 回落
   * @param replacementWeights 覆盖的伪装权重表；null = 回落
   * @param replacementBands   覆盖的按 Y 分区伪装表；null = 回落
   * @param minY               覆盖的高度下界；null = 回落
   * @param maxY               覆盖的高度上界；null = 回落
   * @param mode               覆盖的伪装模式；null = 回落
   */
  public record WorldOverride(String pattern, Pattern glob, List<String> hideBlocks,
      Map<String, Integer> replacementWeights, List<ReplacementBand> replacementBands,
      Integer minY, Integer maxY, ObfuscationMode mode) {

    /** 是否与某世界名精确相等（精确名优先级高于通配）。 */
    public boolean exactMatches(String worldName) {
      return this.pattern.equals(worldName);
    }

    /** 世界名是否被通配模式覆盖（{@code *} 匹配任意字符序列）。 */
    public boolean globMatches(String worldName) {
      return this.glob != null && this.glob.matcher(worldName).matches();
    }
  }

  /**
   * 单个世界最终生效的混淆视图（P0-2）：全局默认值与该世界覆盖段合并后的纯数据。
   *
   * <p>反矿透处理器按世界取用本视图构建「目标位图 + 伪装权重表 + 高度范围」，未覆盖的键回落全局。
   * 高度范围用哨兵值表示「不限制」：{@code minY == Integer.MIN_VALUE} / {@code maxY == Integer.MAX_VALUE}。
   *
   * @param hideBlocks         生效的隐藏方块清单
   * @param replacementWeights 生效的伪装权重表（回落表：无 band 覆盖该高度时使用）
   * @param replacementBands   生效的按 Y 分区伪装表（bands 优先于 {@code replacementWeights}；空表 = 不启用分区）
   * @param minY               生效高度下界（含；{@code Integer.MIN_VALUE} = 不限制）
   * @param maxY               生效高度上界（含；{@code Integer.MAX_VALUE} = 不限制）
   * @param mode               生效的伪装模式
   */
  public record EffectiveObfuscation(List<String> hideBlocks, Map<String, Integer> replacementWeights,
      List<ReplacementBand> replacementBands, int minY, int maxY, ObfuscationMode mode) {
  }

  private final boolean enabled;
  private final Set<String> worlds;
  private final List<String> hideBlocks;
  private final Map<String, Integer> replacementWeights;
  /** 按 Y 分区的伪装权重段（P0-3，声明序；空表 = 不启用分区，回落 replacement-weights）。 */
  private final List<ReplacementBand> replacementBands;
  /** 全局高度范围（obfuscation.min-y/max-y）；null = 不限制。 */
  private final Integer obfuscationMinY;
  private final Integer obfuscationMaxY;
  private final boolean layerObfuscation;
  private final ObfuscationMode obfuscationMode;
  private final boolean removeBlockEntities;
  /** use-block-below：命中伪装时优先用「下方紧邻方块」当伪装方块（默认关闭，行为不变）。 */
  private final boolean useBlockBelow;
  private final Neighbors neighbors;
  private final Occlusion occlusion;
  private final Proximity proximity;
  private final DiskCache diskCache;
  private final PlatformSupport.Mode platform;
  private final int cacheMaximumSize;
  private final int cacheExpireAfterAccessSeconds;
  private final int threads;
  private final int timeoutMillis;
  private final int queueCapacity;
  /** 解析时无法识别而被忽略的 tag 名（启动期由调用方 WARN 提醒）。 */
  private final Set<String> unresolvedTags;
  private final int configHash;
  /** 逐世界覆盖段（声明序，P0-2）；空列表 = 无覆盖。 */
  private final List<WorldOverride> worldOverrides;
  /** 无覆盖时的生效视图（构造期预计算，热路径直接复用）。 */
  private final EffectiveObfuscation globalEffective;
  /** 与 {@link #worldOverrides} 平行的各覆盖段生效视图（构造期预计算）。 */
  private final List<EffectiveObfuscation> overrideEffectives;

  private AntiXrayConfig(boolean enabled, Set<String> worlds, List<String> hideBlocks,
      Map<String, Integer> replacementWeights, List<ReplacementBand> replacementBands,
      Integer obfuscationMinY, Integer obfuscationMaxY, boolean layerObfuscation,
      ObfuscationMode obfuscationMode, boolean removeBlockEntities, boolean useBlockBelow,
      Neighbors neighbors, Occlusion occlusion, Proximity proximity, DiskCache diskCache,
      PlatformSupport.Mode platform, int cacheMaximumSize, int cacheExpireAfterAccessSeconds,
      int threads, int timeoutMillis, int queueCapacity, Set<String> unresolvedTags,
      List<WorldOverride> worldOverrides) {
    this.enabled = enabled;
    this.worlds = Set.copyOf(worlds);
    this.hideBlocks = List.copyOf(hideBlocks);
    this.replacementWeights = Collections.unmodifiableMap(new LinkedHashMap<>(replacementWeights));
    this.replacementBands = List.copyOf(replacementBands);
    this.obfuscationMinY = obfuscationMinY;
    this.obfuscationMaxY = obfuscationMaxY;
    this.layerObfuscation = layerObfuscation;
    this.obfuscationMode = obfuscationMode;
    this.removeBlockEntities = removeBlockEntities;
    this.useBlockBelow = useBlockBelow;
    this.neighbors = neighbors;
    this.occlusion = occlusion;
    this.proximity = proximity;
    this.diskCache = diskCache;
    this.platform = platform == null ? PlatformSupport.Mode.AUTO : platform;
    this.cacheMaximumSize = Math.max(1, cacheMaximumSize);
    this.cacheExpireAfterAccessSeconds = Math.max(1, cacheExpireAfterAccessSeconds);
    this.threads = Math.max(0, threads);
    this.timeoutMillis = Math.max(100, timeoutMillis);
    this.queueCapacity = Math.max(1, queueCapacity);
    this.unresolvedTags = unresolvedTags == null ? Set.of() : Set.copyOf(unresolvedTags);
    this.worldOverrides = List.copyOf(worldOverrides);
    // 预计算各世界的生效视图：合并只在构造期做一次，运行期 matchOverride 命中后直接取用（O(1)）。
    // 高度范围按「覆盖值 > 全局值 > 不限制」回落。
    this.globalEffective = new EffectiveObfuscation(this.hideBlocks, this.replacementWeights,
        this.replacementBands, normalizeMin(obfuscationMinY), normalizeMax(obfuscationMaxY),
        obfuscationMode);
    List<EffectiveObfuscation> effectives = new ArrayList<>(this.worldOverrides.size());
    for (WorldOverride override : this.worldOverrides) {
      effectives.add(new EffectiveObfuscation(
          override.hideBlocks() == null ? this.hideBlocks : List.copyOf(override.hideBlocks()),
          override.replacementWeights() == null ? this.replacementWeights
              : Collections.unmodifiableMap(new LinkedHashMap<>(override.replacementWeights())),
          override.replacementBands() == null ? this.replacementBands
              : List.copyOf(override.replacementBands()),
          normalizeMin(override.minY() == null ? obfuscationMinY : override.minY()),
          normalizeMax(override.maxY() == null ? obfuscationMaxY : override.maxY()),
          override.mode() == null ? obfuscationMode : override.mode()));
    }
    this.overrideEffectives = List.copyOf(effectives);
    // 影响改写结果的全部配置项都参与哈希（含遮挡覆盖表与伪装模式）；顺序敏感，故用有序列表。
    // 伪装模式必须参与：否则「enclosed 写出的缓存」会在切换到 all 后被复用，导致裸露矿泄漏。
    // use-block-below 必须参与：它决定伪装方块的取值，切换后旧缓存不得复用。
    // P0-2/P0-3 新增：高度范围、按 Y 分区伪装表与逐世界覆盖都影响改写结果，一并纳入指纹
    //（逐世界段的指纹 = 模式名 + 合并后的生效值；配合缓存键里的世界名，不同世界互不串包）。
    List<Object> overrideFingerprints = new ArrayList<>(this.worldOverrides.size());
    for (int i = 0; i < this.worldOverrides.size(); i++) {
      EffectiveObfuscation effective = this.overrideEffectives.get(i);
      overrideFingerprints.add(List.of(this.worldOverrides.get(i).pattern(),
          effective.hideBlocks(),
          new ArrayList<>(effective.replacementWeights().entrySet()),
          effective.replacementBands(),
          effective.minY(), effective.maxY(), effective.mode()));
    }
    this.configHash = Objects.hash(this.hideBlocks, new ArrayList<>(replacementWeights.entrySet()),
        this.replacementBands, this.globalEffective.minY(), this.globalEffective.maxY(),
        layerObfuscation, obfuscationMode, useBlockBelow, neighbors.enabled(), neighbors.missingPolicy(),
        OcclusionRules.sortedList(this.occlusion.extraOccluding()),
        OcclusionRules.sortedList(this.occlusion.extraNonOccluding()),
        overrideFingerprints);
  }

  /** 高度下界归一：null（未配置）→ {@code Integer.MIN_VALUE}（不限制）。 */
  private static int normalizeMin(Integer value) {
    return value == null ? Integer.MIN_VALUE : value;
  }

  /** 高度上界归一：null（未配置）→ {@code Integer.MAX_VALUE}（不限制）。 */
  private static int normalizeMax(Integer value) {
    return value == null ? Integer.MAX_VALUE : value;
  }

  /** 从配置根节点解析。 */
  public static AntiXrayConfig from(ConfigurationSection root) {
    Set<String> unknownTags = new LinkedHashSet<>();
    List<String> hideBlocks = root.getStringList("obfuscation.hide-blocks");
    if (hideBlocks.isEmpty()) {
      hideBlocks = DEFAULT_HIDE_BLOCKS;
    }
    // tag(...) 在启动期展开为该 tag 下全部方块（内置静态映射；识别不了的 tag 记入 unknownTags 供 WARN）
    hideBlocks = expandTags(hideBlocks, unknownTags);

    Map<String, Integer> weights = new LinkedHashMap<>();
    ConfigurationSection weightSection = root.getConfigurationSection("obfuscation.replacement-weights");
    if (weightSection != null) {
      for (String key : weightSection.getKeys(false)) {
        int weight = weightSection.getInt(key, 0);
        if (weight <= 0) {
          continue;
        }
        for (String name : expandTags(List.of(key), unknownTags)) {
          weights.put(OcclusionRules.normalize(name), weight);
        }
      }
    }
    if (weights.isEmpty()) {
      weights.putAll(DEFAULT_REPLACEMENT_WEIGHTS);
    }

    // P0-3：按 Y 分区的伪装权重段（bands 优先于 replacement-weights；空表 = 不启用分区）
    List<ReplacementBand> bands = parseBands(root.getMapList("obfuscation.replacement-bands"));

    // P0-2：全局高度范围（可选，缺省不限制——判定与替换都跳过范围外的方块）
    Integer globalMinY = root.contains("obfuscation.min-y") ? root.getInt("obfuscation.min-y") : null;
    Integer globalMaxY = root.contains("obfuscation.max-y") ? root.getInt("obfuscation.max-y") : null;

    // P0-2：逐世界覆盖段（未列出的世界用全局默认；每个段可覆盖 obfuscation 的任意子键）
    List<WorldOverride> overrides = parseWorldOverrides(root.getConfigurationSection("world-overrides"));

    return new AntiXrayConfig(
        root.getBoolean("enabled", true),
        Set.copyOf(root.getStringList("worlds")),
        hideBlocks,
        weights,
        bands,
        globalMinY,
        globalMaxY,
        root.getBoolean("obfuscation.layer-obfuscation", false),
        // 默认 all：用户明确要求「透视不能直接看到裸露在矿洞中的矿物」，故默认对所有目标矿一律伪装。
        obfuscationMode(root.getString("obfuscation.mode", "all")),
        root.getBoolean("obfuscation.remove-block-entities", true),
        // use-block-below（默认关）：命中伪装时优先用「下方紧邻方块」当伪装方块（观感更自然）。
        // 下方方块须可用（非空气/非流体）且不得是目标方块（enclosed 模式下未伪装的裸矿不能被复制）。
        root.getBoolean("obfuscation.use-block-below", false),
        new Neighbors(
            root.getBoolean("neighbors.enabled", true),
            missingPolicy(root.getString("neighbors.missing-policy", "hide")),
            root.getInt("neighbors.cache-maximum-size", 512)),
        new Occlusion(
            OcclusionRules.normalizeAll(root.getStringList("occlusion.extra-occluding")),
            OcclusionRules.normalizeAll(root.getStringList("occlusion.extra-non-occluding"))),
        new Proximity(
            root.getBoolean("proximity.enabled", true),
            // 默认 64（原为 48，更早为 32/12）：真机反馈「48 格仍然偏近，走到跟前才变回来」。显形只在射线通畅
            // （视线真能看到、且只取暴露面上的采样点）时才还原，因此放大距离不会隔着墙泄露——它只是把
            // 「本来就看得到的矿」更早、更远地还给玩家。取舍：距离越大越及时、观感越接近原版，但候选越多、
            // 发包量与每 tick 上限（max-reveals-per-tick）越相关。
            Math.max(0.0D, root.getDouble("proximity.distance", 64.0D)),
            // 默认 4（原为 5）：显形周期越短、玩家转身后「石头还没变回来」的窗口越小；局部扫描已把
            // 单周期开销与探索历史脱钩（整块显形完的区块直接跳过），4 tick 的额外扫描量可忽略。
            Math.max(1, root.getInt("proximity.interval-ticks", 4)),
            // 默认 256（原为 128）：配合扩大到 64 格的距离，候选数量随之上升，单次额度也要相应放大，
            // 否则脚边的矿会被远处候选挤到后面。4 tick 一次、256 个 ≈ 1280 个/秒，仍远低于区块包流量。
            Math.max(1, root.getInt("proximity.max-reveals-per-tick", 256)),
            // 默认 300（原为 120）：登录/传送后区块一次性连续下发，玩家往往过一会儿才走到近处，
            // 窗口太短会让「还没走到就被清掉」的坐标永不还原。
            Math.max(1, root.getInt("proximity.expire-seconds", 300)),
            // 安全阀（原为「容量上限」）：伪装坐标按区块共享、已显形集合只记实际发过包的坐标，
            // 两者天然有界（分别随「已加载的伪装区块数」与「玩家身边的显形数」增长），不再需要
            // 「按玩家容量淘汰」。本项只在索引管理出 bug 时兜底——正常运营下淘汰数应恒为 0。
            Math.max(1, root.getInt("proximity.max-positions", 4194304)),
            // 安全阀（原为「单玩家坐标上限」）：含义同 max-positions，作用于单玩家的已显形坐标数。
            Math.max(1, root.getInt("proximity.max-positions-per-player", 524288)),
            root.getBoolean("proximity.frustum.enabled", true),
            clampFov(root.getDouble("proximity.frustum.fov", 80.0D)),
            Math.max(0.0D, root.getDouble("proximity.frustum.min-distance", 4.0D)),
            // 默认 true（原为 false）：真机 dump 显示为 false，导致「隔着墙也把矿物亮给透视客户端」。
            // 改为 true 后只在射线无遮挡时才发真实方块，代价是每个候选多几次主线程读方块。
            root.getBoolean("proximity.raycast.enabled", true),
            Math.max(2, root.getInt("proximity.raycast.samples", 16)),
            // 事件驱动即时显形（周期巡检的补充，周期兜底不变）：玩家身边的出站方块变更当 tick 补发邻域。
            new InstantReveal(
                root.getBoolean("proximity.instant-reveal.enabled", true),
                Math.max(1, Math.min(8, root.getInt("proximity.instant-reveal.radius", 2))),
                Math.max(0, root.getInt("proximity.instant-reveal.max-per-tick", 16))),
            // 过度显形抽样统计（1/N，只计数不改行为；0 = 关闭）
            Math.max(0, root.getInt("proximity.over-reveal-sampling", 20))),
        new DiskCache(
            root.getBoolean("disk-cache.enabled", true),
            Math.max(1, root.getInt("disk-cache.max-entries", 20000)),
            Math.max(1, root.getInt("disk-cache.max-file-size-mb", 16)),
            Math.max(1, root.getInt("disk-cache.expire-seconds", 1800)),
            // 默认 8（原为 2）：真机负载下 bucket 缓存命中率随容量提升约 16~20 个百分点——
            // 热点区块集中在少数 bucket 里，2 个 bucket 的 LRU 装不下「同一区域文件里的邻区块」，
            // 频繁互相驱逐导致重复重解码。8 个 bucket 的额外内存（≈8×64 槽）受
            // idle-close-seconds 与 max-file-size-mb 双重护栏约束，可控。
            Math.max(1, root.getInt("disk-cache.bucket-cache-size", 8)),
            Math.max(1, root.getInt("disk-cache.idle-close-seconds", 300)),
            Math.max(1, root.getInt("disk-cache.maintenance-interval-seconds", 30)),
            Math.max(1, root.getInt("disk-cache.compact-per-pass", 4)),
            Math.max(1, root.getInt("disk-cache.queue-capacity", 256)),
            Math.max(1, root.getInt("disk-cache.generation-tracker-size", 32768))),
        // 平台兜底：auto（默认，按服务端品牌/版本标识判定）| paper | folia。
        // 该值不影响改写结果，故不参与配置指纹。
        PlatformSupport.Mode.parse(root.getString("advanced.platform", "auto")),
        root.getInt("cache.maximum-size", 4096),
        root.getInt("cache.expire-after-access-seconds", 60),
        root.getInt("advanced.threads", 0),
        root.getInt("advanced.timeout-millis", 2500),
        root.getInt("advanced.queue-capacity", 2048),
        unknownTags,
        overrides);
  }

  /**
   * 解析按 Y 分区的伪装权重段（P0-3）。
   *
   * <p>每段形如 {@code {min-y: -64, max-y: -1, weights: {deepslate: 10, ...}}}；min-y/max-y 缺省时
   * 分别视为「负无穷/正无穷」。min &gt; max 的段（矛盾配置）直接丢弃——空段比错误段更安全
   * （回落 replacement-weights，不会静默漏伪装）。
   */
  private static List<ReplacementBand> parseBands(List<Map<?, ?>> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<ReplacementBand> bands = new ArrayList<>(raw.size());
    for (Map<?, ?> entry : raw) {
      int minY = entry.get("min-y") instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
      int maxY = entry.get("max-y") instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
      if (minY > maxY) {
        continue;
      }
      Map<String, Integer> weights = new LinkedHashMap<>();
      if (entry.get("weights") instanceof Map<?, ?> weightMap) {
        for (Map.Entry<?, ?> weightEntry : weightMap.entrySet()) {
          int weight = weightEntry.getValue() instanceof Number number ? number.intValue() : 0;
          if (weight > 0 && weightEntry.getKey() != null) {
            weights.put(OcclusionRules.normalize(String.valueOf(weightEntry.getKey())), weight);
          }
        }
      }
      if (!weights.isEmpty()) {
        bands.add(new ReplacementBand(minY, maxY, Collections.unmodifiableMap(weights)));
      }
    }
    return List.copyOf(bands);
  }

  /**
   * 解析逐世界覆盖段（P0-2）：每个键是世界名模式（精确名或含 {@code *} 的通配），
   * 值是 {@code obfuscation} 的覆盖子键。未出现的键保持 {@code null}（回落全局默认）。
   */
  private static List<WorldOverride> parseWorldOverrides(ConfigurationSection section) {
    if (section == null) {
      return List.of();
    }
    List<WorldOverride> overrides = new ArrayList<>();
    for (String pattern : section.getKeys(false)) {
      ConfigurationSection world = section.getConfigurationSection(pattern);
      if (world == null) {
        continue;
      }
      // 通配模式预编译为正则（'*' → 任意字符序列）；不含 '*' 时走精确名比较，无需匹配器
      Pattern glob = pattern.indexOf('*') >= 0
          ? Pattern.compile("\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E")
          : null;

      List<String> hideBlocks = null;
      if (world.contains("obfuscation.hide-blocks")) {
        hideBlocks = world.getStringList("obfuscation.hide-blocks");
        hideBlocks = hideBlocks.isEmpty() ? List.of() : List.copyOf(expandTags(hideBlocks, null));
      }
      Map<String, Integer> replacementWeights = null;
      ConfigurationSection weightSection = world.getConfigurationSection("obfuscation.replacement-weights");
      if (weightSection != null) {
        replacementWeights = new LinkedHashMap<>();
        for (String key : weightSection.getKeys(false)) {
          int weight = weightSection.getInt(key, 0);
          if (weight > 0) {
            for (String name : expandTags(List.of(key), null)) {
              replacementWeights.put(OcclusionRules.normalize(name), weight);
            }
          }
        }
        if (replacementWeights.isEmpty()) {
          replacementWeights = null; // 空覆盖回落全局，避免「配了但配错 → 不伪装」的静默失效
        }
      }
      List<ReplacementBand> replacementBands = null;
      if (world.contains("obfuscation.replacement-bands")) {
        replacementBands = parseBands(world.getMapList("obfuscation.replacement-bands"));
      }
      Integer minY = world.contains("obfuscation.min-y") ? world.getInt("obfuscation.min-y") : null;
      Integer maxY = world.contains("obfuscation.max-y") ? world.getInt("obfuscation.max-y") : null;
      ObfuscationMode mode = world.contains("obfuscation.mode")
          ? obfuscationMode(world.getString("obfuscation.mode")) : null;

      overrides.add(new WorldOverride(pattern, glob, hideBlocks, replacementWeights,
          replacementBands, minY, maxY, mode));
    }
    return List.copyOf(overrides);
  }

  /**
   * 展开 {@code tag(...)} 语法（启动期一次性完成，热路径零开销）。
   *
   * <p>{@code tag(diamond_ores)} → 该 tag 的全部成员；普通名称原样透传。识别不了的 tag 名
   * 记入 {@code unknownTags}（调用方启动时 WARN），该条目被<b>忽略</b>（不猜成员、不误藏）。
   */
  static List<String> expandTags(List<String> names, Set<String> unknownTags) {
    boolean hasTag = false;
    for (String name : names) {
      if (tagName(name) != null) {
        hasTag = true;
        break;
      }
    }
    if (!hasTag) {
      return names;
    }
    List<String> expanded = new ArrayList<>(names.size());
    for (String name : names) {
      String tag = tagName(name);
      if (tag == null) {
        expanded.add(name);
        continue;
      }
      List<String> members = STATIC_TAG_MEMBERS.get(tag);
      if (members == null) {
        if (unknownTags != null) {
          unknownTags.add(tag);
        }
        continue;
      }
      expanded.addAll(members);
    }
    return expanded;
  }

  /** 解析 {@code tag(xxx)} 语法；不是 tag 语法返回 {@code null}（大小写不敏感、去空白）。 */
  private static String tagName(String raw) {
    if (raw == null) {
      return null;
    }
    String key = raw.trim().toLowerCase(Locale.ROOT);
    if (key.length() > 5 && key.startsWith("tag(") && key.endsWith(")")) {
      String inner = key.substring(4, key.length() - 1).trim();
      return inner.isEmpty() ? null : inner;
    }
    return null;
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

  /**
   * use-block-below：命中伪装时若「下方紧邻方块」可用（非空气/非流体、非目标方块），优先用它当伪装方块。
   * 默认关闭；开启会改变改写结果（已参与配置指纹，切换后缓存整体失效）。
   */
  public boolean useBlockBelow() {
    return useBlockBelow;
  }

  /** 解析时无法识别而被忽略的 tag 名（供启动日志 WARN 提醒管理员）；无则空集。 */
  public Set<String> unresolvedTags() {
    return unresolvedTags;
  }

  /** 按 Y 分区的伪装权重段（P0-3；空表 = 未配置，回落 replacement-weights）。 */
  public List<ReplacementBand> replacementBands() {
    return replacementBands;
  }

  /** 全局高度范围下界（{@code obfuscation.min-y}）；null = 不限制（供诊断输出）。 */
  public Integer obfuscationMinY() {
    return obfuscationMinY;
  }

  /** 全局高度范围上界（{@code obfuscation.max-y}）；null = 不限制（供诊断输出）。 */
  public Integer obfuscationMaxY() {
    return obfuscationMaxY;
  }

  /** 逐世界覆盖段（P0-2，声明序）；空列表 = 无覆盖。 */
  public List<WorldOverride> worldOverrides() {
    return worldOverrides;
  }

  /** 无覆盖时的生效视图（全局默认值；供处理器构建默认档案）。 */
  public EffectiveObfuscation globalEffective() {
    return globalEffective;
  }

  /** 第 {@code index} 个覆盖段的生效视图（与 {@link #worldOverrides()} 平行）。 */
  public EffectiveObfuscation overrideEffective(int index) {
    return overrideEffectives.get(index);
  }

  /**
   * 匹配某世界名对应的覆盖段下标（P0-2）。
   *
   * <p>匹配规则：<b>精确名 &gt; 通配</b>（如 {@code world_*}）；多个通配同时命中时取模式最长的
   * （最具体），同长取声明序靠前的。无匹配返回 {@code -1}（该世界用全局默认）。
   */
  public int matchOverride(String worldName) {
    if (worldName == null || worldOverrides.isEmpty()) {
      return -1;
    }
    int best = -1;
    int bestPatternLength = -1;
    for (int i = 0; i < worldOverrides.size(); i++) {
      WorldOverride override = worldOverrides.get(i);
      if (override.exactMatches(worldName)) {
        return i; // 精确名最高优先，命中即止
      }
      if (override.globMatches(worldName) && override.pattern().length() > bestPatternLength) {
        best = i;
        bestPatternLength = override.pattern().length();
      }
    }
    return best;
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

  /**
   * 平台判定兜底（{@code advanced.platform}）：{@code AUTO}（默认，按服务端品牌/版本标识判定）
   * 或手动指定 {@code PAPER}/{@code FOLIA}。
   *
   * <p>供 {@code MikuXrayNet} 在装配任何模块之前调用 {@code PlatformSupport.configure(...)}。
   * 它同时影响反矿透与带宽两个模块的调度路径，因此虽然写在 antixray.yml 里也是全局生效的。
   */
  public PlatformSupport.Mode platform() {
    return platform;
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