package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 视锥剔除（{@code entity-culling.frustum}）与元数据剔除（{@code entity-metadata}）的配置解析与安全钳制回归。
 *
 * <p>两个新段都属于「新数值配置键」：必须有一套安全上下限并在被钳制时留痕（供加载路径一次性 WARN），
 * 且必须参与配置指纹（否则改参数后磁盘缓存/热重载判定会失灵）。
 */
class BandwidthFrustumConfigTest {

  private static BandwidthConfig config(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return BandwidthConfig.from(configuration);
  }

  @Test
  void defaultsEnableBothNewSections() {
    BandwidthConfig defaults = config("enabled: true\n");

    assertTrue(defaults.entityCulling().frustum().enabled(),
        "视锥剔除默认开启（性能优先，安全由距离门兜底）");
    assertEquals(110.0D, defaults.entityCulling().frustum().fov(),
        "FOV 默认取客户端上限 110，保证拉满 FOV 也看不到被剔除的实体");
    assertEquals(24.0D, defaults.entityCulling().frustum().minDistance(),
        "距离门默认 24（有效值为 max(24, 强制可见距离)）");
    assertTrue(defaults.entityMetadata().enabled(), "元数据剔除默认开启");
    assertEquals(256, defaults.entityMetadata().maxTrackedPerPlayer(), "每玩家缓存默认 256 个实体");
  }

  @Test
  void outOfRangeValuesAreClampedAndRecorded() {
    // 过窄 / 过宽的 FOV、负数距离门、越界的缓存上限都必须在安全区间内生效并留痕
    BandwidthConfig clamped = config("""
        entity-culling:
          frustum:
            fov: 5.0
            min-distance: -20.0
        entity-metadata:
          max-tracked-per-player: 0
        """);

    assertEquals(BandwidthConfig.MIN_FRUSTUM_FOV, clamped.entityCulling().frustum().fov(),
        "低于下限的 FOV 必须被抬到安全下限 30");
    assertEquals(0.0D, clamped.entityCulling().frustum().minDistance(),
        "负数距离门必须被按 0 生效（并留痕）");
    assertEquals(1, clamped.entityMetadata().maxTrackedPerPlayer(),
        "缓存上限至少为 1（0 会让缓存永久不可用）");
    assertTrue(clamped.clampAdjustments().stream()
            .anyMatch(entry -> entry.startsWith("entity-culling.frustum.fov=")),
        "钳制明细必须记录被改动的键：" + clamped.clampAdjustments());

    BandwidthConfig tooLarge = config("""
        entity-culling:
          frustum:
            fov: 999.0
        entity-metadata:
          max-tracked-per-player: 999999
        """);
    assertEquals(BandwidthConfig.MAX_FRUSTUM_FOV, tooLarge.entityCulling().frustum().fov());
    assertEquals(BandwidthConfig.MAX_METADATA_TRACKED_PER_PLAYER,
        tooLarge.entityMetadata().maxTrackedPerPlayer());
  }

  /** 新增配置键必须参与配置指纹：否则改 frustum 参数后热重载与磁盘缓存判定会失灵。 */
  @Test
  void newKeysParticipateInConfigFingerprint() {
    BandwidthConfig base = config("enabled: true\n");
    BandwidthConfig changedFrustum = config("""
        entity-culling:
          frustum:
            fov: 60.0
        """);
    BandwidthConfig changedMetadata = config("""
        entity-metadata:
          max-tracked-per-player: 512
        """);

    assertNotEquals(base.configHash(), changedFrustum.configHash(),
        "改动 frustum 参数必须改变配置指纹");
    assertNotEquals(base.configHash(), changedMetadata.configHash(),
        "改动 entity-metadata 参数必须改变配置指纹");
  }
}
