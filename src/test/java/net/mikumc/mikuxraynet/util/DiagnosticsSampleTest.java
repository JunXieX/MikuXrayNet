package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 10 秒采样报告的格式回归：增量/速率、峰值、计数重置与样本不足都要如实表达。
 */
class DiagnosticsSampleTest {

  private static Diagnostics.Sample sample(double tps, double mspt, int players, long rewritten,
      long reveals, int queue, int queueCapacity) {
    return new Diagnostics.Sample(tps, mspt, players, rewritten, rewritten * 7, reveals, 0L,
        0L, 0L, 0L, 0L, 0L, 0L, queue, queueCapacity, 1, 1000, 10, 800);
  }

  @Test
  void reportsRatesAndPeaks() {
    List<Diagnostics.Sample> samples = List.of(
        sample(20.00D, 1.00D, 5, 100L, 10L, 0, 256),
        sample(18.50D, 3.00D, 6, 110L, 14L, 3, 256),
        sample(19.50D, 2.00D, 6, 120L, 18L, 1, 256));
    String text = Diagnostics.formatSamples(samples, 10_000L, false);
    assertTrue(text.contains("窗口：10.0 秒"), text);
    assertTrue(text.contains("TPS（1m）：最小 18.50 / 平均 19.33 / 最大 20.00"), text);
    assertTrue(text.contains("改写区块：+20（2.0/s）"), text);
    assertTrue(text.contains("显形发送：+8（0.8/s）"), text);
    assertTrue(text.contains("线程池队列：最小 0 / 平均 1.3 / 最大 3"), text);
    assertTrue(text.contains("期间发生过热重载：否"), text);
  }

  @Test
  void marksCounterReset() {
    List<Diagnostics.Sample> samples = List.of(
        sample(20.00D, 1.00D, 5, 500L, 10L, 0, 256),
        sample(20.00D, 1.00D, 5, 20L, 3L, 0, 256));
    String text = Diagnostics.formatSamples(samples, 10_000L, true);
    assertTrue(text.contains("计数在窗口内被重置"), text);
    assertTrue(text.contains("期间发生过热重载：是"), text);
  }

  @Test
  void insufficientSamplesAreReported() {
    String text = Diagnostics.formatSamples(List.of(sample(20.0D, 1.0D, 1, 0L, 0L, 0, 256)),
        10_000L, false);
    assertTrue(text.contains("样本不足"), text);
  }
}
