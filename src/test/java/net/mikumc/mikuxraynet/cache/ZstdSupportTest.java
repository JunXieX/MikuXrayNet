package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * zstd 前置的自动识别 / 自动下载 / 回退测试。
 *
 * <p>覆盖四件事：①下载 URL 与文件名的构造（含换源）；②探测顺序与「无 zstd 时仍能走 Deflater 往返」；
 * ③下载失败与超时都必须返回 null、不留残留文件、且不抛异常（fail-open）；④三个配置键的默认值与开关。
 *
 * <p>注意：本进程类路径上有 zstd-jni（见离线测试脚本），因此「服务端自带」这条路径天然可测；
 * 「看不到 zstd」的场景用平台类加载器（不含应用类路径）来构造。
 */
class ZstdSupportTest {

  private static byte[] payload(int length) {
    byte[] data = new byte[length];
    for (int i = 0; i < length; i++) {
      data[i] = (byte) ((i * 31) % 23);
    }
    return data;
  }

  /** 测试专用 Deflate（与磁盘缓存旧方案 0x01 同口径）。 */
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

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return configuration;
  }

  /** 收集日志行的测试用 Logger（不向父级传播，避免刷屏）。 */
  private static final class RecordingLogger extends Logger {
    private final List<String> lines = new ArrayList<>();

    private RecordingLogger() {
      super("ZstdSupportTest", null);
      setUseParentHandlers(false);
      setLevel(Level.ALL);
      addHandler(new Handler() {
        @Override
        public void publish(LogRecord record) {
          lines.add(record.getLevel() + " " + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
      });
    }

    private boolean contains(String fragment) {
      return lines.stream().anyMatch(line -> line.contains(fragment));
    }
  }

  // ------------------------------------------------------------------ ① URL 构造与换源

  @Test
  void downloadUrlFollowsMavenLayout() {
    String url = ZstdSupport.downloadUrl(null, ZstdSupport.GROUP, ZstdSupport.ARTIFACT,
        ZstdSupport.VERSION);

    assertEquals("https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.7-15/"
        + "zstd-jni-1.5.7-15.jar", url, "默认源必须指向 Maven Central 的标准坐标路径");
    // 末尾多余的斜杠必须被吃掉（否则会拼出 // 的路径）
    assertEquals(url, ZstdSupport.downloadUrl("https://repo1.maven.org/maven2/",
        ZstdSupport.GROUP, ZstdSupport.ARTIFACT, ZstdSupport.VERSION));
    // 坐标 → 路径 / 文件名 的纯函数
    assertEquals("com/github/luben/zstd-jni/1.5.7-15",
        ZstdSupport.artifactPath(ZstdSupport.GROUP, ZstdSupport.ARTIFACT, ZstdSupport.VERSION));
    assertEquals("zstd-jni-1.5.7-15.jar",
        ZstdSupport.jarFileName(ZstdSupport.ARTIFACT, ZstdSupport.VERSION));
  }

  @Test
  void downloadUrlFollowsConfiguredMirror() {
    String aliyun = ZstdSupport.downloadUrl("https://maven.aliyun.com/repository/public",
        ZstdSupport.GROUP, ZstdSupport.ARTIFACT, ZstdSupport.VERSION);
    String paper = ZstdSupport.downloadUrl(
        "https://maven-central.storage-download.googleapis.com/maven2",
        ZstdSupport.GROUP, ZstdSupport.ARTIFACT, ZstdSupport.VERSION);

    assertEquals("https://maven.aliyun.com/repository/public/com/github/luben/zstd-jni/1.5.7-15/"
        + "zstd-jni-1.5.7-15.jar", aliyun, "换源只替换根地址，坐标路径保持不变");
    assertTrue(paper.startsWith("https://maven-central.storage-download.googleapis.com/maven2/"),
        "Paper 镜像同样可用");
    // 空白源回落 Maven Central（配置写错不会导致「没有下载源」）
    assertEquals(ZstdSupport.downloadUrl(ZstdSupport.DEFAULT_DOWNLOAD_URL, ZstdSupport.GROUP,
            ZstdSupport.ARTIFACT, ZstdSupport.VERSION),
        ZstdSupport.downloadUrl("   ", ZstdSupport.GROUP, ZstdSupport.ARTIFACT,
            ZstdSupport.VERSION));
  }

  /** 平台分类器映射：名称必须与 Maven Central 上实际发布的分类器一致（否则下载必 404）。 */
  @Test
  void classifierMatchesPublishedPlatformNames() {
    String[][] cases = {
        // Windows：64 位别名 / Windows ARM / 32 位别名（真实分类器是 win_x86）
        {"Windows Server 2022", "amd64", "win_amd64"},
        {"Windows 11", "x86_64", "win_amd64"},
        {"Windows 10", "aarch64", "win_aarch64"},
        {"Windows 10", "arm64", "win_aarch64"},
        {"Windows 10", "x86", "win_x86"},
        {"Windows 10", "i386", "win_x86"},
        {"Windows 10", "i686", "win_x86"},
        // macOS（含 Apple Silicon）
        {"Mac OS X", "x86_64", "darwin_x86_64"},
        {"Mac OS X", "amd64", "darwin_x86_64"},
        {"Mac OS X", "aarch64", "darwin_aarch64"},
        {"Mac OS X", "arm64", "darwin_aarch64"},
        // Linux：真实分类器里没有 linux_x86，32 位一律是 linux_i386
        {"Linux", "amd64", "linux_amd64"},
        {"Linux", "x86_64", "linux_amd64"},
        {"Linux", "aarch64", "linux_aarch64"},
        {"Linux", "arm64", "linux_aarch64"},
        {"Linux", "arm", "linux_arm"},
        {"Linux", "armv7l", "linux_arm"},
        {"Linux", "x86", "linux_i386"},
        {"Linux", "i386", "linux_i386"},
        {"Linux", "i486", "linux_i386"},
        {"Linux", "i586", "linux_i386"},
        {"Linux", "i686", "linux_i386"},
        {"Linux", "ppc64", "linux_ppc64"},
        {"Linux", "ppc64le", "linux_ppc64le"},
        {"Linux", "s390x", "linux_s390x"},
        {"Linux", "riscv64", "linux_riscv64"},
        {"Linux", "mips64", "linux_mips64"},
        {"Linux", "loongarch64", "linux_loongarch64"},
        // FreeBSD（含 i386）
        {"FreeBSD", "amd64", "freebsd_amd64"},
        {"FreeBSD", "x86_64", "freebsd_amd64"},
        {"FreeBSD", "x86", "freebsd_i386"},
        {"FreeBSD", "i386", "freebsd_i386"},
        // AIX
        {"AIX", "ppc64", "aix_ppc64"},
        // 不可识别的平台 / 架构 / 缺失属性 → null（调用方退回全平台包）
        {"SunOS", "sparc", null},
        {"SunOS", "amd64", null},
        {"AIX", "x86_64", null},
        {"Linux", "sparc64", null},
        {"Linux", "unknown", null},
        {null, null, null}
    };
    for (String[] entry : cases) {
      assertEquals(entry[2], ZstdSupport.classifierFor(entry[0], entry[1]),
          "平台 " + entry[0] + "/" + entry[1] + " 的分类器必须与实测清单一致（猜错会 404）");
    }
  }

  /**
   * Maven Central 上 zstd-jni 1.5.7-6 / 1.5.7-15 <b>实测</b>的二进制分类器全清单。
   * 映射表里任何一个不在这份清单内的取值都会 404（然后白白回退 6.2 MB 全平台包）。
   */
  private static final Set<String> PUBLISHED_CLASSIFIERS = Set.of(
      "aix_ppc64", "darwin_aarch64", "darwin_x86_64", "freebsd_amd64", "freebsd_i386",
      "linux_aarch64", "linux_amd64", "linux_arm", "linux_i386", "linux_loongarch64",
      "linux_mips64", "linux_ppc64", "linux_ppc64le", "linux_riscv64", "linux_s390x",
      "win_aarch64", "win_amd64", "win_x86");

  /** 映射表的每个取值都必须来自实测清单（防止再出现「凭记忆拼出来的分类器」）。 */
  @Test
  void everyMappedClassifierIsPublishedOnMavenCentral() {
    assertEquals(18, ZstdSupport.platformTable().size(), "映射表应覆盖 18 个可用平台/架构组合");
    for (Map.Entry<String, String> entry : ZstdSupport.platformTable().entrySet()) {
      assertTrue(PUBLISHED_CLASSIFIERS.contains(entry.getValue()),
          entry.getKey() + " 推导出的 " + entry.getValue() + " 不在 Maven Central 实测清单里（会 404）");
    }
    assertTrue(PUBLISHED_CLASSIFIERS.contains("linux_i386"),
        "32 位 Linux 的真实分类器是 linux_i386");
    assertFalse(PUBLISHED_CLASSIFIERS.contains("linux_x86"), "linux_x86 并不存在（曾经的拼写错误）");
  }

  /** 下载候选顺序：平台专用包在前、全平台包在后；文件名 / URL 必须带正确的分类器。 */
  @Test
  void downloadTriesPlatformJarBeforeAllPlatformJar() {
    assertEquals(List.of("win_amd64", ""), ZstdSupport.downloadClassifiers("win_amd64"),
        "Windows 服务器必须先试 win_amd64 平台包，失败再退全平台包");
    assertEquals(List.of("linux_i386", ""), ZstdSupport.downloadClassifiers("linux_i386"));
    assertEquals(List.of(""), ZstdSupport.downloadClassifiers(null),
        "分类器不可识别时只有全平台包一个候选");
    assertEquals(List.of(""), ZstdSupport.downloadClassifiers("   "), "空白分类器按不可识别处理");

    assertEquals("zstd-jni-1.5.7-15-win_amd64.jar",
        ZstdSupport.jarFileName(ZstdSupport.ARTIFACT, ZstdSupport.VERSION, "win_amd64"));
    assertEquals("zstd-jni-1.5.7-15-aix_ppc64.jar",
        ZstdSupport.jarFileName(ZstdSupport.ARTIFACT, ZstdSupport.VERSION, "aix_ppc64"));
    assertEquals("https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.7-15/"
            + "zstd-jni-1.5.7-15-win_amd64.jar",
        ZstdSupport.downloadUrl(null, ZstdSupport.GROUP, ZstdSupport.ARTIFACT,
            ZstdSupport.VERSION, "win_amd64"));
  }

  /** 平台专用包：URL 与文件名都要带上分类器；lib 目录候选顺序必须是「平台包 → 全平台包」。 */
  @Test
  void platformSpecificJarIsTriedBeforeAllPlatformJar(@TempDir Path libDir) {
    String url = ZstdSupport.downloadUrl(ZstdSupport.DEFAULT_DOWNLOAD_URL, ZstdSupport.GROUP,
        ZstdSupport.ARTIFACT, ZstdSupport.VERSION, "linux_amd64");
    assertEquals("https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.7-15/"
        + "zstd-jni-1.5.7-15-linux_amd64.jar", url,
        "平台包约 0.4 MB，全平台包约 6.2 MB（实测），URL 必须带分类器");
    assertEquals("zstd-jni-1.5.7-15-linux_amd64.jar",
        ZstdSupport.jarFileName(ZstdSupport.ARTIFACT, ZstdSupport.VERSION, "linux_amd64"));

    var candidates = ZstdSupport.libraryCandidates(libDir);
    assertEquals(ZstdSupport.jarFileName(ZstdSupport.ARTIFACT, ZstdSupport.VERSION),
        candidates.get(candidates.size() - 1).getFileName().toString(),
        "最后一个候选必须是全平台包（兜底）");
    String classifier = ZstdSupport.platformClassifier();
    if (classifier == null) {
      assertEquals(1, candidates.size(), "平台不可识别时只有全平台包一个候选");
    } else {
      assertEquals(2, candidates.size(), "平台可识别时先试平台包再试全平台包");
      assertEquals(ZstdSupport.jarFileName(ZstdSupport.ARTIFACT, ZstdSupport.VERSION, classifier),
          candidates.get(0).getFileName().toString(), "候选顺序必须与下载顺序一致（否则每次启动都会重下）");
    }
  }

  // ------------------------------------------------------------------ ② 探测顺序与回退

  /**
   * 「看不到 zstd 的类加载器」必须探测失败；此时磁盘缓存仍能完成 Deflate（方案字节 0x01）读写往返。
   *
   * <p>平台类加载器不含应用类路径，因此找不到 zstd-jni（模拟「服务端没有该前置、下载也失败」）。
   */
  @Test
  void probeRejectsLoaderWithoutZstdAndDeflateRoundTripStillWorks() throws Exception {
    assertNull(ZstdSupport.probeCodec(ClassLoader.getPlatformClassLoader()),
        "看不到 zstd-jni 的类加载器必须探测失败（而不是抛异常）");

    // 回退路径：旧方案字节 0x01 仍必须可读可写（用户既有缓存与 fail-open 行为不变）
    byte[] raw = BufferedLinearV3Format.encodeBucket(new BufferedLinearV3Format.Entry[] {
        new BufferedLinearV3Format.Entry(1L, 100L, 5, payload(2_000))},
        BufferedLinearV3Format.DEFAULT_HASH_SEED);
    assertArrayEquals(raw, BufferedLinearV3Format.decompress(deflate(raw), raw.length,
        BufferedLinearV3Format.COMPRESSION_DEFLATE), "Deflate 回退路径必须完成字节级往返");
  }

  /**
   * 探测顺序：类路径可见时 {@code initialize} 必须停在①，不打网络、不在 lib 目录落任何文件。
   */
  @Test
  void initializePrefersServerProvidedZstdAndNeverDownloads(@TempDir Path libDir) {
    RecordingLogger logger = new RecordingLogger();

    ZstdSupport.initialize(libDir, true, "http://127.0.0.1:1/", 1, logger);

    assertTrue(ZstdSupport.available(), "本进程类路径有 zstd-jni，必须探测成功");
    assertEquals(ZstdSupport.Source.SERVER, ZstdSupport.source(), "来源必须是「服务端自带」");
    // 启动日志必须一次性说清三件事：检测到的平台、推导出的分类器、最终来源
    assertTrue(logger.contains("ZSTD 前置：平台 "), "必须打印检测到的平台：" + logger.lines);
    assertTrue(logger.contains("分类器 "), "必须打印推导出的分类器：" + logger.lines);
    assertTrue(logger.contains("；来源=服务端自带"), "必须打印最终来源：" + logger.lines);
    assertFalse(Files.exists(libDir.resolve(
        ZstdSupport.jarFileName(ZstdSupport.ARTIFACT, ZstdSupport.VERSION))),
        "①命中后不得发起下载（lib 目录必须为空）");
  }

  // ------------------------------------------------------------------ ③ 下载失败 / 超时

  /** 连接被拒（离线最常见形态）：返回 null、不抛、lib 目录不留任何文件（含 .tmp）。 */
  @Test
  void refusedDownloadFailsOpenWithoutLeftoverFiles(@TempDir Path libDir) throws Exception {
    ZstdSupport.Codec result =
        ZstdSupport.downloadCodec(libDir, "http://127.0.0.1:1", 1);

    assertNull(result, "连接失败必须返回 null 而不是抛异常");
    try (var entries = Files.list(libDir)) {
      assertTrue(entries.findAny().isEmpty(), "失败后不得留下成品或 .tmp 残留：" + libDir);
    }
  }

  /** 超时：总等待必须有硬上限（绝不超过 timeout-seconds），失败同样 fail-open。 */
  @Test
  void downloadTimeoutIsBoundedAndFailsOpen(@TempDir Path libDir) {
    long startedAt = System.nanoTime();

    // 不可路由地址：连接会一直挂到连接超时，用来验证「总等待上限」生效
    ZstdSupport.Codec result =
        ZstdSupport.downloadCodec(libDir, "http://10.255.255.1:81", 1);

    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
    assertNull(result, "超时必须返回 null（fail-open）");
    assertTrue(elapsedMillis < 5_000L,
        "等待时间必须有硬上限（1 秒超时 + 少量余量），实际 " + elapsedMillis + " ms");
  }

  /** 加载校验：损坏 / 非 zstd 的 jar 必须被拒绝，且不得覆盖已经可用的探测结论。 */
  @Test
  void unloadableJarIsRejectedWithoutAffectingAvailability(@TempDir Path libDir) throws Exception {
    Path fake = libDir.resolve(
        ZstdSupport.jarFileName(ZstdSupport.ARTIFACT, ZstdSupport.VERSION));
    Files.write(fake, new byte[] {0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4});

    assertNull(ZstdSupport.loadFromJar(fake), "损坏/非 zstd 的 jar 必须被拒绝（返回 null）");
    assertTrue(ZstdSupport.available(), "一次失败的加载不得影响已可用的 zstd（探测结论不被覆盖）");
  }

  /** 正例：真实 zstd-jni jar 必须能被独立类加载器加载（即「本地已下载」这条路径本身可用）。 */
  @Test
  void realZstdJarLoadsThroughIsolatedClassLoader() throws Exception {
    Path jar = Path.of(com.github.luben.zstd.Zstd.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI());

    ZstdSupport.Codec codec = ZstdSupport.loadFromJar(jar);

    assertNotNull(codec, "真实 zstd-jni jar 必须能被独立类加载器加载：" + jar);
    assertNotSame(ZstdSupport.class.getClassLoader(), codec.loader(),
        "必须使用独立类加载器（服务端没有该前置时不能依赖父层）");
  }

  // ------------------------------------------------------------------ ④ 配置键

  /** 三个新键的默认值与开关：缺省回落 + 显式覆盖 + 空白源归一。 */
  @Test
  void zstdDownloadConfigDefaultsAndSwitches() {
    AntiXrayConfig defaults = AntiXrayConfig.from(yaml("enabled: true\n"));
    assertTrue(defaults.diskCache().zstdAutoDownload(), "zstd.auto-download 默认开启");
    assertEquals(AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, defaults.diskCache().zstdDownloadUrl(),
        "zstd.download-url 默认 Maven Central");
    assertEquals(10, defaults.diskCache().zstdTimeoutSeconds(), "zstd.timeout-seconds 默认 10 秒");

    AntiXrayConfig overridden = AntiXrayConfig.from(yaml("""
        enabled: true
        disk-cache:
          zstd:
            auto-download: false
            download-url: https://maven.aliyun.com/repository/public
            timeout-seconds: 3
        """));
    assertFalse(overridden.diskCache().zstdAutoDownload(), "auto-download: false 必须生效");
    assertEquals("https://maven.aliyun.com/repository/public",
        overridden.diskCache().zstdDownloadUrl(), "换源必须生效（CN 服务器推荐阿里云镜像）");
    assertEquals(3, overridden.diskCache().zstdTimeoutSeconds(), "超时必须可配");

    AntiXrayConfig blank = AntiXrayConfig.from(yaml("""
        enabled: true
        disk-cache:
          zstd:
            download-url: "   "
            timeout-seconds: 0
        """));
    assertEquals(AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, blank.diskCache().zstdDownloadUrl(),
        "空白源必须回落 Maven Central");
    assertEquals(1, blank.diskCache().zstdTimeoutSeconds(), "超时下限为 1 秒（0 会被抬到 1）");
  }
}