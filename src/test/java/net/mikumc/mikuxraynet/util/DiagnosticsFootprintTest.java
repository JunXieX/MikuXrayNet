package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 自身占用估算与磁盘水位提示的守门用例。
 *
 * <p>两者都服务于同一件事：实机 dump 里「堆 14.2 GB / 上限 24.6 GB」「磁盘 1334 MB 且无上限」
 * 这类数字如果不写清「算的是谁、什么水位算危险」，就会被误读成「本插件吃掉了 14 GB」
 * 或「磁盘不用管」，从而去调错对象。
 */
class DiagnosticsFootprintTest {

  @Test
  void footprintAddsUpAllFourParts() {
    long cache = 100L;
    long index = 10L;
    long bitmaps = 4L;
    long revealed = 3L;
    long expected = cache
        + index * Diagnostics.FOOTPRINT_BYTES_PER_INDEX_POSITION
        + bitmaps * Diagnostics.FOOTPRINT_BYTES_PER_BITMAP_SECTION
        + revealed * Diagnostics.FOOTPRINT_BYTES_PER_REVEALED_MARK;
    assertEquals(expected,
        Diagnostics.estimatePluginHeapBytes(cache, index, bitmaps, revealed));
  }

  @Test
  void footprintTreatsNegativeInputsAsZero() {
    assertEquals(0L, Diagnostics.estimatePluginHeapBytes(-1L, -1L, -1L, -1L),
        "负值只可能来自计数下溢，估算不能因此变小或变负");
  }

  @Test
  void footprintScalesLinearlyWithPositions() {
    long one = Diagnostics.estimatePluginHeapBytes(0L, 1L, 0L, 0L);
    long thousand = Diagnostics.estimatePluginHeapBytes(0L, 1000L, 0L, 0L);
    assertEquals(one * 1000L, thousand);
  }

  @Test
  void noHintsWhenEverythingIsHealthy() {
    List<String> hints = Diagnostics.diskWatermarkHints(8, 64, 50_000, 2_000);
    assertTrue(hints.isEmpty(), "健康态不该往面板里加噪声：" + hints);
  }

  /**
   * 生产实测水位 43/128（未饱和）：面板必须保持沉默。
   *
   * <p>旧实现在 {@code bucket-cache-size(32) < max-open-files(128)} 时就提示「取同值或更大」——
   * 前者是每个已打开文件缓存的桶 LRU 槽位数（安全上限 64），后者是常驻句柄上限，两者不同量纲：
   * 谁大谁小既不说明桶索引被重读，也不是内存占用的算法（常驻内存 ≈ 句柄数 × 每文件桶数）。
   * 照旧提示调大只会把每文件驻留内存放大数倍，故该分支已删除，这里锁住「不再出现」。
   */
  @Test
  void productionWatermarkStaysSilent() {
    List<String> hints = Diagnostics.diskWatermarkHints(43, 128, 50_000, 2_000);
    assertTrue(hints.isEmpty(), "43/128 未饱和，且桶 LRU 槽位数与句柄上限不可比：" + hints);
  }

  @Test
  void openFilesAtNinetyPercentIsReported() {
    List<String> hints = Diagnostics.diskWatermarkHints(58, 64, 50_000, 2_000);
    assertEquals(1, hints.size());
    assertTrue(hints.get(0).contains("58/64"), hints.get(0));
    assertTrue(hints.get(0).contains("重启生效"),
        "max-open-files 属启动期固化，提示必须写明改了要重启才生效：" + hints.get(0));
  }

  @Test
  void openFilesJustBelowNinetyPercentStaysSilent() {
    List<String> hints = Diagnostics.diskWatermarkHints(57, 64, 50_000, 2_000);
    assertTrue(hints.isEmpty(), "57/64 未到 90% 水位，不该出提示：" + hints);
  }

  @Test
  void unlimitedDiskIsReportedEvenWhenSizesAreSmall() {
    List<String> hints = Diagnostics.diskWatermarkHints(8, 64, 0, 0);
    assertEquals(1, hints.size());
    assertTrue(hints.get(0).contains("不限制"), hints.get(0));
  }

  @Test
  void watermarkAndUnlimitedAreReportedTogether() {
    List<String> hints = Diagnostics.diskWatermarkHints(60, 64, 0, 0);
    assertEquals(2, hints.size(), hints.toString());
    assertTrue(hints.get(0).contains("90% 水位"), hints.toString());
    assertTrue(hints.get(1).contains("不限制"), hints.toString());
  }
}
