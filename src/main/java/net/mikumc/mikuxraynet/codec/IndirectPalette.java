
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * 间接调色板（位宽 4..8；另有 FAWE 兼容档位宽 1）：按首次出现顺序为方块状态分配本地索引，
 * 索引表长度为 {@code 1 << bitsPerValue}。
 *
 * <p>关键约束：索引表写满（本地索引达到 {@code 1 << bitsPerValue}）时调用 {@link ChunkSection#grow(int, int)} 升位——
 * 位宽 4~7 时升到 bitsPerValue+1 的间接调色板；位宽 1 的兼容档会经 {@code max(4, bits)} 直接升到 4 位；
 * 8 位写满（出现第 257 个不同状态）时 {@code grow(9)} 会直接切换为直接调色板（位宽取注册表上限）。
 * 因此 8 位间接调色板可容纳满 256 项，与原版上限一致。
 * {@code byValue} 以方块状态 id 直接下标，故长度必须覆盖注册表全部方块状态
 * （由 {@link ChunkScratch} 按线程复用，避免每个 section 都新建这张 32 KB 级反查表）。
 *
 * <p><b>复用缓冲不做整表清零、也不使用 0xFF 哨兵</b>：{@code byValue} 是 scratch 复用的大数组，构造时整表
 * memset 0xFF（注册表全部方块状态，≈2.6 万字节）就是每个 section 一次的无谓开销。改为「用反向表 {@code byId}
 * 校验命中」：仅当 {@code id < size} 且 {@code byId[id] == value} 时 {@code byValue[value]} 才视为有效，否则按
 * 残留处理；因此不清零也不会把上一轮登记的条目误判成本调色板已登记。纯查表校验、不新增常驻内存。
 *
 * <p><b>为什么不能用 0xFF 当「未登记」哨兵</b>：{@code byValue} 是 {@code byte[]}，0xFF 正是 8 位调色板的合法
 * 索引 255。旧实现在命中判定里额外要求 {@code id != 0xFF}，于是索引 255（调色板第 256 项）永远被判为未登记：
 * 读取原版产出的 256 项 8 位调色板后，对第 256 项调用 {@code idFor}/{@code contains} 会误走 {@code grow(9)}，
 * 白白把整个 section 从 8 位间接重编码成 15 位直接（语义不变但开销巨大）。既然命中完全由 {@code byId} 反查校验
 * 保证，哨兵已无必要，故彻底移除，{@code byId} 数组本身逐项校验、不会因 {@code byValue} 残留而误命中。
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

    // byValue 由 scratch 复用、内容未初始化：不做整表 memset，命中与否交由 byId 反查校验（见类注释）
    this.byValue = chunkSection.scratch().bytes(chunkSection.registryAccessor().getUniqueBlockStateCount());
    this.byId = new int[1 << bitsPerValue];
  }

  @Override
  public int idFor(int value) {
    int id = this.byValue[value] & 0xFF;
    // byValue 复用、可能残留上一轮的登记：只有 id 落在已登记区间且反向表自洽才算命中（见类注释）。
    // 不能用 0xFF 排除 id=255——那正是 8 位调色板的合法第 256 项。
    boolean registered = id < this.size && this.byId[id] == value;
    if (!registered) {
      id = this.size++;

      if (id < this.byId.length) {
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
      // 判重同样靠 byId 反查校验（byValue 未清零，可能残留）：仅已在本次循环登记过的 id（< id）且
      // 反向自洽才算重复。不以 0xFF 排除——0xFF 是合法索引 255，且「曾登记于 255」不可能再触发重复
      // （255 已是 8 位调色板的最后一项，之后 size 必超容量）。
      int existing = this.byValue[value] & 0xFF;
      if (existing < id && this.byId[existing] == value) {
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
   * 用保留的值<b>子集</b>重建索引表（裁剪掉未列出的旧条目）。
   *
   * <p>与「全量排列」不同，本方法允许 {@code values} 只包含原值集合的一部分。
   * 正确性由「{@code size} 收缩 + {@code byId} 反查校验」共同保证：命中判定要求
   * {@code id < size && byId[id] == value}，被裁剪条目的 {@code byValue} 反查记录即使仍残留旧索引，
   * 也因 {@code byId} 对不上（或索引越界）而不被误命中，之后再次遇到这些方块状态时会作为新条目重新登记。
   * （不再像过去那样把 {@code byValue} 清回 0xFF：那既是合法索引 255、也与「不使用哨兵」的判定口径相悖。）
   */
  void retain(int[] values) {
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
    // 与 idFor 同一套反查校验：byValue 可能残留上一轮的登记，必须由 byId 确认自洽。
    // 不能排除 0xFF——那是 8 位调色板第 256 项（索引 255）的合法编码。
    int id = this.byValue[value] & 0xFF;
    return id < this.size && this.byId[id] == value;
  }
}