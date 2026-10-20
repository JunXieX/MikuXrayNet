package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link RewriteCache} 的<b>字节预算</b>（{@code cache.maximum-bytes}）回归。
 *
 * <p><b>为什么必须单独测</b>：条目数上限（{@code maximumSize}）与字节上限是两条独立闸门，
 * 而改写结果同时持有「改写后字节 + 伪装坐标数组 + 整列遮挡负载」——只按条目数封顶时默认 40960 条
 * 在大服上可吃掉 1~2 GB 堆。字节记账一旦漂移（覆盖时不加差值、清表时不清零、单条超预算不拒），
 * 表现是「淘汰永不触发 / 内存无界增长」或「缓存被一条超大负载整体挤空」，都不会抛异常、极难察觉。
 *
 * <p>测试用包内可见构造注入固定时钟与 {@code weigher}（{@code String::length}），因此全部断言
 * 都是确定性的：不依赖真实时钟、不依赖线程调度、不依赖机器速度。
 *
 * <p><b>与 {@link RewriteCacheTest} 的分工（避免两处守门漂移）</b>：
 * <ul>
 *   <li>{@code RewriteCacheTest}：键/命中计数、访问过期、world 失效、LRU 淘汰顺序等<b>缓存语义</b>，
 *       并含一组字节预算的「基本行为」（按 CLOCK 淘汰、覆盖 delta、单条超限、失效/过期归还字节）；</li>
 *   <li>本类：字节记账的<b>边界与异常方向</b>——恰好等于预算必须接受、单条超限拒收且不驻留、
 *       覆盖变长/变短/同体积的差值、过期条目不占字节预算、weigher 返回 0 或负数时的口径、
 *       整世界失效后的重算。</li>
 * </ul>
 * 改动字节记账语义（{@code put/overwrite/evictOverflow/invalidateWorld/bytes()}）时，
 * <b>两处都要看</b>：若将来决定只保留一份守门，请把 {@code RewriteCacheTest} 的字节用例折进本类
 * （本类已覆盖其全部断言方向），而不是只改其中一处。
 */
class RewriteCacheByteBudgetTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

  /** 可推进的测试时钟：只在需要验证「过期条目不得占字节预算」时使用。 */
  private final long[] clock = {0L};

  private RewriteCache<String> cache(int maximumSize, long maximumBytes, long expireNanos) {
    return new RewriteCache<>(maximumSize, maximumBytes, expireNanos, () -> clock[0],
        String::length);
  }

  @Test
  void singleEntryLargerThanBudgetIsRejected() {
    RewriteCache<String> cache = cache(8, 100L, 60 * SECOND_NANOS);

    // 超过总预算的单条：不入缓存（否则它一进来就会把此前所有条目挤掉，表现为命中率归零）
    cache.put("world", 0, 0, 1, "a".repeat(101));
    assertNull(cache.get("world", 0, 0, 1), "单条超过字节预算必须拒收（按未命中处理）");
    assertEquals(0, cache.rawSize(), "被拒的条目不得驻留");
    assertEquals(0L, cache.bytes(), "被拒的条目不得计入字节账");

    // 边界：恰好等于预算必须接受（实现用的是 >，不是 >=）
    String exact = "b".repeat(100);
    cache.put("world", 0, 0, 1, exact);
    assertSame(exact, cache.get("world", 0, 0, 1), "恰好等于字节预算的条目必须正常缓存");
    assertEquals(100L, cache.bytes(), "字节账必须等于该条目的体积");
  }

  @Test
  void overwriteAdjustsByteTotalByDeltaOnly() {
    RewriteCache<String> cache = cache(64, 10_000L, 60 * SECOND_NANOS);

    cache.put("world", 0, 0, 1, "aaaa");   // 4
    cache.put("world", 1, 0, 1, "aaaaa");  // 5
    assertEquals(9L, cache.bytes(), "两条新条目的字节账必须累加");

    // 同键覆盖变长：只加差值（+4），不是「再整体加一次新长度」
    String longer = "a".repeat(8);
    cache.put("world", 0, 0, 1, longer);
    assertSame(longer, cache.get("world", 0, 0, 1));
    assertEquals(13L, cache.bytes(), "同键覆盖必须按体积差值记账（4 + 5 - 4 + 8 = 13）");

    // 同键覆盖变短：只减差值（-4）
    cache.put("world", 1, 0, 1, "a");
    assertEquals(9L, cache.bytes(), "覆盖为更短的值必须扣回差值");
    assertEquals(2, cache.rawSize(), "覆盖不得产生重复条目");

    // 同体积覆盖：字节账不变
    cache.put("world", 0, 0, 1, "b".repeat(8));
    assertEquals(9L, cache.bytes(), "同体积覆盖不得改变字节账");
  }

  @Test
  void byteBudgetAloneTriggersEvictionAndFreesBytes() {
    // 条目上限放大，只让字节预算触发淘汰：3 条各 4 字节 = 12 > 10
    RewriteCache<String> cache = cache(100, 10L, 60 * SECOND_NANOS);

    cache.put("world", 0, 0, 1, "aaaa");
    cache.put("world", 1, 0, 1, "bbbb");
    cache.put("world", 2, 0, 1, "cccc");

    assertEquals(2, cache.rawSize(), "超出字节预算必须淘汰条目（容量上限远未到，只有字节预算在起作用）");
    assertEquals(8L, cache.bytes(), "淘汰后字节账必须精确回落到预算内");
    assertNull(cache.get("world", 0, 0, 1), "最久未使用（且未被二次访问）的条目应被淘汰");
    assertTrue(cache.get("world", 1, 0, 1) != null, "其余条目必须保留");
    assertTrue(cache.get("world", 2, 0, 1) != null, "其余条目必须保留");
    assertEquals(8L, cache.bytes(), "读取不得改变字节账");
  }

  @Test
  void entryCountLimitStillAppliesWhenByteBudgetIsGenerous() {
    RewriteCache<String> cache = cache(2, Long.MAX_VALUE, 60 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "a");
    cache.put("world", 1, 0, 1, "b");
    cache.put("world", 2, 0, 1, "c");

    assertEquals(2, cache.rawSize(), "字节预算宽松时条目数上限必须照常生效");
    assertEquals(2L, cache.bytes(), "两条存活条目的字节账必须一致");
  }

  @Test
  void invalidationRecomputesBytesInsteadOfLeavingDrift() {
    RewriteCache<String> cache = cache(64, 100_000L, 60 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "aaaa");        // 4
    cache.put("world", 1, 0, 1, "bbbbbb");      // 6
    cache.put("nether", 0, 0, 1, "ccccc");      // 5
    assertEquals(15L, cache.bytes());

    cache.invalidateWorld("world");
    assertEquals(1, cache.rawSize(), "只应剩 nether 的一条");
    assertEquals(5L, cache.bytes(), "整世界失效后字节账必须按剩余条目重算（不得残留 4+6）");

    cache.invalidateAll();
    assertEquals(0, cache.rawSize());
    assertEquals(0L, cache.bytes(), "全量失效必须把字节账归零");
  }

  @Test
  void expiredEntriesDoNotConsumeByteBudget() {
    // 过期窗口 5 秒；先放一条 6 字节，再把时钟推过窗口，然后放另一条 6 字节
    RewriteCache<String> cache = cache(64, 10L, 5 * SECOND_NANOS);
    cache.put("world", 0, 0, 1, "aaaaaa");
    assertEquals(6L, cache.bytes());

    clock[0] = 6 * SECOND_NANOS;
    cache.put("world", 1, 0, 1, "bbbbbb");

    assertEquals(6L, cache.bytes(), "过期条目不得占用字节预算：淘汰路径必须先剔除它再记账");
    assertEquals(1, cache.rawSize(), "过期条目必须被清除，只留新条目");
    assertNull(cache.get("world", 0, 0, 1), "过期条目不得再命中");
    assertTrue(cache.get("world", 1, 0, 1) != null, "新条目必须保留");
  }

  @Test
  void zeroOrNegativeWeightedEntriesAreNotCharged() {
    RewriteCache<String> zero = new RewriteCache<>(64, 4L, TimeUnit.SECONDS.toNanos(60),
        () -> clock[0], value -> 0L);
    zero.put("world", 0, 0, 1, "a".repeat(1024));
    assertEquals(0L, zero.bytes(), "weigher 返回 0 表示不计账（体积未知/已内嵌到别处）");
    assertEquals(1, zero.rawSize());

    RewriteCache<String> negative = new RewriteCache<>(64, 4L, TimeUnit.SECONDS.toNanos(60),
        () -> clock[0], value -> -5L);
    negative.put("world", 0, 0, 1, "a".repeat(1024));
    assertEquals(0L, negative.bytes(), "weigher 返回负数必须按 0 处理（不得把字节账算成负值）");
  }
}
