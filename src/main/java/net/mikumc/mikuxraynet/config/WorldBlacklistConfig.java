package net.mikumc.mikuxraynet.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 世界黑名单（独立配置文件 {@code blacklist.yml}）：名单内的世界<b>同时豁免反矿透与带宽优化</b>。
 *
 * <p><b>为什么从 {@code antixray.yml} 独立出来</b>：该名单原本只作用于反矿透，但「主城 / 副本 / 小游戏
 * 这类地图既不想伪装、也不想省带宽」是同一件事，因此抽出为一份对两个模块都生效的共享配置。
 *
 * <p><b>匹配语义</b>：世界名支持精确匹配（{@code spawn}）与通配（{@code dungeon_*}，{@code *} 匹配任意
 * 字符序列、可为空）；大小写敏感；任一命中即生效。精确名与通配在构造期拆分并预编译正则，热路径只做
 * 短线性扫描，绝不逐包编译正则。
 *
 * <p><b>线程模型（与 {@link net.mikumc.mikuxraynet.util.BypassRegistry} 同一套）</b>：本类既承载不可变
 * 匹配逻辑，又持有「当前活动实例」的静态引用，供<b>封包线程</b>（netty）无锁查询。写方是插件加载 / 热重载
 * 线程（{@link #publish(WorldBlacklistConfig)} 一次 volatile 写），读方是任意线程——读侧只做字符串比较
 * 与正则匹配，<b>不触碰任何 Bukkit API</b>，因此在封包热路径上安全。
 *
 * <p>未加载（或停用）时静态实例为空名单：{@link #isBlacklistedNow(String)} 恒返回 false，即「所有世界
 * 照常生效」，与既有默认行为一致（fail-open）。
 */
public final class WorldBlacklistConfig {

  /** 当前活动实例；首次加载前为空名单（所有世界都不豁免）。由 {@link #publish} 整体替换。 */
  private static volatile WorldBlacklistConfig active = new WorldBlacklistConfig(List.of());

  /** 原始模式（声明序）；空列表 = 未配置。供启动日志与诊断回显。 */
  private final List<String> patterns;
  /** 精确世界名（启动期拆分，热路径短线性扫描）。 */
  private final List<String> exact;
  /** 通配匹配器（启动期预编译，热路径不做正则编译）。 */
  private final List<Pattern> globs;
  /**
   * 名单的内容哈希（跨进程稳定：字符串列表有规范 hashCode，顺序敏感）。
   *
   * <p><b>不参与磁盘缓存指纹</b>：黑名单世界的区块根本不会被改写、也就不会写入缓存，因此名单变化
   * 天然不影响任何缓存条目的有效性。该值仅供诊断/测试核对「名单是否与上一份相同」。
   */
  private final int configHash;

  private WorldBlacklistConfig(List<String> patterns) {
    this.patterns = List.copyOf(patterns);
    List<String> exactNames = new ArrayList<>(this.patterns.size());
    List<Pattern> globPatterns = new ArrayList<>(this.patterns.size());
    for (String pattern : this.patterns) {
      Pattern glob = compileGlob(pattern);
      if (glob == null) {
        exactNames.add(pattern);
      } else {
        globPatterns.add(glob);
      }
    }
    this.exact = List.copyOf(exactNames);
    this.globs = List.copyOf(globPatterns);
    // 指纹只由名单本身决定（跨进程稳定：字符串列表有规范 hashCode）；顺序敏感，故用有序列表。
    this.configHash = Objects.hash(this.patterns);
  }

  /** 解析 {@code world-blacklist} 段（缺段 = 空名单）。 */
  public static WorldBlacklistConfig from(ConfigurationSection root) {
    if (root == null) {
      return new WorldBlacklistConfig(List.of());
    }
    return fromPatterns(root.getStringList("world-blacklist"));
  }

  /** 由原始模式列表构造（去空白、跳过空条目并按声明序去重）；供单测直接构造。 */
  public static WorldBlacklistConfig fromPatterns(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return new WorldBlacklistConfig(List.of());
    }
    List<String> normalized = new ArrayList<>(raw.size());
    for (String entry : raw) {
      if (entry == null) {
        continue;
      }
      String pattern = entry.trim();
      if (pattern.isEmpty() || normalized.contains(pattern)) {
        continue;
      }
      normalized.add(pattern);
    }
    return new WorldBlacklistConfig(normalized);
  }

  /**
   * 发布新的活动实例（插件加载 / 热重载时调用）。
   *
   * <p>一次 volatile 写替换整个不可变实例：读者要么看到旧名单、要么看到新名单，绝不会看到半更新状态。
   * 传 {@code null} 视为清空（回落「所有世界都不豁免」）。
   */
  public static void publish(WorldBlacklistConfig config) {
    active = config == null ? new WorldBlacklistConfig(List.of()) : config;
  }

  /** 当前活动实例（诊断回显用）。 */
  public static WorldBlacklistConfig current() {
    return active;
  }

  /**
   * 该世界当前是否在黑名单内（封包线程可用的无锁入口，等价于 {@code current().isBlacklisted(name)}）。
   *
   * <p>命名与 {@code BypassRegistry.isBypassedNow} 对齐：静态无锁查询「此刻生效的名单」。
   *
   * @return true 表示该世界同时豁免反矿透与带宽优化
   */
  public static boolean isBlacklistedNow(String worldName) {
    return active.isBlacklisted(worldName);
  }

  /**
   * 某世界是否命中本名单（<b>纯函数</b>：构造期已拆分并预编译，热路径零新增分配）。
   *
   * @return true 表示该世界不使用任何反矿透与带宽优化功能
   */
  public boolean isBlacklisted(String worldName) {
    if (worldName == null || patterns.isEmpty()) {
      return false;
    }
    for (int i = 0; i < exact.size(); i++) {
      if (exact.get(i).equals(worldName)) {
        return true;
      }
    }
    for (int i = 0; i < globs.size(); i++) {
      if (globs.get(i).matcher(worldName).matches()) {
        return true;
      }
    }
    return false;
  }

  /** 是否为「无任何世界被豁免」（诊断与日志用）。 */
  public boolean isEmpty() {
    return patterns.isEmpty();
  }

  /** 原始模式列表（声明序）；供启动日志与诊断回显。 */
  public List<String> patterns() {
    return patterns;
  }

  /** 参与磁盘缓存指纹的取值（名单变化 → 相关世界旧缓存一次性失效重建）。 */
  public int configHash() {
    return configHash;
  }

  /** 编译世界名通配模式：含 {@code *} 时预编译为正则（{@code *} → 任意字符序列）；不含则返回 {@code null}（走精确名比较）。 */
  private static Pattern compileGlob(String pattern) {
    return pattern.indexOf('*') >= 0
        ? Pattern.compile("\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E")
        : null;
  }
}
