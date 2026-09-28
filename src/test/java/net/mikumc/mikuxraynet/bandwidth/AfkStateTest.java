package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AFK 状态机：进入/退出条件（时钟由测试注入）。 */
class AfkStateTest {

  private static final long TIMEOUT = 300_000L;

  @Test
  @DisplayName("刚创建时不是 AFK")
  void freshStateIsActive() {
    AfkState state = new AfkState(1_000L, 0, 64, 0, 0.5D, 64.0D, 0.5D);

    assertFalse(state.afk());
    assertFalse(state.isTimedOut(1_000L, TIMEOUT));
  }

  @Test
  @DisplayName("未达超时阈值不算 AFK，达到阈值即超时")
  void timedOutOnlyAfterThreshold() {
    AfkState state = new AfkState(1_000L, 0, 64, 0, 0.5D, 64.0D, 0.5D);

    assertFalse(state.isTimedOut(1_000L + TIMEOUT - 1L, TIMEOUT));
    assertTrue(state.isTimedOut(1_000L + TIMEOUT, TIMEOUT));
  }

  @Test
  @DisplayName("进入 AFK 只上报一次")
  void enterAfkOnlyOnce() {
    AfkState state = new AfkState(1_000L, 0, 64, 0, 0.5D, 64.0D, 0.5D);

    assertFalse(state.enterIfTimedOut(1_000L + TIMEOUT - 1L, TIMEOUT));
    assertTrue(state.enterIfTimedOut(1_000L + TIMEOUT, TIMEOUT));
    assertTrue(state.afk());
    assertFalse(state.enterIfTimedOut(1_000L + TIMEOUT + 1L, TIMEOUT));
  }

  @Test
  @DisplayName("发生有效活动立即退出 AFK")
  void touchExitsAfk() {
    AfkState state = new AfkState(1_000L, 0, 64, 0, 0.5D, 64.0D, 0.5D);
    assertTrue(state.enterIfTimedOut(1_000L + TIMEOUT, TIMEOUT));

    long now = 1_000L + TIMEOUT + 1L;
    state.touch(now, true, 5, 64, 5, 5.5D, 64.0D, 5.5D);

    assertFalse(state.afk());
    assertFalse(state.isTimedOut(now, TIMEOUT));
    assertTrue(state.isSameBlock(5, 64, 5));
  }

  @Test
  @DisplayName("同一方块内的细微移动不算活动，不刷新缓存坐标")
  void sameBlockMovementIsIgnored() {
    AfkState state = new AfkState(1_000L, 3, 64, 3, 3.1D, 64.0D, 3.1D);

    assertTrue(state.isSameBlock(3, 64, 3));
    assertFalse(state.isSameBlock(4, 64, 3));
  }

  @Test
  @DisplayName("AFK 期间缓存的坐标保持为最后一次有效位置，可用于封包线程距离判定")
  void cachedPositionSurvivesWhileAfk() {
    AfkState state = new AfkState(1_000L, 8, 70, -4, 8.5D, 70.5D, -4.5D);

    assertTrue(state.enterIfTimedOut(1_000L + TIMEOUT, TIMEOUT));

    assertTrue(state.isSameBlock(8, 70, -4));
    org.junit.jupiter.api.Assertions.assertEquals(8.5D, state.x());
    org.junit.jupiter.api.Assertions.assertEquals(70.5D, state.y());
    org.junit.jupiter.api.Assertions.assertEquals(-4.5D, state.z());
  }
}