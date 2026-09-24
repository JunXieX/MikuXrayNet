package net.mikumc.mikuxraynet.bandwidth;

/**
 * 单玩家的活动/AFK 状态机（纯逻辑，时钟由调用方注入，便于单元测试）。
 *
 * <p>状态转移：
 * <ul>
 *   <li>{@link #touch} 记录一次活动（调用方按「方块坐标变化」过滤高频事件）→ 立即退出 AFK；</li>
 *   <li>{@link #enterIfTimedOut} 在超过时长阈值时进入 AFK，且同一段 AFK 只上报一次。</li>
 * </ul>
 *
 * <p>同时缓存玩家最后所在的方块坐标与精确坐标：AFK 期间玩家不再移动，因此该缓存可在网络线程
 * 上安全读取用于距离判定，避免在封包线程访问实体位置。
 */
public final class AfkState {

  private volatile long lastActiveMillis;
  private volatile boolean afk;

  private volatile int blockX;
  private volatile int blockY;
  private volatile int blockZ;
  private volatile double x;
  private volatile double y;
  private volatile double z;

  public AfkState(long nowMillis, int blockX, int blockY, int blockZ, double x, double y, double z) {
    this.lastActiveMillis = nowMillis;
    this.blockX = blockX;
    this.blockY = blockY;
    this.blockZ = blockZ;
    this.x = x;
    this.y = y;
    this.z = z;
  }

  public boolean afk() {
    return afk;
  }

  public double x() {
    return x;
  }

  public double y() {
    return y;
  }

  public double z() {
    return z;
  }

  /**
   * 记录一次活动。
   *
   * @param movedToNewBlock 本次活动是否跨越了方块边界（调用方据此决定是否刷新缓存坐标）
   */
  public void touch(long nowMillis, boolean movedToNewBlock, int blockX, int blockY, int blockZ,
      double x, double y, double z) {
    this.lastActiveMillis = nowMillis;
    this.afk = false;
    if (movedToNewBlock) {
      this.blockX = blockX;
      this.blockY = blockY;
      this.blockZ = blockZ;
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  /** 该方块坐标是否仍是缓存中的位置（用于过滤高频移动事件）。 */
  public boolean isSameBlock(int blockX, int blockY, int blockZ) {
    return this.blockX == blockX && this.blockY == blockY && this.blockZ == blockZ;
  }

  /** 当前是否已超时进入 AFK（只读判定，不改变状态）。 */
  public boolean isTimedOut(long nowMillis, long timeoutMillis) {
    return nowMillis - lastActiveMillis >= timeoutMillis;
  }

  /** 超时则进入 AFK；返回 {@code true} 表示本次刚好「进入 AFK」（用于计数与日志）。 */
  public boolean enterIfTimedOut(long nowMillis, long timeoutMillis) {
    if (afk || !isTimedOut(nowMillis, timeoutMillis)) {
      return false;
    }
    afk = true;
    return true;
  }
}