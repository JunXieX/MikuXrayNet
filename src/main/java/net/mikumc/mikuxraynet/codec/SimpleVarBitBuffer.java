
package net.mikumc.mikuxraynet.codec;

/**
 * 通用位打包缓冲区：每个 long 容纳 {@code 64 / bitsPerEntry} 项（向下取整，余位浪费），
 * 项在 long 内自低位起连续排布，与原版打包方式一致。
 *
 * <p>关键约束：{@code bitsPerEntry} 不得为 0（用 {@link ZeroVarBitBuffer} 代替）；
 * {@link #calculateArraySize(int, int)} 必须与构造出的数组长度一致，否则读取时的长度校验会失败。
 */
public class SimpleVarBitBuffer implements VarBitBuffer {

  public static int calculateArraySize(int bitsPerEntry, int size) {
    return bitsPerEntry == 0 ? 0 : (int) Math.ceil((float) size / (64 / bitsPerEntry));
  }

  private final int bitsPerEntry;
  private final int entriesPerLong;
  private final long adjustmentMask;

  private final int size;
  private final long[] buffer;

  public SimpleVarBitBuffer(int bitsPerEntry, int size) {
    this(bitsPerEntry, size, new long[calculateArraySize(bitsPerEntry, size)]);
  }

  /**
   * 用外部提供的存储构造（供 {@link ChunkScratch} 复用 long 数组）。
   *
   * @param buffer 长度必须等于 {@link #calculateArraySize(int, int)}：长度会参与写出与读取校验
   */
  SimpleVarBitBuffer(int bitsPerEntry, int size, long[] buffer) {
    this.bitsPerEntry = bitsPerEntry;
    this.entriesPerLong = 64 / bitsPerEntry;
    this.adjustmentMask = (1L << bitsPerEntry) - 1L;

    this.size = size;
    this.buffer = buffer;

    if (buffer.length != calculateArraySize(bitsPerEntry, size)) {
      throw new IllegalArgumentException(
          "buffer.length != VarBitBuffer::size " + buffer.length + " " + calculateArraySize(bitsPerEntry, size));
    }
  }

  public int get(int index) {
    int position = index / this.entriesPerLong;
    int offset = (index - position * this.entriesPerLong) * this.bitsPerEntry;
    return (int) (this.buffer[position] >> offset & this.adjustmentMask);
  }

  public void set(int index, int value) {
    int position = index / this.entriesPerLong;
    int offset = (index - position * this.entriesPerLong) * this.bitsPerEntry;
    this.buffer[position] = this.buffer[position]
        & ~(this.adjustmentMask << offset) | (value & this.adjustmentMask) << offset;
  }

  public long[] toArray() {
    return this.buffer;
  }

  public int size() {
    return this.size;
  }

  @Override
  public String toString() {
    return String.format("[size=%d, length=%d, bitsPerEntry=%d, entriesPerLong=%d]", size, buffer.length, bitsPerEntry,
        entriesPerLong);
  }
}