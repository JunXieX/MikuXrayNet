package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkSection;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;

/**
 * 反矿透核心：对单个区块做「6 面正交遮挡判定 + 按权重随机替换」，并可顺带做调色板压缩重排。
 *
 * <p><b>判定语义</b>：只有当目标方块的
 * 上下左右前后 6 个正交方向全部被遮挡（即该方块在客户端不可见）时才替换为伪装方块；
 * 任一方向未遮挡——包括世界上下界之外、以及 section 缺失——都按「暴露」处理并保持原样。
 * 对角方向的方块不参与遮挡判定。
 *
 * <p><b>区块边界</b>：越过本区块的 6 个面时改查 {@link NeighborEdges}（邻块贴边一层快照）。
 * 邻块数据缺失时按 {@code antixray.yml: neighbors.missing-policy} 处理，默认 {@code hide}
 * （宁可多伪装，也不留下沿 16×16 网格线的泄漏）。
 *
 * <p><b>关键优化</b>：
 * <ul>
 *   <li>全空气 section 直接跳过；</li>
 *   <li>非目标方块只做一次位图查询即跳过，不做任何邻居判定；</li>
 *   <li>整块区块无任何改动时直接返回原字节数组，完全不触发重编码；</li>
 *   <li>只有真正被改动的 section 才重编码（未改动的 section 由 codec 原样搬运原始字节）。</li>
 * </ul>
 *
 * <p>本类不做任何 Bukkit 访问，可在工作线程安全运行。
 */
public final class ObfuscationProcessor {

  private static final int SECTION_VOLUME = 4096;
  private static final int[] NO_POSITIONS = new int[0];

  /** 调色板压缩重排开关。 */
  public record PaletteOptions(boolean reorder, boolean strictVerify) {

    /** 关闭重排（保持既有行为）。 */
    public static final PaletteOptions DISABLED = new PaletteOptions(false, false);
  }

  /**
   * 改写结果。
   *
   * @param data                改写后的 section 字节（未改动时即为入参 {@code source}）
   * @param obfuscatedPositions 被伪装的方块位置，编码为 {@code y << 8 | z << 4 | x}（区块内相对坐标）
   * @param failure             {@code null} 表示正常；非 null 为「解码/重编码异常」的摘要
   *                            （此时已 fail-open 放行原包，调用方应计数并告警，勿与「无目标方块」混淆）
   */
  public record Result(byte[] data, int[] obfuscatedPositions, String failure) {

    /** 是否真的改动了字节。 */
    public boolean changed() {
      return obfuscatedPositions.length > 0;
    }

    /** 是否因解码/重编码异常而放弃改写（fail-open）。 */
    public boolean failed() {
      return failure != null;
    }
  }

  private final ChunkCodec codec;
  private final IntPredicate occlusionTable;
  private final BitSet targets;
  private final int[] replacementIds;
  private final int[] cumulativeWeights;
  private final boolean layerObfuscation;
  private final boolean missingPolicyHide;
  private final PaletteOptions paletteOptions;

  /**
   * 「解码侧统计」诊断结果（{@link #diagnose}）：只回答「这次解码到底看到了什么」。
   *
   * @param sectionCount  区块的 section 数
   * @param stateKinds    整块区块里出现过的不同方块状态种类数（0 表示解码出全空气）
   * @param targetMatches 命中目标方块（配置的 19 种矿）的方块个数（<b>不看遮挡</b>）
   */
  public record Diagnostic(int sectionCount, int stateKinds, int targetMatches) {
  }

  /**
   * 直接用「已解析」的数据构造（供单测与显式装配使用）。
   *
   * @param occlusionTable    遮挡判定表：方块状态 id → 是否整块不透明
   * @param targets           目标方块状态位图
   * @param replacementIds    伪装方块状态 id
   * @param cumulativeWeights 与 {@code replacementIds} 等长的累计权重（严格递增，末项为总权重）
   * @param layerObfuscation  true 时同一高度层统一使用同一种伪装方块
   * @param missingPolicyHide 邻块数据缺失时是否视为遮挡（true=宁可多伪装）
   * @param paletteOptions    调色板重排选项
   */
  public ObfuscationProcessor(ChunkCodec codec, IntPredicate occlusionTable, BitSet targets,
      int[] replacementIds, int[] cumulativeWeights, boolean layerObfuscation,
      boolean missingPolicyHide, PaletteOptions paletteOptions) {
    this.codec = codec;
    this.occlusionTable = occlusionTable;
    this.targets = targets;
    this.replacementIds = replacementIds.clone();
    this.cumulativeWeights = cumulativeWeights.clone();
    this.layerObfuscation = layerObfuscation;
    this.missingPolicyHide = missingPolicyHide;
    this.paletteOptions = paletteOptions;
  }

  /** 兼容构造：邻块缺失按「暴露」处理、不做调色板重排（即接入邻块快照之前的行为）。 */
  public ObfuscationProcessor(ChunkCodec codec, IntPredicate occlusionTable, BitSet targets,
      int[] replacementIds, int[] cumulativeWeights, boolean layerObfuscation) {
    this(codec, occlusionTable, targets, replacementIds, cumulativeWeights, layerObfuscation, false,
        PaletteOptions.DISABLED);
  }

  /**
   * 用配置 + 注册表解析出目标与伪装方块。未识别的名称会被记录并跳过。
   *
   * @return 已装配的处理器；若未解析到任何有效目标或伪装方块，{@link #isActive()} 为 false
   */
  public static ObfuscationProcessor create(ChunkCodec codec, BlockStateRegistry registry,
      AntiXrayConfig config, Logger logger, PaletteOptions paletteOptions) {
    BitSet targets = new BitSet(registry.getUniqueBlockStateCount());
    List<String> resolvedTargets = new ArrayList<>();
    for (String name : config.hideBlocks()) {
      int stateId = BlockStateRegistry.resolveStateId(name);
      if (stateId < 0) {
        logger.warning("反矿透配置中的隐藏方块名称无法识别，已跳过：" + name);
      } else {
        targets.set(stateId);
        resolvedTargets.add(name);
      }
    }
    // 启动自检：把「实际匹配到的目标方块清单」打出来，便于一眼看出是否漏了深层矿变体
    logger.info("反矿透目标方块解析完成：" + resolvedTargets.size() + '/' + config.hideBlocks().size()
        + " 种 → " + resolvedTargets);

    int[] replacementIds = new int[config.replacementWeights().size()];
    int[] cumulativeWeights = new int[replacementIds.length];
    int index = 0;
    int cumulative = 0;
    for (var entry : config.replacementWeights().entrySet()) {
      int stateId = BlockStateRegistry.resolveStateId(entry.getKey());
      if (stateId < 0) {
        logger.warning("反矿透配置中的伪装方块名称无法识别，已跳过：" + entry.getKey());
        continue;
      }
      cumulative += entry.getValue();
      replacementIds[index] = stateId;
      cumulativeWeights[index] = cumulative;
      index++;
    }

    if (index != replacementIds.length) {
      replacementIds = Arrays.copyOf(replacementIds, index);
      cumulativeWeights = Arrays.copyOf(cumulativeWeights, index);
    }

    boolean missingPolicyHide = config.neighbors().missingPolicy() == AntiXrayConfig.MissingPolicy.HIDE;

    return new ObfuscationProcessor(codec, registry::isOccluding, targets, replacementIds,
        cumulativeWeights, config.layerObfuscation(), missingPolicyHide, paletteOptions);
  }

  /** 兼容重载：不做调色板重排。 */
  public static ObfuscationProcessor create(ChunkCodec codec, BlockStateRegistry registry,
      AntiXrayConfig config, Logger logger) {
    return create(codec, registry, config, logger, PaletteOptions.DISABLED);
  }

  /** 是否具备生效条件（目标与伪装方块都已解析）。 */
  public boolean isActive() {
    return !targets.isEmpty() && replacementIds.length > 0;
  }

  /** 改写一个区块（不含邻块快照，缺失策略会生效）。 */
  public Result rewrite(byte[] source, int sectionCount, long seed) {
    return rewrite(source, sectionCount, seed, null);
  }

  /**
   * 改写一个区块。
   *
   * @param source       封包中的原始 section 字节（不会被修改）
   * @param sectionCount 该世界的 section 数量
   * @param seed         伪装随机种子；同种子同输入必然得到同结果（缓存可安全复用）
   * @param neighbors    4 个水平邻块的贴边快照；{@code null} 表示缺失，按缺失策略处理
   * @return 改写结果；解码/重编码异常都回退为「原字节 + 空位置 + 异常摘要」（fail-open）
   */
  public Result rewrite(byte[] source, int sectionCount, long seed, NeighborEdges neighbors) {
    if (!isActive() || sectionCount <= 0 || source.length == 0) {
      return new Result(source, NO_POSITIONS, null);
    }

    Random random = new Random(seed);
    int[] positions = new int[16];
    int count = 0;
    boolean changed = false;

    Chunk chunk;
    try {
      chunk = codec.decode(source, sectionCount);
    } catch (RuntimeException exception) {
      // 解码失败：多半是区块二进制布局与预期不符（版本/第三方改写），必须可观测，不能静默当作「无改动」
      return new Result(source, NO_POSITIONS, describe(exception));
    }

    try {
      for (int sectionIndex = 0; sectionIndex < chunk.getSectionCount(); sectionIndex++) {
        ChunkSection section = chunk.getSection(sectionIndex);
        if (section == null || section.isEmpty()) {
          continue;
        }

        int baseY = sectionIndex << 4;
        int layerY = Integer.MIN_VALUE;
        int layerState = -1;
        boolean sectionChanged = false;

        for (int index = 0; index < SECTION_VOLUME; index++) {
          if (!targets.get(section.getBlockState(index))) {
            continue;
          }
          if (!isFullyOccluded(chunk, baseY, index, neighbors)) {
            continue;
          }

          int replacement;
          if (layerObfuscation) {
            int y = baseY | (index >> 8 & 15);
            if (layerY != y) {
              layerY = y;
              layerState = nextReplacement(random);
            }
            replacement = layerState;
          } else {
            replacement = nextReplacement(random);
          }

          section.setBlockState(index, replacement);
          sectionChanged = true;
          changed = true;

          if (count == positions.length) {
            positions = Arrays.copyOf(positions, count << 1);
          }
          positions[count++] = index + (baseY << 8);
        }

        // 调色板压缩重排只作用于被改动的 section：未改动的 section 保持原字节（选择性重编码的前提）
        if (sectionChanged && paletteOptions.reorder()) {
          section.reorderPaletteByFrequency(paletteOptions.strictVerify());
        }
      }

      if (!changed) {
        // 无改动：直接复用原字节，跳过整次重编码
        return new Result(source, NO_POSITIONS, null);
      }
      return new Result(chunk.finalizeOutput(), Arrays.copyOf(positions, count), null);
    } catch (RuntimeException exception) {
      return new Result(source, NO_POSITIONS, describe(exception));
    } finally {
      chunk.close();
    }
  }

  /**
   * 只做「解码侧统计」的诊断：section 数、出现过的方块状态种类数、命中目标方块的个数。
   *
   * <p><b>用途</b>：供「首次改写诊断」一次性日志区分两种失效——①解码出的状态 id 与目标 id 不匹配
   * （目标命中数 = 0，即 {@code shouldObfuscate} 恒为 false）；②匹配与替换都正常但写回没生效。
   * 因此本方法只回答「解码到底看到了什么」：不做遮挡判定、不改写任何字节。
   *
   * <p><b>开销</b>：会完整解码一次区块并遍历全部 4096×N 个方块状态，只允许在一次性诊断路径上调用，
   * 绝不得进入封包热路径。
   *
   * @return 诊断结果；区块解码失败（布局与预期不符）时返回 {@code null}
   */
  public Diagnostic diagnose(byte[] source, int sectionCount) {
    if (source == null || source.length == 0 || sectionCount <= 0) {
      return new Diagnostic(0, 0, 0);
    }

    Chunk chunk;
    try {
      chunk = codec.decode(source, sectionCount);
    } catch (RuntimeException exception) {
      return null;
    }

    try {
      BitSet seen = new BitSet();
      int targetMatches = 0;
      for (int sectionIndex = 0; sectionIndex < chunk.getSectionCount(); sectionIndex++) {
        ChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) {
          continue;
        }
        for (int state : section.readAllBlockStates()) {
          seen.set(state);
          if (targets.get(state)) {
            targetMatches++;
          }
        }
      }
      return new Diagnostic(chunk.getSectionCount(), seen.cardinality(), targetMatches);
    } catch (RuntimeException exception) {
      return null;
    } finally {
      chunk.close();
    }
  }

  /** 异常摘要（供可观测的告警日志使用；不含堆栈，避免刷屏）。 */
  private static String describe(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank()
        ? throwable.getClass().getSimpleName()
        : throwable.getClass().getSimpleName() + ": " + message;
  }

  /**
   * 6 面正交遮挡判定。
   *
   * @param baseY 该 section 的起始 Y（区块内相对坐标）
   * @param index 该 section 内的元素序号（{@code y << 8 | z << 4 | x}）
   */
  private boolean isFullyOccluded(Chunk chunk, int baseY, int index, NeighborEdges neighbors) {
    int x = index & 15;
    int z = index >> 4 & 15;
    int y = baseY | (index >> 8 & 15);

    return isOccluding(chunk, y + 1, x, z, neighbors)
        && isOccluding(chunk, y - 1, x, z, neighbors)
        && isOccluding(chunk, y, x + 1, z, neighbors)
        && isOccluding(chunk, y, x - 1, z, neighbors)
        && isOccluding(chunk, y, x, z + 1, neighbors)
        && isOccluding(chunk, y, x, z - 1, neighbors);
  }

  /** 区块内相对坐标处的方块是否遮挡；越出世界上下界视为未遮挡，越出本区块改查邻块贴边快照。 */
  private boolean isOccluding(Chunk chunk, int y, int x, int z, NeighborEdges neighbors) {
    if (y < 0) {
      return false;
    }

    int sectionIndex = y >> 4;
    if (sectionIndex >= chunk.getSectionCount()) {
      return false;
    }

    if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
      ChunkSection section = chunk.getSection(sectionIndex);
      if (section == null) {
        return false;
      }
      return occlusionTable.test(section.getBlockState((y & 15) << 8 | z << 4 | x));
    }

    return neighborOccluding(neighbors, x, y, z);
  }

  /** 越出本区块的相邻方块是否遮挡；邻块快照缺失时按配置策略处理。 */
  private boolean neighborOccluding(NeighborEdges neighbors, int x, int y, int z) {
    if (neighbors != null) {
      NeighborEdges.Side side;
      int localOther;
      if (x < 0) {
        side = NeighborEdges.Side.X_MINUS;
        localOther = z;
      } else if (x > 15) {
        side = NeighborEdges.Side.X_PLUS;
        localOther = z;
      } else if (z < 0) {
        side = NeighborEdges.Side.Z_MINUS;
        localOther = x;
      } else if (z > 15) {
        side = NeighborEdges.Side.Z_PLUS;
        localOther = x;
      } else {
        return false;
      }

      int state = neighbors.occluding(side, y, localOther);
      if (state != NeighborEdges.MISSING) {
        return state == 1;
      }
    }

    return missingPolicyHide;
  }

  /** 按累计权重随机挑选一个伪装方块状态 id。 */
  private int nextReplacement(Random random) {
    int roll = random.nextInt(cumulativeWeights[cumulativeWeights.length - 1]);
    for (int i = 0; i < cumulativeWeights.length; i++) {
      if (roll < cumulativeWeights[i]) {
        return replacementIds[i];
      }
    }
    return replacementIds[replacementIds.length - 1];
  }
}