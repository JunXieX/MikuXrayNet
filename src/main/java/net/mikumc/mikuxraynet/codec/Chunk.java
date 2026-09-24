
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
 *
 * <p>分配：调色板反查表、位打包 long 数组与输出缓冲区都由按线程复用的 {@link ChunkScratch} 提供，
 * {@link #close()} 时归还给本线程，供下一个区块复用，避免每个区块都重建这几百 KB 的数组。
 * 因此同一输入不因复用而产生任何字节差异：所有借出的数组在借出方手里都会被完整初始化。
 */
public class Chunk implements AutoCloseable {

  /** section 在原始字节数组中的区间（{@code offset} 为下标，{@code length} 为字节数，含尾部群系容器）。 */
  public record SectionRange(int offset, int length) {
  }

  private final ChunkCodec codec;

  private final ChunkSectionHolder[] sections;

  private final ByteBuf inputBuffer;
  private ByteBuf outputBuffer;

  /** 按线程复用的数组来源；{@link #close()} 时归还，供同线程的下一个区块复用。 */
  private final ChunkScratch scratch;

  /** 原始缓冲区中位于所有 section 之后的尾部字节（1.18 之前是独立群系段），写出时原样搬运。 */
  private final int trailingOffset;
  private final int trailingLength;

  private boolean closed;

  Chunk(ChunkCodec codec, byte[] data, boolean[] sectionsPresent) {
    this.codec = codec;
    this.scratch = ChunkScratch.acquire();

    this.sections = new ChunkSectionHolder[sectionsPresent.length];

    this.inputBuffer = Unpooled.wrappedBuffer(data);
    // 输出缓冲区复用 scratch 的字节数组：容量不足时在 finalizeOutput 里扩容重写
    this.outputBuffer = Unpooled.wrappedBuffer(this.scratch.outputArray(Math.max(1, data.length)));
    this.outputBuffer.clear();

    for (int sectionIndex = 0; sectionIndex < this.sections.length; sectionIndex++) {
      if (sectionsPresent[sectionIndex]) {
        this.sections[sectionIndex] = new ChunkSectionHolder();
      }
    }

    this.trailingOffset = this.inputBuffer.readerIndex();
    this.trailingLength = this.inputBuffer.readableBytes();
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

  /**
   * 该 section 在原始字节数组中的区间；{@code null} 表示该 section 在缓冲区中没有字节
   * （或区间无法确定，此时 {@link #finalizeOutput()} 会退回整块重编码）。
   *
   * <p>测试专用豁免：生产的选择性重编码由 {@code ChunkSectionHolder} 内部完成，
   * 本方法当前仅单测与基准路径在用，保留以免破坏测试。
   */
  public SectionRange originalSectionRange(int index) {
    ChunkSectionHolder holder = this.sections[index];
    return holder == null ? null : holder.range();
  }

  public byte[] finalizeOutput() {
    for (int attempt = 0; ; attempt++) {
      ByteBuf out = this.outputBuffer;
      out.clear();
      try {
        writeAll(out);
        // 若底层缓冲区在写出过程中自行扩容，把真实容量同步回 scratch，后续区块一次到位
        this.scratch.keepOutputCapacity(out.capacity());
        return Arrays.copyOfRange(out.array(), out.arrayOffset(), out.arrayOffset() + out.readableBytes());
      } catch (IndexOutOfBoundsException overflow) {
        // 复用的输出数组容量不足（升位后调色板变大等情况）：翻倍扩容后整体重写。
        // 只会在输出超过该线程历史最大长度时发生，稳态下不再触发。
        if (attempt >= 24) {
          throw overflow;
        }
        this.outputBuffer = Unpooled.wrappedBuffer(this.scratch.growOutput(out.capacity() + 1));
      }
    }
  }

  private void writeAll(ByteBuf out) {
    for (ChunkSectionHolder chunkSection : this.sections) {
      if (chunkSection != null) {
        chunkSection.write(out);
      }
    }

    // 用绝对下标搬运尾部字节：扩容重写时不会因为 readerIndex 已被推进而丢数据
    out.writeBytes(this.inputBuffer, this.trailingOffset, this.trailingLength);
  }

  @Override
  public void close() {
    if (this.closed) {
      return;
    }
    this.closed = true;

    this.inputBuffer.release();
    this.outputBuffer.release();
    this.scratch.recycle();
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

    /** 该 section 在原始缓冲中的起始下标与总字节数（含尾部群系容器）；-1 表示未能确定。 */
    private int sectionOffset = -1;
    private int sectionLength = -1;

    public ChunkSectionHolder() {
      this.chunkSection = new ChunkSection(codec, scratch);

      int start = inputBuffer.readerIndex();

      // read() 把方块状态读到 section 内部，本类不需要返回值（扁平化数组已从 read 中移除）
      this.chunkSection.read(inputBuffer);
      this.extraOffset = inputBuffer.readerIndex();

      if (codec.versionFlags().hasBiomePalettedContainer()) {
        skipBiomePalettedContainer();
        this.extraBytes = inputBuffer.readerIndex() - this.extraOffset;
      }

      this.sectionOffset = start;
      this.sectionLength = inputBuffer.readerIndex() - start;
    }

    /** 原始字节区间；区间不可用时返回 {@code null}。 */
    public SectionRange range() {
      if (this.sectionOffset < 0 || this.sectionLength <= 0) {
        return null;
      }
      return new SectionRange(this.sectionOffset, this.sectionLength);
    }

    public void write(ByteBuf outputBuffer) {
      // 选择性重编码：未改动的 section 直接原样搬运原始字节，省下一次完整的调色板/位打包重编码
      if (!this.chunkSection.isModified() && this.sectionOffset >= 0 && this.sectionLength > 0) {
        outputBuffer.writeBytes(inputBuffer, this.sectionOffset, this.sectionLength);
        return;
      }

      this.chunkSection.write(outputBuffer);
      if (this.extraBytes > 0) {
        outputBuffer.writeBytes(inputBuffer, this.extraOffset, extraBytes);
      }
    }
  }
}