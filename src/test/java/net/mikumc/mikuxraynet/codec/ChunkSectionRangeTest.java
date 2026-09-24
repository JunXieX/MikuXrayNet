package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 选择性 section 重编码测试：codec 必须能给出每个 section 的原始字节区间，
 * 未改动的 section 原样搬运后与原始字节逐字节一致，改动过的 section 才重编码。
 *
 * <p>判定依据：即使某个 section 被改写，其余 section 在输出中对应区间内的字节必须与输入完全相同
 * （这正是「原样搬运」的直接证据）；并且整块未改动时往返仍必须逐字节一致。
 */
class ChunkSectionRangeTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final ChunkVersionFlags MODERN = ChunkVersionFlags.PAPER_26_2;

  private static RegistryAccessor registry() {
    return new RegistryAccessor() {
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
  }

  private static ChunkCodec codec() {
    return new ChunkCodec(registry(), MODERN);
  }

  private static final int[] MIDDLE_PALETTE = {AIR, 11, 22, 33, 44, 55};

  private static byte[] buildThreeSections() {
    int[] paletteIndices = new int[4096];
    for (int i = 0; i < paletteIndices.length; i++) {
      paletteIndices[i] = i * 5 % MIDDLE_PALETTE.length;
    }
    int blockCount = 0;
    for (int paletteIndex : paletteIndices) {
      if (MIDDLE_PALETTE[paletteIndex] != AIR) {
        blockCount++;
      }
    }

    return new TestChunkBuilder(MODERN)
        .singleValueSection(AIR, 0, 0, 0, new int[] {7})
        .indirectSection(4, blockCount, 0, MIDDLE_PALETTE, paletteIndices, 0, new int[] {1})
        .singleValueSection(STONE, 4096, 0, 0, new int[] {4})
        .build();
  }

  @Test
  void sectionRangesCoverWholeBuffer() {
    byte[] raw = buildThreeSections();

    try (Chunk chunk = codec().decode(raw, 3)) {
      int expectedOffset = 0;
      for (int sectionIndex = 0; sectionIndex < 3; sectionIndex++) {
        Chunk.SectionRange range = chunk.originalSectionRange(sectionIndex);
        assertNotNull(range, "section " + sectionIndex + " 的原始区间必须可用");
        assertEquals(expectedOffset, range.offset(), "各 section 区间必须连续");
        assertTrue(range.length() > 0, "区间长度必须为正");
        expectedOffset += range.length();
      }
      assertEquals(raw.length, expectedOffset, "各 section 区间应正好覆盖整个缓冲区（无剩余字节）");
    }
  }

  @Test
  void unchangedSectionsAreByteIdenticalToSource() {
    byte[] raw = buildThreeSections();
    int[] ranges = new int[6];

    byte[] output;
    try (Chunk chunk = codec().decode(raw, 3)) {
      for (int sectionIndex = 0; sectionIndex < 3; sectionIndex++) {
        assertFalse(chunk.getSection(sectionIndex).isModified(), "刚解码的 section 不应是已改写状态");
        Chunk.SectionRange range = chunk.originalSectionRange(sectionIndex);
        ranges[sectionIndex * 2] = range.offset();
        ranges[sectionIndex * 2 + 1] = range.length();
      }

      // 只改中间 section，且写入调色板内已有的状态（不会升位，因此字节长度不变）
      ChunkSection middle = chunk.getSection(1);
      middle.setBlockState(0, MIDDLE_PALETTE[2]);
      assertTrue(middle.isModified(), "setBlockState 之后必须标记为已修改");

      output = chunk.finalizeOutput();
    }

    assertEquals(raw.length, output.length, "未升位时输出长度应与输入一致");
    assertArrayEquals(slice(raw, ranges[0], ranges[1]), slice(output, ranges[0], ranges[1]),
        "未改动的 section 0 必须原样搬运");
    assertArrayEquals(slice(raw, ranges[4], ranges[5]), slice(output, ranges[4], ranges[5]),
        "未改动的 section 2 必须原样搬运");

    // 改动过的 section 仍必须是合法可解码的
    try (Chunk chunk = codec().decode(output, 3)) {
      assertEquals(MIDDLE_PALETTE[2], chunk.getSection(1).getBlockState(0));
      assertArrayEquals(output, chunk.finalizeOutput(), "改写结果必须可稳定往返");
    }
  }

  @Test
  void fullyUnchangedChunkRoundTripsByteIdentical() {
    byte[] raw = buildThreeSections();

    try (Chunk chunk = codec().decode(raw, 3)) {
      assertArrayEquals(raw, chunk.finalizeOutput(), "整块未改动时必须逐字节一致");
    }
  }

  @Test
  void absentSectionHasNoRange() {
    byte[] raw = new TestChunkBuilder(MODERN)
        .singleValueSection(STONE, 4096, 0, 0, new int[] {4})
        .singleValueSection(AIR, 0, 0, 0, new int[] {4})
        .build();

    try (Chunk chunk = codec().decode(raw, new boolean[] {true, false, true})) {
      assertNotNull(chunk.originalSectionRange(0));
      assertNull(chunk.originalSectionRange(1), "缓冲区中不含字节的 section 没有区间");
      assertNotNull(chunk.originalSectionRange(2));
      assertArrayEquals(raw, chunk.finalizeOutput(), "未声明的 section 不占字节，往返必须一致");
    }
  }

  private static byte[] slice(byte[] source, int offset, int length) {
    return Arrays.copyOfRange(source, offset, offset + length);
  }
}