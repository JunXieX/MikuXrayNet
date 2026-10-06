package net.mikumc.mikuxraynet.registry;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.MaterialType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.enums.Type;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
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
 * <p><b>遮挡（occluding）判定</b>：规则见 {@link OcclusionRules}（纯逻辑、可单测）；本类只负责把
 * PE 的状态语义摊平成 {@link OcclusionRules.Facts}：
 * <ul>
 *   <li>{@code air} ← {@code StateType#isAir()}；</li>
 *   <li>{@code solid} ← {@code StateType#isSolid()}；</li>
 *   <li>{@code exceedsCube} ← {@code StateType#exceedsCube()}（形状超出整方块的栅栏/墙/竹子/脚手架等）；</li>
 *   <li>{@code materialOccluding} ← 材质类别是否不属于装饰性类别；</li>
 *   <li>{@code doubleSlab} ← 名称以 {@code _slab} 结尾且状态属性 {@code type == DOUBLE}（双台阶填满整方块，
 *       只看方块名会误判为「不遮挡」）。</li>
 * </ul>
 * 判定结果可被 {@code antixray.yml} 的 {@code occlusion.extra-occluding} /
 * {@code occlusion.extra-non-occluding} 覆盖（用户自行纠正判定的出口）。
 */
public final class BlockStateRegistry implements RegistryAccessor {

  /** 探测状态 id 的安全扫描上限（防御映射异常时无限循环）；远大于任何真实映射规模（实测约 3.2 万）。 */
  private static final int MAX_STATE_SCAN = 1 << 18;

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
  /** 「流体覆盖」掩码：只有水/岩浆（含流动变体）与水柱，**不含含水方块**（见 {@link #isFluidCover}）。 */
  private final BitSet fluidCoverStates;
  private final BitSet occludingStates;

  private BlockStateRegistry(int uniqueBlockStateCount, int maxBitsPerBlockState, BitSet airStates,
      BitSet fluidStates, BitSet fluidCoverStates, BitSet occludingStates) {
    this.uniqueBlockStateCount = uniqueBlockStateCount;
    this.maxBitsPerBlockState = maxBitsPerBlockState;
    this.airStates = airStates;
    this.fluidStates = fluidStates;
    this.fluidCoverStates = fluidCoverStates;
    this.occludingStates = occludingStates;
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
    BitSet fluidCoverStates = new BitSet(count);
    BitSet occludingStates = new BitSet(count);

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
      // 流体覆盖掩码：只认「本体就是流体」的材质（WATER / LAVA / 水柱）。
      // 真机 PE 实测：isFluid() 把含水状态也算作流体（32366 个状态里 11760 个 isFluid=true，
      // 其中 11728 个是含水的台阶/栅栏/告示牌等，二者不可混用）——含水方块本体不是流体，
      // 上方放一块含水台阶不应让下方矿「保持伪装」。
      if (isFluidMaterial(type.getMaterialType())) {
        fluidCoverStates.set(id);
      }
      if (OcclusionRules.isOccluding(facts(type, name, state), occludingOverrides,
          nonOccludingOverrides)) {
        occludingStates.set(id);
      }
    }

    return new BlockStateRegistry(count, width, airStates, fluidStates, fluidCoverStates,
        occludingStates);
  }

  /** 本体即流体的材质：水（含流动变体与水柱）与岩浆。 */
  private static boolean isFluidMaterial(MaterialType material) {
    return material == MaterialType.WATER
        || material == MaterialType.LAVA
        || material == MaterialType.BUBBLE_COLUMN;
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
   * 流体覆盖掩码查询（水/岩浆/水柱；<b>不含含水方块</b>）：供反矿透「目标方块上方是流体则按遮挡处理、
   * 且显形侧不显形」的规则使用。与 {@link #isFluid}（方块计数口径，含含水方块）刻意分开——
   * 见 {@link #build} 中的实测说明。
   */
  @Override
  public boolean isFluidCover(int blockId) {
    return blockId >= 0 && blockId < uniqueBlockStateCount && fluidCoverStates.get(blockId);
  }

  /** 该状态是否为「整块不透明」（可作为遮挡面）。 */
  public boolean isOccluding(int blockId) {
    return blockId >= 0 && blockId < uniqueBlockStateCount && occludingStates.get(blockId);
  }

  /** 被判定为「遮挡」的状态数（启动自检日志用，便于一眼看出遮挡表是否全空）。 */
  public int occludingStateCount() {
    return occludingStates.cardinality();
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