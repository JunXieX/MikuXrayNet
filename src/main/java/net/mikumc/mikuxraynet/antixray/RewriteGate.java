package net.mikumc.mikuxraynet.antixray;

/**
 * 区块改写入口的<b>纯值判定</b>：把「这个区块包是否进入改写管线」从 netty 回调里剥离出来。
 *
 * <p><b>为什么</b>：入口判定每秒跑数百次，却把「玩家/世界/直通/黑名单/维度/队列容量」六项短路散在
 * 一个 140 行的方法里，且顺序本身是语义（例如「维度关闭的世界不得先构造 accessor」「队列满必须计数」）。
 * 纯函数化之后可以离线枚举全部组合，新增一个短路项时不必再靠通读回调确认顺序。
 *
 * <p><b>顺序即语义</b>（与历史实现逐条一致，改动前先看 {@code ProtocolLibAsyncListener#handleChunk}）：
 * 玩家缺失 → 直通 → 世界不允许（含 null）→ 维度关闭 → 队列满。调用方必须在判「队列满」之前完成
 * 其余各项，否则会给「本来就不该改写」的玩家白算一次容量。
 */
public final class RewriteGate {

  /** 判定结果；只有 {@link #PROCEED} 才允许构造任务与闸门。 */
  public enum Decision {
    PROCEED,
    /** 封包里没有玩家（离线 / 连接已销毁）。 */
    SKIP_NO_PLAYER,
    /** 世界为 null（世界正在卸载）。 */
    SKIP_NO_WORLD,
    /** 玩家在直通名单里。 */
    SKIP_BYPASS,
    /** 世界被黑名单或总开关拒绝。 */
    SKIP_WORLD_NOT_ALLOWED,
    /** 维度未启用（默认末地关闭）。 */
    SKIP_DIMENSION,
    /** 改写队列已满：必须原包放行并计入 {@code chunksSkippedQueueFull}。 */
    SKIP_QUEUE_FULL
  }

  private RewriteGate() {
  }

  /**
   * 判定是否进入改写管线。
   *
   * @param playerPresent   封包是否有玩家
   * @param worldPresent    是否取到了世界实例
   * @param bypassed        玩家是否在直通名单（只读登录期快照）
   * @param worldAllowed    世界是否允许反矿透（总开关 + 世界黑名单）
   * @param dimensionPresent 是否成功解析出维度
   * @param dimensionEnabled 该维度是否启用
   * @param poolHasCapacity 改写队列是否还有容量
   */
  public static Decision decide(boolean playerPresent, boolean worldPresent, boolean bypassed,
      boolean worldAllowed, boolean dimensionPresent, boolean dimensionEnabled,
      boolean poolHasCapacity) {
    if (!playerPresent) {
      return Decision.SKIP_NO_PLAYER;
    }
    if (!worldPresent) {
      return Decision.SKIP_NO_WORLD;
    }
    if (bypassed) {
      return Decision.SKIP_BYPASS;
    }
    if (!worldAllowed) {
      return Decision.SKIP_WORLD_NOT_ALLOWED;
    }
    if (!dimensionPresent) {
      return Decision.SKIP_DIMENSION;
    }
    if (!dimensionEnabled) {
      return Decision.SKIP_DIMENSION;
    }
    if (!poolHasCapacity) {
      return Decision.SKIP_QUEUE_FULL;
    }
    return Decision.PROCEED;
  }
}
