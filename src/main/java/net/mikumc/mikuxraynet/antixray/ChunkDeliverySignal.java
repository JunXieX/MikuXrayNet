package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketStream;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.ScheduledPacket;
import org.bukkit.entity.Player;

/**
 * 挂在 {@code NetworkMarker} 上的「区块包已写出」信号：借 ProtocolLib 的 scheduled 包派发时机做一次性确认。
 *
 * <p><b>为什么不发任何封包</b>：本类覆写 {@link ScheduledPacket#schedule(PacketStream)} 且<b>绝不</b>调用
 * {@code super.schedule(stream)}、也绝不读写 {@code stream}——它只取「ProtocolLib 执行完写出动作之后」这个
 * 时机，转调 {@link ChunkWriteObserver#confirm()}。因此本类不增加任何线上字节、不改变发送顺序、不重发区块包。
 *
 * <p><b>契约边界（不要拿它当更强的保证）</b>：确认发生在 ProtocolLib {@code NettyChannelInjector#proxyAction}
 * 里 {@code action.run()} / {@code action.call()} 之后，也就是「写出动作已经递交到本连接的 Netty 管线（同
 * channel 内 FIFO）」之后。它<b>不是</b> {@code ChannelPromise} 成功、<b>更不是</b>客户端 ACK；连接已断或
 * 写失败时同样可能走到这里。所以这里只做「该补发的现在可以补发」判定，失败/断连由既有的会话检查、在线检查与
 * 有界过期收账，不产生任何补偿性重发。
 *
 * <p><b>为什么用占位容器</b>：{@code ScheduledPacket} 必须持有一个 {@code PacketContainer}，但若这里塞入
 * 被观察的区块包容器，marker（{@code savedMarkers} 的 value）就会经 scheduled 队列强引用到该包对象——而它
 * 正是 {@code WeakHashMap} 的弱键，形成 value → key 的反向强引用，写出未到达时整列都收不回来。故所有信号
 * 共用一个<b>与区块无关</b>的进程级占位容器（见 {@code ProtocolLibAsyncListener#registerDeliveryObserver}）：
 * 它永不被发送，也不被读取。本类自身不持有任何 {@code PacketContainer} 字段，scheduled 队列不会钉住任何
 * 区块负载。
 */
final class ChunkDeliverySignal extends ScheduledPacket {

  private final ChunkWriteObserver observer;

  /**
   * @param observer    一次性确认记录（携带随包固定的世界快照与确认动作）
   * @param placeholder 与区块无关的占位容器（永不发送；见类注释）
   * @param target      接收者（只用于满足 {@link ScheduledPacket} 的构造约束，本类不会向它发包）
   */
  ChunkDeliverySignal(ChunkWriteObserver observer, PacketContainer placeholder, Player target) {
    super(placeholder, target, false);
    this.observer = observer;
  }

  ChunkWriteObserver observer() {
    return observer;
  }

  /**
   * 只确认、不发包：覆写后<b>永不</b>回调父实现、<b>永不</b>触碰 {@code stream}。
   *
   * <p>重复派发是安全的：{@link ChunkWriteObserver#confirm()} 用 CAS 保证同一观察记录至多生效一次。
   */
  @Override
  public void schedule(PacketStream stream) {
    observer.confirm();
  }
}
