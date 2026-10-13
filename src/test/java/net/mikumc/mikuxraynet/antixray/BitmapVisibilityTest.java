package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.occlusion.EmptyOcclusion;
import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import org.junit.jupiter.api.Test;

/**
 * 邻近显形「位图可见性判定」（{@link BitmapVisibility}）的离线用例：有墙→不可见、无遮挡→可见、
 * 缺 section / 缺世界→不可见（严格方向）、玻璃不阻断。
 *
 * <p>这些用例钉住两类契约：
 * <ul>
 *   <li><b>缺数据 = 不可见</b>：与实体遮挡路径的 fail-open 相反——显形侧「无数据 ≠ 通畅」，
 *       否则整片尚未建表的区域会被一次性还原成真实方块（见 {@code ProximityRevealer} 类注释）；</li>
 *   <li><b>只统计遮挡方块</b>：玻璃等非遮挡方块不阻断步进射线（口径由 Bukkit {@code isOccluding} 决定）。</li>
 * </ul>
 */
class BitmapVisibilityTest {

  private static final String WORLD = "world";
  private static final int SAMPLES = ProximitySelector.MAX_SAMPLE_POINTS;

  /** 建 section 用的方块 id：谓词只把 {@link #STONE} 视为遮挡（玻璃/空气不遮挡）。 */
  private static final int GLASS = 1;
  private static final int STONE = 2;

  /** 站在 (0.5, 0.5, 0.5) 面朝 +Z。 */
  private static ProximitySelector.Eye eye() {
    return ProximitySelector.eye(0.5D, 0.5D, 0.5D, 0.0D, 0.0D, 1.0D);
  }

  @Test
  void emptySectionsAreVisible() {
    OcclusionStores stores = new OcclusionStores();
    fillEmpty(stores);

    assertTrue(visible(stores, 8, 0, 5), "眼与目标之间全不遮挡 → 可见");
    assertTrue(visible(stores, 8, 0, 20), "更远处的目标同样可见");
  }

  /** 一整层石墙挡住射线时必须判不可见（不得为了「多显形」而穿墙）。 */
  @Test
  void stoneWallBlocksVisibility() {
    OcclusionStores stores = new OcclusionStores();
    fillEmpty(stores);
    // chunk(0,0) 的 z=3 整层石头（覆盖该 section 的 16×16）
    stores.of(WORLD).replaceSection(0, 0, 0, layerSection(3, STONE));

    assertFalse(visible(stores, 8, 0, 20), "石墙后面的目标不可见");
    assertTrue(visible(stores, 8, 0, 2), "墙前面的目标不受影响（射线不经过那层墙）");
  }

  /** 同样的几何换成玻璃层时不得阻断：步进射线只统计遮挡方块（玻璃 = 不遮挡）。 */
  @Test
  void glassLayerDoesNotBlockVisibility() {
    OcclusionStores stores = new OcclusionStores();
    fillEmpty(stores);
    stores.of(WORLD).replaceSection(0, 0, 0, layerSection(3, GLASS));

    assertTrue(visible(stores, 8, 0, 20), "玻璃层不阻断步进射线（口径 = Bukkit isOccluding）");
  }

  @Test
  void missingSectionIsNotVisible() {
    OcclusionStores stores = new OcclusionStores();
    // 只写一个无关 section：候选所在 section 缺数据
    stores.of(WORLD).replaceSection(5, 0, 5, EmptyOcclusion.INSTANCE);

    assertFalse(visible(stores, 8, 0, 5), "候选所在 section 无位图数据 → 不显形（严格方向）");
  }

  @Test
  void missingWorldIsNotVisible() {
    OcclusionStores stores = new OcclusionStores();

    assertFalse(visible(stores, 8, 0, 5), "该世界尚无位图 → 不显形（严格方向）");
  }

  @Test
  void nullStoresIsNotVisible() {
    assertFalse(BitmapVisibility.local().isVisible(eye(), null, WORLD, SAMPLES, 8, 0, 5),
        "整体无位图 → 不显形（严格方向；生产由 ProximityRevealer 回落原生射线路径，不走本判定）");
  }

  /**
   * 「缺数据剔除」的归因口径（{@link BitmapVisibility#missingDataConsulted()}）：调用方据此把
   * 「被缺数据挡住」与「真的被墙挡住」分开计数，因此这里必须钉死「只在触到缺数据时为真」。
   */
  @Test
  void missingDataAttributionDistinguishesAbsentDataFromRealOcclusion() {
    // ① 缺 section：剔除且归因到缺数据
    OcclusionStores absent = new OcclusionStores();
    absent.of(WORLD).replaceSection(5, 0, 5, EmptyOcclusion.INSTANCE);
    BitmapVisibility view = BitmapVisibility.local();
    assertFalse(view.isVisible(eye(), absent, WORLD, SAMPLES, 8, 0, 5));
    assertTrue(view.missingDataConsulted(), "缺数据导致的剔除必须被标记为「触到过缺数据」");

    // ② 位图齐全、被真实石墙挡住：剔除但不归因到缺数据
    OcclusionStores walled = new OcclusionStores();
    fillEmpty(walled);
    walled.of(WORLD).replaceSection(0, 0, 0, layerSection(3, STONE));
    assertFalse(view.isVisible(eye(), walled, WORLD, SAMPLES, 8, 0, 20));
    assertFalse(view.missingDataConsulted(), "数据齐全时的真实遮挡不得被算进「缺数据」子集");

    // ③ 可见：即使触到过缺数据也不归因（调用方只在剔除时读这个标记）
    OcclusionStores visible = new OcclusionStores();
    fillEmpty(visible);
    assertTrue(view.isVisible(eye(), visible, WORLD, SAMPLES, 8, 0, 5));
    assertFalse(view.missingDataConsulted(), "数据齐全的可见候选不得被标记");
  }

  // ---------------------------------------------------------------- 辅助

  /** 判定某坐标是否可见（复用当前线程那一份判定器，与生产路径一致）。 */
  private static boolean visible(OcclusionStores stores, int x, int y, int z) {
    return BitmapVisibility.local().isVisible(eye(), stores, WORLD, SAMPLES, x, y, z);
  }

  /**
   * 把「眼位到远目标」沿途的 section 全部写成 {@link EmptyOcclusion}（已知不遮挡）。
   *
   * <p>必须显式写入而不留空：本判定的严格规则是「缺数据 = 遮挡」，若沿途有 section 没数据，
   * 用例就测不到「无遮挡 → 可见」这条。
   */
  private static void fillEmpty(OcclusionStores stores) {
    OcclusionStore store = stores.of(WORLD);
    for (int chunkX = -1; chunkX <= 1; chunkX++) {
      for (int chunkZ = 0; chunkZ <= 2; chunkZ++) {
        for (int sectionY = -1; sectionY <= 1; sectionY++) {
          store.replaceSection(chunkX, sectionY, chunkZ, EmptyOcclusion.INSTANCE);
        }
      }
    }
  }

  /** 造一个「只在局部 z=layer 那一层放同一种方块」的 section（其余为空气）。 */
  private static SectionOcclusion layerSection(int layer, int material) {
    int size = SectionOcclusion.SECTION_SIZE;
    int[] states = new int[size * size * size];
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        // 局部下标编码与 SectionOcclusion#packLocal 一致：y<<8 | z<<4 | x
        states[(y << 8) | (layer << 4) | x] = material;
      }
    }
    return OcclusionStore.buildSection(states, id -> id == STONE);
  }
}
