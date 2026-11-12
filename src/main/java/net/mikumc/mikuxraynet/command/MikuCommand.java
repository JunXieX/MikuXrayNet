package net.mikumc.mikuxraynet.command;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.mikumc.mikuxraynet.AntiXrayRuntime;
import net.mikumc.mikuxraynet.MikuXrayNet;
import net.mikumc.mikuxraynet.antixray.ProximitySelector;
import net.mikumc.mikuxraynet.antixray.RevealProbe;
import net.mikumc.mikuxraynet.bandwidth.Schedulers;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.Constants;
import net.mikumc.mikuxraynet.util.Diagnostics;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * {@code /mikuxraynet <status|dump|probe|reload>} 管理命令（别名 {@code mxnet} / {@code mxr}）。
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
  private static final List<String> SUBCOMMANDS = List.of("status", "dump", "probe", "reload");
  /** 无任何子命令权限时的统一回显（等价于命令描述里声明的 permission-message）。 */
  /** 无任何子命令权限时的统一回显：<b>必须点名需要的节点</b>，否则管理员只能靠猜（真机「reload 用不了」多为此）。 */
  private static final String NO_PERMISSION_MESSAGE =
      "你没有权限使用该命令（需要 mikuxraynet.status / dump / reload 之一，或总权限 mikuxraynet.admin）";

  private final MikuXrayNet plugin;
  private final Diagnostics diagnostics;
  /** dump 采样闸门：两次采样并行会互相拖慢，而且各自写盘。 */
  private final java.util.concurrent.atomic.AtomicBoolean dumpSampling =
      new java.util.concurrent.atomic.AtomicBoolean();

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
        case "probe" -> probe(sender, label, args);
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
      // 探针是只读诊断（不写索引/位图、不发包），沿用 status 的权限节点，不额外扩权。
      case "probe" -> PERMISSION_STATUS;
      case "reload" -> PERMISSION_RELOAD;
      default -> null;
    };
  }

  /**
   * 发送者是否可执行/可见该子命令。
   *
   * <p><b>父权限必须等效放行</b>：{@code paper-plugin.yml} 把 {@code mikuxraynet.admin} 声明为
   * {@code status} / {@code dump} / {@code reload} 的父节点，但那只存在于「插件注册的 Permission 对象的
   * children 里」。权限插件（LuckPerms 等）只查自己库中的节点，<b>不会展开</b>插件注册的 children——
   * 只把 {@code mikuxraynet.admin} 授予管理组的服主会看到「给了总权限却提示没有权限」（
   * 真机反馈的「/mxnet reload 用不了」即此情形）。因此这里显式认父节点：拥有 admin 等价位全部子权限。
   */
  static boolean allowed(CommandSender sender, String subcommand) {
    String permission = permissionOf(subcommand);
    if (permission == null || sender == null) {
      return false;
    }
    return sender.hasPermission(permission) || sender.hasPermission(Constants.ADMIN_PERMISSION);
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
      message(sender, "你没有权限查看运行状态（需要 mikuxraynet.status 或总权限 mikuxraynet.admin）");
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
      message(sender, "你没有权限导出诊断（需要 mikuxraynet.dump 或总权限 mikuxraynet.admin）");
      return;
    }
    if (!dumpSampling.compareAndSet(false, true)) {
      message(sender, "已有一次诊断采样在进行，请等它完成（约 10 秒）");
      return;
    }
    startSampling(sender);
  }

  /**
   * 先采样 10 秒再生成转储：单点快照只有累计值，采样能给出每秒速率、峰值与窗口内是否掉 TPS。
   *
   * <p>采样在全局区域线程按 10 tick 一次推进（只读计数器与 TPS，不做任何计算），
   * 到点后把样本交给后台线程写盘；期间再次执行 dump 会被拒绝（避免两次采样互相拖慢）。
   */
  private void startSampling(CommandSender sender) {
    List<Diagnostics.Sample> samples =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    long[] firstNanos = {0L};
    long reloadAtStart = plugin.lastReloadAt();
    message(sender, "开始采样 10 秒运行数据（期间请保持服务器运行），完成后会回显保存路径…");
    try {
      plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
        try {
          if (samples.isEmpty()) {
            firstNanos[0] = System.nanoTime();
          }
          samples.add(diagnostics.sample());
          long elapsed = System.nanoTime() - firstNanos[0];
          if (elapsed >= java.util.concurrent.TimeUnit.SECONDS.toNanos(10L)) {
            task.cancel();
            finishSampling(sender, samples, elapsed, reloadAtStart);
          }
        } catch (Throwable throwable) {
          task.cancel();
          dumpSampling.set(false);
          plugin.getLogger().log(Level.WARNING, "诊断采样失败", throwable);
          replyToSender(sender, "诊断采样失败：" + describeDumpFailure(throwable) + "（详见服务端日志）");
        }
      }, 1L, 10L);
    } catch (Throwable throwable) {
      dumpSampling.set(false);
      plugin.getLogger().log(Level.WARNING, "提交诊断采样任务失败", throwable);
      replyToSender(sender, "诊断采样任务提交失败（调度器不可用），详见服务端日志");
    }
  }

  /** 采样结束：复制样本、交给后台线程写盘，最后回显路径（任何异常都要复位采样闸门）。 */
  private void finishSampling(CommandSender sender, List<Diagnostics.Sample> samples,
      long elapsedNanos, long reloadAtStart) {
    long durationMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    boolean reloadDuring = plugin.lastReloadAt() > reloadAtStart;
    // 运行环境段必须在当前（全局区域线程）采集：异步线程读 TPS / 世界列表在部分服务端分支会失败
    String collectedRuntime;
    try {
      collectedRuntime = diagnostics.runtimeSection();
    } catch (Throwable throwable) {
      collectedRuntime = "\n---- 运行环境 ----\n（采集失败：" + describeDumpFailure(throwable) + "）\n";
    }
    final String runtimeSection = collectedRuntime;
    try {
      plugin.getServer().getAsyncScheduler().runNow(plugin, scheduled -> {
        String resultLine;
        try {
          java.io.File file = diagnostics.writeDump(new java.util.ArrayList<>(samples),
              durationMillis, reloadDuring, runtimeSection);
          resultLine = "诊断转储已写入：" + file.getAbsolutePath()
              + "（采样 " + (durationMillis / 1000.0D) + " 秒）";
        } catch (Throwable throwable) {
          plugin.getLogger().log(Level.WARNING, "导出诊断转储失败", throwable);
          resultLine = "导出诊断转储失败：" + describeDumpFailure(throwable) + "（详见服务端日志）";
        } finally {
          dumpSampling.set(false);
        }
        replyToSender(sender, resultLine);
      });
    } catch (Throwable throwable) {
      dumpSampling.set(false);
      plugin.getLogger().log(Level.WARNING, "提交诊断转储任务失败", throwable);
      replyToSender(sender, "诊断转储任务提交失败（调度器不可用），详见服务端日志");
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
      if (!Schedulers.onEntity(plugin, entity, () -> safeMessage(sender, text))) {
        // 调度被拒（停用瞬间 / 实体退役）：直接尽力发一次，回显失败也由 safeMessage 吞掉
        safeMessage(sender, text);
      }
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
      message(sender, "你没有权限热重载配置（需要 mikuxraynet.reload 或总权限 mikuxraynet.admin）");
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

  /**
   * 显形探针：把「某个坐标为什么没有被还原」的五层判定（显形索引 / 已显形标记 / 遮挡位图 /
   * 暴露面 / 射线）逐层打印出来。
   *
   * <p><b>只读</b>：不写索引、不写已显形集合、不写位图、不发包；权限沿用 {@code status} 节点，
   * 因为它是纯诊断、不改变任何运行状态。
   *
   * <p><b>为什么必须由玩家执行</b>：判定要用「眼睛位置 + 视线方向」，控制台没有这两者，
   * 给不出与真实显形一致的结论。坐标可选：不填时取视线指向的方块（8 格内）。
   */
  private void probe(CommandSender sender, String label, String[] args) {
    if (!allowed(sender, "probe")) {
      message(sender, "你没有权限使用显形探针");
      return;
    }
    if (!(sender instanceof Player player)) {
      message(sender, "显形探针需要由玩家执行（需要眼睛位置与视线方向）；用法：/" + label
          + " probe <x> <y> <z>");
      return;
    }
    AntiXrayRuntime runtime = plugin.antiXrayRuntime();
    if (runtime == null) {
      message(sender, "反矿透未启用：没有可探测的显形链路");
      return;
    }
    int x;
    int y;
    int z;
    if (args.length != 1 && args.length != 4) {
      // 只接受「不给坐标」或「给满 x y z」两种形态：2/3/5+ 个参数静默改去探测准星方块，
      // 会让管理员以为在看指定坐标、实际是另一个位置的结论（新功能最易误判的一点）。
      message(sender, "用法：/" + label + " probe [x y z]（不给坐标 = 探测视线指向的 8 格内方块）");
      return;
    }
    if (args.length == 4) {
      try {
        x = Integer.parseInt(args[1]);
        y = Integer.parseInt(args[2]);
        z = Integer.parseInt(args[3]);
      } catch (NumberFormatException notANumber) {
        message(sender, "坐标必须是整数：/" + label + " probe <x> <y> <z>");
        return;
      }
    } else {
      Block target = player.getTargetBlockExact(8);
      if (target == null) {
        message(sender, "请看向 8 格内的一个方块，或显式给出坐标：/" + label + " probe <x> <y> <z>");
        return;
      }
      x = target.getX();
      y = target.getY();
      z = target.getZ();
    }
    AntiXrayConfig config = plugin.mikuConfig() == null ? null : plugin.mikuConfig().antiXray();
    int samples = config == null
        ? ProximitySelector.MAX_SAMPLE_POINTS : config.proximity().raycastSamples();
    Location eye = player.getEyeLocation();
    Vector direction = eye.getDirection();
    List<String> lines = RevealProbe.probe(player.getWorld().getName(), player.getUniqueId(),
        ProximitySelector.eye(eye.getX(), eye.getY(), eye.getZ(),
            direction.getX(), direction.getY(), direction.getZ()),
        x, y, z, runtime.obfuscatedChunkIndex(), runtime.revealedSet(),
        runtime.occlusionStores(), samples);
    for (String line : lines) {
      message(sender, line);
    }
  }

  private void usage(CommandSender sender, String label) {
    message(sender, "用法：/" + label + " <status|dump|probe|reload>");
    message(sender, "status 查看运行状态｜dump 导出诊断文件｜reload 热重载配置");
    message(sender, "probe [x y z] 诊断某个坐标为什么没有被还原（不填坐标 = 视线指向的方块，8 格内）");
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