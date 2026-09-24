
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * bitsPerBlock=0 的单值调色板：整个 section 只有一种方块状态，无位打包数据。
 *
 * <p>关键约束：只能容纳一个值，出现第二种方块状态时通过 {@link ChunkSection#grow(int, int)} 升级为间接调色板；
 * 未初始化（值为 -1）时读取或写出都会抛 {@link IllegalStateException}。
 */
public class SingleValuePalette implements Palette {

  private final ChunkSection chunkSection;

  private int value = -1;

  public SingleValuePalette(ChunkSection chunkSection, int value) {
    this.chunkSection = chunkSection;
    this.value = value;
  }

  @Override
  public int idFor(int value) {
    if (this.value != -1 && value != this.value) {
      return this.chunkSection.grow(1, value);
    } else {
      this.value = value;
      return 0;
    }
  }

  @Override
  public int valueFor(int id) {
    if (this.value != -1 && id == 0) {
      return this.value;
    } else {
      throw new IllegalStateException("value isn't initialized");
    }
  }

  @Override
  public void read(ByteBuf buffer) {
    this.value = ByteBufUtil.readVarInt(buffer);
  }

  @Override
  public void write(ByteBuf buffer) {
    if (this.value == -1) {
      throw new IllegalStateException("value isn't initialized");
    } else {
      ByteBufUtil.writeVarInt(buffer, this.value);
    }
  }
}