package net.mikumc.mikuxraynet.config;

import java.util.regex.Pattern;

/**
 * 世界名通配模式的<b>唯一</b>编译实现（{@code antixray.yml} 的 world-overrides 与 {@code blacklist.yml} 共用）。
 *
 * <p><b>为什么必须只有一份</b>：两处配置的匹配语义被文档承诺为「完全一致」（精确名整串相等、
 * {@code *} 匹配任意字符序列且可为空、大小写敏感），但先前各写了一份逐字节相同的正则构造。
 * 任何一处单独修改（例如给 {@code *} 加「不跨冒号」、或补转义边界）都会让两个配置项的匹配规则悄悄分叉，
 * 使用者无从察觉——抽成一份后，语义漂移在结构上不可能发生。
 *
 * <p><b>语义（与既有实现逐例一致，勿随意改）</b>：
 * <ul>
 *   <li>不含 {@code *} 的模式返回 {@code null}：调用方走「精确名 {@code equals}」快路径，无需正则、
 *       零分配（封包热路径上的默认形态）；</li>
 *   <li>含 {@code *} 的模式编译为 {@code \Qa\E.*\Qb\E} 形式：{@code *} 之外一律按字面量匹配，
 *       因此 {@code ?} / {@code [} / {@code .} 等正则元字符都不具特殊含义（{@code ?} 就是字面问号）。</li>
 * </ul>
 */
final class WorldGlobs {

  private WorldGlobs() {
  }

  /**
   * 编译世界名通配模式。
   *
   * @param pattern 原始模式（已 trim、非空；由调用方保证）
   * @return 含 {@code *} 时返回预编译正则；不含时返回 {@code null}（调用方应走精确名比较）
   */
  static Pattern compile(String pattern) {
    return pattern.indexOf('*') >= 0
        ? Pattern.compile("\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E")
        : null;
  }
}
