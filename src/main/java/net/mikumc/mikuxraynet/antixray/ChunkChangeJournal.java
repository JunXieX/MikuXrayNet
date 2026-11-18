package net.mikumc.mikuxraynet.antixray;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.TreeMap;
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
    private boolean written;
    private boolean truncated;
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
  private final Runnable onEvicted;

  public ChunkChangeJournal() {
    this(MAX_DIRTY_SECTIONS_PER_RECEIVER, () -> { });
  }

  ChunkChangeJournal(Runnable onEvicted) {
    this(MAX_DIRTY_SECTIONS_PER_RECEIVER, onEvicted);
  }

  ChunkChangeJournal(int maxDirtySections, Runnable onEvicted) {
    this.maxDirtySections = maxDirtySections;
    this.onEvicted = onEvicted;
  }

  Ticket open(UUID player, String world, int x, int z) {
    Receiver receiver = recipients.computeIfAbsent(player, ignored -> new Receiver());
    Column column = new Column(world, x, z);
    Ticket next = new Ticket(receiver, column);
    synchronized (receiver) {
      Ticket previous = receiver.columns.remove(column);
      if (previous != null) {
        receiver.ready.remove(previous);
        // 尚未修复的格子也要跟随后一个区块快照；旧回调不再有权补发。
        next.dirty = previous.dirty;
        next.truncated = previous.truncated;
        previous.dirty = null;
      }
      while (!receiver.columns.isEmpty()) {
        Ticket head = receiver.columns.firstEntry().getValue();
        if (receiver.columns.size() < MAX_COLUMNS && next.started - head.started < EXPIRY_NANOS) {
          break;
        }
        if (hasDirty(head) || head.truncated) {
          onEvicted.run();
        }
        remove(head);
      }
      receiver.columns.put(column, next);
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
      return ticket != null && !ticket.written;
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
        if (ticket == null || ticket.written) {
          return;
        }
        for (int i = 0; i < count; i++) {
          set(ticket, coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]);
        }
      }
    }
  }

  private void set(Ticket ticket, int x, int y, int z) {
    if (ticket == null || ticket.written) {
      return;
    }
    int section = y >> 4;
    BitSet cells = ticket.dirty == null ? null : ticket.dirty.get(section);
    if (cells == null) {
      if (ticket.receiver.dirtySections >= maxDirtySections
          || (ticket.dirty != null && ticket.dirty.size() >= MAX_SECTIONS)) {
        ticket.truncated = true;
        return;
      }
      if (ticket.dirty == null) {
        ticket.dirty = new TreeMap<>();
      }
      cells = new BitSet(4096);
      ticket.dirty.put(section, cells);
      ticket.receiver.dirtySections++;
    }
    cells.set(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
  }

  /** 只有出站写回调才能封存快照；改写任务声明 ready 并不是这个时点。 */
  Written written(UUID player, Ticket ticket) {
    Receiver receiver = ticket.receiver;
    synchronized (receiver) {
      if (recipients.get(player) != receiver || !current(ticket) || ticket.written) {
        return Written.STALE;
      }
      ticket.written = true;
      if (!hasDirty(ticket)) {
        remove(ticket);
        return new Written(true, false, false, ticket.truncated);
      }
      receiver.ready.add(ticket);
      if (receiver.draining) {
        return new Written(true, true, false, ticket.truncated);
      }
      receiver.draining = true;
      return new Written(true, true, true, ticket.truncated);
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

  /** 读出下一批坐标，保留余下的位，避免大批变化在同一个 tick 集中读世界。 */
  int take(Ticket ticket, int max, int[] out) {
    synchronized (ticket.receiver) {
      if (!current(ticket) || !ticket.written || !hasDirty(ticket)) {
        return 0;
      }
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
          count++;
        }
      }
      return count;
    }
  }

  /** 只有补发提交成功后才移除坐标，让取样之后到达的新区块能完整接手。 */
  void acknowledge(Ticket ticket, int[] cells, int count) {
    synchronized (ticket.receiver) {
      if (!current(ticket) || ticket.dirty == null) {
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
      if (retry && hasDirty(ticket)) {
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

  void abandonDrain(Receiver receiver) {
    synchronized (receiver) {
      for (Ticket ticket : new java.util.ArrayList<>(receiver.ready)) {
        remove(ticket);
      }
      receiver.ready.clear();
      receiver.draining = false;
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

  private static void releaseDirty(Ticket ticket) {
    if (ticket.dirty != null) {
      ticket.receiver.dirtySections -= ticket.dirty.size();
      ticket.dirty = null;
    }
  }
}
