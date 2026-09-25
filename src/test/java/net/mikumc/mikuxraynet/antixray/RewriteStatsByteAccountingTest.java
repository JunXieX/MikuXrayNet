package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P0-1「字节口径统计 + 位宽直方图」的计数器测试：原始/输出/节省三计数的累加口径、
 * 负节省（升位/扩容）如实记录、位宽直方图的越界钳制与中文摘要格式。
 */
class RewriteStatsByteAccountingTest {

  @Test
  void byteCountersAccumulateOriginalOutputAndSaved() {
    RewriteStats stats = new RewriteStats();

    // 两个改写区块：1000 → 900（省 100）、2000 → 1500（省 500）
    stats.bytesOriginal.add(1000L);
    stats.bytesOutput.add(900L);
    stats.bytesSaved.add(100L);
    stats.bytesOriginal.add(2000L);
    stats.bytesOutput.add(1500L);
    stats.bytesSaved.add(500L);

    Map<String, Long> snapshot = stats.snapshot();
    assertEquals(3000L, snapshot.get("原始字节"), "原始字节 = 各改写区块输入长度之和");
    assertEquals(2400L, snapshot.get("输出字节"), "输出字节 = 各改写区块输出长度之和");
    assertEquals(600L, snapshot.get("节省字节"), "节省字节 = 原始 − 输出（逐块累加）");
  }

  @Test
  void negativeSavingsAreRecordedAsIs() {
    // 逃生口 grow 的场景：输出比原始更大，节省为负，必须如实累计（不许钳成 0）
    RewriteStats stats = new RewriteStats();
    stats.bytesOriginal.add(1000L);
    stats.bytesOutput.add(1250L);
    stats.bytesSaved.add(-250L);

    Map<String, Long> snapshot = stats.snapshot();
    assertEquals(-250L, snapshot.get("节省字节"), "升位导致的负节省必须如实体现");
    assertEquals(-250L, stats.bytesOriginal.sum() - stats.bytesOutput.sum(),
        "节省恒等于原始 − 输出（口径自洽）");
  }

  @Test
  void paletteBitsHistogramClampsAndFormats() {
    RewriteStats stats = new RewriteStats();
    assertEquals("无采样", stats.paletteBitsSummary(), "无采样时给出可读文案");

    stats.recordPaletteBits(4);
    stats.recordPaletteBits(4);
    stats.recordPaletteBits(4);
    stats.recordPaletteBits(5);
    assertEquals("4位×3 ｜ 5位×1", stats.paletteBitsSummary(), "摘要只列有采样的位宽槽");
    assertEquals(3L, stats.paletteBitsAt(4));
    assertEquals(1L, stats.paletteBitsAt(5));
    assertEquals(0L, stats.paletteBitsAt(8));

    // 越界钳制：负值并入 0 位槽、超大值并入末槽（防御异常输入，绝不抛异常影响改写链路）
    stats.recordPaletteBits(-1);
    stats.recordPaletteBits(99);
    assertEquals(1L, stats.paletteBitsAt(0), "负位宽并入 0 位槽");
    assertEquals(1L, stats.paletteBitsAt(15), "超大位宽并入末槽");
    assertTrue(stats.paletteBitsSummary().contains("0位×1"));
    assertTrue(stats.paletteBitsSummary().contains("15位×1"));
  }
}
