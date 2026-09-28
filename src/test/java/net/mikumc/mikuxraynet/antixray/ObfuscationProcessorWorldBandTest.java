package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.BitSet;
import net.mikumc.mikuxraynet.codec.ByteBufUtil;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * P0-2「min-y/max-y 高度范围 + 逐世界配置」与 P0-3「按 Y 分区加权伪装表」的处理器行为测试。
 *
 * <p>用包级构造直传手工构建的 {@link ObfuscationProcessor.WorldProfile}（与 create() 的解析产物
 * 同构，但无需 PacketEvents），验证：
 * <ol>
 *   <li><b>min-y/max-y</b>：范围外的方块判定与替换都跳过，范围内的照常伪装；</li>
 *   <li><b>分区伪装表</b>：按绝对 Y 取「第一个覆盖的 band」，未被任何 band 覆盖时回落
 *       replacement-weights；</li>
 *   <li><b>band × 预算联动</b>：band 候选与调色板容量对账，超预算时 band 只选调色板内候选、
 *       无候选可留的表走逃生口；</li>
 *   <li><b>逐世界档案</b>：不同世界名解析到不同档案（伪装方块/高度范围各不相同），世界未覆盖时
 *       用全局默认。</li>
 * </ol>
 */
class ObfuscationProcessorWorldBandTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int DEEPSLATE = 2;
  private static final int NETHERRACK = 3;
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

  /** 全部填充为 stone、仅 {@code index} 处放一个钻石矿的 section。 */
  private static int[] stoneWithTarget(int index) {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, STONE);
    states[index] = DIAMOND_ORE;
    return states;
  }

  /**
   * 组装档案（候选等权）。{@code bands} 为 {@code {minY, maxY, id}} 三元组的平铺数组，
   * 空数组表示无 band。
   */
  private static ObfuscationProcessor.WorldProfile profile(int[] fallbackIds, int[] bands,
      int minY, int maxY) {
    int[] fallbackCum = new int[fallbackIds.length];
    for (int i = 0; i < fallbackIds.length; i++) {
      fallbackCum[i] = i + 1;
    }
    int bandCount = bands.length / 3;
    int[] bandMinY = new int[bandCount];
    int[] bandMaxY = new int[bandCount];
    int[][] bandIds = new int[bandCount][];
    int[][] bandCum = new int[bandCount][];
    for (int b = 0; b < bandCount; b++) {
      bandMinY[b] = bands[b * 3];
      bandMaxY[b] = bands[b * 3 + 1];
      bandIds[b] = new int[] {bands[b * 3 + 2]};
      bandCum[b] = new int[] {1};
    }
    BitSet targets = new BitSet();
    targets.set(DIAMOND_ORE);
    return new ObfuscationProcessor.WorldProfile(targets, fallbackIds, fallbackCum,
        bandMinY, bandMaxY, bandIds, bandCum, minY, maxY, true);
  }

  /** 默认（无逐世界）处理器：档案直传；{@code widthBudget} 控制是否走 P0-1 路径。 */
  private static ObfuscationProcessor processor(ObfuscationProcessor.WorldProfile profile,
      boolean widthBudget) {
    return new ObfuscationProcessor(codec(), blockId -> true, false, true,
        widthBudget
            ? new ObfuscationProcessor.PaletteOptions(false, false, true)
            : ObfuscationProcessor.PaletteOptions.DISABLED,
        false, null, profile, new ObfuscationProcessor.WorldProfile[0], null);
  }

  /** 生成若干「direct 调色板」section 的合成区块（布局与 Paper 26.2 一致）。 */
  private static byte[] chunk(int[]... sections) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      for (int[] states : sections) {
        int blockCount = 0;
        for (int state : states) {
          if (state != AIR) {
            blockCount++;
          }
        }
        buffer.writeShort(blockCount);
        buffer.writeShort(0);
        buffer.writeByte(BITS);
        long mask = (1L << BITS) - 1L;
        int entriesPerLong = 64 / BITS;
        long[] data = new long[(4096 + entriesPerLong - 1) / entriesPerLong];
        for (int i = 0; i < 4096; i++) {
          int position = i / entriesPerLong;
          data[position] |= ((long) states[i] & mask) << ((i - position * entriesPerLong) * BITS);
        }
        for (long entry : data) {
          buffer.writeLong(entry);
        }
        buffer.writeByte(0);
        ByteBufUtil.writeVarInt(buffer, 0);
      }
      byte[] out = new byte[buffer.readableBytes()];
      buffer.getBytes(buffer.readerIndex(), out);
      return out;
    } finally {
      buffer.release();
    }
  }

  private static int index(int x, int y, int z) {
    return y << 8 | z << 4 | x;
  }

  /** 解码输出，读取第 {@code sectionIndex} 个 section 在 {@code index} 处的方块状态。 */
  private static int decodedState(byte[] data, int sectionCount, int sectionIndex, int index) {
    try (Chunk chunk = codec().decode(data, sectionCount)) {
      return chunk.getSection(sectionIndex).getBlockState(index);
    }
  }

  /** 组装一个满 4 位调色板（16 条全部被引用）的间接 section，{@code oreIndex} 处放钻石矿。 */
  private static byte[] fullFourBitChunk(int oreIndex) {
    int[] palette = {STONE, DIAMOND_ORE, IRON_ORE, GOLD_ORE, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24, 25};
    int[] indices = new int[4096];
    java.util.Arrays.fill(indices, 0);
    for (int i = 4; i < 16; i++) {
      indices[100 + (i - 4)] = i; // 填充条目逐一被引用
    }
    indices[200] = 2; // 铁矿（保持引用）
    indices[300] = 3; // 金矿
    indices[oreIndex] = 1; // 钻石矿
    int blockCount = 4096;
    ByteBuf buffer = Unpooled.buffer();
    try {
      buffer.writeShort(blockCount);
      buffer.writeShort(0);
      buffer.writeByte(4);
      ByteBufUtil.writeVarInt(buffer, palette.length);
      for (int value : palette) {
        ByteBufUtil.writeVarInt(buffer, value);
      }
      long[] data = new long[1024];
      for (int i = 0; i < 4096; i++) {
        data[i / 64] |= ((long) indices[i] & 15L) << ((i & 63) * 4);
      }
      for (long entry : data) {
        buffer.writeLong(entry);
      }
      buffer.writeByte(0);
      ByteBufUtil.writeVarInt(buffer, 0);
      byte[] out = new byte[buffer.readableBytes()];
      buffer.getBytes(buffer.readerIndex(), out);
      return out;
    } finally {
      buffer.release();
    }
  }

  // ---------------------------------------------------------- min-y / max-y

  @Test
  void heightRangeSkipsTargetsOutsideRange() {
    // 生效范围 [-16, 31]；世界最低 Y = -64。section 0（绝对 y -64..-49）在范围外、
    // section 5（绝对 y 16..31）在范围内。
    ObfuscationProcessor.WorldProfile profile = profile(new int[] {STONE}, new int[0], -16, 31);
    int target = index(8, 8, 8);
    byte[] source = chunk(stoneWithTarget(target), filled(STONE), filled(STONE), filled(STONE),
        filled(STONE), stoneWithTarget(target), filled(STONE), filled(STONE));

    ObfuscationProcessor.Result result = processor(profile, false)
        .rewrite(source, 8, 42L, null, "world", -64);

    assertTrue(result.changed(), "范围内的矿必须被伪装");
    assertEquals(1, result.obfuscatedPositions().length, "范围外的矿不得被伪装（判定与替换都跳过）");
    assertEquals((5 << 4) * 256 + target, result.obfuscatedPositions()[0], "只有 section 5 的矿被伪装");
    assertEquals(DIAMOND_ORE, decodedState(result.data(), 8, 0, target), "范围外的矿保持原样");
    assertEquals(STONE, decodedState(result.data(), 8, 5, target), "范围内的矿被替换为伪装方块");
  }

  private static int[] filled(int state) {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, state);
    return states;
  }

  @Test
  void heightRangeUnlimitedByDefault() {
    // 不限制高度（哨兵值）：最底与最顶 section 的矿都照常伪装（回归确认默认行为不变）
    ObfuscationProcessor.WorldProfile profile = profile(new int[] {STONE}, new int[0],
        Integer.MIN_VALUE, Integer.MAX_VALUE);
    int target = index(8, 8, 8);
    byte[] source = chunk(stoneWithTarget(target), filled(STONE), filled(STONE),
        stoneWithTarget(target));

    ObfuscationProcessor.Result result = processor(profile, false)
        .rewrite(source, 4, 42L, null, "world", -64);

    assertEquals(2, result.obfuscatedPositions().length, "不限高度时所有目标都伪装");
  }

  // ---------------------------------------------------------- 按 Y 分区伪装表

  @Test
  void bandSelectionFollowsAbsoluteY() {
    // band [-64,-1] → 深板岩；band [0,320] → 石头；世界最低 Y = -64
    ObfuscationProcessor.WorldProfile profile = profile(new int[] {STONE},
        new int[] {-64, -1, DEEPSLATE, 0, 320, STONE}, Integer.MIN_VALUE, Integer.MAX_VALUE);
    int target = index(8, 8, 8);
    byte[] source = chunk(stoneWithTarget(target), filled(STONE), filled(STONE), filled(STONE),
        stoneWithTarget(target));

    ObfuscationProcessor.Result result = processor(profile, false)
        .rewrite(source, 5, 42L, null, "world", -64);

    assertEquals(2, result.obfuscatedPositions().length);
    assertEquals(DEEPSLATE, decodedState(result.data(), 5, 0, target), "深层（y<0）分段的伪装方块");
    assertEquals(STONE, decodedState(result.data(), 5, 4, target), "浅层（y≥0）分段的伪装方块");
  }

  @Test
  void uncoveredHeightFallsBackToReplacementWeights() {
    // 只有 band [-64,-1]；section 4（绝对 y 0..15）未被任何 band 覆盖 → 回落 replacement-weights
    ObfuscationProcessor.WorldProfile profile = profile(new int[] {STONE},
        new int[] {-64, -1, DEEPSLATE}, Integer.MIN_VALUE, Integer.MAX_VALUE);
    int target = index(8, 8, 8);
    byte[] source = chunk(stoneWithTarget(target), filled(STONE), filled(STONE), filled(STONE),
        stoneWithTarget(target));

    ObfuscationProcessor.Result result = processor(profile, false)
        .rewrite(source, 5, 42L, null, "world", -64);

    assertEquals(2, result.obfuscatedPositions().length, "未被 band 覆盖的高度仍必须伪装（回落表兜底）");
    assertEquals(DEEPSLATE, decodedState(result.data(), 5, 0, target), "band 覆盖的高度用 band 候选");
    assertEquals(STONE, decodedState(result.data(), 5, 4, target), "未覆盖高度回落 replacement-weights");
  }

  @Test
  void bandCandidatesRespectPaletteBudget() {
    // 4 位满调色板（16/16）；band [0,320] 候选 = [石头(在调色板内), 深板岩(不在)]，
    // 回落表 = [深板岩]（也不在）。联合预算：新状态 1 > 剩余空位 0 → 超预算：
    //   band 筛选后只剩石头（避免 grow）；回落表无候选可留 → 逃生口保留原表。
    // 目标都在 band 覆盖内 → 只用石头 → 不 grow；裁剪失效矿后位宽回到 4 位。
    byte[] source = fullFourBitChunk(273);
    BitSet targets = new BitSet();
    targets.set(DIAMOND_ORE);
    ObfuscationProcessor.WorldProfile custom = new ObfuscationProcessor.WorldProfile(
        targets, new int[] {DEEPSLATE}, new int[] {1},
        new int[] {0}, new int[] {320}, new int[][] {{STONE, DEEPSLATE}}, new int[][] {{1, 2}},
        Integer.MIN_VALUE, Integer.MAX_VALUE, true);

    ObfuscationProcessor.Result result = processor(custom, true)
        .rewrite(source, 1, 42L, null, "world", 0);

    assertTrue(result.changed());
    assertEquals(STONE, decodedState(result.data(), 1, 0, 273), "band 候选超预算时只选调色板内的石头");
    assertEquals(4, result.sectionBits()[0], "封顶 + 裁剪后位宽保持 4 位（不因深板岩 grow）");
  }

  // ---------------------------------------------------------- 逐世界档案

  @Test
  void perWorldProfilesResolveAndApplyIndependently() {
    AntiXrayConfig config = config("""
        obfuscation:
          replacement-weights:
            stone: 1
        world-overrides:
          world_nether:
            obfuscation:
              replacement-weights:
                netherrack: 1
          world_*:
            obfuscation:
              min-y: 16
              max-y: 32
        """);
    assertEquals(2, config.worldOverrides().size(), "两个世界覆盖段应被解析");

    // 档案与 create() 的产物同构：默认档案 + 与 worldOverrides 平行的覆盖档案
    ObfuscationProcessor.WorldProfile defaultProfile =
        profile(new int[] {STONE}, new int[0], Integer.MIN_VALUE, Integer.MAX_VALUE);
    ObfuscationProcessor.WorldProfile netherProfile =
        profile(new int[] {NETHERRACK}, new int[0], Integer.MIN_VALUE, Integer.MAX_VALUE);
    ObfuscationProcessor.WorldProfile starredProfile =
        profile(new int[] {STONE}, new int[0], 16, 32);
    ObfuscationProcessor processor = new ObfuscationProcessor(codec(), blockId -> true, false, true,
        ObfuscationProcessor.PaletteOptions.DISABLED, false, null,
        defaultProfile, new ObfuscationProcessor.WorldProfile[] {netherProfile, starredProfile},
        config);

    int target = index(8, 8, 8);
    byte[] single = chunk(stoneWithTarget(target));

    // world_nether → 精确命中覆盖段 0：伪装方块换成下界岩
    ObfuscationProcessor.Result nether = processor.rewrite(single, 1, 42L, null, "world_nether", 0);
    assertTrue(nether.changed());
    assertEquals(NETHERRACK, decodedState(nether.data(), 1, 0, target), "下界覆盖段的伪装方块生效");

    // world_custom → 通配命中覆盖段 1（world_*，前缀 world_）：高度范围 [16,32] 生效
    byte[] tall = chunk(stoneWithTarget(index(8, 0, 8)), stoneWithTarget(index(8, 8, 8)),
        filled(STONE), filled(STONE));
    ObfuscationProcessor.Result starred = processor.rewrite(tall, 4, 42L, null, "world_custom", 0);
    assertTrue(starred.changed());
    assertEquals(1, starred.obfuscatedPositions().length, "通配世界段的 min-y/max-y 生效");
    assertEquals((1 << 4) * 256 + index(8, 8, 8), starred.obfuscatedPositions()[0],
        "section 0（绝对 y 0..15）低于 min-y 不伪装；section 1（绝对 y 24）在范围内");
    assertEquals(DIAMOND_ORE, decodedState(starred.data(), 4, 0, index(8, 0, 8)));
    assertEquals(STONE, decodedState(starred.data(), 4, 1, index(8, 8, 8)));

    // unlisted → 无覆盖：全局默认档案（无高度限制），所有目标伪装
    ObfuscationProcessor.Result fallback = processor.rewrite(tall, 4, 42L, null, "unlisted", 0);
    assertEquals(2, fallback.obfuscatedPositions().length, "未列出的世界用全局默认（不限高度）");
    assertEquals(STONE, decodedState(fallback.data(), 4, 0, index(8, 0, 8)));
  }

  @Test
  void inactiveProfileLeavesChunkUntouched() {
    // 目标与候选都为空的档案：该世界整体跳过改写（原字节原样返回）
    ObfuscationProcessor.WorldProfile empty = new ObfuscationProcessor.WorldProfile(
        new BitSet(), new int[0], new int[0], new int[0], new int[0],
        new int[0][], new int[0][], Integer.MIN_VALUE, Integer.MAX_VALUE, true);
    ObfuscationProcessor processor = new ObfuscationProcessor(codec(), blockId -> true, false, true,
        ObfuscationProcessor.PaletteOptions.DISABLED, false, null,
        empty, new ObfuscationProcessor.WorldProfile[0], null);

    assertFalse(processor.isActive(), "空档案不具备生效条件");
    byte[] source = chunk(stoneWithTarget(index(8, 8, 8)));
    ObfuscationProcessor.Result result = processor.rewrite(source, 1, 42L);
    assertFalse(result.changed());
    assertArrayEquals(source, result.data(), "无候选时原包原样放行（fail-open 兜底）");
  }

  private static AntiXrayConfig config(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return AntiXrayConfig.from(configuration);
  }
}
