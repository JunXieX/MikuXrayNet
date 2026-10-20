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
    assertFalse(state.reduced(), "判定阶段不得预置「已降级」：是否真的降级由调用方在生效后回报");
    state.markReduced();
    assertTrue(state.reduced());
  }

  @Test
  @DisplayName("延迟未回落到阈值以下时不会重复降级或还原")
  void doesNotRepeatAdjustmentWhileStillHigh() {
    PingState state = new PingState();
    state.shouldReduce(500, START, THRESHOLD, SUSTAIN);
    assertTrue(state.shouldReduce(500, START + SUSTAIN, THRESHOLD, SUSTAIN));
    state.markReduced(); // 降级确实生效后才回报（B1：判定与生效分离）

    assertFalse(state.shouldReduce(450, START + SUSTAIN + 1_000L, THRESHOLD, SUSTAIN),
        "已降级：同一段高延迟内不得重复降级");
    assertFalse(state.shouldRestore(450, THRESHOLD));
  }

  @Test
  @DisplayName("延迟回落到阈值以下才还原；还原成功后不再重复要求还原")
  void restoresAfterPingDrops() {
    PingState state = new PingState();
    state.shouldReduce(500, START, THRESHOLD, SUSTAIN);
    assertTrue(state.shouldReduce(500, START + SUSTAIN, THRESHOLD, SUSTAIN));
    state.markReduced();

    assertFalse(state.shouldRestore(THRESHOLD, THRESHOLD), "等于阈值仍算高延迟");
    assertTrue(state.shouldRestore(THRESHOLD - 1, THRESHOLD));
    // 「已降级」标记由调用方在还原成功后回报清掉（见 markRestored）
    assertTrue(state.reduced(), "回报之前仍是已降级：还原失败时下一次采样必须继续要求还原");
    assertTrue(state.shouldRestore(THRESHOLD - 1, THRESHOLD), "还原失败后必须能重试");
    state.markRestored();
    assertFalse(state.reduced());
    assertFalse(state.shouldRestore(THRESHOLD - 1, THRESHOLD), "还原成功后不再重复要求还原");
  }

  @Test
  @DisplayName("还原成功并回报后，再次持续高延迟仍可重新降级")
  void canReduceAgainAfterRestoration() {
    PingState state = new PingState();
    state.shouldReduce(500, START, THRESHOLD, SUSTAIN);
    assertTrue(state.shouldReduce(500, START + SUSTAIN, THRESHOLD, SUSTAIN));
    state.markReduced();

    // 延迟回落 → 要求还原，但调用方写入失败（不回报 markRestored）
    assertTrue(state.shouldRestore(THRESHOLD - 1, THRESHOLD));
    assertTrue(state.reduced());
    // 延迟又升回阈值以上且持续足够久，但视距其实没还原成功 → 不得再次降级（避免把降级值当原始值记录）
    assertFalse(state.shouldReduce(500, START + SUSTAIN + 1_000L, THRESHOLD, SUSTAIN));

    // 还原成功并回报后，下一次持续高延迟必须可以再次降级
    state.markRestored();
    // 先回落一次以重置观察计时（真实序列里还原成功的那次采样本身就低于阈值）
    assertFalse(state.shouldReduce(100, START + SUSTAIN + 30_000L, THRESHOLD, SUSTAIN));
    long restart = START + SUSTAIN + 60_000L;
    assertFalse(state.shouldReduce(500, restart, THRESHOLD, SUSTAIN), "重新开始观察");
    assertTrue(state.shouldReduce(500, restart + SUSTAIN, THRESHOLD, SUSTAIN));
  }

  /**
   * B1 回归：{@code shouldReduce} 只回答「该不该降」，不再预置「已降级」。因此降级尝试没真正生效
   * （被服务端视距/配置下限钳制、写视距抛异常）时必须能在下一次采样重试；而真正生效后（调用方回报
   * {@code markReduced}）则不得重复降级——否则会把降级值当成原始值记录，视距被永久压低。
   */
  @Test
  @DisplayName("降级未生效时可重试；生效并回报后不再重复降级")
  void failedReductionCanBeRetriedUntilReported() {
    PingState state = new PingState();

    assertFalse(state.shouldReduce(500, START, THRESHOLD, SUSTAIN));
    assertTrue(state.shouldReduce(500, START + SUSTAIN, THRESHOLD, SUSTAIN));
    assertFalse(state.reduced(), "未回报前状态机不得认为自己已降级");
    assertTrue(state.shouldReduce(500, START + SUSTAIN + 5_000L, THRESHOLD, SUSTAIN),
        "降级没生效（未回报）时必须允许下次采样重试");

    state.markReduced();
    assertTrue(state.reduced());
    assertFalse(state.shouldReduce(500, START + SUSTAIN + 10_000L, THRESHOLD, SUSTAIN),
        "降级已生效：同一段高延迟内不得重复降级");
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