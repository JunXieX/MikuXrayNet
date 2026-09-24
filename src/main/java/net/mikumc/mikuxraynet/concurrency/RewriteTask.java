package net.mikumc.mikuxraynet.concurrency;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个区块封包的改写任务：只承载「基本类型 / 不可变标识 + 世界名」与状态机，
 * 不持有任何 Bukkit / 封包对象。
 *
 * <p>状态机：{@code CREATED → DECODED → ENCODED → RELEASED}。
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

  /** 任务阶段（仅用于诊断与自证，不参与并发仲裁）。 */
  public enum Stage {
    CREATED, DECODED, ENCODED, RELEASED
  }

  private static final int GATE_OPEN = 0;
  private static final int GATE_WRITING = 1;
  private static final int GATE_DONE = 2;

  private final UUID playerId;
  private final int chunkX;
  private final int chunkZ;
  private final String worldName;
  private final int minHeight;
  private final int sectionCount;
  private final long deadlineNanos;
  private final Runnable delivery;

  private final AtomicInteger gate = new AtomicInteger(GATE_OPEN);
  private final AtomicBoolean signaled = new AtomicBoolean();
  private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.CREATED);

  /**
   * @param playerId     该封包的接收者（UUID 不持有玩家强引用）
   * @param minHeight    该世界最低建筑高度，用于与封包中绝对 Y 坐标的方块实体对齐
   * @param sectionCount 该世界的 section 数量（高度 / 16）
   * @param timeoutMillis 处理超时（毫秒）
   * @param delivery     放行动作（通常是 ProtocolLib 的 {@code signalPacketTransmission}）
   */
  public RewriteTask(UUID playerId, int chunkX, int chunkZ, String worldName, int minHeight,
      int sectionCount, long timeoutMillis, Runnable delivery) {
    this.playerId = playerId;
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.worldName = worldName;
    this.minHeight = minHeight;
    this.sectionCount = sectionCount;
    this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
    this.delivery = delivery;
  }

  public UUID playerId() {
    return playerId;
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

  public int minHeight() {
    return minHeight;
  }

  public int sectionCount() {
    return sectionCount;
  }

  public Stage stage() {
    return stage.get();
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

  public void markDecoded() {
    stage.set(Stage.DECODED);
  }

  public void markEncoded() {
    stage.set(Stage.ENCODED);
  }

  /** 放行封包，整条链路恰好执行一次。 */
  public void signalOnce() {
    if (signaled.compareAndSet(false, true)) {
      stage.set(Stage.RELEASED);
      delivery.run();
    }
  }

  /** 看门狗超时兜底：仅在写入尚未开始时放行；写入中/已完成的任务由工作线程负责放行。 */
  public void releaseOnTimeout() {
    if (gate.compareAndSet(GATE_OPEN, GATE_DONE)) {
      signalOnce();
    }
  }
}