package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 「封包线程绝不回退读实体世界」的专项回归（{@code AfkTracker} / {@code EntityPacketFilter}）。
 *
 * <p><b>缺陷</b>：两处在 {@code PlayerWorldIndex} 快照缺失时回退到 {@code player.getWorld()}。封包线程上这是
 * Folia 的跨区域读实体状态（{@code TickThread} 校验先打 ERROR 再抛），每包一条异常日志；而项目其余出站监听
 * 器早已统一改成「只查快照、查不到就原包放行」，旧守门只要求出现 {@code PlayerWorldIndex} 字样，没拦住这条
 * 回退路径。
 *
 * <p><b>为什么行为断言只能打到 {@code skipInspection}</b>：真正驱动包路径需要 {@code PacketEvent}，而它的构造
 * 依赖 {@code PacketContainer} + NMS（见 {@code RegressionWiringGuardTest} 的类注释），离线不可用。因此这里
 * 分两层：① 对前段判定做真实行为断言（快照缺失 / 黑名单 / 正常世界三种输入 + 黑名单配置的发布语义）；
 * ② 对两个包处理器做源码守门（不得出现 {@code getWorld()}、身份只取一次、放行口径仍在），与本仓
 * {@code PacketThreadPurityTest} 同一思路。
 */
class PacketWorldFallbackTest {

  private static final Path MAIN = Path.of("src/main/java/net/mikumc/mikuxraynet");

  @AfterEach
  void resetBlacklist() {
    // 黑名单是进程级静态发布：测试结束必须复位，否则会污染其它用例的世界判定
    WorldBlacklistConfig.publish(null);
  }

  /**
   * 快照缺失（{@code null}）必须直接跳过：这是「不回退读实体世界」的唯一实现方式。
   *
   * <p>两个类的判定必须一致——否则同一部署下 AFK 丢包与零位移取消会表现出不同的世界语义。
   */
  @Test
  void unknownSnapshotIsSkippedByBothModules() {
    assertTrue(EntityPacketFilter.skipInspection(null),
        "快照缺失时零位移取消必须原包放行（不得回退读 player.getWorld()）");
    assertTrue(AfkTracker.skipInspection(null),
        "快照缺失时 AFK 丢包必须原包放行（不得回退读 player.getWorld()）");
  }

  /** 黑名单世界照旧跳过；普通世界照旧进入判定——两处语义必须与既有黑名单口径一致。 */
  @Test
  void blacklistedWorldSkipsAndNormalWorldInspects() {
    WorldBlacklistConfig.publish(WorldBlacklistConfig.fromPatterns(List.of("blocked", "dungeon_*")));

    assertTrue(EntityPacketFilter.skipInspection("blocked"), "精确名命中黑名单必须跳过");
    assertTrue(AfkTracker.skipInspection("blocked"), "精确名命中黑名单必须跳过");
    assertTrue(EntityPacketFilter.skipInspection("dungeon_7"), "通配命中黑名单必须跳过");
    assertTrue(AfkTracker.skipInspection("dungeon_7"), "通配命中黑名单必须跳过");
    assertFalse(EntityPacketFilter.skipInspection("world"), "普通世界必须继续判定（不得误跳过）");
    assertFalse(AfkTracker.skipInspection("world"), "普通世界必须继续判定（不得误跳过）");
  }

  /** 空名单（默认）时任何已知世界都继续判定：避免「配置为空」被误当成「全部跳过」。 */
  @Test
  void emptyBlacklistKeepsInspectingEveryWorld() {
    WorldBlacklistConfig.publish(null);
    assertFalse(EntityPacketFilter.skipInspection("world"));
    assertFalse(AfkTracker.skipInspection("world"));
  }

  /**
   * 源码守门：两个包处理器只许查快照，不得出现 {@code getWorld()}。
   *
   * <p>为什么不直接断言「调用了 skipInspection」就够了：回退读实体世界完全可能被写成另一段分支（正是这次
   * 修掉的形态），只有「方法体内一次 getWorld 都没有」才能同时挡住旧写法与未来新增的回退。
   */
  @Test
  void packetPathsNeverReadEntityWorld() throws Exception {
    assertSnapshotOnly("bandwidth/AfkTracker.java", "void handleLowValuePacket(");
    assertSnapshotOnly("bandwidth/EntityPacketFilter.java", "public void onPacketSending(");
  }

  /**
   * 统计与监听范围不得被这次改动挪动：
   * <ul>
   *   <li>{@code EntityPacketFilter} 的「放行」计数在快照缺失分支仍要累加（否则面板会把「判不出世界」
   *       看成「包没到」）；</li>
   *   <li>朝向包（{@code REL_ENTITY_MOVE_LOOK} / {@code ENTITY_LOOK}）的监听与到达计数必须保留——
   *       它们的「取消 0」是诊断口径的一部分，移除监听会让统计改变含义。</li>
   * </ul>
   */
  @Test
  void passCountingAndLookListenerStayIntact() throws Exception {
    String filter = source("bandwidth/EntityPacketFilter.java");
    assertTrue(filter.contains("PacketType.Play.Server.ENTITY_LOOK"),
        "朝向包监听不得移除：它只被计数/放行，是诊断口径的一部分");
    assertTrue(filter.contains("REL_ENTITY_MOVE_LOOK"), "位移+朝包监听同样不得移除");
    String body = methodBody(filter, "public void onPacketSending(");
    assertTrue(body.contains("pass();"), "快照缺失分支必须照旧计入「放行」口径");
    assertTrue(body.contains("entityLookPackets.increment()")
            || body.contains("stats.entityLookPackets"), "到达口径三分必须保留");
  }

  /** 每包只取一次身份：旧实现同一次判定里读了三次 uuid（快照 / 直通 / 状态表）。 */
  @Test
  void identityIsLookedUpOncePerPacket() throws Exception {
    String afkBody = stripComments(methodBody(source("bandwidth/AfkTracker.java"),
        "void handleLowValuePacket("));
    assertEquals(1, countOf(afkBody, "getUniqueId("), "AFK 丢包路径每包只应取一次 uuid");
    String filterBody = stripComments(methodBody(source("bandwidth/EntityPacketFilter.java"),
        "public void onPacketSending("));
    assertEquals(1, countOf(filterBody, "getUniqueId("), "零位移取消路径每包只应取一次 uuid");
  }

  // ---------------------------------------------------------------- 工具

  /** 方法体内不得出现实体世界读取，且必须走快照。 */
  private static void assertSnapshotOnly(String file, String signature) throws Exception {
    // 去掉注释行：说明文字里提到 getWorld()（解释为什么不许用）不算违规
    String body = stripComments(methodBody(source(file), signature));
    assertTrue(body.length() > 80, file + " 的 " + signature + " 必须存在");
    assertFalse(body.contains("getWorld("),
        file + " 的包路径不得读实体世界：Folia 上封包线程跨区域读会先打 ERROR 再抛（快照缺失即原包放行）");
    assertTrue(body.contains("PlayerWorldIndex.worldName("),
        file + " 的包路径必须只查玩家世界名快照");
    assertTrue(body.contains("skipInspection("),
        file + " 的包路径必须走统一的前段判定（快照缺失与黑名单同一处收口）");
  }

  /** 只丢整行注释（与本仓 RegressionWiringGuardTest 同一口径），保留代码行的行尾注释不影响判定。 */
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

  private static String source(String relative) throws Exception {
    return Files.readString(MAIN.resolve(relative));
  }

  /** 取方法体：从签名处截到其后第一个「两空格缩进的右花括号」（与 DumpSelfExplanationGuardTest 同一口径）。 */
  private static String methodBody(String source, String signature) {
    int start = source.indexOf(signature);
    if (start < 0) {
      return "";
    }
    int end = source.indexOf("\n  }", start);
    return end < 0 ? source.substring(start) : source.substring(start, end);
  }

  private static int countOf(String text, String needle) {
    int count = 0;
    int from = 0;
    while (true) {
      int index = text.indexOf(needle, from);
      if (index < 0) {
        return count;
      }
      count++;
      from = index + needle.length();
    }
  }
}
