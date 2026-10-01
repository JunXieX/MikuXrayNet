package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode(),
        "默认模式必须是 all（透视不得直接看到裸露在矿洞中的矿）");
    assertEquals(64.0D, config.proximity().distance(), 1.0E-9D,
        "显形距离默认 64 格（48 仍偏近；显形只在视线通畅且方块有暴露面时还原，放大距离不会隔墙泄露；"
            + "扫描半径 ceil(64/16)=4 即 9×9 区块）");
    assertEquals(256, config.proximity().maxRevealsPerTick(),
        "单次显形额度默认 256（原 128；配合 64 格距离，候选更多，额度需相应放大）");
    assertEquals(4, config.proximity().intervalTicks(), "巡检周期收紧为 4 tick（0.2 秒一次，显形更及时）");
    assertTrue(config.proximity().raycastEnabled(), "可见性判定默认开启（隔着墙不显形）");
    assertEquals(AntiXrayConfig.FRUSTUM_MIN_DISTANCE_FLOOR, config.proximity().frustumMinDistance(),
        1.0E-9D, "min-distance 默认为安全下限 16 格（原 4 格盖不住眼前方块：门的顶部在 3 格距离就有 40°+ 仰角）");
    assertEquals(524288, config.proximity().maxPositionsPerPlayer(),
        "单玩家坐标上限默认 524288（262144 在真机仍被顶到、淘汰 1400 个坐标）");
    assertEquals(4194304, config.proximity().maxPositions(),
        "全服坐标上限默认 4194304（原 2097152，约 8 个满配玩家，避免多玩家同时在线立刻触顶）");
    assertEquals(300, config.proximity().expireSeconds(), "显形索引过期默认 300 秒（原为 120）");
  }

  /**
   * 配置安全下限：旧配置（fov=80 / min-distance=4 / disk-cache.expire-seconds=1800）也必须被抬升到下限
   * 并留下 WARN 明细。
   *
   * <p>真机回归两条：① 视锥配得比客户端可视范围窄 → 玩家看到的方块一直保持伪装（点一下才变回来）；
   * ② 磁盘缓存过期时间从写入时刻算起、配得比两次启动的间隔还短 → 条目在重启后必然过期，命中率恒为 0
   * （实机 70 分钟后重启：命中 0／未命中 2096，磁盘上 1951 条全是过期条目）。
   * 因此下限不是「建议值」而是硬约束，且必须能被加载路径显式提示（绝不静默改用户配置）。
   */
  @Test
  void optionsBelowSafetyFloorAreRaisedAndReported() {
    AntiXrayConfig raised = AntiXrayConfig.from(yaml("""
        proximity:
          frustum:
            fov: 80.0
            min-distance: 4.0
        disk-cache:
          expire-seconds: 1800
        """));

    assertEquals(AntiXrayConfig.FRUSTUM_FOV_FLOOR, raised.proximity().frustumFov(), 1.0E-9D,
        "旧默认 80° 必须被抬升到下限 110°");
    assertEquals(AntiXrayConfig.FRUSTUM_MIN_DISTANCE_FLOOR, raised.proximity().frustumMinDistance(),
        1.0E-9D, "旧默认 4 格必须被抬升到下限 16 格");
    assertEquals(AntiXrayConfig.DISK_CACHE_EXPIRE_FLOOR_SECONDS, raised.diskCache().expireSeconds(),
        "旧默认 1800 秒必须被抬升到下限 1 天（否则条目活不过一次重启，命中率恒为 0）");
    assertFalse(raised.floorAdjustments().isEmpty(), "抬升必须留下明细，供加载时一次性 WARN");
    assertTrue(joinedFloors(raised).contains("proximity.frustum.fov=80.0"),
        "明细必须点名被抬升的键：" + raised.floorAdjustments());
    assertTrue(joinedFloors(raised).contains("proximity.frustum.min-distance=4.0"),
        "明细必须列出全部被抬升的键：" + raised.floorAdjustments());
    assertTrue(joinedFloors(raised).contains("disk-cache.expire-seconds=1800"),
        "磁盘缓存过期时间被抬升也必须留痕：" + raised.floorAdjustments());

    // 高于下限的值原样生效（下限只挡更短的配置），且不产生明细
    AntiXrayConfig kept = AntiXrayConfig.from(yaml("""
        proximity:
          frustum:
            fov: 120.0
            min-distance: 24.0
        disk-cache:
          expire-seconds: 1209600
        """));
    assertEquals(120.0D, kept.proximity().frustumFov(), 1.0E-9D, "高于下限的 fov 原样生效");
    assertEquals(24.0D, kept.proximity().frustumMinDistance(), 1.0E-9D, "高于下限的 min-distance 原样生效");
    assertEquals(1209600, kept.diskCache().expireSeconds(), "高于下限的过期时间原样生效");
    assertTrue(kept.floorAdjustments().isEmpty(), "未抬升时不得产生明细（否则会误导性 WARN）");

    // 非法值：fov 的 0/-1/大于 360 一律回落默认（= 下限）；min-distance 负数按 0 处理后同样抬升到下限
    AntiXrayConfig invalid = AntiXrayConfig.from(yaml("""
        proximity:
          frustum:
            fov: 0.0
            min-distance: -5.0
        """));
    assertEquals(AntiXrayConfig.FRUSTUM_FOV_FLOOR, invalid.proximity().frustumFov(), 1.0E-9D,
        "fov 非法值回落默认（即下限）");
    assertEquals(AntiXrayConfig.FRUSTUM_MIN_DISTANCE_FLOOR,
        invalid.proximity().frustumMinDistance(), 1.0E-9D, "min-distance 负数按下限处理");
  }

  /** 把「被抬升的键明细」拼成一行，便于用 contains 断言具体键名。 */
  private static String joinedFloors(AntiXrayConfig config) {
    return String.join("、", config.floorAdjustments());
  }

  /**
   * 非法 fov（&gt;360° / 非正）不再静默回落：必须与「低于下限」一样进入一次性 WARN 明细，
   * 否则管理员写了无效角度会以为它已生效（与其它钳制项口径一致）。
   */
  @Test
  void invalidFrustumFovIsReportedInFloorDetails() {
    AntiXrayConfig tooWide = AntiXrayConfig.from(yaml("proximity:\n  frustum:\n    fov: 400.0\n"));
    assertEquals(AntiXrayConfig.FRUSTUM_FOV_FLOOR, tooWide.proximity().frustumFov(), 1.0E-9D,
        "fov>360 属非法值，回落默认（即下限）");
    assertTrue(joinedFloors(tooWide).contains("proximity.frustum.fov=400.0"),
        "fov 非法值必须纳入一次性 WARN 明细（不再静默回落）：" + tooWide.floorAdjustments());

    AntiXrayConfig nonPositive = AntiXrayConfig.from(yaml("proximity:\n  frustum:\n    fov: -10.0\n"));
    assertEquals(AntiXrayConfig.FRUSTUM_FOV_FLOOR, nonPositive.proximity().frustumFov(), 1.0E-9D);
    assertTrue(joinedFloors(nonPositive).contains("proximity.frustum.fov=-10.0"),
        "负角度同样必须留痕：" + nonPositive.floorAdjustments());
  }

  /**
   * 任务3：{@code proximity.frustum.min-distance} 的 NaN、以及 {@code proximity.distance} 的负值
   * 都不得静默回落/归零——必须与 frustumFov 口径一致，进入一次性 WARN 明细。
   */
  @Test
  void nanMinDistanceAndNegativeProximityDistanceAreReported() {
    AntiXrayConfig nan = AntiXrayConfig.from(yaml("proximity:\n  frustum:\n    min-distance: .nan\n"));
    assertEquals(AntiXrayConfig.FRUSTUM_MIN_DISTANCE_FLOOR, nan.proximity().frustumMinDistance(),
        1.0E-9D, "NaN 的 min-distance 必须回落默认（= 下限）");
    assertTrue(joinedFloors(nan).contains("proximity.frustum.min-distance=NaN"),
        "NaN 的 min-distance 必须纳入一次性 WARN 明细（不再静默）：" + nan.floorAdjustments());

    AntiXrayConfig negative = AntiXrayConfig.from(yaml("proximity:\n  distance: -5.0\n"));
    assertEquals(0.0D, negative.proximity().distance(), 1.0E-9D, "负值的显形距离必须按 0 处理");
    assertTrue(joinedFloors(negative).contains("proximity.distance=-5.0"),
        "负值的 proximity.distance 必须留痕（不再静默归 0）：" + negative.floorAdjustments());
  }

  /**
   * 任务3：bandwidth 侧浮点钳制的<b>有限负值</b>不得再静默归 0（旧实现 {@code Math.max(0, v)} 无记录）。
   */
  @Test
  void negativeFiniteFloatClampInBandwidthIsReported() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          force-visible-distance: -3.0
        """));
    assertEquals(0.0D, config.entityCulling().forceVisibleDistance(), 1.0E-9D, "负值必须钳到 0");
    assertTrue(String.join("、", config.clampAdjustments())
            .contains("entity-culling.force-visible-distance=-3.0"),
        "有限负值必须进入钳制明细（不再静默归 0）：" + config.clampAdjustments());
  }

  /**
   * 配置指纹必须是<b>跨进程稳定</b>的固定值（真机回归：磁盘缓存重启后命中率恒为 0 的根因）。
   *
   * <p>指纹曾被「枚举的 identity hash」污染（{@code EffectiveObfuscation.mode} / {@code missingPolicy}），
   * 而 identity hash 每个 JVM 进程随机 → 同一份配置两次启动算出不同指纹 → 上一进程写下的磁盘条目全部
   * 判为「配置已变」而被丢弃。真机实测：同一配置两次启动分别为 335527235 与 -1449763053。
   *
   * <p>因此这里把指纹<b>钉成常量</b>：它在任何 JVM 上都应相同（本测试在两次独立进程的测试运行中都要通过）。
   * 若将来故意调整了指纹的组成（例如新增一个影响改写结果的配置项），改动者是<b>有意</b>的，
   * 请一并更新下面的期望值并在提交信息里说明「旧磁盘缓存会一次性失效重建」。
   */
  @Test
  void configFingerprintIsStableAcrossProcesses() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            hide-blocks: [diamond_ore, emerald_ore]
            mode: all
        """));

    // 2026-09 更新①：新增 disk-cache.zstd-sha256 参与指纹（按任务要求）。
    // 2026-09 更新②：新增 obfuscation.remove-block-entities 参与指纹（它直接改变写进缓存的区块负载字节）。
    // 两次改动都会使期望值变化、现存磁盘缓存一次性失效重建（属正常）。
    assertEquals(-1049098715, config.configHash(),
        "配置指纹必须是跨进程稳定的固定值（不得混入枚举 identity hash / 随机值）");
    // 同一份内容重复解析必须得到同一个值（同一进程内的自洽性）
    assertEquals(config.configHash(), AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            hide-blocks: [diamond_ore, emerald_ore]
            mode: all
        """)).configHash(), "同一份配置重复解析必须得到同一个指纹");
  }

  /**
   * 权重书写顺序必须进入指纹（回归）：{@code ObfuscationProcessor} 按 Map 的<b>迭代序</b>累加权重
   * 决定伪装方块分布，因此仅调换 yml 里 {@code replacement-weights} 的书写顺序就会改变结果。
   * 指纹若对其无序敏感（旧实现直接用 {@code Map.hashCode}），旧磁盘缓存会被错误复用
   * （后果仅观感、不泄漏真矿，但属指纹失准）。
   */
  @Test
  void configHashIsSensitiveToWeightOrder() {
    AntiXrayConfig forward = AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            hide-blocks: [diamond_ore]
            replacement-weights: {stone: 10, deepslate: 4}
        """));
    AntiXrayConfig reversed = AntiXrayConfig.from(yaml("""
        dimensions:
          normal:
            hide-blocks: [diamond_ore]
            replacement-weights: {deepslate: 4, stone: 10}
        """));
    assertNotEquals(forward.configHash(), reversed.configHash(),
        "同内容但权重书写顺序不同 → 指纹必须不同（顺序影响伪装分布）");
  }

  /**
   * 默认隐藏清单按维度独立：主世界 22 种、地狱 4 种（<b>不含 nether_quartz_ore</b>）、末地 1 种。
   *
   * <p>回归用户核心诉求：地狱分布极广、价值极低的石英矿默认不隐藏（否则玩家在地狱到处挖到假石头）。
   */
  @Test
  void defaultHideBlocksArePerDimension() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("enabled: true\n"));

    List<String> normal =
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).hideBlocks();
    List<String> nether =
        config.dimensionEffective(AntiXrayConfig.Dimension.NETHER).hideBlocks();
    List<String> end =
        config.dimensionEffective(AntiXrayConfig.Dimension.THE_END).hideBlocks();

    assertEquals(22, normal.size(),
        "主世界默认隐藏清单 22 种（16 矿 + 3 粗金属块 + chest + spawner + mossy_cobblestone）：" + normal);
    assertEquals(4, nether.size(), "地狱默认隐藏清单 4 种（残骸 + 金矿 + chest + 刷怪笼）：" + nether);
    assertEquals(1, end.size(), "末地默认隐藏清单 1 种（chest）：" + end);

    assertTrue(normal.contains("spawner"),
        "主世界必须隐藏刷怪笼（真机 PE 26.2 注册名就是 spawner）：" + normal);
    assertTrue(normal.contains("mossy_cobblestone"),
        "主世界必须隐藏苔石（地牢/要塞/矿洞结构的标志物）：" + normal);
    for (String name : new String[] {"chest", "raw_iron_block", "raw_gold_block", "raw_copper_block"}) {
      assertTrue(normal.contains(name), "主世界清单必须包含 " + name + "：" + normal);
    }
    // 本轮默认值收紧：这些方块不再默认隐藏（可制造 / 自然结构方块，藏它们只换来到处「假方块」与更高替换量）
    for (String name : new String[] {"trapped_chest", "ender_chest", "barrel", "furnace",
        "blast_furnace", "smoker", "hopper", "dropper", "dispenser", "shulker_box",
        "bedrock", "obsidian", "clay"}) {
      assertFalse(normal.contains(name), "主世界默认清单不得再含 " + name + "：" + normal);
      assertFalse(nether.contains(name), "地狱默认清单不得再含 " + name + "：" + nether);
    }

    // 地狱：含残骸与金矿、明确不含石英矿
    assertTrue(nether.contains("ancient_debris"), "地狱必须隐藏下界残骸：" + nether);
    assertTrue(nether.contains("nether_gold_ore"), "地狱必须隐藏下界金矿：" + nether);
    assertFalse(nether.contains("nether_quartz_ore"),
        "地狱默认不得隐藏石英矿（分布极广、价值极低，全藏会让玩家到处挖到假石头）：" + nether);
    // 主世界默认也不含地狱三矿（它们不会出现在主世界，放进去只是噪音）
    for (String name : new String[] {"nether_gold_ore", "nether_quartz_ore", "ancient_debris"}) {
      assertFalse(normal.contains(name), "主世界清单不得含地狱矿 " + name + "：" + normal);
    }

    // 默认权重：地狱为 netherrack/basalt/blackstone
    assertEquals("{netherrack=10, basalt=4, blackstone=3}",
        config.dimensionEffective(AntiXrayConfig.Dimension.NETHER).replacementWeights().toString(),
        "地狱默认伪装权重必须是下界岩/玄武岩/黑石");
    assertEquals(2,
        config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).replacementBands().size(),
        "主世界默认带 2 段按 Y 分区伪装表（深层深板岩系 / 浅层石头系）");
  }

  /** 新增配置键的默认值：use-block-below 关（行为不变）、事件显形开且限额保守、抽样 1/20、流体覆盖开。 */
  @Test
  void newFeatureDefaultsAreConservative() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("enabled: true\n"));
    assertFalse(config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).useBlockBelow(),
        "use-block-below 默认关闭（行为不变）");
    assertTrue(config.occlusion().fluidCover(),
        "流体覆盖默认开启（刷在岩浆里的下界残骸若不按遮挡处理会裸露在矿洞里被透视看到）");
    assertTrue(config.proximity().instantReveal().enabled(), "事件驱动即时显形默认开启");
    assertEquals(2, config.proximity().instantReveal().radius(), "事件显形曼哈顿半径默认 2");
    assertEquals(16, config.proximity().instantReveal().maxPerTick(), "事件显形每玩家每 tick 限额默认 16");
    assertEquals(20, config.proximity().overRevealSampling(), "过度显形抽样默认 1/20");
    assertFalse(config.proximity().instantReveal().enabled()
        && config.proximity().instantReveal().maxPerTick() <= 0, "开启时限额必须为正");
  }

  /** use-block-below / 事件显形 / 流体覆盖等配置可显式解析，非法/极端值被钳制。 */
  @Test
  void newFeatureKeysParseAndClamp() {
    AntiXrayConfig on = AntiXrayConfig.from(yaml(
        "dimensions:\n"
        + "  normal:\n"
        + "    use-block-below: true\n"
        + "proximity:\n"
        + "  instant-reveal:\n"
        + "    enabled: false\n"
        + "    radius: 99\n"
        + "    max-per-tick: 0\n"
        + "  over-reveal-sampling: 1\n"));
    assertTrue(on.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).useBlockBelow(),
        "use-block-below 可显式开启");
    assertFalse(on.proximity().instantReveal().enabled(), "事件显形可显式关闭");
    assertEquals(8, on.proximity().instantReveal().radius(), "radius 超限钳制到 8");
    assertEquals(0, on.proximity().instantReveal().maxPerTick(), "max-per-tick=0 即关闭事件显形");
    assertEquals(1, on.proximity().overRevealSampling(), "抽样率 1 表示全量复核");

    AntiXrayConfig negative = AntiXrayConfig.from(yaml(
        "proximity:\n"
        + "  instant-reveal:\n"
        + "    radius: -5\n"
        + "  over-reveal-sampling: -3\n"));
    assertEquals(1, negative.proximity().instantReveal().radius(), "radius 负值钳制到 1");
    assertEquals(0, negative.proximity().overRevealSampling(), "抽样负值归 0（关闭）");

    AntiXrayConfig fluidOff = AntiXrayConfig.from(yaml("occlusion:\n  fluid-cover: false\n"));
    assertFalse(fluidOff.occlusion().fluidCover(), "流体覆盖可显式关闭（两处规则都不生效）");
  }

  /** 配置指纹：影响改写结果的 use-block-below 切换必须使指纹变化（缓存不得复用）。 */
  @Test
  void configHashChangesWhenUseBlockBelowFlips() {
    AntiXrayConfig off = AntiXrayConfig.from(yaml("enabled: true\n"));
    AntiXrayConfig on = AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    use-block-below: true\n"));
    assertNotEquals(off.configHash(), on.configHash(),
        "use-block-below 改变改写结果，必须参与配置指纹");
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
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: enclosed\n"))
            .dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode());
    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: all\n"))
            .dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode());
    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: nonsense\n"))
            .dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode(),
        "非法取值一律回落为最安全的 all");
  }

  @Test
  void obfuscationModeParticipatesInConfigHash() {
    AntiXrayConfig enclosed =
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: enclosed\n"));
    AntiXrayConfig all = AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: all\n"));

    assertNotEquals(enclosed.configHash(), all.configHash(),
        "模式必须参与配置指纹，否则切换后旧缓存会被复用而泄漏裸露矿");
  }

  // ------------------------------------------------------------ 带宽

  @Test
  void bandwidthBlockChangeDefaultsFollowMiningStutterFix() {
    BandwidthConfig config = BandwidthConfig.from(yaml("enabled: true\n"));

    assertEquals(8, config.blockChanges().immediateRadius(),
        "近身变更立即放行半径默认 8 格（体验兜底，保持不降）");
    assertEquals(40, config.blockChanges().mergeWindowMillis(),
        "合并窗口默认 40ms（性能优先：更长时间窗更省包；实时手感由立即放行半径兜底）");
    assertEquals(4, config.blockChanges().mergeRadius(),
        "合并半径默认 4（性能优先：合并更多远处变更，交互半径内不受影响）");
  }

  /**
   * 实体剔除周期复检预算默认 12（性能优先，兼顾收敛速度与每周期主线程射线次数；建议区间 8~24）：
   * 必须有非零默认值，否则「先可见、之后才被挡住」的实体在本轮轮转分片下会收敛过慢（预算过小）
   * 或每周期读方块次数失控（预算过大）。复检周期同时回落到 10 tick（性能优先）。
   */
  @Test
  void entityCullingRecheckBudgetDefaultsToTwelve() {
    BandwidthConfig config = BandwidthConfig.from(yaml("enabled: true\n"));

    assertEquals(12, config.entityCulling().recheckBudget(),
        "周期复检预算默认 12（性能优先；建议区间 8~24）");
    assertEquals(10, config.entityCulling().updateIntervalTicks(),
        "复检周期默认 10 tick（性能优先）");
  }

  /** 非法值（0 / 负数）必须保守钳制到至少 1，避免复检完全不推进。 */
  @Test
  void entityCullingRecheckBudgetIsClampedToAtLeastOne() {
    assertEquals(1, BandwidthConfig.from(yaml("entity-culling:\n  recheck-budget: 0\n"))
        .entityCulling().recheckBudget(), "recheck-budget: 0 必须钳制为 1");
    assertEquals(1, BandwidthConfig.from(yaml("entity-culling:\n  recheck-budget: -5\n"))
        .entityCulling().recheckBudget(), "recheck-budget 负数必须钳制为 1");
    assertEquals(20, BandwidthConfig.from(yaml("entity-culling:\n  recheck-budget: 20\n"))
        .entityCulling().recheckBudget(), "显式取值按原样解析");
  }

  /**
   * 旧配置里残留已删除的 {@code entity-culling.threads} 键时，解析必须<b>静默忽略</b>该未知键
   * （不报警、不影响同段其它键、不影响默认值）：射线已改用 Paper 原生 {@code rayTraceBlocks}，
   * 判定直接在实体所属线程完成，该键自 1.1.x 起不再生效、现已彻底删除。
   */
  @Test
  void removedEntityCullingThreadsKeyIsSilentlyIgnored() {
    BandwidthConfig config = BandwidthConfig.from(yaml(
        "entity-culling:\n  threads: 4\n  ray-samples: 6\n"));

    assertTrue(config.entityCulling().raycast(), "同段其它键照常生效（不受已删除键影响）");
    assertEquals(6, config.entityCulling().raySamples(), "同段其它键仍按显式取值解析");
    assertEquals(10, config.entityCulling().updateIntervalTicks(), "未写的键仍回落默认值");
  }

  /**
   * {@code entity-culling.ray-samples} 的上限就是「包围盒可见顶点数」7：包围盒「朝向玩家一侧」的
   * 有效顶点最多 7 个，配置写 8 或更大不会多试出第 8 个点（旧实现钳到 8 但实际只用 7，属静默失效）。
   */
  @Test
  void raySamplesIsClampedToVisibleVertexCount() {
    assertEquals(7, BandwidthConfig.from(yaml("entity-culling:\n  ray-samples: 99\n"))
        .entityCulling().raySamples(), "超过上限按上限钳制");
    assertEquals(7, BandwidthConfig.MAX_RAY_SAMPLES, "上限常量本身即 7");
    assertEquals(1, BandwidthConfig.from(yaml("entity-culling:\n  ray-samples: 0\n"))
        .entityCulling().raySamples(), "下限仍为 1");
  }

  /**
   * 新增的各模块总开关（{@code *.enabled}）默认必须为 true —— 补开关不得改变既有行为。
   * 同时锁住 {@code palette.width-budget} 默认开启（收缩/降级收益），且 {@code palette.strict-verify} 默认关闭。
   */
  @Test
  void bandwidthModuleSwitchesDefaultToEnabled() {
    BandwidthConfig config = BandwidthConfig.from(yaml("enabled: true\n"));

    assertTrue(config.entityPackets().enabled(), "零位移实体包取消默认启用");
    assertTrue(config.blockChanges().enabled(), "方块变更合并默认启用");
    assertTrue(config.palette().enabled(), "调色板模块默认启用");
    assertTrue(config.palette().widthBudget(), "调色板位宽预算（含单值/低位宽降级）默认启用");
    assertFalse(config.palette().strictVerify(), "调色板自检默认关闭（仅在排查问题时开启）");
    assertTrue(config.entityCulling().enabled(), "实体射线剔除默认启用");
    assertTrue(config.afk().enabled(), "AFK 降级默认启用（新增开关不得改变既有行为）");
    assertTrue(config.latency().enabled(), "高延迟降视距默认启用（新增开关不得改变既有行为）");
  }

  /** 各模块总开关可被显式关闭（false），用于确认开关确实接入了配置解析。 */
  @Test
  void bandwidthModuleSwitchesParseExplicitFalse() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-packets:
          enabled: false
        block-changes:
          enabled: false
        palette:
          enabled: false
        entity-culling:
          enabled: false
        afk:
          enabled: false
        latency:
          enabled: false
        """));

    assertFalse(config.entityPackets().enabled());
    assertFalse(config.blockChanges().enabled());
    assertFalse(config.palette().enabled());
    assertFalse(config.entityCulling().enabled());
    assertFalse(config.afk().enabled());
    assertFalse(config.latency().enabled());
  }

  // ------------------------------------------------------------ 缺省键全覆盖（antixray）

  /**
   * antixray.yml 其余各键的内置缺省（空配置触发回落路径）：断言逐键与 antixray.yml 的默认值一致，
   * 防止「yml 注释写了 A、代码回落是 B」的注释失真。
   */
  @Test
  void antiXrayRemainingKeysFallBackToDocumentedDefaults() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml(""));

    assertTrue(config.enabled(), "反矿透总开关默认开启");
    assertTrue(config.antiXrayAppliesTo("any_world"), "总开关开启且未命中黑名单时对任意世界生效（世界白名单已随按维度分段重构移除）");
    assertTrue(config.worldBlacklist().isEmpty(), "反矿透世界黑名单默认空列表（现有用户行为不变）");
    assertFalse(config.isBlacklisted("spawn"), "空黑名单下任何世界都不豁免");
    assertTrue(config.antiXrayAppliesTo("spawn"), "空黑名单下所有世界照常反矿透");
    // 维度启用：主世界/地狱默认启用，末地默认关闭（末地无矿物）
    assertTrue(config.dimensionEnabled(AntiXrayConfig.Dimension.NORMAL), "主世界默认启用");
    assertTrue(config.dimensionEnabled(AntiXrayConfig.Dimension.NETHER), "地狱默认启用");
    assertFalse(config.dimensionEnabled(AntiXrayConfig.Dimension.THE_END), "末地默认关闭");
    assertTrue(config.dimensionsMissing(),
        "空配置缺少 dimensions 段 → 用内置默认运行并会一次性 WARN（绝不让保护静默失效）");
    assertFalse(config.layerObfuscation(), "层状伪装默认关闭（逐方块独立随机，更不易被模式识别）");
    assertTrue(config.removeBlockEntities(), "方块实体剔除默认开启（防幽灵刷怪笼/箱子）");

    // occlusion 三键：覆盖表默认为空（内置规则兜底，由用户按需填写），流体覆盖默认开
    assertTrue(config.occlusion().extraOccluding().isEmpty(), "extra-occluding 默认为空");
    assertTrue(config.occlusion().extraNonOccluding().isEmpty(), "extra-non-occluding 默认为空");
    assertTrue(config.occlusion().fluidCover(), "fluid-cover 默认开启");

    // neighbors 三键
    assertTrue(config.neighbors().enabled(), "邻块贴边快照默认开启");
    assertEquals(AntiXrayConfig.MissingPolicy.HIDE, config.neighbors().missingPolicy(),
        "邻块缺失默认按遮挡处理（hide，宁可多伪装也不留边界透视口子）");
    assertEquals(2048, config.neighbors().cacheMaximumSize(),
        "邻块快照缓存默认 2048 条（快照按位打包，每条约 3 KB → 共约 6 MB）");

    // proximity 其余键
    assertTrue(config.proximity().enabled(), "邻近显形默认开启");
    assertTrue(config.proximity().frustumEnabled(), "视锥剔除默认开启");
    assertEquals(AntiXrayConfig.FRUSTUM_FOV_FLOOR, config.proximity().frustumFov(), 1.0E-9D,
        "视锥竖直全角默认为安全下限 110°（客户端 FOV 上限；配窄会让玩家看得见的方块保持伪装）");
    assertTrue(config.floorAdjustments().isEmpty(), "默认值不触发安全下限抬升（不产生误导性 WARN）");
    assertEquals(4, config.proximity().raycastSamples(),
        "候选点数默认 4（原生射线改造后语义为「每方块最多尝试的候选点数」，钳制 1..5）");
    assertTrue(config.proximity().batchRevealSends(),
        "显形包批量合并默认开启（Paper 原生多方块变更包，关掉即回退为逐坐标单包）");

    // disk-cache 全部 14 键
    assertTrue(config.diskCache().enabled(), "磁盘缓存默认开启");
    assertEquals(20000, config.diskCache().maxEntries(), "条目总数上限默认 20000");
    assertEquals(16, config.diskCache().maxFileSizeMb(), "单区域文件上限默认 16 MB");
    assertEquals(7 * 24 * 60 * 60, config.diskCache().expireSeconds(),
        "条目过期默认 7 天（原 1800 秒：从写入时刻算起，配得比两次启动间隔还短 → 重启后条目全过期、"
            + "命中率恒为 0；实机 70 分钟后重启命中 0／未命中 2096）。内容新鲜度由原始字节指纹判定，"
            + "限制磁盘占用应调 max-entries / max-file-size-mb");
    assertEquals(8, config.diskCache().bucketCacheSize(),
        "bucket 缓存默认 8（原 2；真机命中率提升约 16~20 个百分点，依据见 antixray.yml 注释）");
    assertEquals(300, config.diskCache().idleCloseSeconds(), "句柄闲置关闭默认 300 秒");
    assertEquals(30, config.diskCache().maintenanceIntervalSeconds(), "后台维护周期默认 30 秒");
    assertEquals(4, config.diskCache().compactPerPass(), "每轮最多压缩 4 个区域文件");
    assertEquals(256, config.diskCache().queueCapacity(), "磁盘线程待处理任务上限默认 256");
    // zstd 前置三键（自动识别 / 自动下载）
    assertTrue(config.diskCache().zstdAutoDownload(), "zstd 自动下载默认开启");
    assertEquals(AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, config.diskCache().zstdDownloadUrl(),
        "zstd 下载源默认 Maven Central");
    assertEquals(10, config.diskCache().zstdTimeoutSeconds(), "zstd 下载超时默认 10 秒");
    assertEquals("", config.diskCache().zstdSha256(),
        "zstd 下载校验哈希默认空串（不校验；缺键也不得抛异常）");

    // cache 两键（改写结果的内存缓存）
    assertEquals(40960, config.cacheMaximumSize(),
        "改写缓存条目上限默认 40960（约 0.8~1 GB 上限；条目只在真遇到新区块时增长）");
    assertEquals(600, config.cacheExpireAfterAccessSeconds(), "改写缓存访问后过期默认 600 秒");

    // advanced 三键
    assertEquals(0, config.threads(), "工作线程数默认 0 = 按 CPU 自动推算（上限 4）");
    assertEquals(2500, config.timeoutMillis(), "单区块包处理超时默认 2500 毫秒");
    assertEquals(2048, config.queueCapacity(), "工作队列容量默认 2048");
    assertTrue(config.ceilingAdjustments().isEmpty(), "默认值不触发安全上限回落（不产生误导性 WARN）");
  }

  /**
   * 反矿透侧的安全上限：邻近显形距离 / 邻块缓存 / 改写缓存三键配成失控值时按上限生效并留痕；
   * {@code proximity.raycast.samples} 的有效上限是「暴露面上的候选点数」5（配 6~8 静默无效）。
   *
   * <p>邻近显形距离的扫描量随半径平方增长（O(r²)），配成几百上千会把主线程拖住——这正是补上限的动机。
   */
  @Test
  void antixrayCeilingsClampAndReport() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("""
        proximity:
          distance: 4096.0
          raycast:
            samples: 8
        neighbors:
          cache-maximum-size: 1000000
        cache:
          maximum-size: 409600
        """));

    assertEquals(AntiXrayConfig.PROXIMITY_DISTANCE_MAX, config.proximity().distance(), 1.0E-9D,
        "邻近显形距离超过上限必须钳制到 128 格（扫描量 O(r²)，防止把主线程拖住）");
    assertEquals(AntiXrayConfig.PROXIMITY_RAY_SAMPLES_MAX, config.proximity().raycastSamples(),
        "候选点数超过有效上限必须钳制到 5（暴露面上至多 5 个候选点）");
    assertEquals(AntiXrayConfig.NEIGHBORS_CACHE_MAXIMUM_MAX, config.neighbors().cacheMaximumSize(),
        "邻块缓存条目超过上限必须钳制到 16384");
    assertEquals(AntiXrayConfig.CACHE_MAXIMUM_SIZE_MAX, config.cacheMaximumSize(),
        "改写缓存条目超过上限必须钳制到 65536");

    String details = String.join("、", config.ceilingAdjustments());
    assertTrue(details.contains("proximity.distance=4096.0"), details);
    assertTrue(details.contains("neighbors.cache-maximum-size=1000000"), details);
    assertTrue(details.contains("cache.maximum-size=409600"), details);

    // 上限以内的显式值原样生效、不产生明细
    AntiXrayConfig inRange = AntiXrayConfig.from(yaml("""
        proximity:
          distance: 128.0
          raycast:
            samples: 5
        neighbors:
          cache-maximum-size: 4096
        cache:
          maximum-size: 50000
        """));
    assertEquals(128.0D, inRange.proximity().distance(), 1.0E-9D);
    assertEquals(5, inRange.proximity().raycastSamples(), "samples=5 是有效上限，应原样生效");
    assertEquals(4096, inRange.neighbors().cacheMaximumSize());
    assertEquals(50000, inRange.cacheMaximumSize());
    assertTrue(inRange.ceilingAdjustments().isEmpty(),
        "上限内的值不得产生回落明细：" + inRange.ceilingAdjustments());

    // 下限：邻块缓存/改写缓存为 0 或负数时至少取 1（否则缓存恒空、退化为重复抓取）
    AntiXrayConfig floored = AntiXrayConfig.from(yaml("""
        neighbors:
          cache-maximum-size: 0
        cache:
          maximum-size: -5
        """));
    assertEquals(1, floored.neighbors().cacheMaximumSize(), "邻块缓存下限为 1");
    assertEquals(1, floored.cacheMaximumSize(), "改写缓存下限为 1");
  }

  /**
   * {@code proximity.distance} 的非有限值（NaN / ±Inf）必须回落默认 64，不得原样穿过钳制。
   *
   * <p>NaN 会污染距离比较（所有比较恒 false）并进入配置指纹，导致磁盘缓存整体失效。
   */
  @Test
  void proximityDistanceNonFiniteFallsBackToDefault() {
    AntiXrayConfig nan = AntiXrayConfig.from(yaml("proximity:\n  distance: .nan\n"));
    assertEquals(64.0D, nan.proximity().distance(), 1.0E-9D, "NaN 必须回落默认 64");
    assertTrue(String.join("、", nan.ceilingAdjustments()).contains("proximity.distance=NaN"),
        "非有限值回落必须留痕：" + nan.ceilingAdjustments());

    AntiXrayConfig inf = AntiXrayConfig.from(yaml("proximity:\n  distance: .inf\n"));
    assertEquals(64.0D, inf.proximity().distance(), 1.0E-9D,
        "+Inf 属于非有限值，同样回落默认 64（而不是被当成超大值）");
    assertTrue(String.join("、", inf.ceilingAdjustments()).contains("proximity.distance=Infinity"),
        "非有限值回落必须留痕：" + inf.ceilingAdjustments());
  }

  // ------------------------------------------------------------ 缺省键全覆盖（bandwidth）

  /** bandwidth.yml 其余各键的内置缺省（空配置触发回落路径），与 yml 默认值逐键对照。 */
  @Test
  void bandwidthRemainingKeysFallBackToDocumentedDefaults() {
    BandwidthConfig config = BandwidthConfig.from(yaml(""));

    assertTrue(config.enabled(), "带宽优化总开关默认开启");

    // entity-packets：行为开关与白名单
    assertTrue(config.entityPackets().skipZeroMovement(), "零位移取消默认开启");
    assertTrue(config.entityPackets().whitelist().isEmpty(),
        "白名单内置缺省为空（bandwidth.yml 随包文件里带 armor_stand 等 3 项，属配置文件值而非代码缺省）");

    // block-changes 其余键
    assertTrue(config.blockChanges().merge(), "邻域合并行为开关默认开启");
    assertEquals(4, config.blockChanges().mergeRadius(), "合并邻域半径默认 4（性能优先，曼哈顿距离）");
    assertEquals(4096, config.blockChanges().maxPerPacket(), "单合并包上限默认 4096 条变更");
    assertTrue(config.blockChanges().resendOnOverflow(), "超限拆分续发默认开启");
    assertEquals(256, config.blockChanges().maxPendingEntries(), "待发缓冲上限默认 256 条");

    // palette：strict-verify / width-budget（width-budget 默认已在既有测试锁定为 true）
    assertFalse(config.palette().strictVerify(), "调色板收缩/降级自检默认关闭（仅 width-budget=true 时有意义）");
    assertTrue(config.palette().widthBudget(), "调色板位宽预算（含单值/低位宽降级）默认开启");

    // entity-culling 其余键（threads 键已彻底删除，其残留配置的兼容性见下方专项测试）
    assertTrue(config.entityCulling().raycast(), "实体射线判定默认开启");
    assertEquals(32.0D, config.entityCulling().forceVisibleDistance(), 1.0E-9D,
        "强制可见距离默认 32 格（性能优先，设计兜底值）");
    assertEquals(BandwidthConfig.MAX_RAY_SAMPLES, config.entityCulling().raySamples(),
        "候选顶点数默认取上限 7（包围盒可见顶点最多 7 个，钳制 1..7）");

    // afk 全部键
    assertEquals(300, config.afk().seconds(), "AFK 判定默认 300 秒无操作");
    assertEquals(16.0D, config.afk().distance(), 1.0E-9D, "AFK 低价值包丢弃距离默认 16 格");
    assertTrue(config.afk().dropParticles(), "AFK 丢弃粒子包默认保留 true（数量最多、价值最低）");
    assertTrue(config.afk().dropBlockBreakAnimation(),
        "AFK 默认丢弃破坏动画包（性能优先；玩家体验由「AFK 判定只看真实操作」兜底）");

    // latency 全部键（性能优先：更早介入、降幅略大，体验由下限与持续时间门槛兜底）
    assertEquals(400, config.latency().thresholdMillis(), "延迟观察阈值默认 400 毫秒");
    assertEquals(30, config.latency().sustainSeconds(), "持续超阈 30 秒才真正降视距");
    assertEquals(2, config.latency().reduceViewDistance(), "触发后降视距默认 2");
    assertEquals(6, config.latency().minViewDistance(), "视距下限默认 6（体验兜底）");
    assertEquals(5, config.latency().checkIntervalSeconds(), "延迟采样周期默认 5 秒");

    // diagnostics
    assertEquals(60, config.diagnostics().intervalSeconds(), "周期运行摘要默认 60 秒（0 = 关闭）");
  }

  /**
   * 带宽配置的「安全上限」：失控值（多打一个 0）被钳制到上限并留下明细（供加载路径一次性 WARN）；
   * 上限以内的显式值原样生效；默认值不触发钳制——即新增上限不改变任何默认行为。
   */
  @Test
  void bandwidthOptionsAboveSafetyCeilingAreClampedAndReported() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        block-changes:
          merge-radius: 999
          max-per-packet: 1000000
          merge-window-millis: 100000
          max-pending-entries: 1000000
          immediate-radius: 999
        entity-culling:
          force-visible-distance: 99999.0
          update-interval-ticks: 100000
          recheck-budget: 100000
        afk:
          seconds: 100000000
          distance: 99999.0
        latency:
          threshold-millis: 100000000
          reduce-view-distance: 999
          sustain-seconds: 100000000
          min-view-distance: 999
          check-interval-seconds: 100000000
        diagnostics:
          interval-seconds: 100000000
        """));

    assertEquals(BandwidthConfig.MAX_MERGE_RADIUS, config.blockChanges().mergeRadius());
    assertEquals(BandwidthConfig.MAX_PER_PACKET_LIMIT, config.blockChanges().maxPerPacket());
    assertEquals(BandwidthConfig.MAX_MERGE_WINDOW_MILLIS, config.blockChanges().mergeWindowMillis());
    assertEquals(BandwidthConfig.MAX_PENDING_ENTRIES_LIMIT, config.blockChanges().maxPendingEntries());
    assertEquals(BandwidthConfig.MAX_IMMEDIATE_RADIUS, config.blockChanges().immediateRadius());
    assertEquals(BandwidthConfig.MAX_FORCE_VISIBLE_DISTANCE,
        config.entityCulling().forceVisibleDistance(), 1.0E-9D);
    assertEquals(BandwidthConfig.MAX_UPDATE_INTERVAL_TICKS, config.entityCulling().updateIntervalTicks());
    assertEquals(BandwidthConfig.MAX_RECHECK_BUDGET, config.entityCulling().recheckBudget());
    assertEquals(BandwidthConfig.MAX_AFK_SECONDS, config.afk().seconds());
    assertEquals(BandwidthConfig.MAX_AFK_DISTANCE, config.afk().distance(), 1.0E-9D);
    assertEquals(BandwidthConfig.MAX_LATENCY_THRESHOLD_MILLIS, config.latency().thresholdMillis());
    assertEquals(BandwidthConfig.MAX_REDUCE_VIEW_DISTANCE, config.latency().reduceViewDistance());
    assertEquals(BandwidthConfig.MAX_SUSTAIN_SECONDS, config.latency().sustainSeconds());
    assertEquals(BandwidthConfig.MAX_MIN_VIEW_DISTANCE, config.latency().minViewDistance());
    assertEquals(BandwidthConfig.MAX_CHECK_INTERVAL_SECONDS, config.latency().checkIntervalSeconds());
    assertEquals(BandwidthConfig.MAX_DIAGNOSTICS_INTERVAL_SECONDS,
        config.diagnostics().intervalSeconds());

    String details = String.join("、", config.clampAdjustments());
    assertTrue(details.contains("block-changes.merge-radius=999"), details);
    assertTrue(details.contains("latency.sustain-seconds=100000000"), details);
    assertTrue(details.contains("afk.seconds=100000000"), details);
    assertTrue(details.contains("entity-culling.force-visible-distance=99999.0"), details);

    // 上限以内的显式值原样生效、不产生明细
    BandwidthConfig inRange = BandwidthConfig.from(yaml("""
        block-changes:
          merge-radius: 3
          max-per-packet: 8192
          merge-window-millis: 50
        latency:
          reduce-view-distance: 3
          min-view-distance: 6
        entity-culling:
          recheck-budget: 20
        """));
    assertEquals(3, inRange.blockChanges().mergeRadius(), "上限内的半径原样生效");
    assertEquals(8192, inRange.blockChanges().maxPerPacket());
    assertEquals(50, inRange.blockChanges().mergeWindowMillis());
    assertEquals(3, inRange.latency().reduceViewDistance(), "上限内的降视距原样生效（新上限 4）");
    assertEquals(6, inRange.latency().minViewDistance(), "下限之上的视距下限原样生效");
    assertEquals(20, inRange.entityCulling().recheckBudget());
    assertTrue(inRange.clampAdjustments().isEmpty(),
        "上限内的值不得产生钳制明细：" + inRange.clampAdjustments());

    // 默认值（空配置 / 仅总开关）不得触发任何钳制
    assertTrue(BandwidthConfig.from(yaml("")).clampAdjustments().isEmpty(),
        "默认值必须全部落在合法区间内（新增上限不得改变默认行为）");
    assertTrue(BandwidthConfig.from(yaml("enabled: true\n")).clampAdjustments().isEmpty());
  }

  /**
   * 本轮重定的安全上限 / 下限逐键钉死，并验证「恰好等于边界」原样生效、「越界一格」被钳制。
   *
   * <p>设计原则：取「即使调到极限也不影响玩家体验」的值——越高越安全的键放宽上限，越高越有害的键收到
   * 安全极限；且每个新上限都必须 ≥ 该键默认值（否则默认值自身会被钳制）。
   */
  @Test
  void bandwidthSafetyCeilingsMatchRedesignedLimits() {
    assertEquals(16, BandwidthConfig.MAX_MERGE_RADIUS);
    assertEquals(32768, BandwidthConfig.MAX_PER_PACKET_LIMIT);
    assertEquals(200, BandwidthConfig.MAX_MERGE_WINDOW_MILLIS);
    assertEquals(8192, BandwidthConfig.MAX_PENDING_ENTRIES_LIMIT);
    assertEquals(64, BandwidthConfig.MAX_IMMEDIATE_RADIUS);
    assertEquals(1024.0D, BandwidthConfig.MAX_FORCE_VISIBLE_DISTANCE, 1.0E-9D);
    assertEquals(40, BandwidthConfig.MAX_UPDATE_INTERVAL_TICKS);
    assertEquals(2048, BandwidthConfig.MAX_RECHECK_BUDGET);
    assertEquals(86400, BandwidthConfig.MAX_AFK_SECONDS);
    assertEquals(1024.0D, BandwidthConfig.MAX_AFK_DISTANCE, 1.0E-9D);
    assertEquals(60000, BandwidthConfig.MAX_LATENCY_THRESHOLD_MILLIS);
    assertEquals(4, BandwidthConfig.MAX_REDUCE_VIEW_DISTANCE);
    assertEquals(3600, BandwidthConfig.MAX_SUSTAIN_SECONDS);
    assertEquals(16, BandwidthConfig.MAX_MIN_VIEW_DISTANCE);
    assertEquals(6, BandwidthConfig.MIN_VIEW_DISTANCE_FLOOR);
    assertEquals(3600, BandwidthConfig.MAX_CHECK_INTERVAL_SECONDS);
    assertEquals(86400, BandwidthConfig.MAX_DIAGNOSTICS_INTERVAL_SECONDS);

    // 新上限都必须 ≥ 对应默认值（否则默认值自身会被钳制并打出误导性 WARN）
    BandwidthConfig defaults = BandwidthConfig.from(yaml(""));
    assertTrue(defaults.blockChanges().mergeRadius() <= BandwidthConfig.MAX_MERGE_RADIUS);
    assertTrue(defaults.blockChanges().mergeWindowMillis() <= BandwidthConfig.MAX_MERGE_WINDOW_MILLIS);
    assertTrue(defaults.blockChanges().immediateRadius() <= BandwidthConfig.MAX_IMMEDIATE_RADIUS);
    assertTrue(defaults.entityCulling().forceVisibleDistance() <= BandwidthConfig.MAX_FORCE_VISIBLE_DISTANCE);
    assertTrue(defaults.entityCulling().updateIntervalTicks() <= BandwidthConfig.MAX_UPDATE_INTERVAL_TICKS);
    assertTrue(defaults.entityCulling().recheckBudget() <= BandwidthConfig.MAX_RECHECK_BUDGET);
    assertTrue(defaults.afk().distance() <= BandwidthConfig.MAX_AFK_DISTANCE);
    assertTrue(defaults.latency().reduceViewDistance() <= BandwidthConfig.MAX_REDUCE_VIEW_DISTANCE);
    assertTrue(defaults.latency().minViewDistance() <= BandwidthConfig.MAX_MIN_VIEW_DISTANCE);
    assertTrue(defaults.latency().minViewDistance() >= BandwidthConfig.MIN_VIEW_DISTANCE_FLOOR);

    // 恰好等于边界：原样生效且不产生明细；越界一格：被钳制
    BandwidthConfig atCeiling = BandwidthConfig.from(yaml("""
        block-changes:
          merge-window-millis: 200
        entity-culling:
          update-interval-ticks: 40
        afk:
          distance: 1024.0
        latency:
          reduce-view-distance: 4
        """));
    assertEquals(200, atCeiling.blockChanges().mergeWindowMillis(), "恰好等于上限原样生效");
    assertEquals(40, atCeiling.entityCulling().updateIntervalTicks());
    assertEquals(1024.0D, atCeiling.afk().distance(), 1.0E-9D);
    assertEquals(4, atCeiling.latency().reduceViewDistance());
    assertTrue(atCeiling.clampAdjustments().isEmpty(),
        "恰好等于上限不得产生明细：" + atCeiling.clampAdjustments());

    BandwidthConfig justOver = BandwidthConfig.from(yaml("""
        block-changes:
          merge-window-millis: 201
        entity-culling:
          update-interval-ticks: 41
        afk:
          distance: 1025.0
        latency:
          reduce-view-distance: 5
        """));
    assertEquals(BandwidthConfig.MAX_MERGE_WINDOW_MILLIS, justOver.blockChanges().mergeWindowMillis(),
        "超过一格也必须钳制到上限");
    assertEquals(BandwidthConfig.MAX_UPDATE_INTERVAL_TICKS, justOver.entityCulling().updateIntervalTicks());
    assertEquals(BandwidthConfig.MAX_AFK_DISTANCE, justOver.afk().distance(), 1.0E-9D);
    assertEquals(BandwidthConfig.MAX_REDUCE_VIEW_DISTANCE, justOver.latency().reduceViewDistance());
  }

  /**
   * 本版「性能优先」默认值全部落在合法钳制区间内：把它们逐键显式写进 YAML（等价于服主按注释原样替换）
   * 既不得触发上限钳制、也不得触发下限兜底，因此不会打出任何「被钳制」WARN。
   *
   * <p>同时锁定这组默认值本身（防被无意改回更保守的更省带宽档，或改出越界值）。
   */
  @Test
  void performanceFirstDefaultsAreWithinClampRangeAndProduceNoWarn() {
    BandwidthConfig explicit = BandwidthConfig.from(yaml("""
        block-changes:
          merge-radius: 4
          merge-window-millis: 40
          immediate-radius: 8
        entity-culling:
          force-visible-distance: 32.0
          update-interval-ticks: 10
          recheck-budget: 12
        afk:
          drop-particles: true
          drop-block-break-animation: true
        latency:
          threshold-millis: 400
          sustain-seconds: 30
          reduce-view-distance: 2
          min-view-distance: 6
        """));

    assertTrue(explicit.clampAdjustments().isEmpty(),
        "性能优先默认值不得触发任何钳制明细（否则会出现误导性 WARN）：" + explicit.clampAdjustments());
    assertEquals(4, explicit.blockChanges().mergeRadius());
    assertEquals(40, explicit.blockChanges().mergeWindowMillis());
    assertEquals(8, explicit.blockChanges().immediateRadius());
    assertEquals(32.0D, explicit.entityCulling().forceVisibleDistance(), 1.0E-9D);
    assertEquals(10, explicit.entityCulling().updateIntervalTicks());
    assertEquals(12, explicit.entityCulling().recheckBudget());
    assertTrue(explicit.afk().dropParticles());
    assertTrue(explicit.afk().dropBlockBreakAnimation());
    assertEquals(400, explicit.latency().thresholdMillis());
    assertEquals(30, explicit.latency().sustainSeconds());
    assertEquals(2, explicit.latency().reduceViewDistance());
    assertEquals(6, explicit.latency().minViewDistance());

    // 缺省（空配置）与显式写入必须解析出完全相同的生效值
    BandwidthConfig byDefault = BandwidthConfig.from(yaml(""));
    assertEquals(byDefault.blockChanges(), explicit.blockChanges(),
        "缺省与显式性能优先档的方块合并取值必须一致");
    assertEquals(byDefault.entityCulling(), explicit.entityCulling(), "缺省与显式性能优先档的实体剔除取值必须一致");
    assertEquals(byDefault.afk(), explicit.afk(), "缺省与显式性能优先档的 AFK 取值必须一致");
    assertEquals(byDefault.latency(), explicit.latency(), "缺省与显式性能优先档的延迟降视距取值必须一致");
    assertTrue(byDefault.clampAdjustments().isEmpty(), "缺省默认值同样不得触发钳制");
  }

  /**
   * {@code latency.min-view-distance} 是本轮新增安全下限的键：默认 6，低于 6 会被抬升到 6 并留痕。
   *
   * <p>本项是「视距最多降到多少」的体验兜底——旧值 4 会让弱网玩家几乎看不见周围，故设硬下限；
   * 同时校验新上限（16）与默认值（6）都落在区间内。
   */
  @Test
  void minViewDistanceBelowFloorIsRaisedAndReported() {
    BandwidthConfig raised = BandwidthConfig.from(yaml("latency:\n  min-view-distance: 4\n"));
    assertEquals(BandwidthConfig.MIN_VIEW_DISTANCE_FLOOR, raised.latency().minViewDistance(),
        "旧值 4 必须被抬升到新的安全下限 6");
    assertTrue(String.join("、", raised.clampAdjustments()).contains("latency.min-view-distance=4"),
        "抬升必须留下明细供加载时一次性 WARN：" + raised.clampAdjustments());

    BandwidthConfig atFloor = BandwidthConfig.from(yaml("latency:\n  min-view-distance: 6\n"));
    assertEquals(6, atFloor.latency().minViewDistance(), "恰好等于下限的值原样生效");
    assertTrue(atFloor.clampAdjustments().isEmpty(), "取值在下限处不得产生明细");

    BandwidthConfig aboveCeiling = BandwidthConfig.from(yaml("latency:\n  min-view-distance: 99\n"));
    assertEquals(BandwidthConfig.MAX_MIN_VIEW_DISTANCE, aboveCeiling.latency().minViewDistance(),
        "超过新上限的值钳制到 16");
  }

  /**
   * {@code block-changes.merge-radius} 的新安全下限：0 是纯负收益（原包仍入缓冲并延迟一个时间窗，
   * 却几乎拼不出可合并的多条簇 → 既省不到包又徒增延迟/开销），故抬到 1 并留痕；
   * 要彻底关掉合并应使用 {@code block-changes.merge=false}。
   */
  @Test
  void mergeRadiusZeroIsRaisedToFloorAndReported() {
    BandwidthConfig raised = BandwidthConfig.from(yaml("block-changes:\n  merge-radius: 0\n"));
    assertEquals(BandwidthConfig.MIN_MERGE_RADIUS, raised.blockChanges().mergeRadius(),
        "合并半径 0 必须抬到下限 1（0 仍入缓冲延迟却几乎拼不出可合并簇，纯负收益）");
    assertTrue(String.join("、", raised.clampAdjustments()).contains("block-changes.merge-radius=0"),
        "抬升必须留下明细供加载时一次性 WARN：" + raised.clampAdjustments());

    BandwidthConfig atFloor = BandwidthConfig.from(yaml("block-changes:\n  merge-radius: 1\n"));
    assertEquals(1, atFloor.blockChanges().mergeRadius(), "恰好等于下限的值原样生效");
    assertTrue(atFloor.clampAdjustments().isEmpty(), "取值在下限处不得产生明细");
  }

  /**
   * 非有限值（NaN / ±Inf）必须显式回落默认并留痕，且<b>不得污染配置指纹</b>。
   *
   * <p>回归动机：NaN 不满足 {@code > max}，旧实现会让它原样穿过钳制进入 {@code configHash}——
   * 于是同一份「其实无效」的配置算出的指纹与任何正常进程都不同，磁盘缓存被整体判为「配置已变」
   * 而失效（重启后命中率恒为 0）。现在 NaN/±Inf 一律回落默认，生效值与指纹都与「根本没写这项」一致。
   */
  @Test
  void bandwidthNonFiniteValuesFallBackToDefaultsAndKeepFingerprintStable() {
    BandwidthConfig base = BandwidthConfig.from(yaml("enabled: true\n"));
    BandwidthConfig nan = BandwidthConfig.from(yaml("""
        entity-culling:
          force-visible-distance: .nan
        afk:
          distance: .nan
        """));

    assertEquals(32.0D, nan.entityCulling().forceVisibleDistance(), 1.0E-9D, "NaN 必须回落默认 32");
    assertEquals(16.0D, nan.afk().distance(), 1.0E-9D, "NaN 必须回落默认 16");
    String nanDetails = String.join("、", nan.clampAdjustments());
    assertTrue(nanDetails.contains("entity-culling.force-visible-distance"), nanDetails);
    assertTrue(nanDetails.contains("afk.distance"), nanDetails);
    assertTrue(nanDetails.contains("非有限值"), nanDetails);
    assertEquals(base.configHash(), nan.configHash(),
        "非有限值回落默认后指纹必须与默认配置一致（否则磁盘缓存会被无谓地整体失效）");

    BandwidthConfig inf = BandwidthConfig.from(yaml("""
        entity-culling:
          force-visible-distance: .inf
        afk:
          distance: -.inf
        """));
    assertEquals(32.0D, inf.entityCulling().forceVisibleDistance(), 1.0E-9D, "+Inf 必须回落默认 32");
    assertEquals(16.0D, inf.afk().distance(), 1.0E-9D, "-Inf 必须回落默认 16（而不是被 Math.max 悄悄抬成 0）");
    assertEquals(base.configHash(), inf.configHash(), "±Inf 回落默认后指纹同样必须稳定");
  }

  /**
   * 带宽配置指纹：跨进程稳定（不混入枚举 identity hash），且随影响行为的带宽项变化——
   * 热重载据此判断「带宽侧配置是否变化」，只改 bandwidth.yml 时提示语才不会误导。
   */
  @Test
  void bandwidthConfigHashIsStableAndSensitive() {
    BandwidthConfig base = BandwidthConfig.from(yaml("enabled: true\n"));
    assertEquals(base.configHash(), BandwidthConfig.from(yaml("enabled: true\n")).configHash(),
        "同一份带宽配置重复解析必须得到同一指纹");
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("afk:\n  seconds: 600\n")).configHash(),
        "影响行为的带宽项变化必须改变指纹");
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("block-changes:\n  merge-radius: 3\n")).configHash(),
        "每个带宽子模块的取值都应参与指纹");
  }
}