package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真机 bug 回归：<b>每 30 秒一条 {@code ClosedChannelException}</b>。
 *
 * <p><b>真机栈</b>：{@code RegionFile.writeFully} ← {@code flushBucket} ← {@code flushDirty}
 * ← {@code DiskCacheStore.flushAll} ← {@code maintenance}（30 秒一轮的维护任务）。
 *
 * <p><b>根因</b>（两条互为因果）：
 * <ol>
 *   <li>{@code DiskCacheStore.submit()} 在读取超时（读预算仅 50ms）时用 {@code cancel(true)} 取消任务，
 *       那会<b>打断共享的磁盘线程</b>；{@code FileChannel} 一被中断就<b>永久关闭</b>
 *       （{@code ClosedByInterruptException}）。真机上磁盘线程被大量超时读反复打断，
 *       于是某个区域文件句柄的通道作废；</li>
 *   <li>通道作废后句柄仍留在维护表里、脏 bucket 的脏标记仍在，于是<b>每一轮维护</b>都在同一句柄上
 *       再抛一次 {@code ClosedChannelException}（首轮是 {@code ClosedByInterruptException}，
 *       之后就是这条持续刷屏的栈）。</li>
 * </ol>
 *
 * <p><b>修法</b>：①超时只 {@code cancel(false)}，绝不打断磁盘线程；②落盘失败时若发现通道已失效，
 * 直接丢弃并关闭该句柄（下次访问自动重建），只提示一次而不是每轮重试刷屏；
 * ③{@link RegionFile#close()} 在通道已失效时不再尝试写盘。
 *
 * <p>本测试用「关闭通道」的桩复现第 2 步的故障态，并断言「一次失效」不会退化成「持续刷屏」。
 */
class DiskCacheChannelFailureTest {

  private static final String WORLD = "world";
  private static final Logger LOGGER = Logger.getLogger("MikuXrayNetTest");

  /** 维护周期设得很大，避免后台任务干扰断言；需要落盘的用例显式调用 flush()。 */
  private static AntiXrayConfig.DiskCache config(int idleCloseSeconds) {
    return new AntiXrayConfig.DiskCache(true, 1024, 16, 600, 2, idleCloseSeconds, 3600, 4, 256,
        false, AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, 10, "");
  }

  /** 测试用构造：读取预算放宽到 10 秒，避免 CI 磁盘抖动被误判为超时。 */
  private static DiskCacheStore store(Path dir, AntiXrayConfig.DiskCache config) {
    return new DiskCacheStore(dir, config, LOGGER, 10_000L);
  }

  private static byte[] payload(int length, long seed) {
    byte[] data = new byte[length];
    new Random(seed).nextBytes(data);
    return data;
  }

  /** 取出该 store 里第一个已打开句柄的真实区域文件对象（仅本回归测试用，避免为测试在生产代码开洞）。 */
  private static RegionFile firstOpenRegionFile(DiskCacheStore store) throws Exception {
    Field openField = DiskCacheStore.class.getDeclaredField("open");
    openField.setAccessible(true);
    Map<?, ?> open = (Map<?, ?>) openField.get(store);
    assertFalse(open.isEmpty(), "应当至少有一个已打开的区域文件句柄");
    Object handle = open.values().iterator().next();
    Field fileField = handle.getClass().getDeclaredField("file");
    fileField.setAccessible(true);
    return (RegionFile) fileField.get(handle);
  }

  /** 通道已失效时，关闭句柄不得再抛异常（否则每次关闭都会再刷一条 WARN）。 */
  @Test
  void regionFileCloseSkipsFlushWhenChannelAlreadyDead(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.0.0.b_linear");
    RegionFile region = RegionFile.open(file, 2);
    region.put(BufferedLinearV3Format.chunkIndex(0, 0), new BufferedLinearV3Format.Entry(
        0L, System.currentTimeMillis(), 1, payload(256, 1)));
    region.flushDirty();
    assertTrue(region.channelOpen());

    region.killChannelForTest();
    assertFalse(region.channelOpen());

    assertDoesNotThrow(region::close, "通道已失效时关闭不得再抛 ClosedChannelException");
    region.close(); // 可重复调用
  }

  /**
   * 失效句柄必须被丢弃并只提示一次：第二轮维护不得再产生任何异常
   * （这正是真机「每 30 秒一条」的直接复现）。
   */
  @Test
  void deadChannelHandleIsDroppedInsteadOfFailingEveryMaintenance(@TempDir Path dir) throws Exception {
    try (DiskCacheStore store = store(dir, config(600))) {
      store.put(WORLD, 0, 0, 1, payload(512, 1));
      store.flush();
      assertEquals(1, store.openRegionFiles());

      // 复现故障态：通道被中断/外部关闭，但句柄仍留在维护表且后续写入会把 bucket 标脏
      firstOpenRegionFile(store).killChannelForTest();

      // chunkX=0,chunkZ=2 → chunkIndex 64 → 另一个 bucket（不影响已加载的 bucket 0）
      store.put(WORLD, 0, 2, 1, payload(512, 2));
      store.flush();
      assertEquals(0, store.openRegionFiles(),
          "通道失效的句柄必须被丢弃（旧实现会留在表里，每轮维护再抛一次）");
      long errorsAfterDrop = store.stats().errors.sum();

      store.flush();
      assertEquals(errorsAfterDrop, store.stats().errors.sum(),
          "句柄丢弃后，后续维护不得再反复失败（真机表现为每 30 秒一条 ClosedChannelException）");

      // fail-open：句柄被丢弃后自动重建，缓存继续可用
      byte[] data = payload(640, 3);
      store.put(WORLD, 0, 0, 1, data);
      assertArrayEquals(data, store.get(WORLD, 0, 0, 1), "重建句柄后缓存必须继续可用");
    }
  }

  /**
   * 读取超时<b>不得打断磁盘线程</b>：一旦打断，FileChannel 会永久关闭，
   * 此后每次落盘都抛 {@code ClosedChannelException}（真机根因的第一步）。
   *
   * <p><b>确定性编排（不依赖 CI 机器快慢）</b>：先用 latch 占住唯一的磁盘线程，再以 1ms 读预算发起读取——
   * 任务必然排在预算之外 ⇒ 必然超时，被守护的 {@code TimeoutException → cancel(false)} 路径一定被执行。
   * 旧写法（先灌 32 个 put 再连读）在快机器上可能一次都不超时，等于什么都没测。
   *
   * <p>三条断言：① 超时确实发生（errors 按次递增）；② 磁盘线程未被中断（通道仍打开、句柄未被丢弃、
   * 随后的落盘不产生任何新错误）；③ 释放线程后落盘正常收尾。
   */
  @Test
  void readTimeoutDoesNotInterruptTheDiskThread(@TempDir Path dir) throws Exception {
    try (DiskCacheStore store = new DiskCacheStore(dir, config(600), LOGGER, 1L, 10_000L)) {
      byte[] data = payload(64 * 1024, 1);
      store.put(WORLD, 0, 0, 1, data);
      // flush 预算 10s：等到 put 真正落盘、句柄建立（否则下面取不到句柄）
      store.flush();
      RegionFile region = firstOpenRegionFile(store);
      long errorsBefore = store.stats().errors.sum();

      // 占住唯一的磁盘线程（直接投给执行器，不经过 pendingOps 记账），使后续读必然超时
      CountDownLatch blocked = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      diskThreadExecutor(store).execute(() -> {
        blocked.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(blocked.await(5, TimeUnit.SECONDS), "占位任务必须已在磁盘线程上开始执行");

      int attempts = 8;
      try {
        for (int attempt = 0; attempt < attempts; attempt++) {
          assertNull(store.get(WORLD, 0, 0, 1), "排队超过 1ms 预算的读取必须按未命中降级（fail-open）");
        }
      } finally {
        release.countDown();
      }

      assertEquals(errorsBefore + attempts, store.stats().errors.sum(),
          "每次读超时都必须被计数；计数没涨说明本用例根本没走到被守护的超时路径（旧写法在快机器上就是如此）");
      assertEquals(1, store.openRegionFiles(), "超时不得导致句柄被丢弃（那是通道真的坏掉的表现）");
      store.flush();
      assertEquals(errorsBefore + attempts, store.stats().errors.sum(),
          "磁盘线程恢复后的落盘不得再产生任何错误：说明通道没有被超时打断");
      assertTrue(region.channelOpen(),
          "读超时不得打断磁盘线程：FileChannel 被中断即永久关闭，之后每次落盘都会失败");
    }
  }

  /** 反射取磁盘线程的执行器：用于占住它，让读超时确定性发生（不依赖 CI 磁盘速度）。 */
  private static ScheduledExecutorService diskThreadExecutor(DiskCacheStore store) throws Exception {
    Field field = DiskCacheStore.class.getDeclaredField("executor");
    field.setAccessible(true);
    return (ScheduledExecutorService) field.get(store);
  }
}