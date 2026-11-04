package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.bandwidth.EntityCuller.MotionState;
import org.junit.jupiter.api.Test;

/**
 * 运动跳过闸门的纯函数测试：只有「结论未变 + 眼位/实体中心都没动 + 遮挡补丁代次未变 + 未超上限」才跳过。
 *
 * <p>闸门本身不触碰 Bukkit（坐标/结论都由调用方传入），因此可离线逐条验证边界。
 */
class EntityCullerMotionSkipTest {

  private static final double THRESHOLD = 0.2D;

  private static MotionState state(boolean hidden, long generation, int skips, boolean initialized) {
    MotionState state = new MotionState();
    state.initialized = initialized;
    state.hidden = hidden;
    state.eyeX = 10.0D;
    state.eyeY = 20.0D;
    state.eyeZ = 30.0D;
    state.centerX = 12.0D;
    state.centerY = 21.0D;
    state.centerZ = 30.0D;
    state.generation = generation;
    state.skips = skips;
    return state;
  }

  @Test
  void skipsWhenNothingChanged() {
    assertTrue(EntityCuller.motionSkipApplies(state(false, 7L, 0, true), false,
        10.05D, 20.0D, 30.0D, 12.0D, 21.05D, 30.0D, 7L, THRESHOLD, 3));
  }

  @Test
  void eyeMovementBeyondThresholdForcesEvaluation() {
    assertFalse(EntityCuller.motionSkipApplies(state(false, 7L, 0, true), false,
        10.5D, 20.0D, 30.0D, 12.0D, 21.0D, 30.0D, 7L, THRESHOLD, 3));
  }

  @Test
  void entityMovementBeyondThresholdForcesEvaluation() {
    assertFalse(EntityCuller.motionSkipApplies(state(false, 7L, 0, true), false,
        10.0D, 20.0D, 30.0D, 12.0D, 21.0D, 30.5D, 7L, THRESHOLD, 3));
  }

  @Test
  void occlusionPatchGenerationChangeForcesEvaluation() {
    // 双方都没动，但方块被挖开/炸开/放置 → 补丁代次推进 → 必须重新判定
    assertFalse(EntityCuller.motionSkipApplies(state(false, 7L, 0, true), false,
        10.0D, 20.0D, 30.0D, 12.0D, 21.0D, 30.0D, 8L, THRESHOLD, 3));
  }

  @Test
  void verdictChangeForcesEvaluation() {
    assertFalse(EntityCuller.motionSkipApplies(state(false, 7L, 0, true), true,
        10.0D, 20.0D, 30.0D, 12.0D, 21.0D, 30.0D, 7L, THRESHOLD, 1));
  }

  @Test
  void skipLimitForcesEvaluation() {
    // 隐藏态上限 1：已经跳过 1 次后必须重新判定
    assertFalse(EntityCuller.motionSkipApplies(state(true, 7L, 1, true), true,
        10.0D, 20.0D, 30.0D, 12.0D, 21.0D, 30.0D, 7L, THRESHOLD, 1));
    // 可见态上限 3：跳过 2 次仍可再跳一次
    assertTrue(EntityCuller.motionSkipApplies(state(false, 7L, 2, true), false,
        10.0D, 20.0D, 30.0D, 12.0D, 21.0D, 30.0D, 7L, THRESHOLD, 3));
  }

  @Test
  void uninitializedOrDisabledNeverSkips() {
    assertFalse(EntityCuller.motionSkipApplies(state(false, 7L, 0, false), false,
        10.0D, 20.0D, 30.0D, 12.0D, 21.0D, 30.0D, 7L, THRESHOLD, 3));
    assertFalse(EntityCuller.motionSkipApplies(state(false, 7L, 0, true), false,
        10.0D, 20.0D, 30.0D, 12.0D, 21.0D, 30.0D, 7L, THRESHOLD, 0));
    assertFalse(EntityCuller.motionSkipApplies(null, false,
        10.0D, 20.0D, 30.0D, 12.0D, 21.0D, 30.0D, 7L, THRESHOLD, 3));
  }
}
