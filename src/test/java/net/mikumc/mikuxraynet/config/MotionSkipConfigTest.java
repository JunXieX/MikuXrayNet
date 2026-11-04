package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.config.BandwidthConfig.EntityCulling.MotionSkip;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** 运动跳过子项（{@code entity-culling.motion-skip.*}）的默认值、显式值与安全钳制测试。 */
class MotionSkipConfigTest {

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
    MotionSkip skip = BandwidthConfig.from(yaml("enabled: true\n")).entityCulling().motionSkip();
    assertTrue(skip.enabled(), "默认开启（静态实体多时有明确收益）");
    assertEquals(0.2D, skip.threshold(), 1.0E-9D, "位移阈值默认 0.2 格");
    assertEquals(1, skip.maxSkipHidden(), "隐藏态最多跳 1 个周期（该显示却仍隐藏是最严重方向）");
    assertEquals(3, skip.maxSkipVisible(), "可见态可放宽到 3 个周期");
  }

  @Test
  void explicitValuesAreParsedAsIs() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          motion-skip:
            enabled: false
            threshold: 1.5
            max-skip-hidden: 0
            max-skip-visible: 5
        """));
    MotionSkip skip = config.entityCulling().motionSkip();
    assertFalse(skip.enabled());
    assertEquals(1.5D, skip.threshold(), 1.0E-9D);
    assertEquals(0, skip.maxSkipHidden());
    assertEquals(5, skip.maxSkipVisible());
    assertTrue(config.clampAdjustments().isEmpty(), "区间内的显式值不应被钳制：" + config.clampAdjustments());
  }

  @Test
  void outOfRangeValuesAreClampedAndReported() {
    BandwidthConfig config = BandwidthConfig.from(yaml("""
        entity-culling:
          motion-skip:
            threshold: 0.0
            max-skip-hidden: 99
            max-skip-visible: -1
        """));
    MotionSkip skip = config.entityCulling().motionSkip();
    assertEquals(BandwidthConfig.MIN_MOTION_THRESHOLD, skip.threshold(), 1.0E-9D,
        "阈值为 0 会让跳过永不生效，必须抬到下限");
    assertEquals(BandwidthConfig.MAX_MOTION_SKIP_CYCLES, skip.maxSkipHidden(),
        "隐藏态跳过上限必须钳到「可感知范围」内");
    assertEquals(0, skip.maxSkipVisible(), "负数按 0（不跳过）处理");
    assertFalse(config.clampAdjustments().isEmpty(), "钳制必须留痕");
  }
}
