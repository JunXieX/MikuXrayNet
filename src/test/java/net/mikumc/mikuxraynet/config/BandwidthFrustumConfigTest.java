package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 视锥剔除（{@code entity-culling.frustum}）的配置解析与安全钳制回归。
 *
 * <p>该段属于「新数值配置键」：必须有一套安全上下限并在被钳制时留痕（供加载路径一次性 WARN），
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
  void defaultsEnableFrustumSection() {
    BandwidthConfig defaults = config("enabled: true\n");

    assertTrue(defaults.entityCulling().frustum().enabled(),
        "视锥剔除默认开启（性能优先，安全由距离门兜底）");
    assertEquals(110.0D, defaults.entityCulling().frustum().fov(),
        "FOV 默认取客户端上限 110，保证拉满 FOV 也看不到被剔除的实体");
    assertEquals(24.0D, defaults.entityCulling().frustum().minDistance(),
        "距离门默认 24（有效值为 max(24, 强制可见距离)）");
  }

  @Test
  void outOfRangeValuesAreClampedAndRecorded() {
    // 过窄 / 过宽的 FOV、负数距离门都必须在安全区间内生效并留痕
    BandwidthConfig clamped = config("""
        entity-culling:
          frustum:
            fov: 5.0
            min-distance: -20.0
        """);

    assertEquals(BandwidthConfig.MIN_FRUSTUM_FOV, clamped.entityCulling().frustum().fov(),
        "低于下限的 FOV 必须被抬到安全下限 30");
    assertEquals(0.0D, clamped.entityCulling().frustum().minDistance(),
        "负数距离门必须被按 0 生效（并留痕）");
    assertTrue(clamped.clampAdjustments().stream()
            .anyMatch(entry -> entry.startsWith("entity-culling.frustum.fov=")),
        "钳制明细必须记录被改动的键：" + clamped.clampAdjustments());

    BandwidthConfig tooLarge = config("""
        entity-culling:
          frustum:
            fov: 999.0
        """);
    assertEquals(BandwidthConfig.MAX_FRUSTUM_FOV, tooLarge.entityCulling().frustum().fov());
  }

  /**
   * 钳制明细「每个键至多一条」：{@code fov} 配负值时，若串联「上限钳制 + 下限钳制」会为同一个键记两条
   * （先记「低于下限 0，按 0 生效」、再记「下限 30」），管理员会以为改了两处。单趟区间钳制只应记一条。
   */
  @Test
  void negativeFovRecordsExactlyOneAdjustmentNote() {
    BandwidthConfig clamped = config("""
        entity-culling:
          frustum:
            fov: -5.0
        """);

    assertEquals(BandwidthConfig.MIN_FRUSTUM_FOV, clamped.entityCulling().frustum().fov(),
        "负值 FOV 必须被抬到安全下限 30");
    long notes = clamped.clampAdjustments().stream()
        .filter(entry -> entry.startsWith("entity-culling.frustum.fov="))
        .count();
    assertEquals(1L, notes,
        "同一个键至多一条钳制明细（重复记账会让管理员误以为改了两处）：" + clamped.clampAdjustments());
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

    assertNotEquals(base.configHash(), changedFrustum.configHash(),
        "改动 frustum 参数必须改变配置指纹");
  }

  /**
   * 白名单里的 null 项（YAML 写作 <code>[armor_stand, ~]</code>）不得让整份带宽配置解析失败：
   * 一次手滑的空项若触发「整份回退上一份」，会连带丢掉白名单之外的全部改动。
   */
  @Test
  void nullWhitelistEntryDoesNotBreakWholeConfig() {
    BandwidthConfig parsed = config("""
        entity-packets:
          whitelist:
            - armor_stand
            - null
        """);

    assertEquals(1, parsed.entityPackets().whitelist().size(),
        "null 项必须被过滤，其余白名单照常生效：" + parsed.entityPackets().whitelist());
  }

  /**
   * 下限被静默抬升是禁止的：{@code ray-samples: 0} 必须抬到 1 并<b>留痕</b>，
   * 否则管理员在 status 里看到的生效值与文件里写的值不一致，却没有任何提示。
   */
  @Test
  void lowerBoundClampOfRaySamplesIsRecorded() {
    BandwidthConfig clamped = config("""
        entity-culling:
          ray-samples: 0
        """);

    assertEquals(1, clamped.entityCulling().raySamples(), "0 必须被抬到下限 1");
    assertTrue(clamped.clampAdjustments().stream()
            .anyMatch(entry -> entry.startsWith("entity-culling.ray-samples=")),
        "下限钳制必须留痕：" + clamped.clampAdjustments());
  }
}
