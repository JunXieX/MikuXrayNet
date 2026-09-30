
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * 间接调色板（bitsPerBlock 4..8）：按首次出现顺序为方块状态分配本地索引，索引表长度为 {@code 1 << bitsPerValue}。
 *
 * <p>关键约束：索引表写满（或索引达到 0xFF 哨兵值）时调用 {@link ChunkSection#grow(int, int)} 升位——
 * bitsPerValue 为 4~7 时升到 bitsPerValue+1 的间接调色板；8 位写满时 {@code grow(9)} 会直接切换为
 * 直接调色板（位宽取注册表上限），不再有「+1 位」的间接档位。
 * {@code byValue} 以方块状态 id 直接下标，故长度必须覆盖注册表全部方块状态
 * （由 {@link ChunkScratch} 按线程复用，避免每个 section 都新建这张 32 KB 级反查表）。
 *
 * <p><b>复用缓冲不做整表清零</b>：{@code byValue} 是 scratch 复用的大数组，构造时整表 memset 0xFF
 * （注册表全部方块状态，≈2.6 万字节）就是每个 section 一次的无谓开销。改为「用反向表 {@code byId} 校验命中」：
 * 仅当 {@code id < size} 且 {@code byId[id] == value} 时 {@code byValue[value]} 才视为有效，否则按残留处理
 * （与旧实现的 0xFF 哨兵等价）；因此不清零也不会把上一轮登记的条目误判成本调色板已登记。纯查表校验、不新增常驻内存。
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

    // byValue 由 scratch 复用、内容未初始化：不再整表 memset，命中与否交由 byId 反查校验（见类注释）
    this.byValue = chunkSection.scratch().bytes(chunkSection.registryAccessor().getUniqueBlockStateCount());
    this.byId = new int[1 << bitsPerValue];
  }

  /*
   * 8 位调色板的容量取舍（有意为之，勿「顺手修正」）：
   * byValue 是复用自 ChunkScratch 的 byte[]（32 KB 级，每线程复用，避免每个 section 新建反查表），
   * 其 0xFF 被用作「未登记」哨兵。byte 只能表示 0..255，因此无法同时表达哨兵与 id=255：
   * idFor() 在 id 达到 0xFF 时即 grow()。净效果是 bitsPerValue 为 4~8 的间接调色板实际可容纳
   * (1 << bitsPerValue) - 1 项（8 位为 255 项而非原版理论上限 256 项），即比原版早一档从
   * 8 位间接升到 direct——受影响的阈值是「写入第 256 个不同方块状态时（而非第 257 个）触发
   * grow(9) 切到 direct」。读取不受影响：read() 允许 size 到 byId.length（8 位即 256，原版可能
   * 产出该形态）。不改的直接原因：要容纳 256 项必须把 byValue 换成 short[]/int[] 以留出独立哨兵值，
   * 反查表内存将增大 2~4 倍，且「256 项时是否升位」会改变既有区块的输出字节（格式/往返语义变化）。
   * 按「等价优先」原则保留现状并在此显式记录取舍。
   */

  @Override
  public int idFor(int value) {
    int id = this.byValue[value] & 0xFF;
    // byValue 复用、可能残留上一轮的登记：只有 id 落在已登记区间且反向表自洽才算命中（见类注释）
    boolean registered = id != 0xFF && id < this.size && this.byId[id] == value;
    if (!registered) {
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
      // 同一调色板里不允许重复的方块状态值：合法数据由写入端按「首次出现顺序」去重，重复即数据损坏。
      // 若不在此拦截，后写入的 id 会覆盖 byValue 反查记录，使同一方块状态对应两个索引——后续
      // valueFor(idFor(v)) 可能返回另一个 id，导致本地索引与调色板不一致（静默改写语义）。按 fail-open
      // 约定在此早失败：异常由上层解码兜底，本 section 被拒绝、封包链路不受影响。
      // 判重同样靠 byId 反查校验（byValue 未清零，可能残留）：仅已在本次循环登记过的 id（< id）且反向自洽才算重复。
      int existing = this.byValue[value] & 0xFF;
      if (existing != 0xFF && existing < id && this.byId[existing] == value) {
        throw new IndexOutOfBoundsException(
            "duplicate palette value: " + value + " at id " + id);
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
    if (value < 0 || value >= this.byValue.length) {
      return false;
    }
    // 与 idFor 同一套反查校验：byValue 可能残留上一轮的登记，必须由 byId 确认自洽
    int id = this.byValue[value] & 0xFF;
    return id != 0xFF && id < this.size && this.byId[id] == value;
  }
}