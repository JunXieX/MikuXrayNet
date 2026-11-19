package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「打开失败 ≠ 损坏」的回归：<b>环境失败保留文件并退避降级，只有确定的结构损坏才就地重建</b>。
 *
 * <p><b>本轮修的两个缺陷</b>：
 * <ol>
 *   <li>{@code DiskCacheStore.handle()} 过去把 {@code RegionFile.open} 的<b>任何</b> {@code IOException}
 *       都当成「文件头损坏」——先打一条 WARN、再 {@code Files.deleteIfExists(path)}、然后重开。于是
 *       缓存目录只读 / 文件被占用 / 路径过长这类环境问题会在<b>每个区块包</b>上重复「失败 open ×2
 *       + 失败删除 + 2 条 WARN」，并顺手丢掉一份本来还能用的缓存（而且删除通常也会失败）；</li>
 *   <li>结构损坏（文件短于头部+偏移表、魔数/版本非法）本该重建，但旧实现靠「删除 + 重开」实现，
 *       在 Windows 上删除会因其它句柄占用失败，重开还会多一次取锁窗口。</li>
 * </ol>
 *
 * <p><b>修法</b>：{@link RegionFile#open} 把失败分成「环境」（抛普通 {@code IOException}，不动文件）与
 * 「结构损坏」（内部 {@link RegionFile.CorruptFileException}，沿已持锁的同一条通道就地重建）；
 * {@code DiskCacheStore} 对前者只做「有容量上限 + TTL 的退避」（窗口内纯内存判断、零 I/O、不排任务），
 * 对后者只计一次异常。
 *
 * <p>本类<b>不构造任何恶意/损坏外部数据以外的输入</b>：用「把区域文件路径占成目录」「把世界目录占成普通
 * 文件」「写 5 字节文件」「改魔数/版本字节」这些正常文件系统操作复现失败态，全是管理员真能遇到的场景。
 */
class DiskCacheOpenRecoveryTest {

  private static final String WORLD = "world";
  private static final Logger LOGGER = Logger.getLogger("MikuXrayNetTest");

  private static AntiXrayConfig.DiskCache config(int maxEntries) {
    return new AntiXrayConfig.DiskCache(true, maxEntries, 16, 600, 2, 600, 3600, 4, 256,
        false, AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, 10, "");
  }

  /** 读写与收尾预算都放宽到 10s：本类断言的是「失败分类与计数」，不该被 CI 抖动干扰。 */
  private static DiskCacheStore store(Path dir, AntiXrayConfig.DiskCache config) {
    return new DiskCacheStore(dir, config, LOGGER, 10_000L, 10_000L);
  }

  private static byte[] payload(int length, long seed) {
    byte[] data = new byte[length];
    for (int index = 0; index < length; index++) {
      data[index] = (byte) (seed * 31 + index);
    }
    return data;
  }

  /** 让磁盘线程把此前提交的 doPut 全部跑完（同一单线程执行器，flush 之后即可断言计数）。 */
  private static void drain(DiskCacheStore store) {
    store.flush();
  }

  // ------------------------------------------------------------------ 反射：只读/改写退避表，避免为测试在生产代码开洞

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> openFailuresOf(DiskCacheStore store) throws Exception {
    Field field = DiskCacheStore.class.getDeclaredField("openFailures");
    field.setAccessible(true);
    return (LinkedHashMap<Object, Object>) field.get(store);
  }

  private static long untilNanosOf(Object failure) throws Exception {
    Method method = failure.getClass().getDeclaredMethod("untilNanos");
    method.setAccessible(true);
    return (long) method.invoke(failure);
  }

  /** 把某条退避记录推到「窗口已过」：模拟 30s 退避到期，让下一次访问真的重试。 */
  private static void expireBackoff(Map<Object, Object> failures, Object key) throws Exception {
    Class<?> type = Class.forName(DiskCacheStore.class.getName() + "$OpenFailure");
    Constructor<?> constructor = type.getDeclaredConstructor(long.class);
    constructor.setAccessible(true);
    failures.put(key, constructor.newInstance(System.nanoTime() - 1L));
  }

  // ------------------------------------------------------------------ 1. 打不开 ≠ 损坏

  /**
   * 区域文件「打不开」（这里用「该路径是个目录」复现：Windows 上 {@code AccessDeniedException}、
   * Linux 上 {@code FileSystemException: Is a directory}）时：<b>绝不删除</b>，只降级与计数。
   */
  @Test
  void unreadableRegionFileIsKeptAndDegrades(@TempDir Path dir) throws Exception {
    Path regionPath = dir.resolve(WORLD).resolve("r.0.0.b_linear");
    Files.createDirectories(regionPath); // 关键：把「区域文件」占成一个目录

    try (DiskCacheStore store = store(dir, config(1024))) {
      byte[] data = payload(256, 1);
      store.put(WORLD, 0, 0, 1, data); // 创建路径 → 打不开
      drain(store);

      assertTrue(Files.isDirectory(regionPath),
          "打不开的文件绝不能被删除或替换（旧实现会 deleteIfExists + 重建）");
      assertNull(store.get(WORLD, 0, 0, 1), "打不开时读取按未命中降级");
      assertEquals(0, store.openRegionFiles(), "失败的打开不得留下句柄");
      assertTrue(store.stats().errors.sum() >= 1L, "失败必须计数（诊断口径）");
      assertEquals(0, store.entries(), "失败的写入不得消耗条目额度");
    }
  }

  /**
   * 同一区域的连续失败必须在 TTL 窗口内被<b>退避</b>：第二次访问不再尝试系统调用（errors 不涨）、
   * 也不改动退避截止时间；窗口到期后自动重试并恢复。
   */
  @Test
  void openFailureIsBackedOffAndRetriedAfterWindow(@TempDir Path dir) throws Exception {
    Path regionPath = dir.resolve(WORLD).resolve("r.0.0.b_linear");
    Files.createDirectories(regionPath);

    try (DiskCacheStore store = store(dir, config(1024))) {
      store.put(WORLD, 0, 0, 1, payload(128, 1));
      drain(store);
      Map<Object, Object> failures = openFailuresOf(store);
      assertEquals(1, failures.size(), "应记录一条退避");
      Object key = failures.keySet().iterator().next();
      long firstDeadline = untilNanosOf(failures.get(key));
      long errorsAfterFirst = store.stats().errors.sum();
      assertTrue(firstDeadline > System.nanoTime(), "退避截止时间必须在未来");

      // 窗口内再来两次：纯内存判断，绝不重试
      store.put(WORLD, 0, 0, 1, payload(128, 2));
      store.put(WORLD, 1, 0, 1, payload(128, 3));
      drain(store);
      assertEquals(1, failures.size(), "同一区域只保留一条退避记录");
      assertEquals(firstDeadline, untilNanosOf(failures.get(key)),
          "窗口内不得重新计时或重试（记录未被改写）");
      assertEquals(errorsAfterFirst, store.stats().errors.sum(),
          "窗口内不得再尝试打开（重复失败会再次计数）");

      // 把窗口推到期 → 下一次访问必须真的重试（此处仍失败，故重新计数并续期）
      expireBackoff(failures, key);
      store.put(WORLD, 0, 0, 1, payload(128, 4));
      drain(store);
      assertTrue(store.stats().errors.sum() > errorsAfterFirst,
          "退避到期后必须重新尝试（并因环境仍不可用而再次计数）");
      assertTrue(untilNanosOf(failures.get(key)) > System.nanoTime(), "重试失败后必须续期");
    }
  }

  /** 环境恢复后必须自愈：删掉占位目录 → 窗口到期 → 正常读写，且退避记录被清除。 */
  @Test
  void cacheRecoversWhenEnvironmentComesBack(@TempDir Path dir) throws Exception {
    Path regionPath = dir.resolve(WORLD).resolve("r.0.0.b_linear");
    Files.createDirectories(regionPath);

    try (DiskCacheStore store = store(dir, config(1024))) {
      store.put(WORLD, 0, 0, 1, payload(128, 1));
      drain(store);
      Map<Object, Object> failures = openFailuresOf(store);
      assertEquals(1, failures.size());

      // 管理员把占位目录删掉（缓存目录恢复正常）
      Files.delete(regionPath);
      expireBackoff(failures, failures.keySet().iterator().next());

      byte[] data = payload(640, 2);
      store.put(WORLD, 0, 0, 1, data);
      drain(store);
      assertArrayEquals(data, store.get(WORLD, 0, 0, 1), "环境恢复后必须能正常读写（无需重启）");
      assertTrue(failures.isEmpty(), "打开成功后必须清掉该区域的退避记录");
      assertEquals(1, store.entries());
    }
  }

  /** 退避表必须有容量上限：一个「世界目录被占成普通文件」的故障会为每个区域各记一条。 */
  @Test
  void backoffTableIsCapacityBounded(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir);
    Files.write(dir.resolve(WORLD), new byte[] {1}); // 世界目录被一个普通文件占住

    try (DiskCacheStore store = store(dir, config(1024))) {
      int attempts = 140; // > OPEN_FAILURE_BACKOFF_CAPACITY(128)
      for (int index = 0; index < attempts; index++) {
        store.put(WORLD, index * 32, 0, 1, payload(64, index)); // index*32 ⇒ 每个 put 落在不同 region
      }
      drain(store);
      int recorded = openFailuresOf(store).size();
      assertTrue(recorded <= 128,
          "退避表必须有容量上限（实际 " + recorded + "），否则一个坏目录就能把它撑成无界表");
      assertEquals(0, store.openRegionFiles(), "全部打开失败 ⇒ 不得留下任何句柄");
      assertEquals(0, store.entries(), "失败的写入不得消耗条目额度");
    }
  }

  // ------------------------------------------------------------------ 2. 结构损坏：就地重建

  /** 文件短于「头部 + 偏移表」= 明确的结构损坏：就地重建为可用文件，且原文件不被删除。 */
  @Test
  void truncatedRegionFileIsRebuiltInPlace(@TempDir Path dir) throws Exception {
    Path regionPath = dir.resolve(WORLD).resolve("r.0.0.b_linear");
    Files.createDirectories(regionPath.getParent());
    Files.write(regionPath, new byte[] {1, 2, 3, 4, 5});

    try (DiskCacheStore store = store(dir, config(1024))) {
      byte[] data = payload(512, 7);
      store.put(WORLD, 0, 0, 1, data);
      drain(store);

      assertTrue(Files.isRegularFile(regionPath), "结构损坏也必须保留文件（就地重建，不删除）");
      assertTrue(Files.size(regionPath) >= BufferedLinearV3Format.DATA_AREA_OFFSET,
          "重建后必须至少写下头部 + 偏移表");
      assertArrayEquals(data, store.get(WORLD, 0, 0, 1), "重建后读写必须恢复正常");
      assertTrue(store.stats().errors.sum() >= 1L, "结构损坏重建必须计一次异常（诊断口径）");
    }
  }

  /** 魔数非法 / 版本过旧同属结构损坏：都走就地重建，且重建后的头部必须是「当前版本 + 可用方案」。 */
  @Test
  void invalidHeaderIsRebuiltInPlaceWithCurrentFormat(@TempDir Path dir) throws Exception {
    byte[] seed = BufferedLinearV3Format.encodeHeader(BufferedLinearV3Format.DEFAULT_HASH_SEED);
    for (byte[] broken : new byte[][] {withMagic(seed), withLegacyVersion(seed)}) {
      Path regionPath = dir.resolve(WORLD).resolve("r.0.0.b_linear");
      Files.createDirectories(regionPath.getParent());
      byte[] file = new byte[(int) BufferedLinearV3Format.DATA_AREA_OFFSET];
      System.arraycopy(broken, 0, file, 0, broken.length);
      Files.write(regionPath, file);

      try (DiskCacheStore store = store(dir, config(1024))) {
        byte[] data = payload(256, 3);
        store.put(WORLD, 0, 0, 1, data);
        drain(store);
      }

      assertTrue(Files.isRegularFile(regionPath), "结构损坏不得删除文件");
      byte[] rebuilt = Files.readAllBytes(regionPath);
      assertEquals(BufferedLinearV3Format.VERSION, rebuilt[8],
          "重建后版本字节必须回到当前格式（否则下次打开会再次被判损坏）");
      byte scheme = rebuilt[9];
      assertTrue(scheme == BufferedLinearV3Format.COMPRESSION_DEFLATE
              || scheme == BufferedLinearV3Format.COMPRESSION_ZSTD,
          "重建后压缩方案字节必须合法，实际 0x" + Integer.toHexString(scheme & 0xFF));

      try (DiskCacheStore reopened = store(dir, config(1024))) {
        assertArrayEquals(payload(256, 3), reopened.get(WORLD, 0, 0, 1),
            "重建后的文件必须能被下一个实例正常读出");
      }
      Files.delete(regionPath); // 下一轮用干净目录
    }
  }

  /** 魔数改坏一份（尺寸合法，只有 8 字节魔数不对）。 */
  private static byte[] withMagic(byte[] header) {
    byte[] copy = header.clone();
    copy[0] = 0x00;
    copy[1] = 0x11;
    return copy;
  }

  /** 版本字节改成 0x03（0x06 之前的旧布局）。 */
  private static byte[] withLegacyVersion(byte[] header) {
    byte[] copy = header.clone();
    copy[8] = 0x03;
    return copy;
  }

  // ------------------------------------------------------------------ 3. 通道所有权（结构守门）

  /**
   * {@code RegionFile.open} 的<b>通道所有权</b>守门：{@code FileChannel.open} 之后的第一条语句必须是
   * {@code try}，且异常分支必须关闭通道。
   *
   * <p><b>为什么用源码守门</b>：这条不变量无法用离线行为用例稳定复现——要触发「{@code channel.size()}
   * 抛 IOException」需要一个会在 size() 上失败的 FileChannel 桩（外部实现 19 个抽象方法），代价远大于
   * 收益；而旧缺陷（{@code long size = channel.size();} 写在 close-guard 外面）正是「一行放错位置」，
   * 用源码断言既能钉死、又不会随实现演进误报。
   */
  @Test
  void openOwnsTheChannelFromTheMomentItIsOpened() throws Exception {
    String source = Files.readString(
        Path.of("src/main/java/net/mikumc/mikuxraynet/cache/RegionFile.java"));
    String body = methodBody(source, "static RegionFile open(Path path, int bucketCacheSize)");
    String openedBy = "StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);";
    int openedAt = body.indexOf(openedBy);
    assertTrue(openedAt > 0, "open 必须自己打开通道");
    int openedEnd = openedAt + openedBy.length();
    int guard = body.indexOf("try {", openedEnd);
    assertTrue(guard > openedEnd, "打开通道之后必须立刻进入 close-guard");

    // 通道打开语句结束 → close-guard 之间只允许空白与注释（旧实现正是在这里漏了 channel.size()）
    String gap = body.substring(openedEnd, guard);
    for (String line : gap.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")
          || trimmed.startsWith("*")) {
        continue;
      }
      fail("close-guard 之前不得有任何语句（曾在此漏过 channel.size()，失败时泄漏通道与文件锁）："
          + trimmed);
    }
    assertTrue(body.contains("catch (Throwable throwable)"), "close-guard 必须兜住 Throwable");
    assertTrue(body.contains("closeQuietly(channel)"), "close-guard 的异常分支必须关闭通道");
  }

  /**
   * 终审缺陷回归：<b>读盘与头部解析必须分成两步、两个 try</b>。
   *
   * <p>曾经的写法把 {@code readAt(channel, 0, HEADER_SIZE)} 与 {@code decodeHeaderInfo(...)} 放进同一个
   * {@code try}，于是 {@code readAt} 的<b>普通通道 IOException</b>（文件系统/通道故障）落进了那个
   * {@code catch (IOException)}，被包成 {@code CorruptFileException} ⇒ 上层把一份<b>本来可用</b>的头部与
   * 偏移表就地重写掉（整份缓存条目全丢）。这个把「读」和「解析」混在同一个分类 try 里的形状必须被钉死。
   *
   * <p>用源码守门而不是行为用例的原因：要造出「头部 14 字节已读到、随后解析抛错」以外的通道失败，
   * 只能给 {@code FileChannel} 做桩（19 个抽象方法）或注入新故障开关，两者都比一条结构断言更贵、更易腐化。
   */
  @Test
  void headerReadFailureIsNotClassifiedAsCorruption() throws Exception {
    String source = Files.readString(
        Path.of("src/main/java/net/mikumc/mikuxraynet/cache/RegionFile.java"));
    String body = methodBody(source,
        "private static RegionFile openWithLock(Path path, FileChannel channel, int bucketCacheSize)");

    int parseCatch = body.indexOf("catch (IOException headerFailure)");
    assertTrue(parseCatch > 0, "必须仍存在「头部解析失败 → 结构损坏」的分类 catch");
    int parseTry = body.lastIndexOf("try {", parseCatch);
    assertTrue(parseTry > 0, "分类 catch 必须属于一个 try");
    String parseTryBlock = body.substring(parseTry, parseCatch);
    assertFalse(parseTryBlock.contains("readAt("),
        "读盘不得写进「解析」的 try：readAt 的普通通道 IOException 不是结构损坏，"
            + "误分类会把本来可用的头部与偏移表重写掉");
    int readAtHeader = body.indexOf("readAt(channel, 0, BufferedLinearV3Format.HEADER_SIZE)");
    assertTrue(readAtHeader > 0 && readAtHeader < parseTry,
        "头部读盘必须先于「解析 try」单独发生（普通 IOException 直接上抛到环境失败路径）");
  }

  /**
   * 终审缺陷回归：{@code loadIndex} 逐桶读记录头时<b>不得把普通通道 IOException 吞成空桶</b>。
   *
   * <p>吞掉的后果不是「少一个桶」而是「带着残缺索引继续」：随后 {@code put}/{@code flushDirty} 会把本还在的
   * 记录当成新条目、并用这份读了一半的索引覆盖偏移表——环境抖动被固化成数据丢失。只有已经分类好的
   * {@code CorruptFileException}（记录头被截断）才允许丢桶。
   */
  @Test
  void bucketHeaderReadFailureIsNotSwallowedAsEmptyBucket() throws Exception {
    String source = Files.readString(
        Path.of("src/main/java/net/mikumc/mikuxraynet/cache/RegionFile.java"));
    String body = methodBody(source, "private void loadIndex(long size) throws IOException");
    assertTrue(body.contains("catch (CorruptFileException"),
        "逐桶读记录头只能按已分类的 CorruptFileException（截断）丢桶");
    assertTrue(body.contains("positions[bucket] = 0L;"),
        "截断的记录仍必须把该桶的偏移清空（fail-open 到该桶）");
    assertFalse(body.matches("(?s).*catch\\s*\\(\\s*(java\\.io\\.)?IOException\\s+\\w+\\s*\\).*"),
        "普通通道 IOException 不得被循环里的 catch 吞掉：它必须上抛到外层的关闭+退避路径");
  }

  /** 结构损坏的重建提示必须是一次性闸门（否则每个损坏文件都刷一条 WARN）。 */
  @Test
  void corruptRebuildWarningIsLoggedOnce() throws Exception {
    String source = Files.readString(
        Path.of("src/main/java/net/mikumc/mikuxraynet/cache/RegionFile.java"));
    assertTrue(source.contains("CORRUPT_REBUILD_WARNING.compareAndSet(false, true)"),
        "结构损坏重建的 WARN 必须有一次性别闸门");
    assertTrue(IOException.class.isAssignableFrom(RegionFile.CorruptFileException.class),
        "结构损坏必须用可被 IOException 兜住的专用类型表达（否则会与「打不开」混在一起）");
  }

  /** 按方法签名截出方法体（花括号配平），避免「读固定字符数」在方法变长后误判。 */
  private static String methodBody(String source, String signature) {
    int start = source.indexOf(signature);
    assertTrue(start > 0, "必须仍存在方法 " + signature);
    int open = source.indexOf('{', start);
    int depth = 0;
    for (int index = open; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '{') {
        depth++;
      } else if (character == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(open, index + 1);
        }
      }
    }
    fail("方法体不配平：" + signature);
    return "";
  }
}
