
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * bitsPerBlock=0 的单值调色板：整个 section 只有一种方块状态，无位打包数据。
 *
 * <p>关键约束：只能容纳一个值，出现第二种方块状态时通过 {@link ChunkSection#grow(int, int)} 升级为间接调色板；
 * 未初始化（值为 -1）时读取或写出都会抛 {@link IllegalStateException}。
 */
public class SingleValuePalette implements Palette {

  private final ChunkSection chunkSection;

  private int value = -1;

  public SingleValuePalette(ChunkSection chunkSection, int value) {
    this.chunkSection = chunkSection;
    this.value = value;
  }

  @Override
  public int idFor(int value) {
    if (this.value != -1 && value != this.value) {
      return this.chunkSection.grow(1, value);
    } else {
      this.value = value;
      return 0;
    }
  }

  @Override
  public int valueFor(int id) {
    if (this.value == -1) {
      throw new IllegalStateException("value isn't initialized");
    }
    if (id != 0) {
      // 单值调色板只有 id=0 一个条目：id≠0 说明上游索引越界，而非「未初始化」
      throw new IndexOutOfBoundsException("single-value palette only has id 0, got " + id);
    }
    return this.value;
  }

  @Override
  public void read(ByteBuf buffer) {
    int read = ByteBufUtil.readVarInt(buffer);
    // 与 IndirectPalette.read 同一道入参校验：方块状态 id 必须落在注册表范围内。
    // 否则负值/超大值会一路传到 ObfuscationProcessor 的 targets BitSet（BitSet.get(-1) 抛异常），
    // 或被当成合法 id 去下标字节表——两种结果都让<b>整个区块</b> fail-open（原字节放行、矿全部裸露）。
    // 这里按 fail-open 约定早失败：异常由上层解码兜底，只拒绝这一个 section。
    int registry = this.chunkSection.registryAccessor().getUniqueBlockStateCount();
    if (read < 0 || read >= registry) {
      throw new IndexOutOfBoundsException(
          "block state id out of range: " + read + " (registry " + registry + ")");
    }
    this.value = read;
  }

  @Override
  public void write(ByteBuf buffer) {
    if (this.value == -1) {
      throw new IllegalStateException("value isn't initialized");
    } else {
      ByteBufUtil.writeVarInt(buffer, this.value);
    }
  }

  @Override
  public int size() {
    return this.value == -1 ? 0 : 1;
  }

  @Override
  public boolean contains(int value) {
    return this.value == value;
  }
}