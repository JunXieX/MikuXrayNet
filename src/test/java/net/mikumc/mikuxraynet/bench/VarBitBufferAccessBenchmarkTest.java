package net.mikumc.mikuxraynet.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 位打包缓冲区「逐格读取」的除法开销基准：现有实现 vs 特化实现。
 *
 * <p><b>要回答的问题</b>：{@code SimpleVarBitBuffer#get} 里每格做一次 {@code index / entriesPerLong}，
 * 而 {@code entriesPerLong} 是实例字段（JIT 无法常量折叠）。反矿透改写对每个候选 section 都要逐格读
 * 4096 次（「洞穴」形态 24 段 = 98,304 次/区块），因此这笔除法是否值得特化成移位，需要用数据说话。
 *
 * <p><b>本基准不测「谁更快」的结论，只测两组实现在同一数据上的耗时</b>，并断言二者结果逐位相同
 * （等价性优先：任何优化都必须先证明不改变语义）。
 *
 * <p><b>为什么要跑满整个 section 而不是微基准单次调用</b>：单次 {@code get} 只有几纳秒，
 * JIT 会把测量本身优化掉（死代码消除 / 常量折叠），得到的数字没有意义。这里让每次迭代读完整
 * 4096 格并累加，累加结果参与断言，确保循环不可被消除。
 */
class VarBitBufferAccessBenchmarkTest {

  private static final int SECTION_VOLUME = 4096;
  private static final int WARMUP = 200;
  private static final int ROUNDS = 400;

  private static final Path REPORT_PATH = Path.of("target", "benchmark-report.md");
  private static final String MARKER = "## 5. 位打包逐格读取";

  @Test
  void benchmarkPerCellRead() throws IOException {
    StringBuilder report = new StringBuilder();
    report.append("\n").append(MARKER).append("：除法 vs 特化移位\n\n");
    report.append("- 目的：量化 `SimpleVarBitBuffer#get` 每格一次整数除法的实际开销，"
        + "判断「按位宽特化成移位」是否值得做\n");
    report.append("- 方法：同一份 4096 格数据，分别用「现有实现」与「特化实现」逐格读满整个 section，"
        + "累加结果参与断言（防 JIT 消除）；取中位耗时\n");
    report.append("- 等价性：两种实现对每个格子必须返回完全相同的值（本测试硬断言）\n");
    report.append("- 预热 ").append(WARMUP).append(" 轮、测量 ").append(ROUNDS).append(" 轮\n\n");

    report.append("| 每方块位宽 | 现有实现(µs/section) | 特化实现(µs/section) | 提速 | 每区块省(µs, 24 段) |\n");
    report.append("| --- | --- | --- | --- | --- |\n");

    ThreadMXBean allocation = allocationBean();
    // 位宽 4~8 是间接调色板的全部合法档位；8 位最坏（每 long 8 项，除法次数最多）
    int[] bitWidths = {4, 5, 6, 7, 8};
    for (int bits : bitWidths) {
      long[] packed = buildPacked(bits);
      int entriesPerLong = 64 / bits;
      int mask = (1 << bits) - 1;
      long[] shift = shiftFor(bits, entriesPerLong);

      long[] values = new long[SECTION_VOLUME];
      for (int i = 0; i < SECTION_VOLUME; i++) {
        values[i] = readGeneric(packed, i, entriesPerLong, bits, mask);
      }
      long[] specialised = new long[SECTION_VOLUME];
      for (int i = 0; i < SECTION_VOLUME; i++) {
        specialised[i] = readShifted(packed, i, shift, bits, mask);
      }
      for (int i = 0; i < SECTION_VOLUME; i++) {
        assertEquals(values[i], specialised[i],
            "位宽 " + bits + " 第 " + i + " 格：特化实现必须与现有实现逐格相同");
      }

      for (int i = 0; i < WARMUP; i++) {
        readAllGeneric(packed, entriesPerLong, bits, mask);
        readAllShifted(packed, shift, bits, mask);
      }
      long[] genericNanos = new long[ROUNDS];
      long[] shiftedNanos = new long[ROUNDS];
      long sink = 0L;
      for (int round = 0; round < ROUNDS; round++) {
        long start = System.nanoTime();
        sink += readAllGeneric(packed, entriesPerLong, bits, mask);
        genericNanos[round] = System.nanoTime() - start;
        start = System.nanoTime();
        sink += readAllShifted(packed, shift, bits, mask);
        shiftedNanos[round] = System.nanoTime() - start;
      }
      assertTrue(sink != Long.MIN_VALUE, "累加结果必须参与使用，防止 JIT 消除整段循环");
      Arrays.sort(genericNanos);
      Arrays.sort(shiftedNanos);
      double genericMicros = genericNanos[ROUNDS / 2] / 1000.0;
      double shiftedMicros = shiftedNanos[ROUNDS / 2] / 1000.0;
      double speedup = shiftedMicros <= 0.0 ? 0.0 : genericMicros / shiftedMicros;
      report.append(String.format(Locale.ROOT,
          "| %d | %.2f | %.2f | %.2fx | %.2f |%n",
          bits, genericMicros, shiftedMicros, speedup, (genericMicros - shiftedMicros) * 24.0));
    }

    report.append("\n> 「每区块省」按 24 段全为候选段的最坏情形外推（实际多数区块只有部分段含目标）。\n");
    report.append("> 若提速接近 1.00x，说明 JIT 已把除法优化掉（或在实测数据上不构成瓶颈），"
        + "则**不应**为此引入特化分支——多一套实现就多一份维护与出错面。\n");
    appendReport(report.toString());
    System.out.println(report);
  }

  /** 现有实现的读法：每格一次整数除法（与 {@code SimpleVarBitBuffer#get} 同形）。 */
  private static long readGeneric(long[] buffer, int index, int entriesPerLong, int bits, int mask) {
    int position = index / entriesPerLong;
    int offset = (index - position * entriesPerLong) * bits;
    return buffer[position] >>> offset & mask;
  }

  /** 特化实现的读法：把除法换成「乘法取高位 + 移位」，全程无除法。 */
  private static long readShifted(long[] buffer, int index, long[] shift, int bits, int mask) {
    // shift = { 乘法倒数, 右移位数, 位内偏移掩码 }
    int position = (int) ((index * shift[0]) >>> shift[1]);
    int offset = (index - position * (int) shift[2]) * bits;
    return buffer[position] >>> offset & mask;
  }

  private static long readAllGeneric(long[] buffer, int entriesPerLong, int bits, int mask) {
    long sum = 0L;
    for (int i = 0; i < SECTION_VOLUME; i++) {
      sum += readGeneric(buffer, i, entriesPerLong, bits, mask);
    }
    return sum;
  }

  private static long readAllShifted(long[] buffer, long[] shift, int bits, int mask) {
    long sum = 0L;
    for (int i = 0; i < SECTION_VOLUME; i++) {
      sum += readShifted(buffer, i, shift, bits, mask);
    }
    return sum;
  }

  /** 为 {@code entriesPerLong} 求「乘法倒数」参数（Hacker's Delight 的无符号除法替换）。 */
  private static long[] shiftFor(int bits, int entriesPerLong) {
    if (Integer.bitCount(entriesPerLong) == 1) {
      // 2 的幂：直接用移位，乘法倒数退化为 1 与移位
      int log2 = Integer.numberOfTrailingZeros(entriesPerLong);
      return new long[] {1L, 32 + log2, entriesPerLong};
    }
    // 非 2 的幂（位宽 5/6/7）：用「乘 2^32/d 再右移 32」的经典形式
    long magic = ((1L << 32) + entriesPerLong - 1) / entriesPerLong;
    return new long[] {magic, 32, entriesPerLong};
  }

  /** 构造一份位宽 {@code bits} 的位打包数据（确定性填充，值域落在位宽内）。 */
  private static long[] buildPacked(int bits) {
    int entriesPerLong = 64 / bits;
    int longCount = (SECTION_VOLUME + entriesPerLong - 1) / entriesPerLong;
    long[] buffer = new long[longCount];
    int mask = (1 << bits) - 1;
    for (int i = 0; i < SECTION_VOLUME; i++) {
      int value = (i * 31 + 7) & mask;
      int position = i / entriesPerLong;
      int offset = (i - position * entriesPerLong) * bits;
      buffer[position] |= (long) value << offset;
    }
    return buffer;
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    return bean instanceof ThreadMXBean allocationBean ? allocationBean : null;
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
