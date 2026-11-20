package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
