package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 配置默认值回归：锁住本轮「真机反馈」驱动的默认值变更，避免被无意改回。
 *
 * <p>断言的都是「配置项缺失时的回落值」，即内置默认；因此用最小 YAML 触发回落路径。
 */
class ConfigDefaultsTest {

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return configuration;
  }

  // ------------------------------------------------------------ 反矿透

  @Test
  void antiXrayDefaultsFollowRealWorldFeedback() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("enabled: true\n"));

    assertEquals(AntiXrayConfig.ObfuscationMode.ALL, config.obfuscationMode(),
        "默认模式必须是 all（透视不得直接看到裸露在矿洞中的矿）");
    assertEquals(12.0D, config.proximity().distance(), 1.0E-9D,
        "显形距离默认回到 12 格（8 格拉不住矿洞视野，洞内可见矿会不显形）");
    assertEquals(128, config.proximity().maxRevealsPerTick(),
        "单次显形额度默认 128（mode=all 下候选极多，32 个不够）");
    assertEquals(5, config.proximity().intervalTicks(), "巡检周期保持 5 tick（约 0.25 秒一次）");
    assertTrue(config.proximity().raycastEnabled(), "射线可见性默认开启（隔着墙不显形）");
    assertEquals(4.0D, config.proximity().frustumMinDistance(), 1.0E-9D, "min-distance 保持 4 格");
  }

  @Test
  void obfuscationModeParsesExplicitValues() {
    assertEquals(AntiXrayConfig.ObfuscationMode.ENCLOSED,
        AntiXrayConfig.from(yaml("obfuscation:\n  mode: enclosed\n")).obfuscationMode());
    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        AntiXrayConfig.from(yaml("obfuscation:\n  mode: all\n")).obfuscationMode());
    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        AntiXrayConfig.from(yaml("obfuscation:\n  mode: nonsense\n")).obfuscationMode(),
        "非法取值一律回落为最安全的 all");
  }

  @Test
  void obfuscationModeParticipatesInConfigHash() {
    AntiXrayConfig enclosed = AntiXrayConfig.from(yaml("obfuscation:\n  mode: enclosed\n"));
    AntiXrayConfig all = AntiXrayConfig.from(yaml("obfuscation:\n  mode: all\n"));

    assertNotEquals(enclosed.configHash(), all.configHash(),
        "模式必须参与配置指纹，否则切换后旧缓存会被复用而泄漏裸露矿");
  }

  // ------------------------------------------------------------ 带宽

  @Test
  void bandwidthBlockChangeDefaultsFollowMiningStutterFix() {
    BandwidthConfig config = BandwidthConfig.from(yaml("enabled: true\n"));

    assertEquals(8, config.blockChanges().immediateRadius(), "近身变更立即放行半径默认 8 格");
    assertEquals(20, config.blockChanges().mergeWindowMillis(), "合并窗口默认收紧到 20ms");
  }
}