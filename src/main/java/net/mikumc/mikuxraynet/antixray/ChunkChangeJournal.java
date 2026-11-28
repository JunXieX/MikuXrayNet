package net.mikumc.mikuxraynet.antixray;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 每个接收者的在途区块变更。只保留坐标；状态在区块写出后由拥有区块的线程读取。 */
public final class ChunkChangeJournal {

  private static final int MAX_COLUMNS = 256;
  private static final int MAX_SECTIONS = 256;
  private static final int MAX_DIRTY_SECTIONS_PER_RECEIVER = 1024;
  private static final long EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(35);

  record Column(String world, int x, int z) {
  }

  static final class Receiver {
    private final LinkedHashMap<Column, Ticket> columns = new LinkedHashMap<>();
    private final LinkedHashSet<Ticket> ready = new LinkedHashSet<>();
    private boolean draining;
    private int dirtySections;
  }

  static final class Ticket {
    final Receiver receiver;
    final Column column;
    private final long started = System.nanoTime();
    private TreeMap<Integer, BitSet> dirty;
    private TreeMap<Integer, java.util.HashMap<Integer, Long>> revisions;
    private boolean written;
    private boolean truncated;
    private long revision;
    private long captureRevision;
    private boolean evicted;
    private boolean wholeColumnRefresh;
    private long refreshRevision;
    private long truncatedRevision;
    int retries;

    private Ticket(Receiver receiver, Column column) {
      this.receiver = receiver;
      this.column = column;
    }
  }

  record Written(boolean current, boolean dirty, boolean schedule, boolean truncated) {
    private static final Written STALE = new Written(false, false, false, false);
  }

  private final ConcurrentHashMap<UUID, Receiver> recipients = new ConcurrentHashMap<>();
  private final int maxDirtySections;
  private final CoverageLoss onEvicted;

  @FunctionalInterface
  interface CoverageLoss {
    void onCoverageLost(UUID player, Column column);
  }

  public ChunkChangeJournal() {
    this(MAX_DIRTY_SECTIONS_PER_RECEIVER, () -> { });
  }

  ChunkChangeJournal(Runnable onEvicted) {
    this(MAX_DIRTY_SECTIONS_PER_RECEIVER, onEvicted);
  }

  ChunkChangeJournal(CoverageLoss onEvicted) {
    this(MAX_DIRTY_SECTIONS_PER_RECEIVER, onEvicted);
  }

  ChunkChangeJournal(int maxDirtySections, Runnable onEvicted) {
    this(maxDirtySections, (player, column) -> onEvicted.run());
  }

  ChunkChangeJournal(int maxDirtySections, CoverageLoss onEvicted) {
    this.maxDirtySections = maxDirtySections;
    this.onEvicted = onEvicted;
  }

  Ticket open(UUID player, String world, int x, int z) {
    Receiver receiver = recipients.computeIfAbsent(player, ignored -> new Receiver());
    Column column = new Column(world, x, z);
    Ticket next = new Ticket(receiver, column);
    List<Column> evicted = null;
    synchronized (receiver) {
      Ticket previous = receiver.columns.remove(column);
      if (previous != null) {
        receiver.ready.remove(previous);
        if (previous.wholeColumnRefresh) {
          inheritAfterWholeColumnRefresh(previous, next);
        } else {
          // 普通新快照继承尚未修复的格子；旧回调不再有权补发。
          next.dirty = previous.dirty;
          next.revisions = previous.revisions;
          next.revision = previous.revision;
          next.truncated = previous.truncated;
          next.truncatedRevision = previous.truncatedRevision;
          previous.dirty = null;
          previous.revisions = null;
        }
      }
      while (!receiver.columns.isEmpty()) {
        Ticket head = receiver.columns.firstEntry().getValue();
        if (receiver.columns.size() < MAX_COLUMNS && next.started - head.started < EXPIRY_NANOS) {
          break;
        }
        if (hasDirty(head) || head.truncated) {
          if (evicted == null) {
            evicted = new ArrayList<>();
          }
          evicted.add(head.column);
          head.evicted = true;
        }
        remove(head);
      }
      receiver.columns.put(column, next);
    }
    if (evicted != null) {
      // 调度与整列刷新一律在 journal 锁外，Paper 上区域回调可能立即执行。
      for (Column lost : evicted) {
        onEvicted.onCoverageLost(player, lost);
      }
    }
    return next;
  }

  boolean isWatching(UUID player, String world, int x, int z) {
    Receiver receiver = recipients.get(player);
    if (receiver == null) {
      return false;
    }
    synchronized (receiver) {
      if (receiver.columns.isEmpty()) {
        return false;
      }
      Ticket ticket = receiver.columns.get(new Column(world, x, z));
      return ticket != null;
    }
  }

  void record(UUID player, String world, int x, int y, int z) {
    Receiver receiver = recipients.get(player);
    if (receiver != null) {
      synchronized (receiver) {
        if (!receiver.columns.isEmpty()) {
          set(receiver.columns.get(new Column(world, x >> 4, z >> 4)), x, y, z);
        }
      }
    }
  }

  /** 多格更新总在同一个区块段内，只取一次锁。 */
  void recordSection(UUID player, String world, int[] coordinates, int count) {
    Receiver receiver = recipients.get(player);
    if (receiver != null && count > 0) {
      synchronized (receiver) {
        if (receiver.columns.isEmpty()) {
          return;
        }
        Ticket ticket = receiver.columns.get(new Column(world, coordinates[0] >> 4, coordinates[2] >> 4));
        if (ticket == null) {
          return;
        }
        for (int i = 0; i < count; i++) {
          set(ticket, coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]);
        }
      }
    }
  }

  private void set(Ticket ticket, int x, int y, int z) {
    if (ticket == null) {
      return;
    }
    int section = y >> 4;
    BitSet cells = ticket.dirty == null ? null : ticket.dirty.get(section);
    if (cells == null) {
      if (ticket.receiver.dirtySections >= maxDirtySections
          || (ticket.dirty != null && ticket.dirty.size() >= MAX_SECTIONS)) {
        ticket.truncated = true;
        ticket.truncatedRevision = ++ticket.revision;
        return;
      }
      if (ticket.dirty == null) {
        ticket.dirty = new TreeMap<>();
      }
      cells = new BitSet(4096);
      ticket.dirty.put(section, cells);
      if (ticket.revisions == null) {
        ticket.revisions = new TreeMap<>();
      }
      ticket.revisions.put(section, new java.util.HashMap<>());
      ticket.receiver.dirtySections++;
    }
    int bit = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    cells.set(bit);
    ticket.revisions.get(section).put(bit, ++ticket.revision);
  }

  /** 只有出站写回调才能封存快照；改写任务声明 ready 并不是这个时点。 */
  Written written(UUID player, Ticket ticket) {
    Receiver receiver = ticket.receiver;
    synchronized (receiver) {
      if (recipients.get(player) != receiver || !current(ticket) || ticket.written) {
        return Written.STALE;
      }
      ticket.written = true;
      if (!hasDirty(ticket) && !ticket.truncated) {
        remove(ticket);
        return new Written(true, false, false, false);
      }
      // 截断即使没有任何可枚举脏格也必须保持 ready，直到整列刷新已提交。
      receiver.ready.add(ticket);
      if (receiver.draining) {
        return new Written(true, true, false, ticket.truncated);
      }
      receiver.draining = true;
      return new Written(true, true, true, ticket.truncated);
    }
  }

  /**
   * 该 ticket 是否还有未补发的格子（只用于「覆盖丢失」归因，不参与任何放行判定）。
   *
   * <p>调用点都在「确认已到、但这一列已经补不出去」的少数路径上，锁内只有一次布尔读，不进逐格循环。
   */
  boolean hasPending(Ticket ticket) {
    synchronized (ticket.receiver) {
      return hasDirty(ticket) || ticket.truncated;
    }
  }

  boolean isTruncated(Ticket ticket) {
    synchronized (ticket.receiver) {
      return ticket.truncated;
    }
  }

  boolean wasEvicted(Ticket ticket) {
    synchronized (ticket.receiver) {
      return ticket.evicted;
    }
  }

  void prepareWholeColumnRefresh(Ticket ticket) {
    synchronized (ticket.receiver) {
      if (current(ticket)) {
        ticket.wholeColumnRefresh = true;
        ticket.refreshRevision = ticket.revision;
      }
    }
  }

  Ticket prepareWholeColumnRefresh(UUID player, Column column) {
    Receiver receiver = recipients.get(player);
    if (receiver == null) {
      return null;
    }
    synchronized (receiver) {
      Ticket ticket = receiver.columns.get(column);
      if (ticket != null) {
        ticket.wholeColumnRefresh = true;
        ticket.refreshRevision = ticket.revision;
      }
      return ticket;
    }
  }

  void completeEvictedRefresh(Ticket ticket) {
    if (ticket == null) {
      return;
    }
    synchronized (ticket.receiver) {
      if (current(ticket)) {
        // 没有自-trigger 新包时保留原票的正常 written/replay 权利；旧包迟到仍能收敛。
        ticket.wholeColumnRefresh = false;
      }
    }
  }

  void cancelWholeColumnRefresh(Ticket ticket) {
    if (ticket == null) {
      return;
    }
    synchronized (ticket.receiver) {
      ticket.wholeColumnRefresh = false;
    }
  }

  boolean isCurrent(UUID player, Ticket ticket) {
    Receiver receiver = ticket.receiver;
    synchronized (receiver) {
      return recipients.get(player) == receiver && current(ticket);
    }
  }

  Ticket poll(Receiver receiver) {
    synchronized (receiver) {
      java.util.Iterator<Ticket> iterator = receiver.ready.iterator();
      if (!iterator.hasNext()) {
        return null;
      }
      Ticket next = iterator.next();
      iterator.remove();
      return next;
    }
  }

  /** 调度失败时只读快照；票仍留在 ready，由整列刷新逐项收账。 */
  List<Ticket> ready(Receiver receiver) {
    synchronized (receiver) {
      return receiver.ready.isEmpty() ? List.of() : new ArrayList<>(receiver.ready);
    }
  }

  /** 读出下一批坐标，保留余下的位，避免大批变化在同一个 tick 集中读世界。 */
  int take(Ticket ticket, int max, int[] out) {
    return take(ticket, max, out, null);
  }

  int take(Ticket ticket, int max, int[] out, long[] expectedRevisions) {
    synchronized (ticket.receiver) {
      if (!current(ticket) || !ticket.written || !hasDirty(ticket)) {
        return 0;
      }
      ticket.captureRevision = ticket.revision;
      int count = 0;
      for (var section : ticket.dirty.entrySet()) {
        if (count >= max) {
          break;
        }
        BitSet cells = section.getValue();
        for (int bit = cells.nextSetBit(0); bit >= 0 && count < max; bit = cells.nextSetBit(bit + 1)) {
          out[count * 3] = (ticket.column.x() << 4) | (bit & 15);
          out[count * 3 + 1] = (section.getKey() << 4) | ((bit >>> 8) & 15);
          out[count * 3 + 2] = (ticket.column.z() << 4) | ((bit >>> 4) & 15);
          if (expectedRevisions != null) {
            expectedRevisions[count] = ticket.revisions.get(section.getKey()).get(bit);
          }
          count++;
        }
      }
      return count;
    }
  }

  long captureRevision(Ticket ticket) {
    synchronized (ticket.receiver) {
      return ticket.captureRevision;
    }
  }

  /** 逐格确认：无关格的新变更不会阻塞本批，变更格保留到下一轮。 */
  void acknowledge(Ticket ticket, int[] cells, int count, long[] expectedRevisions) {
    synchronized (ticket.receiver) {
      if (!current(ticket) || ticket.dirty == null) {
        return;
      }
      for (int i = 0; i < count * 3; i += 3) {
        int section = cells[i + 1] >> 4;
        int bit = ((cells[i + 1] & 15) << 8) | ((cells[i + 2] & 15) << 4) | (cells[i] & 15);
        java.util.HashMap<Integer, Long> versions = ticket.revisions.get(section);
        BitSet bits = ticket.dirty.get(section);
        Long version = versions == null ? null : versions.get(bit);
        if (version != null && bits != null && version == expectedRevisions[i / 3]) {
          bits.clear(bit);
          versions.remove(bit);
          if (bits.isEmpty()) {
            ticket.dirty.remove(section);
            ticket.revisions.remove(section);
            ticket.receiver.dirtySections--;
          }
        }
      }
    }
  }

  /** 兼容旧调用：使用最近一次 take 固定的全局代次。 */
  void acknowledge(Ticket ticket, int[] cells, int count) {
    acknowledge(ticket, cells, count, captureRevision(ticket));
  }

  /** 只有发送成功且取样后没有新修改才清位；代次不符时整批保留到下一轮。 */
  void acknowledge(Ticket ticket, int[] cells, int count, long expectedRevision) {
    synchronized (ticket.receiver) {
      if (!current(ticket) || ticket.dirty == null || ticket.revision != expectedRevision) {
        return;
      }
      for (int i = 0; i < count * 3; i += 3) {
        int section = cells[i + 1] >> 4;
        BitSet bits = ticket.dirty.get(section);
        if (bits != null) {
          bits.clear(((cells[i + 1] & 15) << 8) | ((cells[i + 2] & 15) << 4) | (cells[i] & 15));
          if (bits.isEmpty()) {
            ticket.dirty.remove(section);
            ticket.receiver.dirtySections--;
          }
        }
      }
    }
  }

  /** 把有余量的区块放回队尾；完成或明确失效时收账。 */
  boolean finish(Ticket ticket, boolean retry) {
    synchronized (ticket.receiver) {
      if (!current(ticket)) {
        return false;
      }
      if (retry && (hasDirty(ticket) || ticket.truncated)) {
        ticket.receiver.ready.add(ticket);
        return false;
      }
      remove(ticket);
      return true;
    }
  }

  boolean continueDrain(Receiver receiver) {
    synchronized (receiver) {
      if (!receiver.ready.isEmpty()) {
        return true;
      }
      receiver.draining = false;
      return false;
    }
  }

  void clearOtherWorlds(UUID player, String world) {
    Receiver receiver = recipients.get(player);
    if (receiver != null) {
      synchronized (receiver) {
        receiver.columns.values().removeIf(ticket -> {
          if (ticket.column.world().equals(world)) {
            return false;
          }
          releaseDirty(ticket);
          return true;
        });
        receiver.ready.removeIf(ticket -> !ticket.column.world().equals(world));
      }
    }
  }

  void clear(UUID player) {
    Receiver receiver = recipients.remove(player);
    if (receiver != null) {
      synchronized (receiver) {
        receiver.columns.values().forEach(ChunkChangeJournal::releaseDirty);
        receiver.columns.clear();
        receiver.ready.clear();
      }
    }
  }

  void clear() {
    for (UUID player : recipients.keySet()) {
      clear(player);
    }
  }

  private static boolean current(Ticket ticket) {
    return ticket.receiver.columns.get(ticket.column) == ticket;
  }

  private static boolean hasDirty(Ticket ticket) {
    return ticket.dirty != null && !ticket.dirty.isEmpty();
  }

  private static void remove(Ticket ticket) {
    ticket.receiver.columns.remove(ticket.column, ticket);
    ticket.receiver.ready.remove(ticket);
    releaseDirty(ticket);
  }

  /** 整列快照只覆盖屏障前状态；屏障后的变化仍逐格跟随后一个 ticket。 */
  private static void inheritAfterWholeColumnRefresh(Ticket previous, Ticket next) {
    long barrier = previous.refreshRevision;
    next.revision = previous.revision;
    next.truncated = previous.truncated && previous.truncatedRevision > barrier;
    next.truncatedRevision = next.truncated ? previous.truncatedRevision : 0L;
    if (previous.dirty != null) {
      for (var section : previous.dirty.entrySet()) {
        var versions = previous.revisions.get(section.getKey());
        for (int bit = section.getValue().nextSetBit(0); bit >= 0;
            bit = section.getValue().nextSetBit(bit + 1)) {
          long revision = versions.get(bit);
          if (revision <= barrier) {
            continue;
          }
          if (next.dirty == null) {
            next.dirty = new TreeMap<>();
            next.revisions = new TreeMap<>();
          }
          BitSet cells = next.dirty.computeIfAbsent(section.getKey(), ignored -> new BitSet(4096));
          var nextVersions = next.revisions.computeIfAbsent(section.getKey(),
              ignored -> new java.util.HashMap<Integer, Long>());
          if (cells.isEmpty()) {
            next.receiver.dirtySections++;
          }
          cells.set(bit);
          nextVersions.put(bit, revision);
        }
      }
    }
    releaseDirty(previous);
    previous.truncated = false;
  }

  private static void releaseDirty(Ticket ticket) {
    if (ticket.dirty != null) {
      ticket.receiver.dirtySections -= ticket.dirty.size();
      ticket.dirty = null;
      ticket.revisions = null;
    }
  }
}
