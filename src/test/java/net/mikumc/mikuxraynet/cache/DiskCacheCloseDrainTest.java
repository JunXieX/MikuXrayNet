package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>关闭排空的端到端回归</b>（第 10 轮 P3-2 补测）：一次写入远超单批预算的脏记录后<b>直接</b>
 * close()，重开必须逐条可读且内容一致。
 *
 * <p><b>为什么必须补这一条</b>：DiskCacheStore#close() 的排空任务调用 flushAll()，
 * 而后者对每个句柄只写 RegionFile#FLUSH_BATCH_RECORDS 一批；写满后它用
 * execute(...) 把余量排到队尾续写，但此刻 closed 已置位、execute 会拒收
 * ——余量最终由 RegionFile#close() 里的全量 flushDirty() 兜底。
 * 这条「批 → 续排被拒 → close 全量兜底」的链路此前只有 RegionFile 级单测
 * （RegionFileBatchedFlushTest 直接调 flushDirty(int)），没有 DiskCacheStore 级验证；
 * 若未来把 close() 的兜底改坏，缓存会在「正常关服」时静默丢数据。
 *
 * <p>只写同一个区域文件（r.0.0）内的坐标，保证脏记录都落在同一个句柄上、必然超过单批预算。
 */
class DiskCacheCloseDrainTest {

  private static final String WORLD = "world";
  private static final Logger LOGGER = Logger.getLogger("MikuXrayNetTest");

  /**
   * 测试用配置：条目数上限充裕、维护周期很长（避免后台任务干扰）、队列容量大于本用例写入量；
   * {@code bucketCacheSize=256} 必须大于写入条数——否则桶缓存 LRU 会在写入过程中就把脏桶刷盘，
   * 等到 close 时已经没有「超过一批」的脏积压，本用例就守不住要守的链路。
   */
  private static AntiXrayConfig.DiskCache config() {
    return new AntiXrayConfig.DiskCache(true, 4096, 16, 600, 256, 600, 3600, 4, 256,
        false, AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, 10, "");
  }

  @Test
  void closeDrainsMoreThanOneBatchPerRegionFile(@TempDir Path dir) throws Exception {
    int records = RegionFile.FLUSH_BATCH_RECORDS * 3 + 7;
    byte[][] payloads = new byte[records][];

    // 读取/收尾预算都放宽到 10s：本用例验证的是「排空完整性」，不该被 CI 磁盘抖动误判
    try (DiskCacheStore store = new DiskCacheStore(dir, config(), LOGGER, 10_000L)) {
      for (int i = 0; i < records; i++) {
        payloads[i] = randomPayload(200, i);
        // 同一区域文件（chunkX∈[0,31]、chunkZ∈[0,3] → r.0.0），保证脏记录集中在同一个句柄上
        store.put(WORLD, i % 32, i / 32, 1, payloads[i]);
      }
      // 等写入任务全部执行完（只落到内存 + 打脏标记，不落盘）：否则 close() 会先置 closed，
      // 排队中的 doPut 会因 handle() 返回 null 被跳过——那测到的就不再是「关闭排空」。
      awaitPendingOpsIdle(store);
      // 故意不调 flush()：close() 自己必须把超过一批的脏记录全部排空
    }

    try (DiskCacheStore reopened = new DiskCacheStore(dir, config(), LOGGER, 10_000L)) {
      for (int i = 0; i < records; i++) {
        assertArrayEquals(payloads[i], reopened.get(WORLD, i % 32, i / 32, 1),
            "第 " + i + " 条记录必须在 close() 排空时落盘、并在重开后逐字节读回");
      }
    }
  }

  /**
   * 等待磁盘线程把已提交的任务全部执行完（{@code pendingOps} 归零），但<b>不触发落盘</b>。
   *
   * <p>为什么必须等：{@code put} 是「提交即返回」的异步写入，而 {@code close()} 第一步就置
   * {@code closed}；若还有排队的 doPut，其 {@code handle()} 会因 closed 返回 null 而静默跳过写入。
   * 等了再关，才能保证关闭时确实存在「超过一批」的脏积压。
   */
  private static void awaitPendingOpsIdle(DiskCacheStore store) throws Exception {
    Field field = DiskCacheStore.class.getDeclaredField("pendingOps");
    field.setAccessible(true);
    AtomicInteger pending = (AtomicInteger) field.get(store);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (pending.get() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(1L);
    }
    assertEquals(0, pending.get(),
        "前置：写入任务必须在 close 前全部执行完（否则 close 置 closed 会丢弃排队中的 put）");
  }

  /** 确定性负载（同一 seed 同一内容；首字节带上序号，便于失败时定位是哪一条）。 */
  private static byte[] randomPayload(int length, long seed) {
    byte[] data = new byte[length];
    new Random(seed).nextBytes(data);
    data[0] = (byte) (seed ^ 0x5A);
    return data;
  }
}
