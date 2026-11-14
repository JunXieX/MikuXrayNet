
package net.mikumc.mikuxraynet.codec;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import io.netty.buffer.ByteBuf;
import net.mikumc.mikuxraynet.occlusion.BitmapOcclusion;
import net.mikumc.mikuxraynet.occlusion.EmptyOcclusion;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import net.mikumc.mikuxraynet.occlusion.SolidOcclusion;

/**
 * 单个 16×16×16 section 的方块数据：方块计数、bitsPerBlock、调色板与位打包索引数组。
 *
 * <p>关键约束（与上游线格式一致，勿按「最优」改写）：
 * <ul>
 *   <li>bitsPerBlock=0 用单值调色板；读取时位宽 1 保留原值（兼容 FAWE 的非标准区块格式）；
 *       位宽 4..8 为间接调色板（{@code max(4, bitsPerBlock)} 仅对 4..8 是无操作）；
 *       位宽 2..3 视为非法编码并拒绝（旧实现静默归一化为 4 位，见 {@link #setBitsPerBlock} 注释）；
 *       位宽 &gt;8 为 direct（global）格式：<b>读取时保留声明位宽</b>（合法 9..{@value #MAX_DIRECT_BITS}，
 *       服务端按调色板规模选择，不必等于注册表位宽），只有本插件 {@link #grow(int, int)} 升 direct 时
 *       才取注册表位宽（见 {@link #setBitsPerBlock} 注释里的一手取证）；</li>
 *   <li>{@link #grow(int, int)} 升位时会把旧调色板索引整体重映射到新调色板，因此方块状态语义不变，
 *       但调色板索引顺序可能改变；</li>
 *   <li>元素序号为 {@code y << 8 | z << 4 | x}（共 4096 项）；</li>
 *   <li>{@link #write(ByteBuf)} 的字节布局：方块计数(short) → [流体计数(short)] → bitsPerBlock(byte)
 *       → 调色板 → [long 数组长度 VarInt] → long 数组。</li>
 * </ul>
 */
public class ChunkSection {

  private static final int SECTION_VOLUME = 4096;

  /**
   * 直接（direct/global）格式声明位宽的合法上界。
   *
   * <p>取自服务端 {@code SimpleBitStorage} 构造的 {@code Validate.inclusiveBetween(1, 32, bits)}
   * （Paper 26.3 字节码实测）。声明位宽由服务端按调色板规模选择，<b>不必等于注册表位宽</b>。
   */
  private static final int MAX_DIRECT_BITS = 32;

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
      } else if (!grow && (bitsPerBlock == 2 || bitsPerBlock == 3)) {
        // 位宽 2/3 只可能来自损坏数据或非标准写入：原版/Paper 的间接调色板下限就是 4 位，
        // 绝不会下发 2 或 3。旧实现把它静默归一化成 4 位继续读——可位打包是按「声明的位宽」
        // 写出的，归一化后每 long 项数与数组长度都会变，于是同一段字节会被<b>按错误位宽重新解释</b>：
        // 既读出错值，又会多读若干 long、把后续（群系容器/下一个 section）的字节吞进方块数据，
        // 最终解析错位。这是「碰巧能过」的静默错误，而非可用兼容（真正会发生的 FAWE 场景是
        // 上面单独保留的 bits==1）。因此与调色板重复值、越界 id 等其它非法编码一致：显式拒绝，
        // 交由上层解码 fail-open（放行原包、不改写）。
        //
        // 只对<b>读取</b>路径（!grow）生效：升位路径 grow() 会从 FAWE 兼容的 1 位逐级 +1 到 2、3，
        // 那是本插件自己产生的合法中间态，必须继续走下面的 max(4, bits) 直接跳到 4 位。
        throw new IllegalArgumentException("非法的 bitsPerBlock=" + bitsPerBlock
            + "（合法：0 单值 / 1 兼容档 / 4..8 间接 / >8 直接）");
      } else if (bitsPerBlock <= 8) {
        this.bitsPerBlock = Math.max(4, bitsPerBlock);
        this.palette = new IndirectPalette(this.bitsPerBlock, this);
      } else if (grow) {
        // 升位路径：8 位间接调色板写满（出现第 257 个不同状态）时由本插件自己发起，
        // 声明位宽取注册表位宽（能表示全部已知状态的最小位宽）。
        this.bitsPerBlock = registryAccessor.getMaxBitsPerBlockState();
        this.palette = new DirectPalette();
      } else {
        // 读取路径：**直接格式的存储位宽由服务端按调色板规模决定，不必等于注册表位宽**。
        // 一手取证（Paper 26.3 字节码）：
        //   Strategy#getConfigurationForBitCount(bits)：bits>8 一律 new Configuration.Global(
        //       globalPaletteBitsInMemory, bits)——bitsInStorage 取声明值，bitsInMemory 才是注册表位宽；
        //   PalettedContainer#pack → Strategy#getConfigurationForPaletteSize → bitsInStorage，
        //       即出站封包的声明位宽随实际调色板规模变化（HashMapPalette 满 8 位时 onResize(9)）。
        // 因此 9..32 都是合法声明值（上界 32 来自 SimpleBitStorage 构造的
        // Validate.inclusiveBetween(1, 32, bits)）。旧实现要求「必须等于注册表位宽 15」，
        // 会把服务端合法下发的 16 位等 section 判为非法 → 整块 fail-open → 该区块 24 段全部不伪装，
        // 矿物以真实状态下发给透视端（真机 dump 实测：bitsPerBlock=16 应为 15）。
        //
        // 必须按声明位宽解包并重编码：位打包是按声明值写出的，按别的位宽重解释会读出错值、
        // 多读/少读若干 long，把后续（群系容器 / 下一个 section）的字节吞进方块数据；
        // 1.21.5+ 没有 long 数组长度字段可校验，错位没有任何内建校验能兜住。
        if (bitsPerBlock > MAX_DIRECT_BITS) {
          throw new IllegalArgumentException("非法的直接格式位宽 bitsPerBlock=" + bitsPerBlock
              + "（合法上界 " + MAX_DIRECT_BITS + "，见 SimpleBitStorage）");
        }
        this.bitsPerBlock = bitsPerBlock;
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

    // 升 direct（全局）格式时，位打包按注册表位宽写出：状态 id 必须落在 [0, 2^registryBits) 内。
    // 间接调色板读侧只按 paletteCapacity（探测上限 262144）校验，自定义方块（CraftEngine 等）的
    // id 可能 ≥ 2^registryBits（vanilla 约 3.2 万、registryBits=15 → 上限 32768，仅差几百）。
    // 若不拦下，SimpleVarBitBuffer.set 会按位宽掩码把它静默截断成另一个状态（方块错乱、不计数、
    // 不 fail-open）。这里显式拒绝，交由上层 rewrite 的 catch 按原包放行（与 bitsPerBlock 2/3、
    // 调色板重复值等非法编码同一处置口径）。
    if (this.palette instanceof DirectPalette) {
      int stateLimit = 1 << this.bitsPerBlock;
      for (int i = 0; i < data.size(); i++) {
        int preBlockId = palette.valueFor(data.get(i));
        if (preBlockId < 0 || preBlockId >= stateLimit) {
          throw new IllegalArgumentException("状态 id " + preBlockId
              + " 超出直接格式位宽 " + this.bitsPerBlock + " 的表示范围，放弃改写");
        }
        this.data.set(i, preBlockId);
      }
      if (blockId < 0 || blockId >= stateLimit) {
        throw new IllegalArgumentException("伪装状态 id " + blockId
            + " 超出直接格式位宽 " + this.bitsPerBlock + " 的表示范围，放弃改写");
      }
      return blockId;
    }

    for (int i = 0; i < data.size(); i++) {
      int preBlockId = palette.valueFor(data.get(i));
      this.data.set(i, this.palette.idFor(preBlockId));
    }

    return this.palette.idFor(blockId);
  }

  static int positionToIndex(int x, int y, int z) {
    // 段内坐标唯一实现见 SectionOcclusion.packLocal（不变量 16）；codec 分层可依赖 occlusion。
    return net.mikumc.mikuxraynet.occlusion.SectionOcclusion.packLocal(x, y, z);
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
    // 直接调色板没有条目表（size() 恒为 0），无法在不扫描数据的前提下证明「不含」，只能保守放行。
    // 必须先单独判它：下面「0 条目 ⇒ 返回 false」只适用于间接调色板。
    if (this.palette instanceof DirectPalette) {
      return true;
    }
    int entries = this.palette.size();
    if (entries == 0) {
      // 间接调色板读到 0 条目（只可能来自损坏/非原版写入）：条目表为空 ⇒ 数据里任何索引都取不到
      // 方块状态，整段必然不含目标方块，可安全跳过。这里**不能**沿用「保守返回 true」：那会让本段成为
      // 候选，随后逐格 getBlockState 在 IndirectPalette.valueFor 抛 IndexOutOfBoundsException，
      // 被上层 catch 成「整块区块 fail-open」——把本该照常改写的其余 section 一并放弃（该藏的矿全裸露）。
      // 返回 false 后该 section 保持未修改，由 ChunkSectionHolder.write 原样搬运它自己的原始字节，
      // 与 read() 里「空调色板按原样搬运、不改写」的既有口径一致。
      return false;
    }
    for (int id = 0; id < entries; id++) {
      if (test.test(this.palette.valueFor(id))) {
        return true;
      }
    }
    return false;
  }

  /**
   * 一次遍历完成两件事（<b>建遮挡位图</b>可选、<b>收集目标格索引</b>可选），供反矿透改写链路使用。
   *
   * <p><b>为什么合并</b>：建表与逐格扫描都要读同一份 {@code data}（每个候选段 4096 次）。分成两遍时
   * 位图建表约占改写耗时的 40%、逐格扫描约 28%；合并后只付一遍索引读取，两个判定各用一张
   * 「调色板条目 → 布尔」表，逐格不再做 {@code valueFor} 与谓词调用。
   *
   * <p><b>快路径（只在不需要收集目标时生效）</b>：间接调色板若「没有任何条目遮挡」→ 直接
   * {@link EmptyOcclusion}；「全部条目遮挡」→ 直接 {@link SolidOcclusion}。两者都不必逐格遍历——
   * 真实主世界列里全空气段与全实心段占多数，这条能整段省下 4096 次读取。
   *
   * <p><b>语义约束</b>：只读本段当前状态，绝不修改任何方块数据；位图必须反映<b>调用时刻</b>的几何
   * （调用方在改写之前调用）。直接调色板（{@code bitsPerBlock > 8}）没有条目表，退回逐格状态判定。
   *
   * @param occludingTable 遮挡谓词；{@code null} 表示不建位图（此时返回 {@code null}）
   * @param targetTable    目标方块谓词；{@code null} 表示不收集目标
   * @param targetSink     目标格索引消费者（{@code targetTable} 非 null 时必传），按索引升序收到命中格
   * @return 遮挡视图；{@code occludingTable == null} 时返回 {@code null}
   */
  public SectionOcclusion buildOcclusionAndTargets(IntPredicate occludingTable,
      IntPredicate targetTable, IntConsumer targetSink) {
    return buildOcclusionAndTargets(occludingTable, targetTable, targetSink, null);
  }

  /**
   * 同上，并可选地维护「每个调色板条目的引用次数」（供调色板收缩跳过频次扫描）。
   *
   * <p>{@code targetCounts} 长度须等于 {@link #paletteSize()}；遍历时对每个格子累加其条目索引，
   * 因此得到的是全部条目的精确频次。直接调色板没有条目表，传 {@code null}。
   */
  public SectionOcclusion buildOcclusionAndTargets(IntPredicate occludingTable,
      IntPredicate targetTable, IntConsumer targetSink, int[] targetCounts) {
    boolean needBitmap = occludingTable != null;
    if (!needBitmap && targetTable == null) {
      return null;
    }
    if (this.bitsPerBlock == 0) {
      int state = this.palette.valueFor(0);
      SectionOcclusion view = null;
      if (needBitmap) {
        view = occludingTable.test(state) ? SolidOcclusion.INSTANCE : EmptyOcclusion.INSTANCE;
      }
      if (targetTable != null && targetTable.test(state)) {
        for (int index = 0; index < SECTION_VOLUME; index++) {
          targetSink.accept(index);
        }
      }
      if (targetCounts != null && targetCounts.length > 0) {
        // 单值调色板：整段 4096 格都是同一条目（与是否目标无关，计数是「全部条目」的引用频次）
        targetCounts[0] = SECTION_VOLUME;
      }
      return view;
    }

    boolean direct = this.palette instanceof DirectPalette;
    int entries = direct ? 0 : this.palette.size();
    if (!direct && entries == 0) {
      // 空调色板（损坏/异常输入）：没有任何格子可读到合法状态。位图按「全不遮挡」处理，
      // 也不可能有目标格——不进入逐格循环，避免 targetByEntry[0] 越界。
      return needBitmap ? EmptyOcclusion.INSTANCE : null;
    }

    if (direct) {
      long[] words = needBitmap ? new long[BitmapOcclusion.WORD_COUNT] : null;
      int occludedCells = 0;
      for (int index = 0; index < SECTION_VOLUME; index++) {
        int state = this.data.get(index);
        if (needBitmap && occludingTable.test(state)) {
          words[index >>> 6] |= 1L << index;
          occludedCells++;
        }
        if (targetTable != null && targetTable.test(state)) {
          targetSink.accept(index);
        }
      }
      if (!needBitmap) {
        return null;
      }
      if (occludedCells == 0) {
        return EmptyOcclusion.INSTANCE;
      }
      if (occludedCells == SECTION_VOLUME) {
        return SolidOcclusion.INSTANCE;
      }
      return BitmapOcclusion.copyOf(words);
    }

    boolean[] occludingByEntry = null;
    boolean allOccluding = false;
    boolean noneOccluding = false;
    if (needBitmap) {
      occludingByEntry = new boolean[entries];
      int occludingEntries = 0;
      for (int id = 0; id < entries; id++) {
        boolean occluding = occludingTable.test(this.palette.valueFor(id));
        occludingByEntry[id] = occluding;
        if (occluding) {
          occludingEntries++;
        }
      }
      noneOccluding = occludingEntries == 0;
      allOccluding = occludingEntries == entries;
      if (noneOccluding || allOccluding) {
        // 整段结论恒定：不必逐格写位。但若同时要收集目标，仍需继续遍历（见下）。
        occludingByEntry = null;
      }
    }

    boolean[] targetByEntry = null;
    if (targetTable != null) {
      targetByEntry = new boolean[entries];
      for (int id = 0; id < entries; id++) {
        targetByEntry[id] = targetTable.test(this.palette.valueFor(id));
      }
    }

    if (occludingByEntry == null && targetByEntry == null) {
      // 位图结论已由调色板确定，且不需要逐格收集：直接返回，零 4096 次遍历。
      return allOccluding ? SolidOcclusion.INSTANCE : EmptyOcclusion.INSTANCE;
    }

    long[] words = occludingByEntry != null ? new long[BitmapOcclusion.WORD_COUNT] : null;
    int occludedCells = 0;
    for (int index = 0; index < SECTION_VOLUME; index++) {
      int id = this.data.get(index);
      if (words != null && occludingByEntry[id]) {
        words[index >>> 6] |= 1L << index;
        occludedCells++;
      }
      if (targetCounts != null && id < targetCounts.length) {
        // 同时维护「全部条目」的精确引用计数：收缩时无需再扫一遍 4096 格
        targetCounts[id]++;
      }
      if (targetByEntry != null && targetByEntry[id]) {
        targetSink.accept(index);
      }
    }

    if (!needBitmap) {
      return null;
    }
    if (allOccluding) {
      return SolidOcclusion.INSTANCE;
    }
    if (noneOccluding || occludedCells == 0) {
      return EmptyOcclusion.INSTANCE;
    }
    if (occludedCells == SECTION_VOLUME) {
      return SolidOcclusion.INSTANCE;
    }
    return BitmapOcclusion.copyOf(words);
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
    // blockCount 是有符号 short：合法范围 0..4096（section 体积）。负值或超过体积只可能来自损坏/非标准
    // 数据，且会被原样 writeShort 回包下发给客户端。与其它非法编码（bitsPerBlock 2/3、调色板
    // 重复值、越界 id）同口径：显式拒绝，交由上层解码 fail-open（放行原包、不改写）。
    if (this.blockCount < 0 || this.blockCount > SECTION_VOLUME) {
      throw new IllegalArgumentException(
          "非法的 blockCount=" + this.blockCount + "（section 体积为 0..4096）");
    }

    if (this.versionFlags.hasFluidCount()) {
      this.fluidCount = buffer.readShort();
      // 与 blockCount 同口径：流体计数合法范围同为 0..4096，越界同样只可能来自损坏数据，
      // 且会被原样写回客户端，故一并显式拒绝。
      if (this.fluidCount < 0 || this.fluidCount > SECTION_VOLUME) {
        throw new IllegalArgumentException(
            "非法的 fluidCount=" + this.fluidCount + "（section 体积为 0..4096）");
      }
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

    // 刻意不做「4096 个调色板索引全量校验」：约 0.12~0.20 ms/区块（占改写热路径 35%~60%），
    // 收益只是「更早失败」；真正越界时改写循环的 catch(RuntimeException) 会放弃本次改写并按原字节下发
    // （见 ObfuscationProcessor#rewrite），不存在半改写字节泄漏。
    // 直接调色板无条目表、单值调色板无位打包数据、空调色板按原样搬运——三者都不适用本条。
  }

  /** 该 section 是否被外部通过 {@link #setBlockState}（或调色板裁剪/降级）改写。 */
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

  /**
   * 某方块状态在当前调色板里的本地索引（仅间接调色板有意义；状态必须已在调色板内）。
   *
   * <p>供改写阶段按「原状态」递减目标条目引用计数；直接/单值调色板没有可裁剪条目，调用方不统计。
   */
  public int paletteIdFor(int stateId) {
    return this.palette.idFor(stateId);
  }

  /** 按序号读出全部 4096 个方块状态（供裁剪/降级自检与单测；不改变任何状态）。 */
  public int[] readAllBlockStates() {
    int[] states = new int[SECTION_VOLUME];
    for (int i = 0; i < states.length; i++) {
      states[i] = this.getBlockState(i);
    }
    return states;
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
    return compactPalette(verify, null);
  }

  /**
   * 同上，但引用计数可由调用方提供（[[knownCounts]]）：非目标条目预置 1、目标条目由扫描/替换维护，
   * 省掉每段一次 4096 次 {@code data.get} 的频次统计。
   *
   * <p>长度可小于当前调色板条目数（改写期间 grow 新增的条目）：越界条目按「有引用」处理。
   * 传 {@code null} 等价于旧行为（本方法自行扫描）。
   */
  public boolean compactPalette(boolean verify, int[] knownCounts) {
    if (!(this.palette instanceof IndirectPalette indirectPalette)) {
      return false;
    }

    int size = indirectPalette.paletteSize();
    if (size <= 1) {
      return false;
    }

    int[] before = verify ? readAllBlockStates() : null;

    // 1) 统计每个调色板条目的引用计数：调用方已提供（收集阶段 + 替换递减）时直接复用，
    //    省掉一次 4096 格扫描；越界条目（改写期间 grow 新增）按「有引用」处理。
    int[] frequency = new int[size];
    if (knownCounts != null) {
      for (int id = 0; id < size; id++) {
        frequency[id] = id < knownCounts.length ? knownCounts[id] : 1;
      }
    } else {
      for (int i = 0; i < SECTION_VOLUME; i++) {
        frequency[this.data.get(i)]++;
      }
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
    //   （间接调色板下限 4 位）。kept==1 时本方法仍是 4 位间接——更省的「单值调色板」表示由
    //   {@link #downgradePalette(boolean)} 在其后单独完成（职责分离：此处只做裁剪与常规降位）。
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

  /**
   * 调色板降级收尾：紧跟在 {@link #compactPalette(boolean)} 之后调用，把「收缩后仍偏大的」表示换成
   * 线格式允许的更省表示，是「位宽预算封顶」的第二段收益。
   *
   * <p><b>为什么需要</b>：{@code compactPalette} 只做「按引用计数裁剪 + 位宽下压」，其下限是 4 位间接调色板：
   * <ul>
   *   <li><b>被引用状态数 == 1</b> 时它仍写 4 位间接调色板（4096 个索引 ≈ 2048 字节），而协议允许
   *       <b>单值调色板</b>（{@code bitsPerBlock=0}：只写一个 VarInt 状态 id、无索引数组，约 10 字节）。
   *       地下纯石头区把矿脉全部伪装成石头后整节只剩一种方块，属常见且白白多花约 2KB/节 的场景；</li>
   *   <li><b>直接调色板（15 位）</b>改写后若被引用状态数 ≤ 256，应降回间接调色板并取最小位宽（4~8 位），
   *       而不是继续写 15 位——原实现完全不处理直接调色板。</li>
   * </ul>
   *
   * <p><b>语义与不变式</b>：只做「同一状态集合的等价重编码」，方块状态序列逐格不变；位宽<b>单调不增</b>
   * （间接 → 单值降为 0；直接 15 位 → 间接 ≤ 8 位）。不满足降级条件（被引用状态数 &gt; 256、或版本不支持
   * 单值调色板时的间接输入）返回 false 且不改动任何状态。
   *
   * <p><b>空节/全空气边界</b>：只按「数据实际引用的状态」判定，{@code blockCount} 与流体计数一律不动——
   * 因此绝不会把「一种方块的实心节」错判成空节，也不会改变任何方块语义。
   *
   * <p>失败（内部不变量被破坏）由调用方兜底：放弃降级、保留已完成替换的结果（宁可包体大，不可漏伪装）。
   *
   * @param verify true 时对降级前后的方块序列做自检，不一致即抛 {@link IllegalStateException}
   *               （由调用方 fail-open）；会额外分配两份 4096 长数组，仅在配置开启时使用
   * @return 是否实际发生了降级
   */
  public boolean downgradePalette(boolean verify) {
    if (this.bitsPerBlock == 0) {
      return false; // 已是单值调色板，无需降级
    }
    boolean direct = this.palette instanceof DirectPalette;
    boolean indirect = this.palette instanceof IndirectPalette;
    if (!direct && !indirect) {
      return false;
    }
    if (indirect && this.palette.size() == 0) {
      // 空调色板（损坏/异常输入）：数据里任何索引都越界。此处不降级，交由既有路径处理，
      // 避免把一个「本可原样搬运」的节变成非预期异常。
      return false;
    }
    if (indirect && this.palette.size() != 1) {
      // O(1) 早退：本方法只做「多状态 → 单状态」的降级。间接调色板经 compactPalette 之后
      // 必然满足 distinct == palette.size()（未使用的条目已被 retain 掉），因此 size != 1 时
      // 下面的 4096 × O(distinct) 收集循环注定以 distinct > 1 收场、返回 false。
      // 该路径在 width-budget 默认开启时对**每个被改写的 section** 都会走到，
      // 40~256 项时约 52 万次比较/section、1200 万次/区块，与改写主循环同量级——纯浪费。
      return false;
    }

    int[] before = verify ? readAllBlockStates() : null;

    // 统计数据实际引用到的「方块状态」集合（首次出现顺序；上限 257，超过 256 即无法降为间接）。
    // 直接调色板的数据值即状态 id；间接调色板的数据值是本地索引，需经调色板换算成状态 id。
    // 用线性查找而非哈希：降级只在「确实需要」时发生，且最多看 256 个不同状态，代价可忽略。
    Palette currentPalette = this.palette;
    int[] states = new int[257];
    int distinct = 0;
    // 直接调色板的数据值就是状态 id：损坏/伪造数据里的 id 仍可能超出反查表容量。
    // 一旦越界，下面的 idFor 会在「已切换位宽与调色板之后」才抛异常，把这个 section 留成半改写的损坏态。
    // 因此先在收集阶段拦下：越界即放弃降级、不改动任何状态（fail-open 的方向是「保持原表示」）。
    //
    // 判据必须与降级目标（IndirectPalette#idFor / read）同口径，即 paletteCapacity()——它覆盖
    // vanilla 之后追加的自定义方块状态。用 getUniqueBlockStateCount()（仅 vanilla 映射数）会把
    // 合法的自定义 id 误判为越界，让本该降位宽的 direct 段白占带宽。
    int stateLimit = direct ? registryAccessor.paletteCapacity() : Integer.MAX_VALUE;
    for (int i = 0; i < SECTION_VOLUME; i++) {
      int state = direct ? this.data.get(i) : currentPalette.valueFor(this.data.get(i));
      if (direct && (state < 0 || state >= stateLimit)) {
        return false; // 越界状态 id：保持原表示，绝不做半途改写
      }
      boolean seen = false;
      for (int j = 0; j < distinct; j++) {
        if (states[j] == state) {
          seen = true;
          break;
        }
      }
      if (!seen) {
        if (distinct == states.length - 1) {
          return false; // 引用状态数 > 256：无法降为间接，保持原表示（直接调色板继续用）
        }
        states[distinct++] = state;
      }
    }
    if (distinct == 0) {
      return false; // 无数据可读/空调色板：不降级（既不动损坏数据，也不把有方块的节错判成空）
    }

    if (distinct == 1) {
      if (versionFlags.hasSingleValuePalette()) {
        // 单值调色板：只写一个 VarInt 状态 id、无位打包数据（ZeroVarBitBuffer 恒读 0）。
        // 为什么直接改字段而不复用 setBitsPerBlock：后者只能从 0 起建调色板（SingleValuePalette 初值 -1/0），
        // 无法承载这里已知的唯一状态 id。
        this.bitsPerBlock = 0;
        this.palette = new SingleValuePalette(this, states[0]);
        this.data = new ZeroVarBitBuffer(SECTION_VOLUME);
        this.modified = true;
        if (verify && !Arrays.equals(before, readAllBlockStates())) {
          throw new IllegalStateException("调色板降级自检失败：方块序列发生变化");
        }
        return true;
      }
      // 版本不支持单值调色板：退化为最小位宽间接（4 位），语义不变（下面 indirect 分支会拦住，不会重复降级）
    }

    if (indirect) {
      // 间接调色板经 compactPalette 收缩后，位宽已满足位宽-条目数的最小对应关系（除非上面 distinct==1 已降为单值）；
      // 此处不再重复重建，保持「位宽单调不增」且不做无谓拷贝。
      return false;
    }

    // 直接调色板 → 间接调色板：位宽取满足状态数的最小值（间接下限 4 位，上限 8 位）。
    int targetBits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinct - 1));
    VarBitBuffer oldData = this.data;
    this.setBitsPerBlock(targetBits, true);
    IndirectPalette rebuilt = (IndirectPalette) this.palette;
    for (int i = 0; i < SECTION_VOLUME; i++) {
      // oldData.get(i) 即方块状态 id（直接调色板语义），idFor 按首次出现顺序分配本地索引
      this.data.set(i, rebuilt.idFor(oldData.get(i)));
    }
    this.modified = true;

    if (verify && !Arrays.equals(before, readAllBlockStates())) {
      throw new IllegalStateException("调色板降级自检失败：方块序列发生变化");
    }
    return true;
  }
}