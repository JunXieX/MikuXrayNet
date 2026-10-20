package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import org.junit.jupiter.api.Test;

/**
 * 生产可见性射线（{@link BitmapVisibility} + {@code OcclusionRay} + {@link ProximitySelector#isVisible}）
 * 的真实实现用例：<b>不放任何桩</b>，直接喂真实遮挡位图（{@link OcclusionStore}）走完整判定链路。
 *
 * <p><b>为什么必须补</b>：此前这里只有 {@code ProximitySelectorTest} 的「参照实现」用例（测试内自己
 * 抄了一版射线），生产实现的三条硬语义——<b>眼位所在格与终点格不计入遮挡</b>、<b>多候选点任一通畅
 * 即可见</b>、<b>候选点数量上限 5</b>——没有任何断言；把其中一条改坏（例如把 `length - 1` 改成
 * `length`、或在 isVisible 里只试第一个点），参照实现用例仍然全绿。
 *
 * <p>所有坐标都落在 section (0, 4, 0)（世界 y=64..79、x/z=0..15），因此夹具只需建一段位图；
 * 未建表的 section 由严格规则按「全遮挡」处理，正好用于构造「完全掩埋」的反向对照。
 * 期望值由独立手工步进推得（步长 1、floor(length-1) 次、起点格不检查），不依赖生产内部实现细节。
 */
class BitmapVisibilityProductionRayTest {

  private static final String WORLD = "world";
  /** 合成方块状态 id：只在本夹具里表示「遮挡」。 */
  private static final int OCCLUDING_STATE = 7;
  private static final int SECTION_VOLUME = 16 * 16 * 16;

  /** 眼位（视线朝 +Z，仅用于选择「正对玩家」的暴露面；本类所有夹具的主面都是 -Z 面）。 */
  private static ProximitySelector.Eye eye(double x, double y, double z) {
    return ProximitySelector.eye(x, y, z, 0.0D, 0.0D, 1.0D);
  }

  /** 世界坐标 → (0,4,0) section 内的局部位下标（y<<8|z<<4|x，其中 y 为 section 相对值）。 */
  private static int local(int x, int worldY, int z) {
    return SectionOcclusion.packLocal(x & 15, worldY & 15, z & 15);
  }

  /** 只含指定遮挡格（世界坐标）、其余全不遮挡的 section；保证至少一格，故一定拿到逐格位图。 */
  private static SectionOcclusion sectionOccludingAt(int[]... cells) {
    int[] states = new int[SECTION_VOLUME];
    for (int[] cell : cells) {
      states[local(cell[0], cell[1], cell[2])] = OCCLUDING_STATE;
    }
    SectionOcclusion section = OcclusionStore.buildSection(states, state -> state == OCCLUDING_STATE);
    assertNotNull(section, "夹具必须至少包含一个遮挡格（建表入口永不为 null）");
    assertFalse(section.isEmpty(), "夹具必须包含遮挡格（全空会退化为共享空快路径，测不到逐格判定）");
    return section;
  }

  private static OcclusionStores storesOccludingAt(int[]... cells) {
    OcclusionStores stores = new OcclusionStores();
    stores.of(WORLD).replaceSection(0, 4, 0, sectionOccludingAt(cells));
    return stores;
  }

  private static boolean visible(OcclusionStores stores, ProximitySelector.Eye eyes, int maxPoints,
      int x, int y, int z) {
    return BitmapVisibility.local().isVisible(eyes, stores, WORLD, maxPoints, x, y, z);
  }

  // ---------------------------------------------------------- 眼位 / 终点格排除

  /**
   * 眼位所在格不计入遮挡：玩家眼睛「嵌在」一块石头里时，不能因为起点格是遮挡就把所有东西判成不可见。
   * 反向对照（走廊里再放一块石头）必须判不可见，证明上面的「可见」不是射线根本没跑。
   */
  @Test
  void eyeVoxelNeverCountsAsOccluder() {
    ProximitySelector.Eye eyes = eye(0.5D, 64.5D, 0.5D);
    assertTrue(visible(storesOccludingAt(new int[] {0, 64, 0}), eyes,
            ProximitySelector.MAX_SAMPLE_POINTS, 0, 64, 4),
        "眼位所在格为遮挡时仍必须判可见（起点格不参与射线判定）");
    assertFalse(visible(storesOccludingAt(new int[] {0, 64, 0}, new int[] {0, 64, 2}), eyes,
            ProximitySelector.MAX_SAMPLE_POINTS, 0, 64, 4),
        "走廊中途出现遮挡必须判不可见（反向对照：证明上一条不是射线没跑）");
  }

  /**
   * 终点格不计入遮挡：候选方块自身是遮挡方块时，射线不能把「目标方块本体」算成挡路的墙。
   * 反向对照（目标前方再放一块石头）必须判不可见。
   */
  @Test
  void targetVoxelNeverCountsAsOccluder() {
    ProximitySelector.Eye eyes = eye(0.5D, 64.5D, 0.5D);
    assertTrue(visible(storesOccludingAt(new int[] {0, 64, 4}), eyes,
            ProximitySelector.MAX_SAMPLE_POINTS, 0, 64, 4),
        "目标方块自身遮挡不得把自己判成不可见（终点格不参与射线判定）");
    assertFalse(visible(storesOccludingAt(new int[] {0, 64, 4}, new int[] {0, 64, 2}), eyes,
            ProximitySelector.MAX_SAMPLE_POINTS, 0, 64, 4),
        "目标前方出现遮挡必须判不可见（反向对照）");
  }

  // ---------------------------------------------------------- 多候选点 / 采样上限

  /**
   * 多候选点：<b>任一候选点通畅即可见</b>。夹具只挡住「正对玩家的主面中心」那条射线（候选点 #1），
   * 其余候选点（主面角点/包围盒最近点）仍然通畅，因此默认 5 点必须判可见；只允许 1 个候选点时
   * （maxPoints=1）则必须判不可见——两向断言同时钉住「多点采样确实在跑」与「首点被挡不等于整体不可见」。
   */
  @Test
  void anyClearSamplePointIsEnough() {
    ProximitySelector.Eye eyes = eye(0.0D, 64.0D, 0.0D);
    // 只挡 (1,64,2)：主面中心射线经过它；其余候选点走 (0,64,2) 一侧，不受影响
    OcclusionStores stores = storesOccludingAt(new int[] {1, 64, 2});

    assertTrue(visible(stores, eyes, ProximitySelector.MAX_SAMPLE_POINTS, 2, 64, 6),
        "主面中心被挡、但其它候选点通畅时必须判可见（多候选点任一通畅即可见）");
    assertFalse(visible(stores, eyes, 1, 2, 64, 6),
        "只允许 1 个候选点时只有被挡的主面中心，必须判不可见（证明上一条确实靠多点采样）");

    boolean withFivePoints = visible(stores, eyes, ProximitySelector.MAX_SAMPLE_POINTS, 2, 64, 6);
    assertTrue(withFivePoints == visible(stores, eyes, 100, 2, 64, 6),
        "配置给到 100 也必须钳到 5 个候选点：结果必须与 5 点完全一致");
  }

  /**
   * 候选点数量上限：{@link ProximitySelector#visibilityPoints} 无论暴露几面、眼睛在哪，返回的点数都不得
   * 超过 {@link ProximitySelector#MAX_SAMPLE_POINTS}（=5）。用生产实现生成点集，再断言 maxPoints=5 与
   * 更大值的判定一致（上限之外的点不会被尝试）。
   */
  @Test
  void samplePointsNeverExceedTheConfiguredCap() {
    ProximitySelector.Eye eyes = eye(0.0D, 64.0D, 0.0D);
    OcclusionStores stores = storesOccludingAt(new int[] {1, 64, 2});

    int faces = ProximitySelector.exposedFaces(2, 64, 6, (x, y, z) -> false);
    double[][] points = ProximitySelector.visibilityPoints(eyes, 2, 64, 6, faces);
    assertTrue(points.length > 0, "暴露方块必须生成至少一个候选点");
    assertTrue(points.length <= ProximitySelector.MAX_SAMPLE_POINTS,
        "候选点数不得超过上限 " + ProximitySelector.MAX_SAMPLE_POINTS + "（实际 " + points.length + "）");

    boolean atCap = visible(stores, eyes, ProximitySelector.MAX_SAMPLE_POINTS, 2, 64, 6);
    for (int cap : new int[] {ProximitySelector.MAX_SAMPLE_POINTS, 100, 1000}) {
      assertTrue(atCap == visible(stores, eyes, cap, 2, 64, 6),
          "maxPoints=" + cap + " 与上限 5 的判定结果必须一致（候选点本身只有 ≤5 个）");
    }
  }
}
