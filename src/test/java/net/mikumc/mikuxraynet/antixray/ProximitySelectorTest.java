package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.registry.OcclusionRules;
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

  // ---------------------------------------------------------------- 打包默认值的安全保证（真机回归）

  /** 打包 antixray.yml 的视锥默认值：竖直全角 120°、24 格内豁免。 */
  private static final double PACKED_FOV = AntiXrayConfig.FRUSTUM_FOV_DEFAULT;
  private static final double PACKED_MIN_DISTANCE = AntiXrayConfig.FRUSTUM_MIN_DISTANCE_DEFAULT;

  /**
   * 真机回归：站在黑曜石门正前方时，「门的上半部分」在 3 格距离上就有 40° 以上的仰角。
   *
   * <p>旧默认（fov=80 / min-distance=4）会把它们判为视锥外 → 不发包也不记已显形 → 玩家看到的
   * 一直是伪装方块，直到点一下/挖一下（事件显形不做视锥判定）才变回来。
   * 打包默认值下，24 格内的任何方向都必须保留，因此这类「看得见却是假的」不再发生。
   */
  @Test
  void packedDefaultsKeepBlocksInFrontOfThePlayer() {
    ProximitySelector.Eye eye = standingEye();

    // 门顶部：前方 3 格、比眼睛高 3 格（仰角约 45°）——旧配置在这里被剔除
    assertTrue(ProximitySelector.withinFrustum(eye, 0, 69, 3, PACKED_MIN_DISTANCE, PACKED_FOV),
        "眼前 3 格、仰角约 45° 的门顶必须保留（24 格内一律豁免视锥）");
    assertTrue(ProximitySelector.withinFrustum(eye, -4, 68, 2, PACKED_MIN_DISTANCE, PACKED_FOV),
        "近处侧上方同样保留");
    assertTrue(ProximitySelector.withinFrustum(eye, 0, 66, -8, PACKED_MIN_DISTANCE, PACKED_FOV),
        "24 格内的背后/脚下也豁免（贴脸不管朝向都必须显形）");
    assertTrue(ProximitySelector.withinFrustum(eye, 3, 64, 14, PACKED_MIN_DISTANCE, PACKED_FOV),
        "水平偏 12° 左右、14 格远仍在锥内");
  }

  /**
   * 默认 120° 覆盖「客户端 FOV 上限 110 + 16:9」的整个可视区：竖直 60°、水平约 72°。
   *
   * <p>即：客户端的 FOV 无论怎么调，玩家屏幕上的方块都不会被误剔；更宽的屏幕见 yml 注释。
   */
  @Test
  void packedFovCoversClientMaximumFieldOfView() {
    ProximitySelector.Eye eye = facingPositiveZ();

    // 仰角约 50°（与视线俯仰之差 50° > 旧默认的竖直半角 40°，但在新的 60° 内）→ 必须在锥内
    assertTrue(ProximitySelector.withinFrustum(eye, 0, 90, 22, PACKED_MIN_DISTANCE, PACKED_FOV),
        "仰角约 50° 在 60° 半角内（客户端 FOV=100 时屏幕顶部可见）");
    // 水平约 65°（16:9 客户端 FOV=110 时屏幕侧边；旧默认的水平半角是 56°）→ 必须在锥内
    assertTrue(ProximitySelector.withinFrustum(eye, 43, 64, 20, PACKED_MIN_DISTANCE, PACKED_FOV),
        "水平约 65° 在 72° 半角内（16:9、FOV=110 的屏幕边缘）");
    // 远超客户端可视范围（水平约 85°、且已超出 24 格豁免）→ 仍然剔除（视锥的省流作用保留）
    assertFalse(ProximitySelector.withinFrustum(eye, 60, 64, 6, PACKED_MIN_DISTANCE, PACKED_FOV),
        "水平约 85° 超出 72° 半角，仍应剔除（侧后方继续省流）");
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
    int[] path = centerPath(facingPositiveZ(), 0, 64, 5);

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
    int[] path = centerPath(facingPositiveZ(), 0, 64, 5);

    assertTrue(occluded(path, (x, y, z) -> z == 2), "路径中任一遮挡体素都应判为被挡住");
    assertFalse(occluded(path, (x, y, z) -> false), "路径全通时应判为可见");
    assertFalse(occluded(path, (x, y, z) -> z == 5), "终点所在体素不在路径内，其遮挡不影响判定");
  }

  @Test
  void rayDegradesGracefully() {
    assertFalse(occluded(null, (x, y, z) -> true), "无路径视为可见");
    assertFalse(occluded(new int[0], (x, y, z) -> true), "空路径视为可见");
    assertFalse(occluded(new int[] {1, 2, 3}, null), "无查询函数视为可见");
    assertFalse(occluded(centerPath(facingPositiveZ(), 0, 64, 0), (x, y, z) -> true),
        "贴得太近（无中间体素）时不做遮挡判定");
  }

  /** 遮挡判定决定「显形 / 不显形」：这是防止「隔着墙把矿亮给透视客户端」的关键闸门。 */
  @Test
  void occludedCandidateIsNotRevealedWhileVisibleOneIs() {
    ProximitySelector.Eye eye = facingPositiveZ();
    int[] path = centerPath(eye, 0, 64, 6);

    assertTrue(occluded(path, (x, y, z) -> z == 3),
        "视线被墙挡住 → 不显形（等玩家靠近/转向再说）");
    assertFalse(occluded(path, (x, y, z) -> false), "视线通畅 → 显形");
  }

  // ---------------------------------------------------------------- 可见性射线（到方块最近点）

  /** 站姿眼位（脚上方 1.62 格），面朝 +Z。 */
  private static ProximitySelector.Eye standingEye() {
    return ProximitySelector.eye(0.5D, 65.62D, 0.5D, 0.0D, 0.0D, 1.0D);
  }

  // ---------------------------------------------------------------- 测试用等价几何

  /**
   * 测试用「从眼睛到目标点的体素路径」（不含起点与终点所在体素）。
   *
   * <p>生产已改用遮挡位图步进射线（见 {@link BitmapVisibility}），这里保留一份独立的等距步进实现，
   * 只为离线断言「候选点选择 + 任一通畅即可见」的语义不被削弱；它不参与生产路径。
   */
  private static int[] voxelPath(ProximitySelector.Eye eye, double toX, double toY, double toZ) {
    double deltaX = toX - eye.x();
    double deltaY = toY - eye.y();
    double deltaZ = toZ - eye.z();
    double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    if (length < 1.0E-6D) {
      return new int[0];
    }
    int samples = (int) Math.min(Math.max(1, SAMPLES), Math.max(1.0D, Math.floor(length)));
    if (samples < 2) {
      return new int[0];
    }
    int[] path = new int[samples * 3];
    int count = 0;
    int startX = floor(eye.x());
    int startY = floor(eye.y());
    int startZ = floor(eye.z());
    int lastX = Integer.MIN_VALUE;
    int lastY = Integer.MIN_VALUE;
    int lastZ = Integer.MIN_VALUE;
    for (int step = 1; step < samples; step++) {
      double ratio = (double) step / (double) samples;
      int x = floor(eye.x() + deltaX * ratio);
      int y = floor(eye.y() + deltaY * ratio);
      int z = floor(eye.z() + deltaZ * ratio);
      if (x == startX && y == startY && z == startZ) {
        continue;
      }
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

  private static int floor(double value) {
    int truncated = (int) value;
    return value < truncated ? truncated - 1 : truncated;
  }

  private static double clamp(double value, double min, double max) {
    return Math.min(Math.max(value, min), max);
  }

  /** 到方块「中心」的路径（对应改造前的 rayPath）。 */
  private static int[] centerPath(ProximitySelector.Eye eye, int blockX, int blockY, int blockZ) {
    return voxelPath(eye, blockX + 0.5D, blockY + 0.5D, blockZ + 0.5D);
  }

  /** 到方块「包围盒最近点」的路径（对应改造前的 visibilityPath）。 */
  private static int[] nearestPath(ProximitySelector.Eye eye, int blockX, int blockY, int blockZ) {
    return voxelPath(eye, clamp(eye.x(), blockX, blockX + 1.0D),
        clamp(eye.y(), blockY, blockY + 1.0D), clamp(eye.z(), blockZ, blockZ + 1.0D));
  }

  /**
   * 给定内存遮挡表，路径是否被挡（对应改造前的 isRayOccluded）；空路径/null 查询恒判「未挡住」。
   *
   * <p>视觉口径：{@link ProximitySelector.RayQuery#isOccluding} 本身即「视觉遮挡」（含岩浆，由
   * {@code OcclusionRules} 统一并入），因此这里只查 {@code isOccluding}——不再单独叠加岩浆判断。
   */
  private static boolean occluded(int[] path, ProximitySelector.RayQuery table) {
    if (path == null || path.length < 3 || table == null) {
      return false;
    }
    for (int index = 0; index + 2 < path.length; index += 3) {
      int x = path[index];
      int y = path[index + 1];
      int z = path[index + 2];
      if (table.isOccluding(x, y, z)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 由内存遮挡表构造探针：等价于「用体素步进做单点射线」，供 {@code isVisible} 的离线断言使用
   * （生产注入的是遮挡位图步进射线，见 {@link BitmapVisibility}）。
   */
  private static ProximitySelector.RayProbe probe(ProximitySelector.Eye eye,
      ProximitySelector.RayQuery table) {
    return (x, y, z) -> !occluded(voxelPath(eye, x, y, z), table);
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
      assertTrue(occluded(centerPath(eye, 1, 64, z), stone),
          "对照：到方块中心的射线钻进相邻岩石，把 z=" + z + " 的裸露矿误判为被遮挡");
      assertFalse(occluded(nearestPath(eye, 1, 64, z), stone),
          "到最近点的射线必须看到右墙上 z=" + z + " 的裸露矿");
    }
  }

  /** 正前方 1~3 格、视线通畅的裸露矿必须判定可见（用户要的「走近就能看到矿」）。 */
  @Test
  void nearestPointRaySeesOreStraightAhead() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery open = (x, y, z) -> false;

    for (int z = 1; z <= 3; z++) {
      assertFalse(occluded(nearestPath(eye, 0, 64, z), open),
          "正前方 " + z + " 格、视线通畅的裸露矿必须可见");
    }
  }

  /** 目标方块背后/侧面被 2 格厚石墙挡住时必须判定不可见（不得为了「多显形」而穿墙）。 */
  @Test
  void nearestPointRayIsStillBlockedByTwoBlockThickWall() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery wall = (x, y, z) -> z == 3 || z == 4;

    assertTrue(occluded(nearestPath(eye, 0, 64, 5), wall), "2 格厚石墙后面的矿必须判为不可见");
    assertTrue(occluded(nearestPath(eye, 0, 64, 6), wall), "同一射线更远处的矿同样不可见");
    assertFalse(occluded(nearestPath(eye, 0, 64, 2), wall), "墙前面的方块不受这面墙影响");
  }

  /** 眼睛所在体素绝不参与遮挡判定（自遮挡）：否则站在方块棱角上时会「什么都看不见」。 */
  @Test
  void eyeVoxelNeverCountsAsOccluder() {
    // 眼位正好落在方块棱角 (0,64,0) 上：修复前首个采样点会落回该体素并被当成遮挡物
    ProximitySelector.Eye eye = ProximitySelector.eye(0.0D, 64.0D, 0.0D, 0.0D, 0.0D, 1.0D);
    int[] path = centerPath(eye, 0, 66, 2);

    assertFalse(voxelSet(path).contains("0,64,0"), "眼睛所在体素必须排除在路径之外（自遮挡）");
    assertFalse(occluded(path, (x, y, z) -> x == 0 && y == 64 && z == 0),
        "眼睛所在体素即便被判为遮挡，也不得让目标变成不可见");
  }

  /** 目标方块自身绝不参与遮挡判定（终点体素排除）。 */
  @Test
  void targetVoxelNeverCountsAsOccluder() {
    ProximitySelector.Eye eye = standingEye();

    for (int z = 2; z <= 8; z++) {
      int[] path = nearestPath(eye, 0, 64, z);
      assertFalse(voxelSet(path).contains("0,64," + z),
          "目标方块自身不得出现在路径中（z=" + z + "）");
    }
  }

  /** 边界情形：眼位与目标同体素、相邻体素（没有中间体素）都视为可见，不能因「无路径」而漏显形。 */
  @Test
  void visibilityRayDegradesForSameAndAdjacentVoxels() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery allSolid = (x, y, z) -> true;

    assertFalse(occluded(nearestPath(eye, 0, 65, 0), allSolid), "眼位与目标同体素 → 可见");
    assertFalse(occluded(nearestPath(eye, 0, 65, 1), allSolid), "相邻体素、无中间体素 → 可见");
  }

  /** 圆整（贴墙/贴地）时也必须能正确退化，不得抛异常或误判。 */
  @Test
  void visibilityRayDegradesWhenEyeIsExactlyOnBoundary() {
    ProximitySelector.Eye eye = ProximitySelector.eye(1.0D, 65.0D, 1.0D, 0.0D, 0.0D, 1.0D);

    assertFalse(occluded(nearestPath(eye, 1, 65, 1), (x, y, z) -> true), "眼位与目标同体素 → 可见");
    assertFalse(occluded(nearestPath(eye, 1, 65, 2), (x, y, z) -> true), "相邻体素 → 可见");
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
      assertTrue(ProximitySelector.isVisible(eye, 1, 64, z, LONG_TUNNEL, SAMPLES,
              probe(eye, LONG_TUNNEL)),
          z + " 格外「只有一面暴露」的角落矿必须判为可见（真机 bug 回归）");
    }
  }

  /** <b>红线</b>：六面全被遮挡（完全掩埋）的方块绝不显形——从任何方向都不可能看到。 */
  @Test
  void fullyBuriedOreIsNeverVisible() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery allSolid = (x, y, z) -> true;

    assertEquals(0, ProximitySelector.exposedFaces(0, 64, 5, allSolid), "六面全遮挡 = 无暴露面");
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, allSolid, SAMPLES, probe(eye, allSolid)),
        "完全掩埋的方块必须判为不可见（绝不能隔着墙还原）");
  }

  /**
   * <b>判定失败的方向</b>（用户明确要求「尽可能保证玩家可见性」）：射线探针抛异常时按「通畅」处理，
   * 即<b>显形</b>。
   *
   * <p>取舍：漏显形会让玩家对着假石头挖矿（体验直接受损），多还原一块只是少一个伪装方块。
   * 注意这与「六面全掩埋 → 不显形」不冲突——后者是<b>确定性结论</b>（掩埋方块从任何方向都看不到），
   * 不是「无法判定」，因此与探针是否异常无关。
   */
  @Test
  void probeExceptionIsTreatedAsClearSoTheOreIsRevealed() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayProbe throwing = (x, y, z) -> {
      throw new IllegalStateException("模拟射线探针失败");
    };

    assertTrue(ProximitySelector.isVisible(eye, 1, 64, 20, LONG_TUNNEL, SAMPLES, throwing),
        "探针异常时必须按「通畅」处理（偏显形）：绝不因偶发异常让玩家对着假石头挖矿");

    ProximitySelector.RayQuery allSolid = (x, y, z) -> true;
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, allSolid, SAMPLES, throwing),
        "「六面全掩埋」是确定性结论、无暴露面可用，与探针是否异常无关，仍必须判不可见");
  }

  /** <b>红线</b>：完全被 2 格厚石墙挡住的矿石仍必须判为不可见。 */
  @Test
  void twoBlockThickWallStillHidesOre() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery wall = (x, y, z) -> z == 3 || z == 4;

    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 2, wall, SAMPLES, probe(eye, wall)),
        "墙前面的方块必须仍然可见（不得因多候选点而误隐藏）");
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, wall, SAMPLES, probe(eye, wall)),
        "2 格厚石墙后面的矿必须判为不可见");
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 6, wall, SAMPLES, probe(eye, wall)),
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
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, pocket, SAMPLES, probe(eye, pocket)),
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

  /**
   * 有效采样上限：候选点数固定封顶 {@link ProximitySelector#MAX_SAMPLE_POINTS}=5——即使配置给到 8
   * （旧注释曾写「钳制 1..8」），实际尝试的候选点数仍是候选点数（≤5），不会更多（见 P3 打磨项）。
   */
  @Test
  void effectiveSampleCapIsFiveRegardlessOfConfiguredSamples() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery allOpen = (x, y, z) -> false; // 六面全暴露

    assertEquals(5, ProximitySelector.MAX_SAMPLE_POINTS, "有效上限常量本身即 5");
    int expected = ProximitySelector.visibilityPoints(eye, 0, 64, 30, ProximitySelector.ALL_FACES).length;
    assertTrue(expected > 0 && expected <= ProximitySelector.MAX_SAMPLE_POINTS,
        "候选点数必须为正且不超过有效上限：" + expected);

    int[] probes = {0};
    ProximitySelector.RayProbe counter = (x, y, z) -> {
      probes[0]++;
      return false; // 全部不通畅 → 会一路试到上限
    };
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 30, allOpen, 8, counter),
        "所有候选射线被挡 → 判为不可见");
    assertEquals(expected, probes[0],
        "配置 samples=8 时，实际尝试的候选点数仍受有效上限 5 约束（不会超过候选点数）");
  }

  // ---------------------------------------------------------------- 视觉遮挡（岩浆已并入共享遮挡表）

  /**
   * 目标六面全是<b>水</b> → 可见（可显形）。
   *
   * <p>水是透明的——玩家在矿洞里应当能看见被水盖住的矿物，因此水不参与遮挡。用一个
   * {@code isOccluding=false} 的查询模拟「邻块全是水」（视觉口径里水不算遮挡），判定必须显形。
   */
  @Test
  void sixWaterFacesAreVisible() {
    ProximitySelector.Eye eye = standingEye();
    // 邻块全是水：视觉口径下不遮挡（透明）
    ProximitySelector.RayQuery water = (x, y, z) -> false;

    assertEquals(ProximitySelector.ALL_FACES,
        ProximitySelector.exposedFaces(0, 64, 5, water), "水不是视觉遮挡 → 六面全暴露");
    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 5, water, SAMPLES, probe(eye, water)),
        "六面全是水（透明）→ 有暴露面 → 正常显形");
  }

  /**
   * <b>逐面（方案 A）</b>：目标六面全是<b>岩浆</b> → 完全不可见（保持伪装、不显形）。
   *
   * <p>岩浆视觉不透明，已由 {@code OcclusionRules} 并入遮挡，因此 {@link ProximitySelector.RayQuery}
   * 对岩浆返回 {@code isOccluding=true}；六面紧邻岩浆时一个暴露面都取不到，从任何方向都看不到。
   */
  @Test
  void sixLavaFacesMakeTargetInvisible() {
    ProximitySelector.Eye eye = standingEye();
    // 邻块全是岩浆：视觉口径下视为遮挡（isOccluding=true）
    ProximitySelector.RayQuery allLava = (x, y, z) -> true;

    assertEquals(0, ProximitySelector.exposedFaces(0, 64, 5, allLava),
        "六面紧邻岩浆 = 无暴露面（岩浆是视觉遮挡）");
    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, allLava, SAMPLES, probe(eye, allLava)),
        "六面全是岩浆 → 完全不可见（保持伪装，不显形）");
  }

  /**
   * <b>逐面（方案 A）语义</b>：只有<b>部分面</b>紧邻岩浆时仍可见——只有被岩浆覆盖的那些面不可见，
   * 其余面照常暴露（比「六面全是岩浆才判」更精确）。
   *
   * <p>这里仅 -X 面是岩浆、其余五面空气：暴露面应为「除 -X 外的五面」，视线通畅即可见。
   */
  @Test
  void partialLavaFacesStillReveal() {
    ProximitySelector.Eye eye = standingEye();
    // 只有 (x=-1,y=64,z=5) 是岩浆：视觉口径下该面遮挡
    ProximitySelector.RayQuery lavaNegXOnly = (x, y, z) -> x == -1 && y == 64 && z == 5;

    int expected = ProximitySelector.ALL_FACES & ~ProximitySelector.FACE_NEG_X;
    assertEquals(expected, ProximitySelector.exposedFaces(0, 64, 5, lavaNegXOnly),
        "只有 -X 面紧邻岩浆 → 只有该面不可见，其余五面仍暴露");
    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 5, lavaNegXOnly, SAMPLES,
        probe(eye, lavaNegXOnly)),
        "只有部分面是岩浆 → 仍有暴露面 → 可见（逐面语义）");
  }

  // ---------------------------------------------------------------- 视锥「先粗后细」等价性（③）

  /**
   * 断言「粗筛 + 精判」与「仅精判」逐坐标全等。
   *
   * <p>粗筛被设计为精判接受集的<b>严格超集</b>（只允许放行过多），因此两者必须<strong>完全相等</strong>：
   * 若粗筛多剔除任何一个精判会显形的坐标，这里就会不等而失败——这正是「不减少显形」红线的机器证明。
   */
  private static void assertFrustumEquivalent(ProximitySelector.Eye eye, int x, int y, int z,
      double minDistance, double fov) {
    boolean combined = ProximitySelector.withinFrustum(eye, x, y, z, minDistance, fov);
    boolean precise = ProximitySelector.preciseWithinFrustum(eye, x, y, z, minDistance, fov);
    assertEquals(precise, combined,
        "粗筛后判定必须与仅精判全等：eye=" + eye + " block=(" + x + "," + y + "," + z + ")"
            + " min=" + minDistance + " fov=" + fov);
  }

  /** 穷举网格：多组 fov / 最小距离 / 视线方向（含各象限、正上正下、0 向量退化）× 目标网格。 */
  @Test
  void coarsePrefilterIsEquivalentToPreciseOnExhaustiveGrid() {
    double[] fovs = {0.0D, 1.0D, 2.0D, 30.0D, 80.0D, 110.0D, 179.9D, 180.0D, 200.0D, -5.0D};
    double[] minDistances = {0.0D, 0.5D, 4.0D, 16.0D};
    double[][] directions = {
        {0.0D, 0.0D, 0.0D}, {0.0D, 0.0D, 1.0D}, {0.0D, 0.0D, -1.0D}, {1.0D, 0.0D, 0.0D},
        {-1.0D, 0.0D, 0.0D}, {0.0D, 1.0D, 0.0D}, {0.0D, -1.0D, 0.0D},
        {0.3D, 0.2D, -0.9D}, {-0.7D, 0.6D, 0.4D}, {0.05D, 0.0D, -0.049D}, {0.9D, -0.1D, 0.2D}};
    double[] eyeXs = {0.5D, 0.0D}; // 0.0 = 眼位贴方块棱角
    int[] ys = {56, 64, 72};
    int compared = 0;
    for (double fov : fovs) {
      for (double min : minDistances) {
        for (double eyeX : eyeXs) {
          for (double[] dir : directions) {
            ProximitySelector.Eye eye =
                ProximitySelector.eye(eyeX, 64.62D, 0.5D, dir[0], dir[1], dir[2]);
            for (int x = -12; x <= 12; x += 4) {
              for (int y : ys) {
                for (int z = -12; z <= 12; z += 4) {
                  assertFrustumEquivalent(eye, x, y, z, min, fov);
                  compared++;
                }
              }
            }
          }
        }
      }
    }
    assertTrue(compared > 100_000, "等价性语料规模必须足够大：" + compared);
  }

  /** 随机语料：随机眼位 / 视线（含极端与跨象限）/ 目标 / fov / 最小距离，逐条比对。 */
  @Test
  void coarsePrefilterIsEquivalentToPreciseOnRandomCorpus() {
    java.util.Random random = new java.util.Random(20261002L);
    int samples = 120_000;
    for (int i = 0; i < samples; i++) {
      ProximitySelector.Eye eye = ProximitySelector.eye(
          random.nextDouble() * 64.0D - 32.0D, random.nextDouble() * 64.0D,
          random.nextDouble() * 64.0D - 32.0D,
          random.nextDouble() * 4.0D - 2.0D, random.nextDouble() * 4.0D - 2.0D,
          random.nextDouble() * 4.0D - 2.0D);
      assertFrustumEquivalent(eye,
          random.nextInt(-40, 41), random.nextInt(-40, 41), random.nextInt(-40, 41),
          random.nextDouble() * 20.0D, random.nextDouble() * 200.0D - 10.0D);
    }
  }

  /** 边界语料：最小距离边界（距离平方恰等于豁免平方及其两侧）、0/极端视线、俯仰与水平边界附近。 */
  @Test
  void coarsePrefilterIsEquivalentAtBoundaries() {
    ProximitySelector.Eye axis = ProximitySelector.eye(0.5D, 64.5D, 0.5D, 0.0D, 0.0D, 1.0D);
    for (int d : new int[] {1, 4, 16}) {
      // 方块中心 y/z 与眼位对齐 → 距离恰为 d；min 取 d 的左右极近值覆盖边界
      assertFrustumEquivalent(axis, 0, 64, d, (double) d, 80.0D);
      assertFrustumEquivalent(axis, 0, 64, d, (double) d - 0.001D, 80.0D);
      assertFrustumEquivalent(axis, 0, 64, d, (double) d + 0.001D, 80.0D);
    }

    // 视线为 0 向量（退化为 +Z）、极端正上 / 正下
    ProximitySelector.Eye zero = ProximitySelector.eye(0.5D, 64.5D, 0.5D, 0.0D, 0.0D, 0.0D);
    ProximitySelector.Eye up = ProximitySelector.eye(0.5D, 64.5D, 0.5D, 0.0D, 1.0D, 0.0D);
    ProximitySelector.Eye down = ProximitySelector.eye(0.5D, 64.5D, 0.5D, 0.0D, -1.0D, 0.0D);
    for (ProximitySelector.Eye eye : new ProximitySelector.Eye[] {zero, up, down}) {
      for (int x = -8; x <= 8; x += 2) {
        for (int y = -8; y <= 8; y += 2) {
          for (int z = -8; z <= 8; z += 2) {
            assertFrustumEquivalent(eye, x, 64 + y, z, 0.0D, 110.0D);
            assertFrustumEquivalent(eye, x, 64 + y, z, 16.0D, 80.0D);
          }
        }
      }
    }

    // 竖直约 40°（fov=80 的竖直半角）与水平约 56°（16:9 水平半角）边界两侧
    for (int[] block : new int[][] {
        {0, 69, 6}, {0, 70, 6}, {0, 69, 5}, {0, 70, 7},
        {5, 64, 5}, {6, 64, 5}, {7, 64, 4}, {7, 64, 5}, {7, 64, 6}}) {
      assertFrustumEquivalent(axis, block[0], block[1], block[2], 4.0D, 80.0D);
    }
  }

  // ---------------------------------------------------------------- 显形可见性的视觉口径（barrier 例外）

  /**
   * barrier 后方的矿石<b>必须能显形</b>：barrier 的 {@code isOccluding=true} 是服务端材质级怪值，但
   * barrier <b>对玩家完全不可见</b>——「矿石只被 barrier 挡住」时玩家本就能看穿它，显形才是正确体验。
   * 显形路径因此改用视觉口径（{@link OcclusionRules#visualVerdict}，barrier 例外生效），与位图显形路径
   * （{@code BitmapVisibility}）同源。
   *
   * <p>本用例用「服务端原始真值」作对照：旧口径把 barrier 算遮挡 → 后面的矿不显形（错误体验）。
   */
  @Test
  void barrierBehindOreIsRevealedBecauseBarrierIsInvisible() {
    ProximitySelector.Eye eye = standingEye();
    // z=3 整层是 barrier；服务端原始真值：barrier 的 isOccluding()=true
    ProximitySelector.RayQuery raw = (x, y, z) -> z == 3;
    // 显形路径实际使用的视觉口径：barrier 例外 → 不是遮挡
    ProximitySelector.RayQuery visual = (x, y, z) -> OcclusionRules.visualVerdict("barrier", z == 3);

    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, raw, SAMPLES, probe(eye, raw)),
        "对照：旧口径（服务端原始真值）把 barrier 当遮挡 → 后方矿石不显形");
    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 5, visual, SAMPLES, probe(eye, visual)),
        "视觉口径：barrier 后方矿石能显形（barrier 对玩家不可见，显形才是正确体验）");
  }

  /** 真实遮挡方块（石头）后方的矿石仍不得显形——隔离墙必须依旧有效，绝不泄露。 */
  @Test
  void realOccluderBehindOreStillBlocksReveal() {
    ProximitySelector.Eye eye = standingEye();
    ProximitySelector.RayQuery stone = (x, y, z) -> OcclusionRules.visualVerdict("stone", z == 3);

    assertFalse(ProximitySelector.isVisible(eye, 0, 64, 5, stone, SAMPLES, probe(eye, stone)),
        "石头后方的矿不得显形（隔离墙仍有效，不泄露）");
    assertTrue(ProximitySelector.isVisible(eye, 0, 64, 2, stone, SAMPLES, probe(eye, stone)),
        "墙前面的矿照常显形");
  }

  /**
   * <b>零分配改造的等价性守门</b>：{@code isVisible} 改走线程本地 scratch 后，实际尝试的候选点必须与
   * {@link ProximitySelector#visibilityPoints} 逐点一致（顺序与坐标都相同）。
   *
   * <p>为什么必须钉住：候选点顺序是「命中概率从高到低」，顺序一旦漂移，「优先试正对玩家的面中心」
   * 与「有效采样上限 5」的语义就都变了；而 {@code visibilityPoints} 是单测/外部唯一可见的口径。
   */
  @Test
  void probeReceivesExactlyThePointsReportedByVisibilityPoints() {
    ProximitySelector.Eye[] eyes = {
        standingEye(),
        ProximitySelector.eye(1.5D, 66.62D, 5.5D, 0.0D, 0.0D, 1.0D),
        ProximitySelector.eye(0.5D, 64.5D, -3.5D, 0.0D, 0.0D, 1.0D)};
    ProximitySelector.RayQuery[] queries = {
        (x, y, z) -> false,
        (x, y, z) -> !(x == 0 && (y == 64 || y == 65) && z >= 0 && z <= 45)};
    int[][] blocks = {{0, 64, 30}, {1, 65, 5}, {1, 64, 30}, {0, 64, 0}};

    for (ProximitySelector.Eye eye : eyes) {
      for (ProximitySelector.RayQuery query : queries) {
        for (int[] block : blocks) {
          int faces = ProximitySelector.exposedFaces(block[0], block[1], block[2], query);
          double[][] expected =
              ProximitySelector.visibilityPoints(eye, block[0], block[1], block[2], faces);
          java.util.List<double[]> probed = new java.util.ArrayList<>();
          ProximitySelector.RayProbe capture = (x, y, z) -> {
            probed.add(new double[] {x, y, z});
            return false; // 全部不通畅 → 会一路试到候选点上限
          };
          assertFalse(ProximitySelector.isVisible(eye, block[0], block[1], block[2], query,
              ProximitySelector.MAX_SAMPLE_POINTS, capture), "全部不通畅时必须判不可见");
          assertEquals(expected.length, probed.size(),
              "isVisible 尝试的候选点数必须等于 visibilityPoints 的候选点数");
          for (int i = 0; i < expected.length; i++) {
            double[] actual = probed.get(i);
            assertEquals(expected[i][0], actual[0], 1.0E-12D, "第 " + i + " 个候选点 x 必须一致");
            assertEquals(expected[i][1], actual[1], 1.0E-12D, "第 " + i + " 个候选点 y 必须一致");
            assertEquals(expected[i][2], actual[2], 1.0E-12D, "第 " + i + " 个候选点 z 必须一致");
          }
        }
      }
    }
  }
}