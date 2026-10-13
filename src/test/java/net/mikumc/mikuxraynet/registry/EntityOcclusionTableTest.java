package net.mikumc.mikuxraynet.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

/**
 * {@link EntityOcclusionTable} 的语义测试。
 *
 * <p>本类现在只是「逐状态遮挡位图」的只读快照封装（真值的解析在 {@link StateOcclusionOracle} /
 * {@link BlockStateRegistry} 里完成），因此测试只钉死它的封装语义：逐状态查询、越界安全、构建快照
 * 与被后续修改的源位图隔离、{@code null} 表示「不建表」。平台无关，可离线运行。
 */
class EntityOcclusionTableTest {

  @Test
  void wrapsPerStateBitSetVerdictPerId() {
    BitSet perState = new BitSet();
    perState.set(0); // 例如 stone
    perState.set(3); // 例如 anvil

    EntityOcclusionTable table = EntityOcclusionTable.build(perState);

    assertNotNull(table);
    assertTrue(table.isOccluding(0));
    assertFalse(table.isOccluding(1));
    assertFalse(table.isOccluding(2), "未置位的状态（含空洞 id）一律判为非遮挡");
    assertTrue(table.isOccluding(3));
    assertEquals(2, table.occludingStateCount());
  }

  /** 越界 id 一律按「不遮挡」处理（少判 = 实体剔除的安全方向），绝不抛异常。 */
  @Test
  void outOfRangeIdIsNotOccluding() {
    EntityOcclusionTable table = EntityOcclusionTable.build(new BitSet());

    assertNotNull(table);
    assertFalse(table.isOccluding(-1));
    assertFalse(table.isOccluding(4096));
    assertEquals(0, table.occludingStateCount());
  }

  /** 构建时对源位图做快照：构建后再改源位图不得影响已建表（避免「建表后被外部改写」的隐蔽漂移）。 */
  @Test
  void builtTableIsIsolatedFromLaterSourceMutation() {
    BitSet perState = new BitSet();
    perState.set(0);

    EntityOcclusionTable table = EntityOcclusionTable.build(perState);
    perState.set(5); // 构建之后再改源位图

    assertNotNull(table);
    assertTrue(table.isOccluding(0));
    assertFalse(table.isOccluding(5), "表在构建时已快照，后续改动源位图不应影响它");
    assertEquals(1, table.occludingStateCount());
  }

  /** 位图为 {@code null} 表示「不建实体口径表」（调用方据此回落伪装口径，零额外内存）。 */
  @Test
  void nullBitSetMeansNoTable() {
    assertNull(EntityOcclusionTable.build(null));
  }
}
