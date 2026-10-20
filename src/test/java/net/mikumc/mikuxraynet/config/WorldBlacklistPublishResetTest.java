package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 停用复位契约：{@link WorldBlacklistConfig#publish} 传 {@code null} 必须回落为<b>空名单</b>。
 *
 * <p>{@code MikuXrayNet#onDisable} 依赖这一点清理「进程级静态活动名单」：插件停用后，本 JVM 里任何
 * 读者（下一次启用时 onLoad 之前、或其它插件）都不得再看到上一份豁免名单。空名单语义是「所有世界
 * 照常生效」（{@link WorldBlacklistConfig#isBlacklistedNow(String)} 恒 false，fail-open），
 * 与「未加载」一致。
 */
class WorldBlacklistPublishResetTest {

  @Test
  void publishNullResetsToEmptyBlacklist() {
    try {
      WorldBlacklistConfig.publish(WorldBlacklistConfig.fromPatterns(List.of("spawn", "dungeon_*")));
      assertTrue(WorldBlacklistConfig.isBlacklistedNow("spawn"), "前置条件：精确名生效");
      assertTrue(WorldBlacklistConfig.isBlacklistedNow("dungeon_1"), "前置条件：通配生效");
      assertFalse(WorldBlacklistConfig.current().isEmpty(), "前置条件：名单非空");

      WorldBlacklistConfig.publish(null);

      assertTrue(WorldBlacklistConfig.current().isEmpty(), "publish(null) 必须回落为空名单");
      assertFalse(WorldBlacklistConfig.isBlacklistedNow("spawn"),
          "停用复位后旧名单不得残留（否则新实例在 onLoad 前会误豁免该世界）");
      assertFalse(WorldBlacklistConfig.isBlacklistedNow("dungeon_1"));
      assertFalse(WorldBlacklistConfig.isBlacklistedNow(null), "null 世界名同样安全返回 false");
    } finally {
      // 静态实例是进程级共享，用例结束复位，避免污染其它测试。
      WorldBlacklistConfig.publish(null);
    }
  }
}
