package net.mikumc.mikuxraynet.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 *       连接/读取/总等待三重超时，默认 10 秒）；</li>
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

  /** 缺省等待上限（秒）。 */
  public static final int DEFAULT_TIMEOUT_SECONDS = 10;

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
  /** 当前来源。 */
  private static volatile Source source = Source.NONE;
  /** 最后一次失败原因（诊断用，只出现在日志里）。 */
  private static volatile String lastError = "";

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

  /** 构造下载 URL（全平台包）；{@code baseUrl} 为空时回落到 Maven Central，末尾多余的 {@code /} 会被去掉。 */
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
   * 当前平台的 zstd-jni 分类器（{@code linux_amd64} / {@code win_amd64} / {@code darwin_aarch64} …）。
   *
   * <p>zstd-jni 在 Maven Central 上同时发布**平台专用包**与**全平台包**；二者的类与 API 完全一致，
   * 但平台包只有约 0.4 MB，而全平台包要 6.2 MB（实测 1.5.7-15：linux_amd64 410604 字节 vs
   * 全平台 6451268 字节）。因此自动下载优先取平台包，取不到再退全平台包。
   *
   * @return 分类器名；平台无法识别时返回 {@code null}（调用方退回全平台包）
   */
  public static String platformClassifier() {
    return classifierFor(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  /** 纯函数：由 {@code os.name} / {@code os.arch} 推导 zstd-jni 的分类器名（不可识别返回 null）。 */
  public static String classifierFor(String osName, String osArch) {
    String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
    String archToken = switch (arch) {
      case "amd64", "x86_64" -> "amd64";
      case "aarch64", "arm64" -> "aarch64";
      case "x86", "i386", "i486", "i586", "i686" -> "x86";
      case "arm", "armv7l", "armhf" -> "arm";
      default -> arch;
    };
    if (os.contains("win")) {
      return switch (archToken) {
        case "amd64" -> "win_amd64";
        case "aarch64" -> "win_aarch64";
        case "x86" -> "win_x86";
        default -> null;
      };
    }
    if (os.contains("mac") || os.contains("darwin")) {
      return switch (archToken) {
        case "amd64" -> "darwin_x86_64";
        case "aarch64" -> "darwin_aarch64";
        default -> null;
      };
    }
    if (os.contains("linux")) {
      return switch (archToken) {
        case "amd64", "aarch64", "arm", "x86", "ppc64", "ppc64le", "s390x", "riscv64",
            "mips64", "loongarch64" -> "linux_" + archToken;
        default -> null;
      };
    }
    if (os.contains("freebsd")) {
      return switch (archToken) {
        case "amd64" -> "freebsd_amd64";
        case "x86" -> "freebsd_i386";
        default -> null;
      };
    }
    if (os.contains("aix")) {
      return "ppc64".equals(archToken) ? "aix_ppc64" : null;
    }
    return null;
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
   * @param libraryDir     本插件的 lib 目录（{@code plugins/MikuXrayNet/lib}），可为 null（则跳过②③）
   * @param autoDownload   是否允许自动下载
   * @param downloadUrl    下载源根地址（空白回落 Maven Central）
   * @param timeoutSeconds 下载的总等待上限（秒）
   * @param logger         日志通道
   */
  public static void initialize(Path libraryDir, boolean autoDownload, String downloadUrl,
      int timeoutSeconds, Logger logger) {
    codec(); // ① 运行期已有（服务端自带）
    if (codec != null) {
      logger.info("已检测到 ZSTD（来源：" + source.label() + "）");
      return;
    }

    // ② 本地已下载（上次启动下载留下的 jar；平台专用包与全平台包都认）
    if (libraryDir != null) {
      for (Path candidate : libraryCandidates(libraryDir)) {
        Codec local = loadFromJar(candidate);
        if (local != null) {
          install(local, Source.LOCAL);
          logger.info("已检测到 ZSTD（来源：" + source.label() + "）");
          return;
        }
      }
    }

    // ③ 自动下载（默认开启）
    if (autoDownload) {
      Codec downloaded = downloadCodec(libraryDir, downloadUrl, timeoutSeconds);
      if (downloaded != null) {
        logger.info("已检测到 ZSTD（来源：" + source.label() + "）");
        return;
      }
      logger.warning("未检测到 ZSTD 且自动下载失败，已回退内置压缩（磁盘缓存仍可用，压缩率略低）"
          + (lastError.isEmpty() ? "" : "（原因：" + lastError + "）"));
      return;
    }

    logger.warning("未检测到 ZSTD（自动下载已关闭），已回退内置压缩（磁盘缓存仍可用，压缩率略低）");
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

  // ------------------------------------------------------------------ 下载

  /**
   * 按配置下载 zstd-jni 到 {@code libraryDir} 并加载。
   *
   * <p>先试**平台专用包**（约 0.4 MB），失败再试**全平台包**（约 6.2 MB）；两次尝试共享同一个
   * 总等待预算（{@code timeoutSeconds}），因此最坏等待时间仍不会超过该值。
   *
   * <p>每次尝试都校验两级：① 落盘文件必须非空；② 必须能被加载并完成一次压缩往返（否则删除该文件，
   * 避免下次启动误用坏文件）。任一失败返回 {@code null}，**不抛异常**。
   */
  static Codec downloadCodec(Path libraryDir, String baseUrl, int timeoutSeconds) {
    if (libraryDir == null) {
      return null;
    }
    long deadlineNanos =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
    String classifier = platformClassifier();
    List<String> attempts = new ArrayList<>(2);
    if (classifier != null) {
      attempts.add(classifier);
    }
    attempts.add(""); // 全平台包兜底
    for (String attempt : attempts) {
      long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
      if (remainingMillis <= 0L) {
        lastError = "下载总等待已用尽（" + Math.max(1, timeoutSeconds) + "s）";
        return null;
      }
      Codec found = downloadOnce(libraryDir, baseUrl, attempt, remainingMillis);
      if (found != null) {
        install(found, Source.DOWNLOADED);
        return found;
      }
    }
    return null;
  }

  /** 单次下载尝试（一个分类器）：下载 → 非空校验 → 原子替换 → 可加载校验。 */
  private static Codec downloadOnce(Path libraryDir, String baseUrl, String classifier,
      long timeoutMillis) {
    Path target = libraryDir.resolve(jarFileName(ARTIFACT, VERSION, classifier));
    Path temp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(libraryDir);
      download(downloadUrl(baseUrl, GROUP, ARTIFACT, VERSION, classifier), temp,
          (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeoutMillis)));
      if (!Files.isRegularFile(temp) || Files.size(temp) <= 0L) {
        lastError = "下载文件为空";
        return null;
      }
      // 先落临时文件再原子替换：中断/超时留下的残留文件永远不会被当成成品
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      Codec found = loadFromJar(target);
      if (found == null) {
        Files.deleteIfExists(target);
        return null;
      }
      return found;
    } catch (Throwable throwable) {
      lastError = throwable.toString();
      return null;
    } finally {
      deleteQuietly(temp);
    }
  }

  /**
   * 下载到临时文件：在守护线程上执行并以 {@code timeoutMillis} 为**总等待上限**
   * （连接超时 / 读取超时也各自设为该值），保证调用方等待时间有硬上限。
   */
  private static void download(String url, Path target, int timeoutMillis) throws IOException {
    ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-ZstdDownload");
      thread.setDaemon(true);
      return thread;
    });
    try {
      Callable<Void> task = () -> {
        HttpURLConnection connection =
            (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "MikuXrayNet");
        try (InputStream in = connection.getInputStream();
            OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
          in.transferTo(out);
        } finally {
          connection.disconnect();
        }
        return null;
      };
      Future<Void> future = executor.submit(task);
      future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      throw new IOException("下载超时（" + timeoutMillis + "ms）：" + url, exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause() == null ? exception : exception.getCause();
      throw new IOException("下载失败：" + cause, cause);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("下载被中断", exception);
    } finally {
      // 只取消、不等待：调用方已经不再阻塞（超时语义），残留线程是守护线程，随进程退出
      executor.shutdownNow();
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