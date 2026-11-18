package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
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
}
