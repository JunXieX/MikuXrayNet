// 移植自 Orebfuscator（GPL-3.0），本项目为私有自用部署。
package net.mikumc.mikuxraynet.codec;

import java.util.Arrays;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 一个区块原始字节缓冲区的可改写视图。
 *
 * <p>构造时按 {@code sectionsPresent} 逐段解析：为 false 的 section 视为「缓冲区中不含其字节」
 * （1.18+ 原版格式下所有 section 都存在且依次排列，末尾没有独立群系段），其视图为 {@code null}。
 * 1.18+ 每个 section 尾部的生物群系容器按不透明字节整体跳过并原样搬运。
 *
 * <p>用法：{@link #getSection(int)} 取得 section 后改写方块状态，最后调用 {@link #finalizeOutput()}
 * 得到重编码后的完整字节数组（未改动部分原样搬运）。用毕必须 {@link #close()}。
 */
public class Chunk implements AutoCloseable {

  private final ChunkCodec codec;

  private final ChunkSectionHolder[] sections;

  private final ByteBuf inputBuffer;
  private final ByteBuf outputBuffer;

  Chunk(ChunkCodec codec, byte[] data, boolean[] sectionsPresent) {
    this.codec = codec;

    this.sections = new ChunkSectionHolder[sectionsPresent.length];

    this.inputBuffer = Unpooled.wrappedBuffer(data);
    // 初始容量至少为 1：Unpooled.buffer(0) 返回只读的 EMPTY_BUFFER，无法承接升位后变大的调色板
    this.outputBuffer = Unpooled.buffer(Math.max(1, data.length));

    for (int sectionIndex = 0; sectionIndex < this.sections.length; sectionIndex++) {
      if (sectionsPresent[sectionIndex]) {
        this.sections[sectionIndex] = new ChunkSectionHolder();
      }
    }
  }

  public int getSectionCount() {
    return this.sections.length;
  }

  public ChunkSection getSection(int index) {
    ChunkSectionHolder chunkSection = this.sections[index];
    if (chunkSection != null) {
      return chunkSection.chunkSection;
    }
    return null;
  }

  public byte[] finalizeOutput() {
    for (ChunkSectionHolder chunkSection : this.sections) {
      if (chunkSection != null) {
        chunkSection.write();
      }
    }

    this.outputBuffer.writeBytes(this.inputBuffer);

    return Arrays.copyOfRange(
        this.outputBuffer.array(), this.outputBuffer.arrayOffset(),
        this.outputBuffer.arrayOffset() + this.outputBuffer.readableBytes());
  }

  @Override
  public void close() {
    this.inputBuffer.release();
    this.outputBuffer.release();
  }

  private void skipBiomePalettedContainer() {
    int bitsPerValue = this.inputBuffer.readUnsignedByte();

    if (bitsPerValue == 0) {
      ByteBufUtil.readVarInt(this.inputBuffer);
    } else if (bitsPerValue <= 3) {
      for (int i = ByteBufUtil.readVarInt(this.inputBuffer); i > 0; i--) {
        ByteBufUtil.readVarInt(this.inputBuffer);
      }
    }

    int expectedDataLength = SimpleVarBitBuffer.calculateArraySize(bitsPerValue, 64);

    if (codec.versionFlags().hasLongArrayLengthField()) {
      int dataLength = ByteBufUtil.readVarInt(this.inputBuffer);
      if (expectedDataLength != dataLength) {
        throw new IndexOutOfBoundsException(
            "data.length != VarBitBuffer::size " + dataLength + " " + expectedDataLength);
      }
    }

    this.inputBuffer.skipBytes(Long.BYTES * expectedDataLength);
  }

  private class ChunkSectionHolder {

    public final ChunkSection chunkSection;

    public final int extraOffset;

    private int extraBytes;

    public ChunkSectionHolder() {
      this.chunkSection = new ChunkSection(codec);

      // read() 会把方块状态读到 section 内部，返回值（扁平化状态数组）此处不再需要
      this.chunkSection.read(inputBuffer);
      this.extraOffset = inputBuffer.readerIndex();

      if (codec.versionFlags().hasBiomePalettedContainer()) {
        skipBiomePalettedContainer();
        this.extraBytes = inputBuffer.readerIndex() - this.extraOffset;
      }
    }

    public void write() {
      this.chunkSection.write(outputBuffer);
      if (this.extraBytes > 0) {
        outputBuffer.writeBytes(inputBuffer, this.extraOffset, extraBytes);
      }
    }
  }
}