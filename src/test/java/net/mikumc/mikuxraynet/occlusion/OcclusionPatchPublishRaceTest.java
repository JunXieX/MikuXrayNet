package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 「建列快照 → 期间补丁 → 发布时跳过快照后变更的段」的确定性回归。
 *
 * <p><b>要守护的缺陷方向</b>：「该显示却多藏」。建表在解码之后还要花 1~3 ms 构建位图；这段时间里
 * 方块变更（挖开墙/放置方块）已经把新状态写进活表（{@link OcclusionStore#setBlockOccluding}）。
 * 若发布（{@link OcclusionStore#restoreColumn}）无条件用「解码时刻的旧几何」整列覆盖，
 * 刚挖开的墙会继续保持遮挡 → 玩家眼前的矿被永久藏住。
 *
 * <p><b>修法</b>：建表前取 {@link OcclusionStore#patchStamps} 快照；发布时逐段比较当前补丁戳，
 * 戳变了（该段期间被补丁写过）就<b>跳过覆盖</b>、保留含补丁的活位图，并计入
 * {@link OcclusionStore#skippedPatchedSections()}。
 *
 * <p>本用例用公开 API 单线程编排（先取快照 → 打补丁 → 发布），不依赖线程调度与 sleep，
 * 因此是确定性的：把「跳过」判定删掉，断言立刻失败。
 */
class OcclusionPatchPublishRaceTest {

  private static final int CHUNK_X = 0;
  private static final int CHUNK_Z = 0;
  private static final int MIN_SECTION_Y = 0;
  private static final int SECTION_COUNT = 3;

  @Test
  void publishSkipsSectionPatchedAfterSnapshotAndKeepsItsBitmap() {
    OcclusionStore store = new OcclusionStore();

    // 1) 建表前取补丁戳快照（此刻三段都未被打补丁 → 全 0）
    long[] stamps = store.patchStamps(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, SECTION_COUNT);
    assertEquals(SECTION_COUNT, stamps.length);
    for (long stamp : stamps) {
      assertEquals(0L, stamp, "建表前该段尚未被补丁写过，戳必须为 0");
    }

    // 2) 「建表窗口」内玩家挖开了一个方块：中间那段（sectionY=1）被补丁写下
    //    坐标 (1, 1*16+2, 3) → section (0,1,0)，局部坐标 (1,2,3)
    assertNull(store.section(0, 1, 0), "前置：补丁前该段无数据");
    store.setBlockOccluding(1, 1 * 16 + 2, 3, true);
    SectionOcclusion patched = store.section(0, 1, 0);
    assertNotNull(patched, "补丁必须在该段建立位图");
    assertTrue(patched.isOccludingLocal(1, 2, 3), "补丁格必须为遮挡");

    // 3) 建表完成：用「解码时刻的旧几何」发布整列（section1 的旧几何是 null = 全不遮挡）
    SectionOcclusion[] column = {
        SolidOcclusion.INSTANCE,
        null,
        EmptyOcclusion.INSTANCE,
    };
    int skipped = store.restoreColumn(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, column, stamps);

    // 4) 只有「期间被补丁写过」的那一段被跳过
    assertEquals(1, skipped, "只应跳过被补丁写过的那一段");
    assertEquals(1L, store.skippedPatchedSections(), "跳过段数必须计入可观测计数");

    // 5) 被跳过的那段必须保留补丁后的位图（而不是退回 null / 旧 Solid 几何）
    SectionOcclusion survivor = store.section(0, 1, 0);
    assertNotNull(survivor, "被跳过的段不得被旧几何覆盖掉（否则缺数据 = 遮挡 → 该显示却多藏）");
    assertSame(patched, survivor, "该段必须保留补丁时写入的那份位图对象");
    assertTrue(survivor.isOccludingLocal(1, 2, 3), "补丁格必须仍然遮挡");
    assertFalse(survivor.isSolid(), "补丁后的位图不是整段实心");

    // 6) 未被补丁的段必须照常发布
    assertSame(SolidOcclusion.INSTANCE, store.section(0, 0, 0), "未被补丁的段必须按发布数据覆盖");
    assertSame(EmptyOcclusion.INSTANCE, store.section(0, 2, 0), "未被补丁的段必须按发布数据覆盖");

    // 7) 发布成功后未跳过的段戳必须已清；被跳过的段保留戳（供下一次发布继续比较）
    long[] after = store.patchStamps(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, SECTION_COUNT);
    assertEquals(0L, after[0], "已发布段的补丁戳必须在发布时清除");
    assertTrue(after[1] != 0L, "被跳过的段必须保留补丁戳（下一次发布仍需据此跳过）");
    assertEquals(0L, after[2], "已发布段的补丁戳必须在发布时清除");
  }

  @Test
  void unpatchedColumnIsPublishedEvenWhenStampsArePresent() {
    OcclusionStore store = new OcclusionStore();
    // 预先在别的位置打补丁（不影响本列三段），确保「有戳表但本列无戳」的情形也被覆盖
    store.setBlockOccluding(100, 200, 100, true);

    long[] stamps = store.patchStamps(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, SECTION_COUNT);
    SectionOcclusion[] column = {
        EmptyOcclusion.INSTANCE,
        SolidOcclusion.INSTANCE,
        EmptyOcclusion.INSTANCE,
    };
    int skipped = store.restoreColumn(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, column, stamps);

    assertEquals(0, skipped, "本列没有任何段在窗口内被补丁写过，不得跳过");
    assertSame(EmptyOcclusion.INSTANCE, store.section(0, 0, 0));
    assertSame(SolidOcclusion.INSTANCE, store.section(0, 1, 0));
    assertSame(EmptyOcclusion.INSTANCE, store.section(0, 2, 0));
    assertEquals(0L, store.skippedPatchedSections());
  }

  @Test
  void stampSnapshotTakenAfterPatchPublishesOverIt() {
    // 快照晚于补丁：戳已经包含本次补丁，发布时不应把它当作「窗口内变更」而跳过（否则该段永远无法收敛）
    OcclusionStore store = new OcclusionStore();
    store.setBlockOccluding(1, 1 * 16 + 2, 3, true);

    long[] stamps = store.patchStamps(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, SECTION_COUNT);
    SectionOcclusion[] column = {null, null, null};
    int skipped = store.restoreColumn(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, column, stamps);

    assertEquals(0, skipped, "快照已包含该补丁，不得再判为「期间变更」");
    assertNull(store.section(0, 1, 0), "发布数据为 null 时必须清掉旧条目（而非被跳过）");
  }

  @Test
  void removedColumnStampsDoNotBlockPublication() {
    // 区块卸载会同时摘除数据与戳；随后的发布（无快照可比）必须照常写入，
    // 否则该段会退回「缺数据 = 遮挡」，把玩家眼前的矿永久藏住。
    OcclusionStore store = new OcclusionStore();
    store.setBlockOccluding(1, 1 * 16 + 2, 3, true);
    store.removeColumn(CHUNK_X, CHUNK_Z);

    long[] stamps = store.patchStamps(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, SECTION_COUNT);
    for (long stamp : stamps) {
      assertEquals(0L, stamp, "摘列必须把补丁戳一并摘除");
    }

    int skipped = store.restoreColumn(CHUNK_X, MIN_SECTION_Y, CHUNK_Z,
        new SectionOcclusion[] {SolidOcclusion.INSTANCE}, stamps);
    assertEquals(0, skipped, "戳为空（摘列而非补丁）时必须照常发布");
    assertSame(SolidOcclusion.INSTANCE, store.section(0, 0, 0));
  }

  @Test
  void batchPatchStampsItsSectionOnceAndIsSkipped() {
    // 批量补丁（MULTI_BLOCK_CHANGE）：同一段多格只记一次戳，发布时只跳过该段一次
    OcclusionStore store = new OcclusionStore();
    long[] stamps = store.patchStamps(CHUNK_X, MIN_SECTION_Y, CHUNK_Z, SECTION_COUNT);
    int applied = store.setBlocksOccluding(
        new int[] {1, 2, 3}, new int[] {1 * 16 + 1, 1 * 16 + 2, 1 * 16 + 3},
        new int[] {4, 5, 6}, new boolean[] {true, true, true}, 3);
    assertEquals(3, applied, "三格补丁都应生效");

    int skipped = store.restoreColumn(CHUNK_X, MIN_SECTION_Y, CHUNK_Z,
        new SectionOcclusion[] {EmptyOcclusion.INSTANCE, EmptyOcclusion.INSTANCE,
            EmptyOcclusion.INSTANCE}, stamps);
    assertEquals(1, skipped, "同一段被批量补丁写过，只应跳过这一段（不是按格数计）");
    assertEquals(1L, store.skippedPatchedSections());
    SectionOcclusion survivor = store.section(0, 1, 0);
    assertNotNull(survivor, "该段必须保留补丁位图");
    assertTrue(survivor.isOccludingLocal(1, 1, 4));
    assertTrue(survivor.isOccludingLocal(2, 2, 5));
    assertTrue(survivor.isOccludingLocal(3, 3, 6));
  }

  @Test
  void nullStampSnapshotPublishesUnconditionallyAsDocumented() {
    // stamps == null 表示「不做比较」（旧行为）：这条语义必须保留，供缓存恢复等无快照调用方使用
    OcclusionStore store = new OcclusionStore();
    store.setBlockOccluding(1, 1 * 16 + 2, 3, true);

    int skipped = store.restoreColumn(CHUNK_X, MIN_SECTION_Y, CHUNK_Z,
        new SectionOcclusion[] {null, null, null}, null);

    assertEquals(0, skipped, "未提供快照时不得跳过任何段");
    assertNull(store.section(0, 1, 0), "无快照时按旧行为整列覆盖");
  }
}
