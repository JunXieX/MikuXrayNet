package net.mikumc.mikuxraynet.antixray;

/**
 * 邻近显形的「选择逻辑」：视锥剔除 + 可见性判定的纯计算部分。
 *
 * <p><b>为什么拆出来</b>：「读方块」与「步进射线求交」都需要世界/位图数据源，无法离线单测。因此这里只保留
 * 可离线验证的纯函数：视锥角度计算、方块暴露面识别、候选采样点生成，以及「哪些候选点值得一试」。
 * 生产代码把「遮挡查询」注入为 {@link RayQuery}、把「射线求交」注入为 {@link RayProbe}，
 * 单测注入内存表/内存探针即可覆盖全部判定分支——不再自造体素步进。
 *
 * <p><b>生产注入的唯一实现</b>：由 {@link BitmapVisibility} 注入「遮挡位图查询 + 位图步进射线」，
 * 可整段在工作线程执行（无 Bukkit API）。
 *
 * <p><b>视锥</b>（{@link #withinFrustum}）：{@code fovDegrees} 是<b>竖直方向的张开全角</b>（与 Minecraft
 * 客户端 FOV 设置同义），按客户端的矩形投影分解成两轴判定——竖直半角 = {@code fov/2}，
 * 水平半角 = {@code atan(tan(fov/2) × 宽高比)}（按 21:9 估算，故水平方向明显更宽：
 * fov=110 时竖直半角 55°、水平半角约 73°，覆盖「客户端 FOV 开到上限 110 + 常见超宽屏」的整个可视区，
 * 因此不会剔除玩家看得见的坐标；而旧实现按「圆锥半角 40°」判定，水平方向过窄，
 * 真机日志表现为 87% 的候选被误剔）。距眼睛不超过 {@code minDistance} 时豁免视锥判定。
 * 判定本身「先粗后细」（{@link #withinFrustum} 内联一次点积粗筛 → {@link #preciseWithinFrustum} 两轴精判）：
 * 粗筛是精判接受集的<b>严格超集</b>，只省 worker CPU，逐坐标的显形结果与仅用精判完全一致。
 *
 * <p><b>为什么这里必须「宁可多显形」</b>：被剔除的候选既不发包也不记已显形，因此一次误剔就等于
 * 「玩家明明看得见的方块一直保持伪装」——只有事件显形（挖开/放置触发的方块变更）才不经过本判定，
 * 于是表现为「点一下才变回来」。两个参数在生产配置里都有安全下限（见 {@code AntiXrayConfig} 的
 * {@code FRUSTUM_FOV_FLOOR} / {@code FRUSTUM_MIN_DISTANCE_FLOOR}）。
 *
 * <p><b>可见性</b>（{@link #isVisible}）：<b>多候选点、任一通畅即可见</b>。
 * 旧实现只打「包围盒最近点」一条射线：矿嵌在角落时该点往往正好压在相邻方块那一侧，射线被旁边方块挡住，
 * 于是「明明露着面，却要贴到跟前才显形」。现在：
 * <ol>
 *   <li>先用遮挡表（{@link #exposedFaces}）判出该方块<b>哪些面朝向非遮挡方块</b>。此处的「遮挡」是<b>视觉口径</b>
 *       ——由唯一规则入口 {@code OcclusionRules.visualVerdict} 定义（barrier 判非遮挡、岩浆判遮挡）：
 *       岩浆在 vanilla {@code isOccluding()} 里返回 false，但视觉上不透明，故并入遮挡；水是透明的，不参与。
 *       共享遮挡位图（含实体剔除）现已包含岩浆，因此本判定直接读 {@link RayQuery#isOccluding}。</li>
 *   <li>只在<b>暴露面</b>上取至多 {@link #MAX_SAMPLE_POINTS} 个候选点：
 *       ① 正对玩家（按视线方向选面）的那个暴露面的中心 → ② 包围盒最近点（仅当它确实落在暴露面上）
 *       → ③ 该暴露面四角中离眼睛最近的至多 3 个；</li>
 *   <li>按序做步进射线检测（生产为遮挡位图步进，由 {@link RayProbe} 注入），
 *       任一条通畅即判可见并立即返回；全部被挡才判不可见。</li>
 * </ol>
 * 六面全被遮挡（完全掩埋）时一个采样点都不产生 → 直接判不可见，从根上保证不会「隔着墙还原」。
 *
 * <p>射线起点（眼睛）不在判定范围内、目标方块自身被容忍为「通畅」（端点贴面时的边界情形），
 * 候选点有限，宁可漏判遮挡（多显形）也不误隐藏。
 */
public final class ProximitySelector {

  /** 玩家眼睛与视线方向的不可变快照（只含基本类型，不持有 Player / Location 引用）。 */
  public record Eye(double x, double y, double z, double dirX, double dirY, double dirZ) {
  }

  /** 世界坐标遮挡查询（生产由遮挡位图提供，单测可注入纯内存实现）。 */
  @FunctionalInterface
  public interface RayQuery {

    /**
     * 该坐标是否<b>视觉遮挡</b>（玩家看不穿：含服务端 isOccluding 的方块，以及视觉不透明的岩浆）。
     *
     * <p>「视觉遮挡」由唯一规则入口 {@code OcclusionRules.visualVerdict} 定义（barrier 判非遮挡、
     * 岩浆判遮挡），生产实现读共享遮挡位图，故此处不再叠加任何额外判断。
     */
    boolean isOccluding(int x, int y, int z);
  }

  /**
   * 射线探针：从玩家眼睛射向给定世界坐标点，回答「途中是否通畅」。
   *
   * <p>生产实现由 {@link BitmapVisibility} 注入，内部走遮挡位图的纯内存步进（{@code OcclusionRay}），
   * 因此可在工作线程执行、无需任何 Bukkit API。单测注入内存实现，从而在不依赖 Bukkit 的前提下覆盖
   * 「多候选点、任一通畅即可见」的判定分支。
   */
  @FunctionalInterface
  public interface RayProbe {

    /**
     * 从眼睛射向该点，途中是否未被遮挡方块截住。
     *
     * @return true 表示通畅（射线抵达该点前没有可遮挡的方块）；异常由调用方按「通畅」处理（偏显形）
     */
    boolean isClear(double x, double y, double z);
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

  /**
   * 屏幕宽高比（按 <b>21:9</b> 估算）：客户端水平视场比竖直更宽的依据；服务端拿不到客户端真实宽高比。
   *
   * <p><b>为什么不是 16:9</b>：客户端只上报竖直 FOV，水平半角由宽高比推出。按 16:9 估时，默认 120° 的
   * 水平半角算得 72.0°，而 21:9 屏幕的真实水平半角是 76.1°——差出的 4.1° 正是「屏幕最边缘、且超过
   * {@code proximity.frustum.min-distance}」的一楔形区域：那里的方块已被伪装却不会被显形，
   * 与「玩家看得见的方块一定是真的」这条硬保证冲突（表现为贴边那一条始终是石头，转头才变）。
   * 21:9 覆盖常见超宽屏档位；32:9 的极边缘仍是已知残余（再放宽约一倍视锥面积，性价比不成立）。
   */
  private static final double ASPECT_RATIO = 21.0D / 9.0D;

  /** 水平分量小于该值时认为视线/目标近似正上正下（水平角不可靠，只按竖直轴判定）。 */
  private static final double VERTICAL_LOOK_EPSILON = 0.05D;

  /** 两点去重容差（平方距离）。 */
  private static final double DEDUPE_EPSILON_SQUARED = 1.0E-9D;

  private ProximitySelector() {
  }

  /** 构造眼睛快照；方向向量会被归一化，零向量按「朝向 +Z」处理。 */
  public static Eye eye(double x, double y, double z, double dirX, double dirY, double dirZ) {
    double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
    // length 非有限（NaN / Inf，例如上游读到损坏的朝向）时同样回落 +Z：否则下面的除法会把 NaN
    // 传播进所有角度比较，使视锥判定恒为 false——整片矿永远不显形（玩家对着假石头挖矿）。
    if (!(length >= 1.0E-6D) || !Double.isFinite(length)) {
      return new Eye(x, y, z, 0.0D, 0.0D, 1.0D);
    }
    return new Eye(x, y, z, dirX / length, dirY / length, dirZ / length);
  }

  /**
   * 是否在视野锥内（或在最小距离内豁免）。
   *
   * <p>判定按客户端一致的<b>矩形投影</b>分解成两轴（见类注释）：
   * 竖直方向用与视线的仰角之差 ≤ {@code fov/2}；水平方向用偏航角之差 ≤ 水平半角
   * （{@code atan(tan(fov/2) × ASPECT_RATIO)}，见 {@link #ASPECT_RATIO}）。
   * 视线或目标近似正上/正下时水平角不可靠，只按竖直轴判定。
   *
   * <p><b>先粗后细</b>：精判前先做一次点积粗筛。设视线与目标方向夹角为 θ，精判接受集必满足
   * θ ≤ {@code fov/2} + 水平半角（两轴角差之和是球面角距上界：先沿子午线再沿纬线可走一条长
   * ≤ |Δ仰角|+|Δ偏航| 的折线连接两点，测地距离不超过折线长；视线或目标近似正上/正下时精判跳过
   * 水平轴，两轴角差 ≤ {@code fov/2} 叠加「近似竖直」阈值最多约 5.7°，同样落在上界内）。水平半角恒
   * &lt; 90°，故取更宽松的 {@code R = fov/2 + 90°}：θ &gt; R 必在精判接受集之外，粗筛据此排除
   * 绝不会多剔除任何精判会显形的坐标（严格超集，逐坐标结果不变）。判据（视线已归一化）：
   * θ &gt; R ⇔ {@code dot < −|d|·sin(fov/2)}，只含一次点积 + 一次 {@code sin}。
   * 最小距离豁免、fov 越界（不剔除）、眼位与目标重合一律在粗筛之前放行。
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
    // 方块坐标取中心（+0.5）；实体等任意坐标点走下面的双精度重载，两者共用同一套数学。
    return withinFrustum(eye, blockX + 0.5D, blockY + 0.5D, blockZ + 0.5D, minDistance, fovDegrees);
  }

  /**
   * 同 {@link #withinFrustum(Eye, int, int, int, double, double)}，但目标点为<b>任意双精度坐标</b>。
   *
   * <p>供实体剔除复用同一套视锥数学：实体中心不是方块格中心，直接传实体坐标即可，避免「取整到方块」
   * 带来的判定偏差（取整会让锥边缘的实体结果与真实视线不一致）。
   */
  public static boolean withinFrustum(Eye eye, double x, double y, double z,
      double minDistance, double fovDegrees) {
    if (eye == null) {
      return true;
    }

    // 早退与几何量只算一次，供粗筛与精判共用（避免粗筛反而多算一遍）。
    double dx = x - eye.x();
    double dy = y - eye.y();
    double dz = z - eye.z();

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

    double verticalHalf = Math.toRadians(fovDegrees / 2.0D);

    // 粗筛（先粗后细）：一次点积快速排除「与视线夹角明显超过视锥可接受范围」的候选，见下方法注释。
    // 它是精判接受集的严格超集（只可能放行过多，绝不许多剔除），故逐坐标结果与仅精判完全一致。
    double dot = dx * eye.dirX() + dy * eye.dirY() + dz * eye.dirZ();
    // dot = |d|·cosθ；θ > fov/2 + 90° ⇔ dot < -|d|·sin(fov/2)
    if (dot < -distance * Math.sin(verticalHalf)) {
      return false;
    }

    return preciseWithinFrustumCore(eye, dx, dy, dz, distance, verticalHalf);
  }

  /**
   * 视锥精判的完整参照实现（等价性单测用）：<b>粗筛引入前的旧行为</b>，此处独立保留，单测把
   * {@link #withinFrustum}（粗筛 + 精判）与它逐坐标比对，保证粗筛绝不改变任何判定结果。
   */
  static boolean preciseWithinFrustum(Eye eye, int blockX, int blockY, int blockZ,
      double minDistance, double fovDegrees) {
    return preciseWithinFrustum(eye, blockX + 0.5D, blockY + 0.5D, blockZ + 0.5D, minDistance,
        fovDegrees);
  }

  /** 精判参照点的任意双精度坐标版本（与 {@link #preciseWithinFrustum(Eye, int, int, int, double, double)} 同义）。 */
  static boolean preciseWithinFrustum(Eye eye, double x, double y, double z,
      double minDistance, double fovDegrees) {
    if (eye == null) {
      return true;
    }

    double dx = x - eye.x();
    double dy = y - eye.y();
    double dz = z - eye.z();

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

    return preciseWithinFrustumCore(eye, dx, dy, dz, distance,
        Math.toRadians(fovDegrees / 2.0D));
  }

  /** 精判核心（两轴判定）：入参为已算好的向量与光学量，供 {@link #withinFrustum} 与其参照实现共用。 */
  private static boolean preciseWithinFrustumCore(Eye eye, double dx, double dy, double dz,
      double distance, double verticalHalf) {
    double targetX = dx / distance;
    double targetY = dy / distance;
    double targetZ = dz / distance;

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
   * 识别方块的<b>暴露面</b>：某个面朝向的方向上「不是视觉遮挡方块」（空气/玻璃/植物/水等都算暴露）即为暴露面。
   *
   * <p><b>逐面语义（方案 A）</b>：视觉遮挡用的是 {@link RayQuery#isOccluding}，该口径已由唯一规则入口
   * {@code OcclusionRules.visualVerdict} 把岩浆并入遮挡。因此：
   * <ul>
   *   <li>某面紧邻<b>岩浆</b> → 该面不可见（不计入暴露面，射线也不能穿过它）；</li>
   *   <li>某面紧邻<b>水</b> → 该面仍是暴露面（水透明，玩家应当能看见水下的矿物）；</li>
   *   <li>只有<b>部分面</b>是岩浆 → 只有那些面不可见，其余面照常暴露（比「六面全是岩浆才算」更精确）。</li>
   * </ul>
   *
   * @param query 遮挡查询；为 {@code null} 时保守认为六面全暴露（由后续射线判定兜底）
   * @return 暴露面位掩码；0 表示六面全被视觉遮挡（完全掩埋，从任何方向都看不见）
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
   * <p>候选点只取在暴露面上（顺序见类注释），最多 {@code maxPoints} 个（钳制 1..{@link #MAX_SAMPLE_POINTS}，
   * 即有效上限 5；配置给得再大也会被候选点数截断）。命中即返回。
   * 六面全被遮挡（完全掩埋）时直接判不可见——这是「不得隔着墙还原」的红线，
   * 因为掩埋方块从任何方向都不可能被看到。
   *
   * <p><b>判定失败的方向（用户明确要求「尽可能保证玩家可见性」）</b>：探针抛异常时按<b>「通畅」</b>处理，
   * 即无法判定就显形。取舍是「该显形却因偶发异常漏显形」比「多还原一块」更伤玩家体验——
   * 前者让玩家对着假石头挖矿，后者只是少一个伪装方块（代价见 {@code ProximityRevealer} 的说明）。
   * 注意这只作用于<b>异常/歧义</b>，不改变任何确定性的判定结论（见下）。
   *
   * <p><b>不可重入（与 {@link #visibilityPoints} 共用线程本地 scratch）</b>：本方法先把候选点写进
   * 线程本地的 {@link Scratch}，再逐点回调 {@link RayProbe#isClear}。若某个探针实现在回调里重入调用
   * 本方法或 {@link #visibilityPoints}，内层会覆盖外层正在遍历的候选点（结果被静默改错）。
   * 生产实现（{@code BitmapVisibility.isClear}）只是纯内存步进、<b>不会回调本类</b>，因此现状安全；
   * 扩展本类时必须遵守「探针回调内不得重入」。
   *
   * @param query     遮挡查询；为 {@code null} 时视为可见（fail-open）
   * @param maxPoints 每方块最多尝试的候选点数（由配置 {@code proximity.raycast.samples} 提供）；
   *                  <b>有效上限为 {@link #MAX_SAMPLE_POINTS}=5</b>，超出部分会被候选点数截断
   * @param probe     射线探针；为 {@code null} 时视为可见（fail-open）
   */
  public static boolean isVisible(Eye eye, int blockX, int blockY, int blockZ, RayQuery query,
      int maxPoints, RayProbe probe) {
    if (eye == null || query == null || probe == null) {
      return true;
    }
    int faces = exposedFaces(blockX, blockY, blockZ, query);
    if (faces == 0) {
      // 完全掩埋：没有暴露面，取不到任何采样点 → 不可见（绝不隔着墙还原）
      return false;
    }

    // 热路径（每玩家每周期上百个候选 × 每候选一次）：候选点写进线程本地 scratch，不再逐点 new
    // double[]；可见性结果与 visibilityPoints 逐点一致（二者共用同一套 fillVisibilityPoints）。
    Scratch scratch = SCRATCH.get();
    double[] points = scratch.points;
    int pointCount = fillVisibilityPoints(eye, blockX, blockY, blockZ, faces, scratch);
    int limit = Math.min(Math.max(1, maxPoints), pointCount);
    for (int i = 0; i < limit; i++) {
      int base = i * 3;
      boolean clear;
      try {
        clear = probe.isClear(points[base], points[base + 1], points[base + 2]);
      } catch (Throwable throwable) {
        // 探针异常：无法判定 → 按「通畅」处理（偏显形，方向由用户指定：宁可多还原一块，
        // 也不让玩家对着假石头挖矿）。确定性判定不受影响：路径上有遮挡方块仍算「不通畅」。
        clear = true;
      }
      if (clear) {
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
   * <p><b>不可重入</b>：与 {@link #isVisible} 共用同一份线程本地 {@link Scratch}；不要在
   * {@link RayProbe#isClear} 回调里重入调用本方法或 {@link #isVisible}，否则会覆盖外层正在遍历的
   * 候选点。生产探针不回调本类，故安全（详见 {@link #isVisible} 的说明）。
   *
   * @param faces {@link #exposedFaces} 的结果；0 时返回空数组（不采样）
   */
  public static double[][] visibilityPoints(Eye eye, int blockX, int blockY, int blockZ, int faces) {
    if (eye == null || faces == 0) {
      // 不采样：保持旧的「空数组」契约（调用方/单测据此判 0 个候选点）
      return new double[0][];
    }
    // 与 isVisible 共用同一套计算核心：这里只是把线程本地 scratch 拷成精确长度的数组交给外部
    // （测试/诊断用），因此两条路径的候选点顺序与坐标必然逐点一致。
    Scratch scratch = SCRATCH.get();
    int count = fillVisibilityPoints(eye, blockX, blockY, blockZ, faces, scratch);
    double[][] points = new double[count][];
    for (int i = 0; i < count; i++) {
      points[i] = new double[] {
          scratch.points[i * 3], scratch.points[i * 3 + 1], scratch.points[i * 3 + 2]};
    }
    return points;
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

  /**
   * 线程本地候选点缓冲：{@link #isVisible} 与 {@link #visibilityPoints} 共用的复用容器。
   *
   * <p><b>为什么需要</b>：本类是「每玩家每周期上百个候选 × 每候选一次」的热路径，旧实现每次
   * {@code new ArrayList} + 3~5 个 {@code double[]} + 捕获 {@code eye} 的比较器 lambda + toArray，
   * 一次判定约 9~10 个短命对象。改成线程本地定长数组后热路径零分配。
   *
   * <p><b>线程归属与重入约束</b>：只被 {@link #isVisible} / {@link #visibilityPoints} 同步调用，
   * 也不把 scratch 交给任何异步任务（{@code ProximityRevealer} 只把候选坐标与眼位快照交给工作线程），
   * 因此每个线程各持一份是安全的；两次调用之间会被完全重写，不残留上一次的候选点。
   * <b>禁止在 {@link RayProbe#isClear} 回调里重入 {@link #isVisible} / {@link #visibilityPoints}</b>
   * ——二者共用同一份 scratch，内层调用会覆盖外层正在遍历的候选点。生产探针
   * （{@code BitmapVisibility.isClear}）是纯内存步进、不回调本类，因此现状安全。
   */
  private static final class Scratch {

    /** 候选点（每点 3 个 double：x,y,z），最多 {@link #MAX_SAMPLE_POINTS} 个。 */
    private final double[] points = new double[MAX_SAMPLE_POINTS * 3];
    /** 面四角到眼睛的距离平方（排序键）。 */
    private final double[] cornerDistances = new double[4];
    /** 面四角的稳定排序结果（下标 0..3）。 */
    private final int[] cornerOrder = new int[4];
  }

  private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

  /**
   * 释放当前线程的候选点缓冲（插件停用 / 热重载时由 {@link ProximityRevealer#clearThreadLocals()} 汇入）。
   *
   * <p>ThreadLocal 条目随线程存活并钉住旧类加载器；本缓冲只有 23 个 double/int，单次泄漏量很小，但项目
   * 对 ThreadLocal 复用实例有统一清理纪律（见 {@code ChunkScratch#clearThreadLocals}），这里保持一致。
   */
  static void clearThreadLocals() {
    SCRATCH.remove();
  }

  /**
   * 六个面各自的四个角（相对方块最小角的 {@code dx,dy,dz}），<b>顺序与旧 {@code faceCorners} 完全一致</b>。
   *
   * <p>用静态表代替「每个候选点都 new double[4][3]」：表只建一次，热路径按偏移取值即可。行号由
   * {@link #faceIndex(int)} 给出。
   */
  private static final int[][] FACE_CORNER_OFFSETS = {
      {1, 0, 0, 1, 1, 0, 1, 0, 1, 1, 1, 1}, // FACE_POS_X
      {0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1}, // FACE_NEG_X
      {0, 1, 0, 1, 1, 0, 0, 1, 1, 1, 1, 1}, // FACE_POS_Y
      {0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1}, // FACE_NEG_Y
      {0, 0, 1, 1, 0, 1, 0, 1, 1, 1, 1, 1}, // FACE_POS_Z
      {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0}  // FACE_NEG_Z
  };

  /** 面常量 → {@link #FACE_CORNER_OFFSETS} 的行号（{@link #primaryFace} 只返回已置位的六个面之一）。 */
  private static int faceIndex(int face) {
    return switch (face) {
      case FACE_POS_X -> 0;
      case FACE_NEG_X -> 1;
      case FACE_POS_Y -> 2;
      case FACE_NEG_Y -> 3;
      case FACE_POS_Z -> 4;
      default -> 5;
    };
  }

  /**
   * 候选点计算的核心：把至多 {@link #MAX_SAMPLE_POINTS} 个点写入线程本地 scratch，返回点数。
   *
   * <p>顺序与契约同 {@link #visibilityPoints}：① 正对玩家的暴露面中心 → ② 包围盒最近点（仅当落在
   * 暴露面上）→ ③ 该面四角中离眼睛最近的至多 3 个。{@link #visibilityPoints} 只是把结果拷成
   * {@code double[][]} 给外部，{@link #isVisible} 直接按 scratch 取点——两条路径必然逐点一致。
   */
  private static int fillVisibilityPoints(Eye eye, int blockX, int blockY, int blockZ, int faces,
      Scratch scratch) {
    if (eye == null || faces == 0) {
      return 0;
    }
    int primary = primaryFace(eye, blockX, blockY, blockZ, faces);
    double[] points = scratch.points;
    int count = addScratchPoint(points, 0,
        faceCenterX(blockX, primary), faceCenterY(blockY, primary), faceCenterZ(blockZ, primary));

    double nearestX = clamp(eye.x(), blockX, blockX + 1.0D);
    double nearestY = clamp(eye.y(), blockY, blockY + 1.0D);
    double nearestZ = clamp(eye.z(), blockZ, blockZ + 1.0D);
    if (onExposedFacePoint(nearestX, nearestY, nearestZ, blockX, blockY, blockZ, faces)) {
      count = addScratchPoint(points, count, nearestX, nearestY, nearestZ);
    }

    int[] offsets = FACE_CORNER_OFFSETS[faceIndex(primary)];
    double[] distances = scratch.cornerDistances;
    int[] order = scratch.cornerOrder;
    for (int corner = 0; corner < 4; corner++) {
      order[corner] = corner;
      double dx = (blockX + offsets[corner * 3]) - eye.x();
      double dy = (blockY + offsets[corner * 3 + 1]) - eye.y();
      double dz = (blockZ + offsets[corner * 3 + 2]) - eye.z();
      distances[corner] = dx * dx + dy * dy + dz * dz;
    }
    // 稳定插入排序：与旧实现 Arrays.sort 的稳定性一致（等距时保持 faceCorners 的原始顺序）。
    // 只有 4 个元素，插入排序既无分配也不会破坏既有候选点顺序。
    for (int i = 1; i < 4; i++) {
      int key = order[i];
      double keyDistance = distances[key];
      int j = i - 1;
      while (j >= 0 && distances[order[j]] > keyDistance) {
        order[j + 1] = order[j];
        j--;
      }
      order[j + 1] = key;
    }
    for (int k = 0; k < 4 && count < MAX_SAMPLE_POINTS; k++) {
      int corner = order[k];
      count = addScratchPoint(points, count,
          blockX + offsets[corner * 3],
          blockY + offsets[corner * 3 + 1],
          blockZ + offsets[corner * 3 + 2]);
    }
    return count;
  }

  /** 把点写入 scratch（与已有点重合则跳过），返回新的点数（语义同旧 {@code addPoint}）。 */
  private static int addScratchPoint(double[] points, int count, double x, double y, double z) {
    for (int i = 0; i < count; i++) {
      double dx = points[i * 3] - x;
      double dy = points[i * 3 + 1] - y;
      double dz = points[i * 3 + 2] - z;
      if (dx * dx + dy * dy + dz * dz <= DEDUPE_EPSILON_SQUARED) {
        return count;
      }
    }
    points[count * 3] = x;
    points[count * 3 + 1] = y;
    points[count * 3 + 2] = z;
    return count + 1;
  }

  /** 面中心的 x 分量（只有 ±X 面固定在面平面上，其余取方块中心）。 */
  private static double faceCenterX(int blockX, int face) {
    return switch (face) {
      case FACE_POS_X -> blockX + 1.0D;
      case FACE_NEG_X -> blockX;
      default -> blockX + 0.5D;
    };
  }

  /** 面中心的 y 分量（只有 ±Y 面固定在面平面上，其余取方块中心）。 */
  private static double faceCenterY(int blockY, int face) {
    return switch (face) {
      case FACE_POS_Y -> blockY + 1.0D;
      case FACE_NEG_Y -> blockY;
      default -> blockY + 0.5D;
    };
  }

  /** 面中心的 z 分量（只有 ±Z 面固定在面平面上，其余取方块中心）。 */
  private static double faceCenterZ(int blockZ, int face) {
    return switch (face) {
      case FACE_POS_Z -> blockZ + 1.0D;
      case FACE_NEG_Z -> blockZ;
      default -> blockZ + 0.5D;
    };
  }

  /** 该点是否落在某个暴露面上（贴在 min/max 平面上即算；棱/角上只要有一个暴露面命中即可）。 */
  private static boolean onExposedFacePoint(double x, double y, double z, int blockX, int blockY,
      int blockZ, int faces) {
    return (x <= blockX + 1.0E-9D && (faces & FACE_NEG_X) != 0)
        || (x >= blockX + 1.0D - 1.0E-9D && (faces & FACE_POS_X) != 0)
        || (y <= blockY + 1.0E-9D && (faces & FACE_NEG_Y) != 0)
        || (y >= blockY + 1.0D - 1.0E-9D && (faces & FACE_POS_Y) != 0)
        || (z <= blockZ + 1.0E-9D && (faces & FACE_NEG_Z) != 0)
        || (z >= blockZ + 1.0D - 1.0E-9D && (faces & FACE_POS_Z) != 0);
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