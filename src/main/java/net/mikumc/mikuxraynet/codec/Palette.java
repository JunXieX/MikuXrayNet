
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * 方块状态与本地调色板索引之间的双向映射，并负责 paletted container 中调色板部分的读写。
 *
 * <p>关键约束：{@link #idFor(int)} 在无法容纳新值时不得抛异常，而应触发 {@link ChunkSection#grow(int, int)}
 * 升位并返回新调色板中的索引；{@link #valueFor(int)} 对越界索引抛 {@link IndexOutOfBoundsException}
 * （单值调色板未初始化时抛 {@link IllegalStateException}）。
 */
public interface Palette {

  int idFor(int value);

  int valueFor(int id);

  void read(ByteBuf buffer);

  void write(ByteBuf buffer);

  /**
   * 当前调色板条目数（单值调色板为 1，直接调色板为 0）。
   *
   * <p>供反矿透的「位宽预算封顶」计算剩余空位：间接调色板的容量为 {@code 1 << bitsPerBlock}。
   */
  int size();

  /**
   * 调色板是否已包含某方块状态（供预算筛选判断「替换方块是否已在调色板内」）。
   *
   * <p>直接调色板没有容量限制，恒返回 {@code true}。
   */
  boolean contains(int value);
}