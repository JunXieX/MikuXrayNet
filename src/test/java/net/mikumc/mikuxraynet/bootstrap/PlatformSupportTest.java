package net.mikumc.mikuxraynet.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 平台判定回归：锁住「不再用类存在性判断 Folia」这一修正。
 *
 * <p><b>真机 bug 背景</b>：测试服是 Leaf 26.2（Paper 下游分支）。Leaf 的
 * {@code libraries/cn/dreeam/leaf/leaf-api/.../leaf-api-...jar} 内自带
 * {@code io/papermc/paper/threadedregions/RegionizedServerInitEvent.class}（Paper 自 1.20 起随服务端
 * 提供 Folia 调度器 API，方便插件写 Folia 兼容），旧实现「类存在即 Folia」因此把 Leaf 误判为 Folia
 * （真机日志：{@code 运行平台：Leaf 26.2（Folia 区域化线程）}），连带禁用了实体枚举、改走区域调度。
 *
 * <p>现在只按服务端自己报出的品牌/版本标识判定，{@code detect} 是纯函数，可离线覆盖全部分支。
 */
class PlatformSupportTest {

  /** 真机 Leaf 26.2 报出的标识（Bukkit.getName() / getVersion() / getBukkitVersion()）。 */
  private static final String LEAF_NAME = "Leaf";
  private static final String LEAF_VERSION = "git-Leaf-26.2-abc1234 (MC: 26.2)";
  private static final String LEAF_BUKKIT_VERSION = "26.2-R0.1-SNAPSHOT";

  @Test
  void leafBrandIsDetectedAsPaper() {
    PlatformSupport.Detection detection = PlatformSupport.detect(PlatformSupport.Mode.AUTO,
        LEAF_NAME, LEAF_VERSION, LEAF_BUKKIT_VERSION);

    assertFalse(detection.folia(), "Leaf 是 Paper 下游分支，不得判为 Folia（真机误判回归）");
    assertEquals(PlatformSupport.Mode.AUTO, detection.mode());
    assertTrue(detection.evidence().contains("Leaf"), "判定依据必须写清服务端名称：" + detection);
    assertTrue(detection.evidence().contains("Paper"), "判定依据应说明落到 Paper 系：" + detection);
  }

  @Test
  void paperAndPurpurBrandsAreDetectedAsPaper() {
    for (String name : new String[] {"Paper", "Purpur", "Spigot", "Airplane"}) {
      PlatformSupport.Detection detection = PlatformSupport.detect(PlatformSupport.Mode.AUTO, name,
          "git-" + name + "-123 (MC: 26.2)", "26.2-R0.1-SNAPSHOT");
      assertFalse(detection.folia(), name + " 必须判为 Paper 系");
    }
  }

  @Test
  void foliaBrandIsDetectedAsFolia() {
    for (String name : new String[] {"Folia", "FoliaPvP"}) {
      PlatformSupport.Detection detection = PlatformSupport.detect(PlatformSupport.Mode.AUTO, name,
          "git-" + name + "-1 (MC: 1.21.4)", "1.21.4-R0.1-SNAPSHOT");
      assertTrue(detection.folia(), name + " 必须判为 Folia 系");
      assertTrue(detection.evidence().contains("folia 标识"), "必须说明命中标识：" + detection);
    }

    // 名称被改但版本串里带 folia 的衍生端，同样判为 Folia
    PlatformSupport.Detection byVersion = PlatformSupport.detect(PlatformSupport.Mode.AUTO, "Luminol",
        "git-Folia-767a3f1e (MC: 1.21.4)", "1.21.4-R0.1-SNAPSHOT");
    assertTrue(byVersion.folia(), "版本串含 folia 时必须判为 Folia 系：" + byVersion);
  }

  @Test
  void explicitModeOverridesDetection() {
    PlatformSupport.Detection forcedFolia = PlatformSupport.detect(PlatformSupport.Mode.FOLIA,
        LEAF_NAME, LEAF_VERSION, LEAF_BUKKIT_VERSION);
    assertTrue(forcedFolia.folia(), "显式 advanced.platform=folia 必须走 Folia 分支");
    assertTrue(forcedFolia.evidence().contains("手动指定"), "必须说明是手动指定：" + forcedFolia);

    PlatformSupport.Detection forcedPaper = PlatformSupport.detect(PlatformSupport.Mode.PAPER,
        "Folia", "git-Folia-1 (MC: 1.21.4)", "1.21.4-R0.1-SNAPSHOT");
    assertFalse(forcedPaper.folia(), "显式 advanced.platform=paper 必须走 Paper 分支");
    assertTrue(forcedPaper.evidence().contains("手动指定"), "必须说明是手动指定：" + forcedPaper);
  }

  @Test
  void nullModeAndBlankIdentifiersDegradeGracefully() {
    PlatformSupport.Detection detection = PlatformSupport.detect(null, null, null, null);

    assertFalse(detection.folia(), "标识缺失时落到 Paper 系（默认单主线程），不得抛异常");
    assertEquals(PlatformSupport.Mode.AUTO, detection.mode(), "null 模式等同于 auto");
  }
}