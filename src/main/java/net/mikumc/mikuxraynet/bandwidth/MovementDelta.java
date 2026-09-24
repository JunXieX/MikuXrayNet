package net.mikumc.mikuxraynet.bandwidth;

/**
 * 实体位置/朝向增量判定（纯函数，无任何服务端依赖，便于单元测试）。
 *
 * <p>增量全为 0 的实体更新包对客户端不带来任何状态变化，属于可安全取消的冗余包。
 */
public final class MovementDelta {

  private MovementDelta() {
  }

  /** 位置增量是否全为零（dx/dy/dz 都是 0 表示该包不带来任何位移）。 */
  public static boolean isZeroMove(short deltaX, short deltaY, short deltaZ) {
    return deltaX == 0 && deltaY == 0 && deltaZ == 0;
  }

  /** 朝向增量是否全为零（yaw/pitch 都是 0 表示该包不带来任何转向）。 */
  public static boolean isZeroRotation(byte yaw, byte pitch) {
    return yaw == 0 && pitch == 0;
  }
}