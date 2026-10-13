package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.junit.jupiter.api.Test;

/**
 * 「岩浆按视觉遮挡处理」的隐藏侧处理测试——<b>岩浆已并入共享遮挡表，不再有独立开关</b>。
 *
 * <p>场景：下界残骸常刷在岩浆里——若把上方岩浆当作暴露面，enclosed 模式下残骸就不会被伪装，透视端一眼就能
 * 看到它。岩浆视觉不透明（服务端 {@code isOccluding()} 却返回 false），现由唯一规则入口
 * {@code OcclusionRules} 的视觉口径判为遮挡，因此这里直接由遮挡表驱动（不再有 {@code fluid-cover} 开关）。
 *
 * <p><b>水的行为</b>：水（含流动变体、水柱）是<b>透明</b>的——玩家在矿洞里应当能看见被水盖住的矿物，
 * 因此水<b>不算遮挡</b>（下方/上方是水 → 按暴露面处理 → 不伪装）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>上方岩浆 + 其余 5 面下界岩 → 被伪装；对照「上方空气」→ 不伪装、对照「上方水」→ 不伪装；</li>
 *   <li>跨 section：y+1 落在相邻 section 时，岩浆判遮挡、水判不遮挡各一条；</li>
 *   <li>越出区块上边界：按「非岩浆/非遮挡」原语义处理。</li>
 * </ol>
 */
class ObfuscationProcessorLavaOcclusionTest {

  private static final int AIR = 0;
  private static final int NETHERRACK = 1;
  private static final int LAVA = 2;
  private static final int DEBRIS = 3;
  private static final int WATER = 4;
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
        return blockId == LAVA || blockId == WATER;
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

  private static int index(int x, int y, int z) {
    return y << 8 | z << 4 | x;
  }

  private static int[] filled(int state) {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, state);
    return states;
  }

  /** enclosed 模式的档案（目标=残骸、伪装=下界岩）。 */
  private static ObfuscationProcessor.WorldProfile profile() {
    BitSet targets = new BitSet();
    targets.set(DEBRIS);
    return new ObfuscationProcessor.WorldProfile(targets, new int[] {NETHERRACK}, new int[] {1},
        new int[0], new int[0], new int[0][], new int[0][],
        Integer.MIN_VALUE, Integer.MAX_VALUE, false);
  }

  /**
   * enclosed 模式处理器：遮挡表按<b>视觉口径</b>建模——岩浆算遮挡（视觉不透明），水不算遮挡（透明）。
   * 岩浆已并入共享遮挡表，因此不再需要单独的岩浆掩码参数。
   */
  private static ObfuscationProcessor processor() {
    ObfuscationProcessor.WorldProfile profile = profile();
    return new ObfuscationProcessor(new ChunkCodec(registry(), MODERN),
        blockId -> blockId != AIR && blockId != WATER, false, true,
        new ObfuscationProcessor.PaletteOptions(false, false), state -> state != AIR, profile,
        new ObfuscationProcessor.WorldProfile[] {profile, profile, profile},
        new ObfuscationProcessor.WorldProfile[0][3], null, null, null);
  }

  private static int decodedState(byte[] data, int sectionCount, int sectionIndex, int index) {
    try (Chunk chunk = new ChunkCodec(registry(), MODERN).decode(data, sectionCount)) {
      return chunk.getSection(sectionIndex).getBlockState(index);
    }
  }

  private static ObfuscationProcessor.Result rewrite(ObfuscationProcessor processor, byte[] source,
      int sectionCount) {
    return processor.rewrite(source, sectionCount, 42L, null, "world_nether",
        AntiXrayConfig.Dimension.NETHER, 0);
  }

  /** 上方岩浆 + 其余 5 面下界岩 → 残骸被伪装（对照：上方空气 → 不伪装）。 */
  @Test
  void lavaAboveMakesTargetLooksBuried() {
    int target = index(8, 8, 8);

    int[] lavaAbove = filled(NETHERRACK);
    lavaAbove[target] = DEBRIS;
    lavaAbove[index(8, 9, 8)] = LAVA;
    byte[] lavaChunk = new TestChunkBuilder(MODERN)
        .directSection(BITS, 4096, 0, lavaAbove, 0, new int[] {7}).build();

    ObfuscationProcessor.Result withLava = rewrite(processor(), lavaChunk, 1);
    assertFalse(withLava.failed(), "不得出现解码/重编码异常：" + withLava.failure());
    assertTrue(withLava.changed(), "上方是岩浆时残骸必须被伪装");
    assertEquals(NETHERRACK, decodedState(withLava.data(), 1, 0, target),
        "被伪装的残骸应替换为下界岩");

    int[] airAbove = filled(NETHERRACK);
    airAbove[target] = DEBRIS;
    airAbove[index(8, 9, 8)] = AIR;
    byte[] airChunk = new TestChunkBuilder(MODERN)
        .directSection(BITS, 4096, 0, airAbove, 0, new int[] {7}).build();

    ObfuscationProcessor.Result withAir = rewrite(processor(), airChunk, 1);
    assertFalse(withAir.changed(), "对照：上方是空气（暴露面）时不得伪装（enclosed 语义）");
  }

  /** 上方是<b>水</b> → 残骸不伪装（水透明，玩家应当能看见水下的矿物，不算遮挡）。 */
  @Test
  void waterAboveDoesNotDisguiseTarget() {
    int target = index(8, 8, 8);

    int[] waterAbove = filled(NETHERRACK);
    waterAbove[target] = DEBRIS;
    waterAbove[index(8, 9, 8)] = WATER;
    byte[] waterChunk = new TestChunkBuilder(MODERN)
        .directSection(BITS, 4096, 0, waterAbove, 0, new int[] {7}).build();

    ObfuscationProcessor.Result result = rewrite(processor(), waterChunk, 1);
    assertFalse(result.failed(), "不得出现解码/重编码异常：" + result.failure());
    assertFalse(result.changed(),
        "上方是水（透明）→ 上方按暴露面处理 → 残骸不伪装");
  }

  /** 跨 section：目标在 section 0 顶部，y+1 落在 section 1 的底层——岩浆判遮挡、水判不遮挡各一条。 */
  @Test
  void lavaAboveAcrossSectionBoundary() {
    int target = index(8, 15, 8);
    int[] lower = filled(NETHERRACK);
    lower[target] = DEBRIS;

    // 相邻 section 底层放岩浆：目标「上方」跨 section 读到岩浆 → 视为遮挡 → 被伪装
    int[] upperLava = filled(NETHERRACK);
    upperLava[index(8, 0, 8)] = LAVA;
    byte[] lavaChunk = new TestChunkBuilder(MODERN)
        .directSection(BITS, 4096, 0, lower, 0, new int[] {7})
        .directSection(BITS, 4096, 0, upperLava, 0, new int[] {7})
        .build();

    ObfuscationProcessor.Result withLava = rewrite(processor(), lavaChunk, 2);
    assertTrue(withLava.changed(), "y+1 落在相邻 section 的岩浆上时必须判定为遮挡并伪装");
    assertEquals(1, withLava.obfuscatedPositions().length);
    assertEquals(target, withLava.obfuscatedPositions()[0], "只有被岩浆覆盖的那个残骸被伪装");
    assertEquals(NETHERRACK, decodedState(withLava.data(), 2, 0, target));

    // 跨 section 的水：透明 → 判非遮挡 → 不伪装
    int[] upperWater = filled(NETHERRACK);
    upperWater[index(8, 0, 8)] = WATER;
    byte[] waterChunk = new TestChunkBuilder(MODERN)
        .directSection(BITS, 4096, 0, lower, 0, new int[] {7})
        .directSection(BITS, 4096, 0, upperWater, 0, new int[] {7})
        .build();
    assertFalse(rewrite(processor(), waterChunk, 2).changed(),
        "对照：跨 section 的 y+1 是水时不伪装（水透明）");

    // 对照：相邻 section 底层是空气（非遮挡、非岩浆）→ 目标上方暴露 → 不伪装；
    // 该对照同时证明「被伪装」确实来自岩浆的视觉遮挡，而不是一般的遮挡判定。
    int[] upperAir = filled(NETHERRACK);
    upperAir[index(8, 0, 8)] = AIR;
    byte[] airChunk = new TestChunkBuilder(MODERN)
        .directSection(BITS, 4096, 0, lower, 0, new int[] {7})
        .directSection(BITS, 4096, 0, upperAir, 0, new int[] {7})
        .build();
    assertFalse(rewrite(processor(), airChunk, 2).changed(),
        "对照：跨 section 的 y+1 是空气时不伪装");
  }

  /** y+1 越出区块上边界：按「非岩浆/非遮挡」原语义处理（不得越界读取）。 */
  @Test
  void lavaAboveOutsideChunkTopIsNotOccluding() {
    int target = index(8, 15, 8);
    int[] single = filled(NETHERRACK);
    single[target] = DEBRIS;
    byte[] chunk = new TestChunkBuilder(MODERN)
        .directSection(BITS, 4096, 0, single, 0, new int[] {7}).build();

    ObfuscationProcessor.Result result = rewrite(processor(), chunk, 1);
    assertFalse(result.changed(), "最顶层方块的 y+1 越出区块 → 非岩浆，仍有暴露面，不得伪装");
  }
}
