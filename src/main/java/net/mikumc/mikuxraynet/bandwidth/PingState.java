package net.mikumc.mikuxraynet.bandwidth;

/**
 * 单玩家的高延迟判定状态机（纯逻辑，时钟由调用方注入，便于单元测试）。
 *
 * <p>降级条件：延迟持续不低于阈值达到 {@code sustainMillis}；
 * 恢复正常条件：延迟回落到阈值以下。状态机只负责判定，实际视距调整由调用方在所有者线程执行。
 */
public final class PingState {

  private static final long NO_TIME = -1L;

  private long highSinceMillis = NO_TIME;
  private boolean reduced;

  /**
   * 是否处于已降级状态。
   *
   * <p>测试专用豁免：生产路径只用 {@link #shouldReduce} / {@link #shouldRestore} 的返回值，
   * 本方法当前仅单测在用，保留以免破坏测试。
   */
  public boolean reduced() {
    return reduced;
  }

  /** 记录一次延迟采样，返回是否需要在本次采样执行「降视距」。 */
  public boolean shouldReduce(int ping, long nowMillis, int thresholdMillis, long sustainMillis) {
    if (ping < thresholdMillis) {
      highSinceMillis = NO_TIME;
      return false;
    }
    if (highSinceMillis == NO_TIME) {
      highSinceMillis = nowMillis;
      return false;
    }
    if (reduced || nowMillis - highSinceMillis < sustainMillis) {
      return false;
    }
    reduced = true;
    return true;
  }

  /** 记录一次延迟采样，返回是否需要在本次采样执行「还原视距」。 */
  public boolean shouldRestore(int ping, int thresholdMillis) {
    if (ping >= thresholdMillis) {
      return false;
    }
    highSinceMillis = NO_TIME;
    if (!reduced) {
      return false;
    }
    reduced = false;
    return true;
  }
}