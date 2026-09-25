package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 邻近显形「选择逻辑」测试：视锥内/外（正前方、侧前方 30°、背后、竖直边界）、最小距离豁免，
 * 以及可见性判定（暴露面识别、多候选点采样、被挡住则不显形）的纯逻辑部分
 * （读方块由调用方注入，单测用内存表代替）。
 */
class ProximitySelectorTest {

  private static final double DEFAULT_MIN_DISTANCE = 4.0D;
  private static final double DEFAULT_FOV = 80.0D;
  private static final int SAMPLES = 16;

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

  /**
   * 侧前方：水平方向比竖直更宽（客户端 FOV 是「竖直全角」，水平按宽高比更宽）。
   *
   * <p>fov=80 → 竖直半角 40°、水平半角 ≈56.2°（16:9）。修正前按「圆锥半角 40°」判定，
   * 真机日志显示 445/512 个候选被误剔（87%）。
   */
  @Test
  void sideBoundaryFollowsConfiguredFov() {
    ProximitySelector.Eye eye = facingPositiveZ();

    // 约 39°（侧向偏 4 格、前方 5 格）：竖直与水平都在界内
    assertTrue(ProximitySelector.withinFrustum(eye, 4, 64, 5, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "约 39° 应在本视锥内");
    // 侧前方 30°：用户明确要求必须可见
    assertTrue(ProximitySelector.withinFrustum(eye, 3, 64, 6, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "偏离视线约 30° 的侧前方目标必须可见");
    // 水平 45°：仍在水平半角（≈56°）内 → 可见（旧实现按圆锥 40° 会误剔）
    assertTrue(ProximitySelector.withinFrustum(eye, 5, 64, 5, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "水平 45° 在水平半角内（客户端的水平视场更宽）");
    // 水平约 60°：超出水平半角 → 剔除
    assertFalse(ProximitySelector.withinFrustum(eye, 7, 64, 4, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "水平约 60° 超出水平半角，应被剔除");
  }

  /** 竖直方向仍按 fov/2 = 40° 收紧：同样偏离 45° 时竖直超界、水平不超界（正是修正后的差异）。 */
  @Test
  void verticalBoundaryIsNarrowerThanHorizontal() {
    ProximitySelector.Eye eye = facingPositiveZ();

    assertFalse(ProximitySelector.withinFrustum(eye, 0, 70, 6, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "竖直偏离约 45° 超出竖直半角 40°，应被剔除");
    assertTrue(ProximitySelector.withinFrustum(eye, 6, 64, 6, DEFAULT_MIN_DISTANCE, DEFAULT_FOV),
        "同样约 45°、但在水平方向 → 可见（水平半角更宽）");
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

  // ---------------------------------------------------------------- 多候选点可见性（isVisible）

  /** 1 宽 2 高、长 45 格的长隧道：x=0 且 y=64..65 为空气，其余为岩石。 */
  private static final ProximitySelector.RayQuery LONG_TUNNEL =
      (x, y, z) -> !(x == 0 && (y == 64 || y == 65) && z >= 0 && z <= 45);

  /**
   * <b>真机 bug 回归</b>：嵌在角落、只有一面（-X）暴露的矿石，在 20~40 格必须判为可见。
   *
   * <p>用户反馈「矿石的裸露面周围有方块时，经常需要靠得很近才能取消伪装」：单点采样（包围盒最近点）
   * 一旦压在相邻方块那一侧就会被误判为被挡。现在先只看暴露面、再在暴露面上取至多 5 个候选点，
   * 任一通畅即可见。
   */
  @Test
  void cornerOreWithSingleExposedFaceIsVisibleFromFar() {
    ProximitySelector.Eye eye = standingEye();

    for (int z = 20; z <= 40; z += 10) {
      assertEquals(ProximitySelector.FACE_NEG_X,
          ProximitySelector.exposedFaces(1, 64, z, LONG_TUNNEL),
          "+Y/±Z/±X(除 -X)/-Y 全被岩石包围，只应暴露 -X 面：" + z);
      assertTrue(ProximitySelector.isVisible(eye, 1, 64, z, LONG_TUNNEL, SAMPLES),
          z + " 格外「只有一面暴露」的角落矿必须判为可见（真机 bug 回归）");
    }
  }

  /** <b>红线</b>：六面全被遮挡（完全掩埋）的方块绝不显形——从任何方向都不可能看到。 */
  @Test
  void fullyBuriedOreIsNeverVisible() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery allSolid = (x, y, z) -> true;

    assertEquals(0, ProximitySelector.exposedFaces(0, 64, 5, allSolid), "六面全遮挡 = 无暴露面");
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, allSolid, SAMPLES),
        "完全掩埋的方块必须判为不可见（绝不能隔着墙还原）");
  }

  /** <b>红线</b>：完全被 2 格厚石墙挡住的矿石仍必须判为不可见。 */
  @Test
  void twoBlockThickWallStillHidesOre() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery wall = (x, y, z) -> z == 3 || z == 4;

    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 2, wall, SAMPLES),
        "墙前面的方块必须仍然可见（不得因多候选点而误隐藏）");
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, wall, SAMPLES),
        "2 格厚石墙后面的矿必须判为不可见");
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 6, wall, SAMPLES),
        "同一射线更远处的矿同样不可见");
  }

  /** 多候选点全部被挡 → 判为不可见（单个暴露面存在但视线确实被挡）。 */
  @Test
  void allCandidatePointsBlockedMeansInvisible() {
    ProximitySelector.Eye eye = standingEye();
    // 只有 (0,64,4) 与 (0,64,5) 是空气：矿石的 -Z 面暴露在一个被封死的小口袋里，视线仍被 z=3 挡住
    ProximitySelector.RayQuery pocket =
        (x, y, z) -> !(x == 0 && y == 64 && (z == 4 || z == 5));

    assertEquals(ProximitySelector.FACE_NEG_Z,
        ProximitySelector.exposedFaces(0, 64, 5, pocket), "只应暴露 -Z 面");
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, pocket, SAMPLES),
        "所有候选点的射线都被挡时必须判为不可见");
  }

  /** 暴露面识别只认「朝向非遮挡方块」的面：隧道壁上只有 -X 一面暴露，采样点也全部落在该面上。 */
  @Test
  void visibilityPointsOnlyOnExposedFacesAndCappedAtFive() {
    ProximitySelector.Eye eye = standingEye();
    int faces = ProximitySelector.exposedFaces(1, 64, 30, LONG_TUNNEL);

    assertEquals(ProximitySelector.FACE_NEG_X, faces, "只应暴露 -X 面");
    double[][] points = ProximitySelector.visibilityPoints(eye, 1, 64, 30, faces);

    assertTrue(points.length > 0 && points.length <= ProximitySelector.MAX_SAMPLE_POINTS,
        "候选点数量必须为正且不超过 " + ProximitySelector.MAX_SAMPLE_POINTS + "：" + points.length);
    assertEquals(1.0D, points[0][0], 1.0E-9D, "首个候选点必须是暴露面的中心（x 固定在面平面上）");
    assertEquals(64.5D, points[0][1], 1.0E-9D, "首个候选点是 -X 面中心");
    assertEquals(30.5D, points[0][2], 1.0E-9D, "首个候选点是 -X 面中心");
    for (double[] point : points) {
      assertEquals(1.0D, point[0], 1.0E-9D,
          "不暴露的面不采样：所有候选点的 x 都必须落在 -X 面平面上：" + point[0]);
    }
  }

  /** 最近点落在非暴露面上时必须被丢弃（只采样暴露面）；六面全暴露时按与视线的正对程度选面。 */
  @Test
  void nearestPointOnBuriedFaceIsDiscarded() {
    // 眼位正好在方块 x 区间内（x=1.5）：最近点不会落在 -X 面平面上 → 被暴露面闸门丢弃
    ProximitySelector.Eye eye = ProximitySelector.eye(1.5D, 66.62D, 5.5D, 0.0D, 0.0D, 1.0D);
    double[][] points = ProximitySelector.visibilityPoints(eye, 1, 65, 5,
        ProximitySelector.FACE_NEG_X);

    for (double[] point : points) {
      assertEquals(1.0D, point[0], 1.0E-9D, "非暴露面（包围盒最近点落在 +Y 面）不得被采样");
    }

    // 六面全暴露：选面按「最正对视线」——面朝 +Z 的玩家应取 +Z 面中心
    ProximitySelector.Eye plusZ = ProximitySelector.eye(0.5D, 64.5D, -3.5D, 0.0D, 0.0D, 1.0D);
    double[][] all = ProximitySelector.visibilityPoints(plusZ, 0, 64, 0, ProximitySelector.ALL_FACES);

    assertEquals(0.5D, all[0][0], 1.0E-9D);
    assertEquals(64.5D, all[0][1], 1.0E-9D);
    assertEquals(0.0D, all[0][2], 1.0E-9D, "正对 +Z 方向看时，应优先取 -Z 面（朝向玩家的那个面）中心");
  }

  // ---------------------------------------------------------------- 流体覆盖（显形侧）

  /**
   * 流体覆盖（显形侧）回归：目标方块<b>上方是流体</b>时不显形（保持伪装）；关闭该规则时恢复显形。
   *
   * <p>场景与隐藏侧对称：刷在岩浆里的下界残骸本就被伪装，显形侧若把它还原，就等于把它亮给玩家。
   * 流体被移除后（服务端下发变更）重查，应恢复显形。
   */
  @Test
  void fluidAboveKeepsTargetDisguisedOnlyWhenFluidCoverEnabled() {
    ProximitySelector.Eye eye = standingEye();
    // 目标 (0,64,5) 四周空气（视线通畅），其上方 (0,65,5) 是岩浆
    ProximitySelector.RayQuery lavaAbove = new ProximitySelector.RayQuery() {
      @Override
      public boolean isOccluding(int x, int y, int z) {
        return false;
      }

      @Override
      public boolean isFluid(int x, int y, int z) {
        return x == 0 && y == 65 && z == 5;
      }
    };

    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, lavaAbove, SAMPLES, true),
        "上方是岩浆且流体覆盖开启 → 保持伪装，不显形");
    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 5, lavaAbove, SAMPLES, false),
        "流体覆盖关闭 → 规则不生效，视线通畅即显形（行为回到原状）");

    // 上方不是流体：两种设置都正常显形
    ProximitySelector.RayQuery open = (x, y, z) -> false;
    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 5, open, SAMPLES, true),
        "上方非流体 → 正常显形");

    // 流体被移除（变更事件后的重查）→ 恢复显形
    ProximitySelector.RayQuery lavaRemoved = new ProximitySelector.RayQuery() {
      @Override
      public boolean isOccluding(int x, int y, int z) {
        return false;
      }

      @Override
      public boolean isFluid(int x, int y, int z) {
        return false;
      }
    };
    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 5, lavaRemoved, SAMPLES, true),
        "流体被移除 → 恢复显形（由方块变更事件触发正常显形流程）");

    // 兼容：旧的 6 参重载默认不启用流体规则（既有调用方行为不变）
    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 5, lavaAbove, SAMPLES),
        "6 参重载默认 fluidCover=false，行为不变");
  }
}