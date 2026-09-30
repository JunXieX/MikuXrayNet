package net.mikumc.mikuxraynet.bandwidth;

/**
 * 实体位置/朝向更新包的「冗余取消」判定（纯函数，无任何服务端依赖，便于单元测试）。
 *
 * <p><b>只对纯位移包（{@code REL_ENTITY_MOVE}）做零位移取消</b>：其 dx/dy/dz 全为 0 表示该包
 * 不带来任何位移，属于可安全取消的冗余包。
 *
 * <p><b>朝向包绝不按「全 0」取消</b>：{@code REL_ENTITY_MOVE_LOOK} / {@code ENTITY_LOOK} 里的
 * yaw/pitch 是<b>绝对量化角</b>（0 表示朝正南 / 平视），而不是「相对上次的增量」。把全 0 当作
 * 「无转向」取消，会让实体朝向恰好为正南/平视的那次更新被吞掉，客户端保留旧朝向 →
 * 产生持久朝向错误。若将来确实要取消朝向包，必须与「上次下发给该玩家的值」比较，
 * 而不是与 0 比较。
 */
public final class MovementDelta {

  private MovementDelta() {
  }

  /** 位置增量是否全为零（dx/dy/dz 都是 0 表示该包不带来任何位移，可安全取消）。 */
  public static boolean isZeroMove(short deltaX, short deltaY, short deltaZ) {
    return deltaX == 0 && deltaY == 0 && deltaZ == 0;
  }

  /**
   * 实体更新包是否可安全取消。
   *
   * @param carriesRotation 该包是否携带朝向字段；{@code REL_ENTITY_MOVE_LOOK} / {@code ENTITY_LOOK}
   *                        为 true（朝向是绝对量化角，绝不可按 0 取消）
   * @param deltaX          位移增量 X（仅纯位移包参与判定）
   * @param deltaY          位移增量 Y
   * @param deltaZ          位移增量 Z
   * @return 仅当「纯位移包且位移全为 0」时为 true；朝向包恒为 false（必须下发）
   */
  public static boolean isRedundantEntityUpdate(boolean carriesRotation, short deltaX, short deltaY,
      short deltaZ) {
    // 朝向包一律不取消（依据见类注释）；只有纯位移包的零位移才安全取消。
    return !carriesRotation && isZeroMove(deltaX, deltaY, deltaZ);
  }
}