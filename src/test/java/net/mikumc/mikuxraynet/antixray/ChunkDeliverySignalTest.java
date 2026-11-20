package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 交付信号的接线守门：本文件钉死「只借时机、不发封包」与「不钉住被观察的区块负载」两条硬约束。
 *
 * <p>为什么用源码结构断言而不是行为断言：{@code ScheduledPacket} 的构造需要 {@code PacketContainer}，
 * 而后者在离线环境无法创建（结构解析要 NMS，见 {@code RegressionWiringGuardTest} 的类注释）。真正可离线
 * 驱动的行为（一次性确认、异常不外抛）在 {@link ChunkWriteObserverTest} 里覆盖，这里只守剩下的两条：
 * <ol>
 *   <li>{@code schedule(PacketStream)} 只调 {@code observer.confirm()}——<b>不</b>调父实现（父实现在
 *       {@code Sender.SERVER} 时就是 {@code stream.sendServerPacket(target, packet, filtered)}，会真的把
 *       占位封包发出去）、<b>不</b>读写 stream；</li>
 *   <li>本类不持有 {@code PacketContainer} 字段——被观察的区块包对象是 ProtocolLib {@code savedMarkers}
 *       （{@code WeakHashMap}）的弱键，若 scheduled 队列里的 value 反向强引用它，写出未到达时整列都收不回来。</li>
 * </ol>
 */
class ChunkDeliverySignalTest {

  private static final Path SOURCE =
      Path.of("src/main/java/net/mikumc/mikuxraynet/antixray/ChunkDeliverySignal.java");

  @Test
  void signalIsAScheduledPacketWithOverridableSchedule() throws IOException {
    String code = read();
    assertTrue(code.contains("extends ScheduledPacket"),
        "必须复用 ProtocolLib 的 scheduled 包派发时机（post listener 在异步放行路径拿不到 event）");
    assertTrue(code.contains("public void schedule(PacketStream stream)"),
        "必须覆写 schedule(PacketStream)：确认点就在这个回调里");
  }

  @Test
  void scheduleOnlyConfirmsAndNeverSends() throws IOException {
    String body = bodyOf(stripComments(read()), "public void schedule(PacketStream stream)");
    assertNotNull(body, "未找到 schedule(PacketStream) 的方法体");
    assertTrue(body.contains("observer.confirm()"), "scheduled 回调只做一次性确认");
    assertFalse(body.contains("super"), "绝不能调用父实现：父实现会真的把封包发出去");
    assertFalse(body.contains("stream"),
        "不得触碰 PacketStream：本信号只借时机，绝不发送、绝不增加线上字节");
    assertFalse(body.contains("sendServerPacket"), "不得发包（协议层只允许发送真实业务包）");
    assertFalse(body.contains("receiveClientPacket"), "不得注入客户端包（本信号与上行无关）");
  }

  @Test
  void signalNeverPinsTheObservedChunkPayload() throws IOException {
    String code = stripComments(read());
    assertFalse(code.matches("(?s).*\\bprivate\\b[^;\\n]*PacketContainer[^;\\n]*;.*"),
        "不得持有 PacketContainer 字段：占位容器全进程共用一个，绝不能钉住被观察的区块包");
    assertTrue(code.contains("super(placeholder, target, false)"),
        "构造只传占位容器 + 接收者（filtered=false 与占位无关，本类永不派发）");
  }

  private static String read() throws IOException {
    return Files.readString(SOURCE);
  }

  /** 剥掉注释，避免方法体里的 javadoc/行内注释提到 super/stream 造成误判。 */
  private static String stripComments(String text) {
    return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
  }

  /** 取以给定声明开头的第一个方法体（含花括号配对）。 */
  private static String bodyOf(String code, String declaration) {
    int at = code.indexOf(declaration);
    if (at < 0) {
      return null;
    }
    int brace = code.indexOf('{', at);
    if (brace < 0) {
      return null;
    }
    int depth = 0;
    for (int i = brace; i < code.length(); i++) {
      char current = code.charAt(i);
      if (current == '{') {
        depth++;
      } else if (current == '}') {
        depth--;
        if (depth == 0) {
          return code.substring(brace, i + 1);
        }
      }
    }
    return null;
  }
}
