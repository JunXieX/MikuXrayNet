package net.mikumc.mikuxraynet.registry;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.MaterialType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import java.util.BitSet;
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
 * <p>遮挡（occluding）语义说明：PacketEvents 的 {@link StateType} 没有 NMS 的
 * {@code isSolidRender}，故用「实体性 + 形状不超出整方块 + 材质非薄片」近似：
 * <ul>
 *   <li>空气、非实体（{@code isSolid()==false}）、形状超出整方块（{@code exceedsCube()}）一律不算遮挡；</li>
 *   <li>材质属于植物/玻璃/树叶/液体/薄雪/蛛网等装饰性类别的不算遮挡；</li>
 *   <li>名称以台阶/楼梯/栏杆/墙/板/门/活板门/压力板/按钮/地毯/告示牌等薄片族后缀结尾的不算遮挡。</li>
 * </ul>
 * 该近似可能把少量确实能遮挡的方块（如铁砧、双台阶）判为「不遮挡」，方向是「更保守地判定为可被看到」。
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

  /** 薄片方块名称后缀（不足以填满整方块，故不能遮挡）。 */
  private static final String[] THIN_NAME_SUFFIXES = {
      "_stairs", "_slab", "_fence", "_fence_gate", "_wall", "_pane", "_door", "_trapdoor",
      "_pressure_plate", "_button", "_carpet", "_sign", "_banner", "_bed", "_rail", "_torch",
      "_lantern", "_chain", "_bars", "_candle", "_campfire", "_flower_pot", "_head", "_skull",
      "_sapling", "_sprouts", "_roots", "_fan", "_bush"};

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
   * @return 已完全脱 PE 的注册表实例
   */
  public static BlockStateRegistry build() {
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
      if (isOccluding(type, name)) {
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

  private static boolean isOccluding(StateType type, String name) {
    if (type.isAir() || !type.isSolid() || type.exceedsCube()) {
      return false;
    }
    if (NON_OCCLUDING_MATERIALS.contains(type.getMaterialType())) {
      return false;
    }
    for (String suffix : THIN_NAME_SUFFIXES) {
      if (name.endsWith(suffix)) {
        return false;
      }
    }
    return true;
  }
}