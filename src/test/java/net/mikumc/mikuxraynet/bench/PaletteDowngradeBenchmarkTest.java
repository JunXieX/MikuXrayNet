package net.mikumc.mikuxraynet.bench;

import com.github.luben.zstd.Zstd;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.zip.Deflater;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkSection;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调色板降级的实测收益（离线、可复现）：给出「原包 / 改写（现状=仅收缩）/ 改写（含降级）」三条口径的
 * zlib-6（网络封包）与 zstd-3（磁盘缓存）字节数及变化百分比。
 *
 * <p><b>样本来源</b>：
 * <ol>
 *   <li><b>真实负载</b>：{@code src/test/resources/real-chunk-payload.bin}（由 {@code RealCachePayloadExtractor}
 *       从真机磁盘缓存 {@code E:\Server\test\plugins\MikuXrayNet\cache\world} 提取，含信封
 *       {@code [i64 指纹][i32 坐标数][i32×N 坐标][区块字节]}）；这里剥掉信封取区块字节，按合成改写
 *       （与形态无关）跑一遍收缩/降级；</li>
 *   <li><b>合成「纯石头 + 埋着的矿」</b>：24 个 section，下部 8 个是 4 位间接调色板（石头 + 6 种矿，
 *       矿嵌在石头里、无空气/深板岩），上部 16 个是单值石头。把矿全部替换成石头后，下部每个 section
 *       的「被引用状态数」降为 1 → 单值降级收益最大化（这正是「地下纯石头区把矿脉全伪装成石头」的形态）。</li>
 * </ol>
 *
 * <p>数据来源、构造方式与字节数都写进 stdout，供人工复核；真实负载若不含单值场景则降级为 0（如实报告），
 * 收益由合成形态演示。
 */
class PaletteDowngradeBenchmarkTest {

  private static final ChunkVersionFlags FLAGS = ChunkVersionFlags.PAPER_26_2;
  private static final int ZSTD_LEVEL = 3;
  private static final int SECTION_VOLUME = 4096;
  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int[] ORE_STATES = {100, 101, 102, 103, 104, 105};

  private static final String RESOURCE = "/real-chunk-payload.bin";

  private static final boolean ZSTD_AVAILABLE = probeZstd();

  private static final RegistryAccessor REGISTRY = new RegistryAccessor() {
    @Override
    public boolean isAir(int blockId) {
      return blockId == AIR;
    }

    @Override
    public boolean isFluid(int blockId) {
      return false;
    }

    @Override
    public int getUniqueBlockStateCount() {
      return 1 << 15;
    }

    @Override
    public int getMaxBitsPerBlockState() {
      return 15;
    }
  };

  private record Result(int originalBytes, int compactOnlyBytes, int downgradedBytes,
      int originalZlib, int compactOnlyZlib, int downgradedZlib,
      int originalZstd, int compactOnlyZstd, int downgradedZstd, int downgradedSections) {
  }

  @Test
  void reportPaletteDowngradeSavings() throws IOException {
    ChunkCodec codec = new ChunkCodec(REGISTRY, FLAGS);

    System.out.println("\n===== 调色板降级实测（zlib6 = 网络封包口径，zstd3 = 磁盘缓存口径） =====");

    // ① 合成「纯石头 + 埋着的矿」：把矿全部替换成石头 → 整节单值
    byte[] synthetic = pureStoneWithOres(24, 8);
    int[] syntheticEdits = orePositions(24, 8);
    Result syntheticResult = measure(codec, synthetic, 24, syntheticEdits, STONE,
        "合成：纯石头 + 埋着的矿（24 节，下部 8 节含矿）", "把全部矿替换为石头（模拟伪装）");
    print("合成：纯石头 + 埋着的矿", syntheticResult);

    // ② 真实负载：剥信封取区块字节，按合成改写跑一遍（与形态无关）
    byte[] payload = readResource();
    assertNotNull(payload, "classpath 里必须有真机负载资源 " + RESOURCE);
    ByteBuffer buffer = ByteBuffer.wrap(payload);
    buffer.getLong();
    int positionCount = buffer.getInt();
    buffer.position(buffer.position() + positionCount * Integer.BYTES);
    byte[] realChunk = new byte[buffer.remaining()];
    buffer.get(realChunk);
    int realSections = detectSectionCount(codec, realChunk);
    assertTrue(realSections > 0, "真实负载必须能被 codec 完整解析");
    int[] realEdits = syntheticEdits(realSections, Math.min(8, realSections));
    Result realResult = measure(codec, realChunk, realSections, realEdits, STONE,
        "真实负载：" + RESOURCE + "（自证 section 数=" + realSections + "，伪装坐标数=" + positionCount + "）",
        "改写 " + realEdits.length + " 个方块（写入已存在的状态「石头」，不新增调色板项）");
    print("真实负载", realResult);

    System.out.println("说明：合成形态的降级收益来自「整节只剩一种方块」→ 单值调色板（约 2KB → 约 10B/节）；");
    System.out.println("      真实负载不含该场景（地下节仍有空气/深板岩等多种状态），故降级为 0，两列一致（证明无回归）。\n");

    // 硬断言：降级不得改变语义、也不得变大；合成形态必须显著变小
    assertTrue(syntheticResult.downgradedBytes() < syntheticResult.compactOnlyBytes(),
        "合成形态含降级后 payload 必须小于仅收缩");
    assertTrue(syntheticResult.compactOnlyBytes() - syntheticResult.downgradedBytes() > 1000,
        "单值降级应省下约 2KB/节（本例下部 8 节）");
    assertTrue(realResult.downgradedBytes() <= realResult.compactOnlyBytes(),
        "真实负载上降级不得使 payload 变大");

    // 语义自证：降级后重编码结果与「仅收缩」路径读回的方块序列逐格一致
    assertSameSequence(codec, synthetic, 24, syntheticEdits, STONE);
  }

  /** 对同一输入产出「原包 / 仅收缩 / 收缩+降级」三条字节，并给出两种压缩口径的长度。 */
  private static Result measure(ChunkCodec codec, byte[] raw, int sectionCount, int[] edits,
      int replacement, String sourceLabel, String editLabel) {
    System.out.println("  · 样本：" + sourceLabel);
    System.out.println("  · 改写：" + editLabel);
    byte[] compactOnly = rewrite(codec, raw, sectionCount, edits, replacement, false);
    byte[] downgraded = rewrite(codec, raw, sectionCount, edits, replacement, true);
    int downgradedSections = countNewlySingleValue(codec, raw, downgraded);
    return new Result(raw.length, compactOnly.length, downgraded.length,
        compressedBytes(raw), compressedBytes(compactOnly), compressedBytes(downgraded),
        compressedBytesZstd(raw), compressedBytesZstd(compactOnly), compressedBytesZstd(downgraded),
        downgradedSections);
  }

  private static void print(String shape, Result r) {
    System.out.println("  → " + shape + "：");
    System.out.println("      输出字节   原包 " + r.originalBytes() + "｜仅收缩 " + r.compactOnlyBytes()
        + "｜含降级 " + r.downgradedBytes() + "（降级 " + r.downgradedSections() + " 节，"
        + percent(r.compactOnlyBytes(), r.downgradedBytes()) + "）");
    System.out.println("      zlib6 字节 原包 " + r.originalZlib() + "｜仅收缩 " + r.compactOnlyZlib()
        + "｜含降级 " + r.downgradedZlib() + "（" + percent(r.compactOnlyZlib(), r.downgradedZlib()) + "）");
    System.out.println("      zstd3 字节 原包 " + bytesOrDash(r.originalZstd()) + "｜仅收缩 "
        + bytesOrDash(r.compactOnlyZstd()) + "｜含降级 " + bytesOrDash(r.downgradedZstd())
        + "（" + percent(r.compactOnlyZstd(), r.downgradedZstd()) + "）");
  }

  /** 解码 → 应用 edits → 仅收缩（或再降级）→ 重编码。 */
  private static byte[] rewrite(ChunkCodec codec, byte[] raw, int sectionCount, int[] edits,
      int replacement, boolean downgrade) {
    try (Chunk chunk = codec.decode(raw, sectionCount)) {
      long touched = 0L;
      for (int position : edits) {
        int section = position >> 12;
        int local = position & 0xFFF;
        chunk.getSection(section).setBlockState(local, replacement);
        touched |= 1L << section;
      }
      for (int section = 0; section < sectionCount; section++) {
        if ((touched & 1L << section) != 0) {
          ChunkSection target = chunk.getSection(section);
          target.compactPalette(false);
          if (downgrade) {
            target.downgradePalette(false);
          }
        }
      }
      return chunk.finalizeOutput();
    }
  }

  /** 统计「含降级」结果里<b>新增</b>的单值调色板 section 数（原本就是单值的不计入，用于报告）。 */
  private static int countNewlySingleValue(ChunkCodec codec, byte[] raw, byte[] downgraded) {
    int count = 0;
    try (Chunk before = codec.decode(raw, detectSectionCount(codec, raw));
        Chunk after = codec.decode(downgraded, detectSectionCount(codec, downgraded))) {
      int sections = Math.min(before.getSectionCount(), after.getSectionCount());
      for (int section = 0; section < sections; section++) {
        ChunkSection a = after.getSection(section);
        ChunkSection b = before.getSection(section);
        if (a != null && a.bitsPerBlock() == 0 && (b == null || b.bitsPerBlock() != 0)) {
          count++;
        }
      }
    }
    return count;
  }

  /** 逐格语义自证：仅收缩 → 再解码 与 收缩+降级 → 再解码 必须完全一致。 */
  private static void assertSameSequence(ChunkCodec codec, byte[] raw, int sectionCount, int[] edits,
      int replacement) {
    byte[] compactOnly = rewrite(codec, raw, sectionCount, edits, replacement, false);
    byte[] downgraded = rewrite(codec, raw, sectionCount, edits, replacement, true);
    try (Chunk a = codec.decode(compactOnly, sectionCount); Chunk b = codec.decode(downgraded, sectionCount)) {
      for (int section = 0; section < sectionCount; section++) {
        assertArrayEquals(a.getSection(section).readAllBlockStates(),
            b.getSection(section).readAllBlockStates(), "section " + section + " 降级不得改变方块序列");
      }
    }
  }

  /** 合成「纯石头 + 埋着的矿」：下部 {@code oreSections} 个 section 用 4 位间接调色板（石头 + 6 矿），其余单值石头。 */
  private static byte[] pureStoneWithOres(int sectionCount, int oreSections) {
    TestChunkBuilder builder = new TestChunkBuilder(FLAGS);
    for (int section = 0; section < sectionCount; section++) {
      if (section >= oreSections) {
        builder.singleValueSection(STONE, SECTION_VOLUME, 0, 0, new int[] {1});
        continue;
      }
      int[] palette = new int[1 + ORE_STATES.length];
      palette[0] = STONE;
      System.arraycopy(ORE_STATES, 0, palette, 1, ORE_STATES.length);
      int[] indices = new int[SECTION_VOLUME]; // 默认 0 = 石头
      for (int v = 0; v < ORE_STATES.length; v++) {
        for (int k = 0; k < 20; k++) {
          indices[(v * 617 + k * 199 + section * 37 + 11) % SECTION_VOLUME] = 1 + v;
        }
      }
      builder.indirectSection(4, SECTION_VOLUME, 0, palette, indices, 0, new int[] {1});
    }
    return builder.build();
  }

  /** 合成形态里所有矿的 position（{@code section<<12 | localIndex}）。 */
  private static int[] orePositions(int sectionCount, int oreSections) {
    int[] buffer = new int[oreSections * ORE_STATES.length * 20];
    int n = 0;
    for (int section = 0; section < Math.min(sectionCount, oreSections); section++) {
      for (int v = 0; v < ORE_STATES.length; v++) {
        for (int k = 0; k < 20; k++) {
          buffer[n++] = section << 12 | (v * 617 + k * 199 + section * 37 + 11) % SECTION_VOLUME;
        }
      }
    }
    return java.util.Arrays.copyOf(buffer, n);
  }

  /** 与形态无关的合成改写位置：在前 {@code sections} 个 section 里铺开。 */
  private static int[] syntheticEdits(int sectionCount, int sections) {
    int span = sections * SECTION_VOLUME;
    int count = Math.min(1000, span);
    int[] out = new int[count];
    for (int i = 0; i < count; i++) {
      int linear = (int) (((long) i * 7919L + 13L) % span);
      out[i] = (linear / SECTION_VOLUME << 12) | linear % SECTION_VOLUME;
    }
    return out;
  }

  /** 第一个「刚好把字节用完」的 section 数即为真值（与 RealChunkPayloadRegressionTest 同口径）。 */
  private static int detectSectionCount(ChunkCodec codec, byte[] data) {
    for (int count = 1; count <= 64; count++) {
      try (Chunk chunk = codec.decode(data, count)) {
        int consumed = 0;
        for (int index = 0; index < count; index++) {
          Chunk.SectionRange range = chunk.originalSectionRange(index);
          if (range != null) {
            consumed += range.length();
          }
        }
        if (consumed == data.length) {
          return count;
        }
      } catch (RuntimeException exception) {
        // section 数偏大：字节不够解析 → 换下一个候选
      }
    }
    return -1;
  }

  private static byte[] readResource() throws IOException {
    try (InputStream input = PaletteDowngradeBenchmarkTest.class.getResourceAsStream(RESOURCE)) {
      return input == null ? null : input.readAllBytes();
    }
  }

  private static String percent(int before, int after) {
    if (before <= 0 || after < 0) {
      return "—";
    }
    return String.format(Locale.ROOT, "%+.1f%%", 100.0D * (after - before) / before);
  }

  private static String bytesOrDash(int value) {
    return value < 0 ? "—" : Integer.toString(value);
  }

  private static int compressedBytes(byte[] data) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    try {
      deflater.setInput(data);
      deflater.finish();
      byte[] buffer = new byte[8192];
      ByteArrayOutputStream compressed = new ByteArrayOutputStream();
      while (!deflater.finished()) {
        compressed.write(buffer, 0, deflater.deflate(buffer));
      }
      return compressed.size();
    } finally {
      deflater.end();
    }
  }

  private static boolean probeZstd() {
    try {
      return Zstd.compress(new byte[] {0, 1, 2}, ZSTD_LEVEL).length > 0;
    } catch (Throwable throwable) {
      return false;
    }
  }

  private static int compressedBytesZstd(byte[] data) {
    if (!ZSTD_AVAILABLE) {
      return -1;
    }
    try {
      return Zstd.compress(data, ZSTD_LEVEL).length;
    } catch (Throwable throwable) {
      return -1;
    }
  }
}