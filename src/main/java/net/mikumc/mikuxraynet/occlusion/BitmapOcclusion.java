package net.mikumc.mikuxraynet.occlusion;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * 逐格遮挡位图：4096 个方块各占 1 位，共 {@code 64} 个 long（512 字节）。
 *
 * <p><b>单写多读（SWMR）</b>：写入只会在 {@link OcclusionStore} 的写锁内进行（建表或单格补丁），
 * 读取在异步射线线程上并发进行。位读写采用 {@link VarHandle} 的 <b>opaque</b> 语义
 * （{@code getOpaque}/{@code setOpaque}）：普通字段读取理论上可被 JIT 无限期缓存，opaque 读保证
 * 「看到的是某个真实发生过的值」，代价远低于 {@code volatile}。单格位是独立的——读到相邻格子的
 * 新值/旧值不影响本格的判定，因此不需要更强的可见性保证。
 *
 * <p>位下标用 {@link SectionOcclusion#packLocal}（{@code y<<8|z<<4|x}），与工程内既有局部坐标编码一致。
 * {@code packed >>> 6} 定位所属 long，{@code 1L << packed} 定位位（移位量对 64 取模，恰好落在同一 long 内）。
 *
 * <p><b>可变</b>：{@link #setOccluding} 就地修改位图。调用方负责保证同一时刻只有一个写者。
 */
public final class BitmapOcclusion implements SectionOcclusion {

  /** 方块总数（16³）。 */
  public static final int BLOCK_COUNT = 4096;
  /** 位图 long 个数（4096 / 64）。 */
  public static final int WORD_COUNT = BLOCK_COUNT / Long.SIZE;

  private static final VarHandle WORDS = MethodHandles.arrayElementVarHandle(long[].class);

  private final long[] words;

  /** 新建全零位图（全不遮挡）。 */
  public BitmapOcclusion() {
    this.words = new long[WORD_COUNT];
  }

  /**
   * 由既有 long 数组<b>复制</b>构造（按下标取用，长度为 {@link #WORD_COUNT}；不足处补零，超出部分丢弃）。
   *
   * <p>构造后本对象持有的是<b>副本</b>，外部数组的后续改动不影响本实例。
   *
   * <p><b>为什么是静态工厂而不是构造器</b>：本类还需要一个「接管数组、不复制」的私有构造器
   * （见 {@link #BitmapOcclusion(long[])}），两者签名都是 {@code (long[])}、无法共存；把复制版做成工厂
   * 恰好让「接管」这个更危险的语义留在私有侧，外部调用方不可能误用不复制版本。
   *
   * <p>解码路径（{@code OcclusionPayload.decode}）必须用它：解码缓冲是逐段复用的，不复制会让相邻
   * section 共享同一份数组。
   */
  public static BitmapOcclusion copyOf(long[] source) {
    long[] copy = new long[WORD_COUNT];
    System.arraycopy(source, 0, copy, 0, Math.min(source.length, WORD_COUNT));
    return new BitmapOcclusion(copy);
  }

  /**
   * 直接接管 {@code words}（<b>不复制</b>）：调用方必须保证该数组此后不再被外部持有或修改。
   *
   * <p>只在类内部使用（{@link #solid()} / {@link #fromStateLookup} 传入的都是刚分配、尚未外泄的数组）。
   */
  private BitmapOcclusion(long[] words) {
    this.words = words;
  }

  /** 全遮挡位图（全 1）。一般不用它——全遮挡请直接用 {@link SolidOcclusion#INSTANCE}。 */
  public static BitmapOcclusion solid() {
    long[] filled = new long[WORD_COUNT];
    Arrays.fill(filled, -1L);
    return new BitmapOcclusion(filled);
  }

  /**
   * 由「逐格状态 + 遮挡判定谓词」构建 section 遮挡数据（建表入口）。
   *
   * <p>输出使用两种快路径：
   * <ul>
   *   <li>没有任何一格遮挡 → {@link EmptyOcclusion#INSTANCE}（共享「已知全不遮挡」，省下位图）；</li>
   *   <li>每一格都遮挡 → {@link SolidOcclusion#INSTANCE}；</li>
   *   <li>其余 → 逐格 {@link BitmapOcclusion}。</li>
   * </ul>
   *
   * <p>{@code states} 的长度须不小于 {@link #BLOCK_COUNT}；下标语义与
   * {@code codec.ChunkSection} 一致（{@code y<<8|z<<4|x}）。谓词返回 true 表示该状态遮挡。
   *
   * @param states       逐格方块状态 id（长度 ≥ 4096）
   * @param isOccluding  状态 id → 是否遮挡
   * @return {@link EmptyOcclusion#INSTANCE}（全不遮挡）/ {@link SolidOcclusion#INSTANCE}（全遮挡）
   *         / {@link BitmapOcclusion}（逐格位图）——永不为 {@code null}
   */
  public static SectionOcclusion fromStates(int[] states, IntPredicate isOccluding) {
    return fromStateLookup(packed -> states[packed], isOccluding);
  }

  /**
   * 由「按位下标的方块状态查询 + 遮挡谓词」构建 section 遮挡数据（建表热路径入口）。
   *
   * <p>与 {@link #fromStates} 结论完全一致，但<b>不要求调用方先物化一个 {@code int[4096]}</b>：
   * 建表是在反矿透解码的热路径上做的（每个区块 24 段 × 4096 格），物化数组会额外产生一份
   * 每段 16KB 的短命分配。这里改用「按下标取状态」的回调，逐格直接判位。
   *
   * @param stateAtIndex 位下标（{@code y<<8|z<<4|x}）→ 方块状态 id
   * @param isOccluding  状态 id → 是否遮挡
   * @return {@link EmptyOcclusion#INSTANCE} / {@link SolidOcclusion#INSTANCE} / {@link BitmapOcclusion}
   *         ——永不为 {@code null}
   */
  public static SectionOcclusion fromStateLookup(IntUnaryOperator stateAtIndex,
      IntPredicate isOccluding) {
    long[] words = new long[WORD_COUNT];
    int occludingCount = 0;
    for (int packed = 0; packed < BLOCK_COUNT; packed++) {
      if (!isOccluding.test(stateAtIndex.applyAsInt(packed))) {
        continue;
      }
      words[packed >>> 6] |= 1L << packed;
      occludingCount++;
    }
    if (occludingCount == 0) {
      // 「已知全不遮挡」用共享空快路径表示，而非 null（null 表示「没有该段的数据」= 未知）
      return EmptyOcclusion.INSTANCE;
    }
    if (occludingCount == BLOCK_COUNT) {
      return SolidOcclusion.INSTANCE;
    }
    return new BitmapOcclusion(words);
  }

  @Override
  public boolean isOccludingLocal(int x, int y, int z) {
    int packed = SectionOcclusion.packLocal(x, y, z);
    long word = (long) WORDS.getOpaque(words, packed >>> 6);
    return (word & (1L << packed)) != 0;
  }

  /**
   * 就地把某个局部坐标的遮挡位设为指定值。
   *
   * <p>局部坐标必须已在 {@code [0,15]}；越界坐标会被掩码到同 section 的其它格子（调用方负责先算
   * 好「所在 section」）。单写多读场景下，本方法必须由唯一写者调用。
   */
  public void setOccluding(int x, int y, int z, boolean occluding) {
    int packed = SectionOcclusion.packLocal(x, y, z);
    int wordIndex = packed >>> 6;
    long mask = 1L << packed;
    long word = (long) WORDS.getOpaque(words, wordIndex);
    long updated = occluding ? word | mask : word & ~mask;
    WORDS.setOpaque(words, wordIndex, updated);
  }

  /** 是否没有任何一格遮挡（全零）。 */
  public boolean isEmpty() {
    for (int i = 0; i < WORD_COUNT; i++) {
      if (words[i] != 0L) {
        return false;
      }
    }
    return true;
  }

  /**
   * 内部位数组<b>只读</b>视图；供 {@link OcclusionPayload} 编码使用。
   *
   * <p>包级可见且不复制（编码是逐区块一次性动作，复制 512 字节没必要）；调用方<b>绝不可修改</b>返回数组。
   */
  long[] rawWords() {
    return words;
  }

  @Override
  public String toString() {
    return "BitmapOcclusion";
  }
}
