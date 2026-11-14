package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 近处重试相位的纯函数口径：按「距上次已过 N tick」推进，与巡检间隔无关。
 *
 * <p>旧实现用 {@code getCurrentTick() % 25 == 0} 判等，只在恰好命中那一 tick 的巡检上成立；巡检间隔
 * 默认 4 tick，与 25 互质，实际周期被放大到 100 tick（5 秒），与注释承诺的 25 tick 差 4 倍。
 */
class NearRetryPhaseTest {

  @Test
  void firstCallIsAlwaysDue() {
    assertTrue(ProximityRevealer.nearRetryDue(Long.MIN_VALUE, 0L), "从未重试过必须立刻进入相位");
    assertTrue(ProximityRevealer.nearRetryDue(Long.MIN_VALUE, 12345L));
  }

  @Test
  void repeatsNotSoonerThanOnePeriod() {
    long last = 1000L;
    assertFalse(ProximityRevealer.nearRetryDue(last, 1001L), "下一 tick 不得重试");
    assertFalse(ProximityRevealer.nearRetryDue(last, 1024L), "未满 25 tick 不得重试");
    assertTrue(ProximityRevealer.nearRetryDue(last, 1025L), "满 25 tick 必须重试（与巡检间隔无关）");
    assertTrue(ProximityRevealer.nearRetryDue(last, 5000L), "远超周期必须重试");
  }

  @Test
  void clockGoingBackwardsDoesNotRetry() {
    assertFalse(ProximityRevealer.nearRetryDue(1000L, 999L), "tick 回落时按「未满」处理，避免抖动导致连续重发");
  }
}