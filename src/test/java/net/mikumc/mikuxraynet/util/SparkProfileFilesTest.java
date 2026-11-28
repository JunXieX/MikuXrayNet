package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spark 采样文件识别器的不变量：<b>归属只认 metadata.comment 逐字节相等</b>，结构解析严格有界。
 *
 * <p>这里的 fixture 按 spark 仓库 {@code spark_sampler.proto} 的真实字段号/线类型手工编码
 * （{@code SamplerData.metadata = 1}、{@code SamplerMetadata.comment = 6}、{@code start_time = 2}），
 * 因此不是在测「我们自造的形状」，而是在测「真实 protobuf 布局下的匹配与拒绝」。
 *
 * <p>反例同样重要：标记出现在线程名/类名/更长注释里都不算相关（防止把「简单字符串包含」写成归属判据），
 * 截断、超大声明长度、组线类型、软链接、超量目录都必须被拒。
 */
class SparkProfileFilesTest {

  private static final String MARKER = "mxnet-0123456789abcdef0123456789abcdef";

  @TempDir
  Path temp;

  // ------------------------------------------------------------------
  // 真实 schema fixture
  // ------------------------------------------------------------------

  @Test
  void readsCommentAndStartTimeFromRealSchemaFixture() {
    byte[] profile = sampler(
        metadata(
            lengthDelimited(1, stringField(1, "Console")),       // creator
            varintField(2, 1_700_000_000_000L),                   // start_time
            varintField(3, 10L),                                  // interval
            lengthDelimited(4, varintField(1, 0L)),               // thread_dumper(ALL)
            lengthDelimited(5, varintField(1, 0L)),               // data_aggregator
            stringField(6, MARKER),                               // comment
            lengthDelimited(7, stringField(1, "Paper"))));        // platform_metadata

    Optional<SparkProfileFiles.Header> header =
        SparkProfileFiles.parseHeader(profile, profile.length, profile.length);

    assertTrue(header.isPresent(), "真实结构的采样文件必须能被解析");
    assertEquals(MARKER, header.get().comment());
    assertEquals(1_700_000_000_000L, header.get().startTimeMillis());
  }

  @Test
  void readsMarkerAndStartTimeBeforeLargeMetadataFields() {
    byte[] profile = largeMetadataProfile(MARKER);
    byte[] prefix = Arrays.copyOf(profile, SparkProfileFiles.MAX_HEADER_BYTES);
    assertTrue(profile.length > prefix.length, "fixture 的元数据必须明显超过 64 KiB 读取上限");

    Optional<SparkProfileFiles.Header> header =
        SparkProfileFiles.parseHeader(prefix, prefix.length, profile.length);

    assertTrue(header.isPresent(), "comment 已完整读到时，后续大字段被前缀上限截断不影响解析");
    assertEquals(MARKER, header.get().comment());
    assertEquals(1_700_000_000_000L, header.get().startTimeMillis());
  }

  @Test
  void truncatedLargeMetadataIsRejectedEvenWhenCommentIsReadable() {
    byte[] profile = largeMetadataProfile(MARKER);
    byte[] prefix = Arrays.copyOf(profile, SparkProfileFiles.MAX_HEADER_BYTES);

    assertTrue(SparkProfileFiles.parseHeader(prefix, prefix.length, profile.length - 1L).isEmpty(),
        "metadataEnd 超过真实文件大小时仍是真实截断，必须拒绝");
  }

  @Test
  void largeMetadataFileIsOwnedOnlyByExactMarker() throws IOException {
    Path ours = write("profile-2026-11-28_09.42.18.sparkprofile", largeMetadataProfile(MARKER));
    Path other = write("profile-2026-11-28_09.42.19.sparkprofile",
        largeMetadataProfile("mxnet-ffffffffffffffffffffffffffffffff"));

    SparkProfileFiles.Checked owned = SparkProfileFiles.verifyOwnedProfile(List.of(this.temp), ours, MARKER);
    assertTrue(owned.ok(), "完整落盘的大 metadata 文件必须能被本次 marker 认领");
    assertEquals(ours, owned.path());
    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(this.temp), other, MARKER)
        .reason().contains("注释"), "不同 marker 仍必须拒绝");
  }

  @Test
  void findsOnlyFileWhoseCommentEqualsMarker() throws IOException {
    Path ours = write("profile-2026-11-20_17.30.00.sparkprofile", profileWithComment(MARKER));
    write("profile-2026-11-20_17.30.01.sparkprofile", profileWithComment("mxnet-ffffffffffffffffffffffffffffffff"));

    Optional<Path> found = SparkProfileFiles.findOwned(this.temp, MARKER);

    assertTrue(found.isPresent(), "同目录里必须只认 comment 相等的那个文件");
    assertEquals(ours, found.get());
  }

  @Test
  void markerInThreadDataIsNotACorrelation() throws IOException {
    // 元数据完全没有 comment，但采样数据里（线程名）出现同一个标记串
    byte[] profile = sampler(
        metadata(varintField(2, 1L), varintField(3, 10L)),
        lengthDelimited(2, lengthDelimited(1, MARKER.getBytes(StandardCharsets.UTF_8))));
    write("profile-2026-11-20_17.31.00.sparkprofile", profile);

    assertTrue(SparkProfileFiles.findOwned(this.temp, MARKER).isEmpty(),
        "标记出现在线程/类数据里不算相关：归属只看 metadata.comment");
  }

  @Test
  void commentMustMatchExactlyNotAsPrefixOrSubstring() throws IOException {
    write("profile-2026-11-20_17.32.00.sparkprofile", profileWithComment(MARKER + "-suffix"));
    write("profile-2026-11-20_17.32.01.sparkprofile", profileWithComment("prefix-" + MARKER));
    write("profile-2026-11-20_17.32.02.sparkprofile", profileWithComment(MARKER.toLowerCase().replace('a', 'b')));

    assertTrue(SparkProfileFiles.findOwned(this.temp, MARKER).isEmpty(),
        "前缀/后缀/大小写不同的注释都不是本次采样");
  }

  @Test
  void sessionFromAnotherCaptureIsExcluded() throws IOException {
    String otherMarker = "mxnet-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    assertNotEquals(MARKER, otherMarker);
    write("profile-2026-11-20_17.33.00.sparkprofile", profileWithComment(otherMarker));

    assertTrue(SparkProfileFiles.findOwned(this.temp, MARKER).isEmpty(),
        "别的采样会话（不同标记）不能被当成本次文件");
  }

  @Test
  void nonAsciiCommentNeverMatchesAsciiMarker() {
    byte[] profile = sampler(metadata(stringField(6, "本次采样")));
    Optional<SparkProfileFiles.Header> header =
        SparkProfileFiles.parseHeader(profile, profile.length, profile.length);

    assertTrue(header.isPresent());
    assertFalse(header.get().matches(MARKER));
  }

  // ------------------------------------------------------------------
  // 严格有界的拒绝路径
  // ------------------------------------------------------------------

  @Test
  void truncatedMetadataLengthIsRejected() {
    byte[] metadata = metadata(varintField(3, 10L));
    byte[] head = concat(tag(1, 2), varint(metadata.length + 100L), metadata);

    assertTrue(SparkProfileFiles.parseHeader(head, head.length, head.length).isEmpty(),
        "声明的元数据长度超过真实文件时必须放弃，而不是按声明长度分配/读取");
  }

  @Test
  void hugeDeclaredLengthIsRejectedWithoutAllocating() {
    byte[] head = concat(tag(1, 2), varint(0x7FFFFFFFL), new byte[] {0x08, 0x01});

    assertTrue(SparkProfileFiles.parseHeader(head, head.length, head.length).isEmpty());
  }

  @Test
  void groupWireTypeIsRejected() {
    byte[] meta = concat(tag(3, 3), tag(3, 4));
    byte[] head = lengthDelimited(1, meta);

    assertTrue(SparkProfileFiles.parseHeader(head, head.length, head.length).isEmpty(),
        "组线类型（3/4）不该出现在采样元数据里：一律放弃");
  }

  @Test
  void wrongTopLevelFieldIsRejected() {
    byte[] head = lengthDelimited(2, stringField(1, MARKER));

    assertTrue(SparkProfileFiles.parseHeader(head, head.length, head.length).isEmpty(),
        "顶层第一个字段不是 metadata 就不是 Spark 采样文件");
  }

  @Test
  void unknownFixedWidthFieldsAreSkippedButCommentStillFound() {
    byte[] meta = concat(
        tag(3, 5), new byte[] {1, 2, 3, 4},          // 未知 fixed32
        tag(4, 1), new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, // 未知 fixed64
        stringField(6, MARKER));
    byte[] head = lengthDelimited(1, meta);

    Optional<SparkProfileFiles.Header> header =
        SparkProfileFiles.parseHeader(head, head.length, head.length);

    assertTrue(header.isPresent(), "未知的定长字段必须被跳过，而不是直接判定文件损坏");
    assertEquals(MARKER, header.get().comment());
  }

  @Test
  void emptyAndGarbageFilesAreRejected() throws IOException {
    Path empty = write("profile-2026-11-20_17.34.00.sparkprofile", new byte[0]);
    Path garbage = write("profile-2026-11-20_17.34.01.sparkprofile",
        "not a protobuf at all".getBytes(StandardCharsets.UTF_8));

    assertTrue(SparkProfileFiles.readHeader(empty).isEmpty());
    assertTrue(SparkProfileFiles.readHeader(garbage).isEmpty());
    assertTrue(SparkProfileFiles.findOwned(this.temp, MARKER).isEmpty());
  }

  @Test
  void oversizedAndZeroLengthFilesAreSkipped() throws IOException {
    Path huge = this.temp.resolve("profile-2026-11-20_17.35.00.sparkprofile");
    try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
      file.setLength(SparkProfileFiles.MAX_PROFILE_BYTES + 1L);
    }
    Path zero = write("profile-2026-11-20_17.35.01.sparkprofile", new byte[0]);

    assertFalse(SparkProfileFiles.acceptableSize(Files.size(huge)));
    assertFalse(SparkProfileFiles.acceptableSize(0L));
    assertTrue(SparkProfileFiles.acceptableSize(1L));
    assertTrue(SparkProfileFiles.acceptableSize(SparkProfileFiles.MAX_PROFILE_BYTES));
    assertTrue(SparkProfileFiles.readHeader(huge).isEmpty());
    assertTrue(SparkProfileFiles.readHeader(zero).isEmpty());
  }

  @Test
  void symbolicLinkIsNotFollowed() throws IOException {
    Path real = this.temp.resolve("profile-2026-11-20_17.36.00.sparkprofile");
    Files.write(real, profileWithComment(MARKER));
    Path link = this.temp.resolve("profile-2026-11-20_17.36.01.sparkprofile");
    try {
      Files.createSymbolicLink(link, real.getFileName());
    } catch (IOException | UnsupportedOperationException unsupported) {
      Assumptions.assumeTrue(false, "本机不支持创建符号链接，跳过");
    }

    Optional<Path> found = SparkProfileFiles.findOwned(this.temp, MARKER);

    assertTrue(found.isPresent(), "真文件仍应被认领");
    assertEquals(real, found.get(), "认领的必须是真文件，而不是指向它的软链接");
  }

  @Test
  void scanHonoursEntryLimitAndRejectsUnboundedCaps() throws IOException {
    write("profile-2026-11-20_17.37.00.sparkprofile", profileWithComment(MARKER));

    assertTrue(SparkProfileFiles.findOwned(this.temp, MARKER, 0).isEmpty(),
        "条目上限为 0 时不得扫描");
    assertTrue(SparkProfileFiles.findOwned(this.temp, MARKER, -5).isEmpty());
    assertTrue(SparkProfileFiles.findOwned(this.temp, MARKER, SparkProfileFiles.MAX_SCAN_ENTRIES).isPresent());
  }

  @Test
  void crowdedDirectoryStaysUsableAndBounded() throws IOException {
    for (int index = 0; index < 300; index++) {
      write("profile-2026-11-20_17.38." + String.format("%02d", index % 60) + "-" + index + ".sparkprofile",
          profileWithComment("mxnet-" + String.format("%032x", index)));
    }
    Path ours = write("profile-2026-11-20_17.39.00.sparkprofile", profileWithComment(MARKER));

    Optional<Path> found = SparkProfileFiles.findOwned(this.temp, MARKER);

    assertTrue(found.isPresent(), "条目多也要能找到本次文件（上限内）");
    assertEquals(ours, found.get());
  }

  @Test
  void candidatesUseModificationWindowAndNeverBecomeTheCorrelation() throws IOException {
    Path old = write("profile-2026-11-20_17.41.00.sparkprofile", profileWithComment(MARKER));
    // 修改时间必须落到「窗口下界 500_000 之下」：实现是 modified < minModifiedMillis 才跳过，
    // 取 1_000_000 会落在窗内（用例期望与实现相反），取 100 才是真正早于窗口的历史文件
    Files.setLastModifiedTime(old, FileTime.fromMillis(100L));
    Path fresh = write("profile-2026-11-20_17.41.01.sparkprofile", profileWithComment(MARKER));

    List<Path> inWindow = SparkProfileFiles.candidates(this.temp, 500_000L, 8).stream()
        .map(SparkProfileFiles.Candidate::path)
        .toList();

    assertEquals(List.of(fresh), inWindow, "扫描窗口只接受本次时间窗内修改过的文件");
    assertTrue(SparkProfileFiles.matches(old, MARKER),
        "修改时间只是扫描过滤器：内容匹配与否仍只看 metadata.comment");
    assertEquals(1, SparkProfileFiles.candidates(this.temp, Long.MIN_VALUE, 1).size(),
        "候选上限必须生效");
    assertTrue(SparkProfileFiles.candidates(this.temp, Long.MIN_VALUE, 0).isEmpty());
    assertTrue(SparkProfileFiles.candidates(null, Long.MIN_VALUE, 8).isEmpty());
  }

  @Test
  void isInsideRejectsEscapes() throws IOException {
    Path inner = write("profile-2026-11-20_17.40.00.sparkprofile", profileWithComment(MARKER));
    Path outside = Files.createDirectories(this.temp.getParent().resolve("spark-outside"))
        .resolve("other.txt");
    Files.writeString(outside, "x", StandardCharsets.UTF_8);

    assertTrue(SparkProfileFiles.isInside(this.temp, inner));
    assertFalse(SparkProfileFiles.isInside(this.temp, outside));
    assertFalse(SparkProfileFiles.isInside(this.temp, this.temp));
    assertFalse(SparkProfileFiles.isInside(null, inner));
  }

  // ------------------------------------------------------------------
  // 标记与文件名
  // ------------------------------------------------------------------

  @Test
  void markersAreRandomWellFormedAndCommandSafe() {
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < 200; index++) {
      String marker = SparkProfileFiles.newMarker();
      assertTrue(SparkProfileFiles.isMarker(marker), "生成的标记必须自证合法：" + marker);
      assertTrue(marker.startsWith("mxnet-"));
      assertEquals(38, marker.length());
      assertTrue(seen.add(marker), "标记不得重复");
    }
  }

  @Test
  void markerValidationRejectsUnsafeInput() {
    assertFalse(SparkProfileFiles.isMarker(null));
    assertFalse(SparkProfileFiles.isMarker(""));
    assertFalse(SparkProfileFiles.isMarker("short"));
    assertFalse(SparkProfileFiles.isMarker("mxnet-0123 4567"));
    assertFalse(SparkProfileFiles.isMarker("mxnet-0123\n4567"));
    assertFalse(SparkProfileFiles.isMarker("--comment"));
    assertTrue(SparkProfileFiles.isMarker(MARKER));
  }

  @Test
  void profileNameMatchesOnlySparkNaming() {
    assertTrue(SparkProfileFiles.isProfileName("profile-2026-11-20_17.30.00.sparkprofile"));
    assertFalse(SparkProfileFiles.isProfileName("profile-.sparkprofile"));
    assertFalse(SparkProfileFiles.isProfileName("profile-2026.sparkprofile.tmp"));
    assertFalse(SparkProfileFiles.isProfileName("heapsummary-2026.sparkheap"));
    assertFalse(SparkProfileFiles.isProfileName("dump-2026.txt"));
    assertFalse(SparkProfileFiles.isProfileName("profile-2026 11 20.sparkprofile"));
    assertFalse(SparkProfileFiles.isProfileName("profile-" + "a".repeat(200) + ".sparkprofile"));
    assertFalse(SparkProfileFiles.isProfileName(null));
  }

  // ------------------------------------------------------------------
  // spark 自己的账本：activity.json
  // ------------------------------------------------------------------

  /** 真实文件形状（来自隔离靶 plugins/spark/activity.json 的原样结构：美化数组、最新在前）。 */
  private static final String ACTIVITY_FIXTURE = """
      [
        {
          "user": {
            "type": "other",
            "name": "FeedbackForwardingSender"
          },
          "time": 1795266253098,
          "type": "Profiler",
          "data": {
            "type": "file",
            "value": "plugins\\\\spark\\\\profile-2026-11-21_21.04.13.sparkprofile"
          }
        },
        {
          "user": {
            "type": "other",
            "name": "Rcon"
          },
          "time": 1795266085032,
          "type": "Profiler",
          "data": {
            "type": "file",
            "value": "plugins\\\\spark\\\\profile-2026-11-21_21.01.24.sparkprofile"
          }
        }
      ]
      """;

  @Test
  void activityLedgerParsesTheRealSparkFormat() {
    List<SparkProfileFiles.ActivityEntry> entries =
        SparkProfileFiles.parseActivity(ACTIVITY_FIXTURE, 8);

    assertEquals(2, entries.size(), "两条 Profiler 落盘记录都要能读出来");
    assertEquals(1795266253098L, entries.get(0).timeMillis(), "账本按时间降序：最新那条在最前");
    assertEquals("plugins\\spark\\profile-2026-11-21_21.04.13.sparkprofile", entries.get(0).value(),
        "JSON 里的 \\\\ 要还原成单个反斜杠，否则拼不出真实路径");
    assertEquals("Profiler", entries.get(0).type());
    assertEquals("file", entries.get(0).dataType());
  }

  @Test
  void activityLedgerAlsoAcceptsOneObjectPerLineAndFiltersOtherTypes() {
    String json = String.join("\n",
        ledgerJson(1_700_000_000_000L, "plugins\\spark\\a.sparkprofile"),
        "{\"time\":1700000000500,\"type\":\"Command\",\"data\":{\"type\":\"file\",\"value\":\"x\"}}",
        "{\"time\":1700000001000,\"type\":\"Profiler\",\"data\":{\"type\":\"misc\",\"value\":\"y\"}}",
        ledgerJson(1_700_000_002_000L, "plugins\\spark\\b.sparkprofile"));

    List<SparkProfileFiles.ActivityEntry> entries = SparkProfileFiles.parseActivity(json, 8);

    assertEquals(2, entries.size(), "只认 Profiler + data.type=file");
    assertEquals("plugins\\spark\\b.sparkprofile", entries.get(0).value());
  }

  @Test
  void activityLedgerToleratesTruncatedHeadAndGarbage() {
    String truncated = ACTIVITY_FIXTURE.substring(17);

    assertFalse(SparkProfileFiles.parseActivity(truncated, 8).isEmpty(),
        "读尾部可能从半个对象开始：从下一个 { 继续，不能整份放弃");
    assertTrue(SparkProfileFiles.parseActivity("{{{{", 8).isEmpty(), "垃圾内容不得变成条目");
    assertTrue(SparkProfileFiles.parseActivity("", 8).isEmpty());
    assertTrue(SparkProfileFiles.parseActivity(null, 8).isEmpty());
    assertTrue(SparkProfileFiles.parseActivity(ACTIVITY_FIXTURE, 0).isEmpty(), "上限为 0 不得解析");
    assertEquals(1, SparkProfileFiles.parseActivity(ACTIVITY_FIXTURE, 1).size(), "条数上限必须生效");
  }

  @Test
  void activityLedgerReadsOnlyTheTailOfAHugeFile() throws IOException {
    StringBuilder builder = new StringBuilder(400_000);
    builder.append('[');
    for (int index = 0; index < 4_000; index++) {
      builder.append(ledgerJson(1_600_000_000_000L + index, "plugins\\spark\\old-" + index + ".sparkprofile"))
          .append(',');
    }
    builder.append(ledgerJson(1_795_266_253_098L, "plugins\\spark\\profile-2026-11-21_21.04.13.sparkprofile"));
    builder.append(']');
    Path ledger = this.temp.resolve(SparkProfileFiles.ACTIVITY_FILE);
    Files.writeString(ledger, builder.toString(), StandardCharsets.UTF_8);
    assertTrue(Files.size(ledger) > SparkProfileFiles.MAX_ACTIVITY_BYTES,
        "fixture 必须大于尾部读取上限，才能证明「只读尾部」也拿得到最新条目");

    List<SparkProfileFiles.ActivityEntry> entries = SparkProfileFiles.readActivity(ledger, 4);

    assertEquals(1_795_266_253_098L, entries.get(0).timeMillis());
    assertEquals("plugins\\spark\\profile-2026-11-21_21.04.13.sparkprofile", entries.get(0).value());
  }

  @Test
  void activityLedgerIsReadOnlyAndNeverFollowsLinks() throws IOException {
    Path ledger = this.temp.resolve(SparkProfileFiles.ACTIVITY_FILE);
    Files.writeString(ledger, ACTIVITY_FIXTURE, StandardCharsets.UTF_8);

    assertEquals(2, SparkProfileFiles.readActivity(ledger, 8).size());
    assertTrue(SparkProfileFiles.readActivity(this.temp.resolve("missing.json"), 8).isEmpty());
    assertTrue(SparkProfileFiles.readActivity(null, 8).isEmpty());
    assertTrue(SparkProfileFiles.readActivity(ledger, 0).isEmpty());

    Path link = this.temp.resolve("activity-link.json");
    try {
      Files.createSymbolicLink(link, ledger.getFileName());
    } catch (IOException | UnsupportedOperationException unsupported) {
      Assumptions.assumeTrue(false, "本机不支持创建符号链接，跳过");
    }
    assertTrue(SparkProfileFiles.readActivity(link, 8).isEmpty(), "软链接不跟随（可能是别人指向别处的手柄）");
  }

  @Test
  void activityPathResolvesAgainstTheServerRoot(@TempDir Path root) {
    Path expectedRelative = root.resolve("plugins/spark/a.sparkprofile").toAbsolutePath().normalize();

    assertEquals(expectedRelative,
        SparkProfileFiles.activityPath("plugins\\spark\\a.sparkprofile", root).orElseThrow(),
        "账本里的反斜杠要能落成真实路径（Windows 原文，POSIX 上同样要解析得出）");
    assertEquals(expectedRelative,
        SparkProfileFiles.activityPath("./plugins/spark/a.sparkprofile", root).orElseThrow(),
        "前导 ./ 要归一");

    // 「绝对路径不被服务器根改写」必须用本平台真正绝对的路径来断言：@TempDir 一定是绝对路径。
    // 不能写死 C:/… 或 D:/…——在 POSIX 上它们只是普通相对路径，那是在测平台假设而不是契约。
    Path absolute = root.resolve("elsewhere").resolve("a.sparkprofile").toAbsolutePath().normalize();
    assertEquals(absolute,
        SparkProfileFiles.activityPath(absolute.toString(), root).orElseThrow(),
        "绝对路径原样保留");

    // 反过来把平台语义钉住：盘符风格串在 POSIX 上不是绝对路径 → 按服务器根解析（生产如此，正确）
    Path driveLike = Path.of("D:/elsewhere/a.sparkprofile");
    if (!driveLike.isAbsolute()) {
      assertEquals(root.resolve(driveLike).toAbsolutePath().normalize(),
          SparkProfileFiles.activityPath("D:\\elsewhere\\a.sparkprofile", root).orElseThrow(),
          "POSIX 上 D:/… 是相对路径，必须按服务器根解析");
    }

    assertTrue(SparkProfileFiles.activityPath("plugins\\spark\\a.sparkprofile", null).isEmpty(),
        "相对路径拿不到服务器根就无法解析");
    assertTrue(SparkProfileFiles.activityPath("", root).isEmpty());
    assertTrue(SparkProfileFiles.activityPath(null, root).isEmpty());
    assertTrue(SparkProfileFiles.activityPath("plugins\\spark\\a\u0000.sparkprofile", root).isEmpty(),
        "控制字符一律拒绝");
    assertTrue(SparkProfileFiles.activityPath("a".repeat(600), root).isEmpty(), "过长的值直接丢");
  }

  @Test
  void ledgerEntryResolvesToTheVerifiableProfile(@TempDir Path root) throws IOException {
    Path sparkDir = Files.createDirectories(root.resolve("plugins").resolve("spark"));
    Path profile = sparkDir.resolve("profile-2026-11-21_21.04.13.sparkprofile");
    Files.write(profile, profileWithComment(MARKER));
    Path ledger = sparkDir.resolve(SparkProfileFiles.ACTIVITY_FILE);
    Files.writeString(ledger, "[" + ledgerJson(1_795_266_253_098L,
        "plugins\\spark\\profile-2026-11-21_21.04.13.sparkprofile") + "]", StandardCharsets.UTF_8);

    SparkProfileFiles.ActivityEntry entry = SparkProfileFiles.readActivity(ledger, 4).get(0);
    Path resolved = SparkProfileFiles.activityPath(entry.value(), root).orElseThrow();

    assertEquals(profile, resolved);
    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), resolved, MARKER).ok(),
        "账本给的路径 + 四道校验 = 本次文件");
  }

  // ------------------------------------------------------------------
  // 四道校验
  // ------------------------------------------------------------------

  @Test
  void everyVerificationGateReportsItsOwnReason(@TempDir Path sparkDir) throws IOException {
    Path good = sparkDir.resolve("profile-2026-11-21_21.00.00.sparkprofile");
    Files.write(good, profileWithComment(MARKER));
    SparkProfileFiles.Checked pass = SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), good, MARKER);
    assertTrue(pass.ok(), "四道全过才算本次文件");
    assertEquals(good, pass.path());
    assertNull(pass.reason());

    Path foreignName = sparkDir.resolve("notes.txt");
    Files.write(foreignName, profileWithComment(MARKER));
    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), foreignName, MARKER)
        .reason().contains("文件名"));

    Path empty = sparkDir.resolve("profile-2026-11-21_21.00.01.sparkprofile");
    Files.write(empty, new byte[0]);
    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), empty, MARKER)
        .reason().contains("文件为空"));

    Path huge = sparkDir.resolve("profile-2026-11-21_21.00.02.sparkprofile");
    try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
      file.setLength(SparkProfileFiles.MAX_PROFILE_BYTES + 1L);
    }
    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), huge, MARKER)
        .reason().contains("超出大小上限"), "被上限拒必须说清原因（dump 里不再静默）");

    Path outside = Files.createDirectories(sparkDir.getParent().resolve("spark-outside-verify"))
        .resolve("profile-2026-11-21_21.00.03.sparkprofile");
    Files.write(outside, profileWithComment(MARKER));
    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), outside, MARKER)
        .reason().contains("不在 Spark 数据目录内"));

    Path otherSession = sparkDir.resolve("profile-2026-11-21_21.00.04.sparkprofile");
    Files.write(otherSession, profileWithComment("mxnet-ffffffffffffffffffffffffffffffff"));
    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), otherSession, MARKER)
        .reason().contains("注释"));

    Path missing = sparkDir.resolve("profile-2026-11-21_21.00.05.sparkprofile");
    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), missing, MARKER)
        .reason().contains("不存在"));

    assertFalse(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), null, MARKER).ok());
    assertFalse(SparkProfileFiles.verifyOwnedProfile(null, good, null).ok(), "没有标记就没有归属");
  }

  @Test
  void symlinkedProfileIsRejectedEvenInsideTheDirectory(@TempDir Path sparkDir) throws IOException {
    Path real = sparkDir.resolve("profile-2026-11-21_21.00.06.sparkprofile");
    Files.write(real, profileWithComment(MARKER));
    Path link = sparkDir.resolve("profile-2026-11-21_21.00.07.sparkprofile");
    try {
      Files.createSymbolicLink(link, real.getFileName());
    } catch (IOException | UnsupportedOperationException unsupported) {
      Assumptions.assumeTrue(false, "本机不支持创建符号链接，跳过");
    }

    assertTrue(SparkProfileFiles.verifyOwnedProfile(List.of(sparkDir), link, MARKER)
        .reason().contains("符号链接"));
  }

  // ------------------------------------------------------------------
  // 阈值、去重键与诊断清单
  // ------------------------------------------------------------------

  @Test
  void profileLimitIsSixtyFourMiB() {
    assertEquals(64L * 1024 * 1024, SparkProfileFiles.MAX_PROFILE_BYTES);
    assertEquals(64L, SparkProfileFiles.maxProfileMegabytes());
    assertTrue(SparkProfileFiles.acceptableSize(SparkProfileFiles.MAX_PROFILE_BYTES));
    assertFalse(SparkProfileFiles.acceptableSize(SparkProfileFiles.MAX_PROFILE_BYTES + 1L));
  }

  @Test
  void dedupeKeyIncludesSizeSoHalfWrittenFilesAreNotStuck() {
    Path file = this.temp.resolve("profile-2026-11-21_21.00.08.sparkprofile");
    String key = SparkProfileFiles.dedupeKey(new SparkProfileFiles.Candidate(file, 7L, 12L));

    assertEquals("profile-2026-11-21_21.00.08.sparkprofile@7@12", key);
    assertNotEquals(key, SparkProfileFiles.dedupeKey(new SparkProfileFiles.Candidate(file, 7L, 13L)),
        "同一文件长齐后（字节数变了）必须换一个键，否则半写状态被永久跳过");
    assertNotEquals(key, SparkProfileFiles.dedupeKey(new SparkProfileFiles.Candidate(file, 8L, 12L)),
        "修改时间变了也要换键");
    assertEquals("", SparkProfileFiles.dedupeKey(null));
  }

  @Test
  void recentProfilesReportOversizedFilesForDiagnostics() throws IOException {
    Path huge = this.temp.resolve("profile-2026-11-21_21.00.09.sparkprofile");
    try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
      file.setLength(SparkProfileFiles.MAX_PROFILE_BYTES + 1L);
    }
    Path normal = write("profile-2026-11-21_21.00.10.sparkprofile", profileWithComment(MARKER));

    List<SparkProfileFiles.Entry> entries = SparkProfileFiles.recentProfiles(this.temp, Long.MIN_VALUE, 5);

    assertEquals(2, entries.size(), "诊断清单不做大小过滤：超上限的文件也必须报出来");
    assertTrue(entries.stream().anyMatch(entry -> entry.path().equals(huge)
        && entry.size() == SparkProfileFiles.MAX_PROFILE_BYTES + 1L));
    assertTrue(entries.stream().anyMatch(entry -> entry.path().equals(normal)));
    assertTrue(SparkProfileFiles.recentProfiles(null, Long.MIN_VALUE, 5).isEmpty());
    assertTrue(SparkProfileFiles.recentProfiles(this.temp, Long.MIN_VALUE, 0).isEmpty());
  }

  @Test
  void candidatesAreReturnedNewestFirst() throws IOException {
    Path oldest = write("profile-2026-11-21_20.00.00.sparkprofile", profileWithComment(MARKER));
    Files.setLastModifiedTime(oldest, FileTime.fromMillis(1_000L));
    Path newest = write("profile-2026-11-21_20.00.01.sparkprofile", profileWithComment(MARKER));
    Files.setLastModifiedTime(newest, FileTime.fromMillis(3_000L));
    Path middle = write("profile-2026-11-21_20.00.02.sparkprofile", profileWithComment(MARKER));
    Files.setLastModifiedTime(middle, FileTime.fromMillis(2_000L));

    List<Path> order = SparkProfileFiles.candidates(this.temp, Long.MIN_VALUE, 8).stream()
        .map(SparkProfileFiles.Candidate::path)
        .toList();

    assertEquals(List.of(newest, middle, oldest), order, "候选按修改时间降序：前 N 个才是「最近的」");
    assertEquals(List.of(newest),
        SparkProfileFiles.candidates(this.temp, Long.MIN_VALUE, 1).stream()
            .map(SparkProfileFiles.Candidate::path)
            .toList(),
        "截断发生在排序之后，否则「取前 8 个」拿到的会是目录枚举顺序");
  }

  @Test
  void stampOfIsNullForMissingOrLinkedFiles(@TempDir Path sparkDir) throws IOException {
    assertNull(SparkProfileFiles.stampOf(null));
    assertNull(SparkProfileFiles.stampOf(sparkDir.resolve("missing.json")));

    Path ledger = sparkDir.resolve(SparkProfileFiles.ACTIVITY_FILE);
    Files.writeString(ledger, ACTIVITY_FIXTURE, StandardCharsets.UTF_8);
    SparkProfileFiles.Stamp stamp = SparkProfileFiles.stampOf(ledger);
    assertEquals(Files.size(ledger), stamp.size());

    Path link = sparkDir.resolve("activity-link.json");
    try {
      Files.createSymbolicLink(link, ledger.getFileName());
    } catch (IOException | UnsupportedOperationException unsupported) {
      Assumptions.assumeTrue(false, "本机不支持创建符号链接，跳过");
    }
    assertNull(SparkProfileFiles.stampOf(link), "软链接不当作账本");
  }

  /** 一条 activity.json 记录的 JSON 文本（转义由这里统一处理，避免各处手写反斜杠）。 */
  private static String ledgerJson(long time, String rawPath) {
    return "{\"time\":" + time + ",\"type\":\"Profiler\",\"data\":{\"type\":\"file\",\"value\":\""
        + rawPath.replace("\\", "\\\\") + "\"}}";
  }

  // ------------------------------------------------------------------
  // fixture 工具
  // ------------------------------------------------------------------

  private Path write(String name, byte[] content) throws IOException {
    Path file = this.temp.resolve(name);
    Files.write(file, content);
    return file;
  }

  private static byte[] profileWithComment(String comment) {
    return sampler(metadata(
        varintField(2, 1_700_000_000_000L),
        varintField(3, 10L),
        stringField(6, comment)));
  }

  private static byte[] largeMetadataProfile(String comment) {
    return sampler(metadata(
        varintField(2, 1_700_000_000_000L),
        varintField(3, 10L),
        stringField(6, comment),
        lengthDelimited(7, stringField(1, "p".repeat(40_000))),
        lengthDelimited(13, concat(
            stringField(1, "spark"),
            lengthDelimited(2, stringField(1, "s".repeat(40_000)))))));
  }

  private static byte[] sampler(byte[]... fields) {
    return lengthDelimited(1, concat(fields));
  }

  private static byte[] metadata(byte[]... fields) {
    return concat(fields);
  }

  private static byte[] stringField(int field, String value) {
    return lengthDelimited(field, value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] varintField(int field, long value) {
    return concat(tag(field, 0), varint(value));
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
