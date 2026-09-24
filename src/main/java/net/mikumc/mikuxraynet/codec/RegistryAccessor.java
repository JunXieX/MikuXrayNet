
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

  boolean isFluid(int blockId);
}