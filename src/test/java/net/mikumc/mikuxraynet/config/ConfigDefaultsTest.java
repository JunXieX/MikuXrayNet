package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
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
    assertEquals(48.0D, config.proximity().distance(), 1.0E-9D,
        "显形距离默认 48 格（32 仍偏近；显形只在视线通畅且方块有暴露面时还原，放大距离不会隔墙泄露）");
    assertEquals(256, config.proximity().maxRevealsPerTick(),
        "单次显形额度默认 256（原 128；配合 48 格距离，候选更多，额度需相应放大）");
    assertEquals(5, config.proximity().intervalTicks(), "巡检周期保持 5 tick（约 0.25 秒一次）");
    assertTrue(config.proximity().raycastEnabled(), "可见性判定默认开启（隔着墙不显形）");
    assertEquals(4.0D, config.proximity().frustumMinDistance(), 1.0E-9D, "min-distance 保持 4 格");
    assertEquals(524288, config.proximity().maxPositionsPerPlayer(),
        "单玩家坐标上限默认 524288（262144 在真机仍被顶到、淘汰 1400 个坐标）");
    assertEquals(4194304, config.proximity().maxPositions(),
        "全服坐标上限默认 4194304（原 2097152，约 8 个满配玩家，避免多玩家同时在线立刻触顶）");
    assertEquals(300, config.proximity().expireSeconds(), "显形索引过期默认 300 秒（原为 120）");
  }

  /** 默认隐藏清单必须是 21 种，含新加入的两种「与矿等价」的透视目标。 */
  @Test
  void defaultHideBlocksIncludeSpawnerAndMossyCobblestone() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("enabled: true\n"));

    assertEquals(21, config.hideBlocks().size(),
        "默认隐藏清单 21 种（19 种矿石 + spawner + mossy_cobblestone）：" + config.hideBlocks());
    assertTrue(config.hideBlocks().contains("spawner"),
        "必须隐藏刷怪笼（真机 PE 26.2 注册名就是 spawner）：" + config.hideBlocks());
    assertTrue(config.hideBlocks().contains("mossy_cobblestone"),
        "必须隐藏苔石（地牢/要塞/矿洞结构的标志物）：" + config.hideBlocks());
    assertTrue(config.hideBlocks().contains("ancient_debris"), "原有矿种不得丢失");
  }

  /** 平台判定兜底：缺失/非法值回落 auto，显式值可解析（手动指定的唯一出口）。 */
  @Test
  void platformFallsBackToAutoAndParsesExplicitValues() {
    assertEquals(PlatformSupport.Mode.AUTO, AntiXrayConfig.from(yaml("enabled: true\n")).platform(),
        "缺失 advanced.platform 时默认 auto（按服务端品牌/版本标识判定）");
    assertEquals(PlatformSupport.Mode.AUTO,
        AntiXrayConfig.from(yaml("advanced:\n  platform: auto\n")).platform());
    assertEquals(PlatformSupport.Mode.FOLIA,
        AntiXrayConfig.from(yaml("advanced:\n  platform: folia\n")).platform(),
        "真机标识不标准时可手动指定 folia");
    assertEquals(PlatformSupport.Mode.PAPER,
        AntiXrayConfig.from(yaml("advanced:\n  platform: Paper\n")).platform(),
        "大小写不敏感");
    assertEquals(PlatformSupport.Mode.AUTO,
        AntiXrayConfig.from(yaml("advanced:\n  platform: nonsense\n")).platform(),
        "非法值回落 auto（以真实标识为准最安全）");
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