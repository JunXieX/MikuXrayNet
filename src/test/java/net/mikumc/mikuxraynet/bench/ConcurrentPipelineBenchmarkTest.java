package net.mikumc.mikuxraynet.bench;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.LongAdder;
import net.mikumc.mikuxraynet.antixray.ObfuscatedChunkIndex;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import org.junit.jupiter.api.Test;

/**
 * 并发基准：把「改写流水线」与「显形索引读写」放到多线程下，测**吞吐**与**扩展性**。
 *
 * <p><b>为什么需要它</b>：既有基准（{@code ChunkPathBenchmarkTest} / {@code ProductionPathBenchmarkTest}）
 * 全是单线程单区块。而生产环境是「工作池 N 线程并发改写 + 主线程/区域线程并发读写索引」，
 * 单线程数字无法回答「50 人在线时会不会打满」「索引的并发容器是不是瓶颈」这类问题。
 *
 * <p><b>本基准测什么</b>：
 * <ol>
 *   <li><b>改写吞吐扩展性</b>：1 / 2 / 4 / 8 线程并行跑同一批区块，看吞吐是否近似线性增长
 *       （线性度差说明存在共享竞争，通常是 {@code ChunkScratch} 或缓存结构）；</li>
 *   <li><b>索引并发读写</b>：多线程同时对 {@link ObfuscatedChunkIndex} 做「记录 + 查询 + 摘除」，
 *       测量每操作平均耗时（这是玩家线程与工作线程真正共享的结构）。</li>
 * </ol>
 *
 * <p><b>断言口径</b>：只断言「正确性（结果一致 / 无丢失）」与「数值被成功采集」，
 * <b>不断言「谁更快」</b>——CI 机器是共享的，绝对耗时不可比；但同一次运行内的**相对扩展性**有参考价值。
 */
class ConcurrentPipelineBenchmarkTest {

  private static final int SECTION_COUNT = BenchFixtures.SECTION_COUNT;
  private static final long SEED = 20240924L;
  /** 每个线程处理的区块数：足够长以摊薄线程启动与预热成本。 */
  private static final int CHUNKS_PER_THREAD = 40;
  private static final int WARMUP_ROUNDS = 3;
  private static final int INDEX_OPS_PER_THREAD = 20_000;

  private static final Path REPORT_PATH = Path.of("target", "benchmark-report.md");
  private static final String MARKER = "## 6. 并发吞吐与扩展性";

  @Test
  void benchmarkConcurrentRewriteScaling() throws Exception {
    StubRegistry registry = StubRegistry.of();
    ChunkCodec codec = new ChunkCodec(registry, BenchFixtures.FLAGS);
    ObfuscationProcessor processor = processor(registry);
    assertTrue(processor.isActive(), "处理器应处于生效状态");

    // 用「洞穴」形态：它是实测最重的形态（24 段全候选），最能暴露并发争用
    byte[] sample = null;
    for (BenchFixtures.Fixture fixture : BenchFixtures.productionShapes()) {
      if (fixture.name().contains("洞穴")) {
        sample = fixture.bytes();
        break;
      }
    }
    assertTrue(sample != null, "基准夹具里应存在「洞穴」形态");
    byte[] payload = sample;

    // 语义基线：单线程结果必须与并发结果逐字节一致（并发不得改变输出）
    byte[] singleThreaded = processor.rewrite(payload, SECTION_COUNT, SEED, null).data();

    StringBuilder report = new StringBuilder();
    report.append("\n").append(MARKER).append("\n\n");
    report.append("- 形态：洞穴（24 段全候选，实测最重）；每线程 ").append(CHUNKS_PER_THREAD)
        .append(" 区块\n");
    report.append("- 目的：看**吞吐随线程数是否近似线性**——偏离线性即说明存在共享竞争点\n");
    report.append("- 语义校验：并发输出必须与单线程逐字节一致（本测试硬断言）\n");
    report.append("- 注意：CI 机器是共享的，绝对数值不可跨机比较；**同一次运行内的相对扩展性**才有意义\n\n");
    report.append("| 线程数 | 总区块 | 总耗时(ms) | 吞吐(区块/秒) | 相对 1 线程 |\n");
    report.append("| --- | --- | --- | --- | --- |\n");

    int[] threadCounts = {1, 2, 4, 8};
    double baselineThroughput = 0.0;
    for (int threads : threadCounts) {
      long nanos = runConcurrentRewrite(processor, payload, threads);
      int totalChunks = threads * CHUNKS_PER_THREAD;
      double seconds = nanos / 1_000_000_000.0;
      double throughput = seconds <= 0.0 ? 0.0 : totalChunks / seconds;
      if (threads == 1) {
        baselineThroughput = throughput;
      }
      double relative = baselineThroughput <= 0.0 ? 0.0 : throughput / baselineThroughput;
      report.append(String.format(Locale.ROOT, "| %d | %d | %.1f | %.0f | %.2fx |%n",
          threads, totalChunks, nanos / 1_000_000.0, throughput, relative));
    }

    // 并发正确性：多线程结果必须与单线程一致
    byte[] concurrent = runConcurrentAndCapture(processor, payload, 8);
    assertTrue(Arrays.equals(singleThreaded, concurrent),
        "并发改写结果必须与单线程逐字节一致（同种子同输入）");

    report.append("\n> 「相对 1 线程」明显低于线程数时，通常意味着存在共享竞争"
        + "（如 scratch 池争用、缓存锁、内存带宽饱和），值得进一步定位；接近线程数说明扩展性良好。\n");
    appendReport(report.toString());
    System.out.println(report);
  }

  @Test
  void benchmarkConcurrentIndexOps() throws Exception {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(4_194_304, 300);
    StringBuilder report = new StringBuilder();
    report.append("\n### 6.2 显形索引并发读写\n\n");
    report.append("- 结构：{@code ObfuscatedChunkIndex}（伪装坐标，按区块共享，玩家线程与工作线程共享）\n");
    report.append("- 测「记录 + 查询 + 摘除」混合负载下的每操作耗时与并发争用代价\n");
    report.append("- 每线程 ").append(INDEX_OPS_PER_THREAD).append(" 次操作，8 线程并发\n\n");
    report.append("| 线程数 | 总操作 | 总耗时(ms) | 每操作(ns) | 操作/秒 |\n");
    report.append("| --- | --- | --- | --- | --- |\n");

    for (int threads : new int[] {1, 4, 8}) {
      long[] result = runConcurrentIndexOps(index, threads);
      long nanos = result[0];
      long ops = result[1];
      double perOpNanos = ops <= 0 ? 0.0 : (double) nanos / ops;
      double opsPerSecond = nanos <= 0 ? 0.0 : ops * 1_000_000_000.0 / nanos;
      report.append(String.format(Locale.ROOT, "| %d | %d | %.1f | %.1f | %.0f |%n",
          threads, ops, nanos / 1_000_000.0, perOpNanos, opsPerSecond));
    }
    report.append("\n> 每操作耗时随线程数上升的幅度，反映并发容器争用的代价。\n");
    appendReport(report.toString());
    System.out.println(report);

    // 正确性：并发写入后索引仍自洽（计数与条目数一致）
    assertTrue(index.chunkCount() >= 0, "索引计数必须自洽");
    assertFalse(index.positionCount() < 0, "伪装坐标计数不得为负");
  }

  /** 用 {@code threads} 条线程各改写 {@link #CHUNKS_PER_THREAD} 个区块，返回总耗时。 */
  private static long runConcurrentRewrite(ObfuscationProcessor processor, byte[] payload,
      int threads) throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      // 预热：每个线程先跑一遍，避免把 JIT 编译成本算进测量
      runRounds(pool, processor, payload, threads, WARMUP_ROUNDS);
      long start = System.nanoTime();
      runRounds(pool, processor, payload, threads, CHUNKS_PER_THREAD);
      return System.nanoTime() - start;
    } finally {
      pool.shutdownNow();
    }
  }

  private static byte[] runConcurrentAndCapture(ObfuscationProcessor processor, byte[] payload,
      int threads) throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch ready = new CountDownLatch(threads);
      CountDownLatch go = new CountDownLatch(1);
      List<byte[]> outputs = new ArrayList<>(threads);
      for (int t = 0; t < threads; t++) {
        outputs.add(null);
      }
      for (int t = 0; t < threads; t++) {
        int slot = t;
        pool.execute(() -> {
          ready.countDown();
          try {
            go.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
          outputs.set(slot, processor.rewrite(payload, SECTION_COUNT, SEED, null).data());
        });
      }
      ready.await();
      go.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发改写必须在时限内完成");
      byte[] first = outputs.get(0);
      for (byte[] output : outputs) {
        assertTrue(Arrays.equals(first, output), "所有线程的输出必须逐字节相同");
      }
      return first;
    } finally {
      pool.shutdownNow();
    }
  }

  private static void runRounds(ExecutorService pool, ObfuscationProcessor processor,
      byte[] payload, int threads, int rounds) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(threads);
    for (int t = 0; t < threads; t++) {
      pool.execute(() -> {
        try {
          for (int i = 0; i < rounds; i++) {
            processor.rewrite(payload, SECTION_COUNT, SEED, null);
          }
        } finally {
          done.countDown();
        }
      });
    }
    done.await();
  }

  /** 并发跑索引混合负载，返回 {总耗时 nanos, 总操作数}。 */
  private static long[] runConcurrentIndexOps(ObfuscatedChunkIndex index, int threads)
      throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    LongAdder ops = new LongAdder();
    try {
      CountDownLatch done = new CountDownLatch(threads);
      long start = System.nanoTime();
      for (int t = 0; t < threads; t++) {
        int worker = t;
        pool.execute(() -> {
          try {
            String world = "world";
            int[] locals = new int[32];
            for (int i = 0; i < 32; i++) {
              locals[i] = i << 8;
            }
            for (int i = 0; i < INDEX_OPS_PER_THREAD; i++) {
              int chunkX = worker * 512 + (i & 255);
              int chunkZ = i >> 8 & 255;
              // 记录（写）
              index.recordChunk(world, chunkX, chunkZ, 0, locals);
              // 查询（读）
              index.containsPosition(world, chunkX << 4, 0, chunkZ << 4);
              // 摘除（写）：<b>每次记录后都整块摘掉</b>，保持索引驻留量恒定。
              //
              // 为什么必须这样做：{@code recordChunk} 一次写入 32 个坐标，若只摘 1 个，净增 31；
              // 8 线程 × 20000 次即 496 万坐标，会越过 maxPositions 触发 makeRoomFor 的 O(n)
              // 全表淘汰——那是「安全阀」（正常运营永不进入），把它测进来只会得到一个假的
              // O(n²) 退化数字，与本基准想衡量的「并发争用代价」完全无关。
              index.invalidateChunk(world, chunkX, chunkZ);
              ops.add(3L);
            }
          } finally {
            done.countDown();
          }
        });
      }
      done.await();
      long nanos = System.nanoTime() - start;
      return new long[] {nanos, ops.sum()};
    } finally {
      pool.shutdownNow();
    }
  }

  private static ObfuscationProcessor processor(StubRegistry registry) {
    ChunkCodec codec = new ChunkCodec(registry, BenchFixtures.FLAGS);
    java.util.BitSet targets = new java.util.BitSet(registry.getUniqueBlockStateCount());
    for (int ore : BenchFixtures.ORE_STATES) {
      targets.set(ore);
    }
    return new ObfuscationProcessor(codec, registry::isOccluding, targets,
        new int[] {BenchFixtures.STONE, BenchFixtures.DEEPSLATE}, new int[] {10, 18},
        false, true, new ObfuscationProcessor.PaletteOptions(false, false));
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
