package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 直通名单判定：验证「权限只在进入服务器时判定一次」的语义——登录/退出与启用快照维护名单，
 * 且<b>不存在任何周期刷新</b>（运行期改权限不生效，需重进服务器）。
 *
 * <p>只测纯内存逻辑，不依赖 Bukkit（构造时传入 {@code null} 插件，不调用 {@code start()}）。
 */
class BypassRegistryTest {

  /** ① 登录判定生效：登录那一刻按权限决定是否直通。 */
  @Test
  void loginSnapshotAppliesDecision() {
    BypassRegistry registry = new BypassRegistry(null);
    UUID player = UUID.randomUUID();

    assertFalse(registry.isBypassed(player), "初始不应直通");

    // 登录事件入口：有权限 → 登记
    registry.refresh(player, true);
    assertTrue(registry.isBypassed(player), "登录时持有权限即应直通");
    assertEquals(1, registry.size());
  }

  /** ② 退出后移除：退出事件把玩家从名单摘除。 */
  @Test
  void quitRemovesEntry() {
    BypassRegistry registry = new BypassRegistry(null);
    UUID player = UUID.randomUUID();

    registry.refresh(player, true);
    assertTrue(registry.isBypassed(player));

    registry.remove(player);
    assertFalse(registry.isBypassed(player), "退出后必须从名单移除");
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
  }

  @Test
  void nullPlayerIsNeverBypassed() {
    BypassRegistry registry = new BypassRegistry(null);
    assertFalse(registry.isBypassed(null));
    registry.refresh(null, true);
    assertEquals(0, registry.size());
  }

  /**
   * ③ 运行中权限变化不改变判定：本类没有任何周期入口复查权限，登录快照后状态稳定不变。
   *
   * <p>行为上无法「离线推进一个 tick」（因为没有可调用项），因此这里以结构性反射自证：
   * 周期巡检的方法与调度字段必须已全部移除——这等价于「周期检查确实被取消」。
   */
  @Test
  void runtimePermissionChangeDoesNotAlterDecisionAndNoPeriodicRefreshRemains() {
    BypassRegistry registry = new BypassRegistry(null);
    UUID player = UUID.randomUUID();

    // 登录时判定为直通
    registry.refresh(player, true);
    assertTrue(registry.isBypassed(player));

    // 运行期间权限被权限插件撤销：没有任何周期复查，判定保持登录时快照，必须重进服务器才更新。
    assertTrue(registry.isBypassed(player),
        "运行期改权限不得影响登录时快照，需重新进入服务器生效");

    // 结构性自证：旧的周期巡检入口与调度字段不得再存在。
    for (String gone : new String[] {"refreshOnline", "sweepOffline"}) {
      assertFalse(Arrays.stream(BypassRegistry.class.getDeclaredMethods())
              .anyMatch(method -> method.getName().equals(gone)),
          "不得再残留周期巡检方法：" + gone);
    }
    assertFalse(Arrays.stream(BypassRegistry.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("refresh") && method.getParameterCount() == 0),
        "不得再残留无参 refresh() 周期刷新入口");
    for (Field field : BypassRegistry.class.getDeclaredFields()) {
      assertFalse(ScheduledTask.class.isAssignableFrom(field.getType()),
          "不得再持有周期调度任务字段：" + field.getName());
      assertFalse(field.getName().equals("refreshTicks"),
          "不得再残留巡检计数器：" + field.getName());
    }
  }

  /** ④ 启用时在线玩家快照：把「当时在线玩家 → 是否持有权限」一次性灌入名单。 */
  @Test
  void startupSnapshotRegistersOnlyBypassedOnlinePlayers() {
    BypassRegistry registry = new BypassRegistry(null);
    UUID bypassed = UUID.randomUUID();
    UUID normal = UUID.randomUUID();

    registry.snapshot(Map.of(bypassed, true, normal, false));

    assertTrue(registry.isBypassed(bypassed), "启用快照必须登记有权限的在线玩家");
    assertFalse(registry.isBypassed(normal), "启用快照不得登记无权限的在线玩家");
    assertEquals(1, registry.size());
  }

  /**
   * 共享入口 {@link BypassRegistry#isBypassedNow(UUID)} 在名单未装配（未调用 {@code start()}）时必须
   * fail-open 为 false，且绝不抛异常——带宽模块在封包线程无条件依赖它。
   */
  @Test
  void sharedLookupIsFailOpenWhenNoActiveRegistry() {
    assertFalse(BypassRegistry.isBypassedNow(null), "null 玩家恒为「不直通」");
    assertFalse(BypassRegistry.isBypassedNow(UUID.randomUUID()),
        "未装配名单时返回 false（与「无直通」一致，fail-open）");
  }
}