package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.BitSet;
import net.mikumc.mikuxraynet.codec.ByteBufUtil;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import org.junit.jupiter.api.Test;

/**
 * 遮挡判定测试：用合成状态表 + 合成 section 字节验证 6 面正交判定与边界降级行为。
 *
 * <p>判定语义（与实现一致）：目标方块只有在上下左右前后 6 个正交方向全部被遮挡时才被替换；
 * 任一方向暴露、越出世界上下界都保持原样；对角方向不参与判定。
 *
 * <p>区块边界（越过本区块的 6 个面）：有 {@link NeighborEdges} 时按邻块贴边快照判定，
 * 快照缺失时按 {@code missing-policy}（hide = 视为遮挡 / expose = 视为暴露）。
 */
class ObfuscationProcessorTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int DIAMOND_ORE = 2;
  private static final int BITS = 15;

  /** 目标运行版本：Paper 26.2（section 头部含流体计数、无 long 数组长度字段、含群系容器）。 */
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

  /** 只隐藏钻石矿、只伪装成石头的最小处理器。 */
  private static ObfuscationProcessor processor() {
    BitSet targets = new BitSet();
    targets.set(DIAMOND_ORE);
    return new ObfuscationProcessor(new ChunkCodec(registry(), MODERN), blockId -> blockId != AIR,
        targets, new int[] {STONE}, new int[] {1}, false);
  }

  @Test
  void fullyOccludedTargetIsReplaced() {
    int[] states = filled(STONE);
    states[index(8, 8, 8)] = DIAMOND_ORE;
    byte[] source = chunk(states);

    ObfuscationProcessor.Result result = processor().rewrite(source, 1, 42L);

    assertTrue(result.changed(), "6 面全遮挡的矿块应被伪装替换");
    assertArrayEquals(new int[] {index(8, 8, 8)}, result.obfuscatedPositions());
    assertEquals(STONE, decodeState(result.data(), 1, index(8, 8, 8)), "替换后的方块状态应为石头");
  }

  @Test
  void targetWithExposedFaceIsKept() {
    int[] states = filled(STONE);
    states[index(8, 8, 8)] = DIAMOND_ORE;
    states[index(9, 8, 8)] = AIR;
    byte[] source = chunk(states);

    ObfuscationProcessor.Result result = processor().rewrite(source, 1, 42L);

    assertFalse(result.changed(), "任一面暴露时不得伪装");
    assertSame(source, result.data(), "无改动时必须直接用原字节，不触发重编码");
  }

  @Test
  void diagonalNeighborsDoNotCount() {
    int[] states = filled(AIR);
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -1; dz <= 1; dz++) {
          // 跳过自身与 6 个正交方向，只保留对角/棱方向的石头
          if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1 || (dx | dy | dz) == 0) {
            continue;
          }
          states[index(8 + dx, 8 + dy, 8 + dz)] = STONE;
        }
      }
    }
    states[index(8, 8, 8)] = DIAMOND_ORE;
    byte[] source = chunk(states);

    ObfuscationProcessor.Result result = processor().rewrite(source, 1, 42L);

    assertFalse(result.changed(), "对角方向不构成遮挡，正交 6 面全空时不得伪装");
  }

  @Test
  void boundaryTargetIsKept() {
    int[] states = filled(STONE);
    states[index(0, 0, 0)] = DIAMOND_ORE;
    byte[] source = chunk(states);

    ObfuscationProcessor.Result result = processor().rewrite(source, 1, 42L);

    assertFalse(result.changed(), "missing-policy=expose 下，区块边界外与世界下界外按未遮挡处理");
  }

  @Test
  void occlusionIsResolvedAcrossSections() {
    int[] lower = filled(STONE);
    lower[index(8, 15, 8)] = DIAMOND_ORE;
    int[] upper = filled(STONE);

    ObfuscationProcessor.Result occluded = processor().rewrite(chunk(lower, upper), 2, 7L);
    assertTrue(occluded.changed(), "上方邻居位于相邻 section，跨 section 也应算作遮挡");

    int[] airUpper = filled(AIR);
    ObfuscationProcessor.Result exposed = processor().rewrite(chunk(lower, airUpper), 2, 7L);
    assertFalse(exposed.changed(), "上方 section 全空气时该方块暴露，不得伪装");
  }

  @Test
  void layerObfuscationUsesOneReplacementPerLayer() {
    int[] states = filled(STONE);
    states[index(8, 8, 8)] = DIAMOND_ORE;
    states[index(9, 8, 9)] = DIAMOND_ORE;

    BitSet targets = new BitSet();
    targets.set(DIAMOND_ORE);
    ObfuscationProcessor processor = new ObfuscationProcessor(new ChunkCodec(registry(), MODERN),
        blockId -> blockId != AIR, targets, new int[] {STONE, DIAMOND_ORE}, new int[] {1, 2}, true);

    ObfuscationProcessor.Result result = processor.rewrite(chunk(states), 1, 99L);

    assertTrue(result.changed());
    int first = decodeState(result.data(), 1, index(8, 8, 8));
    int second = decodeState(result.data(), 1, index(9, 8, 9));
    assertEquals(first, second, "层状伪装模式下同一高度层必须使用同一种伪装方块");
  }

  @Test
  void borderTargetIsHiddenWhenNeighborPlaneOccludes() {
    int[] states = filled(STONE);
    states[index(0, 8, 8)] = DIAMOND_ORE;

    ObfuscationProcessor.Result result = processor(false)
        .rewrite(chunk(states), 1, 42L, uniformEdges(16, true));

    assertTrue(result.changed(), "邻块贴边层遮挡时，区块边界方块也应被伪装（消除边界泄漏）");
    assertArrayEquals(new int[] {index(0, 8, 8)}, result.obfuscatedPositions());
    assertEquals(STONE, decodeState(result.data(), 1, index(0, 8, 8)));
  }

  @Test
  void borderTargetIsKeptWhenNeighborPlaneIsOpen() {
    int[] states = filled(STONE);
    states[index(0, 8, 8)] = DIAMOND_ORE;
    byte[] source = chunk(states);

    ObfuscationProcessor.Result result = processor(false).rewrite(source, 1, 42L, uniformEdges(16, false));

    assertFalse(result.changed(), "邻块那一格是空气时该面暴露，不得伪装");
    assertSame(source, result.data(), "无改动时必须直接用原字节，不触发重编码");
  }

  @Test
  void zBorderUsesMatchingNeighborSide() {
    int[] states = filled(STONE);
    states[index(8, 8, 15)] = DIAMOND_ORE;

    NeighborEdges onlyZPlus = edges(16, plane(16, false), plane(16, false), plane(16, false),
        plane(16, true));
    assertTrue(processor(false).rewrite(chunk(states), 1, 42L, onlyZPlus).changed(),
        "z=16 方向应在 Z_PLUS 侧邻块快照中查询");

    NeighborEdges onlyZMinus = edges(16, plane(16, false), plane(16, false), plane(16, true),
        plane(16, false));
    assertFalse(processor(false).rewrite(chunk(states), 1, 42L, onlyZMinus).changed(),
        "只有 Z_MINUS 侧遮挡时，z=15 这一面仍然暴露");
  }

  @Test
  void missingNeighborFollowsConfiguredPolicy() {
    int[] states = filled(STONE);
    states[index(0, 8, 8)] = DIAMOND_ORE;

    assertTrue(processor(true).rewrite(chunk(states), 1, 42L).changed(),
        "missing-policy=hide：邻块缺失时宁可多伪装");
    assertFalse(processor(false).rewrite(chunk(states), 1, 42L).changed(),
        "missing-policy=expose：邻块缺失时按旧行为保持原样");
  }

  @Test
  void worldLimitsStillWinOverMissingPolicy() {
    int[] states = filled(STONE);
    states[index(8, 0, 8)] = DIAMOND_ORE;

    // 下方已越出世界下界：即使全部邻块平面都「遮挡」，也必须保持原样（不能凭空遮挡世界之外）
    assertFalse(processor(true).rewrite(chunk(states), 1, 42L, uniformEdges(16, true)).changed(),
        "越出世界上下界一律视为暴露");
  }

  @Test
  void allModeObfuscatesExposedOre() {
    int[] states = filled(STONE);
    states[index(8, 8, 8)] = DIAMOND_ORE;
    states[index(9, 8, 8)] = AIR;

    ObfuscationProcessor.Result result = processorAll().rewrite(chunk(states), 1, 42L);

    assertTrue(result.changed(), "all 模式下即使有面暴露（矿洞壁）也必须伪装");
    assertArrayEquals(new int[] {index(8, 8, 8)}, result.obfuscatedPositions());
    assertEquals(STONE, decodeState(result.data(), 1, index(8, 8, 8)), "裸露矿被替换为伪装方块");
  }

  @Test
  void enclosedModeKeepsExposedOreWhileAllModeHidesIt() {
    // 四周全空气：完全裸露的矿（矿洞中央/壁上）
    int[] states = filled(AIR);
    states[index(8, 8, 8)] = DIAMOND_ORE;
    byte[] source = chunk(states);

    assertFalse(processor(false).rewrite(source, 1, 42L).changed(),
        "enclosed 模式行为不变：全暴露的矿保持原样");
    ObfuscationProcessor.Result all = processorAll().rewrite(source, 1, 42L);
    assertTrue(all.changed(), "切换到 all 后同一输入必须被伪装（配置切换行为正确）");
    assertEquals(STONE, decodeState(all.data(), 1, index(8, 8, 8)));
  }

  @Test
  void allModeIgnoresOcclusionAndNeighborData() {
    int[] states = filled(AIR);
    states[index(0, 0, 0)] = DIAMOND_ORE;
    byte[] source = chunk(states);

    ObfuscationProcessor.Result withoutNeighbors = processorAll().rewrite(source, 1, 42L);
    ObfuscationProcessor.Result withOpenEdges = processorAll().rewrite(source, 1, 42L, uniformEdges(16, false));

    // all 模式不做遮挡判定，因此既不依赖邻块快照，也不受世界上下界 / 区块边界影响
    assertTrue(withoutNeighbors.changed(), "all 模式下区块边界的裸露矿同样被伪装");
    assertArrayEquals(withoutNeighbors.obfuscatedPositions(), withOpenEdges.obfuscatedPositions(),
        "all 模式下邻块数据不影响伪装坐标");
  }

  private static int[] filled(int state) {
    int[] states = new int[4096];
    Arrays.fill(states, state);
    return states;
  }

  private static byte[] plane(int height, boolean occluding) {
    byte[] plane = new byte[height << 4];
    if (occluding) {
      Arrays.fill(plane, (byte) 1);
    }
    return plane;
  }

  private static NeighborEdges uniformEdges(int height, boolean occluding) {
    byte[] plane = plane(height, occluding);
    return new NeighborEdges(height, plane, plane, plane, plane);
  }

  private static NeighborEdges edges(int height, byte[] xMinus, byte[] xPlus, byte[] zMinus, byte[] zPlus) {
    return new NeighborEdges(height, xMinus, xPlus, zMinus, zPlus);
  }

  /** 指定缺失策略的处理器；{@code missingPolicyHide=true} 即 antixray.yml 的默认值。 */
  private static ObfuscationProcessor processor(boolean missingPolicyHide) {
    BitSet targets = new BitSet();
    targets.set(DIAMOND_ORE);
    return new ObfuscationProcessor(new ChunkCodec(registry(), MODERN), blockId -> blockId != AIR,
        targets, new int[] {STONE}, new int[] {1}, false, missingPolicyHide,
        ObfuscationProcessor.PaletteOptions.DISABLED);
  }

  /** {@code obfuscation.mode: all} 的处理器：所有目标矿一律伪装，不做 6 面遮挡判定。 */
  private static ObfuscationProcessor processorAll() {
    BitSet targets = new BitSet();
    targets.set(DIAMOND_ORE);
    return new ObfuscationProcessor(new ChunkCodec(registry(), MODERN), blockId -> blockId != AIR,
        targets, new int[] {STONE}, new int[] {1}, false, false,
        ObfuscationProcessor.PaletteOptions.DISABLED, true);
  }

  private static int index(int x, int y, int z) {
    return y << 8 | z << 4 | x;
  }

  /** 生成一个或多个「direct 调色板」section 的合成区块字节（布局与 Paper 26.2 一致）。 */
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
        writeLongArray(buffer, states);

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

  private static void writeLongArray(ByteBuf buffer, int[] values) {
    int entriesPerLong = 64 / BITS;
    long mask = (1L << BITS) - 1L;
    long[] data = new long[(int) Math.ceil((double) values.length / entriesPerLong)];
    for (int i = 0; i < values.length; i++) {
      int position = i / entriesPerLong;
      int offset = (i - position * entriesPerLong) * BITS;
      data[position] |= ((long) values[i] & mask) << offset;
    }
    for (long entry : data) {
      buffer.writeLong(entry);
    }
  }

  private static int decodeState(byte[] data, int sectionCount, int index) {
    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    try (Chunk chunk = codec.decode(data, sectionCount)) {
      return chunk.getSection(0).getBlockState(index);
    }
  }
}