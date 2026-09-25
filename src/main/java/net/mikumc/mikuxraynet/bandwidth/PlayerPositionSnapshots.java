package net.mikumc.mikuxraynet.bandwidth;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;

/**
 * 玩家位置的「节流实时快照」：由<b>拥有该玩家的线程每 tick 刷新</b>，供封包异步线程只读判定近身变更。
 *
 * <p><b>为什么需要它</b>：封包回调运行在 ProtocolLib 的封包线程，遵循「不触碰实体状态」的 Folia 纪律，
 * 因此近身判定只能读一份快照。旧实现由 {@code PlayerMoveEvent} 维护坐标缓存，而
 * {@code PlayerTeleportEvent} 有独立的 {@link org.bukkit.event.HandlerList}——传送后缓存不会更新，
 * 近身判定按<b>陈旧坐标</b>分类（实测 {@code /tp} 后分类恰好反转：近身的被判「进合并窗口」并吃满
 * 一个窗口的延迟）。改为「每 tick 刷新」后，任何位置变化（含传送）都在至多一个 tick 内进入快照，
 * 不依赖任何移动/传送事件补丁。
 *
 * <p><b>发布方式</b>：快照是不可变 {@link Position} 记录，经 {@link ConcurrentHashMap} 读写。
 * 记录字段是 {@code final}，而 {@code ConcurrentHashMap} 的读写建立 happens-before，因此调用方
 * 看到的一定是一份完整的新快照（不会读到「半新半旧」的坐标），无需额外加锁或 volatile 数组。
 *
 * <p><b>零分配读取端口</b>：刷新侧刻意用 {@code player.getX()/getY()/getZ()} 三个基本类型 getter
 * （{@link org.bukkit.entity.Entity#getX()} 等），不用 {@code getLocation()}——后者每 tick 每玩家
 * 都要 new 一个 {@link org.bukkit.Location}。
 *
 * <p><b>fail-open</b>：快照缺失（刚登录 / 刚重置）或读取异常一律按「近身」处理（立即放行）——
 * 无法判断距离时宁可少合并，绝不制造延迟。本类不触碰任何 Bukkit API，可离线单测。
 *
 * <p><b>方块坐标不变的提前返回</b>：判定只用到方块坐标（见 {@link #immediatePass}），因此
 * {@link #refresh} 先读回当前快照，若其方块坐标与本次完全相同就直接返回——不 new 记录、不写 CHM。
 * 这样静止玩家（占绝大多数）每 tick 只剩一次 CHM 读，省下每秒每玩家一次记录分配与写操作。
 * 判据是<b>同一枚</b> {@link #blockCoordinate} 纯函数同时供写入侧（是否重发）与判定侧（算距离）使用，
 * 两侧不可能漂移。
 */
final class PlayerPositionSnapshots {

  /** 玩家位置快照（精确坐标；判定用的方块坐标在读取侧取 floor，见 {@link #immediatePass}）。 */
  record Position(double x, double y, double z) {
  }

  private final ConcurrentHashMap<UUID, Position> positions = new ConcurrentHashMap<>();

  /** 刷新某玩家快照（必须在拥有该玩家的线程调用）。方块坐标未变时不发布，见类注释。 */
  void refresh(UUID playerId, double x, double y, double z) {
    if (playerId == null) {
      return;
    }
    Position current = positions.get(playerId);
    if (current != null && blockCoordinate(current.x()) == blockCoordinate(x)
        && blockCoordinate(current.y()) == blockCoordinate(y)
        && blockCoordinate(current.z()) == blockCoordinate(z)) {
      // 仍在同一方块：判定结果不可能改变，跳过分配与写入
      return;
    }
    positions.put(playerId, new Position(x, y, z));
  }

  /** 只读快照；尚未建立时返回 {@code null}（调用方按 fail-open 处理）。 */
  Position get(UUID playerId) {
    return playerId == null ? null : positions.get(playerId);
  }

  /** 丢弃某玩家快照（玩家退出 / 实体退役）：此后读取侧一律 fail-open。 */
  void discard(UUID playerId) {
    if (playerId != null) {
      positions.remove(playerId);
    }
  }

  /** 清空全部快照（插件停用）。 */
  void clear() {
    positions.clear();
  }

  /** 当前快照数（仅诊断用）。 */
  int size() {
    return positions.size();
  }

  /**
   * 纯判定：本封包的方块变更是否近身（近身 → 立即放行，不进合并窗口）。
   *
   * <p><b>与旧实现完全一致的边界语义</b>：把快照的精确坐标取 {@code floor} 得到玩家所在<b>方块</b>坐标
   * （旧缓存的坐标来自 {@code Location#getBlockX()}，正是 floor 的结果），再用
   * {@link BlockChangeBatch#anyWithinRadius} 判定「变更方块与玩家方块的欧氏距离平方 ≤ radius²」
   * （含边界）。之所以要 floor 而不能直接用精确坐标：{@code Entity} 上没有 {@code getBlockX()}，
   * 而直接用精确坐标会把半径边界整体挪动不到 1 格——属于「改了边界含义」。
   * {@code radius <= 0} 表示关闭立即放行，恒为 {@code false}（与旧行为一致）。
   *
   * @param snapshot 该玩家的位置快照；{@code null}（尚未建立）时按 fail-open 返回 {@code true}
   * @param updates  一次封包解析出的全部变更
   * @return true 表示应按近身立即放行；false 表示照常入合并窗口
   */
  static <V> boolean immediatePass(Position snapshot, List<Update<V>> updates, int radius) {
    if (radius <= 0) {
      return false;
    }
    if (snapshot == null) {
      // fail-open：无快照 → 按近身处理（宁可少合并，绝不制造延迟）
      return true;
    }
    return BlockChangeBatch.anyWithinRadius(updates, blockCoordinate(snapshot.x()),
        blockCoordinate(snapshot.y()), blockCoordinate(snapshot.z()), radius);
  }

  /**
   * 精确坐标 → 所在方块坐标的<b>唯一</b>转换（写入侧是否重发与判定侧算距离都调用它）。
   *
   * <p>必须是 {@link Math#floor} 语义而<b>不能</b>是 {@code (int)} 截断：负数坐标下截断会向零取整，
   * 例如 {@code -0.5} 截断得 {@code 0}（把玩家算到了隔壁方块），floor 才是正确的 {@code -1}。
   */
  static int blockCoordinate(double value) {
    return (int) Math.floor(value);
  }
}