package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * 调色板级预筛（{@link ChunkSection#paletteCouldContain}）测试：能不能在不逐格读 4096 次的前提下
 * 证明「整段不含」——以及会不会把「其实含有」误判成「不含」（唯一不能错的方向）。
 *
 * <p>预筛的用途是 section 级整段跳过（见 {@code ObfuscationProcessor} 的改写循环），因此这里按调色板
 * 三种形态（单值 / 间接 / 直接）各钉一条边界：单值与间接按条目判定，直接调色板没有条目表必须保守放行。
 */
class ChunkSectionPaletteFilterTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int DIAMOND_ORE = 2;
  private static final ChunkVersionFlags MODERN = ChunkVersionFlags.PAPER_26_2;

  /** 「是不是矿」的查询（生产里对应隐藏方块位图）。 */
  private static final IntPredicate ORE = state -> state == DIAMOND_ORE;

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

  private static Chunk decode(byte[] bytes, int sectionCount) {
    return new ChunkCodec(registry(), MODERN).decode(bytes, sectionCount);
  }

  @Test
  void singleValuePaletteIsDecidedExactly() {
    byte[] bytes = new TestChunkBuilder(MODERN)
        .singleValueSection(STONE, 4096, 0, 0, new int[] {7})
        .build();

    try (Chunk chunk = decode(bytes, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertFalse(section.isEmpty(), "单值石头 section 的 blockCount 不为 0");
      assertFalse(section.paletteCouldContain(ORE), "调色板只有石头 → 整段必不含矿，可安全跳过");
      assertTrue(section.paletteCouldContain(state -> state == STONE), "查石头必须命中");
    }
  }

  @Test
  void indirectPaletteIsJudgedByItsEntries() {
    int[] palette = {STONE, DIAMOND_ORE};
    int[] indices = new int[4096]; // 全 0 = 石头（矿条目存在但未被数据引用）

    byte[] bytes = new TestChunkBuilder(MODERN)
        .indirectSection(4, 4096, 0, palette, indices, 0, new int[] {1})
        .build();

    try (Chunk chunk = decode(bytes, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertTrue(section.paletteCouldContain(ORE),
          "条目里有矿（哪怕数据没引用）→ 只能说「可能有」，绝不能误判为「不含」");
      assertFalse(section.paletteCouldContain(state -> state == 999), "条目之外的状态必定不含");
    }
  }

  @Test
  void directPaletteIsAlwaysConservative() {
    int[] states = new int[4096];
    Arrays.fill(states, STONE);

    byte[] bytes = new TestChunkBuilder(MODERN)
        .directSection(15, 4096, 0, states, 0, new int[] {1})
        .build();

    try (Chunk chunk = decode(bytes, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertTrue(section.paletteCouldContain(ORE),
          "直接调色板没有条目表 → 一律不跳过，语义与不开预筛完全一致");
      assertTrue(section.paletteCouldContain(state -> false), "谓词恒假也不例外（无法证明「不含」）");
    }
  }

  @Test
  void allAirSectionIsEmptyAndSkippable() {
    byte[] bytes = new TestChunkBuilder(MODERN)
        .singleValueSection(AIR, 0, 0, 0, new int[] {7})
        .build();

    try (Chunk chunk = decode(bytes, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertTrue(section.isEmpty(), "blockCount==0 → 全空气 section");
      assertFalse(section.paletteCouldContain(ORE), "全空气必然不含矿");
    }
  }
}