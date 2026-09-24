package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 邻近显形「选择逻辑」测试：视锥内/外（正前方、背后、侧向边界）、最小距离豁免，
 * 以及射线体素路径与「被挡住则不显形」的纯逻辑部分（读方块由主线程注入，单测用内存表代替）。
 */
class ProximitySelectorTest {

  private static final double DEFAULT_MIN_DISTANCE = 4.0D;
  private static final double DEFAULT_FOV = 80.0D;

  /** 站在 (0.5, 64.5, 0.5) 面朝 +Z（与 Minecraft 视线方向约定一致）。 */
  private static ProximitySelector.Eye facingPositiveZ() {
    return ProximitySelector.eye(0.5D, 64.5D, 0.5D, 0.0D, 0.0D, 1.0D);
  }

  @Test
  void eyeDirectionIsNormalized() {
    ProximitySelector.Eye eye = ProximitySelector.eye(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 5.0D);

    assertEquals(0.0D, eye.dirX(), 1.0E-9D);
    assertEquals(1.0D, eye.dirZ(), 1.0E-9D);
  }

  @Test
  void straightAheadIsInsideFrustum() {
    assertTrue(ProximitySelector.withinFrustum(facingPositiveZ(), 0, 64, 6,
        DEFAULT_MIN_DISTANCE, DEFAULT_FOV), "正前方 6 格必须在视锥内");
    assertTrue(ProximitySelector.withinFrustum(facingPositiveZ(), 1, 64, 6,
        DEFAULT_MIN_DISTANCE, DEFAULT_FOV), "正前方偏一格仍在视锥内");
  }

  @Test
  void behindIsOutsideFrustum() {
    assertFalse(ProximitySelector.withinFrustum(facingPositiveZ(), 0, 64, -6,
        DEFAULT_MIN_DISTANCE, DEFAULT_FOV), "背后必须被剔除");
    assertFalse(ProximitySelector.withinFrustum(facingPositiveZ(), 0, 64, -20,
        DEFAULT_MIN_DISTANCE, DEFAULT_FOV), "远处正后方同样剔除");
  }

  @Test
  void sideBoundaryFollowsConfiguredFov() {
    ProximitySelector.Eye eye = facingPositiveZ();

    // 半角 40°（cos≈0.766）：约 39° 应命中，约 45° 应被剔除
    assertTrue(ProximitySelector.withinFrustum(eye, 4, 64, 5, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "约 39°（侧向偏 4 格、前方 5 格）应在视锥内");
    assertFalse(ProximitySelector.withinFrustum(eye, 5, 64, 5, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "约 45° 应被剔除");
  }

  @Test
  void verticalAngleIsAlsoConsidered() {
    ProximitySelector.Eye eye = facingPositiveZ();

    assertTrue(ProximitySelector.withinFrustum(eye, 0, 68, 6, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "前方偏上的目标仍在锥内（俯仰角约 32°）");
    assertFalse(ProximitySelector.withinFrustum(eye, 0, 74, 6, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "抬头 50° 以外的目标应被剔除");
  }

  @Test
  void minimumDistanceExemptsFrustumCheck() {
    ProximitySelector.Eye eye = facingPositiveZ();

    assertTrue(ProximitySelector.withinFrustum(eye, 0, 64, -2, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "最小距离内的背后方块必须豁免（贴脸/脚下也要显形）");
    assertTrue(ProximitySelector.withinFrustum(eye, 0, 64, 0, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "脚下/同格坐标豁免");
    assertTrue(ProximitySelector.withinFrustum(eye, 3, 64, -7, 12.0D, DEFAULT_FOV),
        "豁免距离可配");
  }

  @Test
  void fovOutOfRangeMeansNoCulling() {
    for (double fov : new double[] {0.0D, -10.0D, 180.0D, 360.0D}) {
      assertTrue(ProximitySelector.withinFrustum(facingPositiveZ(), 0, 64, -20,
          DEFAULT_MIN_DISTANCE, fov), "fov=" + fov + " 视为不做视锥剔除");
    }
  }

  @Test
  void rayPathContainsIntermediateVoxelsOnly() {
    int[] path = ProximitySelector.rayPath(facingPositiveZ(), 0, 64, 5, 16);

    assertEquals(12, path.length, "眼睛 (0.5,64.5,0.5) → 方块中心 (0.5,64.5,5.5) 应途经 4 个体素");
    Set<String> voxels = new HashSet<>();
    for (int index = 0; index < path.length; index += 3) {
      voxels.add(path[index] + "," + path[index + 1] + "," + path[index + 2]);
    }
    assertTrue(voxels.contains("0,64,1"), "路径含 (0,64,1)：" + voxels);
    assertTrue(voxels.contains("0,64,4"), "路径含 (0,64,4)：" + voxels);
    assertFalse(voxels.contains("0,64,5"), "不含终点自身所在体素：" + voxels);
  }

  @Test
  void rayIsOccludedWhenAnyVoxelBlocks() {
    int[] path = ProximitySelector.rayPath(facingPositiveZ(), 0, 64, 5, 16);

    assertTrue(ProximitySelector.isRayOccluded(path, (x, y, z) -> z == 2),
        "路径中任一遮挡体素都应判为被挡住");
    assertFalse(ProximitySelector.isRayOccluded(path, (x, y, z) -> false),
        "路径全通时应判为可见");
    assertFalse(ProximitySelector.isRayOccluded(path, (x, y, z) -> z == 5),
        "终点所在体素不在路径内，其遮挡不影响判定");
  }

  @Test
  void rayDegradesGracefully() {
    assertFalse(ProximitySelector.isRayOccluded(null, (x, y, z) -> true), "无路径视为可见");
    assertFalse(ProximitySelector.isRayOccluded(new int[0], (x, y, z) -> true), "空路径视为可见");
    assertFalse(ProximitySelector.isRayOccluded(new int[] {1, 2, 3}, null), "无查询函数视为可见");
    assertFalse(ProximitySelector.isRayOccluded(
        ProximitySelector.rayPath(facingPositiveZ(), 0, 64, 0, 8), (x, y, z) -> true),
        "贴得太近（无中间体素）时不做遮挡判定");
  }
}