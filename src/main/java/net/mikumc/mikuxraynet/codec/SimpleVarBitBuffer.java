
package net.mikumc.mikuxraynet.codec;

/**
 * 通用位打包缓冲区：每个 long 容纳 {@code 64 / bitsPerEntry} 项（向下取整，余位浪费），
 * 项在 long 内自低位起连续排布，与原版打包方式一致。
 *
 * <p>关键约束：{@code bitsPerEntry} 不得为 0（用 {@link ZeroVarBitBuffer} 代替）；
 * {@link #calculateArraySize(int, int)} 必须与构造出的数组长度一致，否则读取时的长度校验会失败。
 */
public class SimpleVarBitBuffer implements VarBitBuffer {

  /**
   * 位打包数组长度：{@code ceil(size / entriesPerLong)}。
   *
   * <p>为什么用整数运算：旧写法 {@code (float) size / ...} 依赖 float 的 24 位有效位，{@code size}
   * 很大时会先丢精度、再 {@code Math.ceil} 可能算出偏小（或偏大）的长度；而该长度会参与写出与
   * 读取时的长度校验（构造出的数组与之不符即抛错），精度误差会变成功能性失败。
   * 整数形式 {@code (size + entriesPerLong - 1) / entriesPerLong} 对任意 int 范围都精确。
   *
   * <p><b>为什么入口必须显式校验位宽</b>：{@code 64 / bitsPerEntry} 在 {@code bitsPerEntry}
   * 属于 {@code [65, 255]} 时整除结果为 0，随后 {@code (size - 1) / 0} 抛 {@code ArithmeticException}。
   * 该路径可达且不是理论值——解码时位的位宽直接来自封包里的字节（如
   * {@code Chunk.skipBiomePalettedContainer} 读到的损坏/被伪装的 bits 字节），因此旧实现只是
   * 「碰巧」被上层解码的 catch 兜住，异常语义也不清晰。这里在入口显式早失败并抛出说明性异常，
   * 由上层按既定 fail-open 约定兜住（拒绝该 section/区块），不再依赖除零这一副作用。
   *
   * @param bitsPerEntry 每项位宽：{@code 0} 表示「无位打包数据」（单值调色板，用
   *                     {@link ZeroVarBitBuffer}），合法范围为 {@code 0} 或 {@code 1..64}
   * @throws IllegalArgumentException 位宽为负数或大于 64（非法编码）
   */
  public static int calculateArraySize(int bitsPerEntry, int size) {
    if (bitsPerEntry == 0) {
      return 0;
    }
    if (bitsPerEntry < 0 || bitsPerEntry > 64) {
      // 0 已在上面返回（单值调色板）；其余非法值全部在此拦下，避免 64/bits==0 的除零副作用
      throw new IllegalArgumentException(
          "bitsPerEntry 必须为 0（单值）或落在 1..64，实际为 " + bitsPerEntry);
    }
    int entriesPerLong = 64 / bitsPerEntry;
    return (size + entriesPerLong - 1) / entriesPerLong;
  }

  private final int bitsPerEntry;
  private final int entriesPerLong;
  private final long adjustmentMask;

  private final int size;
  private final long[] buffer;

  /**
   * 用外部提供的存储构造（供 {@link ChunkScratch} 复用 long 数组）。
   *
   * @param buffer 长度必须等于 {@link #calculateArraySize(int, int)}：长度会参与写出与读取校验
   */
  SimpleVarBitBuffer(int bitsPerEntry, int size, long[] buffer) {
    this.bitsPerEntry = bitsPerEntry;
    this.entriesPerLong = 64 / bitsPerEntry;
    // bitsPerEntry == 64 时 (1L << 64) 是未定义行为（Java 移位按 64 取模，等价于 1L << 0，
    // 掩码会算成 0，取/写值全被抹零）。显式用 -1L 表示「低 64 位全 1」的满掩码。
    this.adjustmentMask = bitsPerEntry >= 64 ? -1L : (1L << bitsPerEntry) - 1L;

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