package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link OcclusionStore} 的增删改查、整列摘除与「单格补丁的表示升降级」测试。
 */
class OcclusionStoreTest {

  @Test
  void replaceGetRemoveAndCount() {
    OcclusionStore store = new OcclusionStore();
    BitmapOcclusion data = new BitmapOcclusion();
    data.setOccluding(0, 0, 0, true);

    store.replaceSection(2, 3, 4, data);
    assertSame(data, store.section(2, 3, 4));
    assertEquals(1, store.loadedSectionCount());

    // 相邻 section 不应串数据
    assertNull(store.section(2, 4, 4));
    assertNull(store.section(3, 3, 4));

    store.removeSection(2, 3, 4);
    assertNull(store.section(2, 3, 4));
    assertEquals(0, store.loadedSectionCount());
  }

  @Test
  void replacingWithNullRemovesEntry() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(0, 0, 0, BitmapOcclusion.solid());
    assertEquals(1, store.loadedSectionCount());
    store.replaceSection(0, 0, 0, null);
    assertNull(store.section(0, 0, 0));
    assertEquals(0, store.loadedSectionCount());
  }

  @Test
  void removeColumnClearsOnlyThatColumn() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(-1, SectionKey.SECTION_Y_MIN, 7, BitmapOcclusion.solid());
    store.replaceSection(-1, 0, 7, BitmapOcclusion.solid());
    store.replaceSection(-1, SectionKey.SECTION_Y_MAX, 7, BitmapOcclusion.solid());
    store.replaceSection(-2, 0, 7, BitmapOcclusion.solid());
    store.replaceSection(-1, 0, 8, BitmapOcclusion.solid());
    assertEquals(5, store.loadedSectionCount());

    store.removeColumn(-1, 7);
    assertEquals(2, store.loadedSectionCount());
    assertNull(store.section(-1, SectionKey.SECTION_Y_MIN, 7));
    assertNull(store.section(-1, 0, 7));
    assertNull(store.section(-1, SectionKey.SECTION_Y_MAX, 7));
    assertNotNull(store.section(-2, 0, 7));
    assertNotNull(store.section(-1, 0, 8));
  }

  @Test
  void clearEmptiesEverything() {
    OcclusionStore store = new OcclusionStore();
    for (int i = 0; i < 50; i++) {
      store.replaceSection(i, 0, 0, BitmapOcclusion.solid());
    }
    store.clear();
    assertEquals(0, store.loadedSectionCount());
    assertNull(store.section(10, 0, 0));
  }

  @Test
  void patchOnMissingSectionCreatesBitmapOnlyWhenOccluding() {
    OcclusionStore store = new OcclusionStore();

    // 把「不遮挡」补丁打到不存在的 section：应当什么都不建
    store.setBlockOccluding(5, 5, 5, false);
    assertNull(store.section(0, 0, 0));
    assertEquals(0, store.loadedSectionCount());

    // 把「遮挡」补丁打到不存在的 section：建位图，仅该格为 1
    store.setBlockOccluding(5, 5, 5, true);
    SectionOcclusion section = store.section(0, 0, 0);
    assertNotNull(section);
    assertTrue(section.isOccludingLocal(5, 5, 5));
    assertFalse(section.isOccludingLocal(5, 5, 6));

    // 再清掉该格：位图变空 → 退化为「已知全不遮挡」快路径（而不是删条目，避免退化成「未知」）
    store.setBlockOccluding(5, 5, 5, false);
    assertSame(EmptyOcclusion.INSTANCE, store.section(0, 0, 0));
    assertEquals(1, store.loadedSectionCount());
  }

  /**
   * 回归：对<b>已存在且为「已知全不遮挡」</b>（{@link EmptyOcclusion} 共享快路径）的 section 打「遮挡」
   * 补丁，必须能新建位图并置位——不得因把 EmptyOcclusion 强转 BitmapOcclusion 而抛异常、被当成补丁失败
   * 静默吞掉。
   *
   * <p>触发路径：区块建表时该段全不遮挡 → 记为 EmptyOcclusion；随后玩家在该段放置一块石头（出站方块变更
   * → {@code OcclusionPatchListener}）→ 该格必须进位图。
   */
  @Test
  void patchOnKnownEmptySectionCreatesBitmap() {
    OcclusionStore store = new OcclusionStore();
    // 位图清空后回落到「已知全不遮挡」快路径（与「从未建表」的 null 不同）
    store.setBlockOccluding(5, 5, 5, true);
    store.setBlockOccluding(5, 5, 5, false);
    assertSame(EmptyOcclusion.INSTANCE, store.section(0, 0, 0), "前置：该段已是「已知全不遮挡」快路径");

    // 在该空段上放置一块遮挡方块：必须能置位（旧实现在此强转抛 CCE → 补丁失败、位图不更新）
    store.setBlockOccluding(9, 9, 9, true);
    SectionOcclusion section = store.section(0, 0, 0);
    assertNotNull(section);
    assertTrue(section.isOccludingLocal(9, 9, 9), "空段上新放置的遮挡方块必须进位图");
    assertFalse(section.isOccludingLocal(9, 9, 10), "其余格保持不遮挡");
  }

  @Test
  void patchDowngradesSolidSectionToBitmap() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(-3, -2, 9, SolidOcclusion.INSTANCE);

    // 清掉实心段中的一格 → 降级为位图，其余格仍遮挡
    store.setBlockOccluding((-3 << 4) + 4, (-2 << 4) + 2, (9 << 4) + 11, false);
    SectionOcclusion section = store.section(-3, -2, 9);
    assertNotNull(section);
    assertFalse(section.isSolid());
    assertFalse(section.isOccludingLocal(4, 2, 11));
    assertTrue(section.isOccludingLocal(4, 2, 10));
    assertTrue(section.isOccludingLocal(0, 0, 0));

    // 对已全遮挡的实心段再打「遮挡」补丁：无变化，仍是 Solid
    OcclusionStore other = new OcclusionStore();
    other.replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    other.setBlockOccluding(1, 1, 1, true);
    assertSame(SolidOcclusion.INSTANCE, other.section(0, 0, 0));
  }

  @Test
  void buildSectionAppliesThreeStateFastPaths() {
    int[] states = new int[BitmapOcclusion.BLOCK_COUNT];
    assertSame(EmptyOcclusion.INSTANCE, OcclusionStore.buildSection(states, state -> false));
    assertSame(SolidOcclusion.INSTANCE, OcclusionStore.buildSection(states, state -> true));

    states[SectionOcclusion.packLocal(1, 2, 3)] = 42;
    SectionOcclusion built = OcclusionStore.buildSection(states, state -> state == 42);
    assertNotNull(built);
    assertTrue(built.isOccludingLocal(1, 2, 3));
    assertFalse(built.isOccludingLocal(1, 2, 4));
  }

  /**
   * <b>越界坐标必须被拒绝</b>：{@code setBlockOccluding} 用带校验的 {@code SectionKey.packSection}，
   * 不会像 {@code packBlock} 那样按位截断成「另一个 section 的键」——否则补丁会被静默写到错误的几何上，
   * 该段位图从此与真实世界不符（该显示却多藏 / 该藏却显示）。
   *
   * <p>可达的越界只来自 Y：方块坐标是 int，水平 {@code x>>4} 最大恰为 {@link SectionKey#HORIZONTAL_MAX}；
   * 而 {@code y>>4} 可到 ±1.34 亿，远超 8 位 sectionY 范围。
   */
  @Test
  void outOfRangeCoordinatesAreRejectedInsteadOfSilentlyRepacked() {
    OcclusionStore store = new OcclusionStore();
    int tooHighY = (SectionKey.SECTION_Y_MAX + 1) << 4;
    assertThrows(IllegalArgumentException.class,
        () -> store.setBlockOccluding(0, tooHighY, 0, true));
    int tooLowY = (SectionKey.SECTION_Y_MIN - 1) << 4;
    assertThrows(IllegalArgumentException.class,
        () -> store.setBlockOccluding(0, tooLowY, 0, true));
    assertEquals(0, store.loadedSectionCount(), "越界坐标不得写入任何 section");
  }
}
