package net.mikumc.mikuxraynet.concurrency;

import java.util.concurrent.ArrayBlockingQueue;
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

  public MikuWorkPool(int threads, int queueCapacity) {
    int workerCount = threads > 0 ? threads : Math.min(4, Runtime.getRuntime().availableProcessors());
    AtomicInteger workerIndex = new AtomicInteger();
    ThreadFactory workerFactory = runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-Worker-" + workerIndex.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };

    this.executor = new ThreadPoolExecutor(
        workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
        workerFactory,
        new ThreadPoolExecutor.AbortPolicy());

    this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-Watchdog");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** 队列是否还有空位；false 表示此刻提交会被拒绝。 */
  public boolean hasCapacity() {
    return this.executor.getQueue().remainingCapacity() > 0;
  }

  /** 提交任务；队列已满会抛 {@link java.util.concurrent.RejectedExecutionException}。 */
  public void execute(Runnable task) {
    this.executor.execute(task);
  }

  /** 登记一个超时回调；调用方应在任务结束时取消它，避免无谓地延长封包引用寿命。 */
  public ScheduledFuture<?> scheduleTimeout(Runnable timeoutAction, long delayMillis) {
    return this.watchdog.schedule(timeoutAction, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
  }

  /** 关闭线程池；已提交未执行的任务会被丢弃（fail-open 由各任务的 signalOnce 兜底）。 */
  @Override
  public void close() {
    this.executor.shutdownNow();
    this.watchdog.shutdownNow();
  }
}