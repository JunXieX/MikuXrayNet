package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.occlusion.OcclusionStats;
import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.SolidOcclusion;
import org.junit.jupiter.api.Test;

/**
 * {@link OcclusionPatchListener} 的纯函数部分：把「坐标 + 三态判定」应用到遮挡位图。
 *
 * <p>封包解析本身（{@code WrappedBlockData}）离线构造不出来（ProtocolLib 的方块数据包装依赖服务端
 * 注册表），因此这里只覆盖确定性的应用逻辑——它才是补丁的正误所在：坐标落点、单格增删、
 * 以及「拿不到口径 → 降级该 section（fail-open）」这条安全兜底。
 */
class OcclusionPatchListenerTest {

  private static final int CHUNK = 0; // 世界方块 x,z ∈ [0,15]、y ∈ [0,15] → sectionY 0

  @Test
  void occludingPatchCreatesBitAndCountsIt() {
    OcclusionStore store = new OcclusionStore();
    OcclusionStats stats = new OcclusionStats();
    assertNull(store.section(CHUNK, 0, CHUNK), "前置：该段无数据");

    OcclusionPatchListener.applyPatches(store, new int[] {3, 4, 5},
        new byte[] {OcclusionPatchListener.OCCLUDING}, stats);

    assertNotNull(store.section(CHUNK, 0, CHUNK), "遮挡格必须落进位图");
    assertTrue(store.section(CHUNK, 0, CHUNK).isOccludingLocal(3, 4, 5), "补丁格本身应遮挡");
    assertFalse(store.section(CHUNK, 0, CHUNK).isOccludingLocal(6, 4, 5), "未补丁的格不得被顺带置位");
    assertEquals(1L, stats.patched.sum(), "补丁格数必须计入可观测计数");
    assertEquals(0L, stats.patchFailures.sum());
  }

  /** 挖掘（变空气）必须把整段实心降级──否则陈旧「遮挡」位会把本已可见的实体继续隐藏。 */
  @Test
  void clearPatchDowngradesSolidSection() {
    OcclusionStore store = new OcclusionStore();
    OcclusionStats stats = new OcclusionStats();
    store.replaceSection(CHUNK, 0, CHUNK, SolidOcclusion.INSTANCE);

    OcclusionPatchListener.applyPatches(store, new int[] {0, 0, 0},
        new byte[] {OcclusionPatchListener.CLEAR}, stats);

    assertNotNull(store.section(CHUNK, 0, CHUNK));
    assertFalse(store.section(CHUNK, 0, CHUNK).isSolid(), "整段实心被挖掉一格后必须降级为位图");
    assertFalse(store.section(CHUNK, 0, CHUNK).isOccludingLocal(0, 0, 0), "被挖的格必须不再遮挡");
    assertTrue(store.section(CHUNK, 0, CHUNK).isOccludingLocal(1, 0, 0), "其它格保持原样（仍遮挡）");
    assertEquals(1L, stats.patched.sum());
  }

  /**
   * 安全兜底：拿不到新方块口径时<b>只把该格置为「不遮挡」</b>（清掉可能陈旧的「遮挡」位），
   * <b>绝不整段摘除</b>——整段摘除会一次丢掉 4096 格遮挡信息（实体侧「少藏」面被放大），
   * 并给显形侧「缺数据 = 全遮挡」的位图判定制造一整段 16³ 死区。
   */
  @Test
  void unresolvedPatchClearsTheCellWithoutDroppingTheWholeSection() {
    OcclusionStore store = new OcclusionStore();
    OcclusionStats stats = new OcclusionStats();
    store.replaceSection(CHUNK, 0, CHUNK, SolidOcclusion.INSTANCE);

    OcclusionPatchListener.applyPatches(store, new int[] {7, 8, 9},
        new byte[] {OcclusionPatchListener.UNRESOLVED}, stats);

    assertNotNull(store.section(CHUNK, 0, CHUNK),
        "不得整段摘除：摘除会让显形侧把整段当成全遮挡（死区）");
    assertFalse(store.section(CHUNK, 0, CHUNK).isSolid(), "整段实心被单格置零后必须降级为位图");
    assertFalse(store.section(CHUNK, 0, CHUNK).isOccludingLocal(7, 8, 9),
        "拿不到口径的格必须不再遮挡（绝不能留下陈旧「遮挡」位误藏实体）");
    assertTrue(store.section(CHUNK, 0, CHUNK).isOccludingLocal(7, 9, 9),
        "其余格保持原样（一次性摘除会把它们的遮挡信息一起丢掉）");
    assertEquals(0L, stats.patched.sum(), "未解析格不属「已按变更改写」的格");
    assertEquals(1L, stats.patchFailures.sum(), "失败格数必须计入可观测计数");
  }

  /** 多格一次应用：各格独立生效，且单格失败（越界被拒）不让整批失败。 */
  @Test
  void multiplePatchesApplyIndependently() {
    OcclusionStore store = new OcclusionStore();
    OcclusionStats stats = new OcclusionStats();

    // 三格分属不同 section：遮挡格 / 未解析格（降级另一段）/ 越界坐标（SectionKey 显式拒绝 → 单格失败）
    OcclusionPatchListener.applyPatches(store,
        new int[] {1, 1, 1, 20, 20, 20, 200_000_000, 0, 0},
        new byte[] {OcclusionPatchListener.OCCLUDING, OcclusionPatchListener.UNRESOLVED,
            OcclusionPatchListener.UNRESOLVED},
        stats);

    assertTrue(store.section(0, 0, 0).isOccludingLocal(1, 1, 1),
        "第 1 格必须生效（后面的失败不得回滚它）");
    assertNull(store.section(200_000_000 >> 4, 0, 0), "越界坐标被拒的格不得写入任何数据");
    assertEquals(1L, stats.patched.sum(), "只有真正写入的那格计入补丁数");
    assertEquals(2L, stats.patchFailures.sum(), "未解析格与越界被拒格都计入失败数");
  }

  /** 长度不一致 / 空参数一律整体跳过（绝不猜着应用）。 */
  @Test
  void malformedBatchIsSkippedEntirely() {
    OcclusionStore store = new OcclusionStore();
    OcclusionStats stats = new OcclusionStats();

    OcclusionPatchListener.applyPatches(store, new int[] {1, 1, 1},
        new byte[] {OcclusionPatchListener.OCCLUDING, OcclusionPatchListener.OCCLUDING}, stats);
    OcclusionPatchListener.applyPatches(null, new int[] {1, 1, 1},
        new byte[] {OcclusionPatchListener.OCCLUDING}, stats);
    OcclusionPatchListener.applyPatches(store, null, null, stats);

    assertEquals(0L, stats.patched.sum(), "参数不合法时不得应用任何补丁");
    assertNull(store.section(0, 0, 0), "不得写入任何数据");
  }

  /**
   * MULTI_BLOCK_CHANGE 的坐标展开：{@code (x & 15) << 8 | (z & 15) << 4 | (y & 15)}。
   *
   * <p>展开错位会去补丁「另一格」，表现为位图与真实世界不一致（可能误藏或漏补），故单独钉死。
   */
  @Test
  void multiBlockCoordsUnpackMatchesTheProtocolPacking() {
    // section (3, -2, 5) → 基准 (48, -32, 80)；三格相对坐标 (1,2,3) / (15,0,0) / (0,15,15)
    short[] positions = {
        (short) ((1 << 8) | (3 << 4) | 2),
        (short) ((15 << 8) | (0 << 4) | 0),
        (short) ((0 << 8) | (15 << 4) | 15)
    };
    int[] coords = new int[positions.length * 3];
    OcclusionPatchListener.unpackMultiCoords(48, -32, 80, positions, coords);

    assertEquals(49, coords[0]);
    assertEquals(-30, coords[1], "y 取低 4 位（此处 2）");
    assertEquals(83, coords[2]);

    assertEquals(63, coords[3]);
    assertEquals(-32, coords[4]);
    assertEquals(80, coords[5]);

    assertEquals(48, coords[6]);
    assertEquals(-17, coords[7], "y 取低 4 位（此处 15）");
    assertEquals(95, coords[8]);
  }
}
