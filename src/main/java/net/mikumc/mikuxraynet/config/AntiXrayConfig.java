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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.registry.OcclusionRules;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 反矿透配置（对应 {@code antixray.yml}）。
 *
 * <p>本类只承载「已解析的纯数据」，不含任何 Bukkit 世界引用，可安全地在工作线程读取。
 * 方块名称到方块状态 id 的解析在 {@code registry.BlockStateRegistry} 中完成。
 *
 * <p><b>按维度分段（本版重构）</b>：旧的顶层 {@code worlds}（世界白名单）与顶层 {@code obfuscation}
 * 已合并为 {@code dimensions.<维度>} 段——主世界 / 地狱 / 末地各自独立指定要隐藏的方块、伪装方块、
 * 伪装模式（all/enclosed）、高度范围与按 Y 分区伪装表。**旧键干净替换、不做迁移**；
 * 若运行期发现配置缺少 {@code dimensions} 段，则用内置默认运行并一次性 WARN（绝不让保护静默失效）。
 *
 * <p><b>世界黑名单（{@code world-blacklist}，优先级最高）</b>：名单内的世界<b>不使用任何反矿透功能</b>
 * （区块改写、邻近显形、方块变更观察、邻块快照、磁盘缓存全部跳过）；它<b>高于 {@code world-overrides}</b>
 * ——某世界既在黑名单又被 {@code world-overrides} 显式启用，仍以黑名单为准（要重新启用必须从黑名单移除）。
 * 带宽模块（实体剔除 / 变更合并 / AFK / 降视距 / 零位移取消）<b>不受</b>影响，继续在这些世界生效。
 *
 * <p><b>解析优先级（高 → 低）</b>：
 * <ol>
 *   <li>{@code world-blacklist}（按世界名，精确名 &gt; 通配；默认空）；</li>
 *   <li>{@code world-overrides}（按世界名，精确名 &gt; 通配；保留为手动出口，默认空）；</li>
 *   <li>{@code dimensions.<维度>}（维度由 {@link Dimension#of(World.Environment)} 判定，与世界名无关）；</li>
 *   <li>内置默认（本类常量）。</li>
 * </ol>
 *
 * <p>任何一段缺失都回落到更安全的默认值，避免「配置损坏 → 什么都不隐藏」的静默失效。
 */
public final class AntiXrayConfig {

  /**
   * 配置维度段：由 {@code World#getEnvironment()} 判定，**不依赖世界名**（CUSTOM 归入 normal）。
   */
  public enum Dimension {
    NORMAL("normal", "主世界"),
    NETHER("nether", "地狱"),
    THE_END("the_end", "末地");

    private final String key;
    private final String label;

    Dimension(String key, String label) {
      this.key = key;
      this.label = label;
    }

    /** {@code antixray.yml} 里的段名（normal / nether / the_end）。 */
    public String key() {
      return key;
    }

    /** 中文名（日志与诊断回显用）。 */
    public String label() {
      return label;
    }

    /**
     * 环境归类：{@code NETHER} → 地狱、{@code THE_END} → 末地、其余（含 {@code CUSTOM}、null）→ 主世界。
     */
    public static Dimension of(World.Environment environment) {
      if (environment == World.Environment.NETHER) {
        return NETHER;
      }
      if (environment == World.Environment.THE_END) {
        return THE_END;
      }
      return NORMAL;
    }
  }

  /**
   * 默认隐藏的容器方块：只保留箱子 {@code chest}（透视端最想找的地下基地/矿洞标志物）。
   *
   * <p>桶/熔炉族/漏斗/发射器/投掷器/潜影盒/末影箱/陷阱箱<b>不再默认隐藏</b>：它们可制造、遍布建筑，
   * 藏它们换来的是到处「假方块」的观感与更高的替换量（按需自行加回 {@code hide-blocks} 即可）。
   */
  private static final List<String> CONTAINER_BLOCKS = List.of("chest");

  /** 16 种主世界矿石（含深层变体）。 */
  private static final List<String> OVERWORLD_ORES = List.of(
      "coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore",
      "copper_ore", "deepslate_copper_ore", "gold_ore", "deepslate_gold_ore",
      "redstone_ore", "deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore",
      "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore");

  /**
   * 主世界默认隐藏清单（共 22 种）：16 种主世界矿石 + 3 种粗金属块 + {@code chest}
   * + {@code spawner}（刷怪笼）+ {@code mossy_cobblestone}（苔石）。
   *
   * <p>箱子为方块实体，被伪装 section 内它的方块实体本就会被 {@code remove-block-entities} 剔除，
   * 与伪装不冲突；粗金属块直接对应富矿脉。刻意不再隐藏：桶/熔炉族/漏斗/发射器/投掷器/潜影盒/末影箱/
   * 陷阱箱（可制造、遍布建筑）与基岩/黑曜石/黏土（自然结构方块，透视价值低）。
   * 全部名称已在真机 PE 2.13.0（V_26_2）状态表逐一核实可解析（见 PeRealDataOcclusionTest）。
   */
  private static final List<String> DEFAULT_NORMAL_HIDE_BLOCKS;
  /**
   * 地狱默认隐藏清单（共 4 种）：{@code ancient_debris}、{@code nether_gold_ore}、{@code chest}、
   * {@code spawner}。桶/基岩不再默认隐藏（可制造 / 自然方块，透视价值低）。
   *
   * <p><b>明确不含 {@code nether_quartz_ore}</b>：地狱石英分布极广、单块价值极低，若把它也全藏，
   * 玩家在地狱会挖到大量「假石头」，观感与效率都不可接受（用户明确要求地狱不隐藏石英矿）。
   */
  private static final List<String> DEFAULT_NETHER_HIDE_BLOCKS;
  /**
   * 末地默认隐藏清单（共 1 种）：{@code chest}。末地本身无矿物，默认维度整体关闭；
   * 如需隐藏末地城的箱子再开启 {@code dimensions.the_end.enabled}。
   */
  private static final List<String> DEFAULT_THE_END_HIDE_BLOCKS;

  /** 各维度内置默认是否启用：末地无矿物，默认关闭。 */
  private static final Map<Dimension, Boolean> DEFAULT_ENABLED;

  /** 各维度内置默认伪装权重表（回落表）。 */
  private static final Map<Dimension, Map<String, Integer>> DEFAULT_WEIGHTS;

  /** 各维度内置默认按 Y 分区伪装表（仅主世界给默认；空表 = 不启用分区）。 */
  private static final Map<Dimension, List<ReplacementBand>> DEFAULT_BANDS;

  /**
   * 内置 tag 静态映射（{@code tag(...)} 语法用）。
   *
   * <p><b>为什么是静态表</b>：离线（以及启动期）无法查 Bukkit 的方块 tag 注册表，因此这里把
   * 常用 tag 按 PacketEvents 的 StateType 名单固化成映射（成员名与真机 PE 2.13.0 / V_26_2 一致，
   * 由单测逐一核实可解析）。表外 tag 名在启动时 WARN 并忽略（不猜）。
   */
  private static final Map<String, List<String>> STATIC_TAG_MEMBERS;

  static {
    // ---- 默认隐藏清单（顺序与打包 antixray.yml 保持一致，便于人工对照）----
    List<String> normal = new ArrayList<>(OVERWORLD_ORES);
    normal.addAll(List.of("raw_iron_block", "raw_gold_block", "raw_copper_block"));
    normal.addAll(CONTAINER_BLOCKS);
    normal.addAll(List.of("spawner", "mossy_cobblestone"));
    DEFAULT_NORMAL_HIDE_BLOCKS = List.copyOf(normal);

    List<String> nether = new ArrayList<>(List.of("ancient_debris", "nether_gold_ore"));
    nether.addAll(CONTAINER_BLOCKS);
    nether.addAll(List.of("spawner"));
    DEFAULT_NETHER_HIDE_BLOCKS = List.copyOf(nether);

    List<String> end = new ArrayList<>(CONTAINER_BLOCKS);
    DEFAULT_THE_END_HIDE_BLOCKS = List.copyOf(end);

    Map<Dimension, Boolean> enabled = new LinkedHashMap<>();
    enabled.put(Dimension.NORMAL, true);
    enabled.put(Dimension.NETHER, true);
    enabled.put(Dimension.THE_END, false);
    DEFAULT_ENABLED = Collections.unmodifiableMap(enabled);

    Map<Dimension, Map<String, Integer>> weights = new LinkedHashMap<>();
    weights.put(Dimension.NORMAL, weightsOf("stone", 10, "deepslate", 8));
    weights.put(Dimension.NETHER, weightsOf("netherrack", 10, "basalt", 4, "blackstone", 3));
    weights.put(Dimension.THE_END, weightsOf("end_stone", 10));
    DEFAULT_WEIGHTS = Collections.unmodifiableMap(weights);

    Map<Dimension, List<ReplacementBand>> bands = new LinkedHashMap<>();
    // 主世界按 Y 分区：深层深板岩系、浅层石头系（伪装观感与真实地层一致，抗统计识别更强）。
    bands.put(Dimension.NORMAL, List.of(
        new ReplacementBand(-64, -1, weightsOf("deepslate", 10, "tuff", 4, "stone", 2)),
        new ReplacementBand(0, 320, weightsOf("stone", 10, "andesite", 3, "dirt", 2))));
    bands.put(Dimension.NETHER, List.of());
    bands.put(Dimension.THE_END, List.of());
    DEFAULT_BANDS = Collections.unmodifiableMap(bands);

    // ---- tag(...) 静态映射 ----
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
  }

  /** 便捷构造「方块名 → 权重」的有序表（保持声明序）。 */
  private static Map<String, Integer> weightsOf(Object... pairs) {
    Map<String, Integer> weights = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      weights.put(String.valueOf(pairs[i]), (Integer) pairs[i + 1]);
    }
    return Collections.unmodifiableMap(weights);
  }

  /** 邻块数据缺失（未加载、跨区域、已清理）时的降级策略。 */
  public enum MissingPolicy {
    /** 视为遮挡：宁可多伪装，也不留下沿区块边界的泄漏（默认）。 */
    HIDE,
    /** 视为暴露：保持「边界那一圈不伪装」的旧行为（存在可利用的透视口子）。 */
    EXPOSE
  }

  /**
   * 伪装范围模式（{@code dimensions.<维度>.mode}，**按维度独立**）。
   *
   * <p><b>enclosed</b>：只伪装「6 面全被遮挡」的方块（掩埋矿）。矿洞壁上的裸露矿保持原样，
   * 因此透视端能直接看到裸露矿；好处是玩家几乎不会看到「假方块」。
   *
   * <p><b>all</b>（默认）：所有目标矿一律伪装（不看 6 面遮挡），靠邻近显形在玩家靠近且可见时还原。
   * 透视端看不到任何真实矿物（含矿洞壁上的裸露矿）；代价是矿洞壁上的矿会暂时显示为伪装方块，
   * 玩家靠近可见后才变回真实矿物。
   */
  public enum ObfuscationMode {
    /** 只伪装被完全掩埋的矿。 */
    ENCLOSED,
    /** 所有目标矿一律伪装，靠邻近显形还原（默认，最防透视）。 */
    ALL
  }

  /** 邻区块贴边快照。 */
  public record Neighbors(boolean enabled, MissingPolicy missingPolicy, int cacheMaximumSize) {
  }

  /**
   * 遮挡判定的用户覆盖表（用户自行纠正判定的出口）与流体覆盖开关。
   *
   * <p>{@code extraNonOccluding} 优先级最高（显式声明不遮挡），{@code extraOccluding} 次之
   * （可覆盖内置的空气/形状/材质/薄片规则）。名称用方块注册名（无需 {@code minecraft:} 前缀）。
   *
   * <p>{@code fluidCover}（默认开）：目标方块「上方紧邻方块是流体」时按遮挡处理（隐藏侧），
   * 且显形侧不显形——让刷在岩浆里的下界残骸保持伪装，透视端看不到。它只作用于目标方块的判定，
   * **不改动全局遮挡表**（避免影响非目标方块）。
   *
   * <p>本表影响启动期构建的遮挡位图与流体规则，因此<b>只在启动时生效</b>（修改后需重启服务端），
   * 且参与配置指纹——重启后磁盘缓存也会随之失效。
   */
  public record Occlusion(Set<String> extraOccluding, Set<String> extraNonOccluding, boolean fluidCover) {
  }

  /**
   * 视锥竖直全角的<b>安全下限</b>（度）：等于 Minecraft 客户端 FOV 设置的上限 110。
   *
   * <p><b>为什么设下限而不是只写默认值</b>：客户端的 FOV 是纯本地设置、服务端拿不到，而视锥剔除
   * 的代价是「被剔除的坐标不发包、也不记已显形」——配得比客户端真实视场更窄，就会让玩家<b>明明看得见</b>
   * 的方块一直保持伪装（真机反馈：「站在黑曜石门正前方，一半方块始终是石头/泥土，点一下才变回来」
   * ——周期性显形走视锥判定、点击走的事件显形不走，两者只差这一道闸门）。
   * 取 110° 时，16:9 屏幕下竖直半角 55°、水平半角 68.4°，恰好覆盖「客户端 FOV 开到上限 + 16:9」
   * 的整个可视区，因此<b>任何合法客户端能看到的方向都不会被剔除</b>（更宽的屏幕见 yml 注释）。
   * 想省带宽应当关掉整个视锥剔除（{@code proximity.frustum.enabled=false}），而不是把角度配窄。
   */
  public static final double FRUSTUM_FOV_FLOOR = 110.0D;

  /**
   * 视锥「最小豁免距离」的<b>安全下限</b>（格）：该距离内的候选完全不参与视锥判定。
   *
   * <p>贴脸/脚边的方块应当「看得见就一定是真的」——角度再偏也必须显形，否则会出现
   * 「站在门/墙正前方，近处那半边是伪装、点一下才变回来」的现象（门的顶部在 3 格距离上就有
   * 40° 以上的仰角，配 4 格豁免必然被剔除）。16 格 = 一个区块的距离，覆盖玩家「在跟前」的全部观感。
   */
  public static final double FRUSTUM_MIN_DISTANCE_FLOOR = 16.0D;

  /**
   * 磁盘缓存条目过期秒数的<b>安全下限</b>（1 天）。
   *
   * <p><b>为什么设下限</b>：磁盘缓存的用途就是「重启后直接复用已改写好的区块负载」，而条目过期时间
   * 是<b>从写入时刻</b>算的——配得比两次启动的间隔还短，条目在重启后必然已经过期，命中率恒为 0
   * （真机反馈：把 expire-seconds 配成默认的 1800 秒，两次会话间隔 70 分钟 → 命中 0／未命中 2096，
   * 磁盘上躺着的 1951 条全是过期条目）。
   *
   * <p>内容是否还新鲜<b>不</b>由过期时间判定：条目负载里带着原始区块字节指纹，与本次要改写的字节
   * 不符即视为未命中（见 {@code DiskPayload}）。因此限制磁盘占用应当调
   * {@code disk-cache.max-entries} / {@code max-file-size-mb}，而不是把过期时间配短。
   */
  public static final int DISK_CACHE_EXPIRE_FLOOR_SECONDS = 86400;

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
   *                              详见 {@code ProximitySelector#withinFrustum}；
   *                              <b>不低于 {@link #FRUSTUM_FOV_FLOOR}</b>（低于下限会被抬升并 WARN）
   * @param frustumMinDistance    该距离内的候选豁免视锥判定；
   *                              <b>不低于 {@link #FRUSTUM_MIN_DISTANCE_FLOOR}</b>（低于下限会被抬升并 WARN）
   * @param raycastEnabled        是否做射线可见性判定（被墙挡住的不显形；<b>默认开启</b>）
   * @param raycastSamples        <b>每方块最多尝试的候选点数</b>（射线改用 Paper 原生
   *                              {@code World#rayTraceBlocks} 后不再表示采样数）；钳制 1..8，默认 4
   * @param instantReveal         事件驱动即时显形（周期巡检的补充，见 {@link InstantReveal}）
   * @param overRevealSampling    过度显形抽样统计的抽样率分母 N（1/N 抽样，只计数不改行为；
   *                              0 表示关闭）
   * @param batchRevealSends      是否把同一个周期内的显形合并成 Paper 原生多方块变更包
   *                              （{@code Player#sendMultiBlockChange}）发出；关闭时逐坐标
   *                              用 {@code Player#sendBlockChange} 发出（默认开启，供 A/B 与回退用）
   */
  public record Proximity(boolean enabled, double distance, int intervalTicks, int maxRevealsPerTick,
      int expireSeconds, int maxPositions, int maxPositionsPerPlayer,
      boolean frustumEnabled, double frustumFov, double frustumMinDistance,
      boolean raycastEnabled, int raycastSamples,
      InstantReveal instantReveal, int overRevealSampling, boolean batchRevealSends) {
  }

  /**
   * 事件驱动即时显形：观测到玩家身边（曼哈顿距离 ≤ {@code radius}）的出站方块变更时，
   * <b>当 tick</b>（不经周期巡检）把变更邻域内「已伪装且视线可见」的坐标显形。
   *
   * @param enabled    总开关（默认开启）
   * @param radius     曼哈顿半径（格），变更必须落在玩家身边该半径内才触发（默认 2）
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
   * @param expireSeconds              条目过期秒数（读取与后台维护都会惰性清理）；
   *                                   <b>不低于 {@link #DISK_CACHE_EXPIRE_FLOOR_SECONDS}</b>（低于下限会被抬升并 WARN）
   * @param bucketCacheSize            每个打开的区域文件在内存里缓存的 bucket 数（内存上限的关键）
   * @param idleCloseSeconds           句柄闲置多久后自动落盘并关闭（释放文件描述符与内存）
   * @param maintenanceIntervalSeconds 后台维护周期（落盘 + 压缩回收 + 关闭闲置句柄）
   * @param compactPerPass             每轮维护最多压缩回收的区域文件数
   * @param queueCapacity              磁盘线程待处理任务上限（超出即丢弃，保网络路径）
   * @param generationTrackerSize      区块代次跟踪表的条目上限（有界 LRU）
   * @param zstdAutoDownload           zstd 前置缺失时是否自动下载（默认 true）
   * @param zstdDownloadUrl            zstd 下载源根地址（默认 Maven Central；可换阿里云镜像）
   * @param zstdTimeoutSeconds         zstd 下载的总等待上限（秒；超时即失败并回退，默认 10）
   */
  public record DiskCache(boolean enabled, int maxEntries, int maxFileSizeMb, int expireSeconds,
      int bucketCacheSize, int idleCloseSeconds, int maintenanceIntervalSeconds, int compactPerPass,
      int queueCapacity, int generationTrackerSize, boolean zstdAutoDownload, String zstdDownloadUrl,
      int zstdTimeoutSeconds) {
  }

  /**
   * 按 Y 分区的伪装权重段（{@code dimensions.<维度>.replacement-bands} 的一项）。
   *
   * @param minY    覆盖高度下界（含）
   * @param maxY    覆盖高度上界（含）
   * @param weights 伪装方块名 → 权重（名称已小写归一，权重已保证为正）
   */
  public record ReplacementBand(int minY, int maxY, Map<String, Integer> weights) {
  }

  /**
   * 逐世界覆盖段（{@code world-overrides} 的一项，最高优先级）。
   *
   * <p>{@code pattern} 为世界名模式：精确名或含 {@code *} 的通配（如 {@code world_*}）。
   * 各覆盖值为 {@code null} 表示「该键未覆盖，回落该世界所属<b>维度</b>的生效值」。
   *
   * @param glob               由 {@code pattern} 预编译的通配匹配器（不含 {@code *} 时为 null，走精确名比较）
   * @param hideBlocks         覆盖的隐藏方块清单；null = 回落
   * @param replacementWeights 覆盖的伪装权重表；null = 回落
   * @param replacementBands   覆盖的按 Y 分区伪装表；null = 回落
   * @param minY               覆盖的高度下界；null = 回落
   * @param maxY               覆盖的高度上界；null = 回落
   * @param mode               覆盖的伪装模式；null = 回落
   * @param useBlockBelow      覆盖的 use-block-below；null = 回落
   */
  public record WorldOverride(String pattern, Pattern glob, List<String> hideBlocks,
      Map<String, Integer> replacementWeights, List<ReplacementBand> replacementBands,
      Integer minY, Integer maxY, ObfuscationMode mode, Boolean useBlockBelow) {

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
   * 单个维度（或某个世界覆盖段在该维度上）最终生效的混淆视图：隐藏清单、伪装权重表、分区表、
   * 高度范围、伪装模式与 use-block-below。
   *
   * <p>高度范围用哨兵值表示「不限制」：{@code minY == Integer.MIN_VALUE} /
   * {@code maxY == Integer.MAX_VALUE}。
   *
   * @param hideBlocks         生效的隐藏方块清单
   * @param replacementWeights 生效的伪装权重表（回落表：无 band 覆盖该高度时使用）
   * @param replacementBands   生效的按 Y 分区伪装表（bands 优先于 {@code replacementWeights}；空表 = 不分区）
   * @param minY               生效高度下界（含；{@code Integer.MIN_VALUE} = 不限制）
   * @param maxY               生效高度上界（含；{@code Integer.MAX_VALUE} = 不限制）
   * @param mode               生效的伪装模式
   * @param useBlockBelow      命中伪装时是否优先用「下方紧邻方块」当伪装方块
   */
  public record EffectiveObfuscation(List<String> hideBlocks, Map<String, Integer> replacementWeights,
      List<ReplacementBand> replacementBands, int minY, int maxY, ObfuscationMode mode,
      boolean useBlockBelow) {
  }

  private final boolean enabled;
  /** 各维度生效视图（下标 = {@link Dimension#ordinal()}）。 */
  private final EffectiveObfuscation[] dimensionEffectives;
  /** 各维度是否启用（下标 = {@link Dimension#ordinal()}）。 */
  private final boolean[] dimensionEnabled;
  /** 配置是否缺少 {@code dimensions} 段（缺段时用内置默认运行并一次性 WARN）。 */
  private final boolean dimensionsMissing;
  /**
   * 被安全下限（{@link #FRUSTUM_FOV_FLOOR} / {@link #FRUSTUM_MIN_DISTANCE_FLOOR} /
   * {@link #DISK_CACHE_EXPIRE_FLOOR_SECONDS}）抬升过的配置键明细；空列表表示未抬升。
   * 加载时由 {@link #warnIfConfigFloorApplied} 一次性 WARN。
   */
  private final List<String> floorAdjustments;
  private final boolean layerObfuscation;
  private final boolean removeBlockEntities;
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
  /** 逐世界覆盖段（声明序）；空列表 = 无覆盖。 */
  private final List<WorldOverride> worldOverrides;
  /** 与 {@link #worldOverrides} 平行的生效视图：{@code [覆盖段下标][维度下标]}（构造期预计算）。 */
  private final EffectiveObfuscation[][] overrideEffectives;
  /** 反矿透世界黑名单原始模式（声明序；诊断/日志回显用）。空列表 = 未配置。 */
  private final List<String> worldBlacklist;
  /** 黑名单中的精确世界名（启动期拆分，热路径短线性扫描）。 */
  private final List<String> blacklistExact;
  /** 黑名单中的通配匹配器（启动期预编译，热路径不做正则编译）。 */
  private final List<Pattern> blacklistGlobs;

  private AntiXrayConfig(boolean enabled, EffectiveObfuscation[] dimensionEffectives,
      boolean[] dimensionEnabled, boolean dimensionsMissing, boolean layerObfuscation,
      boolean removeBlockEntities, Neighbors neighbors, Occlusion occlusion, Proximity proximity,
      DiskCache diskCache, PlatformSupport.Mode platform, int cacheMaximumSize,
      int cacheExpireAfterAccessSeconds, int threads, int timeoutMillis, int queueCapacity,
      Set<String> unresolvedTags, List<WorldOverride> worldOverrides, List<String> worldBlacklist,
      List<String> floorAdjustments) {
    this.enabled = enabled;
    this.dimensionEffectives = dimensionEffectives.clone();
    this.dimensionEnabled = dimensionEnabled.clone();
    this.dimensionsMissing = dimensionsMissing;
    this.layerObfuscation = layerObfuscation;
    this.removeBlockEntities = removeBlockEntities;
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
    // 黑名单在构造期拆分并预编译通配匹配器：热路径只有短线性扫描，绝不逐包编译正则（列表通常 1~10 项）。
    this.worldBlacklist = List.copyOf(worldBlacklist);
    // 被安全下限抬升过的键（空 = 未抬升）；加载时由 warnIfConfigFloorApplied 一次性提示
    this.floorAdjustments = floorAdjustments == null ? List.of() : List.copyOf(floorAdjustments);
    List<String> blacklistExactNames = new ArrayList<>(this.worldBlacklist.size());
    List<Pattern> blacklistGlobPatterns = new ArrayList<>(this.worldBlacklist.size());
    for (String pattern : this.worldBlacklist) {
      Pattern glob = compileGlob(pattern);
      if (glob == null) {
        blacklistExactNames.add(pattern);
      } else {
        blacklistGlobPatterns.add(glob);
      }
    }
    this.blacklistExact = List.copyOf(blacklistExactNames);
    this.blacklistGlobs = List.copyOf(blacklistGlobPatterns);
    // 预计算各覆盖段在「每个维度」上的生效视图：合并只在构造期做一次，运行期命中后直接取用（O(1)）。
    EffectiveObfuscation[][] merged = new EffectiveObfuscation[this.worldOverrides.size()][Dimension.values().length];
    for (int i = 0; i < this.worldOverrides.size(); i++) {
      WorldOverride override = this.worldOverrides.get(i);
      for (Dimension dimension : Dimension.values()) {
        merged[i][dimension.ordinal()] = merge(override, this.dimensionEffectives[dimension.ordinal()]);
      }
    }
    this.overrideEffectives = merged;
    // 影响改写结果的全部配置项都参与哈希（含遮挡/流体覆盖表与各维度生效值）；顺序敏感，故用有序列表。
    // 伪装模式必须参与：否则「enclosed 写出的缓存」会在切换到 all 后被复用，导致裸露矿泄漏。
    // use-block-below / 高度范围 / 分区表 / 维度启用 都影响改写结果，一并纳入指纹。
    List<Object> dimensionFingerprints = new ArrayList<>(Dimension.values().length);
    for (Dimension dimension : Dimension.values()) {
      dimensionFingerprints.add(List.of(dimension.key(), this.dimensionEnabled[dimension.ordinal()],
          this.dimensionEffectives[dimension.ordinal()]));
    }
    List<Object> overrideFingerprints = new ArrayList<>(this.worldOverrides.size());
    for (int i = 0; i < this.worldOverrides.size(); i++) {
      List<Object> perDimension = new ArrayList<>(Dimension.values().length);
      for (Dimension dimension : Dimension.values()) {
        perDimension.add(this.overrideEffectives[i][dimension.ordinal()]);
      }
      overrideFingerprints.add(List.of(this.worldOverrides.get(i).pattern(), perDimension));
    }
    this.configHash = Objects.hash(dimensionFingerprints, overrideFingerprints, layerObfuscation,
        neighbors.enabled(), neighbors.missingPolicy(),
        OcclusionRules.sortedList(this.occlusion.extraOccluding()),
        OcclusionRules.sortedList(this.occlusion.extraNonOccluding()), this.occlusion.fluidCover(),
        // 黑名单决定「哪些世界完全不改写」，参与指纹：切换黑名单后旧缓存（该世界已改写结果）不再复用。
        this.worldBlacklist);
  }

  /** 把某覆盖段合并到某维度的生效值上（覆盖值优先，未覆盖回落维度值）。 */
  private static EffectiveObfuscation merge(WorldOverride override, EffectiveObfuscation base) {
    return new EffectiveObfuscation(
        override.hideBlocks() == null ? base.hideBlocks() : List.copyOf(override.hideBlocks()),
        override.replacementWeights() == null ? base.replacementWeights()
            : Collections.unmodifiableMap(new LinkedHashMap<>(override.replacementWeights())),
        override.replacementBands() == null ? base.replacementBands()
            : List.copyOf(override.replacementBands()),
        normalizeMin(override.minY() == null ? base.minY() : override.minY()),
        normalizeMax(override.maxY() == null ? base.maxY() : override.maxY()),
        override.mode() == null ? base.mode() : override.mode(),
        override.useBlockBelow() == null ? base.useBlockBelow() : override.useBlockBelow());
  }

  /** 高度下界归一：null（未配置）→ {@code Integer.MIN_VALUE}（不限制）。 */
  private static int normalizeMin(Integer value) {
    return value == null ? Integer.MIN_VALUE : value;
  }

  /** 高度上界归一：null（未配置）→ {@code Integer.MAX_VALUE}（不限制）。 */
  private static int normalizeMax(Integer value) {
    return value == null ? Integer.MAX_VALUE : value;
  }

  /** 某维度的内置默认生效视图。 */
  private static EffectiveObfuscation builtinDefault(Dimension dimension) {
    return new EffectiveObfuscation(
        switch (dimension) {
          case NORMAL -> DEFAULT_NORMAL_HIDE_BLOCKS;
          case NETHER -> DEFAULT_NETHER_HIDE_BLOCKS;
          case THE_END -> DEFAULT_THE_END_HIDE_BLOCKS;
        },
        DEFAULT_WEIGHTS.get(dimension),
        DEFAULT_BANDS.get(dimension),
        Integer.MIN_VALUE, Integer.MAX_VALUE, ObfuscationMode.ALL, false);
  }

  /** zstd 下载源缺省值：Maven Central 根地址（国内服务器建议在 antixray.yml 里换成阿里云镜像）。 */
  public static final String DEFAULT_ZSTD_DOWNLOAD_URL = "https://repo1.maven.org/maven2";

  /** zstd 下载源：缺失/空白/只有斜杠时回落到 {@link #DEFAULT_ZSTD_DOWNLOAD_URL}。 */
  private static String zstdDownloadUrl(ConfigurationSection root) {
    String url = root.getString("disk-cache.zstd.download-url", DEFAULT_ZSTD_DOWNLOAD_URL);
    return url == null || url.isBlank() ? DEFAULT_ZSTD_DOWNLOAD_URL : url.trim();
  }

  /** 从配置根节点解析。 */
  public static AntiXrayConfig from(ConfigurationSection root) {
    Set<String> unknownTags = new LinkedHashSet<>();

    // ---- world-blacklist：反矿透世界黑名单（最高优先级；默认空）----
    // 名单内的世界不使用任何反矿透功能（改写/显形/变更观察/邻块快照/磁盘缓存全跳过），带宽模块不受影响。
    List<String> worldBlacklist = parseWorldBlacklist(root.getStringList("world-blacklist"));

    // ---- dimensions：按维度分段（新结构；缺段则全部回落内置默认并标记，供调用方一次性 WARN）----
    ConfigurationSection dimensions = root.getConfigurationSection("dimensions");
    boolean anyDimensionSection = false;
    EffectiveObfuscation[] effectives = new EffectiveObfuscation[Dimension.values().length];
    boolean[] dimensionEnabled = new boolean[Dimension.values().length];
    for (Dimension dimension : Dimension.values()) {
      EffectiveObfuscation fallback = builtinDefault(dimension);
      ConfigurationSection section =
          dimensions == null ? null : dimensions.getConfigurationSection(dimension.key());
      if (section == null) {
        effectives[dimension.ordinal()] = fallback;
        dimensionEnabled[dimension.ordinal()] = Boolean.TRUE.equals(DEFAULT_ENABLED.get(dimension));
        continue;
      }
      anyDimensionSection = true;
      dimensionEnabled[dimension.ordinal()] =
          section.getBoolean("enabled", Boolean.TRUE.equals(DEFAULT_ENABLED.get(dimension)));
      effectives[dimension.ordinal()] = parseDimensionSection(section, fallback, unknownTags);
    }

    // ---- world-overrides：最高优先级（按世界名；默认空）----
    List<WorldOverride> overrides =
        parseWorldOverrides(root.getConfigurationSection("world-overrides"), unknownTags);

    // ---- 安全下限：被抬升的键记进 floorAdjustments，加载时一次性 WARN（解析侧不持日志）----
    List<String> floorAdjustments = new ArrayList<>(3);

    return new AntiXrayConfig(
        root.getBoolean("enabled", true),
        effectives,
        dimensionEnabled,
        !anyDimensionSection,
        root.getBoolean("obfuscation.layer-obfuscation", false),
        root.getBoolean("obfuscation.remove-block-entities", true),
        new Neighbors(
            root.getBoolean("neighbors.enabled", true),
            missingPolicy(root.getString("neighbors.missing-policy", "hide")),
            // 默认 2048（原为 512）：快照已按位打包（每格 1 bit，主世界 384 高度约 3 KB/条），
            // 2048 条约 6 MB；512 条时缓存接近饱和、抓取次数随玩家移动抖动
            //（单次抓取实测约 0.776 ms，现按列高度上界裁剪后更少）。
            root.getInt("neighbors.cache-maximum-size", 2048)),
        new Occlusion(
            OcclusionRules.normalizeAll(root.getStringList("occlusion.extra-occluding")),
            OcclusionRules.normalizeAll(root.getStringList("occlusion.extra-non-occluding")),
            // 流体覆盖默认开启：刷在岩浆里的下界残骸若不按遮挡处理，会裸露在矿洞里被透视看到。
            root.getBoolean("occlusion.fluid-cover", true)),
        new Proximity(
            root.getBoolean("proximity.enabled", true),
            // 默认 64：真机反馈「48 格仍然偏近，走到跟前才变回来」。显形只在射线通畅时才还原，
            // 因此放大距离不会隔着墙泄露——它只是把「本来就看得到的矿」更早、更远地还给玩家。
            Math.max(0.0D, root.getDouble("proximity.distance", 64.0D)),
            // 默认 4（原为 5）：显形周期越短，「石头还没变回来」的窗口越小。
            Math.max(1, root.getInt("proximity.interval-ticks", 4)),
            // 默认 256（原为 128）：配合扩大到 64 格的距离，候选数量随之上升，单次额度也要相应放大。
            Math.max(1, root.getInt("proximity.max-reveals-per-tick", 256)),
            // 默认 300（原为 120）：登录/传送后区块一次性连续下发，玩家往往过一会儿才走到近处。
            Math.max(1, root.getInt("proximity.expire-seconds", 300)),
            Math.max(1, root.getInt("proximity.max-positions", 4194304)),
            Math.max(1, root.getInt("proximity.max-positions-per-player", 524288)),
            root.getBoolean("proximity.frustum.enabled", true),
            // 视锥两键都有安全下限（见 FRUSTUM_FOV_FLOOR / FRUSTUM_MIN_DISTANCE_FLOOR）：
            // 配得比客户端可视范围更窄会让「玩家看得见的方块」保持伪装，因此低于下限时按下限生效，
            // 并把被抬升的键记进 floorAdjustments，由加载时一次性 WARN 显式告知（绝不静默改配置）。
            frustumFov(root.getDouble("proximity.frustum.fov", FRUSTUM_FOV_FLOOR), floorAdjustments),
            frustumMinDistance(
                root.getDouble("proximity.frustum.min-distance", FRUSTUM_MIN_DISTANCE_FLOOR),
                floorAdjustments),
            root.getBoolean("proximity.raycast.enabled", true),
            // 语义已变：原生射线改造后本键表示「每方块最多尝试的候选点数」（不再表示采样数）。
            // 钳制 1..8，默认 4；候选点的选择只在暴露面上（面中心 → 最近点 → 面四角），命中即止。
            Math.max(1, Math.min(8, root.getInt("proximity.raycast.samples", 4))),
            new InstantReveal(
                root.getBoolean("proximity.instant-reveal.enabled", true),
                Math.max(1, Math.min(8, root.getInt("proximity.instant-reveal.radius", 2))),
                Math.max(0, root.getInt("proximity.instant-reveal.max-per-tick", 16))),
            Math.max(0, root.getInt("proximity.over-reveal-sampling", 20)),
            // 批量合并显形包（默认开启）：把一个周期内的显形用 Paper 原生多方块变更包一次发出。
            // 关掉即逐坐标发单方块变更包（旧行为），供 A/B 对比与线上回退。
            root.getBoolean("proximity.batch-reveal-sends", true)),
        new DiskCache(
            root.getBoolean("disk-cache.enabled", true),
            Math.max(1, root.getInt("disk-cache.max-entries", 20000)),
            Math.max(1, root.getInt("disk-cache.max-file-size-mb", 16)),
            // 过期秒数有安全下限（见 DISK_CACHE_EXPIRE_FLOOR_SECONDS）：从写入时刻算的过期时间配得比
            // 两次启动的间隔还短，条目在重启后必然已过期，磁盘缓存等于白写（真机命中率恒为 0 的根因）。
            diskCacheExpireSeconds(root.getInt("disk-cache.expire-seconds", 7 * 24 * 60 * 60),
                floorAdjustments),
            Math.max(1, root.getInt("disk-cache.bucket-cache-size", 8)),
            Math.max(1, root.getInt("disk-cache.idle-close-seconds", 300)),
            Math.max(1, root.getInt("disk-cache.maintenance-interval-seconds", 30)),
            Math.max(1, root.getInt("disk-cache.compact-per-pass", 4)),
            Math.max(1, root.getInt("disk-cache.queue-capacity", 256)),
            Math.max(1, root.getInt("disk-cache.generation-tracker-size", 32768)),
            // zstd 前置：服务端自带则直接用；没有则按这三键决定是否自动下载（见 ZstdSupport）
            root.getBoolean("disk-cache.zstd.auto-download", true),
            zstdDownloadUrl(root),
            Math.max(1, root.getInt("disk-cache.zstd.timeout-seconds", 10))),
        PlatformSupport.Mode.parse(root.getString("advanced.platform", "auto")),
        // 未配置时的内置兜底必须与打包 antixray.yml 的默认值保持一致（有单测对照）
        root.getInt("cache.maximum-size", 40960),
        root.getInt("cache.expire-after-access-seconds", 600),
        root.getInt("advanced.threads", 0),
        root.getInt("advanced.timeout-millis", 2500),
        root.getInt("advanced.queue-capacity", 2048),
        unknownTags,
        overrides,
        worldBlacklist,
        floorAdjustments);
  }

  /**
   * 解析 {@code world-blacklist}：世界名模式列表（精确名或含 {@code *} 的通配）。
   *
   * <p>去空白、跳过空条目并按声明序去重（保留首个），其余原样保留（世界名大小写敏感，与
   * {@code world-overrides} 的匹配语义一致）。
   */
  private static List<String> parseWorldBlacklist(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<String> patterns = new ArrayList<>(raw.size());
    for (String entry : raw) {
      if (entry == null) {
        continue;
      }
      String pattern = entry.trim();
      if (pattern.isEmpty() || patterns.contains(pattern)) {
        continue;
      }
      patterns.add(pattern);
    }
    return List.copyOf(patterns);
  }

  /** 编译世界名通配模式：含 {@code *} 时预编译为正则（{@code *} → 任意字符序列）；不含则返回 {@code null}（走精确名比较）。 */
  private static Pattern compileGlob(String pattern) {
    return pattern.indexOf('*') >= 0
        ? Pattern.compile("\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E")
        : null;
  }

  /** 解析某个维度段（未出现的键回落该维度的内置默认）。 */
  private static EffectiveObfuscation parseDimensionSection(ConfigurationSection section,
      EffectiveObfuscation fallback, Set<String> unknownTags) {
    List<String> hideBlocks = fallback.hideBlocks();
    if (section.contains("hide-blocks")) {
      List<String> raw = section.getStringList("hide-blocks");
      // 显式配了空清单 → 仍回落内置默认（避免「配了但配空 → 该维度完全不伪装」的静默失效）
      if (!raw.isEmpty()) {
        hideBlocks = List.copyOf(expandTags(raw, unknownTags));
      }
    }
    Map<String, Integer> weights = parseWeights(
        section.getConfigurationSection("replacement-weights"), unknownTags);
    if (weights.isEmpty()) {
      weights = fallback.replacementWeights();
    }
    List<ReplacementBand> bands = section.contains("replacement-bands")
        ? parseBands(section.getMapList("replacement-bands")) : fallback.replacementBands();
    int minY = section.contains("min-y") ? section.getInt("min-y") : fallback.minY();
    int maxY = section.contains("max-y") ? section.getInt("max-y") : fallback.maxY();
    ObfuscationMode mode = section.contains("mode")
        ? obfuscationMode(section.getString("mode")) : fallback.mode();
    boolean useBlockBelow = section.contains("use-block-below")
        ? section.getBoolean("use-block-below") : fallback.useBlockBelow();
    return new EffectiveObfuscation(hideBlocks, weights, bands, minY, maxY, mode, useBlockBelow);
  }

  /**
   * 解析「方块名 → 权重」表（支持 {@code tag(...)}，权重非正的条目被丢弃）。
   *
   * @return 有序表；无有效条目时为空表
   */
  private static Map<String, Integer> parseWeights(ConfigurationSection section,
      Set<String> unknownTags) {
    if (section == null) {
      return Map.of();
    }
    Map<String, Integer> weights = new LinkedHashMap<>();
    for (String key : section.getKeys(false)) {
      int weight = section.getInt(key, 0);
      if (weight <= 0) {
        continue;
      }
      for (String name : expandTags(List.of(key), unknownTags)) {
        weights.put(OcclusionRules.normalize(name), weight);
      }
    }
    return weights;
  }

  /**
   * 解析按 Y 分区的伪装权重段。
   *
   * <p>每段形如 {@code {min-y: -64, max-y: -1, weights: {deepslate: 10, ...}}}；min-y/max-y 缺省时
   * 分别视为「负无穷/正无穷」。min &gt; max 的段（矛盾配置）直接丢弃——空段比错误段更安全。
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
   * 解析逐世界覆盖段（最高优先级）：每个键是世界名模式（精确名或含 {@code *} 的通配），
   * 值直接是该世界要覆盖的子键（hide-blocks / replacement-weights / replacement-bands /
   * min-y / max-y / mode / use-block-below）。未出现的键保持 {@code null}（回落该世界所属维度）。
   */
  private static List<WorldOverride> parseWorldOverrides(ConfigurationSection section,
      Set<String> unknownTags) {
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
      Pattern glob = compileGlob(pattern);

      List<String> hideBlocks = null;
      if (world.contains("hide-blocks")) {
        List<String> raw = world.getStringList("hide-blocks");
        hideBlocks = raw.isEmpty() ? List.of() : List.copyOf(expandTags(raw, unknownTags));
      }
      Map<String, Integer> replacementWeights = null;
      if (world.contains("replacement-weights")) {
        Map<String, Integer> parsed = parseWeights(
            world.getConfigurationSection("replacement-weights"), unknownTags);
        if (!parsed.isEmpty()) {
          replacementWeights = parsed; // 空覆盖回落维度值，避免「配了但配错 → 不伪装」的静默失效
        }
      }
      List<ReplacementBand> replacementBands = world.contains("replacement-bands")
          ? parseBands(world.getMapList("replacement-bands")) : null;
      Integer minY = world.contains("min-y") ? world.getInt("min-y") : null;
      Integer maxY = world.contains("max-y") ? world.getInt("max-y") : null;
      ObfuscationMode mode = world.contains("mode")
          ? obfuscationMode(world.getString("mode")) : null;
      Boolean useBlockBelow = world.contains("use-block-below")
          ? world.getBoolean("use-block-below") : null;

      overrides.add(new WorldOverride(pattern, glob, hideBlocks, replacementWeights,
          replacementBands, minY, maxY, mode, useBlockBelow));
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

  /**
   * 视锥竖直全角的生效值：非法值（{@code <=0} / {@code >360}）回落默认，低于
   * {@link #FRUSTUM_FOV_FLOOR} 时抬升到下限并记入 {@code floorAdjustments}。
   *
   * <p>升到下限而不是照配置生效的原因见 {@link #FRUSTUM_FOV_FLOOR}：配窄了会让玩家看得见的方块
   * 一直保持伪装（显形判定里只有这一道闸门会「看得见却不发」）。要省带宽请关掉整个视锥剔除。
   */
  private static double frustumFov(double configured, List<String> floorAdjustments) {
    if (Double.isNaN(configured) || configured <= 0.0D || configured > 360.0D) {
      return FRUSTUM_FOV_FLOOR;
    }
    if (configured >= FRUSTUM_FOV_FLOOR) {
      return configured;
    }
    floorAdjustments.add("proximity.frustum.fov=" + configured + "（下限 " + FRUSTUM_FOV_FLOOR + "°）");
    return FRUSTUM_FOV_FLOOR;
  }

  /** 最小豁免距离的生效值：低于 {@link #FRUSTUM_MIN_DISTANCE_FLOOR} 时抬升到下限并记录明细。 */
  private static double frustumMinDistance(double configured, List<String> floorAdjustments) {
    if (Double.isNaN(configured)) {
      return FRUSTUM_MIN_DISTANCE_FLOOR;
    }
    double value = Math.max(0.0D, configured);
    if (value >= FRUSTUM_MIN_DISTANCE_FLOOR) {
      return value;
    }
    floorAdjustments.add("proximity.frustum.min-distance=" + value
        + "（下限 " + FRUSTUM_MIN_DISTANCE_FLOOR + " 格）");
    return FRUSTUM_MIN_DISTANCE_FLOOR;
  }

  /**
   * 磁盘缓存过期秒数的生效值：低于 {@link #DISK_CACHE_EXPIRE_FLOOR_SECONDS} 时抬升到下限并记录明细。
   *
   * <p>条目内容是否新鲜由负载里的原始字节指纹判定（见 {@code DiskPayload}），过期时间只是「多久没被
   * 复用就回收磁盘」的粒度，因此下限刻意取得较大（1 天）：小于这个量级的取值会让缓存活不过一次重启，
   * 功能等于关闭（真机命中率恒为 0 的根因）。
   */
  private static int diskCacheExpireSeconds(int configured, List<String> floorAdjustments) {
    if (configured >= DISK_CACHE_EXPIRE_FLOOR_SECONDS) {
      return configured;
    }
    floorAdjustments.add("disk-cache.expire-seconds=" + configured
        + "（下限 " + DISK_CACHE_EXPIRE_FLOOR_SECONDS + " 秒 = 1 天）");
    return DISK_CACHE_EXPIRE_FLOOR_SECONDS;
  }

  /** 解析缺失策略；取值非法时回落到最安全的 {@link MissingPolicy#HIDE}。 */
  private static MissingPolicy missingPolicy(String value) {
    if (value == null) {
      return MissingPolicy.HIDE;
    }
    return "expose".equals(value.trim().toLowerCase(Locale.ROOT)) ? MissingPolicy.EXPOSE
        : MissingPolicy.HIDE;
  }

  /** 解析伪装模式；只有显式填写 {@code enclosed} 才回到旧行为，其它一律取更安全的 {@link ObfuscationMode#ALL}。 */
  private static ObfuscationMode obfuscationMode(String value) {
    if (value != null && "enclosed".equals(value.trim().toLowerCase(Locale.ROOT))) {
      return ObfuscationMode.ENCLOSED;
    }
    return ObfuscationMode.ALL;
  }

  public boolean enabled() {
    return enabled;
  }

  /** 某维度是否启用（末地默认关闭）。 */
  public boolean dimensionEnabled(Dimension dimension) {
    return dimensionEnabled[dimension.ordinal()];
  }

  /** 某维度的生效视图（不受 {@code enabled} 影响；调用方按 {@link #dimensionEnabled} 决定是否使用）。 */
  public EffectiveObfuscation dimensionEffective(Dimension dimension) {
    return dimensionEffectives[dimension.ordinal()];
  }

  /** 配置是否缺少 {@code dimensions} 段（缺段时用内置默认运行）。 */
  public boolean dimensionsMissing() {
    return dimensionsMissing;
  }

  /**
   * 缺少 {@code dimensions} 段时输出<b>一次性</b>中文 WARN（进程级闸门 {@code once} 保证只提示一次）。
   *
   * <p><b>为什么必须提示</b>：旧配置（{@code worlds} / 顶层 {@code obfuscation}）已不再解析，
   * 若管理员仍用旧文件且我们不提示，管理员会以为「配置还在生效」，而实际用的是内置默认——
   * 保护虽未失效，但管理员的意图被静默忽略，必须显式告知。
   *
   * @param once 进程级一次性闸门（同一实例重复 reload 不会重复刷屏）
   */
  public void warnIfDimensionsMissing(Logger logger, AtomicBoolean once) {
    if (!dimensionsMissing || logger == null || !once.compareAndSet(false, true)) {
      return;
    }
    logger.warning("antixray.yml 缺少 dimensions 段：已使用内置默认（按维度）反矿透规则运行。"
        + "旧版 worlds / 顶层 obfuscation 键已不再解析且不会自动迁移，请删除旧配置并让插件重新生成 antixray.yml。");
  }

  /**
   * 配置项被安全下限抬升时的<b>一次性</b>中文 WARN（进程级闸门 {@code once} 保证只提示一次）。
   *
   * <p><b>为什么必须提示</b>：生效值与管理员的 yml 不一致，若不说明，管理员会以为「配的还是我写的值」，
   * 或反过来怀疑插件读错配置。这里给出被抬升的键、原因与正确出口（两个下限指向的功能失效模式见
   * {@link #FRUSTUM_FOV_FLOOR} 与 {@link #DISK_CACHE_EXPIRE_FLOOR_SECONDS}）。
   *
   * @param once 进程级一次性闸门（同一实例重复 reload 不会重复刷屏）
   */
  public void warnIfConfigFloorApplied(Logger logger, AtomicBoolean once) {
    if (floorAdjustments.isEmpty() || logger == null || !once.compareAndSet(false, true)) {
      return;
    }
    logger.warning("antixray.yml 的 " + String.join("、", floorAdjustments) + " 低于安全下限，已按下限生效"
        + "（当前：proximity.frustum.fov=" + proximity.frustumFov() + "°、min-distance="
        + proximity.frustumMinDistance() + " 格、disk-cache.expire-seconds="
        + diskCache.expireSeconds() + " 秒）。低于下限的取值会让对应功能静默失效："
        + "视锥配窄 → 玩家看得见的方块保持伪装（要点一下才变回来）；"
        + "磁盘缓存过期时间配短 → 条目活不过一次重启，命中率恒为 0。"
        + "限制磁盘占用请调 disk-cache.max-entries / max-file-size-mb，而不是缩短过期时间。");
  }

  /** 被安全下限抬升过的配置键明细（空列表 = 未抬升）；供诊断回显。 */
  public List<String> floorAdjustments() {
    return floorAdjustments;
  }

  /** 逐世界覆盖段（声明序）；空列表 = 无覆盖。 */
  public List<WorldOverride> worldOverrides() {
    return worldOverrides;
  }

  /** 第 {@code index} 个覆盖段在 {@code dimension} 维度上的生效视图。 */
  public EffectiveObfuscation overrideEffective(int index, Dimension dimension) {
    return overrideEffectives[index][dimension.ordinal()];
  }

  /**
   * 匹配某世界名对应的覆盖段下标。
   *
   * <p>匹配规则：<b>精确名 &gt; 通配</b>（如 {@code world_*}）；多个通配同时命中时取模式最长的
   * （最具体），同长取声明序靠前的。无匹配返回 {@code -1}（该世界用其维度的生效值）。
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

  /** 反矿透世界黑名单原始模式（声明序）；空列表 = 未配置。供启动日志与诊断回显。 */
  public List<String> worldBlacklist() {
    return worldBlacklist;
  }

  /**
   * 某世界是否在反矿透黑名单内（<b>纯函数</b>：启动期已拆分并预编译，热路径零新增分配）。
   *
   * <p>匹配语义与 {@link #matchOverride(String)} 一致：精确名与通配（{@code *} 匹配任意字符序列）都可命中。
   * 黑名单只关心「是否命中」这一布尔结果，因此「精确 &gt; 最长通配」在此等价于「任一命中即为黑名单」。
   *
   * @return true 表示该世界不使用任何反矿透功能（优先级最高，压过 {@code world-overrides}）
   */
  public boolean isBlacklisted(String worldName) {
    if (worldName == null || worldBlacklist.isEmpty()) {
      return false;
    }
    for (int i = 0; i < blacklistExact.size(); i++) {
      if (blacklistExact.get(i).equals(worldName)) {
        return true;
      }
    }
    for (int i = 0; i < blacklistGlobs.size(); i++) {
      if (blacklistGlobs.get(i).matcher(worldName).matches()) {
        return true;
      }
    }
    return false;
  }

  /**
   * 反矿透是否应作用于该世界：总开关开启 <b>且</b> 该世界不在黑名单内。
   *
   * <p><b>这是所有反矿透入口（区块改写、邻近显形、事件即时显形、方块变更观察）统一的判定出口</b>——
   * 各处不得自行重复实现匹配逻辑。注意：黑名单只豁免反矿透，带宽模块不受影响。
   */
  public boolean antiXrayAppliesTo(String worldName) {
    return enabled && !isBlacklisted(worldName);
  }

  /** 是否对同一高度层统一使用同一种伪装方块。 */
  public boolean layerObfuscation() {
    return layerObfuscation;
  }

  public boolean removeBlockEntities() {
    return removeBlockEntities;
  }

  /** 解析时无法识别而被忽略的 tag 名（供启动日志 WARN 提醒管理员）；无则空集。 */
  public Set<String> unresolvedTags() {
    return unresolvedTags;
  }

  /** 邻区块贴边快照相关配置。 */
  public Neighbors neighbors() {
    return neighbors;
  }

  /** 遮挡判定覆盖表 + 流体覆盖开关（启动期构建位图时消费，修改需重启）。 */
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
   * 平台判定兜底（{@code advanced.platform}）：{@code AUTO}（默认）或手动 {@code PAPER}/{@code FOLIA}。
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