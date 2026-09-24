package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 状态/转储文本格式化：给定固定计数器，断言输出包含关键字段且格式稳定。
 *
 * <p>格式化是纯函数，因此无需 Bukkit 运行时即可断言。
 */
class DiagnosticsTest {

  /** 固定计数器快照（数值只用于断言关键字段与命中率，不参与业务逻辑）。 */
  private static Diagnostics.Snapshot fixed() {
    return new Diagnostics.Snapshot(
        true, true, false, true, 1,
        90L, 10L, 42,
        100L, 331L, 5L, 2L, 1L, 2L,
        7L, 3L, 1L,
        20L, 30L,
        4L, 40L, 12L,
        6L, 5L,
        2, 3L, 8L,
        9L, 4L,
        4, 2, 10, 2048,
        "Paper 1.20.4", "25", null, null,
        3L, 2L, 30L, 10L, 12, 2, 7, 41L, 3L);
  }

  @Test
  void statusContainsAllKeyFields() {
    List<String> lines = Diagnostics.formatStatus(fixed());
    String text = String.join("\n", lines);

    assertTrue(text.contains("反矿透"), text);
    assertTrue(text.contains("直通玩家 1 名"), text);
    assertTrue(text.contains("改写缓存"), text);
    assertTrue(text.contains("命中率 90.0%"), text);
    assertTrue(text.contains("区块改写"), text);
    assertTrue(text.contains("替换方块 331"), "状态面板必须显示累计替换方块数（区分『没匹配到目标』与『写回没生效』）");
    assertTrue(text.contains("异常 2"), text);
    assertTrue(text.contains("写回失败 1"), text);
    assertTrue(text.contains("超时放行"), text);
    assertTrue(text.contains("邻近显形"), text);
    assertTrue(text.contains("视锥剔除 3"), text);
    assertTrue(text.contains("射线剔除 2"), text);
    assertTrue(text.contains("显形索引：条目 7，淘汰 41，丢弃 3"),
        "状态面板必须分列显形索引条目数、淘汰数与丢弃数（否则容量触顶会静默发生）：" + text);
    assertTrue(text.contains("磁盘缓存"), text);
    assertTrue(text.contains("命中率 75.0%"), text);
    assertTrue(text.contains("带宽"), text);
    assertTrue(text.contains("AFK 玩家 2"), text);
    assertTrue(text.contains("线程池"), text);
    assertTrue(text.contains("队列 10/2048"), text);
  }

  @Test
  void statusIsStableAcrossCalls() {
    List<String> first = Diagnostics.formatStatus(fixed());
    List<String> second = Diagnostics.formatStatus(fixed());
    assertTrue(first.equals(second), "同样的计数器必须得到同样的文本");
  }

  @Test
  void dumpContainsEnvironmentAndConfigSections() {
    String dump = Diagnostics.formatDump(fixed(), "20260924-120000");

    assertTrue(dump.contains("MikuXrayNet 诊断转储"), dump);
    assertTrue(dump.contains("导出时间：20260924-120000"), dump);
    assertTrue(dump.contains("服务端：Paper 1.20.4"), dump);
    assertTrue(dump.contains("JVM：25"), dump);
    assertTrue(dump.contains("配置指纹："), dump);
    assertTrue(dump.contains("---- 配置项有效值 ----"), dump);
    // 无配置对象时必须给出可读的中文说明而非抛异常
    assertTrue(dump.contains("[antixray]"), dump);
    assertTrue(dump.contains("[bandwidth]"), dump);
  }

  @Test
  void hitRateHandlesNoSamples() {
    Diagnostics.Snapshot empty = new Diagnostics.Snapshot(
        false, false, true, false, 0,
        0L, 0L, 0,
        0L, 0L, 0L, 0L, 0L, 0L,
        0L, 0L, 0L,
        0L, 0L,
        0L, 0L, 0L,
        0L, 0L,
        0, 0L, 0L,
        0L, 0L,
        0, 0, 0, 0,
        "Folia 1.21", "21", null, null,
        0L, 0L, 0L, 0L, 0, 0, 0, 0L, 0L);
    String text = String.join("\n", Diagnostics.formatStatus(empty));
    assertTrue(text.contains("命中率 0.0%"), text);
    assertTrue(text.contains("平台 Folia"), text);
  }
}