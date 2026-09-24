package net.mikumc.mikuxraynet.antixray;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1.20.2+ 区块批量闸门：保证 {@code CHUNK_BATCH_FINISHED} 不会早于本批次内尚未完成的区块改写被放行。
 *
 * <p><b>为什么必须配对</b>：ProtocolLib 只对「异步白名单内的封包」维护每玩家有序发送队列。若只延迟
 * {@code MAP_CHUNK} 而不把 {@code CHUNK_BATCH_START/FINISHED} 纳入白名单，批量结束包会绕过该队列立即发出，
 * 客户端可能在还没收到（或还没处理完）本批次区块时就上报「批次已读完」，进而造成加载节奏错乱。
 *
 * <p><b>放行纪律</b>：本闸门只负责「计数 + 恰好放行一次」。放行动作由调用方提供（通常是
 * {@code signalPacketTransmission}），{@link #forceRelease()} 为超时兜底——超时也必须放行，绝不永久卡住。
 * 本类不持有任何 Bukkit / 封包引用（放行动作由调用方在局部作用域内提供并在批次结束后随之释放）。
 */
public final class ChunkBatchGate {

  private final AtomicInteger pending = new AtomicInteger();
  private final AtomicBoolean released = new AtomicBoolean();

  private boolean finished;
  private Runnable release;

  /** 本批次内新增一个待完成的区块改写任务。 */
  public void chunkStarted() {
    synchronized (this) {
      if (finished) {
        return;
      }
      pending.incrementAndGet();
    }
  }

  /** 一个区块改写任务已结束（无论成功、异常还是被看门狗放行）。 */
  public void chunkDone() {
    pending.decrementAndGet();
    maybeRelease();
  }

  /**
   * 标记批次结束并注册放行动作：此刻若已无待完成任务则立即放行，否则等最后一个任务完成。
   *
   * @param releaseAction 放行动作；由本闸门保证只执行一次
   */
  public void finish(Runnable releaseAction) {
    synchronized (this) {
      this.finished = true;
      this.release = releaseAction;
    }
    maybeRelease();
  }

  /** 超时兜底：忽略未完成的计数强制放行一次。 */
  public void forceRelease() {
    releaseOnce();
  }

  private void maybeRelease() {
    synchronized (this) {
      if (!finished || pending.get() > 0) {
        return;
      }
    }
    releaseOnce();
  }

  private void releaseOnce() {
    Runnable action;
    synchronized (this) {
      action = this.release;
    }
    if (action != null && released.compareAndSet(false, true)) {
      action.run();
    }
  }
}