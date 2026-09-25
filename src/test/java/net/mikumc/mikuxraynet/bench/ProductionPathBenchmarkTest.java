package net.mikumc.mikuxraynet.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import net.mikumc.mikuxraynet.antixray.NeighborEdges;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import org.junit.jupiter.api.Test;

/**
 * 生产路径基准：<b>每区块完整改写流水线</b>的实际耗时与分配量。
 *
 * <p>与既有「解码 → 改 N 个方块 → 重编码」基准不同，本节测的是反矿透生产链路上真正的大头：
 * <pre>
 *   解码 → 6 面遮挡判定（真实权重表 + 隐藏方块集合）→ 权重随机替换 → 选择性 section 重编码 → 输出
 * </pre>
 * 被测对象是<b>真实的</b> {@link ObfuscationProcessor}（不另写简化逻辑），其遮挡表与目标位图由一个
 * <b>离线桩注册表</b>{@link StubRegistry} 提供（不依赖 PacketEvents 平台初始化）。
 *
 * <p>测量口径：
 * <ul>
 *   <li>每形态一行：中位耗时、最小/最大、分配量、<b>实际被替换的方块数</b>、<b>实际重编码的 section 数</b>、输出字节；</li>
 *   <li>外推：按每区块中位耗时推算「1000 个区块的流水线 CPU 时间」与「每玩家每秒发送 5 个区块时的单核占用」；</li>
 *   <li>邻块贴边快照（{@link NeighborEdges} 的构造）发生在主线程 / 区域线程，<b>不在 worker 热路径</b>，
 *       因此单独一列测量，不混入每区块耗时；</li>
 *   <li>断言只覆盖「语义正确 + 数值被成功采集」，不断言「谁更快」。</li>
 * </ul>
 */
class ProductionPathBenchmarkTest {

  private static final int SECTION_COUNT = BenchFixtures.SECTION_COUNT;
  private static final int SECTION_VOLUME = BenchFixtures.SECTION_VOLUME;
  private static final int COLUMN_VOLUME = BenchFixtures.COLUMN_VOLUME;

  private static final int WARMUP_ROUNDS = 10;
  private static final int MEASURE_ROUNDS = 60;
  private static final int MIN_MEASURE_ROUNDS = 5;
  private static final long TIME_BUDGET_NANOS = 8_000_000_000L;
  private static final int SNAPSHOT_ROUNDS = 200;

  /** 伪装随机种子：固定值，保证同输入同结果（便于缓存与对拍）。 */
  private static final long SEED = 20240924L;

  /** 邻块贴边快照的高度（section 数 × 16，与主世界 384 高度一致）。 */
  private static final int NEIGHBOR_HEIGHT = SECTION_COUNT * 16;

  private static final Path REPORT_PATH = Path.of("target", "benchmark-report.md");
  private static final String PRODUCTION_MARKER = "## 4. 生产路径";

  /** 一行生产路径测量结果。 */
  private record PipelineRow(String shape, long medianNanos, long minNanos, long maxNanos,
      long allocatedBytes, int replacedBlocks, int reencodedSections, int outputBytes) {
  }

  /** 邻块快照构造的测量结果。 */
  private record SnapshotRow(long medianNanos, long minNanos, long maxNanos, long allocatedBytes,
      int planeBytes) {
  }

  @Test
  void benchmarkProductionPipeline() throws IOException {
    long startedAt = System.nanoTime();

    StubRegistry registry = StubRegistry.of();
    ObfuscationProcessor processor = processor(registry);
    assertTrue(processor.isActive(), "生产路径处理器应处于生效状态（目标与伪装方块都已解析）");

    List<BenchFixtures.Fixture> shapes = BenchFixtures.productionShapes();

    // 1) 语义校验：改写结果必须「只把目标方块换成伪装方块」，其余方块逐一不变，且同种子可复现
    ChunkCodec codec = new ChunkCodec(registry, BenchFixtures.FLAGS);
    for (BenchFixtures.Fixture shape : shapes) {
      verifySemantics(processor, codec, shape);
    }

    // 2) 估时决定轮数，保证本基准不拖慢 CI
    ThreadMXBean allocationBean = allocationBean();
    long estimate = 0L;
    for (BenchFixtures.Fixture shape : shapes) {
      estimate = Math.max(estimate, estimateIterationNanos(processor, shape));
    }
    int rounds = chooseRounds(estimate, shapes.size());

    // 3) 逐形态测量
    List<PipelineRow> rows = new ArrayList<>();
    for (BenchFixtures.Fixture shape : shapes) {
      byte[] pristine = shape.bytes().clone();
      rows.add(measure(processor, shape, rounds, allocationBean));
      assertArrayEquals(pristine, shape.bytes(), "生产路径不得改写输入字节（" + shape.name() + "）");
    }

    // 4) 邻块快照单独测量（主线程 / 区域线程侧，不属于 worker 热路径）
    SnapshotRow snapshot = measureNeighborSnapshot(allocationBean);

    // 5) 报告：追加为「第 4 节」，并打印到标准输出
    String section = buildSection(rows, snapshot, rounds, allocationBean != null,
        System.nanoTime() - startedAt);
    appendReport(section);
    System.out.println(section);

    // 6) 只断言「数值被成功采集」
    assertEquals(shapes.size(), rows.size(), "测量行数不足：" + rows.size());
    for (PipelineRow row : rows) {
      assertTrue(row.medianNanos() > 0 && row.minNanos() > 0 && row.maxNanos() >= row.medianNanos(),
          "耗时未成功采集：" + row);
      assertTrue(row.outputBytes() > 0, "输出字节未成功采集：" + row);
      if (allocationBean != null) {
        assertTrue(row.allocatedBytes() > 0, "分配量未成功采集：" + row);
      }
    }
    assertTrue(snapshot.medianNanos() > 0
        && snapshot.planeBytes() == 4L * NeighborEdges.planeLongCount(NEIGHBOR_HEIGHT) * 8L,
        "邻块快照未成功采集：" + snapshot);
  }

  /** 用真实配置语义装配处理器：隐藏集合 = 矿，伪装权重 stone:10 / deepslate:8，层状关闭，缺失策略 hide。 */
  private static ObfuscationProcessor processor(StubRegistry registry) {
    ChunkCodec codec = new ChunkCodec(registry, BenchFixtures.FLAGS);

    BitSet targets = new BitSet(registry.getUniqueBlockStateCount());
    for (int ore : BenchFixtures.ORE_STATES) {
      targets.set(ore);
    }

    // 与 antixray.yml 默认一致：stone 权重 10、deepslate 权重 8（累计权重 {10, 18}）。
    // netherrack 在桩注册表中无对应状态，故省略；layer-obfuscation=false；missing-policy=hide。
    // 调色板重排取 PaletteOptions.DISABLED —— 与 bandwidth.yml: palette.reorder 的新默认值 false 一致。
    return new ObfuscationProcessor(codec, registry::isOccluding, targets,
        new int[] {BenchFixtures.STONE, BenchFixtures.DEEPSLATE}, new int[] {10, 18},
        false, true, ObfuscationProcessor.PaletteOptions.DISABLED);
  }

  /**
   * 语义校验：改写只允许把「原始为矿（目标）」且 6 面全遮挡的方块替换成伪装方块；
   * 其余方块逐一不变；同种子重复调用必须逐字节一致。
   */
  private static void verifySemantics(ObfuscationProcessor processor, ChunkCodec codec,
      BenchFixtures.Fixture shape) {
    byte[] source = shape.bytes().clone();
    int[] original = readStates(codec, source);

    ObfuscationProcessor.Result result = processor.rewrite(source, SECTION_COUNT, SEED, null);
    ObfuscationProcessor.Result again = processor.rewrite(source, SECTION_COUNT, SEED, null);
    assertArrayEquals(result.data(), again.data(), shape.name() + "：同种子必须得到相同字节");
    assertArrayEquals(result.obfuscatedPositions(), again.obfuscatedPositions(),
        shape.name() + "：同种子必须得到相同的伪装坐标");

    boolean hasTargets = false;
    for (int state : original) {
      if (isTarget(state)) {
        hasTargets = true;
        break;
      }
    }

    if (!hasTargets) {
      assertFalse(result.changed(), shape.name() + "：无隐藏目标时不得发生任何改写");
      assertSame(source, result.data(), shape.name() + "：无改动时必须复用原字节，不触发重编码");
      return;
    }

    assertTrue(result.changed(), shape.name() + "：存在被遮挡的矿，应发生改写");

    int[] rewritten = readStates(codec, result.data());
    boolean[] replaced = new boolean[COLUMN_VOLUME];
    for (int position : result.obfuscatedPositions()) {
      assertTrue(position >= 0 && position < COLUMN_VOLUME, "伪装坐标越界：" + position);
      assertFalse(replaced[position], "伪装坐标重复：" + position);
      replaced[position] = true;
      assertTrue(isTarget(original[position]),
          shape.name() + "：伪装坐标 " + position + " 原本不是隐藏目标（" + original[position] + "）");
      assertTrue(isReplacement(rewritten[position]),
          shape.name() + "：伪装坐标 " + position + " 未被替换为伪装方块（" + rewritten[position] + "）");
    }

    for (int index = 0; index < COLUMN_VOLUME; index++) {
      if (!replaced[index]) {
        assertEquals(original[index], rewritten[index],
            shape.name() + "：未伪装位置的方块被意外改动（列序号 " + index + "）");
      }
    }
  }

  /** 从编码结果读回整列方块状态（按 section 顺序拼接，序号为 {@code y<<8|z<<4|x}）。 */
  private static int[] readStates(ChunkCodec codec, byte[] encoded) {
    int[] states = new int[COLUMN_VOLUME];
    try (Chunk chunk = codec.decode(encoded, SECTION_COUNT)) {
      for (int section = 0; section < SECTION_COUNT; section++) {
        int[] local = chunk.getSection(section).readAllBlockStates();
        System.arraycopy(local, 0, states, section * SECTION_VOLUME, SECTION_VOLUME);
      }
    }
    return states;
  }

  private static boolean isTarget(int state) {
    for (int ore : BenchFixtures.ORE_STATES) {
      if (ore == state) {
        return true;
      }
    }
    return false;
  }

  private static boolean isReplacement(int state) {
    return state == BenchFixtures.STONE || state == BenchFixtures.DEEPSLATE;
  }

  /** 实际重编码的 section 数 = 伪装坐标涉及的不同 section 数（未触及的 section 由 codec 原样搬运）。 */
  private static int reencodedSections(int[] positions) {
    long mask = 0L;
    for (int position : positions) {
      mask |= 1L << (position >> 12);
    }
    return Long.bitCount(mask);
  }

  private static long estimateIterationNanos(ObfuscationProcessor processor, BenchFixtures.Fixture shape) {
    byte[] source = shape.bytes();
    long last = 0L;
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      long start = System.nanoTime();
      processor.rewrite(source, SECTION_COUNT, SEED, null);
      last = System.nanoTime() - start;
    }
    return Math.max(1L, last);
  }

  private static int chooseRounds(long estimatedNanos, int combos) {
    long affordableIterations = TIME_BUDGET_NANOS / Math.max(1L, estimatedNanos);
    long perCombo = affordableIterations / Math.max(1, combos);
    int rounds = (int) (perCombo - WARMUP_ROUNDS);
    return Math.max(MIN_MEASURE_ROUNDS, Math.min(MEASURE_ROUNDS, rounds));
  }

  private static PipelineRow measure(ObfuscationProcessor processor, BenchFixtures.Fixture shape, int rounds,
      ThreadMXBean allocationBean) {
    byte[] source = shape.bytes();
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      processor.rewrite(source, SECTION_COUNT, SEED, null);
    }

    long threadId = Thread.currentThread().getId();
    long[] durations = new long[rounds];
    long[] allocations = new long[rounds];
    int replacedBlocks = -1;
    int reencodedSections = -1;
    int outputBytes = -1;

    for (int i = 0; i < rounds; i++) {
      long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
      long start = System.nanoTime();
      ObfuscationProcessor.Result result = processor.rewrite(source, SECTION_COUNT, SEED, null);
      durations[i] = System.nanoTime() - start;
      allocations[i] = allocationBean == null ? 0L
          : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

      int replaced = result.obfuscatedPositions().length;
      int reencoded = reencodedSections(result.obfuscatedPositions());
      if (replacedBlocks < 0) {
        replacedBlocks = replaced;
        reencodedSections = reencoded;
        outputBytes = result.data().length;
      } else {
        assertEquals(replacedBlocks, replaced, shape.name() + "：同一输入的替换方块数必须稳定");
        assertEquals(reencodedSections, reencoded, shape.name() + "：同一输入的重编码 section 数必须稳定");
        assertEquals(outputBytes, result.data().length, shape.name() + "：同一输入的输出字节数必须稳定");
      }
    }

    Arrays.sort(durations);
    Arrays.sort(allocations);
    return new PipelineRow(shape.name(), durations[rounds / 2], durations[0], durations[rounds - 1],
        allocations[rounds / 2], replacedBlocks, reencodedSections, outputBytes);
  }

  /**
   * 单独测量「构造一次 4 邻块贴边快照」的耗时与分配。
   *
   * <p>真实抓取由 {@code NeighborChunkProvider#capture} 完成，发生在主线程 / Folia 区域线程，
   * 并用 {@code world.getBlockData(...).isOccluding()} 读方块——离线不可用（需要 Bukkit 世界）。
   * 因此这里用<b>纯计算的布尔平面模拟</b>：以确定性的纯函数代替「读方块是否遮挡」与
   * 「列高度上界查询」，但保持与 {@code capturePlane} 相同的循环形状（4 侧 × 16 列，
   * 每列只读到该列的模拟上界为止，每侧写出 height×16 bit），并用真实的 {@link NeighborEdges} 承载结果。
   * 该模拟是真实抓取耗时的下界（不含缓存查找、加载检查与 Bukkit 读块）。
   */
  private static SnapshotRow measureNeighborSnapshot(ThreadMXBean allocationBean) {
    long threadId = Thread.currentThread().getId();
    long[] durations = new long[SNAPSHOT_ROUNDS];
    long[] allocations = new long[SNAPSHOT_ROUNDS];
    int planeBytes = 0;
    NeighborEdges last = null;

    for (int i = 0; i < SNAPSHOT_ROUNDS; i++) {
      long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
      long start = System.nanoTime();
      NeighborEdges edges = buildSnapshot(NEIGHBOR_HEIGHT);
      durations[i] = System.nanoTime() - start;
      allocations[i] = allocationBean == null ? 0L
          : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
      planeBytes = NeighborEdges.planeLongCount(edges.height()) << 3;
      last = edges;
    }

    assertTrue(last != null && last.height() == NEIGHBOR_HEIGHT && last.has(NeighborEdges.Side.X_PLUS),
        "邻块快照应构造成功");
    Arrays.sort(durations);
    Arrays.sort(allocations);
    return new SnapshotRow(durations[SNAPSHOT_ROUNDS / 2], durations[0], durations[SNAPSHOT_ROUNDS - 1],
        allocations[SNAPSHOT_ROUNDS / 2], planeBytes * 4);
  }

  /** 构造一次 4 邻块贴边快照（纯计算模拟，见 {@link #measureNeighborSnapshot}）。 */
  private static NeighborEdges buildSnapshot(int height) {
    return new NeighborEdges(height,
        buildPlane(height, NeighborEdges.Side.X_MINUS),
        buildPlane(height, NeighborEdges.Side.X_PLUS),
        buildPlane(height, NeighborEdges.Side.Z_MINUS),
        buildPlane(height, NeighborEdges.Side.Z_PLUS));
  }

  /** 单个侧面的贴边层：位下标为 {@code y << 4 | localOther}，与 {@code NeighborChunkProvider#capturePlane} 一致。 */
  private static long[] buildPlane(int height, NeighborEdges.Side side) {
    long[] plane = new long[NeighborEdges.planeLongCount(height)];
    for (int local = 0; local < 16; local++) {
      for (int y = 0; y <= simulatedColumnTop(height, side, local); y++) {
        if (simulatedOccluding(side, local, y)) {
          NeighborEdges.setOccluding(plane, y << 4 | local);
        }
      }
    }
    return plane;
  }

  /**
   * 代替 {@code World#getHighestBlockYAt(...)} 的确定性「列高度上界」模拟：地表放在世界高度的 2/5 处
   * （模拟主世界「只有下半部分有方块」），逐列略起伏；与真实抓取一样，上界之上一位都不读。
   */
  private static int simulatedColumnTop(int height, NeighborEdges.Side side, int local) {
    return Math.min(height - 1, height * 2 / 5 + local % 3 + side.ordinal());
  }

  /** 代替 {@code world.getBlockData(...).isOccluding()} 的确定性纯计算谓词（约 7/8 遮挡）。 */
  private static boolean simulatedOccluding(NeighborEdges.Side side, int local, int y) {
    int mixed = y * 31 + local * 17 + side.ordinal() * 7;
    mixed ^= mixed >>> 13;
    return (mixed & 7) != 0;
  }

  /** JVM 的线程分配量统计；不支持则返回 {@code null}（报告里标注该列不可用）。 */
  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (!(bean instanceof ThreadMXBean allocationBean)) {
      return null;
    }
    if (!allocationBean.isThreadAllocatedMemorySupported()) {
      return null;
    }
    if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
      allocationBean.setThreadAllocatedMemoryEnabled(true);
    }
    return allocationBean;
  }

  /** 追加为「第 4 节」：保留既有报告正文，去掉上一次的第 4 节后重新追加（与测试类执行顺序无关）。 */
  private static void appendReport(String section) throws IOException {
    Files.createDirectories(REPORT_PATH.getParent());
    String existing = Files.exists(REPORT_PATH)
        ? Files.readString(REPORT_PATH, StandardCharsets.UTF_8) : "";
    int marker = existing.indexOf(PRODUCTION_MARKER);
    String base = marker < 0 ? existing : existing.substring(0, marker);
    if (!base.isEmpty() && !base.endsWith("\n")) {
      base += "\n";
    }
    Files.writeString(REPORT_PATH, base + section, StandardCharsets.UTF_8);
  }

  private static String buildSection(List<PipelineRow> rows, SnapshotRow snapshot, int rounds,
      boolean allocationsSupported, long elapsedNanos) {
    StringBuilder report = new StringBuilder();

    report.append("\n## 4. 生产路径：每区块完整改写流水线\n\n");
    report.append("- 流水线：解码 → 6 面遮挡判定（真实权重表 + 隐藏方块集合）→ 权重随机替换 → 选择性 section 重编码 → 输出\n");
    report.append("- 被测对象：**真实的** `ObfuscationProcessor`（生产类，未另写简化逻辑）；"
        + "遮挡表与目标位图由**离线桩注册表**（`StubRegistry`，不依赖 PacketEvents 平台初始化）提供\n");
    report.append("- 配置口径（与 antixray.yml 默认一致）：隐藏集合 = 6 种矿；伪装权重 stone:10 / deepslate:8；"
        + "层状伪装关闭；邻块缺失策略 hide\n");
    report.append("- 调色板重排取 `PaletteOptions.DISABLED`，与 `bandwidth.yml: palette.reorder` 的新默认值 false 一致\n");
    report.append("- 每形态预热 ").append(WARMUP_ROUNDS).append(" 轮、测量 ").append(rounds)
        .append(" 轮；耗时取中位数；分配量为每轮 `getThreadAllocatedBytes` 差值\n");
    report.append("- 本基准自身耗时 ").append(elapsedNanos / 1_000_000L).append(" ms\n\n");

    report.append("> **桩注册表说明**：`StubRegistry` 只构造基准所需的十余个状态"
        + "（空气 / 石头 / 深板岩 / 水 / 6 种矿），遮挡位图以 `BitSet` 承载、口径与 "
        + "`BlockStateRegistry#isOccluding` 一致。它**不含**真实注册表的全量状态、台阶/玻璃等形状与材质规则"
        + "以及用户覆盖表，且未实测服务端全局 id 映射（id 为合成稠密整数）；仅供离线基准使用。\n\n");

    if (!allocationsSupported) {
      report.append("> 当前 JVM 不支持线程分配量统计，分配列以 0 占位。\n\n");
    }

    report.append("### 4.1 逐形态：每区块流水线\n\n");
    report.append("| 形态 | 中位耗时(ms) | 最小/最大 | 分配(MB) | 实际被替换方块数 | 实际重编码 section 数 | 输出字节 |\n");
    report.append("| --- | --- | --- | --- | --- | --- | --- |\n");
    for (PipelineRow row : rows) {
      report.append("| ").append(row.shape())
          .append(" | ").append(millis(row.medianNanos()))
          .append(" | ").append(millis(row.minNanos())).append(" / ").append(millis(row.maxNanos()))
          .append(" | ").append(String.format(Locale.ROOT, "%.2f", row.allocatedBytes() / 1048576.0))
          .append(" | ").append(row.replacedBlocks())
          .append(" | ").append(row.reencodedSections())
          .append(" | ").append(row.outputBytes())
          .append(" |\n");
    }
    report.append("\n> 「全实心」「乱序调色板（合成）」两种形态不含隐藏矿，因此替换数与重编码 section 数必然为 0"
        + "（改写无改动时直接复用原字节、跳过整次重编码）；这是如实结果，不做粉饰。\n");

    report.append("\n### 4.2 外推（按 4.1 的中位耗时）\n\n");
    report.append("| 形态 | 每区块中位耗时(ms) | 1000 区块流水线 CPU(ms) | 每玩家每秒 5 区块的单核占用 |\n");
    report.append("| --- | --- | --- | --- |\n");
    for (PipelineRow row : rows) {
      double nanos = row.medianNanos();
      report.append("| ").append(row.shape())
          .append(" | ").append(millis(row.medianNanos()))
          .append(" | ").append(String.format(Locale.ROOT, "%.1f", nanos * 1000.0 / 1_000_000.0))
          .append(" | ").append(String.format(Locale.ROOT, "%.2f%%", nanos * 5.0 / 1_000_000_000.0 * 100.0))
          .append(" |\n");
    }
    report.append("\n> 「每玩家每秒 5 区块」按 `5 × 每区块耗时 ÷ 1 秒` 折算为单核占用百分比"
        + "（单一玩家、不叠加多玩家；仅描述该流水线本身占用，不含网络与压缩）。\n");

    report.append("\n### 4.3 邻块贴边快照（单独测量，不计入每区块热路径）\n\n");
    report.append("- 抓取发生在主线程 / Folia 区域线程，**不在** worker 热路径，故单独成列\n");
    report.append("- 真实抓取读 Bukkit 世界（离线不可用），这里用**纯计算的布尔平面模拟**："
        + "以确定性纯函数代替 `world.getBlockData(...).isOccluding()` 与列高度上界查询，"
        + "循环形状与 `NeighborChunkProvider#capturePlane` 一致（4 侧 × 16 列，每列读到该列模拟上界为止，"
        + "每侧写出 height×16 bit），结果用真实 `NeighborEdges` 承载；"
        + "是真实耗时的**下界**（不含缓存查找、加载检查与 Bukkit 读块）\n");
    report.append("| 指标 | 值 |\n| --- | --- |\n");
    report.append("| 构造一次 4 邻块快照中位耗时(ms) | ").append(millis(snapshot.medianNanos())).append(" |\n");
    report.append("| 最小/最大(ms) | ").append(millis(snapshot.minNanos())).append(" / ")
        .append(millis(snapshot.maxNanos())).append(" |\n");
    report.append("| 分配(KB) | ").append(String.format(Locale.ROOT, "%.1f", snapshot.allocatedBytes() / 1024.0))
        .append(" |\n");
    report.append("| 快照总字节（4 平面，height=").append(NEIGHBOR_HEIGHT).append("） | ")
        .append(snapshot.planeBytes()).append(" |\n");
    report.append("> 该快照每区块只抓取一次并缓存；世界卸载时整体失效。\n");

    report.append("\n### 4.4 「全实心 section 快路径」对照\n\n");
    report.append("> **未能完成，如实说明**：生产 `ObfuscationProcessor` 中**不存在**可切换的「全实心 section 快路径」；"
        + "与之相关的唯一短路是 `section.isEmpty()`（全空气 section 直接跳过），"
        + "它在生产代码里没有开关。按任务约束（不得改动生产逻辑），无法在不改生产代码的前提下做该 A/B 对照，故跳过。\n");

    return report.toString();
  }

  private static String millis(long nanos) {
    return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
  }
}