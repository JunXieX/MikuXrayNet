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
 * 接线守门：四处「写错了也能编译、也能跑，但语义已经反了」的顺序与字段问题。
 *
 * <p>它们全都无法在无服务端的前提下做行为断言（{@code PacketContainer} 构造需要 NMS 类，
 * 事件/调度器需要 Bukkit 实例），所以这里退化为源码结构断言——与本仓 {@code PacketThreadPurityTest}
 * 同一思路：把「必须长成什么样」钉在测试里，避免下一次改动悄悄改回去。
 *
 * <p>四条的后果分别是：破坏粒子泄露真实方块状态、反矿透关闭时带宽静默退化、
 * 被隐藏实体永远等不到恢复、{@code CHUNK_BATCH_FINISHED} 早于最后一个区块包。
 */
class RegressionWiringGuardTest {

  private static final Path MAIN = Path.of("src/main/java/net/mikumc/mikuxraynet");

  @Test
  void worldEventFilterReadsBlockPositionField() throws IOException {
    String source = readSource("antixray/LeakFilterListener.java");
    // 去掉注释：说明文字里提到 getIntegers（解释为什么不能用它）不算违规
    String body = stripComments(methodBody(source, "shouldFilter"));
    assertTrue(body != null, "shouldFilter 必须存在（泄露过滤的坐标解析入口）");
    int branchStart = body.indexOf("PacketType.Play.Server.WORLD_EVENT");
    assertTrue(branchStart >= 0, "必须保留 WORLD_EVENT 分支（2001 方块破坏粒子带真实方块状态）");
    String branch = blockAt(body, branchStart);
    assertTrue(branch.contains("getBlockPositionModifier"),
        "WORLD_EVENT 的坐标字段是 Position：必须走 getBlockPositionModifier，否则永远取不到坐标、过滤恒失效");
    assertFalse(branch.contains("getIntegers"),
        "WORLD_EVENT 只有 Event/Data 两个整数域，从 getIntegers 取坐标恒为 null（等于不过滤）");
  }

  @Test
  void playerWorldIndexStartsOnEnableRegardlessOfAntiXray() throws IOException {
    String source = stripComments(readSource("MikuXrayNet.java"));
    int start = source.indexOf("PlayerWorldIndex.start");
    assertTrue(start >= 0, "MikuXrayNet 必须自己启动玩家世界名快照（带宽模块与反矿透相互独立）");
    int antiXray = source.indexOf("startAntiXray()");
    assertTrue(antiXray > start,
        "快照启动必须早于 startAntiXray：反矿透关闭或缺 PacketEvents 时它有两处提前 return");
    assertTrue(source.contains("PlayerWorldIndex.stop"), "停用路径必须注销快照，否则重载后不再重建");
  }

  @Test
  void cullerRegistersEntityBeforeHidingIt() throws IOException {
    String source = readSource("bandwidth/EntityCuller.java");
    String body = methodBody(source, "hide");
    assertTrue(body != null, "hide 必须存在");
    int register = body.indexOf("map.put(entityId");
    int hideCall = body.indexOf("hideEntity(plugin");
    assertTrue(register >= 0 && hideCall >= 0, "hide 内必须同时含登记与隐藏两个动作");
    assertTrue(register < hideCall,
        "必须先登记再隐藏：hideEntity 内同步派发的 untrack 若看不到账本条目，该实体就再也不会被恢复可见");
  }

  @Test
  void batchGateReleasesChunkBeforeFinishingBatch() throws IOException {
    String source = readSource("antixray/ProtocolLibAsyncListener.java");
    String body = methodBody(source, "completeRelease");
    assertTrue(body != null, "completeRelease 必须存在");
    int signal = body.indexOf("signalPacketTransmission");
    int done = body.indexOf("gate.chunkDone()");
    assertTrue(signal >= 0 && done >= 0, "completeRelease 内必须同时含放行与归还计数");
    assertTrue(signal < done,
        "必须先放行区块包再归还批次计数：否则 CHUNK_BATCH_FINISHED 会排到最后一个区块包之前");
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

  @Test
  void digTargetRevealChecksLineOfSight() throws IOException {
    String body = methodBody(readSource("antixray/ProximityRevealer.java"), "revealDigTarget");
    assertTrue(body != null, "revealDigTarget 必须存在");
    int sight = body.indexOf("rayTraceBlocks");
    int send = body.indexOf("sendOne(");
    assertTrue(sight >= 0, "开掘预显形必须先做视线判定：服务端对 START_DESTROY_BLOCK 只校验交互距离，不校验视线");
    assertTrue(send > sight, "视线判定必须早于发包，否则伪造破坏包就能换到真实方块状态");
  }

  @Test
  void digNeighbourRevealIgnoresOwnChangeWindow() throws IOException {
    String body = methodBody(readSource("antixray/ProximityRevealer.java"), "revealAfterDig");
    assertTrue(body != null, "revealAfterDig 必须存在");
    assertTrue(body.contains("occludedBy"), "必须仍然只还原「挖开之后才看得见」的坐标");
    assertFalse(body.contains("sameSectionAsOwnChange"),
        "这一段不得再按 section 让开安静窗口：6 邻与正在挖的那一格几乎总在同一 section，会让预显形永不执行");
  }
}