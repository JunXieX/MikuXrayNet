package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.mikumc.mikuxraynet.util.PlayerWorldIndex;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * 交付确认的一次性语义（离线，无需服务端与 ProtocolLib）。
 *
 * <p>被测对象 {@link ChunkWriteObserver} 是「区块包已写出」的唯一收敛点：ProtocolLib 在异步放行路径上只派发
 * scheduled 包（{@code NetworkProcessor#invokePostEvent} 的 {@code event != null} 前提让 post listener 拿不到
 * event），所以这条链路必须自己保证三件事：
 * <ol>
 *   <li><b>至多一次</b>：同一 marker 被重复派发时不能重复补发、更不能重发区块包；</li>
 *   <li><b>绝不外抛</b>：{@code sendScheduledPackets} 用无 try/catch 的循环执行每个 scheduled 包，一次异常会
 *       打断同一 marker 上其它插件的 scheduled 排包；</li>
 *   <li><b>不发包</b>：确认只做「可以补发」的判定，不产生任何线上字节（scheduled 侧的适配见
 *       {@link ChunkDeliverySignalTest}）。</li>
 * </ol>
 */
class ChunkWriteObserverTest {

  private static PlayerWorldIndex.WorldContext context(String worldName) {
    return new PlayerWorldIndex.WorldContext(worldName, null, World.Environment.NORMAL, -64, 24, 1L);
  }

  @Test
  void confirmsDeliveryExactlyOnce() {
    AtomicInteger delivered = new AtomicInteger();
    ChunkWriteObserver observer = new ChunkWriteObserver(context("world"), delivered::incrementAndGet, null);

    assertTrue(observer.confirm(), "第一次确认必须执行回调");
    assertFalse(observer.confirm(), "重复派发不得再次执行回调（否则同一列会被补发两遍）");
    assertEquals(1, delivered.get());
    assertTrue(observer.confirmed());
  }

  @Test
  void deliveryFailureNeverEscapes() {
    List<Throwable> reported = new ArrayList<>();
    ChunkWriteObserver observer = new ChunkWriteObserver(context("world"),
        () -> {
          throw new IllegalStateException("模拟补发取样/发包失败");
        },
        reported::add);

    // 不能抛：ProtocolLib 的 scheduled 派发循环没有 try/catch，抛出会打断同一 marker 上其它插件的回调。
    assertTrue(observer.confirm());
    assertEquals(1, reported.size(), "异常必须交给报错出口，不能静默丢失");
    assertFalse(observer.confirm(), "失败的确认也算用掉了这一次机会，不得重试发送");
    assertEquals(1, reported.size());
  }

  @Test
  void errorSinkFailureIsSwallowedToo() {
    ChunkWriteObserver observer = new ChunkWriteObserver(context("world"),
        () -> {
          throw new IllegalStateException("回调失败");
        },
        throwable -> {
          throw new IllegalStateException("报错出口自身失败");
        });

    assertTrue(observer.confirm());
  }

  @Test
  void contextIsTheSnapshotFixedAtObservationTime() {
    PlayerWorldIndex.WorldContext context = context("nether");
    ChunkWriteObserver observer = new ChunkWriteObserver(context, () -> { }, null);
    assertSame(context, observer.context());
  }

  @Test
  void failureStillCountsAsConsumed() {
    ChunkWriteObserver observer = new ChunkWriteObserver(context("world"),
        () -> {
          throw new IllegalStateException("回调失败");
        }, null);

    assertTrue(observer.confirm());
    assertTrue(observer.confirmed(), "已尝试过的确认不得再看成未确认（否则淘汰统计会误记）");
    assertFalse(observer.confirm());
  }

  @Test
  void nullErrorSinkSilencesFailure() {
    ChunkWriteObserver observer = new ChunkWriteObserver(context("world"),
        () -> {
          throw new IllegalStateException("回调失败");
        }, null);
    assertTrue(observer.confirm());
  }
}
