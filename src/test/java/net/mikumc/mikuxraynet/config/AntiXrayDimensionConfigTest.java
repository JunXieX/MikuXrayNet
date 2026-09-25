package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig.Dimension;
import net.mikumc.mikuxraynet.config.AntiXrayConfig.ObfuscationMode;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 按维度分段（dimensions）的核心语义测试。
 *
 * <p>覆盖：
 * <ol>
 *   <li>三个维度各自独立生效（mode / hide-blocks / 权重互不影响）；</li>
 *   <li>world-overrides 仍为最高优先级（覆盖维度值）；</li>
 *   <li>缺 dimensions 段 → 内置默认生效 + 一次性中文 WARN；</li>
 *   <li>维度识别（NORMAL/NETHER/THE_END/CUSTOM）；</li>
 *   <li>configHash 随维度配置变化。</li>
 * </ol>
 */
class AntiXrayDimensionConfigTest {

  private static AntiXrayConfig config(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return AntiXrayConfig.from(configuration);
  }

  /** 三个维度各自独立：主世界 all、地狱 enclosed，隐藏清单与权重各不相同。 */
  @Test
  void threeDimensionsParseIndependently() {
    AntiXrayConfig config = config("""
        dimensions:
          normal:
            enabled: true
            mode: all
            hide-blocks: [diamond_ore, deepslate_diamond_ore]
            replacement-weights: {stone: 10}
          nether:
            enabled: true
            mode: enclosed
            hide-blocks: [ancient_debris]
            replacement-weights: {netherrack: 10, basalt: 4}
          the_end:
            enabled: true
            mode: enclosed
            hide-blocks: [bedrock]
            replacement-weights: {end_stone: 10}
        """);

    assertEquals(ObfuscationMode.ALL, config.dimensionEffective(Dimension.NORMAL).mode());
    assertEquals(ObfuscationMode.ENCLOSED, config.dimensionEffective(Dimension.NETHER).mode(),
        "伪装模式必须按维度独立（地狱 enclosed、主世界 all）");
    assertEquals(ObfuscationMode.ENCLOSED, config.dimensionEffective(Dimension.THE_END).mode());

    assertEquals(List.of("diamond_ore", "deepslate_diamond_ore"),
        config.dimensionEffective(Dimension.NORMAL).hideBlocks());
    assertEquals(List.of("ancient_debris"),
        config.dimensionEffective(Dimension.NETHER).hideBlocks());
    assertEquals(List.of("bedrock"),
        config.dimensionEffective(Dimension.THE_END).hideBlocks());

    assertEquals("{stone=10}", config.dimensionEffective(Dimension.NORMAL).replacementWeights().toString());
    assertEquals("{netherrack=10, basalt=4}",
        config.dimensionEffective(Dimension.NETHER).replacementWeights().toString());
    assertEquals("{end_stone=10}",
        config.dimensionEffective(Dimension.THE_END).replacementWeights().toString());
    assertFalse(config.dimensionsMissing(), "三个维度段都存在 → 不算缺 dimensions 段");
  }

  /** world-overrides 最高优先级：显式覆盖 mode 时压过该世界所属维度的 mode。 */
  @Test
  void worldOverridesBeatDimensionValues() {
    AntiXrayConfig config = config("""
        dimensions:
          normal:
            mode: enclosed
            hide-blocks: [diamond_ore]
          nether:
            mode: enclosed
            hide-blocks: [ancient_debris]
        world-overrides:
          world_custom:
            mode: all
            hide-blocks: [nether_quartz_ore]
        """);

    assertEquals(0, config.matchOverride("world_custom"), "world_custom 命中覆盖段");
    // 覆盖段压过维度值
    assertEquals(ObfuscationMode.ALL, config.overrideEffective(0, Dimension.NORMAL).mode());
    assertEquals(ObfuscationMode.ALL, config.overrideEffective(0, Dimension.NETHER).mode());
    assertEquals(List.of("nether_quartz_ore"),
        config.overrideEffective(0, Dimension.NORMAL).hideBlocks());
    // 未覆盖的键回落该世界所属维度（这里两个维度都是 enclosed）
    assertEquals(ObfuscationMode.ENCLOSED, config.dimensionEffective(Dimension.NORMAL).mode());
    assertEquals(-1, config.matchOverride("world_plain"), "未列出的世界不命中覆盖段");
  }

  /** 缺 dimensions 段：用内置默认运行，并只 WARN 一次（进程级闸门）。 */
  @Test
  void missingDimensionsSectionUsesBuiltinDefaultsAndWarnsOnce() {
    AntiXrayConfig config = config("enabled: true\nworlds: [world_nether]\n"
        + "obfuscation:\n  hide-blocks: [diamond_ore]\n");

    assertTrue(config.dimensionsMissing(), "旧版结构（缺 dimensions 段）必须被识别");
    // 内置默认仍然生效（绝不让保护静默失效）
    assertEquals(22, config.dimensionEffective(Dimension.NORMAL).hideBlocks().size(),
        "缺段时必须回落内置默认的主世界清单（而非旧配置里的 diamond_ore）");
    assertEquals(4, config.dimensionEffective(Dimension.NETHER).hideBlocks().size());
    assertTrue(config.dimensionEffective(Dimension.NETHER).hideBlocks().contains("ancient_debris"));

    List<String> warnings = new ArrayList<>();
    Logger logger = new Logger("test", null) {
      @Override
      public void warning(String message) {
        warnings.add(message);
      }
    };
    AtomicBoolean once = new AtomicBoolean();
    config.warnIfDimensionsMissing(logger, once);
    config.warnIfDimensionsMissing(logger, once);
    assertEquals(1, warnings.size(), "缺 dimensions 段只提示一次："
        + warnings);
    assertTrue(warnings.get(0).contains("dimensions"), "提示必须点明缺少 dimensions 段：" + warnings);
    assertTrue(warnings.get(0).contains("内置默认"), "提示必须说明已使用内置默认运行：" + warnings);
  }

  /** 存在维度段时不产生 WARN。 */
  @Test
  void presentDimensionsSectionProducesNoWarning() {
    AntiXrayConfig config = config("dimensions:\n  normal:\n    mode: all\n");
    assertFalse(config.dimensionsMissing());
    List<String> warnings = new ArrayList<>();
    Logger logger = new Logger("test", null) {
      @Override
      public void warning(String message) {
        warnings.add(message);
      }
    };
    config.warnIfDimensionsMissing(logger, new AtomicBoolean());
    assertTrue(warnings.isEmpty(), "存在 dimensions 段时不得提示：" + warnings);
  }

  /** 维度识别：NORMAL/NETHER/THE_END 各自命中，CUSTOM（及 null）归入主世界。 */
  @Test
  void dimensionOfMapsEnvironment() {
    assertEquals(Dimension.NORMAL, Dimension.of(World.Environment.NORMAL));
    assertEquals(Dimension.NETHER, Dimension.of(World.Environment.NETHER));
    assertEquals(Dimension.THE_END, Dimension.of(World.Environment.THE_END));
    assertEquals(Dimension.NORMAL, Dimension.of(World.Environment.CUSTOM),
        "CUSTOM 归入 normal");
    assertEquals(Dimension.NORMAL, Dimension.of(null), "null 环境按 normal 处理（最安全的默认）");
  }

  /** configHash 随「仅其它维度」的配置变化而变化（各维度生效值都纳入指纹）。 */
  @Test
  void configHashChangesWhenOnlyOtherDimensionChanges() {
    String base = """
        dimensions:
          normal:
            hide-blocks: [diamond_ore]
          nether:
            hide-blocks: [ancient_debris]
        """;
    AntiXrayConfig plain = config(base);
    AntiXrayConfig endChanged = config("""
        dimensions:
          normal:
            hide-blocks: [diamond_ore]
          nether:
            hide-blocks: [ancient_debris]
          the_end:
            enabled: true
            hide-blocks: [bedrock]
        """);
    AntiXrayConfig netherWeightChanged = config("""
        dimensions:
          normal:
            hide-blocks: [diamond_ore]
          nether:
            hide-blocks: [ancient_debris]
            replacement-weights: {netherrack: 5}
        """);

    assertNotEquals(plain.configHash(), endChanged.configHash(),
        "新增末地维度段必须改变指纹（维度选择参与指纹）");
    assertNotEquals(plain.configHash(), netherWeightChanged.configHash(),
        "仅改变地狱维度权重也必须改变指纹");
  }
}