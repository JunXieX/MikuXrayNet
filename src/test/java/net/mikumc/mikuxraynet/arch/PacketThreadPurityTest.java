package net.mikumc.mikuxraynet.arch;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 架构守门：<b>封包（网络）线程回调里不得读取实体世界/位置</b>。
 *
 * <p>背景：ProtocolLib 的出站回调——同步监听跑在 netty 管线线程、异步监听跑在它自己的 worker——都不是
 * 「拥有该实体的线程」。在这两类线程上读实体状态（{@code getWorld()} / {@code getLocation()}）属于本项目
 * 明令禁止的越界读取：历史上已在三个模块各出现过一次（AFK 丢包、零位移取消、方块变更合并的旧跨世界守卫），
 * 靠注释提醒挡不住下一次。需要世界/位置时一律读由实体所属线程发布的快照
 * （{@code PlayerWorldIndex} / {@code PlayerPositionSnapshots}）。
 *
 * <p>本轮把规则收紧成三条（旧的「出现快照字样就免责」口径已删除，那会让只改一行回落的实现继续蒙混）：
 * <ol>
 *   <li>名单按 <b>文件 + 方法名</b> 登记——避免误拦实体侧同名方法（例如
 *       {@code BlockChangeMerger#sendSection} 与 {@code ProximityRevealer#sendSection} 同名，
 *       后者跑在玩家线程、读世界是合法的）；</li>
 *   <li>名单内的回调体<b>一律禁止</b>出现 {@code .getWorld(} / {@code .getLocation(}，不再有例外；</li>
 *   <li>扫源码前先剥掉注释与字符串字面量，避免 javadoc/注释里提到这些方法名造成误报。</li>
 * </ol>
 *
 * <p>另配一条完整性断言：凡是继承 {@code PacketAdapter} 的类，其 {@code onPacketSending} / {@code onPacketReceiving}
 * 必须已登记（新增监听器绕不过守门）。**本用例不设任何豁免通道**：确需放开时只能改代码，或在评审里单列并
 * 由评审人决定是否放宽规则——「临时豁免」会让守门静默失效（旧口径的「出现快照字样即免责」就是这么烂掉的）。
 */
class PacketThreadPurityTest {

  /** 主源码根（相对项目根，surefire 的工作目录即项目根）。 */
  private static final String SOURCE_ROOT = "src/main/java/net/mikumc/mikuxraynet/";

  /** 实体状态读取（网络线程禁用）：只认真正的调用形态，不误伤同名局部变量或注释（注释已先剥离）。 */
  private static final Pattern ENTITY_READ = Pattern.compile("\\.\\s*(getWorld|getLocation)\\s*\\(");

  /**
   * 必须完全干净的（文件, 方法）登记表：网络线程回调，及其同在网络线程上执行的辅助方法。
   *
   * <p>没有豁免通道（task-14 落地后 {@code AfkTracker#handleLowValuePacket} 与
   * {@code EntityPacketFilter#onPacketSending} 已改为「快照缺失即原包放行」，两条临时豁免随之删除）。
   */
  private static final Map<String, Set<String>> STRICT = new LinkedHashMap<>();

  static {
    strict("antixray/ProtocolLibAsyncListener.java",
        "onPacketSending", "handleChunk", "handleBatchStart", "handleBatchFinish", "appliesTo",
        "observeUpdatesWithoutRevealer", "onChunkWritten", "scheduleReplay", "boundContext");
    strict("antixray/BlockChangeRevealListener.java",
        "onPacketSending", "unregisterSingle", "unregisterMulti", "observeChange", "isOurRevealEcho",
        "recordChange", "worldNameOf", "exposes");
    strict("antixray/LeakFilterListener.java", "onPacketSending", "worldNameOf");
    strict("antixray/OcclusionPatchListener.java",
        "onPacketSending", "patchSingle", "patchMulti", "worldNameOf");
    strict("bandwidth/AfkTracker.java", "onPacketSending", "handleLowValuePacket");
    strict("bandwidth/BlockChangeMerger.java",
        "onPacketSending", "deliver", "trySendMerged", "flushInternal", "sendSection");
    strict("bandwidth/EntityPacketFilter.java", "onPacketSending");
  }

  @Test
  void packetCallbacksNeverReadEntityStateDirectly() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, Set<String>> entry : STRICT.entrySet()) {
      String file = entry.getKey();
      String code = readCode(Path.of(SOURCE_ROOT + file));
      for (String method : entry.getValue()) {
        String key = file + "#" + method;
        List<String> bodies = methodBodies(code, method);
        if (bodies.isEmpty()) {
          violations.add(key + "（登记的方法不存在：改签名后请同步本用例）");
          continue;
        }
        for (String body : bodies) {
          Matcher matcher = ENTITY_READ.matcher(body);
          if (matcher.find()) {
            violations.add(key + " -> " + matcher.group());
          }
        }
      }
    }
    assertTrue(violations.isEmpty(),
        "以下封包线程回调直接读取了实体世界/位置：改为读 PlayerWorldIndex/PlayerPositionSnapshots 快照，"
            + "并确认该路径在拿不到快照时按原包放行（见类注释）：" + violations);
  }

  /**
   * 完整性：任何继承 {@code PacketAdapter} 的类，其封包回调必须登记进 {@link #STRICT}。
   *
   * <p>没有这一条，新增一个监听器就完全绕过守门——旧的实现只按名字扫，天生看不见新类。
   */
  @Test
  void everyPacketAdapterCallbackIsRegistered() throws IOException {
    Set<String> registered = new LinkedHashSet<>();
    for (Map.Entry<String, Set<String>> entry : STRICT.entrySet()) {
      for (String method : entry.getValue()) {
        registered.add(entry.getKey() + "#" + method);
      }
    }
    List<String> missing = new ArrayList<>();
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      files.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
    }
    for (Path source : sources) {
      String code = readCode(source);
      if (!code.contains("extends PacketAdapter")) {
        continue;
      }
      String relative = Path.of("src/main/java").relativize(source).toString().replace('\\', '/');
      String keyPrefix = relative.substring("net/mikumc/mikuxraynet/".length());
      for (String callback : List.of("onPacketSending", "onPacketReceiving")) {
        if (methodBodies(code, callback).isEmpty()) {
          continue;
        }
        if (!registered.contains(keyPrefix + "#" + callback)) {
          missing.add(keyPrefix + "#" + callback);
        }
      }
    }
    assertTrue(missing.isEmpty(),
        "以下封包回调未登记到本守门用例（新增网络线程回调必须登记，否则守门形同虚设）：" + new TreeSet<>(missing));
  }

  /**
   * 钉住「每个 MAP_CHUNK 只做一次同步观察」，且观察结果随身携带世界快照。
   *
   * <p>依据（ProtocolLib 5.3.0 已核实）：{@code AsyncMarker#sendPacket} 以
   * {@code sendServerPacket(player, packet, NetworkMarker.getNetworkMarker(event), false)} 放行，末位的
   * {@code false} 表示「未过滤」——同步监听链会被<b>第二次</b>执行，同一个 {@code NetworkMarker} 再次抵达观察器。
   * 不早退就会重复开 journal ticket（顶掉旧的、一次性记账失效）、重复挂 adapter（{@code boundContext} 只能拿到
   * 其中一个），并把新 ticket 归属到「此刻」的世界——玩家恰在持包期间换维时就是旧世界包记到新世界账上。
   */
  @Test
  void chunkObservationRunsAtMostOncePerPacket() throws IOException {
    String code = readCode(Path.of(SOURCE_ROOT + "antixray/ProtocolLibAsyncListener.java"));
    String observer = null;
    for (String body : methodBodies(code, "onPacketSending")) {
      if (body.contains("observeUpdatesWithoutRevealer(")) {
        observer = body;
        break;
      }
    }
    assertNotNull(observer, "未找到区块写出观察器（deliveryObserver）的 onPacketSending 方法体");
    int guard = observer.indexOf("alreadyObserved(");
    int ticket = observer.indexOf("changeJournal.open(");
    int adapter = observer.indexOf("new ChunkDeliveryPostAdapter(");
    assertTrue(guard >= 0, "观察器必须先用 alreadyObserved(...) 早退，否则二次同步观察会重复记账");
    assertTrue(ticket > guard, "早退判定必须早于 changeJournal.open(...)，否则等于没早退");
    assertTrue(adapter > ticket, "adapter 必须在开 ticket 之后创建，并携带同一份世界快照");
  }

  private static void strict(String file, String... methods) {
    STRICT.computeIfAbsent(file, ignored -> new LinkedHashSet<>()).addAll(List.of(methods));
  }

  /** 读源码并剥掉注释/字符串字面量（等长替换，行号不变，便于定位）。 */
  private static String readCode(Path source) throws IOException {
    return stripNonCode(Files.readString(source));
  }

  /**
   * 把注释与字符串字面量的内容替换成空格（保留换行），使「注释里提到 getWorld()」或
   * 「日志文案里写着 getWorld()」不会被当成真实调用。三引号文本块一并按字符串处理。
   */
  static String stripNonCode(String source) {
    char[] chars = source.toCharArray();
    int index = 0;
    while (index < chars.length) {
      char current = chars[index];
      if (current == '/' && index + 1 < chars.length && chars[index + 1] == '/') {
        while (index < chars.length && chars[index] != '\n') {
          chars[index++] = ' ';
        }
      } else if (current == '/' && index + 1 < chars.length && chars[index + 1] == '*') {
        chars[index++] = ' ';
        chars[index++] = ' ';
        while (index < chars.length
            && !(chars[index] == '*' && index + 1 < chars.length && chars[index + 1] == '/')) {
          if (chars[index] != '\n') {
            chars[index] = ' ';
          }
          index++;
        }
        if (index + 1 < chars.length) {
          chars[index++] = ' ';
          chars[index++] = ' ';
        }
      } else if (current == '"' && index + 2 < chars.length
          && chars[index + 1] == '"' && chars[index + 2] == '"') {
        index = blankTextBlock(chars, index);
      } else if (current == '"' || current == '\'') {
        index = blankQuoted(chars, index, current);
      } else {
        index++;
      }
    }
    return new String(chars);
  }

  private static int blankTextBlock(char[] chars, int start) {
    int index = start;
    while (index < start + 3) {
      chars[index++] = ' ';
    }
    while (index < chars.length) {
      if (chars[index] == '"' && index + 2 < chars.length
          && chars[index + 1] == '"' && chars[index + 2] == '"') {
        chars[index++] = ' ';
        chars[index++] = ' ';
        chars[index++] = ' ';
        return index;
      }
      if (chars[index] != '\n') {
        chars[index] = ' ';
      }
      index++;
    }
    return index;
  }

  private static int blankQuoted(char[] chars, int start, char quote) {
    char[] literal = chars;
    int index = start;
    literal[index++] = ' ';
    while (index < literal.length && literal[index] != quote) {
      if (literal[index] == '\\') {
        literal[index++] = ' ';
        if (index < literal.length && literal[index] != '\n') {
          literal[index] = ' ';
        }
        index++;
        continue;
      }
      if (literal[index] != '\n') {
        literal[index] = ' ';
      }
      index++;
    }
    if (index < literal.length) {
      literal[index++] = ' ';
    }
    return index;
  }

  /** 取某方法名的<b>全部</b>声明体（含花括号配对）；未找到返回空表。 */
  static List<String> methodBodies(String code, String name) {
    Pattern declaration = Pattern.compile(
        "\\b(?:void|boolean|int|long|short|byte|char|float|double|[A-Z][\\w$.]*(?:<[^>]*>)?)\\s+"
            + Pattern.quote(name) + "\\s*\\(");
    Matcher matcher = declaration.matcher(code);
    List<String> bodies = new ArrayList<>();
    while (matcher.find()) {
      int brace = code.indexOf('{', matcher.start());
      if (brace < 0) {
        continue;
      }
      int depth = 0;
      for (int index = brace; index < code.length(); index++) {
        char current = code.charAt(index);
        if (current == '{') {
          depth++;
        } else if (current == '}') {
          depth--;
          if (depth == 0) {
            bodies.add(code.substring(brace, index + 1));
            break;
          }
        }
      }
    }
    return bodies;
  }
}
