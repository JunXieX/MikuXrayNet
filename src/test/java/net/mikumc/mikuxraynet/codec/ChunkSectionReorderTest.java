package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * 调色板压缩重排测试：高频方块状态必须落到低位索引，且方块序列（语义）完全不变。
 *
 * <p>判定依据：重排只做「调色板顺序 + 位打包索引」的同步重映射，因此
 * {@code 解码 → 重排 → 再解码} 必须得到完全相同的方块状态序列；同时重排后的调色板首项
 * 应当就是出现次数最多的那个方块状态（这正是压缩率提升的来源）。
 */
class ChunkSectionReorderTest {

  private static final int AIR = 0;
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

  @Test
  void reorderPutsMostFrequentStateFirstAndKeepsSemantics() {
    int[] palette = {AIR, 10, 20, 30};
    int[] paletteIndices = new int[4096];
    // 频次：索引 1（3000）> 索引 2（900）> 索引 0（100）> 索引 3（96）
    for (int i = 0; i < 3000; i++) {
      paletteIndices[i] = 1;
    }
    for (int i = 3000; i < 3900; i++) {
      paletteIndices[i] = 2;
    }
    for (int i = 3900; i < 4000; i++) {
      paletteIndices[i] = 0;
    }
    for (int i = 4000; i < 4096; i++) {
      paletteIndices[i] = 3;
    }

    byte[] raw = new TestChunkBuilder(MODERN)
        .indirectSection(4, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 0, new int[] {1})
        .build();

    int[] before;
    byte[] reencoded;
    try (Chunk chunk = codec().decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      before = section.readAllBlockStates();
      assertFalse(section.isModified(), "刚解码的 section 不应是已改写状态");

      assertTrue(section.reorderPaletteByFrequency(false), "存在频次差异时应发生重排");
      assertArrayEquals(before, section.readAllBlockStates(), "重排不得改变任何方块状态");
      assertTrue(section.isModified(), "重排属于改写，必须标记为已修改（否则会被原样搬运）");
      assertFalse(section.reorderPaletteByFrequency(false), "已按频次有序，重复重排应为无改动");

      reencoded = chunk.finalizeOutput();
    }

    assertEquals(4, readPalette(reencoded).length, "调色板条目数不变");
    assertArrayEquals(new int[] {10, 20, AIR, 30}, readPalette(reencoded),
        "重排后调色板首项必须是出现最多的方块状态");

    try (Chunk chunk = codec().decode(reencoded, 1)) {
      assertArrayEquals(before, chunk.getSection(0).readAllBlockStates(), "解码 → 重排 → 再解码必须语义一致");
      assertArrayEquals(reencoded, chunk.finalizeOutput(), "重排结果必须可稳定往返");
    }
  }

  @Test
  void reorderPreservesSemanticsAcrossBitWidths() {
    int[] bitsPerBlockValues = {4, 5, 8};
    int[] paletteSizes = {12, 20, 40};

    for (int i = 0; i < bitsPerBlockValues.length; i++) {
      int bitsPerBlock = bitsPerBlockValues[i];
      int[] palette = palette(paletteSizes[i]);
      int[] paletteIndices = skewedIndices(palette.length);

      String message = "bitsPerBlock=" + bitsPerBlock;
      byte[] raw = new TestChunkBuilder(MODERN)
          .indirectSection(bitsPerBlock, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 0,
              new int[] {1})
          .build();

      int[] before;
      byte[] reencoded;
      try (Chunk chunk = codec().decode(raw, 1)) {
        ChunkSection section = chunk.getSection(0);
        before = section.readAllBlockStates();
        section.reorderPaletteByFrequency(true);
        assertArrayEquals(before, section.readAllBlockStates(), message + "：重排后语义必须不变");
        reencoded = chunk.finalizeOutput();
      }

      int[] reorderedPalette = readPalette(reencoded);
      assertEquals(palette[palette.length - 1], reorderedPalette[0], message + "：最多出现的状态应排到索引 0");
      assertEquals(bitsPerBlock, readBitsPerBlock(reencoded), message + "：位宽不变");

      try (Chunk chunk = codec().decode(reencoded, 1)) {
        assertArrayEquals(before, chunk.getSection(0).readAllBlockStates(), message + "：往返语义一致");
        assertArrayEquals(reencoded, chunk.finalizeOutput(), message + "：往返字节一致");
      }
    }
  }

  @Test
  void singleValueAndDirectSectionsAreNotReordered() {
    byte[] singleValue = new TestChunkBuilder(MODERN)
        .singleValueSection(10, 4096, 0, 0, new int[] {1})
        .build();
    try (Chunk chunk = codec().decode(singleValue, 1)) {
      assertFalse(chunk.getSection(0).reorderPaletteByFrequency(false), "单值调色板没有可重排的调色板段");
    }

    int[] blockStates = new int[4096];
    for (int i = 0; i < blockStates.length; i++) {
      blockStates[i] = (i * 13) % 5000;
    }
    byte[] direct = new TestChunkBuilder(MODERN)
        .directSection(15, nonAirCount(blockStates), 0, blockStates, 0, new int[] {1})
        .build();
    try (Chunk chunk = codec().decode(direct, 1)) {
      assertFalse(chunk.getSection(0).reorderPaletteByFrequency(false), "直接调色板的索引本身就是状态 id，无法重排");
    }
  }

  @Test
  void verifyModePassesOnHealthySection() {
    int[] palette = palette(6);
    int[] paletteIndices = skewedIndices(palette.length);
    byte[] raw = new TestChunkBuilder(MODERN)
        .indirectSection(4, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 0, new int[] {1})
        .build();

    try (Chunk chunk = codec().decode(raw, 1)) {
      // verify=true 时若重排破坏了方块序列会抛异常，这里要求正常通过
      assertTrue(chunk.getSection(0).reorderPaletteByFrequency(true));
    }
  }

  private static int[] palette(int size) {
    int[] palette = new int[size];
    palette[0] = AIR;
    for (int i = 1; i < size; i++) {
      palette[i] = i * 7 + 3;
    }
    return palette;
  }

  /** 绝大多数方块都是最后一个调色板条目，便于验证「高频落低位」。 */
  private static int[] skewedIndices(int paletteSize) {
    int[] indices = new int[4096];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = i % paletteSize;
    }
    for (int i = 0; i < 4000; i++) {
      indices[i] = paletteSize - 1;
    }
    return indices;
  }

  private static int nonAirCount(int[] palette, int[] paletteIndices) {
    int count = 0;
    for (int paletteIndex : paletteIndices) {
      if (palette[paletteIndex] != AIR) {
        count++;
      }
    }
    return count;
  }

  private static int nonAirCount(int[] blockStates) {
    int count = 0;
    for (int blockState : blockStates) {
      if (blockState != AIR) {
        count++;
      }
    }
    return count;
  }

  /** 读取重编码结果中首个 section 的调色板（方块状态 id 列表）。 */
  private static int[] readPalette(byte[] sectionData) {
    ByteBuf buffer = Unpooled.wrappedBuffer(sectionData);
    try {
      buffer.readShort();
      buffer.readShort();
      buffer.readUnsignedByte();
      int size = ByteBufUtil.readVarInt(buffer);
      int[] values = new int[size];
      for (int i = 0; i < size; i++) {
        values[i] = ByteBufUtil.readVarInt(buffer);
      }
      return values;
    } finally {
      buffer.release();
    }
  }

  private static int readBitsPerBlock(byte[] sectionData) {
    ByteBuf buffer = Unpooled.wrappedBuffer(sectionData);
    try {
      buffer.readShort();
      buffer.readShort();
      return buffer.readUnsignedByte();
    } finally {
      buffer.release();
    }
  }
}