package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import org.junit.jupiter.api.Test;

/**
 * 「把正在挖的那一格视为空气」的可见性判定（{@link BitmapVisibility#beginIgnoring}）用例：
 * 挖掘预显形（{@code ProximityRevealer#onBlockDamage}）依赖它——否则射线永远被正在挖的方块挡住，
 * 邻接的伪装坐标一个都不会被提前还原。
 *
 * <p>夹具沿用 {@code BitmapVisibilityProductionRayTest}：所有坐标落在 section (0,4,0)，未建表 section
 * 由严格规则按「全遮挡」处理。期望值手工推得：眼位 (0.5,64.5,0.5) 朝 +Z，目标 (0,64,4) 的 -Z 面上
 * 的候选点在 z=2 处必须穿过一整片 2×2 挡墙；把其中一格视为空气后，面中心点那条射线才通畅。
 */
class BitmapVisibilityIgnoringTest {

  private static final String WORLD = "world";
  private static final int OCCLUDING_STATE = 7;
  private static final int SECTION_VOLUME = 16 * 16 * 16;

  private static ProximitySelector.Eye eye(double x, double y, double z) {
    return ProximitySelector.eye(x, y, z, 0.0D, 0.0D, 1.0D);
  }

  private static int local(int x, int worldY, int z) {
    return SectionOcclusion.packLocal(x & 15, worldY & 15, z & 15);
  }

  private static OcclusionStores storesOccludingAt(int[]... cells) {
    int[] states = new int[SECTION_VOLUME];
    for (int[] cell : cells) {
      states[local(cell[0], cell[1], cell[2])] = OCCLUDING_STATE;
    }
    SectionOcclusion section = OcclusionStore.buildSection(states, state -> state == OCCLUDING_STATE);
    assertNotNull(section, "夹具必须能建出位图");
    assertFalse(section.isEmpty(), "夹具必须包含遮挡格，否则退化为共享空快照");
    OcclusionStores stores = new OcclusionStores();
    stores.of(WORLD).replaceSection(0, 4, 0, section);
    return stores;
  }

  @Test
  void diggingBlockIsTreatedAsAirForVisibility() {
    // z=2 处一整片 2×2 挡墙：目标 (0,64,4) 的 -Z 面上任何候选点都要穿过它
    OcclusionStores stores = storesOccludingAt(
        new int[] {0, 64, 2}, new int[] {1, 64, 2}, new int[] {0, 65, 2}, new int[] {1, 65, 2});
    ProximitySelector.Eye eyes = eye(0.5D, 64.5D, 0.5D);
    BitmapVisibility bitmap = BitmapVisibility.local();

    assertFalse(bitmap.isVisible(eyes, stores, WORLD, ProximitySelector.MAX_SAMPLE_POINTS, 0, 64, 4),
        "挖开之前：射线被 (0,64,2) 挡住，必须判不可见（证明下面不是射线没跑）");

    bitmap.beginIgnoring(0, 64, 2);
    try {
      assertTrue(bitmap.isVisible(eyes, stores, WORLD, ProximitySelector.MAX_SAMPLE_POINTS, 0, 64, 4),
          "把正在挖的方块视为空气后，挖开就会看见，必须判可见");
    } finally {
      bitmap.endIgnoring();
    }

    assertFalse(bitmap.isVisible(eyes, stores, WORLD, ProximitySelector.MAX_SAMPLE_POINTS, 0, 64, 4),
        "endIgnoring 之后必须回到严格口径（绝不残留忽略状态）");
  }

  /**
   * 忽略只作用于「那一格」：在更靠近眼睛处（z=1）再放一整片 2×2 挡墙，忽略 (0,64,2) 之后射线仍然
   * 被 z=1 的墙挡住，必须判不可见。
   *
   * <p>挡墙刻意放在<b>第一步</b>能采样到的位置：射线会跳过终点前约 1 格（刻意「偏向可见」），
   * 若把对照挡墙放在 z=3，角落候选点可能因尾段不采样而「漏判成可见」——那是射线语义，不是忽略越界。
   */
  @Test
  void ignoringOneCellDoesNotOpenOtherOccluders() {
    OcclusionStores stores = storesOccludingAt(
        new int[] {0, 64, 1}, new int[] {1, 64, 1}, new int[] {0, 65, 1}, new int[] {1, 65, 1},
        new int[] {0, 64, 2}, new int[] {1, 64, 2}, new int[] {0, 65, 2}, new int[] {1, 65, 2});
    ProximitySelector.Eye eyes = eye(0.5D, 64.5D, 0.5D);
    BitmapVisibility bitmap = BitmapVisibility.local();
    bitmap.beginIgnoring(0, 64, 2);
    try {
      assertFalse(bitmap.isVisible(eyes, stores, WORLD, ProximitySelector.MAX_SAMPLE_POINTS, 0, 64, 4),
          "忽略单格不能连带放过其它遮挡方块（严格口径不变）");
    } finally {
      bitmap.endIgnoring();
    }
  }
}
