package net.mikumc.mikuxraynet.bench;

import java.util.Arrays;
import java.util.List;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;

/**
 * 离线基准的合成数据：16×16×384 的完整列（24 个 16³ section，1.18+ 布局，Paper 26.2 的字节标志）。
 *
 * <p>四种形态覆盖真实区块的典型分布：
 * <ol>
 *   <li>{@link #solid()}「全实心」：全部 section 都是单值调色板（bitsPerBlock=0）的石头；</li>
 *   <li>{@link #sparseOres()}「稀疏矿脉」：下部 8 个 section 是 4 位间接调色板（石头 + 深板岩 + 6 种矿），
 *       每 section 约 25 个矿方块（共约 200 个），其余 section 为单值石头；</li>
 *   <li>{@link #caves()}「洞穴」：每 section 用 4×4×4 粗粒确定性噪声挖出约 55% 空气，洞穴内壁暴露，
 *       调色板最多 9 项（空气 + 石头 + 深板岩 + 矿）；</li>
 *   <li>{@link #shuffledPalette()}「乱序调色板（合成）」：调色板按<b>频次升序</b>排列（出现最多的方块落在
 *       最后一个索引），代表第三方插件/历史区块产生的「未按频次排列」的调色板。前三种形态的调色板按
 *       「首次出现顺序」建立、且出现最多的状态恰好落在低位索引，重排对它们是无改动，因此必须补这一种形态
 *       才能量化重排的收益。</li>
 * </ol>
 *
 * <p>字节全部由 {@link TestChunkBuilder}（与生产 codec 的单测共用同一份布局实现）写出，
 * 因此两种被测路径消费的是同一份原始字节。
 *
 * <p>约定：方块状态 id 是合成的稠密整数，只有 id {@code 0} 是空气——这与 PacketEvents 把全局 id 0 硬编码为
 * {@code AIR} 的约定一致，使两条路径对「空气」的判断口径相同。
 */
public final class BenchFixtures {

  /** 列高 384 格 = 24 个 16 格 section。 */
  public static final int SECTION_COUNT = 24;
  public static final int SECTION_VOLUME = 4096;
  public static final int COLUMN_VOLUME = SECTION_COUNT * SECTION_VOLUME;

  public static final int AIR = 0;
  public static final int STONE = 1;
  public static final int DEEPSLATE = 2;
  /** 水：仅用于「主世界地下」形态（含含水层），非遮挡、属流体。 */
  public static final int WATER = 3;
  public static final int[] ORE_STATES = {100, 101, 102, 103, 104, 105};

  /** 改写目标状态：不在任何形态的调色板里，用于制造「新增调色板项」这一类真实改写代价。 */
  public static final int EDIT_STATE_BASE = 900;

  /** 测试侧注册表语义：状态总数 1<<15、直接格式位宽 15、仅 id 0 为空气、无流体。 */
  private static final int UNIQUE_STATE_COUNT = 1 << 15;
  private static final int MAX_BITS_PER_BLOCK_STATE = 15;

  /** 统一使用本项目的运行目标版本标志（26.2：有流体计数、无 long 数组长度字段、section 内联群系容器）。 */
  public static final ChunkVersionFlags FLAGS = ChunkVersionFlags.PAPER_26_2;

  /** 间接调色板位宽（原版对非单值 section 的下限即 4 位）。 */
  private static final int PALETTE_BITS = 4;
  /** 矿脉形态中：下部哪些 section 含矿（y 0..127）。 */
  private static final int ORE_SECTIONS = 8;
  /** 每个含矿 section 的矿方块数（8×25 = 200 个）。 */
  private static final int ORES_PER_SECTION = 25;

  private static final int BIOME_SINGLETON = 7;
  private static final int[] BIOME_LIST = {1, 2, 7, 9};

  private BenchFixtures() {
  }

  /** 一次方块改写：section 序号 + section 内相对坐标 + 目标状态。 */
  public record Edit(int section, int x, int y, int z, int state) {

    /** section 内元素序号（与 codec、PacketEvents 共同的约定：{@code y<<8|z<<4|x}）。 */
    public int localIndex() {
      return y << 8 | z << 4 | x;
    }
  }

  /** 一个形态的原始区块字节。 */
  public record Fixture(String name, byte[] bytes) {
  }

  public static RegistryAccessor registry() {
    return new RegistryAccessor() {
      @Override
      public int getUniqueBlockStateCount() {
        return UNIQUE_STATE_COUNT;
      }

      @Override
      public int getMaxBitsPerBlockState() {
        return MAX_BITS_PER_BLOCK_STATE;
      }

      @Override
      public boolean isAir(int blockId) {
        return blockId == AIR;
      }

      @Override
      public boolean isFluid(int blockId) {
        return false;
      }
    };
  }

  public static List<Fixture> all() {
    return List.of(solid(), sparseOres(), caves(), shuffledPalette());
  }

  /**
   * 生产路径基准使用的形态集合：四种既有形态 + 更贴近真实的「主世界地下」形态。
   *
   * <p>与 {@link #all()} 分开，是为了不改变「解码 → 改 N 个方块 → 重编码」既有基准的形态集合与耗时口径。
   */
  public static List<Fixture> productionShapes() {
    return List.of(solid(), sparseOres(), caves(), shuffledPalette(), realisticUnderground());
  }

  /** ① 全实心：24 个单值石头 section。 */
  public static Fixture solid() {
    TestChunkBuilder builder = new TestChunkBuilder(FLAGS);
    for (int section = 0; section < SECTION_COUNT; section++) {
      builder.singleValueSection(STONE, SECTION_VOLUME, 0, 0, new int[] {BIOME_SINGLETON});
    }
    return new Fixture("全实心", builder.build());
  }

  /** ② 稀疏矿脉：下部 8 个 4 位间接 section 散布约 200 个矿方块，其余为单值石头。 */
  public static Fixture sparseOres() {
    int[] palette = new int[2 + ORE_STATES.length];
    palette[0] = STONE;
    palette[1] = DEEPSLATE;
    System.arraycopy(ORE_STATES, 0, palette, 2, ORE_STATES.length);

    TestChunkBuilder builder = new TestChunkBuilder(FLAGS);
    for (int section = 0; section < SECTION_COUNT; section++) {
      if (section >= ORE_SECTIONS) {
        builder.singleValueSection(STONE, SECTION_VOLUME, 0, 0, new int[] {BIOME_SINGLETON});
        continue;
      }

      int[] indices = new int[SECTION_VOLUME];
      for (int index = 0; index < SECTION_VOLUME; index++) {
        if (index % 97 == 0) {
          indices[index] = 1; // 深层岩脉
        }
      }
      for (int vein = 0; vein < ORES_PER_SECTION; vein++) {
        int position = (vein * 1543 + section * 97 + 7) % SECTION_VOLUME;
        indices[position] = 2 + vein % ORE_STATES.length;
      }

      builder.indirectSection(PALETTE_BITS, SECTION_VOLUME, 0, palette, indices, 3, BIOME_LIST);
    }
    return new Fixture("稀疏矿脉", builder.build());
  }

  /** ③ 洞穴：4×4×4 粗粒确定性噪声挖空约 55%，洞穴内壁暴露面密集。 */
  public static Fixture caves() {
    TestChunkBuilder builder = new TestChunkBuilder(FLAGS);
    for (int section = 0; section < SECTION_COUNT; section++) {
      int[] states = new int[SECTION_VOLUME];
      for (int index = 0; index < SECTION_VOLUME; index++) {
        int x = index & 15;
        int z = index >> 4 & 15;
        int y = index >> 8 & 15;
        int cell = (y >> 2) << 4 | (z >> 2) << 2 | (x >> 2);
        int noise = hash(section * 64 + cell) % 100;
        if (noise < 55) {
          states[index] = AIR;
        } else if (noise < 62) {
          states[index] = ORE_STATES[noise % ORE_STATES.length];
        } else if (noise < 75) {
          states[index] = DEEPSLATE;
        } else {
          states[index] = STONE;
        }
      }

      // 按首次出现顺序建立调色板（与间接调色板约定一致），容量取 4 位的 16 项
      int[] palette = new int[1 << PALETTE_BITS];
      int[] indices = new int[SECTION_VOLUME];
      int paletteSize = 0;
      int blockCount = 0;
      for (int index = 0; index < SECTION_VOLUME; index++) {
        int state = states[index];
        int id = -1;
        for (int i = 0; i < paletteSize; i++) {
          if (palette[i] == state) {
            id = i;
            break;
          }
        }
        if (id < 0) {
          id = paletteSize++;
          palette[id] = state;
        }
        indices[index] = id;
        if (state != AIR) {
          blockCount++;
        }
      }

      builder.indirectSection(PALETTE_BITS, blockCount, 0, Arrays.copyOf(palette, paletteSize), indices, 3, BIOME_LIST);
    }
    return new Fixture("洞穴", builder.build());
  }

  /**
   * ④ 乱序调色板（合成）：每 section 都是 4 位间接调色板，但调色板按「频次升序」排列——
   * 出现最多的方块落在最后一个索引（约 49%），最少见的落在索引 1。
   *
   * <p>用途：前三形态的调色板按首次出现顺序建立且最高频状态已在索引 0，重排对它们无改动；
   * 这一形态专门用来量化「调色板本来乱序时重排能省多少压缩后带宽」。
   */
  public static Fixture shuffledPalette() {
    int[] palette = new int[10];
    palette[0] = AIR;
    for (int i = 1; i < palette.length; i++) {
      palette[i] = 10 + i;
    }

    TestChunkBuilder builder = new TestChunkBuilder(FLAGS);
    for (int section = 0; section < SECTION_COUNT; section++) {
      int[] indices = new int[SECTION_VOLUME];
      int blockCount = 0;
      for (int index = 0; index < SECTION_VOLUME; index++) {
        // 频次与调色板索引相反：索引越大出现越多（最频繁的落在最后一个索引）
        int roll = hash(section * SECTION_VOLUME + index) % 100;
        int id = roll < 49 ? 9 : roll < 61 ? 0 : roll < 73 ? 8 : roll < 78 ? 7
            : roll < 83 ? 6 : roll < 87 ? 5 : roll < 90 ? 4 : roll < 93 ? 3 : roll < 96 ? 2 : 1;
        indices[index] = id;
        if (palette[id] != AIR) {
          blockCount++;
        }
      }

      builder.indirectSection(PALETTE_BITS, blockCount, 0, palette, indices, 3, BIOME_LIST);
    }
    return new Fixture("乱序调色板（合成）", builder.build());
  }

  /**
   * ⑤ 主世界地下（更贴近真实）：石头 + 深板岩为主，空气与洞穴约占 20%（含少量含水层），矿物稀疏散布。
   *
   * <p>这是真实主世界地下的样子，也是反矿透生产路径真正面对的输入：绝大多数方块是实心的石头/深板岩，
   * 矿脉稀疏且多被实心包裹（因此会被 6 面遮挡判定命中并伪装），只有贴近洞穴的矿块暴露。
   * 分布按深度分带：下部（section 0..7，深板岩带）空气约 14%、深板岩为主、深板岩矿；
   * 中部（8..15）空气约 20%、石头略多、石头矿；上部（16..23）空气约 26%、石头为主、矿极少。
   * 三带平均空气占比恰为 20%，与任务给定的「空气与洞穴占 20%」一致。
   *
   * <p>调色板项：空气 / 石头 / 深板岩 / 水 + 6 种矿 = 至多 10 项，用 4 位间接调色板即可容纳。
   */
  public static Fixture realisticUnderground() {
    TestChunkBuilder builder = new TestChunkBuilder(FLAGS);
    for (int section = 0; section < SECTION_COUNT; section++) {
      int[] states = new int[SECTION_VOLUME];
      for (int index = 0; index < SECTION_VOLUME; index++) {
        int roll = hash(section * SECTION_VOLUME + index) % 1000;
        if (section < 8) {
          // 深板岩带
          if (roll < 140) {
            states[index] = AIR;
          } else if (roll < 150) {
            states[index] = WATER;
          } else if (roll < 165) {
            states[index] = ORE_STATES[3 + roll % 3]; // 深板岩矿
          } else if (roll < 640) {
            states[index] = DEEPSLATE;
          } else {
            states[index] = STONE;
          }
        } else if (section < 16) {
          // 石头—深板岩过渡带
          if (roll < 200) {
            states[index] = AIR;
          } else if (roll < 208) {
            states[index] = WATER;
          } else if (roll < 220) {
            states[index] = ORE_STATES[roll % 3]; // 石头矿
          } else if (roll < 560) {
            states[index] = DEEPSLATE;
          } else {
            states[index] = STONE;
          }
        } else {
          // 浅层带（空气更多、矿极少）
          if (roll < 260) {
            states[index] = AIR;
          } else if (roll < 268) {
            states[index] = ORE_STATES[roll % 3];
          } else if (roll < 420) {
            states[index] = DEEPSLATE;
          } else {
            states[index] = STONE;
          }
        }
      }

      int[] palette = new int[16];
      int[] indices = new int[SECTION_VOLUME];
      int paletteSize = 0;
      int blockCount = 0;
      for (int index = 0; index < SECTION_VOLUME; index++) {
        int state = states[index];
        int id = -1;
        for (int i = 0; i < paletteSize; i++) {
          if (palette[i] == state) {
            id = i;
            break;
          }
        }
        if (id < 0) {
          id = paletteSize++;
          palette[id] = state;
        }
        indices[index] = id;
        if (state != AIR) {
          blockCount++;
        }
      }

      builder.indirectSection(PALETTE_BITS, blockCount, 0, Arrays.copyOf(palette, paletteSize), indices, 3, BIOME_LIST);
    }
    return new Fixture("主世界地下", builder.build());
  }

  /**
   * 改写计划：位置与状态只由 {@code count} 决定，与形态、路径无关——两条路径必然改同一批方块，
   * 语义一致性校验因此才有意义。位置按与 8×4096 互质的步长铺开，{@code count} 不超过 1000 时无重复。
   */
  public static Edit[] edits(int count) {
    int span = ORE_SECTIONS * SECTION_VOLUME;
    Edit[] edits = new Edit[count];
    for (int i = 0; i < count; i++) {
      int linear = (int) (((long) i * 7919L + 13L) % span);
      int local = linear % SECTION_VOLUME;
      edits[i] = new Edit(linear / SECTION_VOLUME, local & 15, local >> 8 & 15, local >> 4 & 15,
          EDIT_STATE_BASE + i % 3);
    }
    return edits;
  }

  /** 确定性整数混淆（同输入必然同输出，便于复现与对拍）。 */
  private static int hash(int value) {
    int mixed = value * 0x9E3779B1;
    mixed ^= mixed >>> 15;
    mixed *= 0x85EBCA6B;
    mixed ^= mixed >>> 13;
    return mixed & 0x7FFFFFFF;
  }
}