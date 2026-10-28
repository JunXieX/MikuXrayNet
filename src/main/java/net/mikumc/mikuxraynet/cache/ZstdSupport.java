package net.mikumc.mikuxraynet.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * zstd 支撑：为磁盘缓存（{@link BufferedLinearV3Format}）提供 zstd 压缩/解压能力，
 * 并按「开服时自动识别 + 缺失时自动下载」的顺序解析可用来源。
 *
 * <p><b>为什么用反射</b>：zstd-jni 在本项目里是 {@code scope=provided}（绝不打进插件 jar）。
 * 服务端（Leaf/Paper 26.x）自带该库时插件类加载器直接可见；但若某服务端没有它，我们就得把一个
 * 下载来的 jar 用独立类加载器挂上——此时编译期的静态引用会链接失败（{@code NoClassDefFoundError}），
 * 因此统一走 {@code Class.forName} + {@code Method.invoke}，三种来源共用同一条调用路径。
 *
 * <p><b>解析顺序（启动期一次，{@link #initialize}）</b>：
 * <ol>
 *   <li><b>运行期已有</b>（服务端自带：类路径可见，探测时做一次最小压缩往返）；</li>
 *   <li>{@code plugins/MikuXrayNet/lib/} 下**已下载**的 jar（{@link URLClassLoader} 加载）；</li>
 *   <li>按配置**自动下载**（默认 Maven Central；优先平台专用包约 0.4 MB，失败再退全平台包约 6.2 MB；
 *       连接 5 秒 / 读取 15 秒 / 总等待不超过 30 秒，配置值更小时以配置为准）；</li>
 *   <li>都不行 → 由 {@link BufferedLinearV3Format} 回退 JDK {@code Deflater}（磁盘缓存仍可用）。</li>
 * </ol>
 * 任一环节失败都只记中文日志并降级，<b>绝不抛异常、绝不阻塞超过超时</b>（fail-open）。
 *
 * <p><b>为什么用反射而不是静态引用</b>：见上——②③ 两个来源的类不在编译期类路径上。
 *
 * <p><b>平台无关</b>：本类只用 JDK（{@code java.net} / {@code java.nio} / 反射），不碰任何 Bukkit API，
 * 因此可安全地被磁盘线程与测试直接使用。
 */
public final class ZstdSupport {

  /** zstd-jni 的 Maven 坐标（版本取真机 Leaf 26.2 自带的 1.5.7-15，最大化复用服务端已有文件）。 */
  public static final String GROUP = "com.github.luben";
  public static final String ARTIFACT = "zstd-jni";
  public static final String VERSION = "1.5.7-15";

  /** 缺省下载源（Maven Central 根地址）。 */
  public static final String DEFAULT_DOWNLOAD_URL = "https://repo1.maven.org/maven2";

  /**
   * 自动下载文件的体积上限：16 MiB。
   *
   * <p><b>依据</b>：zstd-jni 1.5.7-15 的平台专用包约 0.4 MB、全平台包约 6.2 MB，16 MiB 对二者都有 2 倍以上
   * 余量；而「体积远超预期」的响应几乎只可能来自错误源或被劫持/损坏的响应。下载时逐块累计字节数，一旦超过
   * 此上限立即中止并拒绝（在写满磁盘、耗尽带宽之前），保持「校验失败 → 拒绝并回退内置压缩」的既有语义。
   */
  static final long MAX_DOWNLOAD_BYTES = 16L * 1024 * 1024;

  /** 下载线程收尾预算（毫秒）：interrupt 后通常立即结束，这里只是给「删临时文件」留出确定性窗口。 */
  private static final long DOWNLOAD_THREAD_JOIN_MILLIS = 2_000L;

  /**
   * 单次下载尝试的<b>连接</b>超时上限（毫秒）：5 秒。
   *
   * <p><b>为什么不能直接用剩余总预算</b>：出口对下载源 DROP（丢包但不回 RST）时，{@code connect}
   * 会一直挂到超时为止。旧实现把「剩余总预算」（配置允许到 120 秒）直接当连接超时用，于是开服会在
   * {@code onEnable} 上白等两分钟（真机反馈约 122 秒）。建连只是下载的第一步，不该占用整段预算。
   */
  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

  /**
   * 单次下载尝试的<b>读取</b>超时上限（毫秒）：15 秒。
   *
   * <p>与连接超时同理：连上了却收不到数据（假响应、半开连接）也必须有时限。15 秒足够慢速镜像传完
   * 约 0.4 MB 的平台包，剩下的预算还能留给 6.2 MB 的全平台包兜底。
   */
  private static final int READ_TIMEOUT_MILLIS = 15_000;

  /**
   * 下载的<b>总等待</b>硬上限（毫秒）：30 秒。
   *
   * <p><b>为什么必须有</b>：该下载发生在 {@code onEnable} 的主线程上，而配置允许把
   * {@code disk-cache.zstd.timeout-seconds} 配到 120 秒，且平台包失败后还要再试一次全平台包。
   * 统一封顶 30 秒，保证「最坏情况开服也只被拖住半分钟」。线程收尾另有
   * {@link #DOWNLOAD_THREAD_JOIN_MILLIS}（interrupt 后通常立即结束）。
   */
  private static final long DOWNLOAD_TOTAL_BUDGET_MILLIS = 30_000L;

  /** zstd 来源（仅用于日志）。 */
  public enum Source {
    /** 服务端自带（插件类路径可见）。 */
    SERVER("服务端自带"),
    /** 本插件 lib 目录下已下载的 jar。 */
    LOCAL("本地已下载"),
    /** 本次启动新下载的 jar。 */
    DOWNLOADED("新下载"),
    /** 不可用（回退 Deflater）。 */
    NONE("无");

    private final String label;

    Source(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 反射句柄；{@code loader} 同时是「保持类加载器存活」的强引用（否则原生库会被卸载）。 */
  record Codec(ClassLoader loader, Method compress, Method decompress) {
  }

  private static final Object LOCK = new Object();

  /** 当前可用的反射句柄；{@code null} = 不可用。 */
  private static volatile Codec codec;
  /** 类路径探测是否已完成（只做一次）。 */
  private static volatile boolean probed;
  /**
   * {@link #initialize} 是否已<b>成功</b>解析出可用 zstd（幂等：成功后重复调用直接返回，不再下载/不再提示）。
   *
   * <p>只许在成功路径上置位：初始化失败必须保持 false，否则本次进程内再也不会重试（旧实现即如此）。
   */
  private static volatile boolean initialized;
  /** 「自动下载未做完整性校验」的 WARN 只打一次。 */
  private static final AtomicBoolean UNVERIFIED_WARNING = new AtomicBoolean();
  /** 「下载文件 SHA-256 不符」的 WARN 只打一次（初始化失败后可重试，重试不得刷屏）。 */
  private static final AtomicBoolean HASH_MISMATCH_WARNING = new AtomicBoolean();
  /** 「lib 目录里已存在的 jar 与配置的 SHA-256 不符」的 WARN 只打一次（与下载路径的提示分开计）。 */
  private static final AtomicBoolean LOCAL_HASH_MISMATCH_WARNING = new AtomicBoolean();
  /** 「自动下载失败」的 WARN 只打一次（同上）。 */
  private static final AtomicBoolean DOWNLOAD_FAILURE_WARNING = new AtomicBoolean();
  /** 「未启用自动下载」的 WARN 只打一次（同上）。 */
  private static final AtomicBoolean AUTODOWNLOAD_DISABLED_WARNING = new AtomicBoolean();
  /** 当前来源。 */
  private static volatile Source source = Source.NONE;
  /** 最后一次失败原因（诊断用，只出现在日志里）。 */
  private static volatile String lastError = "";
  /** 最近一次成功下载使用的分类器（空 = 全平台包）与体积，仅用于启动日志。 */
  private static volatile String downloadedClassifier = "";
  private static volatile long downloadedBytes;
  /**
   * 最近一次下载落盘文件的<b>实际 SHA-256</b>（十六进制小写）。
   *
   * <p>无论是否配置了期望哈希都计算：未配置时由 {@link #initialize} 一次性打印出来供服主复制；
   * 配置了期望哈希时它也是校验用的「实际值」。
   */
  private static volatile String lastDownloadedSha256 = "";
  /** 本次下载是否因 SHA-256 不匹配而被拒绝（用于打印更有针对性的中文 WARN）。 */
  private static volatile boolean hashMismatchDetected;

  private ZstdSupport() {
  }

  // ------------------------------------------------------------------ 纯函数（可独立测试）

  /** 坐标 → 仓库内相对路径（{@code com/github/luben/zstd-jni/1.5.7-15}）。 */
  public static String artifactPath(String group, String artifact, String version) {
    return group.replace('.', '/') + "/" + artifact + "/" + version;
  }

  /** 坐标 → jar 文件名（{@code zstd-jni-1.5.7-15.jar}）。 */
  public static String jarFileName(String artifact, String version) {
    return jarFileName(artifact, version, "");
  }

  /**
   * 坐标 + 平台分类器 → jar 文件名（{@code zstd-jni-1.5.7-15-linux_amd64.jar}）；
   * {@code classifier} 为空表示「全平台包」。
   */
  public static String jarFileName(String artifact, String version, String classifier) {
    String suffix = classifier == null || classifier.isBlank() ? "" : "-" + classifier;
    return artifact + "-" + version + suffix + ".jar";
  }

  /**
   * 构造下载 URL（全平台包）；{@code baseUrl} 为空时回落到 Maven Central，末尾多余的 {@code /} 会被去掉。
   *
   * <p>测试专用豁免：生产路径一律调 5 参重载（多传平台分类器），本方法当前仅单测在用，
   * 保留以免破坏测试。
   */
  public static String downloadUrl(String baseUrl, String group, String artifact, String version) {
    return downloadUrl(baseUrl, group, artifact, version, "");
  }

  /** 构造下载 URL（可指定平台分类器；空分类器 = 全平台包）。 */
  public static String downloadUrl(String baseUrl, String group, String artifact, String version,
      String classifier) {
    String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_DOWNLOAD_URL : baseUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/" + artifactPath(group, artifact, version) + "/"
        + jarFileName(artifact, version, classifier);
  }

  /**
   * 平台 → zstd-jni 分类器映射表（键为「归一化平台|归一化架构」，例如 {@code win|amd64}）。
   *
   * <p><b>取值来源</b>：Maven Central 上 zstd-jni 1.5.7-6 / 1.5.7-15 实际发布的二进制分类器，
   * 实测清单为：
   * <pre>
   * aix_ppc64, darwin_aarch64, darwin_x86_64, freebsd_amd64, freebsd_i386,
   * linux_aarch64, linux_amd64, linux_arm, linux_i386, linux_loongarch64, linux_mips64,
   * linux_ppc64, linux_ppc64le, linux_riscv64, linux_s390x, win_aarch64, win_amd64, win_x86
   * </pre>
   * （另有 {@code javadoc} / {@code sources} 两个非二进制分类器，以及不适用于本下载的 {@code cloud}。）
   * <b>任何一个「猜出来」的名字都会 404 并白白回退到 6.2 MB 的全平台包</b>——例如 32 位 Linux
   * 曾错拼成 {@code linux_x86}，真实分类器是 {@code linux_i386}。因此本表每个取值都必须在上面
   * 这份实测清单内，并由 {@code ZstdSupportTest} 逐项断言（表驱动，防止再出现猜出来的分类器）。
   *
   * <p>特别注意 32 位架构在三家系统上的真实分类器<b>各不相同</b>：
   * Linux → {@code linux_i386}、Windows → {@code win_x86}、FreeBSD → {@code freebsd_i386}。
   */
  private static final Map<String, String> PLATFORM_CLASSIFIERS = Map.ofEntries(
      Map.entry("win|amd64", "win_amd64"),
      Map.entry("win|aarch64", "win_aarch64"),
      Map.entry("win|x86", "win_x86"),
      Map.entry("macos|amd64", "darwin_x86_64"),
      Map.entry("macos|aarch64", "darwin_aarch64"),
      Map.entry("linux|amd64", "linux_amd64"),
      Map.entry("linux|aarch64", "linux_aarch64"),
      Map.entry("linux|arm", "linux_arm"),
      Map.entry("linux|x86", "linux_i386"),
      Map.entry("linux|ppc64", "linux_ppc64"),
      Map.entry("linux|ppc64le", "linux_ppc64le"),
      Map.entry("linux|s390x", "linux_s390x"),
      Map.entry("linux|riscv64", "linux_riscv64"),
      Map.entry("linux|mips64", "linux_mips64"),
      Map.entry("linux|loongarch64", "linux_loongarch64"),
      Map.entry("freebsd|amd64", "freebsd_amd64"),
      Map.entry("freebsd|x86", "freebsd_i386"),
      Map.entry("aix|ppc64", "aix_ppc64"));

  /**
   * 当前平台的 zstd-jni 分类器（{@code linux_amd64} / {@code win_amd64} / {@code darwin_aarch64} …）。
   *
   * <p>zstd-jni 在 Maven Central 上同时发布**平台专用包**与**全平台包**；二者的类与 API 完全一致，
   * 但平台包只有约 0.4 MB，而全平台包要 6.2 MB（实测 1.5.7-15：linux_amd64 410604 字节 vs
   * 全平台 6451268 字节）。因此自动下载优先取平台包，取不到再退全平台包。
   *
   * @return 分类器名；平台或架构无法识别时返回 {@code null}（调用方退回全平台包）
   */
  public static String platformClassifier() {
    return classifierFor(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  /** 纯函数：由 {@code os.name} / {@code os.arch} 推导 zstd-jni 的分类器名（不可识别返回 null）。 */
  public static String classifierFor(String osName, String osArch) {
    String platform = platformToken(osName);
    if (platform.isEmpty()) {
      return null;
    }
    return PLATFORM_CLASSIFIERS.get(platform + "|" + archToken(osArch));
  }

  /** 平台映射表（只读；仅供测试逐项核对「每个取值都来自实测清单」）。 */
  static Map<String, String> platformTable() {
    return PLATFORM_CLASSIFIERS;
  }

  /**
   * {@code os.name} 归一化（只识别有分类器的平台；其余返回空串，调用方据此退回全平台包）。
   * 归一化后的取值：{@code win} / {@code macos} / {@code linux} / {@code freebsd} / {@code aix}。
   */
  static String platformToken(String osName) {
    String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    // mac/darwin 必须先判：Darwin 含子串 "win"，按 Windows 判会下错平台的原生库（随后回退 Deflater）
    if (os.contains("mac") || os.contains("darwin")) {
      return "macos";
    }
    if (os.contains("win")) {
      return "win";
    }
    if (os.contains("linux")) {
      return "linux";
    }
    if (os.contains("freebsd")) {
      return "freebsd";
    }
    if (os.contains("aix")) {
      return "aix";
    }
    return "";
  }

  /**
   * {@code os.arch} 归一化：32 位 JVM 可能报 {@code x86} / {@code i386} / {@code i486} / {@code i586}
   * / {@code i686}，64 位可能报 {@code amd64} / {@code x86_64}，ARM 可能报 {@code aarch64} /
   * {@code arm64} / {@code arm}（Linux 上还常见 {@code armv7l} / {@code armhf}）。
   * 其余取值（ppc64 / ppc64le / s390x / riscv64 / mips64 / loongarch64 …）原样返回。
   */
  static String archToken(String osArch) {
    String arch = osArch == null ? "" : osArch.trim().toLowerCase(Locale.ROOT);
    return switch (arch) {
      case "amd64", "x86_64" -> "amd64";
      case "aarch64", "arm64" -> "aarch64";
      case "x86", "i386", "i486", "i586", "i686" -> "x86";
      case "arm", "armv7l", "armhf" -> "arm";
      default -> arch;
    };
  }

  /** 平台展示名（仅用于启动日志，例：{@code windows} / {@code linux} / {@code macos}）。 */
  static String platformLabel(String osName) {
    String token = platformToken(osName);
    if (token.equals("win")) {
      return "windows";
    }
    if (!token.isEmpty()) {
      return token;
    }
    String os = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
    return os.isEmpty() ? "未知" : os;
  }

  /**
   * 启动日志前缀：一次性、中文地打印「检测到的平台」与「推导出的分类器」两件事，
   * 让用户在服务器上一眼确认走对了（例如 Windows 服务器必须是 {@code win_amd64}）。
   *
   * <p>形如 {@code ZSTD 前置：平台 windows/amd64 → 分类器 win_amd64}；分类器不可识别时为
   * {@code ZSTD 前置：平台 sunos/sparc → 分类器未识别，将尝试全平台包}。
   */
  static String detectionLine() {
    String osName = System.getProperty("os.name");
    String osArch = System.getProperty("os.arch");
    String classifier = classifierFor(osName, osArch);
    String arch = archToken(osArch);
    String platform = platformLabel(osName) + "/" + (arch.isEmpty() ? "未知" : arch);
    return "ZSTD 前置：平台 " + platform + " → "
        + (classifier == null ? "分类器未识别，将尝试全平台包" : "分类器 " + classifier);
  }

  /** 「分类器 + 体积」的来源细节（例：{@code win_amd64，401 KB}；全平台包时写「全平台包」）。 */
  private static String artifactDetail(String classifier, long bytes) {
    String name = classifier == null || classifier.isBlank() ? "全平台包" : classifier;
    return bytes > 0L ? name + "，" + bytes / 1024L + " KB" : name;
  }

  private static long sizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (Throwable throwable) {
      return 0L;
    }
  }

  /**
   * lib 目录下该坐标的候选文件：平台专用包优先，其次全平台包。
   *
   * <p>顺序必须与下载顺序一致，否则「本次下载平台包、下次启动却只找全平台包」会导致每次都重下。
   */
  static List<Path> libraryCandidates(Path libraryDir) {
    List<Path> candidates = new ArrayList<>(2);
    String classifier = platformClassifier();
    if (classifier != null) {
      candidates.add(libraryDir.resolve(jarFileName(ARTIFACT, VERSION, classifier)));
    }
    candidates.add(libraryDir.resolve(jarFileName(ARTIFACT, VERSION)));
    return candidates;
  }

  // ------------------------------------------------------------------ 可用性

  /**
   * 当前进程内 zstd 是否可用。
   *
   * <p>未调用过 {@link #initialize} 时只做「类路径探测」（不做任何磁盘/网络 IO），
   * 因此纯单元测试也能得到与服务端一致的结论。
   */
  public static boolean available() {
    return codec() != null;
  }

  /** 当前来源（未探测过时先做类路径探测）。 */
  public static Source source() {
    codec();
    return source;
  }

  /** 压缩（{@code level} 为 zstd 压缩等级）；不可用时抛 {@link IllegalStateException}。 */
  public static byte[] compress(byte[] raw, int level) throws Throwable {
    Codec current = codec();
    if (current == null) {
      throw new IllegalStateException("zstd 不可用");
    }
    return (byte[]) current.compress().invoke(null, raw, level);
  }

  /** 已知原始长度时解压；不可用时抛 {@link IllegalStateException}。 */
  public static byte[] decompress(byte[] compressed, int originalSize) throws Throwable {
    Codec current = codec();
    if (current == null) {
      throw new IllegalStateException("zstd 不可用");
    }
    return (byte[]) current.decompress().invoke(null, compressed, originalSize);
  }

  // ------------------------------------------------------------------ 启动期解析

  /**
   * 启动期一次性解析 zstd（只在磁盘缓存启用时调用）。
   *
   * <p>成功后 {@link #available()} 恒为 true；失败则保持 false（由 {@link BufferedLinearV3Format}
   * 回退 Deflater）。**本方法不抛异常**，失败只记中文日志。
   *
   * <p><b>幂等只在成功后成立</b>：真正解析出可用的 zstd 之后，重复调用直接返回，不再下载、不再打印任何提示；
   * 解析失败（服务端没有、本地没有、下载也没成）时保持「未初始化」，下一次调用（例如重载配置后）仍会再试一次——
   * 旧实现在<b>开始前</b>就置位「已初始化」，一次失败即被判为「已经做过」，本次进程内永不重试。
   * 为避免重试把日志刷满，各失败结论的 WARN 由各自的闸门保证只打一次。
   *
   * @param libraryDir     本插件的 lib 目录（{@code plugins/MikuXrayNet/lib}），可为 null（则跳过②③）
   * @param autoDownload   是否允许自动下载
   * @param downloadUrl    下载源根地址（空白回落 Maven Central）
   * @param timeoutSeconds 下载的总等待上限（秒）
   * @param logger         日志通道
   */
  public static void initialize(Path libraryDir, boolean autoDownload, String downloadUrl,
      int timeoutSeconds, Logger logger) {
    initialize(libraryDir, autoDownload, downloadUrl, null, timeoutSeconds, logger);
  }

  /**
   * 同 {@link #initialize(Path, boolean, String, int, Logger)}，但可传入自动下载文件的
   * <b>期望 SHA-256 十六进制摘要</b>用于供应链完整性校验。
   *
   * @param expectedSha256 期望的 SHA-256（十六进制，大小写不敏感）；{@code null}/空白表示不校验
   *                       （此时仍会计算并一次性打印下载文件的「实际 SHA-256」供服主复制；
   *                       非空时<b>本地已有的 jar 与新下载的 jar 都会校验</b>，不匹配则该 jar 被拒绝
   *                       ——本地文件被跳过（继续下一个候选或重新下载），下载文件不落盘，
   *                       最终回退内置压缩并打印中文 WARN）
   */
  public static void initialize(Path libraryDir, boolean autoDownload, String downloadUrl,
      String expectedSha256, int timeoutSeconds, Logger logger) {
    // 幂等：只在成功解析出可用 zstd 之后才置位（见下方各成功分支）；失败保持未初始化，
    // 下一次调用（例如重载配置后）还能再试一次——旧实现在开始前就置位，一次失败即永不重试。
    synchronized (LOCK) {
      if (initialized) {
        return;
      }
    }

    if (libraryDir != null) {
      // 上次在下载途中退出（强杀 / 落盘时文件仍被占用）会留下 <jar>.tmp：它永远不会被认作成品，
      // 这里在启动初始化时顺手清掉，避免长年占着磁盘。服务端自带 zstd 时也清，因为残留与来源无关。
      cleanResidualTempFiles(libraryDir);
    }

    codec(); // ① 运行期已有（服务端自带）
    if (codec != null) {
      // 正常就绪（成功路径）降为 FINE：开服日志只保留插件「已启用」一行；
      // 下方所有「未启用自动下载 / 校验未通过 / 下载失败」等异常路径仍为 WARNING。
      logger.fine(detectionLine() + "；来源=" + source.label());
      initialized = true;
      return;
    }

    // ② 本地已下载（上次启动下载留下的 jar；平台专用包与全平台包都认）
    if (libraryDir != null) {
      String classifier = platformClassifier();
      List<Path> candidates = libraryCandidates(libraryDir);
      for (int i = 0; i < candidates.size(); i++) {
        Path candidate = candidates.get(i);
        if (!matchesExpectedSha256(candidate, expectedSha256, logger)) {
          continue; // 本地文件未通过配置的完整性校验：跳过它（③ 会重新下载覆盖，或回退 Deflater）
        }
        Codec local = loadFromJar(candidate);
        if (local != null) {
          install(local, Source.LOCAL);
          // 候选顺序固定为「平台包 → 全平台包」，故首个候选只在分类器可识别时才是平台包
          String used = i == 0 && classifier != null ? classifier : null;
          logger.fine(detectionLine() + "；来源=" + source.label() + "（"
              + artifactDetail(used, sizeOf(candidate)) + "）");
          initialized = true;
          return;
        }
      }
    }

    // ③ 自动下载（默认开启）
    if (autoDownload) {
      boolean verify = expectedSha256 != null && !expectedSha256.isBlank();
      if (!verify) {
        // 供应链面：未提供期望哈希时无法做完整性校验，至少要一次性明确告知（不静默）。
        // 提示里点名「INFO 级」的那一行：默认日志级别下服主只看得到 WARNING，必须告诉他去哪里取实际值。
        warnOnce(UNVERIFIED_WARNING, logger, detectionLine()
            + "；自动下载未配置期望 SHA-256（disk-cache.zstd-sha256 为空），"
            + "本次下载仅校验「可加载性」，未做完整性校验；下载成功后会以 INFO 打印该文件的实际 SHA-256，"
            + "复制后填回 disk-cache.zstd-sha256 即可启用校验");
      }
      Codec downloaded = downloadCodec(libraryDir, downloadUrl, expectedSha256, timeoutSeconds);
      if (downloaded != null) {
        logger.fine(detectionLine() + "；来源=" + source.label() + "（"
            + artifactDetail(downloadedClassifier, downloadedBytes) + "）");
        if (verify) {
          logger.fine("ZSTD 前置：下载文件已通过 SHA-256 校验（" + lastDownloadedSha256 + "）");
        } else if (!lastDownloadedSha256.isEmpty()) {
          // 未配置期望哈希：把实际值一次性打印出来，供服主复制填入 disk-cache.zstd-sha256。
          // 用 INFO 而不是 FINE：默认日志级别下 FINE 根本不可见，而这条正是「怎么把校验配上」的唯一指引；
          // 成功即置位 initialized，因此整个进程内只会打印一次。
          logger.info("ZSTD 前置：本次下载文件的实际 SHA-256：" + lastDownloadedSha256
              + "，填入 disk-cache.zstd-sha256 即可在下次启动时启用完整性校验");
        }
        initialized = true;
        return;
      }
      if (verify && hashMismatchDetected) {
        // 下载成功但摘要不符：该 jar 已被拒绝（未落盘），回退内置压缩（fail-open）
        warnOnce(HASH_MISMATCH_WARNING, logger, detectionLine()
            + "；自动下载的 jar SHA-256 校验未通过，已拒绝使用该文件"
            + "（期望 " + expectedSha256.trim() + "，实际 "
            + (lastDownloadedSha256.isEmpty() ? "无" : lastDownloadedSha256)
            + "），已回退内置压缩（磁盘缓存仍可用，压缩率略低）");
        return;
      }
      warnOnce(DOWNLOAD_FAILURE_WARNING, logger, detectionLine()
          + "；自动下载失败，已回退内置压缩（磁盘缓存仍可用，压缩率略低）"
          + (lastError.isEmpty() ? "" : "（原因：" + lastError + "）"));
      return;
    }

    warnOnce(AUTODOWNLOAD_DISABLED_WARNING, logger, detectionLine()
        + "；未启用自动下载，已回退内置压缩（磁盘缓存仍可用，压缩率略低）");
  }

  // ------------------------------------------------------------------ 探测与加载

  /** 类路径探测（懒加载一次，线程安全）。 */
  private static Codec codec() {
    if (!probed) {
      synchronized (LOCK) {
        if (!probed) {
          Codec found = probeCodec(ZstdSupport.class.getClassLoader());
          source = found != null ? Source.SERVER : Source.NONE;
          codec = found;
          probed = true;
        }
      }
    }
    return codec;
  }

  /** 安装一个已经加载好的句柄（②③ 路径）。 */
  private static void install(Codec found, Source origin) {
    synchronized (LOCK) {
      codec = found;
      source = origin;
      probed = true;
    }
  }

  /**
   * 从指定类加载器里探测 zstd：类必须存在，且 {@code compress(byte[],int)} → {@code decompress(byte[],int)}
   * 的最小往返必须成功（同时覆盖「原生库缺失导致类初始化失败」与「库在但实现异常」两种情况）。
   *
   * @return 可用句柄；任何 {@link Throwable} 都视为不可用并返回 {@code null}
   */
  static Codec probeCodec(ClassLoader loader) {
    if (loader == null) {
      return null;
    }
    try {
      Class<?> type = Class.forName("com.github.luben.zstd.Zstd", true, loader);
      Method compress = type.getMethod("compress", byte[].class, int.class);
      Method decompress = type.getMethod("decompress", byte[].class, int.class);
      byte[] probe = (byte[]) compress.invoke(null, new byte[] {0}, 1);
      byte[] restored = (byte[]) decompress.invoke(null, probe, 1);
      if (restored == null || restored.length != 1) {
        lastError = "探测往返长度不符";
        return null;
      }
      return new Codec(loader, compress, decompress);
    } catch (Throwable throwable) {
      lastError = throwable.toString();
      return null;
    }
  }

  /**
   * 用独立类加载器加载一个 jar 并探测；失败返回 {@code null}（不抛）。
   *
   * <p>父加载器取<b>平台加载器</b>而不是插件加载器：这样探测的结论只可能来自<b>这个 jar 本身</b>
   * （zstd-jni 只依赖 JDK，自包含），而不会被「父层恰好也有 zstd」掩盖——
   * 否则一个损坏/空的 jar 也能因为父层有 zstd 而被判为「有效」，把坏文件留在 lib 目录里。
   */
  static Codec loadFromJar(Path jar) {
    if (jar == null) {
      return null;
    }
    try {
      if (!Files.isRegularFile(jar) || Files.size(jar) <= 0L) {
        return null;
      }
      URLClassLoader loader = new URLClassLoader("MikuXrayNet-Zstd",
          new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
      Codec found = probeCodec(loader);
      if (found == null) {
        closeQuietly(loader);
        return null;
      }
      return found;
    } catch (Throwable throwable) {
      lastError = throwable.toString();
      return null;
    }
  }

  /**
   * 本地已下载的 jar 是否通过<b>配置的</b> SHA-256 校验；未配置期望哈希时恒为 {@code true}（不校验）。
   *
   * <p><b>为什么本地文件也要校验</b>：{@code disk-cache.zstd-sha256} 的语义是「只信任摘要相符的那个文件」。
   * 若只校验新下载的文件，那么「上次以空哈希自动下载下来、之后才把哈希填进配置」的 jar 永远不会被校验，
   * 被替换/损坏的 jar 也会被当作「来源=本地已下载」直接加载——配置看起来是开着的，实际形同虚设。
   * 不符时按候选剔除处理（继续下一个候选 / 走 ③ 重新下载覆盖），并只 WARN 一次，绝不静默加载。
   */
  static boolean matchesExpectedSha256(Path jar, String expectedSha256, Logger logger) {
    if (expectedSha256 == null || expectedSha256.isBlank()) {
      return true;
    }
    if (!Files.isRegularFile(jar)) {
      // 文件不存在（还没下载过）：交给 loadFromJar 静默跳过即可，不能在这里报「摘要不符」误导服主
      return true;
    }
    String actual;
    try {
      actual = sha256Hex(jar);
    } catch (IOException exception) {
      // 读不出来就不能证明它可信：拒绝该候选，把原因留给失败日志
      lastError = exception.toString();
      return false;
    }
    if (actual.equalsIgnoreCase(expectedSha256.trim())) {
      return true;
    }
    lastError = "本地 jar SHA-256 不符（期望 " + expectedSha256.trim() + "，实际 " + actual + "）";
    warnOnce(LOCAL_HASH_MISMATCH_WARNING, logger, detectionLine()
        + "；lib 目录下已存在的 jar 与 disk-cache.zstd-sha256 不符，已拒绝加载该文件"
        + "（期望 " + expectedSha256.trim() + "，实际 " + actual + "）；"
        + "将尝试重新下载覆盖，或请核对 disk-cache.zstd-sha256 与 lib 目录里的文件");
    return false;
  }

  // ------------------------------------------------------------------ 下载

  /**
   * 按配置下载 zstd-jni 到 {@code libraryDir} 并加载。
   *
   * <p>先试**平台专用包**（约 0.4 MB），失败再试**全平台包**（约 6.2 MB）；两次尝试共享同一个
   * 总等待预算 = {@code min(timeoutSeconds, 30 秒)}，因此最坏等待时间不会超过该值——即便配置把
   * {@code disk-cache.zstd.timeout-seconds} 写到 120 秒，开服也不会被下载拖过 30 秒。
   *
   * <p>每次尝试都校验两级：① 落盘文件必须非空；② 必须能被加载并完成一次压缩往返（否则删除该文件，
   * 避免下次启动误用坏文件）。任一失败返回 {@code null}，**不抛异常**。
   */
  static Codec downloadCodec(Path libraryDir, String baseUrl, int timeoutSeconds) {
    return downloadCodec(libraryDir, baseUrl, null, timeoutSeconds);
  }

  /**
   * 同 {@link #downloadCodec(Path, String, int)}，但可传入期望 SHA-256 摘要，下载落盘后先校验再加载。
   *
   * @param expectedSha256 期望的 SHA-256（十六进制，大小写不敏感）；{@code null}/空白表示不校验
   */
  static Codec downloadCodec(Path libraryDir, String baseUrl, String expectedSha256,
      int timeoutSeconds) {
    if (libraryDir == null) {
      return null;
    }
    // 总预算 = min(配置值, 硬上限)：配置允许到 120 秒，但这段等待发生在 onEnable 主线程上，
    // 故统一封顶 30 秒（见 DOWNLOAD_TOTAL_BUDGET_MILLIS）。
    long budgetMillis = Math.min(DOWNLOAD_TOTAL_BUDGET_MILLIS,
        TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds)));
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    downloadedClassifier = "";
    downloadedBytes = 0L;
    lastDownloadedSha256 = "";
    hashMismatchDetected = false;
    for (String attempt : downloadClassifiers(platformClassifier())) {
      long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
      if (remainingMillis <= 0L) {
        // 预算耗尽即失败，但**保留上一次尝试的真实原因**（连接被拒 / 404 / 读取超时…）：
        // 用「预算不足」覆盖它会让服主以为只是配得太小，而真正要排查的是那个原因。
        if (lastError.isEmpty()) {
          lastError = "下载总等待已用尽（" + budgetMillis + "ms）";
        }
        return null;
      }
      Codec found = downloadOnce(libraryDir, baseUrl, attempt, expectedSha256, remainingMillis);
      if (found != null) {
        install(found, Source.DOWNLOADED);
        return found;
      }
    }
    return null;
  }

  /**
   * 下载候选顺序：平台专用包在前、全平台包在后（空串表示全平台包）；
   * 分类器不可识别时只有全平台包一个候选。
   *
   * <p>该顺序必须与 {@link #libraryCandidates} 的顺序一致，否则「本次下载平台包、下次启动却只找全平台包」
   * 会导致每次启动都重下。
   */
  static List<String> downloadClassifiers(String classifier) {
    List<String> attempts = new ArrayList<>(2);
    if (classifier != null && !classifier.isBlank()) {
      attempts.add(classifier);
    }
    attempts.add(""); // 全平台包兜底
    return attempts;
  }

  /**
   * 单次下载尝试（一个分类器）：下载（边下边算摘要/计体积）→ 上限与非空校验 → 完整性校验（可选）→
   * 原子替换 → 可加载校验。
   *
   * <p>摘要由 {@link #download} 在下载过程中流式算出，<b>不再对落盘后的临时文件做第二遍读取</b>；
   * 完整性校验只在提供了 {@code expectedSha256} 时执行：不匹配即视为被篡改/损坏，删除临时文件并返回
   * {@code null}（原子替换之前就拒绝，绝不把未通过校验的 jar 落到 lib 目录）。
   */
  private static Codec downloadOnce(Path libraryDir, String baseUrl, String classifier,
      String expectedSha256, long timeoutMillis) {
    Path target = libraryDir.resolve(jarFileName(ARTIFACT, VERSION, classifier));
    Path temp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(libraryDir);
      // 流式下载：返回下载内容的实际 SHA-256（未配置期望哈希时也由 initialize 打印，供服主复制）
      String actual = download(downloadUrl(baseUrl, GROUP, ARTIFACT, VERSION, classifier), temp,
          timeoutMillis);
      lastDownloadedSha256 = actual;
      if (!Files.isRegularFile(temp) || Files.size(temp) <= 0L) {
        lastError = "下载文件为空";
        return null;
      }
      if (expectedSha256 != null && !expectedSha256.isBlank()
          && !actual.equalsIgnoreCase(expectedSha256.trim())) {
        // 摘要不符：视为被篡改/损坏，删除临时文件并拒绝（绝不把未通过校验的 jar 落到 lib 目录）
        lastError = "下载文件 SHA-256 校验失败（期望 " + expectedSha256.trim() + "，实际 " + actual + "）";
        hashMismatchDetected = true;
        return null;
      }
      // 先落临时文件再原子替换：中断/超时留下的残留文件永远不会被当成成品。
      // 优先 ATOMIC_MOVE（同一文件系统内为「要么旧、要么新」的原子替换）；文件系统不支持时
      // （AtomicMoveNotSupportedException）退回普通替换移动，行为与旧实现一致。
      try {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicFailure) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      Codec found = loadFromJar(target);
      if (found == null) {
        Files.deleteIfExists(target);
        return null;
      }
      // 记下分类器与体积，供启动日志打印「来源=新下载（win_amd64，401 KB）」
      downloadedClassifier = classifier == null ? "" : classifier;
      downloadedBytes = sizeOf(target);
      return found;
    } catch (Throwable throwable) {
      lastError = throwable.toString();
      return null;
    } finally {
      deleteQuietly(temp);
    }
  }

  /**
   * 下载到临时文件：在守护线程上执行，并以 {@code budgetMillis} 为**总等待上限**；
   * 连接与读取各自再设上限（{@link #CONNECT_TIMEOUT_MILLIS} / {@link #READ_TIMEOUT_MILLIS}），
   * 且都不得超过剩余预算。三者叠起来保证调用方的等待时间有硬上限。
   *
   * <p><b>为什么连接超时要单独收窄</b>：出口把下载源 DROP 掉时，建连会一直挂到超时；若直接用剩余预算
   * （最长 30 秒）当连接超时，两次候选就会把整个预算耗在「连不上」上。5 秒足够建起一次正常连接，
   * 也足够快地判定「这条路不通」，把剩余预算留给下一个候选。
   *
   * <p><b>流式校验 + 体积上限</b>：边下载边计算 SHA-256、边累计字节数——① 一旦累计超过
   * {@link #MAX_DOWNLOAD_BYTES} 立即中止（在写满磁盘/耗尽带宽之前拒绝超大或异常响应）；
   * ② 返回的摘要直接用于完整性校验，<b>不再对落盘后的临时文件做第二遍读取</b>（校验因此前移到下载过程
   * 本身；摘要不匹配时调用方仍会在原子替换之前拒绝该临时文件）。
   *
   * @return 下载内容的 SHA-256 十六进制小写摘要
   */
  private static String download(String url, Path target, long budgetMillis) throws IOException {
    int connectTimeoutMillis = (int) Math.max(1L, Math.min(CONNECT_TIMEOUT_MILLIS, budgetMillis));
    int readTimeoutMillis = (int) Math.max(1L, Math.min(READ_TIMEOUT_MILLIS, budgetMillis));
    ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-ZstdDownload");
      thread.setDaemon(true);
      return thread;
    });
    try {
      Callable<String> task = () -> {
        HttpURLConnection connection =
            (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MikuXrayNet");
        MessageDigest digest;
        try {
          digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
          throw new IOException("JVM 缺少 SHA-256 实现", impossible);
        }
        try (InputStream in = connection.getInputStream();
            OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
          byte[] chunk = new byte[8192];
          long total = 0L;
          int read;
          while ((read = in.read(chunk)) >= 0) {
            if (read == 0) {
              continue;
            }
            total += read;
            if (total > MAX_DOWNLOAD_BYTES) {
              throw new IOException("下载体积超过上限 " + MAX_DOWNLOAD_BYTES + " 字节，已拒绝：" + url);
            }
            digest.update(chunk, 0, read);
            out.write(chunk, 0, read);
          }
        } finally {
          connection.disconnect();
        }
        return HexFormat.of().formatHex(digest.digest());
      };
      Future<String> future = executor.submit(task);
      return future.get(budgetMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      // 三个数都写进失败原因：只报「超时」无法区分「连不上」与「连上收不完」，排查时会误导
      throw new IOException("下载超时（总等待 " + budgetMillis + "ms，连接 " + connectTimeoutMillis
          + "ms，读取 " + readTimeoutMillis + "ms）：" + url, exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause() == null ? exception : exception.getCause();
      throw new IOException("下载失败：" + cause, cause);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("下载被中断", exception);
    } finally {
      // 先停线程再让调用方删临时文件：超时后下载线程可能仍在写 target，若此处不等待就返回，
      // downloadOnce 的 finally（deleteQuietly(temp)）会与写入线程竞争——删除先执行、写入随后重建文件，
      // 于是缓存目录里永久残留一个 .tmp（Windows 上删除还会因句柄占用直接失败）。
      // 这里给一个很短的收尾预算：interrupt 会让阻塞中的 read/connect 立刻返回，
      // 拿不到也只是多留一个临时文件（下次启动不认领它，但不会影响功能）。
      executor.shutdownNow();
      try {
        executor.awaitTermination(DOWNLOAD_THREAD_JOIN_MILLIS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * 计算文件的 SHA-256 十六进制小写摘要。
   *
   * <p>生产路径两处使用：下载时在 {@link #download} 内<b>流式</b>计算（不回读文件），以及
   * {@code initialize} 校验「本地已存在」的 jar（见 {@code matchesExpectedSha256}）。
   */
  static String sha256Hex(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IOException("JVM 缺少 SHA-256 实现", impossible);
    }
  }

  /**
   * <b>仅测试用</b>：复位启动期状态（探测结论、来源、幂等标记与一次性提示标记），
   * 便于在同一个 JVM 内独立验证 {@link #initialize} 的幂等性。
   */
  static void resetForTest() {
    synchronized (LOCK) {
      codec = null;
      probed = false;
      initialized = false;
      source = Source.NONE;
      lastError = "";
      downloadedClassifier = "";
      downloadedBytes = 0L;
      lastDownloadedSha256 = "";
      hashMismatchDetected = false;
      UNVERIFIED_WARNING.set(false);
      HASH_MISMATCH_WARNING.set(false);
      LOCAL_HASH_MISMATCH_WARNING.set(false);
      DOWNLOAD_FAILURE_WARNING.set(false);
      AUTODOWNLOAD_DISABLED_WARNING.set(false);
    }
  }

  /** <b>仅测试用</b>：最近一次下载落盘文件的实际 SHA-256（十六进制小写）；无下载时为空串。 */
  static String lastDownloadedSha256() {
    return lastDownloadedSha256;
  }

  /**
   * 清理本插件 lib 目录里残留的 {@code *.jar.tmp}。
   *
   * <p><b>为什么需要</b>：下载总是先写 {@code <jar>.tmp} 再原子替换，进程在下载途中被强杀（或收尾时
   * 文件仍被占用）就会把这个临时文件留在目录里；它永远不会被认作成品，只会一直占着磁盘。
   *
   * <p><b>为什么收得这么窄</b>：只扫 {@code libraryDir} 的<b>当层</b>（不递归）、只认
   * {@code *.jar.tmp} 后缀、只删常规文件——lib 目录里同时可能有服主自己放进去的 jar，绝不碰它们。
   * 任何异常（目录不存在、无权限）都只当清理失败：那是收尾动作，不能影响 zstd 解析主流程（fail-open）。
   */
  private static void cleanResidualTempFiles(Path libraryDir) {
    try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(libraryDir, "*.jar.tmp")) {
      for (Path leftover : leftovers) {
        if (Files.isRegularFile(leftover)) {
          deleteQuietly(leftover);
        }
      }
    } catch (Throwable ignored) {
      // 目录不存在 / 列举失败：下次启动还会再清，不影响本次解析
    }
  }

  /**
   * 一次性 WARN：初始化失败后不再置位 {@code initialized}（下次可重试），若每次重试都把同一段提示重打一遍，
   * 日志会被刷满却提供不了新信息。因此每个结论各配一个闸门，只在该结论首次出现时打印。
   */
  private static void warnOnce(AtomicBoolean gate, Logger logger, String message) {
    if (gate.compareAndSet(false, true)) {
      logger.warning(message);
    }
  }

  private static void closeQuietly(URLClassLoader loader) {
    try {
      loader.close();
    } catch (Throwable ignored) {
      // 关闭失败无副作用（该 loader 未被引用）
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (Throwable ignored) {
      // 临时文件删除失败不影响功能（启动时会重新覆盖）
    }
  }
}