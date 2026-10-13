package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MikuConfig#load()} 的异常安全回归：解析失败绝不能让异常冒泡到 {@code onLoad}
 * （否则服务端会把插件整体禁用），且必须保留上一份有效配置。
 *
 * <p><b>为什么用「递归别名」构造畸形 yml</b>：{@code MikuConfig#read} 走 {@code loadFromString}，普通语法
 * 错误会抛 {@code InvalidConfigurationException}（属 {@code Exception}）并到达 {@code catch (Throwable)}；
 * 而本用例要覆盖更强的「{@code Error}（如 {@code StackOverflowError}，非 {@code Exception}）也会被兜住」
 * 这条分支——自引用别名 {@code a: &x / b: *x} 会让 {@code YamlConfiguration.fromNodeTree} 无限递归、
 * 抛 {@code StackOverflowError}，正合此意。
 *
 * <p><b>控制台/CI 日志里出现的那条「解析失败」WARN 是本用例的预期产物，并非配置文件坏了</b>：
 * {@link MikuConfig#warnFallbackOnce} 对 {@code StackOverflowError} 只打一行结论（不再倾倒数千层堆栈，
 * 否则会淹没日志/CI）；真实资源文件 {@code src/main/resources/antixray.yml} 与 {@code bandwidth.yml}
 * 不含任何锚点/别名，可被正常解析（由 {@code ConfigDefaultsTest} 用真实解析器逐键守门）。
 *
 * <p>用动态代理桩 {@link Plugin}（不引入新依赖），数据目录指向 {@link TempDir}，
 * 两处配置文件均预先写好，因此不会触发 {@code saveResource}。
 */
class MikuConfigTest {

  private static final Logger LOGGER = Logger.getLogger("MikuConfigTest");

  /** 畸形结构：自引用别名让 SnakeYAML 解析时递归到溢出（抛 Error，不被 loadConfiguration 吞掉）。 */
  private static final String MALFORMED = "a: &x\n  b: *x\n";

  /** 解析失败（重载）必须保留上一份有效配置，且 load() 不抛。 */
  @Test
  void malformedConfigKeepsPreviousValidValues(@TempDir Path dir) throws Exception {
    write(dir, MikuConfig.ANTI_XRAY_FILE, "enabled: false\n");
    write(dir, MikuConfig.BANDWIDTH_FILE, "enabled: false\n");

    MikuConfig config = new MikuConfig(plugin(dir));
    assertDoesNotThrow(config::load);
    AntiXrayConfig firstAntiXray = config.antiXray();
    BandwidthConfig firstBandwidth = config.bandwidth();
    assertNotNull(firstAntiXray);
    assertNotNull(firstBandwidth);

    write(dir, MikuConfig.ANTI_XRAY_FILE, MALFORMED);
    write(dir, MikuConfig.BANDWIDTH_FILE, MALFORMED);

    assertDoesNotThrow(config::load,
        "配置解析抛异常时 load() 不得冒泡：onLoad 抛出会让插件被整体禁用");
    assertSame(firstAntiXray, config.antiXray(), "反矿透解析失败必须保留上一份有效配置");
    assertSame(firstBandwidth, config.bandwidth(), "带宽解析失败必须保留上一份有效配置");
  }

  /** 首次加载即失败：回落到内置默认，绝不为 null（否则后续按 loadedAntiXray 打摘要会 NPE）。 */
  @Test
  void firstLoadFailureFallsBackToBuiltinDefaults(@TempDir Path dir) throws Exception {
    write(dir, MikuConfig.ANTI_XRAY_FILE, MALFORMED);
    write(dir, MikuConfig.BANDWIDTH_FILE, MALFORMED);

    MikuConfig config = new MikuConfig(plugin(dir));
    assertDoesNotThrow(config::load);
    assertNotNull(config.antiXray(), "首次加载失败必须回落到内置默认（不能为 null）");
    assertNotNull(config.bandwidth(), "首次加载失败必须回落到内置默认（不能为 null）");
  }

  private static void write(Path dir, String name, String content) throws Exception {
    Files.writeString(dir.resolve(name), content);
  }

  private static Plugin plugin(Path dir) {
    return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
        new Class<?>[] {Plugin.class}, new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
              case "getDataFolder" -> dir.toFile();
              case "getLogger" -> LOGGER;
              case "toString" -> "PluginStub";
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> proxy == args[0];
              default -> null;
            };
          }
        });
  }
}