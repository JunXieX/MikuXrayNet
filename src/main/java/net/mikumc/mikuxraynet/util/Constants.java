package net.mikumc.mikuxraynet.util;

/**
 * 跨模块共享的常量（唯一出处）。
 *
 * <p>此前 {@code BYPASS_PERMISSION} 在 4 个带宽模块各自重复、{@code MAX_ERROR_LOGS} 在
 * 7 处各自重复——字面量一旦漂移（只改一处）就会出现「日志限额不一致 / 权限判定不一致」的
 * 隐蔽缺陷，故收敛到本类；各模块一律引用这里，不再自带私有副本。
 */
public final class Constants {

  /** 直通权限：持有该权限的玩家不受反矿透与带宽优化影响（与两份插件描述的权限声明一致）。 */
  public static final String BYPASS_PERMISSION = "mikuxraynet.bypass";

  /** 管理命令权限（命令本身的 {@code requires} 判定用它）。 */
  public static final String ADMIN_PERMISSION = "mikuxraynet.admin";

  /** 通配权限：上面各项的父节点，授予它即授予全部。 */
  public static final String ALL_PERMISSION = "mikuxraynet.*";

  /** {@code /mikuxraynet status} 子命令权限。 */
  public static final String STATUS_PERMISSION = "mikuxraynet.status";

  /** {@code /mikuxraynet dump} 子命令权限。 */
  public static final String DUMP_PERMISSION = "mikuxraynet.dump";

  /** {@code /mikuxraynet reload} 子命令权限。 */
  public static final String RELOAD_PERMISSION = "mikuxraynet.reload";

  /** 同类错误的最多提示次数（超过后静默，避免异常场景刷屏）。 */
  public static final int MAX_ERROR_LOGS = 3;

  private Constants() {
  }
}
