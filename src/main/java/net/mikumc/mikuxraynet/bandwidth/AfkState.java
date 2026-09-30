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
 *
 * <p><b>坐标的撕裂读与「允许一步长偏差」的刻意选择（勿当 bug 修）</b>：6 个坐标字段各自是独立
 * volatile，网络线程在同一位置判定里逐个读取 {@link #x()} / {@link #y()} / {@link #z()} 时，
 * 理论上可能读到「X 来自本次更新、Z 来自上一次更新」的混合组合，偏差至多一个移动步长。
 * 这是<b>刻意接受</b>的取舍，原因：
 * <ol>
 *   <li>写入只在「跨方块移动」（{@link #touch} 传入 {@code movedToNewBlock=true}）时发生，而
 *       {@link #touch} 同时会把 {@link #afk} 置为 {@code false} —— 距离判定只在 {@code afk()==true}
 *       时才被使用，玩家正在移动（坐标在变）时几乎不可能命中该分支；</li>
 *   <li>即便命中，判定本身只用于决定「是否丢弃一颗远处粒子/破坏动画包」，一步长的位置误差既不会
 *       丢错（误差远小于默认 16 格距离阈值），也最多造成一颗粒子多留一帧——影响不可感知；</li>
 *   <li>改为「打包成不可变位置对象一次性发布」只能把 6 次 volatile 读降为 1 次，但消费侧
 *       （{@code AfkTracker}）仍是分三次调用 {@link #x()} / {@link #y()} / {@link #z()}，
 *       要真正消除读取侧的混合组合必须同时改调用点；在收益不可感知的前提下，不值得为此改动热路径
 *       调用方。</li>
 * </ol>
 * 因此本类<b>有意</b>保留「允许一步长偏差」的语义；若日后确需严格一致的位置快照，请连同调用点
 * （一次性取出整组坐标）一起改造，切勿只在本类里加锁或换原子对象——那既解决不了读取侧问题，
 * 又会给 netty 热路径引入额外开销。</p>
 */
public final class AfkState {

  private volatile long lastActiveMillis;
  private volatile boolean afk;

  /** 最后所在方块的 X（与 {@link #x} 等精确坐标一并由 {@link #touch} 在跨方块移动时刷新）。 */
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