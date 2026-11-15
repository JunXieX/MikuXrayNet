package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import org.junit.jupiter.api.Test;

/**
 * 含门（成对方块）的 section 在改写后必须<b>逐格语义不变</b>——非目标方块一格都不许动。
 *
 * <p>这是实机「门只开一半、另一半还挡人」在离线侧的对照：门的四种状态（下半/上半 × 关/开）在调色板里是
 * 四个不同条目，且往往排在靠后下标。一旦改写或裁剪把条目下标重映射错（1.34.1 的 {@code grow} 失同步
 * 就是这种形态），解码出来的门格会变成别的方块、或变成另一片的状态——客户端表现就是「有一片开了、
 * 另一片没开」，而这属于编码层错误，跟合并、丢弃、节流都无关，所以反复开关会换一片失败。
 *
 * <p>用例把 16 条满 4 位调色板全部引用，让「替换矿 → 条目失效 → 裁剪重排下标」这条路径必然走到，
 * 再逐格比对：门格必须保持自己的状态，且上下两半必须<b>互相不同</b>。
 */
class PaletteDoorSectionTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int DIAMOND_ORE = 4;
  private static final int GLOWSTONE = 7;
  /** 门的四种状态：真实注册表里是四个不同的全局状态 id。 */
  private static final int DOOR_LOWER_CLOSED = 40;
  private static final int DOOR_UPPER_CLOSED = 41;
  private static final int DOOR_LOWER_OPEN = 42;
  private static final int DOOR_UPPER_OPEN = 43;
  private static final int BITS = 15;
  private static final ChunkVersionFlags MODERN = ChunkVersionFlags.PAPER_26_2;

  /** 门柱坐标：同一根柱子的下半与上半（同一 section 内 y 与 y+1）。 */
  private static final int CLOSED_X = 5;
  private static final int CLOSED_Z = 6;
  private static final int CLOSED_Y = 4;
  private static final int OPEN_X = 9;
  private static final int OPEN_Z = 11;
  private static final int OPEN_Y = 4;
  /** 矿位：{@code x = i} 的四个位置（y/z 固定），与门柱和填充位都不重叠。 */
  private static final int ORE_Y = 8;
  private static final int ORE_Z = 3;

  /** 段内线性下标：{@code (y << 8) | (z << 4) | x}，各分量都在 0..15。 */
  private static int index(int x, int y, int z) {
    return (y << 8) | (z << 4) | x;
  }

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
        return 1 << BITS;
      }

      @Override
      public int getMaxBitsPerBlockState() {
        return BITS;
      }
    };
  }

  private static ChunkCodec codec() {
    return new ChunkCodec(registry(), MODERN);
  }

  private static ObfuscationProcessor processor(BitSet targets, boolean widthBudget) {
    int[] replacementIds = {STONE, GLOWSTONE};
    int[] cumulative = {1, 2};
    return new ObfuscationProcessor(codec(), blockId -> true, targets, replacementIds, cumulative,
        false, true, new ObfuscationProcessor.PaletteOptions(false, widthBudget), true);
  }

  private static BitSet targets(int... states) {
    BitSet targets = new BitSet();
    for (int state : states) {
      targets.set(state);
    }
    return targets;
  }

  /**
   * 造一个「16 条全部被引用」的 section：石头铺底、门的四种状态各占一格、{@code orePositions} 处放钻石矿、
   * 剩余条目逐一被引用（否则会被裁剪，就走不到重排下标那条路径）。
   */
  private static byte[] doorSection(int[] indices, int orePositions) {
    int[] palette = new int[16];
    palette[0] = STONE;
    palette[1] = DIAMOND_ORE;
    palette[2] = DOOR_LOWER_CLOSED;
    palette[3] = DOOR_UPPER_CLOSED;
    palette[4] = DOOR_LOWER_OPEN;
    palette[5] = DOOR_UPPER_OPEN;
    for (int i = 6; i < 16; i++) {
      palette[i] = 20 + (i - 6);
    }
    Arrays.fill(indices, 0);
    // 填充条目 6..15：线性下标 100..109，逐个引用，保证裁剪后仍是 16 条 → 位宽不降，重排必须正确
    for (int i = 6; i < 16; i++) {
      indices[100 + (i - 6)] = i;
    }
    // 门：两根柱子，各含下半与上半
    indices[index(CLOSED_X, CLOSED_Y, CLOSED_Z)] = 2;
    indices[index(CLOSED_X, CLOSED_Y + 1, CLOSED_Z)] = 3;
    indices[index(OPEN_X, OPEN_Y, OPEN_Z)] = 4;
    indices[index(OPEN_X, OPEN_Y + 1, OPEN_Z)] = 5;
    // 矿位
    for (int i = 0; i < orePositions; i++) {
      indices[index(i, ORE_Y, ORE_Z)] = 1;
    }
    int blockCount = 0;
    for (int slot : indices) {
      if (palette[slot] != AIR) {
        blockCount++;
      }
    }
    return new TestChunkBuilder(MODERN)
        .indirectSection(4, blockCount, 0, palette, indices, 0, new int[] {1})
        .build();
  }

  private static int decodedState(byte[] data, int slot) {
    try (Chunk chunk = codec().decode(data, 1)) {
      return chunk.getSection(0).getBlockState(slot);
    }
  }

  private static void assertDoorsIntact(byte[] data) {
    assertEquals(DOOR_LOWER_CLOSED, decodedState(data, index(CLOSED_X, CLOSED_Y, CLOSED_Z)),
        "关着的门下半必须原样保留");
    assertEquals(DOOR_UPPER_CLOSED, decodedState(data, index(CLOSED_X, CLOSED_Y + 1, CLOSED_Z)),
        "关着的门上半必须原样保留");
    assertEquals(DOOR_LOWER_OPEN, decodedState(data, index(OPEN_X, OPEN_Y, OPEN_Z)),
        "开着的门下半必须原样保留");
    assertEquals(DOOR_UPPER_OPEN, decodedState(data, index(OPEN_X, OPEN_Y + 1, OPEN_Z)),
        "开着的门上半必须原样保留");
    assertNotEquals(decodedState(data, index(CLOSED_X, CLOSED_Y, CLOSED_Z)),
        decodedState(data, index(CLOSED_X, CLOSED_Y + 1, CLOSED_Z)),
        "上下两半必须是两个不同状态（塌成同一个就是客户端只开一半的成因）");
  }

  private static void assertFillersIntact(byte[] data) {
    for (int i = 6; i < 16; i++) {
      assertEquals(20 + (i - 6), decodedState(data, 100 + (i - 6)),
          "非目标格不得被改动（线性下标 " + (100 + i - 6) + "）");
    }
    assertEquals(STONE, decodedState(data, index(0, 0, 0)), "铺底石头不得被改动");
  }

  @Test
  void doorsSurviveRewriteWithWidthBudget() {
    int[] indices = new int[4096];
    byte[] source = doorSection(indices, 4);

    ObfuscationProcessor.Result result = processor(targets(DIAMOND_ORE), true).rewrite(source, 1, 7L);

    assertTrue(result.changed(), "有矿位时必须发生改写（否则本用例没覆盖到裁剪重排路径）");
    assertDoorsIntact(result.data());
    assertFillersIntact(result.data());
    for (int i = 0; i < 4; i++) {
      assertEquals(STONE, decodedState(result.data(), index(i, ORE_Y, ORE_Z)),
          "矿位必须被替换成伪装方块（不得漏伪装）");
    }
  }

  @Test
  void doorsSurviveRewriteWithoutWidthBudget() {
    int[] indices = new int[4096];
    byte[] source = doorSection(indices, 4);

    ObfuscationProcessor.Result result =
        processor(targets(DIAMOND_ORE), false).rewrite(source, 1, 7L);

    assertTrue(result.changed());
    assertDoorsIntact(result.data());
    for (int i = 0; i < 4; i++) {
      int state = decodedState(result.data(), index(i, ORE_Y, ORE_Z));
      // 关闭位宽预算时允许扩大调色板，两个候选都可能被选中：只要求「不再是矿、且是候选之一」。
      assertNotEquals(DIAMOND_ORE, state, "矿位不得漏伪装");
      assertTrue(state == STONE || state == GLOWSTONE, "伪装方块必须是候选之一：" + state);
    }
  }

  @Test
  void rewrittenSectionRoundTripsStably() {
    int[] indices = new int[4096];
    byte[] source = doorSection(indices, 4);
    ObfuscationProcessor.Result result = processor(targets(DIAMOND_ORE), true).rewrite(source, 1, 7L);
    try (Chunk chunk = codec().decode(result.data(), 1)) {
      assertTrue(Arrays.equals(result.data(), chunk.finalizeOutput()),
          "改写结果必须能稳定往返（否则缓存里存的就是错字节）");
    }
  }
}
