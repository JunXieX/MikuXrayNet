package net.mikumc.mikuxraynet.antixray;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.mikumc.mikuxraynet.util.PlayerWorldIndex;

/**
 * 单个区块封包的「已写出」观察记录：<b>一次性</b>交付确认的收敛点。
 *
 * <p><b>存在的理由</b>：区块包在 ProtocolLib 异步管线里被扣住，只有「写出动作之后」这个时点才能判定该列
 * 在途的方块更新可以补发。这个判定不能靠「固定等若干 tick」、「发送前 MONITOR」或
 * {@code signalPacketTransmission} 本身——前两者证明不了包真的进了管线，后者只表示我们允许它走。真正的
 * 触发点由 {@link ChunkDeliverySignal} 在 ProtocolLib 执行完写出动作后调用，见本类 {@link #confirm()}。
 *
 * <p><b>为什么单独成类（不塞进 scheduled 包实现里）</b>：ProtocolLib 的 {@code ScheduledPacket} 需要
 * {@code PacketContainer} 才能构造，而后者在离线单测里无法创建（结构解析要 NMS）。把「一次确认」的全部
 * 语义放在这个纯 Java 类里，就可以在无服务端、无 ProtocolLib 的前提下直接把「只确认一次」「不复发」「异常
 * 不外抛」测干净；scheduled 侧只剩一层几行的适配（{@link ChunkDeliverySignal}）。
 *
 * <p><b>线程</b>：由封装层在「写出动作之后」的线程上调用（可能是主线程 / 区域线程 / ProtocolLib 发送线程），
 * 因此 {@link #confirm()} 必须非阻塞、无世界读取、无发送；重活（取样坐标、发包）由回调方调度到拥有区块的
 * 线程。{@code AtomicBoolean} 只用于「至多确认一次」。
 */
final class ChunkWriteObserver {

  /** 该封包所属世界的不可变快照（同步观察阶段固定；去重与回调用的是同一份）。 */
  private final PlayerWorldIndex.WorldContext context;
  /** 确认动作（生产实现转调 {@code onChunkWritten}）；同一个观察记录至多执行一次。 */
  private final Runnable onDelivered;
  /** 确认动作抛出时的报错出口（生产实现走分类限流日志）；{@code null} 表示静默吞掉。 */
  private final Consumer<Throwable> onError;
  private final AtomicBoolean confirmed = new AtomicBoolean();

  ChunkWriteObserver(PlayerWorldIndex.WorldContext context, Runnable onDelivered,
      Consumer<Throwable> onError) {
    this.context = context;
    this.onDelivered = onDelivered;
    this.onError = onError;
  }

  PlayerWorldIndex.WorldContext context() {
    return context;
  }

  /** 是否已经确认过（只用于观察/单测；判定本身仍是 CAS，不依赖本方法的返回值）。 */
  boolean confirmed() {
    return confirmed.get();
  }

  /**
   * 交付确认：<b>至多生效一次</b>，且<b>绝不外抛</b>。
   *
   * <p><b>为什么异常必须吞掉</b>：ProtocolLib 的 {@code NetworkProcessor#sendScheduledPackets} 用一个
   * 无 try/catch 的循环执行 {@code deque.poll()} 取到的每个 {@code ScheduledPacket#schedule(stream)}——
   * 本方法抛出的任何异常都会打断同一个 {@code NetworkMarker} 上<b>其它插件</b>排队的 scheduled 包，
   * 也会让 ProtocolLib 的 post 流程半途而废。确认语义本身是「尽力而为」的观测，不能反过来影响发链路。
   *
   * <p>重复调用是正常的（同一 marker 在极端路径上可能被派发多次），第二次起直接返回 {@code false}：
   * 既不重复登记，也绝不重发区块包。
   *
   * @return 本次是否真的执行了确认回调
   */
  boolean confirm() {
    if (!confirmed.compareAndSet(false, true)) {
      return false;
    }
    try {
      onDelivered.run();
    } catch (Throwable throwable) {
      if (onError != null) {
        try {
          onError.accept(throwable);
        } catch (Throwable ignored) {
          // 报错出口自身失败时只能放弃：这里已经在「必须不外抛」的边界上。
        }
      }
    }
    return true;
  }
}
