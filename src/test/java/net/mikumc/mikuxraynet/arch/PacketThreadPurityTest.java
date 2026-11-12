package net.mikumc.mikuxraynet.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 架构守门：<b>封包线程回调里不得直接读实体状态</b>。
 *
 * <p>背景：{@code ProtocolLib} 的同步出站回调运行在 netty 线程上，Folia 上读
 * {@code player.getWorld()} / {@code getLocation()} 会先打 ERROR 再抛
 * {@code No currently ticking region}。这类违规已在三个模块里各出现过一次（AFK 丢包、零位移取消、
 * 方块变更合并的旧跨世界守卫），靠注释提醒挡不住下一次。
 *
 * <p>规则：下列回调方法体内若出现 {@code getWorld()} 或 {@code getLocation()}，必须同时出现
 * {@code PlayerWorldIndex.worldName}——即只允许「快照优先、快照缺失才回退」的写法，不允许直接读实体状态。
 * 这是纯源码扫描（不启动服务端），新增回调必须加进名单。
 */
class PacketThreadPurityTest {

  /** 已知在封包线程执行的回调（含内部类方法）。新增此类入口必须登记，否则守门形同虚设。 */
  private static final Set<String> PACKET_CALLBACKS = Set.of(
      "onPacketSending",
      "handleLowValuePacket",
      "patchSingle",
      "patchMulti",
      "unregisterCoordinate",
      "processChanges",
      "observeChange");

  private static final Pattern METHOD = Pattern.compile(
      "\\b(?:void|boolean|int)\\s+(%s)\\s*\\(");

  @Test
  void packetCallbacksNeverReadEntityStateDirectly() throws IOException {
    List<String> violations = new ArrayList<>();
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      files.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
    }
    for (Path source : sources) {
      String text = Files.readString(source);
      for (String callback : PACKET_CALLBACKS) {
        String body = methodBody(text, callback);
        if (body == null) {
          continue;
        }
        boolean readsEntityState = body.contains("getWorld()") || body.contains("getLocation()");
        if (readsEntityState && !body.contains("PlayerWorldIndex.worldName")) {
          violations.add(source + " -> " + callback);
        }
      }
    }
    assertTrue(violations.isEmpty(),
        "以下封包线程回调直接读取了实体世界/位置，必须先查 PlayerWorldIndex 快照（见类注释）："
            + violations);
  }

  /** 取方法体（含花括号配对）；未找到返回 {@code null}。 */
  private static String methodBody(String source, String name) {
    Matcher matcher = Pattern.compile(String.format(METHOD.pattern(), Pattern.quote(name)))
        .matcher(source);
    if (!matcher.find()) {
      return null;
    }
    int brace = source.indexOf('{', matcher.start());
    if (brace < 0) {
      return null;
    }
    int depth = 0;
    for (int i = brace; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(brace, i + 1);
        }
      }
    }
    return null;
  }
}
