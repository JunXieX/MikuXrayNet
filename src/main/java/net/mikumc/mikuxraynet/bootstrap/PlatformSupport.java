package net.mikumc.mikuxraynet.bootstrap;

import java.util.Locale;
import java.util.function.Predicate;
import org.bukkit.Bukkit;

/**
 * 服务端平台探测（Paper 系 / Folia 系）。
 *
 * <p><b>判定顺序</b>：配置覆盖 → 品牌标识 → 判别类探测。
 *
 * <p><b>为什么还要判别类</b>：只按品牌判定会漏掉「改了品牌名的 Folia 衍生端」。真机 Lophine 26.3
 * 是 Folia 系（日志：{@code Initialised EDF Folia scheduler}、{@code Folia Async Scheduler Thread}），
 * 但其品牌与版本串都不含 {@code folia}（{@code Running on Bukkit - Lophine} /
 * {@code 26.3-792-dev/26.3@9b1c1ef}），纯品牌判定会误判为 Paper 系。
 *
 * <p><b>判别类的选取依据</b>（本机 javap/解压实测，见类列表对比）：
 * 判别类取 {@value #FOLIA_DISCRIMINATOR} —— 它是 Folia 的<b>服务端实现类</b>，
 * 只在 Folia 系核心中存在：
 * <ul>
 *   <li>Lophine 26.3（{@code versions/26.3/lophine-26.3.jar}）含该类，同包还含
 *       {@code RegionShutdownThread}、{@code ThreadedRegionizer}、{@code TickRegionScheduler}、
 *       {@code RegionizedWorldData}；</li>
 *   <li>Leaf 26.2（{@code versions/26.2/leaf-26.2.jar}）与 paper-api 26.2 <b>都不含</b>该类 ——
 *       它们只在 {@code io.papermc.paper.threadedregions.scheduler} 下提供 Folia 调度器 <b>API</b>
 *       （{@code RegionScheduler} 等）以及 {@code RegionizedServerInitEvent}。</li>
 * </ul>
 * <b>历史教训（务必不要改回）</b>：旧实现探测的是 {@code RegionizedServerInitEvent}（或其父包中的
 * 「任一类存在」），而 {@code RegionizedServerInitEvent} <b>Leaf 26.2 与 paper-api 26.2 都自带</b>
 * （真机 Leaf 的 {@code leaf-api-26.2.build.110-alpha.jar} 内即含），于是纯 Paper 系的 Leaf 被误判为
 * Folia，连带禁用实体枚举、改走区域调度。改用「只能是 Folia 核心」的
 * {@link #FOLIA_DISCRIMINATOR} 后，两种情况都能判对。
 *
 * <p><b>兜底</b>：标识与判别类都不标准的衍生端可用
 * {@code antixray.yml: advanced.platform: paper|folia} 手动指定，手动指定优先于自动判定（最高优先级）。
 *
 * <p>{@link #detect} 为纯函数（只接收字符串 + 类探测回调），可离线单测；Bukkit 与 {@code Class.forName}
 * 访问只出现在 {@link #configure} 里。
 */
public final class PlatformSupport {

  /** 平台判定模式，对应 {@code antixray.yml: advanced.platform}。 */
  public enum Mode {
    /** 自动：按服务端品牌/版本标识 + 判别类探测判定（默认）。 */
    AUTO,
    /** 强制按 Paper 系（单主线程、常规 Bukkit 调度）处理。 */
    PAPER,
    /** 强制按 Folia 系（区域化多线程、区域调度器）处理。 */
    FOLIA;

    /** 解析配置值；{@code null}、{@code auto} 与非法值一律回落为 {@link #AUTO}（最安全：以真实标识为准）。 */
    public static Mode parse(String value) {
      if (value == null) {
        return AUTO;
      }
      String key = value.trim().toLowerCase(Locale.ROOT);
      if ("paper".equals(key)) {
        return PAPER;
      }
      if ("folia".equals(key)) {
        return FOLIA;
      }
      return AUTO;
    }
  }

  /** 判定结果：模式、是否 Folia，以及「判定依据」的人类可读说明（用于启动日志）。 */
  public record Detection(Mode mode, boolean folia, String evidence) {
  }

  private static final String FOLIA_MARKER = "folia";

  /**
   * 判别类：Folia 的<b>服务端实现类</b>（不是调度器 API）。经真 jar 对比确认——
   * Lophine 26.3 有、Leaf 26.2 与 paper-api 26.2 都没有。不要替换成
   * {@code RegionizedServerInitEvent}（那是 Paper/Leaf 也会自带的 API 类，会把 Leaf 误判成 Folia）。
   */
  private static final String FOLIA_DISCRIMINATOR = "io.papermc.paper.threadedregions.RegionizedServer";

  private static volatile Detection cached;

  private PlatformSupport() {
  }

  /**
   * 装配期调用一次（{@code MikuXrayNet#onEnable} 开头）：按配置模式完成平台判定并缓存。
   *
   * <p>必须在任何模块（反矿透 / 带宽）装配之前调用，因为后续所有调度分支与实体枚举能力都读这里的结果。
   */
  public static void configure(Mode mode) {
    cached = detect(mode, Bukkit.getName(), safeVersion(), safeBukkitVersion(), PlatformSupport::isClassPresent);
  }

  /** 当前判定结果（未显式 configure 时按 {@link Mode#AUTO} 惰性判定一次）。 */
  public static Detection detection() {
    Detection local = cached;
    if (local != null) {
      return local;
    }
    synchronized (PlatformSupport.class) {
      if (cached == null) {
        configure(Mode.AUTO);
      }
      return cached;
    }
  }

  /** 是否为 Folia 系（区域化多线程）服务端。 */
  public static boolean isFolia() {
    return detection().folia();
  }

  /** 平台描述（启动日志）。例：{@code Leaf 26.2（Paper 系，单主线程）｜判定依据：服务端名称=Leaf}。 */
  public static String platformDescription() {
    Detection detection = detection();
    return Bukkit.getName() + " " + safeMinecraftVersion()
        + (detection.folia() ? "（Folia 系，区域化多线程）" : "（Paper 系，单主线程）")
        + "｜判定依据：" + detection.evidence();
  }

  /**
   * 纯函数判定（旧签名，等价于「判别类探测回调恒为 false」）：只依据真实标识字符串。
   *
   * @deprecated 仅供离线单测使用；生产路径请用带判别类探测回调的重载。
   */
  @Deprecated
  public static Detection detect(Mode mode, String serverName, String serverVersion,
      String bukkitVersion) {
    return detect(mode, serverName, serverVersion, bukkitVersion, className -> false);
  }

  /**
   * 纯函数判定：配置覆盖 → 品牌标识 → 判别类探测，不直接触碰 Bukkit（可离线单测）。
   *
   * <p>判定顺序与依据：
   * <ol>
   *   <li>{@code advanced.platform=folia|paper}（最高优先级，跳过一切自动判定）；</li>
   *   <li>品牌/版本标识（{@link Bukkit#getName()}/{@link Bukkit#getVersion()}/
   *       {@link Bukkit#getBukkitVersion()}）任一含 {@code folia} → Folia 系；</li>
   *   <li>判别类 {@value #FOLIA_DISCRIMINATOR} 存在 → Folia 系（覆盖改了品牌名的 Folia 衍生端，
   *       如 Lophine 26.3）；不存在 → Paper 系（Leaf 26.2、Paper 官方核心走这里）；</li>
   *   <li>标识缺失时同样落到 Paper 系（默认单主线程，最安全）。</li>
   * </ol>
   *
   * @param mode          配置模式；{@code null} 视为 {@link Mode#AUTO}
   * @param serverName    {@link Bukkit#getName()}（如 Lophine / Leaf / Paper / Folia）
   * @param serverVersion {@link Bukkit#getVersion()}
   * @param bukkitVersion {@link Bukkit#getBukkitVersion()}
   * @param classProbe    判别类探测回调（入参为全限定类名，返回是否存在）；生产路径传真实
   *                      {@code Class.forName} 探测，离线单测可传桩
   */
  public static Detection detect(Mode mode, String serverName, String serverVersion,
      String bukkitVersion, Predicate<String> classProbe) {
    Mode effective = mode == null ? Mode.AUTO : mode;
    String name = clean(serverName);
    String version = clean(serverVersion);
    String bukkit = clean(bukkitVersion);
    String brand = name.isEmpty() ? version : name;

    if (effective == Mode.FOLIA) {
      return new Detection(effective, true,
          "advanced.platform=folia（手动指定，跳过自动判定）");
    }
    if (effective == Mode.PAPER) {
      return new Detection(effective, false,
          "advanced.platform=paper（手动指定，跳过自动判定）");
    }
    if (containsFoliaMarker(name)) {
      return new Detection(effective, true, "服务端名称=" + name + "（含 folia 标识）");
    }
    if (containsFoliaMarker(version)) {
      return new Detection(effective, true, "服务端版本=" + version + "（含 folia 标识）");
    }
    if (containsFoliaMarker(bukkit)) {
      return new Detection(effective, true, "Bukkit 版本=" + bukkit + "（含 folia 标识）");
    }

    boolean discriminatorPresent = detectClass(classProbe, FOLIA_DISCRIMINATOR);
    return new Detection(effective, discriminatorPresent,
        "品牌=" + brand + "（不含 folia 标识）；判别类 " + FOLIA_DISCRIMINATOR
            + (discriminatorPresent ? " 存在 → Folia 系（Folia 衍生端）" : " 不存在 → Paper 系"));
  }

  /** 判别类探测：回调为空或本身抛异常都按「不存在」处理（宁可判 Paper，不得因探测失败判成 Folia）。 */
  private static boolean detectClass(Predicate<String> classProbe, String className) {
    if (classProbe == null) {
      return false;
    }
    try {
      return classProbe.test(className);
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** 真实判别类探测：只加载不初始化（{@code initialize=false}），避免连带初始化服务端类。 */
  private static boolean isClassPresent(String className) {
    try {
      Class.forName(className, false, PlatformSupport.class.getClassLoader());
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static boolean containsFoliaMarker(String value) {
    return value.toLowerCase(Locale.ROOT).contains(FOLIA_MARKER);
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static String safeVersion() {
    try {
      return Bukkit.getVersion();
    } catch (Throwable ignored) {
      return "";
    }
  }

  private static String safeBukkitVersion() {
    try {
      return Bukkit.getBukkitVersion();
    } catch (Throwable ignored) {
      return "";
    }
  }

  private static String safeMinecraftVersion() {
    try {
      return Bukkit.getMinecraftVersion();
    } catch (Throwable ignored) {
      // 极少数平台没有该方法：用版本串兜底
      return safeVersion();
    }
  }
}