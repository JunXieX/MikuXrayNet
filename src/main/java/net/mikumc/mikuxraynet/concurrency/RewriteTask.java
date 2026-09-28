package net.mikumc.mikuxraynet.concurrency;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;

/**
 * 单个区块封包的改写任务：只承载「基本类型 / 不可变标识 + 世界名 + 维度」与放行状态机，
 * 不持有任何 Bukkit / 封包对象。
 *
 * <p>放行（{@code signalOnce()}）必须恰好一次：重复放行会让客户端卡在加载界面，漏放行则永久卡包。
 * 因此这里用两道闸：
 * <ul>
 *   <li>{@link #gate}——仲裁「写入权」与「超时放行」的竞争：看门狗只可能在写入开始前放行，
 *       写入开始后一律由工作线程自己放行，避免「包已发出却仍在改写」的数据竞争；</li>
 *   <li>{@link #signaled}——最终兜底，保证真正调用放行动作的次数为 1。</li>
 * </ul>
 */
public final class RewriteTask {

  private static final int GATE_OPEN = 0;
  private static final int GATE_WRITING = 1;
  private static final int GATE_DONE = 2;

  private final int chunkX;
  private final int chunkZ;
  private final String worldName;
  /** 该世界所属维度（网络线程读 {@code World#getEnvironment()} 后传入；工作线程不碰 Bukkit）。 */
  private final AntiXrayConfig.Dimension dimension;
  private final int minHeight;
  private final int sectionCount;
  private final long deadlineNanos;
  private final Runnable delivery;

  private final AtomicInteger gate = new AtomicInteger(GATE_OPEN);
  private final AtomicBoolean signaled = new AtomicBoolean();

  /**
   * 兼容构造：维度按主世界处理（供旧调用方与既有测试使用）。
   *
   * <p>测试专用豁免：生产路径一律调 8 参完整构造（显式传维度），本构造当前仅单测在用，
   * 保留以免破坏测试。
   *
   * @param minHeight    该世界最低建筑高度，用于与封包中绝对 Y 坐标的方块实体对齐
   * @param sectionCount 该世界的 section 数量（高度 / 16）
   * @param timeoutMillis 处理超时（毫秒）
   * @param delivery     放行动作（通常是 ProtocolLib 的 {@code signalPacketTransmission}）
   */
  public RewriteTask(int chunkX, int chunkZ, String worldName, int minHeight,
      int sectionCount, long timeoutMillis, Runnable delivery) {
    this(chunkX, chunkZ, worldName, AntiXrayConfig.Dimension.NORMAL, minHeight, sectionCount,
        timeoutMillis, delivery);
  }

  /**
   * @param dimension    该世界所属维度（决定用哪一段 {@code dimensions.<维度>} 配置改写）
   * @param minHeight    该世界最低建筑高度，用于与封包中绝对 Y 坐标的方块实体对齐
   * @param sectionCount 该世界的 section 数量（高度 / 16）
   * @param timeoutMillis 处理超时（毫秒）
   * @param delivery     放行动作（通常是 ProtocolLib 的 {@code signalPacketTransmission}）
   */
  public RewriteTask(int chunkX, int chunkZ, String worldName, AntiXrayConfig.Dimension dimension,
      int minHeight, int sectionCount, long timeoutMillis, Runnable delivery) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.worldName = worldName;
    this.dimension = dimension == null ? AntiXrayConfig.Dimension.NORMAL : dimension;
    this.minHeight = minHeight;
    this.sectionCount = sectionCount;
    this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
    this.delivery = delivery;
  }

  public int chunkX() {
    return chunkX;
  }

  public int chunkZ() {
    return chunkZ;
  }

  public String worldName() {
    return worldName;
  }

  /** 该世界所属维度（配置分段依据）。 */
  public AntiXrayConfig.Dimension dimension() {
    return dimension;
  }

  public int minHeight() {
    return minHeight;
  }

  public int sectionCount() {
    return sectionCount;
  }

  /** 是否已超过处理时限。 */
  public boolean expired() {
    return System.nanoTime() - deadlineNanos > 0L;
  }

  /**
   * 尝试取得写入权。
   *
   * @return {@code false} 表示该任务已被看门狗超时放行，调用方必须放弃改写（原包已放行）
   */
  public boolean tryBeginWrite() {
    return gate.compareAndSet(GATE_OPEN, GATE_WRITING);
  }

  /** 放行封包，整条链路恰好执行一次。 */
  public void signalOnce() {
    if (signaled.compareAndSet(false, true)) {
      delivery.run();
    }
  }

  /**
   * 「写入开始前」的原子兜底放行：仅在写入尚未开始时抢到并放行；写入中/已完成的任务由工作线程负责放行。
   *
   * <p>两个调用方共用同一道闸（都是「写入还没开始就必须放行，已经开始就不要抢」）：
   * {@code MikuWorkPool} 的看门狗（超时）与 {@code close()} 的排空兜底。用 CAS 而不是
   * 「先读状态再放行」，是为了让兜底放行与 {@link #tryBeginWrite()} 互斥——抢到者放行原包，
   * 没抢到者交给正在写入的工作线程，绝不会出现「包已发出却仍在改写」。
   *
   * @return {@code true} 表示本次确实由兜底放行了原包（供超时统计使用）
   */
  public boolean releaseOnTimeout() {
    if (gate.compareAndSet(GATE_OPEN, GATE_DONE)) {
      signalOnce();
      return true;
    }
    return false;
  }
}
