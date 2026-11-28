package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在途方块补发的线程编排。玩家 API 只在玩家任务里调用，世界 API 只在目标列任务里调用。
 *
 * <p>泛型与 {@link Bridge} 把调度器和世界句柄隔离在服务端适配层，离线测试可以实际驱动每一跳，
 * 不需要用源码形状代替线程行为。
 */
final class ChunkReplayCoordinator<P, W, B> {

  static final int MAX_CELLS_PER_TICK = 256;
  static final int MAX_COLUMNS_PER_TICK = 8;
  static final int MAX_WAIT_TICKS = 4;
  private static final long REGION_TIMEOUT_TICKS = 20L;

  /** 同一次玩家任务里读取的会话；离开该任务后不得再从玩家实体补读世界。 */
  record Session<W>(W world, String worldName) {
  }

  interface Bridge<P, W, B> {
    boolean active();

    /** 玩家任务：在线且可读实体时返回会话，否则 null。 */
    Session<W> session(P player);

    /** 任意线程：从不可变世界索引取目标列的 World；会话不符或已退出返回 null。 */
    W world(UUID playerId, ChunkChangeJournal.Column column);

    boolean schedulePlayer(P player, Runnable task, Runnable retired, long delayTicks);

    void scheduleRegion(W world, ChunkChangeJournal.Column column, Runnable task);

    boolean scheduleGlobal(Runnable task, long delayTicks);

    /** 玩家任务。 */
    boolean chunkSent(P player, ChunkChangeJournal.Column column);

    /** 目标列 region。 */
    boolean chunkLoaded(W world, ChunkChangeJournal.Column column);

    /** 目标列 region。 */
    B blockData(W world, int x, int y, int z);

    /** 玩家任务。发送成功返回后调用方才会 acknowledge。 */
    void send(P player, ChunkChangeJournal.Column column, int[] cells, int count, List<B> states);

    /** 目标列 region。向该列全部 watchers 重发；没有 watchers 也算已经收敛。 */
    boolean refresh(W world, ChunkChangeJournal.Column column);

    /** 玩家任务：一列已经完成定向补发。 */
    void delivered(P player, ChunkChangeJournal.Column column);

    void failed(ChunkChangeJournal.Column column, String stage, Throwable throwable);

    /** 实体退役、退出或停用：按既有生命周期语义清掉这个接收者。 */
    void retire(UUID playerId);
  }

  private interface Completion {
    void done(boolean retryLater);
  }

  private final class Round {
    private final P player;
    private final UUID playerId;
    private final ChunkChangeJournal.Receiver receiver;
    private int pending;
    private int budget = MAX_CELLS_PER_TICK;
    private boolean retryLater;

    private Round(P player, UUID playerId, ChunkChangeJournal.Receiver receiver, int pending) {
      this.player = player;
      this.playerId = playerId;
      this.receiver = receiver;
      this.pending = pending;
    }

    synchronized int budget() {
      return budget;
    }

    synchronized int take(ChunkChangeJournal.Ticket ticket, int[] cells, long[] revisions) {
      if (budget == 0) {
        return -1;
      }
      int count = changeJournal.take(ticket, budget, cells, revisions);
      budget -= count;
      return count;
    }

    private void done(boolean retry) {
      boolean finish;
      synchronized (this) {
        retryLater |= retry;
        finish = --pending == 0;
      }
      if (finish) {
        finishRound(retryLater);
      }
    }

    private void finishRound(boolean retry) {
      if (changeJournal.continueDrain(receiver)) {
        schedule(player, playerId, receiver, retry ? 5L : 0L);
      }
    }
  }

  private final ChunkChangeJournal changeJournal;
  private final Bridge<P, W, B> bridge;
  private final Set<ChunkChangeJournal.Receiver> scheduled = ConcurrentHashMap.newKeySet();
  private final Set<ChunkChangeJournal.Ticket> refreshing = ConcurrentHashMap.newKeySet();

  ChunkReplayCoordinator(ChunkChangeJournal changeJournal, Bridge<P, W, B> bridge) {
    this.changeJournal = changeJournal;
    this.bridge = bridge;
  }

  /** 写出确认后的唯一入口：截断直接整列刷新，正常脏格走有预算的定向补发。 */
  void written(P player, UUID playerId, ChunkChangeJournal.Ticket ticket,
      ChunkChangeJournal.Written written) {
    if (!written.current()) {
      if (changeJournal.wasEvicted(ticket)) {
        evicted(playerId, ticket.column);
      }
      return;
    }
    if (written.truncated()) {
      refreshTicket(player, playerId, ticket, retry -> {
        if (retry || changeJournal.continueDrain(ticket.receiver)) {
          schedule(player, playerId, ticket.receiver, retry ? 5L : 0L);
        }
      });
    } else if (written.schedule()) {
      schedule(player, playerId, ticket.receiver, 0L);
    }
  }

  void schedule(P player, UUID playerId, ChunkChangeJournal.Receiver receiver, long delayTicks) {
    if (!bridge.active()) {
      bridge.retire(playerId);
      return;
    }
    if (!scheduled.add(receiver)) {
      return;
    }
    Runnable retired = () -> {
      scheduled.remove(receiver);
      bridge.retire(playerId);
    };
    boolean accepted = false;
    try {
      accepted = bridge.schedulePlayer(player,
          () -> {
            scheduled.remove(receiver);
            drain(player, playerId, receiver);
          }, retired, delayTicks);
    } catch (RuntimeException ex) {
      bridge.failed(null, "player-schedule", ex);
    }
    if (!accepted) {
      scheduled.remove(receiver);
      bridge.failed(null, "player-schedule", null);
      // 瞬时拒收绝不清空 ready：每一列直接尝试目标 region 的整列兜底。
      for (ChunkChangeJournal.Ticket ticket : changeJournal.ready(receiver)) {
        refreshTicket(player, playerId, ticket, retry -> {
          if (retry || changeJournal.continueDrain(receiver)) {
            schedule(player, playerId, receiver, retry ? 5L : 0L);
          }
        });
      }
      if (changeJournal.ready(receiver).isEmpty() && changeJournal.continueDrain(receiver)) {
        schedule(player, playerId, receiver, 1L);
      }
    }
  }

  private void drain(P player, UUID playerId, ChunkChangeJournal.Receiver receiver) {
    if (!bridge.active()) {
      bridge.retire(playerId);
      return;
    }
    Session<W> session = bridge.session(player);
    if (session == null) {
      bridge.retire(playerId);
      return;
    }
    List<ChunkChangeJournal.Ticket> tickets = new ArrayList<>(MAX_COLUMNS_PER_TICK);
    for (int i = 0; i < MAX_COLUMNS_PER_TICK; i++) {
      ChunkChangeJournal.Ticket ticket = changeJournal.poll(receiver);
      if (ticket == null) {
        break;
      }
      tickets.add(ticket);
    }
    if (tickets.isEmpty()) {
      if (changeJournal.continueDrain(receiver)) {
        // poll 与 continueDrain 之间写入的 ready 已由 draining=true 拒绝自调度，这里接手。
        schedule(player, playerId, receiver, 0L);
      }
      return;
    }
    Round round = new Round(player, playerId, receiver, tickets.size());
    for (ChunkChangeJournal.Ticket ticket : tickets) {
      ChunkChangeJournal.Column column = ticket.column;
      if (!changeJournal.isCurrent(playerId, ticket)) {
        round.done(false);
      } else if (!session.worldName().equals(column.world())) {
        // 换世界由既有事件清理；迟到任务只收旧账，不跨会话发送。
        changeJournal.finish(ticket, false);
        round.done(false);
      } else if (changeJournal.isTruncated(ticket)) {
        // 上次整列刷新失败的纯截断票没有 dirty 位，不能进入 take==0 的普通完成路径。
        refreshTicket(player, playerId, ticket, round::done);
      } else if (!bridge.chunkSent(player, column)) {
        deferOrRefresh(player, playerId, session.world(), ticket, round::done);
      } else {
        dispatchCapture(player, playerId, session.world(), ticket, round);
      }
    }
  }

  /** 区域受理不等于执行：回调和 global 超时通过 CAS 恰好完成一次。 */
  private void regionWithTimeout(W world, ChunkChangeJournal.Column column,
      Runnable action, Runnable timeout) {
    java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
    bridge.scheduleRegion(world, column, () -> {
      if (completed.compareAndSet(false, true)) {
        action.run();
      }
    });
    try {
      if (!bridge.scheduleGlobal(() -> {
        if (completed.compareAndSet(false, true)) {
          timeout.run();
        }
      }, REGION_TIMEOUT_TICKS) && completed.compareAndSet(false, true)) {
        // 超时任务排不上时同步撤销区域回调并走调用方的失败路径。
        timeout.run();
      }
    } catch (RuntimeException ex) {
      if (completed.compareAndSet(false, true)) {
        timeout.run();
      }
    }
  }

  private void dispatchCapture(P player, UUID playerId, W world,
      ChunkChangeJournal.Ticket ticket, Round round) {
    ChunkChangeJournal.Column column = ticket.column;
    try {
      // 不读玩家位置也不假设玩家 region 拥有目标列；每一列都投给它自己的 region。
      regionWithTimeout(world, column,
          () -> capture(player, playerId, world, ticket, round), () -> {
            bridge.failed(column, "region-timeout", null);
            changeJournal.finish(ticket, true);
            round.done(true);
          });
    } catch (RuntimeException ex) {
      bridge.failed(column, "region-schedule", ex);
      changeJournal.finish(ticket, true);
      round.done(true);
    }
  }

  private void capture(P player, UUID playerId, W world,
      ChunkChangeJournal.Ticket ticket, Round round) {
    ChunkChangeJournal.Column column = ticket.column;
    if (!bridge.active() || !changeJournal.isCurrent(playerId, ticket)) {
      round.done(false);
      return;
    }
    final int count;
    final int[] cells;
    final List<B> states;
    final long[] cellRevisions;
    try {
      if (!bridge.chunkLoaded(world, column)) {
        returnToPlayer(player, playerId, ticket,
            () -> deferOrRefresh(player, playerId, world, ticket, round::done), round);
        return;
      }
      int available = round.budget();
      if (available == 0) {
        changeJournal.finish(ticket, true);
        round.done(false);
        return;
      }
      cells = new int[Math.min(available, MAX_CELLS_PER_TICK) * 3];
      cellRevisions = new long[Math.min(available, MAX_CELLS_PER_TICK)];
      count = round.take(ticket, cells, cellRevisions);
      if (count < 0) {
        changeJournal.finish(ticket, true);
        round.done(false);
        return;
      }
      if (count == 0) {
        changeJournal.finish(ticket, false);
        round.done(false);
        return;
      }
      states = new ArrayList<>(count);
      for (int i = 0; i < count * 3; i += 3) {
        states.add(bridge.blockData(world, cells[i], cells[i + 1], cells[i + 2]));
      }
    } catch (RuntimeException ex) {
      bridge.failed(column, "capture", ex);
      refreshTicket(player, playerId, ticket, round::done);
      return;
    }
    returnToPlayer(player, playerId, ticket, () -> {
      if (!changeJournal.isCurrent(playerId, ticket)) {
        round.done(false);
        return;
      }
      Session<W> current = bridge.session(player);
      if (current == null) {
        bridge.retire(playerId);
        round.done(false);
        return;
      }
      if (current.world() != world || !current.worldName().equals(column.world())
          || !bridge.chunkSent(player, column)) {
        // 取样后尚未 ready：旧样本不发送，重新等待；耗尽后整列刷新。
        deferOrRefresh(player, playerId, world, ticket, round::done);
        return;
      }
      try {
        bridge.send(player, column, cells, count, states);
        changeJournal.acknowledge(ticket, cells, count, cellRevisions);
        ticket.retries = 0;
        if (changeJournal.finish(ticket, true)) {
          bridge.delivered(player, column);
        }
        round.done(false);
      } catch (RuntimeException ex) {
        bridge.failed(column, "send", ex);
        refreshTicket(player, playerId, ticket, round::done);
      }
    }, round);
  }

  private void returnToPlayer(P player, UUID playerId, ChunkChangeJournal.Ticket ticket,
      Runnable action, Round round) {
    Runnable retired = () -> bridge.retire(playerId);
    try {
      if (bridge.schedulePlayer(player, action, retired, 0L)) {
        return;
      }
    } catch (RuntimeException ex) {
      bridge.failed(ticket.column, "player-return", ex);
    }
    // 玩家调度暂不可用时坐标尚未 acknowledge，直接改走当前世界状态的整列刷新。
    bridge.failed(ticket.column, "player-return", null);
    refreshTicket(player, playerId, ticket, round::done);
  }

  private void deferOrRefresh(P player, UUID playerId, W world,
      ChunkChangeJournal.Ticket ticket, Completion completion) {
    if (!changeJournal.isCurrent(playerId, ticket)) {
      completion.done(false);
    } else if (++ticket.retries < MAX_WAIT_TICKS) {
      changeJournal.finish(ticket, true);
      completion.done(false);
    } else {
      bridge.failed(ticket.column, "readiness", null);
      refreshTicket(player, playerId, ticket, completion);
    }
  }

  /** 正常补发的稀有出口。只有 region 接受任务后才收票；拒收则保留并延迟重排。 */
  private void refreshTicket(P player, UUID playerId, ChunkChangeJournal.Ticket ticket,
      Completion completion) {
    if (!changeJournal.isCurrent(playerId, ticket)) {
      completion.done(false);
      return;
    }
    if (!refreshing.add(ticket)) {
      completion.done(false);
      return;
    }
    ChunkChangeJournal.Column column = ticket.column;
    W world = bridge.world(playerId, column);
    if (world == null || !bridge.active()) {
      refreshing.remove(ticket);
      changeJournal.finish(ticket, false);
      completion.done(false);
      return;
    }
    try {
      regionWithTimeout(world, column, () -> {
        boolean retry = false;
        try {
          if (bridge.active() && changeJournal.isCurrent(playerId, ticket)) {
            changeJournal.prepareWholeColumnRefresh(ticket);
            if (bridge.refresh(world, column)) {
              changeJournal.finish(ticket, false);
            } else {
              bridge.failed(column, "refresh-empty", null);
              changeJournal.cancelWholeColumnRefresh(ticket);
              changeJournal.finish(ticket, true);
              retry = true;
            }
          }
        } catch (RuntimeException ex) {
          bridge.failed(column, "refresh", ex);
          changeJournal.cancelWholeColumnRefresh(ticket);
          changeJournal.finish(ticket, true);
          retry = true;
        } finally {
          refreshing.remove(ticket);
          completion.done(retry);
        }
      }, () -> {
        refreshing.remove(ticket);
        changeJournal.cancelWholeColumnRefresh(ticket);
        changeJournal.finish(ticket, true);
        bridge.failed(column, "refresh-timeout", null);
        completion.done(true);
      });
    } catch (RuntimeException ex) {
      refreshing.remove(ticket);
      bridge.failed(column, "refresh-schedule", ex);
      changeJournal.finish(ticket, true);
      completion.done(true);
    }
  }

  /**
   * ticket 已被容量/超时淘汰后的整列兜底。旧票已不在 journal，刷新读取的是执行时世界现状，
   * 因而即便同列已有新快照，也不会把旧 BlockData 盖回去。区域拒收由全局 scheduler 延迟重试。
   */
  void evicted(UUID playerId, ChunkChangeJournal.Column column) {
    if (!bridge.active()) {
      return;
    }
    W world = bridge.world(playerId, column);
    if (world == null) {
      return;
    }
    try {
      regionWithTimeout(world, column, () -> {
        if (!bridge.active()) {
          return;
        }
        ChunkChangeJournal.Ticket current = changeJournal.prepareWholeColumnRefresh(playerId, column);
        try {
          if (bridge.refresh(world, column)) {
            changeJournal.completeEvictedRefresh(current);
          } else {
            changeJournal.cancelWholeColumnRefresh(current);
            bridge.failed(column, "evicted-empty", null);
            retryEvicted(playerId, column);
          }
        } catch (RuntimeException ex) {
          changeJournal.cancelWholeColumnRefresh(current);
          bridge.failed(column, "evicted-refresh", ex);
          retryEvicted(playerId, column);
        }
      }, () -> {
        bridge.failed(column, "evicted-timeout", null);
        retryEvicted(playerId, column);
      });
    } catch (RuntimeException ex) {
      bridge.failed(column, "evicted-schedule", ex);
      retryEvicted(playerId, column);
    }
  }

  private void retryEvicted(UUID playerId, ChunkChangeJournal.Column column) {
    if (!bridge.active()) {
      return;
    }
    try {
      if (!bridge.scheduleGlobal(() -> evicted(playerId, column), 5L)) {
        bridge.failed(column, "evicted-retry", null);
      }
    } catch (RuntimeException ex) {
      bridge.failed(column, "evicted-retry", ex);
    }
  }
}
