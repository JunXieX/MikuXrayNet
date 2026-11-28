package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkReplayCoordinatorTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final String HANDLE = "player";

  @Test
  void acceptedThenDroppedCaptureTimesOutAndLateCallbackIsInert() {
    Fixture fixture = new Fixture(1024);
    var first = fixture.openReady(0, 0);
    fixture.dropNextRegion = true;
    fixture.coordinator.written(HANDLE, PLAYER, first, fixture.lastWritten);
    fixture.runPlayer();
    assertEquals(1, fixture.droppedRegions.size());
    fixture.runGlobal();
    assertTrue(fixture.journal.isCurrent(PLAYER, first));
    assertEquals(1, fixture.players.size(), "超时必须完成 Round 并重排");

    var second = fixture.openReady(1, 0);
    fixture.coordinator.written(HANDLE, PLAYER, second, fixture.lastWritten);
    fixture.runAll();
    assertEquals(2, fixture.sends.size(), "同 Receiver 新列必须继续调度");
    int sent = fixture.sends.size();
    fixture.run(fixture.droppedRegions.removeFirst());
    assertEquals(sent, fixture.sends.size(), "迟到区域回调不得再次读取或发送");
  }

  @Test
  void acceptedThenDroppedRefreshTimesOutAndLateCallbackIsInert() {
    Fixture fixture = new Fixture(0);
    var ticket = fixture.journal.open(PLAYER, "world", 0, 0);
    fixture.journal.record(PLAYER, "world", 1, 2, 3);
    var written = fixture.journal.written(PLAYER, ticket);
    fixture.dropNextRegion = true;
    fixture.coordinator.written(HANDLE, PLAYER, ticket, written);
    fixture.runGlobal();
    assertTrue(fixture.journal.isCurrent(PLAYER, ticket));
    fixture.runAll();
    assertEquals(1, fixture.refreshes.size());
    fixture.run(fixture.droppedRegions.removeFirst());
    assertEquals(1, fixture.refreshes.size());
    assertFalse(fixture.journal.isCurrent(PLAYER, ticket));
  }

  @Test
  void sameNameDifferentWorldInstanceNeverSendsCapturedState() {
    Fixture fixture = new Fixture(1024);
    var ticket = fixture.openReady(0, 0);
    fixture.coordinator.written(HANDLE, PLAYER, ticket, fixture.lastWritten);
    fixture.runPlayer();
    fixture.runRegion();
    fixture.sessionWorld = "world-new-instance";
    fixture.runPlayer();
    assertTrue(fixture.sends.isEmpty());
    assertTrue(fixture.journal.isCurrent(PLAYER, ticket));
    fixture.runAll();
    assertEquals(2, fixture.regionReads.size(), "实例变化后必须重新取样");
    assertEquals(1, fixture.sends.size());
  }

  @Test
  void nonOwnedColumnReadsInChunkRegionAndReturnsToPlayerWithoutDroppingTicket() {
    Fixture fixture = new Fixture(1024);
    var ticket = fixture.openReady(0, 0);

    fixture.coordinator.written(HANDLE, PLAYER, ticket, fixture.lastWritten);
    fixture.runPlayer();
    assertEquals(1, fixture.regions.size());
    assertTrue(fixture.journal.isCurrent(PLAYER, ticket), "区域跳转之前不得 finish(false)");
    assertEquals(0, fixture.sends.size());

    fixture.runRegion();
    assertEquals(1, fixture.players.size());
    assertEquals(List.of("state@1:2:3"), fixture.regionReads);
    fixture.runPlayer();

    assertEquals(1, fixture.sends.size());
    assertEquals(List.of("state@1:2:3"), fixture.sends.get(0));
    assertFalse(fixture.journal.isCurrent(PLAYER, ticket));
    assertEquals(0, fixture.refreshes.size());
  }

  @Test
  void playerSchedulingFailureKeepsEveryReadyTicketAndRefreshesEachColumn() {
    Fixture fixture = new Fixture(1024);
    var first = fixture.openReady(0, 0);
    var firstWritten = fixture.lastWritten;
    var second = fixture.openReady(1, 0);
    fixture.failNextPlayer = true;

    fixture.coordinator.written(HANDLE, PLAYER, first, firstWritten);
    assertTrue(fixture.journal.isCurrent(PLAYER, first));
    assertTrue(fixture.journal.isCurrent(PLAYER, second));
    assertEquals(2, fixture.journal.ready(first.receiver).size(), "调度拒收不得清掉 receiver.ready");
    assertEquals(2, fixture.regions.size());

    fixture.runAllRegions();
    assertEquals(2, fixture.refreshes.size());
    assertFalse(fixture.journal.isCurrent(PLAYER, first));
    assertFalse(fixture.journal.isCurrent(PLAYER, second));
    assertTrue(fixture.sends.isEmpty());
  }

  @Test
  void readinessExhaustionUsesWholeColumnRefreshInsteadOfDiscardingDirtyCells() {
    Fixture fixture = new Fixture(1024);
    var ticket = fixture.openReady(0, 0);
    fixture.chunkSent = false;
    fixture.coordinator.written(HANDLE, PLAYER, ticket, fixture.lastWritten);

    for (int i = 0; i < ChunkReplayCoordinator.MAX_WAIT_TICKS; i++) {
      assertTrue(fixture.journal.isCurrent(PLAYER, ticket));
      fixture.runPlayer();
    }
    assertEquals(1, fixture.regions.size());
    fixture.runRegion();
    assertEquals(1, fixture.refreshes.size());
    assertFalse(fixture.journal.isCurrent(PLAYER, ticket));
    assertTrue(fixture.sends.isEmpty());
  }

  @Test
  void retiredOrStoppedPlayerNeverSendsFromLateCallbacks() {
    Fixture fixture = new Fixture(1024);
    var ticket = fixture.openReady(0, 0);
    fixture.coordinator.written(HANDLE, PLAYER, ticket, fixture.lastWritten);
    fixture.retireNextPlayer();
    fixture.runAll();
    assertTrue(fixture.sends.isEmpty());
    assertTrue(fixture.refreshes.isEmpty());
    assertFalse(fixture.journal.isCurrent(PLAYER, ticket));

    var stopped = fixture.openReady(2, 0);
    fixture.coordinator.written(HANDLE, PLAYER, stopped, fixture.lastWritten);
    fixture.active = false;
    fixture.runAll();
    assertTrue(fixture.sends.isEmpty());
    assertTrue(fixture.refreshes.isEmpty());
    assertFalse(fixture.journal.isCurrent(PLAYER, stopped));
  }

  @Test
  void evictionAndTruncationBothReachWholeColumnRefresh() {
    Fixture fixture = new Fixture(1024);
    var evicted = fixture.journal.open(PLAYER, "world", 7, 9);
    fixture.journal.record(PLAYER, "world", 113, 2, 145);
    for (int i = 0; i < 256; i++) {
      fixture.journal.open(PLAYER, "world", i + 100, 0);
    }
    assertFalse(fixture.journal.isCurrent(PLAYER, evicted));
    assertEquals(1, fixture.regions.size());
    fixture.runRegion();
    assertEquals(List.of("world:7:9"), fixture.refreshes);

    Fixture truncated = new Fixture(0);
    var ticket = truncated.journal.open(PLAYER, "world", 3, 4);
    truncated.journal.record(PLAYER, "world", 49, 2, 65);
    var written = truncated.journal.written(PLAYER, ticket);
    assertTrue(written.truncated());
    assertTrue(written.dirty(), "即使 dirty 位图放不下，截断票也必须进入 ready");
    truncated.coordinator.written(HANDLE, PLAYER, ticket, written);
    truncated.runRegion();
    assertEquals(List.of("world:3:4"), truncated.refreshes);
    assertFalse(truncated.journal.isCurrent(PLAYER, ticket));
  }

  @Test
  void truncatedTicketSurvivesFalseRefreshAndRetriesWholeColumn() {
    Fixture fixture = new Fixture(0);
    var ticket = fixture.journal.open(PLAYER, "world", 3, 4);
    fixture.journal.record(PLAYER, "world", 49, 2, 65);
    var written = fixture.journal.written(PLAYER, ticket);
    fixture.refreshResult = false;
    fixture.coordinator.written(HANDLE, PLAYER, ticket, written);
    fixture.runRegion();
    assertTrue(fixture.journal.isCurrent(PLAYER, ticket));
    assertEquals(1, fixture.players.size());

    fixture.refreshResult = true;
    fixture.runPlayer();
    assertEquals(1, fixture.regions.size());
    fixture.runRegion();
    assertEquals(2, fixture.refreshes.size());
    assertFalse(fixture.journal.isCurrent(PLAYER, ticket));
  }

  @Test
  void lateEvictedMapChunkTriggersAnotherRefreshAfterItsWrite() {
    Fixture fixture = new Fixture(1024);
    var old = fixture.journal.open(PLAYER, "world", 7, 9);
    fixture.journal.record(PLAYER, "world", 113, 2, 145);
    for (int i = 0; i < 256; i++) {
      fixture.journal.open(PLAYER, "world", i + 100, 0);
    }
    fixture.runRegion();
    assertEquals(1, fixture.refreshes.size());

    var late = fixture.journal.written(PLAYER, old);
    assertFalse(late.current());
    fixture.coordinator.written(HANDLE, PLAYER, old, late);
    fixture.runRegion();
    assertEquals(List.of("world:7:9", "world:7:9"), fixture.refreshes);
  }

  @Test
  void refreshSnapshotInheritsOnlyChangesAfterPrepareBarrier() {
    Fixture fixture = new Fixture(1024);
    var ticket = fixture.openReady(0, 0);
    fixture.chunkSent = false;
    fixture.coordinator.written(HANDLE, PLAYER, ticket, fixture.lastWritten);
    for (int i = 0; i < ChunkReplayCoordinator.MAX_WAIT_TICKS; i++) {
      fixture.runPlayer();
    }
    fixture.openOnRefresh = true;
    fixture.recordOnRefresh = true;
    fixture.runRegion();

    var replacement = fixture.refreshOpened;
    var written = fixture.journal.written(PLAYER, replacement);
    assertTrue(written.dirty());
    assertFalse(written.truncated());
    assertSame(replacement, fixture.journal.poll(replacement.receiver));
    int[] cells = new int[3];
    long[] revisions = new long[1];
    assertEquals(1, fixture.journal.take(replacement, 1, cells, revisions));
    assertEquals(2, cells[0]);
    assertEquals(3, cells[1]);
    assertEquals(4, cells[2]);
  }

  @Test
  void mapChunkProducedByRefreshStartsCleanInsteadOfRefreshingForever() {
    Fixture fixture = new Fixture(0);
    var old = fixture.journal.open(PLAYER, "world", 3, 4);
    fixture.journal.record(PLAYER, "world", 49, 2, 65);
    var truncated = fixture.journal.written(PLAYER, old);
    fixture.openOnRefresh = true;
    fixture.coordinator.written(HANDLE, PLAYER, old, truncated);
    fixture.runRegion();

    assertEquals(1, fixture.refreshes.size());
    var replacement = fixture.refreshOpened;
    var written = fixture.journal.written(PLAYER, replacement);
    assertTrue(written.current());
    assertFalse(written.truncated());
    assertFalse(written.dirty());
    assertTrue(fixture.regions.isEmpty());
  }

  @Test
  void evictedRefreshAlsoBreaksTruncationLoopWhenCurrentTicketExists() {
    Fixture fixture = new Fixture(0);
    var current = fixture.journal.open(PLAYER, "world", 3, 4);
    fixture.journal.record(PLAYER, "world", 49, 2, 65);
    fixture.journal.written(PLAYER, current);
    fixture.openOnRefresh = true;

    fixture.coordinator.evicted(PLAYER, current.column);
    fixture.runRegion();
    var replacement = fixture.refreshOpened;
    var written = fixture.journal.written(PLAYER, replacement);
    assertTrue(written.current());
    assertFalse(written.truncated());
    assertFalse(written.dirty());
    assertTrue(fixture.regions.isEmpty());
  }

  @Test
  void truncationAfterRefreshBarrierIsInherited() {
    Fixture fixture = new Fixture(0);
    var current = fixture.journal.open(PLAYER, "world", 3, 4);
    fixture.journal.record(PLAYER, "world", 49, 2, 65);
    fixture.journal.written(PLAYER, current);
    fixture.openOnRefresh = true;
    fixture.recordOnRefresh = true;

    fixture.coordinator.evicted(PLAYER, current.column);
    fixture.runRegion();
    var written = fixture.journal.written(PLAYER, fixture.refreshOpened);
    assertTrue(written.truncated(), "屏障后的新截断必须继续要求整列兜底");
  }

  @Test
  void replacementSnapshotRejectsOldCaptureAndOnlyNewStateCanBeSent() {
    Fixture fixture = new Fixture(1024);
    var old = fixture.openReady(0, 0);
    fixture.coordinator.written(HANDLE, PLAYER, old, fixture.lastWritten);
    fixture.runPlayer();
    assertEquals(1, fixture.regions.size());

    var newer = fixture.journal.open(PLAYER, "world", 0, 0);
    fixture.state = "new";
    fixture.runRegion(); // 旧 region 回调发现代次已变，不读旧态，也不回玩家发包。
    assertTrue(fixture.regionReads.isEmpty());
    assertTrue(fixture.players.isEmpty());

    var newWritten = fixture.journal.written(PLAYER, newer);
    fixture.coordinator.written(HANDLE, PLAYER, newer, newWritten);
    fixture.runPlayer();
    fixture.runRegion();
    fixture.runPlayer();
    assertEquals(List.of(List.of("new@1:2:3")), fixture.sends);
  }

  @Test
  void falseRefreshResultRetainsTicketAndRetriesInsteadOfAcknowledgingLoss() {
    Fixture fixture = new Fixture(1024);
    var ticket = fixture.openReady(0, 0);
    fixture.chunkSent = false;
    fixture.refreshResult = false;
    fixture.coordinator.written(HANDLE, PLAYER, ticket, fixture.lastWritten);
    for (int i = 0; i < ChunkReplayCoordinator.MAX_WAIT_TICKS; i++) {
      fixture.runPlayer();
    }
    fixture.runRegion();
    assertTrue(fixture.journal.isCurrent(PLAYER, ticket), "refreshChunk=false 表示没有刷新，不能收账");
    assertEquals(1, fixture.players.size(), "失败票必须延迟重排");

    fixture.refreshResult = true;
    fixture.runAll();
    assertFalse(fixture.journal.isCurrent(PLAYER, ticket));
  }

  @Test
  void unrelatedCellChangeDoesNotStarveBeyondFirstPage() {
    Fixture fixture = new Fixture(4096);
    var ticket = fixture.journal.open(PLAYER, "world", 0, 0);
    for (int i = 0; i < 300; i++) {
      fixture.journal.record(PLAYER, "world", i & 15, i >> 4, 0);
    }
    fixture.journal.written(PLAYER, ticket);
    fixture.journal.poll(ticket.receiver);
    int[] cells = new int[256 * 3];
    long[] revisions = new long[256];
    assertEquals(256, fixture.journal.take(ticket, 256, cells, revisions));
    fixture.journal.record(PLAYER, "world", 0, 0, 0); // 只改变第一页第一格

    fixture.journal.acknowledge(ticket, cells, 256, revisions);
    int[] rest = new int[256 * 3];
    long[] restRevisions = new long[256];
    int count = fixture.journal.take(ticket, 256, rest, restRevisions);
    assertEquals(45, count, "255 个未变格应收账，变更格与 44 个尾部格必须留下");
    boolean sawTail = false;
    for (int i = 0; i < count * 3; i += 3) {
      sawTail |= rest[i + 1] == 18;
    }
    assertTrue(sawTail, "第 299 格必须推进到下一页");
  }

  @Test
  void eachRoundRetainsCellAndColumnBudgets() {
    Fixture fixture = new Fixture(4096);
    ChunkChangeJournal.Ticket firstTicket = null;
    ChunkChangeJournal.Written firstWritten = null;
    for (int column = 0; column < 10; column++) {
      var ticket = fixture.journal.open(PLAYER, "world", column, 0);
      for (int cell = 0; cell < 100; cell++) {
        fixture.journal.record(PLAYER, "world", column * 16 + (cell & 15), (cell >> 4) & 15, 0);
      }
      var written = fixture.journal.written(PLAYER, ticket);
      if (firstTicket == null) {
        firstTicket = ticket;
        firstWritten = written;
      }
    }
    fixture.coordinator.written(HANDLE, PLAYER, firstTicket, firstWritten);
    fixture.runPlayer();
    assertEquals(8, fixture.regions.size());
    fixture.runAllRegions();
    fixture.runAllPlayers();
    assertEquals(256, fixture.sends.stream().mapToInt(List::size).sum());
    assertTrue(fixture.players.size() <= 1, "每轮只应派生一个下一轮玩家任务");
  }

  private static final class Fixture implements ChunkReplayCoordinator.Bridge<String, String, String> {
    private enum Kind { PLAYER, REGION, GLOBAL }
    private record Task(Kind kind, Runnable action, Runnable retired) {
    }

    private final ArrayDeque<Task> players = new ArrayDeque<>();
    private final ArrayDeque<Task> regions = new ArrayDeque<>();
    private final ArrayDeque<Task> globals = new ArrayDeque<>();
    private final ArrayDeque<Task> droppedRegions = new ArrayDeque<>();
    private final List<String> regionReads = new ArrayList<>();
    private final List<List<String>> sends = new ArrayList<>();
    private final List<String> refreshes = new ArrayList<>();
    private final ChunkChangeJournal journal;
    private final ChunkReplayCoordinator<String, String, String> coordinator;
    private ChunkChangeJournal.Written lastWritten;
    private Kind current;
    private boolean active = true;
    private boolean online = true;
    private boolean chunkSent = true;
    private boolean failNextPlayer;
    private boolean dropNextRegion;
    private boolean refreshResult = true;
    private boolean openOnRefresh;
    private boolean recordOnRefresh;
    private ChunkChangeJournal.Ticket refreshOpened;
    private String state = "state";
    private String sessionWorld = "world";

    private Fixture(int maxDirtySections) {
      journal = new ChunkChangeJournal(maxDirtySections, this::evicted);
      coordinator = new ChunkReplayCoordinator<>(journal, this);
    }

    private void evicted(UUID player, ChunkChangeJournal.Column column) {
      coordinator.evicted(player, column);
    }

    private ChunkChangeJournal.Ticket openReady(int x, int z) {
      var ticket = journal.open(PLAYER, "world", x, z);
      journal.record(PLAYER, "world", x * 16 + 1, 2, z * 16 + 3);
      lastWritten = journal.written(PLAYER, ticket);
      return ticket;
    }

    private void runPlayer() {
      run(players.removeFirst());
    }

    private void runRegion() {
      run(regions.removeFirst());
    }

    private void runGlobal() {
      run(globals.removeFirst());
    }

    private void run(Task task) {
      current = task.kind;
      try {
        task.action.run();
      } finally {
        current = null;
      }
    }

    private void runAllRegions() {
      while (!regions.isEmpty()) {
        runRegion();
      }
    }

    private void runAllPlayers() {
      int initial = players.size();
      for (int i = 0; i < initial; i++) {
        runPlayer();
      }
    }

    private void runAll() {
      int guard = 100;
      while ((!players.isEmpty() || !regions.isEmpty() || !globals.isEmpty()) && guard-- > 0) {
        if (!regions.isEmpty()) {
          runRegion();
        } else if (!players.isEmpty()) {
          runPlayer();
        } else {
          run(globals.removeFirst());
        }
      }
      assertTrue(guard > 0, "测试调度器出现不收敛任务");
    }

    private void retireNextPlayer() {
      players.getFirst().retired.run();
    }

    private void require(Kind kind) {
      assertEquals(kind, current, "Bukkit API 落在错误线程");
    }

    @Override
    public boolean active() {
      return active;
    }

    @Override
    public ChunkReplayCoordinator.Session<String> session(String player) {
      require(Kind.PLAYER);
      return online ? new ChunkReplayCoordinator.Session<>(sessionWorld, "world") : null;
    }

    @Override
    public String world(UUID playerId, ChunkChangeJournal.Column column) {
      return online && column.world().equals("world") ? sessionWorld : null;
    }

    @Override
    public boolean schedulePlayer(String player, Runnable task, Runnable retired, long delayTicks) {
      if (failNextPlayer) {
        failNextPlayer = false;
        return false;
      }
      players.addLast(new Task(Kind.PLAYER, task, retired));
      return true;
    }

    @Override
    public void scheduleRegion(String world, ChunkChangeJournal.Column column, Runnable task) {
      Task scheduled = new Task(Kind.REGION, task, null);
      if (dropNextRegion) {
        dropNextRegion = false;
        droppedRegions.addLast(scheduled);
      } else {
        regions.addLast(scheduled);
      }
    }

    @Override
    public boolean scheduleGlobal(Runnable task, long delayTicks) {
      globals.addLast(new Task(Kind.GLOBAL, task, null));
      return true;
    }

    @Override
    public boolean chunkSent(String player, ChunkChangeJournal.Column column) {
      require(Kind.PLAYER);
      return chunkSent;
    }

    @Override
    public boolean chunkLoaded(String world, ChunkChangeJournal.Column column) {
      require(Kind.REGION);
      return true;
    }

    @Override
    public String blockData(String world, int x, int y, int z) {
      require(Kind.REGION);
      String value = state + "@" + x + ":" + y + ":" + z;
      regionReads.add(value);
      return value;
    }

    @Override
    public void send(String player, ChunkChangeJournal.Column column, int[] cells, int count,
        List<String> states) {
      require(Kind.PLAYER);
      sends.add(List.copyOf(states));
    }

    @Override
    public boolean refresh(String world, ChunkChangeJournal.Column column) {
      require(Kind.REGION);
      refreshes.add(column.world() + ":" + column.x() + ":" + column.z());
      if (recordOnRefresh) {
        recordOnRefresh = false;
        journal.record(PLAYER, column.world(), column.x() * 16 + 2, 3, column.z() * 16 + 4);
      }
      if (openOnRefresh) {
        openOnRefresh = false;
        refreshOpened = journal.open(PLAYER, column.world(), column.x(), column.z());
      }
      return refreshResult;
    }

    @Override
    public void delivered(String player, ChunkChangeJournal.Column column) {
      require(Kind.PLAYER);
    }

    @Override
    public void failed(ChunkChangeJournal.Column column, String stage, Throwable throwable) {
    }

    @Override
    public void retire(UUID playerId) {
      journal.clear(playerId);
    }
  }
}
