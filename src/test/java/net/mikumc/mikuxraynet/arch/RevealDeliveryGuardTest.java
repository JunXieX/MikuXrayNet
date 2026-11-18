package net.mikumc.mikuxraynet.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 接线守门：三条「显形/补发链路悄悄停摆」的回归。
 *
 * <p>它们全都无法在无服务端的前提下做行为断言（事件调度、Bukkit 射线、封包回调都要真实服务端），
 * 因此与 {@link RegressionWiringGuardTest} 同一思路：把「必须长成什么样」钉在源码结构上。
 *
 * <p>三条的现场后果分别是：玩家死亡/切维后周期巡检与视线显形永久停摆（只剩事件路径）、
 * 斜着挖时预显形被中心射线挡掉（看到石头、掉出来是矿）、
 * 段级变更日志晚于区块放行落库（整段附着方块被旧区块字节盖回去且永不补发）。
 */
class RevealDeliveryGuardTest {

  private static final Path MAIN = Path.of("src/main/java/net/mikumc/mikuxraynet");

  /**
   * Folia 上实体调度器任务随实体退役：重生与切维都会重建实体，任务句柄随之失效且不保证回调。
   * 周期巡检与视线即时显形都是「每玩家一条实体调度任务」，不重排就是该玩家此后只能靠事件还原假矿。
   */
  @Test
  void proximityTasksAreRescheduledOnRespawnAndWorldChange() throws IOException {
    String source = stripComments(readSource("antixray/ProximityRevealer.java"));
    for (String handler : new String[] {"onRespawn", "onChangedWorld"}) {
      String body = methodBody(source, handler);
      assertTrue(body != null, handler + " 必须存在（Folia 重生 / 切维会重建实体）");
      assertTrue(body.contains("rescheduleEntityTasks("),
          handler + " 必须重排周期巡检与视线即时显形任务，否则该玩家的假矿从此只由事件路径还原");
    }
    String reschedule = methodBody(source, "rescheduleEntityTasks");
    assertTrue(reschedule != null, "rescheduleEntityTasks 必须存在");
    assertTrue(reschedule.contains("scheduleEntityTask("),
        "重排必须真的重新调度：只取消不重排等于把两条路径永久停掉");
    assertTrue(reschedule.contains("lookScans.remove"), "重排前必须摘掉旧的视线任务句柄");
    assertTrue(reschedule.contains("entityScans.remove"), "重排前必须摘掉旧的周期巡检任务句柄");
  }

  /**
   * 开掘预显形的视线判定：只走「眼睛 → 方块中心」一条射线时，斜着挖（准星落在方块边缘/角上）
   * 会先命中相邻方块而被判不可见——玩家整段挖掘期间看到的都是伪装方块，破坏后却掉真实矿物。
   */
  @Test
  void digTargetRevealFallsBackToViewRay() throws IOException {
    String source = stripComments(readSource("antixray/ProximityRevealer.java"));
    String dig = methodBody(source, "revealDigTarget");
    assertTrue(dig != null, "revealDigTarget 必须存在");
    int sight = dig.indexOf("canSeeDugBlock(");
    int send = dig.indexOf("sendOne(");
    assertTrue(sight >= 0, "开掘预显形必须先做视线判定（否则伪造破坏包就能换到真实状态）");
    assertTrue(send > sight, "视线判定必须早于发包");
    String sightBody = methodBody(source, "canSeeDugBlock");
    assertTrue(sightBody != null, "canSeeDugBlock 必须存在");
    assertTrue(countOccurrences(sightBody, "rayTraceBlocks(") >= 2,
        "必须同时用「眼睛→方块中心」与「玩家视线方向」两条射线：斜着挖时中心射线会先穿过相邻方块，"
            + "只有视线方向才代表客户端真的挖到了它");
    assertTrue(sightBody.contains("eye.getDirection()"), "第二条射线必须用玩家真实视线方向");
  }

  /**
   * 段级（MULTI_BLOCK_CHANGE）变更的「回显过滤 + 变更日志」必须在封包线程同步完成。
   *
   * <p>区块包放行动作可能在改写一完成后立刻执行（封包线程或玩家线程），而历史实现把这两步连同
   * 索引摘除一起投递到 BlockUndo 后台线程——晚一步落库就是「旧区块字节盖回去、补发列表里没有它」，
   * 客户端永久保留旧状态（幽匿脉络等附着方块整段卡住，右键被服务端纠正后才消失）。
   */
  @Test
  void sectionChangeJournalingStaysOnPacketThread() throws IOException {
    String body = methodBody(stripComments(readSource("antixray/BlockChangeRevealListener.java")),
        "unregisterMulti");
    assertTrue(body != null, "unregisterMulti 必须存在");
    int journal = body.indexOf("recordChange(");
    int submit = body.indexOf("submitUndo(");
    assertTrue(journal >= 0, "多方块变更必须在封包线程逐个记录变更日志");
    assertTrue(submit > journal,
        "变更日志必须先于后台投递落库：区块放行与 BlockUndo 线程竞速，晚一步落库就等于该格永不补发");
  }

  /**
   * 周期候选窗口必须挂「语义等价预筛」（六面全遮挡直接丢弃）。
   *
   * <p>没有它时，高矿密度世界的窗口名额会被最近的全埋矿占满（真机 dump：407 个伪装坐标/区块），
   * 看得见的矿排在窗口外，玩家只能靠准星对准才看到真方块。
   */
  @Test
  void periodicScanWiresTheSemanticPrefilter() throws IOException {
    String source = stripComments(readSource("antixray/ProximityRevealer.java"));
    String body = methodBody(source, "reveal");
    assertTrue(body != null, "reveal 必须存在");
    assertTrue(body.contains("hasExposedFace("),
        "周期巡检必须挂语义等价预筛：窗口名额只该发给可能可见的坐标");
    assertTrue(body.contains("prefilter)") || body.contains("prefilter,"),
        "预筛必须真的传进 ProximityScanner.candidates（只建对象不传等于没挂）");
    assertTrue(body.contains("recordScanTally("),
        "窗口覆盖距离与预筛丢弃数必须记账，否则「覆盖够不够」无法从 dump 判断");
  }

  /**
   * 破坏方块后的邻域强制显形：<b>去掉调度限制、保留可见性判定</b>。
   *
   * <p>去掉的是窗口/分片/同段安静窗口/张角判定（它们服务的是「少发包」，代价是玩家眼前的假方块排队）；
   * 保留的是遮挡位图射线（去掉它等于把透视端要的东西直接发过去）。额度必须独立，否则一次爆炸事件
   * 就能把当 tick 的即时显形额度吃光，紧随其后的挖掘一格都发不出去。
   */
  @Test
  void breakSweepBypassesSchedulingFiltersButKeepsVisibility() throws IOException {
    String source = stripComments(readSource("antixray/ProximityRevealer.java"));
    String entry = methodBody(source, "onBreakObserved");
    assertTrue(entry != null, "onBreakObserved 必须存在（破坏方块后的邻域强制显形入口）");
    assertTrue(entry.contains("breakSweep"), "入口必须读 break-sweep 配置（可关闭、可调半径）");
    assertTrue(entry.contains("hasDisguisedNearby("), "封包线程必须先做邻域初筛，避免无谓调度");

    String sweep = methodBody(source, "revealSweep");
    assertTrue(sweep != null, "revealSweep 必须存在");
    assertFalse(sweep.contains("occludedBy"), "不得再做「只还原被这一格挡住」的张角判定");
    assertFalse(sweep.contains("sameSectionAsOwnChange"), "不得让开同段安静窗口（否则整条路径几乎永不执行）");
    assertTrue(sweep.contains("sendOne("), "必须复用既有发包链路（标记/额度/统计单点）");
    assertTrue(sweep.contains("batch, false)"), "必须保留遮挡位图可见性判定（visibilityInPlan=false）");
    assertTrue(sweep.contains("breakSweepQuota"), "必须用独立额度，不能被其它即时显形路径挤占");
  }

  /** 「变成空气」与一般暴露类变更必须分流：前者整片扫邻域，后者保持旧的「只还原被挡住的」。 */
  @Test
  void airChangesRouteToBreakSweep() throws IOException {
    String source = stripComments(readSource("antixray/BlockChangeRevealListener.java"));
    assertTrue(source.contains("clearedToAir("), "必须区分「新状态是空气」与一般暴露类变更");
    String body = methodBody(source, "observeChange");
    assertTrue(body != null, "observeChange 必须存在");
    int sweep = body.indexOf("onBreakObserved(");
    int legacy = body.indexOf("onBlockChangeObserved(");
    assertTrue(sweep >= 0, "破坏（新状态是空气）必须走邻域强制显形");
    assertTrue(legacy > sweep, "非空气的暴露类变更（火把/玻璃）仍走旧的「只还原被挡住」路径");
  }

  private static String readSource(String relativePath) throws IOException {
    return Files.readString(MAIN.resolve(relativePath));
  }

  /** 丢掉行注释与块注释行，避免「注释里提到过」被当成真实调用。 */
  private static String stripComments(String text) {
    StringBuilder builder = new StringBuilder(text.length());
    for (String line : text.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
        builder.append('\n');
        continue;
      }
      builder.append(line).append('\n');
    }
    return builder.toString();
  }

  /** 取方法体（含花括号配对）；未找到返回 {@code null}。 */
  private static String methodBody(String source, String name) {
    Matcher matcher = Pattern.compile("\\b(?:void|boolean|int|String)\\s+" + Pattern.quote(name)
        + "\\s*\\(").matcher(source);
    if (!matcher.find()) {
      return null;
    }
    return blockAt(source, matcher.start());
  }

  /** 从 {@code fromIndex} 之后第一个左花括号开始，返回到配对右花括号为止的片段。 */
  private static String blockAt(String text, int fromIndex) {
    int brace = text.indexOf('{', fromIndex);
    if (brace < 0) {
      return "";
    }
    int depth = 0;
    for (int i = brace; i < text.length(); i++) {
      char character = text.charAt(i);
      if (character == '{') {
        depth++;
      } else if (character == '}') {
        depth--;
        if (depth == 0) {
          return text.substring(brace, i + 1);
        }
      }
    }
    return text.substring(brace);
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
