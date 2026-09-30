package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 压缩方案一致性回归：写入必须按「文件头偏移 9 声明的方案」，而不是全局
 * {@link BufferedLinearV3Format#currentCompression()}。
 *
 * <p><b>缺陷</b>：一个以 Deflate（头=0x01）创建的区域文件，在 zstd 变得可用后追加写会用 zstd 压缩、
 * 头部却仍是 0x01；读回时按 0x01 走 Inflater 解压失败、整个 bucket 被当作空，<b>刚写入的条目静默丢失</b>
 * （与 {@link BufferedLinearV3Format} 类注释「头部方案字节与实际写入的压缩算法始终一致」直接矛盾）。
 *
 * <p><b>做法</b>：手工拼出「头=Deflate + 全零偏移表」的区域文件（等价于「创建时 zstd 不可用」留下的
 * 历史文件），在 zstd 可用的进程里写入并读回；断言读回成功、且头部方案字节始终保持 0x01。
 */
class RegionFileSchemeConsistencyTest {

  private static final int SEED = BufferedLinearV3Format.DEFAULT_HASH_SEED;

  /** 固定内容的条目（负载非空，否则不会落盘）。 */
  private static BufferedLinearV3Format.Entry entry(long writtenAt, int marker) {
    byte[] payload = new byte[512];
    new Random(marker).nextBytes(payload);
    return new BufferedLinearV3Format.Entry(0L, writtenAt, marker, payload);
  }

  /** 手工构造「头=Deflate、偏移表全零」的区域文件（模拟创建时 zstd 不可用）。 */
  private static void writeDeflateHeaderOnly(Path path) throws Exception {
    ByteBuffer buffer = ByteBuffer.allocate((int) BufferedLinearV3Format.DATA_AREA_OFFSET);
    buffer.put(BufferedLinearV3Format.encodeHeader(SEED, BufferedLinearV3Format.COMPRESSION_DEFLATE));
    buffer.put(BufferedLinearV3Format.encodePosTable(new long[BufferedLinearV3Format.BUCKET_COUNT]));
    Files.createDirectories(path.getParent());
    Files.write(path, buffer.array());
  }

  /** 文件头偏移 9 的压缩方案字节。 */
  private static byte headerScheme(Path path) throws Exception {
    return Files.readAllBytes(path)[9];
  }

  /**
   * 主回归：头=Deflate 的文件在「zstd 可用」的进程里写入后，读回必须成功（写入沿用头部的 Deflate），
   * 且头部方案字节始终不变。旧实现会写出 zstd 数据而头部仍是 0x01 → 读回整桶当空。
   */
  @Test
  void deflateFileStaysReadableWhenZstdIsAvailable(@TempDir Path dir) throws Exception {
    // 前置：本测试进程里 zstd 可用（run.ps1 类路径已含 zstd-jni），否则无法暴露「写入改用 zstd」的错配
    assertTrue(ZstdSupport.available(),
        "测试环境应带 zstd-jni；否则本用例失去意义");

    Path path = dir.resolve("r.0.0.b_linear");
    writeDeflateHeaderOnly(path);

    try (RegionFile region = RegionFile.open(path, 4)) {
      region.put(BufferedLinearV3Format.chunkIndex(0, 0), entry(1L, 7));
      assertTrue(region.flushDirty(), "应发生落盘");
    }
    assertEquals(BufferedLinearV3Format.COMPRESSION_DEFLATE, headerScheme(path),
        "写入后头部方案字节必须仍是 0x01（不得被 currentCompression 改写成 zstd）");

    try (RegionFile reopened = RegionFile.open(path, 4)) {
      BufferedLinearV3Format.Entry read = reopened.get(BufferedLinearV3Format.chunkIndex(0, 0));
      assertNotNull(read, "刚写入的条目必须能读回（旧实现因头/数据错配而整桶当空）");
      assertArrayEquals(entry(1L, 7).payload(), read.payload());
    }
  }

  /**
   * 跨「方案切换」回归：头=Deflate 的文件被多次打开并追加写入、再压缩回收，头部方案与可读性都必须保持。
   * 这正是「Deflate 创建 → zstd 变可用 → 再次追加写」的真机时序。
   */
  @Test
  void deflateFileKeepsSchemeAcrossReopenAppendsAndCompact(@TempDir Path dir) throws Exception {
    Path path = dir.resolve("r.0.0.b_linear");
    writeDeflateHeaderOnly(path);

    try (RegionFile region = RegionFile.open(path, 1)) {
      region.put(BufferedLinearV3Format.chunkIndex(0, 0), entry(1L, 11)); // bucket 0
      region.flushDirty();
    }
    try (RegionFile region = RegionFile.open(path, 1)) {
      region.put(BufferedLinearV3Format.chunkIndex(0, 2), entry(2L, 22)); // chunkIndex 64 → bucket 1
      region.flushDirty();
      region.compact(null); // 压缩回收也必须沿用声明方案、不改变头部
    }
    assertEquals(BufferedLinearV3Format.COMPRESSION_DEFLATE, headerScheme(path),
        "跨多次打开/追加/压缩回收后，头部方案字节必须仍为 0x01");

    try (RegionFile reopened = RegionFile.open(path, 4)) {
      assertNotNull(reopened.get(BufferedLinearV3Format.chunkIndex(0, 0)), "第一次写入的条目必须仍可读");
      assertNotNull(reopened.get(BufferedLinearV3Format.chunkIndex(0, 2)), "第二次追加的条目必须仍可读");
      assertArrayEquals(entry(1L, 11).payload(),
          reopened.get(BufferedLinearV3Format.chunkIndex(0, 0)).payload());
      assertArrayEquals(entry(2L, 22).payload(),
          reopened.get(BufferedLinearV3Format.chunkIndex(0, 2)).payload());
    }
  }
}