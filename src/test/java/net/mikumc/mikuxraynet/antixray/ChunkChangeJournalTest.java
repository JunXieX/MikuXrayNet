package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkChangeJournalTest {

  private static final UUID FIRST = UUID.randomUUID();
  private static final UUID SECOND = UUID.randomUUID();

  @Test
  void onlyActualWriteMakesThatRecipientsChangesReady() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    var a = journal.open(FIRST, "world", -1, 0);
    var b = journal.open(SECOND, "world", -1, 0);
    journal.record(FIRST, "world", -15, -62, 3);
    assertNull(journal.poll(a.receiver));
    // 改写已计算好却还在连接队列：这时发生的批量变化也必须留下。
    journal.recordSection(FIRST, "world", new int[] {-15, -62, 3, -14, -61, 3}, 2);
    journal.record(FIRST, "other", -15, -62, 3);
    journal.record(FIRST, "world", 1, -62, 3);
    assertTrue(journal.written(FIRST, a).schedule());
    assertFalse(journal.written(SECOND, b).dirty());
    journal.record(FIRST, "world", -13, -60, 3); // 区块写出之后的更新已在正确顺序，不应再补。
    assertSame(a, journal.poll(a.receiver));
    int[] cells = new int[6];
    assertEquals(2, journal.take(a, 2, cells));
    assertArrayEquals(new int[] {-15, -62, 3, -14, -61, 3}, cells);
    journal.acknowledge(a, cells, 2);
    assertTrue(journal.finish(a, true));
    assertFalse(journal.continueDrain(a.receiver));
  }

  @Test
  void burstContinuesInBoundedSlicesWithoutForgettingOtherSections() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    var ticket = journal.open(FIRST, "world", 0, 0);
    for (int i = 0; i < 5000; i++) {
      journal.record(FIRST, "world", i & 15, (i >> 8) - 64, (i >> 4) & 15);
    }
    assertFalse(journal.written(FIRST, ticket).truncated());
    int[] cells = new int[128 * 3];
    int seen = 0;
    while (journal.continueDrain(ticket.receiver)) {
      assertSame(ticket, journal.poll(ticket.receiver));
      int count = journal.take(ticket, 128, cells);
      assertTrue(count > 0 && count <= 128);
      seen += count;
      assertTrue(seen <= 5000);
      journal.acknowledge(ticket, cells, count);
      journal.finish(ticket, true);
    }
    assertEquals(5000, seen);
  }

  @Test
  void newSnapshotInheritsUnacknowledgedCoordsAndRejectsOldCallbacks() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    var old = journal.open(FIRST, "world", 0, 0);
    journal.record(FIRST, "world", 1, 2, 3);
    assertTrue(journal.written(FIRST, old).schedule());
    assertSame(old, journal.poll(old.receiver));
    int[] cells = new int[3];
    assertEquals(1, journal.take(old, 1, cells));
    var newer = journal.open(FIRST, "world", 0, 0);
    journal.acknowledge(old, cells, 1);
    assertFalse(journal.written(FIRST, old).current());
    assertTrue(journal.written(FIRST, newer).dirty());
    assertSame(newer, journal.poll(newer.receiver));
    assertEquals(1, journal.take(newer, 1, cells));
    assertArrayEquals(new int[] {1, 2, 3}, cells);
  }

  @Test
  void worldSwitchAndReconnectInvalidateStaleTickets() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    var old = journal.open(FIRST, "old", 0, 0);
    var next = journal.open(FIRST, "next", 0, 0);
    journal.clearOtherWorlds(FIRST, "next");
    assertFalse(journal.written(FIRST, old).current());
    assertTrue(journal.isCurrent(FIRST, next));
    journal.clear(FIRST);
    assertFalse(journal.written(FIRST, next).current());
  }
  @Test
  void dirtySectionLimitIsSharedAcrossColumnsAndReleasedOnAcknowledgement() {
    ChunkChangeJournal journal = new ChunkChangeJournal(2, () -> { });
    var first = journal.open(FIRST, "world", 0, 0);
    var second = journal.open(FIRST, "world", 1, 0);
    journal.record(FIRST, "world", 1, 2, 3);
    journal.record(FIRST, "world", 17, 2, 3);
    journal.record(FIRST, "world", 17, 16, 3);
    assertTrue(journal.written(FIRST, second).truncated());
    assertTrue(journal.written(FIRST, first).dirty());
    int[] cells = new int[3];
    assertEquals(1, journal.take(first, 1, cells));
    journal.acknowledge(first, cells, 1);
    var later = journal.open(FIRST, "world", 2, 0);
    journal.record(FIRST, "world", 33, 16, 3);
    assertFalse(journal.written(FIRST, later).truncated());
    assertEquals(1, journal.take(later, 1, cells));
  }

  /**
   * 「还有没有未补发的格子」是「覆盖丢失」归因的唯一判据：只有确认已到、却再也补不出去时才该为真。
   * 它只看本 ticket 的脏格，不参与任何放行判定。
   */
  @Test
  void pendingFlagTracksOnlyThisTicketsUnreplayedCells() {
    ChunkChangeJournal journal = new ChunkChangeJournal();
    var ticket = journal.open(FIRST, "world", 0, 0);
    assertFalse(journal.hasPending(ticket), "还没记录任何变更时不该算有在途更新");
    journal.record(FIRST, "world", 1, 2, 3);
    assertTrue(journal.hasPending(ticket));
    assertTrue(journal.written(FIRST, ticket).dirty());
    assertSame(ticket, journal.poll(ticket.receiver));
    int[] cells = new int[3];
    assertEquals(1, journal.take(ticket, 1, cells));
    journal.acknowledge(ticket, cells, 1);
    assertFalse(journal.hasPending(ticket), "补发提交成功后不得再报「有在途更新」");
    assertTrue(journal.finish(ticket, true));
  }

  /**
   * 淘汰口径：只有「确实还带着未补发格子」的列被容量/超时挤掉才该计入淘汰；
   * 同一列被新快照接手时脏格会随 {@code open} 转移到新 ticket，<b>不算</b>淘汰。
   * 这条口径直接决定线上「回调未结账淘汰」的计数是否可信。
   */
  @Test
  void evictionAccountingOnlyCountsColumnsThatStillHadCells() {
    AtomicInteger evictions = new AtomicInteger();
    ChunkChangeJournal journal = new ChunkChangeJournal(4096, evictions::incrementAndGet);
    var first = journal.open(FIRST, "world", 0, 0);
    journal.record(FIRST, "world", 1, 2, 3);

    // 同一列第二次下发：脏格转移到新 ticket，旧 ticket 不应记成淘汰。
    var replaced = journal.open(FIRST, "world", 0, 0);
    assertEquals(0, evictions.get(), "同列重发不是淘汰：脏格跟随后一个快照");
    assertTrue(journal.hasPending(replaced));
    assertFalse(journal.written(FIRST, first).current());

    // 用「没有脏格」的列把表填满，挤掉排在最前的有用列——只有它算淘汰。
    for (int i = 1; i <= 256; i++) {
      journal.open(FIRST, "world", i, 0);
    }
    assertEquals(1, evictions.get(), "只有仍带在途更新的列被挤掉才计数");
  }

  @Test
  void replacingAColumnKeepsItsCellsWithoutCountingEviction() {
    AtomicInteger evictions = new AtomicInteger();
    ChunkChangeJournal journal = new ChunkChangeJournal(4096, evictions::incrementAndGet);
    var first = journal.open(FIRST, "world", 3, 4);
    journal.record(FIRST, "world", 49, 2, 65);
    var second = journal.open(FIRST, "world", 3, 4);
    assertEquals(0, evictions.get());
    assertFalse(journal.written(FIRST, first).current());
    assertTrue(journal.written(FIRST, second).dirty());
    assertSame(second, journal.poll(second.receiver));
    int[] cells = new int[3];
    assertEquals(1, journal.take(second, 1, cells));
    assertArrayEquals(new int[] {49, 2, 65}, cells, "重发后脏格必须完整转交给新快照");
  }
}
