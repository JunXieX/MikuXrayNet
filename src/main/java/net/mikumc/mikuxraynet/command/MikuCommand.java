package net.mikumc.mikuxraynet.command;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.mikumc.mikuxraynet.MikuXrayNet;
import net.mikumc.mikuxraynet.bandwidth.Schedulers;
import net.mikumc.mikuxraynet.util.Constants;
import net.mikumc.mikuxraynet.util.Diagnostics;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;

/**
 * {@code /mikuxraynet <status|dump|reload>} 管理命令（别名 {@code mxnet} / {@code mxr}）。
 *
 * <p>命令本身只做「权限校验 + 提交一次聚合/重载 + 回显中文结果」，不做任何重活：
 * 状态与转储的指标聚合、文件写入都交给 {@link Diagnostics}；热重载交给插件的
 * {@code reloadConfigs()}。任何异常都只记日志并回显一句中文提示，绝不因命令抛异常影响封包主链路。
 *
 * <p><b>线程纪律（转储）</b>：命令线程在 Folia 上是发送者所属的<b>区域线程</b>、在 Paper 上是主线程，
 * 两者都不能做磁盘 IO。因此 {@code dump} 只提交任务，真正的「生成文本 + 写文件 + 清理旧文件」
 * 跑在异步线程（{@code AsyncScheduler}）上，完成后回到发送者所在线程回显（见 {@link #dump(CommandSender)}）。
 *
 * <p><b>不是 Bukkit 的 PluginCommand 执行器 / 补全器</b>：本类<b>不</b> implements
 * {@code CommandExecutor} / {@code TabCompleter}——唯一的调用方是 {@code CommandRegistrar.PaperCommandBridge}
 * （直接转调 {@link #onCommand} / {@link #onTabComplete}），项目已无 {@code plugin.yml} 传统注册路径。
 * 这两个方法仍保留为 public，是因为桥接需要「命令逻辑 / 补全语义各有单一入口」；不要据此以为还存在
 * 第二条注册路径（历史实现曾有，已随 plugin.yml 一并删除）。
 */
public final class MikuCommand {

  /**
   * 三个子命令的权限节点统一取自 {@link Constants}（唯一出处）。
   *
   * <p>此前是写在本类里的私有字面量，与 {@code paper-plugin.yml} 的 {@code permissions} 段之间
   * 没有任何构建期对照——改错权限名（例如大小写写错）不会报错，却会让对应子命令永远无权可用。
   */
  private static final String PERMISSION_STATUS = Constants.STATUS_PERMISSION;
  private static final String PERMISSION_DUMP = Constants.DUMP_PERMISSION;
  private static final String PERMISSION_RELOAD = Constants.RELOAD_PERMISSION;
  private static final List<String> SUBCOMMANDS = List.of("status", "dump", "reload");
  /** 无任何子命令权限时的统一回显（等价于命令描述里声明的 permission-message）。 */
  private static final String NO_PERMISSION_MESSAGE = "你没有权限使用该命令";

  private final MikuXrayNet plugin;
  private final Diagnostics diagnostics;

  public MikuCommand(MikuXrayNet plugin) {
    this.plugin = plugin;
    this.diagnostics = new Diagnostics(plugin);
  }

  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    // 整段包在 try 内：usage 提示此前在任何保护之外，一旦回显失败（例如在不支持 Adventure 的核心上）
    // 会从命令分发器二次抛出。现在任何异常都只记日志并尽力回显一句中文提示，绝不外抛。
    try {
      // 权限闸门在这里（而不是 Brigadier 的 permission()）：根命令若在框架层要求 mikuxraynet.admin，
      // 则只授予 mikuxraynet.status 的玩家会被**框架**直接拒绝，三个子权限形同虚设。
      // 改为「命令本身不设框架级权限、逐子命令判权」，子权限才可单独授予；admin 仍可用是因为
      // paper-plugin.yml 里 admin 的 children 已包含这三个节点（授予 admin 即同时授予三者）。
      if (!hasAnySubPermission(sender)) {
        message(sender, NO_PERMISSION_MESSAGE);
        return true;
      }
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
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "执行管理命令时出现异常", throwable);
      safeMessage(sender, "执行管理命令时出现异常，详见服务端日志");
      return true;
    }
  }

  /**
   * 子命令 → 权限节点的<b>唯一映射</b>。
   *
   * <p>执行侧（{@link #onCommand}）与补全侧（{@link #suggestSubcommands(CommandSender, String[])}）
   * 共用它，避免「补全能看到、执行说没权限」这类两处判据漂移；未知子命令返回 {@code null}。
   */
  private static String permissionOf(String subcommand) {
    if (subcommand == null) {
      return null;
    }
    return switch (subcommand) {
      case "status" -> PERMISSION_STATUS;
      case "dump" -> PERMISSION_DUMP;
      case "reload" -> PERMISSION_RELOAD;
      default -> null;
    };
  }

  /** 发送者是否可执行/可见该子命令。 */
  static boolean allowed(CommandSender sender, String subcommand) {
    String permission = permissionOf(subcommand);
    return permission != null && sender != null && sender.hasPermission(permission);
  }

  /** 发送者是否至少拥有一个子命令权限（用于「完全无权限」时的统一拒绝）。 */
  private static boolean hasAnySubPermission(CommandSender sender) {
    for (String sub : SUBCOMMANDS) {
      if (allowed(sender, sub)) {
        return true;
      }
    }
    return false;
  }

  private void status(CommandSender sender) {
    if (!allowed(sender, "status")) {
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

  /**
   * 导出诊断转储：<b>所有重活都不在命令线程上做</b>。
   *
   * <p><b>为什么必须异步</b>：一次转储要生成整份文本（拉取全部计数器与配置回显）、写文件、列插件数据
   * 目录并按保留上限删除旧文件。命令线程在 Folia 上是发送者所属的<b>区域线程</b>（同步 IO 会让该区域
   * 所有玩家/实体的 tick 停摆），在 Paper 上是主线程（服务器级卡顿）——两类线程都不允许阻塞 IO。
   *
   * <p><b>线程编排</b>：命令线程只做权限校验与任务提交 → 异步线程生成/写盘/清理 → 回到<b>发送者所在
   * 线程</b>回显（玩家走实体调度器、控制台/命令方块走全局区域调度器）：Folia 上绝不能在异步线程直接
   * 向玩家发包。提交失败与生成失败都必须回显原因，绝不静默。
   */
  private void dump(CommandSender sender) {
    if (!allowed(sender, "dump")) {
      message(sender, "你没有权限导出诊断");
      return;
    }
    if (!submitDump(sender)) {
      // 提交阶段就失败（插件停用中 / 调度器拒绝）：立刻如实回显，不能让管理员等一个永远不会来的结果
      message(sender, "诊断转储任务提交失败（调度器不可用），详见服务端日志");
      return;
    }
    message(sender, "正在生成诊断转储（后台线程执行，完成后会回显保存路径）…");
  }

  /**
   * 把「生成 + 写盘 + 清理旧转储」提交到异步线程。
   *
   * @return {@code true} 表示已被调度器受理；{@code false} 表示提交即失败（已记日志，由调用方回显）
   */
  private boolean submitDump(CommandSender sender) {
    try {
      plugin.getServer().getAsyncScheduler().runNow(plugin, scheduled -> {
        String resultLine;
        try {
          File file = diagnostics.writeDump();
          resultLine = "诊断转储已写入：" + file.getAbsolutePath();
        } catch (Throwable throwable) {
          // 原因既进日志（带堆栈）也进回显（一行）：「不能静默失败」是这条路径的硬要求，
          // 而管理员在游戏内看不到日志，只回显「失败」而不给原因等于要他自己去翻文件。
          plugin.getLogger().log(Level.WARNING, "导出诊断转储失败", throwable);
          resultLine = "导出诊断转储失败：" + describeDumpFailure(throwable) + "（详见服务端日志）";
        }
        replyToSender(sender, resultLine);
      });
      return true;
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "提交诊断转储任务失败", throwable);
      return false;
    }
  }

  /**
   * 回到发送者所属线程回显结果。
   *
   * <p>玩家是实体，必须经实体调度器回到其区域线程；控制台 / 命令方块走全局区域调度器。
   * 插件停用导致调度被拒时退化为「尽力直接发一次」——此时已无正确线程可回，回显失败只能吞掉
   * （{@link #safeMessage} 本身不再抛）。
   */
  private void replyToSender(CommandSender sender, String text) {
    if (sender instanceof Entity entity) {
      Schedulers.onEntity(plugin, entity, () -> safeMessage(sender, text));
      return;
    }
    try {
      plugin.getServer().getGlobalRegionScheduler()
          .run(plugin, scheduled -> safeMessage(sender, text));
    } catch (Throwable throwable) {
      safeMessage(sender, text);
    }
  }

  /**
   * 把转储失败压成一行中文回显（不带堆栈、不含换行，避免刷屏）。
   *
   * <p>包可见<b>纯函数</b>，便于离线单测：{@code getMessage()} 常为 {@code null}（如
   * {@code NullPointerException}）或空白，此时回落为异常类名——绝不能让回显出现「失败：null」。
   *
   * @param throwable 捕获到的异常；{@code null} 也给出明确文案（防御性）
   */
  static String describeDumpFailure(Throwable throwable) {
    if (throwable == null) {
      return "未知原因";
    }
    String detail = throwable.getMessage();
    if (detail == null || detail.isBlank()) {
      detail = throwable.getClass().getSimpleName();
      if (detail == null || detail.isBlank()) {
        detail = throwable.getClass().getName();
      }
    }
    // 异常消息可能内嵌换行（例如带路径 + 说明的 IOException）：压成一行，回显只占一行聊天
    return detail.replace('\n', ' ').replace('\r', ' ').trim();
  }

  private void reload(CommandSender sender) {
    if (!allowed(sender, "reload")) {
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

  /**
   * 回显失败也不外抛的兜底入口（供 {@code onCommand} 的顶层 catch 使用）：
   * 回显本身再失败时只吞掉——顶层早已把异常记进日志，绝不从命令分发器二次抛出。
   */
  private static void safeMessage(CommandSender sender, String text) {
    try {
      message(sender, text);
    } catch (Throwable ignored) {
      // 回显失败：日志已记录，命令处理到此为止（返回 true 表示已处理）
    }
  }

  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    return suggestSubcommands(sender, args);
  }

  /**
   * 按发送者权限过滤后的子命令补全。
   *
   * <p>与执行侧共用 {@link #permissionOf(String)} 的同一份映射：只补出「确实有权限执行」的子命令，
   * 既避免误导（补出来却执行不了），也避免把管理命令的存在与否暴露给完全无权限的玩家。
   */
  static List<String> suggestSubcommands(CommandSender sender, String[] args) {
    List<String> all = suggestSubcommands(args);
    if (all.isEmpty()) {
      return all;
    }
    if (sender == null) {
      // 取不到发送者就无法判定权限：一律不补全（旧实现此时直接返回全部子命令，等于把
      // status / dump / reload 的存在暴露给无权限来源，与本方法「不暴露管理命令」的承诺相反）
      return List.of();
    }
    List<String> visible = new ArrayList<>(all.size());
    for (String sub : all) {
      if (allowed(sender, sub)) {
        visible.add(sub);
      }
    }
    return visible;
  }

  /**
   * 子命令补全（纯函数，可离线单测）。
   *
   * <p><b>为什么要同时兼容「空数组」与 {@code [""]} 两种入参形态</b>：本命令在 Paper/Folia 上走
   * Brigadier（{@code BasicCommand#suggest}，由 {@code CommandRegistrar.PaperCommandBridge} 转调），
   * 该路径对「只敲了命令、还没输入任何字符」传入的是<b>长度 0 的数组</b>：
   * {@code PaperCommands#register(label, description, aliases, basicCommand)} 里
   * {@code String[] args = StringUtils.split(suggestionsBuilder.getRemaining())}，remaining 为空串时
   * split 结果为空数组。而 {@code TabCompleter} 的历史约定（以及某些包装层）在同一情形传 {@code [""]}，
   * 且长度恒 ≥ 1。本项目已无传统 Bukkit 注册路径（{@code plugin.yml} 已下线、该回退已随
   * {@code CommandRegistrar} 一并删除），但两种形态都按「长度 0 或 1 = 正在输入第一个参数」归一化——
   * 上游形态一旦变化，补全不会静默失效。
   * 真机反馈的「TAB 补全不能用」正是前者：旧实现在长度不为 1 时直接返回空列表，
   * 于是 Paper/Folia 上 {@code /mxnet <TAB>} 一个候选都出不来（输入了首字母反而有）。
   *
   * <p>本重载只做「有哪些子命令」这一件事（纯函数，便于离线单测）；<b>权限过滤</b>由
   * {@link #suggestSubcommands(CommandSender, String[])} 承担——根命令已不在框架层要求
   * {@code mikuxraynet.admin}（否则子权限无法单独授予），故补全必须自行按发送者权限收敛。
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