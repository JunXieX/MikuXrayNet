package net.mikumc.mikuxraynet.bandwidth;

import java.util.ArrayList;
import java.util.List;

/**
 * 体素遮挡射线（固定步长 / 3D-DDA 步进，纯计算实现）。
 *
 * <p>本类只做数学运算：输入射线的起终点（世界坐标），输出射线途经的体素坐标序列。
 * 由工作线程负责「算序列」，主线程/区域线程负责「读方块判定是否遮挡」，从而保证
 * worker 不触碰任何 Bukkit API（Folia 兼容）。
 *
 * <p>采样采用等距步进并对最大采样数设上限：宁可少判遮挡（实体照常显示），也不漏放，
 * 避免误隐藏造成视觉异常。
 *
 * <p>坐标序列按 3 个 int 一组（x, y, z）扁平存放，便于跨线程传递与回收。
 */
public final class OcclusionRaytracer {

  /** 单个小体积实体（如物品、投掷物）只取中心点即可。 */
  private static final double SMALL_BOX = 0.25D;

  private OcclusionRaytracer() {
  }

  /**
   * 计算从眼睛到目标点的射线途经体素（不含终点所在体素）。
   *
   * @param maxSamples 最多采样数，用于限制主线程读取方块的次数
   * @return 扁平坐标数组（x,y,z 依次排列）；长度可能为 0，表示相邻体素或距离过近
   */
  public static int[] voxelPath(double fromX, double fromY, double fromZ,
      double toX, double toY, double toZ, int maxSamples) {
    double deltaX = toX - fromX;
    double deltaY = toY - fromY;
    double deltaZ = toZ - fromZ;
    double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    if (length < 1.0E-6D) {
      return new int[0];
    }

    int samples = (int) Math.min(Math.max(1, maxSamples), Math.max(1.0D, Math.floor(length)));
    if (samples < 2) {
      return new int[0];
    }

    int[] path = new int[samples * 3];
    int count = 0;
    int lastX = Integer.MIN_VALUE;
    int lastY = Integer.MIN_VALUE;
    int lastZ = Integer.MIN_VALUE;

    // 从 1 到 samples-1：跳过起点所在体素与终点（实体自身）所在体素
    for (int step = 1; step < samples; step++) {
      double ratio = (double) step / (double) samples;
      int x = floor(fromX + deltaX * ratio);
      int y = floor(fromY + deltaY * ratio);
      int z = floor(fromZ + deltaZ * ratio);
      if (x == lastX && y == lastY && z == lastZ) {
        continue;
      }
      lastX = x;
      lastY = y;
      lastZ = z;
      path[count * 3] = x;
      path[count * 3 + 1] = y;
      path[count * 3 + 2] = z;
      count++;
    }

    if (count * 3 == path.length) {
      return path;
    }
    int[] trimmed = new int[count * 3];
    System.arraycopy(path, 0, trimmed, 0, trimmed.length);
    return trimmed;
  }

  /**
   * 取实体包围盒上「朝向玩家一侧」的可见顶点，用于多射线判定（任一射线不被遮挡即视为可见）。
   *
   * @return 顶点数组，每项为 (x, y, z)
   */
  public static double[][] visibleVertices(double[] eye,
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    double sizeX = maxX - minX;
    double sizeY = maxY - minY;
    double sizeZ = maxZ - minZ;
    if (sizeX <= SMALL_BOX && sizeY <= SMALL_BOX && sizeZ <= SMALL_BOX) {
      return new double[][] {{(minX + maxX) / 2.0D, (minY + maxY) / 2.0D, (minZ + maxZ) / 2.0D}};
    }

    boolean nearestXIsMin = Math.abs(eye[0] - minX) < Math.abs(eye[0] - maxX);
    boolean nearestYIsMin = Math.abs(eye[1] - minY) < Math.abs(eye[1] - maxY);
    boolean nearestZIsMin = Math.abs(eye[2] - minZ) < Math.abs(eye[2] - maxZ);

    double nearX = nearestXIsMin ? minX : maxX;
    double nearY = nearestYIsMin ? minY : maxY;
    double nearZ = nearestZIsMin ? minZ : maxZ;
    double farX = nearestXIsMin ? maxX : minX;
    double farY = nearestYIsMin ? maxY : minY;
    double farZ = nearestZIsMin ? maxZ : minZ;

    return new double[][] {
        {nearX, nearY, nearZ},
        {farX, nearY, nearZ},
        {nearX, farY, nearZ},
        {nearX, nearY, farZ},
        {farX, farY, nearZ},
        {farX, nearY, farZ},
        {nearX, farY, farZ}
    };
  }

  /** 对多个顶点分别求体素路径。 */
  public static List<int[]> traceAll(double[] eye, double[][] vertices, int maxSamples) {
    List<int[]> paths = new ArrayList<>(vertices.length);
    for (double[] vertex : vertices) {
      paths.add(voxelPath(eye[0], eye[1], eye[2], vertex[0], vertex[1], vertex[2], maxSamples));
    }
    return paths;
  }

  private static int floor(double value) {
    int truncated = (int) value;
    return value < truncated ? truncated - 1 : truncated;
  }
}