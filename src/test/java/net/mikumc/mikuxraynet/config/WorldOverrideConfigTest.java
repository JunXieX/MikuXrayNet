package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.config.AntiXrayConfig.ObfuscationMode;
import net.mikumc.mikuxraynet.config.AntiXrayConfig.ReplacementBand;
import net.mikumc.mikuxraynet.config.AntiXrayConfig.WorldOverride;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * P0-2/P0-3 配置解析测试：逐世界覆盖（world-overrides）、全局/逐世界高度范围
 * （obfuscation.min-y/max-y）与按 Y 分区伪装表（replacement-bands）。
 *
 * <p>覆盖四类语义：
 * <ol>
 *   <li>解析回落：覆盖段只写需要覆盖的键，未覆盖的键回落全局默认；</li>
 *   <li>匹配规则：精确名 &gt; 通配（{@code world_*}），多个通配取最长模式，无匹配返回 -1；</li>
 *   <li>configHash：世界覆盖、高度范围与分区表都参与指纹（不同指纹缓存不串）；</li>
 *   <li>非法/矛盾配置被保守丢弃（空段比错误段更安全，回落全局默认不漏伪装）。</li>
 * </ol>
 */
class WorldOverrideConfigTest {

  private static AntiXrayConfig config(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return AntiXrayConfig.from(configuration);
  }

  // ---------------------------------------------------------- 解析与回落

  @Test
  void overrideParsesAndUnsetKeysFallBackToGlobal() {
    AntiXrayConfig config = config("""
        obfuscation:
          replacement-weights:
            stone: 5
          mode: enclosed
          min-y: -32
        world-overrides:
          world_nether:
            obfuscation:
              replacement-weights:
                netherrack: 3
              max-y: 120
        """);

    assertEquals(1, config.worldOverrides().size());
    WorldOverride override = config.worldOverrides().get(0);
    assertEquals("world_nether", override.pattern(), "世界模式名");
    assertNull(override.hideBlocks(), "未覆盖的 hide-blocks 保持 null（回落全局）");
    assertNull(override.mode(), "未覆盖的 mode 保持 null（回落全局）");

    // 生效视图：覆盖值 > 全局值 > 缺省
    var effective = config.overrideEffective(0);
    assertEquals("{netherrack=3}", effective.replacementWeights().toString(), "覆盖的权重表生效");
    assertEquals(config.hideBlocks(), effective.hideBlocks(), "hide-blocks 回落全局");
    assertEquals(ObfuscationMode.ENCLOSED, effective.mode(), "mode 回落全局");
    assertEquals(-32, effective.minY(), "min-y 回落全局的 -32");
    assertEquals(120, effective.maxY(), "覆盖的 max-y 生效");
    var global = config.globalEffective();
    assertEquals("{stone=5}", global.replacementWeights().toString(), "全局视图不受覆盖影响");
  }

  @Test
  void noOverridesMeansGlobalEverywhere() {
    AntiXrayConfig config = config("enabled: true\n");
    assertTrue(config.worldOverrides().isEmpty(), "无 world-overrides 段时为空");
    assertEquals(-1, config.matchOverride("world"), "无覆盖时任何世界都匹配不到");
    assertNull(config.obfuscationMinY(), "min-y 缺省不限制（null）");
    assertNull(config.obfuscationMaxY(), "max-y 缺省不限制（null）");
    assertEquals(Integer.MIN_VALUE, config.globalEffective().minY(), "生效视图下界哨兵 = 不限制");
    assertEquals(Integer.MAX_VALUE, config.globalEffective().maxY(), "生效视图上界哨兵 = 不限制");
    assertTrue(config.replacementBands().isEmpty(), "replacement-bands 缺省为空（回落权重表）");
  }

  // ---------------------------------------------------------- 匹配规则

  @Test
  void matchOverridePrefersExactThenLongestWildcard() {
    AntiXrayConfig config = config("""
        world-overrides:
          world_*:
            obfuscation:
              mode: enclosed
          world_nether:
            obfuscation:
              mode: all
          world_resource_*:
            obfuscation:
              mode: enclosed
        """);

    assertEquals(1, config.matchOverride("world_nether"), "精确名优先，即使声明在通配之后");
    assertEquals(0, config.matchOverride("world_plain"), "普通通配命中 world_*");
    assertEquals(2, config.matchOverride("world_resource_1"), "更长的通配（更具体）优先");
    assertEquals(2, config.matchOverride("world_resource_"), "通配 * 允许空后缀");
    assertEquals(-1, config.matchOverride("unlisted"), "未匹配返回 -1（用全局默认）");
    assertEquals(-1, config.matchOverride(null), "null 世界名返回 -1");
  }

  // ---------------------------------------------------------- configHash

  @Test
  void worldOverridesAndBandsParticipateInConfigHash() {
    String base = """
        obfuscation:
          replacement-weights:
            stone: 5
        """;
    AntiXrayConfig plain = config(base);
    AntiXrayConfig withOverride = config(base + """
        world-overrides:
          world_nether:
            obfuscation:
              replacement-weights:
                netherrack: 3
        """);
    AntiXrayConfig withOtherOverride = config(base + """
        world-overrides:
          world_nether:
            obfuscation:
              replacement-weights:
                netherrack: 9
        """);
    AntiXrayConfig withBands = config("""
        obfuscation:
          replacement-weights:
            stone: 5
          replacement-bands:
            - min-y: -64
              max-y: -1
              weights: {deepslate: 10}
        """);
    AntiXrayConfig withRange = config("""
        obfuscation:
          replacement-weights:
            stone: 5
          min-y: 0
        """);

    assertNotEquals(plain.configHash(), withOverride.configHash(), "新增世界覆盖必须改变指纹");
    assertNotEquals(withOverride.configHash(), withOtherOverride.configHash(),
        "覆盖值变化必须改变指纹（该世界的缓存不得复用）");
    assertNotEquals(plain.configHash(), withBands.configHash(), "分区伪装表必须参与指纹");
    assertNotEquals(plain.configHash(), withRange.configHash(), "高度范围必须参与指纹");
    // 同一份配置重复解析：指纹必须稳定
    assertEquals(withOverride.configHash(), config(base + """
        world-overrides:
          world_nether:
            obfuscation:
              replacement-weights:
                netherrack: 3
        """).configHash(), "相同覆盖配置的指纹必须一致");
  }

  // ---------------------------------------------------------- replacement-bands

  @Test
  void replacementBandsParseAndDropInvalidEntries() {
    AntiXrayConfig config = config("""
        obfuscation:
          replacement-bands:
            - min-y: -64
              max-y: -1
              weights: {deepslate: 10, tuff: 4, stone: 2}
            - min-y: 0
              max-y: 320
              weights: {stone: 10, andesite: 3}
            - min-y: 100
              max-y: 50
              weights: {stone: 5}
            - min-y: 0
              max-y: 10
              weights: {stone: 0, dirt: -3}
        """);

    assertEquals(2, config.replacementBands().size(), "矛盾段（min>max）与空权重段被丢弃");
    ReplacementBand deep = config.replacementBands().get(0);
    assertEquals(-64, deep.minY());
    assertEquals(-1, deep.maxY());
    assertEquals("{deepslate=10, tuff=4, stone=2}", deep.weights().toString(), "段内权重保持声明序");
    ReplacementBand shallow = config.replacementBands().get(1);
    assertEquals(0, shallow.minY());
    assertEquals(320, shallow.maxY());
    // min-y/max-y 缺省的段覆盖正负无穷
    AntiXrayConfig openEnded = config("""
        obfuscation:
          replacement-bands:
            - weights: {stone: 1}
        """);
    assertEquals(Integer.MIN_VALUE, openEnded.replacementBands().get(0).minY(), "min-y 缺省 = 负无穷");
    assertEquals(Integer.MAX_VALUE, openEnded.replacementBands().get(0).maxY(), "max-y 缺省 = 正无穷");
  }

  @Test
  void worldOverrideSupportsBandsAndHeightRange() {
    AntiXrayConfig config = config("""
        world-overrides:
          world_nether:
            obfuscation:
              min-y: 0
              max-y: 120
              replacement-bands:
                - min-y: 0
                  max-y: 40
                  weights: {basalt: 5}
        """);

    var effective = config.overrideEffective(0);
    assertEquals(0, effective.minY(), "世界段的高度范围生效");
    assertEquals(120, effective.maxY());
    assertEquals(1, effective.replacementBands().size(), "世界段可覆盖 replacement-bands");
    assertEquals("{basalt=5}", effective.replacementBands().get(0).weights().toString());
  }
}
