
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

  /**
   * 调色板反查表（{@code byValue}）的容量：<b>必须覆盖服务端可能下发的全部状态 id</b>，而不只是
   * PacketEvents 能映射的 vanilla 状态数。
   *
   * <p>原因：装了自定义方块插件（如 CraftEngine）的服务端会在 vanilla 之后追加状态 id，调色板里会读到
   * 超出 vanilla 映射的 id。若反查表按 vanilla 数量建，解码会越界抛异常，整个区块被 fail-open（矿全部裸露）。
   * 默认取 {@link #getUniqueBlockStateCount()}；平台实现可放大到探测扫描上限。
   */
  default int paletteCapacity() {
    return getUniqueBlockStateCount();
  }

  int getMaxBitsPerBlockState();

  boolean isAir(int blockId);

  /**
   * 该状态是否为流体（<b>方块计数口径</b>：含含水方块，与客户端区块 section 的 fluidCount 一致）。
   * 仅供 codec 统计使用，绝不可擅自改成「只有水/岩浆」——那会让改写后的 section 流体计数与客户端不符。
   */
  boolean isFluid(int blockId);
}