package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.BitSet;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkSection;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import org.junit.jupiter.api.Test;

/**
 * 窄声明位宽的直接（direct）调色板段：伪装候选**不得被位打包掩码静默截断**。
 *
 * <p>背景（真机取证）：服务端按调色板规模选择直接格式的声明位宽（{@code Strategy} 的
 * {@code bitsInStorage}），257~512 种状态会下发 9 位（可表示 0..511）。而配置里的伪装方块
 * （深板岩等）状态 id 可能远大于 511。若直接把该 id 写进位打包数据，{@code SimpleVarBitBuffer.set}
 * 会按 9 位掩码把它截断成另一个状态——客户端看到毫不相干的方块，且不计数、不 fail-open。
 *
 * <p>正确方向：直接调色板不能 grow，放不下的候选必须被预算筛选剔除、回落到能放下的候选；
 * 全部放不下时跳过该格（宁可少伪装这一块，绝不写错方块）。
 *
 * <p>夹具取值刻意让「矿」能放进 9 位（{@value #ORE}=5）、「深板岩」放不进（{@value #DEEPSLATE}=28000），
 * 这样才真正检验「候选放不下时的回落」，而不是被夹具自身的截断掩盖。
 */
class ObfuscationProcessorDirectWidthTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int ORE = 5;
  private static final int DEEPSLATE = 28000;
  private static final int REGISTRY_BITS = 16;

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
        return 1 << REGISTRY_BITS;
      }

      @Override
      public int paletteCapacity() {
        return 1 << 18;
      }

      @Override
      public int getMaxBitsPerBlockState() {
        return REGISTRY_BITS;
      }
    };
  }

  private static ObfuscationProcessor processor(int... replacementIds) {
    int[] cumulative = new int[replacementIds.length];
    for (int i = 0; i < replacementIds.length; i++) {
      cumulative[i] = i + 1;
    }
    BitSet targets = new BitSet();
    targets.set(ORE);
    return new ObfuscationProcessor(new ChunkCodec(registry(), MODERN), blockId -> true, targets,
        replacementIds, cumulative, false, true,
        new ObfuscationProcessor.PaletteOptions(false, true), true);
  }

  /** 石头铺满、第 100 格放一枚矿。 */
  private static byte[] section(int bits) {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, STONE);
    states[100] = ORE;
    return new TestChunkBuilder(MODERN).directSection(bits, 4096, 0, states, 0, new int[] {7}).build();
  }

  private static int[] decodeStates(byte[] data) {
    int[] states = new int[4096];
    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    try (Chunk chunk = codec.decode(data, 1)) {
      ChunkSection section = chunk.getSection(0);
      for (int i = 0; i < 4096; i++) {
        states[i] = section.getBlockState(i);
      }
    }
    return states;
  }

  /**
   * 9 位 direct（0..511）里放一枚矿：候选表里的深板岩 28000 放不下，
   * 必须回落到放得下的石头 1，而不是把 28000 截断成 {@code 28000 & 511 = 352}。
   */
  @Test
  void unrepresentableCandidateFallsBackInsteadOfBeingTruncated() {
    byte[] raw = section(9);

    ObfuscationProcessor.Result result = processor(DEEPSLATE, STONE).rewrite(raw, 1, 42L);

    assertEquals(1, result.obfuscatedPositions().length, "该矿必须被伪装（不得整块放弃）");
    int[] rewritten = decodeStates(result.data());
    assertEquals(STONE, rewritten[100], "必须回落到放得下的候选（石头）");
    assertNotEquals(DEEPSLATE & 511, rewritten[100], "绝不能是位宽掩码截断后的残值 352");
    for (int i = 0; i < 4096; i++) {
      if (i != 100) {
        assertEquals(STONE, rewritten[i], "其余格不得改动：" + i);
      }
    }
  }

  /** 候选全都放不下（都 ≥ 512）：跳过该格，绝不写入被截断的 id。 */
  @Test
  void whenNoCandidateFitsTheCellIsSkipped() {
    byte[] raw = section(9);

    ObfuscationProcessor.Result result = processor(DEEPSLATE).rewrite(raw, 1, 42L);

    assertEquals(0, result.obfuscatedPositions().length,
        "全部候选放不下 → 该格跳过（宁可少伪装，绝不写错方块）");
    assertArrayEquals(raw, result.data(), "无改动即原字节放行");
  }

  /** 16 位 direct（0..65535）放得下深板岩：必须真的用上它（位宽优化可另降级，语义不变）。 */
  @Test
  void representableCandidateIsUsedAsConfigured() {
    byte[] raw = section(16);

    ObfuscationProcessor.Result result = processor(DEEPSLATE).rewrite(raw, 1, 42L);

    assertEquals(1, result.obfuscatedPositions().length);
    int[] rewritten = decodeStates(result.data());
    assertEquals(DEEPSLATE, rewritten[100], "16 位放得下，必须按配置伪装成深板岩");
    for (int i = 0; i < 4096; i++) {
      if (i != 100) {
        assertEquals(STONE, rewritten[i], "其余格不得改动：" + i);
      }
    }
  }

  /**
   * 直接调色板的 {@code contains} 必须如实反映声明位宽：这是预算筛选与 use-block-below
   * 判断「能不能写进去」的唯一依据。
   */
  @Test
  void directPaletteContainsReflectsDeclaredWidth() {
    byte[] narrow = section(9);
    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    try (Chunk chunk = codec.decode(narrow, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(true, section.paletteContains(511), "9 位可表示 511");
      assertEquals(false, section.paletteContains(512), "9 位表示不了 512");
      assertEquals(false, section.paletteContains(DEEPSLATE), "9 位表示不了深板岩");
    }

    byte[] wide = section(16);
    try (Chunk chunk = codec.decode(wide, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(true, section.paletteContains(DEEPSLATE), "16 位可表示深板岩");
    }
  }
}
