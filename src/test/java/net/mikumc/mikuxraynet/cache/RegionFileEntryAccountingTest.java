package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 「打开时补计磁盘既有条目」的口径回归：<b>复用 {@code loadIndex} 已读到的记录头、且不与本次会话的新写入重复记账</b>。
 *
 * <p><b>本轮修的两个缺陷</b>：
 * <ol>
 *   <li><b>重复 I/O</b>：旧实现先由 {@code loadIndex} 把每个桶的 8 字节记录头读一遍（读完把 {@code rawLength}
 *       丢弃），再由延迟任务 {@code countDiskEntries → countEntriesOnDisk} 把同样的记录头<b>再读一遍</b>
 *       （最多 1024 次随机 {@code pread}/文件打开）。</li>
 *   <li><b>基线重复计数</b>：计数任务排在磁盘线程队尾，可能与「打开之后才写入」的 {@code put} 交错，
 *       把本会话刚写（尚未落盘）或刚落盘的条目再算进基线一次——{@code max-entries} 因此被提前耗尽。</li>
 * </ol>
 *
 * <p><b>修法</b>：基线在 {@code RegionFile#loadIndex} 的同一次遍历里按记录头算出
 * （{@code diskEntryBaseline()}），{@code DiskCacheStore.handle()} 在句柄发布<b>之前</b>一次性补进近似计数；
 * 此后本会话的写入只由 put/clear/compact 增量维护，句柄关闭时整笔归还。
 */
class RegionFileEntryAccountingTest {

  private static final String WORLD = "world";
  private static final Logger LOGGER = Logger.getLogger("MikuXrayNetTest");

  private static AntiXrayConfig.DiskCache config(int maxEntries) {
    return new AntiXrayConfig.DiskCache(true, maxEntries, 16, 600, 2, 600, 3600, 4, 256,
        false, AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, 10, "");
  }

  private static DiskCacheStore store(Path dir, AntiXrayConfig.DiskCache config) {
    return new DiskCacheStore(dir, config, LOGGER, 10_000L, 10_000L);
  }

  private static byte[] payload(int length, long seed) {
    byte[] data = new byte[length];
    new Random(seed).nextBytes(data);
    return data;
  }

  private static BufferedLinearV3Format.Entry entry(int marker) {
    return new BufferedLinearV3Format.Entry(0L, System.currentTimeMillis(), marker,
        payload(192, marker));
  }

  /** 三个互不相同的桶（BUCKET_SIZE=1 ⇒ 一个 chunkIndex 一个桶）。 */
  private static int[] threeBuckets() {
    return new int[] {
        BufferedLinearV3Format.chunkIndex(0, 0),
        BufferedLinearV3Format.chunkIndex(1, 0),
        BufferedLinearV3Format.chunkIndex(2, 0)};
  }

  // ------------------------------------------------------------------ RegionFile 级：基线口径

  /** 基线 = 偏移表指向的有效记录数；覆盖写不增加条目数，{@code clear} 落盘后减一。 */
  @Test
  void baselineCountsRecordsUnderThePositionTable(@TempDir Path dir) throws Exception {
    Path path = dir.resolve("r.0.0.b_linear");
    int[] buckets = threeBuckets();

    try (RegionFile region = RegionFile.open(path, 4)) {
      assertEquals(0, region.diskEntryBaseline(), "新建文件的基线必须是 0");
      for (int index = 0; index < buckets.length; index++) {
        region.put(buckets[index], entry(index + 1));
      }
      region.flushDirty();
    }

    try (RegionFile reopened = RegionFile.open(path, 4)) {
      assertEquals(buckets.length, reopened.diskEntryBaseline(), "三条记录 ⇒ 基线 3");
      assertFalse(reopened.rebuiltOnOpen(), "合法文件不得被判定为结构损坏");
      // 覆盖写同一条目并落盘：新增一份记录副本，但偏移表仍只指向一条 ⇒ 基线不变
      reopened.put(buckets[0], entry(9));
      reopened.flushDirty();
    }

    try (RegionFile reopened = RegionFile.open(path, 4)) {
      assertEquals(buckets.length, reopened.diskEntryBaseline(),
          "覆盖写（追加新副本）不得让基线变大：条目数按偏移表口径");
      // 清掉一条并落盘（写入 tombstone 记录）⇒ 基线减一。
      // 先读一次把桶载入：clear 对未加载的桶按设计返回 false（不为清理而整桶解码）
      assertNotNull(reopened.get(buckets[1]), "前置：先载入待清理的桶");
      assertTrue(reopened.clear(buckets[1]), "已加载桶上的 clear 必须生效");
      reopened.flushDirty();
    }

    try (RegionFile reopened = RegionFile.open(path, 4)) {
      assertEquals(buckets.length - 1, reopened.diskEntryBaseline(), "清掉一条后基线必须为 2");
    }
  }

  /** 压缩回收后基线按「保留的条目」重算；重建/新建/lock 不可用的基线恒为 0。 */
  @Test
  void baselineFollowsCompactAndDegradedPaths(@TempDir Path dir) throws Exception {
    Path path = dir.resolve("r.0.0.b_linear");
    int[] buckets = threeBuckets();

    try (RegionFile region = RegionFile.open(path, 4)) {
      for (int index = 0; index < buckets.length; index++) {
        region.put(buckets[index], entry(index + 1));
      }
      region.flushDirty();
      // 丢弃中间那条（模拟过期回收）：压缩把整文件重写成 2 条记录
      assertEquals(1, region.compact((chunkIndex, value) -> chunkIndex != buckets[1]),
          "压缩应丢弃 1 条");
    }

    try (RegionFile reopened = RegionFile.open(path, 4)) {
      assertEquals(2, reopened.diskEntryBaseline(), "压缩后基线必须按保留条目重算");
      assertNotNull(reopened.get(buckets[0]));
      assertNull(reopened.get(buckets[1]), "被丢弃的条目不得残留");
    }

    // 结构损坏就地重建：基线归零、rebuiltOnOpen 置位；重开后不再标记重建
    Files.write(path, new byte[] {1, 2, 3});
    try (RegionFile rebuilt = RegionFile.open(path, 4)) {
      assertTrue(rebuilt.rebuiltOnOpen(), "结构损坏必须标记「已就地重建」");
      assertEquals(0, rebuilt.diskEntryBaseline(), "重建后的文件是空表 ⇒ 基线 0");
    }
    try (RegionFile healthy = RegionFile.open(path, 4)) {
      assertFalse(healthy.rebuiltOnOpen(), "已重建好的文件再次打开不得重复标记");
      assertEquals(0, healthy.diskEntryBaseline());
    }
  }

  /** 锁不可用的空壳句柄：不读索引 ⇒ 基线恒 0（否则会白占 max-entries 额度）。 */
  @Test
  void lockUnavailableShellHasZeroBaseline(@TempDir Path dir) throws Exception {
    Path path = dir.resolve("r.0.0.b_linear");
    try (RegionFile region = RegionFile.open(path, 4)) {
      region.put(BufferedLinearV3Format.chunkIndex(0, 0), entry(1));
      region.flushDirty();
    }

    RegionFile.lockOutcomeOverrideForTest = Boolean.FALSE; // false = 按「被占用」处理
    try (RegionFile shell = RegionFile.open(path, 4)) {
      assertTrue(shell.lockUnavailable(), "前置：本实例应按「锁不可用」停用");
      assertEquals(0, shell.diskEntryBaseline(), "停用态不得声称已知磁盘条目数");
      assertFalse(shell.rebuiltOnOpen(), "停用态不读头，不得判成结构损坏");
      assertNull(shell.get(BufferedLinearV3Format.chunkIndex(0, 0)), "停用态读一律未命中");
      assertFalse(shell.isEmptyFile(), "停用态绝不能因「看似为空」被删除");
    } finally {
      RegionFile.lockOutcomeOverrideForTest = null;
    }
  }

  // ------------------------------------------------------------------ DiskCacheStore 级：不与会话内新写重复

  /** 打开句柄补计基线之后，本会话的 {@code put} 只 +1：{@code entries()} 不得翻倍。 */
  @Test
  void storeBaselineDoesNotDoubleCountSessionWrites(@TempDir Path dir) {
    byte[] first = payload(256, 1);
    byte[] second = payload(320, 2);
    try (DiskCacheStore writer = store(dir, config(1024))) {
      writer.put(WORLD, 0, 0, 5, first);
      writer.put(WORLD, 1, 0, 5, second);
      writer.flush();
    }

    try (DiskCacheStore reader = store(dir, config(1024))) {
      assertArrayEquals(first, reader.get(WORLD, 0, 0, 5), "重启后既有条目仍可读");
      assertEquals(2, reader.entries(), "首次打开按基线补计 2 条");

      byte[] third = payload(384, 3);
      reader.put(WORLD, 2, 0, 5, third);
      reader.flush();
      assertEquals(3, reader.entries(),
          "基线 2 + 本会话新增 1 = 3（旧实现的延迟补计会把这条重复算成 4）");
      assertArrayEquals(third, reader.get(WORLD, 2, 0, 5));
    }

    // 再重启一次：基线按磁盘真实条目数重算，不累积
    try (DiskCacheStore restarted = store(dir, config(1024))) {
      assertArrayEquals(first, restarted.get(WORLD, 0, 0, 5));
      assertEquals(3, restarted.entries(), "三次打开之后条目数必须仍是 3");
    }
  }

  /** 句柄关闭必须整笔归还基线（否则 max-entries 随句柄开关单调抬高）。 */
  @Test
  void closingHandleReleasesTheBaseline(@TempDir Path dir) {
    try (DiskCacheStore writer = store(dir, config(1024))) {
      writer.put(WORLD, 0, 0, 5, payload(128, 1));
      writer.put(WORLD, 1, 0, 5, payload(128, 2));
      writer.flush();
    }

    try (DiskCacheStore store = store(dir, config(1024))) {
      store.get(WORLD, 0, 0, 5); // 打开句柄 ⇒ 基线 2
      assertEquals(2, store.entries());
      store.invalidateWorld(WORLD); // 关句柄 ⇒ 归还额度
      assertEquals(0, store.openRegionFiles());
      assertEquals(0, store.entries(), "关句柄必须归还基线额度");
      store.get(WORLD, 0, 0, 5); // 重新打开 ⇒ 重新补计
      assertEquals(2, store.entries());
    }
  }

  /** 条目上限按基线生效：重启后旧条目必须占额度（max-entries=2 时第三条写入被拒）。 */
  @Test
  void baselineKeepsMaxEntriesEffectiveAcrossRestart(@TempDir Path dir) {
    try (DiskCacheStore writer = store(dir, config(2))) {
      writer.put(WORLD, 0, 0, 5, payload(128, 1));
      writer.put(WORLD, 1, 0, 5, payload(128, 2));
      writer.flush();
    }
    try (DiskCacheStore restarted = store(dir, config(2))) {
      assertArrayEquals(payload(128, 1), restarted.get(WORLD, 0, 0, 5));
      assertEquals(2, restarted.entries(), "重启后既有条目必须补进额度");
      byte[] third = payload(128, 3);
      restarted.put(WORLD, 2, 0, 5, third);
      restarted.flush();
      assertEquals(1L, restarted.stats().rejectedByCapacity.sum(), "上限必须继续生效");
      assertNull(restarted.get(WORLD, 2, 0, 5), "被拒的条目不得可读");
    }
  }
}
