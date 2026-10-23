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

  /**
   * <b>建表期间落库的方块补丁不得被「解码时刻的旧几何」覆盖</b>（P1 竞态回归）。
   *
   * <p>时序：旧位图（整段遮挡）→ 取补丁戳快照 → 玩家挖开一格（补丁落库）→ 建表线程把「解码时刻
   * 仍整段遮挡」的整列发布过来。发布必须跳过这一段，保留含补丁的活位图，否则刚挖开的墙会重新变成
   * 遮挡，墙后矿不再显形（该显示却多藏）。
   */
  @Test
  void patchAppliedBetweenSnapshotAndPublishSurvivesColumnRestore() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    long[] stamps = store.patchStamps(0, 0, 0, 1);
    assertEquals(0L, stamps[0], "前置：建表前该段没有补丁戳");

    store.setBlockOccluding(1, 2, 3, false); // 建表期间：玩家挖开一格
    int skipped = store.restoreColumn(0, 0, 0,
        new SectionOcclusion[] {SolidOcclusion.INSTANCE}, stamps);

    assertEquals(1, skipped, "期间被补丁写过的段必须跳过覆盖");
    SectionOcclusion live = store.section(0, 0, 0);
    assertNotNull(live, "跳过覆盖应保留活位图，而不是把条目删掉");
    assertFalse(live.isOccludingLocal(1, 2, 3), "挖开的格不得被旧几何重新标成遮挡");
    assertTrue(live.isOccludingLocal(3, 2, 1), "其余格沿用活位图的遮挡状态");
    assertEquals(1L, store.skippedPatchedSections(), "跳过段数必须计入观测点");
  }

  /**
   * 快照取自补丁之后（补丁早已落库）：戳比较发现不了这次补丁，但「补丁格回放」必须把它修正回来。
   *
   * <p>这正是「包序列化 → 取戳快照」窗口：解码几何早于补丁，若只靠戳比较，发布会用旧几何吞掉补丁。
   */
  @Test
  void snapshotTakenAfterPatchReplaysThePatch() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    store.setBlockOccluding(1, 2, 3, false);
    long[] stamps = store.patchStamps(0, 0, 0, 1);

    int skipped = store.restoreColumn(0, 0, 0,
        new SectionOcclusion[] {SolidOcclusion.INSTANCE}, stamps);

    assertEquals(0, skipped, "快照已包含该补丁：不得整段跳过");
    assertFalse(store.section(0, 0, 0).isOccludingLocal(1, 2, 3),
        "回放必须把该格修正回不遮挡（否则旧几何吞掉补丁）");
    assertTrue(store.section(0, 0, 0).isOccludingLocal(2, 2, 4), "其余格按发布几何");
    assertEquals(0L, store.skippedPatchedSections());
  }

  /** 旧入口（不带快照）仍整列覆盖，但窗口内的补丁格会由回放补回。 */
  @Test
  void restoreColumnWithoutSnapshotStillReplaysPendingPatches() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    store.setBlockOccluding(1, 2, 3, false);

    store.restoreColumn(0, 0, 0, new SectionOcclusion[] {SolidOcclusion.INSTANCE});

    assertFalse(store.section(0, 0, 0).isOccludingLocal(1, 2, 3),
        "不带快照的旧入口不再做戳比较，但补丁格必须回放（方向：不被旧几何吞掉）");
  }

  /**
   * 整列被 {@link OcclusionStore#removeColumn} 摘除（区块卸载）后，即便手里的快照是旧的，
   * 也必须照常发布：空戳意味着「不是补丁」，不能跳过，否则该段退回「缺数据 = 遮挡」，
   * 把玩家眼前的矿永久藏住。
   */
  @Test
  void clearedColumnIsPublishedEvenWithStaleSnapshot() {
    OcclusionStore store = new OcclusionStore();
    long[] stamps = store.patchStamps(0, 0, 0, 1);
    store.setBlockOccluding(1, 2, 3, true);
    store.removeColumn(0, 0);

    int skipped = store.restoreColumn(0, 0, 0,
        new SectionOcclusion[] {SolidOcclusion.INSTANCE}, stamps);

    assertEquals(0, skipped, "整列被摘除后必须恢复数据");
    assertSame(SolidOcclusion.INSTANCE, store.section(0, 0, 0));
  }

  /**
   * <b>P2 回归：活表里本来没有该段时不得跳过覆盖</b>。
   *
   * <p>「打断」类补丁在无数据段上不会建条目（{@code applyCellLocked} 早退），但会记戳。若发布时只看
   * 「戳变了」就跳过，这轮整列发布结束后该段依然为空 → 显形侧按「缺数据 = 遮挡」判定 → 该段内的矿
   * 永不显形。跳过条件必须同时要求「活表确有该段数据」。
   */
  @Test
  void patchOnAbsentSectionDoesNotBlockColumnPublish() {
    OcclusionStore store = new OcclusionStore();
    long[] stamps = store.patchStamps(0, 0, 0, 1);

    // 窗口内打断一格：该段此前没有数据 → 不建条目，但记戳
    store.setBlockOccluding(1, 2, 3, false);
    assertEquals(0, store.loadedSectionCount(), "前置：打断类补丁在无数据段上不建条目");

    int skipped = store.restoreColumn(0, 0, 0,
        new SectionOcclusion[] {SolidOcclusion.INSTANCE}, stamps);

    assertEquals(0, skipped, "活表没有该段数据时不得跳过覆盖（否则整段永久留空）");
    SectionOcclusion published = store.section(0, 0, 0);
    assertNotNull(published, "该段必须被发布出来");
    assertFalse(published.isOccludingLocal(1, 2, 3), "窗口内的打断补丁必须由回放补回");
    assertTrue(published.isOccludingLocal(2, 2, 4), "其余格按发布几何");
  }

  // ---------------------------------------------------------- 只补缺失段（缓存命中路径）

  /**
   * 活表只有一段时：{@code restoreMissingColumn} 只补其余缺段，已有段一格都不动。
   *
   * <p>已有段可能含更晚的方块补丁；用旧负载覆盖会把补丁抹掉（该显示却多藏）。也不能用「首段存在
   * ⇒ 整列存在」的列级探针（补丁只建出单段时会把整列误判为已存在 → 其余段永久缺失）。
   */
  @Test
  void restoreMissingColumnFillsOnlyMissingSections() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(0, 1, 0, SolidOcclusion.INSTANCE); // 活表只有 sectionY=1

    SectionOcclusion[] column = {
        SolidOcclusion.INSTANCE,   // sectionY=0：缺失 → 应补
        EmptyOcclusion.INSTANCE,   // sectionY=1：已有 → 不得覆盖
        SolidOcclusion.INSTANCE    // sectionY=2：缺失 → 应补
    };
    int filled = store.restoreMissingColumn(0, 0, 0, column);

    assertEquals(2, filled, "只补两个缺失段");
    assertSame(SolidOcclusion.INSTANCE, store.section(0, 0, 0), "缺失段被补");
    assertSame(SolidOcclusion.INSTANCE, store.section(0, 1, 0), "已有段必须保持原样（不得被旧负载覆盖）");
    assertSame(SolidOcclusion.INSTANCE, store.section(0, 2, 0), "缺失段被补");
  }

  /** 已有段里的方块补丁必须原样保留（旧负载不得回退它）。 */
  @Test
  void restoreMissingColumnLeavesPatchedSectionUntouched() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    store.setBlockOccluding(1, 2, 3, false); // 该段被补丁改过（挖开一格）

    SectionOcclusion[] column = {SolidOcclusion.INSTANCE, EmptyOcclusion.INSTANCE};
    int filled = store.restoreMissingColumn(0, 0, 0, column);

    assertEquals(1, filled, "只补缺失的 sectionY=1");
    SectionOcclusion live = store.section(0, 0, 0);
    assertNotNull(live);
    assertFalse(live.isOccludingLocal(1, 2, 3), "已有段里的补丁不得被旧负载覆盖");
    assertSame(EmptyOcclusion.INSTANCE, store.section(0, 1, 0), "缺失段被补");
  }

  /** 整列都已存在时不做任何改动（返回 0），命中路径可据此判断是否需要后续工作。 */
  @Test
  void restoreMissingColumnIsNoOpWhenNothingMissing() {
    OcclusionStore store = new OcclusionStore();
    store.replaceSection(0, 0, 0, EmptyOcclusion.INSTANCE);
    store.replaceSection(0, 1, 0, SolidOcclusion.INSTANCE);

    int filled = store.restoreMissingColumn(0, 0, 0,
        new SectionOcclusion[] {SolidOcclusion.INSTANCE, SolidOcclusion.INSTANCE});

    assertEquals(0, filled);
    assertSame(EmptyOcclusion.INSTANCE, store.section(0, 0, 0), "已有段不被覆盖");
  }
}
