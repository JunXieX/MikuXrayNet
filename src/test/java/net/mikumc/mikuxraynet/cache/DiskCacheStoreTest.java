package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 磁盘缓存生命周期测试：写入→读回字节一致（含句柄关闭后重新打开 = 模拟重启复用）、
 * 代次失效、配置指纹不符即未命中、容量上限、过期清理、世界卸载后句柄关闭、停用后不再服务。
 *
 * <p>全部走真实的临时目录 IO：既能验证 {@code RegionFile}/{@code BufferedLinearV3Format} 的落盘路径，
 * 也能验证「绝不返回脏数据」的判定顺序。
 */
class DiskCacheStoreTest {

  private static final String WORLD = "world";
  private static final Logger LOGGER = Logger.getLogger("MikuXrayNetTest");

  /** 维护周期设得很大，避免后台任务干扰断言；需要落盘的用例显式调用 flush()。 */
  private static AntiXrayConfig.DiskCache config(int maxEntries, int expireSeconds,
      int idleCloseSeconds) {
    return new AntiXrayConfig.DiskCache(true, maxEntries, 16, expireSeconds, 2, idleCloseSeconds,
        3600, 4, 256, 4096);
  }

  /** 测试用构造：把读取预算放宽到 10 秒，避免 CI 磁盘抖动被误判为「未命中」。 */
  private static DiskCacheStore store(Path dir, AntiXrayConfig.DiskCache config) {
    return new DiskCacheStore(dir, config, LOGGER, 10_000L);
  }

  private static byte[] payload(int length) {
    byte[] data = new byte[length];
    new Random(length).nextBytes(data);
    return data;
  }

  /** 互不相同的随机负载（同一 bucket 内多条内容各不相同，Deflater 无法把它压小）。 */
  private static byte[] randomPayload(int length, long seed) {
    byte[] data = new byte[length];
    new Random(seed).nextBytes(data);
    return data;
  }

  /**
   * 单文件大小上限用例（回归：真机上 16MB 上限写出了 30MB 的文件）。
   *
   * <p>根因是上限只看「已落盘字节」，而 {@code put} 只改内存、真正 append 发生在落盘时，
   * 于是首次落盘会把整批 bucket（此处 32 条 × 64KB ≈ 2MB）一次性追加，直接冲破上限。
   * 修正后上限同时计入「已接受但尚未 append 的待写字节」。
   */
  @Test
  void regionFileSizeLimitCountsUnflushedData(@TempDir Path dir) throws Exception {
    AntiXrayConfig.DiskCache limit1Mb = new AntiXrayConfig.DiskCache(true, 20000, 1, 600, 2, 600,
        3600, 4, 256, 4096);
    try (DiskCacheStore store = store(dir, limit1Mb)) {
      for (int chunkX = 0; chunkX < 32; chunkX++) {
        store.put(WORLD, chunkX, 0, 1, randomPayload(64 * 1024, chunkX));
      }
      store.flush();

      Path file = dir.resolve(WORLD).resolve("r.0.0.b_linear");
      assertTrue(Files.isRegularFile(file), "应已生成区域文件");
      long size = Files.size(file);
      assertTrue(size <= 1_200_000L,
          "1MB 上限必须同时拦住尚未落盘的数据，实际文件 " + size + " 字节");
      assertTrue(store.stats().rejectedBySize.sum() > 0L, "超出上限的写入必须被拒绝并计数");
    }
  }

  @Test
  void putThenGetReturnsIdenticalPayload(@TempDir Path dir) {
    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      byte[] data = payload(2_048);
      store.put(WORLD, 3, -5, 77, data);

      assertArrayEquals(data, store.get(WORLD, 3, -5, 77), "同键读取必须字节一致");
      assertEquals(1L, store.stats().hits.sum());
      assertEquals(1, store.entries());

      // 落盘并关闭句柄 → 重新打开（模拟重启）：条目仍在且字节一致
      store.flush();
      store.invalidateWorld(WORLD);
      assertEquals(0, store.openRegionFiles(), "世界失效后句柄必须被关闭");
      assertArrayEquals(data, store.get(WORLD, 3, -5, 77), "重新打开后仍应命中（可跨重启复用）");
    }
  }

  @Test
  void missingKeyIsMiss(@TempDir Path dir) {
    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      assertNull(store.get(WORLD, 0, 0, 1), "从未写入的区块必须未命中");
      assertNull(store.get(WORLD, 0, 0, 1), "同键重复读取仍为未命中");

      store.put(WORLD, 0, 0, 2, payload(128));
      assertNull(store.get(WORLD, 0, 0, 3), "配置指纹不同必须未命中");
      assertNull(store.get("nether", 0, 0, 2), "世界名不同必须未命中");
      assertNull(store.get(WORLD, 1, 0, 2), "区块坐标不同必须未命中");
      assertTrue(store.stats().misses.sum() >= 5L);
    }
  }

  @Test
  void blockChangeBumpsGenerationAndInvalidatesOldEntry(@TempDir Path dir) {
    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      byte[] first = payload(512);
      store.put(WORLD, 8, 9, 5, first);
      assertArrayEquals(first, store.get(WORLD, 8, 9, 5));

      // 观测到该区块的方块变更 → 代次递增 → 旧代次条目失效
      store.markBlockChange(WORLD, 8, 9);
      assertEquals(1L, store.stats().generationBumps.sum());
      assertNull(store.get(WORLD, 8, 9, 5), "代次变化后旧条目必须失效");

      // 重新写入后按新代次命中，且与第一次的内容互不干扰
      byte[] second = payload(640);
      store.put(WORLD, 8, 9, 5, second);
      assertArrayEquals(second, store.get(WORLD, 8, 9, 5), "新代次条目必须可命中");

      // 其它区块不受影响
      store.put(WORLD, 8, 10, 5, first);
      assertArrayEquals(first, store.get(WORLD, 8, 10, 5));
    }
  }

  @Test
  void expiredEntryIsRemovedOnRead(@TempDir Path dir) throws InterruptedException {
    try (DiskCacheStore store = store(dir, config(1024, 1, 600))) {
      store.put(WORLD, 1, 1, 1, payload(256));
      assertArrayEquals(payload(256), store.get(WORLD, 1, 1, 1));

      Thread.sleep(1_200L);
      assertNull(store.get(WORLD, 1, 1, 1), "超过过期时间的条目必须未命中");
      assertTrue(store.stats().expiredRemoved.sum() >= 1L, "过期条目应被清理并计数");
    }
  }

  @Test
  void entryCapacityLimitRejectsNewWrites(@TempDir Path dir) {
    try (DiskCacheStore store = store(dir, config(1, 600, 600))) {
      byte[] first = payload(128);
      store.put(WORLD, 0, 0, 1, first);
      store.flush(); // 确保第一条已经落到磁盘线程（整体计数才准确）
      assertArrayEquals(first, store.get(WORLD, 0, 0, 1));

      store.put(WORLD, 1, 0, 1, payload(128));
      store.flush();
      assertEquals(1L, store.stats().rejectedByCapacity.sum(), "超出条目上限必须拒绝新写入");
      assertNull(store.get(WORLD, 1, 0, 1), "被拒绝的条目不得可读");
      assertArrayEquals(first, store.get(WORLD, 0, 0, 1), "已达上限不影响既有条目");
    }
  }

  @Test
  void invalidateWorldOnlyClosesThatWorld(@TempDir Path dir) {
    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      store.put(WORLD, 0, 0, 1, payload(64));
      store.put("nether", 0, 0, 1, payload(64));
      store.flush();
      assertEquals(2, store.openRegionFiles());

      store.invalidateWorld(WORLD);

      assertEquals(1, store.openRegionFiles(), "只关闭目标世界的句柄");
      assertTrue(Files.isRegularFile(dir.resolve(WORLD).resolve("r.0.0.b_linear")),
          "世界卸载不删除缓存文件（重启后可复用）");
    }
  }

  @Test
  void closedStoreStopsServing(@TempDir Path dir) {
    DiskCacheStore store = store(dir, config(1024, 600, 600));
    store.put(WORLD, 0, 0, 1, payload(64));
    store.close();
    store.close(); // 可重复调用

    assertFalse(store.usable());
    assertNull(store.get(WORLD, 0, 0, 1), "关闭后不再提供读取");
    store.put(WORLD, 2, 0, 1, payload(64));
    store.flush();
    assertEquals(0, store.openRegionFiles());
  }

  @Test
  void disabledConfigIsInert(@TempDir Path dir) {
    AntiXrayConfig.DiskCache disabled = new AntiXrayConfig.DiskCache(false, 1024, 16, 600, 2, 600,
        3600, 4, 256, 4096);
    try (DiskCacheStore store = store(dir, disabled)) {
      assertFalse(store.usable());
      store.put(WORLD, 0, 0, 1, payload(64));
      assertNull(store.get(WORLD, 0, 0, 1), "关闭时读写都是空操作");
      assertEquals(0, store.openRegionFiles());
    }
  }

  @Test
  void corruptRegionFileIsRebuiltInsteadOfFailing(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(WORLD).resolve("r.0.0.b_linear");
    Files.createDirectories(file.getParent());
    Files.write(file, new byte[] {1, 2, 3, 4, 5});

    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      assertNull(store.get(WORLD, 0, 0, 1), "损坏文件按未命中处理");

      byte[] data = payload(256);
      store.put(WORLD, 0, 0, 1, data);
      assertArrayEquals(data, store.get(WORLD, 0, 0, 1), "重建后应恢复正常读写");
      assertTrue(store.stats().errors.sum() >= 1L, "损坏文件应被计数");
    }
  }
}