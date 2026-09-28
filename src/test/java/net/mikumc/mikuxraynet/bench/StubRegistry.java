package net.mikumc.mikuxraynet.bench;

import java.util.BitSet;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;

/**
 * 生产路径基准的<b>离线注册表桩</b>：不依赖 PacketEvents 平台初始化，只构造基准所需的少量方块状态
 * （石头 / 深板岩 / 矿 / 空气 / 水）的 {@link RegistryAccessor} 视图与一张遮挡位图，
 * 供测试侧装配真实的 {@link net.mikumc.mikuxraynet.antixray.ObfuscationProcessor}。
 *
 * <p><b>构造范围</b>：状态总数 1&lt;&lt;15（直接格式位宽 15），语义约定与 {@link BenchFixtures} 一致——
 * 仅 id {@link BenchFixtures#AIR} 为空气；石头、深板岩与 6 种矿都按「整块不透明」处理（矿石是实心方块）；
 * {@link BenchFixtures#WATER} 属流体、不遮挡。遮挡位图以 {@code BitSet} 承载，对外暴露
 * {@link #isOccluding(int)}——与生产 {@code registry.BlockStateRegistry#isOccluding} 同一口径。
 *
 * <p><b>局限</b>：① 只覆盖基准所需的十余个状态，不含真实注册表的全量状态、台阶/玻璃等形状与材质规则
 * 以及用户覆盖表（{@code occlusion.extra-*}）；② 未实测服务端全局 id 映射，id 为合成的稠密整数；
 * ③ 仅供离线基准使用，不得进入生产代码——生产判定一律以 {@code registry.BlockStateRegistry} 为准。
 */
final class StubRegistry implements RegistryAccessor {

  private static final int STATE_COUNT = 1 << 15;
  private static final int BITS = 15;

  private final BitSet occluding;

  private StubRegistry(BitSet occluding) {
    this.occluding = occluding;
  }

  /** 按基准语义构造桩：石头 / 深板岩 / 矿遮挡，空气 / 水不遮挡。 */
  static StubRegistry of() {
    BitSet occluding = new BitSet(STATE_COUNT);
    occluding.set(BenchFixtures.STONE);
    occluding.set(BenchFixtures.DEEPSLATE);
    for (int ore : BenchFixtures.ORE_STATES) {
      occluding.set(ore);
    }
    return new StubRegistry(occluding);
  }

  @Override
  public int getUniqueBlockStateCount() {
    return STATE_COUNT;
  }

  @Override
  public int getMaxBitsPerBlockState() {
    return BITS;
  }

  @Override
  public boolean isAir(int blockId) {
    return blockId == BenchFixtures.AIR;
  }

  @Override
  public boolean isFluid(int blockId) {
    return blockId == BenchFixtures.WATER;
  }

  /** 该状态是否整块不透明（可作为遮挡面）。口径与 {@code BlockStateRegistry#isOccluding} 一致。 */
  boolean isOccluding(int blockId) {
    return blockId >= 0 && blockId < STATE_COUNT && occluding.get(blockId);
  }
}