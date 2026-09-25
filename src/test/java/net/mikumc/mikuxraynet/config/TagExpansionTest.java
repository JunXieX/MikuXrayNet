package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * tag(...) 语法（P2-6c）：启动期把 {@code tag(名称)} 展开为内置静态映射的成员；
 * 普通名称透传；识别不了的 tag 名忽略并记录（启动时 WARN），绝不猜测成员。
 */
class TagExpansionTest {

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return configuration;
  }

  /** 已知 tag 展开为全部成员；普通名称原样保留；两者可混用。 */
  @Test
  void knownTagsExpandAndPlainNamesPassThrough() {
    Set<String> unknown = new LinkedHashSet<>();

    assertEquals(List.of("diamond_ore", "deepslate_diamond_ore"),
        AntiXrayConfig.expandTags(List.of("tag(diamond_ores)"), unknown),
        "tag(diamond_ores) 必须展开为普通矿与深层变体");
    assertEquals(List.of("stone", "diamond_ore", "deepslate_diamond_ore"),
        AntiXrayConfig.expandTags(List.of("stone", "tag(diamond_ores)"), unknown),
        "普通名称与 tag(...) 必须可以混用");
    assertTrue(unknown.isEmpty(), "已知 tag 不得记入无法识别清单");

    List<String> same = List.of("stone", "chest");
    assertSame(same, AntiXrayConfig.expandTags(same, unknown),
        "不含 tag 语法时必须原样返回（启动期零开销路径）");

    // 大小写不敏感、支持 minecraft: 风格归一化之外的空白
    assertEquals(List.of("iron_ore", "deepslate_iron_ore"),
        AntiXrayConfig.expandTags(List.of("  TAG(IRON_ORES) "), unknown),
        "tag 语法必须大小写不敏感、忽略空白");
  }

  /** 未知 tag：条目被忽略（不猜成员）、名字记入 unknownTags 供启动 WARN。 */
  @Test
  void unknownTagsAreIgnoredAndRecorded() {
    Set<String> unknown = new LinkedHashSet<>();
    List<String> expanded = AntiXrayConfig.expandTags(
        List.of("chest", "tag(not_a_real_tag)", "tag(also_missing)"), unknown);

    assertEquals(List.of("chest"), expanded, "未知 tag 的条目必须被忽略（其余条目保留）");
    assertEquals(Set.of("not_a_real_tag", "also_missing"), unknown,
        "未知 tag 名必须逐个记录（调用方启动时 WARN）");

    assertEquals(List.of("tag()"), AntiXrayConfig.expandTags(List.of("tag()"), unknown),
        "空 tag 名不是有效语法，原样透传（由名称解析阶段统一报「无法识别」）");
    assertFalse(unknown.contains(""), "无效语法不记入未知 tag 清单");
  }

  /** 集成：hide-blocks 与 replacement-weights 都支持 tag(...)，未知 tag 落到 unresolvedTags。 */
  @Test
  void configParsesTagSyntaxInBothSections() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml(
        "dimensions:\n"
        + "  normal:\n"
        + "    hide-blocks:\n"
        + "      - tag(iron_ores)\n"
        + "      - chest\n"
        + "    replacement-weights:\n"
        + "      tag(base_stone_nether): 5\n"));

    AntiXrayConfig.EffectiveObfuscation normal =
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL);
    assertEquals(List.of("iron_ore", "deepslate_iron_ore", "chest"), normal.hideBlocks(),
        "hide-blocks 的 tag(...) 必须在解析期展开：" + normal.hideBlocks());
    assertEquals(Set.of("netherrack", "basalt", "blackstone"), normal.replacementWeights().keySet(),
        "replacement-weights 的 tag(...) 必须在解析期展开为同权重的全部成员："
            + normal.replacementWeights());
    assertTrue(config.unresolvedTags().isEmpty(), "已知 tag 不得进入未识别清单");
  }

  /** 集成：无法识别的 tag 记入 unresolvedTags（MikuConfig 读取后 WARN）。 */
  @Test
  void configSurfacesUnresolvedTags() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml(
        "dimensions:\n"
        + "  normal:\n"
        + "    hide-blocks:\n"
        + "      - tag(what_ores)\n"
        + "      - chest\n"));
    assertEquals(Set.of("what_ores"), config.unresolvedTags(),
        "未识别 tag 必须暴露给启动日志 WARN：" + config.unresolvedTags());
    assertEquals(List.of("chest"),
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).hideBlocks(),
        "未识别 tag 的条目不得进入隐藏清单");
  }

  /** tag 展开结果影响配置指纹（改变改写结果，缓存必须整体失效）。 */
  @Test
  void configHashReflectsTagExpansion() {
    AntiXrayConfig withTag = AntiXrayConfig.from(yaml(
        "dimensions:\n  normal:\n    hide-blocks:\n      - tag(diamond_ores)\n"));
    AntiXrayConfig withPlain = AntiXrayConfig.from(yaml(
        "dimensions:\n  normal:\n    hide-blocks:\n      - diamond_ore\n      - deepslate_diamond_ore\n"));
    AntiXrayConfig withOther = AntiXrayConfig.from(yaml(
        "dimensions:\n  normal:\n    hide-blocks:\n      - iron_ore\n"));

    assertEquals(withTag.configHash(), withPlain.configHash(),
        "tag(...) 与其展开结果必须是同一指纹（语义等价）");
    assertFalse(withTag.configHash() == withOther.configHash(),
        "不同目标集合必须有不同指纹");
  }
}
