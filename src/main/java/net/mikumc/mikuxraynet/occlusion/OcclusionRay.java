package net.mikumc.mikuxraynet.occlusion;

/**
 * 纯内存体素遍历射线（3D DDA / Amanatides–Woo）：沿一条线段逐格穿过它真正经过的方块，
 * 统计途中「遮挡」的方块数，据此判定是否被完全挡住。
 *
 * <p><b>为什么必须是 DDA 而不是定长步进</b>:定长步进（每步固定 1 格）会斜穿角落时<b>跳过</b>方块，
 * 漏掉的那一格若正好是墙，射线就会被判成「通畅」——调用方据此把墙后的伪装矿还原，等于隔墙透视。
 * DDA 按「下一个轴向格边界」推进，路径经过的每一格都会恰好访问一次，不漏也不重。
 *
 * <p><b>判定语义</b>：{@code maxOccluding} 是「允许穿过的最大遮挡方块数」。途中遮挡数超过它即判
 * 「不通畅」（{@code false}）；否则判「通畅」（{@code true}）。取 {@code maxOccluding = 0} 即
 * 「路径上不能有任何遮挡方块」——最严格，是本项目接线时的默认取值。起点所在格与终点所在格都不参与
 * 判定（起点是观察者、终点是目标方块本体）。
 *
 * <p><b>数据来源</b>：{@link Lookup}（生产由 {@link OcclusionStore} 实现）。查不到 section
 * （返回 {@code null}）按「该处不遮挡」处理，与「整段全不遮挡」等价；显形侧由调用方包成
 * 「缺数据 = 遮挡」的严格视图（见 {@code BitmapVisibility#strictSection}）。
 *
 * <p><b>线程与分配</b>：内部游标为实例字段，因此<b>实例不可跨线程共享</b>；请按工作线程各持一份
 * 复用（热路径零分配）。{@link #lineOfSight} 是测试/低频调用用的便捷入口，每次都会新建实例。
 */
public final class OcclusionRay {

  /** 遮挡数据来源：由「section 坐标」取得该段的遮挡视图；查不到返回 {@code null}。 */
  @FunctionalInterface
  public interface Lookup {
    SectionOcclusion section(int chunkX, int sectionY, int chunkZ);
  }

  private Lookup lookup;
  /** 当前缓存的 section 坐标与其视图（{@code resolved} 为 false 表示尚未查询）。 */
  private int viewChunkX;
  private int viewSectionY;
  private int viewChunkZ;
  private boolean viewResolved;
  private SectionOcclusion view;
  private int occluded;

  /**
   * 判定从起点到终点是否通畅（能看到）。
   *
   * @param lookup                   遮挡数据来源
   * @param startX/Y/Z               起点（观察者眼睛）
   * @param endX/Y/Z                 终点（目标点）
   * @param maxOccluding             允许穿过的最大遮挡方块数（0 = 最严格）
   * @param alwaysVisibleRadiusSquared 「恒可见」半径的平方：起点到终点距离不超过它时直接判通畅
   * @param maxRadiusSquared         最远射线距离的平方：超过它直接判不通畅（不遍历）
   * @return true = 通畅（未被完全遮挡）
   */
  public boolean hasLineOfSight(Lookup lookup, double startX, double startY, double startZ,
      double endX, double endY, double endZ, int maxOccluding,
      double alwaysVisibleRadiusSquared, double maxRadiusSquared) {
    double dx = endX - startX;
    double dy = endY - startY;
    double dz = endZ - startZ;
    double lengthSquared = dx * dx + dy * dy + dz * dz;
    if (lengthSquared <= alwaysVisibleRadiusSquared) {
      return true;
    }
    if (lengthSquared > maxRadiusSquared) {
      return false;
    }
    double length = Math.sqrt(lengthSquared);
    if (length <= 1.0E-12D) {
      return true;
    }

    this.lookup = lookup;
    this.view = null;
    this.viewResolved = false;
    this.occluded = 0;

    double dirX = dx / length;
    double dirY = dy / length;
    double dirZ = dz / length;

    int x = (int) Math.floor(startX);
    int y = (int) Math.floor(startY);
    int z = (int) Math.floor(startZ);
    int endBlockX = (int) Math.floor(endX);
    int endBlockY = (int) Math.floor(endY);
    int endBlockZ = (int) Math.floor(endZ);
    if (x == endBlockX && y == endBlockY && z == endBlockZ) {
      return true;
    }

    int stepX = dirX > 0 ? 1 : (dirX < 0 ? -1 : 0);
    int stepY = dirY > 0 ? 1 : (dirY < 0 ? -1 : 0);
    int stepZ = dirZ > 0 ? 1 : (dirZ < 0 ? -1 : 0);
    double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dirX);
    double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dirY);
    double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dirZ);
    double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY
        : boundaryDistance(startX, x, stepX) / Math.abs(dirX);
    double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY
        : boundaryDistance(startY, y, stepY) / Math.abs(dirY);
    double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
        : boundaryDistance(startZ, z, stepZ) / Math.abs(dirZ);

    // 上界：三个轴各自要跨越的格数之和再加少量余量；正常情况下循环会先到达终点格。
    int guard = Math.abs(endBlockX - x) + Math.abs(endBlockY - y) + Math.abs(endBlockZ - z) + 3;
    while (guard-- > 0) {
      if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
        x += stepX;
        tMaxX += tDeltaX;
      } else if (tMaxY <= tMaxZ) {
        y += stepY;
        tMaxY += tDeltaY;
      } else {
        z += stepZ;
        tMaxZ += tDeltaZ;
      }
      if (x == endBlockX && y == endBlockY && z == endBlockZ) {
        break;
      }
      SectionOcclusion section = sectionAt(x, y, z);
      if (section != null && section.isOccluding(x, y, z)) {
        occluded++;
        if (occluded > maxOccluding) {
          return false;
        }
      }
    }
    return occluded <= maxOccluding;
  }

  /** 便捷入口（每次新建实例）：适合测试与低频调用；热路径请复用实例。 */
  public static boolean lineOfSight(Lookup lookup, double startX, double startY, double startZ,
      double endX, double endY, double endZ, int maxOccluding,
      double alwaysVisibleRadiusSquared, double maxRadiusSquared) {
    return new OcclusionRay().hasLineOfSight(lookup, startX, startY, startZ, endX, endY, endZ,
        maxOccluding, alwaysVisibleRadiusSquared, maxRadiusSquared);
  }

  /** 当前方块所在 section 的视图（按 section 坐标缓存，避免逐格调用 {@link Lookup}）。 */
  private SectionOcclusion sectionAt(int blockX, int blockY, int blockZ) {
    int chunkX = blockX >> 4;
    int sectionY = blockY >> 4;
    int chunkZ = blockZ >> 4;
    if (!viewResolved || chunkX != viewChunkX || sectionY != viewSectionY || chunkZ != viewChunkZ) {
      viewChunkX = chunkX;
      viewSectionY = sectionY;
      viewChunkZ = chunkZ;
      viewResolved = true;
      view = lookup == null ? null : lookup.section(chunkX, sectionY, chunkZ);
    }
    return view;
  }

  /** 从 {@code start} 沿 {@code step} 方向到所在格边界的距离（0 < 结果 ≤ 1）。 */
  private static double boundaryDistance(double start, int cell, int step) {
    return step > 0 ? (cell + 1.0D - start) : (start - cell);
  }
}
