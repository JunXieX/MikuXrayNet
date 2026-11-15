package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 视野增量耗时 p99 的守门用例。
 *
 * <p>为什么必须用 p99 而不是最大值：实机 dump（45 人 / 16 小时）报「最大 63.7 ms」，而每玩家每 tick 的
 * 预算只有 0.3 ms——差两个数量级。扫描的预算检查是<b>按区块粒度</b>做的，单个区块不可能耗 63.7 ms，
 * 因此那个最大值必然含 GC 或线程调度抖动。用最大值判断「预算是否配小」会得出错误结论。
 */
class ViewChangePercentileTest {

  private static final long MS = 1_000_000L;

  @Test
  void singleSampleIsItsOwnPercentile() {
    long[] buckets = new long[ProximityStats.VIEW_CHANGE_BUCKET_UPPER_NANOS.length];
    buckets[2] = 1L; // 0.5 ms 档
    assertEquals(0.5D, ProximityStats.percentileMillis(buckets, 0.99D), 1e-9);
    assertEquals(0.5D, ProximityStats.percentileMillis(buckets, 0.5D), 1e-9);
  }

  @Test
  void p99IgnoresTheSingleOutlier() {
    long[] buckets = new long[ProximityStats.VIEW_CHANGE_BUCKET_UPPER_NANOS.length];
    // 99 次落在 0.25 ms 档（索引 1），1 次落在 50 ms 档（索引 8）
    buckets[1] = 99L;
    buckets[8] = 1L;
    assertEquals(0.25D, ProximityStats.percentileMillis(buckets, 0.99D), 1e-9,
        "99% 分位不该被 1% 的毛刺拉高");
    // 最大值档位的语义仍要能取到（100% 分位）
    assertEquals(50D, ProximityStats.percentileMillis(buckets, 1.0D), 1e-9);
  }

  @Test
  void overflowBucketReportsInfinityNotAFakeNumber() {
    long[] buckets = new long[ProximityStats.VIEW_CHANGE_BUCKET_UPPER_NANOS.length];
    buckets[buckets.length - 1] = 4L;
    double p99 = ProximityStats.percentileMillis(buckets, 0.99D);
    assertTrue(Double.isInfinite(p99), "落在最高档（≥100 ms）时必须返回 INFINITY，由渲染层写「≥100 ms」");
  }

  @Test
  void emptyAndMalformedInputsDoNotThrow() {
    assertEquals(-1.0D, ProximityStats.percentileMillis(null, 0.99D));
    assertEquals(-1.0D, ProximityStats.percentileMillis(new long[3], 0.99D));
    assertEquals(-1.0D, ProximityStats.percentileMillis(
        new long[ProximityStats.VIEW_CHANGE_BUCKET_UPPER_NANOS.length], 0.99D), "空样本必须返回 -1（未采集）");
    assertEquals(-1.0D, ProximityStats.percentileMillis(
        new long[ProximityStats.VIEW_CHANGE_BUCKET_UPPER_NANOS.length], 0.0D));
  }

  @Test
  void histogramRecordsIntoTheSmallestFittingBucket() {
    ProximityStats stats = new ProximityStats();
    stats.recordViewChange(0L);
    stats.recordViewChange(99_999L);
    stats.recordViewChange(100_001L);
    stats.recordViewChange(200_000_000L);
    long[] buckets = stats.viewChangeBucketSnapshot();
    assertEquals(2L, buckets[0], "0 与 99 999 纳秒都应落在第一档（<=100 000）");
    assertEquals(1L, buckets[1], "100 001 纳秒应落在第二档（<=250 000；第一档上界 100 000 是闭区间）");
    assertEquals(1L, buckets[buckets.length - 1], "200 ms 落在最高档");
    assertFalse(buckets.length == 0);
    // 负值不得污染直方图
    ProximityStats clean = new ProximityStats();
    clean.recordViewChange(-1L);
    assertEquals(0L, clean.viewChangeBucketSnapshot()[0]);
    assertTrue(MS > 0L);
  }
}
