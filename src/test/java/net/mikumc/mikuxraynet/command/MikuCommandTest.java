package net.mikumc.mikuxraynet.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 补全回归：{@code /mxnet <TAB>} 必须给出全部子命令（真机反馈「TAB 补全不能用」即此情形）。
 *
 * <p>两条注册路径的入参约定不同，因此这里按「实际会被传进来的数组形态」逐个断言：
 * <ul>
 *   <li>Paper/Folia（{@code BasicCommand#suggest}，见 paper-server 的 PaperCommands）：什么都没输入时是
 *       <b>空数组</b>，输入了前缀时是 {@code [前缀]}，首个参数后跟空格时是 {@code [首个参数, ""]}；</li>
 *   <li>纯 Bukkit（传统 {@code TabCompleter}）：空前缀是 {@code [""]}，其余同上。</li>
 * </ul>
 */
class MikuCommandTest {

  @Test
  void emptyArgsFromPaperYieldsAllSubcommands() {
    assertEquals(List.of("status", "dump", "reload"), MikuCommand.suggestSubcommands(new String[0]),
        "Paper 在「只敲了 /mxnet」时传空数组，必须给出全部子命令");
    assertEquals(List.of("status", "dump", "reload"), MikuCommand.suggestSubcommands(new String[] {""}),
        "Bukkit 在同一情形传 [\"\"]，同样必须给出全部子命令");
    assertEquals(List.of("status", "dump", "reload"), MikuCommand.suggestSubcommands(null),
        "入参为 null 时不应抛异常（保守给出全部子命令）");
  }

  @Test
  void partialPrefixIsFilteredCaseInsensitively() {
    assertEquals(List.of("status"), MikuCommand.suggestSubcommands(new String[] {"st"}));
    assertEquals(List.of("status"), MikuCommand.suggestSubcommands(new String[] {"ST"}),
        "客户端可能保留大写，补全必须大小写不敏感");
    assertEquals(List.of("dump"), MikuCommand.suggestSubcommands(new String[] {"d"}));
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
    assertEquals(List.of("status", "dump", "reload"), MikuCommand.suggestSubcommands(new String[0]),
        "补全项必须与 /mikuxraynet <status|dump|reload> 的用法逐项一致");
  }
}