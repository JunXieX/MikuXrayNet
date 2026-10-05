package net.mikumc.mikuxraynet.concurrency;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 反矿透工作线程池：固定线程 + 有界队列，全部线程为 daemon（不阻止服务端关停）。
 *
 * <p>线程命名：工作线程 {@code MikuXrayNet-Worker-N}，超时看门狗 {@code MikuXrayNet-Watchdog}。
 * 看门狗只负责「任务超时放行」，本身不做任何封包改写。
 *
 * <p>关键约束：队列有界且拒绝策略为中止，因此调用方必须先 {@link #hasCapacity()} 再提交，
 * 队列满时直接放行原包（fail-open），绝不在网络线程上阻塞。
 */
public final class MikuWorkPool implements AutoCloseable {

  private final ThreadPoolExecutor executor;
  private final ScheduledExecutorService watchdog;
  private final int queueCapacity;

  /**
   * 队列占用的近似计数（锁-free）：提交时 +1、任务「开始执行」时 -1，故它统计的是「已提交但尚未开始
   * 执行」的任务数，≈ {@code ArrayBlockingQueue} 当前长度。
   *
   * <p><b>为什么用自维护计数而不是 {@code getQueue().remainingCapacity()}</b>：后者要拿
   * {@code ArrayBlockingQueue} 的内部锁，而封包线程对<b>每个</b>区块封包都要先 {@link #hasCapacity()}
   * 再 {@link #execute}，于是同一个包要两次抢队列锁、还要与轮询同队列的工作线程争锁。本计数只做原子
   * 加减，调用方在提交前读一次即可，不再触碰队列锁。
   *
   * <p><b>为什么「开始执行即 -1」而非「执行完毕才 -1」</b>：后者会把正在运行的任务也算进占用，取值
   * 会大于真实队列长度，可能在本有大量空位时误报「无容量」；「开始执行即 -1」逼近真实队列长度，
   * 与 {@code queueCapacity} 比较的语义最接近。
   *
   * <p><b>为什么允许近似</b>：本值只是提交前的优化提示，正确性不依赖它——所有调用方都会捕获
   * {@link java.util.concurrent.RejectedExecutionException} 并以「原包放行」兜底（fail-open），
   * 或退回当前线程直接处理。并发提交下它可能有极小瞬时偏差，属可接受。
   *
   * <p><b>如何保证不永久漂高</b>：每次 +1 都恰好对应一次 -1——要么任务开始执行时 -1，要么提交被拒时
   * 在 catch 中 -1；{@link #close()} 会将其清零并置 {@link #closed}。因此即便曾抛过异常，本值也只会
   * 短暂偏高、不会永久上报「无容量」。
   */
  private final AtomicInteger queuedTasks = new AtomicInteger();

  /** 是否已关闭；关闭后 {@link #hasCapacity()} 一律返回 false（提交必然被拒）。 */
  private volatile boolean closed;

  /**
   * 已提交、尚未执行完毕的改写任务登记。
   *
   * <p><b>为什么需要</b>：{@code close()} 时 {@code shutdownNow} 会把队列里还没跑的任务一并丢弃——
   * 若没有这份登记，这些任务对应的封包就「既没人改写、也没人放行」，客户端永久卡在加载界面。
   * close 会对其中尚未开始写入的任务逐一 {@link RewriteTask#signalOnce()} 放行（fail-open 真正兜底）。
   */
  private final Set<RewriteTask> pending = ConcurrentHashMap.newKeySet();

  public MikuWorkPool(int threads, int queueCapacity) {
    int workerCount = threads > 0 ? threads : Math.min(4, Runtime.getRuntime().availableProcessors());
    AtomicInteger workerIndex = new AtomicInteger();
    ThreadFactory workerFactory = runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-Worker-" + workerIndex.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };

    this.queueCapacity = Math.max(1, queueCapacity);
    this.executor = new ThreadPoolExecutor(
        workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(this.queueCapacity),
        workerFactory,
        new ThreadPoolExecutor.AbortPolicy());

    this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-Watchdog");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** 工作线程数（诊断用）。 */
  public int poolSize() {
    return this.executor.getCorePoolSize();
  }

  /** 当前正在处理任务的线程数（诊断用）。 */
  public int activeCount() {
    return this.executor.getActiveCount();
  }

  /** 当前排队任务数（诊断用）。 */
  public int queueSize() {
    return this.executor.getQueue().size();
  }

  /** 队列容量上限（诊断用）。 */
  public int queueCapacity() {
    return this.queueCapacity;
  }

  /**
   * 队列是否还有空位；false 表示此刻提交很可能被拒绝。
   *
   * <p>锁-free 近似判断（见 {@link #queuedTasks}）：本值仅供参考，调用方仍必须处理
   * {@link java.util.concurrent.RejectedExecutionException}。关闭后一律返回 false。
   */
  public boolean hasCapacity() {
    return !this.closed && this.queuedTasks.get() < this.queueCapacity;
  }

  /** 提交任务；队列已满会抛 {@link java.util.concurrent.RejectedExecutionException}。 */
  public void execute(Runnable task) {
    this.queuedTasks.incrementAndGet();
    try {
      this.executor.execute(() -> {
        // 一开始执行就撤销占用：本计数只近似「排队中」的任务数（见 queuedTasks 说明）
        this.queuedTasks.decrementAndGet();
        task.run();
      });
    } catch (Throwable throwable) {
      // 提交被拒：必须撤销刚才的 +1，否则计数会永久漂高、hasCapacity 永远返回 false
      this.queuedTasks.decrementAndGet();
      throw throwable;
    }
  }

  /**
   * 提交一个区块改写任务（登记进 {@link #pending}，执行完毕后自动解除登记）。
   *
   * <p>队列已满会抛 {@link java.util.concurrent.RejectedExecutionException}（登记随之解除）；
   * {@link #close()} 时会对其中尚未开始写入的任务做放行兜底。
   *
   * <p><b>契约：{@code payload} 必须自行保证「恰好放行一次」原包，且在 {@code finally} 中放行。</b>
   * 本池只负责「把 payload 跑起来」并在跑完后解除登记；它<b>不知道</b>如何放行该封包，也<b>不会</b>
   * 替 payload 兜底放行（{@code close()} 的兜底只覆盖「已入队但尚未开始执行」的任务，见
   * {@link #close()}）。若 payload 在放行前抛异常、或忘记放行，该封包将<b>永久卡住</b>（客户端卡在
   * 加载界面），直到超时看门狗或停用兜底才可能被放出——因此放行动作必须放进 {@code finally}，
   * 例如：
   * <pre>{@code
   * pool.execute(task, () -> {
   *   try {
   *     rewrite(task);          // 改写期间可能抛异常
   *   } finally {
   *     task.signalOnce();      // 无论成功与否都必须放行原包（signalOnce 幂等）
   *   }
   * });
   * }</pre>
   *
   * <p>同理，payload 内取得写入权（{@link RewriteTask#tryBeginWrite()}）后若放弃改写，也必须调用
   * {@link RewriteTask#signalOnce()} 放行——放行权一旦拿到就不会再有人替你放行。
   */
  public void execute(RewriteTask task, Runnable payload) {
    pending.add(task);
    this.queuedTasks.incrementAndGet();
    try {
      this.executor.execute(() -> {
        // 一开始执行就撤销占用：本计数只近似「排队中」的任务数（见 queuedTasks 说明）
        this.queuedTasks.decrementAndGet();
        try {
          payload.run();
        } finally {
          pending.remove(task);
        }
      });
    } catch (Throwable throwable) {
      pending.remove(task);
      // 提交被拒：撤销 +1，避免计数漂高导致 hasCapacity 永远返回 false
      this.queuedTasks.decrementAndGet();
      throw throwable;
    }
  }

  /** 登记一个超时回调；调用方应在任务结束时取消它，避免无谓地延长封包引用寿命。 */
  public ScheduledFuture<?> scheduleTimeout(Runnable timeoutAction, long delayMillis) {
    return this.watchdog.schedule(timeoutAction, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
  }

  /**
   * 关闭线程池。关闭前先「排空队列 + 放行兜底」：
   * 对每个已登记、尚未开始写入的 {@link RewriteTask} 调 {@link RewriteTask#releaseOnTimeout()}
   * 放行（其内部经 CAS 抢到写入权后转调 {@code signalOnce()}），
   * 否则 shutdownNow 丢弃的排队任务会让对应封包永远无人放行（客户端卡加载界面）。
   *
   * <p>放行顺序上「先取登记表快照 → 再 {@code shutdownNow} → 最后按快照兜底」：快照在中断工作线程之前
   * 取出，因此「已出队但尚未开始写」的任务也一定被覆盖（若先 shutdownNow，被中断的 payload 可能已自行
   * 注销，兜底会漏掉它）；排空队列同样保证不会再有排队任务被启动，而正在写入中的任务由其工作线程自行放行，
   * 兜底不得碰它们，避免「包已发出却仍在改写」的写回竞争。
   *
   * <p>这里用 {@link RewriteTask#releaseOnTimeout()} 的<b>原子 CAS</b>（写入权 OPEN → DONE）而不是
   * 「先看 {@code isWriting()} 再放行」：后者是 check-then-act，在「工作线程刚把任务从队列取出、
   * 还没调 {@code tryBeginWrite()}」的窗口里会误判为「未开始写」并抢先放行，随后工作线程的
   * {@code tryBeginWrite()} 仍会成功 → 出现「包已发出却仍在改写」的数据竞争。CAS 让「取得写入权」
   * 与「兜底放行」互斥：本方法抢到就放行（工作线程随后必然放弃改写），抢不到说明工作线程已在写
   * （或已由看门狗放行/已完成），一律交由工作线程自行放行。
   *
   * <p>{@code signalOnce} 幂等（CAS 保证恰好一次）：已被看门狗放行或已完成的任务不受影响。
   */
  @Override
  public void close() {
    // 先置关闭标志：关闭后 hasCapacity 一律 false（提交必然被拒），调用方随即走 fail-open。
    this.closed = true;
    // 先取登记表快照、再 shutdownNow。
    // 为什么不能先 shutdownNow：它会立刻中断工作线程，而被中断的 payload 可能在自己的 finally 里
    // 先行注销（pending.remove），于是 close 随后遍历登记表时已经看不到这个任务 ——
    // 该任务「已出队但尚未开始写」，既没被兜底放行、也没人改写（CI 上稳定复现「放行 0 次」）。
    // 快照在中断前取出，保证这类任务一定被兜底覆盖；CAS 仍保证「恰好一次」：
    // 若工作线程已抢到写入权，兜底会因 CAS 失败而不放行，改由工作线程自行放行。
    Set<RewriteTask> snapshot = Set.copyOf(pending);
    this.executor.shutdownNow();
    for (RewriteTask task : snapshot) {
      try {
        task.releaseOnTimeout();
      } catch (Throwable ignored) {
        // 单个任务的放行动作异常（例如玩家已掉线导致回调失败）不得中断其余任务的兜底放行
      }
    }
    pending.clear();
    // 队列里被 shutdownNow 丢弃的任务永远不会「开始执行」，其 +1 也就永远不会被撤销；清零避免计数残留。
    // （关闭后 hasCapacity 因 closed 已恒为 false，本值不再影响任何结果，仅作清理以免诊断口径失真。）
    this.queuedTasks.set(0);
    this.watchdog.shutdownNow();
  }
}