package net.mikumc.mikuxraynet.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.junit.jupiter.api.Test;

/**
 * 补全回归：{@code /mxnet <TAB>} 必须给出全部子命令（真机反馈「TAB 补全不能用」即此情形）。
 *
 * <p>按「实际会被传进来的数组形态」逐个断言（本命令只有 Paper/Folia 的 Brigadier 一条注册路径，
 * 见 {@code CommandRegistrar}）：
 * <ul>
 *   <li>什么都没输入时可能是<b>空数组</b>（Paper 的 {@code StringUtils.split("")}）或 {@code [""]}
 *       （{@code TabCompleter} 历史约定），两者都必须补出全部子命令；</li>
 *   <li>输入了前缀时是 {@code [前缀]}，首个参数后跟空格时是 {@code [首个参数, ""]}。</li>
 * </ul>
 *
 * <p>另含转储失败回显的纯函数测试（{@code describeDumpFailure} 必须是单行且永远带原因）。
 */
class MikuCommandTest {

  @Test
  void emptyArgsFromPaperYieldsAllSubcommands() {
    assertEquals(List.of("status", "dump", "probe", "reload"),
        MikuCommand.suggestSubcommands(new String[0]),
        "Paper 在「只敲了 /mxnet」时传空数组，必须给出全部子命令");
    assertEquals(List.of("status", "dump", "probe", "reload"),
        MikuCommand.suggestSubcommands(new String[] {""}),
        "Bukkit 在同一情形传 [\"\"]，同样必须给出全部子命令");
    assertEquals(List.of("status", "dump", "probe", "reload"), MikuCommand.suggestSubcommands(null),
        "入参为 null 时不应抛异常（保守给出全部子命令）");
  }

  @Test
  void partialPrefixIsFilteredCaseInsensitively() {
    assertEquals(List.of("status"), MikuCommand.suggestSubcommands(new String[] {"st"}));
    assertEquals(List.of("status"), MikuCommand.suggestSubcommands(new String[] {"ST"}),
        "客户端可能保留大写，补全必须大小写不敏感");
    assertEquals(List.of("dump"), MikuCommand.suggestSubcommands(new String[] {"d"}));
    assertEquals(List.of("probe"), MikuCommand.suggestSubcommands(new String[] {"pr"}),
        "探针子命令同样要能被前缀补全");
    assertEquals(List.of("reload"), MikuCommand.suggestSubcommands(new String[] {"re"}));
    assertTrue(MikuCommand.suggestSubcommands(new String[] {"xyz"}).isEmpty(), "无匹配时必须返回空列表");
  }

  /** 本命令没有二级参数：首个参数之后不再补全（Paper 会传 ["status", ""]）。 */
  @Test
  void secondArgumentIsNotCompleted() {
    assertTrue(MikuCommand.suggestSubcommands(new String[] {"status", ""}).isEmpty());
    assertTrue(MikuCommand.suggestSubcommands(new String[] {"status", "x"}).isEmpty());
  }

  /** 补全列表必须与命令用法一致（usage 文案里的 status|dump|reload），否则会出现「补不出来但能执行」。 */
  @Test
  void completionsMatchTheDocumentedUsage() {
    assertEquals(List.of("status", "dump", "probe", "reload"),
        MikuCommand.suggestSubcommands(new String[0]),
        "补全项必须与 /mikuxraynet <status|dump|probe|reload> 的用法逐项一致");
  }

  /** 取不到发送者时无法判定权限：必须一个都不补，绝不能把管理命令暴露给无权限来源。 */
  @Test
  void nullSenderGetsNoCompletions() {
    assertTrue(MikuCommand.suggestSubcommands(null, new String[0]).isEmpty(),
        "sender 为 null 时不得补出任何子命令");
    assertTrue(MikuCommand.suggestSubcommands(null, new String[] {"s"}).isEmpty(),
        "sender 为 null 时也不得按前缀补出子命令");
  }

  /**
   * 转储失败的回显必须永远带原因、且只有一个异常类名兜底——绝不能出现「失败：null」。
   *
   * <p>回归背景：把转储挪到异步线程后，失败原因只能靠这条回显送到管理员眼前（游戏内看不到日志）。
   */
  @Test
  void dumpFailureAlwaysCarriesReason() {
    assertEquals("磁盘已满", MikuCommand.describeDumpFailure(new IOException("磁盘已满")),
        "有 message 时必须原样回显 message");
    assertEquals("NullPointerException", MikuCommand.describeDumpFailure(new NullPointerException()),
        "message 为 null 时回落到异常类名，绝不能回显 null");
    assertEquals("IllegalStateException", MikuCommand.describeDumpFailure(new IllegalStateException("   ")),
        "message 为空白时同样回落到异常类名");
    assertEquals("未知原因", MikuCommand.describeDumpFailure(null),
        "防御性分支：异常本身为 null 时也要给出明确文案");
  }

  /** 回显必须压成一行：异常消息里的换行不得把聊天框刷成多行，首尾空白要去掉。 */
  @Test
  void dumpFailureIsCollapsedToOneLine() {
    String line = MikuCommand.describeDumpFailure(new IOException("  第一行\n第二行\r\n第三行  "));
    assertFalse(line.contains("\n"), "回显不得包含换行符");
    assertFalse(line.contains("\r"), "回显不得包含回车符");
    assertTrue(line.startsWith("第一行"), "首尾空白必须去掉：" + line);
    assertTrue(line.endsWith("第三行"), "首尾空白必须去掉：" + line);
  }

  /**
   * 结构性自证：命令类不得再声称自己是 Bukkit 的 {@link CommandExecutor} / {@link TabCompleter}。
   *
   * <p>本项目只有 Paper/Folia 的 Brigadier 一条注册路径（{@code CommandRegistrar.PaperCommandBridge}
   * 直接转调 {@code onCommand} / {@code onTabComplete}）。保留那对接口只会让后来者误以为还存在传统
   * {@code plugin.yml} 注册路径（历史实现曾有，已随 plugin.yml 一并删除）。
   */
  @Test
  void isNotALegacyBukkitCommandExecutor() {
    assertFalse(CommandExecutor.class.isAssignableFrom(MikuCommand.class),
        "MikuCommand 不得再 implements CommandExecutor（唯一注册路径是 Paper 桥接）");
    assertFalse(TabCompleter.class.isAssignableFrom(MikuCommand.class),
        "MikuCommand 不得再 implements TabCompleter（唯一补全路径是 Paper 桥接）");
  }
}