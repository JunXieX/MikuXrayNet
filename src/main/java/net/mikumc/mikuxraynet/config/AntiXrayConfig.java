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
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><b>世界黑名单</b>：已从本文件<b>迁出</b>为独立的 {@code blacklist.yml}
 * （见 {@link WorldBlacklistConfig}），因为它现在同时对反矿透与带宽优化生效。反矿透各入口统一查询
 * {@link WorldBlacklistConfig#isBlacklistedNow(String)}；本类不再持有该名单。
 * 名单的优先级<b>最高</b>：{@code world-overrides} 里写 {@code enabled: true} 也不能让名单内的世界
 * 重新启用（要启用须先从黑名单移除）。
 *
 * <p><b>解析优先级（高 → 低）</b>：
 * <ol>
 *   <li>{@code world-overrides}（按世界名，精确名 &gt; 通配；保留为手动出口，默认空）；
 *       其中 {@code enabled} 可覆盖该世界所属维度的启用开关（显式值优先，未写则沿用维度）；</li>
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
   * 遮挡判定的用户覆盖表（用户自行纠正判定的出口）。
   *
   * <p>{@code extraNonOccluding} 优先级最高（显式声明不遮挡），{@code extraOccluding} 次之
   * （可覆盖逐状态真值 {@code BlockData#isOccluding()} 与全部启发式回落规则）。名称用方块注册名
   * （无需 {@code minecraft:} 前缀）。
   *
   * <p>「岩浆算遮挡」不再是一个可关的开关，而是<b>唯一规则入口</b> {@code OcclusionRules} 的视觉口径
   * 的一部分（岩浆视觉不透明）：它已并入共享遮挡位图，因此实体剔除、显形（位图/降级射线）、方块补丁四条
   * 路径自动一致。水是透明的，不参与遮挡。
   *
   * <p>本表影响启动期构建的遮挡位图，因此<b>只在启动时生效</b>（修改后需重启服务端），
   * 且参与配置指纹——重启后磁盘缓存也会随之失效。
   */
  public record Occlusion(Set<String> extraOccluding, Set<String> extraNonOccluding) {
  }

  /**
   * 视锥竖直全角的<b>默认值</b>（度）：120。
   *
   * <p>取在安全下限（{@link #FRUSTUM_FOV_FLOOR}）之上：下限保证「按 21:9 屏幕计算的合法可视区不会被剔除」，
   * 而默认值再宽一些，是为了覆盖极宽屏（32:9 等）与「转头瞬间视锥边界还没更新」的迟滞窗口——
   * 越宽越不会出现「看得见却还是伪装方块」，代价只是多评估一些侧后方向的候选（判定本身很廉价）。
   * 要省带宽请关掉整个视锥剔除，而不是把角度配窄。
   */
  public static final double FRUSTUM_FOV_DEFAULT = 120.0D;

  /**
   * 视锥竖直全角的<b>安全下限</b>（度）：等于 Minecraft 客户端 FOV 设置的上限 110。
   *
   * <p><b>为什么设下限而不是只写默认值</b>：客户端的 FOV 是纯本地设置、服务端拿不到，而视锥剔除
   * 的代价是「被剔除的坐标不发包、也不记已显形」——配得比客户端真实视场更窄，就会让玩家<b>明明看得见</b>
   * 的方块一直保持伪装（真机反馈：「站在黑曜石门正前方，一半方块始终是石头/泥土，点一下才变回来」
   * ——周期性显形走视锥判定、点击走的事件显形不走，两者只差这一道闸门）。
   * 取 110° 时，21:9 屏幕下竖直半角 55°、水平半角 73.3°（与 21:9 客户端的真实水平视场一致）。
   * 默认值 {@link #FRUSTUM_FOV_DEFAULT}（120°）的水平半角 76.1°，换算成宽高比约 25.5:9——
   * 即 32:9 客户端在 FOV ≤ 约 97° 时仍被完整覆盖，FOV 拉满到 110° 时最边缘约差 2.8°，
   * 这是已知残余（32:9 属极小众，再放宽会让显形候选量再增约一倍）。
   * 想省带宽应当关掉整个视锥剔除（{@code proximity.frustum.enabled=false}），而不是把角度配窄。
   */
  public static final double FRUSTUM_FOV_FLOOR = 110.0D;

  /**
   * 视锥「最小豁免距离」的<b>默认值</b>（格）：24（安全下限的 1.5 倍，约 1.5 个区块）。
   *
   * <p>该距离内的候选完全不参与视锥判定。取在下限之上，是让「刚进视锥边界、角度还偏」的近处方块
   * 也一并还原——玩家对「脚边/门后那一块还是假的」最敏感。
   */
  public static final double FRUSTUM_MIN_DISTANCE_DEFAULT = 24.0D;

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
   * 邻近显形半径的<b>默认值</b>（区块 = 视距单位）：<b>0 = 跟随服务器视距</b>。
   *
   * <p><b>为什么默认「跟随视距」而不是写死数值</b>：显形包只有在客户端<b>已加载该区块</b>时才生效，
   * 超出视距的显形包会被客户端直接丢弃（既浪费带宽，又可能留下「已显形」标记）。跟随服务器
   * view-distance（{@code server.properties}；若某世界在 world 级配置里覆盖过，则取该世界的生效值）
   * 能保证「发出去的显形包客户端一定收得到」，同时把扫描量收在玩家真实可见范围内。
   *
   * <p>配置正数时表示「最多向外显形几个区块」，仍会被钳制到该玩家的有效视距（服务器视距与
   * 客户端自报视距取小），因此不会越界。
   */
  public static final int PROXIMITY_DISTANCE_CHUNKS_DEFAULT = 0;

  /**
   * {@code proximity.distance-chunks} 的安全上限（区块）：32（Minecraft 允许的最大视距）。
   *
   * <p>实际半径永远被玩家视距钳制（≤ 32），因此这里只是拦住「手滑多打一位数」的配置值；
   * 扫描量随半径平方增长（O(r²)）的顾虑由「钳到真实视距」本身化解。
   */
  public static final int PROXIMITY_DISTANCE_CHUNKS_MAX = 32;

  /** {@code proximity.view-change.budget-ms} 的下限（毫秒）：低于它几乎判不完一个候选，等于功能失效。 */
  public static final double VIEW_CHANGE_BUDGET_MS_FLOOR = 0.05D;
  /**
   * {@code proximity.view-change.budget-ms} 的默认值（毫秒）：每玩家每 tick 的硬耗时预算。
   *
   * <p><b>为什么是 0.3</b>：实机数据（0.15 ms 默认时）视野增量巡检「预算耗尽」占 17%（751/4338），
   * 平均耗时 0.09 ms、最大 0.5 ms——密集矿区里取候选+判定会先撞预算，约 1/6 的转身/移动触发只能
   * 部分补显形。抬到 0.3 ms 后按 10 次/秒/玩家上限估算最坏 3 ms/s/玩家（50 人约 15% 单核，实际触发
   * 远低于上限），用可接受的峰值换掉「转身时仍看到假方块」的体验缺口。
   */
  public static final double VIEW_CHANGE_BUDGET_MS_DEFAULT = 0.3D;
  /** {@code proximity.view-change.budget-ms} 的上限（毫秒）：再大就不是「补漏」而是替代周期巡检了。 */
  public static final double VIEW_CHANGE_BUDGET_MS_MAX = 2.0D;

  /** {@code proximity.view-change.cooldown-ticks} 的下限（tick）：0 会让旋转抖动把预算打满。 */
  public static final int VIEW_CHANGE_COOLDOWN_TICKS_FLOOR = 1;
  /** {@code proximity.view-change.cooldown-ticks} 的默认值（tick）：2 tick = 100 ms 的最坏补发延迟。 */
  public static final int VIEW_CHANGE_COOLDOWN_TICKS_DEFAULT = 2;
  /** {@code proximity.view-change.cooldown-ticks} 的上限（tick）。 */
  public static final int VIEW_CHANGE_COOLDOWN_TICKS_MAX = 20;

  /** {@code proximity.view-change.radius} 的下限（格）：小于它不如直接等周期巡检。 */
  public static final int VIEW_CHANGE_RADIUS_FLOOR = 8;
  /** {@code proximity.view-change.radius} 的默认值（格）：近场 1.5 个区块，覆盖「贴脸看到」的范围。 */
  public static final int VIEW_CHANGE_RADIUS_DEFAULT = 24;
  /** {@code proximity.view-change.radius} 的上限（格）：再大会把取候选本身也做成开销。 */
  public static final int VIEW_CHANGE_RADIUS_MAX = 64;

  /**
   * {@code proximity.raycast.samples}（每方块最多尝试的候选点数）的有效上限。
   *
   * <p>候选点只取在方块<b>暴露面</b>上、按命中概率排序：面中心 + 最近点 + 至多 3 个面角，<b>至多 5 个</b>
   * （见 {@code ProximitySelector#MAX_SAMPLE_POINTS}）。配 6~8 不会多试出第 6 个点，属静默失效，
   * 因此钳制到 5；默认即取上限 5（多试一个面角，减少「只露一角的矿」漏显形）。
   */
  public static final int PROXIMITY_RAY_SAMPLES_MAX = 5;

  /**
   * {@code proximity.instant-reveal.radius}（事件驱动即时显形的邻域半径）的有效上限。
   *
   * <p>该半径决定「一个方块变更要连带检查周围多少格」——判定量随半径的三次方增长
   * （半径 8 时每个变更坐标要查 17³ 量级的邻域）。默认 4，钳制区间 1..8（与立即放行半径同量级）。
   */
  public static final int PROXIMITY_INSTANT_RADIUS_MAX = 8;

  /**
   * {@code neighbors.cache-maximum-size}（邻块贴边快照缓存条目上限）的安全上限。
   *
   * <p>快照按位打包（每格 1 bit），约 3 KB/条；16384 条约 48 MB。再大只放大内存占用而无精度收益。
   */
  public static final int NEIGHBORS_CACHE_MAXIMUM_MAX = 16384;

  /**
   * {@code cache.maximum-size}（改写结果内存缓存条目上限）的安全上限。
   *
   * <p>每条约占 20~25 KB，65536 条饱和时约 1.3~1.6 GB。默认 40960 已接近常见堆预算，
   * 上限用于防止手滑多打一位数把内存打满（缓存条目只在遇到新区块时增长）。
   */
  public static final int CACHE_MAXIMUM_SIZE_MAX = 65536;

  /**
   * {@code cache.maximum-mb}（改写结果内存缓存的<b>按 MB</b>硬上限）的默认值：512 MB。
   *
   * <p><b>为什么在「条目上限」之外还要一个字节上限</b>：条目上限只管「最多多少条」，管不住
   * 「单条负载偏大」——同一个区块，普通地形约 20~25 KB，但直接调色板 / 高熵地形可以到几百 KB，
   * 于是「条数没超、内存却超」完全可能发生。条目上限因此只是近似约束，真正与堆占用挂钩的是字节数。
   *
   * <p>默认 512 MB 对应约 2 万条「普通体积」条目，与 {@code cache.maximum-size} 的默认 40960
   * 同量级但更保守：先撞到的通常是字节上限，管理员据此调小即可精确控制这块内存。
   */
  public static final int CACHE_MAXIMUM_MB_DEFAULT = 512;

  /**
   * {@code cache.maximum-mb} 的<b>安全下限</b>（64 MB）。
   *
   * <p>低于它连「一个视距内的改写结果」都装不下（2048 条普通体积条目 ≈ 50 MB），缓存等于关闭，
   * 反而让每个区块都重新改写——与「调小以省内存」的初衷相反。因此低于下限会被抬升并留痕。
   */
  public static final int CACHE_MAXIMUM_MB_MIN = 64;

  /**
   * {@code cache.maximum-mb} 的<b>安全上限</b>（4096 MB ≈ 4 GB）。
   *
   * <p>条目上限 {@link #CACHE_MAXIMUM_SIZE_MAX}（65536）在不改它的情况下，按 20~25 KB/条约合
   * 1.3~1.6 GB；本上限留出足够余量，只拦「手滑多打一位数」（如 53687091200）把堆一次吃光。
   */
  public static final int CACHE_MAXIMUM_MB_MAX = 4096;

  /**
   * {@code advanced.threads}（反矿透改写工作线程数）的安全上限。
   *
   * <p>每个 worker 线程各持一份按线程复用的编解码 scratch（调色板反查表 + 位打包数组 + 输出缓冲，
   * 可达 MB 级常驻），线程数直接决定这部分常驻内存与内核调度开销。默认 0 表示「按 CPU 自动」（上限 4），
   * 正常配置远低于本上限；上限只为拦住手滑多打一位数（如 100000）造成的线程风暴。
   */
  public static final int ADVANCED_THREADS_MAX = 64;

  /**
   * {@code advanced.queue-capacity}（改写任务队列容量）的安全上限。
   *
   * <p><b>队列里钉着封包</b>：每个排队项持有一个 {@code RewriteTask} 与其对应的区块封包（单个区块
   * 未压缩负载通常几十~几百 KB），因此队列容量直接等于「积压时最多钉住多少内存」。
   * 默认 2048 已足够吸收突发；上限用于拦住手滑多打一位数（如 10000000）导致的巨数组分配与内存打满。
   */
  public static final int ADVANCED_QUEUE_CAPACITY_MAX = 8192;

  /**
   * {@code disk-cache.max-entries}（磁盘缓存条目上限）的安全上限。
   *
   * <p>条目上限直接决定磁盘占用与启动期扫描量（默认 20000 条约 200~600 MB）。上限只为拦住手滑多打
   * 一位数（如 200000000）造成的磁盘打满与超长启动扫描，取值远高于正常调优范围。
   */
  public static final int DISK_CACHE_MAX_ENTRIES_MAX = 2_000_000;

  /**
   * {@code disk-cache.max-file-size-mb}（单个区域文件大小上限）的安全上限。
   *
   * <p>压缩回收会把整个区域文件读进内存重写，单文件越大，这一瞬间的内存尖峰越高。
   * 上限 1024 MB（1 GB）远超默认 16 MB 与正常调优范围，只为拦住手滑多打一位数。
   */
  public static final int DISK_CACHE_MAX_FILE_SIZE_MB_MAX = 1024;

  /**
   * {@code disk-cache.max-region-files}（磁盘上保留的区域文件数上限）的<b>默认值</b>：4096。
   *
   * <p>一个区域文件覆盖 32×32 = 1024 个区块，4096 个文件约合 400 万个区块的改写结果；而默认
   * {@code max-entries} 只有 20000 条，正常运营下先撞到的总是条目上限——本项只是兜住「条目一直没到顶、
   * 文件却随世界数量持续增长」的情形，避免磁盘被无止境地铺满。
   */
  public static final int DISK_CACHE_MAX_REGION_FILES_DEFAULT = 4096;

  /**
   * {@code disk-cache.max-region-files} 的<b>安全上限</b>：65536。
   *
   * <p>超出上限时按「最久未访问」淘汰，维护期要遍历这些区域文件；65536 个文件约合 6700 万个区块，
   * 已远超任何正常服务器的缓存规模。上限只为拦住手滑多打一位数（如 409600）造成的超长淘汰扫描。
   */
  public static final int DISK_CACHE_MAX_REGION_FILES_MAX = 65_536;

  /**
   * {@code disk-cache.bucket-cache-size}（每个区域文件常驻内存的槽位记录数）的安全上限。
   *
   * <p>槽位粒度记录（{@code BufferedLinearV3Format.BUCKET_SHIFT = 0}）下「1 条记录 = 1 个区块」，
   * 故本值即「每个已打开的区域文件最多把多少个已载入的区块数据留在内存里」。文件中最多有
   * {@code BUCKET_COUNT}（1024）条记录，理论上限是 1024；这里仍取 64，理由是<b>内存</b>而非收益：
   * 一条记录解压后可达数十~上百 KB（最坏 16 MiB），64 条即约 6 MB/文件，与旧布局默认 8 桶
   * （8 × 64 槽，量级相当）持平；再往上抬会让「多文件同时打开」时的驻留内存线性放大。
   */
  public static final int DISK_CACHE_BUCKET_CACHE_SIZE_MAX = 64;

  /**
   * {@code disk-cache.maintenance-interval-seconds}（维护周期）的安全上限。
   *
   * <p>该周期同时驱动「脏 bucket 落盘」「压缩回收」与「关闭闲置文件句柄」。配得过大（如 86400）
   * 会让这三件事事实上停摆：脏数据长期只在内存、文件句柄不再关闭。上限 3600 秒（1 小时）
   * 已足够宽松，只拦住「误把秒当毫秒」之类的量级错误。
   */
  public static final int DISK_CACHE_MAINTENANCE_INTERVAL_MAX_SECONDS = 3600;

  /**
   * {@code disk-cache.zstd.timeout-seconds}（自动下载 zstd 的超时）的安全上限。
   *
   * <p><b>为什么必须有上限</b>：该超时用于 {@code ZstdSupport.initialize}，而后者在
   * {@code onEnable} 的<b>主线程</b>上同步等待下载完成——配得过大（如 3600）会在网络黑洞时把
   * 开服卡住任意久。上限 120 秒既允许慢速镜像完成下载，又保证开服不会被无限期拖住。
   */
  public static final int DISK_CACHE_ZSTD_TIMEOUT_MAX_SECONDS = 120;

  /**
   * {@code proximity.max-positions}（伪装坐标总量安全阀）的安全上限。
   *
   * <p>这是「正常运营绝不该触发」的安全阀；每个坐标在索引里约占 4 字节 + 容器开销。
   * 上限 33554432（约 3200 万）对应约 128 MB 量级的索引占用，只为拦住手滑多打一位数。
   */
  public static final int PROXIMITY_MAX_POSITIONS_MAX = 33_554_432;

  /**
   * {@code proximity.max-positions-per-player}（单玩家已显形坐标安全阀）的安全上限。
   *
   * <p>同样只在异常态触发。上限 4194304（约 400 万）对应单玩家约 16 MB 量级，拦住误配大数。
   */
  public static final int PROXIMITY_MAX_POSITIONS_PER_PLAYER_MAX = 4_194_304;

  /*
   * 以下为「只有下限、原先没有上限」的资源型键补齐的安全上限：它们都直接决定单次/单周期的
   * 工作量或常驻内存，手滑多打一位数即可造成每 tick 巨额发包、队列风暴或内存暴涨。
   * 取值原则与其它 MAX 常量一致——正常调优撞不到，只拦失控配置。
   */

  /** {@code proximity.interval-ticks} 上限（tick）：200 tick = 10 秒，再长等于「几乎不做邻近显形」。 */
  public static final int PROXIMITY_INTERVAL_TICKS_MAX = 200;
  /** {@code proximity.max-reveals-per-tick} 上限：单次巡检的发包上限，防一次巡检打出包风暴。 */
  public static final int PROXIMITY_MAX_REVEALS_PER_TICK_MAX = 100_000;
  /** {@code proximity.expire-seconds} 上限（秒，1 天）：再长等于条目永不自然过期。 */
  public static final int PROXIMITY_EXPIRE_SECONDS_MAX = 86_400;
  /** {@code proximity.instant-reveal.max-per-tick} 上限：事件即时显形的每 tick 限额。 */
  public static final int PROXIMITY_INSTANT_MAX_PER_TICK_MAX = 100_000;
  /** {@code proximity.over-reveal-sampling} 上限（抽样分母）：只影响诊断计数，防溢出即可。 */
  public static final int PROXIMITY_OVER_REVEAL_SAMPLING_MAX = 1_000_000;
  /** {@code disk-cache.idle-close-seconds} 上限（秒，1 天）：再长等于区域文件句柄永不回收。 */
  public static final int DISK_CACHE_IDLE_CLOSE_SECONDS_MAX = 86_400;
  /** {@code disk-cache.compact-per-pass} 上限：单次维护最多压缩回收的区域文件数（全量重写，代价高）。 */
  public static final int DISK_CACHE_COMPACT_PER_PASS_MAX = 4_096;
  /** {@code disk-cache.queue-capacity} 上限：每个排队项都钉着一份区块负载。 */
  public static final int DISK_CACHE_QUEUE_CAPACITY_MAX = 65_536;
  /** {@code cache.expire-after-access-seconds} 上限（秒，1 天）：再长等于改写缓存条目永不淘汰。 */
  public static final int CACHE_EXPIRE_AFTER_ACCESS_SECONDS_MAX = 86_400;
  /**
   * {@code advanced.timeout-millis} 的<b>有效下限</b>（毫秒，100）。
   *
   * <p>解析侧显式把配置值钳到该下限（见 {@code from()} 里的 clampCeiling）——注意这不是新语义：
   * 构造器的 {@code Math.max(100, timeoutMillis)} 一直把 <100 的取值按 100 生效，只是过去<b>不留痕</b>、
   * 管理员看不到配置已被抬高。现在改为在解析期钳制并计入 {@code floorAdjustments}，加载时一次性提示。
   */
  public static final int ADVANCED_TIMEOUT_MILLIS_MIN = 100;

  /** {@code advanced.timeout-millis} 上限（毫秒，60 秒）：改写超时，配大等于放弃「超时放行」兜底。 */
  public static final int ADVANCED_TIMEOUT_MILLIS_MAX = 60_000;

  /**
   * 邻近显形：玩家靠近曾被伪装的坐标时，主动把该坐标的真实方块发回客户端。
   *
   * @param distanceChunks        显形半径（<b>区块 = 视距单位</b>）；0（默认）表示跟随服务器视距
   *                              （{@code server.properties} 的 view-distance，world 级覆盖时取该世界值），
   *                              正数表示最多向外几个区块。运行时一律再与「该玩家的有效视距」
   *                              （服务器视距与客户端自报视距取小）取小值，因此不会给客户端还没加载的
   *                              区块发显形包；配置超过 {@link #PROXIMITY_DISTANCE_CHUNKS_MAX} 会被钳制并 WARN
   * @param intervalTicks         巡检周期（tick）
   * @param maxRevealsPerTick     单次巡检的发包上限（普通服务端为全服合计，Folia 为每玩家）
   * @param expireSeconds         显形索引条目的过期秒数（区块卸载未触发时的兜底）
   * @param maxPositions          伪装区块索引的坐标总量<b>安全阀</b>（正常运营下不应触发）
   * @param maxPositionsPerPlayer 单玩家已显形坐标的<b>安全阀</b>（正常运营下不应触发）
   * @param frustumEnabled        是否只显形玩家视野锥内的候选坐标
   * @param frustumFov            视野锥<b>竖直全张开角</b>（度）——与 Minecraft 客户端 FOV 设置同义；
   *                              水平方向按 21:9 宽高比换算后更宽（约 2.3 倍），
   *                              详见 {@code ProximitySelector#withinFrustum}；
   *                              默认 {@link #FRUSTUM_FOV_DEFAULT}，
   *                              <b>不低于 {@link #FRUSTUM_FOV_FLOOR}</b>（低于下限会被抬升并 WARN）
   * @param frustumMinDistance    该距离内的候选豁免视锥判定；默认 {@link #FRUSTUM_MIN_DISTANCE_DEFAULT}，
   *                              <b>不低于 {@link #FRUSTUM_MIN_DISTANCE_FLOOR}</b>（低于下限会被抬升并 WARN）
   * @param raycastEnabled        是否做射线可见性判定（被墙挡住的不显形；<b>默认开启</b>）
   * @param raycastSamples        <b>每方块最多尝试的候选点数</b>；
   *                              钳制 1..{@link #PROXIMITY_RAY_SAMPLES_MAX}（默认即上限 5）
   * @param instantReveal         事件驱动即时显形（周期巡检的补充，见 {@link InstantReveal}）
   * @param overRevealSampling    过度显形抽样统计的抽样率分母 N（1/N 抽样，只计数不改行为；
   *                              0 表示关闭）
   * @param batchRevealSends      是否把同一个周期内的显形合并成 Paper 原生多方块变更包
   *                              （{@code Player#sendMultiBlockChange}）发出；关闭时逐坐标
   *                              用 {@code Player#sendBlockChange} 发出（默认开启，供 A/B 与回退用）
   * @param revealOnChunkSend     区块包下发后立即把「该区块中当前可见」的伪装坐标显形（默认开启）。
   *                              目的是消掉「区块先以伪装状态下发、等巡检才还原」造成的矿石/箱子凭空出现；
   *                              显形包紧随区块包发出，客户端通常在同一 tick 内一并应用。
   * @param preRevealOnDig        玩家开始挖某个方块时，预先显形其 6 邻中「挖开后会看见」的伪装坐标
   *                              （默认开启）。客户端对挖掘有本地预测，等方块破坏后再显形必然晚一个 RTT；
   *                              改为开掘即显形，正常挖掘速度下显形包先于方块破坏到达，玩家看不到假方块。
   * @param viewChange            视野变化增量巡检（移动/转向触发，见 {@link ViewChange}）：把周期巡检
   *                              分片轮转最坏 0.8 秒的补发窗口压到 ≤ 冷却+1 tick
   */
  public record Proximity(boolean enabled, int distanceChunks, int intervalTicks, int maxRevealsPerTick,
      int expireSeconds, int maxPositions, int maxPositionsPerPlayer,
      boolean frustumEnabled, double frustumFov, double frustumMinDistance,
      boolean raycastEnabled, int raycastSamples,
      InstantReveal instantReveal, int overRevealSampling, boolean batchRevealSends,
      boolean revealOnChunkSend, boolean preRevealOnDig, ViewChange viewChange) {
  }

  /**
   * 事件驱动即时显形：观测到玩家身边（曼哈顿距离 ≤ {@code radius}）的出站方块变更时，
   * <b>当 tick</b>（不经周期巡检）把变更邻域内「已伪装且视线可见」的坐标显形。
   *
   * @param enabled    总开关（默认开启）
   * @param radius     曼哈顿半径（格），变更必须落在玩家身边该半径内才触发（默认 4）
   * @param maxPerTick 每玩家每 tick 事件显形限额（防爆刷；默认 16，0 表示关闭事件显形）
   */
  public record InstantReveal(boolean enabled, int radius, int maxPerTick) {
  }

  /**
   * 视野变化增量巡检：玩家移动/转向超过阈值时，在<b>当 tick</b> 对近场做一次受限增量扫描，
   * 把「刚进入视野」的伪装坐标补发下去（周期巡检的分片轮转最坏要 4 个周期 ≈ 0.8 秒才轮到）。
   *
   * <p><b>为什么需要它</b>：周期巡检按分片轮转，玩家「转身/绕过拐角」看到的新区域可能要等 0.8 秒
   * 才被还原——这期间看到的是合理的假方块（石头），虽不致泄漏，但会有「矿凭空出现」的观感。
   *
   * <p><b>成本纪律</b>：这条路径由玩家行为驱动，必须硬性封顶：每玩家每 tick 时间预算
   * （{@code budget-ms}）+ 冷却（{@code cooldown-ticks}）+ 单次候选上限（代码常量 32）+
   * 每 tick 发包上限（代码常量 16）。超预算即停，剩余候选交给周期巡检兜底。
   *
   * @param enabled       总开关（默认开启）
   * @param budgetMs      每玩家每 tick 的耗时预算（毫秒）；低于 {@link #VIEW_CHANGE_BUDGET_MS_FLOOR}
   *                      抬升到下限、高于 {@link #VIEW_CHANGE_BUDGET_MS_MAX} 钳到上限，均留痕
   * @param cooldownTicks 同一玩家两次增量巡检的最小间隔（tick）；钳制 1..{@link #VIEW_CHANGE_COOLDOWN_TICKS_MAX}
   * @param radius        扫描半径（格）；钳制 {@link #VIEW_CHANGE_RADIUS_FLOOR}..{@link #VIEW_CHANGE_RADIUS_MAX}
   */
  public record ViewChange(boolean enabled, double budgetMs, int cooldownTicks, int radius) {
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
   * @param zstdAutoDownload           zstd 前置缺失时是否自动下载（默认 true）
   * @param zstdDownloadUrl            zstd 下载源根地址（默认 Maven Central；可换阿里云镜像）
   * @param zstdTimeoutSeconds         zstd 下载的总等待上限（秒；超时即失败并回退，默认 10）
   * @param zstdSha256                 自动下载文件的期望 SHA-256（十六进制，已归一为小写）；
   *                                   {@code ""} = 不校验（默认），非空则下载后校验，不匹配即拒绝使用该 jar
   * @param maxRegionFiles             磁盘上最多保留多少个区域文件（超出按最久未访问淘汰，默认
   *                                   {@link AntiXrayConfig#DISK_CACHE_MAX_REGION_FILES_DEFAULT}，
   *                                   钳制 16..{@link AntiXrayConfig#DISK_CACHE_MAX_REGION_FILES_MAX}）；
   *                                   与 {@code maxEntries} 一起界定磁盘占用
   */
  public record DiskCache(boolean enabled, int maxEntries, int maxFileSizeMb, int expireSeconds,
      int bucketCacheSize, int idleCloseSeconds, int maintenanceIntervalSeconds, int compactPerPass,
      int queueCapacity, boolean zstdAutoDownload, String zstdDownloadUrl,
      int zstdTimeoutSeconds, String zstdSha256, int maxRegionFiles) {

    /**
     * 不含区域文件上限的构造：按 {@link AntiXrayConfig#DISK_CACHE_MAX_REGION_FILES_DEFAULT} 补齐。
     *
     * <p><b>为什么保留</b>：区域文件上限是后加的限额，既有调用点（测试夹具、以及只关心其余键的装配代码）
     * 仍按原来的 13 个参数构造；没有它，每加一个磁盘限额键都得把所有调用点改一遍。
     */
    public DiskCache(boolean enabled, int maxEntries, int maxFileSizeMb, int expireSeconds,
        int bucketCacheSize, int idleCloseSeconds, int maintenanceIntervalSeconds,
        int compactPerPass, int queueCapacity, boolean zstdAutoDownload, String zstdDownloadUrl,
        int zstdTimeoutSeconds, String zstdSha256) {
      this(enabled, maxEntries, maxFileSizeMb, expireSeconds, bucketCacheSize, idleCloseSeconds,
          maintenanceIntervalSeconds, compactPerPass, queueCapacity, zstdAutoDownload,
          zstdDownloadUrl, zstdTimeoutSeconds, zstdSha256, DISK_CACHE_MAX_REGION_FILES_DEFAULT);
    }
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
   * @param enabled            覆盖的反矿透开关；null = 沿用所属维度（{@code dimensions.<维度>.enabled}）。
   *                           非 null 时优先于维度开关，但世界黑名单仍然最高优先（名单内世界一律不启用）
   * @param hideBlocks         覆盖的隐藏方块清单；null = 回落
   * @param replacementWeights 覆盖的伪装权重表；null = 回落
   * @param replacementBands   覆盖的按 Y 分区伪装表；null = 回落
   * @param minY               覆盖的高度下界；null = 回落
   * @param maxY               覆盖的高度上界；null = 回落
   * @param mode               覆盖的伪装模式；null = 回落
   * @param useBlockBelow      覆盖的 use-block-below；null = 回落
   */
  public record WorldOverride(String pattern, Pattern glob, Boolean enabled,
      List<String> hideBlocks, Map<String, Integer> replacementWeights,
      List<ReplacementBand> replacementBands, Integer minY, Integer maxY,
      ObfuscationMode mode, Boolean useBlockBelow) {

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

    /**
     * 参与<b>配置指纹</b>的稳定表示（绝不能用本记录自身的 {@code hashCode}）。
     *
     * <p><b>为什么必须单独给一份</b>：记录默认的 {@code hashCode} 会把 {@code mode}（枚举）按 identity hash
     * 计入，而 identity hash 每个 JVM 进程随机 → 同一份 antixray.yml 在两次启动上算出<b>不同</b>的
     * configHash，磁盘缓存条目会被逐条判为「配置已变」而丢弃，重启后命中率恒为 0
     * （真机实测：同一配置两次启动 configHash 分别为 335527235 与 -1449763053）。
     * 这里改用 {@code mode.name()}（{@link String#hashCode} 有规范定义，跨进程/跨版本稳定），
     * 其余分量（{@code List} / {@code int} / {@code boolean}）的哈希也都有规范定义。
     *
     * <p><b>权重表必须按有序 entry 列表参与</b>（不再直接用 {@code Map}）：{@code Map} 的哈希与迭代序
     * 无关，而伪装分布依赖迭代序（见 {@link #weightEntries}），仅调换书写顺序就会改变结果——用
     * {@code Map} 会让这种情况下的旧磁盘缓存被错误复用。此次改动会使现存磁盘缓存<b>一次性失效</b>
     * （属正常：指纹变了，旧条目判为「配置已变」而被回收）。
     */
    public List<Object> fingerprint() {
      return List.of(hideBlocks, weightEntries(replacementWeights),
          bandFingerprint(replacementBands), minY, maxY, mode.name(), useBlockBelow);
    }

    /**
     * 权重表的有序稳定表示：{@code Map} 的 {@code hashCode} 与迭代顺序无关，而
     * {@code ObfuscationProcessor.weightedTable} 是按<b>迭代序</b>累加权重决定伪装方块分布的——
     * 仅调换 yml 里权重的书写顺序就会改变伪装结果。因此这里把权重表按 entry <b>有序列表</b>参与指纹
     * （{@code List} 的哈希对顺序敏感，键/值的哈希也都有规范定义），否则旧磁盘缓存会被错误复用。
     */
    private static List<List<Object>> weightEntries(Map<String, Integer> weights) {
      List<List<Object>> entries = new ArrayList<>(weights.size());
      for (Map.Entry<String, Integer> entry : weights.entrySet()) {
        entries.add(List.of(entry.getKey(), entry.getValue()));
      }
      return List.copyOf(entries);
    }

    /** 分区伪装表同样含权重映射：逐段拆成有序 entry 列表参与指纹（理由同 {@link #weightEntries}）。 */
    private static List<Object> bandFingerprint(List<ReplacementBand> bands) {
      List<Object> result = new ArrayList<>(bands.size());
      for (ReplacementBand band : bands) {
        result.add(List.of(band.minY(), band.maxY(), weightEntries(band.weights())));
      }
      return List.copyOf(result);
    }
  }

  /** {@link #overrideEnabledCache} 的条目上限（与 {@code ObfuscationProcessor} 的世界档案缓存同一量级）。 */
  private static final int OVERRIDE_ENABLED_CACHE_MAX = 4096;

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
   * 加载时由 {@link #warnIfConfigAdjusted} 一次性 WARN。
   */
  private final List<String> floorAdjustments;
  /**
   * 被安全上限（{@link #PROXIMITY_DISTANCE_CHUNKS_MAX} / {@link #NEIGHBORS_CACHE_MAXIMUM_MAX} /
   * {@link #CACHE_MAXIMUM_SIZE_MAX}）回落过的配置键明细；空列表表示未回落。
   * 加载时由 {@link #warnIfConfigAdjusted} 一次性 WARN。
   */
  private final List<String> ceilingAdjustments;
  private final boolean layerObfuscation;
  private final boolean removeBlockEntities;
  private final Neighbors neighbors;
  private final Occlusion occlusion;
  private final Proximity proximity;
  private final DiskCache diskCache;
  private final PlatformSupport.Mode platform;
  private final int cacheMaximumSize;
  /** 改写结果内存缓存的硬上限，单位 <b>MB</b>（与条目上限同时生效，谁先到顶以谁为准）。 */
  private final int cacheMaximumMb;
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
  /**
   * 高度键（{@code min-y} / {@code max-y}）或覆盖开关类型不符、已按安全默认值处理时的明细；空列表 = 无。
   * 解析侧不持日志，加载时由 {@link #warnIfConfigAdjusted} 一次性 WARN——绝不静默吞掉写法错误。
   */
  private final List<String> typeAdjustments;
  /** 是否有覆盖段显式写了 {@code enabled}；没有时 {@link #antiXrayAppliesTo} 走 O(1) 快路径，不做任何匹配。 */
  private final boolean hasEnabledOverride;
  /**
   * 世界名 → 覆盖段 enabled 的判定缓存（仅在 {@link #hasEnabledOverride} 为 true 时使用）。
   *
   * <p>封包入口按世界名查询，属于热路径；键是任意世界名，故按 {@link #OVERRIDE_ENABLED_CACHE_MAX}
   * 整体清空而不做淘汰算法（与 {@code ObfuscationProcessor} 的覆盖档案缓存同一套做法）。
   */
  private final ConcurrentHashMap<String, OverrideEnabled> overrideEnabledCache =
      new ConcurrentHashMap<>();

  private AntiXrayConfig(boolean enabled, EffectiveObfuscation[] dimensionEffectives,
      boolean[] dimensionEnabled, boolean dimensionsMissing, boolean layerObfuscation,
      boolean removeBlockEntities, Neighbors neighbors, Occlusion occlusion, Proximity proximity,
      DiskCache diskCache, PlatformSupport.Mode platform, int cacheMaximumSize,
      int cacheMaximumMb, int cacheExpireAfterAccessSeconds, int threads, int timeoutMillis,
      int queueCapacity,
      Set<String> unresolvedTags, List<WorldOverride> worldOverrides,
      List<String> floorAdjustments, List<String> ceilingAdjustments,
      List<String> typeAdjustments) {
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
    // 解析侧已按 [CACHE_MAXIMUM_MB_MIN, CACHE_MAXIMUM_MB_MAX] 钳制并留痕；这里只兜底非正数，
    // 避免「配置项缺省/被写成 0」时把上限变成 0 而让缓存永久不可写（与 cacheMaximumSize 同口径）。
    this.cacheMaximumMb = Math.max(1, cacheMaximumMb);
    this.cacheExpireAfterAccessSeconds = Math.max(1, cacheExpireAfterAccessSeconds);
    this.threads = Math.max(0, threads);
    this.timeoutMillis = Math.max(100, timeoutMillis);
    this.queueCapacity = Math.max(1, queueCapacity);
    this.unresolvedTags = unresolvedTags == null ? Set.of() : Set.copyOf(unresolvedTags);
    this.worldOverrides = List.copyOf(worldOverrides);
    // 被安全下限抬升过的键（空 = 未抬升）；加载时由 warnIfConfigAdjusted 一次性提示
    this.floorAdjustments = floorAdjustments == null ? List.of() : List.copyOf(floorAdjustments);
    // 被安全上限回落过的键（空 = 未回落）；同样由 warnIfConfigAdjusted 一次性提示
    this.ceilingAdjustments =
        ceilingAdjustments == null ? List.of() : List.copyOf(ceilingAdjustments);
    // 高度键/覆盖开关类型不符的明细（空 = 全部合法）；加载时由 warnIfConfigAdjusted 一次性提示
    this.typeAdjustments = typeAdjustments == null ? List.of() : List.copyOf(typeAdjustments);
    // 没有任何覆盖段写 enabled 时，运行期无需按世界名匹配（避免给每个区块封包增加无谓开销）
    boolean anyEnabledOverride = false;
    for (WorldOverride override : this.worldOverrides) {
      if (override.enabled() != null) {
        anyEnabledOverride = true;
        break;
      }
    }
    this.hasEnabledOverride = anyEnabledOverride;
    // 预计算各覆盖段在「每个维度」上的生效视图：合并只在构造期做一次，运行期命中后直接取用（O(1)）。
    EffectiveObfuscation[][] merged = new EffectiveObfuscation[this.worldOverrides.size()][Dimension.values().length];
    for (int i = 0; i < this.worldOverrides.size(); i++) {
      WorldOverride override = this.worldOverrides.get(i);
      for (Dimension dimension : Dimension.values()) {
        merged[i][dimension.ordinal()] = merge(override,
            this.dimensionEffectives[dimension.ordinal()], effectiveEnabled(override, dimension));
      }
    }
    this.overrideEffectives = merged;
    // 影响改写结果的全部配置项都参与哈希（含遮挡覆盖表与各维度生效值）；顺序敏感，故用有序列表。
    // 伪装模式必须参与：否则「enclosed 写出的缓存」会在切换到 all 后被复用，导致裸露矿泄漏。
    // use-block-below / 高度范围 / 分区表 / 维度启用 都影响改写结果，一并纳入指纹。
    // ⚠ 所有分量都必须是「哈希有规范定义」的稳定表示（字符串/数字/列表/映射/枚举名），
    //   绝不能让枚举的 identity hash 混进来——那会让指纹随进程变化，磁盘缓存重启后永不命中
    //   （见 EffectiveObfuscation#fingerprint 的说明）。
    List<Object> dimensionFingerprints = new ArrayList<>(Dimension.values().length);
    for (Dimension dimension : Dimension.values()) {
      dimensionFingerprints.add(List.of(dimension.key(), this.dimensionEnabled[dimension.ordinal()],
          this.dimensionEffectives[dimension.ordinal()].fingerprint()));
    }
    List<Object> overrideFingerprints = new ArrayList<>(this.worldOverrides.size());
    for (int i = 0; i < this.worldOverrides.size(); i++) {
      List<Object> perDimension = new ArrayList<>(Dimension.values().length);
      for (Dimension dimension : Dimension.values()) {
        perDimension.add(this.overrideEffectives[i][dimension.ordinal()].fingerprint());
      }
      overrideFingerprints.add(List.of(this.worldOverrides.get(i).pattern(), perDimension));
    }
    this.configHash = Objects.hash(dimensionFingerprints, overrideFingerprints, layerObfuscation,
        neighbors.enabled(), neighbors.missingPolicy().name(),
        OcclusionRules.sortedList(this.occlusion.extraOccluding()),
        OcclusionRules.sortedList(this.occlusion.extraNonOccluding()),
        // remove-block-entities 直接改变「写进缓存的区块负载字节」（是否从区块封包剔除方块实体数据），
        // 必须参与指纹：否则开↔关切换后旧缓存仍被命中（即使重启也不生效，管理员只能手动清缓存）。
        // 纳入后现存磁盘缓存会一次性失效重建（属预期）。
        this.removeBlockEntities,
        // zstd 下载校验哈希参与指纹（按任务要求）：切换它会让现存磁盘缓存一次性失效重建（属正常）。
        this.diskCache.zstdSha256());
  }

  /**
   * 把某覆盖段合并到某维度的生效值上（覆盖值优先，未覆盖回落维度值）。
   *
   * <p>{@code enabled} 是该覆盖段在此维度上的最终启用值（非 null 的覆盖值优先，否则沿用维度开关，
   * 见 {@link #effectiveEnabled}）。为 false 时返回<b>空视图</b>：没有目标方块，{@code WorldProfile}
   * 因而不具备生效条件，该世界的改写、邻块抓取与显形登记会自动跳过——与 {@code dimensions.<维度>}
   * 的 {@code enabled: false} 走同一条既有通道，不需要在改写链路里再加分支。
   */
  private static EffectiveObfuscation merge(WorldOverride override, EffectiveObfuscation base,
      boolean enabled) {
    if (!enabled) {
      return new EffectiveObfuscation(List.of(), Map.of(), List.of(), Integer.MIN_VALUE,
          Integer.MAX_VALUE, base.mode(), base.useBlockBelow());
    }
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

  /**
   * 覆盖段在某维度上的启用值：非 null 的覆盖值优先于维度开关，null = 沿用该维度。
   *
   * <p>黑名单不在这里判：它是运行期可变的独立配置（{@code blacklist.yml}），统一在
   * {@link #enabledForWorld} / {@link #antiXrayAppliesTo} 的入口处判定，优先级最高。
   */
  private boolean effectiveEnabled(WorldOverride override, Dimension dimension) {
    return override.enabled() == null ? dimensionEnabled[dimension.ordinal()] : override.enabled();
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

  /**
   * zstd 下载文件的期望 SHA-256：缺失/空白 → {@code ""}（不校验）。
   *
   * <p>归一为小写：SHA-256 的十六进制摘要大小写不敏感（比较用 {@code equalsIgnoreCase}），
   * 统一小写可避免「仅大小写不同」的两份配置算出不同指纹（指纹要求「哈希有规范定义」，见构造期说明）。
   */
  private static String zstdSha256(ConfigurationSection root) {
    String hash = root.getString("disk-cache.zstd-sha256", "");
    return hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
  }

  /** 从配置根节点解析。 */
  public static AntiXrayConfig from(ConfigurationSection root) {
    Set<String> unknownTags = new LinkedHashSet<>();
    // 高度键（min-y/max-y）与覆盖开关类型不符时的明细：解析侧不持日志，统一交给加载路径一次性 WARN
    List<String> typeAdjustments = new ArrayList<>(2);

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
      // 维度开关同样走宽松解析：enabled: "false" 被 getBoolean 当成默认值 true，会静默反转「关闭」意图
      dimensionEnabled[dimension.ordinal()] = lenientBoolean(section, "enabled",
          Boolean.TRUE.equals(DEFAULT_ENABLED.get(dimension)), typeAdjustments,
          "dimensions." + dimension.key() + ".enabled");
      effectives[dimension.ordinal()] = parseDimensionSection(section, fallback, unknownTags,
          typeAdjustments, "dimensions." + dimension.key());
    }

    // ---- world-overrides：最高优先级（按世界名；默认空）----
    List<WorldOverride> overrides =
        parseWorldOverrides(root.getConfigurationSection("world-overrides"), unknownTags,
            typeAdjustments);

    // ---- 安全下限：被抬升的键记进 floorAdjustments，加载时一次性 WARN（解析侧不持日志）----
    List<String> floorAdjustments = new ArrayList<>(3);
    // ---- 安全上限：被回落的键记进 ceilingAdjustments，同样由加载时一次性 WARN 显式告知 ----
    List<String> ceilingAdjustments = new ArrayList<>(4);

    return new AntiXrayConfig(
        // 布尔键统一走宽松解析：getBoolean 对 "false"（带引号）这类写法会返回默认值 true，
        // 于是「明确写下的关闭」被静默反转成「保持开启」。见 lenientBoolean(...) 说明。
        lenientBoolean(root, "enabled", true, typeAdjustments),
        effectives,
        dimensionEnabled,
        !anyDimensionSection,
        lenientBoolean(root, "obfuscation.layer-obfuscation", false, typeAdjustments),
        lenientBoolean(root, "obfuscation.remove-block-entities", true, typeAdjustments),
        new Neighbors(
            lenientBoolean(root, "neighbors.enabled", true, typeAdjustments),
            // 枚举类字符串键同样走宽松解析：非法值回落默认并留痕（见 missingPolicy 的说明）
            missingPolicy(root.get("neighbors.missing-policy"), typeAdjustments),
            // 默认 2048（原为 512）：快照已按位打包（每格 1 bit，主世界 384 高度约 3 KB/条），
            // 2048 条约 6 MB；512 条时缓存接近饱和、抓取次数随玩家移动抖动
            //（单次抓取实测约 0.776 ms，现按列高度上界裁剪后更少）。
            // 下限 1（否则缓存恒空、退化为每次重复抓取），上限见 NEIGHBORS_CACHE_MAXIMUM_MAX。
            clampCeiling(root.getInt("neighbors.cache-maximum-size", 2048), 1,
                NEIGHBORS_CACHE_MAXIMUM_MAX, "neighbors.cache-maximum-size", floorAdjustments,
                ceilingAdjustments)),
        new Occlusion(
            OcclusionRules.normalizeAll(root.getStringList("occlusion.extra-occluding")),
            OcclusionRules.normalizeAll(root.getStringList("occlusion.extra-non-occluding"))),
        new Proximity(
            lenientBoolean(root, "proximity.enabled", true, typeAdjustments),
            // 单位是「区块」（= 视距单位）。0（默认）= 跟随服务器 view-distance：
            // 显形包只在客户端已加载该区块时生效，超出视距的包会被客户端丢弃，因此半径没有理由越界。
            // 正数也一律与玩家有效视距取小（见 ProximityRevealer#effectiveRevealChunks）。
            proximityDistanceChunks(
                narrowInt(lenientLongValue(root.get("proximity.distance-chunks"),
                    PROXIMITY_DISTANCE_CHUNKS_DEFAULT, "proximity.distance-chunks", typeAdjustments)),
                floorAdjustments, ceilingAdjustments),
            // 默认 4（原为 5）：显形周期越短，「石头还没变回来」的窗口越小。
            clampCeiling(root.getInt("proximity.interval-ticks", 4), 1, PROXIMITY_INTERVAL_TICKS_MAX,
                "proximity.interval-ticks", floorAdjustments, ceilingAdjustments),
            // 默认 512（原 256）：配合按视距的显形半径与放宽的视锥，单周期通过筛选的坐标更多，
            // 单次额度也要相应放大，避免「近处的矿排队等到下一周期才还原」。
            clampCeiling(root.getInt("proximity.max-reveals-per-tick", 512), 1,
                PROXIMITY_MAX_REVEALS_PER_TICK_MAX, "proximity.max-reveals-per-tick",
                floorAdjustments, ceilingAdjustments),
            // 默认 300（原为 120）：登录/传送后区块一次性连续下发，玩家往往过一会儿才走到近处。
            clampCeiling(root.getInt("proximity.expire-seconds", 300), 1, PROXIMITY_EXPIRE_SECONDS_MAX,
                "proximity.expire-seconds", floorAdjustments, ceilingAdjustments),
            // 安全阀容量见 PROXIMITY_MAX_POSITIONS(_PER_PLAYER)_MAX：两个键都只在异常态触发，
            // 但同样需要上限，否则误配大数会让「安全阀」失效、内存失去上界。
            clampCeiling(root.getInt("proximity.max-positions", 4194304), 1,
                PROXIMITY_MAX_POSITIONS_MAX, "proximity.max-positions", floorAdjustments,
                ceilingAdjustments),
            clampCeiling(root.getInt("proximity.max-positions-per-player", 524288), 1,
                PROXIMITY_MAX_POSITIONS_PER_PLAYER_MAX, "proximity.max-positions-per-player",
                floorAdjustments, ceilingAdjustments),
            lenientBoolean(root, "proximity.frustum.enabled", true, typeAdjustments),
            // 视锥两键都有安全下限（见 FRUSTUM_FOV_FLOOR / FRUSTUM_MIN_DISTANCE_FLOOR）：
            // 配得比客户端可视范围更窄会让「玩家看得见的方块」保持伪装，因此低于下限时按下限生效，
            // 并把被抬升的键记进 floorAdjustments，由加载时一次性 WARN 显式告知（绝不静默改配置）。
            // 键缺失/非法值时回落的是「默认值」（不是下限）——默认值本身就在下限之上（120 / 24）。
            frustumFov(root.getDouble("proximity.frustum.fov", FRUSTUM_FOV_DEFAULT), floorAdjustments),
            frustumMinDistance(
                root.getDouble("proximity.frustum.min-distance", FRUSTUM_MIN_DISTANCE_DEFAULT),
                floorAdjustments),
            lenientBoolean(root, "proximity.raycast.enabled", true, typeAdjustments),
            // 本键表示「每方块最多尝试的候选点数」。
            // 钳制 1..PROXIMITY_RAY_SAMPLES_MAX（有效上限 = 暴露面上的候选点数），默认即上限 5。
            // 用 clampCeiling 而非裸 Math.min/max：两端钳制都留痕，管理员能看到配置已被改写。
            clampCeiling(root.getInt("proximity.raycast.samples", 5), 1, PROXIMITY_RAY_SAMPLES_MAX,
                "proximity.raycast.samples", floorAdjustments, ceilingAdjustments),
            new InstantReveal(
                lenientBoolean(root, "proximity.instant-reveal.enabled", true, typeAdjustments),
                // 半径同时受「下限 1」与「上限 8」约束（见 PROXIMITY_INSTANT_RADIUS_MAX）：
                // 旧实现用裸 Math.min/Math.max 静默钳制，管理员看不出配置已被改写，故改为两端留痕。
                clampCeiling(root.getInt("proximity.instant-reveal.radius", 4), 1,
                    PROXIMITY_INSTANT_RADIUS_MAX, "proximity.instant-reveal.radius", floorAdjustments,
                    ceilingAdjustments),
                clampCeiling(root.getInt("proximity.instant-reveal.max-per-tick", 16), 0,
                    PROXIMITY_INSTANT_MAX_PER_TICK_MAX, "proximity.instant-reveal.max-per-tick",
                    floorAdjustments, ceilingAdjustments)),
            clampCeiling(root.getInt("proximity.over-reveal-sampling", 20), 0,
                PROXIMITY_OVER_REVEAL_SAMPLING_MAX, "proximity.over-reveal-sampling",
                floorAdjustments, ceilingAdjustments),
            // 批量合并显形包（默认开启）：把一个周期内的显形用 Paper 原生多方块变更包一次发出。
            // 关掉即逐坐标发单方块变更包（旧行为），供 A/B 对比与线上回退。
            lenientBoolean(root, "proximity.batch-reveal-sends", true, typeAdjustments),
            // 区块下发即显形（默认开启）：区块包放行后立即补发「该区块中当前可见」的坐标，
            // 消掉「区块先伪装、等巡检才还原」的凭空出现窗口（见 ProximityRevealer#onChunkDelivered）。
            lenientBoolean(root, "proximity.reveal-on-chunk-send", true, typeAdjustments),
            // 开掘即预显形（默认开启）：客户端对挖掘有本地预测，等方块破坏后再显形必然晚一个 RTT；
            // 提前到「开始挖」时把 6 邻中挖开后会看见的坐标发下去（见 ProximityRevealer#onBlockDamage）。
            lenientBoolean(root, "proximity.instant-reveal.pre-reveal-on-dig", true, typeAdjustments),
            // 视野变化增量巡检（默认开启）：移动/转向超阈值时当 tick 对近场补一次受限增量扫描，
            // 把周期巡检分片轮转的最坏 0.8 秒窗口压到 ≤ 冷却+1 tick（见 ProximityRevealer#onPlayerMove）。
            new ViewChange(
                lenientBoolean(root, "proximity.view-change.enabled", true, typeAdjustments),
                viewChangeBudgetMs(
                    root.getDouble("proximity.view-change.budget-ms", VIEW_CHANGE_BUDGET_MS_DEFAULT),
                    floorAdjustments, ceilingAdjustments),
                clampCeiling(root.getInt("proximity.view-change.cooldown-ticks",
                        VIEW_CHANGE_COOLDOWN_TICKS_DEFAULT), VIEW_CHANGE_COOLDOWN_TICKS_FLOOR,
                    VIEW_CHANGE_COOLDOWN_TICKS_MAX, "proximity.view-change.cooldown-ticks",
                    floorAdjustments, ceilingAdjustments),
                clampCeiling(root.getInt("proximity.view-change.radius", VIEW_CHANGE_RADIUS_DEFAULT),
                    VIEW_CHANGE_RADIUS_FLOOR, VIEW_CHANGE_RADIUS_MAX, "proximity.view-change.radius",
                    floorAdjustments, ceilingAdjustments))),
        new DiskCache(
            lenientBoolean(root, "disk-cache.enabled", true, typeAdjustments),
            // 上限见 DISK_CACHE_MAX_ENTRIES_MAX：条目上限直接决定磁盘占用与启动期扫描量。
            clampCeiling(root.getInt("disk-cache.max-entries", 20000), 1,
                DISK_CACHE_MAX_ENTRIES_MAX, "disk-cache.max-entries", floorAdjustments,
                ceilingAdjustments),
            // 上限见 DISK_CACHE_MAX_FILE_SIZE_MB_MAX：压缩回收会把整个区域文件读进内存重写。
            // 默认 32 MB 的依据：一个区域文件覆盖 1024 个区块，单条负载约 22~26 KB（改写后区块字节
            // ~19.8 KB + 坐标表 + 遮挡负载），整区装满约需 24~27 MB；16 MB 只能装下约 2/3 的区块，
            // 且文件满了以后没有垃圾可回收 → 该区域剩余区块永远无法进缓存、每次重发都要重算。
            clampCeiling(root.getInt("disk-cache.max-file-size-mb", 32), 1,
                DISK_CACHE_MAX_FILE_SIZE_MB_MAX, "disk-cache.max-file-size-mb", floorAdjustments,
                ceilingAdjustments),
            // 过期秒数有安全下限（见 DISK_CACHE_EXPIRE_FLOOR_SECONDS）：从写入时刻算的过期时间配得比
            // 两次启动的间隔还短，条目在重启后必然已过期，磁盘缓存等于白写（真机命中率恒为 0 的根因）。
            diskCacheExpireSeconds(root.getInt("disk-cache.expire-seconds", 7 * 24 * 60 * 60),
                floorAdjustments),
            // 上限见 DISK_CACHE_BUCKET_CACHE_SIZE_MAX：一个区域文件的记录条数上限是 BUCKET_COUNT=1024
            // （BufferedLinearV3Format，一槽一条记录），但每文件常驻 64 条已约 8~16 MB 内存——再往上抬只会
            // 放大「多文件同时打开」时的驻留内存，故按内存取舍收在 64，而不是格式上限。默认 32：
            // 读命中率与内存的平衡点（每文件约 4~8 MB），常驻文件数还受 idle-close-seconds 约束。
            clampCeiling(root.getInt("disk-cache.bucket-cache-size", 32), 1,
                DISK_CACHE_BUCKET_CACHE_SIZE_MAX, "disk-cache.bucket-cache-size", floorAdjustments,
                ceilingAdjustments),
            clampCeiling(root.getInt("disk-cache.idle-close-seconds", 300), 1,
                DISK_CACHE_IDLE_CLOSE_SECONDS_MAX, "disk-cache.idle-close-seconds", floorAdjustments,
                ceilingAdjustments),
            // 上限见 DISK_CACHE_MAINTENANCE_INTERVAL_MAX_SECONDS：该周期驱动落盘/压缩/关句柄，
            // 配得过大等于让这三件事事实停摆。
            clampCeiling(root.getInt("disk-cache.maintenance-interval-seconds", 30), 1,
                DISK_CACHE_MAINTENANCE_INTERVAL_MAX_SECONDS,
                "disk-cache.maintenance-interval-seconds", floorAdjustments, ceilingAdjustments),
            clampCeiling(root.getInt("disk-cache.compact-per-pass", 4), 1,
                DISK_CACHE_COMPACT_PER_PASS_MAX, "disk-cache.compact-per-pass", floorAdjustments,
                ceilingAdjustments),
            clampCeiling(root.getInt("disk-cache.queue-capacity", 256), 1,
                DISK_CACHE_QUEUE_CAPACITY_MAX, "disk-cache.queue-capacity", floorAdjustments,
                ceilingAdjustments),
            // zstd 前置：服务端自带则直接用；没有则按这几键决定是否自动下载与校验（见 ZstdSupport）
            lenientBoolean(root, "disk-cache.zstd.auto-download", true, typeAdjustments),
            zstdDownloadUrl(root),
            // 上限见 DISK_CACHE_ZSTD_TIMEOUT_MAX_SECONDS：该超时在 onEnable 主线程上同步等待下载，
            // 配得过大则网络黑洞时开服可被卡任意久。
            clampCeiling(root.getInt("disk-cache.zstd.timeout-seconds", 10), 1,
                DISK_CACHE_ZSTD_TIMEOUT_MAX_SECONDS, "disk-cache.zstd.timeout-seconds",
                floorAdjustments, ceilingAdjustments),
            // 下载文件的期望 SHA-256：留空 = 不校验（默认）
            zstdSha256(root),
            // 磁盘上最多保留多少个区域文件（超出按最久未访问淘汰）：与 max-entries 一起界定磁盘占用。
            // 下限 16 —— 再小的话缓存刚热起来就会被淘汰挤掉，命中率趋近 0（等于白写磁盘）；
            // 上限见 DISK_CACHE_MAX_REGION_FILES_MAX，只拦失控配置。
            clampCeiling(root.getInt("disk-cache.max-region-files",
                DISK_CACHE_MAX_REGION_FILES_DEFAULT), 16, DISK_CACHE_MAX_REGION_FILES_MAX,
                "disk-cache.max-region-files", floorAdjustments, ceilingAdjustments)),
        PlatformSupport.Mode.parse(root.getString("advanced.platform", "auto")),
        // 未配置时的内置兜底必须与打包 antixray.yml 的默认值保持一致（有单测对照）
        // 上限见 CACHE_MAXIMUM_SIZE_MAX：每条约 20~25 KB，饱和时内存占用与条目数成正比。
        clampCeiling(root.getInt("cache.maximum-size", 40960), 1,
            CACHE_MAXIMUM_SIZE_MAX, "cache.maximum-size", floorAdjustments, ceilingAdjustments),
        // 按 MB 的硬上限：条目上限管不住「单条负载偏大」（直接调色板 / 高熵地形可到几百 KB/条），
        // 这个上限才是与堆占用直接挂钩的那一个。单位是 MB（旧版 key 叫 cache.maximum-bytes、单位字节）。
        // 两端都留痕：下限防止把缓存配成事实关闭（反而更耗 CPU），
        // 上限只拦手滑多打一位数（默认 512 MB，正常调优撞不到）。
        clampCeiling(
            narrowInt(lenientLongValue(root.get("cache.maximum-mb"), CACHE_MAXIMUM_MB_DEFAULT,
                "cache.maximum-mb", typeAdjustments)),
            CACHE_MAXIMUM_MB_MIN, CACHE_MAXIMUM_MB_MAX, "cache.maximum-mb",
            floorAdjustments, ceilingAdjustments),
        clampCeiling(root.getInt("cache.expire-after-access-seconds", 600), 1,
            CACHE_EXPIRE_AFTER_ACCESS_SECONDS_MAX, "cache.expire-after-access-seconds",
            floorAdjustments, ceilingAdjustments),
        // 上限见 ADVANCED_THREADS_MAX：每个 worker 各持一份按线程复用的编解码 scratch，
        // 手滑多打一位数会同时放大线程数与这部分常驻内存。
        clampCeiling(root.getInt("advanced.threads", 0), 0, ADVANCED_THREADS_MAX,
            "advanced.threads", floorAdjustments, ceilingAdjustments),
        // advanced.timeout-millis：有效区间 [ADVANCED_TIMEOUT_MILLIS_MIN, ADVANCED_TIMEOUT_MILLIS_MAX]。
        // 下限不是新行为——构造器的 Math.max(100, ...) 一直把 <100 的取值按 100 生效，只是过去不留痕；
        // 这里显式按同一区间钳制，让「配置 50、实际 100」也进入 floorAdjustments（绝不静默抬高配置值）。
        // 上限直接决定单任务能挂住一条工作线程多久，见 ADVANCED_TIMEOUT_MILLIS_MAX。
        clampCeiling(root.getInt("advanced.timeout-millis", 2500), ADVANCED_TIMEOUT_MILLIS_MIN,
            ADVANCED_TIMEOUT_MILLIS_MAX, "advanced.timeout-millis",
            floorAdjustments, ceilingAdjustments),
        // 上限见 ADVANCED_QUEUE_CAPACITY_MAX：每个排队项都钉着一个区块封包，
        // 队列容量直接决定积压时钉住多少内存。
        // 下限 4：工作池要为区块改写预留 1/4 队列（见 MikuWorkPool#hasSpareCapacity）。cap<4 时
      // 要么预留把阈值压到 0 → 规划/清理永久停摆，要么不预留 → 非关键任务占掉唯一改写槽位（窗口内区块不伪装）。
      clampCeiling(root.getInt("advanced.queue-capacity", 2048), 4, ADVANCED_QUEUE_CAPACITY_MAX,
            "advanced.queue-capacity", floorAdjustments, ceilingAdjustments),
        unknownTags,
        overrides,
        floorAdjustments,
        ceilingAdjustments,
        typeAdjustments);
  }

  /** 解析某个维度段（未出现的键回落该维度的内置默认）。 */
  private static EffectiveObfuscation parseDimensionSection(ConfigurationSection section,
      EffectiveObfuscation fallback, Set<String> unknownTags, List<String> typeAdjustments,
      String path) {
    List<String> hideBlocks = fallback.hideBlocks();
    if (section.contains("hide-blocks")) {
      List<String> raw = section.getStringList("hide-blocks");
      // 显式配了空清单 → 仍回落内置默认（避免「配了但配空 → 该维度完全不伪装」的静默失效）
      if (!raw.isEmpty()) {
        List<String> expanded = expandTags(raw, unknownTags);
        // 展开后为空 = 清单里<b>全部</b>是识别不了的 tag(...)（误配置，如把 diamond_ores 拼错）。
        // 这种情况必须同样回落内置默认：一旦接受空清单，该维度会一个方块都不伪装（世界档案 targets
        // 为空 → active()=false），而插件因其它维度仍 active 而正常启动，管理员极难察觉。
        if (!expanded.isEmpty()) {
          hideBlocks = List.copyOf(expanded);
        }
      }
    }
    Map<String, Integer> weights = parseWeights(
        section.getConfigurationSection("replacement-weights"), unknownTags, typeAdjustments,
        path + ".replacement-weights");
    if (weights.isEmpty()) {
      weights = fallback.replacementWeights();
    }
    // 分区表：显式配了就优先用它的解析结果（含「解析后为空」——即分段落全部非法被丢弃的情形）。
    // 【刻意如此，勿当 bug 修】解析为空时不回落默认分区表，而是让该维度「按全局权重伪装」（等价于
    // 未启用分区）：与 hide-blocks / replacement-weights 的「空表回落默认」不同，这里把管理员显式写出
    // 的 replacement-bands 视为最终意图——写照了却一个合法分段都没有，说明本意就是不要分区伪装；
    // 后果仅是伪装分布不再随高度分层（外观问题），不会让任何方块漏伪装（隐藏清单与权重表照旧生效）。
    List<ReplacementBand> bands = section.contains("replacement-bands")
        ? parseBands(section.getMapList("replacement-bands"), path + ".replacement-bands",
            typeAdjustments)
        : fallback.replacementBands();
    // 宽松解析：getInt 会把带引号的 "64" 与浮点静默当成 0，把高度范围收窄成 [0,0]；
    // 这里改用宽松解析，解析失败/类型不符时保持回落值（内置默认 = 不限制）并一次性告警
    Integer minY = lenientYValue(section.get("min-y"), path + ".min-y", typeAdjustments);
    Integer maxY = lenientYValue(section.get("max-y"), path + ".max-y", typeAdjustments);
    ObfuscationMode mode = section.contains("mode")
        ? obfuscationMode(section.get("mode"), path + ".mode", typeAdjustments) : fallback.mode();
    // 缺键沿用 fallback；写了但不是布尔值时回落 fallback 并留一条明细（旧实现会静默当成 false）
    boolean useBlockBelow = lenientBoolean(section, "use-block-below", fallback.useBlockBelow(),
        typeAdjustments, path + ".use-block-below");
    return new EffectiveObfuscation(hideBlocks, weights, bands,
        minY == null ? fallback.minY() : minY, maxY == null ? fallback.maxY() : maxY,
        mode, useBlockBelow);
  }

  /**
   * 解析「方块名 → 权重」表（支持 {@code tag(...)}，权重非正的条目被丢弃）。
   *
   * <p><b>为什么权重必须走宽松解析</b>：旧实现用 {@code section.getInt(key, 0)}，而类型化 getter 只认
   * YAML 的整数节点——权重写成带引号的 {@code "10"} 或有小数的 {@code 10.0} 时它静默返回 0，条目被当作
   * 「权重非正」丢弃；若整张表都这么写，{@code weights.isEmpty()} 成立后直接回落内置默认表，最终伪装
   * 外观与配置毫无关系，控制台却没有任何提示（这类「写了但没生效」正是本项目明令禁止的静默落差）。
   * 这里与 {@code min-y}/{@code max-y} 用同一套口径：整数/长整数/整值浮点/十进制数字字符串均接受，
   * 小数四舍五入取整并记入 {@code typeAdjustments}，无法解析的值记入明细后跳过该条。
   *
   * @param path 键的完整路径前缀（如 {@code dimensions.normal.replacement-weights}），用于告警定位
   * @return 有序表；无有效条目时为空表
   */
  private static Map<String, Integer> parseWeights(ConfigurationSection section,
      Set<String> unknownTags, List<String> typeAdjustments, String path) {
    if (section == null) {
      return Map.of();
    }
    Map<String, Integer> weights = new LinkedHashMap<>();
    for (String key : section.getKeys(false)) {
      // 空白键（YAML 里的 "" / ~）归一化后是空名，不可能对应任何方块：留着只会让候选表里多一条
      // 永远解析失败的条目，并让诊断里的「伪装 N 种」虚增。
      if (key == null || key.trim().isEmpty()) {
        continue;
      }
      Integer weight = lenientWeight(section.get(key), path + "." + key, typeAdjustments);
      if (weight == null || weight <= 0) {
        continue;
      }
      for (String name : expandTags(List.of(key), unknownTags)) {
        String normalized = OcclusionRules.normalize(name);
        if (!normalized.isEmpty()) {
          weights.put(normalized, weight);
        }
      }
    }
    return weights;
  }

  /**
   * 宽松解析一个伪装权重（正整数）。
   *
   * <p>接受：整数类型（Integer/Long/Short/Byte）、整值浮点、以及十进制数字字符串（可带正负号）。
   * 小数按 {@link Math#round(double)} 四舍五入取整并记入 {@code typeAdjustments}——伪装的<b>分布</b>
   * 由权重决定，静默把 10.5 截成 10 会让管理员看到的分布与预期不一致，因此必须留痕。
   * 无法解析时同样记入明细并返回 {@code null}（该条目被跳过）。
   *
   * <p><b>非正权重为什么静默丢弃、不告警</b>：yml 注释已写明「须为正整数」，而写 0/负数通常是
   * 「暂时停用某个伪装候选」的刻意做法；逐条告警会把一次正常调整变成一串 WARN。真正需要告警的是
   * 「写法根本不被理解」（带引号/浮点/其它类型）——那才是管理员以为生效、实际却没生效的情形。
   *
   * @param path 键的完整路径（用于告警定位，如 {@code dimensions.normal.replacement-weights.stone}）
   * @return 已取整的正权重；缺失、非正或无法解析时返回 {@code null}
   */
  private static Integer lenientWeight(Object raw, String path, List<String> typeAdjustments) {
    if (raw == null) {
      return null; // 键缺值 / ~：与旧实现一致，按非正权重丢弃，不告警
    }
    if (raw instanceof Integer || raw instanceof Long || raw instanceof Short
        || raw instanceof Byte) {
      return clampWeight(((Number) raw).longValue(), path, typeAdjustments);
    }
    if (raw instanceof Double || raw instanceof Float) {
      double value = ((Number) raw).doubleValue();
      if (!Double.isFinite(value)) {
        typeAdjustments.add(path + "=" + value + "（不是有效权重，已忽略该条目）");
        return null;
      }
      long rounded = Math.round(value);
      if (rounded != value) {
        typeAdjustments.add(path + "=" + value + "（权重须为整数，已四舍五入为 " + rounded + "）");
      }
      return clampWeight(rounded, path, typeAdjustments);
    }
    if (raw instanceof String text) {
      String trimmed = text.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      try {
        return clampWeight(Long.parseLong(trimmed), path, typeAdjustments);
      } catch (NumberFormatException notInteger) {
        // 落到下面按十进制小数再试一次（覆盖带引号的小数，如 "10.5"）
      }
      try {
        double value = Double.parseDouble(trimmed);
        if (!Double.isFinite(value)) {
          typeAdjustments.add(path + "=" + describe(raw) + "（不是有效权重，已忽略该条目）");
          return null;
        }
        long rounded = Math.round(value);
        if (rounded != value) {
          typeAdjustments.add(path + "=" + describe(raw) + "（权重须为整数，已四舍五入为 "
              + rounded + "）");
        }
        return clampWeight(rounded, path, typeAdjustments);
      } catch (NumberFormatException notNumber) {
        typeAdjustments.add(path + "=" + describe(raw) + "（不是数字，已忽略该条目）");
        return null;
      }
    }
    typeAdjustments.add(path + "=" + describe(raw) + "（不是数字，已忽略该条目）");
    return null;
  }

  /**
   * 权重取值收敛：非正返回 {@code null}（丢弃），超出 int 的饱和到 {@link Integer#MAX_VALUE}。
   *
   * <p>饱和而不是丢弃：伪装分布用累计权重实现，超大权重与「饱和后的极大权重」在实际分布上等价
   * （{@code ObfuscationProcessor.weightedTable} 本身也做饱和累加），保留它比静默删掉一个候选更安全。
   */
  private static Integer clampWeight(long value, String path, List<String> typeAdjustments) {
    if (value <= 0L) {
      return null;
    }
    if (value > Integer.MAX_VALUE) {
      typeAdjustments.add(path + "=" + value + "（权重过大，已按 " + Integer.MAX_VALUE + " 生效）");
      return Integer.MAX_VALUE;
    }
    return (int) value;
  }

  /**
   * 解析按 Y 分区的伪装权重段。
   *
   * <p>每段形如 {@code {min-y: -64, max-y: -1, weights: {deepslate: 10, ...}}}；min-y/max-y 缺省时
   * 分别视为「负无穷/正无穷」。min &gt; max 的段（矛盾配置）直接丢弃——空段比错误段更安全。
   * 段内的 min-y/max-y 与维度段同口径宽松解析（带引号的数字按数字解析；类型不符时保持「不限制」
   * 并一次性告警），避免 YAML 写法错误让整段悄悄变成「覆盖全部高度」。
   *
   * <p><b>weights 的键不做 tag(...) 展开</b>（与 {@code replacement-weights} 不同）：分区表里的键
   * 会被当作最终方块名参与伪装分布，用户随手写 {@code tag(...)} 时若静默丢弃整段，管理员会以为已经
   * 分层。因此这里把无法识别的键名原样保留——它会在构建世界档案时解析失败并被跳过（少一个候选，
   * 不会误藏），同时不像丢弃整段那样改变分区结构。
   */
  private static List<ReplacementBand> parseBands(List<Map<?, ?>> raw, String path,
      List<String> typeAdjustments) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<ReplacementBand> bands = new ArrayList<>(raw.size());
    for (int index = 0; index < raw.size(); index++) {
      Map<?, ?> entry = raw.get(index);
      String bandPath = path + "[" + index + "]";
      // 与维度段同口径：带引号的数字/字符串按数字解析；类型不符时保持「不限制」并一次性告警
      Integer bandMinY = lenientYValue(entry.get("min-y"), bandPath + ".min-y", typeAdjustments);
      Integer bandMaxY = lenientYValue(entry.get("max-y"), bandPath + ".max-y", typeAdjustments);
      int minY = bandMinY == null ? Integer.MIN_VALUE : bandMinY;
      int maxY = bandMaxY == null ? Integer.MAX_VALUE : bandMaxY;
      if (minY > maxY) {
        continue;
      }
      Map<String, Integer> weights = new LinkedHashMap<>();
      if (entry.get("weights") instanceof Map<?, ?> weightMap) {
        for (Map.Entry<?, ?> weightEntry : weightMap.entrySet()) {
          // 键必须是有效方块名：null / 空白（YAML 里的 ~ 或 ""）无法对应任何方块状态，
          // 保留它只会让分区表里多一条永远解析失败的条目，并在诊断回显里显示成 "null"。
          String name = weightEntry.getKey() == null ? "" : String.valueOf(weightEntry.getKey()).trim();
          if (name.isEmpty()) {
            continue;
          }
          // 与 replacement-weights 同口径宽松解析：带引号的数字 / 整值浮点都接受，小数四舍五入并留痕。
          // 旧实现在这里用 instanceof Number ? intValue() : 0，字符串权重会被静默当成 0 丢掉。
          Integer weight = lenientWeight(weightEntry.getValue(),
              bandPath + ".weights." + name, typeAdjustments);
          if (weight != null && weight > 0) {
            weights.put(OcclusionRules.normalize(name), weight);
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
   * 值直接是该世界要覆盖的子键（enabled / hide-blocks / replacement-weights / replacement-bands /
   * min-y / max-y / mode / use-block-below）。未出现的键保持 {@code null}（回落该世界所属维度）；
   * enabled 的类型不符时同样保持 null（沿用维度）并记入一次性告警，绝不静默当成 false。
   */
  private static List<WorldOverride> parseWorldOverrides(ConfigurationSection section,
      Set<String> unknownTags, List<String> typeAdjustments) {
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
      // 通配编译与 blacklist.yml 共用同一份实现（WorldGlobs），避免两处语义悄悄分叉
      Pattern glob = WorldGlobs.compile(pattern);
      // 覆盖键的完整路径（仅用于类型不符时的告警文案，让服主能一眼定位到哪一行）
      String path = "world-overrides." + pattern;
      // enabled：可覆盖所属维度的启用开关；缺省 null = 沿用维度（绝不静默当成 false）
      Boolean enabled = lenientBoolean(world, "enabled", path + ".enabled", typeAdjustments);

      List<String> hideBlocks = null;
      if (world.contains("hide-blocks")) {
        List<String> raw = world.getStringList("hide-blocks");
        if (raw.isEmpty()) {
          hideBlocks = List.of(); // 显式空清单 = 有意的「该世界不伪装」
        } else {
          List<String> expanded = expandTags(raw, unknownTags);
          // 展开后为空 = 清单里全是识别不了的 tag(...)（误配置）。保持 null（回落维度值），
          // 绝不把误配置当成「该世界不伪装」——那会让该世界一个方块都不伪装且无从察觉。
          hideBlocks = expanded.isEmpty() ? null : List.copyOf(expanded);
        }
      }
      Map<String, Integer> replacementWeights = null;
      if (world.contains("replacement-weights")) {
        Map<String, Integer> parsed = parseWeights(
            world.getConfigurationSection("replacement-weights"), unknownTags, typeAdjustments,
            path + ".replacement-weights");
        if (!parsed.isEmpty()) {
          replacementWeights = parsed; // 空覆盖回落维度值，避免「配了但配错 → 不伪装」的静默失效
        }
      }
      List<ReplacementBand> replacementBands = world.contains("replacement-bands")
          ? parseBands(world.getMapList("replacement-bands"), path + ".replacement-bands",
              typeAdjustments)
          : null;
      // 与维度段同口径的宽松解析；类型不符时保持 null（沿用所属维度），不会被当成 0 收窄高度
      Integer minY = lenientYValue(world.get("min-y"), path + ".min-y", typeAdjustments);
      Integer maxY = lenientYValue(world.get("max-y"), path + ".max-y", typeAdjustments);
      ObfuscationMode mode = world.contains("mode")
          ? obfuscationMode(world.get("mode"), path + ".mode", typeAdjustments) : null;
      // 缺键返回 null（沿用所属维度）；不是布尔值时同样返回 null 并留一条明细
      Boolean useBlockBelow = lenientBoolean(world, "use-block-below",
          path + ".use-block-below", typeAdjustments);

      overrides.add(new WorldOverride(pattern, glob, enabled, hideBlocks, replacementWeights,
          replacementBands, minY, maxY, mode, useBlockBelow));
    }
    return List.copyOf(overrides);
  }

  /**
   * 宽松解析高度键（{@code min-y} / {@code max-y}）的原始值。
   *
   * <p><b>为什么不用 {@code ConfigurationSection#getInt}</b>：类型化 getter 只认 YAML 的整数节点，
   * 写成带引号的数字（{@code "64"}）或浮点（{@code 64.0}）时会静默返回调用方给的默认值 0——高度范围
   * 于是被悄悄收窄成 {@code [0,0]}，该世界几乎不再伪装，日志里却没有任何提示。这里接受整数、长整数与
   * 可带正负号的十进制数字字符串；其它类型或无法解析时返回 null（= 该键不限制高度，调用方按回落值
   * 处理），并把键与原始值记进 {@code typeAdjustments}，由加载路径一次性 WARN（绝不静默改配置）。
   *
   * @param raw              YAML 原始值（{@code ConfigurationSection#get} 或 map 的取值）
   * @param path             键的完整路径，用于告警定位（如 {@code dimensions.normal.min-y}）
   * @param typeAdjustments 类型不符明细的收集器
   * @return 解析出的高度；键缺失/留空/类型不符时为 null
   */
  private static Integer lenientYValue(Object raw, String path, List<String> typeAdjustments) {
    if (raw == null) {
      return null; // 键缺失或留空 = 不限制（与「没写」同义，不告警）
    }
    Long value = integralValue(raw);
    if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      typeAdjustments.add(path + "=" + describe(raw) + "（类型不符，已按不限制处理）");
      return null;
    }
    return value.intValue();
  }

  /**
   * 把 YAML 原始值转成整数：只接受整数类型（Integer / Long / Short / Byte）与十进制数字字符串
   * （可带正负号）；浮点、布尔、列表等一律返回 null（由 {@link #lenientYValue} 告警并回落）。
   */
  private static Long integralValue(Object raw) {
    if (raw instanceof Integer || raw instanceof Long || raw instanceof Short
        || raw instanceof Byte) {
      return ((Number) raw).longValue();
    }
    if (raw instanceof String text) {
      String trimmed = text.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      try {
        return Long.parseLong(trimmed);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  /** 把宽松解析出的 long 安全收窄为 int（超出 int 范围时饱和，随后由调用方按上下限钳制并留痕）。 */
  private static int narrowInt(long value) {
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
  }

  /**
   * 宽松解析 long 型配置值（当前用于 {@code cache.maximum-mb} 这类容量键的中间解析）。
   *
   * <p>与 {@link #lenientYValue} 同口径：接受整数类型（Integer/Long/Short/Byte）与十进制数字字符串
   * （可带正负号），其余写法记一条 {@code typeAdjustments} 后回落默认值。刻意不用
   * {@code ConfigurationSection#getLong}：它对带引号的数字同样静默取默认值，会让「写了却没生效」无声无息
   * ——而这正是 D1/D2 两处宽松解析要消灭的那类落差。
   */
  private static long lenientLongValue(Object raw, long defaultValue, String path,
      List<String> typeAdjustments) {
    if (raw == null) {
      return defaultValue;
    }
    Long value = integralValue(raw);
    if (value == null) {
      typeAdjustments.add(path + "=" + describe(raw) + "（不是整数，已按默认值 " + defaultValue
          + " 生效）");
      return defaultValue;
    }
    return value;
  }

  /** 告警里回显原始值的写法（字符串加引号，便于服主看出「多写了一对引号」）。 */
  private static String describe(Object raw) {
    return raw instanceof String text ? "\"" + text + "\"" : String.valueOf(raw);
  }

  /**
   * 宽松解析布尔开关（{@code world-overrides.<世界>.enabled}）：缺键返回 {@code null}（= 沿用所属维度）。
   *
   * <p>接受 YAML 布尔与 {@code "true"} / {@code "false"} 字符串（大小写不敏感、去首尾空白）；
   * 其它类型返回 null 并记进 {@code typeAdjustments}。这里刻意不走 {@code getBoolean}：它对类型不符的
   * 取值同样静默返回默认值，会让「本想关闭」的世界悄悄保持启用。
   */
  private static Boolean lenientBoolean(ConfigurationSection section, String key, String path,
      List<String> typeAdjustments) {
    if (!section.contains(key)) {
      return null;
    }
    Object raw = section.get(key);
    if (raw == null) {
      return null; // 键存在但无值（YAML 里的 "enabled:"）：按「未写」处理，不告警
    }
    Boolean value = lenientBooleanValue(raw);
    if (value == null) {
      typeAdjustments.add(path + "=" + describe(raw) + "（类型不符，已忽略该覆盖，沿用所属维度）");
    }
    return value;
  }

  /**
   * 宽松解析<b>带默认值</b>的布尔开关（{@code enabled} / {@code use-block-below} / 各模块开关等）。
   *
   * <p><b>为什么不能用 {@code getBoolean}</b>：它的实现是「取值不是 Boolean 就返回默认值」，于是 yml 里
   * 写成带引号的 {@code enabled: "false"}（YAML 里是完全合法的字符串）会拿到默认 {@code true}——
   * 「明确写下的关闭」被静默反转成「保持开启」，日志里没有任何痕迹（配置里也看不出异常）。
   * 这里改为：Boolean 原样、{@code "true"}/{@code "false"}（大小写不敏感、去首尾空白）按布尔解析，
   * 其余取值回落默认值并记进 {@code typeAdjustments}，由加载路径一次性 WARN。
   *
   * <p><b>合法配置的语义不变</b>：{@code enabled: false} 仍是 false、缺键仍是默认值——宽松解析只在
   * 写法不被理解时才介入，绝不改变真实布尔值的含义。
   */
  private static boolean lenientBoolean(ConfigurationSection section, String path,
      boolean defaultValue, List<String> typeAdjustments) {
    return lenientBoolean(section, path, defaultValue, typeAdjustments, path);
  }

  /**
   * 同 {@link #lenientBoolean(ConfigurationSection, String, boolean, List)}，但把「读哪个键」与
   * 「告警里写哪个路径」分开。
   *
   * <p>维度段里的 {@code enabled} / {@code use-block-below} 三个维度同名，裸键名告警无法区分是主世界
   * 还是地狱写错了；一次性 WARN 只有一次机会，必须带上 {@code dimensions.<维度>} 前缀。
   */
  private static boolean lenientBoolean(ConfigurationSection section, String key,
      boolean defaultValue, List<String> typeAdjustments, String path) {
    Object raw = section.get(key);
    if (raw == null) {
      return defaultValue;
    }
    Boolean value = lenientBooleanValue(raw);
    if (value == null) {
      typeAdjustments.add(path + "=" + describe(raw) + "（不是布尔值，已按默认值 " + defaultValue
          + " 生效）");
      return defaultValue;
    }
    return value;
  }

  /**
   * 布尔值的宽松转换：{@link Boolean} 原样，{@code "true"}/{@code "false"} 字符串解析，其它返回
   * {@code null}（由调用方决定回落策略并留一条明细）。
   */
  private static Boolean lenientBooleanValue(Object raw) {
    if (raw instanceof Boolean value) {
      return value;
    }
    if (raw instanceof String text) {
      String trimmed = text.trim();
      if ("true".equalsIgnoreCase(trimmed)) {
        return Boolean.TRUE;
      }
      if ("false".equalsIgnoreCase(trimmed)) {
        return Boolean.FALSE;
      }
    }
    return null;
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
   * 视锥竖直全角的生效值：非法值（NaN / {@code <=0} / {@code >360}）回落<b>默认值</b>
   * （{@link #FRUSTUM_FOV_DEFAULT}）；低于 {@link #FRUSTUM_FOV_FLOOR} 时抬升到下限——两种情况都记入
   * {@code floorAdjustments}，由加载路径一次性 WARN（绝不静默改用户配置）。
   *
   * <p><b>为什么非法值回落默认值、合法但过窄的值才抬到下限</b>：前者是「这个数根本不能用」，
   * 应回到插件默认；后者是「能用但会让玩家看得见的方块保持伪装」，下限 110° 恰好是「不伤可见性」的
   * 最低值，故按最低值生效（详见 {@link #FRUSTUM_FOV_FLOOR}）。要省带宽请关掉整个视锥剔除。
   */
  private static double frustumFov(double configured, List<String> floorAdjustments) {
    if (Double.isNaN(configured) || configured <= 0.0D || configured > 360.0D) {
      // 非法值（NaN / 非正 / 超过 360°）与低于下限一样不会照配置生效：纳入统一的一次性 WARN 明细，
      // 避免出现「管理员写了无效角度、却以为它已生效」的静默落差（与其它钳制项口径一致）。
      floorAdjustments.add("proximity.frustum.fov=" + configured + "（非法值，回落默认 "
          + FRUSTUM_FOV_DEFAULT + "°）");
      return FRUSTUM_FOV_DEFAULT;
    }
    if (configured >= FRUSTUM_FOV_FLOOR) {
      return configured;
    }
    floorAdjustments.add("proximity.frustum.fov=" + configured + "（下限 " + FRUSTUM_FOV_FLOOR + "°）");
    return FRUSTUM_FOV_FLOOR;
  }

  /**
   * 最小豁免距离的生效值：非有限值（NaN / ±Inf）回落<b>默认值</b>
   * （{@link #FRUSTUM_MIN_DISTANCE_DEFAULT}），低于 {@link #FRUSTUM_MIN_DISTANCE_FLOOR} 时抬升到下限——
   * 两种情况都记入 {@code floorAdjustments}（与 {@link #frustumFov} 口径一致，绝不静默改用户配置）。
   *
   * <p><b>为什么用 {@code !isFinite} 而不是只拦 NaN</b>：{@code Math.max(0.0, +Inf)} 仍是 {@code +Inf}，
   * 会让 {@code +Inf} 原样穿过钳制进入生效值——视锥判定的豁免半径变成无穷大，<b>所有候选都豁免</b>，
   * 等于静默把「视锥剔除」关掉（与 {@link #proximityDistance} 的 NaN 缺陷同源）。非有限值一律回落默认。
   */
  private static double frustumMinDistance(double configured, List<String> floorAdjustments) {
    if (!Double.isFinite(configured)) {
      // 与 frustumFov / proximityDistance 对齐：非有限值不再静默回落，纳入统一的一次性 WARN 明细
      floorAdjustments.add("proximity.frustum.min-distance=" + configured + "（非有限值，回落默认 "
          + FRUSTUM_MIN_DISTANCE_DEFAULT + " 格）");
      return FRUSTUM_MIN_DISTANCE_DEFAULT;
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
   * 视野变化增量巡检的时间预算（毫秒）：非有限值回落默认、越界两端钳制并留痕（与视锥参数同一口径）。
   *
   * <p>该值是「每玩家每 tick 的硬耗时上限」，配得过大等于把周期巡检的成本搬家到事件路径上；
   * 配得过小则一次都判不完（功能静默失效）。两端都必须显式告知，绝不静默改配置。
   */
  private static double viewChangeBudgetMs(double configured, List<String> floorAdjustments,
      List<String> ceilingAdjustments) {
    if (!Double.isFinite(configured)) {
      ceilingAdjustments.add("proximity.view-change.budget-ms=" + configured + "（非有限值，回落默认 "
          + VIEW_CHANGE_BUDGET_MS_DEFAULT + " ms）");
      return VIEW_CHANGE_BUDGET_MS_DEFAULT;
    }
    if (configured < VIEW_CHANGE_BUDGET_MS_FLOOR) {
      floorAdjustments.add("proximity.view-change.budget-ms=" + configured
          + "（下限 " + VIEW_CHANGE_BUDGET_MS_FLOOR + " ms）");
      return VIEW_CHANGE_BUDGET_MS_FLOOR;
    }
    if (configured > VIEW_CHANGE_BUDGET_MS_MAX) {
      ceilingAdjustments.add("proximity.view-change.budget-ms=" + configured
          + "（上限 " + VIEW_CHANGE_BUDGET_MS_MAX + " ms）");
      return VIEW_CHANGE_BUDGET_MS_MAX;
    }
    return configured;
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

  /**
   * 整数钳制：取值做「下限 {@code max(minimum)} + 上限 {@code min(maximum)}」。
   *
   * <p><b>两端都留痕</b>：下限被抬升记入 {@code floorAdjustments}、上限被压低记入
   * {@code ceilingAdjustments}，由加载路径一次性 WARN（与 {@link #proximityDistance} 同口径）。
   * 旧实现的下限分支是静默 {@code Math.max}，管理员把值写成 0/负数时配置被改写却看不到任何提示
   * （而 yml 注释里写着「不得低于 X」），与「绝不静默改用户配置」的项目约定相矛盾。
   */
  private static int clampCeiling(int value, int minimum, int maximum, String key,
      List<String> floorAdjustments, List<String> ceilingAdjustments) {
    if (value < minimum) {
      floorAdjustments.add(key + "=" + value + "（下限 " + minimum + "）");
      return minimum;
    }
    if (value > maximum) {
      ceilingAdjustments.add(key + "=" + value + "（上限 " + maximum + "）");
      return maximum;
    }
    return value;
  }

  /**
   * 邻近显形半径的生效值（区块）：0 = 跟随服务器视距；正数原样保留（运行时再与玩家有效视距取小），
   * 超过 {@link #PROXIMITY_DISTANCE_CHUNKS_MAX} 时按上限生效；负数钳到 0 并留痕。
   *
   * <p>三种情况都记入明细、由加载路径一次性 WARN（绝不静默改用户配置）：负数记 {@code floorAdjustments}，
   * 超上限记 {@code ceilingAdjustments}。
   */
  private static int proximityDistanceChunks(int configured, List<String> floorAdjustments,
      List<String> ceilingAdjustments) {
    if (configured < 0) {
      // 负数钳到 0（= 跟随服务器视距）：旧实现直接 Math.max 静默归零、不留痕，管理员会以为负数已生效
      floorAdjustments.add("proximity.distance-chunks=" + configured + "（负值，按 0 处理 = 跟随服务器视距）");
      return PROXIMITY_DISTANCE_CHUNKS_DEFAULT;
    }
    if (configured > PROXIMITY_DISTANCE_CHUNKS_MAX) {
      ceilingAdjustments.add("proximity.distance-chunks=" + configured
          + "（上限 " + PROXIMITY_DISTANCE_CHUNKS_MAX + " 区块，实际还会被玩家视距钳制）");
      return PROXIMITY_DISTANCE_CHUNKS_MAX;
    }
    return configured;
  }

  /**
   * 宽松解析缺失策略（{@code neighbors.missing-policy}）。
   *
   * <p>接受不区分大小写、首尾可带空白的 {@code hide} / {@code expose}（与旧实现的可接受集合逐字相同）；
   * 其它取值回落最安全的 {@link MissingPolicy#HIDE}，并记入 {@code typeAdjustments}。
   *
   * <p><b>为什么必须留痕</b>：旧实现只做一次相等比较，写错（{@code expse}）会静默按 hide 生效——而 hide
   * 恰好是最保守的一侧，管理员会以为自己配的 expose 已生效，实际却在「多伪装」，且日志里没有任何提示。
   * 这与布尔键的类型化 getter 属同一类「写了却没生效」的静默落差。
   */
  private static MissingPolicy missingPolicy(Object raw, List<String> typeAdjustments) {
    if (raw == null) {
      return MissingPolicy.HIDE; // 键缺失：正常回落，不告警
    }
    if (raw instanceof String text) {
      String normalized = text.trim().toLowerCase(Locale.ROOT);
      if ("expose".equals(normalized)) {
        return MissingPolicy.EXPOSE;
      }
      if ("hide".equals(normalized)) {
        return MissingPolicy.HIDE;
      }
    }
    typeAdjustments.add("neighbors.missing-policy=" + describe(raw)
        + "（不是 hide / expose，已按最安全的 " + MissingPolicy.HIDE + " 生效）");
    return MissingPolicy.HIDE;
  }

  /**
   * 宽松解析伪装模式（{@code dimensions.<维度>.mode} / {@code world-overrides.<世界>.mode}）。
   *
   * <p>接受不区分大小写、首尾可带空白的 {@code all} / {@code enclosed}（与旧实现的可接受集合逐字相同）；
   * 其它取值回落更安全的 {@link ObfuscationMode#ALL}，并记入 {@code typeAdjustments}（{@code path} 用于
   * 点名具体键，如 {@code dimensions.normal.mode}）。
   *
   * <p><b>为什么必须留痕</b>：旧实现只在「恰好等于 enclosed」时取 ENCLOSED，写错（{@code enclose}）会
   * 静默按 ALL 生效——ALL 会把矿洞壁上本可看见的矿也伪装掉，玩家观感与管理员预期相反，却无任何提示。
   */
  private static ObfuscationMode obfuscationMode(Object raw, String path,
      List<String> typeAdjustments) {
    if (raw instanceof String text) {
      String normalized = text.trim().toLowerCase(Locale.ROOT);
      if ("enclosed".equals(normalized)) {
        return ObfuscationMode.ENCLOSED;
      }
      if ("all".equals(normalized)) {
        return ObfuscationMode.ALL;
      }
    }
    typeAdjustments.add(path + "=" + describe(raw)
        + "（不是 all / enclosed，已按更安全的 " + ObfuscationMode.ALL + " 生效）");
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
   * 配置项被安全下限抬升或被安全上限回落时的<b>一次性</b>中文 WARN（进程级闸门 {@code once} 只提示一次）。
   *
   * <p><b>为什么必须提示</b>：生效值与管理员的 yml 不一致，若不说明，管理员会以为「配的还是我写的值」，
   * 或反过来怀疑插件读错配置。这里分三条分别给出被抬升 / 被回落 / 类型不符的键与原因（下限指向
   * 「玩家看得见假方块」，上限指向「扫描量 O(r²) 拖垮主线程 / 缓存把内存打满」，类型不符指向
   * 「高度键被静默当成 0 而使伪装范围收窄」）。
   *
   * @param once 进程级一次性闸门（同一实例重复 reload 不会重复刷屏）
   */
  public void warnIfConfigAdjusted(Logger logger, AtomicBoolean once) {
    if (logger == null
        || (floorAdjustments.isEmpty() && ceilingAdjustments.isEmpty()
            && typeAdjustments.isEmpty())) {
      return;
    }
    if (!once.compareAndSet(false, true)) {
      return;
    }
    // 写法不被理解（类型不符 / 无法解析）：类型化 getter 会静默取默认值（0 / false / 丢掉条目），
    // 这里改为一次性点名并说明每类键实际按什么生效——这是权重 / 布尔 / 枚举类键宽松解析的可见出口。
    if (!typeAdjustments.isEmpty()) {
      logger.warning("antixray.yml 的 " + String.join("、", typeAdjustments) + "。"
          + "写法无法识别的键已按更安全的取值处理：伪装权重无法解析时跳过该条（该候选不参与伪装），"
          + "高度键不再限制高度（world-overrides 里则回落所属维度），布尔开关按默认值生效"
          + "（world-overrides 的开关则沿用所属维度），枚举类开关（neighbors.missing-policy 与各 mode）"
          + "也按默认值生效。旧实现会把这些写法静默吞掉：带引号/浮点的高度被当成 0（高度范围收窄成 [0,0]，"
          + "该世界几乎不再伪装）、带引号/浮点的权重被当成 0（该伪装方块直接消失）、enabled: \"false\" 被当成"
          + "默认值 true（「写下的关闭」变成「仍然开启」）、missing-policy: expse 或 mode: enclose 这类拼错"
          + "被当成默认策略（与实际意图相反且无提示）。请把数字写成不带引号的整数（如 min-y: -64、"
          + "权重 stone: 10）、布尔写成不带引号的 true / false、枚举写成 hide / expose 与 all / enclosed。");
    }
    if (!floorAdjustments.isEmpty()) {
      // 明细里既有「低于下限被抬升」，也有「取值非法/非有限值回落默认」——两者的共同点是「没有按你写的值
      // 生效」，因此文案不再一律说成「低于下限」（那会让写了 NaN / 负角度的管理员以为自己配的是合法的过窄值）。
      logger.warning("antixray.yml 的 " + String.join("、", floorAdjustments)
          + " 低于安全下限或取值非法，已改按安全值生效"
          + "（当前：proximity.frustum.fov=" + proximity.frustumFov() + "°、min-distance="
          + proximity.frustumMinDistance() + " 格、disk-cache.expire-seconds="
          + diskCache.expireSeconds() + " 秒、advanced.timeout-millis=" + timeoutMillis
          + " 毫秒）。低于下限或非法的取值会让对应功能静默失效："
          + "视锥配窄 → 玩家看得见的方块保持伪装（要点一下才变回来）；"
          + "磁盘缓存过期时间配短 → 条目活不过一次重启，命中率恒为 0。"
          + "限制磁盘占用请调 disk-cache.max-entries / max-file-size-mb，而不是缩短过期时间。");
    }
    if (!ceilingAdjustments.isEmpty()) {
      logger.warning("antixray.yml 的 " + String.join("、", ceilingAdjustments) + " 超过安全上限，已按上限生效"
          + "（当前：proximity.distance-chunks=" + proximity.distanceChunks() + "、neighbors.cache-maximum-size="
          + neighbors.cacheMaximumSize() + "、cache.maximum-size=" + cacheMaximumSize
          + "、cache.maximum-mb=" + cacheMaximumMb + "）。"
          + "上限用于防止手滑多打一位数的失控配置：邻近显形距离的扫描量随半径平方增长（O(r²)）会拖住主线程，"
          + "缓存条目上限则直接换算成内存占用。");
    }
  }

  /** 被安全下限抬升过的配置键明细（空列表 = 未抬升）；供诊断回显。 */
  public List<String> floorAdjustments() {
    return floorAdjustments;
  }

  /** 被安全上限回落过的配置键明细（空列表 = 未回落）；供诊断回显。 */
  public List<String> ceilingAdjustments() {
    return ceilingAdjustments;
  }

  /**
   * 写法无法识别、已按安全取值处理的明细（权重条目 / 高度键 / 布尔开关 / 枚举类开关；空列表 = 无）。
   *
   * <p>唯一的生产消费点是 {@link #warnIfConfigAdjusted}（加载期一次性 WARN，内部直接读字段）；
   * 本访问器本身只被单测使用，诊断 dump（{@code util/Diagnostics}）当前尚未输出它，需要时可在那里补一行
   * （与 floorAdjustments / ceilingAdjustments 同口径）。
   *
   * @implNote 保留为 public 的原因：单测用它逐类断言「宽松解析是否真的留了痕」（权重四舍五入/被跳过、
   *     高度键回落、布尔与枚举键按默认值生效）。若把断言改为读日志会引入对日志格式的耦合，故保留访问器。
   */
  public List<String> typeAdjustments() {
    return typeAdjustments;
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

  /**
   * 逐世界 {@code enabled} 覆盖的缓存值。
   *
   * <p>{@link ConcurrentHashMap} 不能存 null，而「没有匹配到覆盖段」与「匹配到了但没写 enabled」都表示
   * 「沿用维度」，故用三态枚举把它们与显式的 true / false 区分开。
   */
  private enum OverrideEnabled {
    /** 没有匹配到覆盖段，或匹配到的覆盖段没写 enabled（= 沿用所属维度）。 */
    ABSENT,
    /** 覆盖段显式启用。 */
    ENABLED,
    /** 覆盖段显式关闭。 */
    DISABLED
  }

  /**
   * 「某世界是否启用反矿透」的统一判定。
   *
   * <p>优先级（高 → 低）：总开关 {@link #enabled()} / 世界黑名单（{@link WorldBlacklistConfig}）→
   * {@code world-overrides.<世界>.enabled}（非 null 时）→ {@code dimensions.<维度>.enabled}。
   * <b>黑名单仍然最高优先</b>：名单内的世界即使覆盖段写了 {@code enabled: true} 也不会启用
   * （要重新启用须先从黑名单移除）。
   *
   * @param dimension 该世界所属维度；传 {@code null} 表示只判「世界级」开关（拿不到维度时用，见
   *                  {@link #antiXrayAppliesTo(String)}）
   */
  public boolean enabledForWorld(String worldName, Dimension dimension) {
    if (!enabled || WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      return false;
    }
    Boolean override = overrideEnabled(worldName);
    if (override != null) {
      return override;
    }
    return dimension == null || dimensionEnabled[dimension.ordinal()];
  }

  /**
   * 反矿透是否应作用于该世界：总开关开启 <b>且</b> 该世界不在世界黑名单内 <b>且</b> 未被
   * {@code world-overrides.<世界>.enabled: false} 关闭。
   *
   * <p><b>这是所有反矿透入口（区块改写、邻近显形、事件即时显形、方块变更观察）统一的判定出口</b>——
   * 各处不得自行重复实现匹配逻辑。黑名单现在是独立配置，对反矿透与带宽优化<b>同时</b>生效，
   * 且优先级高于 world-overrides。
   *
   * <p>封包入口只拿得到世界名、拿不到维度，故这里<b>不</b>判维度开关；维度开关在构建世界档案时生效
   * （维度关闭 ⇒ 档案为空；覆盖段未写 enabled 时沿用维度值，见 {@link #overrideEffective}）。
   */
  public boolean antiXrayAppliesTo(String worldName) {
    return enabledForWorld(worldName, null);
  }

  /**
   * 取某世界的覆盖开关：匹配到的覆盖段显式写了 {@code enabled} 时返回其值，否则返回 null
   * （= 沿用所属维度）。
   *
   * <p>结果按世界名缓存。没有<b>任何</b>覆盖段写 enabled（默认配置）时直接返回 null，让
   * {@link #antiXrayAppliesTo} 保持「总开关 + 黑名单」的 O(1) 快路径，不给每个区块封包增加
   * 字符串/正则匹配；只有在确有 enabled 覆盖时，才为首次见到的世界名匹配一次并缓存。
   */
  private Boolean overrideEnabled(String worldName) {
    if (!hasEnabledOverride || worldName == null) {
      return null;
    }
    OverrideEnabled cached = overrideEnabledCache.get(worldName);
    if (cached == null) {
      int index = matchOverride(worldName);
      Boolean value = index < 0 ? null : worldOverrides.get(index).enabled();
      cached = value == null ? OverrideEnabled.ABSENT
          : (value ? OverrideEnabled.ENABLED : OverrideEnabled.DISABLED);
      // 上界保护：超限整体清空，只让下一次多匹配一遍字符串，不影响正确性
      if (overrideEnabledCache.size() >= OVERRIDE_ENABLED_CACHE_MAX) {
        overrideEnabledCache.clear();
      }
      overrideEnabledCache.put(worldName, cached);
    }
    if (cached == OverrideEnabled.ABSENT) {
      return null;
    }
    return cached == OverrideEnabled.ENABLED;
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
   * 平台判定兜底（{@code advanced.platform}）：{@code AUTO}（默认）或手动 {@code PAPER}/{@code FOLIA}。
   * 它同时影响反矿透与带宽两个模块的调度路径，因此虽然写在 antixray.yml 里也是全局生效的。
   */
  public PlatformSupport.Mode platform() {
    return platform;
  }

  public int cacheMaximumSize() {
    return cacheMaximumSize;
  }

  /**
   * 改写结果内存缓存的硬上限，单位 <b>MB</b>（{@code cache.maximum-mb}，默认 512）。
   *
   * <p>与 {@link #cacheMaximumSize()}（条目上限）同时生效，谁先到顶以谁为准：条目上限管「最多多少条」，
   * 本值管「最多占多少内存」，后者才与堆占用直接挂钩（单条负载可达几百 KB）。
   * 超出后按最近最少使用（LRU）淘汰。修改后需重启（缓存对象在启动期构造）。
   */
  public int cacheMaximumMb() {
    return cacheMaximumMb;
  }

  /**
   * 同一上限的<b>字节</b>形式（{@link #cacheMaximumMb()} × 1 MiB）：写给按字节记账的
   * {@code RewriteCache} 用，配置侧只暴露 MB 单位的键。
   */
  public long cacheMaximumBytes() {
    return cacheMaximumMb * 1024L * 1024L;
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