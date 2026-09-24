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

  /** 遮挡判定决定「显形 / 不显形」：这是防止「隔着墙把矿亮给透视客户端」的关键闸门。 */
  @Test
  void occludedCandidateIsNotRevealedWhileVisibleOneIs() {
    ProximitySelector.Eye eye = facingPositiveZ();
    int[] path = ProximitySelector.rayPath(eye, 0, 64, 6, 16);

    assertTrue(ProximitySelector.isRayOccluded(path, (x, y, z) -> z == 3),
        "视线被墙挡住 → 不显形（等玩家靠近/转向再说）");
    assertFalse(ProximitySelector.isRayOccluded(path, (x, y, z) -> false),
        "视线通畅 → 显形");
  }

  // ---------------------------------------------------------------- 可见性射线（到方块最近点）

  /** 站姿眼位（脚上方 1.62 格），面朝 +Z。 */
  private static ProximitySelector.Eye standingEye() {
    return ProximitySelector.eye(0.5D, 65.62D, 0.5D, 0.0D, 0.0D, 1.0D);
  }

  /** 1 宽 2 高隧道：x=0 且 y=64..65 为空气，其余为岩石。 */
  private static boolean tunnelAir(int x, int y, int z) {
    return x == 0 && (y == 64 || y == 65) && z >= 0 && z <= 12;
  }

  private static Set<String> voxelSet(int[] path) {
    Set<String> voxels = new HashSet<>();
    for (int index = 0; index < path.length; index += 3) {
      voxels.add(path[index] + "," + path[index + 1] + "," + path[index + 2]);
    }
    return voxels;
  }

  /**
   * 真机根因回归：矿洞里「嵌在岩壁上、只露出一面」的裸露矿必须判定可见。
   *
   * <p>到<b>方块中心</b>的射线会先钻进紧贴该面的岩石，于是被误判为遮挡（显形永不发生）；
   * 到<b>最近点</b>的射线贴着可见面走，才判得对。这里同时锁住两个方向，防止旧逻辑回归。
   */
  @Test
  void nearestPointRayRevealsOreExposedOnTunnelWall() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery stone = (x, y, z) -> !tunnelAir(x, y, z);

    for (int z = 2; z <= 8; z += 2) {
      assertTrue(ProximitySelector.isRayOccluded(
          ProximitySelector.rayPath(eye, 1, 64, z, 16), stone),
          "对照：到方块中心的射线钻进相邻岩石，把 z=" + z + " 的裸露矿误判为被遮挡");
      assertFalse(ProximitySelector.isRayOccluded(
          ProximitySelector.visibilityPath(eye, 1, 64, z, 16), stone),
          "到最近点的射线必须看到右墙上 z=" + z + " 的裸露矿");
    }
  }

  /** 正前方 1~3 格、视线通畅的裸露矿必须判定可见（用户要的「走近就能看到矿」）。 */
  @Test
  void nearestPointRaySeesOreStraightAhead() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery open = (x, y, z) -> false;

    for (int z = 1; z <= 3; z++) {
      assertFalse(ProximitySelector.isRayOccluded(
          ProximitySelector.visibilityPath(eye, 0, 64, z, 16), open),
          "正前方 " + z + " 格、视线通畅的裸露矿必须可见");
    }
  }

  /** 目标方块背后/侧面被 2 格厚石墙挡住时必须判定不可见（不得为了「多显形」而穿墙）。 */
  @Test
  void nearestPointRayIsStillBlockedByTwoBlockThickWall() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery wall = (x, y, z) -> z == 3 || z == 4;

    assertTrue(ProximitySelector.isRayOccluded(
        ProximitySelector.visibilityPath(eye, 0, 64, 5, 16), wall),
        "2 格厚石墙后面的矿必须判为不可见");
    assertTrue(ProximitySelector.isRayOccluded(
        ProximitySelector.visibilityPath(eye, 0, 64, 6, 16), wall),
        "同一射线更远处的矿同样不可见");
    assertFalse(ProximitySelector.isRayOccluded(
        ProximitySelector.visibilityPath(eye, 0, 64, 2, 16), wall),
        "墙前面的方块不受这面墙影响");
  }

  /** 眼睛所在体素绝不参与遮挡判定（自遮挡）：否则站在方块棱角上时会「什么都看不见」。 */
  @Test
  void eyeVoxelNeverCountsAsOccluder() {
    // 眼位正好落在方块棱角 (0,64,0) 上：修复前首个采样点会落回该体素并被当成遮挡物
    ProximitySelector.Eye eye = ProximitySelector.eye(0.0D, 64.0D, 0.0D, 0.0D, 0.0D, 1.0D);
    int[] path = ProximitySelector.rayPath(eye, 0, 66, 2, 16);

    assertFalse(voxelSet(path).contains("0,64,0"), "眼睛所在体素必须排除在路径之外（自遮挡）");
    assertFalse(ProximitySelector.isRayOccluded(path, (x, y, z) -> x == 0 && y == 64 && z == 0),
        "眼睛所在体素即便被判为遮挡，也不得让目标变成不可见");
  }

  /** 目标方块自身绝不参与遮挡判定（终点体素排除）。 */
  @Test
  void targetVoxelNeverCountsAsOccluder() {
    ProximitySelector.Eye eye = standingEye();

    for (int z = 2; z <= 8; z++) {
      int[] path = ProximitySelector.visibilityPath(eye, 0, 64, z, 16);
      assertFalse(voxelSet(path).contains("0,64," + z),
          "目标方块自身不得出现在路径中（z=" + z + "）");
    }
  }

  /** 边界情形：眼位与目标同体素、相邻体素（没有中间体素）都视为可见，不能因「无路径」而漏显形。 */
  @Test
  void visibilityRayDegradesForSameAndAdjacentVoxels() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery allSolid = (x, y, z) -> true;

    assertFalse(ProximitySelector.isRayOccluded(
        ProximitySelector.visibilityPath(eye, 0, 65, 0, 16), allSolid),
        "眼位与目标同体素 → 可见");
    assertFalse(ProximitySelector.isRayOccluded(
        ProximitySelector.visibilityPath(eye, 0, 65, 1, 16), allSolid),
        "相邻体素、无中间体素 → 可见");
  }

  /** 圆整（贴墙/贴地）时也必须能正确退化，不得抛异常或误判。 */
  @Test
  void visibilityRayDegradesWhenEyeIsExactlyOnBoundary() {
    ProximitySelector.Eye eye = ProximitySelector.eye(1.0D, 65.0D, 1.0D, 0.0D, 0.0D, 1.0D);

    assertFalse(ProximitySelector.isRayOccluded(
        ProximitySelector.visibilityPath(eye, 1, 65, 1, 16), (x, y, z) -> true),
        "眼位与目标同体素 → 可见");
    assertFalse(ProximitySelector.isRayOccluded(
        ProximitySelector.visibilityPath(eye, 1, 65, 2, 16), (x, y, z) -> true),
        "相邻体素 → 可见");
  }
}