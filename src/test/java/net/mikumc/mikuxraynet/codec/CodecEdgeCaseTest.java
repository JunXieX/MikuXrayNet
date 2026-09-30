package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * codec 边界回归：位打包数组长度的整数计算、{@code bitsPerEntry=64} 的满掩码，以及
 * 间接调色板对「重复方块状态值」的损坏检测。
 */
class CodecEdgeCaseTest {

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

  /**
   * 长度必须是精确的整数上取整。旧实现用 {@code (float)} 除法，size 很大时（超过 float 的 24 位有效位）
   * 会丢精度、{@code ceil} 得到偏小的长度——该长度参与读取时的长度校验，会造成功能性失败。
   */
  @Test
  void calculateArraySizeIsExactForLargeSizes() {
    int[] bitsPerEntryValues = {1, 3, 5, 8, 15, 64};
    int[] sizes = {0, 1, 63, 64, 65, 4096, 21_000_001, 100_000_000};
    for (int bitsPerEntry : bitsPerEntryValues) {
      int entriesPerLong = 64 / bitsPerEntry;
      for (int size : sizes) {
        long expected = (size + entriesPerLong - 1L) / entriesPerLong;
        assertEquals((int) expected, SimpleVarBitBuffer.calculateArraySize(bitsPerEntry, size),
            "bitsPerEntry=" + bitsPerEntry + " size=" + size + " 的数组长度必须精确（整数上取整）");
      }
    }
    assertEquals(0, SimpleVarBitBuffer.calculateArraySize(0, 4096), "bitsPerEntry=0 的数组长度为 0");
  }

  /** {@code bitsPerEntry=64} 时掩码必须是「低 64 位全 1」；旧写法 {@code (1L << 64) - 1} 会算出 0。 */
  @Test
  void fullWidthMaskHandlesBitsPerEntry64() {
    int size = 4;
    long[] buffer = new long[SimpleVarBitBuffer.calculateArraySize(64, size)];
    assertEquals(size, buffer.length, "entriesPerLong=1 时数组长度应等于 size");

    SimpleVarBitBuffer bits = new SimpleVarBitBuffer(64, size, buffer);
    bits.set(1, 0xDEADBEEF);
    assertEquals(0xDEADBEEF, bits.get(1),
        "64 位时掩码为满掩码，写入的值必须原样读回（掩码为 0 会把值全部抹零）");
    bits.set(3, -1);
    assertEquals(-1, bits.get(3), "负值也必须原样往返");
    assertEquals(0, bits.get(0), "未写入的项必须保持 0");
  }

  /** 调色板里出现重复的方块状态值即为损坏：必须在读取时拒绝（fail-open 由上层解码兜底）。 */
  @Test
  void duplicatePaletteValueIsRejected() {
    int[] palette = {AIR, 10, 10};
    int[] paletteIndices = new int[4096];
    byte[] raw = new TestChunkBuilder(MODERN)
        .indirectSection(4, 4096, 0, palette, paletteIndices, 0, new int[] {1})
        .build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    assertThrows(IndexOutOfBoundsException.class, () -> codec.decode(raw, 1),
        "调色板含重复方块状态值必须视为损坏并拒绝，而不是静默接受两个索引指向同一状态");
  }
}