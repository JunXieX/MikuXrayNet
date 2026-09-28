package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkSection;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import org.junit.jupiter.api.Test;

/**
 * P0-1「调色板位宽预算封顶 + 失效条目裁剪」测试。
 *
 * <p>用桩注册表构造 4 位/5 位满调色板 section（<b>所有调色板条目都被引用</b>，模拟真实区块），
 * 验证：
 * <ol>
 *   <li><b>封顶</b>：调色板写满时替换优先选用调色板内候选，位宽不升（修「4 位 → 5 位包体 +25%」）；</li>
 *   <li><b>裁剪降位</b>：改写后引用计数为 0 的失效条目（被替换掉的矿）被移除并压缩索引，
 *       条目数降到更低档位时位宽一并下降（位宽单调不增），且方块序列语义完全不变；</li>
 *   <li><b>逃生口</b>：调色板内无任何候选时仍允许 grow 升位（保防护红线：宁可包体大，不可漏伪装），
 *       且 grow 后的字节可正常解码；</li>
 *   <li><b>关闭开关</b>：width-budget=false 走旧路径（grow 后不裁剪，位宽停留在高位）。</li>
 * </ol>
 */
class ObfuscationProcessorWidthBudgetTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int DEEPSLATE = 2;
  private static final int NETHERRACK = 3;
  private static final int GLOWSTONE = 7;
  private static final int DIAMOND_ORE = 4;
  private static final int IRON_ORE = 5;
  private static final int GOLD_ORE = 6;
  private static final int BITS = 15;

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

  /** 候选等权（各 1）的处理器；{@code widthBudget} 控制是否走 P0-1 路径。 */
  private static ObfuscationProcessor processor(BitSet targets, int[] replacementIds,
      boolean widthBudget) {
    int[] cumulative = new int[replacementIds.length];
    for (int i = 0; i < replacementIds.length; i++) {
      cumulative[i] = i + 1;
    }
    return new ObfuscationProcessor(codec(), blockId -> true, targets, replacementIds, cumulative,
        false, true,
        widthBudget
            ? new ObfuscationProcessor.PaletteOptions(false, false, true)
            : new ObfuscationProcessor.PaletteOptions(false, false, false),
        true);
  }

  private static BitSet targets(int... states) {
    BitSet targets = new BitSet();
    for (int state : states) {
      targets.set(state);
    }
    return targets;
  }

  /** 组装一个间接调色板 section（{@code indices[i] = 调色板下标}），全部条目须被引用。 */
  private static byte[] indirectChunk(int bits, int[] palette, int[] indices) {
    int blockCount = 0;
    for (int index : indices) {
      if (palette[index] != AIR) {
        blockCount++;
      }
    }
    return new TestChunkBuilder(MODERN)
        .indirectSection(bits, blockCount, 0, palette, indices, 0, new int[] {1})
        .build();
  }

  /**
   * 「满 4 位调色板」造数：16 条全部被引用——石头铺满、3 种矿各占若干位置、
   * 12 个填充状态分别出现在 100..111 号位置（不与矿位重叠）。
   */
  private static int[] fullFourBitPalette(int[] indices) {
    int[] palette = {STONE, DIAMOND_ORE, IRON_ORE, GOLD_ORE, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24, 25};
    java.util.Arrays.fill(indices, 0); // 石头
    for (int i = 4; i < 16; i++) {
      indices[100 + (i - 4)] = i; // 填充条目逐一被引用
    }
    indices[200] = 2; // 铁矿（不被替换场景中保持引用）
    indices[300] = 3; // 金矿
    return palette;
  }

  /** 解码输出字节，读取首个 section 在 {@code index} 处的方块状态。 */
  private static int decodedState(byte[] data, int index) {
    try (Chunk chunk = codec().decode(data, 1)) {
      return chunk.getSection(0).getBlockState(index);
    }
  }

  @Test
  void fullPaletteReplacementStaysAtFourBitsAndPicksInPaletteCandidate() {
    // 满 4 位调色板（16/16），候选 = [石头(在调色板内), 下界岩(不在)]：8 个钻石矿位待替换
    int[] indices = new int[4096];
    int[] palette = fullFourBitPalette(indices);
    int[] orePositions = new int[15];
    for (int i = 0; i < orePositions.length; i++) {
      orePositions[i] = 273 * (i + 1); // 与 100..111/200/300 不重叠
      indices[orePositions[i]] = 1;    // 钻石矿
    }
    byte[] source = indirectChunk(4, palette, indices);
    ObfuscationProcessor.Result result = processor(targets(DIAMOND_ORE), new int[] {STONE, NETHERRACK},
        true).rewrite(source, 1, 42L);

    assertTrue(result.changed(), "矿应被伪装替换");
    // 封顶：剩余空位 0，候选中下界岩不在调色板内被剔除，只选石头 → 不 grow
    assertEquals(4, result.sectionBits()[0], "满 4 位调色板替换不得升位（预算封顶）");
    for (int position : orePositions) {
      assertEquals(STONE, decodedState(result.data(), position), "应选用调色板内的石头当伪装方块");
    }
    assertEquals(IRON_ORE, decodedState(result.data(), 200), "非目标的铁矿不得被改动");
    // 裁剪：钻石矿条目失效被移除（16 → 15 条），位打包位宽不变 → 输出严格变小
    assertTrue(result.data().length < source.length,
        "裁剪失效条目后输出应小于原始（原始 " + source.length + " → 输出 " + result.data().length + "）");
    // 往返：再解码并重新编码，字节必须稳定（裁剪结果确定，缓存可复用）
    try (Chunk chunk = codec().decode(result.data(), 1)) {
      assertArrayEquals(result.data(), chunk.finalizeOutput(), "裁剪结果必须可稳定往返");
    }
  }

  @Test
  void pruneLowersBitWidthFromFiveToFourAndPreservesSemantics() {
    // 5 位 20 条全部被引用：4 种矿全部换成调色板内的石头 → 失效 4 条 → 16 条 → 降回 4 位
    int[] palette = {STONE, DIAMOND_ORE, IRON_ORE, GOLD_ORE, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24, 25, 26, 27, 28, 29};
    int[] indices = new int[4096];
    java.util.Arrays.fill(indices, 0);
    for (int i = 4; i < 20; i++) {
      indices[100 + (i - 4)] = i; // 填充条目逐一被引用
    }
    int[] orePositions = new int[8];
    for (int i = 0; i < orePositions.length; i++) {
      orePositions[i] = 273 * (i + 1);
      indices[orePositions[i]] = 1 + (i % 4); // 4 种「矿」（含 14 号填充位，也会被替换）
    }
    byte[] source = indirectChunk(5, palette, indices);
    int[] before;
    try (Chunk chunk = codec().decode(source, 1)) {
      before = chunk.getSection(0).readAllBlockStates();
    }

    ObfuscationProcessor.Result result = processor(
        targets(DIAMOND_ORE, IRON_ORE, GOLD_ORE, 14), new int[] {STONE}, true)
        .rewrite(source, 1, 7L);

    assertEquals(4, result.sectionBits()[0], "20 条裁到 16 条后应从 5 位降到 4 位（位宽单调不增）");
    // 语义：解码 → 裁剪 → 重编码 → 再解码，逐位置比对——目标位置换成石头，其余与原 section 一致
    try (Chunk chunk = codec().decode(result.data(), 1)) {
      int[] after = chunk.getSection(0).readAllBlockStates();
      for (int i = 0; i < after.length; i++) {
        boolean wasTarget = before[i] == DIAMOND_ORE || before[i] == IRON_ORE
            || before[i] == GOLD_ORE || before[i] == 14;
        assertEquals(wasTarget ? STONE : before[i], after[i], "位置 " + i + " 语义必须保持");
      }
      assertArrayEquals(result.data(), chunk.finalizeOutput(), "降位结果必须可稳定往返");
    }
  }

  @Test
  void escapeHatchGrowsWhenPaletteHasNoCandidate() {
    // 满 4 位调色板，候选 = [下界岩, 荧石]（都不在调色板内）→ 逃生口：允许 grow。
    // 15 个钻石矿位以 1:1 权重随机（Random(42) 序列确定，两个候选实际都会被采到）；
    // 仅钻石矿条目失效（-1 条）、两个新状态入板（+2）→ 17 条 → 5 位，裁剪无法再降。
    int[] indices = new int[4096];
    int[] palette = fullFourBitPalette(indices);
    int[] orePositions = new int[15];
    for (int i = 0; i < orePositions.length; i++) {
      orePositions[i] = 273 * (i + 1);
      indices[orePositions[i]] = 1;
    }
    byte[] source = indirectChunk(4, palette, indices);

    ObfuscationProcessor.Result result = processor(targets(DIAMOND_ORE),
        new int[] {NETHERRACK, GLOWSTONE}, true).rewrite(source, 1, 42L);

    assertTrue(result.changed(), "逃生口不得漏伪装：矿仍必须被替换");
    // 逃生口可见证据：调色板内无候选仍完成替换 → 必然发生 grow；两个候选都被采到时 17 条 → 5 位
    boolean bothUsed = true;
    boolean sawNetherrack = false;
    boolean sawGlowstone = false;
    for (int position : orePositions) {
      int state = decodedState(result.data(), position);
      if (state == NETHERRACK) {
        sawNetherrack = true;
      } else if (state == GLOWSTONE) {
        sawGlowstone = true;
      } else {
        bothUsed = false;
      }
    }
    assertTrue(sawNetherrack && sawGlowstone, "两个调色板外候选都应被采到（种子确定）");
    assertTrue(bothUsed, "替换结果必须落在候选集合内");
    assertEquals(5, result.sectionBits()[0],
        "15 条旧状态 + 2 个新状态 = 17 条，超出 4 位容量（逃生口 grow 的可见证据）");
    assertEquals(14, decodedState(result.data(), 100), "未替换位置不得被 grow 破坏（该位是填充条目 14）");
  }

  @Test
  void disabledBudgetKeepsLegacyPath() {
    // 关闭 width-budget（P0-1 之前行为）：替换照常 grow，且不裁剪——
    // 虽然替换后只剩 16 条（本可落回 4 位），位宽仍停留在 grow 后的 5 位。
    int[] indices = new int[4096];
    int[] palette = fullFourBitPalette(indices);
    int[] orePositions = new int[15];
    for (int i = 0; i < orePositions.length; i++) {
      orePositions[i] = 273 * (i + 1);
      indices[orePositions[i]] = 1;
    }
    byte[] source = indirectChunk(4, palette, indices);

    ObfuscationProcessor.Result result = processor(targets(DIAMOND_ORE), new int[] {NETHERRACK},
        false).rewrite(source, 1, 42L);

    assertTrue(result.changed());
    assertEquals(5, result.sectionBits()[0], "关闭预算时按旧路径 grow，不裁剪不降位");
    assertEquals(NETHERRACK, decodedState(result.data(), orePositions[0]));
  }

  @Test
  void compactPaletteRoundTripsSemanticsAndDownshiftsBits() {
    // ChunkSection 级直测：解码 → 制造失效条目 → 裁剪（verify 自检）→ 重编码 → 再解码，语义逐位一致
    int[] palette = {STONE, DIAMOND_ORE, IRON_ORE, GOLD_ORE, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24, 25, 26, 27, 28, 29};
    int[] indices = new int[4096];
    java.util.Arrays.fill(indices, 0);
    for (int i = 4; i < 20; i++) {
      indices[100 + (i - 4)] = i;
    }
    for (int i = 0; i < 8; i++) {
      indices[273 * (i + 1)] = 1 + (i % 3);
    }
    byte[] source = indirectChunk(5, palette, indices);

    try (Chunk chunk = codec().decode(source, 1)) {
      ChunkSection section = chunk.getSection(0);
      int[] expected = section.readAllBlockStates();

      // 把 3 种矿的所有出现位置替换为石头 → 3 条调色板条目引用计数归 0
      for (int i = 0; i < 4096; i++) {
        if (expected[i] == DIAMOND_ORE || expected[i] == IRON_ORE || expected[i] == GOLD_ORE) {
          section.setBlockState(i, STONE);
          expected[i] = STONE;
        }
      }
      assertTrue(section.compactPalette(true), "存在失效条目时应发生裁剪");
      assertEquals(17, section.paletteSize(), "失效条目应被移除（20 - 3）");
      assertEquals(5, section.bitsPerBlock(), "17 条仍处于 5 位档：位宽不变但索引已压缩");
      assertArrayEquals(expected, section.readAllBlockStates(), "裁剪不得改变方块序列");

      // 再裁一次触发降位：把 14 号状态唯一的出现位置替换掉 → 16 条 → 降回 4 位
      for (int i = 0; i < 4096; i++) {
        if (section.getBlockState(i) == 14) {
          section.setBlockState(i, STONE);
          expected[i] = STONE;
        }
      }
      assertTrue(section.compactPalette(true));
      assertEquals(16, section.paletteSize());
      assertEquals(4, section.bitsPerBlock(), "条目数降到 16 以内必须降位（位宽单调不增）");
      assertArrayEquals(expected, section.readAllBlockStates(), "降位重建不得改变方块序列");

      byte[] reencoded = chunk.finalizeOutput();
      try (Chunk again = codec().decode(reencoded, 1)) {
        assertArrayEquals(expected, again.getSection(0).readAllBlockStates(),
            "解码 → 裁剪 → 重编码 → 再解码 必须得到相同方块序列");
        assertArrayEquals(reencoded, again.finalizeOutput(), "裁剪结果必须可稳定往返");
      }
    }
  }
}
