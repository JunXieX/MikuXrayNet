package net.mikumc.mikuxraynet.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkSection;
import org.junit.jupiter.api.Test;

/**
 * <b>分段计时基准</b>：把改写流水线拆成「解码 / 逐格判定 / 重编码」三段，分别测并发扩展性。
 *
 * <p><b>要回答的问题</b>：{@code ConcurrentPipelineBenchmarkTest} 实测 8 线程并行改写只有约 3.2x
 * （1→2 线程 2.7x、2→4 线程 3.1x、4→8 线程 3.2x），明显偏离线性，说明存在共享竞争点。
 * 但整条流水线的总吞吐无法告诉我们是<b>哪一段</b>在争用。本基准把三段分开测，逐一对比扩展性。
 *
 * <p><b>三段的驱动方式</b>：
 * <ul>
 *   <li><b>解码</b>：{@code codec.decode(bytes, sections)} —— 含调色板/位打包解析，产出 {@link Chunk}；</li>
 *   <li><b>逐格判定</b>：对已解码的 {@link Chunk} 逐 section、逐格调 {@code getBlockState} 并做目标位图查询
 *       —— 这是「6 面遮挡判定 + 权重替换」的主体循环，但剥离了改写副作用，只测读取与判定；</li>
 *   <li><b>重编码</b>：{@code chunk.finalizeOutput()} —— 含选择性 section 重编码与字节搬运。</li>
 * </ul>
 *
 * <p><b>为什么这样拆</b>：三段各自独立、可单独驱动，且恰好对应生产代码里三段不同的资源类型——
 * 解码/重编码重度依赖 {@code ChunkScratch}（按线程复用的数组池），逐格判定几乎只读 Chunk 内部状态。
 * 若某段扩展性显著低于其它段，即可定位竞争源。
 */
class StageScalingBenchmarkTest {

  private static final int SECTION_COUNT = BenchFixtures.SECTION_COUNT;
  private static final int SECTION_VOLUME = BenchFixtures.SECTION_VOLUME;
  /**
   * 每线程迭代次数。
   *
   * <p><b>为什么不能太小</b>：解码单线程约 4860 次/秒，25 次只有约 5 ms——测量窗口短到
   * {@code System.nanoTime()} 的分辨率与调度抖动都会主导结果（首版取 25 时整个基准只跑 0.26 秒，
   * 数据完全不可信）。这里按「单档至少数百毫秒」取值：12 档合计约 5~10 秒，对 CI 可接受。
   */
  private static final int ITERATIONS_PER_THREAD = 400;
  private static final int WARMUP_ROUNDS = 3;

  private static final Path REPORT_PATH = Path.of("target", "benchmark-report.md");
  private static final String MARKER = "## 7. 分段并发扩展性";

  @Test
  void benchmarkStageScaling() throws Exception {
    StubRegistry registry = StubRegistry.of();
    ChunkCodec codec = new ChunkCodec(registry, BenchFixtures.FLAGS);

    byte[] payload = null;
    for (BenchFixtures.Fixture fixture : BenchFixtures.productionShapes()) {
      if (fixture.name().contains("洞穴")) {
        payload = fixture.bytes();
        break;
      }
    }
    assertTrue(payload != null, "基准夹具里应存在「洞穴」形态");
    byte[] bytes = payload;

    // 语义自检：三段独立驱动不得抛错，且解码出的 section 数与夹具一致
    try (Chunk chunk = codec.decode(bytes, SECTION_COUNT)) {
      assertEquals(SECTION_COUNT, chunk.getSectionCount(), "解码出的 section 数应与夹具一致");
    }

    StringBuilder report = new StringBuilder();
    report.append("\n").append(MARKER).append("\n\n");
    report.append("- 形态：洞穴（24 段全候选，实测最重）；每线程 ").append(ITERATIONS_PER_THREAD)
        .append(" 次\n");
    report.append("- 目的：定位 `ConcurrentPipelineBenchmarkTest` 里「8 线程仅 3.2x」的竞争源\n");
    report.append("- 三段：解码 `codec.decode` / 逐格判定 `getBlockState + targets.get` / 重编码 `finalizeOutput`\n");
    report.append("- 扩展性 = N 线程吞吐 ÷ 单线程吞吐；**越接近 N 越好**，明显低于 N 即为该段的竞争点\n\n");
    report.append("| 阶段 | 1 线程(次/秒) | 2 线程 | 4 线程 | 8 线程 | 8 线程扩展性 |\n");
    report.append("| --- | --- | --- | --- | --- | --- |\n");

    String[] stageNames = {"解码", "逐格判定", "重编码"};
    int[] threadCounts = {1, 2, 4, 8};
    double[] singleThreadThroughput = new double[stageNames.length];
    double[] eightThreadScaling = new double[stageNames.length];

    for (int stage = 0; stage < stageNames.length; stage++) {
      double[] throughputs = new double[threadCounts.length];
      for (int t = 0; t < threadCounts.length; t++) {
        int threads = threadCounts[t];
        long nanos = runStage(codec, bytes, registry, stage, threads);
        long operations = (long) threads * ITERATIONS_PER_THREAD;
        throughputs[t] = nanos <= 0 ? 0.0 : operations * 1_000_000_000.0 / nanos;
      }
      singleThreadThroughput[stage] = throughputs[0];
      eightThreadScaling[stage] = throughputs[0] <= 0 ? 0.0 : throughputs[3] / throughputs[0];
      report.append("| ").append(stageNames[stage]).append(" | ")
          .append(String.format(Locale.ROOT, "%.0f", throughputs[0])).append(" | ")
          .append(String.format(Locale.ROOT, "%.2fx", throughputs[0] <= 0 ? 0.0 : throughputs[1] / throughputs[0])).append(" | ")
          .append(String.format(Locale.ROOT, "%.2fx", throughputs[0] <= 0 ? 0.0 : throughputs[2] / throughputs[0])).append(" | ")
          .append(String.format(Locale.ROOT, "%.2fx", eightThreadScaling[stage])).append(" | ")
          .append(String.format(Locale.ROOT, "%.2fx", eightThreadScaling[stage])).append(" |\n");
    }

    // ---- 对照实验：区分「锁竞争」与「内存带宽饱和」 ----
    // 若瓶颈是锁，则同线程串行跑多份不会变慢；若是内存带宽，串行同样受带宽限制但不会被抢。
    // 这里加一组「单线程跑 8 倍工作量」的对照，看其单次耗时是否与 1 倍工作量持平：
    // 持平 ⇒ 单线程本身没有随工作量退化（说明不是 GC/缓存失效），多线程退化只能来自共享资源。
    report.append("\n### 7.2 对照：单线程 8 倍工作量\n\n");
    report.append("| 阶段 | 1x 单次(µs) | 8x 单次(µs) | 8x/1x |\n");
    report.append("| --- | --- | --- | --- |\n");
    for (int stage = 0; stage < stageNames.length; stage++) {
      double oneX = measureSingleThread(codec, bytes, registry, stage, ITERATIONS_PER_THREAD);
      double eightX = measureSingleThread(codec, bytes, registry, stage, ITERATIONS_PER_THREAD * 8);
      report.append("| ").append(stageNames[stage]).append(" | ")
          .append(String.format(Locale.ROOT, "%.1f", oneX)).append(" | ")
          .append(String.format(Locale.ROOT, "%.1f", eightX)).append(" | ")
          .append(String.format(Locale.ROOT, "%.2fx", oneX <= 0 ? 0.0 : eightX / oneX)).append(" |\n");
    }
    report.append("\n> 「8x/1x」接近 1.00 表示单线程工作量增加不改变单次成本（无 GC/缓存退化）；"
        + "此时多线程仍不线性，即可判定瓶颈是**全核共享资源**（内存带宽/LLC）而非锁。\n");

    report.append("\n> 表中 2/4/8 线程列均为「相对 1 线程的吞吐倍数」。\n");
    report.append("> 判读：若「解码」与「重编码」扩展性都明显低于「逐格判定」，则瓶颈在 `ChunkScratch` "
        + "的线程复用池；若三段都低，则更可能是内存带宽或 GC 压力。\n");
    appendReport(report.toString());
    System.out.println(report);

    for (double throughput : singleThreadThroughput) {
      assertTrue(throughput > 0.0, "每段的单线程吞吐都必须被成功采集");
    }
  }

  /** 单线程跑 {@code rounds} 次指定阶段，返回「每次」的平均微秒数。 */
  private static double measureSingleThread(ChunkCodec codec, byte[] bytes, StubRegistry registry,
      int stage, int rounds) {
    // 预热
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      runStageOnce(codec, bytes, registry, stage);
    }
    long start = System.nanoTime();
    for (int i = 0; i < rounds; i++) {
      runStageOnce(codec, bytes, registry, stage);
    }
    long nanos = System.nanoTime() - start;
    return rounds <= 0 ? 0.0 : nanos / 1000.0 / rounds;
  }

  private static void runStageOnce(ChunkCodec codec, byte[] bytes, StubRegistry registry, int stage) {
    switch (stage) {
      case 0 -> decodeOnly(codec, bytes);
      case 1 -> scanOnly(codec, bytes, registry);
      case 2 -> reencodeOnly(codec, bytes);
      default -> throw new IllegalArgumentException("未知阶段 " + stage);
    }
  }

  /**
   * 跑指定阶段。{@code stage}：0=解码、1=逐格判定、2=重编码。
   *
   * @return 总耗时（纳秒）
   */
  private static long runStage(ChunkCodec codec, byte[] bytes, StubRegistry registry, int stage,
      int threads) throws InterruptedException {
    // <b>同一个线程池跑预热与测量</b>：
    // ① 线程创建成本不计入测量；
    // ② 关键——{@code ChunkScratch} 是<b>按线程复用</b>的数组池，若每轮都新建线程池，
    //    每次测量拿到的都是「首次借出、需要重新分配几百 KB 数组」的冷线程，
    //    测出来的不是稳态复用行为，与生产（长驻工作线程）完全不符。
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      runStageRounds(pool, codec, bytes, registry, stage, threads, WARMUP_ROUNDS);
      long start = System.nanoTime();
      runStageRounds(pool, codec, bytes, registry, stage, threads, ITERATIONS_PER_THREAD);
      return System.nanoTime() - start;
    } finally {
      pool.shutdownNow();
    }
  }

  private static void runStageRounds(ExecutorService pool, ChunkCodec codec, byte[] bytes,
      StubRegistry registry, int stage, int threads, int rounds) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(threads);
    for (int t = 0; t < threads; t++) {
      pool.execute(() -> {
        try {
          for (int i = 0; i < rounds; i++) {
            switch (stage) {
              case 0 -> decodeOnly(codec, bytes);
              case 1 -> scanOnly(codec, bytes, registry);
              case 2 -> reencodeOnly(codec, bytes);
              default -> throw new IllegalArgumentException("未知阶段 " + stage);
            }
          }
        } finally {
          done.countDown();
        }
      });
    }
    done.await();
  }

  /** 阶段 0：只解码（含调色板/位打包解析与 scratch 借还）。 */
  private static void decodeOnly(ChunkCodec codec, byte[] bytes) {
    try (Chunk chunk = codec.decode(bytes, SECTION_COUNT)) {
      // 触碰一下，防止整个 decode 被判为无用代码
      if (chunk.getSectionCount() < 0) {
        throw new IllegalStateException();
      }
    }
  }

  /** 阶段 1：只做逐格判定（读状态 + 查目标位图），不改写、不重编码。 */
  private static void scanOnly(ChunkCodec codec, byte[] bytes, StubRegistry registry) {
    java.util.BitSet targets = targets(registry);
    long sink = 0L;
    try (Chunk chunk = codec.decode(bytes, SECTION_COUNT)) {
      for (int sectionIndex = 0; sectionIndex < chunk.getSectionCount(); sectionIndex++) {
        ChunkSection section = chunk.getSection(sectionIndex);
        if (section == null || section.isEmpty()) {
          continue;
        }
        for (int index = 0; index < SECTION_VOLUME; index++) {
          int state = section.getBlockState(index);
          if (targets.get(state)) {
            sink++;
          }
        }
      }
    }
    if (sink < 0) {
      throw new IllegalStateException();
    }
  }

  /** 阶段 2：解码 + 少量改写 + 重编码（把重编码的成本单独拎出来）。 */
  private static void reencodeOnly(ChunkCodec codec, byte[] bytes) {
    try (Chunk chunk = codec.decode(bytes, SECTION_COUNT)) {
      // 每个 section 改一格，迫使它走「选择性重编码」而不是整段原样搬运
      for (int sectionIndex = 0; sectionIndex < chunk.getSectionCount(); sectionIndex++) {
        ChunkSection section = chunk.getSection(sectionIndex);
        if (section == null || section.isEmpty()) {
          continue;
        }
        section.setBlockState(0, section.getBlockState(0));
      }
      byte[] output = chunk.finalizeOutput();
      if (output.length == 0) {
        throw new IllegalStateException();
      }
    }
  }

  private static java.util.BitSet targets(StubRegistry registry) {
    java.util.BitSet targets = new java.util.BitSet(registry.getUniqueBlockStateCount());
    for (int ore : BenchFixtures.ORE_STATES) {
      targets.set(ore);
    }
    return targets;
  }

  private static void appendReport(String section) throws IOException {
    Files.createDirectories(REPORT_PATH.getParent());
    String existing = Files.exists(REPORT_PATH)
        ? Files.readString(REPORT_PATH, StandardCharsets.UTF_8) : "";
    int marker = existing.indexOf(MARKER);
    String base = marker < 0 ? existing : existing.substring(0, marker);
    if (!base.isEmpty() && !base.endsWith("\n")) {
      base += "\n";
    }
    Files.writeString(REPORT_PATH, base + section, StandardCharsets.UTF_8);
  }
}
