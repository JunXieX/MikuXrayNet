package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link SparkDumpCapture} 的对外口径与纯函数部分：返回段格式、命令形态、端点收敛、回执提示、
 * 目录候选与生命周期（close/start 的幂等与 fail-soft）。
 *
 * <p>真正「跑起来」的路径需要服务端与 Spark 实例，离线只能覆盖这些决定性环节——它们恰好也是最容易被
 * 后续改动悄悄破坏的部分（返回段格式被调用方整段拼进 dump、key 白名单挡住 URL 注入、Folia 判据决定
 * 是否值得等 30 秒）。行为证据另见 {@code SparkProfileFilesTest} 与真机冒烟记录。
 */
class SparkDumpCaptureTest {

  private static final String MARKER = "mxnet-0123456789abcdef0123456789abcdef";

  // ------------------------------------------------------------------
  // 返回段：格式固定，且只写状态与链接
  // ------------------------------------------------------------------

  @Test
  void uploadedBlockHasFixedShapeAndLinkLine() {
    String text = SparkDumpCapture.render("已上传", "https://spark.lucko.me/abc123def", "说明占位");

    assertTrue(text.startsWith("\n---- Spark 采样 ----\n"), "标题行是调用方定位整段的锚点");
    assertTrue(text.endsWith("\n"), "必须以换行结尾，拼接进 dump 时不粘连");
    assertTrue(text.contains("窗口：30 秒"), "窗口口径必须写明（Spark 原生采样 30 秒）");
    assertTrue(text.contains("状态：已上传"));
    assertTrue(text.contains("完整链接：https://spark.lucko.me/abc123def"));
    assertTrue(text.contains("说明：说明占位"));
  }

  @Test
  void blockWithoutLinkNeverInventsOne() {
    String text = SparkDumpCapture.render("未执行（本服没有可用的 Spark）", null, null);

    assertFalse(text.contains("完整链接"), "没有上传就不能出现链接行");
    assertFalse(text.contains("说明："), "没有说明就不该留空壳说明行");
    assertTrue(text.contains("状态：未执行（本服没有可用的 Spark）"));
  }

  @Test
  void notStartedBlockNeverClaimsAProfileWindow() {
    // 真机 T4 抓到的红项：Spark 没跑时仍写「窗口：30 秒」，与同文件 10 秒的数值采样段自相矛盾
    for (String state : List.of(
        "未执行（本服没有可用的 Spark）",
        "未执行（已有 Spark 采样在进行，未打断它）",
        "未执行（Spark 已被服务端的全局配置禁用）",
        "未执行（本服没有 spark 命令）",
        "未执行（派发 Spark 命令失败：连接超时）")) {
      String text = SparkDumpCapture.render(state, null, "说明占位");
      assertFalse(text.contains("30 秒"), "未执行时不得出现 30 秒口径：" + state);
      assertFalse(text.contains("全部线程"), "未执行时不得出现「全部线程」：" + state);
      assertTrue(text.contains("窗口：未采样"), "未执行时必须明说没有性能记录：" + state);
      assertTrue(text.contains("状态：" + state), "状态行必须保留：" + state);
      assertTrue(text.endsWith("\n"));
    }
  }

  @Test
  void startedBlockKeepsTheThirtySecondWindow() {
    for (String state : List.of("已上传", "未完成（等待 Spark 生成采样文件超时）",
        "未完成（上传失败：服务端返回 500）", "未完成（本次采样文件超出大小上限，未上传）")) {
      String text = SparkDumpCapture.render(state, null, "说明占位");
      assertTrue(text.contains("窗口：30 秒"), "已启动分支必须保留 30 秒口径：" + state);
      assertTrue(text.contains("全部线程"), "已启动分支必须保留采样范围说明：" + state);
      assertFalse(text.contains("窗口：未采样"), "已启动分支不得写成未采样：" + state);
    }
  }

  @Test
  void blankStateFallsBackToUnknownInsteadOfEmpty() {
    assertTrue(SparkDumpCapture.render(null, null, null).contains("状态：未完成（未知原因）"));
    assertTrue(SparkDumpCapture.render("  ", null, null).contains("状态：未完成（未知原因）"));
  }

  @Test
  void isNotStartedOnlyMatchesTheNotStartedStateWord() {
    assertTrue(SparkDumpCapture.isNotStarted("未执行（本服没有可用的 Spark）"));
    assertFalse(SparkDumpCapture.isNotStarted("已上传"));
    assertFalse(SparkDumpCapture.isNotStarted("未完成（等待 Spark 生成采样文件超时）"));
    assertFalse(SparkDumpCapture.isNotStarted("已取消（插件停用或调用方放弃等待）"));
    assertFalse(SparkDumpCapture.isNotStarted(null));
  }

  @Test
  void notStartedStateWordIsStableAndExclusive() {
    // 「未执行」是「这次 Spark 根本没跑」的统一状态词：调用方据此把数值窗口收回 10 秒，
    // 因此启动成功后的任何分支（超时/上传失败/已上传/已取消）都不得出现这四个字。
    assertTrue(SparkDumpCapture.render("未执行（已有 Spark 采样在进行，未打断它）", null, null).contains("未执行"));
    for (String ongoing : List.of("已上传", "未完成（等待 Spark 生成采样文件超时）",
        "未完成（上传失败：连接超时）", "已取消（插件停用或调用方放弃等待）")) {
      assertFalse(SparkDumpCapture.render(ongoing, null, null).contains("未执行"),
          "已启动的分支不得出现「未执行」：" + ongoing);
    }
  }

  // ------------------------------------------------------------------
  // 命令形态
  // ------------------------------------------------------------------

  @Test
  void commandUsesSparkNativeFlagsAndOurMarker() {
    assertEquals(
        "spark profiler start --thread * --interval 10 --timeout 30 --save-to-file --comment " + MARKER,
        SparkDumpCapture.commandLine(MARKER),
        "命令行是上游解析器的输入：flag 顺序可换，但键名与取值必须与 SamplerModule 一致");
  }

  @Test
  void timingConstantsStayWithinThePublicPromise() {
    assertEquals(30, SparkDumpCapture.PROFILE_SECONDS);
    assertEquals(65, SparkDumpCapture.MAX_WAIT_SECONDS);
    assertTrue(SparkDumpCapture.FILE_WAIT_MILLIS >= SparkDumpCapture.PROFILE_SECONDS * 1000L + 5_000L,
        "等待落盘的窗口必须明显长于采样窗口，否则正常采样也会超时");
    assertTrue(SparkDumpCapture.FILE_WAIT_MILLIS < SparkDumpCapture.MAX_WAIT_SECONDS * 1000L,
        "落盘等待 + 上传预算必须落在总时限内");
  }

  // ------------------------------------------------------------------
  // 回执提示（只作提示，不当作结果来源）
  // ------------------------------------------------------------------

  @Test
  void busyHintUsesSparkReplyPhrase() {
    assertTrue(SparkDumpCapture.hintsBusy(List.of("Profiler is already running!")));
    assertFalse(SparkDumpCapture.hintsBusy(List.of("Profiler stopped & save complete!")));
    assertFalse(SparkDumpCapture.hintsBusy(List.of()));
    assertFalse(SparkDumpCapture.hintsBusy(null));
  }

  @Test
  void disabledHintUsesSparkReplyPhrase() {
    assertTrue(SparkDumpCapture.hintsDisabled(List.of("The spark profiler is currently disabled.")));
    assertTrue(SparkDumpCapture.hintsDisabled(List.of("The spark profiler will not be enabled.")));
    assertFalse(SparkDumpCapture.hintsDisabled(List.of("Starting a new profiler, please wait...")));
  }

  // ------------------------------------------------------------------
  // 目录候选
  // ------------------------------------------------------------------

  @Test
  void candidateDirectoriesPrefersPluginFolderThenDedupes() {
    Path plugins = Path.of("E:/srv/plugins");
    List<Path> deduped = SparkDumpCapture.candidateDirectories(plugins.resolve("spark"), plugins);
    assertEquals(1, deduped.size(), "独立 spark 插件目录与内置 <plugins>/spark 相同时不能重复扫描");
    assertEquals(plugins.toAbsolutePath().normalize().resolve("spark"), deduped.get(0));

    List<Path> two = SparkDumpCapture.candidateDirectories(Path.of("E:/srv/plugins/spark-custom"), plugins);
    assertEquals(2, two.size());
    assertEquals(Path.of("E:/srv/plugins/spark-custom").toAbsolutePath().normalize(), two.get(0));
    assertTrue(two.get(1).endsWith("spark"));

    assertTrue(SparkDumpCapture.candidateDirectories(null, null).isEmpty());
  }

  // ------------------------------------------------------------------
  // 端点与 key 白名单
  // ------------------------------------------------------------------

  @Test
  void endpointsDefaultToOfficialAndOnlyAcceptHttps() {
    SparkDumpCapture.Endpoints defaults = SparkDumpCapture.resolveEndpoints(null, null);
    assertEquals("https://spark-usercontent.lucko.me/post", defaults.uploadUrl());
    assertEquals("https://spark.lucko.me/", defaults.viewerUrl());

    assertTrue(defaults.uploadUrl().endsWith("/post"), "bytebin 的 POST 路径固定为 /post");
    assertEquals("https://b.example.org/", SparkDumpCapture.normalizeBase("https://b.example.org", "x"));
    assertEquals("https://b.example.org/p/", SparkDumpCapture.normalizeBase(" https://b.example.org/p ", "x"));
    assertEquals("fallback", SparkDumpCapture.normalizeBase("http://b.example.org/", "fallback"));
    assertEquals("fallback", SparkDumpCapture.normalizeBase("https://user:pw@b.example.org/", "fallback"));
    assertEquals("fallback", SparkDumpCapture.normalizeBase("https://b.example.org/?a=1", "fallback"));
    assertEquals("fallback", SparkDumpCapture.normalizeBase("file:///etc/passwd", "fallback"));
    assertEquals("fallback", SparkDumpCapture.normalizeBase("not a url", "fallback"));
  }

  @Test
  void configJsonIsReadOnlyForSimpleStringValues() {
    String json = "{\"bytebinUrl\":\"https://b.example.org/\",\"viewerUrl\":\"https://v.example.org/\"}";
    assertEquals("https://b.example.org/", SparkDumpCapture.jsonString(json, "bytebinUrl"));
    assertEquals("https://v.example.org/", SparkDumpCapture.jsonString(json, "viewerUrl"));
    assertNull(SparkDumpCapture.jsonString(json, "missing"));
    assertNull(SparkDumpCapture.jsonString(null, "bytebinUrl"));
    assertNull(SparkDumpCapture.jsonString("{\"bytebinUrl\":\"https:\\/\\/b\"}", "bytebinUrl"),
        "带转义的值一律不认，避免把奇怪内容带进 URL");
  }

  @Test
  void keyWhitelistBlocksInjectedUrlsAndHeaders() {
    assertEquals(Optional.of("abc123DEF"), SparkDumpCapture.keyFromLocation("abc123DEF"));
    assertEquals(Optional.of("abc123DEF"), SparkDumpCapture.keyFromLocation("/abc123DEF"));
    assertEquals(Optional.of("abc123DEF"), SparkDumpCapture.keyFromLocation("  abc123DEF  "));

    for (String rejected : List.of("", " ", "abc", "ab", "abc/def", "abc?x=1",
        "https://evil.example/abc123", "abc123\r\nSet-Cookie: x=1", "abc123 def",
        "a".repeat(65))) {
      assertTrue(SparkDumpCapture.keyFromLocation(rejected).isEmpty(), "必须拒绝：" + rejected);
    }
    assertTrue(SparkDumpCapture.keyFromLocation(null).isEmpty());
  }

  @Test
  void linkIsViewerUrlPlusKey() {
    assertEquals("https://spark.lucko.me/abc123DEF",
        SparkDumpCapture.link("https://spark.lucko.me/", "abc123DEF"));
  }

  // ------------------------------------------------------------------
  // 生命周期：fail-soft、幂等、可重入
  // ------------------------------------------------------------------

  @Test
  void startWithoutPluginOrServerFailsSoftImmediately() {
    SparkDumpCapture capture = new SparkDumpCapture(null);

    String text = capture.start().toCompletableFuture().join();

    assertTrue(text.startsWith("\n---- Spark 采样 ----\n"));
    assertTrue(text.contains("状态：未执行"), "没有服务端时必须明确写「未执行」，调用方据此收回窗口");
    assertTrue(text.endsWith("\n"));
    capture.close();
  }

  @Test
  void startIsIdempotentAndReturnsTheSameStage() {
    SparkDumpCapture capture = new SparkDumpCapture(null);

    assertSame(capture.start(), capture.start(), "重复调用必须复用同一 stage，不能起第二次采样");
    capture.close();
  }

  @Test
  void closeIsIdempotentAndSettlesOnce() {
    SparkDumpCapture capture = new SparkDumpCapture(null);

    capture.close();
    capture.close();
    capture.close();

    String text = capture.start().toCompletableFuture().join();
    assertTrue(text.contains("已取消"), "关闭后 start() 只能返回已取消，绝不会去碰 Spark");
  }

  @Test
  void availableIsFalseWithoutServer() {
    assertFalse(SparkDumpCapture.available(), "无服务端时必须判为不可用（零 IO 探测不允许抛）");
  }

  @Test
  void claimOnceAllowsAtMostOneUploadPath() throws Exception {
    AtomicBoolean gate = new AtomicBoolean();
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch startLine = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    try {
      List<Future<?>> racing = new ArrayList<>(threads);
      for (int index = 0; index < threads; index++) {
        racing.add(pool.submit(() -> {
          try {
            startLine.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
          }
          if (SparkDumpCapture.claimOnce(gate)) {
            winners.incrementAndGet();
          }
          return null;
        }));
      }
      startLine.countDown();
      for (Future<?> future : racing) {
        future.get(5, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, winners.get(), "并发收尾时只允许一个路径进入上传");
    assertFalse(SparkDumpCapture.claimOnce(gate), "已落定后的下一拍轮询不得开始第二次上传");
    assertFalse(SparkDumpCapture.claimOnce(null), "空闸门按「不可占」处理，绝不当作成功");
  }

  @Test
  void describeNeverLeaksStackOrNewlines() {
    assertEquals("未知原因", SparkDumpCapture.describe(null));
    assertEquals("a b", SparkDumpCapture.describe(new IllegalStateException("a\nb")));
    assertEquals("boom", SparkDumpCapture.describe(new RuntimeException("boom")));
    assertEquals("IllegalStateException",
        SparkDumpCapture.describe(new IllegalStateException()));
    assertTrue(SparkDumpCapture.describe(new RuntimeException("x".repeat(500))).length() <= 120);
  }
}
