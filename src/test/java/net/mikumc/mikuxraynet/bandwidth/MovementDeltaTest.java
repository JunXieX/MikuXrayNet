package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 零位移判定：覆盖各字段组合（全 0 → 取消；任一非 0 → 放行）。 */
class MovementDeltaTest {

  @Test
  @DisplayName("位置增量全为 0 时才判定为冗余")
  void zeroMoveOnlyWhenAllZero() {
    assertTrue(MovementDelta.isZeroMove((short) 0, (short) 0, (short) 0));

    assertFalse(MovementDelta.isZeroMove((short) 1, (short) 0, (short) 0));
    assertFalse(MovementDelta.isZeroMove((short) 0, (short) -1, (short) 0));
    assertFalse(MovementDelta.isZeroMove((short) 0, (short) 0, (short) 1));
    assertFalse(MovementDelta.isZeroMove((short) -1, (short) -1, (short) -1));
  }

  @Test
  @DisplayName("朝向增量全为 0 时才判定为冗余")
  void zeroRotationOnlyWhenAllZero() {
    assertTrue(MovementDelta.isZeroRotation((byte) 0, (byte) 0));

    assertFalse(MovementDelta.isZeroRotation((byte) 1, (byte) 0));
    assertFalse(MovementDelta.isZeroRotation((byte) 0, (byte) -1));
    assertFalse(MovementDelta.isZeroRotation((byte) 1, (byte) 1));
  }

  @Test
  @DisplayName("移动+转向包：位移与转向都为零才取消")
  void moveAndRotationPacketNeedsBothZero() {
    // REL_ENTITY_MOVE_LOOK 形态
    assertTrue(isRedundantMoveLook((short) 0, (short) 0, (short) 0, (byte) 0, (byte) 0));
    assertFalse(isRedundantMoveLook((short) 0, (short) 0, (short) 0, (byte) 0, (byte) 1));
    assertFalse(isRedundantMoveLook((short) 0, (short) 1, (short) 0, (byte) 0, (byte) 0));
    assertFalse(isRedundantMoveLook((short) 1, (short) 1, (short) 1, (byte) 1, (byte) 1));
  }

  /** 与监听器中的组合判定保持一致。 */
  private static boolean isRedundantMoveLook(short dx, short dy, short dz, byte yaw, byte pitch) {
    return MovementDelta.isZeroMove(dx, dy, dz) && MovementDelta.isZeroRotation(yaw, pitch);
  }
}