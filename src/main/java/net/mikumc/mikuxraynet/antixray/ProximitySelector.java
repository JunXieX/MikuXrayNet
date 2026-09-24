package net.mikumc.mikuxraynet.antixray;

import net.mikumc.mikuxraynet.bandwidth.OcclusionRaytracer;

/**
 * 邻近显形的「选择逻辑」：视锥剔除 + 射线可见性判定的纯计算部分。
 *
 * <p><b>为什么要拆出来</b>：这两项判定都必须在工作线程完成数学计算、在主线程 / Folia 区域线程读取方块，
 * 而「读方块」需要 Bukkit 世界对象，无法离线单测。因此这里只保留可离线验证的纯函数：
 * 视锥角度计算、射线体素路径，以及「给定遮挡查询时路径是否被挡住」。
 *
 * <p><b>视锥</b>：以玩家视线方向为轴、{@code fovDegrees} 为全张开角的圆锥（半角 = fov/2）。
 * 方块中心与视线的夹角不超过半角即在锥内；距离不超过 {@code minDistance} 时豁免视锥判定
 * （贴脸/脚下的方块即便在视野边缘也必须显形，否则会出现「贴着走过的矿物不显形」）。
 *
 * <p><b>射线</b>：从眼睛到目标方块中心做体素步进（复用 {@link OcclusionRaytracer}），
 * 路径上任一被遮挡的体素都视为「看不见」，此时不显形（留给玩家靠近后再显形）。
 * 采样数有限，宁可少判遮挡（多显形）也不漏放。
 */
public final class ProximitySelector {

  /** 玩家眼睛与视线方向的不可变快照（只含基本类型，不持有 Player / Location 引用）。 */
  public record Eye(double x, double y, double z, double dirX, double dirY, double dirZ) {
  }

  /** 世界坐标遮挡查询（显形流程用主线程读世界方块，单测可注入纯内存实现）。 */
  @FunctionalInterface
  public interface RayQuery {
    boolean isOccluding(int x, int y, int z);
  }

  private ProximitySelector() {
  }

  /** 构造眼睛快照；方向向量会被归一化，零向量按「朝向 +Z」处理。 */
  public static Eye eye(double x, double y, double z, double dirX, double dirY, double dirZ) {
    double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
    if (length < 1.0E-6D) {
      return new Eye(x, y, z, 0.0D, 0.0D, 1.0D);
    }
    return new Eye(x, y, z, dirX / length, dirY / length, dirZ / length);
  }

  /**
   * 是否在视野锥内（或在最小距离内豁免）。
   *
   * @param eye                  眼睛与视线方向
   * @param blockX               目标方块 X（取方块中心参与计算）
   * @param blockY               目标方块 Y
   * @param blockZ               目标方块 Z
   * @param minDistance          不超过该距离时直接豁免视锥判定
   * @param fovDegrees           视野锥全张开角（度）；{@code <=0} 或 {@code >=180} 时视为不做视锥剔除
   * @return true 表示应当继续参与显形流程
   */
  public static boolean withinFrustum(Eye eye, int blockX, int blockY, int blockZ,
      double minDistance, double fovDegrees) {
    if (eye == null) {
      return true;
    }

    double dx = blockX + 0.5D - eye.x();
    double dy = blockY + 0.5D - eye.y();
    double dz = blockZ + 0.5D - eye.z();

    double distanceSquared = dx * dx + dy * dy + dz * dz;
    double exemption = Math.max(0.0D, minDistance);
    if (distanceSquared <= exemption * exemption) {
      return true;
    }
    if (fovDegrees <= 0.0D || fovDegrees >= 180.0D) {
      return true;
    }

    double distance = Math.sqrt(distanceSquared);
    if (distance < 1.0E-6D) {
      return true;
    }

    // 夹角余弦 = (视线 · 到方块的方向) / 距离；视线已归一化
    double cosine = (dx * eye.dirX() + dy * eye.dirY() + dz * eye.dirZ()) / distance;
    return cosine >= Math.cos(Math.toRadians(fovDegrees / 2.0D));
  }

  /**
   * 从眼睛到目标方块中心的射线体素路径（不含起点与终点所在体素）。
   *
   * @param maxSamples 最多采样数（越小越省主线程读方块次数，但可能漏判薄墙）
   */
  public static int[] rayPath(Eye eye, int blockX, int blockY, int blockZ, int maxSamples) {
    if (eye == null) {
      return new int[0];
    }
    return OcclusionRaytracer.voxelPath(eye.x(), eye.y(), eye.z(),
        blockX + 0.5D, blockY + 0.5D, blockZ + 0.5D, maxSamples);
  }

  /**
   * 给定遮挡查询，判定射线路径是否被挡住。
   *
   * @param path  {@link #rayPath} 返回的扁平坐标数组（x,y,z 依次排列）
   * @param query 遮挡查询；为 {@code null} 时不做判定（返回 false，即视为可见）
   * @return 是否被遮挡；路径为空时恒为 false（贴得太近，视为可见）
   */
  public static boolean isRayOccluded(int[] path, RayQuery query) {
    if (path == null || path.length < 3 || query == null) {
      return false;
    }
    for (int index = 0; index + 2 < path.length; index += 3) {
      if (query.isOccluding(path[index], path[index + 1], path[index + 2])) {
        return true;
      }
    }
    return false;
  }
}