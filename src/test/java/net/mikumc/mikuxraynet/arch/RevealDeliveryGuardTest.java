package net.mikumc.mikuxraynet.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

  @Test
  void foliaReusesHealthyTaskAndRetiresByIdentity() throws IOException {
    String source = stripComments(readSource("antixray/ProximityRevealer.java"));
    String schedule = methodBody(source, "scheduleEntityTask");
    assertTrue(schedule.indexOf("previous.isLive(") < schedule.indexOf("runAtFixedRate("));
    assertTrue(schedule.contains("next.heartbeat = System.nanoTime()"));
    assertTrue(schedule.contains("entityScans.remove(id, next)"));
    assertTrue(methodBody(source, "rescheduleEntityTasks").contains("periodicWindows.remove(id)"));
  }

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

  @Test
  void sectionJournalingPrecedesBackgroundIndexWork() throws IOException {
    String body = methodBody(stripComments(readSource("antixray/BlockChangeRevealListener.java")),
        "unregisterMulti");
    int journal = body.indexOf("changeJournal.recordSection(");
    assertTrue(journal >= 0 && journal < body.indexOf("submitUndo("));
  }

  @Test
  void chunkReleaseNeverWaitsForEntityAndRepairStartsAfterWrite() throws IOException {
    String source = stripComments(readSource("antixray/ProtocolLibAsyncListener.java"));
    String release = methodBody(source, "completeRelease");
    assertTrue(release.contains("signalPacketTransmission(event)"));
    assertFalse(release.contains("scheduleReplay("));
    assertFalse(release.contains("getScheduler("));
    String observer = methodBody(source, "registerDeliveryObserver");
    assertTrue(observer.contains("addPostListener("));
    assertTrue(observer.contains("onChunkWritten("));
    assertTrue(methodBody(source, "onChunkWritten").contains("changeJournal.written("));
  }

  @Test
  void periodicScanRunsBoundedSelectionInWorker() throws IOException {
    String body = methodBody(stripComments(readSource("antixray/ProximityRevealer.java")), "reveal");
    int submit = body.indexOf("workPool.execute(");
    assertTrue(submit >= 0 && body.indexOf("hasExposedFace(") > submit);
    assertTrue(body.indexOf("nextWindow(") > submit);
    assertTrue(body.contains("MAX_PERIODIC_POSITIONS"));
  }

  @Test
  void successfulBreakQueuesSweepAndDoesNotReplaceLegacyAirUpdates() throws IOException {
    String source = stripComments(readSource("antixray/ProximityRevealer.java"));
    assertTrue(source.contains("@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)"
        + "\n  public void onBlockBreak(BlockBreakEvent event)"));
    String entry = methodBody(source, "onBlockBreak");
    assertTrue(entry.contains("!proximity.raycastEnabled()"));
    assertTrue(entry.indexOf("revealMiningFront(") < entry.indexOf("breakSweep.enabled()"));
    for (String guard : new String[] {"onBlockDamage", "onBreakMiningGuard"}) {
      String body = methodBody(source, guard);
      int correction = body.indexOf("if (revealDigTarget(");
      assertTrue(correction >= 0 && correction < body.indexOf("event.setCancelled(true)"));
      assertFalse(body.contains("revealMiningFront("), "尝试和拒绝事件不能提前亮出墙后");
    }
    String sweep = methodBody(source, "revealSweep");
    assertTrue(sweep.indexOf("center.original") < sweep.indexOf("sendOne("));
    assertTrue(sweep.contains("batch, false)"));
    assertFalse(sweep.contains("withinManhattanRadius("));
    assertFalse(sweep.contains("sameSectionAsOwnChange("));
    String observe = methodBody(stripComments(readSource("antixray/BlockChangeRevealListener.java")),
        "observeChange");
    assertTrue(observe.contains("onBlockChangeObserved("));
    assertFalse(observe.contains("onBreakObserved("));
  }

  @Test
  void slowPreparationDoesNotClaimExclusivePacketWrite() throws IOException {
    String body = methodBody(stripComments(readSource("antixray/ProtocolLibAsyncListener.java")), "handleAsync");
    int prepare = body.indexOf("pipeline.rewrite(");
    int claim = body.indexOf("task.tryBeginWrite()");
    assertTrue(prepare >= 0 && claim > prepare, "磁盘、解码和缓存命中准备阶段仍须让看门狗抢占");
    assertTrue(body.indexOf("restoreOcclusion(") < claim);
    assertTrue(claim < body.indexOf("writeBack("));
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
