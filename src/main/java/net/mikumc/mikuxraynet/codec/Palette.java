
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
}