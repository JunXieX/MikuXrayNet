package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import org.junit.jupiter.api.Test;

/**
 * {@link RevealProbe} 的离线用例：用真实位图 / 真实索引 / 真实可见性实现，断言探针能正确指出
 * 「这个坐标为什么没被显形」的每一层结论。
 *
 * <p>夹具几何：section (0,4,0)（世界 y=64..79）里，石头填满整段，只在 (2,64,6) 留「箱子」、
 * (2,65,6) 留「告示牌」——两者都按不遮挡处理（与生产口径一致：箱子/告示牌在视觉口径下都不遮挡）。
 * 于是箱子的六个面里只有顶面暴露，探针必须报告「+Y 暴露」并判可见。
 */
class RevealProbeTest {

  private static final String WORLD = "world";
  private static final int MIN_HEIGHT = -64;
  private static final int SECTION_VOLUME = 16 * 16 * 16;
  private static final int OCCLUDING_STATE = 7;

  private static int local(int x, int worldY, int z) {
    return SectionOcclusion.packLocal(x & 15, worldY & 15, z & 15);
  }

  /**
   * 箱子嵌在墙里、四周与下方都被石头包住（下方那一格在相邻 section，由「缺数据 = 遮挡」兜住），
   * 只有顶面朝向开阔空间。
   *
   * @param withSign 顶面之上是否留一格告示牌（不遮挡）；{@code false} 表示顶面也被石头盖住
   */
  private static SectionOcclusion chestInWall(boolean withSign) {
    int[] states = new int[SECTION_VOLUME];
    int[][] walls = {{1, 64, 6}, {3, 64, 6}, {2, 64, 5}, {2, 64, 7}};
    for (int[] wall : walls) {
      states[local(wall[0], wall[1], wall[2])] = OCCLUDING_STATE;
    }
    if (!withSign) {
      states[local(2, 65, 6)] = OCCLUDING_STATE;
    }
    SectionOcclusion section = OcclusionStore.buildSection(states, state -> state == OCCLUDING_STATE);
    assertNotNull(section, "夹具必须能建出位图");
    assertFalse(section.isEmpty(), "夹具必须包含遮挡格，否则退化为共享空快照");
    return section;
  }

  private static OcclusionStores storesWith(SectionOcclusion section) {
    OcclusionStores stores = new OcclusionStores();
    stores.of(WORLD).replaceSection(0, 4, 0, section);
    return stores;
  }

  /** 登记「(2,64,6) 被伪装」的索引。 */
  private static ObfuscatedChunkIndex indexWithChest() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1_000_000, 3600);
    int packed = ((64 - MIN_HEIGHT) << 8) | ((6 & 15) << 4) | (2 & 15);
    index.recordChunkWithRemovals(WORLD, 0, 0, MIN_HEIGHT, new int[] {packed});
    return index;
  }

  /** 玩家站在箱子旁 3 格处、平视略向下：眼睛 y=65.62（脚在 y=64 的地面上）。 */
  private static ProximitySelector.Eye eye() {
    return ProximitySelector.eye(2.5D, 65.62D, 9.5D, 0.0D, -0.12D, -1.0D);
  }

  private static List<String> probe(ObfuscatedChunkIndex index, RevealedSet revealed,
      OcclusionStores stores, UUID playerId) {
    return RevealProbe.probe(WORLD, playerId, eye(), 2, 64, 6, index, revealed, stores,
        ProximitySelector.MAX_SAMPLE_POINTS);
  }

  private static String joined(List<String> lines) {
    return String.join("\n", lines);
  }

  @Test
  void reportsVisibleWhenOnlyTheTopFaceIsExposedUnderASign() {
    SectionOcclusion section = chestInWall(true);
    String text = joined(probe(indexWithChest(), new RevealedSet(1_000, 600),
        storesWith(section), UUID.randomUUID()));

    assertTrue(text.contains("在清单里"), "箱子坐标必须在伪装清单里：" + text);
    assertTrue(text.contains("逐格位图"), "该段必须是逐格位图：" + text);
    assertTrue(text.contains("+Y"), "顶面（告示牌处）必须被标为暴露面：" + text);
    assertTrue(text.contains("可见 → 会显形"), "顶面暴露 + 射线通畅时必须判可见：" + text);
  }

  @Test
  void reportsInvisibleWhenEveryFaceIsOccluding() {
    // 没有告示牌缺口：箱子六面全被石头包住
    SectionOcclusion section = chestInWall(false);
    String text = joined(probe(indexWithChest(), new RevealedSet(1_000, 600),
        storesWith(section), UUID.randomUUID()));

    assertTrue(text.contains("六面全遮挡"), "必须指出六面全遮挡：" + text);
    assertTrue(text.contains("不可见（无暴露面）"), "无暴露面必须判不可见：" + text);
  }

  @Test
  void reportsMissingBitmapAndUnregisteredCoordinate() {
    String unregistered = joined(RevealProbe.probe(WORLD, UUID.randomUUID(), eye(), 2, 65, 6,
        indexWithChest(), new RevealedSet(1_000, 600), new OcclusionStores(),
        ProximitySelector.MAX_SAMPLE_POINTS));
    assertTrue(unregistered.contains("不在清单里"), "必须报告坐标不在清单：" + unregistered);
    assertTrue(unregistered.contains("没有位图"), "必须报告位图缺失：" + unregistered);
  }

  @Test
  void reportsRevealedMarkThatWouldSkipTheCoordinate() {
    UUID playerId = UUID.randomUUID();
    RevealedSet revealed = new RevealedSet(1_000, 600);
    revealed.markIfAbsent(playerId, ChunkKey.ofBlock(WORLD, 2, 6), 2, 64, 6);
    SectionOcclusion section = chestInWall(true);
    String text = joined(probe(indexWithChest(), revealed, storesWith(section), playerId));

    assertTrue(text.contains("已标记"), "已显形标记必须被报告为「会跳过」：" + text);
  }
}
