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

  /** 记录一次延迟采样，返回是否需要在本次采样<b>尝试</b>还原视距。 */
  public boolean shouldRestore(int ping, int thresholdMillis) {
    if (ping >= thresholdMillis) {
      return false;
    }
    highSinceMillis = NO_TIME;
    return reduced;
  }

  /**
   * 视距是否已真正还原（由调用方在还原<b>成功</b>后回报），清掉「已降级」标记，使下一次高延迟可以再次降级。
   *
   * <p><b>为什么不能像旧实现那样在 {@link #shouldRestore} 里就先把 {@code reduced} 置为 false</b>：
   * 那样「判定要还原」与「真的还原了」被混为一谈——一旦 {@code setSendViewDistance} 抛异常，状态机已认为
   * 没在降级，此后的采样永不再返回 true，玩家会<b>永久卡在降级后的视距</b>直到重登。现在由成功路径回报，
   * 失败则保持 {@code reduced=true}，下一次采样继续重试。
   */
  public void markRestored() {
    reduced = false;
  }
}