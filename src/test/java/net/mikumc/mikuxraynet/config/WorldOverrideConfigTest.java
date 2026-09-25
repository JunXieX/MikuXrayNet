package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.config.AntiXrayConfig.Dimension;
import net.mikumc.mikuxraynet.config.AntiXrayConfig.ObfuscationMode;
import net.mikumc.mikuxraynet.config.AntiXrayConfig.ReplacementBand;
import net.mikumc.mikuxraynet.config.AntiXrayConfig.WorldOverride;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 按维度分段（dimensions）与逐世界覆盖（world-overrides）的配置解析测试。
 *
 * <p>覆盖四类语义：
 * <ol>
 *   <li>解析回落：维度段只写需要覆盖的键，未覆盖的键回落该维度的内置默认；
 *       世界覆盖段未覆盖的键回落「该世界所属维度」的生效值；</li>
 *   <li>匹配规则：精确名 &gt; 通配（{@code world_*}），多个通配取最长模式，无匹配返回 -1；</li>
 *   <li>configHash：维度生效值、世界覆盖、高度范围与分区表都参与指纹（不同指纹缓存不串）；</li>
 *   <li>非法/矛盾配置被保守丢弃（空段比错误段更安全，回落默认不漏伪装）。</li>
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

  private static AntiXrayConfig.EffectiveObfuscation normal(AntiXrayConfig config) {
    return config.dimensionEffective(Dimension.NORMAL);
  }

  // ---------------------------------------------------------- 维度分段与回落

  @Test
  void dimensionSectionParsesAndUnsetKeysFallBackToBuiltinDefaults() {
    AntiXrayConfig config = config("""
        dimensions:
          normal:
            replacement-weights:
              stone: 5
            mode: enclosed
            min-y: -32
            enabled: false
        """);

    AntiXrayConfig.EffectiveObfuscation normal = normal(config);
    assertEquals("{stone=5}", normal.replacementWeights().toString(), "维度段显式权重生效");
    assertEquals(ObfuscationMode.ENCLOSED, normal.mode(), "维度段显式模式生效");
    assertEquals(-32, normal.minY(), "维度段显式高度下界生效");
    assertEquals(Integer.MAX_VALUE, normal.maxY(), "未配置的 max-y 回落内置默认（不限制）");
    assertEquals(35, normal.hideBlocks().size(), "未配置的 hide-blocks 回落内置默认（主世界 35 种）");
    assertFalse(config.dimensionEnabled(Dimension.NORMAL), "维度段可显式关闭本维度");
    // 未出现的维度段整体回落内置默认
    assertEquals(15, config.dimensionEffective(Dimension.NETHER).hideBlocks().size(),
        "未配置的地狱段回落内置默认（15 种）");
    assertEquals(12, config.dimensionEffective(Dimension.THE_END).hideBlocks().size(),
        "未配置的末地段回落内置默认（12 种）");
    assertFalse(config.dimensionEnabled(Dimension.THE_END), "末地内置默认关闭");
    assertFalse(config.dimensionsMissing(), "至少存在一个维度段 → 不算缺 dimensions 段");
  }

  @Test
  void worldOverridesParseAndUnsetKeysFallBackToDimension() {
    AntiXrayConfig config = config("""
        dimensions:
          nether:
            mode: enclosed
            min-y: -16
          normal:
            replacement-weights:
              stone: 5
        world-overrides:
          world_nether:
            replacement-weights:
              netherrack: 3
            max-y: 120
        """);

    assertEquals(1, config.worldOverrides().size());
    WorldOverride override = config.worldOverrides().get(0);
    assertEquals("world_nether", override.pattern(), "世界模式名");
    assertNull(override.hideBlocks(), "未覆盖的 hide-blocks 保持 null（回落维度）");
    assertNull(override.mode(), "未覆盖的 mode 保持 null（回落维度）");

    // 生效视图：覆盖值 > 维度值 > 内置默认
    var effective = config.overrideEffective(0, Dimension.NETHER);
    assertEquals("{netherrack=3}", effective.replacementWeights().toString(), "覆盖的权重表生效");
    assertEquals(config.dimensionEffective(Dimension.NETHER).hideBlocks(), effective.hideBlocks(),
        "hide-blocks 回落地狱维度");
    assertEquals(ObfuscationMode.ENCLOSED, effective.mode(), "mode 回落地狱维度");
    assertEquals(-16, effective.minY(), "min-y 回落地狱维度的 -16");
    assertEquals(120, effective.maxY(), "覆盖的 max-y 生效");
    // 同一覆盖段在不同维度上的「未覆盖键」回落目标不同（覆盖键则各维度一致）
    assertEquals(3, config.overrideEffective(0, Dimension.NORMAL).replacementWeights().get("netherrack"),
        "覆盖的权重表在主世界维度上同样生效");
    assertEquals(config.dimensionEffective(Dimension.NORMAL).hideBlocks(),
        config.overrideEffective(0, Dimension.NORMAL).hideBlocks(),
        "未覆盖的 hide-blocks 在主世界维度上回落主世界维度值");
    assertEquals(config.dimensionEffective(Dimension.NETHER).hideBlocks(),
        config.overrideEffective(0, Dimension.NETHER).hideBlocks(),
        "未覆盖的 hide-blocks 在地狱维度上回落地狱维度值");
    assertEquals(config.dimensionEffective(Dimension.NORMAL).mode(),
        config.overrideEffective(0, Dimension.NORMAL).mode(),
        "未覆盖的 mode 在主世界维度上回落主世界维度值（即默认 all）");
    assertEquals("{stone=5}", normal(config).replacementWeights().toString(),
        "主世界维度视图不受该覆盖影响");
  }

  @Test
  void noOverridesMeansNoMatch() {
    AntiXrayConfig config = config("enabled: true\n");
    assertTrue(config.worldOverrides().isEmpty(), "无 world-overrides 段时为空");
    assertEquals(-1, config.matchOverride("world"), "无覆盖时任何世界都匹配不到");
    assertEquals(Integer.MIN_VALUE, normal(config).minY(), "生效视图下界哨兵 = 不限制");
    assertEquals(Integer.MAX_VALUE, normal(config).maxY(), "生效视图上界哨兵 = 不限制");
    assertEquals(2, normal(config).replacementBands().size(), "主世界内置默认带 2 段分区伪装表");
  }

  // ---------------------------------------------------------- 匹配规则

  @Test
  void matchOverridePrefersExactThenLongestWildcard() {
    AntiXrayConfig config = config("""
        world-overrides:
          world_*:
            mode: enclosed
          world_nether:
            mode: all
          world_resource_*:
            mode: enclosed
        """);

    assertEquals(1, config.matchOverride("world_nether"), "精确名优先，即使声明在通配之后");
    assertEquals(0, config.matchOverride("world_plain"), "普通通配命中 world_*");
    assertEquals(2, config.matchOverride("world_resource_1"), "更长的通配（更具体）优先");
    assertEquals(2, config.matchOverride("world_resource_"), "通配 * 允许空后缀");
    assertEquals(-1, config.matchOverride("unlisted"), "未匹配返回 -1（用维度默认）");
    assertEquals(-1, config.matchOverride(null), "null 世界名返回 -1");
  }

  // ---------------------------------------------------------- configHash

  @Test
  void dimensionsHeightRangesBandsAndOverridesParticipateInConfigHash() {
    String base = """
        dimensions:
          normal:
            replacement-weights:
              stone: 5
        """;
    AntiXrayConfig plain = config(base);
    AntiXrayConfig withOverride = config(base + """
        world-overrides:
          world_nether:
            replacement-weights:
              netherrack: 3
        """);
    AntiXrayConfig withOtherOverride = config(base + """
        world-overrides:
          world_nether:
            replacement-weights:
              netherrack: 9
        """);
    AntiXrayConfig withBands = config("""
        dimensions:
          normal:
            replacement-weights:
              stone: 5
            replacement-bands:
              - min-y: -64
                max-y: -1
                weights: {deepslate: 10}
        """);
    AntiXrayConfig withRange = config("""
        dimensions:
          normal:
            replacement-weights:
              stone: 5
            min-y: 0
        """);
    // 只切换「地狱」维度的配置：主世界生效值不变，但维度选择与地狱生效值变化必须改变指纹
    AntiXrayConfig withNetherMode = config("""
        dimensions:
          normal:
            replacement-weights:
              stone: 5
          nether:
            mode: enclosed
        """);
    AntiXrayConfig withEndEnabled = config("""
        dimensions:
          normal:
            replacement-weights:
              stone: 5
          the_end:
            enabled: true
        """);

    assertNotEquals(plain.configHash(), withOverride.configHash(), "新增世界覆盖必须改变指纹");
    assertNotEquals(withOverride.configHash(), withOtherOverride.configHash(),
        "覆盖值变化必须改变指纹（该世界的缓存不得复用）");
    assertNotEquals(plain.configHash(), withBands.configHash(), "分区伪装表必须参与指纹");
    assertNotEquals(plain.configHash(), withRange.configHash(), "高度范围必须参与指纹");
    assertNotEquals(plain.configHash(), withNetherMode.configHash(),
        "其它维度的 mode 变化也必须改变指纹（维度选择与各维度生效值都纳入指纹）");
    assertNotEquals(plain.configHash(), withEndEnabled.configHash(),
        "维度启用开关必须参与指纹（否则开关末地后旧缓存会被复用）");
    // 流体覆盖影响改写结果与显形行为，必须参与指纹
    assertNotEquals(plain.configHash(),
        config(base + "occlusion:\n  fluid-cover: false\n").configHash(),
        "occlusion.fluid-cover 必须参与指纹");
    // 同一份配置重复解析：指纹必须稳定
    assertEquals(withOverride.configHash(), config(base + """
        world-overrides:
          world_nether:
            replacement-weights:
              netherrack: 3
        """).configHash(), "相同覆盖配置的指纹必须一致");
  }

  // ---------------------------------------------------------- replacement-bands

  @Test
  void replacementBandsParseAndDropInvalidEntries() {
    AntiXrayConfig config = config("""
        dimensions:
          normal:
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

    assertEquals(2, normal(config).replacementBands().size(), "矛盾段（min>max）与空权重段被丢弃");
    ReplacementBand deep = normal(config).replacementBands().get(0);
    assertEquals(-64, deep.minY());
    assertEquals(-1, deep.maxY());
    assertEquals("{deepslate=10, tuff=4, stone=2}", deep.weights().toString(), "段内权重保持声明序");
    ReplacementBand shallow = normal(config).replacementBands().get(1);
    assertEquals(0, shallow.minY());
    assertEquals(320, shallow.maxY());
    // min-y/max-y 缺省的段覆盖正负无穷
    AntiXrayConfig openEnded = config("""
        dimensions:
          normal:
            replacement-bands:
              - weights: {stone: 1}
        """);
    assertEquals(Integer.MIN_VALUE, normal(openEnded).replacementBands().get(0).minY(),
        "min-y 缺省 = 负无穷");
    assertEquals(Integer.MAX_VALUE, normal(openEnded).replacementBands().get(0).maxY(),
        "max-y 缺省 = 正无穷");
  }

  @Test
  void worldOverrideSupportsBandsHeightRangeAndUseBlockBelow() {
    AntiXrayConfig config = config("""
        world-overrides:
          world_nether:
            min-y: 0
            max-y: 120
            use-block-below: true
            replacement-bands:
              - min-y: 0
                max-y: 40
                weights: {basalt: 5}
        """);

    var effective = config.overrideEffective(0, Dimension.NETHER);
    assertEquals(0, effective.minY(), "世界段的高度范围生效");
    assertEquals(120, effective.maxY());
    assertTrue(effective.useBlockBelow(), "世界段可覆盖 use-block-below");
    assertEquals(1, effective.replacementBands().size(), "世界段可覆盖 replacement-bands");
    assertEquals("{basalt=5}", effective.replacementBands().get(0).weights().toString());
  }
}