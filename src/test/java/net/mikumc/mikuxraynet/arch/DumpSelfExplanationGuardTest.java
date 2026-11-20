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

  @Test
  void blockUpdateDecisionsAreTracedIntoDump() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    assertTrue(merger.contains("BlockUpdateTrace"),
        "合并器必须持有处置环：面板只有总数，回答不了「这两片走了哪条路」");
    assertTrue(merger.contains("recordTracePass("), "直通路径必须留痕（多坐标包到底放行了没有）");
    assertTrue(source("bandwidth/ThrottlePipeline.java").contains("blockUpdateTrace("),
        "管线必须把处置环暴露给诊断层");
    String diagnostics = source("util/Diagnostics.java");
    assertTrue(diagnostics.contains("最近块更新处置"),
        "dump 必须有一节逐条处置流水，否则「门只开一半」这类现场问题无法离线定位");
    assertTrue(diagnostics.contains("未采集（方块变更合并未接线"),
        "没有记录时必须明写成因，不能留一段看着有内容、其实是空壳的取证段");
  }

  @Test
  void cancelledButUndeliveredUpdatesAreRedeemed() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    assertTrue(merger.contains("shouldRedeem("),
        "取消即欠账：缓冲原包已被取消，丢弃前必须先判断能不能补发");
    assertTrue(merger.contains("BlockUpdateTrace.Decision.REDEEM"), "补发必须留痕");
    String stats = source("bandwidth/ThrottleStats.java");
    assertTrue(stats.contains("discardRedeemed"), "补发次数必须可观测");
    assertTrue(stats.contains("mergeVerifyFailed"), "合批校验失败次数必须可观测");
    String diagnostics = source("util/Diagnostics.java");
    assertTrue(diagnostics.contains("同世界补发") && diagnostics.contains("合批校验失败"),
        "面板必须把这两条兜底计数印出来：正常恒为 0，持续非 0 说明丢弃判定或字段布局出了问题");
  }

  @Test
  void mergedPacketLayoutIsVerifiedByContentNotJustLength() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    assertTrue(merger.contains("verifyPositions("),
        "合并包必须逐坐标校验内容：只比长度会让两片门被压成一条时照样发出去");
    assertTrue(merger.contains("DUPLICATE_SCAN_LIMIT"),
        "重复坐标必须能检出（同一 packed short 出现两次 = 另一片永远收不到更新）");
  }

  @Test
  void strictVerifyOffIsCalledOutInDump() throws Exception {
    assertTrue(source("util/Diagnostics.java").contains("自检关闭"),
        "strict-verify=false 时改写错配不会回退整块，这个风险必须印在 dump 里而不是藏在注释里");
  }

  @Test
  void lastResortResendGoesThroughTheGuardedHelper() throws Exception {
    assertTrue(source("bandwidth/BlockChangeMerger.java").contains("deliverEach("),
        "最后一道防线（逐条补发）必须走 UpdateDelivery：裸循环在中途抛出会把剩余条目一起丢掉");
    String delivery = source("bandwidth/UpdateDelivery.java");
    assertTrue(delivery.contains("catch (Throwable"), "逐条投递必须每条独立兜底，方法自身永不抛出");
    assertTrue(source("bandwidth/ThrottleStats.java").contains("sendFailed"),
        "逐条补发仍失败的条数必须可观测：正常恒为 0，非 0 代表客户端确实缺了这些方块更新");
  }

  @Test
  void flushDiscardDecisionStaysInOnePredicate() throws Exception {
    // 这条守门的原意是「丢弃判定只许有一份实现」：1.34.6 之前 flushInternal 自己写了一遍世界比较，
    // 与 discard() 各写一份，测试断言那张表而生产代码在那个点根本没查它。
    // 1.34.7 把冲刷这条改成「只认世界索引」（快照会冻结，见 FlushWorldGuardTest），判定仍在唯一的
    // 纯函数里，因此守门改为：flushInternal 必须调用该纯函数，且不得再自己拼世界比较去决定丢弃。
    String body = methodBody(source("bandwidth/BlockChangeMerger.java"), "private boolean flushInternal(");
    assertTrue(body.length() > 200, "flushInternal 必须仍存在");
    assertTrue(body.contains("FlushWorld.classify("),
        "冲刷前的丢弃判定必须落在唯一的判定表里（FlushWorld.classify）");
    assertTrue(!body.contains("worldChanged = !"), "丢弃判定不得再在方法体内就地拼表达式");
  }

  /**
   * 取某个方法/处理器的<b>方法体</b>：从签名处截到其后第一个「两空格缩进的右花括号」。
   *
   * <p>早期版本用「签名后固定 N 个字符」当窗口，窗口偏大时会把下一个方法的声明也圈进来——
   * 于是「A 方法里必须有 X」这种断言会被下一个方法里的 {@code X} 满足，红版也可能意外通过。
   * 这里改成按方法边界截断，断言必须落在真正被执行的那段代码里。
   */
  private static String methodBody(String source, String signature) {
    int start = source.indexOf(signature);
    if (start < 0) {
      return "";
    }
    int end = source.indexOf("\n  }", start);
    return end < 0 ? source.substring(start) : source.substring(start, end);
  }

  @Test
  void flushDiscardRequiresWorldIndexCorroboration() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    String body = methodBody(merger, "private boolean flushInternal(");
    assertTrue(body.length() > 200, "flushInternal 必须仍存在且能取到方法体");
    assertTrue(body.contains("FlushWorld.classify("),
        "冲刷守卫必须走 FlushWorld.classify：单侧快照会冻结（Folia 实体调度器随切维退役且不保证回调），"
            + "拿它当「玩家已换世界」的证据会整批丢掉在途更新（实测 69.5 万包）");
    assertTrue(body.contains("PlayerWorldIndex.context("),
        "守卫必须读世界索引上下文（与入缓冲固定同源），不能只看快照");
  }

  @Test
  void positionRefreshIsRescheduledOnWorldChangeAndRespawn() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    String changedBody = methodBody(merger, "public void onChangedWorld(");
    assertTrue(changedBody.length() > 100, "切维处理器必须存在");
    assertTrue(changedBody.contains("schedulePositionRefresh("),
        "切维后必须重排位置刷新任务：实体调度器任务会随实体移出旧 world 退役，不重排则快照永久冻结");
    String respawnBody = methodBody(merger, "public void onPostRespawn(");
    assertTrue(respawnBody.length() > 40,
        "必须处理复活完成：Folia 上重生会重建实体，调度器任务同样退役");
    assertTrue(respawnBody.contains("schedulePositionRefresh("), "复活完成后同样要重排刷新任务");
    assertTrue(merger.contains("PlayerPostRespawnEvent"),
        "复活重排必须挂 PlayerPostRespawnEvent：PlayerRespawnEvent 时玩家尚未落到复活点，登记会随旧实体退役");
  }

  @Test
  void playerMonitorsAreRescheduledOnPostRespawnAndWorldChange() throws Exception {
    assertMonitorReschedule("bandwidth/EntityCuller.java", "scheduleRecheck(", "retireRecheck(");
    assertMonitorReschedule("bandwidth/LatencyMonitor.java", "scheduleCheck(", "retireCheck(");
  }

  /**
   * 玩家周期任务的重排纪律（{@code EntityCuller} / {@code LatencyMonitor}）：挂在「复活完成」阶段、
   * 处理器与事件线程无关、退役回调只按注册身份摘除且不碰实体。
   *
   * <p>为什么只能源码守门：真实退役由 Folia 运行时驱动，离线造不出「实体被移除」；但「谁负责重排、重排
   * 挂在哪个阶段、退役回调允许做什么」全都写在源码里，可以静态钉死。行为面（幂等 / 精确 fencing / 停用与
   * 退出收口）由 {@code PlayerMonitorRescheduleTest} 用调度器替身覆盖。
   */
  private static void assertMonitorReschedule(String file, String scheduleCall, String retireMethod)
      throws Exception {
    String monitor = source(file);
    assertTrue(monitor.contains("PlayerPostRespawnEvent"),
        file + " 的复活重排必须挂 PlayerPostRespawnEvent：PlayerRespawnEvent 触发时玩家尚未落到复活点，"
            + "此时登记的任务会随旧实体退役（等于白排一次）");
    String post = methodBody(monitor, "public void onPostRespawn(");
    assertTrue(post.length() > 40, file + " 缺复活完成后的重排处理器（Folia 上复活后巡检会永久停摆）");
    assertTrue(post.contains(scheduleCall), file + " 的复活处理必须重排周期任务");
    String changed = methodBody(monitor, "public void onChangedWorld(");
    assertTrue(changed.length() > 40, file + " 缺切维后的重排处理器");
    assertTrue(changed.contains(scheduleCall), file + " 的切维处理必须重排周期任务");
    for (String body : new String[] {post, changed}) {
      assertTrue(!body.contains("getWorld()") && !body.contains("getLocation")
              && !body.contains("getPing()") && !body.contains("isOnline()"),
          file + " 的重排处理器不得读世界/坐标/在线状态：调度登记可从任意线程进行，处理器必须与事件线程无关");
    }
    String retire = methodBody(monitor, "private void " + retireMethod);
    assertTrue(retire.length() > 20, file + " 必须把退役回调单独成方法（关键代码路径要可静态核对）");
    assertTrue(retire.contains("remove(playerId, entry)"),
        file + " 的退役回调必须按注册身份精确摘除：按键盲删会把重建后的新注册一起摘掉");
    assertTrue(!retire.contains("cancel("),
        file + " 的退役回调在关键代码里执行（Javadoc 明令不得操作实体/世界），只许做内存记账");
    assertTrue(monitor.contains("isLive("),
        file + " 必须按「注册是否仍存活」复用任务，否则同 tick 的复活+切维会登记两条周期任务");
  }
  @Test
  void staleSnapshotIsTreatedAsUnknown() throws Exception {
    assertTrue(source("bandwidth/PlayerPositionSnapshots.java").contains("isStale("),
        "快照必须能自报心跳过期：冻结快照的内容看起来完全正常，只盯内容发现不了它已经死了");
    String merger = source("bandwidth/BlockChangeMerger.java");
    assertTrue(merger.contains("positions.isStale("),
        "近身判定与冲刷守卫都必须看心跳，过期即按未知处理（fail-open）");
    assertTrue(source("bandwidth/ThrottleStats.java").contains("snapshotResync"),
        "「快照与索引矛盾」的次数必须可观测：它是刷新任务已停的唯一早期信号");
  }

  @Test
  void flushDiscardIsTracedIntoTheRing() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    int flush = merger.indexOf("private boolean flushInternal(");
    String body = merger.substring(flush, Math.min(merger.length(), flush + 6000));
    assertTrue(body.contains("trace.record("),
        "四条丢弃入口一律要留痕：只有总数的话，「门只开一半」在现场无法离线定位");
    assertTrue(body.contains("FLUSH_SNAPSHOT"), "冲刷前比对这条来源必须点名");
  }
}
