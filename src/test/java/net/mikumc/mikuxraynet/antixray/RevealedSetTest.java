package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 已显形集合测试：按玩家隔离、按区块隔离、去重、注销、区块/玩家/世界清理、过期兜底与安全阀。
 *
 * <p>该结构取代旧索引里「按玩家各存一份被伪装坐标」的那半边：它<b>只记录实际发过显形包的坐标</b>，
 * 因此内存随「玩家身边确实显形的坐标数」增长，而不是随「玩家探索过的历史」增长。
 */
class RevealedSetTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final String WORLD = "world";
  private static final UUID PLAYER = UUID.randomUUID();
  private static final UUID OTHER_PLAYER = UUID.randomUUID();

  private final long[] clock = {0L};

  private RevealedSet revealed(int maxPerPlayer, long expireNanos) {
    return new RevealedSet(maxPerPlayer, expireNanos, () -> clock[0]);
  }

  private static ChunkKey chunk(int chunkX, int chunkZ) {
    return new ChunkKey(WORLD, chunkX, chunkZ);
  }

  @Test
  void marksAreScopedByPlayer() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);

    revealed.mark(PLAYER, key, 1, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1);

    assertTrue(revealed.contains(PLAYER, key, 1, 64, 1));
    assertEquals(2, revealed.sizeFor(PLAYER, key));
    assertFalse(revealed.contains(OTHER_PLAYER, key, 1, 64, 1), "不同玩家的标记互不可见");
    assertEquals(0, revealed.sizeFor(OTHER_PLAYER, key));
    assertEquals(1, revealed.markerCount(), "只有玩家 A 产生了一条「玩家 × 区块」标记");
  }

  @Test
  void marksAreScopedByChunk() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);

    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(1, 0), 17, 64, 1);

    assertEquals(1, revealed.sizeFor(PLAYER, chunk(0, 0)));
    assertEquals(1, revealed.sizeFor(PLAYER, chunk(1, 0)));
    assertFalse(revealed.contains(PLAYER, chunk(0, 0), 17, 64, 1), "同坐标在不同区块下互不影响");
  }

  @Test
  void duplicateMarkKeepsSingleEntry() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);

    revealed.mark(PLAYER, key, 7, 33, 7);
    revealed.mark(PLAYER, key, 7, 33, 7);

    assertEquals(1, revealed.sizeFor(PLAYER, key));
  }

  @Test
  void removePositionDropsOnlyThatMark() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);
    revealed.mark(PLAYER, key, 1, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1);
    revealed.mark(OTHER_PLAYER, key, 1, 64, 1);

    revealed.removePosition(WORLD, 1, 64, 1);

    assertFalse(revealed.contains(PLAYER, key, 1, 64, 1), "命中即注销");
    assertFalse(revealed.contains(OTHER_PLAYER, key, 1, 64, 1), "所有玩家同一坐标都要注销（维持子集不变式）");
    assertTrue(revealed.contains(PLAYER, key, 2, 64, 1), "同区块其它坐标不受影响");
    assertEquals(1, revealed.sizeFor(PLAYER, key));
  }

  /** 批量注销（同一区块）：等价于逐坐标，且计数与存活集合自洽；未命中坐标不误伤。 */
  @Test
  void removePositionsDropsBatchAndKeepsCountConsistent() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);
    revealed.mark(PLAYER, key, 1, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1);
    revealed.mark(PLAYER, key, 3, 64, 1);
    revealed.mark(OTHER_PLAYER, key, 1, 64, 1);
    revealed.mark(OTHER_PLAYER, key, 2, 64, 1);
    revealed.mark(OTHER_PLAYER, key, 3, 64, 1);

    // 含一个不在集合里的坐标（9,64,9）：不得影响计数
    int[] batch = {1, 64, 1, 2, 64, 1, 9, 64, 9};
    revealed.removePositions(WORLD, batch, 3);

    assertFalse(revealed.contains(PLAYER, key, 1, 64, 1));
    assertFalse(revealed.contains(PLAYER, key, 2, 64, 1));
    assertFalse(revealed.contains(OTHER_PLAYER, key, 1, 64, 1), "所有玩家的同一坐标都要摘除（维持子集不变式）");
    assertFalse(revealed.contains(OTHER_PLAYER, key, 2, 64, 1));
    assertTrue(revealed.contains(PLAYER, key, 3, 64, 1), "未变更坐标必须保留");
    assertTrue(revealed.contains(OTHER_PLAYER, key, 3, 64, 1));
    assertEquals(1, revealed.sizeFor(PLAYER, key));
    assertEquals(1, revealed.sizeFor(OTHER_PLAYER, key));
    assertEquals(2, revealed.positionCount(), "批量摘除后计数与存活集合自洽");
  }

  @Test
  void clearChunkClearsEveryPlayerOfThatChunk() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(OTHER_PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(1, 0), 17, 64, 1);

    revealed.clearChunk(chunk(0, 0));

    assertEquals(0, revealed.sizeFor(PLAYER, chunk(0, 0)));
    assertEquals(0, revealed.sizeFor(OTHER_PLAYER, chunk(0, 0)));
    assertEquals(1, revealed.sizeFor(PLAYER, chunk(1, 0)), "其它区块不受影响");
    assertEquals(1, revealed.markerCount());
  }

  @Test
  void clearPlayerAndWorldAreScoped() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(OTHER_PLAYER, chunk(0, 0), 1, 64, 1);
    // 其它世界的存活标记必须记在「不会被 clearPlayer 清掉」的玩家上，否则下面的世界作用域断言无意义：
    // clearPlayer(PLAYER) 会按契约清掉 PLAYER 在所有世界的标记（含 world_nether）。
    revealed.mark(OTHER_PLAYER, new ChunkKey("world_nether", 0, 0), 1, 64, 1);

    revealed.clearPlayer(PLAYER);
    assertEquals(0, revealed.sizeFor(PLAYER, chunk(0, 0)), "只清理该玩家");
    assertEquals(1, revealed.sizeFor(OTHER_PLAYER, chunk(0, 0)));

    revealed.clearWorld(WORLD);
    assertEquals(0, revealed.sizeFor(OTHER_PLAYER, chunk(0, 0)), "世界卸载清理该世界");
    assertEquals(1, revealed.sizeFor(OTHER_PLAYER, new ChunkKey("world_nether", 0, 0)),
        "其它世界不受影响");

    revealed.clear();
    assertEquals(0, revealed.markerCount());
  }

  @Test
  void markersExpireUnlessTouched() {
    long window = 10 * SECOND_NANOS;
    RevealedSet revealed = revealed(1024, window);
    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(1, 0), 17, 64, 1);

    clock[0] = 9 * SECOND_NANOS;
    revealed.expire();
    assertEquals(2, revealed.markerCount(), "窗口内不应清理");

    // 扫描到区块 (1,0)（touch）→ 只有它的过期窗口重新计时
    revealed.touch(PLAYER, chunk(1, 0));
    clock[0] = 11 * SECOND_NANOS;
    revealed.expire();

    assertEquals(1, revealed.markerCount(), "未被扫描到的标记必须清理");
    assertEquals(0, revealed.sizeFor(PLAYER, chunk(0, 0)));
    assertEquals(1, revealed.sizeFor(PLAYER, chunk(1, 0)), "被扫描到的标记必须保留");

    clock[0] = 21 * SECOND_NANOS + 1;
    revealed.expire();
    assertEquals(0, revealed.markerCount(), "长期未被扫描同样必须清理");
  }

  @Test
  void clearChunkResetsPlayerTotalForSafetyValve() {
    RevealedSet revealed = revealed(2, 60 * SECOND_NANOS);

    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(0, 0), 2, 64, 1);
    revealed.mark(PLAYER, chunk(0, 0), 3, 64, 1);
    assertEquals(1L, revealed.droppedByCapacity(), "单玩家安全阀触发必须可诊断");
    assertEquals(2, revealed.sizeFor(PLAYER, chunk(0, 0)));

    revealed.clearChunk(chunk(0, 0));
    revealed.mark(PLAYER, chunk(0, 0), 3, 64, 1);
    assertEquals(1, revealed.sizeFor(PLAYER, chunk(0, 0)), "清理后计数归零，可以重新标记");
  }

  /**
   * 「累计登记」与「实时坐标数」是两个口径：前者只增不减（不随登出 / 过期 / 失效回落），
   * 后者随清理实时回落。真机上的「发送 108 但已显形 0」正是只看实时值导致的误读，
   * 因此这里锁死：标记确实在记录（累计登记恒 &gt;= 实时值），且登出只影响实时值。
   */
  @Test
  void registeredTotalIsCumulativeWhilePositionCountIsLive() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);

    revealed.mark(PLAYER, key, 1, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1); // 重复标记不计入累计登记

    assertEquals(2L, revealed.registeredTotal(), "只统计真正新登记的坐标（去重不重复计数）");
    assertEquals(2, revealed.positionCount(), "实时坐标数与累计登记一致（尚未清理）");

    revealed.clearPlayer(PLAYER);
    assertEquals(0, revealed.positionCount(), "实时坐标数随登出归零");
    assertEquals(2L, revealed.registeredTotal(), "累计登记不与登出绑定：这才能证明标记确实在记录");
  }

  /** 安全阀放弃标记的坐标不算「登记成功」，且累计登记只增不减（区块失效后不回落）。 */
  @Test
  void registeredTotalIgnoresCapacityDropsAndChunkInvalidation() {
    RevealedSet revealed = revealed(1, 60 * SECOND_NANOS);

    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(0, 0), 2, 64, 1); // 触发安全阀，未登记

    assertEquals(1L, revealed.droppedByCapacity());
    assertEquals(1L, revealed.registeredTotal(), "被安全阀放弃的坐标不得计入累计登记");

    revealed.clearChunk(chunk(0, 0));
    assertEquals(0, revealed.positionCount(), "区块失效后实时坐标数归零");
    assertEquals(1L, revealed.registeredTotal(), "累计登记不因区块失效而回落");
  }

  /** 正常运营量级（49 个区块 × 约 404 个坐标）下安全阀不得触发。 */
  @Test
  void safetyValveNeverTriggersAtProductionScale() {
    RevealedSet revealed = revealed(524288, 300 * SECOND_NANOS);
    for (int chunk = 0; chunk < 49; chunk++) {
      for (int i = 0; i < 404; i++) {
        revealed.mark(PLAYER, chunk(chunk % 7, chunk / 7), i & 15, 64 + (i >> 8), i >> 4 & 15);
      }
    }

    assertEquals(0L, revealed.droppedByCapacity(), "正常量级下安全阀必须恒为 0");
    assertEquals(49, revealed.markerCount());
  }

  /**
   * 开放寻址哈希集的扩容：跨越初始容量（8）后每条坐标仍可命中；注销一半（含跨扩容的探测簇）后
   * 其余坐标的可达性不得被「向后移位删除」破坏。这是把线性扫描换成哈希集后最容易写错的两处。
   */
  @Test
  void growsBeyondInitialCapacityAndKeepsMembership() {
    RevealedSet revealed = revealed(1 << 20, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);
    int count = 1500;
    for (int i = 0; i < count; i++) {
      revealed.mark(PLAYER, key, i & 15, i >> 4, i % 7);
    }
    assertEquals(count, revealed.sizeFor(PLAYER, key), "扩容后不得丢条目");

    for (int i = 0; i < count; i += 2) {
      revealed.removePosition(WORLD, i & 15, i >> 4, i % 7);
    }
    assertEquals(count / 2, revealed.sizeFor(PLAYER, key), "注销后计数必须精确");
    assertEquals(count / 2, revealed.positionCount());
    for (int i = 0; i < count; i++) {
      assertEquals((i & 1) == 1, revealed.contains(PLAYER, key, i & 15, i >> 4, i % 7),
          "删除不得破坏同簇其它条目的可达性：i=" + i);
    }
  }

  /**
   * 并发读写：多条线程同时标记 + 立即读取。标记与读取都在同一条目锁内，因此「标记后立刻读」必须成立；
   * 结束后每条坐标恰好登记一次（去重 + 并发写入不丢条目）。
   */
  @Test
  void concurrentMarksAndReadsKeepEveryEntry() throws Exception {
    RevealedSet revealed = revealed(1 << 20, 600 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);
    int threads = 4;
    int perThread = 400;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    try {
      for (int t = 0; t < threads; t++) {
        int base = t * perThread;
        pool.execute(() -> {
          try {
            start.await();
            for (int i = 0; i < perThread; i++) {
              int v = base + i;
              revealed.mark(PLAYER, key, v & 15, v >> 4, 0);
              assertTrue(revealed.contains(PLAYER, key, v & 15, v >> 4, 0),
                  "同线程内标记后必须立即可见：v=" + v);
            }
          } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS), "并发标记必须在超时内完成");
    } finally {
      pool.shutdownNow();
    }

    assertNull(failure.get(), "并发标记/读取不得抛异常：" + failure.get());
    assertEquals(threads * perThread, revealed.sizeFor(PLAYER, key),
        "并发写入的每条坐标都必须登记且仅一次");
    for (int v = 0; v < threads * perThread; v++) {
      assertTrue(revealed.contains(PLAYER, key, v & 15, v >> 4, 0), "v=" + v);
    }
  }

  /**
   * 并发 mark 与 expire 交错：expire 把「摘除 + 统计扣减」并入同一把 marker 锁、mark 又对「孤儿」
   * 标记做重试后，计数器必须始终与存活集合自洽。
   *
   * <p>判据：清点所有用过的区块键上的实时 size 之和，必须等于 {@link RevealedSet#positionCount()}
   * （后者就是 playerTotals 之和）。旧实现在锁外 remove，会在「读 size」与「remove」之间被并发 mark
   * 写入，按旧 size 扣减导致计数漂高；若 mark 落进被摘掉的孤儿对象，也会造成两者偏离。
   */
  @Test
  void concurrentMarkAndExpireKeepTotalsConsistent() throws Exception {
    long window = 10 * SECOND_NANOS;
    RevealedSet revealed = revealed(1 << 20, window);
    int keys = 4;
    int threads = 3;
    int perThread = 3000;
    ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch marksDone = new CountDownLatch(threads);
    CountDownLatch allDone = new CountDownLatch(threads + 1);
    try {
      for (int t = 0; t < threads; t++) {
        int base = t * perThread;
        pool.execute(() -> {
          try {
            for (int i = 0; i < perThread; i++) {
              int v = base + i;
              revealed.mark(PLAYER, chunk(v % keys, 0), v & 15, v >> 4 & 15, v & 7);
            }
          } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
          } finally {
            marksDone.countDown();
            allDone.countDown();
          }
        });
      }
      // 过期线程：持续推进时钟并触发 expire，制造与 mark 的交错（每推进一个窗口即让上一批标记过期）
      pool.execute(() -> {
        try {
          long t = 0;
          while (marksDone.getCount() > 0) {
            clock[0] = t;
            revealed.expire(t);
            t += window;
          }
        } catch (Throwable throwable) {
          failure.compareAndSet(null, throwable);
        } finally {
          allDone.countDown();
        }
      });
      assertTrue(allDone.await(30, TimeUnit.SECONDS), "并发 mark/expire 必须在超时内完成");
    } finally {
      pool.shutdownNow();
    }

    assertNull(failure.get(), "并发 mark/expire 不得抛异常：" + failure.get());
    int live = 0;
    for (int k = 0; k < keys; k++) {
      live += revealed.sizeFor(PLAYER, chunk(k, 0));
    }
    assertEquals(live, revealed.positionCount(),
        "playerTotals 必须与存活集合大小自洽（无孤儿标记 / 无扣减漂移）");
  }

  /**
   * 并发 clearChunk 与 removePosition 交错：两者必须用<b>同一判据</b>（持「映射监视器」并校验映射仍在
   * {@code chunks} 里），否则同一坐标会被 removePosition 摘一次（扣 1）、又被 clearChunk 按旧 size 计一次，
   * 使 playerTotals 被多扣（低估，方向为「越清越少」）。
   *
   * <p>判据与 {@link #concurrentMarkAndExpireKeepTotalsConsistent} 同：清点全部区块键上的实时 size 之和
   * 必须等于 {@link RevealedSet#positionCount()}。
   */
  @Test
  void concurrentClearChunkAndRemoveKeepTotalsConsistent() throws Exception {
    RevealedSet revealed = revealed(1 << 20, 600 * SECOND_NANOS);
    ChunkKey[] chunkKeys = {
        new ChunkKey(WORLD, -1, -1), new ChunkKey(WORLD, -1, 0),
        new ChunkKey(WORLD, 0, -1), new ChunkKey(WORLD, 0, 0),
    };
    int threads = 3;
    int perThread = 4000;
    ExecutorService pool = Executors.newFixedThreadPool(threads * 2 + 1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(threads * 2 + 1);
    try {
      for (int t = 0; t < threads; t++) {
        int base = t * perThread;
        // 标记线程：x/z 落在 [-16,15]，其导出的区块键恰为上面 4 个（保证后续 removePosition 能命中）
        pool.execute(() -> {
          try {
            for (int i = 0; i < perThread; i++) {
              int v = base + i;
              int x = (v & 31) - 16;
              int z = (v >> 5 & 31) - 16;
              int y = (v >> 10) & 255;
              revealed.mark(PLAYER, ChunkKey.ofBlock(WORLD, x, z), x, y, z);
            }
          } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
          } finally {
            done.countDown();
          }
        });
        // 注销线程：与标记线程用同一坐标编码，制造与 clearChunk 的三方交错
        pool.execute(() -> {
          try {
            for (int i = 0; i < perThread; i++) {
              int v = base + i;
              revealed.removePosition(WORLD, (v & 31) - 16, (v >> 10) & 255, (v >> 5 & 31) - 16);
            }
          } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
          } finally {
            done.countDown();
          }
        });
      }
      // clearChunk 线程：轮流清掉 4 个区块，与 mark / removePosition 交错
      pool.execute(() -> {
        try {
          for (int i = 0; i < chunkKeys.length * 64; i++) {
            revealed.clearChunk(chunkKeys[i % chunkKeys.length]);
          }
        } catch (Throwable throwable) {
          failure.compareAndSet(null, throwable);
        } finally {
          done.countDown();
        }
      });
      assertTrue(done.await(60, TimeUnit.SECONDS), "并发 clearChunk/removePosition/mark 必须在超时内完成");
    } finally {
      pool.shutdownNow();
    }

    assertNull(failure.get(), "并发清理不得抛异常：" + failure.get());
    int live = 0;
    for (ChunkKey key : chunkKeys) {
      live += revealed.sizeFor(PLAYER, key);
    }
    assertEquals(live, revealed.positionCount(),
        "clearChunk 与 removePosition 判据一致：计数必须与存活集合自洽（不得二次命中多扣）");
  }
}