package net.mikumc.mikuxraynet.antixray;

import net.mikumc.mikuxraynet.occlusion.OcclusionRay;
import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import net.mikumc.mikuxraynet.occlusion.SolidOcclusion;

/**
 * 邻近显形的「位图可见性判定」：复用反矿透已建好的遮挡位图（{@link OcclusionStores}）在纯内存里回答
 * 「这个坐标对玩家可见吗」，取代原先只能在玩家线程上跑的精确判定（{@code World#rayTraceBlocks} + 6 邻块读）。
 *
 * <p><b>为什么能搬离玩家线程</b>：精确判定要调 Bukkit API（读世界方块、原生射线），只能在玩家所属线程做；
 * 位图判定只读 {@link OcclusionStores}（底层是单写多读容器，读侧无锁）与纯浮点步进
 * （{@link OcclusionRay}），因此可整段交给工作线程（见 {@code ProximityRevealer}），把这两笔 CPU 从主线程 /
 * 区域线程上搬走——那里正是本功能最重、最该省的地方。
 *
 * <p><b>严格缺数据规则（与实体遮挡路径的 fail-open 刻意相反）</b>：位图查不到 section、或该世界还没有位图时
 * 一律按「遮挡」处理（{@link SolidOcclusion#INSTANCE}）。理由：显形与隐藏的代价不对称——隐藏侧
 * 「无数据 = 不遮挡」最坏是少藏一个实体；显形侧「无数据 = 通畅」会让整片尚未建表的区域被一次性还原成
 * 真实方块，等于把矿直接亮给透视端（本功能最严重的失效模式）。缺数据时「不显形」只是延迟到区块建表之后：
 * 该坐标仍留在伪装清单里，后续周期会重试。
 *
 * <p><b>口径</b>：位图由 {@code entityOcclusionTable}（服务端逐状态真值 {@code BlockData#isOccluding()}
 * + 唯一的 barrier 例外）构建。步进射线只统计「遮挡方块」，因此玻璃、蜡烛等
 * 非遮挡方块不再阻断射线（旧实现是「命中任何别的方块即不通畅」）——这一语义变化与用户既定的
 * 「尽可能保证玩家可见性」方向一致。
 *
 * <p><b>与降级路径（{@code ProximityRevealer} 的原生射线）在 barrier 上已统一</b>：本类读的是视觉口径位图
 * （barrier 例外生效 → barrier 不阻断射线）；降级路径的可见性查询也已改走 {@code visualOccluding}（同一
 * barrier 例外）。两条路径对 barrier 的结论因此一致：barrier 后方的方块可见即显形——barrier 对玩家
 * <b>完全不可见</b>，「矿石只被 barrier 挡住」时玩家本就能看穿，显形是正确体验而非泄露。
 *
 * <p><b>分工</b>：位图分不出的「目标上方是流体」规则（需读世界方块）不在本类判，由发布侧
 * （{@code ProximityRevealer} 的玩家线程）补判。
 *
 * <p><b>线程与分配</b>：{@link OcclusionRay} 的游标是实例字段，实例不可跨线程共享；因此本类以
 * {@link ThreadLocal} 每线程一份复用（工作线程与玩家线程各持一份）。一次判定只用可变字段承载上下文，
 * 热路径零分配。
 */
final class BitmapVisibility implements ProximitySelector.RayQuery, ProximitySelector.RayProbe {

  /**
   * 「恒可见」半径的平方（近乎只对「眼位与候选点重合」生效）。
   *
   * <p>取近乎 0 而非「近身豁免 N 格」：本功能的方向是偏显形，但近身豁免会连「贴着墙的整块掩埋矿」一起
   * 放过——那不是看不见，而是玩家一伸手就挖到，属另一回事（由事件显形覆盖）。这里只在起点与终点几乎
   * 重合时短路（无从遮挡）。
   */
  private static final double ALWAYS_VISIBLE_RADIUS_SQUARED = 1.0E-12D;

  private static final ThreadLocal<BitmapVisibility> LOCAL =
      ThreadLocal.withInitial(BitmapVisibility::new);

  private final OcclusionRay ray = new OcclusionRay();
  /** 步进射线的严格查询（缺数据按遮挡，见类注释）；线程封闭，随实例复用。 */
  private final OcclusionRay.Lookup strictLookup = this::strictSection;

  /** 本次判定的遮挡仓库；{@code null} = 该世界尚无位图（全部按遮挡处理）。 */
  private OcclusionStore store;
  private double eyeX;
  private double eyeY;
  private double eyeZ;
  /**
   * 本次判定是否触碰过「无数据」的 section（见类注释的严格规则）。每次判定开始时清零。
   *
   * <p><b>用途</b>：调用方据它把「因缺数据当遮挡而剔除」的候选与「真的被墙挡住」区分开（见
   * {@code ProximityRevealer}）——这是唯一能把「严格规则是否在伤人」变成可读数字的观测口径。
   * 属<b>上界式归因</b>：只在该候选最终判「不可见」且期间触碰过缺数据 section 时才算，因此不会
   * 把「有数据却确实被挡」的剔除混进来，但极少数「既触到缺数据、也确实被墙挡住」的候选会被多算一次。
   */
  private boolean missingDataConsulted;

  private BitmapVisibility() {
  }

  /** 取当前线程的复用实例（工作线程与玩家线程各持一份，互不共享内部游标）。 */
  static BitmapVisibility local() {
    return LOCAL.get();
  }

  /**
   * 判定坐标 {@code (x, y, z)} 对 {@code eye} 是否可见（多候选点、任一通畅即可见）。
   *
   * <p>不使用「上方流体」规则（{@code fluidCover=false}）——它需要读世界方块，只能在玩家线程补判。
   *
   * @param stores    遮挡位图注册表；{@code null} 时全部按遮挡处理（严格）
   * @param worldName 该坐标所属世界名（位图按世界分域，见 {@link OcclusionStores}）
   * @param maxPoints 每方块最多尝试的候选点数（有效上限见 {@link ProximitySelector#MAX_SAMPLE_POINTS}）
   */
  boolean isVisible(ProximitySelector.Eye eye, OcclusionStores stores, String worldName,
      int maxPoints, int x, int y, int z) {
    // 归因标记必须在任何早退之前清零：否则 eye==null 的早退分支会保留上一次判定的取值，
    // 调用方随后读 missingDataConsulted() 就会把上一轮的「触碰过缺数据」误算到本轮（归因失真）。
    this.missingDataConsulted = false;
    if (eye == null) {
      return true;
    }
    this.store = stores == null ? null : stores.lookup(worldName);
    this.eyeX = eye.x();
    this.eyeY = eye.y();
    this.eyeZ = eye.z();
    return ProximitySelector.isVisible(eye, x, y, z, this, maxPoints, false, this);
  }

  /** 本次判定是否触碰过「无数据」的 section（仅在 {@link #isVisible} 之后读取才有效，见字段说明）。 */
  boolean missingDataConsulted() {
    return missingDataConsulted;
  }

  /**
   * 暴露面判定用的遮挡查询：<b>严格</b>——查不到 section（该段无位图数据）即视为遮挡，
   * 于是「无数据」的方块一个暴露面都取不到，直接判不可见（见类注释）。
   */
  @Override
  public boolean isOccluding(int x, int y, int z) {
    SectionOcclusion section = store == null ? null : store.section(x >> 4, y >> 4, z >> 4);
    if (section == null) {
      missingDataConsulted = true;
      return true;
    }
    return section.isOccluding(x, y, z);
  }

  /**
   * 候选点射线：从眼睛步进到候选点，途中出现任何遮挡方块即判「不通畅」（{@code maxOccluding=0}，
   * <b>最严格</b>）。终点自身所在格子不参与判定（见 {@link OcclusionRay}），因此候选点落在目标方块
   * 表面上时不会把目标方块本身算成遮挡。
   */
  @Override
  public boolean isClear(double pointX, double pointY, double pointZ) {
    return ray.hasLineOfSight(strictLookup, eyeX, eyeY, eyeZ, pointX, pointY, pointZ, 0,
        ALWAYS_VISIBLE_RADIUS_SQUARED, Double.MAX_VALUE);
  }

  /** 步进射线的严格 section 查询：缺数据 → 全遮挡单例（{@code null} 会被射线按「不遮挡」处理）。 */
  private SectionOcclusion strictSection(int chunkX, int sectionY, int chunkZ) {
    SectionOcclusion section = store == null ? null : store.section(chunkX, sectionY, chunkZ);
    if (section == null) {
      missingDataConsulted = true;
      return SolidOcclusion.INSTANCE;
    }
    return section;
  }
}
