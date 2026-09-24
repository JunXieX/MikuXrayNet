package net.mikumc.mikuxraynet.bench;

import com.sun.management.ThreadMXBean;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 离线基准：在「解码区块 → 改 N 个方块 → 重新编码」这一任务上，量化自研字节级 codec 与
 * PacketEvents 结构化区块模型的耗时、线程分配量与输出字节数。
 *
 * <p>测量口径：
 * <ul>
 *   <li>耗时：每档预热 {@value #WARMUP_ROUNDS} 轮后测量若干轮，取<b>中位数</b>，并给出最小/最大体现抖动；</li>
 *   <li>分配量：{@link ThreadMXBean#getThreadAllocatedBytes(long)} 的每轮差值（确定性指标，不受 CI 机器抖动影响）；</li>
 *   <li>输出字节：重编码后整列字节数（确定性）；</li>
 *   <li>压缩字节：对整列字节做 zlib（level {@code 6}，与 Minecraft 区块包默认压缩级别一致）后的长度（确定性）。
 *       调色板重排只改索引排列、不改位宽与数组长度，因此<b>原始字节长度恒等</b>，带宽收益只能体现在压缩字节上；</li>
 *   <li>语义：同一输入 + 同一批改写下，各路径编码出的方块序列（24×4096 项）必须逐项相同，
 *       并额外做双向交叉解码（自研读 PE 字节、PE 读自研字节），不一致立即失败并打印差异位置。</li>
 * </ul>
 *
 * <p>断言只覆盖「语义一致 + 各路径都能稳定完成 + 数值被成功采集」，不断言「谁更快」——
 * CI 机器的耗时列会抖动，结论由读者看表得出。PacketEvents 路径若离线不可用，则跳过该路径并报告原因，而不是失败。
 */
class ChunkPathBenchmarkTest {

  private static final int[] EDIT_COUNTS = {10, 100, 1000};
  private static final int WARMUP_ROUNDS = 10;
  private static final int MEASURE_ROUNDS = 30;
  private static final int MIN_MEASURE_ROUNDS = 5;
  /** 基准自身的时间预算，超出就按比例下调测量轮数（实际轮数写进报告）。 */
  private static final long TIME_BUDGET_NANOS = 12_000_000_000L;
  private static final Path REPORT_PATH = Path.of("target", "benchmark-report.md");

  /** 优化前的 CI 基线（由本任务提供，用于与本次实测做优化前后对照）。 */
  private static final Object[][] ALLOCATION_BASELINE = {
      {"全实心", 1000, 0.72},
      {"稀疏矿脉", 1000, 0.71},
      {"洞穴", 1000, 1.34},
  };

  /** 一行测量结果。 */
  private record Row(String shape, int edits, String path, long medianNanos, long minNanos, long maxNanos,
      long allocatedBytes, int outputBytes, int compressedBytes, double zeroRatio, int reorderedSections) {
  }

  @Test
  void benchmarkDecodeEditReencode() throws IOException {
    long startedAt = System.nanoTime();

    List<BenchFixtures.Fixture> fixtures = BenchFixtures.all();
    OurCodecPath codecWithoutReorder = new OurCodecPath(false);
    OurCodecPath codecWithReorder = new OurCodecPath(true);
    List<ChunkPath> ourPaths = List.of(codecWithoutReorder, codecWithReorder);

    String peUnavailableReason = probePePath(fixtures.getFirst());
    ChunkPath pePath = peUnavailableReason == null ? new PeModelPath() : null;

    // 1) 语义校验：同一输入 + 同一批改写，期望序列由「原始列的方块序列 + 改写」推出
    for (BenchFixtures.Fixture fixture : fixtures) {
      int[] baseStates = codecWithoutReorder.readStates(fixture.bytes());
      for (int editCount : EDIT_COUNTS) {
        BenchFixtures.Edit[] edits = BenchFixtures.edits(editCount);
        int[] expected = expectedStates(baseStates, edits);
        String label = fixture.name() + " / 改写 " + editCount;

        byte[] outputWithoutReorder = codecWithoutReorder.encode(fixture.bytes(), edits);
        assertSameSequence("自研 codec（" + label + "）", expected, codecWithoutReorder.readStates(outputWithoutReorder));

        byte[] outputWithReorder = codecWithReorder.encode(fixture.bytes(), edits);
        assertSameSequence("自研 codec + 重排（" + label + "）", expected, codecWithReorder.readStates(outputWithReorder));
        // 重排只重排调色板顺序与位打包索引，不得改变输出长度（位宽、调色板条目数都不变）
        assertTrue(outputWithReorder.length <= outputWithoutReorder.length,
            "开启重排不得使输出变长（" + label + "）：" + outputWithReorder.length + " > " + outputWithoutReorder.length);

        if (pePath != null) {
          byte[] peOutput = pePath.encode(fixture.bytes(), edits);
          assertSameSequence("PacketEvents 模型（" + label + "）", expected, pePath.readStates(peOutput));
          assertSameSequence("交叉解码：PE 读自研字节（" + label + "）", expected, pePath.readStates(outputWithReorder));
          assertSameSequence("交叉解码：自研读 PE 字节（" + label + "）", expected, codecWithoutReorder.readStates(peOutput));
        }
      }
    }

    // 2) 测量：先估时决定轮数，保证基准自身不拖慢 CI
    ThreadMXBean allocationBean = allocationBean();
    int heaviestEditCount = EDIT_COUNTS[EDIT_COUNTS.length - 1];
    long estimate = 0L;
    for (ChunkPath path : ourPaths) {
      estimate = Math.max(estimate,
          estimateIterationNanos(path, fixtures.getFirst(), BenchFixtures.edits(heaviestEditCount)));
    }
    if (pePath != null) {
      estimate = Math.max(estimate,
          estimateIterationNanos(pePath, fixtures.getFirst(), BenchFixtures.edits(heaviestEditCount)));
    }
    int pathCount = ourPaths.size() + (pePath == null ? 0 : 1);
    int rounds = chooseRounds(estimate, fixtures.size() * EDIT_COUNTS.length * pathCount);

    List<Row> rows = new ArrayList<>();
    for (BenchFixtures.Fixture fixture : fixtures) {
      byte[] pristine = fixture.bytes().clone();
      for (int editCount : EDIT_COUNTS) {
        BenchFixtures.Edit[] edits = BenchFixtures.edits(editCount);
        for (ChunkPath path : ourPaths) {
          rows.add(measure(path, fixture, edits, rounds, allocationBean));
        }
        if (pePath != null) {
          rows.add(measure(pePath, fixture, edits, rounds, allocationBean));
        }
      }
      assertArrayEquals(pristine, fixture.bytes(), "被测路径不得改写输入字节（" + fixture.name() + "）");
    }

    // 3) 报告：写入文件并打印同一张表
    String report = buildReport(fixtures.size(), rows, rounds, peUnavailableReason, allocationBean != null,
        System.nanoTime() - startedAt);
    Files.createDirectories(REPORT_PATH.getParent());
    // 保留另一个基准类已写入的「第 4 节 生产路径」，避免测试类执行顺序不同导致该节丢失
    Files.writeString(REPORT_PATH, report + productionSectionOf(REPORT_PATH), StandardCharsets.UTF_8);
    System.out.println(report);

    // 4) 只断言「数值被成功采集」与「各路径都稳定完成」
    assertTrue(rows.size() == fixtures.size() * EDIT_COUNTS.length * pathCount, "测量行数不足：" + rows.size());
    for (Row row : rows) {
      assertTrue(row.medianNanos() > 0 && row.minNanos() > 0 && row.maxNanos() >= row.medianNanos(),
          "耗时未成功采集：" + row);
      assertTrue(row.outputBytes() > 0, "输出字节未成功采集：" + row);
      assertTrue(row.compressedBytes() > 0, "压缩字节未成功采集：" + row);
      if (allocationBean != null) {
        assertTrue(row.allocatedBytes() > 0, "分配量未成功采集：" + row);
      }
    }
  }

  /**
   * 读取已存在的报告里「第 4 节 生产路径」（由 {@link ProductionPathBenchmarkTest} 追加）；不存在返回空串。
   *
   * <p>用于让两个基准类的执行顺序不影响最终报告：无论谁先写，第 4 节都不会被覆盖丢失。
   */
  private static String productionSectionOf(Path path) {
    try {
      if (!Files.exists(path)) {
        return "";
      }
      String content = Files.readString(path, StandardCharsets.UTF_8);
      int marker = content.indexOf("## 4. 生产路径");
      return marker < 0 ? "" : "\n" + content.substring(marker);
    } catch (IOException exception) {
      return "";
    }
  }

  /** PacketEvents 路径的可用性探测：不可用（离线缺平台初始化等）时返回原因，测试转为跳过该路径。 */
  private static String probePePath(BenchFixtures.Fixture fixture) {
    try {
      byte[] output = new PeModelPath().encode(fixture.bytes(), BenchFixtures.edits(1));
      return output.length > 0 ? null : "PE 路径编码结果为空";
    } catch (Throwable failure) {
      return failure.getClass().getName() + ": " + failure.getMessage();
    }
  }

  /** 期望的整列方块序列：原始序列叠加改写（与路径无关，各路径必须都收敛到它）。 */
  private static int[] expectedStates(int[] baseStates, BenchFixtures.Edit[] edits) {
    int[] expected = baseStates.clone();
    for (BenchFixtures.Edit edit : edits) {
      expected[edit.section() * BenchFixtures.SECTION_VOLUME + edit.localIndex()] = edit.state();
    }
    return expected;
  }

  /** 逐项比对整列方块序列，不一致时打印首个差异的精确位置。 */
  private static void assertSameSequence(String label, int[] expected, int[] actual) {
    assertEquals(expected.length, actual.length, label + "：方块序列长度不一致");
    for (int index = 0; index < expected.length; index++) {
      if (expected[index] != actual[index]) {
        int local = index % BenchFixtures.SECTION_VOLUME;
        fail(String.format(Locale.ROOT,
            "%s：方块序列第 %d 项不一致（section=%d, x=%d, y=%d, z=%d）：期望 %d，实际 %d",
            label, index, index / BenchFixtures.SECTION_VOLUME, local & 15, local >> 8 & 15, local >> 4 & 15,
            expected[index], actual[index]));
      }
    }
  }

  private static long estimateIterationNanos(ChunkPath path, BenchFixtures.Fixture fixture,
      BenchFixtures.Edit[] edits) {
    long last = 0;
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      long start = System.nanoTime();
      path.encode(fixture.bytes(), edits);
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

  private static Row measure(ChunkPath path, BenchFixtures.Fixture fixture, BenchFixtures.Edit[] edits, int rounds,
      ThreadMXBean allocationBean) {
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      path.encode(fixture.bytes(), edits);
    }

    long threadId = Thread.currentThread().getId();
    long[] durations = new long[rounds];
    long[] allocations = new long[rounds];
    byte[] firstOutput = null;
    int lastOutputBytes = -1;

    for (int i = 0; i < rounds; i++) {
      // 每轮都从未被改动的原始字节重新解码，绝不复用上一轮被改过的数据
      long allocatedBefore = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId);
      long start = System.nanoTime();
      byte[] output = path.encode(fixture.bytes(), edits);
      durations[i] = System.nanoTime() - start;
      allocations[i] = allocationBean == null ? 0L : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;

      if (firstOutput == null) {
        firstOutput = output;
      }
      lastOutputBytes = output.length;
    }

    assertEquals(firstOutput.length, lastOutputBytes, path.name() + "：同一输入的输出字节数必须稳定");

    Arrays.sort(durations);
    Arrays.sort(allocations);

    int reordered = path instanceof OurCodecPath ourCodec ? ourCodec.reorderedSections() : 0;
    return new Row(fixture.name(), edits.length, path.name(), durations[rounds / 2], durations[0],
        durations[rounds - 1], allocations[rounds / 2], firstOutput.length, compressedBytes(firstOutput),
        zeroRatio(firstOutput), reordered);
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

  /** zlib 压缩后的字节数（level 6，与 Minecraft 区块包默认压缩级别一致）；同输入必然同输出。 */
  private static int compressedBytes(byte[] data) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    try {
      deflater.setInput(data);
      deflater.finish();
      byte[] buffer = new byte[8192];
      ByteArrayOutputStream compressed = new ByteArrayOutputStream();
      while (!deflater.finished()) {
        compressed.write(buffer, 0, deflater.deflate(buffer));
      }
      return compressed.size();
    } finally {
      deflater.end();
    }
  }

  /** 零字节占比：调色板重排的直接效果就是让位打包数据出现更多 0 位，从而更容易被压缩。 */
  private static double zeroRatio(byte[] data) {
    int zeros = 0;
    for (byte value : data) {
      if (value == 0) {
        zeros++;
      }
    }
    return data.length == 0 ? 0.0D : (double) zeros / data.length;
  }

  private static String buildReport(int fixtureCount, List<Row> rows, int rounds, String peUnavailableReason,
      boolean allocationsSupported, long elapsedNanos) {
    StringBuilder report = new StringBuilder();

    report.append("# MikuXrayNet 离线基准：解码区块 → 改 N 个方块 → 重新编码\n\n");
    report.append("- 数据形态：16×16×384 完整列（24 个 section，1.18+ 布局，Paper 26.2 字节标志）\n");
    report.append("- 每档预热 ").append(WARMUP_ROUNDS).append(" 轮、测量 ").append(rounds)
        .append(" 轮；耗时取中位数，最小/最大用于体现抖动\n");
    report.append("- 分配量：com.sun.management.ThreadMXBean#getThreadAllocatedBytes 的每轮差值（确定性，不受机器抖动影响）\n");
    report.append("- 输出字节：重编码后整列字节数（确定性）\n");
    report.append("- 压缩字节：整列字节经 zlib（level 6）后的长度（确定性）\n");
    report.append("- 语义校验：同一输入 + 同一批改写下，各路径的方块序列（24×4096 项）逐项相同，且双向交叉解码一致\n");
    report.append("- 本基准自身耗时 ").append(elapsedNanos / 1_000_000L).append(" ms；运行环境 ")
        .append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.version"))
        .append(" / ").append(System.getProperty("os.name")).append("\n\n");

    if (peUnavailableReason != null) {
      report.append("> **PacketEvents 模型路径已跳过**（未计入下表）：").append(peUnavailableReason).append("\n\n");
    } else {
      report.append("> PacketEvents 模型路径为「PE 模型近似」：读/写用 PE 真实 API（Chunk_v1_18.read/write + ")
          .append("NetStreamInput/NetStreamOutput），方块改写用 DataPalette#set 并自行维护 blockCount——")
          .append("PE 原生的 Chunk_v1_18#set(...) 依赖 WrappedBlockState 全局映射（需平台初始化），离线不可用。")
          .append("该近似去掉了 PE 原生路径的两次方块状态映射查询，对 PE 有利（对结论更保守）。\n\n");
    }

    if (!allocationsSupported) {
      report.append("> 当前 JVM 不支持线程分配量统计，分配列以 0 占位。\n\n");
    }

    report.append("## 1. 逐行测量\n\n");
    report.append("| 形态 | 修改数 | 路径 | 中位耗时(ms) | 最小/最大 | 分配(MB) | 输出字节 | 压缩字节 | 零字节占比 | 重排 section |\n");
    report.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
    for (Row row : rows) {
      report.append("| ").append(row.shape())
          .append(" | ").append(row.edits())
          .append(" | ").append(row.path())
          .append(" | ").append(millis(row.medianNanos()))
          .append(" | ").append(millis(row.minNanos())).append(" / ").append(millis(row.maxNanos()))
          .append(" | ").append(String.format(Locale.ROOT, "%.2f", row.allocatedBytes() / 1048576.0))
          .append(" | ").append(row.outputBytes())
          .append(" | ").append(row.compressedBytes())
          .append(" | ").append(String.format(Locale.ROOT, "%.1f%%", row.zeroRatio() * 100.0D))
          .append(" | ").append(row.reorderedSections())
          .append(" |\n");
    }

    appendReorderSection(report, rows);
    appendAllocationSection(report, rows);

    report.append("\n> 覆盖：").append(fixtureCount).append(" 种形态 × ").append(EDIT_COUNTS.length)
        .append(" 个改写档位（10 / 100 / 1000）× ").append(rows.size() / (fixtureCount * EDIT_COUNTS.length))
        .append(" 条路径。\n");
    report.append("> 本表只陈述测量结果，不构成「谁更快」的结论；耗时列受 CI 机器抖动影响，分配量与输出/压缩字节是确定性的。\n");

    return report.toString();
  }

  /** 调色板重排的带宽收益：输出字节与压缩字节的「关闭重排 → 开启重排」对照。 */
  private static void appendReorderSection(StringBuilder report, List<Row> rows) {
    report.append("\n## 2. 调色板频次重排的带宽收益\n\n");
    report.append("> 重排只重排调色板顺序与位打包索引，**不改位宽、不改数组长度、不改调色板条目数**，")
        .append("因此「输出字节」在两种设置下必然相同；它的收益只体现在压缩后长度上（索引越小、位打包数据里的 0 位越多）。\n\n");
    report.append("| 形态 | 修改数 | 实际重排 section | 输出字节 关→开 | 压缩字节 关→开 | 压缩收益 | 零字节占比 关→开 |\n");
    report.append("| --- | --- | --- | --- | --- | --- | --- |\n");

    for (Row off : rows) {
      if (!off.path().equals("自研 codec（重排关）")) {
        continue;
      }
      Row on = findRow(rows, off.shape(), off.edits(), "自研 codec（重排开）");
      if (on == null) {
        continue;
      }
      String gain = off.compressedBytes() == 0 ? "—"
          : String.format(Locale.ROOT, "%+.1f%%",
              100.0D * (on.compressedBytes() - off.compressedBytes()) / off.compressedBytes());
      report.append("| ").append(off.shape())
          .append(" | ").append(off.edits())
          .append(" | ").append(on.reorderedSections())
          .append(" | ").append(off.outputBytes()).append(" → ").append(on.outputBytes())
          .append(" | ").append(off.compressedBytes()).append(" → ").append(on.compressedBytes())
          .append(" | ").append(gain)
          .append(" | ").append(String.format(Locale.ROOT, "%.1f%% → %.1f%%", off.zeroRatio() * 100.0D,
              on.zeroRatio() * 100.0D))
          .append(" |\n");
    }

    report.append("\n> 「实际重排 section = 0」表示该形态/档位下调色板本来就按频次降序排列（索引按首次出现分配，")
        .append("而出现最多的状态恰好落在低位索引），重排是无改动，收益为零——这是如实结果，不做粉饰。\n");
  }

  /** 分配量优化前后对照：以任务给定的 CI 基线为「优化前」，本次实测为「优化后」。 */
  private static void appendAllocationSection(StringBuilder report, List<Row> rows) {
    report.append("\n## 3. 每区块分配量：优化前（任务给定基线）vs 本次实测\n\n");
    report.append("| 形态 | 修改数 | 优化前分配(MB) | 本次·重排关(MB) | 本次·重排开(MB) | 降幅(相对优化前) |\n");
    report.append("| --- | --- | --- | --- | --- | --- |\n");

    for (Object[] baseline : ALLOCATION_BASELINE) {
      String shape = (String) baseline[0];
      int edits = (Integer) baseline[1];
      double before = (Double) baseline[2];

      Row off = findRow(rows, shape, edits, "自研 codec（重排关）");
      Row on = findRow(rows, shape, edits, "自研 codec（重排开）");
      double offMb = off == null ? Double.NaN : off.allocatedBytes() / 1048576.0D;
      double onMb = on == null ? Double.NaN : on.allocatedBytes() / 1048576.0D;

      report.append("| ").append(shape)
          .append(" | ").append(edits)
          .append(" | ").append(String.format(Locale.ROOT, "%.2f", before))
          .append(" | ").append(formatMb(offMb))
          .append(" | ").append(formatMb(onMb))
          .append(" | ").append(Double.isNaN(offMb) ? "—"
              : String.format(Locale.ROOT, "%.0f%%", 100.0D * (before - offMb) / before))
          .append(" |\n");
    }
  }

  private static String formatMb(double value) {
    return Double.isNaN(value) ? "—" : String.format(Locale.ROOT, "%.2f", value);
  }

  private static Row findRow(List<Row> rows, String shape, int edits, String path) {
    for (Row row : rows) {
      if (row.shape().equals(shape) && row.edits() == edits && row.path().equals(path)) {
        return row;
      }
    }
    return null;
  }

  private static String millis(long nanos) {
    return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
  }
}