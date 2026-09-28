package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 直通名单判定：验证权限集合变化时「直通名单」同步变化，且不同玩家互不影响。
 *
 * <p>只测纯内存逻辑，不依赖 Bukkit（构造时传入 {@code null} 插件，不调用 {@code start()}）。
 */
class BypassRegistryTest {

  @Test
  void registryFollowsPermissionChanges() {
    BypassRegistry registry = new BypassRegistry(null);
    UUID player = UUID.randomUUID();

    assertFalse(registry.isBypassed(player), "初始不应直通");

    // 授予 bypass 权限 → 名单加入
    registry.refresh(player, true);
    assertTrue(registry.isBypassed(player), "授予权限后应立即直通");
    assertEquals(1, registry.size());

    // 收回 bypass 权限 → 名单移除
    registry.refresh(player, false);
    assertFalse(registry.isBypassed(player), "收回权限后应立即不再直通");
    assertEquals(0, registry.size());
  }

  @Test
  void playersAreTrackedIndependently() {
    BypassRegistry registry = new BypassRegistry(null);
    UUID bypassed = UUID.randomUUID();
    UUID normal = UUID.randomUUID();

    registry.refresh(bypassed, true);
    registry.refresh(normal, false);

    assertTrue(registry.isBypassed(bypassed));
    assertFalse(registry.isBypassed(normal));
    assertEquals(1, registry.size());

    // 退出即从名单移除
    registry.remove(bypassed);
    assertFalse(registry.isBypassed(bypassed));
    assertEquals(0, registry.size());
  }

  @Test
  void nullPlayerIsNeverBypassed() {
    BypassRegistry registry = new BypassRegistry(null);
    assertFalse(registry.isBypassed(null));
    registry.refresh(null, true);
    assertEquals(0, registry.size());
  }
}