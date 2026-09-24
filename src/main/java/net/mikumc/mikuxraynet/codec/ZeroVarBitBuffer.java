// 移植自 Orebfuscator（GPL-3.0），本项目为私有自用部署。
package net.mikumc.mikuxraynet.codec;

/**
 * 不占存储的位打包缓冲区：配合单值调色板使用，任何索引都读出 0，写入非 0 值即抛异常。
 *
 * <p>关键约束：{@link #toArray()} 返回共享的空数组，只可用于长度为 0 的写出。
 */
public record ZeroVarBitBuffer(int size) implements VarBitBuffer {

  public static final long[] EMPTY = new long[0];

  @Override
  public int get(int index) {
    return 0;
  }

  @Override
  public void set(int index, int value) {
    if (value != 0) {
      throw new IllegalArgumentException("ZeroVarBitBuffer can't hold any value");
    }
  }

  @Override
  public long[] toArray() {
    return EMPTY;
  }
}