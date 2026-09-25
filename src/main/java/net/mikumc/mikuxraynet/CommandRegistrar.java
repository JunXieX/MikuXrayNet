package net.mikumc.mikuxraynet;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.command.MikuCommand;
import org.bukkit.command.PluginCommand;

/**
 * 管理命令注册器：把 {@code /mikuxraynet}（别名 {@code mxnet} / {@code mxr}）注册到服务端。
 *
 * <p><b>现代路径（Paper / Folia）</b>：通过 Paper 生命周期事件 {@link LifecycleEvents#COMMANDS} 注册
 * Brigadier 命令（Paper 官方推荐做法）。paper-plugin.yml 下 Paper 不会从 YAML 注册任何命令
 * （PaperPluginClassLoader#init 以 Map.of() 填充 PluginDescriptionFile#commands），故命令只能由代码注册。
 *
 * <p><b>回退路径（纯 Bukkit/Spigot 或老核心）</b>：这些核心没有 {@code Plugin#getLifecycleManager()}，
 * 调用会抛 {@link NoSuchMethodError}；此时退回按 plugin.yml 的 commands 段取回 {@link PluginCommand}
 * 并绑定执行器（命令名 / 别名 / 权限 / 补全行为完全一致）。
 *
 * <p><b>守门声明</b>：下面的 {@link #COMMAND_NAME} / {@link #COMMAND_ALIASES} /
 * {@link #COMMAND_PERMISSION} 必须与两份插件描述（plugin.yml 与 paper-plugin.yml）的 commands 段
 * 逐字一致，由 {@code PluginDescriptionConsistencyTest} 守门（含「代码常量 ↔ 两份 yml」的对照断言）。
 */
final class CommandRegistrar {

  /** 管理命令名（两份插件描述与代码注册三处必须一致，由 PluginDescriptionConsistencyTest 守门）。 */
  static final String COMMAND_NAME = "mikuxraynet";
  static final String COMMAND_DESCRIPTION = "MikuXrayNet 管理命令";
  static final String COMMAND_PERMISSION = "mikuxraynet.admin";
  static final List<String> COMMAND_ALIASES = List.of("mxnet", "mxr");

  private final MikuXrayNet plugin;

  CommandRegistrar(MikuXrayNet plugin) {
    this.plugin = plugin;
  }

  /** 注册管理命令（执行器与补全器）。 */
  void register() {
    try {
      MikuCommand executor = new MikuCommand(plugin);
      if (registerLifecycleCommand(executor)) {
        plugin.getLogger().info("管理命令 /" + COMMAND_NAME + " 已按 paper-plugin.yml 现代方式注册");
        return;
      }
      PluginCommand legacyCommand = legacyPluginCommand();
      if (legacyCommand != null) {
        legacyCommand.setExecutor(executor);
        legacyCommand.setTabCompleter(executor);
        plugin.getLogger().info("管理命令 /" + COMMAND_NAME + " 已按 plugin.yml 传统方式注册");
        return;
      }
      plugin.getLogger().warning("注册管理命令失败（管理命令不可用）");
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注册管理命令失败（管理命令不可用）", throwable);
    }
  }

  /**
   * 现代路径：在生命周期事件 {@code COMMANDS} 中注册命令（Paper 与 Folia 是同一套 API）。
   *
   * <p>返回 {@code false} 表示当前核心不是 Paper（{@code Plugin#getLifecycleManager()} 不可用），
   * 由调用方退回 plugin.yml 传统路径。注意求值顺序：{@code getLifecycleManager()} 先于 lambda 求值，
   * 因此非 Paper 核心上根本不会加载 {@link LifecycleEvents} / {@link Commands} / {@link BasicCommand} 等类。
   */
  private boolean registerLifecycleCommand(MikuCommand executor) {
    try {
      plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
        Commands commands = event.registrar();
        commands.register(COMMAND_NAME, COMMAND_DESCRIPTION, COMMAND_ALIASES,
            new PaperCommandBridge(executor));
      });
      return true;
    } catch (Throwable throwable) {
      // 纯 Bukkit/Spigot：Plugin#getLifecycleManager 不存在（NoSuchMethodError），改走传统路径
      return false;
    }
  }

  /**
   * 取传统描述（plugin.yml）注册的命令；现代描述（paper-plugin.yml）下无此类命令。
   *
   * <p>Paper 对「paper 插件」在 onEnable 内调用 {@code getCommand} 会直接抛
   * {@link UnsupportedOperationException}，并明确提示 paper 插件不支持 YAML 命令声明，
   * 因此把它当作「无传统命令」处理，转走现代注册路径。
   */
  private PluginCommand legacyPluginCommand() {
    try {
      return plugin.getCommand(COMMAND_NAME);
    } catch (UnsupportedOperationException unsupported) {
      return null;
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "查询传统命令时出现异常", throwable);
      return null;
    }
  }

  /**
   * Bukkit 执行器 → Paper Brigadier 命令的适配层。
   *
   * <p>两条路径复用同一份 {@link MikuCommand} 逻辑，避免出现两套命令实现而漂移；
   * 子命令的权限与中文回显都在 MikuCommand 内部判定，这里只透传发送者与参数。
   */
  private static final class PaperCommandBridge implements BasicCommand {

    private final MikuCommand delegate;

    PaperCommandBridge(MikuCommand delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
      delegate.onCommand(source.getSender(), null, COMMAND_NAME, args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
      // 入参约定（Paper 源码 paper-server：PaperCommands#register(label, description, aliases, basicCommand)）：
      //   args = StringUtils.split(suggestionsBuilder.getRemaining())，且 remaining 以空格结尾时补一个空串。
      // 因此「只敲了 /mxnet、还没输入字符」时 args 是空数组（传统 Bukkit TabCompleter 那里是 [""]）；
      // 归一化交给 MikuCommand#suggestSubcommands 做，两条注册路径共用同一份补全语义。
      List<String> suggestions = delegate.onTabComplete(source.getSender(), null, COMMAND_NAME, args);
      return suggestions == null ? List.of() : suggestions;
    }

    @Override
    public String permission() {
      return COMMAND_PERMISSION;
    }
  }
}
