package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 配置默认值回归：锁住本轮「真机反馈」驱动的默认值变更，避免被无意改回。
 *
 * <p>断言的都是「配置项缺失时的回落值」，即内置默认；因此用最小 YAML 触发回落路径。
 * 另外有一条用例把<b>打包的 antixray.yml</b> 与这套回落值逐个对照——yml 文本与代码回落是两处独立
 * 维护的默认值，只测回落路径的话，yml 里的数字漏改不会被任何用例发现（玩家拿到的却是另一套默认）。
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
    assertEquals(AntiXrayConfig.PROXIMITY_DISTANCE_CHUNKS_DEFAULT, config.proximity().distanceChunks(),
        "显形半径默认 0 = 跟随服务器视距（server.properties 的 view-distance，运行时再与客户端自报视距取小）："
            + "显形包只在客户端已加载该区块时才生效，越界的包会被客户端直接丢弃");
    assertEquals(512, config.proximity().maxRevealsPerTick(),
        "单次显形额度默认 512（原 256；配合按视距的显形半径与放宽的视锥，候选更多，额度需相应放大）");
    assertEquals(4, config.proximity().intervalTicks(), "巡检周期收紧为 4 tick（0.2 秒一次，显形更及时）");
    assertTrue(config.proximity().raycastEnabled(), "可见性判定默认开启（隔着墙不显形）");
    assertEquals(AntiXrayConfig.FRUSTUM_MIN_DISTANCE_DEFAULT, config.proximity().frustumMinDistance(),
        1.0E-9D, "min-distance 默认 24 格（下限 16 之上；原 4 格盖不住眼前方块：门的顶部在 3 格距离就有 40°+ 仰角）");
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
    assertEquals(AntiXrayConfig.FRUSTUM_FOV_DEFAULT, invalid.proximity().frustumFov(), 1.0E-9D,
        "fov 非法值回落默认（120°）");
    assertEquals(AntiXrayConfig.FRUSTUM_MIN_DISTANCE_FLOOR,
        invalid.proximity().frustumMinDistance(), 1.0E-9D, "min-distance 负数按下限处理");
  }

  /** 把「被抬升的键明细」拼成一行，便于用 contains 断言具体键名。 */
  private static String joinedFloors(AntiXrayConfig config) {
    return String.join("、", config.floorAdjustments());
  }

  /**
   * {@code advanced.timeout-millis} 的有效下限 100ms 必须<b>留痕</b>（钳制行为本身不变）。
   *
   * <p>旧实现把下限藏在构造器的 {@code Math.max(100, ...)} 里：配置 50 实际按 100 生效，但明细为空、
   * 控制台没有任何提示——管理员会以为自己写的 50ms 生效了。现在解析期按同一区间钳制并把「配置 50、
   * 实际 100」记入 {@code floorAdjustments}，与其它下限项同口径。
   */
  @Test
  void advancedTimeoutFloorIsReportedWithoutChangingClamp() {
    AntiXrayConfig floored = AntiXrayConfig.from(yaml("advanced:\n  timeout-millis: 50\n"));
    assertEquals(AntiXrayConfig.ADVANCED_TIMEOUT_MILLIS_MIN, floored.timeoutMillis(),
        "有效值仍是 100ms（钳制行为不变）");
    assertTrue(joinedFloors(floored).contains("advanced.timeout-millis=50"),
        "必须把「配置 50、实际按 100 生效」记入明细：" + floored.floorAdjustments());

    AntiXrayConfig kept = AntiXrayConfig.from(yaml("advanced:\n  timeout-millis: 2500\n"));
    assertEquals(2500, kept.timeoutMillis(), "区间内的值原样生效");
    assertTrue(kept.floorAdjustments().isEmpty(),
        "区间内的值不得产生明细（否则每次开服都会刷一条误导性 WARN）：" + kept.floorAdjustments());
  }

  /**
   * 打包 antixray.yml 的邻近显形默认值必须与「缺键时的代码回落」<b>逐个一致</b>。
   *
   * <p><b>为什么必须钉死</b>：yml 文本与代码回落是两处独立维护的默认值——yml 里的数字写错或漏改时，
   * 只测回落路径的用例（本类其余用例）全部照绿，而玩家实际拿到的是另一套默认值。这条用例用真实解析器
   * 读打包资源，一次钉住这两处的一致性。
   */
  @Test
  void packagedAntiXrayYmlDefaultsMatchCodeFallbacks() {
    AntiXrayConfig fallback = AntiXrayConfig.from(yaml("enabled: true\n"));
    AntiXrayConfig packed = AntiXrayConfig.from(loadPackedAntiXrayYml());

    assertEquals(fallback.proximity().distanceChunks(), packed.proximity().distanceChunks(),
        "打包 yml 的 proximity.distance-chunks 必须等于代码回落值");
    assertEquals(fallback.proximity().maxRevealsPerTick(), packed.proximity().maxRevealsPerTick(),
        "打包 yml 的 proximity.max-reveals-per-tick 必须等于代码回落值");
    assertTrue(fallback.proximity().revealOnChunkSend(), "区块下发即显形默认开启（M2）");
    assertTrue(fallback.proximity().preRevealOnDig(), "开掘即预显形默认开启（M1a）");
    assertEquals(fallback.proximity().revealOnChunkSend(), packed.proximity().revealOnChunkSend(),
        "打包 yml 的 proximity.reveal-on-chunk-send 必须等于代码回落值");
    assertEquals(fallback.proximity().preRevealOnDig(), packed.proximity().preRevealOnDig(),
        "打包 yml 的 proximity.instant-reveal.pre-reveal-on-dig 必须等于代码回落值");
    assertTrue(fallback.proximity().viewChange().enabled(), "视野变化增量巡检默认开启");
    assertEquals(AntiXrayConfig.VIEW_CHANGE_BUDGET_MS_DEFAULT,
        fallback.proximity().viewChange().budgetMs(), 1.0E-9D, "视野增量默认预算 0.3 ms");
    assertEquals(AntiXrayConfig.VIEW_CHANGE_COOLDOWN_TICKS_DEFAULT,
        fallback.proximity().viewChange().cooldownTicks(), "视野增量默认冷却 2 tick");
    assertEquals(AntiXrayConfig.VIEW_CHANGE_RADIUS_DEFAULT,
        fallback.proximity().viewChange().radius(), "视野增量默认半径 24 格");
    assertEquals(fallback.proximity().viewChange().budgetMs(),
        packed.proximity().viewChange().budgetMs(), 1.0E-9D,
        "打包 yml 的 proximity.view-change.budget-ms 必须等于代码回落值");
    assertEquals(fallback.proximity().viewChange().cooldownTicks(),
        packed.proximity().viewChange().cooldownTicks(),
        "打包 yml 的 proximity.view-change.cooldown-ticks 必须等于代码回落值");
    assertEquals(fallback.proximity().viewChange().radius(), packed.proximity().viewChange().radius(),
        "打包 yml 的 proximity.view-change.radius 必须等于代码回落值");
    assertEquals(fallback.proximity().frustumFov(), packed.proximity().frustumFov(), 1.0E-9D,
        "打包 yml 的 proximity.frustum.fov 必须等于代码回落值");
    assertEquals(fallback.proximity().frustumMinDistance(), packed.proximity().frustumMinDistance(),
        1.0E-9D, "打包 yml 的 proximity.frustum.min-distance 必须等于代码回落值");
    assertEquals(fallback.proximity().raycastSamples(), packed.proximity().raycastSamples(),
        "打包 yml 的 proximity.raycast.samples 必须等于代码回落值");
    assertEquals(fallback.proximity().instantReveal(), packed.proximity().instantReveal(),
        "打包 yml 的 instant-reveal 三键必须等于代码回落值");
    assertEquals(fallback.cacheMaximumMb(), packed.cacheMaximumMb(),
        "打包 yml 的 cache.maximum-mb 必须等于代码回落值");
    assertTrue(packed.floorAdjustments().isEmpty(),
        "打包默认值不得触发安全下限抬升（否则每个服主开服都会看到一条误导性 WARN）："
            + packed.floorAdjustments());
    // 同理：打包 yml 里的布尔/枚举/权重写法必须全部被解析器理解——否则每个服主开服都会吃到一条
    // 「写法无法识别」WARN（例如把 mode 写成 all_v2、或权重被引号化）。
    assertTrue(packed.typeAdjustments().isEmpty(),
        "打包默认值不得产生「写法无法识别」明细："
            + packed.typeAdjustments() + "（检查 antixray.yml 的布尔/枚举/权重写法）");
  }

  /** 用真实解析器读随插件打包的 {@code antixray.yml}（断言默认值文本与代码回落一致）。 */
  private static YamlConfiguration loadPackedAntiXrayYml() {
    YamlConfiguration configuration = new YamlConfiguration();
    try (InputStream input = ConfigDefaultsTest.class.getResourceAsStream("/antixray.yml")) {
      assertNotNull(input, "classpath 里必须有打包的 antixray.yml（应位于 src/main/resources 下）");
      configuration.load(new InputStreamReader(input, StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new AssertionError("解析打包 antixray.yml 失败", exception);
    }
    return configuration;
  }

  /**
   * 非法 fov（&gt;360° / 非正）不再静默回落：必须与「低于下限」一样进入一次性 WARN 明细，
   * 否则管理员写了无效角度会以为它已生效（与其它钳制项口径一致）。
   */
  @Test
  void invalidFrustumFovIsReportedInFloorDetails() {
    AntiXrayConfig tooWide = AntiXrayConfig.from(yaml("proximity:\n  frustum:\n    fov: 400.0\n"));
    assertEquals(AntiXrayConfig.FRUSTUM_FOV_DEFAULT, tooWide.proximity().frustumFov(), 1.0E-9D,
        "fov>360 属非法值，回落默认（120°）");
    assertTrue(joinedFloors(tooWide).contains("proximity.frustum.fov=400.0"),
        "fov 非法值必须纳入一次性 WARN 明细（不再静默回落）：" + tooWide.floorAdjustments());

    AntiXrayConfig nonPositive = AntiXrayConfig.from(yaml("proximity:\n  frustum:\n    fov: -10.0\n"));
    assertEquals(AntiXrayConfig.FRUSTUM_FOV_DEFAULT, nonPositive.proximity().frustumFov(), 1.0E-9D);
    assertTrue(joinedFloors(nonPositive).contains("proximity.frustum.fov=-10.0"),
        "负角度同样必须留痕：" + nonPositive.floorAdjustments());
  }

  /**
   * 任务3：{@code proximity.frustum.min-distance} 的 NaN、以及 {@code proximity.distance-chunks} 的负值
   * 都不得静默回落/归零——必须与 frustumFov 口径一致，进入一次性 WARN 明细。
   */
  @Test
  void nanMinDistanceAndNegativeProximityDistanceAreReported() {
    AntiXrayConfig nan = AntiXrayConfig.from(yaml("proximity:\n  frustum:\n    min-distance: .nan\n"));
    assertEquals(AntiXrayConfig.FRUSTUM_MIN_DISTANCE_DEFAULT, nan.proximity().frustumMinDistance(),
        1.0E-9D, "NaN 的 min-distance 必须回落默认（24 格）");
    assertTrue(joinedFloors(nan).contains("proximity.frustum.min-distance=NaN"),
        "NaN 的 min-distance 必须纳入一次性 WARN 明细（不再静默）：" + nan.floorAdjustments());

    AntiXrayConfig negative = AntiXrayConfig.from(yaml("proximity:\n  distance-chunks: -5\n"));
    assertEquals(AntiXrayConfig.PROXIMITY_DISTANCE_CHUNKS_DEFAULT, negative.proximity().distanceChunks(),
        "负值的显形半径必须回到默认（0 = 跟随服务器视距）");
    assertTrue(joinedFloors(negative).contains("proximity.distance-chunks=-5"),
        "负值的 proximity.distance-chunks 必须留痕（不再静默归 0）：" + negative.floorAdjustments());
  }

  /**
   * 任务3：bandwidth 侧浮点钳制的<b>有限负值</b>不得再静默归 0（旧实现 {@code Math.max(0, v)} 无记录）。
   *
   * <p>同时覆盖 {@code entity-culling.force-visible-distance} 的<b>硬下限</b>：该键承载「近身实体一律
   * 可见」这条安全策略，配 0 或负数必须被抬到 {@link BandwidthConfig#MIN_FORCE_VISIBLE_DISTANCE}
   * （而不是静默关掉兜底），并进入钳制明细。
   */
  @Test
  void negativeFiniteFloatClampInBandwidthIsReported() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          force-visible-distance: -3.0
        """));
    assertEquals(BandwidthConfig.MIN_FORCE_VISIBLE_DISTANCE,
        config.entityCulling().forceVisibleDistance(), 1.0E-9D,
        "负值必须被抬到硬下限（不得静默关掉近身可见兜底）");
    assertTrue(String.join("、", config.clampAdjustments())
            .contains("entity-culling.force-visible-distance=-3.0"),
        "有限负值必须进入钳制明细（不再静默归 0）：" + config.clampAdjustments());

    // 低于下限（0 与下限以下的正值）同样被抬到硬下限，且各只有一条明细
    BandwidthConfig zero = BandwidthConfig.from(yaml("""
        entity-culling:
          force-visible-distance: 0.0
        """));
    assertEquals(BandwidthConfig.MIN_FORCE_VISIBLE_DISTANCE,
        zero.entityCulling().forceVisibleDistance(), 1.0E-9D,
        "0 会关掉近身可见兜底，必须被抬到硬下限");
    assertEquals(1, zero.clampAdjustments().size(),
        "单趟钳制必须保证每个键至多一条明细：" + zero.clampAdjustments());
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
    // 更新③：移除 occlusion.fluid-cover（岩浆改为恒判视觉遮挡，不再是开关）→ 指纹随之变化。
    // 更新④：world-blacklist 迁出为独立配置 blacklist.yml（不再参与 AntiXrayConfig 指纹）→ 指纹随之变化。
    // 以上改动都会使期望值变化、现存磁盘缓存一次性失效重建（属正常）。
    assertEquals(-519526893, config.configHash(),
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

  /** 新增配置键的默认值：use-block-below 关（行为不变）、事件显形开且限额保守、抽样 1/20。 */
  @Test
  void newFeatureDefaultsAreConservative() {
    AntiXrayConfig config = AntiXrayConfig.from(yaml("enabled: true\n"));
    assertFalse(config.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).useBlockBelow(),
        "use-block-below 默认关闭（行为不变）");
    assertTrue(config.proximity().instantReveal().enabled(), "事件驱动即时显形默认开启");
    assertEquals(4, config.proximity().instantReveal().radius(), "事件显形曼哈顿半径默认 4（= 半个区块）");
    assertEquals(16, config.proximity().instantReveal().maxPerTick(), "事件显形每玩家每 tick 限额默认 16");
    assertEquals(20, config.proximity().overRevealSampling(), "过度显形抽样默认 1/20");
    assertFalse(config.proximity().instantReveal().enabled()
        && config.proximity().instantReveal().maxPerTick() <= 0, "开启时限额必须为正");
  }

  /** use-block-below / 事件显形等配置可显式解析，非法/极端值被钳制。 */
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
    AntiXrayConfig invalid =
        AntiXrayConfig.from(yaml("dimensions:\n  normal:\n    mode: nonsense\n"));
    assertEquals(AntiXrayConfig.ObfuscationMode.ALL,
        invalid.dimensionEffective(AntiXrayConfig.Dimension.NORMAL).mode(),
        "非法取值一律回落为最安全的 all");
    assertTrue(String.join("、", invalid.typeAdjustments()).contains("dimensions.normal.mode="),
        "非法枚举值必须留痕（否则写错的 mode 会被静默按 all 生效）：" + invalid.typeAdjustments());
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
   * 实体剔除复检周期默认 10 tick（性能优先）：遮挡/可见切换的延迟由「已隐藏实体每周期全量复检」
   * 兜底，玩家几乎察觉不到。
   */
  @Test
  void entityCullingUpdateIntervalDefaultsToTen() {
    BandwidthConfig config = BandwidthConfig.from(yaml("enabled: true\n"));

    assertEquals(10, config.entityCulling().updateIntervalTicks(),
        "复检周期默认 10 tick（性能优先）");
  }

  /**
   * 旧配置里残留已删除的 {@code entity-culling.recheck-budget} / {@code entity-culling.threads} 键时，
   * 解析必须<b>静默忽略</b>这些未知键（不报警、不影响同段其它键、不影响默认值）：两者都是同步射线
   * 时代的档位（条数封顶 / 线程数），遮挡判定改为异步位图后已彻底删除。
   */
  @Test
  void removedEntityCullingKeysAreSilentlyIgnored() {
    BandwidthConfig config = BandwidthConfig.from(yaml(
        "entity-culling:\n  recheck-budget: 12\n  threads: 4\n  ray-samples: 6\n"));

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
    assertTrue(config.antiXrayAppliesTo("any_world"),
        "总开关开启且世界黑名单为空时对任意世界生效（世界白名单已随按维度分段重构移除）");
    // 世界黑名单已迁出为独立配置 blacklist.yml（见 WorldBlacklistTest）；此处只验证默认空名单下不豁免
    assertTrue(config.antiXrayAppliesTo("spawn"), "默认空黑名单下所有世界照常反矿透");
    // 维度启用：主世界/地狱默认启用，末地默认关闭（末地无矿物）
    assertTrue(config.dimensionEnabled(AntiXrayConfig.Dimension.NORMAL), "主世界默认启用");
    assertTrue(config.dimensionEnabled(AntiXrayConfig.Dimension.NETHER), "地狱默认启用");
    assertFalse(config.dimensionEnabled(AntiXrayConfig.Dimension.THE_END), "末地默认关闭");
    assertTrue(config.dimensionsMissing(),
        "空配置缺少 dimensions 段 → 用内置默认运行并会一次性 WARN（绝不让保护静默失效）");
    assertFalse(config.layerObfuscation(), "层状伪装默认关闭（逐方块独立随机，更不易被模式识别）");
    assertTrue(config.removeBlockEntities(), "方块实体剔除默认开启（防幽灵刷怪笼/箱子）");

    // occlusion 两键：覆盖表默认为空（内置规则兜底，由用户按需填写）
    assertTrue(config.occlusion().extraOccluding().isEmpty(), "extra-occluding 默认为空");
    assertTrue(config.occlusion().extraNonOccluding().isEmpty(), "extra-non-occluding 默认为空");

    // neighbors 三键
    assertTrue(config.neighbors().enabled(), "邻块贴边快照默认开启");
    assertEquals(AntiXrayConfig.MissingPolicy.HIDE, config.neighbors().missingPolicy(),
        "邻块缺失默认按遮挡处理（hide，宁可多伪装也不留边界透视口子）");
    assertEquals(2048, config.neighbors().cacheMaximumSize(),
        "邻块快照缓存默认 2048 条（快照按位打包，每条约 3 KB → 共约 6 MB）");

    // proximity 其余键
    assertTrue(config.proximity().enabled(), "邻近显形默认开启");
    assertTrue(config.proximity().frustumEnabled(), "视锥剔除默认开启");
    assertEquals(AntiXrayConfig.FRUSTUM_FOV_DEFAULT, config.proximity().frustumFov(), 1.0E-9D,
        "视锥竖直全角默认 120°（下限 110° 之上；配窄会让玩家看得见的方块保持伪装）");
    assertTrue(config.floorAdjustments().isEmpty(), "默认值不触发安全下限抬升（不产生误导性 WARN）");
    assertEquals(5, config.proximity().raycastSamples(),
        "候选点数默认 5（= 上限；原生射线改造后语义为「每方块最多尝试的候选点数」，钳制 1..5）");
    assertTrue(config.proximity().batchRevealSends(),
        "显形包批量合并默认开启（Paper 原生多方块变更包，关掉即回退为逐坐标单包）");

    // disk-cache 全部 14 键
    assertTrue(config.diskCache().enabled(), "磁盘缓存默认开启");
    assertEquals(20000, config.diskCache().maxEntries(), "条目总数上限默认 20000");
    assertEquals(32, config.diskCache().maxFileSizeMb(),
        "单区域文件上限默认 32 MB：16 MB 装不下一个区域文件的 1024 个区块（单条约 22~26 KB），"
            + "文件满了没有可回收垃圾时该区域剩余区块将永久无法进缓存");
    assertEquals(7 * 24 * 60 * 60, config.diskCache().expireSeconds(),
        "条目过期默认 7 天（原 1800 秒：从写入时刻算起，配得比两次启动间隔还短 → 重启后条目全过期、"
            + "命中率恒为 0；实机 70 分钟后重启命中 0／未命中 2096）。内容新鲜度由原始字节指纹判定，"
            + "限制磁盘占用应调 max-entries / max-file-size-mb");
    assertEquals(32, config.diskCache().bucketCacheSize(),
        "bucket 缓存默认 32（每文件约 4~8 MB；常驻文件数还受 idle-close-seconds 约束）");
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

    // cache 三键（改写结果的内存缓存：条目数 + MB 上限）
    // 注意：默认 512 MB 的 MB 上限会先封顶（30~50 KB/条约 1.0~1.7 万条），40960 这个条目上限在默认
    // 配置下实际不可达；这里仍断言 40960，锁的是「配置值」而不是「实际驻留条数」（后者由两者共同决定）。
    assertEquals(40960, config.cacheMaximumSize(),
        "改写缓存条目上限默认 40960（与 maximum-mb 共同封顶；默认实际由 512 MB 先到顶）");
    assertEquals(600, config.cacheExpireAfterAccessSeconds(), "改写缓存访问后过期默认 600 秒");
    assertEquals(AntiXrayConfig.CACHE_MAXIMUM_MB_DEFAULT, config.cacheMaximumMb(),
        "改写缓存内存上限默认 512 MB（配置单位 MB；条目上限管不住「单条负载偏大」）");
    assertEquals(512L * 1024L * 1024L, config.cacheMaximumBytes(),
        "字节形式由 MB 换算得出（RewriteCache 按字节记账）");

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
          distance-chunks: 4096
          raycast:
            samples: 8
        neighbors:
          cache-maximum-size: 1000000
        cache:
          maximum-size: 409600
        """));

    assertEquals(AntiXrayConfig.PROXIMITY_DISTANCE_CHUNKS_MAX, config.proximity().distanceChunks(),
        "邻近显形半径超过上限必须钳制到 32 区块（Minecraft 允许的最大视距；实际还会被玩家视距钳制）");
    assertEquals(AntiXrayConfig.PROXIMITY_RAY_SAMPLES_MAX, config.proximity().raycastSamples(),
        "候选点数超过有效上限必须钳制到 5（暴露面上至多 5 个候选点）");
    assertEquals(AntiXrayConfig.NEIGHBORS_CACHE_MAXIMUM_MAX, config.neighbors().cacheMaximumSize(),
        "邻块缓存条目超过上限必须钳制到 16384");
    assertEquals(AntiXrayConfig.CACHE_MAXIMUM_SIZE_MAX, config.cacheMaximumSize(),
        "改写缓存条目超过上限必须钳制到 65536");

    String details = String.join("、", config.ceilingAdjustments());
    assertTrue(details.contains("proximity.distance-chunks=4096"), details);
    assertTrue(details.contains("neighbors.cache-maximum-size=1000000"), details);
    assertTrue(details.contains("cache.maximum-size=409600"), details);

    // 上限以内的显式值原样生效、不产生明细
    AntiXrayConfig inRange = AntiXrayConfig.from(yaml("""
        proximity:
          distance-chunks: 32
          raycast:
            samples: 5
        neighbors:
          cache-maximum-size: 4096
        cache:
          maximum-size: 50000
        """));
    assertEquals(32, inRange.proximity().distanceChunks(), "32 是有效上限，应原样生效");
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
   * {@code cache.maximum-mb}：两端留痕的「按 MB」硬上限。
   *
   * <p>要点：① 缺键时默认 512 MB 落在区间内、不产生明细；② 超上限（手滑多打一位数）按上限生效并留痕；
   * ③ 低于下限（会把缓存配成事实关闭、反而更耗 CPU）按下限抬升并留痕；④ 4096 这个合法边界必须能原样
   * 表达；⑤ 内部按字节记账的访问器必须是 MB × 1 MiB（RewriteCache 用字节）。
   */
  @Test
  void cacheMaximumMbClampsAtBothEndsAndReports() {
    AntiXrayConfig defaults = AntiXrayConfig.from(yaml("enabled: true\n"));
    assertEquals(AntiXrayConfig.CACHE_MAXIMUM_MB_DEFAULT, defaults.cacheMaximumMb(),
        "缺键时按 MB 上限默认 512");
    assertTrue(defaults.floorAdjustments().isEmpty(), defaults.floorAdjustments().toString());
    assertTrue(defaults.ceilingAdjustments().isEmpty(), defaults.ceilingAdjustments().toString());

    AntiXrayConfig inRange = AntiXrayConfig.from(yaml("""
        cache:
          maximum-mb: 1024
        """));
    assertEquals(1024, inRange.cacheMaximumMb(), "区间内（1024 MB）原样生效");
    assertEquals(1024L * 1024L * 1024L, inRange.cacheMaximumBytes(), "字节形式 = MB × 1 MiB");
    assertTrue(inRange.floorAdjustments().isEmpty(), inRange.floorAdjustments().toString());
    assertTrue(inRange.ceilingAdjustments().isEmpty(), inRange.ceilingAdjustments().toString());

    AntiXrayConfig tooBig = AntiXrayConfig.from(yaml("""
        cache:
          maximum-mb: 8192
        """));
    assertEquals(AntiXrayConfig.CACHE_MAXIMUM_MB_MAX, tooBig.cacheMaximumMb(),
        "超过 4096 MB 上限按上限生效（拦住手滑多打一位数）");
    assertTrue(String.join("、", tooBig.ceilingAdjustments()).contains("cache.maximum-mb=8192"),
        tooBig.ceilingAdjustments().toString());

    AntiXrayConfig tooSmall = AntiXrayConfig.from(yaml("""
        cache:
          maximum-mb: 1
        """));
    assertEquals(AntiXrayConfig.CACHE_MAXIMUM_MB_MIN, tooSmall.cacheMaximumMb(),
        "低于 64 MB 下限按下限抬升（否则缓存事实关闭，每个区块都重写、更耗 CPU）");
    assertTrue(String.join("、", tooSmall.floorAdjustments()).contains("cache.maximum-mb=1"),
        tooSmall.floorAdjustments().toString());

    AntiXrayConfig atMax = AntiXrayConfig.from(yaml("""
        cache:
          maximum-mb: 4096
        """));
    assertEquals(AntiXrayConfig.CACHE_MAXIMUM_MB_MAX, atMax.cacheMaximumMb(),
        "4096 MB 是合法边界，必须能原样表达");
    assertTrue(atMax.ceilingAdjustments().isEmpty(), atMax.ceilingAdjustments().toString());
  }

  /**
   * {@code proximity.view-change.*} 的钳制：预算两端钳制并留痕，冷却/半径越界钳制并留痕。
   *
   * <p>这条路径成本直接来自玩家行为，配置错误（例如预算填 100 ms）会把事件路径变成主线程负担，
   * 因此两端都必须显式钳制 + 一次性 WARN，不允许静默生效。
   */
  @Test
  void viewChangeParametersAreClampedAndReported() {
    AntiXrayConfig low = AntiXrayConfig.from(yaml("""
        proximity:
          view-change:
            budget-ms: 0.0
            cooldown-ticks: 0
            radius: 2
        """));
    assertEquals(AntiXrayConfig.VIEW_CHANGE_BUDGET_MS_FLOOR, low.proximity().viewChange().budgetMs(),
        1.0E-9D, "低于下限的预算必须抬升到下限");
    assertEquals(AntiXrayConfig.VIEW_CHANGE_COOLDOWN_TICKS_FLOOR,
        low.proximity().viewChange().cooldownTicks(), "冷却 0 必须抬升到下限（否则旋转抖动会打满预算）");
    assertEquals(AntiXrayConfig.VIEW_CHANGE_RADIUS_FLOOR, low.proximity().viewChange().radius(),
        "半径低于下限必须抬升");
    assertTrue(String.join("、", low.floorAdjustments()).contains("proximity.view-change.budget-ms=0.0"),
        "预算抬升必须留痕：" + low.floorAdjustments());
    assertTrue(String.join("、", low.floorAdjustments()).contains("proximity.view-change.cooldown-ticks=0"),
        "冷却抬升必须留痕：" + low.floorAdjustments());
    assertTrue(String.join("、", low.floorAdjustments()).contains("proximity.view-change.radius=2"),
        "半径抬升必须留痕：" + low.floorAdjustments());

    AntiXrayConfig high = AntiXrayConfig.from(yaml("""
        proximity:
          view-change:
            budget-ms: 100.0
            cooldown-ticks: 999
            radius: 999
        """));
    assertEquals(AntiXrayConfig.VIEW_CHANGE_BUDGET_MS_MAX, high.proximity().viewChange().budgetMs(),
        1.0E-9D, "超过上限的预算必须钳到上限");
    assertEquals(AntiXrayConfig.VIEW_CHANGE_COOLDOWN_TICKS_MAX,
        high.proximity().viewChange().cooldownTicks());
    assertEquals(AntiXrayConfig.VIEW_CHANGE_RADIUS_MAX, high.proximity().viewChange().radius());
    String details = String.join("、", high.ceilingAdjustments());
    assertTrue(details.contains("proximity.view-change.budget-ms=100.0"), details);
    assertTrue(details.contains("proximity.view-change.cooldown-ticks=999"), details);
    assertTrue(details.contains("proximity.view-change.radius=999"), details);
  }

  /**
   * {@code proximity.distance-chunks} 是整型键：非整数写法（NaN / ±Inf / 浮点 / 带引号的非数字）
   * 必须回落默认 0（= 跟随服务器视距）<b>并留痕</b>，不得静默取默认值。
   *
   * <p>与旧的 {@code proximity.distance}（浮点、毫秒级距离）不同，这里用 {@code lenientLongValue} 解析：
   * 类型不符时写入 {@code typeAdjustments}，从而保证「写了却没生效」仍能在启动日志里看到。
   */
  @Test
  void proximityDistanceChunksNonIntegerFallsBackToDefaultAndReports() {
    for (String raw : new String[] {".nan", ".inf", "1.5", "\"abc\""}) {
      AntiXrayConfig config = AntiXrayConfig.from(yaml("proximity:\n  distance-chunks: " + raw + "\n"));
      assertEquals(AntiXrayConfig.PROXIMITY_DISTANCE_CHUNKS_DEFAULT, config.proximity().distanceChunks(),
          "非整数写法必须回落默认 0（跟随服务器视距）：" + raw);
      assertTrue(String.join("、", config.typeAdjustments()).contains("proximity.distance-chunks"),
          "非整数写法必须留痕（不得静默取默认值）：" + raw + " → " + config.typeAdjustments());
    }
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
    assertEquals(0, config.diagnostics().intervalSeconds(), "周期运行摘要默认关闭（0 = 不输出）");
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
        """));
    assertEquals(3, inRange.blockChanges().mergeRadius(), "上限内的半径原样生效");
    assertEquals(8192, inRange.blockChanges().maxPerPacket());
    assertEquals(50, inRange.blockChanges().mergeWindowMillis());
    assertEquals(3, inRange.latency().reduceViewDistance(), "上限内的降视距原样生效（新上限 4）");
    assertEquals(6, inRange.latency().minViewDistance(), "下限之上的视距下限原样生效");
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
    assertEquals(16.0D, BandwidthConfig.MIN_FORCE_VISIBLE_DISTANCE, 1.0E-9D);
    assertEquals(40, BandwidthConfig.MAX_UPDATE_INTERVAL_TICKS);
    assertEquals(86400, BandwidthConfig.MAX_AFK_SECONDS);
    assertEquals(1024.0D, BandwidthConfig.MAX_AFK_DISTANCE, 1.0E-9D);
    assertEquals(4.0D, BandwidthConfig.MIN_AFK_DISTANCE, 1.0E-9D);
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
    assertTrue(defaults.entityCulling().forceVisibleDistance() >= BandwidthConfig.MIN_FORCE_VISIBLE_DISTANCE,
        "默认值必须不低于硬下限，否则默认配置自身会被抬升并打出误导性 WARN");
    assertTrue(defaults.entityCulling().updateIntervalTicks() <= BandwidthConfig.MAX_UPDATE_INTERVAL_TICKS);
    assertTrue(defaults.afk().distance() <= BandwidthConfig.MAX_AFK_DISTANCE);
    assertTrue(defaults.afk().distance() >= BandwidthConfig.MIN_AFK_DISTANCE,
        "默认值必须不低于硬下限，否则默认配置自身会被抬升并打出误导性 WARN");
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
   * {@code afk.distance} 的新安全下限：0/负数会让 AFK 玩家连脚下的粒子与破坏动画都被丢弃
   * （判定式 {@code 距离² ≤ distance²} 恒成立），属体验崩塌而非调优，故抬到 4 并留痕。
   */
  @Test
  void afkDistanceBelowFloorIsRaisedAndReported() {
    for (String raw : new String[] {"0.0", "-8.0"}) {
      BandwidthConfig raised =
          BandwidthConfig.from(yaml("afk:\n  distance: " + raw + "\n"));
      assertEquals(BandwidthConfig.MIN_AFK_DISTANCE, raised.afk().distance(), 1.0E-9D,
          "afk.distance=" + raw + " 必须被抬到下限 " + BandwidthConfig.MIN_AFK_DISTANCE);
      assertTrue(String.join("、", raised.clampAdjustments()).contains("afk.distance=" + raw),
          "抬升必须留下明细供加载时一次性 WARN：" + raised.clampAdjustments());
    }

    BandwidthConfig atFloor = BandwidthConfig.from(yaml("afk:\n  distance: 4.0\n"));
    assertEquals(4.0D, atFloor.afk().distance(), 1.0E-9D, "恰好等于下限的值原样生效");
    assertTrue(atFloor.clampAdjustments().isEmpty(), "取值在下限处不得产生明细");
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
   * 带宽配置指纹：<b>参与字段集合与顺序</b>稳定（结构契约），且随影响行为的带宽项变化——
   * 热重载据此判断「带宽侧配置是否变化」，只改 bandwidth.yml 时提示语才不会误导。
   *
   * <p><b>已修正的旧缺口</b>：原用例只做「同进程两次解析相等 + 改两项可变」，既没钉住「哪些字段参与、
   * 以什么顺序参与」，也完全无法发现指纹里混入 {@code identityHashCode} 之类「同进程稳定、跨进程随机」
   * 的来源（那正是跨进程稳定要防的事）。现在改为结构断言：用公开 record 访问器按文档顺序重算
   * {@code Objects.hash}，并与 {@code configHash()} 逐位比对。任何「增删参与字段 / 调整顺序 / 换成
   * 不稳定来源」都会在此判红。
   *
   * <p><b>仍存在的已知缺口（不在本批修）</b>：真正的「跨进程 = 同一值」只能靠固定字面量证明；该字面量
   * 需由 CI 采集一次后钉入（本机禁止跑构建，无法采集，也绝不伪造数字）。上面的结构断言已覆盖「公式被
   * 改坏」，但若某个子配置的 {@code hashCode} 实现本身在不同 JVM 上不同（例如依赖默认 Locale / 平台
   * 字符集），结构断言会与生产同值、发现不了——这一层必须等 CI 字面量钉入后才能守住。
   */
  @Test
  void bandwidthConfigHashIsStableAndSensitive() {
    BandwidthConfig base = BandwidthConfig.from(yaml("enabled: true\n"));

    // ⑤ 跨进程字面量（由 CI 采集一次后钉入）：同一份 bandwidth.yml 在任何 JVM / 区域设置下
    //    都必须得到同一值。若参与哈希的字段换成「枚举 identity hash」或依赖默认 Locale 的字符串，
    //    这一行会在别的机器/别的 JDK 上直接变红——这正是结构断言（②）发现不了的那一层。
    assertEquals(403537882, base.configHash(),
        "带宽配置指纹必须是跨进程稳定的字面量（本值由 CI 采集；若不同环境不同值，说明参与哈希的字段不稳定）");

    // ① 同进程重复解析稳定（原有断言保留：先挡住「每次解析都变」这种最粗糙的回归）
    assertEquals(base.configHash(), BandwidthConfig.from(yaml("enabled: true\n")).configHash(),
        "同一份带宽配置重复解析必须得到同一指纹");

    // ② 结构契约：参与指纹的字段集合与顺序 = 8 个 record 组件按文档顺序的 Objects.hash。
    //    故意在测试里重抄一遍顺序（而不是从生产反推），这样生产侧任何增删/换序都会与这里不等。
    assertEquals(Objects.hash(base.enabled(), base.entityPackets(), base.blockChanges(),
            base.palette(), base.entityCulling(), base.afk(), base.latency(), base.diagnostics()),
        base.configHash(),
        "configHash 必须等价于「按文档顺序对 enabled + 7 个子配置求 Objects.hash」"
            + "（参与字段集合/顺序即跨进程稳定的结构契约）");

    // ③ 敏感性：影响行为的带宽项变化必须改变指纹
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("afk:\n  seconds: 600\n")).configHash(),
        "影响行为的带宽项变化必须改变指纹");
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("block-changes:\n  merge-radius: 3\n")).configHash(),
        "每个带宽子模块的取值都应参与指纹");

    // ④ 逐个子配置「确实参与」：改一个该模块独有的键，指纹必须变（与结构断言互为冗余，
    //    在有人把某模块从 Objects.hash 里删掉时给出更直观的失败信息）
    assertNotEquals(base.configHash(), BandwidthConfig.from(yaml("enabled: false\n")).configHash(),
        "总开关 enabled 必须参与指纹");
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("entity-packets:\n  enabled: false\n")).configHash(),
        "entity-packets 子配置必须参与指纹");
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("palette:\n  enabled: false\n")).configHash(),
        "palette 子配置必须参与指纹");
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("entity-culling:\n  raycast: false\n")).configHash(),
        "entity-culling 子配置必须参与指纹");
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("latency:\n  enabled: false\n")).configHash(),
        "latency 子配置必须参与指纹");
    assertNotEquals(base.configHash(),
        BandwidthConfig.from(yaml("diagnostics:\n  interval-seconds: 60\n")).configHash(),
        "diagnostics 子配置必须参与指纹");
  }
}