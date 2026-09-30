package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 区域文件的健壮性加固回归：
 * <ul>
 *   <li><b>伪造桶头钳制</b>：声明远超整文件大小的 {@code rawLength} 必须在分配解压缓冲之前被拒；</li>
 *   <li><b>读路径轻量清理</b>：{@code clear} 不得为清一个槽位把未加载的整桶解码进内存。</li>
 * </ul>
 */
class RegionFileHardeningTest {

  /** bucket 下标 b 的槽位序号（bucketIndex = chunkIndex >>> BUCKET_SHIFT）。 */
  private static int chunkIndexForBucket(int bucket) {
    return bucket << 6;
  }

  private static BufferedLinearV3Format.Entry entry(int marker) {
    return new BufferedLinearV3Format.Entry(0L, System.currentTimeMillis(), marker,
        new byte[] {(byte) marker, 2, 3});
  }

  /** 测试专用 Deflater（与历史 0x01 文件一致）。 */
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

  @SuppressWarnings("unchecked")
  private static Map<Integer, Boolean> loadedOf(RegionFile region) throws Exception {
    Field field = RegionFile.class.getDeclaredField("loaded");
    field.setAccessible(true);
    return (Map<Integer, Boolean>) field.get(region);
  }

  private static boolean rawLengthWithinFileBudget(RegionFile region, int rawLength)
      throws Exception {
    Method method = RegionFile.class.getDeclaredMethod("rawLengthWithinFileBudget", int.class);
    method.setAccessible(true);
    return (boolean) method.invoke(region, rawLength);
  }

  /**
   * 桶头声明的 rawLength 相对文件大小的钳制：远超整文件大小的声明必须被拒，合理范围必须放行。
   *
   * <p>这正是「约 16 KB 的压缩数据声明 128 MiB raw、16 桶叠加约 2 GiB 分配尖峰」的洞：压缩长度
   * 16384 恰好满足 {@code 8192 × 16384 = 128 MiB} 的压缩比闸门，因此只能靠「与文件大小挂钩」的钳制拦下。
   */
  @Test
  void rawLengthClampRejectsDeclarationFarExceedingFileSize(@TempDir Path dir) throws Exception {
    RegionFile region = RegionFile.open(dir.resolve("r.0.0.b_linear"), 4);
    try {
      assertFalse(rawLengthWithinFileBudget(region, 128 * 1024 * 1024),
          "远大于文件大小的 rawLength 必须被钳制拒绝（该文件此刻还很小）");
      assertTrue(rawLengthWithinFileBudget(region, 4096),
          "落在合理范围内的 rawLength 必须放行（不得误伤合法桶）");
    } finally {
      region.close();
    }
  }

  /**
   * 伪造超大 rawLength 的桶头：读路径必须按「损坏」处理（视为空桶），而不是按其声明分配 128 MiB。
   *
   * <p>做法：手工拼一份「偏移表指向 bucket0，bucket0 头声明 rawLength = 128 MiB、compressedLength = 16384」
   * 的文件；同时放入一个合法的 bucket1 作为对照，断言伪造桶读不到、合法桶照常可读。压缩长度取 16384 是
   * 为了恰好通过压缩比闸门（8192 × 16384 = 128 MiB），从而确证「光靠压缩比闸门拦不住、必须靠文件大小钳制」。
   */
  @Test
  void forgedOversizedBucketHeaderIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
    int seed = BufferedLinearV3Format.DEFAULT_HASH_SEED;
    int forgedRawLength = 128 * 1024 * 1024;
    int forgedCompressedLength = 16384;

    byte[] forgedCompressed = new byte[forgedCompressedLength]; // 内容无关：本应在解压前就被拦下

    BufferedLinearV3Format.Entry[] legitSlots =
        new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
    legitSlots[0] = entry(9);
    byte[] legitCompressed =
        deflate(BufferedLinearV3Format.encodeBucket(legitSlots, seed));

    long bucket0Offset = BufferedLinearV3Format.DATA_AREA_OFFSET;
    long bucket1Offset = bucket0Offset + 8 + forgedCompressedLength;
    long[] positions = new long[BufferedLinearV3Format.BUCKET_COUNT];
    positions[0] = bucket0Offset;
    positions[1] = bucket1Offset;

    byte[] header = BufferedLinearV3Format.encodeHeader(seed);
    header[9] = BufferedLinearV3Format.COMPRESSION_DEFLATE;
    ByteBuffer fileBytes = ByteBuffer.allocate(
        (int) bucket1Offset + 8 + legitCompressed.length);
    fileBytes.put(header);
    fileBytes.put(BufferedLinearV3Format.encodePosTable(positions));
    fileBytes.putInt(forgedRawLength);
    fileBytes.putInt(forgedCompressedLength);
    fileBytes.put(forgedCompressed);
    fileBytes.putInt(BufferedLinearV3Format.encodeBucket(legitSlots, seed).length);
    fileBytes.putInt(legitCompressed.length);
    fileBytes.put(legitCompressed);

    Path file = dir.resolve("r.0.0.b_linear");
    Files.write(file, fileBytes.array());

    RegionFile region = RegionFile.open(file, 4);
    try {
      assertNull(region.get(chunkIndexForBucket(0)),
          "伪造超大 rawLength 的桶必须被当成损坏（视为空），不得按其声明分配解压缓冲");
      BufferedLinearV3Format.Entry loaded = region.get(chunkIndexForBucket(1));
      assertNotNull(loaded, "对照用的合法桶必须照常可读（钳制不得误伤）");
      assertArrayEquals(new byte[] {9, 2, 3}, loaded.payload());
    } finally {
      region.close();
    }
  }

  /**
   * 读路径清理（{@code clear}）不得触发整桶解码：桶未被加载时直接跳过，交由维护期压缩回收兜底。
   *
   * <p>回归：旧实现 {@code clear} 会 {@code ensureLoaded}，若桶已被 LRU 释放，就会为清一个槽位把整桶
   * 64 个条目解码进内存——读路径只担保 50ms 预算，这属于不该有的开销。
   */
  @Test
  void clearOnUnloadedBucketDoesNotForceDecode(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.0.0.b_linear");

    RegionFile writer = RegionFile.open(file, 2);
    try {
      writer.put(chunkIndexForBucket(0), entry(5));
      writer.flushDirty();
    } finally {
      writer.close();
    }

    RegionFile reader = RegionFile.open(file, 2);
    try {
      assertTrue(loadedOf(reader).isEmpty(), "前置：新实例不应预加载任何桶");
      assertFalse(reader.clear(chunkIndexForBucket(0)),
          "未加载的桶不得为清理而整桶解码（应直接返回 false）");
      assertTrue(loadedOf(reader).isEmpty(), "clear 之后仍不得加载任何桶（轻量路径）");
      assertNotNull(reader.get(chunkIndexForBucket(0)),
          "条目仍保留在磁盘上（清理延后到维护期压缩回收）");
    } finally {
      reader.close();
    }
  }
}