package net.mikumc.mikuxraynet.occlusion;

/**
 * 区块 section 世界坐标的打包键。
 *
 * <p><b>为什么打包成一个 long</b>：遮挡数据以「一个方块 section（16×16×16）」为最小单位存放，
 * 而射线遍历需要在每跨过一个 section 时查一次表。用「三元素对象做键」会让每次查询都产生一次
 * 装箱/分配；把 (chunkX, sectionY, chunkZ) 压进一个 {@code long} 后，查询是纯基本类型的哈希，
 * 热路径零分配。
 *
 * <p><b>位布局（共 64 位，必须与单测锁定）</b>：
 * <pre>
 *   [63..36] chunkX  —— 28 位有符号（水平坐标，±1.34 亿区块）
 *   [35..28] sectionY —— 8 位有符号（section 高度，-128..127，覆盖 Y = -2048..2047）
 *   [27..0]  chunkZ  —— 28 位有符号（水平坐标）
 * </pre>
 * 取 sectionY 而不是绝对 Y，是因为本模块的存储单位就是 section；绝对 Y 在查询时除以 16 得到
 * section 序号，负数由算术右移天然处理（{@code -1 >> 4 == -1}）。
 *
 * <p><b>越界语义</b>：水平坐标或 section 高度超出可表示范围时抛 {@link IllegalArgumentException}。
 * 服务端世界水平坐标远超 28 位范围（±1.34 亿区块 = ±21 亿方块），实际不可能触界；这里显式拒绝
 * 而非静默回绕，避免把两个不同 section 映射到同一个键（静默错配遮挡数据）。
 * 调用方（射线/建表）对异常一律按 fail-open 处理。
 *
 * <p><b>无状态、线程安全</b>：全为静态纯函数。
 */
public final class SectionKey {

  /** chunkX / chunkZ 占用的位数。 */
  public static final int HORIZONTAL_BITS = 28;
  /** sectionY 占用的位数。 */
  public static final int SECTION_Y_BITS = 8;

  /** 水平坐标可表示范围（28 位有符号）。 */
  public static final int HORIZONTAL_MIN = -(1 << (HORIZONTAL_BITS - 1));
  public static final int HORIZONTAL_MAX = (1 << (HORIZONTAL_BITS - 1)) - 1;

  /** section 高度可表示范围（8 位有符号）。 */
  public static final int SECTION_Y_MIN = -(1 << (SECTION_Y_BITS - 1));
  public static final int SECTION_Y_MAX = (1 << (SECTION_Y_BITS - 1)) - 1;

  /** 一个水平列内的 section 总数（8 位全部取值）。 */
  public static final int SECTION_Y_COUNT = 1 << SECTION_Y_BITS;

  /**
   * 同一水平列内相邻 sectionY 的键增量（= {@code 1 << HORIZONTAL_BITS}）。
   *
   * <p>用于 {@link #columnKeys(int, int)} 逐 section 摘除整列：键的 sectionY 字段恰好是「低位段
   * 上方的第一个字段」，因此对键加 {@link #SECTION_Y_INCREMENT} 就等于 sectionY + 1
   * （水平字段不受进位影响，因为在范围内 sectionY 不会溢出）。不是通用方案，仅对本位布局成立。
   */
  public static final long SECTION_Y_INCREMENT = 1L << HORIZONTAL_BITS;

  private static final long HORIZONTAL_MASK = (1L << HORIZONTAL_BITS) - 1L;
  private static final long SECTION_Y_MASK = (1L << SECTION_Y_BITS) - 1L;

  private SectionKey() {
  }

  /**
   * 打包一个 section 键。
   *
   * @param chunkX   区块 X（水平，28 位有符号）
   * @param sectionY section 高度（8 位有符号）
   * @param chunkZ   区块 Z（水平，28 位有符号）
   * @throws IllegalArgumentException 任一坐标超出可表示范围
   */
  public static long packSection(int chunkX, int sectionY, int chunkZ) {
    checkHorizontal(chunkX, "chunkX");
    checkSectionY(sectionY);
    checkHorizontal(chunkZ, "chunkZ");
    return packUnchecked(chunkX, sectionY, chunkZ);
  }

  /**
   * 由「方块世界坐标」打包其所属 section 的键（{@code x>>4, y>>4, z>>4}）。
   *
   * <p><b>越界不抛异常</b>：越界坐标按位掩码截断后仍得到某个键。因此它只适合<b>坐标必然合法</b>
   * 且失败可容忍的场景；写入路径（方块补丁）必须改用 {@link #packSection}，否则越界坐标会被
   * 静默写到另一个 section 上。射线遍历不经过本方法（它直接以 {@code >>4} 得到的三个分量查表）。
   */
  public static long packBlock(int x, int y, int z) {
    return packUnchecked(x >> 4, y >> 4, z >> 4);
  }

  /** 不做范围校验的打包（越界坐标按位截断）；{@link #packSection} 与 {@link #packBlock} 共用。 */
  public static long packUnchecked(int chunkX, int sectionY, int chunkZ) {
    return ((long) chunkX & HORIZONTAL_MASK) << (SECTION_Y_BITS + HORIZONTAL_BITS)
        | ((long) sectionY & SECTION_Y_MASK) << HORIZONTAL_BITS
        | ((long) chunkZ & HORIZONTAL_MASK);
  }

  /** 从键取出区块 X（符号扩展 28 位）。 */
  public static int chunkX(long key) {
    return (int) (key >> (SECTION_Y_BITS + HORIZONTAL_BITS));
  }

  /** 从键取出 section 高度（符号扩展 8 位）。 */
  public static int sectionY(long key) {
    return (int) (key << HORIZONTAL_BITS >> (HORIZONTAL_BITS + HORIZONTAL_BITS));
  }

  /** 从键取出区块 Z（符号扩展 28 位）。 */
  public static int chunkZ(long key) {
    return (int) (key << (Long.SIZE - HORIZONTAL_BITS) >> (Long.SIZE - HORIZONTAL_BITS));
  }

  /**
   * 每线程复用的列键缓冲（{@link #SECTION_Y_COUNT} 个 long，共 2 KB）。
   *
   * <p><b>为什么复用而不是每次分配</b>：{@link #columnKeys} 的唯一生产调用方是
   * {@code OcclusionStore#removeColumn}（区块卸载，跑在主线程 / Folia 区域线程上）；玩家高速移动时
   * 每秒可达数十次卸载，每次 {@code new long[256]} 就是 2 KB 纯可避免的短命分配。缓冲内容每次都会被
   * 完整覆盖（循环写满 256 个槽位），因此复用不会残留上一次调用的旧值。
   *
   * <p><b>为什么必须是线程本地</b>：卸载可能在主线程与多个区域线程上并发发生，共享一个缓冲会互相覆盖；
   * 反过来，一个线程的缓冲也<b>不得</b>交给另一个线程使用（见 {@link #columnKeys} 的返回约定）。
   * 2 KB/线程 的常驻成本可忽略，故不需要额外的清理入口（对比 {@code ChunkScratch} 的 MB 级缓冲才需要）。
   */
  private static final ThreadLocal<long[]> COLUMN_KEYS =
      ThreadLocal.withInitial(() -> new long[SECTION_Y_COUNT]);

  /**
   * 生成某水平列上全部 section 的键，用于整列摘除（区块卸载）。
   *
   * <p><b>为什么从 sectionY = 0 起步而不是 {@link #SECTION_Y_MIN}</b>：键的 sectionY 字段只有 8 位，
   * 若从 -128（位模式 0x80）起步并逐 +1，走到 -1（0xFF）再 +1 会进位溢出到 chunkX 字段，导致后半段
   * 的键 chunkX 被改错、整列删不干净。从 0 起步则 256 次递增恰好覆盖 0..127 与 -128..-1 两组值，
   * 最后一次递增的进位发生在循环之后，不产生错误键。
   *
   * <p>因此返回的数组<b>顺序是 0,1,…,127,-128,…,−1</b>（不是按 sectionY 数值升序），
   * 元素本身互不相同、且并集恰好是全部 256 个可表示 sectionY。
   *
   * @implNote 返回的是<b>当前线程复用的缓冲</b>（不是新数组）：下一次在本线程调用本方法会覆盖其内容，
   *     因此调用方必须<b>立即消费</b>、不得保存引用或跨线程传递。当前唯一生产调用方在方法体内同步消费，
   *     单测若需保留旧结果请先 {@code clone()}。
   */
  public static long[] columnKeys(int chunkX, int chunkZ) {
    checkHorizontal(chunkX, "chunkX");
    checkHorizontal(chunkZ, "chunkZ");
    long[] keys = COLUMN_KEYS.get();
    long key = packUnchecked(chunkX, 0, chunkZ);
    for (int i = 0; i < SECTION_Y_COUNT; i++) {
      keys[i] = key;
      key += SECTION_Y_INCREMENT;
    }
    return keys;
  }

  private static void checkHorizontal(int value, String name) {
    if (value < HORIZONTAL_MIN || value > HORIZONTAL_MAX) {
      throw new IllegalArgumentException(
          name + " 超出可表示范围 [" + HORIZONTAL_MIN + ", " + HORIZONTAL_MAX + "]，实际为 " + value);
    }
  }

  private static void checkSectionY(int sectionY) {
    if (sectionY < SECTION_Y_MIN || sectionY > SECTION_Y_MAX) {
      throw new IllegalArgumentException(
          "sectionY 超出可表示范围 [" + SECTION_Y_MIN + ", " + SECTION_Y_MAX + "]，实际为 " + sectionY);
    }
  }
}
