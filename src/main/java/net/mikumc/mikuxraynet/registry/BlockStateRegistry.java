package net.mikumc.mikuxraynet.registry;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.MaterialType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.enums.Type;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;

/**
 * 方块状态注册表：把 PacketEvents 的方块状态语义压缩成紧凑位图，供 codec 与反矿透判定使用。
 *
 * <p><b>只在启动期构建一次</b>（{@link #build} 需要 PacketEvents 已就绪），构建完成后实例内部
 * 只保留 {@code int + BitSet}，热路径不再触碰任何 PacketEvents 类型。
 *
 * <p><b>遮挡（occluding）判定</b>：优先采用<b>逐状态真值</b>——启动期把每个状态的
 * {@code WrappedBlockState#toString()} 交给注入的 {@link StateOcclusionOracle} 解析成
 * {@code BlockData#isOccluding()}（服务端权威、逐状态，即「是否挡光」）。单个状态解析失败时
 * 回落到 {@link OcclusionRules} 的启发式，并计入回落数；<b>绝不让单个状态失败影响整体</b>。
 * 服务端真值与「玩家视觉能否看穿」在两个方向上各有一处偏差，均由 {@link OcclusionRules} 归一到
 * 「视觉口径」这唯一入口：{@code barrier}（服务端说遮挡、视觉不可见 → 判非遮挡）与
 * 岩浆（服务端说不遮挡、视觉不透明 → 判遮挡）；两处例外都只定义一处
 * （见 {@code OcclusionRules#visuallyInvisibleOccluder} / {@code #visuallyOpaqueFluid}）。
 *
 * <p>启发式回落后，本类把 PE 的状态语义摊平成 {@link OcclusionRules.Facts}：
 * <ul>
 *   <li>{@code air} ← {@code StateType#isAir()}；</li>
 *   <li>{@code solid} ← {@code StateType#isSolid()}；</li>
 *   <li>{@code exceedsCube} ← {@code StateType#exceedsCube()}（形状超出整方块的栅栏/墙/竹子/脚手架等）；</li>
 *   <li>{@code materialOccluding} ← 材质类别是否不属于装饰性类别；</li>
 *   <li>{@code doubleSlab} ← 名称以 {@code _slab} 结尾且状态属性 {@code type == DOUBLE}。</li>
 * </ul>
 * 判定结果可被 {@code antixray.yml} 的 {@code occlusion.extra-occluding} /
 * {@code occlusion.extra-non-occluding} 覆盖（用户自行纠正判定的出口，优先级最高）。
 */
public final class BlockStateRegistry implements RegistryAccessor {

  /** 探测状态 id 的安全扫描上限（防御映射异常时无限循环）；远大于任何真实映射规模（实测约 3.2 万）。 */
  private static final int MAX_STATE_SCAN = 1 << 18;

  /** 回落启发式的「代表性方块名」采样上限（仅用于日志/导出的 triage，避免整表失败时打印爆屏）。 */
  private static final int MAX_FALLBACK_NAME_SAMPLE = 64;

  /** 「探测结果不确定需保守放大位宽」的一次性 WARN 闸门。 */
  private static final AtomicBoolean UNCERTAIN_PROBE_WARNED = new AtomicBoolean();

  /** 材质类别明确不属于「整块不透明」的集合。 */
  private static final Set<MaterialType> NON_OCCLUDING_MATERIALS = EnumSet.of(
      MaterialType.AIR, MaterialType.STRUCTURAL_AIR, MaterialType.PORTAL, MaterialType.CLOTH_DECORATION,
      MaterialType.PLANT, MaterialType.WATER_PLANT, MaterialType.REPLACEABLE_PLANT,
      MaterialType.REPLACEABLE_FIREPROOF_PLANT, MaterialType.REPLACEABLE_WATER_PLANT,
      MaterialType.WATER, MaterialType.BUBBLE_COLUMN, MaterialType.LAVA, MaterialType.TOP_SNOW,
      MaterialType.FIRE, MaterialType.DECORATION, MaterialType.WEB, MaterialType.BUILDABLE_GLASS,
      MaterialType.LEAVES, MaterialType.GLASS, MaterialType.BARRIER, MaterialType.POWDER_SNOW,
      MaterialType.FROGSPAWN, MaterialType.BAMBOO_SAPLING, MaterialType.CACTUS);

  private final int uniqueBlockStateCount;
  private final int maxBitsPerBlockState;
  private final BitSet airStates;
  private final BitSet fluidStates;
  private final BitSet occludingStates;
  /**
   * 实体遮挡口径表：只供实体剔除的异步位图使用，内容与伪装口径<b>同源</b>（同一逐状态真值），
   * 仅少了 {@code occlusion.extra-*} 用户覆盖。
   * {@code null} = 未构建，此时 {@link #isEntityOccluding} 回落 {@link #occludingStates}。
   */
  private final EntityOcclusionTable entityOcclusionTable;

  /** 空 id 数组（{@link #allStateIds(String)} 未命中时的返回值，避免每次分配）。 */
  private static final int[] EMPTY_IDS = new int[0];

  /**
   * 方块类型名（{@link OcclusionRules#normalize(String)} 归一化后）→ 它的<b>全部</b>状态 id。
   *
   * <p><b>为什么需要</b>：{@code hide-blocks} 填的是「方块注册名」，语义上应隐藏该方块的<b>所有</b>
   * 状态；而 {@link #resolveStateId(String)} 只解析出「默认状态」一个 id。多状态方块（箱子有 24 种
   * 朝向/含水组合、楼梯、含水方块等）若只藏默认态，其余状态照旧下发——透视端仍能看到它们，而诊断面板
   * 完全看不出来（被藏的那一个默认态仍在正常计数）。这里在启动期顺手记下「名称 → 全部状态 id」，
   * 供隐藏清单使用。仅启动期构建一次，运行期只做一次哈希查找。
   */
  private final Map<String, int[]> stateIdsByBlock;

  /** 逐状态真值解析成功（并生效）的状态数。 */
  private final int truthStateCount;
  /** 逐状态真值解析失败、回落启发式的状态数。 */
  private final int heuristicFallbackStateCount;
  /** 命中 {@code occlusion.extra-*} 覆盖表的状态数（覆盖优先于真值）。 */
  private final int overrideHitStateCount;
  /** 伪装口径与实体口径判定不同的状态数（由覆盖表造成；无覆盖时应为 0）。 */
  private final int occlusionDriftStateCount;
  /** 回落启发式涉及的代表性方块名（去重、按名排序、上限内截断），供启动日志与导出 triage。 */
  private final List<String> heuristicFallbackNames;

  private BlockStateRegistry(int uniqueBlockStateCount, int maxBitsPerBlockState, BitSet airStates,
      BitSet fluidStates, BitSet occludingStates,
      EntityOcclusionTable entityOcclusionTable, int truthStateCount, int heuristicFallbackStateCount,
      int overrideHitStateCount, int occlusionDriftStateCount, List<String> heuristicFallbackNames,
      Map<String, int[]> stateIdsByBlock) {
    this.uniqueBlockStateCount = uniqueBlockStateCount;
    this.maxBitsPerBlockState = maxBitsPerBlockState;
    this.airStates = airStates;
    this.fluidStates = fluidStates;
    this.occludingStates = occludingStates;
    this.entityOcclusionTable = entityOcclusionTable;
    this.truthStateCount = truthStateCount;
    this.heuristicFallbackStateCount = heuristicFallbackStateCount;
    this.overrideHitStateCount = overrideHitStateCount;
    this.occlusionDriftStateCount = occlusionDriftStateCount;
    this.heuristicFallbackNames = heuristicFallbackNames;
    this.stateIdsByBlock = stateIdsByBlock;
  }

  /**
   * 枚举服务端当前版本的全部方块状态并建立位图。
   *
   * <p><b>为什么不能「遇到第一个空洞就停」</b>：旧实现逐个探测到「回落到空气」即认定总数 N，
   * 这隐含假设「状态 id 是 0..N-1 的连续稠密区间」。一旦 PE 映射<b>存在空洞</b>，第一次中断处
   * 就会把 id 统计偏小，进而使 {@code M = ceilLog2(N)} 位宽偏小——高位状态被漏判、反矿透<b>静默失效</b>。
   * 这里改为<b>扫描整个 id 区间取实际最大有效 id</b>（空洞处跳过、继续向后），按 {@code 最大 id + 1}
   * 的口径确定覆盖范围与位宽。
   *
   * @param extraOccluding    额外视为「遮挡」的方块名（覆盖表；可为空集）
   * @param extraNonOccluding 额外视为「不遮挡」的方块名（覆盖表；可为空集）
   * @return 已完全脱 PE 的注册表实例
   */
  public static BlockStateRegistry build(Collection<String> extraOccluding,
      Collection<String> extraNonOccluding) {
    return build(extraOccluding, extraNonOccluding, null);
  }

  /**
   * 同 {@link #build(Collection, Collection)}，但把「探测结果不确定」这类告警交给调用方的插件 logger。
   *
   * <p><b>为什么要传 logger</b>：{@code plugin.getLogger()} 是 Bukkit 的 {@code PluginLogger}（名字为
   * 插件主类全名、带 {@code [MikuXrayNet]} 前缀、受插件日志级别统一约束），而
   * {@code Logger.getLogger("MikuXrayNet")} 是另一个 JUL logger —— 那条告警既没有前缀、也不随插件日志
   * 配置走，运维很难把它与插件关联起来。传入为 {@code null} 时才回落到 JUL 兜底（离线单测场景）。
   */
  public static BlockStateRegistry build(Collection<String> extraOccluding,
      Collection<String> extraNonOccluding, Logger logger) {
    return build(extraOccluding, extraNonOccluding, logger, null);
  }

  /**
   * 完整构建：追加「逐状态真值」来源，并由它同时驱动伪装口径与实体口径（两套同源）。
   *
   * @param oracle 逐状态遮挡真值来源（服务端 {@code BlockData#isOccluding()}）；{@code null} = 不提供，
   *               全部状态回落启发式（离线单测场景）
   */
  public static BlockStateRegistry build(Collection<String> extraOccluding,
      Collection<String> extraNonOccluding, Logger logger, StateOcclusionOracle oracle) {
    Set<String> occludingOverrides = OcclusionRules.normalizeAll(extraOccluding);
    Set<String> nonOccludingOverrides = OcclusionRules.normalizeAll(extraNonOccluding);

    ClientVersion version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
    Probe probe = probeStates(version);
    int count = probe.stateCount();
    int width = ceilLog2(count);
    if (probe.uncertain()) {
      // 扫到上限仍存在有效状态：无法确定映射是否更大 → 位宽宁大不小（宁可多占几 bit，绝不漏判高位状态）
      width = Math.max(width, ceilLog2(MAX_STATE_SCAN));
      warnUncertainProbe(count, width, logger);
    }

    BitSet airStates = new BitSet(count);
    BitSet fluidStates = new BitSet(count);
    BitSet occludingStates = new BitSet(count);
    // 实体口径 = 逐状态真值（不含 extra-* 覆盖）；与伪装口径同源，仅差覆盖表。
    BitSet entityOccludingStates = new BitSet(count);
    int truthCount = 0;
    int fallbackCount = 0;
    int overrideHitCount = 0;
    int driftCount = 0;
    Set<String> fallbackNames = new LinkedHashSet<>();
    // 「方块名 → 全部状态 id」收集器（构建期一次性；见 stateIdsByBlock 的说明）
    Map<String, List<Integer>> idsByName = new HashMap<>();

    for (int id = 0; id < count; id++) {
      // 空洞 id（映射异常）本身不是有效状态：直接跳过，不参与任何位图
      WrappedBlockState state = getByGlobalIdOrNull(version, id);
      if (state == null) {
        continue;
      }
      StateType type = state.getType();
      String name = type.getName();

      if (type.isAir()) {
        airStates.set(id);
      }
      if (state.isFluid() || type.getMaterialType() == MaterialType.BUBBLE_COLUMN) {
        fluidStates.set(id);
      }

      OcclusionRules.Facts facts = facts(type, name, state);
      // 逐状态真值：单个状态解析失败返回 null（异常已被 oracle 吞掉），据此计数并回落启发式。
      Boolean truth = resolveTruth(oracle, state);
      if (truth != null) {
        truthCount++;
      } else {
        fallbackCount++;
        if (fallbackNames.size() < MAX_FALLBACK_NAME_SAMPLE) {
          fallbackNames.add(name);
        }
      }
      // 基线（不含覆盖）= 实体口径：真值优先，失败才走启发式。
      boolean baseline = OcclusionRules.baseVerdict(facts, truth);
      if (baseline) {
        entityOccludingStates.set(id);
      }
      boolean camouflage = OcclusionRules.isOccluding(facts, occludingOverrides,
          nonOccludingOverrides, truth);
      if (camouflage) {
        occludingStates.set(id);
      }
      if (camouflage != baseline) {
        driftCount++; // 只有覆盖表能造成伪装/实体口径分歧
      }
      String key = OcclusionRules.normalize(name);
      idsByName.computeIfAbsent(key, ignored -> new ArrayList<>()).add(id);
      if (occludingOverrides.contains(key) || nonOccludingOverrides.contains(key)) {
        overrideHitCount++; // 该状态命中用户覆盖表（判定以覆盖表为准）
      }
    }

    List<String> fallbackNameList = new ArrayList<>(fallbackNames);
    fallbackNameList.sort(String::compareTo);
    // 冻结为「名称 → int[]」，运行期只读、无装箱
    Map<String, int[]> stateIdsByBlock = new HashMap<>(idsByName.size() * 2);
    for (Map.Entry<String, List<Integer>> entry : idsByName.entrySet()) {
      List<Integer> collected = entry.getValue();
      int[] array = new int[collected.size()];
      for (int i = 0; i < array.length; i++) {
        array[i] = collected.get(i);
      }
      stateIdsByBlock.put(entry.getKey(), array);
    }
    return new BlockStateRegistry(count, width, airStates, fluidStates, occludingStates,
        EntityOcclusionTable.build(entityOccludingStates),
        truthCount, fallbackCount, overrideHitCount, driftCount, List.copyOf(fallbackNameList),
        stateIdsByBlock);
  }

  /** 逐状态真值来源：无 oracle（离线）返回 {@code null}；状态字符串不可得也返回 {@code null}（回落）。 */
  private static Boolean resolveTruth(StateOcclusionOracle oracle, WrappedBlockState state) {
    if (oracle == null) {
      return null;
    }
    try {
      String stateString = state.toString();
      if (stateString == null) {
        return null;
      }
      return oracle.resolve(stateString);
    } catch (Throwable throwable) {
      // 单个状态取字符串/解析失败：不中断整个构建，按「真值不可用」回落
      return null;
    }
  }

  /** 方块名称 → 默认状态的全局 id；未知名称返回 -1。仅启动期用于解析配置。 */
  public static int resolveStateId(String name) {
    return resolveStateId(PacketEvents.getAPI().getServerManager().getVersion().toClientVersion(), name);
  }

  /** 方块名称 → 默认状态的全局 id；未知名称返回 -1。仅启动期用于解析配置。 */
  public static int resolveStateId(ClientVersion version, String name) {
    if (name == null || name.isBlank()) {
      return -1;
    }

    String key = name.trim().toLowerCase(Locale.ROOT);
    if (key.startsWith("minecraft:")) {
      key = key.substring("minecraft:".length());
    }

    StateType type = StateTypes.getByName(key);
    if (type == null) {
      return -1;
    }
    return WrappedBlockState.getDefaultState(version, type, false).getGlobalId();
  }

  /**
   * 方块类型名 → 它的<b>全部</b>状态 id（未知名称返回空数组）。仅启动期用于解析配置。
   *
   * <p>与 {@link #resolveStateId(String)} 的区别：后者只给「默认状态」一个 id，本方法给该方块的
   * <b>所有</b>状态 id。{@code hide-blocks} 的语义是「隐藏这个方块」，因此必须用本方法——否则
   * 多状态方块（箱子 24 态、楼梯、含水方块等）只会藏住默认态，其余状态照旧下发给客户端。
   *
   * <p>名称容忍 {@code namespace:} 前缀与大小写差异，归一化口径与 {@link OcclusionRules#normalize} 一致。
   *
   * <p><b>返回的是副本</b>：内部表是启动期构建一次、全生命周期共享的只读结构，若把内部数组直接交出去，
   * 调用方一次就地排序/去重/截断就会永久污染后续所有 {@code hide-blocks} 解析（且没有任何防御）。
   * 本方法只在启动期按「配置里的方块种数」调用，复制开销可忽略。
   */
  public int[] allStateIds(String name) {
    if (name == null || name.isBlank()) {
      return EMPTY_IDS;
    }
    int[] ids = stateIdsByBlock.get(OcclusionRules.normalize(name));
    return ids == null ? EMPTY_IDS : ids.clone();
  }

  @Override
  public int getUniqueBlockStateCount() {
    return uniqueBlockStateCount;
  }

  @Override
  public int getMaxBitsPerBlockState() {
    return maxBitsPerBlockState;
  }

  @Override
  public boolean isAir(int blockId) {
    return blockId >= 0 && blockId < uniqueBlockStateCount && airStates.get(blockId);
  }

  @Override
  public boolean isFluid(int blockId) {
    return blockId >= 0 && blockId < uniqueBlockStateCount && fluidStates.get(blockId);
  }

  /**
   * 该状态是否为「整块不透明」（可作为遮挡面）——<b>反矿透伪装口径</b>。
   *
   * <p>优先取逐状态真值（服务端 {@code BlockData#isOccluding()}），真值不可用的状态才回落 PE 状态语义启发式；
   * 两者都要经 {@link OcclusionRules} 的视觉口径校正（barrier 判非遮挡、岩浆判遮挡），并可被
   * {@code occlusion.extra-*} 覆盖表改写（覆盖优先级最高）。
   */
  public boolean isOccluding(int blockId) {
    return blockId >= 0 && blockId < uniqueBlockStateCount && occludingStates.get(blockId);
  }

  /**
   * 该状态在<b>实体遮挡口径</b>下是否遮挡（与伪装口径<b>同源</b>：同一逐状态真值，仅不含 {@code extra-*} 覆盖）。
   *
   * <p>供实体剔除的异步位图使用；未构建实体口径表时回落 {@link #isOccluding}。
   */
  public boolean isEntityOccluding(int blockId) {
    if (entityOcclusionTable == null) {
      return isOccluding(blockId);
    }
    return blockId >= 0 && blockId < uniqueBlockStateCount
        && entityOcclusionTable.isOccluding(blockId);
  }

  /** 被判定为「遮挡」的状态数（启动自检日志用，便于一眼看出遮挡表是否全空）。 */
  public int occludingStateCount() {
    return occludingStates.cardinality();
  }

  /** 实体遮挡口径下的遮挡状态数；未构建该表时为 -1。 */
  public int entityOccludingStateCount() {
    return entityOcclusionTable == null ? -1 : entityOcclusionTable.occludingStateCount();
  }

  /** 实体口径里「逐状态真值不可用、回落启发式」的状态数；未构建实体口径表时为 -1。 */
  public int entityOcclusionFallbackCount() {
    return entityOcclusionTable == null ? -1 : heuristicFallbackStateCount;
  }

  /** 逐状态真值解析成功（并生效）的状态数。 */
  public int truthStateCount() {
    return truthStateCount;
  }

  /** 逐状态真值解析失败、回落启发式的状态数。 */
  public int heuristicFallbackStateCount() {
    return heuristicFallbackStateCount;
  }

  /** 命中 {@code occlusion.extra-*} 覆盖表、判定以覆盖表为准的状态数。 */
  public int overrideHitStateCount() {
    return overrideHitStateCount;
  }

  /** 伪装口径与实体口径判定不同的状态数（由覆盖表造成；无覆盖时应为 0，即两套口径完全一致）。 */
  public int occlusionDriftStateCount() {
    return occlusionDriftStateCount;
  }

  /** 回落启发式涉及的代表性方块名（去重、按名排序、上限内截断），供启动日志与导出 triage。 */
  public List<String> heuristicFallbackNames() {
    return heuristicFallbackNames;
  }

  /** 把 PE 状态摊平为纯判定输入（不保留任何 PE 引用）。 */
  private static OcclusionRules.Facts facts(StateType type, String name, WrappedBlockState state) {
    return new OcclusionRules.Facts(
        type.isAir(),
        type.isSolid(),
        type.exceedsCube(),
        !NON_OCCLUDING_MATERIALS.contains(type.getMaterialType()),
        isDoubleSlab(name, state),
        name);
  }

  /** 双台阶：名称属于台阶族且状态属性 type=double（此时它填满整方块，可以与整块石头一样遮挡）。 */
  private static boolean isDoubleSlab(String name, WrappedBlockState state) {
    if (!name.endsWith("_slab")) {
      return false;
    }
    try {
      return state.getTypeData() == Type.DOUBLE;
    } catch (RuntimeException exception) {
      // 该状态没有 type 属性（映射异常）：按非双台阶处理
      return false;
    }
  }

  /** 探测结果：覆盖范围（实际最大有效 id + 1）与「是否无法确定上限」。 */
  private record Probe(int stateCount, boolean uncertain) {
  }

  /**
   * 扫描整个 id 区间，取<b>实际最大有效 id</b>（空洞处跳过并继续向后），据此给出覆盖范围。
   *
   * <p><b>刻意不早停</b>：早停会在「第一个空洞」处误判结束，使统计偏小、位宽偏小——正是本次要修的缺陷。
   *
   * @return 覆盖范围（最大有效 id + 1）与「扫满上限仍未确定上限」标记
   */
  private static Probe probeStates(ClientVersion version) {
    int maxValidId = -1;
    for (int id = 0; id < MAX_STATE_SCAN; id++) {
      if (isValidState(version, id)) {
        maxValidId = id;
      }
    }

    if (maxValidId < 0) {
      throw new IllegalStateException("方块状态映射异常：在 0.." + (MAX_STATE_SCAN - 1)
          + " 内探测不到任何有效状态，请检查 PacketEvents 版本是否匹配服务端");
    }
    int stateCount = maxValidId + 1;
    // 扫满上限仍存在有效状态：无法确定映射是否更大 → 交由调用方保守放大位宽（宁大不小）
    return new Probe(stateCount, stateCount >= MAX_STATE_SCAN);
  }

  /** id 处是否为有效状态（未越界、且未落在空洞）：取回状态的 globalId 必须等于 id。 */
  private static boolean isValidState(ClientVersion version, int id) {
    return getByGlobalIdOrNull(version, id) != null;
  }

  /** 取 id 对应的状态；越界或落在空洞（返回状态的 globalId 不等于 id）时返回 {@code null}，绝不误取。 */
  private static WrappedBlockState getByGlobalIdOrNull(ClientVersion version, int id) {
    try {
      WrappedBlockState state = WrappedBlockState.getByGlobalId(version, id, false);
      return state != null && state.getGlobalId() == id ? state : null;
    } catch (Throwable throwable) {
      return null;
    }
  }

  /**
   * 探测结果不确定时的一次性中文 WARN：位宽已按上限保守放大（仅影响体积、不影响正确性）。
   *
   * <p>优先走调用方传入的插件 logger（带 {@code [MikuXrayNet]} 前缀、受插件日志级别约束）；
   * 未传入（离线单测）时才回落到 JUL 兜底。
   */
  private static void warnUncertainProbe(int stateCount, int width, Logger logger) {
    if (!UNCERTAIN_PROBE_WARNED.compareAndSet(false, true)) {
      return;
    }
    String message = "方块状态映射探测结果不确定：扫描到上限仍存在有效状态"
        + "（最大 id + 1 = " + stateCount + "），已保守把直接格式位宽放大到 " + width
        + "（宁大不小，避免高位状态被漏判导致反矿透静默失效）。请检查 PacketEvents 版本是否匹配服务端。";
    (logger != null ? logger : Logger.getLogger("MikuXrayNet")).warning(message);
  }

  private static int ceilLog2(int value) {
    return 32 - Integer.numberOfLeadingZeros(Math.max(1, value - 1));
  }
}