package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.config.BandwidthConfig.EntityCulling.AsyncOcclusion;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 遮挡判定子项（{@code entity-culling.occlusion-*}）的默认值与安全钳制测试。
 *
 * <p>遮挡判定恒走异步位图（原生命中射线已移除），因此本子项<b>没有开关</b>，只剩调优键。
 */
class AsyncOcclusionConfigTest {

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return configuration;
  }

  @Test
  void defaultsAreConservative() {
    AsyncOcclusion async = BandwidthConfig.from(yaml("enabled: true\n")).entityCulling().asyncOcclusion();
    assertEquals(2.0D, async.timeBudgetMillis(), 1.0E-9D, "时间预算默认 2.0ms");
    assertEquals(1, async.threads(), "专用池默认单线程");
    assertEquals(0, async.maxOccludingBlocks(), "最大穿越遮挡数默认 0（最严格）");
    assertEquals(3, async.timeoutCycles(), "结果超时丢弃默认 3 个周期");
    assertEquals(1, async.hysteresisCycles(), "防抖默认 1 个周期");
  }

  @Test
  void explicitValuesAreParsedAsIs() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          occlusion-time-budget-ms: 8.5
          occlusion-threads: 3
          max-occluding-blocks: 2
          occlusion-timeout-cycles: 7
          occlusion-hysteresis-cycles: 4
        """));
    AsyncOcclusion async = config.entityCulling().asyncOcclusion();
    assertEquals(8.5D, async.timeBudgetMillis(), 1.0E-9D);
    assertEquals(3, async.threads());
    assertEquals(2, async.maxOccludingBlocks());
    assertEquals(7, async.timeoutCycles());
    assertEquals(4, async.hysteresisCycles());
    assertTrue(config.clampAdjustments().isEmpty(), "区间内的显式值不应被钳制：" + config.clampAdjustments());
  }

  @Test
  void belowFloorValuesAreRaisedAndReported() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          occlusion-time-budget-ms: 0.01
          occlusion-threads: 0
          max-occluding-blocks: -5
          occlusion-timeout-cycles: 0
          occlusion-hysteresis-cycles: -1
        """));
    AsyncOcclusion async = config.entityCulling().asyncOcclusion();
    assertEquals(BandwidthConfig.MIN_OCCLUSION_TIME_BUDGET_MS, async.timeBudgetMillis(), 1.0E-9D);
    assertEquals(BandwidthConfig.MIN_OCCLUSION_THREADS, async.threads());
    assertEquals(0, async.maxOccludingBlocks(), "负值按 0 生效（下限）");
    assertEquals(BandwidthConfig.MIN_OCCLUSION_TIMEOUT_CYCLES, async.timeoutCycles());
    assertEquals(0, async.hysteresisCycles(), "负值按 0 生效（不防抖下限）");
    assertFalse(config.clampAdjustments().isEmpty(), "低于下限必须留下明细（供一次性 WARN）");
  }

  @Test
  void aboveCeilingValuesAreCappedAndReported() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          occlusion-time-budget-ms: 999.0
          occlusion-threads: 99
          max-occluding-blocks: 99
          occlusion-timeout-cycles: 99
          occlusion-hysteresis-cycles: 99
        """));
    AsyncOcclusion async = config.entityCulling().asyncOcclusion();
    assertEquals(BandwidthConfig.MAX_OCCLUSION_TIME_BUDGET_MS, async.timeBudgetMillis(), 1.0E-9D);
    assertEquals(BandwidthConfig.MAX_OCCLUSION_THREADS, async.threads());
    assertEquals(BandwidthConfig.MAX_OCCLUDING_BLOCKS_LIMIT, async.maxOccludingBlocks());
    assertEquals(BandwidthConfig.MAX_OCCLUSION_TIMEOUT_CYCLES, async.timeoutCycles());
    assertEquals(BandwidthConfig.MAX_OCCLUSION_HYSTERESIS_CYCLES, async.hysteresisCycles());
    assertEquals(5, config.clampAdjustments().size(), "五个越界键各记一条明细");
  }

  /** 已移除的 {@code entity-culling.recheck-budget} 不再被解析（它是同步射线时代的条数封顶）。 */
  @Test
  void removedRecheckBudgetIsIgnored() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          recheck-budget: 12
        """));
    // 不抛异常、且不影响任何其它键（配置项已废弃，静默忽略）
    assertEquals(2.0D, config.entityCulling().asyncOcclusion().timeBudgetMillis(), 1.0E-9D);
  }
}
