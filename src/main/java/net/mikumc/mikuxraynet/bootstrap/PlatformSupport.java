package net.mikumc.mikuxraynet.bootstrap;

import java.util.Locale;
import org.bukkit.Bukkit;

/**
 * 服务端平台探测（Paper 系 / Folia 系）。
 *
 * <p><b>为什么不再用「类存在性」判断</b>：旧实现只要类路径上存在
 * {@code io.papermc.paper.threadedregions.RegionizedServer(InitEvent)} 就判为 Folia。但现代 Paper
 * <b>及其下游分支</b>都会把这个包（Folia 调度器 API）随服务端一起提供，以便插件写成 Folia 兼容：
 * 真机 Leaf 26.2 的 {@code libraries/cn/dreeam/leaf/leaf-api/26.2.build.110-alpha/leaf-api-...jar}
 * 内即含 {@code io/papermc/paper/threadedregions/RegionizedServerInitEvent.class}，于是纯 Paper 系的
 * Leaf 被误判为 Folia（真机日志：{@code 运行平台：Leaf 26.2（Folia 区域化线程）}），
 * 连带导致实体枚举被禁用、调度走区域线程等一串错误路径。
 *
 * <p><b>现在的判定依据</b>：只看服务端自己报出的<b>品牌/版本标识</b>
 * （{@link Bukkit#getName()}、{@link Bukkit#getVersion()}、{@link Bukkit#getBukkitVersion()}），
 * 其中任一含 {@code folia}（不区分大小写）才算 Folia 系；否则一律按 Paper 系单主线程处理。
 * 真机上 Leaf 返回的名称是 {@code Leaf}、版本形如 {@code git-Leaf-xxx (MC: 26.2)}，都不含 {@code folia}，
 * 因此正确判为 Paper 系。
 *
 * <p><b>兜底</b>：标识不标准的下游分支（改了品牌的 Folia 衍生端）可用
 * {@code antixray.yml: advanced.platform: paper|folia} 手动指定，手动指定优先于自动判定。
 *
 * <p>{@link #detect} 为纯函数（只接收字符串），可离线单测；Bukkit 访问只出现在 {@link #configure} 里。
 */
public final class PlatformSupport {

  /** 平台判定模式，对应 {@code antixray.yml: advanced.platform}。 */
  public enum Mode {
    /** 自动：按服务端品牌/版本标识判定（默认）。 */
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

  private static volatile Detection cached;

  private PlatformSupport() {
  }

  /**
   * 装配期调用一次（{@code MikuXrayNet#onEnable} 开头）：按配置模式完成平台判定并缓存。
   *
   * <p>必须在任何模块（反矿透 / 带宽）装配之前调用，因为后续所有调度分支与实体枚举能力都读这里的结果。
   */
  public static void configure(Mode mode) {
    cached = detect(mode, Bukkit.getName(), safeVersion(), safeBukkitVersion());
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
   * 纯函数判定：只依据真实标识字符串，不触碰 Bukkit（可离线单测）。
   *
   * @param mode          配置模式；{@code null} 视为 {@link Mode#AUTO}
   * @param serverName    {@link Bukkit#getName()}（如 Leaf / Paper / Purpur / Folia）
   * @param serverVersion {@link Bukkit#getVersion()}（如 {@code git-Leaf-xxx (MC: 26.2)}）
   * @param bukkitVersion {@link Bukkit#getBukkitVersion()}
   */
  public static Detection detect(Mode mode, String serverName, String serverVersion,
      String bukkitVersion) {
    Mode effective = mode == null ? Mode.AUTO : mode;
    String name = clean(serverName);
    String version = clean(serverVersion);
    String bukkit = clean(bukkitVersion);

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
    return new Detection(effective, false,
        "服务端名称=" + (name.isEmpty() ? version : name) + "（不含 folia 标识 → Paper 系）");
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