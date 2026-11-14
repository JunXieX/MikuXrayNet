
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * 直接（direct）调色板：数据里存的就是方块状态 id 本身，没有调色板段。
 *
 * <p>关键约束：读写均为空操作，因此重编码不会输出调色板；不含调色板意味着位打包数据里存的就是状态 id，
 * 必须按 {@link ChunkSection#bitsPerBlock()} 的<b>声明位宽</b>解释——读取时保留服务端声明值（9..32），
 * 只有本插件升位时才取注册表位宽。
 */
public class DirectPalette implements Palette {

  /**
   * 与 {@link ChunkSection#bitsPerBlock()} 一致的声明位宽。
   *
   * <p><b>为什么要持有它</b>：直接格式的可表示范围由声明位宽决定（{@code 0..2^bits-1}），而不是注册表。
   * 服务端会按调色板规模下发较窄的声明位宽（如 9 位 = 0..511），此时若把一个更大的状态 id 写进位打包
   * 数据，{@link SimpleVarBitBuffer#set} 会按位宽掩码把它<b>静默截断成另一个状态</b>（方块错乱、不计数）。
   * 因此 {@link #contains} 必须如实回答「本表示能否容纳该 id」，供位宽预算与 use-block-below 过滤。
   */
  private final int bitsPerBlock;

  DirectPalette(int bitsPerBlock) {
    this.bitsPerBlock = bitsPerBlock;
  }

  @Override
  public int idFor(int value) {
    return value;
  }

  @Override
  public int valueFor(int id) {
    return id;
  }

  @Override
  public void read(ByteBuf buffer) {
  }

  @Override
  public void write(ByteBuf buffer) {
  }

  /** 直接调色板没有调色板段，条目数为 0。 */
  @Override
  public int size() {
    return 0;
  }

  /**
   * 本表示能否容纳该状态 id（{@code 0..2^声明位宽-1}）：放不下就会被位打包掩码截断，必须判 false。
   *
   * <p>上界必须用 {@code long} 算：位宽 31 时 {@code 1 << 31} 在 int 下溢出成负数，
   * 会让所有正数都判成「放不下」；位宽 32 时同理。{@code 1L << 32} 恒大于任何 int，无需特判。
   */
  @Override
  public boolean contains(int value) {
    return value >= 0 && value < (1L << bitsPerBlock);
  }
}
