package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * {@link LatencyMonitor} 降视距/还原「只触碰 send 视距」的回归。
 *
 * <p><b>缺陷（P1）</b>：{@code reduce} 读 {@code getSendViewDistance()} 却写 {@code setViewDistance()}，
 * {@code restore} 又把读到的 send 值写回 view —— Paper 上这是两个独立配置，于是「一次降级+还原」会把
 * 玩家的 {@code view-distance} <b>永久</b>改成原 send 值（如 view=12/send=8 → 还原成 8），直到重登。
 *
 * <p>本测试用动态代理桩出 Player（只关心视距读写这一缝），反射调用私有的 reduce / restore，
 * 直接断言两个 setter 的调用轨迹；reduce/restore 非 public，是为不破坏封装而用反射而非开洞。
 */
class LatencyMonitorTest {

  private static BandwidthConfig.Latency config(int reduceViewDistance, int minViewDistance) {
    return new BandwidthConfig.Latency(true, 400, reduceViewDistance, 30, minViewDistance, 5);
  }

  /** 桩 Player 的可观测状态：send/view 的当前值与被调用的 setter。 */
  private static final class PlayerState {
    int send;
    int view;
    final List<String> sendSets = new ArrayList<>();
    final List<String> viewSets = new ArrayList<>();
  }

  private static Player stubPlayer(PlayerState state) {
    UUID uuid = UUID.randomUUID();
    InvocationHandler handler = (proxy, method, args) -> {
      switch (method.getName()) {
        case "getSendViewDistance":
          return state.send;
        case "getViewDistance":
          return state.view;
        case "setSendViewDistance":
          state.sendSets.add(String.valueOf(args[0]));
          state.send = (Integer) args[0];
          return null;
        case "setViewDistance":
          state.viewSets.add(String.valueOf(args[0]));
          state.view = (Integer) args[0];
          return null;
        case "getUniqueId":
          return uuid;
        case "isOnline":
          return true;
        case "getPing":
          return 0;
        case "toString":
          return "stub-player";
        default:
          return defaultValue(method.getReturnType());
      }
    };
    return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
        new Class<?>[] {Player.class}, handler);
  }

  private static Plugin stubPlugin() {
    InvocationHandler handler = (proxy, method, args) -> {
      if ("getLogger".equals(method.getName())) {
        return Logger.getLogger("LatencyMonitorTest");
      }
      if ("toString".equals(method.getName())) {
        return "stub-plugin";
      }
      return defaultValue(method.getReturnType());
    };
    return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
        new Class<?>[] {Plugin.class}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return (char) 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == double.class) {
      return 0.0d;
    }
    if (type == float.class) {
      return 0.0f;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    return 0;
  }

  private static void invoke(LatencyMonitor monitor, String name, Player player) throws Exception {
    Method method = LatencyMonitor.class.getDeclaredMethod(name, Player.class);
    method.setAccessible(true);
    method.invoke(monitor, player);
  }

  /** 降级与还原都只调用 setSendViewDistance，绝不触碰 setViewDistance。 */
  @Test
  void reduceAndRestoreOnlyTouchSendViewDistance() throws Exception {
    LatencyMonitor monitor = new LatencyMonitor(stubPlugin(), config(2, 4), new ThrottleStats());
    PlayerState state = new PlayerState();
    state.send = 12;
    state.view = 12;
    Player player = stubPlayer(state);

    invoke(monitor, "reduce", player);
    assertEquals(List.of("10"), state.sendSets, "降级只应调用 setSendViewDistance(target=10)");
    assertTrue(state.viewSets.isEmpty(),
        "降级绝不能调用 setViewDistance（否则会永久改掉 view-distance）");
    assertEquals(12, state.view, "view-distance 必须原样不动");

    invoke(monitor, "restore", player);
    assertEquals(List.of("10", "12"), state.sendSets, "还原应把原 send 值 12 写回 send 视距");
    assertTrue(state.viewSets.isEmpty(), "还原也绝不能触碰 view-distance");
    assertEquals(12, state.view, "整轮降级+还原后 view-distance 仍为 12");
  }

  /** 目标超过 view-distance 时钳到合法范围（不能让它抛异常），仍只写 send 视距。 */
  @Test
  void targetIsClampedToViewDistance() throws Exception {
    LatencyMonitor monitor = new LatencyMonitor(stubPlugin(), config(1, 4), new ThrottleStats());
    PlayerState state = new PlayerState();
    state.send = 12;
    state.view = 6; // 目标 11 超过 view=6 → 必须钳到 6
    Player player = stubPlayer(state);

    invoke(monitor, "reduce", player);
    assertEquals(List.of("6"), state.sendSets, "目标超过 view-distance 时必须钳到合法范围");
    assertTrue(state.viewSets.isEmpty(), "钳制路径同样不得触碰 view-distance");
  }

  /** 已在最小视距时不降级（无任何 setter 调用）。 */
  @Test
  void noReductionWhenAlreadyAtMinimum() throws Exception {
    LatencyMonitor monitor = new LatencyMonitor(stubPlugin(), config(2, 4), new ThrottleStats());
    PlayerState state = new PlayerState();
    state.send = 4;
    state.view = 12;
    Player player = stubPlayer(state);

    invoke(monitor, "reduce", player);
    assertTrue(state.sendSets.isEmpty(), "已在最小 send 视距时不降级");
    assertTrue(state.viewSets.isEmpty());
  }
}