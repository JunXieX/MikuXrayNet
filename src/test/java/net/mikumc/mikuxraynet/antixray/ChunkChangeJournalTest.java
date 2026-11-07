package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 方块变更日志：区块包放行后「补发在途变更」的依据（真机反馈：边走边放方块时，刚放的那一格闪一下）。
 *
 * <p>纯数据结构，不依赖 Bukkit：断言四条契约——只取持包之后、只取同一区块、同格去重、单次补发有上限。
 */
class ChunkChangeJournalTest {

  @Test
  void changedSinceReturnsOnlyNewerChangesInsideTheSameChunk() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    journal.record("world", 0, 64, 0);
    long mark = journal.sequence();
    journal.record("world", 5, 64, 5);      // 同一区块（chunk 0,0）
    journal.record("world", 40, 64, 40);    // 另一区块（chunk 2,2）
    journal.record("world_nether", 6, 64, 6); // 同名坐标、不同世界

    List<int[]> cells = journal.changedSince(mark, "world", 0, 0);
    assertEquals(1, cells.size(), "只补发同一世界、同一区块、且序号在持包之后的变更");
    assertArrayEquals(new int[] {5, 64, 5}, cells.get(0));
  }

  @Test
  void changedSinceIsEmptyWhenNothingHappenedAfterTheMark() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    journal.record("world", 0, 64, 0);
    long mark = journal.sequence();
    assertTrue(journal.changedSince(mark, "world", 0, 0).isEmpty(), "持包之后没有变更：一次都不补发");

    journal.record("world", 1, 64, 1);
    assertFalse(journal.changedSince(mark, "world", 0, 0).isEmpty(), "持包之后有变更：必须补发");
  }

  @Test
  void repeatedChangesToTheSameCellAreReplayedOnce() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    for (int i = 0; i < 40; i++) {
      journal.record("world", 1, 64, 1);
    }
    List<int[]> cells = journal.changedSince(0L, "world", 0, 0);
    assertEquals(1, cells.size(), "同一格反复变更只补发一次（补发的是当前状态，重复没有意义）");
  }

  @Test
  void replayIsCappedSoOnePacketCanNeverFlood() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    for (int i = 0; i < 200; i++) {
      journal.record("world", i, 64, 0);
    }
    List<int[]> cells = journal.changedSince(0L, "world", 0, 0);
    assertEquals(ChunkChangeJournal.MAX_REPLAY, cells.size(), "单次补发必须有上限");
  }

  @Test
  void ringWrapKeepsServingTheNewestChanges() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    for (int i = 0; i < 2000; i++) {
      journal.record("world", i & 15, 64, 0);
    }
    long mark = journal.sequence();
    journal.record("world", 3, 70, 3);
    List<int[]> cells = journal.changedSince(mark, "world", 0, 0);
    assertEquals(1, cells.size(), "环形覆盖之后仍能取到最新一条");
    assertArrayEquals(new int[] {3, 70, 3}, cells.get(0));
  }
}