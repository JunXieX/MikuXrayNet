package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 裁剪自检的守门用例：计数一旦少报，裁剪就会把<b>仍被引用</b>的调色板条目当成失效条目移除，
 * 其格子被重映射到别的条目——方块序列被静默改写。{@code verify=true} 必须在写回之前发现并抛出。
 *
 * <p>为什么这条重要：生产路径默认 {@code strict-verify=false}，靠的是「计数一定正确」这个前提。
 * 本用例把前提打破，验证一旦前提不成立，严格口径确实能挡住，而不是一起静默。
 */
class ChunkSectionPaletteVerifyTest {

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

  /** 2 条调色板、各占一半格子的间接 section 字节。
   */
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
  void compactRejectsPruningStillReferencedEntry() {
    byte[] source = twoEntrySection();
    try (Chunk chunk = new ChunkCodec(registry(), MODERN).decode(source, 1)) {
      ChunkSection section = chunk.getSection(0);
      // 条目 1 实际有 2048 处引用，却少报成 0：裁剪会移除它并把那些格子重映射到条目 0
      assertThrows(IllegalStateException.class,
          () -> section.compactPalette(true, new int[] {2048, 0}),
          "计数少报导致裁掉被引用条目时，自检必须抛出，而不是静默返回改过的方块序列");
    }
  }

  @Test
  void withoutVerifyTheSameCountsSilentlyChangeBlocks() {
    // 对照组：关闭自检时同一份错误计数照常裁剪——正因如此，生产路径绝不能吞掉自检异常
    byte[] source = twoEntrySection();
    try (Chunk chunk = new ChunkCodec(registry(), MODERN).decode(source, 1)) {
      ChunkSection section = chunk.getSection(0);
      int[] before = section.readAllBlockStates();
      assertTrue(section.compactPalette(false, new int[] {2048, 0}), "关闭自检时同样执行裁剪");
      int[] after = section.readAllBlockStates();
      boolean changed = false;
      for (int i = 0; i < before.length; i++) {
        if (before[i] != after[i]) {
          changed = true;
          break;
        }
      }
      assertTrue(changed, "少报计数时方块序列确实被改动（这就是必须靠自检挡住的原因）");
    }
  }
}