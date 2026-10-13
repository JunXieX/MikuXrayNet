package net.mikumc.mikuxraynet.occlusion;

/**
 * 纯内存步进射线：沿一条线段逐格前进，统计途中「遮挡」的方块数，据此判定是否被完全挡住。
 *
 * <p><b>为什么是步进而不是 DDA</b>：步进（每步固定 1 格长）比 DDA 更快，且实现简单、无浮点边界
 * 累积误差。代价是<b>可能漏采样斜穿角落的格子</b>——本算法刻意「偏向可见」（漏格 = 少算一次遮挡 =
 * 更可能判定为通畅）。对「决定是否隐藏实体」这一用途，偏可见是安全方向（宁可多显示、不可误藏）。
 *
 * <p><b>判定语义</b>：{@code maxOccluding} 是「允许穿过的最大遮挡方块数」。途中遮挡数超过它即判
 * 「不通畅」（{@code false}）；否则判「通畅」（{@code true}）。取 {@code maxOccluding = 0} 即
 * 「路径上不能有任何遮挡方块」——最严格，是本项目接线时的默认取值。
 *
 * <p><b>数据来源</b>：{@link Lookup}（生产由 {@link OcclusionStore} 实现）。查不到 section
 * （返回 {@code null}）按「该处不遮挡」处理，与「整段全不遮挡」等价。
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

  /** 步进起点（浮点，通常为观察者眼睛坐标）。 */
  private double startX;
  private double startY;
  private double startZ;
  /** 单位方向向量（步长恰为 1 格）。 */
  private double directionX;
  private double directionY;
  private double directionZ;
  /** 当前所在 section 坐标。 */
  private int chunkX;
  private int sectionY;
  private int chunkZ;
  /** 当前所在 section 的遮挡视图；{@code null} = 该段无数据（按不遮挡）。 */
  private SectionOcclusion view;
  private Lookup lookup;
  /** 途中累计的遮挡方块数。 */
  private int occluded;

  /** 步进游标位置（内部复用，{@link #advance()} 逐格推进）。 */
  private double cursorX;
  private double cursorY;
  private double cursorZ;

  /**
   * 判定从起点到终点是否通畅（能看到）。
   *
   * @param lookup                   遮挡数据来源
   * @param startX/Y/Z               起点（观察者眼睛）
   * @param endX/Y/Z                 终点（目标点）
   * @param maxOccluding             允许穿过的最大遮挡方块数（0 = 最严格）
   * @param alwaysVisibleRadiusSquared 「恒可见」半径的平方：起点到终点距离不超过它时直接判通畅，
   *                                  不做任何步进（用于近身豁免）
   * @param maxRadiusSquared         最远射线距离的平方：超过它直接判不通畅（不步进）
   * @return true = 通畅（未被完全遮挡）
   */
  public boolean hasLineOfSight(Lookup lookup, double startX, double startY, double startZ,
      double endX, double endY, double endZ, int maxOccluding,
      double alwaysVisibleRadiusSquared, double maxRadiusSquared) {
    this.lookup = lookup;
    this.startX = startX;
    this.startY = startY;
    this.startZ = startZ;

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
    double scale = 1 / length;
    directionX = dx * scale;
    directionY = dy * scale;
    directionZ = dz * scale;

    // 终点自身所在格子不参与判定（减 1 格），避免把「目标方块本体」算成遮挡
    int remainingSteps = (int) Math.floor(length - 1);

    cursorX = startX;
    cursorY = startY;
    cursorZ = startZ;
    occluded = 0;
    setupSection((int) Math.floor(cursorX), (int) Math.floor(cursorY), (int) Math.floor(cursorZ));

    while (remainingSteps >= 4) {
      advance();
      advance();
      advance();
      advance();
      if (occluded > maxOccluding) {
        return false;
      }
      remainingSteps -= 4;
    }
    while (remainingSteps-- > 0) {
      advance();
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

  private void advance() {
    cursorX += directionX;
    cursorY += directionY;
    cursorZ += directionZ;

    int blockX = (int) Math.floor(cursorX);
    int blockY = (int) Math.floor(cursorY);
    int blockZ = (int) Math.floor(cursorZ);

    if (!isCurrentSection(blockX, blockY, blockZ)) {
      setupSection(blockX, blockY, blockZ);
    }
    if (view != null && view.isOccluding(blockX, blockY, blockZ)) {
      occluded++;
    }
  }

  /** 所处的 section 是否未变（用异或聚合判断，比逐项短路比较更快）。 */
  private boolean isCurrentSection(int blockX, int blockY, int blockZ) {
    return ((blockX >> 4) ^ chunkX) == 0 && ((blockY >> 4) ^ sectionY) == 0
        && ((blockZ >> 4) ^ chunkZ) == 0;
  }

  /** 切换到指定方块坐标所属的 section，并取该段的遮挡视图（可能为 {@code null}）。 */
  private void setupSection(int blockX, int blockY, int blockZ) {
    chunkX = blockX >> 4;
    sectionY = blockY >> 4;
    chunkZ = blockZ >> 4;
    view = lookup == null ? null : lookup.section(chunkX, sectionY, chunkZ);
  }
}
