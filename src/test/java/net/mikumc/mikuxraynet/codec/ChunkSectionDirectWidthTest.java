
package net.mikumc.mikuxraynet.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接（direct/global）格式的<b>声明位宽</b>契约。
 *
 * <p>一手取证（Paper 26.3 字节码）：{@code Strategy#getConfigurationForBitCount(bits)} 对 {@code bits>8}
 * 一律构造 {@code Configuration.Global(globalPaletteBitsInMemory, bits)}——<b>存储位宽取声明值</b>，
 * 注册表位宽只是内存位宽；出站声明位宽由 {@code PalettedContainer#pack} 按调色板规模选择
 * （{@code HashMapPalette} 满 8 位时 {@code onResize(9)}）。合法上界 32 来自 {@code SimpleBitStorage}
 * 构造的 {@code Validate.inclusiveBetween(1, 32, bits)}。
 *
 * <p>因此声明位宽<b>不必等于</b>注册表位宽。旧实现要求相等，会把服务端合法下发的 16 位 section
 * 判为非法 → 整块 fail-open → 该区块全部 section 都不伪装（矿物裸露给透视端）。
 */
class ChunkSectionDirectWidthTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int DIAMOND_ORE = 100;

  private static final ChunkVersionFlags MODERN = new ChunkVersionFlags("26.2");

  /** 注册表位宽固定 15（vanilla 约 3.2 万状态），与声明位宽刻意不同。 */
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

      /** 与生产实现（BlockStateRegistry）同口径：覆盖 vanilla 之后追加的自定义方块状态。 */
      @Override
      public int paletteCapacity() {
        return 1 << 18;
      }

      @Override
      public int getMaxBitsPerBlockState() {
        return 15;
      }
    };
  }

  private static int[] states(int distinct) {
    int[] values = new int[4096];
    for (int i = 0; i < values.length; i++) {
      values[i] = i % distinct;
    }
    return values;
  }

  /** 声明位宽 9..32 全部合法：往返逐字节一致，且逐格状态不变。 */
  @Test
  void declaredDirectWidthsRoundTripByteForByte() {
    for (int bits = 9; bits <= 32; bits++) {
      int[] expected = states(7);
      TestChunkBuilder builder = new TestChunkBuilder(MODERN);
      builder.directSection(bits, 4096, 0, expected, 0, new int[] {7});
      byte[] raw = builder.build();

      ChunkCodec codec = new ChunkCodec(registry(), MODERN);
      try (Chunk chunk = codec.decode(raw, 1)) {
        ChunkSection section = chunk.getSection(0);
        assertEquals(bits, section.bitsPerBlock(),
            "声明位宽必须原值保留（bits=" + bits + "），不得归一化为注册表位宽 15");
        for (int i = 0; i < expected.length; i++) {
          assertEquals(expected[i], section.getBlockState(i), "bits=" + bits + " 第 " + i + " 格");
        }
        assertArrayEquals(raw, chunk.finalizeOutput(),
            "bits=" + bits + "：未改动的 direct section 必须逐字节往返一致");
      }
    }
  }

  /**
   * 真机 dump 的失效场景：16 位声明 + 注册表 15 位。改写必须真的发生（而不是整块 fail-open），
   * 且改写后逐格语义正确、结果可再次稳定解码。
   */
  @Test
  void sixteenBitSectionIsRewrittenNotRejected() {
    int[] original = states(3);
    original[5] = DIAMOND_ORE;
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.directSection(16, 4096, 0, original, 0, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    byte[] reencoded;
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(16, section.bitsPerBlock(), "前置：16 位声明必须被接受");
      assertEquals(DIAMOND_ORE, section.getBlockState(5));
      section.setBlockState(5, STONE);
      reencoded = chunk.finalizeOutput();
    }

    try (Chunk again = codec.decode(reencoded, 1)) {
      ChunkSection section = again.getSection(0);
      assertEquals(STONE, section.getBlockState(5), "伪装必须真的写进字节（旧实现整块放行原包）");
      assertEquals(16, section.bitsPerBlock(), "改写后仍按声明位宽写出");
      for (int i = 0; i < original.length; i++) {
        if (i != 5) {
          assertEquals(original[i], section.getBlockState(i), "其余格不得受影响：第 " + i + " 格");
        }
      }
    }
  }

  /** 超出 SimpleBitStorage 上界（32）的声明位宽仍必须拒绝：那是损坏数据，不是合法编码。 */
  @Test
  void directWidthAboveStorageLimitIsRejected() {
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.directSection(33, 4096, 0, states(3), 0, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> codec.decode(raw, 1), "位宽 33 超出 SimpleBitStorage 上界，必须显式拒绝");
    assertTrue(failure.getMessage().contains("33"), "异常须带上实际位宽以便定位：" + failure.getMessage());
  }

  /**
   * 自定义方块 id（超出 vanilla 映射、但在反查表容量内）的 direct section：
   * 降级判据必须按 {@code paletteCapacity()}，否则合法 id 被误判越界、白白保留大位宽。
   */
  @Test
  void customStateIdsAboveVanillaCountStillDowngrade() {
    int customId = (1 << 15) + 1234; // 超出 getUniqueBlockStateCount()，仍在 paletteCapacity() 内
    int[] original = new int[4096];
    java.util.Arrays.fill(original, customId);

    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.directSection(18, 4096, 0, original, 0, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(18, section.bitsPerBlock());
      assertEquals(customId, section.getBlockState(0));
      assertTrue(section.downgradePalette(true),
          "只引用一种状态 → 必须能降为单值调色板（旧判据按 vanilla 数会误拒）");
      assertEquals(0, section.bitsPerBlock(), "降级后为单值调色板");
      assertEquals(customId, section.getBlockState(0), "降级不得改变方块语义");
      assertEquals(customId, section.getBlockState(4095));
    }
  }

  /**
   * 群系容器与方块 section 同一套 Strategy 语义（一手取证：{@code Strategy$2} 对 {@code bits>3}
   * 构造 {@code Global(memory, bits)}）。旧实现按「群系只有 64 格、上限 8」拒绝 &gt;8，
   * 会把自定义群系较多的服务端合法负载判为非法 → 整块 fail-open（与方块位宽误拒同一根因）。
   */
  @Test
  void biomeContainerAcceptsDirectWidthsUpToStorageLimit() {
    for (int biomeBits : new int[] {4, 6, 8, 9, 16, 32}) {
      int[] expected = states(5);
      TestChunkBuilder builder = new TestChunkBuilder(MODERN);
      builder.directSection(15, 4096, 0, expected, biomeBits, new int[] {7});
      byte[] raw = builder.build();

      ChunkCodec codec = new ChunkCodec(registry(), MODERN);
      try (Chunk chunk = codec.decode(raw, 1)) {
        assertEquals(expected[0], chunk.getSection(0).getBlockState(0),
            "群系位宽 " + biomeBits + " 不得影响方块解析");
        assertArrayEquals(raw, chunk.finalizeOutput(),
            "群系位宽 " + biomeBits + "：未改动必须逐字节往返一致");
      }
    }
  }

  /** 群系声明位宽超出 SimpleBitStorage 上界仍必须拒绝（真错位/损坏数据）。 */
  @Test
  void biomeContainerWidthAboveStorageLimitIsRejected() {
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.directSection(15, 4096, 0, states(5), 33, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(raw, 1),
        "群系位宽 33 超出上界 32，必须显式拒绝");
  }

  /**
   * 多 section 区块里混用不同声明位宽（含群系 direct）：边界必须逐段精确，
   * 否则一段错位会把后续所有 section 的字节吞掉。
   */
  @Test
  void mixedDeclaredWidthsKeepSectionBoundariesExact() {
    int[] a = states(3);
    int[] b = states(5);
    TestChunkBuilder builder = new TestChunkBuilder(MODERN);
    builder.directSection(16, 4096, 0, a, 9, new int[] {7});
    builder.directSection(11, 4096, 0, b, 4, new int[] {7});
    builder.singleValueSection(STONE, 4096, 0, 0, new int[] {7});
    byte[] raw = builder.build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    try (Chunk chunk = codec.decode(raw, 3)) {
      assertEquals(16, chunk.getSection(0).bitsPerBlock());
      assertEquals(11, chunk.getSection(1).bitsPerBlock());
      assertEquals(0, chunk.getSection(2).bitsPerBlock());
      assertEquals(a[4095], chunk.getSection(0).getBlockState(4095));
      assertEquals(b[4095], chunk.getSection(1).getBlockState(4095));
      assertEquals(STONE, chunk.getSection(2).getBlockState(4095));
      assertArrayEquals(raw, chunk.finalizeOutput(), "混合声明位宽必须逐字节往返一致");
    }
  }

  /** 位宽 31/32 的 contains 上界必须按 long 计算（int 下 1<<31 溢出成负数会误判全部放不下）。 */
  @Test
  void directContainsHandlesWideDeclaredWidths() {
    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    for (int bits : new int[] {30, 31, 32}) {
      int[] states = states(3);
      byte[] raw = new TestChunkBuilder(MODERN)
          .directSection(bits, 4096, 0, states, 0, new int[] {7})
          .build();
      try (Chunk chunk = codec.decode(raw, 1)) {
        ChunkSection section = chunk.getSection(0);
        assertEquals(true, section.paletteContains(0), "bits=" + bits + "：0 必定可表示");
        assertEquals(true, section.paletteContains(32000), "bits=" + bits + "：32000 必定可表示");
        assertEquals(false, section.paletteContains(-1), "bits=" + bits + "：负数不可表示");
      }
    }
  }
}
