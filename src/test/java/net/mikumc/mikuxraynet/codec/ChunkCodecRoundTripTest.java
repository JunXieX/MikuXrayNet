// 移植自 Orebfuscator（GPL-3.0），本项目为私有自用部署。
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * codec 解码 → 重编码往返一致性测试。
 *
 * <p>判定依据：
 * <ul>
 *   <li>「未改动即重编码逐字节一致」：解码会把每个 section 的计数、位宽、调色板、位打包数组与
 *       群系容器按原布局重新写出，故字节相等是最强断言，同时也验证了位序与「每 long 项数」约定；</li>
 *   <li>调色板升位时索引会重排（上游行为），此时改判「方块状态语义不变 + 升位结果可再次稳定往返」，
 *       因为在该契约下原始字节已无法保留。</li>
 * </ul>
 */
class ChunkCodecRoundTripTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int WATER = 42;
  private static final int DIAMOND_ORE = 100;
  private static final int NEW_STATE = 12345;

  /** 目标版本 Paper 26.2（Minecraft 26.2）。 */
  private static final ChunkVersionFlags MODERN = new ChunkVersionFlags("26.2");

  private static RegistryAccessor registry(int maxBitsPerBlockState) {
    return new RegistryAccessor() {
      @Override
      public boolean isAir(int blockId) {
        return blockId == AIR;
      }

      @Override
      public boolean isFluid(int blockId) {
        return blockId == WATER;
      }

      @Override
      public int getUniqueBlockStateCount() {
        return 1 << 15;
      }

      @Override
      public int getMaxBitsPerBlockState() {
        return maxBitsPerBlockState;
      }
    };
  }

  @Test
  void versionFlagsFollowMinecraftVersion() {
    assertTrue(MODERN.hasFluidCount(), "26.2 >= 26.1.0：section 头部含流体计数");
    assertFalse(MODERN.hasLongArrayLengthField(), "26.2 >= 1.21.5：long 数组前无长度字段");
    assertTrue(MODERN.hasBiomePalettedContainer(), "1.18+：section 尾部内联群系容器");
    assertTrue(MODERN.hasSingleValuePalette(), "1.18+：支持单值调色板");

    ChunkVersionFlags legacy = new ChunkVersionFlags("1.21.4");
    assertFalse(legacy.hasFluidCount());
    assertTrue(legacy.hasLongArrayLengthField());
    assertTrue(legacy.hasBiomePalettedContainer());

    ChunkVersionFlags ancient = new ChunkVersionFlags("1.17.1");
    assertFalse(ancient.hasBiomePalettedContainer(), "1.17：群系段在末尾而非 section 内");
    assertFalse(ancient.hasSingleValuePalette());
  }

  @Test
  void emptySingleValueSectionRoundTrip() {
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.singleValueSection(AIR, 0, 0, 0, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertTrue(section.isEmpty(), "全空气 section 的 blockCount 为 0");
      assertEquals(AIR, section.getBlockState(0));
      assertEquals(AIR, section.getBlockState(4095));
      assertArrayEquals(raw, chunk.finalizeOutput(), "空 section 往返必须逐字节一致");
    }
  }

  @Test
  void uniformSingleValueSectionRoundTrip() {
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.singleValueSection(STONE, 4096, 0, 0, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertFalse(section.isEmpty(), "全同方块 section 的 blockCount 为 4096");
      assertEquals(STONE, section.getBlockState(0));
      assertEquals(STONE, section.getBlockState(2077));
      assertEquals(STONE, section.getBlockState(4095));
      assertArrayEquals(raw, chunk.finalizeOutput(), "全同方块 section 往返必须逐字节一致");
    }
  }

  @Test
  void fluidCountIsDecrementedWhenFluidIsReplacedByAir() {
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.singleValueSection(WATER, 4096, 4096, 0, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    byte[] reencoded;
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(WATER, section.getBlockState(0));
      // 单值调色板容不下第二种状态，会升位为间接调色板；blockCount / fluidCount 各减 1
      section.setBlockState(0, AIR);
      reencoded = chunk.finalizeOutput();
    }

    int[] header = readSectionHeader(reencoded, MODERN);
    assertEquals(4095, header[0], "blockCount 应减 1");
    assertEquals(4095, header[1], "fluidCount 应减 1");
    assertEquals(4, header[2], "出现第二种状态后位宽升为 4 位间接调色板");

    try (Chunk chunk = codec.decode(reencoded, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(AIR, section.getBlockState(0));
      assertEquals(WATER, section.getBlockState(1));
      assertArrayEquals(reencoded, chunk.finalizeOutput(), "升位结果必须可再次稳定往返");
    }
  }

  @Test
  void singleValueAirSectionGrowsWhenFluidIsPlaced() {
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.singleValueSection(AIR, 0, 0, 0, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    byte[] reencoded;
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertTrue(section.isEmpty());
      section.setBlockState(100, WATER);
      assertFalse(section.isEmpty());
      reencoded = chunk.finalizeOutput();
    }

    int[] header = readSectionHeader(reencoded, MODERN);
    assertEquals(1, header[0], "blockCount 应为 1");
    assertEquals(1, header[1], "fluidCount 应为 1");
    assertEquals(4, header[2], "单值调色板出现第二种状态后升为 4 位间接调色板");

    try (Chunk chunk = codec.decode(reencoded, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(WATER, section.getBlockState(100));
      assertEquals(AIR, section.getBlockState(99));
      assertEquals(AIR, section.getBlockState(101));
      assertArrayEquals(reencoded, chunk.finalizeOutput(), "升位结果必须可再次稳定往返");
    }
  }

  @Test
  void indirectPaletteRoundTrip() {
    int[] bitsPerBlockValues = {4, 5, 8};
    int[] paletteSizes = {12, 20, 40};

    for (int i = 0; i < bitsPerBlockValues.length; i++) {
      int bitsPerBlock = bitsPerBlockValues[i];
      int[] palette = palette(paletteSizes[i]);
      int[] paletteIndices = indices(palette.length, 7);

      TestChunkBuilder builder = new TestChunkBuilder(MODERN);
      builder.indirectSection(bitsPerBlock, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 3,
          new int[] {1, 2, 7, 9});
      byte[] raw = builder.build();

      String message = "bitsPerBlock=" + bitsPerBlock;
      ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
      try (Chunk chunk = codec.decode(raw, 1)) {
        ChunkSection section = chunk.getSection(0);
        assertEquals(bitsPerBlock, readSectionHeader(raw, MODERN)[2], message + "：位宽应保持");
        assertEquals(palette[paletteIndices[0]], section.getBlockState(0), message);
        assertEquals(palette[paletteIndices[1000]], section.getBlockState(1000), message);
        assertEquals(palette[paletteIndices[4095]], section.getBlockState(4095), message);
        assertArrayEquals(raw, chunk.finalizeOutput(), message + "：往返必须逐字节一致");
      }
    }
  }

  @Test
  void fullIndirectPaletteGrowsToHigherBits() {
    int[] palette = palette(16);
    int[] paletteIndices = indices(16, 7);

    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.indirectSection(4, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 0, new int[] {2});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    byte[] reencoded;
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      section.setBlockState(0, NEW_STATE);
      reencoded = chunk.finalizeOutput();
    }

    assertEquals(5, readSectionHeader(reencoded, MODERN)[2], "4 位调色板写满后写入新状态应升为 5 位");

    try (Chunk chunk = codec.decode(reencoded, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(NEW_STATE, section.getBlockState(0));
      assertEquals(palette[paletteIndices[1]], section.getBlockState(1), "升位后其余位置语义不变");
      assertEquals(palette[paletteIndices[4095]], section.getBlockState(4095));
      assertArrayEquals(reencoded, chunk.finalizeOutput(), "升位后的间接 section 必须可稳定往返");
    }
  }

  @Test
  void fullIndirectPaletteAtThresholdGrowsToDirect() {
    int[] palette = palette(256);
    int[] paletteIndices = indices(256, 1);

    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.indirectSection(8, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 0, new int[] {2});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    byte[] reencoded;
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      // 8 位间接调色板已写满（256 项），再写入新状态即跨过 indirect → direct 阈值
      section.setBlockState(0, NEW_STATE);
      reencoded = chunk.finalizeOutput();
    }

    assertEquals(15, readSectionHeader(reencoded, MODERN)[2], "跨阈值后位宽取注册表的 getMaxBitsPerBlockState()");

    try (Chunk chunk = codec.decode(reencoded, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(NEW_STATE, section.getBlockState(0));
      assertEquals(palette[paletteIndices[1]], section.getBlockState(1), "升位后其余位置语义不变");
      assertEquals(palette[paletteIndices[3000]], section.getBlockState(3000));
      assertArrayEquals(reencoded, chunk.finalizeOutput(), "升位后的 direct section 必须可稳定往返");
    }
  }

  @Test
  void directPaletteRoundTrip() {
    int[] blockStates = new int[4096];
    for (int i = 0; i < blockStates.length; i++) {
      blockStates[i] = (i * 31) % 5000;
    }
    blockStates[0] = AIR;
    blockStates[1234] = DIAMOND_ORE;

    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.directSection(15, nonAirCount(blockStates), 0, blockStates, 6, new int[0]);
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(15, readSectionHeader(raw, MODERN)[2]);
      assertEquals(blockStates[0], section.getBlockState(0), "direct 调色板的 id 即方块状态 id");
      assertEquals(blockStates[1234], section.getBlockState(1234));
      assertEquals(blockStates[4095], section.getBlockState(4095));
      assertArrayEquals(raw, chunk.finalizeOutput(), "direct section 往返必须逐字节一致");
    }
  }

  /**
   * bitsPerBlock=9 的分支：编码器把 &gt;8 位一律当作 direct，位宽取自注册表，故这里让注册表返回 9，
   * 9 位直接格式便可逐字节往返（真实服务器上注册表通常返回 15）。
   */
  @Test
  void nineBitsIsHandledAsDirectPalette() {
    int[] blockStates = new int[4096];
    for (int i = 0; i < blockStates.length; i++) {
      blockStates[i] = (i * 13) % 512;
    }

    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.directSection(9, nonAirCount(blockStates), 0, blockStates, 0, new int[] {3});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(9), MODERN);
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(blockStates[0], section.getBlockState(0));
      assertEquals(blockStates[2048], section.getBlockState(2048));
      assertArrayEquals(raw, chunk.finalizeOutput(), "9 位 direct section 往返必须逐字节一致");
    }
  }

  @Test
  void multiSectionChunkWithBiomeContainersRoundTrip() {
    int[] palette = palette(6);
    int[] paletteIndices = indices(6, 5);
    int[] blockStates = new int[4096];
    for (int i = 0; i < blockStates.length; i++) {
      blockStates[i] = (i * 17) % 900;
    }

    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.singleValueSection(AIR, 0, 0, 0, new int[] {7});
    builder.indirectSection(4, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 3,
        new int[] {1, 2, 7, 9});
    builder.directSection(15, nonAirCount(blockStates), 0, blockStates, 6, new int[0]);
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    try (Chunk chunk = codec.decode(raw, 3)) {
      assertEquals(3, chunk.getSectionCount());
      assertTrue(chunk.getSection(0).isEmpty());
      assertEquals(palette[paletteIndices[5]], chunk.getSection(1).getBlockState(5));
      assertEquals(blockStates[4095], chunk.getSection(2).getBlockState(4095));
      assertArrayEquals(raw, chunk.finalizeOutput(),
          "含单值/间接/直接三种群系容器形态的多 section 区块必须逐字节往返一致");
    }
  }

  @Test
  void chunkWithoutBiomeContainerCopiesTrailingBytes() {
    // 1.17 形态：section 尾部没有群系容器，群系段位于所有 section 之后，由 finalizeOutput() 原样搬运
    ChunkVersionFlags flags = new ChunkVersionFlags(false, true, false, true);

    int[] palette = palette(6);
    int[] paletteIndices = indices(6, 5);

    TestChunkBuilder builder = new TestChunkBuilder(flags);
    builder.singleValueSection(STONE, 4096, 0, 0, new int[0]);
    builder.indirectSection(4, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 0, new int[0]);
    builder.trailing(new byte[] {0x02, 0x05, 0x11, 0x22, 0x33});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), flags);
    try (Chunk chunk = codec.decode(raw, 2)) {
      assertEquals(STONE, chunk.getSection(0).getBlockState(0));
      assertEquals(palette[paletteIndices[7]], chunk.getSection(1).getBlockState(7));
      assertArrayEquals(raw, chunk.finalizeOutput(), "末尾群系段必须原样追加");
    }
  }

  @Test
  void absentSectionHasNoView() {
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.singleValueSection(STONE, 4096, 0, 0, new int[] {4});
    builder.singleValueSection(AIR, 0, 0, 0, new int[] {4});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), MODERN);
    try (Chunk chunk = codec.decode(raw, new boolean[] {true, false, true})) {
      assertEquals(3, chunk.getSectionCount());
      assertEquals(STONE, chunk.getSection(0).getBlockState(0));
      assertNull(chunk.getSection(1), "未声明有字节的 section 没有视图");
      assertEquals(AIR, chunk.getSection(2).getBlockState(0));
      assertArrayEquals(raw, chunk.finalizeOutput(), "未声明的 section 不占字节，往返必须一致");
    }
  }

  @Test
  void longArrayLengthFieldVariantsRoundTrip() {
    // 1.18 ~ 1.21.4 形态：方块数据与群系容器的 long 数组前都带 VarInt 长度字段，section 尾部内联群系容器
    ChunkVersionFlags flags = new ChunkVersionFlags(false, true, true, true);

    int[] palette = palette(6);
    int[] paletteIndices = indices(6, 5);
    int[] blockStates = new int[4096];
    for (int i = 0; i < blockStates.length; i++) {
      blockStates[i] = (i * 17) % 900;
    }

    TestChunkBuilder builder = new TestChunkBuilder(flags);
    builder.singleValueSection(AIR, 0, 0, 0, new int[] {7});
    builder.indirectSection(4, nonAirCount(palette, paletteIndices), 0, palette, paletteIndices, 2,
        new int[] {1, 3, 5});
    builder.directSection(15, nonAirCount(blockStates), 0, blockStates, 6, new int[0]);
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(15), flags);
    try (Chunk chunk = codec.decode(raw, 3)) {
      assertEquals(3, chunk.getSectionCount());
      assertTrue(chunk.getSection(0).isEmpty());
      assertEquals(palette[paletteIndices[3]], chunk.getSection(1).getBlockState(3));
      assertEquals(blockStates[4095], chunk.getSection(2).getBlockState(4095));
      assertArrayEquals(raw, chunk.finalizeOutput(), "含 long 数组长度字段的布局必须逐字节往返一致");
    }
  }

  private static int[] palette(int size) {
    int[] palette = new int[size];
    palette[0] = AIR;
    for (int i = 1; i < size; i++) {
      palette[i] = i + 1;
    }
    return palette;
  }

  private static int[] indices(int paletteSize, int step) {
    int[] indices = new int[4096];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = i * step % paletteSize;
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

  /** 读取重编码结果中首个 section 的头部：{blockCount, fluidCount, bitsPerBlock}。 */
  private static int[] readSectionHeader(byte[] sectionData, ChunkVersionFlags flags) {
    ByteBuf buffer = Unpooled.wrappedBuffer(sectionData);
    try {
      int blockCount = buffer.readShort();
      int fluidCount = flags.hasFluidCount() ? buffer.readShort() : 0;
      int bitsPerBlock = buffer.readUnsignedByte();
      return new int[] {blockCount, fluidCount, bitsPerBlock};
    } finally {
      buffer.release();
    }
  }
}