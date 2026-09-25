
package net.mikumc.mikuxraynet.codec;

/**
 * codec 需要的方块注册表最小视图，使本模块自包含（不依赖任何平台层包）。
 *
 * <p>关键约束：方块状态 id 必须是全局唯一的整数下标，取值范围 {@code [0, getUniqueBlockStateCount())}；
 * 由此 {@link #getMaxBitsPerBlockState()} 应为 {@code ceil(log2(getUniqueBlockStateCount()))}，
 * 它决定直接（direct）格式的位宽——若与出站封包实际位宽不一致，重编码会改写位宽。
 */
public interface RegistryAccessor {

  int getUniqueBlockStateCount();

  int getMaxBitsPerBlockState();

  boolean isAir(int blockId);

  /**
   * 该状态是否为流体（<b>方块计数口径</b>：含含水方块，与客户端区块 section 的 fluidCount 一致）。
   * 仅供 codec 统计使用，绝不可擅自改成「只有水/岩浆」——那会让改写后的 section 流体计数与客户端不符。
   */
  boolean isFluid(int blockId);

  /**
   * 「流体覆盖」判定用的流体掩码（水/岩浆，含静止与流动变体；含水的台阶/栅栏等<b>不算</b>）。
   *
   * <p>只用于反矿透的「目标方块上方是流体则按遮挡处理 / 不显形」规则，与 {@link #isFluid} 的
   * 方块计数口径解耦。默认回落 {@link #isFluid}，使测试桩（只实现了 isFluid）行为与既有语义一致。
   */
  default boolean isFluidCover(int blockId) {
    return isFluid(blockId);
  }
}