package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.logging.Logger;
import java.util.zip.Deflater;
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

  /** 互不相同的随机负载（同一 bucket 内多条内容各不相同，压缩器无法把它压小）。 */
  private static byte[] randomPayload(int length, long seed) {
    byte[] data = new byte[length];
    new Random(seed).nextBytes(data);
    return data;
  }

  /** 测试专用的旧格式压缩器（JDK Deflater.BEST_SPEED）：用于构造 0x01 的历史区域文件。 */
  private static byte[] deflate(byte[] raw) {
    Deflater deflater = new Deflater(Deflater.BEST_SPEED);
    try {
      deflater.setInput(raw);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2 + 64));
      byte[] chunk = new byte[8192];
      while (!deflater.finished()) {
        int produced = deflater.deflate(chunk);
        if (produced <= 0) {
          break;
        }
        out.write(chunk, 0, produced);
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
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

  /**
   * max-entries 上限必须跨重启继续生效（回归：重启后近似计数从 0 开始，磁盘上的旧条目全部不占额度）。
   *
   * <p>重启 = 用同一目录重新创建 DiskCacheStore。首次打开既有区域文件时会异步扫描补计磁盘条目；
   * 第二次读取已排在计数任务之后，因此返回时补计必然完成。
   */
  @Test
  void maxEntriesLimitSurvivesRestart(@TempDir Path dir) {
    byte[] first = payload(128);
    byte[] second = payload(256);
    try (DiskCacheStore original = store(dir, config(2, 600, 600))) {
      original.put(WORLD, 0, 0, 1, first);
      original.put(WORLD, 1, 0, 1, second);
      original.flush();
    }

    try (DiskCacheStore restarted = store(dir, config(2, 600, 600))) {
      assertEquals(0, restarted.openRegionFiles(), "重启后句柄表为空");
      assertArrayEquals(first, restarted.get(WORLD, 0, 0, 1), "重启后既有条目仍可读");
      // 第一次读取建立了句柄并排程补计；第二次读取已排在补计任务之后 → 返回时补计必然完成
      assertArrayEquals(second, restarted.get(WORLD, 1, 0, 1));
      assertEquals(2, restarted.entries(), "磁盘上的既有条目必须补进近似计数");

      restarted.put(WORLD, 2, 0, 1, payload(512));
      assertEquals(1L, restarted.stats().rejectedByCapacity.sum(),
          "重启后 max-entries 上限必须继续生效（旧条目占额度）");
      assertNull(restarted.get(WORLD, 2, 0, 1), "被拒绝的条目不得可读");
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

  /**
   * 旧格式（方案字节 0x01 = Deflate）区域文件升级后仍必须可读——不得被当成损坏文件删除。
   *
   * <p>做法：手工拼一份「头部 0x01 + 偏移表 + 用 Deflater 压缩的 bucket」的 {@code .b_linear}，
   * 再让当前实现（写入已改用 zstd）去读，验证读路径按头部方案字节选择解压器。
   */
  @Test
  void legacyDeflateRegionFileIsReadableAfterUpgrade(@TempDir Path dir) throws Exception {
    int seed = BufferedLinearV3Format.DEFAULT_HASH_SEED;
    int chunkX = 0;
    int chunkZ = 0;
    int configHash = 42;
    byte[] data = payload(1_024);

    int chunkIndex = BufferedLinearV3Format.chunkIndex(chunkX, chunkZ);
    int bucket = BufferedLinearV3Format.bucketIndex(chunkIndex);
    int slot = chunkIndex & (BufferedLinearV3Format.BUCKET_SIZE - 1);

    BufferedLinearV3Format.Entry[] slots =
        new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
    slots[slot] = new BufferedLinearV3Format.Entry(0L, System.currentTimeMillis(), configHash, data);
    byte[] raw = BufferedLinearV3Format.encodeBucket(slots, seed);
    byte[] deflated = deflate(raw);

    long[] offsets = new long[BufferedLinearV3Format.BUCKET_COUNT];
    offsets[bucket] = BufferedLinearV3Format.DATA_AREA_OFFSET;

    byte[] header = BufferedLinearV3Format.encodeHeader(seed);
    header[9] = BufferedLinearV3Format.COMPRESSION_DEFLATE;

    ByteBuffer fileBytes =
        ByteBuffer.allocate((int) BufferedLinearV3Format.DATA_AREA_OFFSET + 8 + deflated.length);
    fileBytes.put(header);
    fileBytes.put(BufferedLinearV3Format.encodePosTable(offsets));
    fileBytes.putInt(raw.length);
    fileBytes.putInt(deflated.length);
    fileBytes.put(deflated);

    Path file = dir.resolve(WORLD).resolve("r.0.0.b_linear");
    Files.createDirectories(file.getParent());
    Files.write(file, fileBytes.array());

    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      assertArrayEquals(data, store.get(WORLD, chunkX, chunkZ, configHash),
          "旧 Deflate 缓存文件必须仍能正确读出（方案字节 0x01）");
      assertEquals(1L, store.stats().hits.sum());
      assertTrue(Files.isRegularFile(file), "旧文件不得被删除");
    }
  }
}