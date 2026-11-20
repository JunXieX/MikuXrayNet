package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 转储里 Spark 段的拼装：段序、换行规整与三种「没有链接」的自解释文案。
 *
 * <p><b>为什么这些必须锁住</b>：Spark 段来自插件层的异步回调，前后换行由实现方决定；dump 其余各段都以
 * {@code "\n---- 标题 ----\n"} 起头。一旦两边各写一半换行，就会出现「标题贴着上一段」或段间空两行；
 * 而段序错了（Spark 段跑到运行环境之后），读的人得在几百行里来回找同一段时间的三个读数。
 *
 * <p>另外三条「没有链接」的文案必须各自点名原因：dump 的价值在于拿到文件的人能自己判断，留一段空壳
 * 等于让他回头问人。
 */
class DiagnosticsSparkDumpTest {

  private static final String SPARK_SECTION = """
      ---- Spark 采样 ----
      窗口：30 秒（--thread * --interval 10）
      状态：已上传
      完整链接：https://spark.lucko.me/AbCdEf1234
      """;

  private static final String RUNTIME_SECTION = """
      ---- 运行环境 ----
      在线人数：12
      """;

  // ------------------------------------------------------------ 换行规整

  /** 空段一律渲染成空串：没有 Spark 段时不得留下两行空白。 */
  @Test
  void blankSectionsRenderAsNothing() {
    assertEquals("", Diagnostics.normalizeSparkSection(null));
    assertEquals("", Diagnostics.normalizeSparkSection(""));
    assertEquals("", Diagnostics.normalizeSparkSection("   \n  "));
  }

  /** 恰好一个前导换行 + 一个尾随换行，与段来自哪一方无关。 */
  @Test
  void sectionIsNormalizedToExactlyOneSurroundingNewline() {
    String withNewlines = Diagnostics.normalizeSparkSection("\n\n---- Spark 采样 ----\n状态：已上传\n\n");
    assertEquals("\n---- Spark 采样 ----\n状态：已上传\n", withNewlines);

    String withoutNewlines = Diagnostics.normalizeSparkSection("---- Spark 采样 ----\n状态：已上传");
    assertEquals("\n---- Spark 采样 ----\n状态：已上传\n", withoutNewlines,
        "调用方不自带换行时，拼接结果必须与自带时逐字一致");
  }

  /** 规整必须幂等：重复拼接（例如重试路径）不应叠加空行。 */
  @Test
  void normalizationIsIdempotent() {
    String once = Diagnostics.normalizeSparkSection(SPARK_SECTION);
    assertEquals(once, Diagnostics.normalizeSparkSection(once));
  }

  // ---------------------------------------------------------------- 段序

  /** Spark 段在运行环境之前、采样段之后：同一段时间的三个读数必须挨着读。 */
  @Test
  void sparkSectionSitsBetweenSamplesAndRuntime() {
    String samples = "\n---- 运行采样 ----\n窗口：30.0 秒\n";
    String tail = Diagnostics.trailingSections(SPARK_SECTION, RUNTIME_SECTION);
    String dump = samples + tail;

    int samplesAt = dump.indexOf("---- 运行采样 ----");
    int sparkAt = dump.indexOf("---- Spark 采样 ----");
    int runtimeAt = dump.indexOf("---- 运行环境 ----");
    assertTrue(samplesAt >= 0 && sparkAt > samplesAt && runtimeAt > sparkAt,
        "段序必须是 运行采样 → Spark 采样 → 运行环境，实际：" + dump);
  }

  /** 没有 Spark 段时段序退化为「运行采样 → 运行环境」，不得插入空段。 */
  @Test
  void missingSparkSectionLeavesNoGap() {
    String tail = Diagnostics.trailingSections(null, RUNTIME_SECTION);
    assertEquals("\n---- 运行环境 ----\n在线人数：12\n", tail);
  }

  /** 链接只出现一次：拼接（而不是追加）是「迟到结果不再写第二遍」的前提。 */
  @Test
  void linkAppearsExactlyOnce() {
    String dump = Diagnostics.trailingSections(SPARK_SECTION, RUNTIME_SECTION);
    int first = dump.indexOf("https://spark.lucko.me/AbCdEf1234");
    assertTrue(first >= 0, dump);
    assertEquals(-1, dump.indexOf("https://spark.lucko.me/AbCdEf1234", first + 1),
        "同一个链接不得出现两次（重复拼接会让「哪一份才是这次 dump」失去答案）");
  }

  // ------------------------------------------------------------ 无链接文案

  /** dump local / 未安装 Spark：写明「未请求」并点名原因，且不出现任何链接。 */
  @Test
  void notRequestedSectionNamesTheReason() {
    String local = Diagnostics.sparkSectionNotRequested("dump local 只做本地采样，不上传任何内容");
    assertTrue(local.contains("---- Spark 采样 ----"), local);
    assertTrue(local.contains("未请求"), local);
    assertTrue(local.contains("dump local"), local);
    assertFalse(local.contains("http"), local);

    String fallback = Diagnostics.sparkSectionNotRequested(null);
    assertTrue(fallback.contains("未请求") && fallback.contains("Spark"), fallback);
  }

  /** 超时：点名等待秒数与「没有链接」，同时说明本地采样段仍然完整。 */
  @Test
  void timeoutSectionIsSelfExplaining() {
    String timeout = Diagnostics.sparkSectionTimeout(65L);
    assertTrue(timeout.contains("超时"), timeout);
    assertTrue(timeout.contains("65 秒"), timeout);
    assertTrue(timeout.contains("不含 Spark 链接"), timeout);
    assertFalse(timeout.contains("http"), timeout);
  }

  /** 失败：带原因且不为空；原因缺失时也给明确文案（绝不出现「失败：null」）。 */
  @Test
  void failureSectionAlwaysCarriesReason() {
    String failed = Diagnostics.sparkSectionFailed("已有 Spark 采样在进行");
    assertTrue(failed.contains("失败"), failed);
    assertTrue(failed.contains("已有 Spark 采样在进行"), failed);
    assertFalse(failed.contains("null"), failed);

    String blank = Diagnostics.sparkSectionFailed("  ");
    assertTrue(blank.contains("原因未知"), blank);
  }

  /** 三种无链接文案都能被规整器接受（不会因为缺前导换行而贴住上一段）。 */
  @Test
  void fallbackSectionsAreNormalizable() {
    for (String section : List.of(Diagnostics.sparkSectionNotRequested("x"),
        Diagnostics.sparkSectionTimeout(65L), Diagnostics.sparkSectionFailed("y"))) {
      String normalized = Diagnostics.normalizeSparkSection(section);
      assertTrue(normalized.startsWith("\n---- Spark 采样 ----\n"), normalized);
      assertTrue(normalized.endsWith("\n"), normalized);
      assertEquals(normalized, "\n" + normalized.strip() + "\n");
    }
  }
}
