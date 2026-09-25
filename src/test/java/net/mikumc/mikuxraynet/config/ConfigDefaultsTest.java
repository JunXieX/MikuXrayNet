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

    assertEquals(8, config.blockChanges().immediateRadius(), "近身变更立即放行半径默认 8 格");
    assertEquals(20, config.blockChanges().mergeWindowMillis(), "合并窗口默认收紧到 20ms");
  }

  /**
   * 实体剔除周期复检预算默认 12（建议区间 8~16）：必须有非零默认值，否则「先可见、之后才被挡住」的
   * 实体在本轮轮转分片下会收敛过慢（预算过小）或每周期读方块次数失控（预算过大）。
   */
  @Test
  void entityCullingRecheckBudgetDefaultsToTwelve() {
    BandwidthConfig config = BandwidthConfig.from(yaml("enabled: true\n"));

    assertEquals(12, config.entityCulling().recheckBudget(),
        "周期复检预算默认 12（落在建议的 8~16 区间内）");
    assertEquals(10, config.entityCulling().updateIntervalTicks(), "复检周期保持 10 tick");
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
   * 同时锁住 {@code palette.reorder} 仍为 false（zlib/zstd 两种压缩口径实测均无收益）。
   */
  @Test
  void bandwidthModuleSwitchesDefaultToEnabled() {
    BandwidthConfig config = BandwidthConfig.from(yaml("enabled: true\n"));

    assertTrue(config.entityPackets().enabled(), "零位移实体包取消默认启用");
    assertTrue(config.blockChanges().enabled(), "方块变更合并默认启用");
    assertTrue(config.palette().enabled(), "调色板模块默认启用");
    assertTrue(config.entityCulling().enabled(), "实体射线剔除默认启用");
    assertTrue(config.afk().enabled(), "AFK 降级默认启用（新增开关不得改变既有行为）");
    assertTrue(config.latency().enabled(), "高延迟降视距默认启用（新增开关不得改变既有行为）");
    assertFalse(config.palette().reorder(), "调色板重排默认保持 false（两种压缩口径实测均无收益）");
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
        "候选点数默认 4（原生射线改造后语义为「每方块最多尝试的候选点数」，钳制 1..8）");
    assertTrue(config.proximity().batchRevealSends(),
        "显形包批量合并默认开启（Paper 原生多方块变更包，关掉即回退为逐坐标单包）");

    // disk-cache 全部 13 键
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
    assertEquals(32768, config.diskCache().generationTrackerSize(), "代次跟踪表默认 32768 条");
    // zstd 前置三键（自动识别 / 自动下载）
    assertTrue(config.diskCache().zstdAutoDownload(), "zstd 自动下载默认开启");
    assertEquals(AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, config.diskCache().zstdDownloadUrl(),
        "zstd 下载源默认 Maven Central");
    assertEquals(10, config.diskCache().zstdTimeoutSeconds(), "zstd 下载超时默认 10 秒");

    // cache 两键（改写结果的内存缓存）
    assertEquals(40960, config.cacheMaximumSize(),
        "改写缓存条目上限默认 40960（约 0.8~1 GB 上限；条目只在真遇到新区块时增长）");
    assertEquals(600, config.cacheExpireAfterAccessSeconds(), "改写缓存访问后过期默认 600 秒");

    // advanced 三键
    assertEquals(0, config.threads(), "工作线程数默认 0 = 按 CPU 自动推算（上限 4）");
    assertEquals(2500, config.timeoutMillis(), "单区块包处理超时默认 2500 毫秒");
    assertEquals(2048, config.queueCapacity(), "工作队列容量默认 2048");
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
    assertEquals(2, config.blockChanges().mergeRadius(), "合并邻域半径默认 2（曼哈顿距离）");
    assertEquals(4096, config.blockChanges().maxPerPacket(), "单合并包上限默认 4096 条变更");
    assertTrue(config.blockChanges().resendOnOverflow(), "超限拆分续发默认开启");
    assertEquals(256, config.blockChanges().maxPendingEntries(), "待发缓冲上限默认 256 条");

    // palette：strict-verify（reorder 默认已在既有测试锁定为 false）
    assertFalse(config.palette().strictVerify(), "重排自检默认关闭（仅 reorder=true 时有意义）");

    // entity-culling 其余键（threads 键已彻底删除，其残留配置的兼容性见下方专项测试）
    assertTrue(config.entityCulling().raycast(), "实体射线判定默认开启");
    assertEquals(32.0D, config.entityCulling().forceVisibleDistance(), 1.0E-9D, "强制可见距离默认 32 格");
    assertEquals(BandwidthConfig.MAX_RAY_SAMPLES, config.entityCulling().raySamples(),
        "候选顶点数默认取上限 7（包围盒可见顶点最多 7 个，钳制 1..7）");

    // afk 全部键
    assertEquals(300, config.afk().seconds(), "AFK 判定默认 300 秒无操作");
    assertEquals(16.0D, config.afk().distance(), 1.0E-9D, "AFK 低价值包丢弃距离默认 16 格");
    assertTrue(config.afk().dropParticles(), "AFK 丢弃粒子包默认开启");
    assertTrue(config.afk().dropBlockBreakAnimation(), "AFK 丢弃破坏动画包默认开启");

    // latency 全部键
    assertEquals(400, config.latency().thresholdMillis(), "延迟观察阈值默认 400 毫秒");
    assertEquals(30, config.latency().sustainSeconds(), "持续超阈 30 秒才真正降视距");
    assertEquals(2, config.latency().reduceViewDistance(), "触发后降视距默认 2");
    assertEquals(4, config.latency().minViewDistance(), "视距下限默认 4");
    assertEquals(5, config.latency().checkIntervalSeconds(), "延迟采样周期默认 5 秒");

    // diagnostics
    assertEquals(60, config.diagnostics().intervalSeconds(), "周期运行摘要默认 60 秒（0 = 关闭）");
  }
}