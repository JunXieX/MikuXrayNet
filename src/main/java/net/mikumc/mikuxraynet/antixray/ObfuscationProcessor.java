package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkSection;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;

/**
 * 反矿透核心：对单个区块做「6 面正交遮挡判定 + 按权重随机替换」，并可顺带做调色板压缩重排、
 * 位宽预算封顶与失效条目裁剪。
 *
 * <p><b>判定语义</b>：由 {@code dimensions.<维度>.mode} 选择（<b>按维度独立</b>）——
 * <ul>
 *   <li>{@code enclosed}（旧行为）：只有当目标方块的上下左右前后 6 个正交方向全部被遮挡
 *       （即该方块在客户端「不可见」）时才替换为伪装方块；任一方向未遮挡——包括世界上下界之外、
 *       以及 section 缺失——都按「暴露」处理并保持原样。对角方向的方块不参与遮挡判定。</li>
 *   <li>{@code all}（默认）：所有目标方块一律伪装，<b>不做</b> 6 面遮挡判定，因此矿洞壁上的
 *       裸露矿也会被伪装；由邻近显形在玩家靠近且可见时还原真实方块（见 {@code ProximityRevealer}）。</li>
 * </ul>
 *
 * <p><b>区块边界</b>：越过本区块的 6 个面时改查 {@link NeighborEdges}（邻块贴边一层快照）。
 * 邻块数据缺失时按 {@code antixray.yml: neighbors.missing-policy} 处理，默认 {@code hide}
 * （宁可多伪装，也不留下沿 16×16 网格线的泄漏）。
 *
 * <p><b>按维度配置</b>：{@code dimensions.<维度>} 为主世界/地狱/末地各自指定 hide-blocks /
 * replacement-weights / replacement-bands / min-y / max-y / mode / use-block-below；
 * {@code world-overrides}（按世界名，最高优先级）可覆盖其中任意键。维度由
 * {@code World#getEnvironment()} 判定（不依赖世界名）。各维度档案与覆盖档案在启动期解析为
 * {@link WorldProfile}（含方块状态 id 解析与权重表预构建），运行期按（世界名 + 维度）取用：
 * 无覆盖段时 O(1) 直取维度档案，有覆盖段时按世界名在该维度的缓存表里解析一次。
 *
 * <p><b>按 Y 分区伪装（P0-3）</b>：{@code replacement-bands} 按「第一个覆盖该高度的分段」选择
 * 候选表，无覆盖时回落 {@code replacement-weights}；每段的累计权重表启动期预构建为 int 数组，
 * 热路径零装箱、零 HashMap。
 *
 * <p><b>位宽预算封顶（P0-1）</b>：改写每个 section 前把「所有候选表合计将引入的新状态数」与当前
 * 位宽容量（{@code 1 << bitsPerBlock} − 已有条目数）对账，超预算时各候选表只保留已在调色板内的
 * 伪装方块（避免升位）；改写后裁剪引用计数为 0 的失效条目（被替换掉的矿）并在可能时降位宽，
 * 位宽<b>单调不增</b>。仅当调色板内候选完全耗尽才允许 grow（逃生口）——
 * <b>红线：宁可包体大，不可漏伪装</b>；任何裁剪失败只放弃体积优化，不回滚已完成的替换。
 *
 * <p><b>关键优化</b>：
 * <ul>
 *   <li>section 级预筛：全空气（封包头 blockCount==0）与<b>调色板里没有目标方块</b>的 section 整段跳过，
 *       不做 4096 次逐格查表（见 {@link ChunkSection#paletteCouldContain}）；</li>
 *   <li>非目标方块只做一次位图查询即跳过，不做任何邻居判定；</li>
 *   <li>整块区块无任何改动时直接返回原字节数组，完全不触发重编码；</li>
 *   <li>只有真正被改动的 section 才重编码（未改动的 section 由 codec 原样搬运原始字节）；</li>
 *   <li>无逐世界覆盖时按世界名取档案不走任何 Map；预算充足时各候选表直接共享预构建数组
 *       （表本身不拷贝，只分配一次候选表容器数组）。</li>
 * </ul>
 *
 * <p>本类不做任何 Bukkit 访问，可在工作线程安全运行。
 */
public final class ObfuscationProcessor {

  private static final int SECTION_VOLUME = 4096;
  private static final int[] NO_POSITIONS = new int[0];

  /** 调色板选项：压缩重排 + 自检 + 位宽预算封顶。 */
  public record PaletteOptions(boolean reorder, boolean strictVerify, boolean widthBudget) {

    /** 全关（保持 P0-1 之前的行为：不重排、不封顶、不裁剪）。 */
    public static final PaletteOptions DISABLED = new PaletteOptions(false, false, false);
  }

  /**
   * 改写结果。
   *
   * @param data                改写后的 section 字节（未改动时即为入参 {@code source}）
   * @param obfuscatedPositions 被伪装的方块位置，编码为 {@code y << 8 | z << 4 | x}（区块内相对坐标）
   * @param failure             {@code null} 表示正常；非 null 为「解码/重编码异常」的摘要
   *                            （此时已 fail-open 放行原包，调用方应计数并告警，勿与「无目标方块」混淆）
   * @param sectionBits         各 section 改写后的每方块位宽（未改写的 section 为 -1；无任何改写为 null）。
   *                            供「位宽直方图」诊断观察封顶/降位效果
   */
  public record Result(byte[] data, int[] obfuscatedPositions, String failure, int[] sectionBits) {

    /** 是否真的改动了字节。 */
    public boolean changed() {
      return obfuscatedPositions.length > 0;
    }

    /** 是否因解码/重编码异常而放弃改写（fail-open）。 */
    public boolean failed() {
      return failure != null;
    }
  }

  /**
   * 单个世界的改写档案（P0-2/P0-3）：目标位图、伪装候选表（回落表 + 各 band）与生效范围。
   *
   * <p>全部字段在启动期解析预构建（方块名 → 状态 id、权重表 → 累计权重 int 数组），
   * 热路径只做数组读取，零装箱、零 HashMap。包级可见供同包测试构造固定档案。
   */
  static final class WorldProfile {

    final BitSet targets;
    /** 回落候选表（{@code replacement-weights}）：无 band 覆盖该高度时使用。 */
    final int[] replacementIds;
    final int[] replacementCum;
    /** band 候选表（{@code replacement-bands}，bands 优先于回落表；空表段已在构建期丢弃）。 */
    final int[] bandMinY;
    final int[] bandMaxY;
    final int[][] bandIds;
    final int[][] bandCum;
    /** 生效高度范围（含端点）；{@code Integer.MIN_VALUE}/{@code Integer.MAX_VALUE} = 不限制。 */
    final int minY;
    final int maxY;
    final boolean obfuscateAll;
    /** use-block-below：命中伪装时优先用「下方紧邻方块」当伪装方块（按档案独立，逐维度可不同）。 */
    final boolean useBlockBelow;

    WorldProfile(BitSet targets, int[] replacementIds, int[] replacementCum,
        int[] bandMinY, int[] bandMaxY, int[][] bandIds, int[][] bandCum,
        int minY, int maxY, boolean obfuscateAll) {
      this(targets, replacementIds, replacementCum, bandMinY, bandMaxY, bandIds, bandCum,
          minY, maxY, obfuscateAll, false);
    }

    WorldProfile(BitSet targets, int[] replacementIds, int[] replacementCum,
        int[] bandMinY, int[] bandMaxY, int[][] bandIds, int[][] bandCum,
        int minY, int maxY, boolean obfuscateAll, boolean useBlockBelow) {
      this.targets = targets;
      this.replacementIds = replacementIds.clone();
      this.replacementCum = replacementCum.clone();
      this.bandMinY = bandMinY.clone();
      this.bandMaxY = bandMaxY.clone();
      this.bandIds = bandIds.clone();
      this.bandCum = bandCum.clone();
      this.minY = minY;
      this.maxY = maxY;
      this.obfuscateAll = obfuscateAll;
      this.useBlockBelow = useBlockBelow;
    }

    /** 空档案：不具备生效条件（用于「维度整体关闭」）。 */
    static final WorldProfile EMPTY = new WorldProfile(new BitSet(), new int[0], new int[0],
        new int[0], new int[0], new int[0][], new int[0][],
        Integer.MIN_VALUE, Integer.MAX_VALUE, true);

    /** 返回把 use-block-below 设为指定值的副本（相同则原样返回）；供兼容构造统一施加该开关。 */
    WorldProfile withUseBlockBelow(boolean value) {
      if (this.useBlockBelow == value) {
        return this;
      }
      return new WorldProfile(targets, replacementIds, replacementCum, bandMinY, bandMaxY,
          bandIds, bandCum, minY, maxY, obfuscateAll, value);
    }

    /** 是否具备生效条件（有目标方块，且回落表或任一 band 表有候选）。 */
    boolean active() {
      return !targets.isEmpty() && (replacementIds.length > 0 || bandIds.length > 0);
    }

    /**
     * 用生效视图 + 注册表解析出本世界的档案（启动期调用；未识别的名称记录并跳过）。
     *
     * @param label 日志前缀（如「反矿透」或「反矿透（世界覆盖 world_nether）」）
     */
    static WorldProfile resolve(BlockStateRegistry registry, AntiXrayConfig.EffectiveObfuscation effective,
        Logger logger, String label) {
      BitSet targets = new BitSet(registry.getUniqueBlockStateCount());
      List<String> resolvedTargets = new ArrayList<>();
      for (String name : effective.hideBlocks()) {
        int stateId = BlockStateRegistry.resolveStateId(name);
        if (stateId < 0) {
          logger.warning(label + "配置中的隐藏方块名称无法识别，已跳过：" + name);
        } else {
          targets.set(stateId);
          resolvedTargets.add(name);
        }
      }
      // 启动自检：把「实际匹配到的目标方块清单」打出来，便于一眼看出是否漏了深层矿变体
      logger.info(label + "目标方块解析完成：" + resolvedTargets.size() + '/' + effective.hideBlocks().size()
          + " 种 → " + resolvedTargets);

      int[][] fallback = weightedTable(effective.replacementWeights(), logger, label);
      if (fallback[0].length == 0 && !effective.replacementBands().isEmpty()) {
        logger.warning(label + "的 replacement-weights 未解析到任何有效伪装方块（仅有分区段可用，"
            + "未被分区覆盖的高度将不伪装，请检查伪装方块名称）");
      }

      // band 表构建：无有效候选的段直接丢弃，使该高度回落下一覆盖段或回落表（运行期无需空表分支）
      List<AntiXrayConfig.ReplacementBand> validBands = new ArrayList<>();
      List<int[][]> validTables = new ArrayList<>();
      for (AntiXrayConfig.ReplacementBand band : effective.replacementBands()) {
        int[][] table = weightedTable(band.weights(), logger,
            label + "的伪装分区段 [y " + band.minY() + ".." + band.maxY() + "]");
        if (table[0].length == 0) {
          continue;
        }
        validBands.add(band);
        validTables.add(table);
      }
      int bandCount = validBands.size();
      int[] bandMinY = new int[bandCount];
      int[] bandMaxY = new int[bandCount];
      int[][] bandIds = new int[bandCount][];
      int[][] bandCum = new int[bandCount][];
      for (int i = 0; i < bandCount; i++) {
        AntiXrayConfig.ReplacementBand band = validBands.get(i);
        bandMinY[i] = band.minY();
        bandMaxY[i] = band.maxY();
        bandIds[i] = validTables.get(i)[0];
        bandCum[i] = validTables.get(i)[1];
      }

      if (effective.minY() > effective.maxY()) {
        logger.warning(label + "的 min-y(" + effective.minY() + ") 大于 max-y(" + effective.maxY()
            + ")，该世界范围内不会伪装任何方块（请修正配置）");
      }

      boolean obfuscateAll = effective.mode() == AntiXrayConfig.ObfuscationMode.ALL;
      return new WorldProfile(targets, fallback[0], fallback[1], bandMinY, bandMaxY,
          bandIds, bandCum, effective.minY(), effective.maxY(), obfuscateAll,
          effective.useBlockBelow());
    }

    /**
     * 把「方块名 → 权重」解析为「状态 id → 累计权重」表（保持声明顺序，累计权重严格递增）。
     *
     * @return {@code {ids, cum}}；两个数组等长（可能为空）
     */
    private static int[][] weightedTable(Map<String, Integer> weights, Logger logger, String label) {
      int[] ids = new int[weights.size()];
      int[] cum = new int[ids.length];
      int index = 0;
      int cumulative = 0;
      for (Map.Entry<String, Integer> entry : weights.entrySet()) {
        int stateId = BlockStateRegistry.resolveStateId(entry.getKey());
        if (stateId < 0) {
          logger.warning(label + "配置中的伪装方块名称无法识别，已跳过：" + entry.getKey());
          continue;
        }
        cumulative += entry.getValue();
        ids[index] = stateId;
        cum[index] = cumulative;
        index++;
      }
      if (index != ids.length) {
        ids = Arrays.copyOf(ids, index);
        cum = Arrays.copyOf(cum, index);
      }
      return new int[][] {ids, cum};
    }
  }

  /**
   * 「解码侧统计」诊断结果（{@link #diagnose}）：只回答「这次解码到底看到了什么」。
   *
   * @param sectionCount  区块的 section 数
   * @param stateKinds    整块区块里出现过的不同方块状态种类数（0 表示解码出全空气）
   * @param targetMatches 命中目标方块（配置的隐藏清单）的方块个数（<b>不看遮挡</b>）
   */
  public record Diagnostic(int sectionCount, int stateKinds, int targetMatches) {
  }

  private final ChunkCodec codec;
  private final IntPredicate occlusionTable;
  private final boolean layerObfuscation;
  private final boolean missingPolicyHide;
  private final PaletteOptions paletteOptions;
  /**
   * 「下方方块可用性」过滤器：true = 可以拿它当伪装方块（非空气/非流体等）。
   * {@code null} 表示未提供（use-block-below 只能退回按遮挡语义判定）。
   */
  private final IntPredicate belowUsable;
  /**
   * 流体覆盖开关（{@code occlusion.fluid-cover}，默认开）：enclosed 模式下目标方块<b>上方是流体</b>
   * 时按遮挡处理（刷在岩浆里的残骸由此被伪装）。只作用于目标方块的判定，不改动全局遮挡表。
   */
  private final boolean fluidCover;
  /** 流体覆盖掩码（水/岩浆/水柱；不含含水方块）；{@code null} = 不可用（流体规则整体不生效）。 */
  private final IntPredicate fluidTable;
  /** 无覆盖世界（或世界名为 null）时使用的档案（= 主世界档案，供解码侧诊断用）。 */
  private final WorldProfile defaultProfile;
  /** 各维度档案（下标 = {@link AntiXrayConfig.Dimension#ordinal()}）。 */
  private final WorldProfile[] dimensionProfiles;
  /** 与 {@code config.worldOverrides()} 平行的各覆盖段档案：{@code [覆盖段下标][维度下标]}。 */
  private final WorldProfile[][] overrideProfiles;
  /** 逐世界配置来源；{@code null} 表示无逐世界支持（测试构造），一律用维度档案。 */
  private final AntiXrayConfig config;
  /**
   * 逐维度的「世界名 → 已匹配档案」缓存（首个该世界的区块解析一次，此后 O(1)）。
   *
   * <p><b>为什么按维度分开</b>：覆盖段的生效值回落「该世界所属维度」，同一世界名在不同维度下
   * 应命中不同档案；分表后既避免键拼接产生新分配，也不会因同名世界跨维度而串档。
   * 值不可变（含 {@link #NO_OVERRIDE} 哨兵），多线程安全。
   */
  private final ConcurrentHashMap<String, WorldProfile>[] overrideCache;
  /** 「未命中任何覆盖段」的哨兵值（避免用 null 表示「已查询但无覆盖」而反复匹配）。 */
  private static final WorldProfile NO_OVERRIDE = WorldProfile.EMPTY;

  /**
   * 直接用「已解析」的数据构造（供单测与显式装配使用）；伪装模式为 {@code enclosed}
   * （只伪装 6 面全遮挡的掩埋矿），与旧行为一致。
   *
   * @param occlusionTable    遮挡判定表：方块状态 id → 是否整块不透明
   * @param targets           目标方块状态位图
   * @param replacementIds    伪装方块状态 id
   * @param cumulativeWeights 与 {@code replacementIds} 等长的累计权重（严格递增，末项为总权重）
   * @param layerObfuscation  true 时同一高度层统一使用同一种伪装方块
   * @param missingPolicyHide 邻块数据缺失时是否视为遮挡（true=宁可多伪装）
   * @param paletteOptions    调色板重排选项
   *
   * <p>测试专用豁免：生产装配一律走 {@link #create}，本构造当前仅单测在用，保留以免破坏测试。
   */
  public ObfuscationProcessor(ChunkCodec codec, IntPredicate occlusionTable, BitSet targets,
      int[] replacementIds, int[] cumulativeWeights, boolean layerObfuscation,
      boolean missingPolicyHide, PaletteOptions paletteOptions) {
    this(codec, occlusionTable, targets, replacementIds, cumulativeWeights, layerObfuscation,
        missingPolicyHide, paletteOptions, false);
  }

  /**
   * 完整构造（追加伪装模式）。
   *
   * @param obfuscateAll {@code true} 即 {@code obfuscation.mode: all}——所有目标矿一律伪装，
   *                     跳过 6 面遮挡判定（靠邻近显形在玩家靠近可见时还原）；
   *                     {@code false} 即 {@code enclosed}——只伪装 6 面全遮挡的掩埋矿。
   *
   * <p>测试专用豁免：生产装配一律走 {@link #create}，本构造当前仅单测在用，保留以免破坏测试。
   */
  public ObfuscationProcessor(ChunkCodec codec, IntPredicate occlusionTable, BitSet targets,
      int[] replacementIds, int[] cumulativeWeights, boolean layerObfuscation,
      boolean missingPolicyHide, PaletteOptions paletteOptions, boolean obfuscateAll) {
    this(codec, occlusionTable, targets, replacementIds, cumulativeWeights, layerObfuscation,
        missingPolicyHide, paletteOptions, obfuscateAll, false, null);
  }

  /**
   * 完整构造（追加 use-block-below，P2-6a）。
   *
   * @param useBlockBelow true = 命中伪装时若「下方紧邻方块」可用，优先用它当伪装方块（观感自然，
   *                      同竞品思路）；false = 完全保持既有行为（按权重随机 / 层状）。
   * @param belowUsable   下方方块可用性过滤器（非空气/非流体等）；null 时退回用遮挡表判定可用性
   *
   * <p>测试专用豁免：生产装配一律走 {@link #create}，本构造当前仅单测在用，保留以免破坏测试。
   */
  public ObfuscationProcessor(ChunkCodec codec, IntPredicate occlusionTable, BitSet targets,
      int[] replacementIds, int[] cumulativeWeights, boolean layerObfuscation,
      boolean missingPolicyHide, PaletteOptions paletteOptions, boolean obfuscateAll,
      boolean useBlockBelow, IntPredicate belowUsable) {
    this(codec, occlusionTable, layerObfuscation, missingPolicyHide, paletteOptions, useBlockBelow,
        belowUsable, new WorldProfile(targets, replacementIds, cumulativeWeights,
            new int[0], new int[0], new int[0][], new int[0][],
            Integer.MIN_VALUE, Integer.MAX_VALUE, obfuscateAll),
        new WorldProfile[0], null);
  }

  /**
   * 兼容构造（测试用手工档案）：逐世界覆盖档案对所有维度一致，且 {@code useBlockBelow} 统一施加到
   * 全部档案（档案自带值时以参数为准——参数即旧版的「全局 use-block-below」）。
   *
   * <p>测试专用豁免：生产装配一律走 {@link #create}，本构造当前仅单测在用，保留以免破坏测试。
   */
  ObfuscationProcessor(ChunkCodec codec, IntPredicate occlusionTable, boolean layerObfuscation,
      boolean missingPolicyHide, PaletteOptions paletteOptions, boolean useBlockBelow,
      IntPredicate belowUsable, WorldProfile defaultProfile, WorldProfile[] overrideProfiles,
      AntiXrayConfig config) {
    this(codec, occlusionTable, layerObfuscation, missingPolicyHide, paletteOptions, belowUsable,
        defaultProfile.withUseBlockBelow(useBlockBelow),
        new WorldProfile[] {defaultProfile.withUseBlockBelow(useBlockBelow),
            defaultProfile.withUseBlockBelow(useBlockBelow),
            defaultProfile.withUseBlockBelow(useBlockBelow)},
        expandOverrides(overrideProfiles, useBlockBelow), config, false, null);
  }

  /** 逐世界覆盖档案展开为「各覆盖段 × 各维度」（兼容构造用：同一档案施加到全部维度）。 */
  private static WorldProfile[][] expandOverrides(WorldProfile[] overrides, boolean useBlockBelow) {
    WorldProfile[][] expanded = new WorldProfile[overrides.length][AntiXrayConfig.Dimension.values().length];
    for (int i = 0; i < overrides.length; i++) {
      WorldProfile profile = overrides[i].withUseBlockBelow(useBlockBelow);
      for (int d = 0; d < expanded[i].length; d++) {
        expanded[i][d] = profile;
      }
    }
    return expanded;
  }

  /**
   * 包级完整构造：档案由调用方构建（{@code create} 走配置解析路径，测试构造走参数直传路径）。
   *
   * @param defaultProfile   无覆盖世界默认档案（= 主世界档案，供解码侧诊断使用）
   * @param dimensionProfiles 各维度档案（下标 = {@code Dimension#ordinal()}）
   * @param overrideProfiles  各覆盖段档案：{@code [覆盖段下标][维度下标]}
   * @param fluidCover        流体覆盖开关（{@code occlusion.fluid-cover}）
   * @param fluidTable        流体覆盖掩码；null 表示不可用（流体规则不生效）
   */
  @SuppressWarnings("unchecked")
  ObfuscationProcessor(ChunkCodec codec, IntPredicate occlusionTable, boolean layerObfuscation,
      boolean missingPolicyHide, PaletteOptions paletteOptions, IntPredicate belowUsable,
      WorldProfile defaultProfile, WorldProfile[] dimensionProfiles,
      WorldProfile[][] overrideProfiles, AntiXrayConfig config, boolean fluidCover,
      IntPredicate fluidTable) {
    this.codec = codec;
    this.occlusionTable = occlusionTable;
    this.layerObfuscation = layerObfuscation;
    this.missingPolicyHide = missingPolicyHide;
    this.paletteOptions = paletteOptions;
    this.belowUsable = belowUsable;
    this.fluidCover = fluidCover;
    this.fluidTable = fluidTable;
    this.defaultProfile = defaultProfile;
    this.dimensionProfiles = dimensionProfiles;
    this.overrideProfiles = overrideProfiles;
    this.config = config;
    ConcurrentHashMap<String, WorldProfile>[] caches =
        new ConcurrentHashMap[AntiXrayConfig.Dimension.values().length];
    for (int d = 0; d < caches.length; d++) {
      caches[d] = new ConcurrentHashMap<>();
    }
    this.overrideCache = caches;
  }

  /**
   * 兼容构造：邻块缺失按「暴露」处理、不做调色板重排、模式为 enclosed（即接入邻块快照之前的行为）。
   *
   * <p>测试专用豁免：生产装配一律走 {@link #create}（或 9 参完整构造），本构造当前仅单测在用，
   * 保留以免破坏既有测试。
   */
  public ObfuscationProcessor(ChunkCodec codec, IntPredicate occlusionTable, BitSet targets,
      int[] replacementIds, int[] cumulativeWeights, boolean layerObfuscation) {
    this(codec, occlusionTable, targets, replacementIds, cumulativeWeights, layerObfuscation, false,
        PaletteOptions.DISABLED);
  }

  /**
   * 用配置 + 注册表解析出目标与伪装方块（各维度档案 + 逐世界覆盖段 + 分区伪装表，均启动期预构建）。
   * 未识别的名称会被记录并跳过。
   *
   * @return 已装配的处理器；若未解析到任何有效目标或伪装方块，{@link #isActive()} 为 false
   */
  public static ObfuscationProcessor create(ChunkCodec codec, BlockStateRegistry registry,
      AntiXrayConfig config, Logger logger, PaletteOptions paletteOptions) {
    AntiXrayConfig.Dimension[] dimensions = AntiXrayConfig.Dimension.values();
    WorldProfile[] dimensionProfiles = new WorldProfile[dimensions.length];
    for (AntiXrayConfig.Dimension dimension : dimensions) {
      // 维度整体关闭（如默认关闭的末地）→ 空档案（该维度任何方块都不伪装），但仍打印一行以便核对
      dimensionProfiles[dimension.ordinal()] = config.dimensionEnabled(dimension)
          ? WorldProfile.resolve(registry, config.dimensionEffective(dimension), logger,
              "反矿透（" + dimension.label() + "）")
          : WorldProfile.EMPTY;
    }
    List<AntiXrayConfig.WorldOverride> overrides = config.worldOverrides();
    WorldProfile[][] overrideProfiles = new WorldProfile[overrides.size()][dimensions.length];
    for (int i = 0; i < overrides.size(); i++) {
      for (AntiXrayConfig.Dimension dimension : dimensions) {
        // 覆盖段是最高优先级的手动出口：即便该维度整体关闭也照常解析（用户显式配置的世界应当生效）
        overrideProfiles[i][dimension.ordinal()] = WorldProfile.resolve(registry,
            config.overrideEffective(i, dimension), logger,
            "反矿透（世界覆盖 " + overrides.get(i).pattern() + " / " + dimension.label() + "）");
      }
    }

    boolean missingPolicyHide = config.neighbors().missingPolicy() == AntiXrayConfig.MissingPolicy.HIDE;
    // use-block-below（默认关）：「下方方块可用」= 非空气/非流体（注册表语义）。
    // 是否是目标方块的检查在改写循环里做（targets 位图），这里只提供空气/流体过滤器。
    IntPredicate belowUsable = state -> !(registry.isAir(state) || registry.isFluid(state));

    return new ObfuscationProcessor(codec, registry::isOccluding, config.layerObfuscation(),
        missingPolicyHide, paletteOptions, belowUsable,
        dimensionProfiles[AntiXrayConfig.Dimension.NORMAL.ordinal()], dimensionProfiles,
        overrideProfiles, config, config.occlusion().fluidCover(), registry::isFluidCover);
  }

  /** 是否具备生效条件（任一维度档案或任一世界覆盖档案的目标与伪装候选都已解析）。 */
  public boolean isActive() {
    for (WorldProfile profile : dimensionProfiles) {
      if (profile.active()) {
        return true;
      }
    }
    for (WorldProfile[] perDimension : overrideProfiles) {
      for (WorldProfile profile : perDimension) {
        if (profile.active()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 改写一个区块（不含邻块快照，缺失策略会生效）。
   *
   * <p>测试专用豁免：生产路径走 7 参完整入口（带世界名与维度），本重载当前仅单测在用，
   * 保留以免破坏测试。
   */
  public Result rewrite(byte[] source, int sectionCount, long seed) {
    return rewrite(source, sectionCount, seed, null);
  }

  /**
   * 改写一个区块。
   *
   * @param source       封包中的原始 section 字节（不会被修改）
   * @param sectionCount 该世界的 section 数量
   * @param seed         伪装随机种子；同种子同输入必然得到同结果（缓存可安全复用）
   * @param neighbors    4 个水平邻块的贴边快照；{@code null} 表示缺失，按缺失策略处理
   * @return 改写结果；解码/重编码异常都回退为「原字节 + 空位置 + 异常摘要」（fail-open）
   *
   * <p>测试专用豁免：生产路径走 7 参完整入口（带世界名与维度），本重载当前仅单测在用，
   * 保留以免破坏测试。
   */
  public Result rewrite(byte[] source, int sectionCount, long seed, NeighborEdges neighbors) {
    return rewrite(source, sectionCount, seed, neighbors, null, 0);
  }

  /**
   * 改写一个区块（按世界取配置；维度按主世界处理，供既有调用方与旧测试使用）。
   *
   * @param worldName 区块所在世界名；{@code null} 表示用主世界档案（无逐世界覆盖）
   * @param worldMinY 该世界最低方块 Y（世界坐标系），用于把 section 内相对 Y 换算成绝对 Y
   *                  （min-y/max-y 过滤与分区伪装表都按绝对 Y 判定）
   *
   * <p>测试专用豁免：生产路径走 7 参完整入口（显式传维度），本重载当前仅单测在用，
   * 保留以免破坏测试。
   */
  public Result rewrite(byte[] source, int sectionCount, long seed, NeighborEdges neighbors,
      String worldName, int worldMinY) {
    return rewrite(source, sectionCount, seed, neighbors, worldName,
        AntiXrayConfig.Dimension.NORMAL, worldMinY);
  }

  /**
   * 改写一个区块（完整入口：按「世界名 + 维度」取配置）。
   *
   * <p>解析优先级：{@code world-overrides}（按世界名）&gt; {@code dimensions.<维度>}。
   *
   * @param worldName 区块所在世界名；{@code null} 表示无逐世界覆盖（用维度档案）
   * @param dimension 该世界所属维度（由 {@code World#getEnvironment()} 判定，见
   *                  {@link AntiXrayConfig.Dimension#of(org.bukkit.World.Environment)}）
   * @param worldMinY 该世界最低方块 Y（世界坐标系），用于把 section 内相对 Y 换算成绝对 Y
   */
  public Result rewrite(byte[] source, int sectionCount, long seed, NeighborEdges neighbors,
      String worldName, AntiXrayConfig.Dimension dimension, int worldMinY) {
    if (!isActive() || sectionCount <= 0 || source.length == 0) {
      return new Result(source, NO_POSITIONS, null, null);
    }
    WorldProfile profile = profileFor(worldName, dimension == null
        ? AntiXrayConfig.Dimension.NORMAL : dimension);
    if (!profile.active()) {
      return new Result(source, NO_POSITIONS, null, null);
    }

    Random random = new Random(seed);
    int[] positions = new int[16];
    int count = 0;
    boolean changed = false;
    // 改写 section 的最终位宽（位宽直方图诊断用；null = 无任何改写，不分配）
    int[] sectionBits = null;

    Chunk chunk;
    try {
      chunk = codec.decode(source, sectionCount);
    } catch (RuntimeException exception) {
      // 解码失败：多半是区块二进制布局与预期不符（版本/第三方改写），必须可观测，不能静默当作「无改动」
      return new Result(source, NO_POSITIONS, describe(exception), null);
    }

    try {
      boolean rangeLimited = profile.minY != Integer.MIN_VALUE || profile.maxY != Integer.MAX_VALUE;
      boolean bandDriven = profile.bandMinY.length > 0;
      // section 级预筛（封包侧判空）：全空气（封包头 blockCount==0）与本段调色板里没有目标方块的
      // section 整段跳过，省下它们各 4096 次逐格查表（见 ChunkSection#paletteCouldContain）。
      // 判定一次性算在循环外：热循环里只读一个布尔数组，不引入任何接口调用——否则逐格扫描那段
      // 会有可观测的变慢（实测「24 个 section 全含目标」的形态从 0.34ms 涨到 0.76ms）。
      boolean[] sectionCandidates = selectCandidateSections(chunk, profile);

      for (int sectionIndex = 0; sectionIndex < chunk.getSectionCount(); sectionIndex++) {
        if (!sectionCandidates[sectionIndex]) {
          continue;
        }
        ChunkSection section = chunk.getSection(sectionIndex);

        int baseY = sectionIndex << 4;
        int layerY = Integer.MIN_VALUE;
        int layerState = -1;
        boolean sectionChanged = false;
        // 本 section 的候选表（含联合位宽预算筛选），首次替换时惰性构建（无目标的 section 零开销）
        int[][] sectionTables = null;
        // 候选表占用预算后的剩余调色板空位（use-block-below × 预算联动用；见 buildSectionTables）
        int[] freeAfterTables = {Integer.MAX_VALUE};

        for (int index = 0; index < SECTION_VOLUME; index++) {
          int state = section.getBlockState(index);
          if (!profile.targets.get(state)) {
            continue;
          }

          // 绝对 Y：min-y/max-y 过滤与分区伪装表都按世界坐标判定
          int blockY = 0;
          if (rangeLimited || bandDriven) {
            blockY = worldMinY + baseY + (index >> 8 & 15);
            // min-y/max-y（P0-2）：范围外的方块判定与替换都跳过
            if (rangeLimited && (blockY < profile.minY || blockY > profile.maxY)) {
              continue;
            }
          }

          // mode=all：所有目标矿一律伪装（不看 6 面遮挡）；mode=enclosed：只伪装 6 面全遮挡的掩埋矿。
          if (!profile.obfuscateAll && !isFullyOccluded(chunk, baseY, index, neighbors)) {
            continue;
          }

          if (sectionTables == null) {
            sectionTables = buildSectionTables(section, profile, freeAfterTables);
          }
          // 分区伪装表（P0-3）：按「第一个覆盖该高度的 band」取表；无覆盖回落 replacement-weights
          int table = bandDriven ? bandIndexFor(profile, blockY) : -1;
          int[] ids;
          int[] cum;
          if (table < 0) {
            ids = sectionTables[0];
            cum = sectionTables[1];
          } else {
            ids = sectionTables[(table + 1) << 1];
            cum = sectionTables[((table + 1) << 1) | 1];
          }

          int replacement = -1;
          if (profile.useBlockBelow) {
            int below = camouflageFromBelow(chunk, profile, baseY, index);
            // 预算联动（P0-1 × P2-6a）：下方方块可能不在本 section 调色板内（跨 section 读到的
            // 状态、或候选表已把空位用满）——此时引入它会新增调色板条目、有升位风险，只有在
            // 「候选表占用后仍有空位」或「已在调色板内」时才取用，否则退回候选表随机伪装，
            // 绝不绕过位宽封顶（预算关闭时 freeAfterTables 为最大值，行为与旧版一致）。
            if (below >= 0 && (freeAfterTables[0] > 0 || section.paletteContains(below))) {
              replacement = below;
            }
          }
          if (replacement < 0) {
            if (layerObfuscation) {
              int y = baseY | (index >> 8 & 15);
              if (layerY != y) {
                layerY = y;
                layerState = pick(ids, cum, random);
              }
              replacement = layerState;
            } else {
              replacement = pick(ids, cum, random);
            }
          }

          section.setBlockState(index, replacement);
          sectionChanged = true;
          changed = true;

          if (count == positions.length) {
            positions = Arrays.copyOf(positions, count << 1);
          }
          positions[count++] = index + (baseY << 8);
        }

        if (sectionChanged) {
          if (sectionBits == null) {
            sectionBits = new int[sectionCount];
            Arrays.fill(sectionBits, -1);
          }
          // 位宽预算封顶收尾（P0-1）：裁剪引用计数为 0 的失效条目并在可能时降位宽。
          // 失败只放弃体积优化，绝不回滚已完成的替换（宁可包体大，不可漏伪装）。
          if (paletteOptions.widthBudget()) {
            try {
              section.compactPalette(false);
            } catch (RuntimeException pruneFailure) {
              // 裁剪失败：保留替换结果原样（可能已 grow），伪装正确性不受影响
            }
          }
          // 调色板压缩重排只作用于被改动的 section：未改动的 section 保持原字节（选择性重编码的前提）
          if (paletteOptions.reorder()) {
            section.reorderPaletteByFrequency(paletteOptions.strictVerify());
          }
          sectionBits[sectionIndex] = section.bitsPerBlock();
        }
      }

      if (!changed) {
        // 无改动：直接复用原字节，跳过整次重编码
        return new Result(source, NO_POSITIONS, null, null);
      }
      return new Result(chunk.finalizeOutput(), Arrays.copyOf(positions, count), null, sectionBits);
    } catch (RuntimeException exception) {
      return new Result(source, NO_POSITIONS, describe(exception), null);
    } finally {
      chunk.close();
    }
  }

  /**
   * section 级预筛（封包侧判空）：标出「这个 section 可能需要处理」的位置。
   *
   * <p>三个条件都成立才算候选：section 存在（未在缓冲区内省略）、非全空气（封包头
   * {@code blockCount == 0}）、且本段调色板里存在目标方块状态（{@link ChunkSection#paletteCouldContain}）。
   * 前两条是原有的短路，第三条把「整段没有目标方块」的 section 一并跳过——那些段逐格扫 4096 次也不会命中。
   *
   * <p>预筛刻意放在逐格循环<b>之外</b>一次性完成、循环内只读布尔数组：在热循环头部引入接口调用
   * （{@code profile.targets::get}）会拖累逐格扫描的优化程度，实测让「每段都含目标」的形态从
   * 0.34 ms/区块 涨到 0.76 ms/区块（输出字节与替换数完全一致，属纯粹的编译质量退化）。
   */
  private static boolean[] selectCandidateSections(Chunk chunk, WorldProfile profile) {
    int sectionCount = chunk.getSectionCount();
    boolean[] candidates = new boolean[sectionCount];
    for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
      ChunkSection section = chunk.getSection(sectionIndex);
      candidates[sectionIndex] = section != null
          && !section.isEmpty()
          && section.paletteCouldContain(profile.targets::get);
    }
    return candidates;
  }

  /**
   * 该（世界名, 维度）下的改写是否需要邻块贴边快照。
   *
   * <p>{@code mode=all} 的档案在 {@link #rewrite} 里<b>不做任何遮挡判定</b>（所有目标方块一律伪装，
   * 由邻近显形还原），邻块数据一位都用不到；据此调用方可以连「抓取贴边快照」都省掉——
   * 那是一次<em>在主线程 / Folia 区域线程</em>上逐格读世界的操作。{@code mode=enclosed} 时才需要。
   *
   * <p>档案在启动期固化（{@code mode} 与隐藏方块表一样不在热重载范围内），因此本判定是稳定的。
   *
   * @param worldName 世界名；{@code null} 表示按维度档案判定（不看逐世界覆盖）
   */
  public boolean needsNeighbors(String worldName, AntiXrayConfig.Dimension dimension) {
    WorldProfile profile = profileFor(worldName,
        dimension == null ? AntiXrayConfig.Dimension.NORMAL : dimension);
    return profile.active() && !profile.obfuscateAll;
  }

  /**
   * 取「世界名 + 维度」对应的改写档案（解析优先级：world-overrides &gt; dimensions.&lt;维度&gt;）。
   *
   * <p>无覆盖段（或测试构造）直接返回维度档案（O(1)，零分配）；有覆盖段时按世界名在<b>该维度的缓存表</b>
   * 里解析一次并缓存（稳态 O(1)，热路径零新增分配）。
   */
  private WorldProfile profileFor(String worldName, AntiXrayConfig.Dimension dimension) {
    int dimensionIndex = dimension.ordinal();
    if (overrideProfiles.length == 0 || worldName == null || config == null) {
      return dimensionProfiles[dimensionIndex];
    }
    ConcurrentHashMap<String, WorldProfile> cache = overrideCache[dimensionIndex];
    WorldProfile matched = cache.get(worldName);
    if (matched == null) {
      int index = config.matchOverride(worldName);
      matched = index < 0 ? NO_OVERRIDE : overrideProfiles[index][dimensionIndex];
      cache.put(worldName, matched);
    }
    return matched == NO_OVERRIDE ? dimensionProfiles[dimensionIndex] : matched;
  }

  /**
   * 为单个 section 构建候选表（下标布局：{@code 0/1 = 回落表，2/3 = band 0，4/5 = band 1 …}），
   * 并做「位宽预算」联合筛选（P0-1）。
   *
   * <p>联合口径：统计<b>所有表合计</b>将引入的「不在调色板内」的不同新状态数（同一状态出现在
   * 多个表只算一次），超出当前剩余容量（{@code (1 << bitsPerBlock) − 调色板条目数}）即为超预算。
   * 超预算时各表只保留已在调色板内的候选（保持声明顺序与相对权重）；某表剔除后为空则保留原表
   * （逃生口：调色板内完全无候选才允许 grow）。预算关闭或预算充足时直接共享 profile 预构建表
   * （表本身不拷贝，仅分配一次候选表容器数组）。候选总量为个位数～几十，O(n²) 去重代价可忽略。
   *
   * @param freeAfterOut 单元素输出：候选表占用预算后的剩余调色板空位（供 use-block-below
   *                     判断「下方方块不在调色板内时还能否无升位写入」；预算关闭时为最大值）
   */
  private int[][] buildSectionTables(ChunkSection section, WorldProfile profile, int[] freeAfterOut) {
    int bandCount = profile.bandIds.length;
    int[][] out = new int[(bandCount + 1) << 1][];
    if (!paletteOptions.widthBudget()) {
      sharePrebuiltTables(out, profile);
      freeAfterOut[0] = Integer.MAX_VALUE;
      return out;
    }

    int total = profile.replacementIds.length;
    for (int[] ids : profile.bandIds) {
      total += ids.length;
    }
    boolean overBudget = false;
    int free = 0;
    int distinctNew = 0;
    if (total > 0) {
      int[] all = new int[total];
      int filled = 0;
      filled = appendAll(all, filled, profile.replacementIds);
      for (int[] ids : profile.bandIds) {
        filled = appendAll(all, filled, ids);
      }
      free = (1 << section.bitsPerBlock()) - section.paletteSize();
      for (int i = 0; i < filled; i++) {
        if (section.paletteContains(all[i])) {
          continue;
        }
        boolean duplicate = false;
        for (int j = 0; j < i; j++) {
          if (all[j] == all[i]) {
            duplicate = true;
            break;
          }
        }
        if (!duplicate) {
          distinctNew++;
        }
      }
      overBudget = distinctNew > free;
    }

    if (!overBudget) {
      // 预算充足：调色板装得下所有新状态，共享预构建表即可
      sharePrebuiltTables(out, profile);
      freeAfterOut[0] = Math.max(0, free - distinctNew);
      return out;
    }

    // 超预算：逐表剔除不在调色板内的候选；剔除后为空的表保留原表（逃生口，允许 grow）
    freeAfterOut[0] = 0;
    int[][] fallback = filterTable(profile.replacementIds, profile.replacementCum, section);
    out[0] = fallback[0];
    out[1] = fallback[1];
    for (int b = 0; b < bandCount; b++) {
      int[][] filtered = filterTable(profile.bandIds[b], profile.bandCum[b], section);
      out[(b + 1) << 1] = filtered[0];
      out[((b + 1) << 1) | 1] = filtered[1];
    }
    return out;
  }

  /** 把 profile 预构建的回落表与各 band 表直接共享进输出数组（零拷贝）。 */
  private static void sharePrebuiltTables(int[][] out, WorldProfile profile) {
    out[0] = profile.replacementIds;
    out[1] = profile.replacementCum;
    for (int b = 0; b < profile.bandIds.length; b++) {
      out[(b + 1) << 1] = profile.bandIds[b];
      out[((b + 1) << 1) | 1] = profile.bandCum[b];
    }
  }

  /** 把 ids 追加到 all 的 {@code from} 位置，返回新的写游标。 */
  private static int appendAll(int[] all, int from, int[] ids) {
    for (int id : ids) {
      all[from++] = id;
    }
    return from;
  }

  /**
   * 剔除表中「不在调色板内」的候选（保持声明顺序与相对权重）。
   *
   * @return 筛选后的 {@code {ids, cum}}；无候选可留（全被剔除）时原样返回——逃生口：
   *         调色板内完全无候选才允许 grow，保防护优先于包体
   */
  private static int[][] filterTable(int[] ids, int[] cum, ChunkSection section) {
    int kept = 0;
    for (int id : ids) {
      if (section.paletteContains(id)) {
        kept++;
      }
    }
    if (kept == 0 || kept == ids.length) {
      return new int[][] {ids, cum};
    }
    int[] outIds = new int[kept];
    int[] outCum = new int[kept];
    int n = 0;
    int running = 0;
    for (int i = 0; i < ids.length; i++) {
      if (section.paletteContains(ids[i])) {
        running += cum[i] - (i == 0 ? 0 : cum[i - 1]);
        outIds[n] = ids[i];
        outCum[n] = running;
        n++;
      }
    }
    return new int[][] {outIds, outCum};
  }

  /**
   * 返回覆盖该绝对高度的第一个 band 下标（声明序优先，P0-3 语义）。
   *
   * @return band 下标；无覆盖返回 {@code -1}（回落 {@code replacement-weights}）
   */
  private static int bandIndexFor(WorldProfile profile, int blockY) {
    for (int b = 0; b < profile.bandMinY.length; b++) {
      if (blockY >= profile.bandMinY[b] && blockY <= profile.bandMaxY[b]) {
        return b;
      }
    }
    return -1;
  }

  /** 按累计权重随机挑选一个伪装方块状态 id（表来自 profile 预构建或本 section 的预算筛选结果）。 */
  private static int pick(int[] ids, int[] cum, Random random) {
    int roll = random.nextInt(cum[cum.length - 1]);
    for (int i = 0; i < cum.length; i++) {
      if (roll < cum[i]) {
        return ids[i];
      }
    }
    return ids[ids.length - 1];
  }

  /**
   * 只做「解码侧统计」的诊断：section 数、出现过的方块状态种类数、命中目标方块的个数。
   *
   * <p><b>用途</b>：供「首次改写诊断」一次性日志区分两种失效——①解码出的状态 id 与目标 id 不匹配
   * （目标命中数 = 0，即 {@code shouldObfuscate} 恒为 false）；②匹配与替换都正常但写回没生效。
   * 因此本方法只回答「解码到底看到了什么」：不做遮挡判定、不改写任何字节。
   *
   * <p><b>开销</b>：会完整解码一次区块并遍历全部 4096×N 个方块状态，只允许在一次性诊断路径上调用，
   * 绝不得进入封包热路径。
   *
   * @return 诊断结果；区块解码失败（布局与预期不符）时返回 {@code null}
   */
  public Diagnostic diagnose(byte[] source, int sectionCount) {
    if (source == null || source.length == 0 || sectionCount <= 0) {
      return new Diagnostic(0, 0, 0);
    }

    Chunk chunk;
    try {
      chunk = codec.decode(source, sectionCount);
    } catch (RuntimeException exception) {
      return null;
    }

    try {
      BitSet seen = new BitSet();
      int targetMatches = 0;
      for (int sectionIndex = 0; sectionIndex < chunk.getSectionCount(); sectionIndex++) {
        ChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) {
          continue;
        }
        for (int state : section.readAllBlockStates()) {
          seen.set(state);
          if (defaultProfile.targets.get(state)) {
            targetMatches++;
          }
        }
      }
      return new Diagnostic(chunk.getSectionCount(), seen.cardinality(), targetMatches);
    } catch (RuntimeException exception) {
      return null;
    } finally {
      chunk.close();
    }
  }

  /** 异常摘要（供可观测的告警日志使用；不含堆栈，避免刷屏）。 */
  private static String describe(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank()
        ? throwable.getClass().getSimpleName()
        : throwable.getClass().getSimpleName() + ": " + message;
  }

  /**
   * 6 面正交遮挡判定（enclosed 模式）。
   *
   * <p><b>流体覆盖规则</b>：目标方块的<b>上方（y+1）</b>紧邻方块是流体（水/岩浆）时，该方向按「遮挡」
   * 处理——下界残骸常刷在岩浆里，若把上方岩浆当作「暴露面」，残骸就不会被伪装，透视端会看到它。
   * 只加在「上方」这一面（用户要求只判上方）：流体几乎总是从上方覆盖目标（残骸/矿脉被岩浆淹没），
   * 而侧面的流体要么极罕见、要么本身就在裸露矿洞里——若把侧面也当作遮挡反而会隐藏「本应显形」的矿。
   * 该规则只在目标方块的判定里生效，**不改动全局遮挡表**（避免影响非目标方块）。
   *
   * @param baseY 该 section 的起始 Y（区块内相对坐标）
   * @param index 该 section 内的元素序号（{@code y << 8 | z << 4 | x}）
   */
  private boolean isFullyOccluded(Chunk chunk, int baseY, int index, NeighborEdges neighbors) {
    int x = index & 15;
    int z = index >> 4 & 15;
    int y = baseY | (index >> 8 & 15);

    boolean above = isOccluding(chunk, y + 1, x, z, neighbors)
        || isFluidCoverAt(chunk, y + 1, x, z);
    return above
        && isOccluding(chunk, y - 1, x, z, neighbors)
        && isOccluding(chunk, y, x + 1, z, neighbors)
        && isOccluding(chunk, y, x - 1, z, neighbors)
        && isOccluding(chunk, y, x, z + 1, neighbors)
        && isOccluding(chunk, y, x, z - 1, neighbors);
  }

  /**
   * 「上方紧邻方块是否是流体」（流体覆盖规则用）。
   *
   * <p><b>跨 section</b>：{@code y} 可能落在相邻 section（当前方块处于 section 顶部 localY=15 时），
   * 也可能越出区块上边界（最顶 section 的顶部方块）——前者在 {@link Chunk} 内纯数据读取
   * （section 顺序与索引计算与 {@link #isOccluding} 完全一致），后者按「越界非流体」处理
   * （世界上下界之外不可能是流体，与 {@link #isOccluding} 的越界语义一致）。
   *
   * <p>x/z 恒在 {@code [0,15]}（只判 y+1 不改横向坐标），因此无需查邻块快照。
   */
  private boolean isFluidCoverAt(Chunk chunk, int y, int x, int z) {
    if (!fluidCover || fluidTable == null || y < 0) {
      return false;
    }
    int sectionIndex = y >> 4;
    if (sectionIndex >= chunk.getSectionCount()) {
      return false;
    }
    ChunkSection section = chunk.getSection(sectionIndex);
    if (section == null) {
      return false;
    }
    return fluidTable.test(section.getBlockState((y & 15) << 8 | z << 4 | x));
  }

  /** 区块内相对坐标处的方块是否遮挡；越出世界上下界视为未遮挡，越出本区块改查邻块贴边快照。 */
  private boolean isOccluding(Chunk chunk, int y, int x, int z, NeighborEdges neighbors) {
    if (y < 0) {
      return false;
    }

    int sectionIndex = y >> 4;
    if (sectionIndex >= chunk.getSectionCount()) {
      return false;
    }

    if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
      ChunkSection section = chunk.getSection(sectionIndex);
      if (section == null) {
        return false;
      }
      return occlusionTable.test(section.getBlockState((y & 15) << 8 | z << 4 | x));
    }

    return neighborOccluding(neighbors, x, y, z);
  }

  /** 越出本区块的相邻方块是否遮挡；邻块快照缺失时按配置策略处理。 */
  private boolean neighborOccluding(NeighborEdges neighbors, int x, int y, int z) {
    if (neighbors != null) {
      NeighborEdges.Side side;
      int localOther;
      if (x < 0) {
        side = NeighborEdges.Side.X_MINUS;
        localOther = z;
      } else if (x > 15) {
        side = NeighborEdges.Side.X_PLUS;
        localOther = z;
      } else if (z < 0) {
        side = NeighborEdges.Side.Z_MINUS;
        localOther = x;
      } else if (z > 15) {
        side = NeighborEdges.Side.Z_PLUS;
        localOther = x;
      } else {
        return false;
      }

      int state = neighbors.occluding(side, y, localOther);
      if (state != NeighborEdges.MISSING) {
        return state == 1;
      }
    }

    return missingPolicyHide;
  }

  /**
   * use-block-below（P2-6a）：取「下方紧邻方块」当伪装方块；不可用时返回 -1（退回既有策略）。
   *
   * <p><b>跨 section 读取</b>：y-1 可能在邻 section（当前 section 的 localY=0 时），读取在
   * {@link Chunk} 内完成（纯数据操作，section 顺序遍历保证下方 section 已处理完），不触碰 Bukkit。
   *
   * <p><b>为什么不会泄露真实矿物</b>：遍历按「section 升序 → 段内 index 升序」，下方方块（y 更小）
   * 一定先于当前方块处理——若下方是目标方块且已被伪装，读到的是伪装结果（伪装自然向上延续）；
   * 若下方是目标方块但<b>未被伪装</b>（enclosed 模式下暴露的矿），本方法显式拒绝复制目标状态，
   * 绝不把「真矿」当伪装方块写上去（不漏伪装红线）。目标判定用<b>当前世界的档案</b>
   * {@code profile.targets}——逐世界覆盖段新增的目标方块同样受保护（P0-2 × P2-6a 交叉）。
   *
   * <p><b>与位宽预算的关系</b>：下方方块状态可能不在本 section 调色板内（跨 section 读取）——
   * 引入它会新增调色板条目、有升位风险，是否放行由调用方按「候选表占用后的剩余空位」判定
   * （见 rewrite 循环），本方法只负责取值与可用性过滤。
   *
   * <p><b>可用性</b>：下方为空气/流体（无 section、越出世界下界同理）时不可用——
   * 用 {@code belowUsable} 过滤器判定（未提供时退回遮挡表判定，同样排除空气与流体）。
   *
   * @return 可用的伪装状态 id；不可用返回 -1
   */
  private int camouflageFromBelow(Chunk chunk, WorldProfile profile, int baseY, int index) {
    int localY = index >> 8 & 15;
    int belowState;
    if (localY > 0) {
      belowState = chunk.getSection(baseY >> 4).getBlockState(index - 256);
    } else {
      int belowSection = (baseY >> 4) - 1;
      if (belowSection < 0) {
        return -1;
      }
      ChunkSection section = chunk.getSection(belowSection);
      if (section == null) {
        return -1; // 全空气/缺失的 section：下方是空气，不可用
      }
      belowState = section.getBlockState((15 << 8) | (index & 0xFF));
    }
    if (belowState < 0 || profile.targets.get(belowState)) {
      return -1;
    }
    boolean usable = belowUsable != null
        ? belowUsable.test(belowState)
        : occlusionTable.test(belowState);
    return usable ? belowState : -1;
  }
}
