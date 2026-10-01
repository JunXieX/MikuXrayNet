package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import org.junit.jupiter.api.Test;

/**
 * 调色板降级（{@link ChunkSection#downgradePalette(boolean)}）测试：
 * <ol>
 *   <li><b>单值降级</b>：整节只剩一种方块时改用单值调色板（bitsPerEntry=0），payload 从约 2KB 降到约 10 字节，
 *       且方块序列逐格不变、{@code blockCount} 语义不变（有方块的节绝不被错判成空）；</li>
 *   <li><b>直接调色板 → 间接</b>：15 位直接调色板在被引用状态数 ≤256 时降为间接并取最小位宽（4~8 位），
 *       状态数 &gt;256 时保持直接（位宽单调不增）；</li>
 *   <li><b>未改动的 section 仍原样搬运</b>：只改一个 section 不得改变另一个 section 的原始字节；</li>
 *   <li><b>门控</b>：width-budget=false 时不做任何收缩/降级，行为与既有路径一致；</li>
 *   <li><b>自检</b>：verify=true 在健康数据上通过，且 ObfuscationProcessor 的 strict-verify 写回仍可往返。</li>
 * </ol>
 */
class ChunkSectionPaletteDowngradeTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int DEEPSLATE = 2;
  private static final int DIAMOND_ORE = 3;
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

  /** 4 位间接调色板：石头铺满 + 若干钻石矿（{@code i % 97 == 0}）。 */
  private static byte[] stoneWithOres() {
    int[] indices = new int[4096];
    for (int i = 0; i < indices.length; i++) {
      if (i % 97 == 0) {
        indices[i] = 1; // DIAMOND_ORE
      }
    }
    return new TestChunkBuilder(MODERN)
        .indirectSection(4, 4096, 0, new int[] {STONE, DIAMOND_ORE}, indices, 0, new int[] {1})
        .build();
  }

  /** 把 raw 里所有钻石矿替换为石头，返回替换后的期望方块序列。 */
  private static int[] replaceOresWithStone(ChunkSection section) {
    int[] states = section.readAllBlockStates();
    for (int i = 0; i < states.length; i++) {
      if (states[i] == DIAMOND_ORE) {
        section.setBlockState(i, STONE);
        states[i] = STONE;
      }
    }
    return states;
  }

  @Test
  void singleValueDowngradeShrinksPayloadAndPreservesSemantics() {
    byte[] raw = stoneWithOres();

    // 现状口径：只做 compact（kept==1 仍写 4 位间接调色板 ≈ 2KB）
    byte[] compactOnly;
    int[] expected;
    try (Chunk chunk = codec().decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      expected = replaceOresWithStone(section);
      assertTrue(section.compactPalette(false), "存在失效条目（矿）应发生裁剪");
      assertEquals(4, section.bitsPerBlock(), "前置：compact 之后仍是 4 位间接（单值降级尚未发生）");
      assertEquals(1, section.paletteSize());
      assertArrayEquals(expected, section.readAllBlockStates(), "裁剪不得改变方块序列");
      compactOnly = chunk.finalizeOutput();
    }

    // 本次改造：compact + downgrade → 单值调色板
    byte[] downgraded;
    try (Chunk chunk = codec().decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      int[] states = replaceOresWithStone(section);
      assertTrue(section.compactPalette(false));
      assertTrue(section.downgradePalette(true), "只剩一种方块时必须降为单值调色板");
      assertEquals(0, section.bitsPerBlock(), "单值调色板 bitsPerEntry=0");
      assertEquals(1, section.paletteSize());
      assertTrue(section.isModified(), "降级属于改写，必须置 modified（否则会被原样搬运）");
      assertArrayEquals(states, section.readAllBlockStates(), "降级不得改变方块序列");
      downgraded = chunk.finalizeOutput();
    }

    assertTrue(compactOnly.length > 2000,
        "现状（4 位间接）应约 2KB：实际 " + compactOnly.length + " 字节");
    assertTrue(downgraded.length < 64,
        "单值降级后应约 10 字节：实际 " + downgraded.length + " 字节");
    assertTrue(downgraded.length < compactOnly.length, "降级必须真正减小 payload");

    // 再解码：单值表示必须被正确读回；整节石头不得被错判成空节
    try (Chunk chunk = codec().decode(downgraded, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertFalse(section.isEmpty(), "整节石头 blockCount>0，绝不能被错判成空节");
      assertArrayEquals(expected, section.readAllBlockStates(), "解码 → 降级 → 再编码 → 再解码 语义逐格一致");
      assertArrayEquals(downgraded, chunk.finalizeOutput(), "单值降级结果必须可稳定往返");
    }
  }

  @Test
  void directToIndirectDowngradePicksMinimalBitWidthAndPreservesSemantics() {
    // 直接调色板（15 位）里只出现 200 种不同状态 → 应降为 8 位间接
    int[] states = new int[4096];
    for (int i = 0; i < states.length; i++) {
      states[i] = (i * 13) % 200; // 200 种不同状态，均非空气（AIR=0 会出现吗？(i*13)%200==0 于 i=0）
    }
    states[0] = 50; // 避开空气 id 0，令 blockCount=4096
    int blockCount = 0;
    for (int state : states) {
      if (state != AIR) {
        blockCount++;
      }
    }
    byte[] raw = new TestChunkBuilder(MODERN)
        .directSection(15, blockCount, 0, states, 0, new int[] {1})
        .build();

    try (Chunk chunk = codec().decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      int[] before = section.readAllBlockStates();
      assertEquals(15, section.bitsPerBlock(), "前置：直接调色板位宽取注册表上限");
      assertTrue(section.downgradePalette(true), "≤256 种状态的直接调色板应降为间接");

      assertEquals(8, section.bitsPerBlock(), "200 种状态 → 8 位间接（最小满足位宽）");
      assertEquals(200, section.paletteSize(), "间接调色板条目数 = 被引用状态数");
      assertArrayEquals(before, section.readAllBlockStates(), "直接 → 间接不得改变方块序列");
      assertTrue(section.isModified());

      byte[] out = chunk.finalizeOutput();
      assertTrue(out.length < raw.length, "降为 8 位间接后 payload 必须变小");
      try (Chunk again = codec().decode(out, 1)) {
        assertArrayEquals(before, again.getSection(0).readAllBlockStates(), "往返语义一致");
        assertArrayEquals(out, again.finalizeOutput(), "降级结果必须可稳定往返");
      }
    }
  }

  @Test
  void directToSingleValueWhenOnlyOneStateIsReferenced() {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, DEEPSLATE);
    byte[] raw = new TestChunkBuilder(MODERN)
        .directSection(15, 4096, 0, states, 0, new int[] {1})
        .build();

    try (Chunk chunk = codec().decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertTrue(section.downgradePalette(true), "只有一种状态的直接调色板应直接降为单值");
      assertEquals(0, section.bitsPerBlock());
      assertEquals(1, section.paletteSize());
      assertEquals(DEEPSLATE, section.getBlockState(0));
      assertFalse(section.isEmpty(), "整节深板岩不是空节");
    }
  }

  @Test
  void directPaletteWithMoreThan256StatesIsLeftUntouched() {
    // 300 种不同状态 > 256：无法降为间接（8 位上限 256），必须保持直接、位宽单调不增
    int[] states = new int[4096];
    for (int i = 0; i < states.length; i++) {
      states[i] = 1 + (i % 300);
    }
    byte[] raw = new TestChunkBuilder(MODERN)
        .directSection(15, 4096, 0, states, 0, new int[] {1})
        .build();

    try (Chunk chunk = codec().decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      int[] before = section.readAllBlockStates();
      assertFalse(section.downgradePalette(true), ">256 种状态不满足降级条件");
      assertEquals(15, section.bitsPerBlock(), "保持直接调色板 15 位");
      assertFalse(section.isModified(), "未发生降级不得置 modified（保证原样搬运）");
      assertArrayEquals(before, section.readAllBlockStates());
    }
  }

  @Test
  void untouchedSectionIsStillCopiedByteIdentical() {
    int[] palette = {STONE, DEEPSLATE};
    int[] indices = new int[4096];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = i % 3 == 0 ? 1 : 0;
    }
    byte[] raw = new TestChunkBuilder(MODERN)
        .indirectSection(4, 4096, 0, palette, indices, 0, new int[] {1})
        .singleValueSection(DEEPSLATE, 4096, 0, 0, new int[] {7})
        .build();

    byte[] rawSectionOne;
    byte[] out;
    try (Chunk chunk = codec().decode(raw, 2)) {
      assertFalse(chunk.getSection(1).isModified());
      Chunk.SectionRange rangeOne = chunk.originalSectionRange(1);
      rawSectionOne = slice(raw, rangeOne.offset(), rangeOne.length());

      // 只改 section 0（并做收缩/降级），section 1 应保持原样搬运
      ChunkSection section = chunk.getSection(0);
      section.setBlockState(0, 0, 0, STONE);
      section.compactPalette(false);
      section.downgradePalette(false);
      assertFalse(chunk.getSection(1).isModified(), "未触及的 section 不得被标记为已修改");
      out = chunk.finalizeOutput();
    }

    try (Chunk decoded = codec().decode(out, 2)) {
      Chunk.SectionRange rangeOne = decoded.originalSectionRange(1);
      assertArrayEquals(rawSectionOne, slice(out, rangeOne.offset(), rangeOne.length()),
          "未改动的 section 必须逐字节原样搬运");
    }
  }

  /** 用真机同款处理器验证门控：width-budget=false 不做降级，true 则把整节压成单值。 */
  @Test
  void widthBudgetGateControlsDowngrade() {
    BitSet targets = new BitSet();
    targets.set(DIAMOND_ORE);
    int[] replacement = {STONE};
    int[] cumulative = {1};

    ObfuscationProcessor disabled = new ObfuscationProcessor(codec(), state -> true, targets,
        replacement, cumulative, false, true,
        new ObfuscationProcessor.PaletteOptions(false, false), true);
    ObfuscationProcessor enabled = new ObfuscationProcessor(codec(), state -> true, targets,
        replacement, cumulative, false, true,
        new ObfuscationProcessor.PaletteOptions(true, true), true);

    byte[] raw = stoneWithOres();

    ObfuscationProcessor.Result withoutBudget = disabled.rewrite(raw, 1, 7L);
    assertTrue(withoutBudget.changed());
    assertEquals(4, withoutBudget.sectionBits()[0], "width-budget=false：替换照常，但位宽维持在 4 位（不降级）");

    ObfuscationProcessor.Result withBudget = enabled.rewrite(raw, 1, 7L);
    assertTrue(withBudget.changed());
    assertEquals(0, withBudget.sectionBits()[0], "width-budget=true 且 strict-verify=true：整节压成单值调色板");
    assertTrue(withBudget.data().length < withoutBudget.data().length, "开启降级后包体必须更小");

    try (Chunk chunk = codec().decode(withBudget.data(), 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(0, section.bitsPerBlock());
      assertFalse(section.isEmpty());
      for (int state : section.readAllBlockStates()) {
        assertEquals(STONE, state, "替换后整节都应是伪装方块（石头）");
      }
      assertArrayEquals(withBudget.data(), chunk.finalizeOutput(), "strict-verify 写回必须可稳定往返");
    }
  }

  private static byte[] slice(byte[] data, int offset, int length) {
    byte[] out = new byte[length];
    System.arraycopy(data, offset, out, 0, length);
    return out;
  }
}