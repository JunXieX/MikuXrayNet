package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
        3600, 4, 256, false, AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, 10, "");
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
        3600, 4, 256, false, AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, 10, "");
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

  /**
   * 跨进程复用（真机回归）：上一进程写下的条目，必须能被「新进程」（新实例、配置指纹相同）读回。
   *
   * <p>历史缺陷：磁盘键里曾包含「区块代次」，而代次表只在进程内存在——重启后所有代次归零，
   * 于是上一进程写下的、代次非 0 的条目会被逐条判为过期并删除。真机表现为「重启后命中 0／未命中 586、
   * 过期清理 0、异常 0，磁盘上 665 条对不上号」。现在内容新鲜度只由负载里的原始字节指纹判定，
   * 代次不参与键，因此这里断言：<b>新实例读回上一实例写入的条目必须命中</b>。
   */
  @Test
  void entriesSurviveAcrossStoreInstances(@TempDir Path dir) {
    byte[] first = payload(512);
    byte[] second = payload(640);
    try (DiskCacheStore writer = store(dir, config(1024, 600, 600))) {
      writer.put(WORLD, 8, 9, 5, first);
      writer.put(WORLD, 8, 10, 5, second);
      writer.flush();
      writer.invalidateWorld(WORLD);
      assertEquals(0, writer.openRegionFiles());
    }
    try (DiskCacheStore reader = store(dir, config(1024, 600, 600))) {
      assertArrayEquals(first, reader.get(WORLD, 8, 9, 5), "新实例必须能读回上一实例的条目");
      assertArrayEquals(second, reader.get(WORLD, 8, 10, 5));
      assertEquals(2L, reader.stats().hits.sum());
      assertEquals(0L, reader.stats().misses.sum(), "旧条目不允许多余的一次未命中");
      assertNull(reader.get(WORLD, 8, 9, 6), "配置指纹不同仍然必须未命中");
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

  /**
   * 世界名仅大小写不同时 sanitize 后同名：必须共用同一物理文件与<b>同一句柄</b>。
   *
   * <p>回归：旧实现 {@code RegionKey} 存原始世界名、物理文件却按 sanitize 名落盘，于是
   * {@code World} / {@code world} 两个键各自开出句柄、指向同一文件互踩（Windows 上 ATOMIC_MOVE 还会失败）。
   */
  @Test
  void worldNamesDifferingOnlyByCaseShareOnePhysicalFile(@TempDir Path dir) {
    byte[] first = payload(128);
    byte[] second = payload(256);
    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      store.put("World", 0, 0, 1, first);
      store.put("world", 1, 0, 1, second);
      store.flush();

      assertEquals(1, store.openRegionFiles(), "sanitize 后同名的世界必须共用一个句柄（不互踩）");
      assertTrue(Files.isRegularFile(dir.resolve("world").resolve("r.0.0.b_linear")),
          "物理文件按 sanitize 名（小写）落盘");
      assertArrayEquals(first, store.get("World", 0, 0, 1), "大小写不同的世界名指向同一物理文件");
      assertArrayEquals(second, store.get("world", 1, 0, 1));
    }
  }

  /**
   * 句柄关闭必须收回其计入的条目额度（否则条目计数只增不减，{@code max-entries} 随句柄开关不断抬高）。
   */
  @Test
  void entryCountIsReleasedWhenHandleCloses(@TempDir Path dir) {
    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      store.put(WORLD, 0, 0, 1, payload(128));
      store.put(WORLD, 1, 0, 1, payload(256));
      store.flush();
      assertEquals(2, store.entries());

      store.invalidateWorld(WORLD);
      assertEquals(0, store.openRegionFiles());
      assertEquals(0, store.entries(), "世界卸载关闭句柄时必须收回该文件的条目额度");
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

  /**
   * 关闭进行期间的提交不得重新打开句柄。
   *
   * <p>回归：旧实现把 {@code closed} 置位放在 {@code future.get(...)} 之后，排空窗口（最长约 5s）里
   * {@code usable()} 仍为 true，工作线程提交的写入会排到排空任务之后执行，在 {@code closeAllHandles}
   * 之后重新打开句柄并留在 open 表里，{@code shutdownNow} 后无人再关闭（句柄泄漏、文件锁悬挂）。
   * 修正后 {@code closed} 先置位，且 {@code execute()/submit()/handle()} 都以 closed 为闸。
   *
   * <p>用「另一个线程持续提交 + 主线程 close」制造并发的提交窗口；断言在修正后<b>恒成立</b>（不受
   * 调度时序影响），因此不是脆弱用例。
   */
  @Test
  void putDuringCloseNeverLeavesHandlesOpen(@TempDir Path dir) throws Exception {
    DiskCacheStore store = store(dir, config(1024, 600, 600));
    store.put(WORLD, 0, 0, 1, randomPayload(64 * 1024, 0));
    store.flush();

    AtomicBoolean stop = new AtomicBoolean();
    Thread putter = new Thread(() -> {
      int chunkX = 1;
      while (!stop.get()) {
        store.put(WORLD, chunkX++, 0, 1, randomPayload(64, chunkX));
      }
    }, "disk-cache-test-putter");

    putter.start();
    try {
      store.close();
    } finally {
      stop.set(true);
      putter.join(5_000L);
      assertFalse(putter.isAlive(), "提交线程必须能结束（关闭后 put 立即返回，不阻塞）");
    }

    assertFalse(store.usable(), "close 返回后必须不可用");
    assertEquals(0, store.openRegionFiles(), "关闭期间/之后的提交不得重新打开任何句柄");
    store.close(); // 可重复调用
  }

  @Test
  void disabledConfigIsInert(@TempDir Path dir) {
    AntiXrayConfig.DiskCache disabled = new AntiXrayConfig.DiskCache(false, 1024, 16, 600, 2, 600,
        3600, 4, 256, false, AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, 10, "");
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

  /**
   * 世界卸载（主线程）不得为等磁盘缓存而卡顿：磁盘线程被长任务占住时，{@code invalidateWorld}
   * 必须在等待预算内返回，且超时<b>不取消</b>任务——释放磁盘线程后关句柄仍须最终完成（一致性不破坏）。
   *
   * <p>回归：旧实现 {@code submit(..., FLUSH_TIMEOUT_MILLIS)} 会等满 5 秒，而世界卸载发生在主线程，
   * 磁盘线程若正在压缩（整文件重写 + force + move）就会把主线程卡住最长 5 秒（玩家可感的卡服）。
   */
  @Test
  void invalidateWorldDoesNotBlockCallerWhileDiskThreadIsBusy(@TempDir Path dir) throws Exception {
    // 收尾等待预算取 50ms，与生产的 FLUSH_WAIT_MILLIS 一致
    try (DiskCacheStore store = new DiskCacheStore(dir, config(1024, 600, 600), LOGGER,
        10_000L, 50L)) {
      store.put(WORLD, 0, 0, 1, payload(128));
      awaitTrue(() -> store.openRegionFiles() == 1, 5_000L);
      assertEquals(1, store.openRegionFiles(), "写入后应已打开该世界的区域文件句柄");

      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      diskThreadExecutor(store).execute(() -> {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS), "占住磁盘线程的任务必须已开始执行");

      long startNanos = System.nanoTime();
      store.invalidateWorld(WORLD);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

      assertTrue(elapsedMillis < 3_000L,
          "磁盘线程繁忙时世界卸载必须在等待预算内返回（旧实现会阻塞最长 5s），实际 "
              + elapsedMillis + "ms");
      assertEquals(1, store.openRegionFiles(),
          "调用方返回时收尾任务尚未执行（证明磁盘线程确实被占住，等待是被预算截断的）");

      release.countDown();
      awaitTrue(() -> store.openRegionFiles() == 0, 5_000L);
      assertEquals(0, store.openRegionFiles(),
          "超时返回不等于取消：世界失效任务仍须由磁盘线程跑完（句柄最终关闭）");
    }
  }

  /**
   * {@code isCacheThread} 必须是不可伪造的身份判定：只有真正的磁盘线程为 true，
   * 主线程与「改了同名」的外来线程都必须为 false。
   *
   * <p>回归：旧实现按线程名（{@code MikuXrayNet-DiskCache}）判定——外来线程改个同名就会被误判为
   * 磁盘线程，从而绕过 {@code submit()} 的排队、直接在自己线程上跑磁盘 IO（拖住热路径）；
   * 磁盘线程一旦被改名，它自己提交的读取又会去排队等自己、必然超时。
   */
  @Test
  void cacheThreadIdentityCannotBeSpoofedByName(@TempDir Path dir) throws Exception {
    try (DiskCacheStore store = store(dir, config(1024, 600, 600))) {
      assertFalse(cacheThreadFlag(), "主线程（调用方）不得被判为磁盘线程");

      AtomicBoolean impostorFlag = new AtomicBoolean(true);
      AtomicBoolean impostorRan = new AtomicBoolean();
      Thread impostor = new Thread(() -> {
        impostorFlag.set(cacheThreadFlag());
        impostorRan.set(true);
      }, "MikuXrayNet-DiskCache"); // 与真实磁盘线程完全同名
      impostor.start();
      impostor.join(5_000L);
      assertTrue(impostorRan.get(), "伪造线程必须已运行");
      assertFalse(impostorFlag.get(), "同名外来线程不得被判为磁盘线程（名字判定会被伪造误导）");

      AtomicBoolean realFlag = new AtomicBoolean();
      diskThreadExecutor(store).submit(() -> realFlag.set(cacheThreadFlag()))
          .get(5, TimeUnit.SECONDS);
      assertTrue(realFlag.get(), "真实磁盘线程在自己的线程上必须被判为 true");
    }
  }

  /** 反射调用生产代码私有的「是否磁盘线程」判定（不为测试在生产代码开洞）。 */
  private static boolean cacheThreadFlag() {
    try {
      Method method = DiskCacheStore.class.getDeclaredMethod("isCacheThread");
      method.setAccessible(true);
      return (boolean) method.invoke(null);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("无法调用 DiskCacheStore.isCacheThread", exception);
    }
  }

  /** 反射取磁盘线程的执行器：用于在磁盘线程上运行任务、或占住它制造「磁盘线程繁忙」。 */
  private static ScheduledExecutorService diskThreadExecutor(DiskCacheStore store) throws Exception {
    Field field = DiskCacheStore.class.getDeclaredField("executor");
    field.setAccessible(true);
    return (ScheduledExecutorService) field.get(store);
  }

  /** 轮询等待条件成立（避免固定 sleep 时长造成的脆弱用例）。 */
  private static void awaitTrue(BooleanSupplier condition, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10L);
    }
  }
}