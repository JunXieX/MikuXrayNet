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
  /**
   * 超时兜底是否已经到点。
   *
   * <p><b>为什么必须记下来</b>：{@link #forceRelease()} 可能在 {@link #finish(Runnable)} 之前触发
   * （看门狗线程与封包线程的调度竞态：{@code scheduleTimeout} 先于 {@code finish} 调用）。此时放行动作
   * 还没登记，{@code releaseOnce()} 只能空转、也不会置位 {@code released}——这一次「唯一一次超时兜底」
   * 就此被消耗。若此后仍有区块任务永不回调 {@link #chunkDone()}，批次结束包就永久无人放行
   * （客户端卡在加载界面），违反 fail-open 不变量。记下「已强制到点」后，迟到的 {@code finish()}
   * 会立刻放行，不再等未完成的计数。
   */
  private boolean forced;
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
    // 竞态防护：FINISHED 包与改写回调并发时（handleChunk 在「取到闸门」之后才调用 chunkStarted，
    // 期间 FINISHED 可能已带着 pending=0 放行），迟到的 chunkDone 会把 pending 打成负数。
    // 负数违反「pending = 未完成数」的不变式，还会让后续 maybeRelease 的判断失去意义——
    // 这里钳回 0。对正常路径无影响：非负区间内 updateAndGet(max(0, n-1)) 与 decrementAndGet 等价。
    pending.updateAndGet(current -> Math.max(0, current - 1));
    maybeRelease();
  }

  /**
   * 标记批次结束并注册放行动作：此刻若已无待完成任务则立即放行，否则等最后一个任务完成。
   *
   * @param releaseAction 放行动作；由本闸门保证只执行一次
   */
  public void finish(Runnable releaseAction) {
    boolean alreadyForced;
    synchronized (this) {
      this.finished = true;
      this.release = releaseAction;
      alreadyForced = this.forced;
    }
    if (alreadyForced) {
      // 超时兜底已在本次登记之前到点：立刻放行，绝不再等未完成的计数
      // （否则那次兜底就白费了，见 {@link #forced} 的说明）。
      releaseOnce();
      return;
    }
    maybeRelease();
  }

  /**
   * 超时兜底：忽略未完成的计数强制放行一次。
   *
   * <p>即便放行动作此刻尚未登记也要记账（{@link #forced}），由迟到的 {@link #finish(Runnable)} 消费，
   * 保证「唯一一次超时兜底」不会因为一次线程调度竞态而丢失。
   */
  public void forceRelease() {
    synchronized (this) {
      this.forced = true;
    }
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