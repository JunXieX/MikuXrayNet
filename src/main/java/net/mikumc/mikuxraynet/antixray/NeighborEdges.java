package net.mikumc.mikuxraynet.antixray;

/**
 * 某区块四个水平邻块「贴边一层」的遮挡快照：每个平面只有 1 格厚（16×世界高度），不做整块复制。
 *
 * <p>每个平面按「区块内相对 Y（0=世界最低建筑高度）」与「另一水平轴（0..15）」索引，字节含义
 * {@code 1=遮挡、0=不遮挡}；平面为 {@code null} 表示该侧邻块未抓到（未加载、跨区域、被清理等）。
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
  private final byte[] xMinus;
  private final byte[] xPlus;
  private final byte[] zMinus;
  private final byte[] zPlus;

  public NeighborEdges(int height, byte[] xMinus, byte[] xPlus, byte[] zMinus, byte[] zPlus) {
    this.height = height;
    this.xMinus = xMinus;
    this.xPlus = xPlus;
    this.zMinus = zMinus;
    this.zPlus = zPlus;
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
    byte[] plane = plane(side);
    if (plane == null) {
      return MISSING;
    }
    return plane[y << 4 | localOther] == 0 ? 0 : 1;
  }

  private byte[] plane(Side side) {
    return switch (side) {
      case X_MINUS -> xMinus;
      case X_PLUS -> xPlus;
      case Z_MINUS -> zMinus;
      case Z_PLUS -> zPlus;
    };
  }
}