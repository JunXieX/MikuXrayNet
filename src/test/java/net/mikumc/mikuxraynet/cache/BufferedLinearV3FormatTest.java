package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * BufferedLinearV3 格式测试：文件头/偏移表/压缩/条目/bucket 的往返一致性，
 * 以及损坏数据的健壮性（校验失败、截断、单槽损坏、条目缺失）——损坏必须被识别而不是当作命中返回。
 *
 * <p>压缩相关：新写入走 zstd（方案字节 0x02），并覆盖空负载 / 大负载 / 不可压缩负载的往返；
 * 同时回归「旧 Deflate（方案字节 0x01）文件仍能被正确读取」，保证用户既有缓存不被误判为损坏。
 */
class BufferedLinearV3FormatTest {

  private static final int SEED = BufferedLinearV3Format.DEFAULT_HASH_SEED;

  private static byte[] payload(int length) {
    byte[] data = new byte[length];
    new Random(length).nextBytes(data);
    return data;
  }

  /** 测试专用的旧格式压缩器：复刻历史实现（JDK Deflater.BEST_SPEED），用于构造 0x01 旧字节。 */
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

  @Test
  void headerRoundTrip() throws IOException {
    byte[] header = BufferedLinearV3Format.encodeHeader(SEED);

    assertEquals(BufferedLinearV3Format.HEADER_SIZE, header.length);
    assertEquals(SEED, BufferedLinearV3Format.decodeHeader(header));
  }

  @Test
  void headerRejectsForeignOrCorruptFiles() {
    byte[] header = BufferedLinearV3Format.encodeHeader(SEED);
    header[0] = (byte) ~header[0];
    assertThrows(IOException.class, () -> BufferedLinearV3Format.decodeHeader(header),
        "魔数不符必须拒绝");

    byte[] badVersion = BufferedLinearV3Format.encodeHeader(SEED);
    badVersion[8] = 0x7f;
    assertThrows(IOException.class, () -> BufferedLinearV3Format.decodeHeader(badVersion),
        "版本不符必须拒绝");

    assertThrows(IOException.class,
        () -> BufferedLinearV3Format.decodeHeader(new byte[] {1, 2, 3}), "长度不足必须拒绝");
  }

  /** 版本字节：新写入必须是 0x04；初版 0x03 必须被明确拒绝（交由调用方删除重建）。 */
  @Test
  void headerCarriesCurrentVersionAndRejectsLegacy() {
    byte[] header = BufferedLinearV3Format.encodeHeader(SEED);
    assertEquals(BufferedLinearV3Format.VERSION, header[8], "新文件版本字节必须是 0x04");
    assertEquals(0x04, BufferedLinearV3Format.VERSION, "版本常量必须是 0x04");

    byte[] legacy = BufferedLinearV3Format.encodeHeader(SEED);
    legacy[8] = 0x03;
    assertThrows(IOException.class, () -> BufferedLinearV3Format.decodeHeaderInfo(legacy),
        "旧版（0x03）区域文件必须被拒绝（负载信封约定已升级）");
  }

  @Test
  void positionTableRoundTrip() throws IOException {
    long[] offsets = new long[BufferedLinearV3Format.BUCKET_COUNT];
    for (int index = 0; index < offsets.length; index++) {
      offsets[index] = index == 3 ? 4096L : 0L;
    }

    long[] decoded = BufferedLinearV3Format.decodePosTable(
        BufferedLinearV3Format.encodePosTable(offsets));

    assertArrayEquals(offsets, decoded);
    assertThrows(IOException.class,
        () -> BufferedLinearV3Format.decodePosTable(new byte[4]), "截断的偏移表必须拒绝");
  }

  @Test
  void compressionRoundTripIsByteIdentical() throws IOException {
    byte[] data = payload(20_000);
    byte[] compressed = BufferedLinearV3Format.compress(data);

    assertArrayEquals(data, BufferedLinearV3Format.decompress(compressed, data.length));
  }

  @Test
  void truncatedCompressedDataIsRejected() {
    byte[] compressed = BufferedLinearV3Format.compress(payload(20_000));
    byte[] truncated = Arrays.copyOf(compressed, compressed.length / 2);

    assertThrows(IOException.class,
        () -> BufferedLinearV3Format.decompress(truncated, 20_000), "截断的压缩数据必须拒绝");
  }

  @Test
  void entryRoundTripIsByteIdentical() throws IOException {
    byte[] data = payload(1_024);
    BufferedLinearV3Format.Entry entry = new BufferedLinearV3Format.Entry(7L, 1_700_000_000_000L, 42,
        data);

    BufferedLinearV3Format.Entry decoded =
        BufferedLinearV3Format.decodeEntry(BufferedLinearV3Format.encodeEntry(entry, SEED), SEED);

    assertEquals(7L, decoded.generation());
    assertEquals(1_700_000_000_000L, decoded.writtenAtMillis());
    assertEquals(42, decoded.configHash());
    assertArrayEquals(data, decoded.payload());
  }

  @Test
  void entryChecksumFailureIsDetected() {
    BufferedLinearV3Format.Entry entry =
        new BufferedLinearV3Format.Entry(1L, 2L, 3, payload(256));
    byte[] encoded = BufferedLinearV3Format.encodeEntry(entry, SEED);
    // 篡改负载最后一个字节：校验和必须能识别
    encoded[encoded.length - 1] ^= 0x5A;

    assertThrows(IOException.class, () -> BufferedLinearV3Format.decodeEntry(encoded, SEED),
        "负载被篡改必须拒绝");
    assertThrows(IOException.class, () -> BufferedLinearV3Format.decodeEntry(encoded, SEED + 1),
        "种子不符导致校验失败也必须拒绝");
  }

  @Test
  void truncatedEntryIsRejected() {
    byte[] encoded = BufferedLinearV3Format.encodeEntry(
        new BufferedLinearV3Format.Entry(1L, 2L, 3, payload(64)), SEED);

    assertThrows(IOException.class,
        () -> BufferedLinearV3Format.decodeEntry(Arrays.copyOf(encoded, encoded.length - 10), SEED));
    assertThrows(IOException.class,
        () -> BufferedLinearV3Format.decodeEntry(new byte[] {1, 2}, SEED));
  }

  @Test
  void bucketRoundTripKeepsSlotLayout() throws IOException {
    BufferedLinearV3Format.Entry[] slots =
        new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
    slots[1] = new BufferedLinearV3Format.Entry(1L, 100L, 5, payload(300));
    slots[63] = new BufferedLinearV3Format.Entry(2L, 200L, 6, payload(700));

    BufferedLinearV3Format.Entry[] decoded = BufferedLinearV3Format.decodeBucket(
        BufferedLinearV3Format.encodeBucket(slots, SEED), SEED);

    assertEquals(BufferedLinearV3Format.BUCKET_SIZE, decoded.length);
    assertNull(decoded[0], "空槽位必须保持为空（条目缺失即未命中）");
    assertNull(decoded[62]);
    assertNotNull(decoded[1]);
    assertNotNull(decoded[63]);
    assertEquals(1L, decoded[1].generation());
    assertEquals(6, decoded[63].configHash());
    assertArrayEquals(payload(300), decoded[1].payload());
    assertArrayEquals(payload(700), decoded[63].payload());
  }

  @Test
  void corruptedSlotOnlyLosesThatSlot() throws IOException {
    BufferedLinearV3Format.Entry[] slots =
        new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
    slots[0] = new BufferedLinearV3Format.Entry(1L, 1L, 1, payload(200));
    slots[1] = new BufferedLinearV3Format.Entry(2L, 2L, 2, payload(200));

    byte[] encoded = BufferedLinearV3Format.encodeBucket(slots, SEED);
    // 篡改第一个槽位的负载（槽位 = 4 字节长度 + 28 字节条目头 + 负载）
    encoded[4 + 28 + 10] ^= 0x7F;

    BufferedLinearV3Format.Entry[] decoded = BufferedLinearV3Format.decodeBucket(encoded, SEED);

    assertNull(decoded[0], "损坏的槽位必须作废（宁可未命中）");
    assertNotNull(decoded[1], "其它槽位不受影响：" + decoded[1]);
    assertArrayEquals(payload(200), decoded[1].payload());
  }

  @Test
  void emptyBucketDecodesToAllEmptySlots() throws IOException {
    BufferedLinearV3Format.Entry[] decoded =
        BufferedLinearV3Format.decodeBucket(BufferedLinearV3Format.encodeBucket(null, SEED), SEED);

    for (BufferedLinearV3Format.Entry entry : decoded) {
      assertNull(entry);
    }
    assertEquals(BufferedLinearV3Format.BUCKET_SIZE, decoded.length);
  }

  @Test
  void chunkIndexMappingMatchesRegionLayout() {
    assertEquals(0, BufferedLinearV3Format.chunkIndex(0, 0));
    assertEquals(31, BufferedLinearV3Format.chunkIndex(31, 0));
    assertEquals(32, BufferedLinearV3Format.chunkIndex(0, 1));
    assertEquals(1023, BufferedLinearV3Format.chunkIndex(31, 31));
    // 负数坐标同样落在 0..1023（region 内部相对坐标）
    assertEquals(BufferedLinearV3Format.chunkIndex(0, 0) + 32,
        BufferedLinearV3Format.chunkIndex(-32, -31));
    assertEquals(0, BufferedLinearV3Format.bucketIndex(0));
    assertEquals(15, BufferedLinearV3Format.bucketIndex(1023));
    assertEquals(1, BufferedLinearV3Format.bucketIndex(64));

    assertEquals(0, BufferedLinearV3Format.regionCoordinate(0));
    assertEquals(0, BufferedLinearV3Format.regionCoordinate(31));
    assertEquals(1, BufferedLinearV3Format.regionCoordinate(32));
    assertEquals(-1, BufferedLinearV3Format.regionCoordinate(-1));
    assertEquals(BufferedLinearV3Format.POS_TABLE_OFFSET + BufferedLinearV3Format.POS_TABLE_SIZE,
        BufferedLinearV3Format.DATA_AREA_OFFSET, "数据区必须紧随偏移表");
  }

  // ------------------------------------------------------------------ 压缩方案（zstd / 旧 Deflate）

  @Test
  void headerAdvertisesZstdAndAcceptsLegacyDeflate() throws IOException {
    // 新写入：方案字节必须是 zstd 0x02，且与实际压缩算法一致
    byte[] header = BufferedLinearV3Format.encodeHeader(SEED);
    assertEquals(BufferedLinearV3Format.COMPRESSION_ZSTD, header[9], "新文件方案字节必须是 0x02(zstd)");
    assertEquals(BufferedLinearV3Format.COMPRESSION_ZSTD, BufferedLinearV3Format.currentCompression());
    assertEquals(BufferedLinearV3Format.COMPRESSION_ZSTD,
        BufferedLinearV3Format.decodeHeaderInfo(header).compression());

    // 旧文件：方案字节 0x01(deflate) 仍必须被接受（否则既有缓存会被误判损坏而删除）
    byte[] legacy = BufferedLinearV3Format.encodeHeader(SEED);
    legacy[9] = BufferedLinearV3Format.COMPRESSION_DEFLATE;
    BufferedLinearV3Format.Header info = BufferedLinearV3Format.decodeHeaderInfo(legacy);
    assertEquals(BufferedLinearV3Format.COMPRESSION_DEFLATE, info.compression());
    assertEquals(SEED, info.hashSeed());

    // 未知方案必须拒绝
    byte[] unknown = BufferedLinearV3Format.encodeHeader(SEED);
    unknown[9] = 0x03;
    assertThrows(IOException.class, () -> BufferedLinearV3Format.decodeHeaderInfo(unknown),
        "未知压缩方案必须拒绝");
  }

  @Test
  void zstdRoundTripForEmptyLargeAndIncompressiblePayloads() throws IOException {
    // 空负载
    assertArrayEquals(new byte[0],
        BufferedLinearV3Format.decompress(BufferedLinearV3Format.compress(new byte[0]), 0,
            BufferedLinearV3Format.COMPRESSION_ZSTD));

    // 大且可压缩的负载
    byte[] large = new byte[1 << 20];
    for (int i = 0; i < large.length; i++) {
      large[i] = (byte) (i % 7);
    }
    assertArrayEquals(large, BufferedLinearV3Format.decompress(
        BufferedLinearV3Format.compress(large), large.length,
        BufferedLinearV3Format.COMPRESSION_ZSTD));

    // 不可压缩的随机负载
    byte[] random = payload(64 * 1024);
    assertArrayEquals(random, BufferedLinearV3Format.decompress(
        BufferedLinearV3Format.compress(random), random.length,
        BufferedLinearV3Format.COMPRESSION_ZSTD));
  }

  @Test
  void legacyDeflateBytesRemainReadable() throws IOException {
    byte[] raw = BufferedLinearV3Format.encodeBucket(
        new BufferedLinearV3Format.Entry[] {
            new BufferedLinearV3Format.Entry(1L, 100L, 5, payload(300))}, SEED);
    byte[] legacyCompressed = deflate(raw);

    // 按旧方案 0x01 必须能解出原始 bucket 字节
    assertArrayEquals(raw, BufferedLinearV3Format.decompress(legacyCompressed, raw.length,
        BufferedLinearV3Format.COMPRESSION_DEFLATE));

    // 用错误的方案（zstd）去解 Deflate 字节必须失败，而不是返回脏数据
    assertThrows(IOException.class, () -> BufferedLinearV3Format.decompress(legacyCompressed,
        raw.length, BufferedLinearV3Format.COMPRESSION_ZSTD), "方案不符必须报错");
  }

  @Test
  void compressionSchemeMismatchIsRejected() {
    byte[] raw = payload(8_000);
    byte[] zstd = BufferedLinearV3Format.compress(raw);
    assertThrows(IOException.class, () -> BufferedLinearV3Format.decompress(zstd, raw.length,
        BufferedLinearV3Format.COMPRESSION_DEFLATE), "把 zstd 当 Deflate 解必须报错");
  }

  /**
   * 体积对比（报告数据，允许在 CI 输出）：同一负载下 zstd 与旧 Deflate 的压缩后长度。
   * 不可压缩随机数据做参照，确认 zstd 在「可压缩」样本上不劣于 Deflate。
   */
  @Test
  void compressionSizeComparisonReport() {
    byte[] compressible = new byte[256 * 1024];
    for (int i = 0; i < compressible.length; i++) {
      compressible[i] = (byte) ((i * 31) % 23);
    }
    byte[] incompressible = payload(256 * 1024);

    byte[] zstdCompressible = BufferedLinearV3Format.compress(compressible);
    byte[] deflateCompressible = deflate(compressible);
    byte[] zstdRandom = BufferedLinearV3Format.compress(incompressible);
    byte[] deflateRandom = deflate(incompressible);

    System.out.println("[体积对比] 可压缩 256KiB：原始=" + compressible.length
        + " zstd=" + zstdCompressible.length + " deflate=" + deflateCompressible.length);
    System.out.println("[体积对比] 随机 256KiB：原始=" + incompressible.length
        + " zstd=" + zstdRandom.length + " deflate=" + deflateRandom.length);

    assertTrue(zstdCompressible.length < compressible.length, "可压缩样本 zstd 必须能压小");
  }
}