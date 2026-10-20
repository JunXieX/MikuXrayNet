package net.mikumc.mikuxraynet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 邻近显形启停门闸：<b>只看 {@code proximity.enabled}，反矿透总开关不得参与</b>。
 *
 * <p>{@link AntiXrayRuntime} 实例的构造需要 {@code Plugin}，无法离线构造，因此把这条判定抽成包级
 * 纯函数 {@link AntiXrayRuntime#proximitySwitchOn(AntiXrayConfig)} 并在这里逐个断言。
 *
 * <p>回归背景（两个方向都要钉住）：
 * <ul>
 *   <li>旧缺陷一：reload 只凭「字段非 null」判定，把 {@code proximity.enabled} 改成 false 后显形仍会
 *       照常启动（「关闭即零开销」失效）；</li>
 *   <li>旧缺陷二（本项目禁止的方向）：曾把总开关也并进门闸，{@code antixray.enabled=false} 时 reload
 *       会停掉显形并清空伪装索引 —— 客户端里已经存在的伪装方块于是永远无法换回真实方块（要等区块
 *       重发），属「该显形不显形 / 玩家体验异常」。总开关关闭时必须继续显形兜底。</li>
 * </ul>
 */
class AntiXrayRuntimeProximityGateTest {

  private static AntiXrayConfig config(String yaml) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(yaml);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return AntiXrayConfig.from(configuration);
  }

  /** 显形开关开启即运行（与总开关无关；proximity.enabled 缺省为 true）。 */
  @Test
  void proximitySwitchOnRunsRegardlessOfMasterSwitch() {
    assertTrue(AntiXrayRuntime.proximitySwitchOn(
            config("enabled: true\nproximity:\n  enabled: true\n")),
        "总开关与显形开关都开启时必须运行邻近显形");
    // proximity.enabled 缺省 true：只写总开关也必须视为开启
    assertTrue(AntiXrayRuntime.proximitySwitchOn(config("enabled: true\n")),
        "proximity.enabled 缺省为 true，只写 enabled: true 也必须运行邻近显形");
  }

  /**
   * 核心回归：总开关关闭**不得**让门闸返回 false —— 否则 reload 会停显形并清空索引，
   * 已下发的伪装方块将永远无法还原（要等区块重发）。显形必须继续兜底。
   */
  @Test
  void masterSwitchOffStillRunsProximityForAlreadySentFakeBlocks() {
    assertTrue(AntiXrayRuntime.proximitySwitchOn(
            config("enabled: false\nproximity:\n  enabled: true\n")),
        "总开关关闭时邻近显形仍须运行（兜底还原已下发的伪装方块）");
    assertTrue(AntiXrayRuntime.proximitySwitchOn(config("enabled: false\n")),
        "总开关关闭且 proximity.enabled 缺省（true）时同样必须继续显形");
  }

  /** 显形开关关闭时必须停用（与启动路径「关闭即不建索引、零开销」一致）。 */
  @Test
  void proximitySwitchOffStopsProximity() {
    assertFalse(AntiXrayRuntime.proximitySwitchOn(
            config("enabled: true\nproximity:\n  enabled: false\n")),
        "显形开关关闭时不得运行邻近显形");
    assertFalse(AntiXrayRuntime.proximitySwitchOn(
            config("enabled: false\nproximity:\n  enabled: false\n")),
        "两个开关都关闭时不得运行邻近显形");
  }

  /** 防御性分支：配置尚未加载 / 读取失败时不应起任何巡检任务。 */
  @Test
  void missingConfigNeverRunsProximity() {
    assertFalse(AntiXrayRuntime.proximitySwitchOn(null), "配置缺失时不得运行邻近显形");
  }
}
