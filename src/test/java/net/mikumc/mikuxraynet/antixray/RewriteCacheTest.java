package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 改写缓存生命周期测试：写入/读取计数、访问过期、按世界整体失效、LRU 淘汰，
 * 以及「键结构只含基本类型与字符串」的结构性断言（自证不持有 World/Chunk/Player/封包 强引用）。
 */
class RewriteCacheTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final long[] clock = {0L};

  private RewriteCache<String> cache(int maximumSize, long expireAfterAccessNanos) {
    return new RewriteCache<>(maximumSize, expireAfterAccessNanos, () -> clock[0]);
  }

  @Test
  void putAndGetTrackHitsAndMisses() {
    RewriteCache<String> cache = cache(8, 60 * SECOND_NANOS);
    cache.put("world", 1, 2, 7, "payload");

    assertEquals("payload", cache.get("world", 1, 2, 7));
    assertEquals(1, cache.hitCount(), "命中计数");

    assertNull(cache.get("world", 1, 2, 8), "配置指纹不同视为未命中");
    assertNull(cache.get("nether", 1, 2, 7), "世界名不同视为未命中");
    assertNull(cache.get("world", 3, 2, 7), "区块坐标不同视为未命中");

    assertEquals(3, cache.missCount(), "未命中计数");
    assertEquals(1, cache.size());
  }

  @Test
  void entryExpiresAfterAccessWindow() {
    RewriteCache<String> cache = cache(8, 10 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "payload");

    clock[0] = 9 * SECOND_NANOS;
    assertEquals("payload", cache.get("world", 0, 0, 1), "窗口内应命中，并刷新访问时间");

    clock[0] = 18 * SECOND_NANOS;
    assertEquals("payload", cache.get("world", 0, 0, 1), "距上次访问 9 秒（窗口 10 秒）仍应命中");

    clock[0] = 29 * SECOND_NANOS;
    assertNull(cache.get("world", 0, 0, 1), "距上次访问 11 秒已过期");
    assertEquals(0, cache.size(), "过期条目必须在读取时被移除");
  }

  @Test
  void invalidateWorldRemovesOnlyThatWorld() {
    RewriteCache<String> cache = cache(8, 60 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "a");
    cache.put("world", 1, 1, 1, "b");
    cache.put("nether", 0, 0, 1, "c");

    cache.invalidateWorld("world");

    assertEquals(1, cache.size(), "只应移除目标世界的条目");
    assertNull(cache.get("world", 0, 0, 1));
    assertNull(cache.get("world", 1, 1, 1));
    assertEquals("c", cache.get("nether", 0, 0, 1), "其它世界的条目必须保留");
  }

  @Test
  void invalidateAllClearsEverything() {
    RewriteCache<String> cache = cache(8, 60 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "a");
    cache.put("nether", 0, 0, 1, "b");

    cache.invalidateAll();

    assertEquals(0, cache.size());
    assertNull(cache.get("world", 0, 0, 1));
    assertNull(cache.get("nether", 0, 0, 1));
  }

  @Test
  void maximumSizeEvictsLeastRecentlyUsed() {
    RewriteCache<String> cache = cache(2, 60 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "a");
    cache.put("world", 1, 0, 1, "b");
    assertEquals("a", cache.get("world", 0, 0, 1), "访问 a 使其成为最近使用项");

    cache.put("world", 2, 0, 1, "c");

    assertEquals(2, cache.size(), "容量上限生效");
    assertNull(cache.get("world", 1, 0, 1), "最久未使用的条目应被淘汰");
    assertEquals("a", cache.get("world", 0, 0, 1));
    assertEquals("c", cache.get("world", 2, 0, 1));
  }

  /** 结构断言：键只由基本类型与不可变字符串组成，不可能间接钉住世界/区块/玩家/封包对象。 */
  @Test
  void keyHoldsOnlyPrimitivesAndStrings() {
    Class<?> keyType = RewriteCache.Key.class;
    assertTrue(keyType.isRecord(), "键应为不可变 record");

    Field[] fields = keyType.getDeclaredFields();
    assertEquals(4, fields.length, "键字段应恰为 worldName/x/z/configHash");

    for (Field field : fields) {
      Class<?> fieldType = field.getType();
      assertTrue(fieldType.isPrimitive() || fieldType == String.class,
          "键字段只允许基本类型或 String，实际为：" + fieldType.getName());
    }
  }

  /** 未被再访问的过期条目不得计入 size()：旧实现按 entries.size() 统计会「虚高」。 */
  @Test
  void expiredEntriesAreExcludedFromSize() {
    RewriteCache<String> cache = cache(8, 10 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "a");
    cache.put("world", 1, 0, 1, "b");
    assertEquals(2, cache.size());

    clock[0] = 11 * SECOND_NANOS;
    assertEquals(0, cache.size(), "未被再访问的过期条目不得计入有效条目数");
  }

  /** 过期条目不得占用淘汰名额：余量够时新条目不得因过期条目占位而被 CLOCK 淘汰。 */
  @Test
  void expiredEntriesDoNotConsumeEvictionSlots() {
    RewriteCache<String> cache = cache(2, 10 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "a");
    cache.get("world", 0, 0, 1); // 置 second-chance 位：旧实现会把它当「有效候选」反复轮转
    cache.put("world", 1, 0, 1, "b");
    cache.get("world", 1, 0, 1);

    clock[0] = 11 * SECOND_NANOS; // 两条都过期
    cache.put("world", 2, 0, 1, "c");
    cache.put("world", 3, 0, 1, "d");

    assertEquals(2, cache.size(), "过期条目被剔除后只应剩两条有效条目");
    assertEquals("c", cache.get("world", 2, 0, 1), "新条目不得因过期条目占位而被淘汰");
    assertEquals("d", cache.get("world", 3, 0, 1), "新条目不得因过期条目占位而被淘汰");
  }

  /**
   * 冷条目（put 后永不再 get）在过期后必须被摊还清理回收。
   *
   * <p>回归：旧实现只有「get 命中已过期」与「put 且超容量」两个回收触发点，多数区块只下发一次、
   * 此后永不 get，于是过期冷条目会一直驻留到顶满上限——「按过期时间回收」对它们失效。
   */
  @Test
  void coldExpiredEntriesAreReclaimedByAmortizedSweep() {
    RewriteCache<String> cache = cache(64, 10 * SECOND_NANOS);
    for (int i = 0; i < 6; i++) {
      cache.put("world", i, 0, 1, "cold" + i);
    }
    assertEquals(6, cache.rawSize(), "前置：6 条冷条目入驻");

    clock[0] = 11 * SECOND_NANOS; // 全部过期，但此后不再 get，只能靠摊还清理回收
    for (int i = 0; i < 3; i++) {
      cache.put("world", 100 + i, 0, 1, "fresh" + i); // 新建条目触发摊还清理
    }

    assertEquals(3, cache.rawSize(), "过期冷条目必须被摊还清理回收，不得无限期驻留");
    assertEquals(3, cache.size(), "只应剩 3 条新写入的有效条目");
    for (int i = 0; i < 6; i++) {
      assertNull(cache.get("world", i, 0, 1), "过期冷条目必须已被回收：冷条目 " + i);
    }
  }

  /** 摊还清理不得误伤有效条目：窗口内、或近期被访问过的条目必须原样保留。 */
  @Test
  void activeEntriesSurviveAmortizedSweep() {
    RewriteCache<String> cache = cache(64, 10 * SECOND_NANOS);
    for (int i = 0; i < 5; i++) {
      cache.put("world", i, 0, 1, "v" + i);
    }

    clock[0] = 6 * SECOND_NANOS;
    assertEquals("v0", cache.get("world", 0, 0, 1), "刷新 k0 的访问时间，使其在新时刻仍有效");

    clock[0] = 12 * SECOND_NANOS; // k1..k4 距上次访问 12 秒已过期；k0 仅 6 秒仍有效
    for (int i = 0; i < 5; i++) {
      cache.put("world", 100 + i, 0, 1, "n" + i);
    }

    assertEquals(6, cache.rawSize(), "应为 k0 + 5 条新条目（k1..k4 已过期回收）");
    assertEquals(6, cache.size());
    assertEquals("v0", cache.get("world", 0, 0, 1), "窗口内仍有效的条目不得被清理");
    for (int i = 1; i < 5; i++) {
      assertNull(cache.get("world", i, 0, 1), "过期条目应已被回收：k" + i);
    }
  }

  /** size() 只报活跃数、rawSize() 报原始条目数：口径分离，供诊断区分「已过期未回收」的驻留量。 */
  @Test
  void sizeSeparatesActiveFromRaw() {
    RewriteCache<String> cache = cache(8, 10 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "a");
    cache.put("world", 1, 0, 1, "b");
    assertEquals(2, cache.size());
    assertEquals(2, cache.rawSize());

    clock[0] = 11 * SECOND_NANOS; // 两条都过期，但尚未被巡检回收
    assertEquals(0, cache.size(), "活跃数立即排除过期条目");
    assertEquals(2, cache.rawSize(), "原始数仍含未回收的过期条目——两者之差即驻留量");

    cache.put("world", 2, 0, 1, "c"); // 触发摊还清理
    assertEquals(1, cache.size());
    assertEquals(1, cache.rawSize(), "摊还清理后原始数收敛到活跃数");
  }

  /** 并发回填不同键：缩小同步范围（只在新建条目时维护 CLOCK 队列）后不得丢条目。 */
  @Test
  void concurrentPutsKeepEveryEntry() throws Exception {
    RewriteCache<String> cache = cache(1 << 20, 600 * SECOND_NANOS);
    int threads = 4;
    int perThread = 500;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(threads);
    try {
      for (int t = 0; t < threads; t++) {
        int base = t * perThread;
        pool.execute(() -> {
          try {
            for (int i = 0; i < perThread; i++) {
              int v = base + i;
              cache.put("world", v, 0, 1, "v" + v);
            }
          } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
          } finally {
            done.countDown();
          }
        });
      }
      assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入必须在超时内完成");
    } finally {
      pool.shutdownNow();
    }

    assertNull(failure.get(), "并发写入不得抛异常：" + failure.get());
    assertEquals(threads * perThread, cache.size(), "并发写入的每条条目都不得丢失");
    for (int v = 0; v < threads * perThread; v++) {
      assertEquals("v" + v, cache.get("world", v, 0, 1), "键 z=" + v);
    }
  }
}