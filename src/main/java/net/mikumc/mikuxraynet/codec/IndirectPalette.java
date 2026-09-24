// 移植自 Orebfuscator（GPL-3.0），本项目为私有自用部署。
package net.mikumc.mikuxraynet.codec;

import java.util.Arrays;
import io.netty.buffer.ByteBuf;

/**
 * 间接调色板（bitsPerBlock 4..8）：按首次出现顺序为方块状态分配本地索引，索引表长度为 {@code 1 << bitsPerValue}。
 *
 * <p>关键约束：索引表写满（或索引达到 0xFF 哨兵值）时调用 {@link ChunkSection#grow(int, int)}
 * 升位到 bitsPerValue+1；{@code byValue} 以方块状态 id 直接下标，故长度必须覆盖注册表全部方块状态。
 */
public class IndirectPalette implements Palette {

  private final int bitsPerValue;
  private final ChunkSection chunkSection;

  private final byte[] byValue;
  private final int[] byId;

  private int size = 0;

  public IndirectPalette(int bitsPerValue, ChunkSection chunkSection) {
    this.bitsPerValue = bitsPerValue;
    this.chunkSection = chunkSection;

    this.byValue = new byte[chunkSection.registryAccessor().getUniqueBlockStateCount()];
    Arrays.fill(this.byValue, (byte) 0xFF);
    this.byId = new int[1 << bitsPerValue];
  }

  @Override
  public int idFor(int value) {
    int id = this.byValue[value] & 0xFF;
    if (id == 0xFF) {
      id = this.size++;

      if (id != 0xFF && id < this.byId.length) {
        this.byValue[value] = (byte) id;
        this.byId[id] = value;
      } else {
        id = this.chunkSection.grow(this.bitsPerValue + 1, value);
      }
    }
    return id;
  }

  @Override
  public int valueFor(int id) {
    if (id < 0 || id >= this.size) {
      throw new IndexOutOfBoundsException();
    } else {
      return this.byId[id];
    }
  }

  @Override
  public void read(ByteBuf buffer) {
    this.size = ByteBufUtil.readVarInt(buffer);
    for (int id = 0; id < size; id++) {
      int value = ByteBufUtil.readVarInt(buffer);
      this.byId[id] = value;
      this.byValue[value] = (byte) id;
    }
  }

  @Override
  public void write(ByteBuf buffer) {
    ByteBufUtil.writeVarInt(buffer, this.size);

    for (int id = 0; id < this.size; id++) {
      ByteBufUtil.writeVarInt(buffer, this.valueFor(id));
    }
  }

  /** 当前调色板条目数。 */
  int paletteSize() {
    return this.size;
  }

  /** 读取某个本地索引对应的方块状态 id。 */
  int valueAt(int id) {
    return this.byId[id];
  }

  /**
   * 用新的值顺序重建索引表（{@code values} 必须是原值集合的一个排列）。
   *
   * <p>用于调色板重排：索引表与 {@code byValue} 反查表一并更新，语义不变。
   */
  void rebuild(int[] values) {
    this.size = values.length;
    for (int id = 0; id < values.length; id++) {
      this.byId[id] = values[id];
      this.byValue[values[id]] = (byte) id;
    }
  }
}