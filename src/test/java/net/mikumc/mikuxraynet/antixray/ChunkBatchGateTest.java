package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 批次闸门测试：批次未完成前不得放行、恰好放行一次、超时兜底必放行。
 */
class ChunkBatchGateTest {

  @Test
  void releasesImmediatelyWhenNoPendingChunks() {
    ChunkBatchGate gate = new ChunkBatchGate();
    AtomicInteger releases = new AtomicInteger();

    gate.finish(releases::incrementAndGet);

    assertEquals(1, releases.get(), "没有待完成区块时应立即放行");
  }

  @Test
  void waitsForAllPendingChunks() {
    ChunkBatchGate gate = new ChunkBatchGate();
    gate.chunkStarted();
    gate.chunkStarted();

    AtomicInteger releases = new AtomicInteger();
    gate.finish(releases::incrementAndGet);

    assertEquals(0, releases.get(), "仍有未完成区块时不得放行 FINISHED");
    gate.chunkDone();
    assertEquals(0, releases.get());
    gate.chunkDone();
    assertEquals(1, releases.get(), "最后一个区块完成后放行");
  }

  @Test
  void releasesExactlyOnce() {
    ChunkBatchGate gate = new ChunkBatchGate();
    gate.chunkStarted();

    AtomicInteger releases = new AtomicInteger();
    gate.finish(releases::incrementAndGet);

    gate.chunkDone();
    gate.chunkDone();
    gate.forceRelease();

    assertEquals(1, releases.get(), "放行必须恰好一次（重复放行会让客户端卡包）");
  }

  @Test
  void timeoutForceReleasesWhileStillPending() {
    ChunkBatchGate gate = new ChunkBatchGate();
    gate.chunkStarted();

    AtomicInteger releases = new AtomicInteger();
    gate.finish(releases::incrementAndGet);
    assertEquals(0, releases.get());

    gate.forceRelease();

    assertEquals(1, releases.get(), "超时兜底必须放行，绝不永久卡住");
  }

  @Test
  void chunkStartedAfterFinishDoesNotHoldBatch() {
    ChunkBatchGate gate = new ChunkBatchGate();
    AtomicInteger releases = new AtomicInteger();

    gate.finish(releases::incrementAndGet);
    assertEquals(1, releases.get());

    // 批次已结束：后续误计的区块不得再触发放行
    gate.chunkStarted();
    gate.chunkDone();

    assertEquals(1, releases.get());
  }

  @Test
  void concurrentCompletionStillReleasesExactlyOnce() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      for (int round = 0; round < 50; round++) {
        ChunkBatchGate gate = new ChunkBatchGate();
        int chunks = 8;
        for (int i = 0; i < chunks; i++) {
          gate.chunkStarted();
        }

        AtomicInteger releases = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(chunks);
        for (int i = 0; i < chunks; i++) {
          pool.execute(() -> {
            try {
              start.await();
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
            gate.chunkDone();
            completed.countDown();
          });
        }
        start.countDown();

        gate.finish(releases::incrementAndGet);
        gate.forceRelease();

        completed.await(5, TimeUnit.SECONDS);
        assertEquals(1, releases.get(), "并发完成 + 超时兜底也只能放行一次");
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}