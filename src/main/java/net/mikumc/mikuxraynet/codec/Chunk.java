
package net.mikumc.mikuxraynet.codec;

import java.util.Arrays;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 一个区块原始字节缓冲区的可改写视图。
 *
 * <p>构造时按 {@code sectionsPresent} 逐段解析：为 false 的 section 视为「缓冲区中不含其字节」
 * （1.18+ 原版格式下所有 section 都存在且依次排列，末尾没有独立群系段），其视图为 {@code null}。
 * 1.18+ 每个 section 尾部的生物群系容器会先结构化解析其头部（bitsPerValue 字节、调色板条目，
 * 以及旧版格式的长度字段）再整体跳过载荷，重编码时原样搬运。
 *
 * <p>用法：{@link #getSection(int)} 取得 section 后改写方块状态，最后调用 {@link #finalizeOutput()}
 * 得到重编码后的完整字节数组（未改动部分原样搬运）。用毕必须 {@link #close()}。
 *
 * <p>分配：调色板反查表、位打包 long 数组与输出缓冲区都由按线程复用的 {@link ChunkScratch} 提供，
 * {@link #close()} 时归还给本线程，供下一个区块复用，避免每个区块都重建这几百 KB 的数组。
 * 因此同一输入不因复用而产生任何字节差异：借出的数组在借出方手里都会被完整初始化或校验
 * （位打包数组与输出缓冲区会清零/写满；调色板反查表 {@code byValue} 不清零，改由 {@code byId}
 * 反向表逐项校验命中，见 {@link IndirectPalette} 的说明）。
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
    ByteBuf input = Unpooled.wrappedBuffer(data);
    try {
      // 必须在构造任何 ChunkSectionHolder 之前完成赋值：holder 内部会读取本字段推进 readerIndex
      this.inputBuffer = input;
      this.sections = new ChunkSectionHolder[sectionsPresent.length];

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
    } catch (RuntimeException | Error throwable) {
      // 构造中途失败（典型：损坏区块在 read/skip 时抛 IndexOutOfBoundsException）：对象不会交付给调用方，
      // close() 永远不会被调到，因此必须在此自行收尾——否则连续损坏区块会把按线程复用的空闲池打空
      // （每次都 acquire 却从不 recycle），并泄漏 wrappedBuffer 包装的输入/输出堆缓冲。
      // 回收 scratch 是安全的：它不保留区块数据，下个借出方会重新初始化各数组。
      releaseQuietly(input);
      releaseQuietly(this.outputBuffer);
      this.scratch.recycle();
      throw throwable;
    }
  }

  /** 尽力释放一个缓冲区；null 或重复释放都忽略（构造失败路径专用）。 */
  private static void releaseQuietly(ByteBuf buffer) {
    if (buffer == null) {
      return;
    }
    try {
      buffer.release();
    } catch (Throwable ignored) {
      // 构造失败收尾：释放异常无可补救，交给 GC / 引用计数兜底
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
    // 入口先校验所有「按绝对下标从输入缓冲区搬运」的源区间都落在缓冲区内。校验通过后，writeAll 期间
    // 唯一可能的越界就只剩「输出缓冲区容量不足」，重试条件因此可精确判定；真正的数据损坏在这里就被
    // 拦下（不进入任何扩容重试，直接交由上层 fail-open）。
    validateSourceRanges();

    for (;;) {
      ByteBuf out = this.outputBuffer;
      out.clear();
      try {
        writeAll(out);
        byte[] array = out.array();
        int readable = out.readableBytes();
        if (out.arrayOffset() == 0 && readable == array.length) {
          // 缓冲区恰好写满（无偏移）：把底层数组零拷贝移交给调用方，省掉 worker 热路径上约 50KB 的整块复制。
          // 别名安全前提：返回的数组会被内存缓存 / 磁盘 / NMS 字段长期持有，必须由调用方独占，因此这里
          // 让 scratch 与 outputBuffer 双双放弃对它的引用——scratch 下次另分配（detachOutput），本对象
          // 再 finalizeOutput 也会写进新缓冲，绝不会就地改写已移交的数组；数组长度恰为 readable，长度精确。
          this.scratch.detachOutput();
          ByteBuf previousOut = out;
          this.outputBuffer = Unpooled.wrappedBuffer(
              this.scratch.outputArray(Math.max(1, array.length)));
          this.outputBuffer.clear();
          // 交出数组后旧包装已无引用价值：立即释放，避免「替换即释放」的记账约定被破坏
          // （当前是 Unpooled 堆包装、不涉及池化内存，但一旦将来换成池化/直接缓冲，漏放就是真泄漏）。
          releaseQuietly(previousOut);
          return array;
        }
        // 未写满（复用数组偏大）：必须复制出长度精确的独立数组，不能把偏大的复用数组交出去
        // （若底层缓冲区在写出过程中自行扩容，把真实容量同步回 scratch，后续区块一次到位）。
        // 这是既定取舍：稳态下复用数组往往比本次输出略大，于是每个区块要付一次整块复制；换来的是
        // 「交出去的字节长度精确、且完全独立于 scratch」这一强不变式（调用方会长期持有该数组）。
        // 若未来确有需要，可让 scratch 记「上次实际写出长度」并据此定容以减少该复制，此处不做。
        this.scratch.keepOutputCapacity(out.capacity());
        return Arrays.copyOfRange(array, out.arrayOffset(), out.arrayOffset() + readable);
      } catch (IndexOutOfBoundsException overflow) {
        // 源区间已在入口校验 ⇒ 此处越界一定来自输出缓冲区写满（唯一允许重试的情形，与真损坏无关）。
        // 扩容重写只会在「输出超过该线程历史最大长度」时发生，稳态下不再触发。
        if (out.capacity() >= ChunkScratch.MAX_OUTPUT_CAPACITY) {
          // 已达绝对上限：绝不继续翻倍（否则会指数膨胀到 OOM，而 OOM 是 Error、不被上层
          // catch(RuntimeException) 兜住）。抛专用溢出异常，由封包链路按 fail-open 放行原包。
          throw new ChunkScratch.OutputOverflowException(
              "区块重编码输出超过绝对上限 " + ChunkScratch.MAX_OUTPUT_CAPACITY + " 字节，放弃改写");
        }
        // 扩容替换输出缓冲同样遵循「替换即释放」（与上方零拷贝分支同约定）：旧包装已无引用价值，
        // 不释放其 refCnt 会永远停在 1。注意先算后换——growOutput 可能抛 OutputOverflowException，
        // 抛出时旧缓冲必须仍在本对象手里（由 close() 释放），故不得提前释放。
        // 请求按翻倍语义（而非只比当前大 1 字节）：输出数组一旦超过保留上限（或已被零拷贝移交），
        // scratch 就不再驻留它、growOutput 只能按请求量分配；若这里只请求 capacity+1，每轮重试仅多
        // 1 字节，最坏退化为 O(n²)（大区块/异常输入下反复整块重写）。请求翻倍即恢复几何级数增长。
        // 仍封顶于绝对上限，避免「需要 50 MiB，却因翻倍请求 80 MiB」直接把本可完成的改写判成溢出。
        int required = Math.min(Math.max(out.capacity() + 1, out.capacity() * 2),
            ChunkScratch.MAX_OUTPUT_CAPACITY);
        ByteBuf previousOut = out;
        this.outputBuffer = Unpooled.wrappedBuffer(this.scratch.growOutput(required));
        releaseQuietly(previousOut);
      }
    }
  }

  /**
   * 校验写出时会按绝对下标搬运的源区间都在输入缓冲区内；任何越界即判「数据损坏」并就地抛出
   * （{@link RuntimeException}，交由封包链路 fail-open），从而与「容量不足」的重试路径彻底分开。
   */
  private void validateSourceRanges() {
    int capacity = this.inputBuffer.capacity();
    checkSourceRange(this.trailingOffset, this.trailingLength, capacity);
    for (ChunkSectionHolder chunkSection : this.sections) {
      if (chunkSection != null) {
        chunkSection.validateSourceRange(capacity);
      }
    }
  }

  private static void checkSourceRange(int offset, int length, int capacity) {
    if (length < 0 || offset < 0 || (long) offset + length > capacity) {
      throw new IllegalStateException("区块源字节区间越界（offset=" + offset + ", length=" + length
          + ", capacity=" + capacity + "）：数据已损坏，放弃改写");
    }
  }

  /**
   * <b>仅测试用</b>：把一个 section 的原始字节区间改成越界值，用于验证「源数据损坏」会在
   * {@link #finalizeOutput()} 入口被立即拦下（不进入扩容重试、直接 fail-open）。
   */
  void corruptSectionRangeForTest(int index, int offset, int length) {
    ChunkSectionHolder holder = this.sections[index];
    holder.sectionOffset = offset;
    holder.sectionLength = length;
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

    /** 校验写出时按绝对下标搬运的两个源区间（未改动 section 的整体区间 / 尾部群系容器）都在输入缓冲区内。 */
    void validateSourceRange(int inputCapacity) {
      if (this.sectionLength > 0) {
        checkSourceRange(this.sectionOffset, this.sectionLength, inputCapacity);
      }
      if (this.extraBytes > 0) {
        checkSourceRange(this.extraOffset, this.extraBytes, inputCapacity);
      }
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