package net.mikumc.mikuxraynet.antixray;

/** 挖掘近场的多面采样。候选点在目标格内，真实碰撞判定由调用者提供。 */
final class MiningSight {

  private static final double INSET = 1.0E-4D;
  private static final double INNER_EDGE = 0.08D;
  private static final double[] SAMPLES = {INNER_EDGE, 1.0D - INNER_EDGE};

  @FunctionalInterface
  interface Probe {
    boolean hits(double x, double y, double z);
  }

  private MiningSight() {
  }

  static boolean inRange(double ex, double ey, double ez, int x, int y, int z, double reach) {
    double dx = ex - clamp(ex, x, x + 1.0D);
    double dy = ey - clamp(ey, y, y + 1.0D);
    double dz = ez - clamp(ez, z, z + 1.0D);
    return dx * dx + dy * dy + dz * dz <= reach * reach;
  }

  /** 每轴至多选一个朝向眼睛的面。每面先查最近位置和面心，再查四个内缩角。 */
  static boolean anyHit(double ex, double ey, double ez, int x, int y, int z, double reach, Probe probe) {
    if (!inRange(ex, ey, ez, x, y, z, reach)) {
      return false;
    }
    double reachSquared = reach * reach;
    if (ex < x || ex > x + 1.0D) {
      double fx = x + (ex < x ? INSET : 1.0D - INSET);
      if (hitsFace(0, fx, y, z, ex, ey, ez, reachSquared, probe)) {
        return true;
      }
    }
    if (ey < y || ey > y + 1.0D) {
      double fy = y + (ey < y ? INSET : 1.0D - INSET);
      if (hitsFace(1, fy, x, z, ex, ey, ez, reachSquared, probe)) {
        return true;
      }
    }
    if (ez < z || ez > z + 1.0D) {
      double fz = z + (ez < z ? INSET : 1.0D - INSET);
      return hitsFace(2, fz, x, y, ex, ey, ez, reachSquared, probe);
    }
    return false;
  }

  private static boolean hitsFace(int axis, double fixed, int u, int v, double ex, double ey, double ez,
      double reachSquared, Probe probe) {
    double nearU = clamp(axis == 0 ? ey : ex, u + INNER_EDGE, u + 1.0D - INNER_EDGE);
    double nearV = clamp(axis == 2 ? ey : ez, v + INNER_EDGE, v + 1.0D - INNER_EDGE);
    if (sample(axis, fixed, nearU, nearV, ex, ey, ez, reachSquared, probe)
        || ((nearU != u + 0.5D || nearV != v + 0.5D)
            && sample(axis, fixed, u + 0.5D, v + 0.5D, ex, ey, ez, reachSquared, probe))) {
      return true;
    }
    for (double offU : SAMPLES) {
      for (double offV : SAMPLES) {
        double pu = u + offU;
        double pv = v + offV;
        if ((pu != nearU || pv != nearV) && sample(axis, fixed, pu, pv, ex, ey, ez, reachSquared, probe)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean sample(int axis, double fixed, double u, double v, double ex, double ey, double ez,
      double reachSquared, Probe probe) {
    double x = axis == 0 ? fixed : u;
    double y = axis == 1 ? fixed : axis == 0 ? u : v;
    double z = axis == 2 ? fixed : v;
    double dx = x - ex;
    double dy = y - ey;
    double dz = z - ez;
    double distanceSquared = dx * dx + dy * dy + dz * dz;
    return distanceSquared > 1.0E-12D && distanceSquared <= reachSquared && probe.hits(x, y, z);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
