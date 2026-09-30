package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 零位移取消判定：只有纯位移包（{@code REL_ENTITY_MOVE}）的零位移可取消；朝向包绝不因全 0 取消。
 */
class MovementDeltaTest {

  @Test
  @DisplayName("位置增量全为 0 时才判定为零位移")
  void zeroMoveOnlyWhenAllZero() {
    assertTrue(MovementDelta.isZeroMove((short) 0, (short) 0, (short) 0));

    assertFalse(MovementDelta.isZeroMove((short) 1, (short) 0, (short) 0));
    assertFalse(MovementDelta.isZeroMove((short) 0, (short) -1, (short) 0));
    assertFalse(MovementDelta.isZeroMove((short) 0, (short) 0, (short) 1));
    assertFalse(MovementDelta.isZeroMove((short) -1, (short) -1, (short) -1));
  }

  @Test
  @DisplayName("纯位移包（carriesRotation=false）：零增量仍取消，任一非 0 放行")
  void pureMoveZeroDeltaIsCancelled() {
    assertTrue(MovementDelta.isRedundantEntityUpdate(false, (short) 0, (short) 0, (short) 0),
        "纯位移包位移全为 0 → 冗余，取消（带宽优化收益保留）");
    assertFalse(MovementDelta.isRedundantEntityUpdate(false, (short) 1, (short) 0, (short) 0));
    assertFalse(MovementDelta.isRedundantEntityUpdate(false, (short) 0, (short) 0, (short) -1));
  }

  @Test
  @DisplayName("朝向包：即便 yaw=0/pitch=0 也必须下发，绝不按全 0 取消")
  void rotationPacketsAreNeverCancelled() {
    // REL_ENTITY_MOVE_LOOK / ENTITY_LOOK：yaw/pitch 是绝对量化角，0 表示朝正南 / 平视，不是「无变化」。
    // 若按全 0 取消，客户端会保留旧朝向 → 产生持久朝向错误（「实体转向为 0」收不到）。
    assertFalse(MovementDelta.isRedundantEntityUpdate(true, (short) 0, (short) 0, (short) 0),
        "yaw=0/pitch=0 的朝向包必须下发");
    assertFalse(MovementDelta.isRedundantEntityUpdate(true, (short) 1, (short) 2, (short) 3),
        "带非零位移的朝向包同样必须下发");
  }
}