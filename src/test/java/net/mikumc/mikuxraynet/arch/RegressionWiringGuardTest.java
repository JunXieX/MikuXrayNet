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

  // ------------------------------------------------- Spark 转储会话接线

  /**
   * 默认 dump 必须在<b>同一个全局区域 tick</b> 里先起 Spark、再起数值采样。
   *
   * <p>为什么这也要守门：{@code SparkDumpCapture#start} 的契约是「在全局区域拥有线程调用」，而玩家执行
   * 命令时命令线程是他所属的区域线程。把两路各投一处（一个 {@code run}、一个 {@code runAtFixedRate}）
   * 不会报错，只会让「同一窗口」这个承诺变成「差不多同一窗口」——这种偏差在任何行为用例里都看不出来。
   */
  @Test
  void sparkAndSamplingStartInTheSameGlobalRegionTick() throws IOException {
    String body = methodBody(readSource("command/MikuCommand.java"), "begin");
    assertTrue(body != null, "begin 必须存在");
    assertTrue(body.contains("getGlobalRegionScheduler"),
        "两路必须一起投到全局区域线程：Spark start 的契约就是在全局区域拥有线程上调用");
    int spark = body.indexOf("startSpark()");
    int sampling = body.indexOf("startNumericSampling()");
    assertTrue(spark >= 0 && sampling >= 0, "begin 内必须同时启动 Spark 与数值采样");
    assertTrue(spark < sampling, "先起 Spark 再起采样：反过来会让两端的起点差出一个 tick");
  }

  /** 写盘只发生一次，且一定在 AsyncScheduler 上（区域线程/命令线程不做同步 IO）。 */
  @Test
  void dumpWritesExactlyOnceAndOffTheRegionThread() throws IOException {
    String source = readSource("command/MikuCommand.java");
    String maybeFinish = methodBody(source, "maybeFinish");
    assertTrue(maybeFinish != null, "maybeFinish 必须存在");
    assertTrue(maybeFinish.contains("finished.compareAndSet(false, true)"),
        "汇合必须用一次性 CAS 收口：Spark 结果与数值窗口任一先到都会触发，重复执行就会写出第二份文件");
    String writeOnce = methodBody(source, "writeOnce");
    assertTrue(writeOnce != null, "writeOnce 必须存在");
    assertTrue(writeOnce.contains("getAsyncScheduler"),
        "写盘必须投到 AsyncScheduler：命令线程与区域线程都不允许阻塞 IO");
    assertTrue(writeOnce.contains("writeDump("), "写盘必须走 Diagnostics#writeDump（唯一出口）");
  }

  /** 等 Spark 只能靠回调 + 异步超时，绝不在任何线程上 join/sleep/await。 */
  @Test
  void dumpNeverBlocksWaitingForSpark() throws IOException {
    String source = stripComments(readSource("command/MikuCommand.java"));
    assertFalse(source.contains(".join("), "不得用 CompletionStage#join 同步等待 Spark");
    assertFalse(source.contains("Thread.sleep"), "不得 sleep 等 Spark");
    assertFalse(source.contains("awaitTermination"), "不得等待线程池收尾");
    assertTrue(source.contains("whenComplete"), "结果必须走回调汇合（否则只能在某个线程上阻塞等）");
  }

  /** Spark 链接只回给发起人：不广播、不遍历在线玩家。 */
  @Test
  void sparkLinkIsOnlyEverSentToTheRequester() throws IOException {
    String source = stripComments(readSource("command/MikuCommand.java"));
    assertFalse(source.contains("broadcast("), "链接绝不广播");
    assertFalse(source.contains("getOnlinePlayers"), "不得遍历在线玩家（等价于广播）");
    assertTrue(source.contains("replyToSender(sender, reply)"),
        "转储结果（含链接）只能回给发起人本人");
  }

  /** 「会上传」必须在开始采样<b>之前</b>说出口。 */
  @Test
  void uploadNoticeIsToldBeforeSamplingStarts() throws IOException {
    String source = stripComments(readSource("command/MikuCommand.java"));
    assertTrue(source.contains("SPARK_UPLOAD_NOTICE"),
        "口径提示必须由命令自己拼出来，不能只写在 README 里");
    String announce = methodBody(source, "announce");
    assertTrue(announce != null && announce.contains("SPARK_UPLOAD_NOTICE"),
        "开始采样前就要告知「Spark 记录会上传、dump 留本地」——事后再在文件里发现是不可接受的");
  }

  /** 停用 / 热重载 / 发起者断线三条路径都要能中断会话并放闸门。 */
  @Test
  void dumpSessionIsAbortedOnDisableReloadAndQuit() throws IOException {
    String plugin = stripComments(readSource("MikuXrayNet.java"));
    int first = plugin.indexOf("abortDumpSession(");
    int second = first < 0 ? -1 : plugin.indexOf("abortDumpSession(", first + 1);
    assertTrue(first >= 0 && second > first,
        "停用与热重载都必须中断在途会话：否则闸门被占到超时，且异步写盘会落在已停用的插件上");
    String registrar = stripComments(readSource("CommandRegistrar.java"));
    assertTrue(registrar.contains("setDumpAbortHook("), "生命周期钩子必须在注册命令时接上");
    assertTrue(registrar.contains("PlayerQuitEvent"), "发起者断线必须能触发回收");
    assertTrue(registrar.contains("onSenderDisconnect("), "断线事件必须转交给命令层的会话注销入口");
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
    String source = readSource("antixray/ProximityRevealer.java");
    String body = methodBody(source, "revealDigTarget");
    assertTrue(body != null, "revealDigTarget 必须存在");
    int sight = body.indexOf("canSeeDugBlock(");
    int send = body.indexOf("sendOne(");
    assertTrue(sight >= 0, "开掘预显形必须先做视线判定：服务端对 START_DESTROY_BLOCK 只校验交互距离，不校验视线");
    assertTrue(send > sight, "视线判定必须早于发包，否则伪造破坏包就能换到真实方块状态");
    // 判定实现本身仍必须是方块射线（不能退化成「按距离/按声明」），见 RevealDeliveryGuardTest 的双射线要求
    String sightBody = methodBody(source, "canSeeDugBlock");
    assertTrue(sightBody != null && sightBody.contains("rayTraceBlocks"),
        "canSeeDugBlock 必须用方块射线判定视线");
  }

  @Test
  void miningFrontUsesLiveGeometryInsteadOfLastTicksBitmap() throws IOException {
    String source = readSource("antixray/ProximityRevealer.java");
    String body = methodBody(source, "revealMiningFront");
    int trace = body.indexOf("visibleMiningFace(");
    assertTrue(trace >= 0 && trace < body.indexOf("sendOne("));
    assertTrue(body.contains("block.getX() != bx") && body.contains("block.getY() != by")
        && body.contains("block.getZ() != bz"), "仅忽略即将破坏的一格");
    assertFalse(body.contains("sameSectionAsOwnChange"));
    String visibility = methodBody(source, "visibleMiningFace");
    assertTrue(visibility.contains("rayChunksAvailable(") && visibility.contains("hitsBlock("));
  }
}