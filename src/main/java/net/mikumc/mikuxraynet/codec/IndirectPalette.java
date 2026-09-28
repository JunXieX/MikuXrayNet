
package net.mikumc.mikuxraynet.codec;

import java.util.Arrays;
import io.netty.buffer.ByteBuf;

/**
 * 间接调色板（bitsPerBlock 4..8）：按首次出现顺序为方块状态分配本地索引，索引表长度为 {@code 1 << bitsPerValue}。
 *
 * <p>关键约束：索引表写满（或索引达到 0xFF 哨兵值）时调用 {@link ChunkSection#grow(int, int)} 升位——
 * bitsPerValue 为 4~7 时升到 bitsPerValue+1 的间接调色板；8 位写满时 {@code grow(9)} 会直接切换为
 * 直接调色板（位宽取注册表上限），不再有「+1 位」的间接档位。
 * {@code byValue} 以方块状态 id 直接下标，故长度必须覆盖注册表全部方块状态
 * （由 {@link ChunkScratch} 按线程复用，避免每个 section 都新建这张 32 KB 级反查表）。
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

    this.byValue = chunkSection.scratch().bytes(chunkSection.registryAccessor().getUniqueBlockStateCount());
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
    // 入参校验（损坏数据早失败，fail-open 由上层解码兜底）：调色板长度不得超过索引表容量，
    // 方块状态 id 必须落在注册表范围内——否则会静默越界污染 byId / 复用的 byValue 缓冲。
    if (this.size < 0 || this.size > this.byId.length) {
      throw new IndexOutOfBoundsException(
          "palette size out of range: " + this.size + " (capacity " + this.byId.length + ")");
    }
    for (int id = 0; id < size; id++) {
      int value = ByteBufUtil.readVarInt(buffer);
      if (value < 0 || value >= this.byValue.length) {
        throw new IndexOutOfBoundsException(
            "block state id out of range: " + value + " (registry " + this.byValue.length + ")");
      }
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

  /**
   * 用保留的值<b>子集</b>重建索引表（裁剪掉未列出的旧条目）。
   *
   * <p>与 {@link #rebuild(int[])}（全量排列）不同，本方法允许 {@code values} 只包含原值集合的一部分：
   * 被裁剪条目的 {@code byValue} 反查记录会被清回哨兵值，之后再次遇到这些方块状态时会作为
   * 新条目重新登记，不会命中残留的旧索引——这是「引用计数为 0 的失效条目裁剪」的正确性前提。
   */
  void retain(int[] values) {
    // 先按旧 size 清空全部反查记录，再登记保留条目（顺序不能反，否则清空会抹掉新登记）
    for (int id = 0; id < this.size; id++) {
      this.byValue[this.byId[id]] = (byte) 0xFF;
    }
    this.size = values.length;
    for (int id = 0; id < values.length; id++) {
      this.byId[id] = values[id];
      this.byValue[values[id]] = (byte) id;
    }
  }

  @Override
  public int size() {
    return this.size;
  }

  @Override
  public boolean contains(int value) {
    return value >= 0 && value < this.byValue.length && (this.byValue[value] & 0xFF) != 0xFF;
  }
}