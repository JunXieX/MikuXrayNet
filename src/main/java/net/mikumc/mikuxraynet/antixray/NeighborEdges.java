package net.mikumc.mikuxraynet.antixray;

/**
 * 某区块四个水平邻块「贴边一层」的遮挡快照：每个平面只有 1 格厚（16×世界高度），不做整块复制。
 *
 * <p>每个平面按「区块内相对 Y（0=世界最低建筑高度）」与「另一水平轴（0..15）」索引，
 * <b>按位存储</b>：下标 {@code index = y << 4 | localOther} 对应位 {@code index}，
 * 位为 {@code 1} 表示遮挡、{@code 0} 表示不遮挡；平面为 {@code null} 表示该侧邻块未抓到
 * （未加载、跨区域、被清理等）。
 *
 * <p><b>为什么按位</b>：快照条目数（{@code neighbors.cache-maximum-size}）可达数千，
 * 而每格只需 1 bit；早期按字节存时单条主世界快照（384 高度）约 24 KB、2048 条约 48 MB，
 * 按位打包后约 3 KB、2048 条约 6 MB——容器与访问器语义完全等价，只是省掉 8 倍内存。
 *
 * <p>本类只承载纯数据数组，构造后不再修改，可安全地在工作线程读取（不持有任何 World/Chunk 引用）。
 */
public final class NeighborEdges {

  /** 请求方看待邻块的方向。 */
  public enum Side {
    X_MINUS, X_PLUS, Z_MINUS, Z_PLUS
  }

  /** 平面缺失：查询结果不可用，由调用方按缺失策略处理。 */
  public static final int MISSING = -1;

  private final int height;
  private final long[] xMinus;
  private final long[] xPlus;
  private final long[] zMinus;
  private final long[] zPlus;
  /** 内容指纹（构造期算一次）：供调用方把「本次改写实际用到的快照内容」并入缓存键。 */
  private final long contentFingerprint;

  public NeighborEdges(int height, long[] xMinus, long[] xPlus, long[] zMinus, long[] zPlus) {
    this.height = height;
    this.xMinus = xMinus;
    this.xPlus = xPlus;
    this.zMinus = zMinus;
    this.zPlus = zPlus;
    this.contentFingerprint = fingerprint(height, xMinus, xPlus, zMinus, zPlus);
  }

  /**
   * 快照内容指纹。
   *
   * <p><b>为什么需要它</b>：{@code mode=enclosed} 的改写结果依赖邻块贴边快照的遮挡位，
   * 而快照内容会随邻块方块变化。若缓存键只看「本区块原始字节」，邻块变化后本区块字节未变，
   * 缓存就会继续返回按<b>旧邻块</b>算出的结果。调用方把本值并入缓存键即可让这类条目自然失效。
   *
   * <p>构造期计算一次（本类字段全部 final、构造后不可变，故可在工作线程安全读取）；数据量为
   * 「4 个平面 × height×16 位」（主世界约 384 个 long），开销可忽略。缺失的平面（{@code null}）
   * 以独立哨兵参与，保证「缺失」与「非空平面」不会算出同一指纹。
   */
  public long contentFingerprint() {
    return contentFingerprint;
  }

  /** FNV-1a 64：对高度 + 各平面内容（含 null 标记）做一次性摘要。 */
  private static long fingerprint(int height, long[]... planes) {
    long hash = 0xcbf29ce484222325L ^ height;
    for (long[] plane : planes) {
      // 先混入「平面是否存在/长度」：缺失平面与长度为 0 的平面必须与「有内容」区分开
      hash = (hash ^ (plane == null ? 0x9E3779B97F4A7C15L : plane.length)) * 0x100000001b3L;
      if (plane != null) {
        for (long value : plane) {
          hash = (hash ^ value) * 0x100000001b3L;
        }
      }
    }
    return hash;
  }

  /** 一个平面的位数（{@code height × 16}）。 */
  public static int planeBitCount(int height) {
    return height << 4;
  }

  /** 一个平面需要的 {@code long} 数（位打包后的实际数组长度）。 */
  public static int planeLongCount(int height) {
    return (planeBitCount(height) + 63) >>> 6;
  }

  /** 把平面内 {@code index} 处记为「遮挡」（位打包写入）。 */
  public static void setOccluding(long[] plane, int index) {
    plane[index >>> 6] |= 1L << (index & 63);
  }

  /** 读取平面内 {@code index} 处是否为「遮挡」（位打包读取）。 */
  public static boolean isOccludingAt(long[] plane, int index) {
    return ((plane[index >>> 6] >>> (index & 63)) & 1L) != 0L;
  }

  /**
   * 快照覆盖的区块内高度（section 数 × 16）。
   *
   * <p>测试专用豁免：生产查询走 {@link #occluding}（内部已含高度越界判定），
   * 本方法当前仅单测与基准路径在用，保留以免破坏测试。
   */
  public int height() {
    return height;
  }

  /**
   * 该侧平面是否可用。
   *
   * <p>测试专用豁免：生产查询直接调 {@link #occluding}（平面缺失返回 {@link #MISSING}），
   * 本方法当前仅单测与基准路径在用，保留以免破坏测试。
   */
  public boolean has(Side side) {
    return plane(side) != null;
  }

  /**
   * 查询某侧贴边层的遮挡状态。
   *
   * @param y          区块内相对 Y（0 = 世界最低建筑高度）
   * @param localOther 另一水平轴的区块内相对坐标（0..15）
   * @return {@code 1} 遮挡 / {@code 0} 不遮挡 / {@link #MISSING} 平面缺失或坐标越界
   */
  public int occluding(Side side, int y, int localOther) {
    if (y < 0 || y >= height || localOther < 0 || localOther > 15) {
      return MISSING;
    }
    long[] plane = plane(side);
    if (plane == null) {
      return MISSING;
    }
    return isOccludingAt(plane, y << 4 | localOther) ? 1 : 0;
  }

  private long[] plane(Side side) {
    return switch (side) {
      case X_MINUS -> xMinus;
      case X_PLUS -> xPlus;
      case Z_MINUS -> zMinus;
      case Z_PLUS -> zPlus;
    };
  }
}