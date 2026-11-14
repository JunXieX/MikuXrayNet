package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 间接调色板 idFor 的入参守门：{@code byValue} 的长度就是调色板容量，越界下标会读到别人的槽位
 * （{@code (byte) 转换}后表现为"写进去的方块变成另一个方块"），必须在入口显式拒绝。
 */
class IndirectPaletteInputTest {

  private static final ChunkVersionFlags MODERN = ChunkVersionFlags.PAPER_26_2;

  private static RegistryAccessor registry() {
    return new RegistryAccessor() {
      @Override
      public boolean isAir(int blockId) {
        return blockId == 0;
      }

      @Override
      public boolean isFluid(int blockId) {
        return false;
      }

      @Override
      public int getUniqueBlockStateCount() {
        return 16;
      }

      @Override
      public int getMaxBitsPerBlockState() {
        return 4;
      }
    };
  }

  private static byte[] twoEntrySection() {
    int[] palette = {1, 4};
    int[] indices = new int[4096];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = i % 2;
    }
    return new TestChunkBuilder(MODERN)
        .indirectSection(1, 2048, 0, palette, indices, 0, new int[] {1}).build();
  }

  @Test
  void rejectsOutOfRangeStateId() {
    try (Chunk chunk = new ChunkCodec(registry(), MODERN).decode(twoEntrySection(), 1)) {
      ChunkSection section = chunk.getSection(0);
      assertThrows(IllegalArgumentException.class, () -> section.paletteIdFor(-1));
      assertThrows(IllegalArgumentException.class, () -> section.paletteIdFor(1 << 20));
      assertDoesNotThrow(() -> section.paletteIdFor(4));
    }
  }
}
