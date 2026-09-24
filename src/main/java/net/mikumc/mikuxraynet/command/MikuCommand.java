package net.mikumc.mikuxraynet.command;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
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
      sender.sendMessage("你没有权限查看运行状态");
      return;
    }
    try {
      for (String line : diagnostics.statusLines()) {
        sender.sendMessage(line);
      }
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "生成运行状态失败", throwable);
      sender.sendMessage("生成运行状态失败，详见服务端日志");
    }
  }

  private void dump(CommandSender sender) {
    if (!sender.hasPermission(PERMISSION_DUMP)) {
      sender.sendMessage("你没有权限导出诊断");
      return;
    }
    try {
      File file = diagnostics.writeDump();
      sender.sendMessage("诊断转储已写入：" + file.getAbsolutePath());
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "导出诊断转储失败", throwable);
      sender.sendMessage("导出诊断转储失败，详见服务端日志");
    }
  }

  private void reload(CommandSender sender) {
    if (!sender.hasPermission(PERMISSION_RELOAD)) {
      sender.sendMessage("你没有权限热重载配置");
      return;
    }
    try {
      for (String line : plugin.reloadConfigs()) {
        sender.sendMessage(line);
      }
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "热重载配置失败", throwable);
      sender.sendMessage("热重载配置失败，详见服务端日志（原配置与运行状态未受影响）");
    }
  }

  private void usage(CommandSender sender, String label) {
    sender.sendMessage("用法：/" + label + " <status|dump|reload>");
    sender.sendMessage("status 查看运行状态｜dump 导出诊断文件｜reload 热重载配置");
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length != 1) {
      return List.of();
    }
    String prefix = args[0].toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (String sub : SUBCOMMANDS) {
      if (sub.startsWith(prefix)) {
        matches.add(sub);
      }
    }
    return matches;
  }
}