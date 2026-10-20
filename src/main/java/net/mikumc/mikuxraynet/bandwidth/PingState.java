package net.mikumc.mikuxraynet.bandwidth;

/**
 * 单玩家的高延迟判定状态机（纯逻辑，时钟由调用方注入，便于单元测试）。
 *
 * <p>降级条件：延迟持续不低于阈值达到 {@code sustainMillis}；
 * 恢复正常条件：延迟回落到阈值以下。状态机只负责判定，实际视距调整由调用方在所有者线程执行。
 *
 * <p><b>「已降级」标记只在降级真正生效后由调用方回报</b>（{@link #markReduced()}）：
 * {@link #shouldReduce} 只回答「该不该降」，不预先假设立场，否则「判定要降但没降成」
 * 会把状态机永久卡在「已降级」而再也降不下来（详见该方法与 markReduced 的说明）。
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

  /**
   * 记录一次延迟采样，返回本次采样是否应该尝试降视距。
   *
   * <p><b>这里不置「已降级」标记</b>：该标记代表「视距确实已经被我们下调且原始值已记录」，
   * 由调用方在 {@code reduce()} 成功后调用 {@link #markReduced()} 回报。若在判定阶段就置位，
   * 「判定要降但没降成」（被服务端视距/配置下限钳制、写视距抛异常、停用竞态回滚）会让状态机
   * 一直认为已降级：此后 {@link #shouldReduce} 恒返回 false，而还原路径又因没有原始值记录返回
   * false、无从清标记——该玩家整段会话内再也降不下来（功能静默失效），且每次采样都白跑一次还原。
   * 不预置后，未生效的降级会在下一次采样自然重试。
   */
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
    return true;
  }

  /**
   * 由调用方在降视距<b>确实生效</b>（已写入新视距且原始值记录已保留）后回报。
   *
   * <p>与「判定」分离是刻意的：见 {@link #shouldReduce} 的说明——把「想降」与「降成了」混为一谈
   * 会让一次失败尝试把状态机永久钉在已降级。
   */
  public void markReduced() {
    reduced = true;
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