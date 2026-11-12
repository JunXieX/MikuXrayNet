package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.BitSet;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import org.junit.jupiter.api.Test;

/**
 * 位宽预算开启时的真实改写回归：把目标方块替换成<b>非目标</b>伪装方块后，调色板引用计数必须包含
 * 替换条目，否则 compactPalette 会把「只由替换引入」的伪装条目当成失效条目裁掉，该格的本地索引
 * 重映射到条目 0，客户端看到的方块与写入的伪装完全不同。
 */
class ObfuscationPaletteBudgetTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int ORE = 2;
  private static final ChunkVersionFlags FLAGS = ChunkVersionFlags.PAPER_26_2;

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

  @Test
  void replacingTargetWithNonTargetKeepsPaletteConsistent() {
    ChunkCodec codec = new ChunkCodec(registry(), FLAGS);
    BitSet targets = new BitSet(1 << 15);
    targets.set(ORE);
    ObfuscationProcessor processor = new ObfuscationProcessor(codec, state -> true, targets,
        new int[] {STONE}, new int[] {10}, false, true,
        new ObfuscationProcessor.PaletteOptions(false, true), true, false, null);

    int[] palette = {STONE, ORE};
    int[] indices = new int[4096];
    Arrays.fill(indices, 1); // 整段全是矿；伪装方块 STONE 在调色板内但当前零引用
    byte[] source = new TestChunkBuilder(FLAGS)
        .indirectSection(4, 4096, 0, palette, indices, 0, new int[] {1})
        .build();

    ObfuscationProcessor.Result result = processor.rewrite(source, 1, 42L, null, "world",
        net.mikumc.mikuxraynet.config.AntiXrayConfig.Dimension.NORMAL, 0, 0, 0);
    assertNull(result.failure(), "改写不得失败");
    assertEquals(4096, result.obfuscatedPositions().length, "所有矿都应被替换");

    try (Chunk chunk = codec.decode(result.data(), 1)) {
      int[] states = chunk.getSection(0).readAllBlockStates();
      for (int state : states) {
        assertEquals(STONE, state,
            "替换后的每一格都必须是伪装方块（条目被误裁会让索引重映射到条目 0）");
      }
    }
  }
}
