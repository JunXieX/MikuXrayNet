// 移植自 Orebfuscator（GPL-3.0），本项目为私有自用部署。
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 测试用合成区块缓冲区构造器：按 section 字节布局手写原始字节，供「解码 → 重编码」往返断言使用。
 *
 * <p>关键点：位打包独立实现（不复用 {@link SimpleVarBitBuffer}），这样「字节完全一致」才能同时验证
 * 生产代码与格式约定的位序、每 long 项数、数组长度都相符。
 */
final class TestChunkBuilder {

  private final ChunkVersionFlags flags;
  private final ByteBuf buffer = Unpooled.buffer();

  TestChunkBuilder(ChunkVersionFlags flags) {
    this.flags = flags;
  }

  /** 追加单值调色板（bitsPerBlock=0）section；{@code biomeBits=0} 时用 {@code biomePalette[0]}。 */
  TestChunkBuilder singleValueSection(int blockState, int blockCount, int fluidCount,
      int biomeBits, int[] biomePalette) {
    writeHeader(blockCount, fluidCount);
    this.buffer.writeByte(0);
    ByteBufUtil.writeVarInt(this.buffer, blockState);
    if (this.flags.hasLongArrayLengthField()) {
      ByteBufUtil.writeVarInt(this.buffer, 0);
    }
    writeBiomes(biomeBits, biomePalette);
    return this;
  }

  /** 追加间接调色板 section；{@code paletteIndices} 为 4096 个调色板索引。 */
  TestChunkBuilder indirectSection(int bitsPerBlock, int blockCount, int fluidCount,
      int[] palette, int[] paletteIndices, int biomeBits, int[] biomePalette) {
    writeHeader(blockCount, fluidCount);
    this.buffer.writeByte(bitsPerBlock);
    ByteBufUtil.writeVarInt(this.buffer, palette.length);
    for (int value : palette) {
      ByteBufUtil.writeVarInt(this.buffer, value);
    }
    writeLongArray(bitsPerBlock, paletteIndices);
    writeBiomes(biomeBits, biomePalette);
    return this;
  }

  /** 追加直接（direct）调色板 section；{@code blockStates} 为 4096 个方块状态 id，无调色板段。 */
  TestChunkBuilder directSection(int bitsPerBlock, int blockCount, int fluidCount,
      int[] blockStates, int biomeBits, int[] biomePalette) {
    writeHeader(blockCount, fluidCount);
    this.buffer.writeByte(bitsPerBlock);
    writeLongArray(bitsPerBlock, blockStates);
    writeBiomes(biomeBits, biomePalette);
    return this;
  }

  /** 追加任意原始字节（例如 1.18 之前位于所有 section 之后的群系段）。 */
  TestChunkBuilder trailing(byte[] bytes) {
    this.buffer.writeBytes(bytes);
    return this;
  }

  byte[] build() {
    byte[] out = new byte[this.buffer.readableBytes()];
    this.buffer.getBytes(this.buffer.readerIndex(), out);
    return out;
  }

  private void writeHeader(int blockCount, int fluidCount) {
    this.buffer.writeShort(blockCount);
    if (this.flags.hasFluidCount()) {
      this.buffer.writeShort(fluidCount);
    }
  }

  private void writeLongArray(int bitsPerEntry, int[] values) {
    int length = arraySize(bitsPerEntry, values.length);
    if (this.flags.hasLongArrayLengthField()) {
      ByteBufUtil.writeVarInt(this.buffer, length);
    }

    int entriesPerLong = 64 / bitsPerEntry;
    long mask = (1L << bitsPerEntry) - 1L;
    long[] data = new long[length];

    for (int i = 0; i < values.length; i++) {
      int position = i / entriesPerLong;
      int offset = (i - position * entriesPerLong) * bitsPerEntry;
      data[position] |= ((long) values[i] & mask) << offset;
    }
    for (long entry : data) {
      this.buffer.writeLong(entry);
    }
  }

  private void writeBiomes(int bitsPerValue, int[] biomePalette) {
    if (!this.flags.hasBiomePalettedContainer()) {
      return;
    }

    this.buffer.writeByte(bitsPerValue);
    if (bitsPerValue == 0) {
      ByteBufUtil.writeVarInt(this.buffer, biomePalette[0]);
    } else if (bitsPerValue <= 3) {
      ByteBufUtil.writeVarInt(this.buffer, biomePalette.length);
      for (int value : biomePalette) {
        ByteBufUtil.writeVarInt(this.buffer, value);
      }
    }

    // 群系数据对 codec 是不透明字节，只要求长度与 arraySize 一致，内容全 0 即可
    int length = arraySize(bitsPerValue, 64);
    if (this.flags.hasLongArrayLengthField()) {
      ByteBufUtil.writeVarInt(this.buffer, length);
    }
    for (int i = 0; i < length; i++) {
      this.buffer.writeLong(0L);
    }
  }

  private static int arraySize(int bitsPerEntry, int size) {
    return bitsPerEntry == 0 ? 0 : (int) Math.ceil((float) size / (64 / bitsPerEntry));
  }
}