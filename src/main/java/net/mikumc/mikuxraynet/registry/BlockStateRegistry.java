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

  /** 探测状态总数的安全上限，防止映射异常时无限循环。 */
  private static final int MAX_STATE_SCAN = 1 << 20;

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

  private BlockStateRegistry(int uniqueBlockStateCount, int maxBitsPerBlockState, BitSet airStates,
      BitSet fluidStates, BitSet occludingStates) {
    this.uniqueBlockStateCount = uniqueBlockStateCount;
    this.maxBitsPerBlockState = maxBitsPerBlockState;
    this.airStates = airStates;
    this.fluidStates = fluidStates;
    this.occludingStates = occludingStates;
  }

  /**
   * 枚举服务端当前版本的全部方块状态并建立位图。
   *
   * <p>状态 id 在原版映射中是 0..N-1 的稠密区间，越界 id 会回落为空气（globalId=0），
   * 因此逐个探测到「回落到空气」即得到总数 N。
   *
   * @param extraOccluding    额外视为「遮挡」的方块名（覆盖表；可为空集）
   * @param extraNonOccluding 额外视为「不遮挡」的方块名（覆盖表；可为空集）
   * @return 已完全脱 PE 的注册表实例
   */
  public static BlockStateRegistry build(Collection<String> extraOccluding,
      Collection<String> extraNonOccluding) {
    Set<String> occludingOverrides = OcclusionRules.normalizeAll(extraOccluding);
    Set<String> nonOccludingOverrides = OcclusionRules.normalizeAll(extraNonOccluding);

    ClientVersion version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
    int count = probeStateCount(version);

    BitSet airStates = new BitSet(count);
    BitSet fluidStates = new BitSet(count);
    BitSet occludingStates = new BitSet(count);

    for (int id = 0; id < count; id++) {
      // clone=false：直接使用映射表内的共享实例，避免构建期产生大量临时对象
      WrappedBlockState state = WrappedBlockState.getByGlobalId(version, id, false);
      StateType type = state.getType();
      String name = type.getName();

      if (type.isAir()) {
        airStates.set(id);
      }
      if (state.isFluid() || type.getMaterialType() == MaterialType.BUBBLE_COLUMN) {
        fluidStates.set(id);
      }
      if (OcclusionRules.isOccluding(facts(type, name, state), occludingOverrides,
          nonOccludingOverrides)) {
        occludingStates.set(id);
      }
    }

    return new BlockStateRegistry(count, ceilLog2(count), airStates, fluidStates, occludingStates);
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

  /** 该状态是否为「整块不透明」（可作为遮挡面）。 */
  public boolean isOccluding(int blockId) {
    return blockId >= 0 && blockId < uniqueBlockStateCount && occludingStates.get(blockId);
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

  private static int probeStateCount(ClientVersion version) {
    int id = 0;
    while (id < MAX_STATE_SCAN) {
      if (WrappedBlockState.getByGlobalId(version, id, false).getGlobalId() != id) {
        break;
      }
      id++;
    }

    if (id == 0 || id >= MAX_STATE_SCAN) {
      throw new IllegalStateException(
          "方块状态映射异常：探测到的状态数量为 " + id + "，请检查 PacketEvents 版本是否匹配服务端");
    }
    return id;
  }

  private static int ceilLog2(int value) {
    return 32 - Integer.numberOfLeadingZeros(Math.max(1, value - 1));
  }
}