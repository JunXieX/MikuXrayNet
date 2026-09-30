package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 批次闸门表（{@link ProtocolLibAsyncListener.BatchTable}）行为测试：
 * 超限时只淘汰最旧的一个条目（不清全部，旧实现 {@code clear()} 会误清所有玩家的批次）、
 * 玩家退出时按 UUID 精确清理。
 *
 * <p>用小容量构造走同样的淘汰路径，无需实例化整个监听器（离线无 ProtocolLib 运行时）。
 */
class ProtocolLibAsyncListenerBatchTest {

  @Test
  void openUnderLimitKeepsAllGates() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);

    for (int i = 0; i < 4; i++) {
      table.open(UUID.nameUUIDFromBytes(("p" + i).getBytes()));
    }

    assertEquals(4, table.size(), "未达上限时所有闸门都保留");
    for (int i = 0; i < 4; i++) {
      assertNotNull(table.get(UUID.nameUUIDFromBytes(("p" + i).getBytes())),
          "未达上限时既有闸门不得被清掉");
    }
  }

  @Test
  void openOverLimitEvictsOnlyTheOldestGate() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(3);
    UUID oldest = UUID.nameUUIDFromBytes("oldest".getBytes());
    UUID middle = UUID.nameUUIDFromBytes("middle".getBytes());
    UUID newer = UUID.nameUUIDFromBytes("newer".getBytes());

    table.open(oldest);
    table.open(middle);
    table.open(newer);
    assertEquals(3, table.size());

    // 达到上限后再开一个：只淘汰最旧的，其余闸门必须原样保留（旧实现 clear() 会全清）
    UUID fresh = UUID.nameUUIDFromBytes("fresh".getBytes());
    table.open(fresh);

    assertEquals(3, table.size(), "淘汰一个再放入一个：总量保持在上限内");
    assertNull(table.get(oldest), "只有最旧的闸门被淘汰");
    assertNotNull(table.get(middle), "较新的闸门不得被误清");
    assertNotNull(table.get(newer), "较新的闸门不得被误清");
    assertNotNull(table.get(fresh), "新打开的闸门必须存在");
  }

  @Test
  void openOverLimitKeepsEvictingTheOldestEachTime() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(2);
    UUID a = UUID.nameUUIDFromBytes("a".getBytes());
    UUID b = UUID.nameUUIDFromBytes("b".getBytes());
    UUID c = UUID.nameUUIDFromBytes("c".getBytes());
    UUID d = UUID.nameUUIDFromBytes("d".getBytes());

    table.open(a);
    table.open(b);
    table.open(c); // 淘汰 a
    table.open(d); // 淘汰 b

    assertNull(table.get(a));
    assertNull(table.get(b));
    assertNotNull(table.get(c));
    assertNotNull(table.get(d));
    assertEquals(2, table.size());
  }

  @Test
  void removeClearsExactlyOnePlayerGate() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);
    UUID player = UUID.nameUUIDFromBytes("quitter".getBytes());
    UUID other = UUID.nameUUIDFromBytes("stayer".getBytes());
    table.open(player);
    table.open(other);

    ChunkBatchGate removed = table.remove(player);

    assertNotNull(removed, "退出时必须取走该玩家的闸门");
    assertNull(table.get(player), "退出玩家的闸门必须被清理");
    assertNotNull(table.get(other), "其它玩家的闸门不得受影响");
    assertFalse(table.remove(player) == removed, "同一玩家的闸门只能清理一次");
    assertEquals(1, table.size());
  }

  @Test
  void reopenedGateForSamePlayerReplacesTheOldOne() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);
    UUID player = UUID.nameUUIDFromBytes("player".getBytes());

    table.open(player);
    ChunkBatchGate first = table.get(player);
    table.open(player);
    ChunkBatchGate second = table.get(player);

    assertSame(second, table.get(player));
    assertTrue(first != second, "重新打开批次必须换新闸门（旧闸门随 FINISHED 释放）");
    assertEquals(1, table.size());
  }

  /**
   * 超限逐出行为不变（每次只淘汰最旧一个），并新增「累计逐出计数 + 节流中文 WARN」观测点：
   * 每 64 次逐出输出一条日志，避免真机超限时刷屏。
   */
  @Test
  void overLimitEvictionIsCountedAndLoggedThrottled() {
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger("MikuXrayNet-BatchTest-" + System.nanoTime());
    logger.setUseParentHandlers(false);
    java.util.List<String> warnings = new java.util.ArrayList<>();
    java.util.logging.Handler handler = new java.util.logging.Handler() {
      @Override
      public void publish(java.util.logging.LogRecord record) {
        if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
          warnings.add(record.getMessage());
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    logger.addHandler(handler);
    try {
      ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(2, logger);
      for (int i = 0; i < 66; i++) {
        table.open(UUID.nameUUIDFromBytes(("p" + i).getBytes()));
      }
      assertEquals(2, table.size(), "容量上限不变");
      assertEquals(64L, table.evictions(), "逐出行为不变：每次超限只淘汰最旧一个");
      assertEquals(1, warnings.size(), "每 64 次逐出输出一条 WARN（节流，不刷屏）");
      assertTrue(warnings.get(0).contains("批次闸门"), "日志应为可读中文");
    } finally {
      logger.removeHandler(handler);
    }
  }

  // ---------------------------------------------------------- 取闸门 + 登记 原子性（任务 3）

  /**
   * 顺序用例：{@code acquire}（取闸门 + 登记）先于 FINISHED 时，FINISHED 必须等该区块改写完成才放行。
   * 修复前 {@code get} 与 {@code chunkStarted} 分离，这里会出现「取到闸门但尚未登记 → pending=0 →
   * FINISHED 提前放行」的窗口。
   */
  @Test
  void acquireRegistersChunkBeforeFinishCanRelease() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);
    UUID player = UUID.nameUUIDFromBytes("p".getBytes());
    table.open(player);

    // 模拟 handleChunk：取闸门 + 登记在一次原子操作里完成
    ChunkBatchGate gate = table.acquire(player);
    assertNotNull(gate);

    // CHUNK_BATCH_FINISHED 到达：remove + finish
    AtomicInteger releases = new AtomicInteger();
    ChunkBatchGate removed = table.remove(player);
    assertSame(gate, removed, "FINISHED 应取到同一闸门");
    removed.finish(releases::incrementAndGet);

    assertEquals(0, releases.get(), "已登记的区块未完成前不得放行 FINISHED（修复前这里会被提前放行）");
    gate.chunkDone();
    assertEquals(1, releases.get(), "改写完成后恰好放行一次");
  }

  /** FINISHED 已先取走闸门时，{@code acquire} 返回 null：该区块不再登记，按无批次处理。 */
  @Test
  void acquireReturnsNullWhenFinishAlreadyTookTheGate() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);
    UUID player = UUID.nameUUIDFromBytes("q".getBytes());
    table.open(player);
    table.remove(player);

    assertNull(table.acquire(player), "闸门已被 FINISHED 取走：不得再登记");
  }

  /** 并发用例：{@code acquire} 与 FINISHED（remove + finish）并发时仍必须「恰好放行一次」。 */
  @Test
  void acquireRacesFinishButReleasesExactlyOnce() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 300; round++) {
        ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);
        UUID player = UUID.nameUUIDFromBytes(("r" + round).getBytes());
        table.open(player);
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        pool.execute(() -> {
          awaitQuietly(go);
          ChunkBatchGate gate = table.acquire(player);
          if (gate != null) {
            gate.chunkDone();
          }
          done.countDown();
        });
        pool.execute(() -> {
          awaitQuietly(go);
          ChunkBatchGate gate = table.remove(player);
          if (gate != null) {
            gate.finish(releases::incrementAndGet);
          }
          done.countDown();
        });

        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "并发用例不得超时");
        assertEquals(1, releases.get(), "并发取闸门/FINISHED 也必须恰好放行一次（不早放、不重复）");
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------- 失败结果不入缓存（任务 2）

  /** 失败结果（解码/重编码异常）绝不允许写入内存/磁盘缓存；正常与未改动结果照常可缓存。 */
  @Test
  void failedResultIsNeverCacheable() {
    ObfuscationProcessor.Result failed =
        new ObfuscationProcessor.Result(new byte[] {1}, new int[0], "boom", null);
    assertTrue(failed.failed());
    assertFalse(ProtocolLibAsyncListener.resultCacheable(failed),
        "失败结果不得写入任何一级缓存（否则瞬时故障被固化，同指纹区块不再重试改写）");

    ObfuscationProcessor.Result unchanged =
        new ObfuscationProcessor.Result(new byte[] {1}, new int[0], null, null);
    assertTrue(ProtocolLibAsyncListener.resultCacheable(unchanged), "未改动结果照常可缓存");

    ObfuscationProcessor.Result changed =
        new ObfuscationProcessor.Result(new byte[] {2}, new int[] {1}, null, null);
    assertTrue(ProtocolLibAsyncListener.resultCacheable(changed), "改写结果照常可缓存");
  }
}
