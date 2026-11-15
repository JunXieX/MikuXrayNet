package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 处置环的守门用例。
 *
 * <p>这份环是「门只开一半」这类现场问题的唯一物证来源：面板只有总数，回答不了「这两片方块到底走了
 * 哪条路」。因此它自己的语义必须钉死——容量固定、最新在前、旧记录被覆盖、空槽不导出、文本能读出坐标。
 */
class BlockUpdateTraceTest {

  @Test
  void recordsNewestFirstAndKeepsCoordinates() {
    BlockUpdateTrace trace = new BlockUpdateTrace();
    trace.record(BlockUpdateTrace.Decision.PASS, "world", true, 2, 100, 64, -200, null);
    trace.record(BlockUpdateTrace.Decision.MERGE, "world", true, 2, 100, 65, -200, "窗口合并");
    List<BlockUpdateTrace.Entry> entries = trace.snapshot();
    assertEquals(2, entries.size());
    assertEquals(BlockUpdateTrace.Decision.MERGE, entries.get(0).decision(), "最新一条必须在前");
    assertEquals(BlockUpdateTrace.Decision.PASS, entries.get(1).decision());
    assertEquals(65, entries.get(0).y(), "坐标必须原样保留（上半/下半靠 y 区分）");
    assertEquals(64, entries.get(1).y());
    assertEquals(100 >> 4, entries.get(0).chunkX(), "区块坐标由首坐标右移 4 位得出");
    assertEquals(-200 >> 4, entries.get(0).chunkZ());
  }

  @Test
  void capacityIsFixedAndOldestEntriesAreOverwritten() {
    BlockUpdateTrace trace = new BlockUpdateTrace();
    int total = BlockUpdateTrace.CAPACITY + 5;
    for (int i = 0; i < total; i++) {
      trace.record(BlockUpdateTrace.Decision.MERGE, "world", true, 2, i, 64, 0, null);
    }
    assertEquals(total, trace.recorded());
    List<BlockUpdateTrace.Entry> entries = trace.snapshot();
    assertEquals(BlockUpdateTrace.CAPACITY, entries.size(), "环容量固定，不能无界增长");
    assertEquals(total, entries.get(0).seq(), "最新序号必须等于总记录数");
    assertEquals(total - BlockUpdateTrace.CAPACITY + 1L, entries.get(entries.size() - 1).seq(),
        "最早留下的应当是「总数 - 容量 + 1」那一条");
  }

  @Test
  void nullDecisionIsIgnoredInsteadOfThrowing() {
    BlockUpdateTrace trace = new BlockUpdateTrace();
    trace.record(null, "world", false, 1, 0, 0, 0, null);
    assertEquals(0L, trace.recorded());
    assertTrue(trace.snapshot().isEmpty());
  }

  @Test
  void coordinateArrayOverloadUsesFirstCoordinate() {
    BlockUpdateTrace trace = new BlockUpdateTrace();
    trace.record(BlockUpdateTrace.Decision.PASS, "world", true, new int[] {7, 70, -7, 8, 70}, 2, null);
    BlockUpdateTrace.Entry entry = trace.snapshot().get(0);
    assertEquals(2, entry.coordCount());
    assertEquals(7, entry.x());
    assertEquals(70, entry.y());
    assertEquals(-7, entry.z());
  }

  @Test
  void emptyCoordinateArrayStillRecordsCount() {
    BlockUpdateTrace trace = new BlockUpdateTrace();
    trace.record(BlockUpdateTrace.Decision.DISCARD, "world", false, null, 3, "丢弃 3 个原包");
    BlockUpdateTrace.Entry entry = trace.snapshot().get(0);
    assertEquals(3, entry.coordCount());
    assertEquals(BlockUpdateTrace.Decision.DISCARD, entry.decision());
  }

  @Test
  void lineCarriesDecisionWorldCoordinatesAndNote() {
    BlockUpdateTrace trace = new BlockUpdateTrace();
    trace.record(BlockUpdateTrace.Decision.VERIFY_FAIL, "world_nether", true, 2, -3, 70, 4,
        "坐标回读与意图不一致");
    String line = trace.snapshot().get(0).line();
    assertTrue(line.contains("VERIFY_FAIL"), line);
    assertTrue(line.contains("world_nether"), line);
    assertTrue(line.contains("(-3,70,4)"), line);
    assertTrue(line.contains("坐标回读与意图不一致"), line);
    assertFalse(line.contains("null"), "没有附加说明时不得把 null 打进 dump：" + line);
  }
}
