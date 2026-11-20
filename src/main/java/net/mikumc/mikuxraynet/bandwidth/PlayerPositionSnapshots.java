package net.mikumc.mikuxraynet.bandwidth;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
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
 * <p><b>每玩家只有一个持有者（{@link Session}）</b>：位置快照、心跳、会话号都在同一个对象上，表里存的是
 * 「当前连接的那个持有者」。换代 = 换对象（{@link #beginSession(UUID)} 直接替换表里的引用）：
 * <ul>
 *   <li>旧持有者上的迟到写入只落在旧对象里——读取侧只读表里当前持有者的字段，新连接看不到旧坐标；</li>
 *   <li>不存在「清表之后、重新登记之前」被旧任务写回新代的窗口（旧实现是「位置表 + 心跳表 + 无锁
 *       check→put」，重连时编号还会从 1 重来，属于典型的 ABA）；</li>
 *   <li>退役回调拿着旧持有者，只能按引用相等摘掉自己那一条（见 {@code BlockChangeMerger}）。</li>
 * </ul>
 * 会话号是<b>进程级单调递增</b>的，只用于诊断与日志；<b>世界代次不在这里</b>——世界身份（含代次）由
 * {@code PlayerWorldIndex.WorldContext} 负责，本类只回答「这个玩家的哪一次连接、什么时候、在哪个世界、
 * 方块坐标是多少」。
 *
 * <p><b>零分配读取端口</b>：刷新侧刻意用 {@code player.getX()/getY()/getZ()} 三个基本类型 getter
 * （{@link org.bukkit.entity.Entity#getX()} 等），不用 {@code getLocation()}——后者每 tick 每玩家
 * 都要 new 一个 {@link org.bukkit.Location}。
 *
 * <p><b>fail-open</b>：快照缺失（刚登录 / 刚重置）或读取异常一律按「近身」处理（立即放行）——
 * 无法判断距离时宁可少合并，绝不制造延迟。本类不触碰任何 Bukkit API，可离线单测。
 *
 * <p><b>方块坐标不变的提前返回</b>：判定只用到方块坐标（见 {@link #immediatePass}），因此
 * {@link #refresh} 先读回当前快照，若其方块坐标与本次完全相同就直接返回——不 new 记录、不写持有者。
 * 这样静止玩家（占绝大多数）每 tick 只剩一次字段读，省下每秒每玩家一次记录分配。
 * 判据是<b>同一枚</b> {@link #blockCoordinate} 纯函数同时供写入侧（是否重发）与判定侧（算距离）使用，
 * 两侧不可能漂移。
 *
 * <p><b>黑名单世界的坐标捕获</b>：黑名单世界不做任何带宽优化，因此
 * {@link #refreshWorldOnly} 只维护「当前世界」而<b>跳过坐标读取</b>（省掉每 tick 三次 getter 与三次
 * floor 比较）。该分支下坐标被标记为未知（{@link Position#positionKnown()} = false），
 * {@link #immediatePass} 据此 fail-open（按近身立即放行），绝不拿一个与当前世界无关的旧坐标去算距离。
 * 「当前世界」仍每 tick 维护，因为世界归属判定全靠它。
 */
final class PlayerPositionSnapshots {

  /**
   * 玩家位置快照（精确坐标；判定用的方块坐标在读取侧取 floor，见 {@link #immediatePass}）。
   *
   * <p><b>为什么带世界名</b>：方块变更包里的坐标<b>不含世界标识</b>。合并窗口内玩家跨世界（传送门 /
   * {@code /tp}）时，若仍把旧世界的变更照常合并发出，客户端会把这些坐标当成<b>新世界</b>的坐标，
   * 表现为新世界对应位置出现「鬼影方块」，直到该区块被重新下发。有了世界名，封包线程就能在
   * 「快照世界 ≠ 索引世界」时判定为<b>不一致</b>并即时放行原包（见 {@code BlockChangeMerger}）；
   * 跨世界丢弃仍然只认「已证实的会话/世界更替」，绝不拿单侧快照当丢弃依据。
   *
   * <p>世界名由<b>拥有该玩家的线程</b>读取后写入，封包线程只读这份快照——与坐标同一纪律，绝不跨线程读
   * {@code Player#getWorld()}。
   *
   * <p><b>{@code positionKnown}</b>：坐标是否可信。黑名单世界里坐标捕获被有意跳过
   * （见 {@link #refreshWorldOnly}），此时该字段为 {@code false}、三个坐标字段无意义；读取侧必须按
   * fail-open 处理（按近身立即放行），绝不能拿它算距离。
   */
  record Position(String world, double x, double y, double z, boolean positionKnown) {
  }

  /**
   * 一次连接的持有者：位置快照 + 心跳 + 会话号。
   *
   * <p>字段全部 volatile：位置与心跳由拥有线程每 tick 写、封包线程无锁读。两者在同一对象上，
   * 读取侧看到的一定是「某一次连接」的数据，不会出现「新会话的世界 + 旧会话的坐标」这种跨代拼接。
   * 位置与心跳是两次独立的 volatile 写，读者可能读到「新位置 + 上一 tick 的心跳」——那只影响
   * {@link #isStale(UUID)} 的判定精度（最多早一个 tick 判过期，方向是 fail-open），不影响正确性。
   */
  static final class Session {
    private final UUID playerId;
    private final long id;
    private volatile Position position;
    private volatile long stamp;

    private Session(UUID playerId, long id) {
      this.playerId = playerId;
      this.id = id;
    }

    UUID playerId() {
      return playerId;
    }

    /** 会话号（进程级单调递增，仅用于诊断与日志）。 */
    long id() {
      return id;
    }

    Position position() {
      return position;
    }

    /** 心跳是否已过期；从未刷新同样算过期。 */
    boolean isStale(long now) {
      long last = stamp;
      return last == 0L || now - last > STALE_NANOS;
    }

    void touch(long now) {
      stamp = now;
    }
  }

  /**
   * 快照「心跳」阈值：刷新任务是每 tick 执行的，超过这个时长没有心跳就说明它已经不再运行。
   *
   * <p><b>为什么需要心跳</b>：Folia 的实体调度器任务会随实体被移出旧 world（切维、重生）而退役，
   * 而退役并<b>不保证</b>回调到我们的清理入口——结果就是快照被冻结在切换前的那一刻，并且看起来一切正常：
   * 世界名还是旧世界、坐标还是旧坐标。冻结的快照比「没有快照」危险得多：近身判定会把所有变更判成「远」
   * （全部进合并窗口），世界归属判定也会拿着一份旧世界名去和索引比对。实测（29 人 / 4 小时的 dump）仅这
   * 一条就丢了 69.5 万个原包，玩家侧表现是假方块挖不掉、右键才消失。
   *
   * <p>心跳只能证明「还有人在写」，<b>不能</b>证明「写的人拥有这个实体」。1.34.13 生产 dump 里
   * 「快照重同步 1234102 / 冲刷 2428522」证明的是两源长期冲突，尚未证明是哪一侧——可能的原因有：
   * 旧实体上的任务仍在非拥有线程读实体（心跳新鲜、世界陈旧）、索引漏接/错序了切维事件、或切维事件与
   * 本任务的重排之间存在竞态。因此写侧另有一道归属闸与一道会话闸（见
   * {@code BlockChangeMerger#refreshPosition}），并用 {@code ThrottleStats#indexRepaired} /
   * {@code #worldConflict} 在下一次 dump 里区分这两种候选根因。
   */
  static final long STALE_NANOS = 5_000_000_000L;

  /** 当前连接持有者：每玩家一条，换代即换对象。 */
  private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

  /** 会话号分配器（进程级单调递增；不参与任何判定，只用于诊断）。 */
  private final AtomicLong sessionSeq = new AtomicLong();

  /** 时钟（测试可注入，生产即 {@link System#nanoTime}）。 */
  private final LongSupplier clock;

  PlayerPositionSnapshots() {
    this(System::nanoTime);
  }

  PlayerPositionSnapshots(LongSupplier clock) {
    this.clock = clock == null ? System::nanoTime : clock;
  }

  /**
   * 开始一次新会话（玩家加入 / 插件启用时的在线玩家快照）：换一个持有者，旧持有者立即失效。
   *
   * <p>{@code put} 是原子的：旧任务即便正在写，也只会写进它自己手里的旧持有者，表里已经是新对象——
   * 不存在「先清空、再被旧任务写回」的窗口。
   *
   * @return 新会话（恒非 {@code null}）
   */
  Session beginSession(UUID playerId) {
    if (playerId == null) {
      return null;
    }
    return sessions.compute(playerId,
        (key, previous) -> new Session(key, sessionSeq.incrementAndGet()));
  }

  /** 当前会话；没有会话（未登录 / 已退出 / 停用）时为 {@code null}。 */
  Session session(UUID playerId) {
    return playerId == null ? null : sessions.get(playerId);
  }

  /** 该会话是否仍是表里的当前会话（迟到的刷新/退役必须据此停手）。 */
  boolean isCurrent(Session session) {
    return session != null && sessions.get(session.playerId()) == session;
  }

  /**
   * 只失效「位置快照 + 心跳」，<b>保留连接会话</b>。
   *
   * <p>用于「这次读不到世界 / 读实体抛异常 / 本轮刷新任务退役」这类<b>临时</b>失败：读取侧立刻 fail-open
   * （入口一律即时放行），但会话令牌还在——否则一次瞬时读失败就会被下游当成「换了连接」，把已有待发批
   * 判成上一代连接而整批不投递，属于用临时故障换永久损失。
   */
  void invalidate(Session session) {
    if (!isCurrent(session)) {
      return;
    }
    session.position = null;
    session.stamp = 0L;
  }

  /** 结束会话（退出 / 停用）：此后读取侧一律 fail-open。 */
  void endSession(UUID playerId) {
    if (playerId != null) {
      sessions.remove(playerId);
    }
  }

  /**
   * 刷新某玩家快照（必须在<b>拥有该玩家的线程</b>调用）。方块坐标与世界均未变时不发布，见类注释。
   *
   * @param session 本任务的会话（见 {@link #beginSession(UUID)}）；已不是当前会话时本方法无操作
   * @return <b>刷新前</b>快照的世界名（原本没有快照时为 {@code null}），未发布时返回当前世界名
   *         （此时等于入参 {@code world}）。调用方据此检测跨世界切换——旧世界的方块变更缓冲必须丢弃，
   *         绝不能按新世界坐标下发（见 {@code BlockChangeMerger}）
   */
  String refresh(Session session, String world, double x, double y, double z) {
    if (!isCurrent(session)) {
      return null;
    }
    session.touch(clock.getAsLong());
    Position current = session.position;
    // positionKnown 必须一起判：黑名单世界留下的「世界名相同但坐标未知」快照不能被当成「坐标未变」而
    // 跳过发布，否则切出黑名单世界后坐标会一直是未知、近身判定永远 fail-open。
    if (current != null && current.positionKnown() && Objects.equals(current.world(), world)
        && blockCoordinate(current.x()) == blockCoordinate(x)
        && blockCoordinate(current.y()) == blockCoordinate(y)
        && blockCoordinate(current.z()) == blockCoordinate(z)) {
      // 仍在同一世界、同一方块：判定结果不可能改变，跳过分配与写入。
      // 返回当前世界名（= world）：调用方只据此判断「世界是否变化」，此处不得触发丢弃。
      return current.world();
    }
    session.position = new Position(world, x, y, z, true);
    return current == null ? null : current.world();
  }

  /**
   * 只刷新「当前世界」、<b>不读取也不更新坐标</b>（黑名单世界专用，见类注释）。
   *
   * <p>世界未变且坐标本已标记为未知时不发布（每 tick 零分配、零写入）；世界变化、或坐标由「已知」
   * 转「未知」时发布一次。调用方在离开黑名单世界后改回 {@link #refresh}，即可恢复精确坐标。
   *
   * @return <b>刷新前</b>快照的世界名（原本没有快照时为 {@code null}），与 {@link #refresh} 语义一致
   */
  String refreshWorldOnly(Session session, String world) {
    if (!isCurrent(session)) {
      return null;
    }
    session.touch(clock.getAsLong());
    Position current = session.position;
    if (current != null && !current.positionKnown() && Objects.equals(current.world(), world)) {
      return current.world();
    }
    session.position = new Position(world, 0.0D, 0.0D, 0.0D, false);
    return current == null ? null : current.world();
  }

  /** 只读快照；尚未建立会话时返回 {@code null}（调用方按 fail-open 处理）。 */
  Position get(UUID playerId) {
    if (playerId == null) {
      return null;
    }
    Session session = sessions.get(playerId);
    return session == null ? null : session.position();
  }

  /**
   * 该玩家的快照是否已经<b>过期</b>（没有会话 / 从未刷新也算过期）。
   *
   * <p>过期即视为「未知」：近身判定按立即放行（fail-open），世界归属判定按「无法证明玩家已换世界」
   * 处理（照常交付，绝不整批丢弃）。判据是心跳而不是内容——内容看起来完全正常恰恰是冻结点最
   * 会骗人的地方（世界名与坐标都停留在切换前）。
   */
  boolean isStale(UUID playerId) {
    if (playerId == null) {
      return true;
    }
    Session session = sessions.get(playerId);
    return session == null || session.isStale(clock.getAsLong());
  }

  /** 清空全部会话（插件停用）。 */
  void clear() {
    sessions.clear();
  }

  /**
   * 当前会话数。
   *
   * <p>测试专用豁免：生产路径不读该值，本方法当前仅单测在用，保留以免破坏测试。
   */
  int size() {
    return sessions.size();
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
    if (snapshot == null || !snapshot.positionKnown()) {
      // fail-open：无快照 / 坐标未知 → 按近身处理（宁可少合并，绝不制造延迟）。
      // 坐标未知时绝不拿与当前世界无关的旧坐标算距离（会把近身变更误判为远、白吃一个窗口）。
      return true;
    }
    return BlockChangeBatch.anyWithinRadius(updates, blockCoordinate(snapshot.x()),
        blockCoordinate(snapshot.y()), blockCoordinate(snapshot.z()), radius);
  }

  /**
   * 坐标版近身判定：供「只解坐标、不构造方块数据」的快速路径使用，语义与列表版逐条一致。
   *
   * @param coordinates 绝对坐标三元组（{@code x,y,z} 连续，前 {@code count} 个）
   */
  static boolean immediatePass(Position snapshot, int[] coordinates, int count, int radius) {
    if (radius <= 0) {
      return false;
    }
    if (snapshot == null || !snapshot.positionKnown()) {
      return true;
    }
    return BlockChangeBatch.anyWithinRadius(coordinates, count, blockCoordinate(snapshot.x()),
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
