package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.mikumc.mikuxraynet.bandwidth.OcclusionRaytracer;

/**
 * 邻近显形的「选择逻辑」：视锥剔除 + 可见性判定的纯计算部分。
 *
 * <p><b>为什么拆出来</b>：「读方块」需要 Bukkit 世界对象，无法离线单测。因此这里只保留可离线验证的纯函数：
 * 视锥角度计算、方块暴露面识别、候选采样点生成、射线体素路径，以及「给定遮挡查询时路径是否被挡住」。
 * 生产代码把「读世界方块」注入为 {@link RayQuery}，单测注入内存表即可覆盖全部判定分支。
 *
 * <p><b>视锥</b>（{@link #withinFrustum}）：{@code fovDegrees} 是<b>竖直方向的张开全角</b>（与 Minecraft
 * 客户端 FOV 设置同义），按客户端的矩形投影分解成两轴判定——竖直半角 = {@code fov/2}，
 * 水平半角 = {@code atan(tan(fov/2) × 宽高比)}（按 16:9 估算，故水平方向明显更宽：
 * fov=80 时竖直半角 40°、水平半角约 56°，而旧实现按「圆锥半角 40°」判定，水平方向过窄，
 * 真机日志表现为 87% 的候选被误剔）。距眼睛不超过 {@code minDistance} 时豁免视锥判定。
 *
 * <p><b>可见性</b>（{@link #isVisible}）：<b>多候选点、任一通畅即可见</b>。
 * 旧实现只打「包围盒最近点」一条射线：矿嵌在角落时该点往往正好压在相邻方块那一侧，射线被旁边方块挡住，
 * 于是「明明露着面，却要贴到跟前才显形」。现在：
 * <ol>
 *   <li>先用遮挡表（{@link #exposedFaces}）判出该方块<b>哪些面朝向非遮挡方块</b>；</li>
 *   <li>只在<b>暴露面</b>上取至多 {@link #MAX_SAMPLE_POINTS} 个候选点：
 *       ① 正对玩家（按视线方向选面）的那个暴露面的中心 → ② 包围盒最近点（仅当它确实落在暴露面上）
 *       → ③ 该暴露面四角中离眼睛最近的至多 3 个；</li>
 *   <li>按序做体素步进，任一条通畅即判可见并立即返回；全部被挡才判不可见。</li>
 * </ol>
 * 六面全被遮挡（完全掩埋）时一个采样点都不产生 → 直接判不可见，从根上保证不会「隔着墙还原」。
 *
 * <p>射线起点（眼睛）与终点所在体素都会被排除，且目标方块自身绝不作为遮挡物，避免自遮挡。
 * 采样数有限，宁可漏判遮挡（多显形）也不误隐藏。
 */
public final class ProximitySelector {

  /** 玩家眼睛与视线方向的不可变快照（只含基本类型，不持有 Player / Location 引用）。 */
  public record Eye(double x, double y, double z, double dirX, double dirY, double dirZ) {
  }

  /** 世界坐标遮挡查询（显形流程用主线程读世界方块，单测可注入纯内存实现）。 */
  @FunctionalInterface
  public interface RayQuery extends OcclusionRaytracer.OcclusionQuery {
  }

  /** 各正交面的位掩码（{@link #exposedFaces} 的返回值）。 */
  public static final int FACE_POS_X = 1 << 0;
  public static final int FACE_NEG_X = 1 << 1;
  public static final int FACE_POS_Y = 1 << 2;
  public static final int FACE_NEG_Y = 1 << 3;
  public static final int FACE_POS_Z = 1 << 4;
  public static final int FACE_NEG_Z = 1 << 5;

  /**
   * 六个面全暴露的掩码。
   *
   * <p>测试专用豁免：生产路径只在 {@link #exposedFaces} 内部用作「无遮挡表」时的返回值，
   * 无外部生产调用方；保留为公开常量以免破坏单测。
   */
  public static final int ALL_FACES =
      FACE_POS_X | FACE_NEG_X | FACE_POS_Y | FACE_NEG_Y | FACE_POS_Z | FACE_NEG_Z;

  /** 单方块最多采样的候选点数（① 面中心 + ② 最近点 + ③ 至多 3 个面角）。 */
  public static final int MAX_SAMPLE_POINTS = 5;

  /** 面判定顺序（固定，保证同输入同输出）。 */
  private static final int[] FACE_ORDER = {
      FACE_POS_X, FACE_NEG_X, FACE_POS_Y, FACE_NEG_Y, FACE_POS_Z, FACE_NEG_Z};

  /** 屏幕宽高比（按 16:9 估算）：客户端水平视场比竖直更宽的依据；服务端拿不到客户端真实宽高比。 */
  private static final double ASPECT_RATIO = 16.0D / 9.0D;

  /** 水平分量小于该值时认为视线/目标近似正上正下（水平角不可靠，只按竖直轴判定）。 */
  private static final double VERTICAL_LOOK_EPSILON = 0.05D;

  /** 两点去重容差（平方距离）。 */
  private static final double DEDUPE_EPSILON_SQUARED = 1.0E-9D;

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
   * <p>判定按客户端一致的<b>矩形投影</b>分解成两轴（见类注释）：
   * 竖直方向用与视线的仰角之差 ≤ {@code fov/2}；水平方向用偏航角之差 ≤ 水平半角
   * （{@code atan(tan(fov/2) × 16/9)}）。视线或目标近似正上/正下时水平角不可靠，只按竖直轴判定。
   *
   * @param eye                  眼睛与视线方向
   * @param blockX               目标方块 X（取方块中心参与计算）
   * @param blockY               目标方块 Y
   * @param blockZ               目标方块 Z
   * @param minDistance          不超过该距离时直接豁免视锥判定
   * @param fovDegrees           竖直方向的视野张开<b>全角</b>（度）；{@code <=0} 或 {@code >=180} 时视为不做视锥剔除
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

    double targetX = dx / distance;
    double targetY = dy / distance;
    double targetZ = dz / distance;

    double verticalHalf = Math.toRadians(fovDegrees / 2.0D);

    // ① 竖直轴：目标仰角与视线仰角之差不超过竖直半角（矩形投影的上下边界）
    double pitchDifference =
        Math.abs(Math.asin(clampUnit(targetY)) - Math.asin(clampUnit(eye.dirY())));
    if (pitchDifference > verticalHalf) {
      return false;
    }

    // ② 水平轴：偏航角之差不超过水平半角（宽高比让水平视场比竖直更宽）
    double viewHorizontal = Math.hypot(eye.dirX(), eye.dirZ());
    double targetHorizontal = Math.hypot(targetX, targetZ);
    if (viewHorizontal < VERTICAL_LOOK_EPSILON || targetHorizontal < VERTICAL_LOOK_EPSILON) {
      // 近似正上/正下看或目标近似正上/正下：水平角不可靠，竖直轴判定已足够
      return true;
    }
    double horizontalHalf = Math.atan(Math.tan(verticalHalf) * ASPECT_RATIO);
    double yawDifference = Math.abs(normalizeAngle(
        Math.atan2(targetX, targetZ) - Math.atan2(eye.dirX(), eye.dirZ())));
    return yawDifference <= horizontalHalf;
  }

  /**
   * 从眼睛到目标方块「中心」的射线体素路径（不含起点与终点所在体素）。
   *
   * <p>中心射线更严格：它要求玩家能直视方块中心，因而更适合需要「完整可见」判定的场合。
   * 显形判定请用 {@link #isVisible}——矿几乎总是嵌在岩石里，到中心的射线会先钻进
   * 相邻岩石，把「明明看得到」的裸露矿误判为被遮挡。
   *
   * <p>测试专用豁免：生产显形判定走 {@link #isVisible} 的多候选点采样，本方法当前仅单测在用，
   * 保留以免破坏测试。
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
   * 从眼睛到目标方块<b>最近点</b>（包围盒上离眼睛最近的那一点，即朝向玩家一侧的面/棱/角）的射线体素路径。
   *
   * <p><b>为什么不用方块中心</b>：矿几乎总是嵌在岩石里（裸露 = 某一面朝着空气），而到中心的射线必须
   * 先穿过紧贴该面的岩石才能抵达中心，于是那些岩石被当成遮挡物——表现为「看得到的裸露矿永不显形」。
   * 打到最近点则让射线沿着朝向玩家的可见面走，只有真正挡在前面的方块才会被判为遮挡。
   *
   * <p>注意：这只是「单点」采样，角落场景仍会被旁边方块挡住；生产路径已改用 {@link #isVisible} 的
   * 多候选点判定，本方法保留给需要单点语义的场合与离线测试。
   *
   * <p>测试专用豁免：生产显形判定走 {@link #isVisible}，本方法当前仅单测在用，保留以免破坏测试。
   *
   * <p>起点（眼睛）与终点所在体素由 {@link OcclusionRaytracer#voxelPath} 排除，因此贴墙、站在
   * 方块棱角上、甚至眼位就在目标方块内（此时长度 0 → 空路径 → 视为可见）都能正确退化。
   *
   * @param maxSamples 最多采样数（越小越省主线程读方块次数，但可能漏判薄墙）
   */
  public static int[] visibilityPath(Eye eye, int blockX, int blockY, int blockZ, int maxSamples) {
    if (eye == null) {
      return new int[0];
    }
    // 把眼睛坐标夹进方块包围盒 → 得到「离眼睛最近的点」；眼睛本就在方块内时即眼睛自身
    double[] nearest = nearestPoint(eye, blockX, blockY, blockZ);
    return OcclusionRaytracer.voxelPath(eye.x(), eye.y(), eye.z(),
        nearest[0], nearest[1], nearest[2], maxSamples);
  }

  /**
   * 给定遮挡查询，判定射线路径是否被挡住。
   *
   * <p>实现下沉到 {@link OcclusionRaytracer#isPathOccluded}（与实体剔除的遮挡循环共用同一份
   * 纯函数，避免两处实现漂移）；本方法保留原签名供既有调用方与单测使用。
   *
   * <p>测试专用豁免：生产路径只有 {@link #isVisible} 内部复用它做逐路径判定，无其它生产调用方；
   * 保留为公开方法以免破坏单测。
   *
   * @param path  {@link #visibilityPath}（或 {@link #rayPath}）返回的扁平坐标数组（x,y,z 依次排列）
   * @param query 遮挡查询；为 {@code null} 时不做判定（返回 false，即视为可见）
   * @return 是否被遮挡；路径为空时恒为 false（贴得太近，视为可见）
   */
  public static boolean isRayOccluded(int[] path, RayQuery query) {
    return OcclusionRaytracer.isPathOccluded(path, query);
  }

  /**
   * 识别方块的<b>暴露面</b>：某个面朝向的方向上「不是遮挡方块」（空气/玻璃/植物等都算暴露）即为暴露面。
   *
   * @param query 遮挡查询；为 {@code null} 时保守认为六面全暴露（由后续射线判定兜底）
   * @return 暴露面位掩码；0 表示六面全被遮挡（完全掩埋，从任何方向都看不见）
   */
  public static int exposedFaces(int blockX, int blockY, int blockZ, RayQuery query) {
    if (query == null) {
      return ALL_FACES;
    }
    int mask = 0;
    if (!query.isOccluding(blockX + 1, blockY, blockZ)) {
      mask |= FACE_POS_X;
    }
    if (!query.isOccluding(blockX - 1, blockY, blockZ)) {
      mask |= FACE_NEG_X;
    }
    if (!query.isOccluding(blockX, blockY + 1, blockZ)) {
      mask |= FACE_POS_Y;
    }
    if (!query.isOccluding(blockX, blockY - 1, blockZ)) {
      mask |= FACE_NEG_Y;
    }
    if (!query.isOccluding(blockX, blockY, blockZ + 1)) {
      mask |= FACE_POS_Z;
    }
    if (!query.isOccluding(blockX, blockY, blockZ - 1)) {
      mask |= FACE_NEG_Z;
    }
    return mask;
  }

  /**
   * 方块是否对玩家可见：<b>多候选点、任一通畅即可见</b>。
   *
   * <p>候选点只取在暴露面上（顺序见类注释），最多 {@link #MAX_SAMPLE_POINTS} 个；命中即返回。
   * 六面全被遮挡（完全掩埋）时直接判不可见——这是「不得隔着墙还原」的红线，
   * 因为掩埋方块从任何方向都不可能被看到。
   *
   * @param query      遮挡查询；为 {@code null} 时视为可见（fail-open）
   * @param maxSamples 每条射线的最大采样体素数
   */
  public static boolean isVisible(Eye eye, int blockX, int blockY, int blockZ, RayQuery query,
      int maxSamples) {
    if (eye == null || query == null) {
      return true;
    }
    int faces = exposedFaces(blockX, blockY, blockZ, query);
    if (faces == 0) {
      // 完全掩埋：没有暴露面，取不到任何采样点 → 不可见（绝不隔着墙还原）
      return false;
    }

    // 目标方块自身绝不作为遮挡物（眼位与方块同格/贴面时射线可能擦过自身体素）
    RayQuery effective = (x, y, z) -> !(x == blockX && y == blockY && z == blockZ)
        && query.isOccluding(x, y, z);

    double[][] points = visibilityPoints(eye, blockX, blockY, blockZ, faces);
    for (double[] point : points) {
      int[] path = OcclusionRaytracer.voxelPath(eye.x(), eye.y(), eye.z(),
          point[0], point[1], point[2], maxSamples);
      if (!isRayOccluded(path, effective)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 生成候选采样点（最多 {@link #MAX_SAMPLE_POINTS} 个，按命中概率从高到低排列）。
   *
   * <p>顺序：① 正对玩家的那个暴露面的中心（按玩家视线方向选面）→ ② 包围盒最近点
   * （仅当它确实落在暴露面上）→ ③ 该暴露面四角中离眼睛最近的至多 3 个。
   *
   * @param faces {@link #exposedFaces} 的结果；0 时返回空数组（不采样）
   */
  public static double[][] visibilityPoints(Eye eye, int blockX, int blockY, int blockZ, int faces) {
    List<double[]> points = new ArrayList<>(MAX_SAMPLE_POINTS);
    if (eye == null || faces == 0) {
      return new double[0][];
    }

    int primary = primaryFace(eye, blockX, blockY, blockZ, faces);
    addPoint(points, faceCenter(blockX, blockY, blockZ, primary));

    double[] nearest = nearestPoint(eye, blockX, blockY, blockZ);
    if (onExposedFace(nearest, blockX, blockY, blockZ, faces)) {
      addPoint(points, nearest);
    }

    double[][] corners = faceCorners(blockX, blockY, blockZ, primary);
    sortByDistanceToEye(corners, eye);
    for (double[] corner : corners) {
      if (points.size() >= MAX_SAMPLE_POINTS) {
        break;
      }
      addPoint(points, corner);
    }
    return points.toArray(new double[0][]);
  }

  /** 选「正对玩家」的暴露面：优先取法线与视线方向最相反者，相同则取更靠近眼睛的那个面。 */
  private static int primaryFace(Eye eye, int blockX, int blockY, int blockZ, int faces) {
    double toEyeX = eye.x() - (blockX + 0.5D);
    double toEyeY = eye.y() - (blockY + 0.5D);
    double toEyeZ = eye.z() - (blockZ + 0.5D);

    int best = 0;
    double bestAlign = -Double.MAX_VALUE;
    double bestProximity = -Double.MAX_VALUE;
    for (int face : FACE_ORDER) {
      if ((faces & face) == 0) {
        continue;
      }
      double nx = normal(face, 0);
      double ny = normal(face, 1);
      double nz = normal(face, 2);
      // align 越大 = 该面越正对玩家的视线方向
      double align = -(nx * eye.dirX() + ny * eye.dirY() + nz * eye.dirZ());
      // 同精度时取「面朝向眼睛」程度更高者（更近、更可能被看到）
      double proximity = nx * toEyeX + ny * toEyeY + nz * toEyeZ;
      if (best == 0 || align > bestAlign + 1.0E-9D
          || (Math.abs(align - bestAlign) <= 1.0E-9D && proximity > bestProximity)) {
        best = face;
        bestAlign = align;
        bestProximity = proximity;
      }
    }
    return best;
  }

  /** 面的外法线分量（axis：0=x，1=y，2=z）。 */
  private static double normal(int face, int axis) {
    int sign = (face & (FACE_POS_X | FACE_POS_Y | FACE_POS_Z)) != 0 ? 1 : -1;
    int faceAxis = switch (face) {
      case FACE_POS_X, FACE_NEG_X -> 0;
      case FACE_POS_Y, FACE_NEG_Y -> 1;
      default -> 2;
    };
    return faceAxis == axis ? sign : 0.0D;
  }

  /** 面中心：只改动法线那一轴的坐标。 */
  private static double[] faceCenter(int blockX, int blockY, int blockZ, int face) {
    double x = blockX + 0.5D;
    double y = blockY + 0.5D;
    double z = blockZ + 0.5D;
    switch (face) {
      case FACE_POS_X -> x = blockX + 1.0D;
      case FACE_NEG_X -> x = blockX;
      case FACE_POS_Y -> y = blockY + 1.0D;
      case FACE_NEG_Y -> y = blockY;
      case FACE_POS_Z -> z = blockZ + 1.0D;
      default -> z = blockZ;
    }
    return new double[] {x, y, z};
  }

  /** 面四角（3 维坐标，其中一轴固定在面平面上）。 */
  private static double[][] faceCorners(int blockX, int blockY, int blockZ, int face) {
    double x0 = blockX;
    double y0 = blockY;
    double z0 = blockZ;
    double x1 = blockX + 1.0D;
    double y1 = blockY + 1.0D;
    double z1 = blockZ + 1.0D;
    return switch (face) {
      case FACE_POS_X -> new double[][] {
          {x1, y0, z0}, {x1, y1, z0}, {x1, y0, z1}, {x1, y1, z1}};
      case FACE_NEG_X -> new double[][] {
          {x0, y0, z0}, {x0, y1, z0}, {x0, y0, z1}, {x0, y1, z1}};
      case FACE_POS_Y -> new double[][] {
          {x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}, {x1, y1, z1}};
      case FACE_NEG_Y -> new double[][] {
          {x0, y0, z0}, {x1, y0, z0}, {x0, y0, z1}, {x1, y0, z1}};
      case FACE_POS_Z -> new double[][] {
          {x0, y0, z1}, {x1, y0, z1}, {x0, y1, z1}, {x1, y1, z1}};
      default -> new double[][] {
          {x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
    };
  }

  /** 包围盒上离眼睛最近的点（把眼睛坐标夹进包围盒）。 */
  private static double[] nearestPoint(Eye eye, int blockX, int blockY, int blockZ) {
    return new double[] {
        clamp(eye.x(), blockX, blockX + 1.0D),
        clamp(eye.y(), blockY, blockY + 1.0D),
        clamp(eye.z(), blockZ, blockZ + 1.0D)};
  }

  /** 该点是否落在某个暴露面上（贴在 min/max 平面上即算；棱/角上只要有一个暴露面命中即可）。 */
  private static boolean onExposedFace(double[] point, int blockX, int blockY, int blockZ,
      int faces) {
    return (point[0] <= blockX + 1.0E-9D && (faces & FACE_NEG_X) != 0)
        || (point[0] >= blockX + 1.0D - 1.0E-9D && (faces & FACE_POS_X) != 0)
        || (point[1] <= blockY + 1.0E-9D && (faces & FACE_NEG_Y) != 0)
        || (point[1] >= blockY + 1.0D - 1.0E-9D && (faces & FACE_POS_Y) != 0)
        || (point[2] <= blockZ + 1.0E-9D && (faces & FACE_NEG_Z) != 0)
        || (point[2] >= blockZ + 1.0D - 1.0E-9D && (faces & FACE_POS_Z) != 0);
  }

  /** 加入候选点，跳过与已有点重合者（避免重复射线）。 */
  private static void addPoint(List<double[]> points, double[] point) {
    for (double[] existing : points) {
      double dx = existing[0] - point[0];
      double dy = existing[1] - point[1];
      double dz = existing[2] - point[2];
      if (dx * dx + dy * dy + dz * dz <= DEDUPE_EPSILON_SQUARED) {
        return;
      }
    }
    points.add(point);
  }

  private static void sortByDistanceToEye(double[][] points, Eye eye) {
    Arrays.sort(points, (left, right) -> Double.compare(
        squaredDistance(left, eye), squaredDistance(right, eye)));
  }

  private static double squaredDistance(double[] point, Eye eye) {
    double dx = point[0] - eye.x();
    double dy = point[1] - eye.y();
    double dz = point[2] - eye.z();
    return dx * dx + dy * dy + dz * dz;
  }

  private static double clamp(double value, double min, double max) {
    return Math.min(Math.max(value, min), max);
  }

  private static double clampUnit(double value) {
    return Math.min(Math.max(value, -1.0D), 1.0D);
  }

  /** 角度归一到 (-π, π]，用于偏航角之差。 */
  private static double normalizeAngle(double radians) {
    double wrapped = radians % (2.0D * Math.PI);
    if (wrapped > Math.PI) {
      wrapped -= 2.0D * Math.PI;
    } else if (wrapped <= -Math.PI) {
      wrapped += 2.0D * Math.PI;
    }
    return wrapped;
  }
}