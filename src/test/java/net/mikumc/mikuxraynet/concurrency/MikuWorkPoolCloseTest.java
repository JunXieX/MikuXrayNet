package net.mikumc.mikuxraynet.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 工作线程池关闭语义测试：close() 必须先排空队列并对每个存留的 {@link RewriteTask}
 * 恰好放行一次（fail-open 的最后一道兜底），绝不把封包永久卡住。
 */
class MikuWorkPoolCloseTest {

  /** close 时队列里尚未执行的任务必须逐一被放行（旧实现直接 shutdownNow 丢弃 → 封包永久卡住）。 */
  @Test
  @Timeout(10)
  void closeSignalsQueuedTasksExactlyOnce() throws Exception {
    MikuWorkPool pool = new MikuWorkPool(1, 64);
    CountDownLatch workerBlocked = new CountDownLatch(1);
    CountDownLatch workerStarted = new CountDownLatch(1);
    try {
      // 占住唯一的工作线程，让后续提交的任务全部滞留队列
      pool.execute(() -> {
        workerStarted.countDown();
        try {
          workerBlocked.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(workerStarted.await(2, TimeUnit.SECONDS), "工作线程应已开始执行阻塞任务");

      AtomicInteger deliveries = new AtomicInteger();
      for (int i = 0; i < 5; i++) {
        RewriteTask task = new RewriteTask(i, 0, "world", -64, 24, 10_000L, deliveries::incrementAndGet);
        pool.execute(task, () -> {
          // 模拟改写负载：close 排空后这些负载根本不会执行
          deliveries.incrementAndGet();
        });
      }
      assertEquals(0, deliveries.get(), "队列滞留期间不得放行");

      pool.close();

      assertEquals(5, deliveries.get(),
          "close 必须排空队列并对每个存留任务放行恰好一次");
    } finally {
      workerBlocked.countDown();
    }
  }

  /** 兜底放行不得与正在写入的任务竞争：写入中的任务由其工作线程自行放行，总数仍恰好一次。 */
  @Test
  @Timeout(10)
  void closeSkipsWritingTasksButTheyStillReleaseExactlyOnce() throws Exception {
    MikuWorkPool pool = new MikuWorkPool(1, 64);
    AtomicInteger deliveries = new AtomicInteger();
    CountDownLatch releaseObserved = new CountDownLatch(1);
    try {
      RewriteTask task = new RewriteTask(0, 0, "world", -64, 24, 10_000L,
          () -> {
            deliveries.incrementAndGet();
            releaseObserved.countDown();
          });
      pool.execute(task, () -> {
        // 模拟工作线程已取得写入权并正在改写（此时 close 的兜底必须跳过它）
        assertTrue(task.tryBeginWrite());
        // 改写完成：任务自行放行（兜底跳过后唯一的一次放行）
        task.signalOnce();
      });

      assertTrue(releaseObserved.await(2, TimeUnit.SECONDS), "任务应已自行放行");
      pool.close();
      assertEquals(1, deliveries.get(), "写入中/已完成的任务不得被兜底重复放行");
    } finally {
      pool.close();
    }
  }

  /** 兜底放行必须跑完全部任务：某个放行动作抛异常时不得中断其余任务的放行。 */
  @Test
  @Timeout(10)
  void closeSignalsAllTasksEvenIfOneDeliveryThrows() {
    MikuWorkPool pool = new MikuWorkPool(1, 64);
    AtomicInteger deliveries = new AtomicInteger();
    for (int i = 0; i < 3; i++) {
      RewriteTask task = new RewriteTask(i, 0, "world", -64, 24, 10_000L, () -> {
        deliveries.incrementAndGet();
        if (deliveries.get() == 1) {
          throw new IllegalStateException("模拟放行回调异常");
        }
      });
      pool.execute(task, () -> {
        // 滞留队列，close 时由兜底放行
      });
    }

    pool.close();
    assertEquals(3, deliveries.get(), "某个放行动作抛异常不得中断其它任务的兜底放行");
  }
}
