package net.mikumc.mikuxraynet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 插件描述（{@code paper-plugin.yml}）自检守门。
 *
 * <p><b>为什么只剩一份描述</b>：本项目只适配 Paper 系核心（Paper / Leaf / Folia）——命令走 Brigadier
 * 生命周期注册、回显走 Adventure {@code Component}、调度走区域调度器，这些在纯 Bukkit/Spigot 上都不成立。
 * 因此传统 {@code plugin.yml} 已删除：Paper 系核心读 modern 描述（{@code paper-plugin.yml}），
 * 保留一份「永远不会生效」的传统描述只会带来两处漂移的风险。本类的断言全部针对这一份文件。
 *
 * <p>解析器说明：不新增依赖 —— 这里用的 snakeyaml 由 {@code io.papermc.paper:paper-api}
 * （provided）以 compile 作用域传递而来，测试期天然可见。
 *
 * <p>读取的是 {@code target/classes} 里「已做资源过滤」的副本，因此本测试顺带守住了
 * {@code pom.xml} 的资源白名单：一旦 paper-plugin.yml 未加入过滤名单，它的 version 会残留
 * {@code ${project.version}} 而立即失败。
 */
class PluginDescriptionConsistencyTest {

  private static final String MODERN = "/paper-plugin.yml";

  /** Paper 插件对 api-version 的下限（源码 PaperPluginMeta.MINIMUM = 1.19）。 */
  private static final String PAPER_API_MINIMUM = "1.19";

  @Test
  void 必需字段存在且占位符已被替换() {
    Map<String, Object> yaml = load(MODERN);
    for (String key : List.of("name", "version", "main", "api-version")) {
      assertNotNull(yaml.get(key), MODERN + " 缺少必需字段 " + key);
    }
    String version = String.valueOf(yaml.get("version"));
    assertFalse(version.contains("${"),
        MODERN + " 的 version 未被资源过滤替换：" + version
            + "（请检查 pom.xml 的 resources 过滤白名单）");
  }

  @Test
  void 现代描述的api版本不低于Paper下限() {
    String apiVersion = String.valueOf(load(MODERN).get("api-version"));
    assertTrue(compareVersions(apiVersion, PAPER_API_MINIMUM) >= 0,
        "paper-plugin.yml 的 api-version=" + apiVersion + " 低于 Paper 插件要求的 " + PAPER_API_MINIMUM);
  }

  /**
   * Folia 支持必须显式声明。
   *
   * <p>删除 {@code plugin.yml} 后，这里是<b>唯一</b>可声明该标记的位置；一旦缺失，Folia 核心会直接
   * 拒绝加载本插件（而 README 与运行时装配都按「Paper/Folia 双端」设计），属于必须在构建期拦下的错误。
   */
  @Test
  void 声明支持Folia() {
    assertEquals(Boolean.TRUE, load(MODERN).get("folia-supported"),
        MODERN + " 必须声明 folia-supported: true（本项目适配 Folia；plugin.yml 已删除，"
            + "没有第二处可声明该标记）");
  }

  @Test
  void 现代描述的依赖段满足类加载隔离要求() {
    Map<String, Object> modern = load(MODERN);
    Map<String, Object> server = child(modern, "dependencies", "server");
    assertNotNull(server, "paper-plugin.yml 缺少 dependencies.server（Paper 插件要求依赖必须写在 " +
        "dependencies 里，不存在 depend/softdepend 字段）");

    // PacketEvents：硬前置，且源码引用其类型，必须并入类路径。
    Map<String, Object> packetEvents = asMap(server.get("packetevents"));
    assertNotNull(packetEvents, "dependencies.server 缺少 packetevents");
    assertEquals(Boolean.TRUE, packetEvents.get("required"), "packetevents 应为硬依赖");
    assertEquals(Boolean.TRUE, packetEvents.get("join-classpath"), "packetevents 必须 join-classpath");
    assertEquals("BEFORE", packetEvents.get("load"), "packetevents 必须先于本插件加载");

    // ProtocolLib：可降级（缺失只停用反矿透主体），但源码引用其类型，必须并入类路径。
    Map<String, Object> protocolLib = asMap(server.get("ProtocolLib"));
    assertNotNull(protocolLib, "dependencies.server 缺少 ProtocolLib");
    assertEquals(Boolean.FALSE, protocolLib.get("required"), "ProtocolLib 应允许缺失（可降级）");
    assertEquals(Boolean.TRUE, protocolLib.get("join-classpath"),
        "ProtocolLib 必须 join-classpath：源码直接引用 com.comphenix.protocol.*，"
            + "隔离状态下否则会 NoClassDefFoundError");
  }

  /**
   * 代码命令常量 ↔ 插件描述的对照守门。
   *
   * <p>注意 Paper 上不会从 YAML 注册任何命令（Paper 只认 Brigadier 生命周期注册，见
   * {@code CommandRegistrar}），因此描述里的 {@code commands} 段只承担「元数据/文档」职责；
   * 即便如此也必须与代码常量保持一致，否则管理员从描述文件读到的命令名/权限会与真实行为不符。
   */
  @Test
  void 代码命令常量与插件描述一致() {
    Map<String, Object> commands = child(load(MODERN), "commands");
    Map<String, Object> command = asMap(commands == null
        ? null : commands.get(CommandRegistrar.COMMAND_NAME));
    assertNotNull(command, MODERN + " 缺少命令 " + CommandRegistrar.COMMAND_NAME
        + "（请同步 CommandRegistrar 的代码常量与插件描述）");

    assertEquals(CommandRegistrar.COMMAND_DESCRIPTION, String.valueOf(command.get("description")),
        MODERN + " 的命令 description 与代码常量 COMMAND_DESCRIPTION 不一致");
    assertEquals(CommandRegistrar.COMMAND_PERMISSION, String.valueOf(command.get("permission")),
        MODERN + " 的命令 permission 与代码常量 COMMAND_PERMISSION 不一致");

    Object aliases = command.get("aliases");
    List<String> aliasList = aliases instanceof List<?> list
        ? list.stream().map(String::valueOf).toList() : List.of();
    assertEquals(CommandRegistrar.COMMAND_ALIASES, aliasList,
        MODERN + " 的命令 aliases 与代码常量 COMMAND_ALIASES 不一致");
  }

  // ---------------------------------------------------------------- 工具方法

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load(String resource) {
    try (InputStream input = PluginDescriptionConsistencyTest.class.getResourceAsStream(resource)) {
      assertNotNull(input, "未找到插件描述资源 " + resource + "（应位于 src/main/resources 下）");
      return new Yaml().load(new InputStreamReader(input, StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new AssertionError("解析 " + resource + " 失败", exception);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> root, String... path) {
    Object current = root;
    for (String key : path) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = ((Map<String, Object>) map).get(key);
    }
    return current instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
  }

  /** 比较形如 {@code 26.2} / {@code 1.20.5} 的版本串（逐段数值比较，段数不足按 0 补齐）。 */
  private static int compareVersions(String left, String right) {
    String[] a = left.split("\\.");
    String[] b = right.split("\\.");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length ? Integer.parseInt(a[i].trim()) : 0;
      int y = i < b.length ? Integer.parseInt(b[i].trim()) : 0;
      if (x != y) {
        return Integer.compare(x, y);
      }
    }
    return 0;
  }
}
