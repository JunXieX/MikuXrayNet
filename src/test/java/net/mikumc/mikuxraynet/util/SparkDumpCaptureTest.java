package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SparkDumpCapture} 的对外口径与纯函数部分：返回段格式、命令形态、端点收敛、回执四态与快速失败、
 * 目录扫描（去重与排序）、自诊断渲染、生命周期（close/start 的幂等与 fail-soft）。
 *
 * <p>真正「跑起来」的路径需要服务端与 Spark 实例，离线只能覆盖这些决定性环节——它们恰好也是最容易被
 * 后续改动悄悄破坏的部分（返回段格式被调用方整段拼进 dump、key 白名单挡住 URL 注入、Folia 判据决定
 * 是否值得等 30 秒、半写文件被永久跳过）。行为证据另见 {@code SparkProfileFilesTest} 与真机冒烟记录。
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
        "未执行（Spark 连一句话都没回，命令可能没有进入 spark）",
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
        "未完成（上传失败：服务端返回 500）",
        "未完成（本次采样文件超出上限：78643201 字节 > 64 MiB，未上传）")) {
      String text = SparkDumpCapture.render(state, null, "说明占位");
      assertTrue(text.contains("窗口：30 秒"), "已启动分支必须保留 30 秒口径：" + state);
      assertTrue(text.contains("全部线程"), "已启动分支必须保留采样范围说明：" + state);
      assertFalse(text.contains("窗口：未采样"), "已启动分支不得写成未采样：" + state);
    }
  }

  @Test
  void windowLineNeverClaimsSparkWillSaveOnItsOwn() {
    // P5：超时分支曾同时出现「由 Spark 到期自动落盘」与「未完成」，自相矛盾
    String text = SparkDumpCapture.render("未完成（等待 Spark 生成采样文件超时）", null, null);

    assertTrue(text.contains("落盘由 Spark 侧完成"), "只陈述采样范围，不承诺「已经落盘」");
    assertFalse(text.contains("自动落盘"), "不得再出现「到期自动落盘」这种与失败矛盾的措辞");
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
        "未完成（上传失败：连接超时）", "未完成（Spark 自报失败：An error occurred whilst saving the data.）",
        "已取消（插件停用或调用方放弃等待）")) {
      assertFalse(SparkDumpCapture.render(ongoing, null, null).contains("未执行"),
          "已启动的分支不得出现「未执行」：" + ongoing);
    }
  }

  // ------------------------------------------------------------------
  // 自诊断渲染
  // ------------------------------------------------------------------

  @Test
  void diagnosticsAreRenderedBoundedAndUrlFree() {
    List<String> details = new ArrayList<>();
    for (int index = 0; index < 60; index++) {
      details.add("第 " + index + " 行 " + "x".repeat(400) + " https://spark.lucko.me/SECRETKEY");
    }

    String text = SparkDumpCapture.render("未完成（等待 Spark 生成采样文件超时）", null, "说明占位", details);

    assertTrue(text.contains("诊断："), "失败分支必须带自诊断");
    assertFalse(text.contains("SECRETKEY"), "回执/诊断里不得带出任何链接");
    assertFalse(text.contains("https://"), "自诊断行里不允许出现 URL");
    long rows = Arrays.stream(text.split("\n")).filter(line -> line.startsWith("  ")).count();
    assertTrue(rows <= 24, "诊断行数必须有界（实际 " + rows + "）");
    for (String line : text.split("\n")) {
      assertTrue(line.length() <= 202, "单行必须有界：" + line.length());
    }
  }

  @Test
  void diagnosticsAreAbsentWhenThereIsNothingToExplain() {
    assertFalse(SparkDumpCapture.render("已上传", "https://spark.lucko.me/abc123", "说明占位").contains("诊断："),
        "成功分支不该被诊断刷屏");
    assertFalse(SparkDumpCapture.render("已上传", null, null, List.of()).contains("诊断："));
    assertFalse(SparkDumpCapture.render("已上传", null, null, null).contains("诊断："));
  }

  @Test
  void receiptLinesAreSanitisedToOneShortLine() {
    assertEquals("Profiler is now running!", SparkDumpCapture.receiptLine("Profiler is now running!"));
    assertEquals("Data has been written to: (URL)", SparkDumpCapture.receiptLine(
        "Data has been written to: https://spark.lucko.me/abcdef"));
    assertEquals("plain", SparkDumpCapture.receiptLine("§cplain"), "旧式颜色码要被剥掉");
    assertEquals("plain", SparkDumpCapture.receiptLine("<gray>plain</gray>"), "MiniMessage 标签要被剥掉");
    assertEquals("a b", SparkDumpCapture.receiptLine("a\n\tb"), "控制字符压成空格（单行）");
    assertEquals("", SparkDumpCapture.receiptLine(null));
    assertTrue(SparkDumpCapture.receiptLine("y".repeat(500)).length() <= 200);
  }

  // ------------------------------------------------------------------
  // P0：回执通道只能靠强引用（源码级守门，防回归成局部变量）
  // ------------------------------------------------------------------

  @Test
  void commandSenderIsHeldByAVolatileField() throws Exception {
    Field field = SparkDumpCapture.class.getDeclaredField("dispatchSender");

    assertEquals(CommandSender.class, field.getType());
    assertTrue(Modifier.isVolatile(field.getModifiers()),
        "sender 必须是字段（volatile）：spark 只持弱引用，局部变量出栈后 30 秒的完成/落盘/报错回执就收不到了");
    assertFalse(Modifier.isStatic(field.getModifiers()), "每次采样各持自己的 sender");
  }

  // ------------------------------------------------------------------
  // 命令形态与时限
  // ------------------------------------------------------------------

  @Test
  void commandUsesSparkNativeFlagsAndOurMarker() {
    assertEquals(
        "spark profiler start --thread * --interval 10 --timeout 30 --save-to-file --comment " + MARKER,
        SparkDumpCapture.commandLine(MARKER),
        "命令行是上游解析器的输入：flag 顺序可换，但键名与取值必须与 SamplerModule 一致");
  }

  @Test
  void fileWaitIsDecoupledFromTheSamplingWindow() {
    assertEquals(30, SparkDumpCapture.PROFILE_SECONDS);
    assertEquals(90_000L, SparkDumpCapture.FILE_WAIT_MILLIS, "落盘窗口 = 采样 30 秒 + 宽限 60 秒");
    assertEquals(110, SparkDumpCapture.MAX_WAIT_SECONDS, "总时限 = 落盘窗口 + 上传预算 20 秒");
    assertTrue(SparkDumpCapture.FILE_WAIT_MILLIS >= SparkDumpCapture.PROFILE_SECONDS * 1000L + 60_000L,
        "落盘宽限必须与 --timeout 解耦：finalize 时长没有上界，不能只留 15 秒");
    assertTrue(SparkDumpCapture.FILE_WAIT_MILLIS < SparkDumpCapture.MAX_WAIT_SECONDS * 1000L,
        "落盘等待 + 上传预算必须落在总时限内");
  }

  // ------------------------------------------------------------------
  // 回执四态与快速失败（只作提示，不当作结果来源）
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

  @Test
  void classifyHintCoversTheFourStatesPlusSparkReportedFailure() {
    assertEquals(SparkDumpCapture.HINT_DISABLED,
        SparkDumpCapture.classifyHint(List.of("The spark profiler is currently disabled.")));
    assertEquals(SparkDumpCapture.HINT_BUSY,
        SparkDumpCapture.classifyHint(List.of("Profiler is already running!")));
    assertEquals(SparkDumpCapture.HINT_STARTED,
        SparkDumpCapture.classifyHint(List.of("Profiler is now running! (engine: Java)")));
    assertEquals(SparkDumpCapture.HINT_STARTED,
        SparkDumpCapture.classifyHint(List.of("Starting a new profiler, please wait...")));
    assertEquals(SparkDumpCapture.HINT_SILENT, SparkDumpCapture.classifyHint(List.of()));
    assertEquals(SparkDumpCapture.HINT_SILENT, SparkDumpCapture.classifyHint(null));
    assertEquals(SparkDumpCapture.HINT_SILENT,
        SparkDumpCapture.classifyHint(List.of("The active profiler has completed!")));

    // 自报失败优先于「已启动」：跑了但明确失败了，没必要继续等文件
    assertEquals(SparkDumpCapture.HINT_ERROR, SparkDumpCapture.classifyHint(List.of(
        "Profiler is now running!",
        "An error occurred whilst saving the data.")));

    // 「某项能力不支持」只是提示：采样已经在跑时不得据此收尾
    assertEquals(SparkDumpCapture.HINT_STARTED, SparkDumpCapture.classifyHint(List.of(
        "Profiler is now running!",
        "Native hooks are not supported on your system!")));
  }

  @Test
  void sparkReportedFailuresAreRecognisedByPhrase() {
    for (String phrase : List.of(
        "An error occurred whilst saving the data.",
        "An error occurred whilst uploading the data.",
        "A profiler operation failed unexpectedly.",
        "The profiler is not supported on your system!")) {
      assertTrue(SparkDumpCapture.sparkError(List.of(phrase)).isPresent(), "必须识别：" + phrase);
    }
    assertFalse(SparkDumpCapture.sparkError(List.of("Profiler is now running!")).isPresent());
    assertFalse(SparkDumpCapture.sparkError(List.of()).isPresent());
    assertFalse(SparkDumpCapture.sparkError(null).isPresent());
    // 已在跑 + 「能力不支持」提示 ≠ 本次失败（否则会把一次正常采样直接丢掉）
    assertFalse(SparkDumpCapture.sparkError(List.of("Profiler is now running!",
        "Native hooks are not supported on your system!")).isPresent());
    // 已在跑 + 保存失败 = 真失败
    assertTrue(SparkDumpCapture.sparkError(List.of("Profiler is now running!",
        "An error occurred whilst saving the data.")).isPresent());
    assertEquals("An error occurred whilst saving the data.",
        SparkDumpCapture.sparkError(List.of("Profiler is now running!", "An error occurred whilst saving the data."))
            .orElse(null),
        "返回命中的那一行原文，供状态行复述");
  }

  @Test
  void onlyTheWrittenReceiptYieldsAPath() {
    assertEquals("plugins\\spark\\profile-2026-11-21_21.04.13.sparkprofile",
        SparkDumpCapture.writtenPathFrom(
            "Data has been written to: plugins\\spark\\profile-2026-11-21_21.04.13.sparkprofile"));
    assertEquals("plugins/spark/x.sparkprofile",
        SparkDumpCapture.writtenPathFrom("data has been written to:   plugins/spark/x.sparkprofile  "),
        "大小写不敏感、去掉首尾空白");
    assertNull(SparkDumpCapture.writtenPathFrom("Profiler is now running!"));
    assertNull(SparkDumpCapture.writtenPathFrom("Data has been written to:   "));
    assertNull(SparkDumpCapture.writtenPathFrom(null));
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
  // 目录扫描：半写文件可重试、拥挤目录仍拿到本次那个、时间窗生效
  // ------------------------------------------------------------------

  @Test
  void halfWrittenProfileIsRetriedOnceItCompletes(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("profile-2026-11-21_21.00.00.sparkprofile");
    byte[] full = samplerWithComment(MARKER);
    long startedAt = System.currentTimeMillis();
    Files.write(file, Arrays.copyOf(full, 8));

    Set<String> inspected = ConcurrentHashMap.newKeySet();

    assertNull(SparkDumpCapture.scanProfileDirectories(List.of(directory), MARKER, startedAt, inspected, 8, 256),
        "只写了开头的文件这一轮认不出来");
    assertTrue(inspected.isEmpty(),
        "本次会话内写出的半写文件不得登记去重键：下一轮长齐后还要再试");

    Files.write(file, full);

    assertEquals(file,
        SparkDumpCapture.scanProfileDirectories(List.of(directory), MARKER, startedAt, inspected, 8, 256),
        "文件长齐后必须能被认领（去重键里带字节数，mtime 没变也照样重读）");
  }

  @Test
  void stableForeignProfilesAreNotReReadEveryRound(@TempDir Path directory) throws IOException {
    Path stale = directory.resolve("profile-2026-11-21_19.00.00.sparkprofile");
    Files.write(stale, "not a protobuf".getBytes(StandardCharsets.UTF_8));
    // 落在扫描窗口内（startedAt - 120 秒之上）、但早于本次开始：属于「稳定的无关文件」
    Files.setLastModifiedTime(stale, FileTime.fromMillis(150_000L));
    Set<String> inspected = ConcurrentHashMap.newKeySet();

    assertNull(SparkDumpCapture.scanProfileDirectories(List.of(directory), MARKER, 200_000L, inspected, 8, 256));
    assertEquals(1, inspected.size(), "早于本次开始且读不出头的文件登记一次，避免每轮重读");
  }

  @Test
  void scanPicksTheNewestProfileEvenWhenTheDirectoryIsCrowded(@TempDir Path directory) throws IOException {
    long base = System.currentTimeMillis() - 60_000L;
    for (int index = 0; index < 11; index++) {
      Path other = directory.resolve("profile-2026-11-21_20.5" + index + ".00.sparkprofile");
      Files.write(other, samplerWithComment("mxnet-" + String.format("%032x", index)));
      Files.setLastModifiedTime(other, FileTime.fromMillis(base + index * 1_000L));
    }
    Path ours = directory.resolve("profile-2026-11-21_21.00.00.sparkprofile");
    Files.write(ours, samplerWithComment(MARKER));
    Files.setLastModifiedTime(ours, FileTime.fromMillis(base + 60_000L));

    assertEquals(ours,
        SparkDumpCapture.scanProfileDirectories(List.of(directory), MARKER, base + 59_000L,
            ConcurrentHashMap.newKeySet(), 8, 256),
        "历史文件多于候选上限时也必须拿到本次那个：候选按 mtime 降序取前 8");
  }

  @Test
  void scanHonoursTheStartWindowAndTheMarker(@TempDir Path directory) throws IOException {
    Path old = directory.resolve("profile-2026-11-21_19.00.00.sparkprofile");
    Files.write(old, samplerWithComment(MARKER));
    Files.setLastModifiedTime(old, FileTime.fromMillis(100_000L));
    Path foreign = directory.resolve("profile-2026-11-21_21.00.01.sparkprofile");
    Files.write(foreign, samplerWithComment("mxnet-ffffffffffffffffffffffffffffffff"));

    assertNull(SparkDumpCapture.scanProfileDirectories(List.of(directory), MARKER,
            System.currentTimeMillis(), ConcurrentHashMap.newKeySet(), 8, 256),
        "早于本次开始两分钟以上的文件不参与；注释不等的文件更不算本次");
    assertNull(SparkDumpCapture.scanProfileDirectories(null, MARKER, 1L,
        ConcurrentHashMap.newKeySet(), 8, 256));
    assertNull(SparkDumpCapture.scanProfileDirectories(List.of(directory), MARKER,
        System.currentTimeMillis(), null, 8, 256));
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

  // ------------------------------------------------------------------
  // fixture：按 spark_sampler.proto 的真实字段号/线类型手工编码
  // ------------------------------------------------------------------

  private static byte[] samplerWithComment(String comment) {
    byte[] metadata = concat(
        tag(2, 0), varint(1_700_000_000_000L),
        tag(3, 0), varint(10L),
        lengthDelimited(6, comment.getBytes(StandardCharsets.UTF_8)));
    return lengthDelimited(1, metadata);
  }

  private static byte[] lengthDelimited(int field, byte[] payload) {
    return concat(tag(field, 2), varint(payload.length), payload);
  }

  private static byte[] tag(int field, int wireType) {
    return varint(((long) field << 3) | wireType);
  }

  private static byte[] varint(long value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(10);
    long remaining = value;
    while ((remaining & ~0x7FL) != 0L) {
      out.write((int) ((remaining & 0x7F) | 0x80));
      remaining >>>= 7;
    }
    out.write((int) remaining);
    return out.toByteArray();
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) {
      length += part.length;
    }
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, result, offset, part.length);
      offset += part.length;
    }
    return result;
  }
}
