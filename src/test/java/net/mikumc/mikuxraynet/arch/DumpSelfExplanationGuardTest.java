package net.mikumc.mikuxraynet.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 「dump 必须自解释」的结构守门。
 *
 * <p>起因是一份 45 人 / 16 小时的生产 dump：里面同时出现「零位移取消未注册（原因见启动日志）」「丢弃 628 049」
 * 「缺遮挡数据 602 862」「视野增量最大 63.7 ms 而预算 0.3 ms」——四个数字都只有结论、没有归因，服主手上
 * 只有 dump（日志早已滚动），于是无法判断该等、该调还是该查。这些用例把「归因入口必须存在」钉在源码上：
 * 判定逻辑可以演进，但这几条渲染/计数链路不许被悄悄删掉。
 *
 * <p>为什么用源码守门而不是行为用例：这些链路的输入端都是 Bukkit/Folia 运行时对象（配置、世界、封包），
 * 离线造不出来；能验的部分（纯函数）已另有行为用例（{@code ViewChangePercentileTest}、
 * {@code DiagnosticsFootprintTest}、{@code ThrottleDiscardSourceTest}）。
 */
class DumpSelfExplanationGuardTest {

  private static String source(String relative) throws Exception {
    return Files.readString(Path.of("src/main/java/net/mikumc/mikuxraynet/" + relative));
  }

  @Test
  void unregisteredReasonIsRenderedFromPlanInsteadOfStartupLog() throws Exception {
    String diagnostics = source("util/Diagnostics.java");
    assertTrue(diagnostics.contains("ThrottlePipeline.entityPacketsDisabledReason("),
        "未注册原因必须由面板自己算出并渲染，不能只写「原因见启动日志」");
    String pipeline = source("bandwidth/ThrottlePipeline.java");
    assertTrue(pipeline.contains("entityPacketsDisabledReason"),
        "原因推导入口必须存在于 ThrottlePipeline，且与 plan() 用同一组条件");
  }

  @Test
  void discardSourcesAreDistinguishedAtEveryEntry() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    for (String source : new String[] {"DIMENSION_EVENT", "POSITION_SNAPSHOT", "BLACKLIST", "FLUSH_SNAPSHOT"}) {
      assertTrue(merger.contains("ThrottleStats.DiscardSource." + source),
          "丢弃入口 " + source + " 必须按来源打标，否则面板无法区分「真切维」与「守卫误判」");
    }
    assertTrue(source("util/Diagnostics.java").contains("discardedByDimensionEvent"),
        "面板必须渲染四源拆分");
  }

  @Test
  void unknownSectionSplitsByWorldAndSection() throws Exception {
    String engine = source("occlusion/OcclusionEngine.java");
    assertTrue(engine.contains("unknownSectionNoStore") && engine.contains("unknownSectionNoEntry"),
        "缺遮挡数据必须区分「世界无表」与「段无条目」：前者随运行时间趋零，后者会稳定存在");
    assertTrue(source("util/Diagnostics.java").contains("unknownSectionNoStore"),
        "面板必须渲染这两类来源");
  }

  @Test
  void viewChangeReportsPercentileNotOnlyMaximum() throws Exception {
    assertTrue(source("antixray/ProximityRevealer.java").contains("recordViewChange("),
        "视野增量耗时必须进直方图，否则算不出 p99");
    String diagnostics = source("util/Diagnostics.java");
    assertTrue(diagnostics.contains("p99Text("),
        "面板必须显示 p99：最大值被 GC/调度抖动污染，用它判断预算会得出错误结论");
    assertTrue(diagnostics.contains("墙钟"),
        "最大值必须标注口径（墙钟，含 GC 抖动）");
  }

  @Test
  void footprintAndDiskWatermarkAreRendered() throws Exception {
    String diagnostics = source("util/Diagnostics.java");
    assertTrue(diagnostics.contains("footprintLine("), "必须渲染「本插件自身占用估算」，避免把整份堆算到本插件头上");
    assertTrue(diagnostics.contains("diskWatermarkHints("), "磁盘水位（打开文件 / 桶缓存 / 无上限）必须在面板可见");
  }
}
