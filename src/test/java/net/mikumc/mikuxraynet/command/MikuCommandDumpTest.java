package net.mikumc.mikuxraynet.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import net.mikumc.mikuxraynet.util.Constants;
import net.mikumc.mikuxraynet.util.SparkDumpCapture;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * {@code /mxnet dump [local]} 的口径与补全回归。
 *
 * <p><b>为什么这些必须是纯函数用例</b>：真正的执行链路要 Bukkit 调度器、Spark 采样与磁盘，
 * 离线造不出来；但「第二参数认什么」「补全给什么」「回显哪几行」三件事全是纯判定，改错一次就会让
 * 管理员以为自己做的是本地采样、实际却上传了性能记录。把它们钉在用例里，比写在注释里可靠。
 */
class MikuCommandDumpTest {

  // ------------------------------------------------------------ 参数解析

  /** 不给参数 = 默认口径（请求 Spark + 同窗采样）。 */
  @Test
  void noSecondArgumentMeansSparkWindow() {
    assertEquals(MikuCommand.DumpMode.SPARK, MikuCommand.dumpMode(new String[] {"dump"}));
    assertEquals(MikuCommand.DumpMode.SPARK, MikuCommand.dumpMode(new String[] {"dump", ""}),
        "paper 的 BasicCommand 在「dump 后面跟空格」时会传空串，必须按默认口径处理");
    assertEquals(MikuCommand.DumpMode.SPARK, MikuCommand.dumpMode(null));
  }

  /** local 是唯一的第二参数，大小写与前导/尾随空白都要认（客户端会补空格）。 */
  @Test
  void localIsTheOnlyAcceptedSecondArgument() {
    assertEquals(MikuCommand.DumpMode.LOCAL, MikuCommand.dumpMode(new String[] {"dump", "local"}));
    assertEquals(MikuCommand.DumpMode.LOCAL, MikuCommand.dumpMode(new String[] {"dump", "LOCAL"}));
    assertEquals(MikuCommand.DumpMode.LOCAL, MikuCommand.dumpMode(new String[] {"dump", " local "}));
  }

  /** 写错第二参数必须判非法（回用法行），绝不静默按默认口径上传。 */
  @Test
  void unknownOrExtraArgumentsAreRejected() {
    assertEquals(MikuCommand.DumpMode.INVALID, MikuCommand.dumpMode(new String[] {"dump", "spark"}));
    assertEquals(MikuCommand.DumpMode.INVALID, MikuCommand.dumpMode(new String[] {"dump", "--local"}));
    assertEquals(MikuCommand.DumpMode.INVALID,
        MikuCommand.dumpMode(new String[] {"dump", "local", "x"}),
        "多给参数必须判非法：第三个参数目前没有语义，静默忽略会让「我明明加了参数」变成谜");
  }

  // -------------------------------------------------------------- 补全

  /** 第二参数补全只出 local；前缀匹配大小写不敏感。 */
  @Test
  void secondArgumentSuggestsOnlyLocal() {
    assertEquals(List.of("local"), MikuCommand.suggestDumpArguments(new String[] {"dump", ""}));
    assertEquals(List.of("local"), MikuCommand.suggestDumpArguments(new String[] {"dump", "l"}));
    assertEquals(List.of("local"), MikuCommand.suggestDumpArguments(new String[] {"dump", "LO"}));
    assertTrue(MikuCommand.suggestDumpArguments(new String[] {"dump", "x"}).isEmpty(),
        "前缀不匹配时必须返回空列表");
    assertTrue(MikuCommand.suggestDumpArguments(new String[] {"status", ""}).isEmpty(),
        "只有 dump 有第二参数");
    assertTrue(MikuCommand.suggestDumpArguments(new String[] {"dump", "local", ""}).isEmpty(),
        "第三参数不补全（也没有语义）");
    assertTrue(MikuCommand.suggestDumpArguments(null).isEmpty(), "入参为 null 时不得抛异常");
  }

  /** 第二参数补全必须先过 dump 权限：补出一个执行时会被拒的候选，比不补更糟。 */
  @Test
  void secondArgumentIsGatedByDumpPermission() {
    assertEquals(List.of("local"),
        MikuCommand.suggestSubcommands(sender(Constants.DUMP_PERMISSION), new String[] {"dump", ""}));
    assertEquals(List.of("local"),
        MikuCommand.suggestSubcommands(sender(Constants.ADMIN_PERMISSION), new String[] {"dump", ""}),
        "总权限必须等效放行（与执行侧同一判据）");
    assertTrue(MikuCommand.suggestSubcommands(sender(Constants.STATUS_PERMISSION),
        new String[] {"dump", ""}).isEmpty(), "只授 status 时不得补出 dump 的参数");
    assertTrue(MikuCommand.suggestSubcommands(null, new String[] {"dump", ""}).isEmpty(),
        "取不到发送者时不补全");
  }

  /** 一级补全不受影响：dump [local] 之后仍然只有那四个子命令。 */
  @Test
  void firstLevelSuggestionsAreUnchanged() {
    assertEquals(List.of("status", "dump", "probe", "reload"),
        MikuCommand.suggestSubcommands(sender(Constants.ADMIN_PERMISSION), new String[0]));
    assertEquals(List.of("dump"),
        MikuCommand.suggestSubcommands(sender(Constants.DUMP_PERMISSION), new String[] {"d"}));
  }

  // ------------------------------------------------------------ 回显口径

  /** 只把带链接的行回给发起人：状态与说明留在文件里，聊天框不该被刷成五段。 */
  @Test
  void onlyLinkedLinesAreEchoedBack() {
    String section = """
        ---- Spark 采样 ----
        窗口：30 秒（--thread * --interval 10）
        状态：已上传
        完整链接：https://spark.lucko.me/AbCdEf1234
        说明：内置后台采样器已停止，可用 /spark profiler start 恢复
        """;
    assertEquals(List.of("完整链接：https://spark.lucko.me/AbCdEf1234"),
        MikuCommand.sparkReplyLines(section));
  }

  /** 没有链接（未请求 / 忙 / 超时 / 失败）时一行都不回显：原因已经写进文件。 */
  @Test
  void sectionsWithoutLinkProduceNoEcho() {
    assertTrue(MikuCommand.sparkReplyLines(
        "---- Spark 采样 ----\n状态：未执行：已有 Spark 采样在进行（未打断）").isEmpty());
    assertTrue(MikuCommand.sparkReplyLines("---- Spark 采样 ----\n超时：等待 65 秒仍未取到结果").isEmpty());
    assertTrue(MikuCommand.sparkReplyLines(null).isEmpty());
    assertTrue(MikuCommand.sparkReplyLines("   ").isEmpty());
  }

  // -------------------------------------------------------------- 常量

  /** 两个窗口与上限必须与文档/README 的口径一致（30 / 110 / 10）。 */
  @Test
  void windowConstantsMatchTheDocumentedContract() {
    assertEquals(30, MikuCommand.SPARK_WINDOW_SECONDS, "默认窗口必须与 Spark profile 同为 30 秒");
    assertEquals(SparkDumpCapture.MAX_WAIT_SECONDS, MikuCommand.SPARK_MAX_WAIT_SECONDS,
        "总等待上限必须与后端一致（引用同一常量，避免两处各写一份数字漂移）");
    assertEquals(10, MikuCommand.LOCAL_WINDOW_SECONDS, "dump local 保持原来的 10 秒");
    assertTrue(MikuCommand.SPARK_MAX_WAIT_SECONDS > MikuCommand.SPARK_WINDOW_SECONDS,
        "上限必须大于窗口，否则正常采样也会被判超时");
  }

  /** 上传提示必须同时写清「会上传」与「dump 留本地」——少一半就是误导。 */
  @Test
  void uploadNoticeStatesBothHalves() {
    assertTrue(MikuCommand.SPARK_UPLOAD_NOTICE.contains("会上传至 Spark"),
        MikuCommand.SPARK_UPLOAD_NOTICE);
    assertTrue(MikuCommand.SPARK_UPLOAD_NOTICE.contains("留本地"), MikuCommand.SPARK_UPLOAD_NOTICE);
    assertTrue(MikuCommand.SPARK_UPLOAD_NOTICE.contains("dump"),
        "必须点名「dump 本身」留本地，否则读者会以为整个转储都不外发");
  }

  /** 权限桩：只回答 {@code hasPermission(String)}，其余方法返回安全默认值。 */
  private static CommandSender sender(String... granted) {
    Set<String> nodes = Set.of(granted);
    return (CommandSender) Proxy.newProxyInstance(MikuCommandDumpTest.class.getClassLoader(),
        new Class<?>[] {CommandSender.class}, (proxy, method, args) -> {
          switch (method.getName()) {
            case "hasPermission":
              return args != null && args.length == 1 && nodes.contains(String.valueOf(args[0]));
            case "getName":
              return "StubSender";
            case "toString":
              return "StubSender";
            case "hashCode":
              return System.identityHashCode(proxy);
            case "equals":
              return proxy == args[0];
            default:
              Class<?> returnType = method.getReturnType();
              if (returnType == boolean.class) {
                return false;
              }
              if (returnType == int.class) {
                return 0;
              }
              if (returnType == long.class) {
                return 0L;
              }
              return null;
          }
        });
  }
}
