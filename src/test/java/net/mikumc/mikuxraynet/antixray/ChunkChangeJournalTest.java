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

  /**
   * 单次放行的补发上限必须能覆盖「一个 section 在同一窗口内的全部变更」。
   *
   * <p>旧值 16 是随手取的：一次爆炸 / 大范围施工在同一段内改掉几十上百格时，超出部分既不补发、
   * 也没有任何别的路径会来纠正——客户端就永久保留旧区块字节（真机表现：整段幽匿脉络等附着方块
   * 卡在空中，右键被服务端纠正后才消失）。上限只该由环形容量兜底，不该是任意小值。
   */
  @Test
  void replayCoversAWholeSectionOfSimultaneousChanges() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    for (int i = 0; i < 200; i++) {
      // 全部落在同一区块 (0,0)：x/z 取 0..15，y 递增避免同格去重
      journal.record("world", i & 15, 64 + (i >> 4), i & 15);
    }
    List<int[]> cells = journal.changedSince(0L, "world", 0, 0);
    assertEquals(200, cells.size(),
        "同一段内的一次性批量变更必须整批补发；上限只该是环形容量，不能是 16");
    assertTrue(ChunkChangeJournal.MAX_REPLAY >= 4096,
        "单次补发上限至少覆盖一个 section 的 4096 格（爆炸/大范围施工的真实量级）");
  }

  @Test
  void ringWrapKeepsServingTheNewestChanges() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    // 录制量必须显著大于环形容量（否则「覆盖之后仍能取到最新一条」根本没被验证）
    for (int i = 0; i < 20_000; i++) {
      journal.record("world", i & 15, 64, 0);
    }
    long mark = journal.sequence();
    journal.record("world", 3, 70, 3);
    List<int[]> cells = journal.changedSince(mark, "world", 0, 0);
    assertEquals(1, cells.size(), "环形覆盖之后仍能取到最新一条");
    assertArrayEquals(new int[] {3, 70, 3}, cells.get(0));
  }
}