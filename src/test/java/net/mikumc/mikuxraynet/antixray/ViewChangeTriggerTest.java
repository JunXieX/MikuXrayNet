package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 视野变化触发的纯函数（{@link ProximityRevealer#viewAngle} / {@link ProximityRevealer#viewChangeExceeds}）
 * 离线用例：这条路径由玩家行为驱动，阈值算错会导致两种坏结果——<b>过于敏感</b>（每 tick 都跑增量巡检，
 * 把预算打满）或<b>过于迟钝</b>（转身看到假方块却不再触发）。
 *
 * <p>阈值口径：眼位位移 ≥ 1 格，或视线夹角 ≥ 25°。夹角按 Minecraft 视角定义
 * （x = -sin(yaw)·cos(pitch)、y = -sin(pitch)、z = cos(yaw)·cos(pitch)），yaw 环绕必须自然处理。
 */
class ViewChangeTriggerTest {

  private static final double DELTA = 1.0E-9D;

  @Test
  void angleMatchesMinecraftViewGeometry() {
    assertEquals(0.0D, ProximityRevealer.viewAngle(0.0D, 0.0D, 0.0D, 0.0D), DELTA,
        "同一视线夹角为 0");
    assertEquals(Math.PI / 2.0D, ProximityRevealer.viewAngle(0.0D, 0.0D, 90.0D, 0.0D), DELTA,
        "yaw 转 90° 夹角为 π/2");
    assertEquals(Math.PI, ProximityRevealer.viewAngle(0.0D, 0.0D, 180.0D, 0.0D), DELTA,
        "yaw 转 180° 夹角为 π");
    assertEquals(Math.PI / 2.0D, ProximityRevealer.viewAngle(0.0D, 0.0D, 0.0D, 90.0D), DELTA,
        "pitch 转 90° 夹角为 π/2");
  }

  @Test
  void yawWraparoundIsHandled() {
    double oneDegree = Math.toRadians(1.0D);
    assertEquals(oneDegree, ProximityRevealer.viewAngle(359.0D, 0.0D, 0.0D, 0.0D), 1.0E-6D,
        "359° → 0° 是 1° 而非 359°（yaw 环绕必须由三角函数自然处理）");
    assertEquals(oneDegree, ProximityRevealer.viewAngle(0.0D, 0.0D, 359.0D, 0.0D), 1.0E-6D);
  }

  @Test
  void thresholdsMatchDocumentedValues() {
    // 位移：< 1 格且视角变化 < 25° → 不触发
    assertFalse(ProximityRevealer.viewChangeExceeds(0.99D, Math.toRadians(24.9D)),
        "位移与视角都低于阈值时不得触发（否则每 tick 都会跑增量巡检）");
    // 位移达 1 格 → 触发
    assertTrue(ProximityRevealer.viewChangeExceeds(1.0D, 0.0D), "位移 ≥ 1 格必须触发");
    // 视角达 25° → 触发
    assertTrue(ProximityRevealer.viewChangeExceeds(0.0D, Math.toRadians(25.0D)),
        "视线变化 ≥ 25° 必须触发（转身看新区域）");
    assertFalse(ProximityRevealer.viewChangeExceeds(0.0D, Math.toRadians(24.99D)));
  }
}
