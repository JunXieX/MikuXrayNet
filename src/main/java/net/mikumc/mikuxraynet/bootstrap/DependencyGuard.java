package net.mikumc.mikuxraynet.bootstrap;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * 前置依赖守卫：只做「是否就绪」的探测，缺失时由调用方禁用对应模块并打印可读日志。
 *
 * <p>关键约束：本类不静态引用 PacketEvents / ProtocolLib 的任何类型，改用类存在性 + 插件管理器判断，
 * 因此在缺少软依赖（ProtocolLib）时也不会触发 {@link NoClassDefFoundError} 崩服。
 *
 * <p><b>增强（只做提示，绝不做门禁）</b>：
 * <ul>
 *   <li><b>版本范围提示</b>：PacketEvents 低于 {@link #MIN_PACKET_EVENTS_VERSION} 或 ProtocolLib
 *       低于 {@link #MIN_PROTOCOL_LIB_VERSION} 时提示「可能不兼容」。版本串从插件描述文件读取，
 *       读不到或解析不了时<b>静默跳过</b>——提示永远不该阻断启动；</li>
 *   <li><b>冲突检测</b>：环境里若还装着其它反矿透插件（{@link #CONFLICT_HINTS} 名单，按插件名近似匹配），
 *       提示「两个反矿透会双重改写区块包，建议只留一个」。同样只提示不阻断。</li>
 * </ul>
 * 全部判定逻辑抽成<b>纯函数</b>（{@link #compareVersions} / {@link #versionCompatibilityWarning} /
 * {@link #isConflictingAntiXrayPlugin}），可离线单测。
 */
public final class DependencyGuard {

  private static final String PACKET_EVENTS_CLASS = "com.github.retrooper.packetevents.PacketEvents";
  private static final String PACKET_EVENTS_PLUGIN = "packetevents";
  private static final String PROTOCOL_LIB_CLASS = "com.comphenix.protocol.ProtocolLibrary";
  private static final String PROTOCOL_LIB_PLUGIN = "ProtocolLib";

  /** 真机 26.2 所需的最低 PacketEvents 版本（低于此值提示「可能不兼容」）。 */
  public static final String MIN_PACKET_EVENTS_VERSION = "2.13.0";

  /** 最低建议 ProtocolLib 版本（低于此值提示「可能不兼容」，不作门禁）。 */
  public static final String MIN_PROTOCOL_LIB_VERSION = "5.3.0";

  /** 本插件名（冲突检测时跳过自己）。 */
  private static final String SELF_PLUGIN = "MikuXrayNet";

  /**
   * 其它反矿透插件的识别线索（与插件名的小写形式做包含匹配）。
   *
   * <p>刻意保守：只列「明确以区块改写方式反矿透」的插件名，避免把 Vichat 之类的普通协议插件误报。
   * 自家插件（MikuXrayNet / MikuAntiXray 的旧名）里 {@code mikuantixray} 命中的是竞品——
   * 本插件名 {@code mikuxraynet} 不含该词，不会被误伤。
   */
  private static final String[] CONFLICT_HINTS = {
      "orebfuscator", "mikuantixray", "antixray", "anti_xray"};

  private static final String CONFLICT_ADVICE =
      "同时启用两个反矿透会双重改写区块包（负载膨胀且互相干扰），建议只留一个";

  private DependencyGuard() {
  }

  /** 类是否存在于当前类加载器（不初始化该类）。 */
  public static boolean classPresent(String className) {
    try {
      Class.forName(className, false, DependencyGuard.class.getClassLoader());
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** PacketEvents 是否已安装（硬前置；仅做存在性判断，是否可用由 PacketEventsHook 进一步确认）。 */
  public static boolean isPacketEventsPresent() {
    return Bukkit.getPluginManager().getPlugin(PACKET_EVENTS_PLUGIN) != null
        && classPresent(PACKET_EVENTS_CLASS);
  }

  /** ProtocolLib 是否已安装（软前置；唯一的封包拦截通道）。 */
  public static boolean isProtocolLibPresent() {
    return Bukkit.getPluginManager().getPlugin(PROTOCOL_LIB_PLUGIN) != null
        && classPresent(PROTOCOL_LIB_CLASS);
  }

  // ---------------------------------------------------------------- 增强：版本范围提示（纯函数，可离线单测）

  /**
   * 版本范围提示（纯函数）：前置版本低于建议下限时返回中文 WARN 文案，否则返回 {@code null}。
   *
   * <p><b>跳过语义</b>：版本串传 {@code null}、空白或解析不出任何数字段时<b>静默跳过</b>——
   * 提示只是提醒，绝不能因为「读不到版本」而刷误导性告警。PE 与 PL 的问题合并为一条文案。
   *
   * @param selfName           本插件名（用于文案主语）
   * @param packetEventsVersion 已安装 PacketEvents 的版本串（可为 null）
   * @param protocolLibVersion 已安装 ProtocolLib 的版本串（可为 null）
   * @return 警告文案；无需警告时为 {@code null}
   */
  public static String versionCompatibilityWarning(String selfName, String packetEventsVersion,
      String protocolLibVersion) {
    String who = selfName == null || selfName.isBlank() ? SELF_PLUGIN : selfName.trim();
    StringBuilder problems = new StringBuilder();
    int[] pe = parseVersion(packetEventsVersion);
    if (pe.length > 0 && compareVersions(packetEventsVersion, MIN_PACKET_EVENTS_VERSION) < 0) {
      problems.append("PacketEvents 版本 ").append(packetEventsVersion).append(" 低于建议下限 ")
          .append(MIN_PACKET_EVENTS_VERSION);
    }
    int[] pl = parseVersion(protocolLibVersion);
    if (pl.length > 0 && compareVersions(protocolLibVersion, MIN_PROTOCOL_LIB_VERSION) < 0) {
      if (problems.length() > 0) {
        problems.append("；");
      }
      problems.append("ProtocolLib 版本 ").append(protocolLibVersion).append(" 低于建议下限 ")
          .append(MIN_PROTOCOL_LIB_VERSION);
    }
    if (problems.length() == 0) {
      return null;
    }
    return who + " 可能不兼容：" + problems + "，建议升级后重启服务端";
  }

  /** 读取环境里已安装前件的版本并给出提示（启动期调用一次；读不到版本时静默跳过）。 */
  public static void logVersionCompatibilityWarning(java.util.logging.Logger logger) {
    try {
      String warning = versionCompatibilityWarning(SELF_PLUGIN,
          pluginVersion(Bukkit.getPluginManager().getPlugin(PACKET_EVENTS_PLUGIN)),
          pluginVersion(Bukkit.getPluginManager().getPlugin(PROTOCOL_LIB_PLUGIN)));
      if (warning != null) {
        logger.warning(warning);
      }
    } catch (Throwable ignored) {
      // 插件管理器不可用等极端情况：提示性功能，绝不影响启动
    }
  }

  // ---------------------------------------------------------------- 增强：反矿透冲突检测（纯函数，可离线单测）

  /**
   * 插件名是否命中「其它反矿透」黑名单（纯函数）。
   *
   * <p>规则：与自身同名 → false（不报自己）；名字的小写形式包含 {@link #CONFLICT_HINTS} 任一线索 → true。
   * 大小写不敏感、去空白，兼容「Orebfuscator」「miku-antixray」这类写法。
   */
  public static boolean isConflictingAntiXrayPlugin(String selfName, String otherName) {
    if (otherName == null || otherName.isBlank()) {
      return false;
    }
    String normalized = otherName.trim().toLowerCase(Locale.ROOT);
    String self = selfName == null ? SELF_PLUGIN : selfName.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals(self)) {
      return false;
    }
    for (String hint : CONFLICT_HINTS) {
      if (normalized.contains(hint)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 扫描环境里已安装的其它反矿透插件并打印冲突告警（启动期调用一次，只提示不阻断）。
   *
   * @return 命中的冲突插件名（逗号连接）；没有冲突时为 {@code null}（便于测试与诊断复用）
   */
  public static String logConflictingAntiXrayWarning(java.util.logging.Logger logger) {
    String conflicts = findConflictingAntiXrayPlugins();
    if (conflicts != null) {
      logger.warning("检测到其它反矿透插件：" + conflicts + "。" + CONFLICT_ADVICE);
    }
    return conflicts;
  }

  /** 汇总当前环境命中的冲突插件名（无冲突返回 null；任何异常都按「无冲突」处理）。 */
  public static String findConflictingAntiXrayPlugins() {
    StringBuilder hits = null;
    try {
      for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
        String name = plugin == null ? null : plugin.getName();
        if (!isConflictingAntiXrayPlugin(SELF_PLUGIN, name)) {
          continue;
        }
        if (hits == null) {
          hits = new StringBuilder();
        } else {
          hits.append(", ");
        }
        hits.append(name);
      }
    } catch (Throwable ignored) {
      return null;
    }
    return hits == null ? null : hits.toString();
  }

  // ---------------------------------------------------------------- 内部工具

  /**
   * 读取插件版本串（优先 Paper 的 PluginMeta，退回旧的描述文件 API；都读不到返回 null → 调用方跳过提示）。
   */
  @SuppressWarnings("deprecation")
  private static String pluginVersion(Plugin plugin) {
    if (plugin == null) {
      return null;
    }
    try {
      String version = plugin.getPluginMeta().getVersion();
      if (version != null && !version.isBlank()) {
        return version;
      }
    } catch (Throwable ignored) {
      // Paper 专属 API 不存在时退回旧 API
    }
    try {
      String version = plugin.getDescription().getVersion();
      return version == null || version.isBlank() ? null : version;
    } catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * 比较两个点分版本串（逐段数值比较，缺失段视为 0）：{@code a < b} 返回负数，相等返回 0。
   * 解析不出的段按 0 处理，绝不抛异常。
   */
  public static int compareVersions(String a, String b) {
    int[] va = parseVersion(a);
    int[] vb = parseVersion(b);
    int length = Math.max(va.length, vb.length);
    for (int i = 0; i < length; i++) {
      int left = i < va.length ? va[i] : 0;
      int right = i < vb.length ? vb[i] : 0;
      if (left != right) {
        return Integer.compare(left, right);
      }
    }
    return 0;
  }

  /**
   * 解析版本串为数值段数组（最多 4 段）：忽略前缀字母（{@code v2.13.0} → {@code [2,13,0]}）、
   * 截断后缀（{@code 5.3.0-SNAPSHOT} → {@code [5,3,0]}）；解析不出任何数字段返回空数组。
   */
  static int[] parseVersion(String version) {
    if (version == null) {
      return new int[0];
    }
    String cleaned = version.trim();
    int start = 0;
    while (start < cleaned.length() && !Character.isDigit(cleaned.charAt(start))) {
      start++;
    }
    cleaned = cleaned.substring(start);
    if (cleaned.isEmpty()) {
      return new int[0];
    }
    int[] out = new int[4];
    int count = 0;
    int value = -1;
    for (int i = 0; i <= cleaned.length() && count < 4; i++) {
      if (i == cleaned.length() || !Character.isDigit(cleaned.charAt(i))) {
        if (value >= 0) {
          out[count++] = value;
          value = -1;
        }
        if (i < cleaned.length() && cleaned.charAt(i) == '-') {
          break; // 后缀（-SNAPSHOT 等）之后不再解析
        }
        continue;
      }
      value = value < 0 ? 0 : value;
      value = value * 10 + (cleaned.charAt(i) - '0');
    }
    return count == 0 ? new int[0] : java.util.Arrays.copyOf(out, count);
  }
}