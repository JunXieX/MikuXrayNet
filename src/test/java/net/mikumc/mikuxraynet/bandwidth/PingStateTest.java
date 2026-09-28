package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 高延迟降视距：阈值与持续时长判定（时钟由测试注入）。 */
class PingStateTest {

  private static final int THRESHOLD = 400;
  private static final long SUSTAIN = 30_000L;
  private static final long START = 10_000L;

  @Test
  @DisplayName("首次超阈值只是开始观察，不立刻降级")
  void firstHighSampleStartsObservation() {
    PingState state = new PingState();

    assertFalse(state.shouldReduce(500, START, THRESHOLD, SUSTAIN));
    assertFalse(state.reduced());
  }

  @Test
  @DisplayName("未达到持续时长不降级，达到持续时长才降级")
  void reducesOnlyAfterSustainDuration() {
    PingState state = new PingState();

    assertFalse(state.shouldReduce(500, START, THRESHOLD, SUSTAIN));
    assertFalse(state.shouldReduce(500, START + SUSTAIN - 1L, THRESHOLD, SUSTAIN));
    assertTrue(state.shouldReduce(500, START + SUSTAIN, THRESHOLD, SUSTAIN));
    assertTrue(state.reduced());
  }

  @Test
  @DisplayName("延迟未回落到阈值以下时不会重复降级或还原")
  void doesNotRepeatAdjustmentWhileStillHigh() {
    PingState state = new PingState();
    state.shouldReduce(500, START, THRESHOLD, SUSTAIN);
    assertTrue(state.shouldReduce(500, START + SUSTAIN, THRESHOLD, SUSTAIN));

    assertFalse(state.shouldReduce(450, START + SUSTAIN + 1_000L, THRESHOLD, SUSTAIN));
    assertFalse(state.shouldRestore(450, THRESHOLD));
  }

  @Test
  @DisplayName("延迟回落到阈值以下才还原，且只还原一次")
  void restoresAfterPingDrops() {
    PingState state = new PingState();
    state.shouldReduce(500, START, THRESHOLD, SUSTAIN);
    assertTrue(state.shouldReduce(500, START + SUSTAIN, THRESHOLD, SUSTAIN));

    assertFalse(state.shouldRestore(THRESHOLD, THRESHOLD));
    assertTrue(state.shouldRestore(THRESHOLD - 1, THRESHOLD));
    assertFalse(state.reduced());
    assertFalse(state.shouldRestore(THRESHOLD - 1, THRESHOLD));
  }

  @Test
  @DisplayName("中途回落会重置观察计时，需重新持续满时长")
  void dropResetsObservationTimer() {
    PingState state = new PingState();

    assertFalse(state.shouldReduce(500, START, THRESHOLD, SUSTAIN));
    // 回落：重置计时
    assertFalse(state.shouldReduce(200, START + 5_000L, THRESHOLD, SUSTAIN));

    long restart = START + 10_000L;
    assertFalse(state.shouldReduce(600, restart, THRESHOLD, SUSTAIN));
    assertFalse(state.shouldReduce(600, restart + SUSTAIN - 1L, THRESHOLD, SUSTAIN));
    assertTrue(state.shouldReduce(600, restart + SUSTAIN, THRESHOLD, SUSTAIN));
  }
}