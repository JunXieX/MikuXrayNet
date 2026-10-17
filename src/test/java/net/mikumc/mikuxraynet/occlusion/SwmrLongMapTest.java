package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link SwmrLongMap} 的正确性与并发冒烟测试。
 */
class SwmrLongMapTest {

  @Test
  void putGetRemoveAndResizeKeepAllEntries() {
    SwmrLongMap<String> map = new SwmrLongMap<>(4);
    int count = 5000; // 远超初始容量，强制多次 rehash
    for (int i = 0; i < count; i++) {
      assertNull(map.put(key(i), "v" + i));
    }
    assertEquals(count, map.size());
    for (int i = 0; i < count; i++) {
      assertEquals("v" + i, map.get(key(i)), "键 " + i + " 应命中");
    }
    // 覆盖写入不改变条目数
    assertEquals("v0", map.put(key(0), "v0-new"));
    assertEquals(count, map.size());
    assertEquals("v0-new", map.get(key(0)));

    // 删除后其余键仍可达（验证墓碑没有切断探测链）
    for (int i = 0; i < count; i += 2) {
      assertEquals("v" + i + (i == 0 ? "-new" : ""), map.remove(key(i)));
    }
    assertEquals(count / 2, map.size());
    for (int i = 1; i < count; i += 2) {
      assertEquals("v" + i, map.get(key(i)), "奇数键 " + i + " 删除后仍应可达");
    }
    for (int i = 0; i < count; i += 2) {
      assertNull(map.get(key(i)), "偶数键 " + i + " 应已删除");
    }
  }

  @Test
  void putIfAbsentOnlyInsertsWhenMissing() {
    SwmrLongMap<Integer> map = new SwmrLongMap<>();
    assertNull(map.putIfAbsent(key(1), 10));
    assertEquals(10, map.putIfAbsent(key(1), 20));
    assertEquals(10, map.get(key(1)));
    assertEquals(1, map.size());
  }

  @Test
  void clearEmptiesEntriesAndSlots() {
    SwmrLongMap<String> map = new SwmrLongMap<>();
    for (int i = 0; i < 100; i++) {
      map.put(key(i), "v" + i);
    }
    map.remove(key(3));
    map.clear();
    assertEquals(0, map.size());
    assertTrue(map.isEmpty());
    assertNull(map.get(key(5)));
    // 清空后可继续正常写入
    map.put(key(5), "again");
    assertEquals("again", map.get(key(5)));
    assertEquals(1, map.size());
  }

  /**
   * 批量删除：只删存在的键、返回真实删除数，且不影响其它键（整列摘除路径依赖它）。
   */
  @Test
  void removeAllDeletesOnlyPresentKeys() {
    SwmrLongMap<String> map = new SwmrLongMap<>();
    for (int i = 0; i < 50; i++) {
      map.put(key(i), "v" + i);
    }
    long[] batch = new long[20];
    for (int i = 0; i < 20; i++) {
      batch[i] = key(i); // 0..19 存在
    }
    assertEquals(20, map.removeAll(batch), "全部存在时删除数应等于批量键数");
    assertEquals(30, map.size());
    for (int i = 0; i < 20; i++) {
      assertNull(map.get(key(i)), "已删除键 " + i + " 不得再命中");
    }
    for (int i = 20; i < 50; i++) {
      assertEquals("v" + i, map.get(key(i)), "未列入批量的键必须保留");
    }

    // 重复删除同一批：已不存在，返回 0 且不改变条目数
    assertEquals(0, map.removeAll(batch));
    assertEquals(30, map.size());
    // 空数组 / null 是 no-op
    assertEquals(0, map.removeAll(new long[0]));
    assertEquals(0, map.removeAll(null));
  }

  @Test
  void concurrentWritersAndReadersStayConsistent() throws Exception {
    SwmrLongMap<String> map = new SwmrLongMap<>(16);
    int writers = 4;
    int perWriter = 2000;
    int keyStride = 1_000_000; // 每个写者一段互不重叠的键空间
    ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int w = 0; w < writers; w++) {
        long base = (long) w * keyStride;
        int writerIndex = w;
        futures.add(pool.submit(() -> {
          await(start);
          for (int i = 0; i < perWriter; i++) {
            map.put(base + i, "w" + writerIndex + "-" + i);
          }
        }));
      }
      // 两个读者：持续读取，不应抛异常（乐观读被打断时应退化为读锁而不是崩溃）
      for (int r = 0; r < 2; r++) {
        futures.add(pool.submit(() -> {
          await(start);
          for (int i = 0; i < perWriter * 4; i++) {
            map.get(i);
          }
        }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(writers * perWriter, map.size());
    for (int w = 0; w < writers; w++) {
      long base = (long) w * keyStride;
      for (int i = 0; i < perWriter; i++) {
        assertEquals("w" + w + "-" + i, map.get(base + i));
      }
    }
    assertSame(null, map.get(-1));
    assertFalse(map.isEmpty());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** 生成分散且非连续的长键，避免开放寻址在连续键上退化。 */
  private static long key(int index) {
    return index * 0x9E3779B97F4A7C15L;
  }
}
