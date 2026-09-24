package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 状态/转储文本格式化：给定固定计数器，断言输出包含关键字段且格式稳定。
 *
 * <p>格式化是纯函数，因此无需 Bukkit 运行时即可断言。
 */
class DiagnosticsTest {

  /** 固定计数器快照（数值只用于断言关键字段与命中率，不参与业务逻辑）。 */
  private static Diagnostics.Snapshot fixed() {
    return fixed(null);
  }

  private static Diagnostics.Snapshot fixed(BandwidthConfig bandwidth) {
    return new Diagnostics.Snapshot(
        true, true, false, true, 1,
        90L, 10L, 42,
        100L, 331L, 5L, 2L, 1L, 2L,
        7L, 3L, 1L,
        20L, 30L,
        4L, 40L, 12L,
        6L, 5L, 4,
        40L, 7L, 3L,
        2, 3L, 8L,
        9L, 4L,
        4, 2, 10, 2048,
        "Paper 1.20.4", "25", null, bandwidth,
        3L, 2L, 30L, 10L, 12, 2, 7, 41, 12, 108L, 3L, 2L);
  }

  private static BandwidthConfig bandwidth(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return BandwidthConfig.from(configuration);
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
    assertTrue(text.contains("邻近显形：发送 7，坐标跳过 3"),
        "「跳过」必须写明是「坐标跳过」，否则会被误读成「整块跳过 = 0 → 整块跳过没生效」：" + text);
    assertTrue(text.contains("视锥剔除 3"), text);
    assertTrue(text.contains("射线剔除 2"), text);
    assertTrue(text.contains("显形索引：伪装区块 7（坐标 41）｜已显形 坐标 12（当前在线）｜累计登记 108｜安全阀触发 3/2"),
        "状态面板必须分列伪装区块数、伪装坐标数、实时已显形坐标数、累计登记数与安全阀触发数"
            + "（否则「发送 N 但已显形 0」这类口径误读会静默发生）：" + text);
    assertTrue(text.contains("实体隐藏 6/恢复 5（当前隐藏中 4"),
        "实体隐藏/恢复必须附带实时「当前隐藏中」，否则 721 vs 299 这类不对称无法自证：" + text);
    assertTrue(text.contains("实体复检 40（复检致隐藏 7，复检致恢复 3）"),
        "状态面板必须单列周期复检口径，否则「先可见后被遮挡」是否被收敛到隐藏无法观测：" + text);
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

  /**
   * 周期运行摘要（diagnostics.interval-seconds 驱动）必须恒为一行、只含关键计数，
   * 与完整状态面板（formatStatus）区分开——周期日志输出整面板会刷屏。
   */
  @Test
  void summaryLineIsSingleLineWithKeyCounters() {
    String line = Diagnostics.formatSummaryLine(fixed());

    assertFalse(line.contains("\n"), "运行摘要必须恒为一行：" + line);
    assertTrue(line.startsWith("运行摘要："), line);
    assertTrue(line.contains("反矿透 生效"), line);
    assertTrue(line.contains("区块改写 100（异常 2，超时放行 2）"), line);
    assertTrue(line.contains("显形发送 7"), line);
    assertTrue(line.contains("实体隐藏 6/恢复 5（当前隐藏中 4）"), line);
    assertTrue(line.contains("磁盘缓存命中 30"), line);
    assertTrue(line.contains("队列 10/2048"), line);
    // 精简版不得把整面板的内容都塞进来（如依赖状态、AFK、带宽开关段）
    assertFalse(line.contains("依赖"), "单行摘要不该包含状态面板的完整内容：" + line);
    assertFalse(line.contains("AFK"), line);
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
        0L, 0L, 0,
        0L, 0L, 0L,
        0, 0L, 0L,
        0L, 0L,
        0, 0, 0, 0,
        "Folia 1.21", "21", null, null,
        0L, 0L, 0L, 0L, 0, 0, 0, 0, 0, 0L, 0L, 0L);
    String text = String.join("\n", Diagnostics.formatStatus(empty));
    assertTrue(text.contains("命中率 0.0%"), text);
    assertTrue(text.contains("平台 Folia"), text);
  }

  /**
   * 调色板重排的回显必须把「模块总开关」与「行为开关 reorder」合成一句无歧义中文：
   * 只打印 {@code palette.enabled()} 会输出「调色板重排 true」，而真正决定是否重排的是 reorder（默认 false）。
   */
  @Test
  void paletteSwitchIsUnambiguous() {
    String defaultOff = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("enabled: true\n"))));
    assertTrue(defaultOff.contains("调色板重排 关闭（模块启用但 reorder=false，不做任何重排）"),
        "模块启用 + reorder=false 时必须明确写「不做任何重排」：" + defaultOff);

    String reorderOn = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("palette:\n  reorder: true\n"))));
    assertTrue(reorderOn.contains("调色板重排 启用（reorder=true）"), reorderOn);

    String moduleOff = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("palette:\n  enabled: false\n"))));
    assertTrue(moduleOff.contains("调色板重排 关闭（模块未启用，不做任何重排）"), moduleOff);
  }
}