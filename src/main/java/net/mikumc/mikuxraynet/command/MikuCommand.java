package net.mikumc.mikuxraynet.command;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import net.mikumc.mikuxraynet.util.SparkDumpCapture;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * {@code /mikuxraynet <status|dump [local]|probe|reload>} 管理命令（别名 {@code mxnet} / {@code mxr}）。
 *
 * <p>命令本身只做「权限校验 + 提交一次聚合/重载 + 回显中文结果」，不做任何重活：
 * 状态与转储的指标聚合、文件写入都交给 {@link Diagnostics}；热重载交给插件的
 * {@code reloadConfigs()}。任何异常都只记日志并回显一句中文提示，绝不因命令抛异常影响封包主链路。
 *
 * <p><b>线程纪律（转储）</b>：命令线程在 Folia 上是发送者所属的<b>区域线程</b>、在 Paper 上是主线程，
 * 两者都不能做磁盘 IO。因此 {@code dump} 只提交任务，真正的「生成文本 + 写文件 + 清理旧文件」
 * 跑在异步线程（{@code AsyncScheduler}）上，完成后回到发送者所在线程回显
 * （见 {@link #dump(CommandSender, String, String[])}）。
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

  /** 本地采样窗口（秒）：{@code dump local} 固定 10 秒，且不请求 Spark、不上传任何内容。 */
  static final int LOCAL_WINDOW_SECONDS = 10;

  /**
   * 默认采样窗口（秒）：与 Spark profile 的 {@link SparkDumpCapture#PROFILE_SECONDS} <b>同窗</b>。
   *
   * <p><b>为什么直接引用后端常量而不是再写一个 30</b>：数值段与性能记录必须是同一段时间，差几秒就会让
   * 「这段卡顿有没有被 Spark 抓到」无法对账；两处各写一份数字迟早漂移，因此只留一个出处。
   */
  static final int SPARK_WINDOW_SECONDS = SparkDumpCapture.PROFILE_SECONDS;

  /**
   * 从发起到写文件的最长等待（秒）：数值窗口结束后 Spark 仍未返回，就在这个上限处按超时收尾。
   *
   * <p>取值同样来自后端（它自己会给出一个约 65 秒的结果），本类只按同一个上限做兜底，避免出现
   * 「后端 65 秒放弃、这里等到 90 秒」这种两套口径。
   */
  static final int SPARK_MAX_WAIT_SECONDS = SparkDumpCapture.MAX_WAIT_SECONDS;

  /**
   * 口径提示：Spark 性能记录会离开本机，而 dump 文件不会。
   *
   * <p>必须由命令<b>提前</b>告知执行者：事后从文件里才发现「原来上传了」是不可接受的。
   */
  static final String SPARK_UPLOAD_NOTICE = "Spark 性能记录会上传至 Spark，dump 本身留本地";

  private final MikuXrayNet plugin;
  private final Diagnostics diagnostics;
  /** dump 会话闸门：两次采样并行会互相拖慢，而且各自写盘（同一时刻只允许一个会话）。 */
  private final AtomicBoolean dumpSampling = new AtomicBoolean();
  /**
   * 当前在途会话（{@code null} = 没有）。
   *
   * <p>会话对象本身就是「那一轮请求」的身份：写盘与回显前都要确认自己仍是<b>当前</b>会话，
   * 迟到的 Spark 结果因此不可能被算进后一轮请求（否则会把上一段时间的链接回给新的请求）。
   */
  private final AtomicReference<DumpSession> activeDump = new AtomicReference<>();

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
        case "dump" -> dump(sender, label, args);
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
   * <p><b>两种口径</b>：
   * <ul>
   *   <li>{@code /mxnet dump}（默认）：数值采样 {@link #SPARK_WINDOW_SECONDS} 秒，并在<b>同一窗口</b>
   *       请求 Spark 性能记录（{@link #SPARK_UPLOAD_NOTICE}）；链接写进 dump，且只回显给发起人。</li>
   *   <li>{@code /mxnet dump local}：数值采样 {@link #LOCAL_WINDOW_SECONDS} 秒，<b>仅本地</b>——不请求
   *       Spark、不上传任何内容。{@code local} 本身就是明确的退出方式，不需要额外的配置开关。</li>
   * </ul>
   *
   * <p><b>为什么必须异步</b>：一次转储要生成整份文本（拉取全部计数器与配置回显）、写文件、列插件数据
   * 目录并按保留上限删除旧文件。命令线程在 Folia 上是发送者所属的<b>区域线程</b>（同步 IO 会让该区域
   * 所有玩家/实体的 tick 停摆），在 Paper 上是主线程（服务器级卡顿）——两类线程都不允许阻塞 IO。
   *
   * <p><b>线程编排</b>：命令线程只做权限校验、参数解析、抢闸门与任务提交 → Spark 请求投到<b>全局区域
   * 线程</b>（{@code SparkDumpCapture#start} 自身非阻塞，读文件/联网由它内部异步完成，绝不在区域线程
   * 上同步做）→ 数值采样在全局区域线程按 10 tick 推进 → 两路结果在任意线程汇合后<b>只提交一次</b>写盘
   * （{@code AsyncScheduler}）→ 回到<b>发送者所在线程</b>回显（玩家走实体调度器、控制台/命令方块走
   * 全局区域调度器）：Folia 上绝不能在异步线程直接向玩家发包。提交失败与生成失败都必须回显原因，绝不静默。
   *
   * <p><b>全程不 wait/join</b>：汇合靠 {@link CompletionStage} 回调 + 异步超时任务，绝不在区域线程或
   * 服务端线程上阻塞等待 Spark——为了一个链接让整段区域停摆 65 秒，比拿不到链接严重得多。
   */
  private void dump(CommandSender sender, String label, String[] args) {
    if (!allowed(sender, "dump")) {
      message(sender, "你没有权限导出诊断（需要 mikuxraynet.dump 或总权限 mikuxraynet.admin）");
      return;
    }
    DumpMode mode = dumpMode(args);
    if (mode == DumpMode.INVALID) {
      // 写错参数时绝不静默按默认执行：那会让「我只做本地采样」变成「已经上传了性能记录」
      message(sender, "用法：/" + label + " dump [local]（不给参数 = " + SPARK_WINDOW_SECONDS
          + " 秒采样并请求 Spark；local = " + LOCAL_WINDOW_SECONDS + " 秒、仅本地、不上传）");
      return;
    }
    if (!dumpSampling.compareAndSet(false, true)) {
      message(sender, "已有一次诊断采样在进行，请等它完成（最长约 " + SPARK_MAX_WAIT_SECONDS + " 秒）");
      return;
    }
    DumpSession session = new DumpSession(sender, mode);
    activeDump.set(session);
    try {
      session.begin();
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "启动诊断采样失败", throwable);
      session.abort("启动采样失败：" + describeDumpFailure(throwable), true);
    }
  }

  /**
   * 解析 {@code dump} 之后的参数（纯函数，执行与补全共用同一份判定）。
   *
   * <p><b>为什么非法参数必须报错而不是降级</b>：把 {@code local} 写错成别的词时，若悄悄按默认口径执行，
   * 执行者会以为「这次只做了本地采样」，实际却把性能记录上传了。行为必须与说出口的口径一致。
   */
  static DumpMode dumpMode(String[] args) {
    if (args == null || args.length <= 1) {
      return DumpMode.SPARK;
    }
    if (args.length > 2) {
      return DumpMode.INVALID;
    }
    String second = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
    if (second.isEmpty()) {
      return DumpMode.SPARK;
    }
    return "local".equals(second) ? DumpMode.LOCAL : DumpMode.INVALID;
  }

  /** {@code dump} 的三种参数形态。 */
  enum DumpMode {
    /** 默认：请求 Spark 性能记录 + 同窗数值采样（性能记录会上传）。 */
    SPARK,
    /** {@code dump local}：仅本地数值采样，不上传任何内容。 */
    LOCAL,
    /** 参数非法：多给了参数，或第二参数不是 {@code local}。 */
    INVALID
  }

  /**
   * Spark 是否可用（<b>零 IO 的同步探测</b>，可在全局区域线程调用）。
   *
   * <p>只用来决定「数值窗口取 {@link #SPARK_WINDOW_SECONDS} 还是回退 {@link #LOCAL_WINDOW_SECONDS}」：
   * 不可用时白等 30 秒毫无意义。真正能否采样仍以 {@code start()} 的返回为准。
   *
   * <p><b>为什么用后端的 {@code available()} 而不是查插件管理器</b>：Paper 26.x 把 spark <b>内置</b>进服务端
   * （由 {@code SparksFly} 注册进命令表，没有同名插件），只看 {@code getPlugin("spark")} 会在这种端上误判
   * 为「不可用」；而 Folia 上内置 spark 被上游补丁显式禁用，只看命令表又会误判为「可用」。这两件事只有
   * 后端自己分得清，探测入口就该由它提供。
   */
  private boolean sparkInstalled() {
    try {
      return SparkDumpCapture.available();
    } catch (Throwable throwable) {
      // 探测入口自称不抛；万一实现变了，按「不可用」处理即可（等价于 dump local 的行为）
      return false;
    }
  }

  /**
   * 从 Spark 段里挑出可以回显给发起人的行。
   *
   * <p>规则很窄——<b>只回带链接的行</b>：原因（未请求/超时/失败）留在文件里，聊天框只多一行链接，
   * 既不刷屏，也不会出现「说了一堆但没给链接」。
   */
  static List<String> sparkReplyLines(String sparkSection) {
    if (sparkSection == null || sparkSection.isBlank()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>(2);
    for (String raw : sparkSection.split("\n")) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("----")) {
        continue;
      }
      if (line.contains("http://") || line.contains("https://")) {
        lines.add(line);
      }
    }
    return lines;
  }

  /** 取消调度任务（已结束/已取消都不抛）。 */
  private static void cancelTask(ScheduledTask task) {
    if (task == null) {
      return;
    }
    try {
      task.cancel();
    } catch (Throwable ignored) {
      // 任务可能已结束或插件已停用：无需处理
    }
  }

  /**
   * 一次转储会话：数值采样、Spark 性能记录、写盘、回显与闸门释放全部收敛到同一个对象上。
   *
   * <p><b>为什么必须是一个对象</b>：三路时序（数值窗口、Spark 结果、等待超时）来自不同线程，任何一路都
   * 可能先到。只有把它们绑在同一个会话上，才能保证「只写一次文件、只回显一次、闸门只放一次」，并在
   * 停用 / 热重载 / 发起者断线时整体作废（迟到的 Spark 结果不会写进后来的 dump）。
   *
   * <p><b>不把旧链接回给新会话</b>：Spark 链接只属于产生它的那次会话。会话作废后迟到的结果一律丢弃——
   * 把上一轮的链接回给新一轮请求，等于让管理员拿着另一段时间的样本去判断这次卡顿。
   */
  private final class DumpSession {

    private final CommandSender sender;
    private final DumpMode mode;
    /** 发起时刻：等待上限以它为基准，避免把「启动耗时」重复计入窗口。 */
    private final long startedAtNanos = System.nanoTime();
    private final long reloadAtStart = plugin.lastReloadAt();
    /** 采样点只在全局区域线程追加；写盘前复制成不可变快照交给异步线程。 */
    private final List<Diagnostics.Sample> samples = new ArrayList<>();
    /** 只写一次文件、只回显一次（Spark 结果与数值窗口任一先到都会触发汇合）。 */
    private final AtomicBoolean finished = new AtomicBoolean();
    /** Spark 结果是否已落定（完成 / 超时 / 本次根本没请求）。 */
    private final AtomicBoolean sparkSettled = new AtomicBoolean();
    /** 数值窗口是否已结束。 */
    private final AtomicBoolean numericDone = new AtomicBoolean();
    /** 会话是否已被作废（停用 / 热重载 / 断线 / 启动失败）。 */
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /** 闸门与后台资源是否已释放（保证只放一次）。 */
    private final AtomicBoolean released = new AtomicBoolean();
    /** Spark 采样是否已 close（幂等）。 */
    private final AtomicBoolean captureClosed = new AtomicBoolean();

    private volatile ScheduledTask samplingTask;
    private volatile ScheduledTask sparkTimeoutTask;
    private volatile SparkDumpCapture capture;
    /** 首个采样点时刻：窗口时长按「首末样本」计算，与旧实现逐字一致。 */
    private volatile long firstSampleNanos;
    private volatile long elapsedNanos;
    private volatile String runtimeSection = "";
    private volatile String sparkSection = "";
    private volatile List<Diagnostics.Sample> finalSamples = List.of();
    /**
     * 当前数值窗口（纳秒）。
     *
     * <p><b>为什么是可变字段而不是构造时的局部量</b>：Spark 明确「未启动」（忙 / 起不来）时要把窗口尽早收回
     * 到本地 10 秒，否则管理员会为一个根本没有性能记录可拿的 dump 白等 30 秒。采样线程每 tick 重读它，
     * 缩短立即生效。
     */
    private volatile long windowNanos = TimeUnit.SECONDS.toNanos(LOCAL_WINDOW_SECONDS);

    DumpSession(CommandSender sender, DumpMode mode) {
      this.sender = sender;
      this.mode = mode;
    }

    /**
     * 先定窗口口径并回显，再在<b>同一个全局区域 tick</b> 里启动 Spark 请求与数值采样。
     *
     * <p><b>为什么两路要挤进同一个 tick</b>：数值段与 Spark profile 必须是同一段时间。分两处各自投递
     * （一个 {@code run}、一个 {@code runAtFixedRate}）时起点会差一到几个 tick，长窗口下无伤大雅，但
     * 「同一窗口」这个承诺就变成了「差不多同一窗口」——既然能一次做完，就不留这个含糊。
     */
    void begin() {
      boolean sparkPresent = mode == DumpMode.SPARK && sparkInstalled();
      windowNanos = TimeUnit.SECONDS.toNanos(sparkPresent ? SPARK_WINDOW_SECONDS : LOCAL_WINDOW_SECONDS);
      if (!sparkPresent) {
        // 没有 Spark / 明确 local：一开始就「落定」，汇合条件只剩数值窗口
        sparkSettled.set(true);
        sparkSection = mode == DumpMode.LOCAL
            ? Diagnostics.sparkSectionNotRequested(
                "dump local 只做本地采样，不请求 Spark、不上传任何内容")
            : Diagnostics.sparkSectionNotRequested(
                "未检测到 Spark 插件，本次只做 " + LOCAL_WINDOW_SECONDS + " 秒本地采样");
      }
      announce(sparkPresent);
      try {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduled -> {
          if (cancelled.get()) {
            return;
          }
          if (sparkPresent) {
            startSpark();
          }
          startNumericSampling();
        });
      } catch (Throwable throwable) {
        // 全局区域调度被拒（停用瞬间 / 调度器不可用）：两路都起不来，立刻收口并放闸门
        plugin.getLogger().log(Level.WARNING, "提交诊断采样任务失败", throwable);
        settleSpark(Diagnostics.sparkSectionFailed("调度器不可用，未请求 Spark"));
        abort("调度器不可用", true);
      }
    }

    /** 回显本次口径：执行者必须<b>提前</b>知道「会上传」还是「只在本机」。 */
    private void announce(boolean sparkPresent) {
      if (mode == DumpMode.LOCAL) {
        message(sender, "开始采样 " + LOCAL_WINDOW_SECONDS
            + " 秒运行数据（仅本地：不请求 Spark、不上传任何内容），完成后会回显保存路径…");
        return;
      }
      if (!sparkPresent) {
        message(sender, "未检测到 Spark：本次按 " + LOCAL_WINDOW_SECONDS
            + " 秒采样，仅本地、不上传，完成后会回显保存路径…");
        return;
      }
      message(sender, "开始采样 " + SPARK_WINDOW_SECONDS + " 秒运行数据，并同时请求 Spark 性能记录（"
          + SPARK_UPLOAD_NOTICE + "），完成后会回显保存路径与链接…");
    }

    /**
     * 在当前全局区域 tick 内创建并启动 Spark 采样。
     *
     * <p><b>为什么必须在全局区域线程</b>：{@code SparkDumpCapture#start} 的契约是「在全局区域拥有线程调用、
     * 内部自行安排异步 IO 与反馈」；玩家执行命令时命令线程是<b>他所属的区域线程</b>——直接在命令线程里调
     * 会把一条按全局区域制定的契约变成碰运气。
     *
     * <p><b>这里不许同步读盘/联网</b>：只创建对象并调用非阻塞的 {@code start()}，结果经
     * {@link CompletionStage} 回调回来。回调可能在<b>任意线程</b>，因此它只做内存记账；写盘一律再投递到
     * {@code AsyncScheduler}。
     */
    private void startSpark() {
      try {
        SparkDumpCapture created = new SparkDumpCapture(plugin);
        capture = created;
        if (cancelled.get()) {
          // 建好就已被作废（停用/重载恰好插在中间）：立刻关掉，不留后台资源
          closeCapture();
          return;
        }
        CompletionStage<String> stage = created.start();
        if (stage == null) {
          settleSpark(Diagnostics.sparkSectionFailed("Spark 采样没有返回结果句柄"));
          return;
        }
        stage.whenComplete((section, error) -> {
          if (error != null) {
            settleSpark(Diagnostics.sparkSectionFailed(describeDumpFailure(error)));
          } else if (section == null || section.isBlank()) {
            settleSpark(Diagnostics.sparkSectionFailed("Spark 采样返回了空结果"));
          } else {
            settleSpark(section);
          }
        });
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "请求 Spark 性能记录失败", throwable);
        settleSpark(Diagnostics.sparkSectionFailed(describeDumpFailure(throwable)));
      }
    }

    /**
     * Spark 结果落定（成功 / 失败 / 超时，<b>只认第一次</b>）。
     *
     * <p><b>迟到结果一律丢弃</b>：超时先落定时，之后真正回来的结果只用于关掉后台资源，绝不再写文件、
     * 不再回显——否则会出现「文件里一个链接、聊天里另一个链接」，或者把作废会话的链接回给新会话。
     */
    private void settleSpark(String section) {
      if (cancelled.get() || !sparkSettled.compareAndSet(false, true)) {
        // 已作废或已落定：只收尾，不再参与汇合
        closeCapture();
        return;
      }
      sparkSection = section == null ? "" : section;
      closeCapture();
      shortenWindowIfSparkNeverStarted(sparkSection);
      maybeFinish();
    }

    /**
     * Spark 明确「未启动」时，把窗口尽早收回到本地 {@link #LOCAL_WINDOW_SECONDS} 秒。
     *
     * <p>判定只用后端承诺的稳定状态词 {@code 未执行}（忙 / 缺 Spark / 起不来都走它）。<b>认不出就不缩短</b>：
     * 状态不明时宁可多等，也不要凭猜测把 30 秒窗口砍成 10 秒——那段性能记录可能正真的在跑。
     *
     * <p>缩短只动「还要跑多久」，<b>不动已采集的样本</b>：dump 里的窗口长度始终写实际时长，所以不会出现
     * 「说 30 秒、文件写 10 秒」的对不上——回显也会补一句说明。
     */
    private void shortenWindowIfSparkNeverStarted(String section) {
      if (section == null || !section.contains("未执行")) {
        return;
      }
      long localNanos = TimeUnit.SECONDS.toNanos(LOCAL_WINDOW_SECONDS);
      if (windowNanos <= localNanos) {
        return;
      }
      windowNanos = localNanos;
      // 回显统一走调度器：这个回调可能在任意线程上（绝不在异步线程直接给玩家发包）
      replyToSender(sender, "Spark 未启动（原因已写进 dump），本次采样按 " + LOCAL_WINDOW_SECONDS
          + " 秒收尾…");
    }

    /** 数值采样：全局区域线程每 10 tick 取一个点（只读计数器与 TPS，不计算、不写盘）。 */
    private void startNumericSampling() {
      try {
        samplingTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
          try {
            if (cancelled.get()) {
              task.cancel();
              return;
            }
            if (samples.isEmpty()) {
              firstSampleNanos = System.nanoTime();
            }
            samples.add(diagnostics.sample());
            long elapsed = System.nanoTime() - firstSampleNanos;
            // 每 tick 重读 windowNanos：Spark 报「未启动」时会被就地缩短到本地 10 秒
            if (elapsed >= windowNanos) {
              task.cancel();
              finishNumeric(elapsed);
            }
          } catch (Throwable throwable) {
            task.cancel();
            plugin.getLogger().log(Level.WARNING, "诊断采样失败", throwable);
            abort("采样失败：" + describeDumpFailure(throwable), true);
          }
        }, 1L, 10L);
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "提交诊断采样任务失败", throwable);
        abort("调度器不可用", true);
      }
    }

    /**
     * 数值窗口结束：在<b>区域线程</b>采集运行环境段并冻结样本，然后等 Spark 那一路（或超时）。
     *
     * <p>运行环境段必须在这里采集：{@code Bukkit#getTPS} / {@code getWorlds} 在部分服务端分支会校验调用
     * 线程，异步线程读到的是「读取失败」。
     */
    private void finishNumeric(long elapsed) {
      if (!numericDone.compareAndSet(false, true)) {
        return;
      }
      elapsedNanos = elapsed;
      String collected;
      try {
        collected = diagnostics.runtimeSection();
      } catch (Throwable throwable) {
        collected = "\n---- 运行环境 ----\n（采集失败：" + describeDumpFailure(throwable) + "）\n";
      }
      runtimeSection = collected;
      finalSamples = List.copyOf(samples);
      if (!sparkSettled.get()) {
        scheduleSparkTimeout();
      }
      maybeFinish();
    }

    /**
     * Spark 等待上限：窗口结束后仍未落定就挂一个<b>异步</b>超时任务（绝不在任何线程上 sleep/join）。
     *
     * <p>上限从<b>发起时刻</b>算起而不是从此刻算起：Spark 已经在前面跑了一段时间，再等满
     * {@link #SPARK_MAX_WAIT_SECONDS} 秒会把「最多约 65 秒」变成「最多约 95 秒」。剩余时间非正时立即按
     * 超时处理。
     */
    private void scheduleSparkTimeout() {
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
      long remainingMillis =
          Math.max(0L, TimeUnit.SECONDS.toMillis(SPARK_MAX_WAIT_SECONDS) - elapsedMillis);
      try {
        sparkTimeoutTask = plugin.getServer().getAsyncScheduler()
            .runDelayed(plugin, scheduled -> onSparkTimeout(), remainingMillis, TimeUnit.MILLISECONDS);
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.FINE, "登记 Spark 超时兜底失败（直接按超时处理）", throwable);
        onSparkTimeout();
      }
    }

    private void onSparkTimeout() {
      if (sparkSettled.get() || cancelled.get()) {
        return;
      }
      plugin.getLogger().info("Spark 性能记录未在 " + SPARK_MAX_WAIT_SECONDS
          + " 秒内返回，本次 dump 只保留本地采样（不上传任何内容）");
      settleSpark(Diagnostics.sparkSectionTimeout(SPARK_MAX_WAIT_SECONDS));
    }

    /** 汇合：数值窗口结束且 Spark 已落定后才写盘（只写一次）。 */
    private void maybeFinish() {
      if (cancelled.get() || !numericDone.get() || !sparkSettled.get()) {
        return;
      }
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      writeOnce();
    }

    /**
     * 写盘 + 回显（<b>唯一</b>一次）：先投递到 {@code AsyncScheduler}，再在发送者所在线程回显。
     *
     * <p>投递失败（停用瞬间 / 调度器不可用）也必须放闸门，否则一次失败就能把这个功能永久卡死。
     */
    private void writeOnce() {
      long durationMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
      boolean reloadDuring = plugin.lastReloadAt() > reloadAtStart;
      List<Diagnostics.Sample> snapshot = finalSamples;
      String spark = sparkSection;
      String runtime = runtimeSection;
      try {
        plugin.getServer().getAsyncScheduler().runNow(plugin, scheduled -> {
          // 双重身份校验：① 会话已被作废（停用/重载/断线）② 已不是当前会话（不该有第二个，但一旦有，
          // 这里就是唯一能拦住「旧会话把文件/链接写进新一轮请求」的地方）。两者任一成立都只收口、不落盘。
          if (cancelled.get() || activeDump.get() != this) {
            releaseOnce();
            return;
          }
          List<String> reply = new ArrayList<>(2);
          try {
            File file = diagnostics.writeDump(snapshot, durationMillis, reloadDuring, spark, runtime);
            reply.add("诊断转储已写入：" + file.getAbsolutePath()
                + "（采样 " + (durationMillis / 1000.0D) + " 秒）");
            reply.addAll(sparkReplyLines(spark));
          } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "导出诊断转储失败", throwable);
            reply.add("导出诊断转储失败：" + describeDumpFailure(throwable) + "（详见服务端日志）");
          } finally {
            releaseOnce();
          }
          replyToSender(sender, reply);
        });
      } catch (Throwable throwable) {
        releaseOnce();
        plugin.getLogger().log(Level.WARNING, "提交诊断转储任务失败", throwable);
        replyToSender(sender, "诊断转储任务提交失败（调度器不可用），详见服务端日志");
      }
    }

    /**
     * 释放闸门与后台资源（幂等，返回「本次是否真的释放了」）。
     *
     * <p>顺序是纪律：先摘会话归属，再放闸门——反过来的话，新的会话可能在本会话还没摘干净时拿到闸门，
     * 随后被这里 {@code dumpSampling.set(false)} 顺手放开，于是出现两个在途会话。
     */
    private boolean releaseOnce() {
      if (!released.compareAndSet(false, true)) {
        return false;
      }
      activeDump.compareAndSet(this, null);
      cancelTask(samplingTask);
      cancelTask(sparkTimeoutTask);
      closeCapture();
      dumpSampling.set(false);
      return true;
    }

    /**
     * 作废本次会话（停用 / 热重载 / 发起者断线 / 启动失败），并立刻释放闸门。
     *
     * <p><b>作废后不写文件</b>：现场已经不属于这次会话（配置可能已重建、计数可能被重置），落半份数据只会
     * 制造误导。闸门必须马上放开，否则功能在本次运行内再也用不了。
     *
     * @param reason       中文原因（写日志、必要时回显）
     * @param notifySender 是否回显给发起者（停用/断线时不回显：此刻没有可用的回显线程）
     */
    void abort(String reason, boolean notifySender) {
      boolean firstAbort = !cancelled.getAndSet(true);
      if (!releaseOnce()) {
        return;
      }
      if (reason != null && !reason.isBlank()) {
        plugin.getLogger().info("诊断转储会话已中断：" + reason);
      }
      if (notifySender && firstAbort) {
        replyToSender(sender, "诊断采样已中断：" + reason + "（本次不写文件）");
      }
    }

    /** 发起者是否就是这个会话的请求人（按 UUID 判定，与对象身份无关）。 */
    boolean fromSender(Player player) {
      return sender instanceof Player requester && requester.getUniqueId().equals(player.getUniqueId());
    }

    /** 关闭 Spark 采样（幂等）：只关自己创建的那一个，不触碰任何其它采样。 */
    private void closeCapture() {
      if (!captureClosed.compareAndSet(false, true)) {
        return;
      }
      SparkDumpCapture closing = capture;
      capture = null;
      if (closing == null) {
        return;
      }
      try {
        closing.close();
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.FINE, "关闭 Spark 采样失败（忽略）", throwable);
      }
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
    replyToSender(sender, List.of(text));
  }

  /**
   * 批量回显（一次调度发多行）：转储结果 + Spark 链接必须<b>在同一个任务里</b>发出。
   *
   * <p>逐行各投一次会把「文件路径」与「链接」拆到两次调度，中间若插件被停用，玩家只会看到半边结果
   * （有路径没链接，或者反过来）；一次投递就消灭了这个半态。
   */
  private void replyToSender(CommandSender sender, List<String> lines) {
    if (lines == null || lines.isEmpty()) {
      return;
    }
    Runnable deliver = () -> {
      for (String line : lines) {
        safeMessage(sender, line);
      }
    };
    if (sender instanceof Entity entity) {
      if (!Schedulers.onEntity(plugin, entity, deliver)) {
        // 调度被拒（停用瞬间 / 实体退役）：直接尽力发一次，回显失败也由 safeMessage 吞掉
        deliver.run();
      }
      return;
    }
    try {
      plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduled -> deliver.run());
    } catch (Throwable throwable) {
      deliver.run();
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
    if (net.mikumc.mikuxraynet.bootstrap.PlatformSupport.isFolia()) {
      long heartbeat = runtime.proximityHeartbeatAgeMillis(player.getUniqueId());
      message(sender, heartbeat < 0L ? "显形任务心跳：未登记"
          : "显形任务最近心跳：" + heartbeat + " ms 前");
    }
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
    message(sender, "用法：/" + label + " <status|dump [local]|probe|reload>");
    message(sender, "status 查看运行状态｜dump 导出诊断文件｜reload 热重载配置");
    message(sender, "dump 默认采样 " + SPARK_WINDOW_SECONDS + " 秒并同时请求 Spark 性能记录（"
        + SPARK_UPLOAD_NOTICE + "）；dump local 采样 " + LOCAL_WINDOW_SECONDS
        + " 秒，仅本地、不上传");
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
   * 按发送者权限过滤后的补全。
   *
   * <p>与执行侧共用 {@link #permissionOf(String)} 的同一份映射：只补出「确实有权限执行」的子命令，
   * 既避免误导（补出来却执行不了），也避免把管理命令的存在与否暴露给完全无权限的玩家。
   *
   * <p><b>二级参数走同一条路径</b>：{@code dump local} 的补全与执行共用 {@link #dumpMode(String[])} 的
   * 词表（只有 {@code local}），且<b>先判 {@code mikuxraynet.dump}</b> 再补——补出一个执行时会被拒的
   * 候选，比不补更糟。
   */
  static List<String> suggestSubcommands(CommandSender sender, String[] args) {
    if (sender == null) {
      // 取不到发送者就无法判定权限：一律不补全（旧实现此时直接返回全部子命令，等于把
      // status / dump / reload 的存在暴露给无权限来源，与本方法「不暴露管理命令」的承诺相反）
      return List.of();
    }
    if (args != null && args.length > 1) {
      // 第二级只有 dump 有参数（Paper 在「首个参数后跟空格」时会传 ["dump", ""]）
      return allowed(sender, "dump") ? suggestDumpArguments(args) : List.of();
    }
    List<String> all = suggestSubcommands(args);
    if (all.isEmpty()) {
      return all;
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
   * {@code dump} 的第二参数补全（纯函数，可离线单测）。
   *
   * <p>只认 {@code local} 一个词：这是「仅本地采样」的唯一入口，多给别名只会让「到底上传没上传」
   * 变得需要记忆。前缀匹配与执行侧同样大小写不敏感。
   */
  static List<String> suggestDumpArguments(String[] args) {
    if (args == null || args.length != 2 || !"dump".equalsIgnoreCase(args[0])) {
      return List.of();
    }
    String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
    return "local".startsWith(prefix) ? List.of("local") : List.of();
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
      // 二级参数（dump local）不在本重载的职责里，见 suggestDumpArguments
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

  /**
   * 按原因作废在途会话（停用 / 热重载由插件层经 {@link MikuXrayNet#abortDumpSession} 调用）。
   *
   * <p><b>停用时不再回显</b>：Bukkit 在调用 {@code onDisable()} 之前已把 {@code isEnabled()} 置为
   * {@code false}，此刻向玩家回显要靠一次可能被拒绝的调度；热重载则仍然活着，发起人应该知道他那一份
   * dump 已经作废（否则他会一直等一个永远不会出现的文件路径）。
   *
   * <p><b>为什么是 public</b>：调用方 {@code CommandRegistrar} 在父包（{@code net.mikumc.mikuxraynet}），
   * 跨包访问不了包可见方法；它本身不对外暴露命令语义，只是生命周期钩子。
   */
  public void abortDump(String reason) {
    DumpSession session = activeDump.get();
    if (session == null) {
      return;
    }
    session.abort(reason, plugin.isEnabled());
  }

  /**
   * 发起 dump 的玩家断线：立刻作废在途会话并释放闸门。
   *
   * <p><b>为什么断线要作废</b>：会话的产物只有两样——文件路径与链接，两者都要回显给发起者。人走了以后
   * 继续把闸门占住最多 {@link #SPARK_MAX_WAIT_SECONDS} 秒，等于让下一位管理员在这段时间里无法取证。
   * 断线本身不是事故，因此不回显（此刻也没有可用的回显对象）。
   *
   * <p>只认<b>同一个发起者</b>（按 UUID）：别人退出不该把在途 dump 一起作废。
   *
   * <p><b>为什么是 public</b>：断线事件由父包的 {@code CommandRegistrar} 注册的监听器转发进来。
   */
  public void onSenderDisconnect(Player player) {
    if (player == null) {
      return;
    }
    DumpSession session = activeDump.get();
    if (session == null || !session.fromSender(player)) {
      return;
    }
    session.abort("发起者已断线", false);
  }
}
