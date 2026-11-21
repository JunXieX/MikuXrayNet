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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><b>怎么知道文件在哪（三条路径，优先级从高到低）</b>
 * <ol>
 *   <li><b>回执</b>：Spark 落盘后会回 {@code Data has been written to: <path>}；这条路径最直接，
 *       但前提是我们把命令的 {@code CommandSender} <b>强引用</b>住（Spark 内部只持弱引用，30 秒后回执会被 GC）；</li>
 *   <li><b>账本</b>：Spark 每次落盘都往 {@code <spark 数据目录>/activity.json} 追加一条
 *       {@code {time, type:"Profiler", data:{type:"file", value:"<相对服务器根的路径>"}}}，
 *       这条路不依赖 sender 是否还活着，是主路径；</li>
 *   <li><b>目录轮询</b>：定制构建不写账本时的兜底，按「修改时间窗口 + 注释逐字节相等」认领。</li>
 * </ol>
 * 三条路得到的路径都要过同一组<b>四道校验</b>（真实路径在 Spark 目录内 / 文件名合规 / 0&lt;大小≤上限 /
 * 注释与本轮标记逐字节相等，见 {@link SparkProfileFiles#verifyOwnedProfile(List, Path, String)}）才会被上传。
 *
 * <p><b>失败必须能自解释</b>：等到超时/上传失败时，返回段会附上自诊断（Spark 最近几句回执、候选目录里
 * 本次开始之后的新文件及其大小与注释比对、账本里的本次新条目）。三种完全不同的原因——没落盘、落盘了但被拒、
 * 落盘了但不是本次——因此在 dump 里不再长成同一句话。
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
 * 本次标记的那一个文件，以及同目录下 spark 自己的 {@code activity.json}（只读、不跟随软链接）；不解析日志、
 * 不上传 dump 本体或任何令牌；链接只出现在返回给执行者的文本里（日志最多记状态，不记 URL）。
 */
public final class SparkDumpCapture {

  /** 本次采样窗口（秒）。取 30 秒是 Spark 自己的建议阈值：低于 30 秒它会提示结果精度不足。 */
  public static final int PROFILE_SECONDS = 30;

  /**
   * 采样结束到「应该落盘」之间的宽限（秒）。
   *
   * <p>Spark 的 finalize 时长没有上界：零人小服实测 2~3 秒，几十人在线的生产服可能久得多。
   * 因此这里<b>与 {@code --timeout} 解耦</b>，单独给 60 秒——旧口径把「30 秒采样 + 15 秒余量」写死成 45 秒，
   * 正好卡在「落盘比预期慢一点」的边缘上，于是生产上看到的全是同一句「超时」。
   */
  static final int SAVE_GRACE_SECONDS = 60;

  /** 等待落盘的毫秒上限（采样窗口 + 宽限）。 */
  static final long FILE_WAIT_MILLIS = (PROFILE_SECONDS + SAVE_GRACE_SECONDS) * 1000L;

  /**
   * 留给上传的时间预算（秒）。
   *
   * <p>整条命令的最坏耗时 = {@link #FILE_WAIT_MILLIS} + 本预算，也就是 {@link #MAX_WAIT_SECONDS}：
   * 连接 10 秒 + 读取 10 秒的 HTTP 预算。真正的失败（Spark 自报失败、命令没进 spark）会被
   * {@link #quickFailure()} 与「无任何回执」判定提前收尾，不必等满这个上限。
   */
  private static final int UPLOAD_BUDGET_SECONDS = 20;

  /** 一次采样的总时限（秒）：等待落盘 + 上传。超过即 fail-soft 返回「未完成」。 */
  public static final int MAX_WAIT_SECONDS = PROFILE_SECONDS + SAVE_GRACE_SECONDS + UPLOAD_BUDGET_SECONDS;

  /** 轮询 Spark 目录的周期（毫秒）。 */
  private static final long POLL_PERIOD_MILLIS = 500L;

  /**
   * 派发完成后等多久才判定「Spark 一句话都没回」（毫秒）。
   *
   * <p>Spark 的回执是在 {@code dispatch} 内<b>同步</b>发出的，正常情况派发返回时缓冲里就有了
   * {@code Profiler is now running!}。留 2.5 秒只是给「同一 tick 里其它插件也在往 sender 里塞东西」留余量；
   * 这一步只处理「命令根本没进 spark」（被别名劫持、插件自我禁用）的病态情形，命中时状态是
   * {@code 未执行}，调用方照样会把窗口收回本地口径。
   */
  private static final long HINT_SETTLE_MILLIS = 2500L;

  /** 候选文件的时间下界余量（毫秒）：只接受「本次开始前两分钟内」修改过的文件，避免误读旧采样。 */
  private static final long SESSION_SLACK_MILLIS = 120_000L;

  /**
   * 判「稳定无关文件」时必须比本次开始时刻再往前退的容差（毫秒）。
   *
   * <p><b>为什么不能直接比 {@code mtime < startedAt}</b>：文件时间戳的精度不一定是毫秒。Linux CI 与容器
   * 常见的时间戳粒度是 1 秒，{@code Files.getLastModifiedTime(...).toMillis()} 会把「本次会话内刚写出的文件」
   * 向下取整到秒，于是它看起来比 {@code startedAt} 还早。若据此把它当成「历史垃圾」登记去重键，
   * 这个<b>正在写</b>的文件就再也读不到了（半写文件重试的契约被破坏）。
   *
   * <p>2 秒足够覆盖 1 秒粒度 + 时钟抖动；真正早于本次采样的历史文件是分钟/小时级，不受影响。
   */
  static final long STABLE_MTIME_SLACK_MILLIS = 2_000L;

  /** 账本时间的容差（毫秒）：取「不早于本次开始前 5 秒」的记录，容忍两次读时钟的抖动。 */
  private static final long ACTIVITY_SLACK_MILLIS = 5_000L;

  /** 每轮扫描最多处理多少个候选（解析头部的次数因此有界）。 */
  private static final int MAX_CANDIDATES_PER_SCAN = 8;

  /** 已解析并排除过的候选上限（文件名 + 修改时间 + 字节数去重）。 */
  private static final int MAX_INSPECTED = 256;

  /** 一次账本读取最多取多少条记录（只为找到「本次」那一条）。 */
  private static final int MAX_ACTIVITY_ENTRIES = 8;

  /** 回执提示最多记多少行、每行多长（纯提示用途，内存有界）。 */
  private static final int MAX_FEEDBACK_LINES = 24;
  private static final int MAX_FEEDBACK_CHARS = 200;

  /** 自诊断的边界：回执行数、每个目录的文件数、整段行数、单行字符数。 */
  private static final int MAX_DIAGNOSTIC_RECEIPTS = 6;
  private static final int MAX_DIAGNOSTIC_FILES = 5;
  private static final int MAX_DIAGNOSTIC_LINES = 24;
  private static final int MAX_DIAGNOSTIC_CHARS = 200;

  /** 「路径被拒」那一行的前缀：它计入 {@link #MAX_DIAGNOSTIC_CHARS} 的单行预算，不额外超界。 */
  private static final String REJECTION_PREFIX = "路径校验：";

  /** 返回文本的标题行：调用方按这一行定位整段（前导换行让拼接处不漏行）。 */
  private static final String TITLE = "\n---- Spark 采样 ----\n";

  /**
   * 「本次 Spark 根本没跑」的唯一状态词前缀。
   *
   * <p>它同时决定返回段里的<b>窗口口径</b>：未执行时不存在任何 Spark 性能记录，窗口行必须写成
   * 「未采样」——否则 dump 会出现「Spark 段说 30 秒、同文件运行采样段是 10 秒」的自相矛盾
   * （对外的承诺是 30 秒窗口，收回 10 秒后绝不能仍宣称 30 秒）。
   */
  static final String NOT_STARTED = "未执行";

  /** 未启动时的窗口行：明说没有性能记录，绝不出现「30 秒 / 全部线程」字样。 */
  private static final String WINDOW_NOT_SAMPLED = "窗口：未采样（Spark 未启动，无性能记录）";

  /**
   * 已启动时的窗口行。
   *
   * <p>刻意<b>不写</b>「由 Spark 到期自动落盘」：失败/超时分支里那句话与「未完成」自相矛盾。
   * 落盘是 Spark 侧的动作，我们只承诺「采样窗口 30 秒、全部线程、间隔 10 毫秒」。
   */
  private static final String WINDOW_SAMPLED = "窗口：" + PROFILE_SECONDS
      + " 秒（Spark 原生采样：全部线程、间隔 10 毫秒；落盘由 Spark 侧完成）";

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

  /** 回执里「真实落盘路径」的前缀（Spark 的 SamplerModule 原文）。 */
  private static final String WRITTEN_PREFIX = "data has been written to:";

  /** 回执四态：被服务端全局配置禁用。 */
  static final String HINT_DISABLED = "disabled";

  /** 回执四态：已有采样在进行（我们没有打断它）。 */
  static final String HINT_BUSY = "busy";

  /** 回执四态：Spark 已明确启动（{@code Profiler is now running!}）。 */
  static final String HINT_STARTED = "started";

  /** 回执四态：Spark 一句话都没回（命令可能根本没进 spark）。 */
  static final String HINT_SILENT = "silent";

  /** 第五类（非四态之一）：Spark 自己报了错，直接收尾。 */
  static final String HINT_ERROR = "error";

  /**
   * Spark 自报失败里<b>无歧义、且一定意味着「不会有文件」</b>的短语，命中即收尾。
   *
   * <p>两条都取自 spark 1.10.180/1.10.187 的 {@code SamplerModule} 字符串常量（本机 jar 实测，
   * 含 Paper 内置 {@code spark-paper} 与独立 {@code spark-bukkit} 两份，措辞一致）：
   * <ul>
   *   <li>{@code An error occurred whilst saving the data.} —— 落盘失败，之后不会再产出文件；</li>
   *   <li>{@code Profiler operation failed unexpectedly}（另有带 {@code . Error: } 的变体）—— 采样器内部错误。</li>
   * </ul>
   */
  private static final String[] HARD_ERROR_PHRASES = {
      "an error occurred whilst saving",
      "profiler operation failed unexpectedly",
  };

  /**
   * <b>不</b>意味着本次采样失败、只能当线索的短语：<b>只有 Spark 从未确认启动</b>时才算失败。
   *
   * <p>上传失败就是这一类：spark 的同一条回执原文是
   * {@code An error occurred whilst uploading the results. Attempting to save to disk instead.}
   * ——它后面会<b>继续落盘</b>，紧接着回 {@code Data has been written to: …}。
   * 把它当终局失败收尾，就会在一次即将成功的采样上提前放弃（本功能最不能接受的回归）。
   * 「某项能力不支持」同理：采样照跑，只是那项能力不可用。
   */
  private static final String[] SOFT_ERROR_PHRASES = {
      "an error occurred whilst uploading",
      "error whilst uploading",
      "is not supported on your system",
  };

  /** ANSI 转义序列（控制台回执里可能带）：ESC + {@code [...]} + 收尾字母。 */
  private static final Pattern ANSI = Pattern.compile("\033\\[[0-9;]*[A-Za-z]");

  /** MiniMessage 风格标签：自诊断里不需要格式，剥掉避免把标签当成正文。 */
  private static final Pattern TAG = Pattern.compile("<[^<>\\s]{1,40}>");

  /** 任何形态的 URL：dump 里只允许「完整链接」这一行带链接，其余位置一律隐去。 */
  private static final Pattern URL = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://\\S+");

  /** 控制字符（含换行、制表与 ESC）：自诊断是单行文本。 */
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

  /** 连续空白：压成单个空格。 */
  private static final Pattern BLANKS = Pattern.compile("\\s+");

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
  /** 已解析并排除过的候选（文件名@修改时间@字节数）：同一份内容不重复读，IO 有界。 */
  private final Set<String> inspected = ConcurrentHashMap.newKeySet();
  /** 每个 activity.json 上一次解析时的戳：账本没变就不重复解析（轮询是 500 毫秒一拍）。 */
  private final Map<Path, SparkProfileFiles.Stamp> activityStamps = new ConcurrentHashMap<>();

  private volatile ScheduledTask pollTask;
  private volatile String marker = "";
  private volatile long startedAtMillis;
  /**
   * 命令的 {@link CommandSender} <b>强引用</b>。
   *
   * <p>Spark 侧用 {@code WeakReference<CommandSender>} 广播回执（{@code CommandResponseHandler}），
   * 一旦这里的局部变量出栈，30 秒后的 {@code The active profiler has completed!} /
   * {@code Data has been written to: …} / {@code An error occurred whilst saving the data.}
   * 就再也到不了我们手里。存成字段是本类唯一需要「主动记住」的东西，收尾时置 null。
   */
  private volatile CommandSender dispatchSender;
  /** 命令是否已派发完成（「Spark 连一句话都没回」的判定前提）。 */
  private volatile boolean dispatchDone;
  private volatile long dispatchDoneAtMillis;
  /** 回执四态是否已判定（只在派发完成后判一次）。 */
  private volatile boolean hintClassified;
  /** 判定出的回执四态（写进自诊断）。 */
  private volatile String hintState = "unknown";
  /**
   * <b>回执或账本</b>给出了路径、但没过四道校验时的最后一条原因（写进自诊断的「路径校验：」一行）。
   *
   * <p>只有这一条线索能解释「Spark 明明说写了文件，dump 却说没认领」：目录轮询看不到它（例如目录不可枚举、
   * 或定制构建把文件写在别的目录），账本行也可能根本没有本次条目。因此它必须独立渲染，而不是附在某一行后面。
   */
  private volatile String activityRejection;

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
   * 与必要的说明/自诊断；不含本地绝对路径、标记或令牌。重复调用返回同一个 stage。
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
    this.dispatchSender = null;
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
      // 强引用住 sender：Spark 内部只持弱引用，这段引用一旦出栈，后续回执就没人收了
      CommandSender sender = server.createCommandSender(forwarded);
      this.dispatchSender = sender;
      boolean accepted = commands.dispatch(sender, commandLine(this.marker));
      if (!accepted) {
        settle(render("未执行（spark 命令拒绝了本次请求）", null, NOT_REQUESTED_NOTE));
        return;
      }
      log(Level.FINE, "已提交 Spark 采样（窗口 " + PROFILE_SECONDS + " 秒），等待落盘");
    } catch (Throwable throwable) {
      settle(render("未执行（派发 Spark 命令失败：" + describe(throwable) + "）", null, NOT_REQUESTED_NOTE));
    } finally {
      // 无论成败都记「派发已结束」：它是「Spark 一句话都没回」判定的唯一前提
      this.dispatchDoneAtMillis = System.currentTimeMillis();
      this.dispatchDone = true;
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
      // ① 回执里的「已经明确失败」每一拍都看：Spark 自报失败时不必等满窗口
      Optional<String> quick = quickFailure();
      if (quick.isPresent()) {
        settle(quick.get());
        return;
      }
      // ② 找文件（回执路径 → 账本 → 目录轮询，内部已过四道校验）
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
      // ③ 回执四态：只在派发完成后判一次；一句都没回时不必等满窗口
      if (!this.hintClassified && this.dispatchDone
          && System.currentTimeMillis() - this.dispatchDoneAtMillis >= HINT_SETTLE_MILLIS) {
        this.hintClassified = true;
        List<String> lines = snapshotFeedback();
        this.hintState = classifyHint(lines);
        // 判据是「一句话都没有」，不是「没有我们认识的那句话」：Spark 换了措辞时宁可继续等，
        // 也不能凭一次未识别的分类就否掉一次真在跑的采样
        if (lines.isEmpty()) {
          settle(render(NOT_STARTED + "（Spark 连一句话都没回，命令可能没有进入 spark）", null,
              NOT_REQUESTED_NOTE));
          return;
        }
      }
      // ④ 兜底：等满落盘窗口即收尾，并把「为什么」写进自诊断
      if (elapsed >= FILE_WAIT_MILLIS) {
        settle(render("未完成（等待 Spark 生成采样文件超时）", null, BACKGROUND_NOTE, diagnostics()));
      }
    } catch (Throwable throwable) {
      settle(render("未完成（处理 Spark 采样时出错：" + describe(throwable) + "）", null, BACKGROUND_NOTE,
          diagnostics()));
    }
  }

  /**
   * 回执的快速失败：Spark 自己已经明确拒绝/失败的几种情形，无需等满整个窗口。
   *
   * <p><b>只作提示</b>：这些分支都只把「已经被 Spark 明确拒绝」的情形提前收尾；真正的结论仍以文件匹配为准。
   */
  private Optional<String> quickFailure() {
    List<String> lines = snapshotFeedback();
    String hint = classifyHint(lines);
    if (HINT_DISABLED.equals(hint)) {
      return Optional.of(render(NOT_STARTED + "（Spark 已被服务端的全局配置禁用）", null, NOT_REQUESTED_NOTE));
    }
    if (HINT_BUSY.equals(hint)) {
      return Optional.of(render(NOT_STARTED + "（已有 Spark 采样在进行，未打断它）", null, NOT_REQUESTED_NOTE));
    }
    if (HINT_ERROR.equals(hint)) {
      String detail = sparkError(lines).orElse("原因未知");
      return Optional.of(render("未完成（Spark 自报失败：" + receiptLine(detail) + "）", null,
          BACKGROUND_NOTE, diagnostics()));
    }
    return Optional.empty();
  }

  /**
   * 回执四态判定（外加「自报失败」一类）。
   *
   * <p>顺序即优先级：{@code disabled} / {@code busy} 是「本次 Spark 根本没跑」，
   * {@code error} 是「跑了但明确失败」；{@code started} 表示看到启动回执；其余一律 {@code silent}。
   * 纯函数，可离线单测。
   */
  static String classifyHint(List<String> lines) {
    if (hintsDisabled(lines)) {
      return HINT_DISABLED;
    }
    if (hintsBusy(lines)) {
      return HINT_BUSY;
    }
    if (sparkError(lines).isPresent()) {
      return HINT_ERROR;
    }
    if (hintsStarted(lines)) {
      return HINT_STARTED;
    }
    return HINT_SILENT;
  }

  /** 是否收到「Spark 未启用」的回执（Paper 内置 spark 的 CommandImpl 在禁用时只回这一句）。 */
  static boolean hintsDisabled(List<String> lines) {
    return containsAny(lines, "currently disabled", "will not be enabled");
  }

  /** 是否收到「已有采样在进行」的回执（非 background 的采样会让 profilerStart 直接返回）。 */
  static boolean hintsBusy(List<String> lines) {
    return containsAny(lines, "profiler is already running");
  }

  /** 是否收到「采样已启动」的回执（{@code Profiler is now running!} / {@code Starting a new profiler…}）。 */
  static boolean hintsStarted(List<String> lines) {
    return containsAny(lines, "profiler is now running", "starting a new profiler");
  }

  /**
   * Spark 自己报的失败：返回命中的那一行原文（未清洗），没有则空。
   *
   * <p>命中时「文件永远不会出现」已经是 Spark 自己说过的结论，继续空等毫无意义。
   * 分两档判定，避免误杀正常采样：
   * <ol>
   *   <li><b>硬失败</b>（保存/上传/内部错误）：任何情况下都算；</li>
   *   <li><b>软失败</b>（「某项能力在你这台机器上不支持」）：只有当 Spark <b>从未</b>确认启动时才算——
   *       已经在跑时这类提示与本次采样是否成功无关。</li>
   * </ol>
   */
  static Optional<String> sparkError(List<String> lines) {
    Optional<String> hard = firstPhrase(lines, HARD_ERROR_PHRASES);
    if (hard.isPresent()) {
      return hard;
    }
    if (hintsStarted(lines)) {
      return Optional.empty();
    }
    return firstPhrase(lines, SOFT_ERROR_PHRASES);
  }

  private static Optional<String> firstPhrase(List<String> lines, String[] phrases) {
    if (lines == null || lines.isEmpty()) {
      return Optional.empty();
    }
    for (String line : lines) {
      if (line == null) {
        continue;
      }
      String lower = line.toLowerCase(Locale.ROOT);
      for (String phrase : phrases) {
        if (lower.contains(phrase)) {
          return Optional.of(line);
        }
      }
    }
    return Optional.empty();
  }

  /** 回执里是否出现任一短语（大小写不敏感；与失败短语共用同一套匹配，避免两处各写一份）。 */
  private static boolean containsAny(List<String> lines, String... needles) {
    return firstPhrase(lines, needles).isPresent();
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

  /**
   * 从 Spark 的「{@code Data has been written to: <path>}」回执里取出路径原文。
   *
   * <p>只认这一种句式（大小写不敏感），取冒号之后的全部内容；拿不到就是 {@code null}，绝不猜测。
   */
  static String writtenPathFrom(String line) {
    if (line == null) {
      return null;
    }
    int at = line.toLowerCase(Locale.ROOT).indexOf(WRITTEN_PREFIX);
    if (at < 0) {
      return null;
    }
    String raw = line.substring(at + WRITTEN_PREFIX.length()).strip();
    return raw.isEmpty() ? null : raw;
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
   * 服务器根目录：activity.json 里的路径是「相对服务器根」的。
   *
   * <p>优先问服务端要世界容器（就是根目录），拿不到再由 {@code <plugins>} 反推；都不成立时退回
   * 第一个候选目录——那种情况下相对路径会解析歪，但四道校验会把歪掉的路径全部拒掉，不会上传错文件。
   */
  private Path serverRoot(List<Path> directories) {
    try {
      Server server = Bukkit.getServer();
      if (server != null) {
        File container = server.getWorldContainer();
        if (container != null) {
          return container.toPath().toAbsolutePath().normalize();
        }
      }
    } catch (Throwable ignored) {
      // 取不到就按目录结构反推
    }
    for (Path directory : directories) {
      Path parent = directory.getParent();
      if (parent != null && parent.getFileName() != null
          && "plugins".equalsIgnoreCase(parent.getFileName().toString())) {
        Path root = parent.getParent();
        if (root != null) {
          return root.toAbsolutePath().normalize();
        }
      }
    }
    return directories.isEmpty() ? null : directories.get(0).toAbsolutePath().normalize();
  }

  /**
   * 找本次匹配的采样文件；找不到返回 {@code null}。
   *
   * <p>顺序：回执里的真实路径（最快，零额外读盘）→ spark 账本（最权威，不依赖 sender）→ 目录轮询（兜底）。
   * 三条路的产出都要过 {@link SparkProfileFiles#verifyOwnedProfile(List, Path, String)} 的四道校验。
   */
  private Path locateOwnedProfile() {
    List<Path> directories = directories();
    if (directories.isEmpty()) {
      return null;
    }
    Path serverRoot = serverRoot(directories);
    Path fromReceipt = profileFromReceipt(directories, serverRoot);
    if (fromReceipt != null) {
      return fromReceipt;
    }
    Path fromActivity = profileFromActivity(directories, serverRoot);
    if (fromActivity != null) {
      return fromActivity;
    }
    return scanProfileDirectories(directories, this.marker, this.startedAtMillis, this.inspected,
        MAX_CANDIDATES_PER_SCAN, MAX_INSPECTED);
  }

  /** 加速路径：Spark 已经回过「Data has been written to: …」时直接用那个路径。 */
  private Path profileFromReceipt(List<Path> directories, Path serverRoot) {
    for (String line : snapshotFeedback()) {
      String raw = writtenPathFrom(line);
      if (raw == null) {
        continue;
      }
      Optional<Path> resolved = SparkProfileFiles.activityPath(raw, serverRoot);
      if (resolved.isEmpty()) {
        this.activityRejection = "回执里的路径形态不可用";
        continue;
      }
      SparkProfileFiles.Checked checked =
          SparkProfileFiles.verifyOwnedProfile(directories, resolved.get(), this.marker);
      if (checked.ok()) {
        log(Level.FINE, "按 Spark 回执定位到本次采样文件");
        return checked.path();
      }
      this.activityRejection = "回执路径未通过校验：" + checked.reason();
    }
    return null;
  }

  /** 主路径：读 spark 自己的 {@code activity.json}，取「本次开始之后」最新的 Profiler 落盘记录。 */
  private Path profileFromActivity(List<Path> directories, Path serverRoot) {
    long since = this.startedAtMillis - ACTIVITY_SLACK_MILLIS;
    for (Path directory : directories) {
      Optional<List<SparkProfileFiles.ActivityEntry>> fresh = freshActivity(directory);
      if (fresh.isEmpty()) {
        continue;
      }
      for (SparkProfileFiles.ActivityEntry entry : fresh.get()) {
        if (entry.timeMillis() < since) {
          continue;
        }
        Optional<Path> resolved = SparkProfileFiles.activityPath(entry.value(), serverRoot);
        if (resolved.isEmpty()) {
          this.activityRejection = "账本里的路径形态不可用";
          continue;
        }
        SparkProfileFiles.Checked checked =
            SparkProfileFiles.verifyOwnedProfile(directories, resolved.get(), this.marker);
        if (checked.ok()) {
          log(Level.FINE, "按 Spark 账本（activity.json）定位到本次采样文件");
          return checked.path();
        }
        this.activityRejection = "账本路径未通过校验：" + checked.reason();
      }
    }
    return null;
  }

  /**
   * 读一次账本；<b>文件没变就返回空</b>（上一拍已经看过这一版内容）。
   *
   * <p>这样 500 毫秒一拍的轮询不会把 {@code activity.json} 反复解析：只有 spark 真的追加了新记录
   * （size/mtime 变化）时才重新读，读也仍然是有界尾部读。
   */
  private Optional<List<SparkProfileFiles.ActivityEntry>> freshActivity(Path directory) {
    Path activityFile = directory.resolve(SparkProfileFiles.ACTIVITY_FILE);
    SparkProfileFiles.Stamp stamp = SparkProfileFiles.stampOf(activityFile);
    if (stamp == null) {
      this.activityStamps.remove(activityFile);
      return Optional.empty();
    }
    SparkProfileFiles.Stamp previous = this.activityStamps.put(activityFile, stamp);
    if (stamp.equals(previous)) {
      return Optional.empty();
    }
    return Optional.of(SparkProfileFiles.readActivity(activityFile, MAX_ACTIVITY_ENTRIES));
  }

  /**
   * 兜底路径：按目录轮询找本次文件（<b>静态</b>，只碰文件系统，便于离线单测）。
   *
   * <p><b>去重键带字节数</b>：Spark 边写边可见，轮询可能在文件只写了一半时读到它（头部不可解析）。
   * 只有「读到可解析的头」或「文件早于本次开始（稳定垃圾）」才登记键，半写文件下一轮长齐后还能被认领。
   *
   * @param startedAtMillis 本次开始时刻（用于判定「稳定」与扫描窗口下界）
   * @param inspected       跨轮共用的去重集合（会被本方法就地修改）
   */
  static Path scanProfileDirectories(List<Path> directories, String marker, long startedAtMillis,
      Set<String> inspected, int maxCandidates, int maxInspected) {
    if (directories == null || directories.isEmpty() || inspected == null) {
      return null;
    }
    long notBefore = startedAtMillis - SESSION_SLACK_MILLIS;
    for (Path directory : directories) {
      for (SparkProfileFiles.Candidate candidate :
          SparkProfileFiles.candidates(directory, notBefore, maxCandidates)) {
        String key = SparkProfileFiles.dedupeKey(candidate);
        if (inspected.contains(key)) {
          continue;
        }
        Optional<SparkProfileFiles.Header> header = SparkProfileFiles.readHeader(candidate.path());
        if (header.isPresent()) {
          rememberInspected(inspected, key, maxInspected);
          if (header.get().matches(marker)) {
            return candidate.path();
          }
          continue;
        }
        if (candidate.modifiedMillis() + STABLE_MTIME_SLACK_MILLIS < startedAtMillis) {
          // 早于本次开始（且超出时间戳粒度容差）又读不出头：稳定的无关文件，登记后不再每轮重读。
          // 容差不可省：时间戳粒度较粗的文件系统会把「本会话刚写出」的文件取整到 startedAt 之前。
          rememberInspected(inspected, key, maxInspected);
        }
      }
    }
    return null;
  }

  private static void rememberInspected(Set<String> inspected, String key, int maxInspected) {
    if (maxInspected > 0 && inspected.size() >= maxInspected) {
      inspected.clear();
    }
    inspected.add(key);
  }

  // ------------------------------------------------------------------
  // 自诊断：失败时把「为什么」写进 Spark 段
  // ------------------------------------------------------------------

  /**
   * 构造自诊断行（全部有界）：回执最近 {@value #MAX_DIAGNOSTIC_RECEIPTS} 行、每个候选目录本次开始之后的
   * {@value #MAX_DIAGNOSTIC_FILES} 个新文件、账本里的本次新条目。
   *
   * <p>只在失败/超时分支调用；行数上限 {@value #MAX_DIAGNOSTIC_LINES}，单行上限
   * {@value #MAX_DIAGNOSTIC_CHARS}，且 URL / 颜色 / 控制字符都会被剥掉（投影到 dump 里不外带链接）。
   */
  private List<String> diagnostics() {
    try {
      return buildDiagnostics();
    } catch (Throwable ignored) {
      // 自诊断只是解释失败原因：它自己出错绝不能把「已经失败的采样」变成「抛进调度器的异常」
      return List.of();
    }
  }

  /** {@link #diagnostics()} 的实现体（做真正的枚举与读盘）。 */
  private List<String> buildDiagnostics() {
    List<String> lines = new ArrayList<>(12);
    List<String> receipts = snapshotFeedback();
    int from = Math.max(0, receipts.size() - MAX_DIAGNOSTIC_RECEIPTS);
    lines.add("Spark 回执（共 " + receipts.size() + " 行，最近 " + (receipts.size() - from) + " 行）：");
    if (receipts.isEmpty()) {
      lines.add("· 无（Spark 未回任何一句话）");
    } else {
      for (int index = from; index < receipts.size(); index++) {
        lines.add("· " + receiptLine(receipts.get(index)));
      }
    }
    if (!"unknown".equals(this.hintState)) {
      lines.add("回执四态：" + describeHint(this.hintState));
    }
    List<Path> directories = directories();
    long notBefore = this.startedAtMillis - SESSION_SLACK_MILLIS;
    if (directories.isEmpty()) {
      lines.add("Spark 目录：未解析到任何候选目录");
    }
    for (Path directory : directories) {
      List<SparkProfileFiles.Entry> recent =
          SparkProfileFiles.recentProfiles(directory, notBefore, MAX_DIAGNOSTIC_FILES);
      lines.add("Spark 目录 " + describeDirectory(directory) + "："
          + (recent.isEmpty() ? "本次开始后没有新文件" : "本次开始后的新文件（最近 " + recent.size() + " 个）"));
      for (SparkProfileFiles.Entry entry : recent) {
        lines.add("· " + describeEntry(entry));
      }
    }
    lines.addAll(describeActivity(directories));
    lines.addAll(rejectionLines(this.activityRejection));
    return lines;
  }

  /**
   * 把「回执/账本给了路径但没过四道校验」的原因渲染成一行诊断（纯函数，可离线单测）。
   *
   * <p>为什么单列成一行而不是附在目录/账本行里：这条失败恰恰发生在<b>目录轮询也看不到</b>的时候
   * （目录不可枚举、或定制构建写在别的目录、账本没有本次条目），只有它能说清「Spark 说写了文件，
   * 我们为什么不认领」。空白原因不产生行；单行（含前缀）不超过 {@link #MAX_DIAGNOSTIC_CHARS}，
   * 且与其余诊断一样剥掉颜色与 URL。
   *
   * @param rejection 形如 {@code 回执路径未通过校验：<原因>} / {@code 账本路径未通过校验：<原因>}
   */
  static List<String> rejectionLines(String rejection) {
    if (rejection == null || rejection.isBlank()) {
      return List.of();
    }
    String reason = receiptLine(rejection);
    int room = MAX_DIAGNOSTIC_CHARS - REJECTION_PREFIX.length();
    if (room <= 0) {
      return List.of();
    }
    if (reason.length() > room) {
      reason = reason.substring(0, room);
    }
    return reason.isEmpty() ? List.of() : List.of(REJECTION_PREFIX + reason);
  }

  /** 账本视角：本次开始之后 spark 自己记下的落盘路径（「有记录但文件没通过校验」是最关键的线索）。 */
  private List<String> describeActivity(List<Path> directories) {
    List<String> lines = new ArrayList<>(2);
    long since = this.startedAtMillis - ACTIVITY_SLACK_MILLIS;
    for (Path directory : directories) {
      List<SparkProfileFiles.ActivityEntry> entries =
          SparkProfileFiles.readActivity(directory.resolve(SparkProfileFiles.ACTIVITY_FILE),
              MAX_ACTIVITY_ENTRIES);
      if (entries.isEmpty()) {
        continue;
      }
      SparkProfileFiles.ActivityEntry newest = entries.get(0);
      String name = fileNameOf(newest.value());
      if (newest.timeMillis() < since) {
        lines.add("Spark 账本（activity.json）：没有本次新条目（最近一条 " + name + "，早于本次开始）");
        continue;
      }
      // 被拒原因统一由 rejectionLines 那一行承担（这条只负责指出「账本里有本次条目」）
      lines.add("Spark 账本（activity.json）：本次新条目 " + name);
    }
    return lines;
  }

  /** 一个候选文件的判定摘要：名字、字节数、注释是否等于本轮标记、是否被上限拒。 */
  private String describeEntry(SparkProfileFiles.Entry entry) {
    StringBuilder builder = new StringBuilder(64);
    builder.append(fileNameOf(entry.path() == null ? null : entry.path().toString()))
        .append(' ').append(entry.size()).append(" 字节");
    if (entry.size() <= 0L) {
      return builder.append("，空文件（可能仍在写入）").toString();
    }
    if (!SparkProfileFiles.acceptableSize(entry.size())) {
      return builder.append("，超出上限 ").append(SparkProfileFiles.maxProfileMegabytes())
          .append(" MiB（被拒，未上传）").toString();
    }
    Optional<SparkProfileFiles.Header> header = SparkProfileFiles.readHeader(entry.path());
    if (header.isEmpty()) {
      return builder.append("，头部不可解析（可能仍在写入）").toString();
    }
    if (header.get().matches(this.marker)) {
      return builder.append("，注释=本轮标记（未通过路径校验）").toString();
    }
    return builder.append("，注释≠本轮标记（不是本次采样）").toString();
  }

  /** 候选目录的展示名：优先「相对服务器根的相对路径」（不把用户的绝对目录写进 dump）。 */
  private String describeDirectory(Path directory) {
    if (directory == null) {
      return "（未知）";
    }
    List<Path> directories = List.of(directory);
    Path root = serverRoot(directories);
    try {
      if (root != null && directory.toAbsolutePath().normalize().startsWith(root)) {
        Path relative = root.relativize(directory.toAbsolutePath().normalize());
        String text = relative.toString().replace('\\', '/');
        if (!text.isEmpty()) {
          return text;
        }
      }
    } catch (RuntimeException ignored) {
      // 相对化失败就退回文件名
    }
    Path name = directory.getFileName();
    return name == null ? "（未知）" : name.toString();
  }

  private static String fileNameOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return "（未知文件）";
    }
    String text = raw.replace('\\', '/');
    int slash = text.lastIndexOf('/');
    return slash < 0 ? text : text.substring(slash + 1);
  }

  /** 回执四态的中文口径（写进自诊断，便于读的人一眼确认「到底哪一态」）。 */
  private static String describeHint(String hint) {
    if (hint == null) {
      return "未知";
    }
    return switch (hint) {
      case HINT_DISABLED -> "Spark 已被服务端全局配置禁用";
      case HINT_BUSY -> "已有采样在进行（未打断）";
      case HINT_STARTED -> "已收到启动回执（Profiler is now running!）";
      case HINT_SILENT -> "Spark 无任何回执（命令可能未进入 spark）";
      case HINT_ERROR -> "Spark 自报失败";
      default -> "未知";
    };
  }

  /**
   * 单行回执清洗：剥 ANSI 颜色、MiniMessage 标签、URL，压掉控制字符与连续空白，截断到
   * {@value #MAX_DIAGNOSTIC_CHARS} 字符。
   *
   * <p>剥 URL 不是洁癖：dump 可能被服主转给别人，Spark 段里唯一该出现的链接是「完整链接」那一行。
   * 纯函数，可离线单测。
   */
  static String receiptLine(String raw) {
    if (raw == null) {
      return "";
    }
    String text = raw.replaceAll("§.", "");
    text = ANSI.matcher(text).replaceAll("");
    text = TAG.matcher(text).replaceAll("");
    text = URL.matcher(text).replaceAll("(URL)");
    text = CONTROL.matcher(text).replaceAll(" ");
    text = BLANKS.matcher(text).replaceAll(" ").strip();
    return text.length() > MAX_DIAGNOSTIC_CHARS ? text.substring(0, MAX_DIAGNOSTIC_CHARS) : text;
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
      return render("未完成（读取本次采样文件大小失败）", null, BACKGROUND_NOTE, diagnostics());
    }
    if (!SparkProfileFiles.acceptableSize(size)) {
      // 被上限拒也必须说清「多大、上限多少」，绝不静默跳过
      return render("未完成（本次采样文件超出上限：" + size + " 字节 > "
          + SparkProfileFiles.maxProfileMegabytes() + " MiB，未上传）", null, BACKGROUND_NOTE, diagnostics());
    }
    Endpoints endpoints = endpoints(file.getParent());
    Upload upload = upload(file, endpoints);
    if (upload.ok()) {
      return render("已上传", link(endpoints.viewerUrl(), upload.key()), BACKGROUND_NOTE);
    }
    return render("未完成（上传失败：" + upload.reason() + "）", null, BACKGROUND_NOTE, diagnostics());
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

  /** 渲染返回给执行者的整段文本（无自诊断）。 */
  static String render(String state, String link, String note) {
    return render(state, link, note, List.of());
  }

  /**
   * 渲染返回给执行者的整段文本（可带自诊断）。
   *
   * <p>格式固定（调用方与文档都按它对齐）：标题行 → 窗口 → 状态 →（上传成功时）完整链接 →（必要时）说明
   * →（失败时）诊断。以换行结尾，便于直接拼进 dump 文本。诊断行逐行清洗并受行数/长度上限约束。
   */
  static String render(String state, String link, String note, List<String> details) {
    String effectiveState = state == null || state.isBlank() ? "未完成（未知原因）" : state;
    StringBuilder builder = new StringBuilder(320);
    builder.append(TITLE);
    // 窗口口径必须与「到底有没有在采样」一致：未执行 = 没有 Spark 性能记录，不能写 30 秒/全部线程
    builder.append(isNotStarted(effectiveState) ? WINDOW_NOT_SAMPLED : WINDOW_SAMPLED).append('\n');
    builder.append("状态：").append(effectiveState).append('\n');
    if (link != null && !link.isBlank()) {
      builder.append("完整链接：").append(link).append('\n');
    }
    if (note != null && !note.isBlank()) {
      builder.append("说明：").append(note).append('\n');
    }
    if (details != null && !details.isEmpty()) {
      int written = 0;
      StringBuilder block = new StringBuilder(256);
      for (String raw : details) {
        if (written >= MAX_DIAGNOSTIC_LINES) {
          break;
        }
        String line = receiptLine(raw);
        if (line.isEmpty()) {
          continue;
        }
        block.append("  ").append(line).append('\n');
        written++;
      }
      if (block.length() > 0) {
        builder.append("诊断：\n").append(block);
      }
    }
    return builder.toString();
  }

  /** 状态是否属于「本次 Spark 根本没跑」（{@link #NOT_STARTED} 前缀）。 */
  static boolean isNotStarted(String state) {
    return state != null && state.startsWith(NOT_STARTED);
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
    // 结果已定：不再需要 spark 的回执，放掉强引用（也避免把 sender 及其缓冲挂在实例上）
    this.dispatchSender = null;
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
