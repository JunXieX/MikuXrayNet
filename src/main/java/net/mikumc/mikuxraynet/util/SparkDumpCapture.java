package net.mikumc.mikuxraynet.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * 触发一次 <b>可关联的 Spark 采样</b>：跟随 Spark 自己的命令把它定时落盘，再按随机标记认出「本次」的文件，
 * 由本插件自行上传到 Spark 官方查看器，最后返回一段固定格式的中文文本（{@code /mxnet dump} 直接拼接）。
 *
 * <p><b>为什么不走 Spark 的公开 API</b>：{@code me.lucko.spark.api.Spark} 只暴露统计（TPS/MSPT 等）。
 * 启动采样器只存在于命令层；而命令回复经过 Bukkit {@code CommandSender} 包装（内部弱引用），
 * 异步完成时的链接既拿不可靠、也不该被当作结果来源。因此这里只借 Spark 的「命令 + 落盘」，
 * 归属判定与上传都由我们自己完成。
 *
 * <p><b>线程纪律</b>：{@link #start()} 立即返回，绝不做 IO：命令派发投到全局区域调度器
 * （Paper = 主线程、Folia = 全局区域线程），文件轮询与 HTTP 上传投到异步调度器。
 * 全程不 sleep、不阻塞区域线程 / Netty，轮询频率与次数都有上限。
 *
 * <p><b>可用性</b>：{@link #available()} 零 IO 同步判定，但只代表「有 Spark 可试」；真正的结论永远是
 * 「匹配到本次文件的 metadata.comment + 上传成功」，命令回执仅作提示。
 *
 * <p><b>会带来的副作用（务必明示）</b>：Spark 在启动一次带超时的采样前，会先停掉它自己的内置后台采样器
 * （{@code SamplerModule#profilerStart}），而重启后台采样器只发生在手动 {@code /spark profiler stop} 分支里；
 * 也就是说本功能跑过一次后，内置后台采样器不会自动恢复，需要重启服务器。任何情况下都不会向 Spark
 * 发送 stop / cancel，也不会打断管理员手工启动的采样。
 *
 * <p><b>数据边界</b>：只读 {@code <plugins>/spark}（或独立 spark 插件的实际数据目录）里、comment 严格等于
 * 本次标记的那一个文件；不解析日志、不跟随软链接、不上传 dump 本体或任何令牌；链接只出现在返回给执行者的
 * 文本里（日志最多记状态，不记 URL）。
 */
public final class SparkDumpCapture {

  /** 本次采样窗口（秒）。取 30 秒是 Spark 自己的建议阈值：低于 30 秒它会提示结果精度不足。 */
  public static final int PROFILE_SECONDS = 30;

  /** 一次采样的总时限（秒）：等待落盘 + 上传。超过即 fail-soft 返回「未完成」。 */
  public static final int MAX_WAIT_SECONDS = 65;

  /** 轮询 Spark 目录的周期（毫秒）。 */
  private static final long POLL_PERIOD_MILLIS = 500L;

  /**
   * 留给上传的时间预算（秒）。
   *
   * <p>等待落盘的上限 = {@link #MAX_WAIT_SECONDS} 减去它：Spark 在 {@link #PROFILE_SECONDS} 秒后写完文件，
   * 45 秒的窗口足够覆盖一次慢磁盘/卡顿；剩下的 20 秒正好是「连接 10 秒 + 读取 10 秒」的 HTTP 预算，
   * 因此整条命令的最坏耗时仍然落在 {@link #MAX_WAIT_SECONDS} 秒这个对外口径内。
   */
  private static final int UPLOAD_BUDGET_SECONDS = 20;

  /** 等待落盘的毫秒上限。 */
  static final long FILE_WAIT_MILLIS = (MAX_WAIT_SECONDS - UPLOAD_BUDGET_SECONDS) * 1000L;

  /** 命令派发后多久检查一次回执提示（毫秒）：派发本身是同步执行完的，留一点余量给调度延迟。 */
  private static final long HINT_CHECK_MILLIS = 1250L;

  /** 候选文件的时间下界余量（毫秒）：只接受「本次开始前两分钟内」修改过的文件，避免误读旧采样。 */
  private static final long SESSION_SLACK_MILLIS = 120_000L;

  /** 每轮扫描最多处理多少个候选（解析头部的次数因此有界）。 */
  private static final int MAX_CANDIDATES_PER_SCAN = 8;

  /** 已解析并排除过的候选上限（文件名 + 修改时间去重）。 */
  private static final int MAX_INSPECTED = 256;

  /** 回执提示最多记多少行、每行多长（纯提示用途，内存有界）。 */
  private static final int MAX_FEEDBACK_LINES = 24;
  private static final int MAX_FEEDBACK_CHARS = 200;

  /** 返回文本的标题行：调用方按这一行定位整段（前导换行让拼接处不漏行）。 */
  private static final String TITLE = "\n---- Spark 采样 ----\n";

  /** 默认上传端点（Spark 官方；与 SparkPlatform 的默认配置一致）。 */
  private static final String DEFAULT_BYTEBIN = "https://spark-usercontent.lucko.me/";

  /** 默认查看器端点。 */
  private static final String DEFAULT_VIEWER = "https://spark.lucko.me/";

  /** HTTP 超时与响应读取上限。 */
  private static final int HTTP_CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int HTTP_READ_TIMEOUT_MILLIS = 10_000;
  private static final int MAX_RESPONSE_BYTES = 8 * 1024;

  /** spark 的 config.json 读取上限（只看两个端点字符串）。 */
  private static final int MAX_CONFIG_BYTES = 64 * 1024;

  /** bytebin 返回的 key 形态：只接受字母数字，长度有界，避免把任意 URL 拼进回显。 */
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9]{4,64}");

  /** 采样内容类型（bytebin 要求与 Spark 一致，否则查看器打不开）。 */
  private static final String CONTENT_TYPE = "application/x-spark-sampler";

  /** 后台采样器副作用的固定说明（服主可见，会写进 dump 文件）。 */
  static final String BACKGROUND_NOTE =
      "Spark 开始本次采样前会停掉它自己的内置后台采样器（若有），且不会自动重启；需要恢复请重启服务器。";

  /** 因「Spark 不可用」而不执行的固定说明。 */
  private static final String NOT_REQUESTED_NOTE = "本次不请求 Spark、不上传任何数据。";

  private final Plugin plugin;
  private final CompletableFuture<String> result = new CompletableFuture<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  /**
   * 结果落定闸门：<b>同一次采样只允许一个收尾路径</b>。
   *
   * <p>存在的理由不是防御「调度器会不会并发」，而是防御<b>有公网副作用</b>的动作重复发生：轮询一旦找到文件
   * 就会上传，若此时超时/取消路径又完成一次 stage，就会出现两次上传或「先失败后成功」的矛盾回显。
   * 所有收尾（上传、超时、错误、快速失败、取消）都必须先过 {@link #claimOnce(AtomicBoolean)}。
   */
  private final AtomicBoolean settled = new AtomicBoolean();
  private final List<String> feedback = Collections.synchronizedList(new ArrayList<>());
  /** 已解析并排除过的候选（文件名@修改时间）：同一文件不重复读，IO 有界。 */
  private final java.util.Set<String> inspected =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  private volatile ScheduledTask pollTask;
  private volatile String marker = "";
  private volatile long startedAtMillis;
  private volatile boolean hintChecked;

  /**
   * @param plugin 插件实例（用于调度与日志）；为 {@code null} 时 {@link #start()} 直接返回「未执行」，
   *               构造本身不做任何 IO、不碰 Bukkit
   */
  public SparkDumpCapture(Plugin plugin) {
    this.plugin = plugin;
  }

  /**
   * 是否「有 Spark 可试」：零 IO、任意线程、不抛。
   *
   * <p>判据（均有上游源码依据）：
   * <ol>
   *   <li>独立 spark 插件已启用 → 可用；</li>
   *   <li>否则非 Folia 端且命令表里有 {@code spark}（Paper 26.2 的内置 spark 由
   *       {@code io.papermc.paper.SparksFly} 注册进 CommandMap）→ 可用；</li>
   *   <li>Folia 端没有独立 spark 插件 → 不可用：Folia 的补丁把内置 spark 显式禁用
   *       （{@code enable()} 内为 {@code if (false)}、{@code isPluginPreferred()} 恒 true），
   *       此时 {@code /spark} 只会回「currently disabled」。</li>
   * </ol>
   *
   * <p>本方法只是<b>省的等了才发现</b>的前置判断：Paper 侧若在全局配置里关掉了 spark，
   * 仍可能返回 true，那种情况由 {@link #start()} 的回执提示与「等不到文件」快速失败兜住。
   */
  public static boolean available() {
    try {
      Server server = Bukkit.getServer();
      if (server == null) {
        return false;
      }
      PluginManager manager = server.getPluginManager();
      Plugin spark = manager == null ? null : manager.getPlugin("spark");
      if (spark != null) {
        return spark.isEnabled();
      }
      if (PlatformSupport.isFolia()) {
        return false;
      }
      CommandMap commands = server.getCommandMap();
      return commands != null && commands.getCommand("spark") != null;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * 启动一次采样并立即返回。
   *
   * <p>返回的 {@link CompletionStage} <b>永不异常完成</b>：最迟约 {@link #MAX_WAIT_SECONDS} 秒以一段固定格式的
   * 中文文本完成（以 {@code \n---- Spark 采样 ----\n} 开头、以换行结尾），内容只有窗口、状态、上传后的完整链接
   * 与必要的说明；不含本地路径、标记或令牌。重复调用返回同一个 stage。
   */
  public CompletionStage<String> start() {
    if (!this.started.compareAndSet(false, true)) {
      return this.result;
    }
    try {
      this.startedAtMillis = System.currentTimeMillis();
      this.marker = SparkProfileFiles.newMarker();
      if (this.closed.get()) {
        settle(render("未执行（已取消）", null, null));
        return this.result;
      }
      if (this.plugin == null) {
        settle(render("未执行（服务端尚未就绪）", null, NOT_REQUESTED_NOTE));
        return this.result;
      }
      Server server = Bukkit.getServer();
      if (server == null) {
        settle(render("未执行（服务端尚未就绪）", null, NOT_REQUESTED_NOTE));
        return this.result;
      }
      if (!available()) {
        settle(render("未执行（本服没有可用的 Spark）", null, NOT_REQUESTED_NOTE));
        return this.result;
      }
      scheduleDispatch(server);
    } catch (Throwable throwable) {
      settle(render("未执行（提交采样请求时出错：" + describe(throwable) + "）", null, NOT_REQUESTED_NOTE));
    }
    return this.result;
  }

  /**
   * 释放本次采样的自有资源：取消轮询、以「已取消」完成未完成的 stage。
   *
   * <p>幂等、任意线程可调；<b>只碰自己</b>——绝不向 Spark 发送 stop / cancel，也不会读取或删除任何
   * 非本次匹配到的文件。
   *
   * <p><b>与上传的交界</b>：若关闭恰好发生在「已找到本次文件、正在上传」的瞬间，那次上传会继续跑完并
   * 作为唯一一次收尾完成 stage（{@code claimOnce} 已占闸门，{@code close()} 不会改为「已取消」，
   * 也不会再次上传）。调用方按自己的 CAS 收结果即可，迟到完成不会造成重复副作用。
   */
  public void close() {
    if (!this.closed.compareAndSet(false, true)) {
      return;
    }
    cancelPoll();
    settle(render("已取消（插件停用或调用方放弃等待）", null, null));
  }

  // ------------------------------------------------------------------
  // 派发：只在全局区域线程上碰 Bukkit 命令
  // ------------------------------------------------------------------

  private void scheduleDispatch(Server server) {
    Runnable dispatch = this::dispatch;
    try {
      server.getGlobalRegionScheduler().execute(this.plugin, dispatch);
    } catch (Throwable throwable) {
      // 调度器不可用（停用瞬间等）：直接在当前线程试一次；再失败就 fail-soft，绝不外抛
      try {
        dispatch.run();
      } catch (Throwable ignored) {
        settle(render("未执行（无法提交 Spark 采样命令）", null, NOT_REQUESTED_NOTE));
        return;
      }
    }
    startPolling(server);
  }

  /** 派发 {@code spark profiler start ...}；回执只喂给提示缓冲，不作为结果来源。 */
  private void dispatch() {
    if (this.closed.get()) {
      return;
    }
    try {
      Server server = Bukkit.getServer();
      if (server == null) {
        settle(render("未执行（服务端尚未就绪）", null, NOT_REQUESTED_NOTE));
        return;
      }
      CommandMap commands = server.getCommandMap();
      if (commands == null || commands.getCommand("spark") == null) {
        settle(render("未执行（本服没有 spark 命令）", null, NOT_REQUESTED_NOTE));
        return;
      }
      java.util.function.Consumer<Component> forwarded = this::captureFeedback;
      CommandSender sender = server.createCommandSender(forwarded);
      boolean accepted = commands.dispatch(sender, commandLine(this.marker));
      if (!accepted) {
        settle(render("未执行（spark 命令拒绝了本次请求）", null, NOT_REQUESTED_NOTE));
        return;
      }
      log(Level.FINE, "已提交 Spark 采样（窗口 " + PROFILE_SECONDS + " 秒），等待落盘");
    } catch (Throwable throwable) {
      settle(render("未执行（派发 Spark 命令失败：" + describe(throwable) + "）", null, NOT_REQUESTED_NOTE));
    }
  }

  /** 本次采样使用的命令行（固定形态；标记由 {@link SparkProfileFiles#newMarker()} 生成）。 */
  static String commandLine(String marker) {
    return "spark profiler start --thread * --interval 10 --timeout " + PROFILE_SECONDS
        + " --save-to-file --comment " + marker;
  }

  // ------------------------------------------------------------------
  // 等待落盘：异步轮询，有次数与时长上限
  // ------------------------------------------------------------------

  private void startPolling(Server server) {
    if (this.result.isDone()) {
      // 派发阶段已经给出结论（例如没有 spark 命令）：不必再轮询
      return;
    }
    try {
      ScheduledTask task = server.getAsyncScheduler().runAtFixedRate(this.plugin,
          scheduled -> poll(), POLL_PERIOD_MILLIS, POLL_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
      this.pollTask = task;
      if (this.closed.get()) {
        cancelPoll();
      }
    } catch (Throwable throwable) {
      settle(render("未执行（无法提交采样轮询任务：" + describe(throwable) + "）", null, NOT_REQUESTED_NOTE));
    }
  }

  private void poll() {
    try {
      if (this.closed.get() || this.result.isDone()) {
        cancelPoll();
        return;
      }
      long elapsed = System.currentTimeMillis() - this.startedAtMillis;
      if (!this.hintChecked && elapsed >= HINT_CHECK_MILLIS) {
        this.hintChecked = true;
        Optional<String> quick = quickFailure();
        if (quick.isPresent()) {
          settle(quick.get());
          return;
        }
      }
      Path file = locateOwnedProfile();
      if (file != null) {
        // 先占闸门再上传：上传有公网副作用，绝不允许超时/取消路径与它重复收尾
        if (!claimOnce(this.settled)) {
          return;
        }
        if (this.closed.get()) {
          complete(render("已取消（已找到本次采样文件，但调用方已放弃，未上传）", null, null));
          return;
        }
        complete(uploadAndDescribe(file));
        return;
      }
      if (elapsed >= FILE_WAIT_MILLIS) {
        settle(render("未完成（等待 Spark 生成采样文件超时）", null, BACKGROUND_NOTE));
      }
    } catch (Throwable throwable) {
      settle(render("未完成（处理 Spark 采样时出错：" + describe(throwable) + "）", null, BACKGROUND_NOTE));
    }
  }

  /**
   * 回执提示的快速失败：Spark 自己会拒绝的两种情况，无需等满整个窗口。
   *
   * <p><b>只作提示</b>：这两条分支都只把「已经被明确拒绝」的情形提前收尾；真正的结论仍以文件匹配为准。
   */
  private Optional<String> quickFailure() {
    List<String> lines = snapshotFeedback();
    if (hintsDisabled(lines)) {
      return Optional.of(render("未执行（Spark 已被服务端的全局配置禁用）", null, NOT_REQUESTED_NOTE));
    }
    if (hintsBusy(lines)) {
      return Optional.of(render("未执行（已有 Spark 采样在进行，未打断它）", null, NOT_REQUESTED_NOTE));
    }
    return Optional.empty();
  }

  /** 是否收到「Spark 未启用」的回执（Paper 内置 spark 的 CommandImpl 在禁用时只回这一句）。 */
  static boolean hintsDisabled(List<String> lines) {
    return containsAny(lines, "currently disabled", "will not be enabled");
  }

  /** 是否收到「已有采样在进行」的回执（非 background 的采样会让 profilerStart 直接返回）。 */
  static boolean hintsBusy(List<String> lines) {
    return containsAny(lines, "profiler is already running");
  }

  private static boolean containsAny(List<String> lines, String... needles) {
    if (lines == null || lines.isEmpty()) {
      return false;
    }
    for (String line : lines) {
      if (line == null) {
        continue;
      }
      String lower = line.toLowerCase(Locale.ROOT);
      for (String needle : needles) {
        if (lower.contains(needle)) {
          return true;
        }
      }
    }
    return false;
  }

  private void captureFeedback(Component component) {
    try {
      if (this.feedback.size() >= MAX_FEEDBACK_LINES) {
        return;
      }
      String text = component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
      if (text == null || text.isBlank()) {
        return;
      }
      String trimmed = text.length() > MAX_FEEDBACK_CHARS ? text.substring(0, MAX_FEEDBACK_CHARS) : text;
      this.feedback.add(trimmed);
    } catch (Throwable ignored) {
      // 回执只是提示：解析失败不影响主流程
    }
  }

  private List<String> snapshotFeedback() {
    synchronized (this.feedback) {
      return this.feedback.isEmpty() ? List.of() : List.copyOf(this.feedback);
    }
  }

  // ------------------------------------------------------------------
  // 目录解析：独立插件用它的数据目录，Paper 内置用 <plugins>/spark
  // ------------------------------------------------------------------

  /** 本机可能存放 Spark 采样文件的目录（有序、去重、最多两个）。 */
  static List<Path> candidateDirectories(Path pluginDataFolder, Path pluginsFolder) {
    List<Path> directories = new ArrayList<>(2);
    if (pluginDataFolder != null) {
      directories.add(pluginDataFolder.toAbsolutePath().normalize());
    }
    if (pluginsFolder != null) {
      Path builtIn = pluginsFolder.toAbsolutePath().normalize().resolve("spark");
      if (!directories.contains(builtIn)) {
        directories.add(builtIn);
      }
    }
    return List.copyOf(directories);
  }

  /** 解析当前环境下的候选目录（不抛、不因缺失而失败）。 */
  private List<Path> directories() {
    Path pluginDataFolder = null;
    Path pluginsFolder = null;
    try {
      Server server = Bukkit.getServer();
      if (server != null) {
        PluginManager manager = server.getPluginManager();
        Plugin spark = manager == null ? null : manager.getPlugin("spark");
        if (spark != null) {
          File folder = spark.getDataFolder();
          if (folder != null) {
            pluginDataFolder = folder.toPath();
          }
        }
        File plugins = server.getPluginsFolder();
        if (plugins != null) {
          pluginsFolder = plugins.toPath();
        }
      }
    } catch (Throwable ignored) {
      // 取不到就靠下面的兜底
    }
    if (pluginsFolder == null && this.plugin != null) {
      try {
        File own = this.plugin.getDataFolder();
        if (own != null && own.getParentFile() != null) {
          pluginsFolder = own.getParentFile().toPath();
        }
      } catch (Throwable ignored) {
        // 兜底也失败：只剩候选为空
      }
    }
    return candidateDirectories(pluginDataFolder, pluginsFolder);
  }

  /**
   * 在当前候选目录里找本次匹配的采样文件；找不到返回 {@code null}。
   *
   * <p><b>IO 有界</b>：每轮只取「修改时间不早于本次开始前两分钟」的少量候选，且已解析并排除过的
   * （文件名 + 修改时间）不再重读——一次采样期间对同一个别人的文件只会读一次头部。
   */
  private Path locateOwnedProfile() {
    long notBefore = this.startedAtMillis - SESSION_SLACK_MILLIS;
    for (Path directory : directories()) {
      List<SparkProfileFiles.Candidate> candidates =
          SparkProfileFiles.candidates(directory, notBefore, MAX_CANDIDATES_PER_SCAN);
      for (SparkProfileFiles.Candidate candidate : candidates) {
        String key = candidate.path().getFileName() + "@" + candidate.modifiedMillis();
        if (!this.inspected.add(key)) {
          continue;
        }
        if (this.inspected.size() > MAX_INSPECTED) {
          this.inspected.clear();
        }
        if (SparkProfileFiles.matches(candidate.path(), this.marker)) {
          return candidate.path();
        }
      }
    }
    return null;
  }

  // ------------------------------------------------------------------
  // 上传：固定官方 HTTPS、有限请求/响应、不开重定向
  // ------------------------------------------------------------------

  /** 上传端点与查看器端点（已校验过形态）。 */
  record Endpoints(String uploadUrl, String viewerUrl) {
  }

  /** 由配置里的原始值解析端点：缺失或不合规一律回落官方默认。 */
  static Endpoints resolveEndpoints(String bytebinRaw, String viewerRaw) {
    String bytebin = normalizeBase(bytebinRaw, DEFAULT_BYTEBIN);
    String viewer = normalizeBase(viewerRaw, DEFAULT_VIEWER);
    return new Endpoints(bytebin + "post", viewer);
  }

  /**
   * 把配置里的基地址收敛成「https、有主机名、以 / 结尾」的形态；不合规回落 {@code fallback}。
   *
   * <p>只接受 https：这是把性能数据送出服务器的动作，明文端点一律不认。
   */
  static String normalizeBase(String raw, String fallback) {
    if (raw == null) {
      return fallback;
    }
    String value = raw.trim();
    if (value.isEmpty() || value.length() > 300) {
      return fallback;
    }
    try {
      URI uri = URI.create(value);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
        return fallback;
      }
      if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        return fallback;
      }
      return value.endsWith("/") ? value : value + "/";
    } catch (RuntimeException invalid) {
      return fallback;
    }
  }

  /** 读取 {@code <spark 目录>/config.json} 里运营者自定义的端点（只认 https，读不到就回落默认）。 */
  private Endpoints endpoints(Path sparkDirectory) {
    String bytebin = null;
    String viewer = null;
    if (sparkDirectory != null) {
      Path config = sparkDirectory.resolve("config.json");
      try {
        if (!Files.isSymbolicLink(config) && Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
          long size = Files.size(config);
          if (size > 0L && size <= MAX_CONFIG_BYTES) {
            String json = Files.readString(config, StandardCharsets.UTF_8);
            bytebin = jsonString(json, "bytebinUrl");
            viewer = jsonString(json, "viewerUrl");
          }
        }
      } catch (IOException | RuntimeException unreadable) {
        // 配置读不了不影响上传：用官方默认
      }
    }
    return resolveEndpoints(bytebin, viewer);
  }

  /**
   * 从 JSON 文本里取一个<b>字符串字段</b>的原始值（不求完备解析：只认最简单的 {@code "key": "value"}）。
   *
   * <p>刻意不接受转义序列：出现反斜杠就放弃该键（回落默认端点），避免把奇怪的值带进 URL。
   */
  static String jsonString(String json, String key) {
    if (json == null || key == null || json.isEmpty()) {
      return null;
    }
    String needle = "\"" + key + "\"";
    int from = 0;
    while (true) {
      int at = json.indexOf(needle, from);
      if (at < 0) {
        return null;
      }
      int cursor = at + needle.length();
      while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
        cursor++;
      }
      if (cursor >= json.length() || json.charAt(cursor) != ':') {
        from = at + needle.length();
        continue;
      }
      cursor++;
      while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
        cursor++;
      }
      if (cursor >= json.length() || json.charAt(cursor) != '"') {
        return null;
      }
      int start = cursor + 1;
      int end = json.indexOf('"', start);
      if (end < 0 || end - start > 300) {
        return null;
      }
      String value = json.substring(start, end);
      return value.indexOf('\\') >= 0 ? null : value;
    }
  }

  /** bytebin 的 key 取自响应头 {@code Location}；只接受保守形态，其它一律视为失败。 */
  static Optional<String> keyFromLocation(String location) {
    if (location == null) {
      return Optional.empty();
    }
    String value = location.trim();
    while (value.startsWith("/")) {
      value = value.substring(1);
    }
    if (value.isEmpty() || !KEY.matcher(value).matches()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  /** 查看器链接：{@code viewerUrl + key}。 */
  static String link(String viewerUrl, String key) {
    return viewerUrl + key;
  }

  /** 上传结果：成功带 key；失败带一句中文原因（不含 URL 细节，避免把端点写进 dump）。 */
  record Upload(String key, String reason) {

    boolean ok() {
      return this.key != null;
    }
  }

  private String uploadAndDescribe(Path file) {
    long size;
    try {
      size = Files.size(file);
    } catch (IOException | RuntimeException unreadable) {
      return render("未完成（读取本次采样文件大小失败）", null, BACKGROUND_NOTE);
    }
    if (!SparkProfileFiles.acceptableSize(size)) {
      return render("未完成（本次采样文件超出大小上限，未上传）", null, BACKGROUND_NOTE);
    }
    Endpoints endpoints = endpoints(file.getParent());
    Upload upload = upload(file, endpoints);
    if (upload.ok()) {
      return render("已上传", link(endpoints.viewerUrl(), upload.key()), BACKGROUND_NOTE);
    }
    return render("未完成（上传失败：" + upload.reason() + "）", null, BACKGROUND_NOTE);
  }

  private Upload upload(Path file, Endpoints endpoints) {
    HttpURLConnection connection = null;
    try {
      URL url = URI.create(endpoints.uploadUrl()).toURL();
      connection = (HttpURLConnection) url.openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MILLIS);
      connection.setReadTimeout(HTTP_READ_TIMEOUT_MILLIS);
      connection.setDoOutput(true);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", CONTENT_TYPE);
      connection.setRequestProperty("Content-Encoding", "gzip");
      connection.setRequestProperty("User-Agent", "MikuXrayNet-spark-capture");
      connection.setChunkedStreamingMode(8192);
      try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
          OutputStream output = new GZIPOutputStream(connection.getOutputStream())) {
        input.transferTo(output);
      }
      int code = connection.getResponseCode();
      String location = connection.getHeaderField("Location");
      drain(connection, code);
      if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_CREATED) {
        return new Upload(null, "服务端返回 " + code);
      }
      Optional<String> key = keyFromLocation(location);
      return key.map(value -> new Upload(value, null))
          .orElseGet(() -> new Upload(null, "上传响应里没有可用的 key"));
    } catch (IOException | RuntimeException failure) {
      return new Upload(null, describe(failure));
    } finally {
      if (connection != null) {
        try {
          connection.disconnect();
        } catch (Throwable ignored) {
          // 断开失败无需处理
        }
      }
    }
  }

  /** 有界丢弃响应体（不解析内容，只为让连接正常收尾）。 */
  private static void drain(HttpURLConnection connection, int code) {
    try (InputStream body = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
      if (body == null) {
        return;
      }
      byte[] buffer = new byte[512];
      int total = 0;
      int count;
      while (total < MAX_RESPONSE_BYTES && (count = body.read(buffer)) > 0) {
        total += count;
      }
    } catch (IOException | RuntimeException ignored) {
      // 响应体读不到不影响结论（结论只看状态码与 Location）
    }
  }

  // ------------------------------------------------------------------
  // 结果文本与收尾
  // ------------------------------------------------------------------

  /**
   * 渲染返回给执行者的整段文本。
   *
   * <p>格式固定（调用方与文档都按它对齐）：标题行 → 窗口 → 状态 →（上传成功时）完整链接 →（必要时）说明。
   * 以换行结尾，便于直接拼进 dump 文本。
   */
  static String render(String state, String link, String note) {
    StringBuilder builder = new StringBuilder(320);
    builder.append(TITLE);
    builder.append("窗口：").append(PROFILE_SECONDS)
        .append(" 秒（Spark 原生采样：全部线程、间隔 10 毫秒、由 Spark 到期自动落盘）").append('\n');
    builder.append("状态：").append(state == null || state.isBlank() ? "未完成（未知原因）" : state).append('\n');
    if (link != null && !link.isBlank()) {
      builder.append("完整链接：").append(link).append('\n');
    }
    if (note != null && !note.isBlank()) {
      builder.append("说明：").append(note).append('\n');
    }
    return builder.toString();
  }

  /**
   * 统一收尾：只有第一个到达的路径有权完成 stage。
   *
   * <p>轮询、超时、快速失败、取消、错误都会走这里；{@code claimOnce} 保证上传这种有公网副作用的动作
   * 至多发生一次。
   */
  private void settle(String text) {
    if (!claimOnce(this.settled)) {
      return;
    }
    complete(text);
  }

  /**
   * 一次性占位（CAS）：并发/多路径收尾时只有第一个返回 {@code true}。
   *
   * <p>包内可见且独立成方法，是为了让「上传至多一次」这条语义能离线单测——它不依赖调度器是否允许并发，
   * 也不依赖服务端实例。
   */
  static boolean claimOnce(AtomicBoolean gate) {
    return gate != null && gate.compareAndSet(false, true);
  }

  private void complete(String text) {
    cancelPoll();
    try {
      this.result.complete(text == null || text.isBlank() ? render(null, null, null) : text);
    } catch (Throwable ignored) {
      // CompletableFuture#complete 不会抛；这里只是防御
    }
  }

  private void cancelPoll() {
    ScheduledTask task = this.pollTask;
    this.pollTask = null;
    if (task != null) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已结束
      }
    }
  }

  private void log(Level level, String message) {
    Plugin current = this.plugin;
    if (current == null) {
      return;
    }
    try {
      current.getLogger().log(level, message);
    } catch (Throwable ignored) {
      // 日志失败不影响采样
    }
  }

  /** 把异常压成一行短原因（不带堆栈、不带换行，避免回显刷屏）。 */
  static String describe(Throwable throwable) {
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
    String oneLine = detail.replace('\n', ' ').replace('\r', ' ').trim();
    return oneLine.length() > 120 ? oneLine.substring(0, 120) : oneLine;
  }
}
