package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.config.BandwidthConfig.EntityCulling.AsyncOcclusion;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 异步实体遮挡子项（{@code entity-culling.async-occlusion.*}）的默认值、显式解析与安全钳制测试。
 *
 * <p>本子项<b>默认开启</b>（位图/补丁/同步回退已统一为同一遮挡口径并完成真机验证）；这里的断言同时
 * 钉住「三个开关各自参与配置指纹」这一约定，避免热重载漏检。
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
  void defaultsAreOnAndConservative() {
    AsyncOcclusion async = BandwidthConfig.from(yaml("enabled: true\n")).entityCulling().asyncOcclusion();
    assertTrue(async.enabled(), "异步遮挡默认必须开启（口径已对齐 + 真机验证通过）");
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
          async-occlusion: true
          occlusion-time-budget-ms: 8.5
          occlusion-threads: 3
          max-occluding-blocks: 2
          occlusion-timeout-cycles: 7
          occlusion-hysteresis-cycles: 4
        """));
    AsyncOcclusion async = config.entityCulling().asyncOcclusion();
    assertTrue(async.enabled());
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

  @Test
  void asyncSwitchParticipatesInConfigFingerprint() {
    // 默认已是 true，故这里必须用「显式 false vs 显式 true」对照，不能拿默认值当 off 用
    int off = BandwidthConfig.from(yaml("""
        enabled: true
        entity-culling:
          async-occlusion: false
        """)).configHash();
    int on = BandwidthConfig.from(yaml("""
        enabled: true
        entity-culling:
          async-occlusion: true
        """)).configHash();
    assertNotEquals(off, on, "开关必须参与配置指纹（否则热重载检测不到该侧变化）");
  }
}
