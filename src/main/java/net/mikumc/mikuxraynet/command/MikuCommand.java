package net.mikumc.mikuxraynet.command;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.mikumc.mikuxraynet.MikuXrayNet;
import net.mikumc.mikuxraynet.util.Diagnostics;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * {@code /mikuxraynet <status|dump|reload>} 管理命令（别名 {@code mxnet} / {@code mxr}）。
 *
 * <p>命令本身只做「权限校验 + 调一次聚合/重载 + 回显中文结果」，不做任何重活：
 * 状态与转储的指标聚合、文件写入都交给 {@link Diagnostics}；热重载交给插件的
 * {@code reloadConfigs()}。任何异常都只记日志并回显一句中文提示，绝不因命令抛异常影响封包主链路。
 */
public final class MikuCommand implements CommandExecutor, TabCompleter {

  private static final String PERMISSION_STATUS = "mikuxraynet.status";
  private static final String PERMISSION_DUMP = "mikuxraynet.dump";
  private static final String PERMISSION_RELOAD = "mikuxraynet.reload";
  private static final List<String> SUBCOMMANDS = List.of("status", "dump", "reload");

  private final MikuXrayNet plugin;
  private final Diagnostics diagnostics;

  public MikuCommand(MikuXrayNet plugin) {
    this.plugin = plugin;
    this.diagnostics = new Diagnostics(plugin);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      usage(sender, label);
      return true;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "status" -> status(sender);
      case "dump" -> dump(sender);
      case "reload" -> reload(sender);
      default -> usage(sender, label);
    }
    return true;
  }

  private void status(CommandSender sender) {
    if (!sender.hasPermission(PERMISSION_STATUS)) {
      message(sender, "你没有权限查看运行状态");
      return;
    }
    try {
      for (String line : diagnostics.statusLines()) {
        message(sender, line);
      }
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "生成运行状态失败", throwable);
      message(sender, "生成运行状态失败，详见服务端日志");
    }
  }

  private void dump(CommandSender sender) {
    if (!sender.hasPermission(PERMISSION_DUMP)) {
      message(sender, "你没有权限导出诊断");
      return;
    }
    try {
      File file = diagnostics.writeDump();
      message(sender, "诊断转储已写入：" + file.getAbsolutePath());
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "导出诊断转储失败", throwable);
      message(sender, "导出诊断转储失败，详见服务端日志");
    }
  }

  private void reload(CommandSender sender) {
    if (!sender.hasPermission(PERMISSION_RELOAD)) {
      message(sender, "你没有权限热重载配置");
      return;
    }
    try {
      for (String line : plugin.reloadConfigs()) {
        message(sender, line);
      }
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "热重载配置失败", throwable);
      message(sender, "热重载配置失败，详见服务端日志（原配置与运行状态未受影响）");
    }
  }

  private void usage(CommandSender sender, String label) {
    message(sender, "用法：/" + label + " <status|dump|reload>");
    message(sender, "status 查看运行状态｜dump 导出诊断文件｜reload 热重载配置");
  }

  /**
   * 向发送者回显一行文本（Paper 原生 Adventure {@link Component}，取代已废弃的 {@code sendMessage(String)}）。
   *
   * <p>仅做 {@code Component.text(text)} 包装，文本内容与旧实现逐字一致（不解析颜色/迷你消息标记，
   * 避免已有文案中的字符被误当格式符）。控制台日志仍走 {@code getLogger()}，不在此列。
   */
  private static void message(CommandSender sender, String text) {
    sender.sendMessage(Component.text(text));
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    return suggestSubcommands(args);
  }

  /**
   * 子命令补全（纯函数，可离线单测）。
   *
   * <p><b>为什么要兼容两种调用约定</b>：本命令在 Paper/Folia 上走 Brigadier（{@code BasicCommand#suggest}），
   * 在纯 Bukkit/Spigot 上走传统 {@link TabCompleter}，两者对「只敲了命令、还没输入任何字符」这一情形的
   * 入参不同：
   * <ul>
   *   <li>Paper：{@code PaperCommands#register(label, description, aliases, basicCommand)} 里
   *       {@code String[] args = StringUtils.split(suggestionsBuilder.getRemaining())}，
   *       此时 remaining 为空串 → 传入<b>长度 0 的数组</b>（源码见 paper-server 的 PaperCommands）；</li>
   *   <li>Bukkit：{@code args} 的最后一项是「正在输入的那一段」，空前缀时即 {@code [""]}，
   *       且长度始终 ≥ 1（所以下面按「长度 0 或 1 = 正在输入第一个参数」归一化）。</li>
   * </ul>
   * 真机反馈的「TAB 补全不能用」正是前者：旧实现在长度不为 1 时直接返回空列表，
   * 于是 Paper/Folia 上 {@code /mxnet <TAB>} 一个候选都出不来（输入了首字母反而有）。
   *
   * <p>权限不在这里过滤：命令本身要求 {@code mikuxraynet.admin}，各子命令的权限在真正执行时判定，
   * 补全只做「有哪些子命令」这一件事（与旧行为一致）。
   */
  static List<String> suggestSubcommands(String[] args) {
    if (args != null && args.length > 1) {
      // 已有第二个参数：本命令没有二级参数，不补全（Paper 在「首个参数后跟空格」时会传 ["status", ""]）
      return List.of();
    }
    String prefix = args == null || args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>(SUBCOMMANDS.size());
    for (String sub : SUBCOMMANDS) {
      if (sub.startsWith(prefix)) {
        matches.add(sub);
      }
    }
    return matches;
  }
}