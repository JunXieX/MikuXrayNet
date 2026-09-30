package net.mikumc.mikuxraynet.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link RewriteTask} 的放行状态机回归。
 *
 * <p>① 构造期必须对空放行动作快速失败——否则唯一的放行动作会在首个 {@code signalOnce()} 里抛 NPE，
 * 异常一旦被上层吞掉，该封包就永久无人放行（卡包）。
 *
 * <p>② 无论「正常写入结束」还是「写入前兜底放行」，闸门最终都停在终态；迟到的兜底
 * （{@code releaseOnTimeout()}）必须返回 {@code false}，不得把已完成的任务误报为「超时放行」。
 */
class RewriteTaskTest {

  private static RewriteTask task(AtomicInteger deliveries) {
    return new RewriteTask(0, 0, "world", -64, 24, 10_000L, deliveries::incrementAndGet);
  }

  @Test
  void constructorRejectsNullDelivery() {
    assertThrows(IllegalArgumentException.class,
        () -> new RewriteTask(0, 0, "world", -64, 24, 10_000L, null),
        "放行动作缺失必须在构造期快速失败，绝不构造一个「看似正常却永不放行」的任务");
  }

  @Test
  void signalOnceIsIdempotent() {
    AtomicInteger deliveries = new AtomicInteger();
    RewriteTask task = task(deliveries);

    task.signalOnce();
    task.signalOnce();

    assertEquals(1, deliveries.get(), "放行必须恰好一次");
  }

  /** 正常写入路径：取得写入权 → 放行 → 闸门到终态，迟到的兜底不得再报「已超时放行」。 */
  @Test
  void writePathEndsAtTerminalStateSoLateTimeoutCannotDoubleRelease() {
    AtomicInteger deliveries = new AtomicInteger();
    RewriteTask task = task(deliveries);

    assertTrue(task.tryBeginWrite(), "未被兜底抢占时应能取得写入权");
    task.signalOnce();
    assertEquals(1, deliveries.get());

    assertFalse(task.tryBeginWrite(), "任务已放行，不得再取得写入权");
    assertFalse(task.releaseOnTimeout(), "已完成的任务不得被兜底误报为超时放行");
    assertEquals(1, deliveries.get(), "兜底不得重复放行");
  }

  /** 写入前就直接放行的异常兜底路径：闸门同样终态化，迟到兜底返回 false。 */
  @Test
  void directSignalOnceAlsoEndsAtTerminalState() {
    AtomicInteger deliveries = new AtomicInteger();
    RewriteTask task = task(deliveries);

    task.signalOnce();

    assertFalse(task.releaseOnTimeout(),
        "写入前就直接放行后，闸门应已终态化：迟到兜底不得再报超时");
    assertEquals(1, deliveries.get());
  }

  /** 写入开始前的超时兜底：恰好放行一次，并从此关死写入权。 */
  @Test
  void releaseOnTimeoutReleasesExactlyOnceBeforeWrite() {
    AtomicInteger deliveries = new AtomicInteger();
    RewriteTask task = task(deliveries);

    assertTrue(task.releaseOnTimeout(), "写入尚未开始时兜底应放行原包并返回 true");
    assertEquals(1, deliveries.get());
    assertFalse(task.releaseOnTimeout(), "兜底只生效一次");
    assertFalse(task.tryBeginWrite(), "已被兜底放行的任务不得再取得写入权");
    assertEquals(1, deliveries.get());
  }
}