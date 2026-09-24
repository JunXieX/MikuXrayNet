package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * BufferedLinearV3 格式测试：文件头/偏移表/压缩/条目/bucket 的往返一致性，
 * 以及损坏数据的健壮性（校验失败、截断、单槽损坏、条目缺失）——损坏必须被识别而不是当作命中返回。
 */
class BufferedLinearV3FormatTest {

  private static final int SEED = BufferedLinearV3Format.DEFAULT_HASH_SEED;

  private static byte[] payload(int length) {
    byte[] data = new byte[length];
    new Random(length).nextBytes(data);
    return data;
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
}