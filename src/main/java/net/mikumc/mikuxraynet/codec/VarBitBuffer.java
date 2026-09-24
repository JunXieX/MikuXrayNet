
package net.mikumc.mikuxraynet.codec;

/**
 * 位打包的 4096 项调色板索引数组。
 *
 * <p>关键约束：{@link #toArray()} 返回内部存储（非副本），对该数组的写入会直接改变缓冲内容。
 */
public interface VarBitBuffer {

  int get(int index);

  void set(int index, int value);

  long[] toArray();

  int size();
}