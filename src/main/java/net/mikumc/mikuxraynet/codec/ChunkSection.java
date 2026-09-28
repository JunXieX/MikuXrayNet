
package net.mikumc.mikuxraynet.codec;

import java.util.Arrays;
import java.util.function.IntPredicate;
import io.netty.buffer.ByteBuf;

/**
 * 单个 16×16×16 section 的方块数据：方块计数、bitsPerBlock、调色板与位打包索引数组。
 *
 * <p>关键约束（与上游实现一致，勿按「最优」改写）：
 * <ul>
 *   <li>bitsPerBlock=0 用单值调色板；读取时位宽 1 保留原值（兼容 FAWE 的非标准区块格式）；
 *       位宽 2..8 会被归一化为 {@code max(4, bitsPerBlock)}；位宽 &gt;8 一律按 direct 处理，
 *       且位宽取自 {@code registryAccessor.getMaxBitsPerBlockState()}；</li>
 *   <li>{@link #grow(int, int)} 升位时会把旧调色板索引整体重映射到新调色板，因此方块状态语义不变，
 *       但调色板索引顺序可能改变；</li>
 *   <li>元素序号为 {@code y << 8 | z << 4 | x}（共 4096 项）；</li>
 *   <li>{@link #write(ByteBuf)} 的字节布局：方块计数(short) → [流体计数(short)] → bitsPerBlock(byte)
 *       → 调色板 → [long 数组长度 VarInt] → long 数组。</li>
 * </ul>
 */
public class ChunkSection {

  private static final int SECTION_VOLUME = 4096;

  private final RegistryAccessor registryAccessor;
  private final ChunkVersionFlags versionFlags;
  private final ChunkScratch scratch;

  private int blockCount;
  private int fluidCount;
  private int bitsPerBlock = -1;
  /** 是否被外部改写（供选择性 section 重编码判断“可原样搬运”）。 */
  private boolean modified;

  private Palette palette;
  private VarBitBuffer data;

  ChunkSection(ChunkCodec codec, ChunkScratch scratch) {
    this.registryAccessor = codec.registryAccessor();
    this.versionFlags = codec.versionFlags();
    this.scratch = scratch;

    this.setBitsPerBlock(0, true);
  }

  public RegistryAccessor registryAccessor() {
    return registryAccessor;
  }

  /** 所属区块的按线程复用 scratch，供调色板与位打包缓冲区借用数组。 */
  ChunkScratch scratch() {
    return scratch;
  }

  private void setBitsPerBlock(int bitsPerBlock, boolean grow) {
    if (this.bitsPerBlock != bitsPerBlock) {
      if (versionFlags.hasSingleValuePalette() && bitsPerBlock == 0) {
        this.bitsPerBlock = 0;
        this.palette = new SingleValuePalette(this, 0);
      } else if (!grow && bitsPerBlock == 1) {
        // 兼容第三方插件（如 FAWE）改写区块后产生的非法 bitsPerBlock == 1
        this.bitsPerBlock = bitsPerBlock;
        this.palette = new IndirectPalette(this.bitsPerBlock, this);
      } else if (bitsPerBlock <= 8) {
        this.bitsPerBlock = Math.max(4, bitsPerBlock);
        this.palette = new IndirectPalette(this.bitsPerBlock, this);
      } else {
        this.bitsPerBlock = registryAccessor.getMaxBitsPerBlockState();
        this.palette = new DirectPalette();
      }

      if (this.bitsPerBlock == 0) {
        this.data = new ZeroVarBitBuffer(4096);
      } else {
        this.data = new SimpleVarBitBuffer(this.bitsPerBlock, 4096,
            scratch.longs(SimpleVarBitBuffer.calculateArraySize(this.bitsPerBlock, 4096)));
      }
    }
  }

  int grow(int bitsPerBlock, int blockId) {
    Palette palette = this.palette;
    VarBitBuffer data = this.data;

    this.setBitsPerBlock(bitsPerBlock, true);

    for (int i = 0; i < data.size(); i++) {
      int preBlockId = palette.valueFor(data.get(i));
      this.data.set(i, this.palette.idFor(preBlockId));
    }

    return this.palette.idFor(blockId);
  }

  static int positionToIndex(int x, int y, int z) {
    return y << 8 | z << 4 | x;
  }

  public void setBlockState(int x, int y, int z, int blockId) {
    this.setBlockState(positionToIndex(x, y, z), blockId);
  }

  public void setBlockState(int index, int blockId) {
    this.modified = true;
    int prevBlockId = this.getBlockState(index);

    if (!registryAccessor.isAir(prevBlockId)) {
      --this.blockCount;

      if (registryAccessor.isFluid(prevBlockId)) {
        --this.fluidCount;
      }
    }

    if (!registryAccessor.isAir(blockId)) {
      ++this.blockCount;

      if (registryAccessor.isFluid(blockId)) {
        ++this.fluidCount;
      }
    }

    int paletteIndex = this.palette.idFor(blockId);
    this.data.set(index, paletteIndex);
  }

  public int getBlockState(int index) {
    return this.palette.valueFor(this.data.get(index));
  }

  public boolean isEmpty() {
    return this.blockCount == 0;
  }

  /**
   * 调色板级预筛：本 section 的<b>调色板条目</b>里是否存在满足 {@code test} 的方块状态。
   *
   * <p>只看调色板（O(条目数)），不逐格读 4096 次。返回 {@code false} 可安全整段跳过：方块的本地 id
   * 只能取自调色板，条目里一个都没有 ⇒ 整段必然不含；返回 {@code true} 只是「可能有」——间接调色板
   * 可能留有数据未引用的条目。这类查询一律不会把「其实含有」误判成 {@code false}。
   *
   * <p><b>直接调色板（{@code bitsPerBlock > 8}）恒返回 {@code true}</b>：它没有条目表（本地 id 即全局
   * 状态 id），无法在不扫描数据的前提下证明「不含」，因此保守不跳过，语义与不开预筛完全一致。
   */
  public boolean paletteCouldContain(IntPredicate test) {
    int entries = this.palette.size();
    if (entries == 0) {
      return true;
    }
    for (int id = 0; id < entries; id++) {
      if (test.test(this.palette.valueFor(id))) {
        return true;
      }
    }
    return false;
  }

  public void write(ByteBuf buffer) {
    buffer.writeShort(this.blockCount);

    if (this.versionFlags.hasFluidCount()) {
      buffer.writeShort(this.fluidCount);
    }

    buffer.writeByte(this.bitsPerBlock);
    this.palette.write(buffer);

    long[] data = this.data.toArray();

    if (versionFlags.hasLongArrayLengthField()) {
      ByteBufUtil.writeVarInt(buffer, data.length);
    }

    for (long entry : data) {
      buffer.writeLong(entry);
    }
  }

  /**
   * 从缓冲区读入该 section 的方块计数、位宽、调色板与位打包数据。
   *
   * <p>不返回任何扁平化数组：调用方只关心 section 内部状态，额外构造 4096 项 int 数组纯属浪费。
   */
  public void read(ByteBuf buffer) {
    this.blockCount = buffer.readShort();

    if (this.versionFlags.hasFluidCount()) {
      this.fluidCount = buffer.readShort();
    }

    this.setBitsPerBlock(buffer.readUnsignedByte(), false);

    this.palette.read(buffer);

    long[] data = this.data.toArray();

    if (versionFlags.hasLongArrayLengthField()) {
      int length = ByteBufUtil.readVarInt(buffer);
      if (data.length != length) {
        throw new IndexOutOfBoundsException("data.length != VarBitBuffer::size " + length + " " + this.data);
      }
    }

    for (int i = 0; i < data.length; i++) {
      data[i] = buffer.readLong();
    }
  }

  /** 该 section 是否被外部通过 {@link #setBlockState}（或调色板重排/裁剪）改写。 */
  public boolean isModified() {
    return this.modified;
  }

  /** 当前每方块位宽（0=单值，4..8=间接，&gt;8=直接；供位宽预算与直方图诊断读取）。 */
  public int bitsPerBlock() {
    return this.bitsPerBlock;
  }

  /** 当前调色板条目数（单值 1、间接 0..容量、直接 0）。 */
  public int paletteSize() {
    return this.palette.size();
  }

  /** 调色板是否已包含某方块状态（预算筛选「替换方块是否已在调色板内」用；直接调色板恒 true）。 */
  public boolean paletteContains(int stateId) {
    return this.palette.contains(stateId);
  }

  /** 按序号读出全部 4096 个方块状态（供重排自检与单测；不改变任何状态）。 */
  public int[] readAllBlockStates() {
    int[] states = new int[SECTION_VOLUME];
    for (int i = 0; i < states.length; i++) {
      states[i] = this.getBlockState(i);
    }
    return states;
  }

  /**
   * 按出现频次重排调色板：出现次数多的方块状态排到低位索引，从而让位打包数据产生更多 0 位、提升网络压缩率。
   *
   * <p>只对间接调色板生效（单值/直接调色板没有可重排的调色板段，返回 false）。调色板值与位打包索引
   * 同步重映射，因此方块状态语义完全不变；位宽与方块计数不动。
   *
   * @param verify true 时对重排前后的方块序列做自检，不一致即抛 {@link IllegalStateException}
   *               （由调用方 fail-open）；会额外分配两份 4096 长数组，仅在配置开启时使用
   * @return 是否实际发生了重排
   */
  public boolean reorderPaletteByFrequency(boolean verify) {
    if (!(this.palette instanceof IndirectPalette indirectPalette)) {
      return false;
    }

    int size = indirectPalette.paletteSize();
    if (size <= 1) {
      return false;
    }

    int[] before = verify ? readAllBlockStates() : null;

    int[] frequency = new int[size];
    for (int i = 0; i < SECTION_VOLUME; i++) {
      frequency[this.data.get(i)]++;
    }

    // 频次降序；同频保持原索引顺序，保证结果确定（同输入必然同输出，缓存可复用）
    Integer[] order = new Integer[size];
    for (int i = 0; i < size; i++) {
      order[i] = i;
    }
    Arrays.sort(order, (a, b) -> frequency[b] != frequency[a]
        ? Integer.compare(frequency[b], frequency[a])
        : Integer.compare(a, b));

    int[] remap = new int[size];
    int[] values = new int[size];
    boolean changed = false;
    for (int newId = 0; newId < size; newId++) {
      int oldId = order[newId];
      remap[oldId] = newId;
      values[newId] = indirectPalette.valueAt(oldId);
      if (oldId != newId) {
        changed = true;
      }
    }
    if (!changed) {
      return false;
    }

    for (int i = 0; i < SECTION_VOLUME; i++) {
      this.data.set(i, remap[this.data.get(i)]);
    }
    indirectPalette.rebuild(values);
    this.modified = true;

    if (verify && !Arrays.equals(before, readAllBlockStates())) {
      throw new IllegalStateException("调色板重排自检失败：方块序列发生变化");
    }
    return true;
  }

  /**
   * 裁剪调色板里「引用计数为 0」的失效条目（被伪装替换掉的矿等）并压缩索引；若裁剪后条目数
   * 降到更低位宽阈值内，则连位宽一并下调（如 5 位 17 条 → 裁到 15 条 → 4 位）。
   *
   * <p>这是「调色板位宽预算封顶」的收尾步骤：替换只会往调色板里加伪装方块，被替换掉的目标方块
   * 条目失去全部引用后仍占着调色板与位打包位宽。本方法按出现频次统计引用计数，只保留被引用的
   * 条目（<b>按原索引顺序压缩</b>，保证结果确定），再按压缩后的条目数计算最小位宽
   * {@code max(4, ceil(log2(kept)))} 重建位打包数据——方块状态语义完全不变，位宽<b>单调不增</b>。
   *
   * <p>只对间接调色板生效（单值没有失效条目可言，直接调色板没有调色板段）；无可裁剪条目时
   * 返回 false 且不做任何修改。失败（理论上只会是内部不变量被破坏）由调用方兜底：放弃裁剪、
   * 保留已完成替换的结果（宁可包体大，不可漏伪装）。
   *
   * @param verify true 时对裁剪前后的方块序列做自检，不一致即抛 {@link IllegalStateException}
   *               （会额外分配两份 4096 长数组，仅在排查问题时开启）
   * @return 是否实际发生了裁剪（含降位宽）
   */
  public boolean compactPalette(boolean verify) {
    if (!(this.palette instanceof IndirectPalette indirectPalette)) {
      return false;
    }

    int size = indirectPalette.paletteSize();
    if (size <= 1) {
      return false;
    }

    int[] before = verify ? readAllBlockStates() : null;

    // 1) 统计每个调色板条目的引用计数
    int[] frequency = new int[size];
    for (int i = 0; i < SECTION_VOLUME; i++) {
      frequency[this.data.get(i)]++;
    }

    // 2) 保留引用非 0 的条目（按原索引顺序压缩，同输入必然同输出，缓存可复用）
    int kept = 0;
    for (int id = 0; id < size; id++) {
      if (frequency[id] > 0) {
        kept++;
      }
    }
    if (kept == size) {
      return false;
    }

    int[] remap = new int[size];
    int[] values = new int[kept];
    int newId = 0;
    for (int id = 0; id < size; id++) {
      if (frequency[id] > 0) {
        remap[id] = newId;
        values[newId] = indirectPalette.valueAt(id);
        newId++;
      }
    }

    // 3) 计算裁剪后的最小位宽并重建：kept ≤ size ⇒ targetBits ≤ bitsPerBlock，位宽单调不增
    //   （间接调色板下限 4 位；kept=1 时也保持 4 位间接，不迁移到单值调色板）
    int targetBits = Math.max(4, 32 - Integer.numberOfLeadingZeros(kept - 1));

    if (targetBits == this.bitsPerBlock) {
      // 位宽不变：原地压缩索引，只更新调色板
      for (int i = 0; i < SECTION_VOLUME; i++) {
        this.data.set(i, remap[this.data.get(i)]);
      }
      indirectPalette.retain(values);
    } else {
      // 降位宽：换更窄的位打包缓冲与更小容量的调色板，再按压缩索引重写全部 4096 项。
      // 旧调色板/旧缓冲的取值已先提取到 values/remap，setBitsPerBlock 换掉引用后再整体写入。
      VarBitBuffer oldData = this.data;
      this.setBitsPerBlock(targetBits, true);
      for (int i = 0; i < SECTION_VOLUME; i++) {
        this.data.set(i, remap[oldData.get(i)]);
      }
      ((IndirectPalette) this.palette).retain(values);
    }
    this.modified = true;

    if (verify && !Arrays.equals(before, readAllBlockStates())) {
      throw new IllegalStateException("调色板裁剪自检失败：方块序列发生变化");
    }
    return true;
  }
}