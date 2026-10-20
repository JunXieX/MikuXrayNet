package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 配置的「宽松解析」回归：权重（正整数）与布尔开关在写法不被类型化 getter 理解时，必须按宽松值生效
 * 并<b>留痕</b>，绝不静默改配置、更不允许把 {@code "false"} 静默反转成 {@code true}。
 *
 * <p><b>两条真实故障形态</b>：
 * <ol>
 *   <li>{@code replacement-weights: {stone: "10"}}：YAML 里是字符串，{@code getInt} 返回 0，
 *       该伪装条目被当「权重非正」丢弃；整表都被引号化时会悄悄回落内置默认表，控制台无提示。</li>
 *   <li>{@code enabled: "false"}：{@code getBoolean} 的语义是「不是 Boolean 就取默认值」，默认值为
 *       {@code true} 的开关因此被静默反转成「仍然开启」。</li>
 * </ol>
 *
 * <p>本类同时钉死「合法写法语义不变」：真布尔值、无引号整数、缺键回落都不得产生任何 {@code typeAdjustments}
 * 明细（明细只为「写法不被理解」而存在，否则每次开服都会刷一条误导性 WARN）。
 */
class LenientConfigParsingTest {

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return configuration;
  }

  private static String joined(List<String> details) {
    return String.join("、", details);
  }

  // ---------------------------------------------------------------- 权重（D1）

  @Test
  void quotedWeightsAreAcceptedInsteadOfDropped() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            hide-blocks: [diamond_ore]
            replacement-weights:
              stone: "10"
              deepslate: "8"
        """));

    Map<String, Integer> weights =
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).replacementWeights();
    assertEquals(10, weights.get("stone"), "带引号的权重必须按 10 生效（旧实现当成 0 丢弃）");
    assertEquals(8, weights.get("deepslate"), "带引号权重必须按 8 生效");
    assertEquals(2, weights.size(), "两个条目都要保留：" + weights);
    assertTrue(config.typeAdjustments().isEmpty(),
        "数字字符串是宽松解析接受的写法，不应产生「写法不被理解」明细：" + config.typeAdjustments());
  }

  @Test
  void fractionalWeightsAreRoundedAndReported() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            replacement-weights:
              stone: 10.5
              dirt: 2.0
        """));

    Map<String, Integer> weights =
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).replacementWeights();
    assertEquals(11, weights.get("stone"), "浮点权重四舍五入（10.5 → 11，而不是静默截断成 10）");
    assertEquals(2, weights.get("dirt"), "整值浮点（2.0）等价于 2，不需要四舍五入");
    assertTrue(joined(config.typeAdjustments()).contains("replacement-weights.stone=10.5"),
        "小数取整必须留痕：" + config.typeAdjustments());
    assertFalse(joined(config.typeAdjustments()).contains("dirt"),
        "整值浮点不改变取值，不应留痕：" + config.typeAdjustments());
  }

  @Test
  void unparsableWeightIsSkippedAndReported() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            replacement-weights:
              stone: 10
              deepslate: lots
        """));

    Map<String, Integer> weights =
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).replacementWeights();
    assertEquals(10, weights.get("stone"), "合法条目照常生效");
    assertFalse(weights.containsKey("deepslate"), "无法解析的权重条目被跳过（不生效）");
    String details = joined(config.typeAdjustments());
    assertTrue(details.contains("replacement-weights.deepslate="), "必须点名被跳过的键：" + details);
    assertTrue(details.contains("lots"), "必须回显原始写法：" + details);
  }

  @Test
  void allInvalidWeightsFallBackToBuiltinTableAndAreReported() {
    AntiXrayConfig builtin = AntiXrayConfig.from(yaml("enabled: true\n"));
    AntiXrayConfig broken = AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            replacement-weights:
              stone: lots
        """));

    assertEquals(builtin.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).replacementWeights(),
        broken.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).replacementWeights(),
        "整表都无法解析时回落内置默认权重表（既有行为不变）");
    assertFalse(broken.typeAdjustments().isEmpty(),
        "但绝不允许静默回落：必须留下明细供加载路径一次性 WARN");
  }

  @Test
  void bandWeightsUseTheSameLenientParsing() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            replacement-bands:
              - min-y: -64
                max-y: -1
                weights:
                  deepslate: "10"
                  tuff: 4.4
        """));

    List<AntiXrayConfig.ReplacementBand> bands =
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).replacementBands();
    assertEquals(1, bands.size(), "该段必须被保留：" + bands);
    assertEquals(10, bands.get(0).weights().get("deepslate"), "分区表里的带引号权重同样接受");
    assertEquals(4, bands.get(0).weights().get("tuff"), "4.4 四舍五入为 4");
    assertTrue(joined(config.typeAdjustments()).contains("[0].weights.tuff"),
        "分区表内的取整同样必须留痕：" + config.typeAdjustments());
  }

  @Test
  void worldOverrideWeightsAreLenientToo() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        world-overrides:
          arena:
            replacement-weights:
              stone: "10"
        """));

    assertEquals(0, config.matchOverride("arena"), "覆盖段必须匹配到该世界");
    assertEquals(10, config.overrideEffective(0, AntiXrayConfig.Dimension.NORMAL)
        .replacementWeights().get("stone"), "world-overrides 的权重同样走宽松解析");
    assertTrue(config.typeAdjustments().isEmpty(), config.typeAdjustments().toString());
  }

  // ---------------------------------------------------------------- 布尔开关（D2）

  @Test
  void quotedFalseSwitchesAreHonouredForAntiXray() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        enabled: "false"
        neighbors:
          enabled: "false"
        proximity:
          enabled: "false"
          frustum:
            enabled: "false"
          raycast:
            enabled: "false"
        """));

    assertFalse(config.enabled(), "enabled: \"false\" 必须真的关闭");
    assertFalse(config.neighbors().enabled(), "neighbors.enabled: \"false\" 必须真的关闭");
    assertFalse(config.proximity().enabled(), "proximity.enabled: \"false\" 必须真的关闭");
    assertFalse(config.proximity().frustumEnabled(), "frustum.enabled: \"false\" 必须真的关闭");
    assertFalse(config.proximity().raycastEnabled(), "raycast.enabled: \"false\" 必须真的关闭");
    assertTrue(config.typeAdjustments().isEmpty(),
        "字符串布尔值是宽松解析接受的写法，不算「写法不被理解」：" + config.typeAdjustments());
  }

  @Test
  void dimensionAndOverrideBooleansAreLenient() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        dimensions:
          the_end:
            enabled: "true"
          normal:
            use-block-below: "true"
        world-overrides:
          arena:
            enabled: "false"
          hub:
            use-block-below: "true"
        """));

    assertTrue(config.dimensionEnabled(AntiXrayConfig.Dimension.THE_END),
        "末地 enabled: \"true\" 必须生效（默认是 false，被静默忽略会很难察觉）");
    assertTrue(config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).useBlockBelow(),
        "use-block-below: \"true\" 必须生效（getBoolean 会返回默认 false）");
    // 两个覆盖段分开写：enabled=false 的段会被折叠成空视图，use-block-below 在那种段里观察不到。
    assertEquals(0, config.matchOverride("arena"), "覆盖段必须匹配到 arena");
    assertTrue(config.overrideEffective(0, AntiXrayConfig.Dimension.NORMAL).hideBlocks().isEmpty(),
        "覆盖段 enabled: \"false\" 必须真的关闭该世界（空视图 = 不伪装）");
    assertEquals(1, config.matchOverride("hub"), "第二个覆盖段必须匹配到 hub");
    assertTrue(config.overrideEffective(1, AntiXrayConfig.Dimension.NORMAL).useBlockBelow(),
        "覆盖段的 use-block-below 同样接受字符串布尔值");
  }

  @Test
  void realBooleanValuesKeepWorkingWithoutAdjustments() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        enabled: true
        proximity:
          enabled: false
        """));

    assertTrue(config.enabled(), "真实布尔值 true 语义不变");
    assertFalse(config.proximity().enabled(), "真实布尔值 false 语义不变");
    assertTrue(config.typeAdjustments().isEmpty(),
        "合法写法不得产生明细（否则每次开服都会刷一条误导性 WARN）：" + config.typeAdjustments());
  }

  @Test
  void nonBooleanValueFallsBackToDefaultAndIsReported() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        proximity:
          enabled: nope
        """));

    assertTrue(config.proximity().enabled(), "无法识别的布尔值回落默认 true（与既有默认一致）");
    assertTrue(joined(config.typeAdjustments()).contains("proximity.enabled="),
        "必须留痕并回显原始写法：" + config.typeAdjustments());
  }

  @Test
  void bandwidthQuotedSwitchesAreHonoured() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        enabled: "false"
        afk:
          enabled: "false"
          drop-particles: "false"
        block-changes:
          merge: "false"
        entity-culling:
          enabled: "false"
          raycast: "false"
        """));

    assertFalse(config.enabled(), "bandwidth.enabled: \"false\" 必须真的关闭");
    assertFalse(config.afk().enabled(), "afk.enabled: \"false\" 必须真的关闭");
    assertFalse(config.afk().dropParticles(), "drop-particles: \"false\" 必须真的关闭");
    assertFalse(config.blockChanges().merge(), "block-changes.merge: \"false\" 必须真的关闭");
    assertFalse(config.entityCulling().enabled(), "entity-culling.enabled: \"false\" 必须真的关闭");
    assertFalse(config.entityCulling().raycast(), "entity-culling.raycast: \"false\" 必须真的关闭");
    assertTrue(config.typeAdjustments().isEmpty(),
        "字符串布尔值是宽松解析接受的写法：" + config.typeAdjustments());
  }

  @Test
  void bandwidthInvalidSwitchKeepsDefaultAndIsReported() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          enabled: nope
        palette:
          width-budget: nope
        """));

    assertTrue(config.entityCulling().enabled(), "无法识别时回落默认 true（与既有默认一致）");
    assertTrue(config.palette().widthBudget(), "无法识别时回落默认 true（与既有默认一致）");
    String details = joined(config.typeAdjustments());
    assertTrue(details.contains("entity-culling.enabled=nope"), details);
    assertTrue(details.contains("palette.width-budget=nope"), details);
  }

  @Test
  void bandwidthRealBooleansKeepWorkingWithoutAdjustments() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        enabled: true
        afk:
          enabled: false
        """));

    assertTrue(config.enabled());
    assertFalse(config.afk().enabled());
    assertTrue(config.typeAdjustments().isEmpty(), config.typeAdjustments().toString());
  }

  // ---------------------------------------------------------------- 枚举类字符串键（第 10 轮 P3-3）

  /**
   * {@code neighbors.missing-policy}：合法取值（含大小写/首尾空白）行为逐字不变；写错必须留痕。
   *
   * <p>旧实现只做一次相等比较，{@code expse} 会被静默当成 hide——而 hide 是最保守的一侧，管理员会以为
   * 自己配的 expose 已生效；这正是本项目禁止的「写了却没生效且无提示」。
   */
  @Test
  void missingPolicyIsLenientAndReported() {
    AntiXrayConfig upper = AntiXrayConfig.from(yaml("neighbors:\n  missing-policy: EXPOSE\n"));
    assertEquals(AntiXrayConfig.MissingPolicy.EXPOSE, upper.neighbors().missingPolicy(),
        "大写 EXPOSE 必须生效（宽松解析容忍大小写）");
    assertTrue(upper.typeAdjustments().isEmpty(), upper.typeAdjustments().toString());

    AntiXrayConfig padded =
        AntiXrayConfig.from(yaml("neighbors:\n  missing-policy: '  expose  '\n"));
    assertEquals(AntiXrayConfig.MissingPolicy.EXPOSE, padded.neighbors().missingPolicy(),
        "首尾空白必须被容忍");

    AntiXrayConfig explicitHide =
        AntiXrayConfig.from(yaml("neighbors:\n  missing-policy: hide\n"));
    assertEquals(AntiXrayConfig.MissingPolicy.HIDE, explicitHide.neighbors().missingPolicy());
    assertTrue(explicitHide.typeAdjustments().isEmpty(), explicitHide.typeAdjustments().toString());

    AntiXrayConfig absent = AntiXrayConfig.from(yaml("enabled: true\n"));
    assertEquals(AntiXrayConfig.MissingPolicy.HIDE, absent.neighbors().missingPolicy(),
        "缺键仍回落默认 hide（既有行为不变）");
    assertTrue(absent.typeAdjustments().isEmpty(), absent.typeAdjustments().toString());

    AntiXrayConfig typo = AntiXrayConfig.from(yaml("neighbors:\n  missing-policy: expse\n"));
    assertEquals(AntiXrayConfig.MissingPolicy.HIDE, typo.neighbors().missingPolicy(),
        "非法值回落最安全的 HIDE（取值映射不变）");
    assertTrue(joined(typo.typeAdjustments()).contains("neighbors.missing-policy="),
        "非法值必须留痕：" + typo.typeAdjustments());

    AntiXrayConfig wrongType = AntiXrayConfig.from(yaml("neighbors:\n  missing-policy: 1\n"));
    assertEquals(AntiXrayConfig.MissingPolicy.HIDE, wrongType.neighbors().missingPolicy(),
        "非字符串同样回落 HIDE");
    assertTrue(joined(wrongType.typeAdjustments()).contains("neighbors.missing-policy="),
        "类型不符同样留痕：" + wrongType.typeAdjustments());
  }

  /**
   * {@code mode}（维度段与 world-overrides）：合法取值行为逐字不变；写错必须留痕。
   *
   * <p>旧实现只在「恰好等于 enclosed」时取 ENCLOSED，{@code enclose} 会被静默当成 ALL——ALL 会把矿洞壁上
   * 本可看见的矿也伪装掉，观感与管理员预期相反却无任何提示。
   */
  @Test
  void obfuscationModeIsLenientAndReported() {
    AntiXrayConfig upper =
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: ENCLOSED\n"));
    assertEquals(AntiXrayConfig.ObfuscationMode.ENCLOSED,
        upper.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode(), "大写 ENCLOSED 必须生效");
    assertTrue(upper.typeAdjustments().isEmpty(), upper.typeAdjustments().toString());

    AntiXrayConfig padded =
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: '  Enclosed  '\n"));
    assertEquals(AntiXrayConfig.ObfuscationMode.ENCLOSED,
        padded.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode(), "首尾空白必须被容忍");

    AntiXrayConfig explicitAll =
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: all\n"));
    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        explicitAll.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode());
    assertTrue(explicitAll.typeAdjustments().isEmpty(), explicitAll.typeAdjustments().toString());

    AntiXrayConfig typo =
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: enclose\n"));
    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        typo.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode(),
        "非法值回落最安全的 ALL（取值映射不变）");
    assertTrue(joined(typo.typeAdjustments()).contains("dimensions.normal.mode="),
        "非法值必须点名具体键：" + typo.typeAdjustments());

    // world-overrides 的 mode 同口径：合法值（含大小写差异）生效且不留痕；非法值回落 ALL 并留痕。
    AntiXrayConfig override = AntiXrayConfig.from(yaml("""
        world-overrides:
          arena:
            mode: Enclosed
          hub:
            mode: bogus
        """));
    // 用 matchOverride 取下标，不依赖 YAML 里的声明顺序（顺序可变，语义不变）
    int arenaIndex = override.matchOverride("arena");
    int hubIndex = override.matchOverride("hub");
    assertTrue(arenaIndex >= 0 && hubIndex >= 0, "两个覆盖段都应匹配到（arena=" + arenaIndex
        + ", hub=" + hubIndex + "）");
    assertEquals(AntiXrayConfig.ObfuscationMode.ENCLOSED,
        override.overrideEffective(arenaIndex, AntiXrayConfig.Dimension.NORMAL).mode(),
        "覆盖段的 mode 同样接受大小写差异");
    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        override.overrideEffective(hubIndex, AntiXrayConfig.Dimension.NORMAL).mode(),
        "覆盖段的非法 mode 回落 ALL");
    String details = joined(override.typeAdjustments());
    assertFalse(details.contains("world-overrides.arena.mode="), "合法值不得留痕：" + details);
    assertTrue(details.contains("world-overrides.hub.mode="), details);
  }
}
