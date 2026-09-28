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
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * use-block-below 伪装策略（P2-6a）：开启后伪装方块 = 下方紧邻方块（观感自然），
 * 跨 section 边界读取正确，关闭时行为与既有完全一致，且绝不把「真实目标方块」当伪装方块。
 *
 * <p>用合成状态表离线验证（无 PE 依赖）：AIR=0 / STONE=1 / DIRT=2 / WATER=3 / DIAMOND_ORE=4。
 * 伪装权重只放 DIRT 或 STONE，使「下方取值」与「权重回退」的结果可区分。
 */
class UseBlockBelowObfuscationTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int DIRT = 2;
  private static final int WATER = 3;
  private static final int DIAMOND_ORE = 4;
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
        return blockId == WATER;
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

  private static BitSet oreTargets() {
    BitSet targets = new BitSet();
    targets.set(DIAMOND_ORE);
    return targets;
  }

  /** 「下方可用」= 非空气、非流体（与生产装配传入的注册表过滤器同语义）。 */
  private static final java.util.function.IntPredicate BELOW_USABLE =
      state -> state != AIR && state != WATER;

  /** use-block-below 开启、伪装方块回退值为 {@code fallback} 的处理器。 */
  private static ObfuscationProcessor processor(boolean useBlockBelow, int fallback,
      boolean obfuscateAll) {
    return new ObfuscationProcessor(new ChunkCodec(registry(), MODERN),
        blockId -> blockId != AIR, oreTargets(), new int[] {fallback}, new int[] {1}, false,
        true, ObfuscationProcessor.PaletteOptions.DISABLED, obfuscateAll, useBlockBelow,
        BELOW_USABLE);
  }

  private static int[] filled(int state) {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, state);
    return states;
  }

  /** 段内序号：{@code y << 8 | z << 4 | x}。 */
  private static int index(int x, int y, int z) {
    return y << 8 | z << 4 | x;
  }

  private static int decodedState(byte[] data, int sectionCount, int sectionIndex, int index) {
    try (Chunk chunk = new ChunkCodec(registry(), MODERN).decode(data, sectionCount)) {
      return chunk.getSection(sectionIndex).getBlockState(index);
    }
  }

  // ---------------------------------------------------------- 开启后的行为

  /** 下方是普通方块（石头）时：伪装方块 = 下方方块，而不是权重回退值（泥土）。 */
  @Test
  void camouflageUsesBlockBelowWhenUsable() {
    int[] states = filled(STONE);
    states[index(8, 8, 8)] = DIAMOND_ORE;
    byte[] source = new TestChunkBuilder(MODERN).directSection(15, 4096, 0, states, 0,
        new int[] {7}).build();

    ObfuscationProcessor.Result result = processor(true, DIRT, true).rewrite(source, 1, 42L, null);

    assertTrue(result.changed(), "目标矿必须被伪装");
    assertEquals(STONE, decodedState(result.data(), 1, 0, index(8, 8, 8)),
        "开启 use-block-below 后伪装方块必须是下方方块（石头），而非权重回退值（泥土）");
  }

  /** 下方是空气/流体（不可用）时：回退到权重随机伪装，绝不复制空气或流体。 */
  @Test
  void unusableBlockBelowFallsBackToWeightedReplacement() {
    int[] airBelow = filled(STONE);
    airBelow[index(8, 8, 8)] = DIAMOND_ORE;
    airBelow[index(8, 7, 8)] = AIR;
    byte[] airSource = new TestChunkBuilder(MODERN).directSection(15, 4096, 0, airBelow, 0,
        new int[] {7}).build();

    ObfuscationProcessor.Result airResult = processor(true, DIRT, true).rewrite(airSource, 1, 42L,
        null);
    assertTrue(airResult.changed(), "下方是空气时仍必须伪装（不漏伪装）");
    assertEquals(DIRT, decodedState(airResult.data(), 1, 0, index(8, 8, 8)),
        "下方不可用时回退到权重随机（泥土），不得复制空气");

    int[] waterBelow = filled(STONE);
    waterBelow[index(8, 8, 8)] = DIAMOND_ORE;
    waterBelow[index(8, 7, 8)] = WATER;
    byte[] waterSource = new TestChunkBuilder(MODERN).directSection(15, 4096, 0, waterBelow, 0,
        new int[] {7}).build();

    ObfuscationProcessor.Result waterResult = processor(true, DIRT, true).rewrite(waterSource, 1,
        42L, null);
    assertTrue(waterResult.changed(), "下方是流体时仍必须伪装（不漏伪装）");
    assertEquals(DIRT, decodedState(waterResult.data(), 1, 0, index(8, 8, 8)),
        "下方是流体时回退到权重随机，不得把流体当伪装方块");
  }

  /** 跨 section 边界：伪装方块在 section 顶部（localY=0），下方方块在邻 section 的底层。 */
  @Test
  void crossSectionBelowIsReadCorrectly() {
    int[] lower = filled(STONE);
    for (int x = 0; x < 16; x++) {
      for (int z = 0; z < 16; z++) {
        lower[index(x, 15, z)] = DIRT; // 下层 section 的顶面全部是泥土
      }
    }
    int[] upper = filled(STONE);
    upper[index(3, 0, 5)] = DIAMOND_ORE; // 上层 section 的最底一格（localY=0）

    byte[] source = new TestChunkBuilder(MODERN)
        .directSection(15, 4096, 0, lower, 0, new int[] {7})
        .directSection(15, 4096, 0, upper, 0, new int[] {7})
        .build();

    ObfuscationProcessor.Result result = processor(true, STONE, true).rewrite(source, 2, 42L, null);

    assertTrue(result.changed(), "跨 section 的目标矿必须被伪装");
    int position = index(3, 0, 5) + (1 << 4 << 8); // baseY = sectionIndex(1) << 4
    assertEquals(1, result.obfuscatedPositions().length);
    assertEquals(position, result.obfuscatedPositions()[0], "伪装位置编码必须正确");
    assertEquals(DIRT, decodedState(result.data(), 2, 1, index(3, 0, 5)),
        "伪装方块必须取邻 section（下层）顶面的泥土，而非权重回退值（石头）");
    assertEquals(DIRT, decodedState(result.data(), 2, 0, index(3, 15, 5)),
        "下层 section 未命中目标，必须原样保留");
  }

  /** 下方方块已被本轮伪装时：读到的是伪装结果（伪装自然向上延续），绝不复制真实矿。 */
  @Test
  void camouflagePropagatesFromAlreadyObfuscatedBelow() {
    int[] states = filled(STONE);
    states[index(8, 7, 8)] = DIRT;        // 最底下是泥土：下方的矿会取它当伪装
    states[index(8, 8, 8)] = DIAMOND_ORE; // 下方的矿（先被处理 → 变成泥土）
    states[index(8, 9, 8)] = DIAMOND_ORE; // 上方的矿（读下方 = 伪装结果）
    byte[] source = new TestChunkBuilder(MODERN).directSection(15, 4096, 0, states, 0,
        new int[] {7}).build();

    // 权重回退值是石头：上方矿若拿到泥土，只能来自「读取下方已被改写的状态」（而非回退）
    ObfuscationProcessor.Result result = processor(true, STONE, true).rewrite(source, 1, 42L, null);

    assertEquals(2, result.obfuscatedPositions().length, "两格矿都必须被伪装");
    assertEquals(DIRT, decodedState(result.data(), 1, 0, index(8, 8, 8)),
        "下方的矿取下方泥土当伪装");
    assertEquals(DIRT, decodedState(result.data(), 1, 0, index(8, 9, 8)),
        "上方的矿取下方已被伪装的方块（泥土）当伪装——真实矿状态绝不能被复制，且伪装自然向上延续");
    assertFalse(result.data() == source, "发生伪装时必须重编码输出");
  }

  /** 红线（不漏伪装）：enclosed 模式下未被伪装的裸矿（暴露面）绝不能被当伪装方块复制。 */
  @Test
  void unobfuscatedTargetBelowIsNeverCopiedAsCamouflage() {
    int[] states = filled(STONE);
    states[index(8, 7, 8)] = AIR; // 让下面的矿在下方暴露 → enclosed 模式不伪装它
    states[index(8, 8, 8)] = DIAMOND_ORE; // 裸矿：保持真实状态
    states[index(8, 9, 8)] = DIAMOND_ORE; // 被上面的方块…全遮挡，会被伪装
    byte[] source = new TestChunkBuilder(MODERN).directSection(15, 4096, 0, states, 0,
        new int[] {7}).build();

    ObfuscationProcessor processor = processor(true, STONE, false); // enclosed 模式
    ObfuscationProcessor.Result result = processor.rewrite(source, 1, 42L, null);

    assertTrue(result.changed(), "上方的矿（6 面全遮挡）必须被伪装");
    assertEquals(DIAMOND_ORE, decodedState(result.data(), 1, 0, index(8, 8, 8)),
        "裸矿保持真实状态（enclosed 既有行为）");
    assertEquals(STONE, decodedState(result.data(), 1, 0, index(8, 9, 8)),
        "上方矿的伪装必须回退到权重随机（石头）：真实矿状态绝不能被当伪装方块复制");
  }

  // ---------------------------------------------------------- 关闭时的行为

  /** 关闭 use-block-below（默认）：行为与既有完全一致（按权重随机，无视下方方块）。 */
  @Test
  void disabledKeepsLegacyWeightedBehavior() {
    int[] states = filled(STONE);
    states[index(8, 8, 8)] = DIAMOND_ORE;
    byte[] source = new TestChunkBuilder(MODERN).directSection(15, 4096, 0, states, 0,
        new int[] {7}).build();

    ObfuscationProcessor.Result result = processor(false, DIRT, true).rewrite(source, 1, 42L,
        null);

    assertTrue(result.changed(), "目标矿必须被伪装");
    assertEquals(DIRT, decodedState(result.data(), 1, 0, index(8, 8, 8)),
        "关闭时必须仍按权重随机（泥土），不得受下方方块影响");
  }

  // ------------------------------------------------ P0-2 × P2-6a 交叉：逐世界覆盖

  private static final int GOLD_ORE = 5;
  private static final int NETHERRACK = 6;

  private static BitSet bitSet(int... states) {
    BitSet targets = new BitSet();
    for (int state : states) {
      targets.set(state);
    }
    return targets;
  }

  /** 从 YAML 构造配置（仅用于 matchOverride 命中覆盖段；方块 id 由测试手工给定）。 */
  private static AntiXrayConfig config(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return AntiXrayConfig.from(configuration);
  }

  /**
   * 逐世界处理器：默认档案 targets={钻石矿}；{@code world_nether} 覆盖段<b>新增</b>金矿为目标
   * （全局清单不含）——覆盖段档案与 {@code create()} 的解析产物同构。
   */
  private static ObfuscationProcessor perWorldProcessor(boolean obfuscateAll) {
    ObfuscationProcessor.WorldProfile defaultProfile = new ObfuscationProcessor.WorldProfile(
        bitSet(DIAMOND_ORE), new int[] {STONE}, new int[] {1},
        new int[0], new int[0], new int[0][], new int[0][],
        Integer.MIN_VALUE, Integer.MAX_VALUE, obfuscateAll);
    ObfuscationProcessor.WorldProfile netherProfile = new ObfuscationProcessor.WorldProfile(
        bitSet(DIAMOND_ORE, GOLD_ORE), new int[] {NETHERRACK}, new int[] {1},
        new int[0], new int[0], new int[0][], new int[0][],
        Integer.MIN_VALUE, Integer.MAX_VALUE, obfuscateAll);
    AntiXrayConfig config = config("""
        world-overrides:
          world_nether:
            obfuscation:
              replacement-weights:
                netherrack: 1
        """);
    return new ObfuscationProcessor(new ChunkCodec(registry(), MODERN), blockId -> blockId != AIR,
        false, true, ObfuscationProcessor.PaletteOptions.DISABLED, true, BELOW_USABLE,
        defaultProfile, new ObfuscationProcessor.WorldProfile[] {netherProfile}, config);
  }

  /** P0-2 × P2-6a 红线：世界覆盖段新增的目标矿（全局清单不含）绝不能被当伪装方块复制。 */
  @Test
  void perWorldTargetBelowIsNeverCopiedAsCamouflage() {
    int[] states = filled(STONE);
    states[index(8, 6, 8)] = AIR;       // 让 (8,7,8) 的矿在下方暴露 → enclosed 模式不伪装它
    states[index(8, 7, 8)] = GOLD_ORE;  // 世界覆盖新增的目标矿（裸矿，保持真实状态）
    states[index(8, 8, 8)] = GOLD_ORE;  // 6 面全遮挡 → 会被伪装
    byte[] source = new TestChunkBuilder(MODERN).directSection(15, 4096, 0, states, 0,
        new int[] {7}).build();

    ObfuscationProcessor processor = perWorldProcessor(false); // enclosed 模式
    ObfuscationProcessor.Result result = processor.rewrite(source, 1, 42L, null, "world_nether", 0);

    assertTrue(result.changed(), "全遮挡的目标矿必须被伪装");
    assertEquals(GOLD_ORE, decodedState(result.data(), 1, 0, index(8, 7, 8)),
        "裸矿保持真实状态（enclosed 既有行为，世界覆盖新增目标同样生效）");
    assertEquals(NETHERRACK, decodedState(result.data(), 1, 0, index(8, 8, 8)),
        "上方矿必须回退到该世界的权重随机（下界岩）：世界覆盖新增的目标矿绝不能被当伪装方块复制");
  }

  // ------------------------------------------------ P0-1 × P2-6a 交叉：位宽预算联动

  /**
   * 构造「下 section 为 direct、上 section 为 {@code palette}/{@code indices} 定义的 4 位间接」
   * 的两段区块：矿在上 section 的 localY=0（index 0），下方方块从邻 section 读出。
   */
  private static byte[] indirectOverDirect(int[] palette, int[] indices, int[] lowerStates) {
    return new TestChunkBuilder(MODERN)
        .directSection(15, 4096, 0, lowerStates, 0, new int[] {7})
        .indirectSection(4, 4096, 0, palette, indices, 0, new int[] {7})
        .build();
  }

  /** 预算联动（无空位）：调色板已满且下方方块不在调色板内 → 退回候选表，绝不引入新状态升位。 */
  @Test
  void belowOutsidePaletteWithNoFreeSlotFallsBackToBudgetedTable() {
    // 满 4 位调色板（16/16，free=0），全部条目被引用
    int[] palette = {STONE, DIAMOND_ORE, DIRT, WATER, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25};
    int[] indices = new int[4096];
    java.util.Arrays.fill(indices, 0);
    for (int i = 4; i < 16; i++) {
      indices[100 + (i - 4)] = i;
    }
    indices[0] = 1; // 目标矿（localY=0 → 下方在邻 section）
    int[] lower = filled(STONE);
    lower[(15 << 8)] = 7; // 下方方块=状态7：可用（非空气/流体）但不在上 section 调色板内
    byte[] source = indirectOverDirect(palette, indices, lower);

    // widthBudget 开启；候选表=[石头]（在调色板内 → 候选本身不超预算，但剩余空位为 0）
    ObfuscationProcessor processor = new ObfuscationProcessor(new ChunkCodec(registry(), MODERN),
        blockId -> blockId != AIR, oreTargets(), new int[] {STONE}, new int[] {1}, false,
        true, new ObfuscationProcessor.PaletteOptions(false, false, true), true, true,
        BELOW_USABLE);
    ObfuscationProcessor.Result result = processor.rewrite(source, 2, 42L, null, null, 0);

    assertTrue(result.changed(), "目标矿必须被伪装");
    assertEquals(STONE, decodedState(result.data(), 2, 1, 0),
        "下方方块不在调色板且无空位时必须退回候选表（石头），不得绕过位宽封顶引入新状态");
    assertEquals(4, result.sectionBits()[1], "位宽必须保持 4 位（不因下方新状态 grow）");
  }

  /** 预算联动（有空位）：调色板有余量时下方新状态照常取用——只拦「会升位」的写入，不误伤观感。 */
  @Test
  void belowOutsidePaletteWithFreeSlotIsStillAllowed() {
    int[] palette = {STONE, DIAMOND_ORE}; // paletteSize=2，bits=4 → 剩余空位 14
    int[] indices = new int[4096];
    java.util.Arrays.fill(indices, 0);
    indices[0] = 1;
    int[] lower = filled(STONE);
    lower[(15 << 8)] = 7;
    byte[] source = indirectOverDirect(palette, indices, lower);

    ObfuscationProcessor processor = new ObfuscationProcessor(new ChunkCodec(registry(), MODERN),
        blockId -> blockId != AIR, oreTargets(), new int[] {STONE}, new int[] {1}, false,
        true, new ObfuscationProcessor.PaletteOptions(false, false, true), true, true,
        BELOW_USABLE);
    ObfuscationProcessor.Result result = processor.rewrite(source, 2, 42L, null, null, 0);

    assertTrue(result.changed(), "目标矿必须被伪装");
    assertEquals(7, decodedState(result.data(), 2, 1, 0),
        "调色板有余量时下方方块照常取用（伪装=下方新状态 7）");
    assertEquals(4, result.sectionBits()[1], "写入后 palette 仅 3 条，位宽仍为 4 位");
  }
}
