package net.mikumc.mikuxraynet.bandwidth;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.antixray.SectionCoords;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.util.PlayerWorldIndex;
import net.mikumc.mikuxraynet.util.PlayerWorldIndex.WorldContext;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * 方块变更合并：把同一玩家在短时间窗内的多条 {@code BLOCK_CHANGE} / {@code MULTI_BLOCK_CHANGE}
 * 按配置 {@code block-changes.merge-radius}（默认 2）的曼哈顿邻域聚簇，并按 section 分组重建成更少的合并包。
 *
 * <p><b>同步监听 + 取消原包</b>：命中合并窗口的变更<b>先入有界缓冲、随即取消原包</b>（两步在同一把锁内完成，
 * 不存在「已取消却没进缓冲」的丢更新）→ 时间窗到期（或条目超限）时冲刷 → 合并包经
 * {@link ProtocolManager#sendServerPacket}（Netty 安全）发出；构包/发送失败则按 section 逐条补发原更新。
 *
 * <p><b>近身变更立即放行</b>：距离玩家 {@code block-changes.immediate-radius}（默认 8 格）以内的
 * 方块变更<b>不进合并窗口</b>，原包立即放行——玩家自己挖/放方块时目标就在脚边，被窗口延迟会让
 * 客户端预测得不到确认，表现为「挖掘时顿一下」。半径外的变更（爆炸、大面积刷新等）照常合并。
 * 判定只读一份由实体调度器<b>每 tick 刷新</b>的位置快照（见 {@link PlayerPositionSnapshots}）：
 * 封包线程不触碰实体状态，且刷新跟随实体/区域归属，因此传送等任何位置变化都会在一个 tick 内生效
 * （旧实现依赖 {@code PlayerMoveEvent}，而传送有独立 HandlerList，会按陈旧坐标判错）。
 *
 * <p><b>fail-open</b>：读不出字段、构包失败、定时器排不上、玩家离线 —— 一律原样放行原包，绝不丢更新；
 * 只有「已确认写入缓冲」的包才会被取消。位置快照不可用（尚未建立 / 心跳过期 / 刷新或读取异常）时同样
 * fail-open：近身判定按「立即放行」处理，宁可少合并、绝不制造延迟。
 *
 * <p><b>入口两源一致闸</b>：方块变更包不含世界标识，因此「这批坐标属于哪个世界」只能靠两份来源推断——
 * {@link PlayerWorldIndex}（玩家事件驱动）与 {@link PlayerPositionSnapshots}（拥有线程每 tick 读实体）。
 * 只有两者<b>同会话、同世界、坐标已知、心跳新鲜</b>时本类才允许入缓冲；任一侧未知或两侧冲突时
 * <b>原包即时放行</b>（不进窗口、不按任何一侧的旧世界名打标、不延迟），计数见
 * {@code ThrottleStats#worldUnknown} / {@code #worldConflict}。
 * 这条闸是 1.34.13 生产 dump 的直接回归：那份 dump 里「快照重同步 1234102 / 冲刷 2428522」，
 * 处置环反复出现「位置快照（world）与索引（minecraft_lobby）矛盾」——两源长期不一致时，旧实现仍把每个包
 * 按索引世界入缓冲，再在冲刷时整批补发：既不丢更新，也永远修不好近身判定（每批都白吃一个窗口）。
 *
 * <p><b>last-write-wins</b>：窗口内已经由「立即放行」路径下发过新状态的坐标，会在冲刷时从合并结果里
 * 剔除（见 {@link #dropPassed}）——旧态晚到会把先到的新态覆盖回去（方块回退）。剔除后没有剩余坐标时
 * 直接不发包。
 *
 * <p><b>结构自检是「每次发送」而非「首包」</b>：{@code sendSection} 每次构造完合并包都会回读刚写入的
 * 字段做结构校验（单条与多条的路径都做）。校验不过即放弃本次合并结果、按 section 逐条补发原更新——
 * 原包此时已被取消，退化成逐条补发是唯一能保证「不丢更新」的做法。
 *
 * <p><b>冲刷丢弃只认「两源一致地证明换了世界」</b>：缓冲把世界上下文（{@link WorldContext}，含世界名与
 * 进程级代次）与<b>接收连接</b>（{@link PlayerPositionSnapshots} 的连接会话）一起固定。冲刷与送出前按序判定：
 * ① <b>接收连接已换代</b>（或世界身份换代但名字相同）→ 上一代，不投递
 * （与快照新鲜度无关：新连接可能还没有第一条位置快照）；
 * ② 索引上下文 / 活快照缺失或心跳过期 → 世界未知，照常交付；③ 索引与快照不一致 → 冲突，照常交付；
 * ④ 索引已换代、活快照与索引同世界、且与固定的世界不同 → 真切维，整批丢弃。单侧证据永远不是丢弃依据
 * （历史事故 69.5 万包正是拿冻结快照整批丢的）。
 *
 * <p><b>不变量（取消即欠账）</b>：凡本类 {@code event.setCancelled(true)} 过的包，<b>必须</b>经
 * {@link #deliver} 的合并包或 {@link UpdateDelivery#deliverEach} 的逐条补发之一离开本模块；
 * 整批丢弃只允许在「已证明玩家换到了别的世界、客户端会整列重建该世界区块」时发生（事件 / 黑名单入口
 * 用 {@link #shouldRedeem}，冲刷入口用 {@link FlushWorld#classify}；同名世界重连按「上一代连接」不投递，
 * 同样不丢有效更新）。交付末段的任何一步都不允许把异常当成「玩家已离线」：
 * 探测失败按「未知、照常发送」处理，单条失败只损失该条并计入 {@code ThrottleStats#sendFailed}。
 * 违反这条不变量的表现就是「门只开一半、另一半永远不动」。
 *
 * <p><b>已聚合的批量包直接放行</b>：{@code MULTI_BLOCK_CHANGE} 若携带的坐标数达到
 * {@link #MULTI_BLOCK_BATCH_THRESHOLD}，视为「本已是一次批量下发」（反矿透的邻近显形正是
 * 由 Paper 原生 {@code sendMultiBlockChange} 按区块段一次性发出），直接放行原包——不进 Pending、
 * 不延迟、不重编码。此类包再进合并窗口的边际收益极小，却要额外吃满 {@code merge-window-millis}
 * （默认 40ms）的延迟（超过立即放行半径的显形变更尤为明显）。判定只看包自身的坐标数量（不看世界），
 * 且在<b>解析之前</b>就生效：命中即直通，并顺手登记「这些坐标已有新态下发」（有待发缓冲时按
 * packed short 直接解包记账，不必解析出 {@code WrappedBlockData}）。这层标记不能省——否则缓冲里
 * 同坐标的旧态会在窗口到期时把本包刚下发的新态覆盖回去（方块回退）。
 *
 * <p><b>last-write-wins 的微秒级残余窗口（已接受，勿当 bug 修）</b>：{@code passed} 集合是在
 * 持锁的临界区里「读取 + 剔除旧态」的，但把合并包发到客户端发生在<b>释放锁之后</b>。
 * 因此「本窗口内某坐标经立即放行先行下发」与「合并包真正抵达客户端」之间存在一个微秒级窗口：
 * 若该坐标恰在此窗口内又发生一次新的变更，客户端理论上可能在极短时间内看到「新→旧→新」的抖动。
 * 后果仅为<b>一帧级</b>的方块观感抖动，不丢更新、不影响判定，且窗口宽度远小于客户端一帧；
 * 引入跨线程顺序屏障来消除它得不偿失，故此处<b>明确接受该窗口并文档化</b>，不改变行为。
 */
public final class BlockChangeMerger extends PacketAdapter implements Listener {

  /** 冲刷批次内的 section 分组键。 */
  private record SectionKey(int x, int y, int z) {
  }

  /** 方块坐标（用于「本窗口内已立即放行的坐标」做 last-write-wins 判定）。 */
  record Coord(int x, int y, int z) {
  }

  /**
   * 「已聚合批量」的坐标数阈值：{@code MULTI_BLOCK_CHANGE} 携带的坐标数达到该值即视为
   * 本已是一次批量化下发，直接放行、不进合并缓冲。
   *
   * <p><b>为什么取 8</b>：合并窗口的收益来自把「分散的小变更」拼成更少的包——坐标数很少
   * （约 2~7 个）的批量仍可能与邻域变更拼包，值得合并；达到 8 个以上时本包已是成形的批量下发
   * （反矿透显形包按区块段一次发出，通常数十至上千个坐标），再进 40ms 窗口拼包的边际收益极小，
   * 却要额外付「解析 → 缓冲 → 延迟 → 重编码」的成本。8 与默认立即放行半径（8 格）同量级、便于理解。
   * 做成常量便于日后按基准数据调整。
   */
  static final int MULTI_BLOCK_BATCH_THRESHOLD = 8;

  /**
   * 纯函数判定：一个出站方块变更包是否「已聚合成批量」而应直接放行、不进合并窗口。
   *
   * <p>只依赖包形态（是否为 {@code MULTI_BLOCK_CHANGE}）与包自身携带的坐标数量，<b>不看玩家、
   * 不看世界</b>，便于离线单测。{@code BLOCK_CHANGE} 恒只含 1 个坐标，因此永远走原合并路径。
   *
   * @param multiBlockChange 该包是否为 {@code MULTI_BLOCK_CHANGE}
   * @param coordCount       该包携带的坐标数
   */
  static boolean bypassesMerge(boolean multiBlockChange, int coordCount) {
    return multiBlockChange && coordCount >= MULTI_BLOCK_BATCH_THRESHOLD;
  }

  /**
   * 每个玩家的待发缓冲。
   *
   * <p><b>已有更新下发的坐标</b>（{@link #passed}）：本窗口内经「近身立即放行」或「远距离直通批量包」
   * 把<b>新状态</b>直接交给客户端的坐标——窗口到期构造合并包前据此把缓冲里的<b>旧状态</b>剔除，
   * 避免旧态晚到覆盖先到的新态（方块短暂回退）。
   *
   * <p><b>「过时」是相对的、可撤销</b>：同坐标若之后又被重新缓冲，{@code onPacketSending} 会立即把
   * 该坐标从本集合移除（此刻缓冲条目才是最新的）——因此它不会把「更新的变更」当旧态误删。
   * 只在持有该 {@code Pending} 的锁内读写。
   *
   * <p>只记当前缓冲坐标与直通坐标的交集。无关的大包不会挤掉抑制旧态所需的记号。
   */
  private static final class Pending {
    private final UUID uuid;
    /** 本窗口内被取消（改为窗口末合并下发）的原包数，供统计与「是否有实包」判定。 */
    private int cancelledPackets;
    /**
     * 本缓冲内坐标所属的世界上下文（入缓冲时由封包线程从 {@link PlayerWorldIndex} 读取后在锁内固定；
     * {@code null} = 尚未入过包）。
     *
     * <p><b>为什么需要它</b>：方块变更包<b>不含世界标识</b>，冲刷时无法从包本身判断「这些坐标属于哪个世界」。
     * 固定的不只是世界名，还有该世界的<b>代次</b>（{@code session}）：同名世界重连时世界名一模一样，
     * 只有代次能区分「同一世界的同一代连接」与「重连后的新连接」。冲刷时据此判定
     * {@code SAME / UNKNOWN / CONFLICT / CHANGED / PREVIOUS_CONNECTION}（见 {@link FlushWorld}）。
     *
     * <p>只在 {@code synchronized (target)} 内读写；{@link #worldName()} 供留痕与报文使用。
     */
    private WorldContext context;

    /**
     * 接收这批更新的<b>连接</b>（{@code PlayerPositionSnapshots} 的会话持有者，登录建立、退出结束）。
     *
     * <p><b>为什么不借世界代次充当连接</b>：世界代次只在「世界身份变化」时换代，同名世界重连前后完全
     * 相同；而连接换代与世界无关（退出重登、断线重连）。两者混用就会出现「上一连接的缓冲算成同一世界，
     * 发给新连接」——尤其在新连接还没产出第一条位置快照时，快照新鲜度也救不了。
     *
     * <p>刻意<b>不</b> pin {@code Player} 实例：Folia 上重生/切维会重建实体，同一连接会换对象；
     * 按实例比对会把「同一连接换了实体」误判成新连接而丢掉仍在有效期内的批。
     */
    private PlayerPositionSnapshots.Session connection;
    private final Set<Coord> buffered = new HashSet<>();
    private final Set<Coord> passed = new HashSet<>();
    private final BlockChangeBatch<WrappedBlockData> batch;
    private ScheduledFuture<?> timer;

    /** 缓冲坐标所属的世界名；尚未入过包时为 {@code null}（只用于留痕与报文）。 */
    private String worldName() {
      WorldContext pinned = context;
      return pinned == null ? null : pinned.worldName();
    }

    private void rememberPass(int x, int y, int z) {
      Coord coord = new Coord(x, y, z);
      if (buffered.contains(coord)) {
        passed.add(coord);
      }
    }

    private final AtomicBoolean flushed = new AtomicBoolean();
    /**
     * 溢出冲刷是否已排入冲刷线程（只在 {@code synchronized (target)} 内读写）。
     *
     * <p><b>为什么需要它</b>：{@link BlockChangeBatch#add} 一旦达到上限<b>此后每个包都返回 true</b>
     * （超限即真），而首个冲刷任务从提交到执行存在窗口；窗口内到达的每个溢出包都会再排一个
     * {@code flush} 任务——它们会因 {@code flushed} CAS 全部成 no-op，但白占单线程冲刷队列。
     * 加此闸后同一 Pending 至多排一次，窗口内的后续溢出包直接复用在途任务。
     */
    private boolean overflowFlushScheduled;

    private Pending(UUID uuid, int maxEntries) {
      this.uuid = uuid;
      this.batch = new BlockChangeBatch<>(maxEntries);
    }
  }

  /**
   * 一次「每 tick 位置刷新」的注册：会话 + 句柄 + 退役/失主标志。
   *
   * <p>退役回调跑在关键代码里，只能做内存记账；它必须能回答「摘的是不是我自己这条」——因此回调持有本
   * 对象、用 {@code positionTasks.remove(playerId, slot)} 按<b>注册身份</b>摘除：旧实体迟到的退役摘不到
   * 重建后的新注册，也删不掉新会话的位置快照（{@code PlayerPositionSnapshots#invalidate} 只作用于仍是
   * 当前会话的那一条）。
   */
  static final class RefreshSlot {
    /** 登记时的连接会话（{@code PlayerPositionSnapshots} 的持有者；换代即换对象）。 */
    final PlayerPositionSnapshots.Session session;
    /** 登记时该玩家实体的调度器：实体被重建后 owner 会变，旧注册据此判为「已不可能再服务」。 */
    final EntityScheduler owner;
    /** 调度返回的句柄（调度失败时不会入表）。 */
    volatile ScheduledTask handle;
    /** 退役回调已到达（只做记账，不得操作实体/世界）。 */
    volatile boolean retired;
    /** 已判定为「跑在非拥有线程」的僵尸注册：只允许触发一次重建。 */
    volatile boolean ownerLost;

    RefreshSlot(PlayerPositionSnapshots.Session session, EntityScheduler owner) {
      this.session = session;
      this.owner = owner;
    }

    /**
     * 未退役、句柄在、未被取消，且 owner 没被换掉。
     *
     * <p>owner 比较不可省：同一玩家重生/切维后实体被重建，旧实体上的任务即便还没被服务端取消，也
     * 不可能再服务这个玩家——只按 {@code isCancelled()} 判断会把它当成「已有登记」而拒绝重排。
     */
    boolean isLive(EntityScheduler current) {
      ScheduledTask task = handle;
      return !retired && task != null && !task.isCancelled() && owner == current;
    }
  }

  /**
   * 冲刷前对「这批已取消的坐标还能不能交给当前连接」的判定表（唯一实现，纯函数）。
   *
   * <p>三段证据缺一不可：入缓冲时固定的世界上下文（{@code pinned}）、此刻的世界索引上下文
   * （{@code now}）、此刻的位置快照（{@code snapshotFresh} + {@code snapshotWorld}）。
   * 任何单侧证据都不构成丢弃理由——历史事故（69.5 万包）正是拿冻结快照当证据整批丢掉的。
   */
  enum FlushWorld {
    /** 两源一致：仍是入缓冲时的那个世界，照常交付。 */
    SAME,
    /** 世界未知（索引或快照缺失 / 心跳过期）：无法证明换过世界，按「不丢」交付。 */
    UNKNOWN,
    /** 两源冲突（索引未换代却世界不同，或换代后两源不一致）：按「不丢」交付，保留快照等对账修正。 */
    CONFLICT,
    /** 已证明换世界（同一连接下索引换代 + 活快照与索引同世界 + 与固定世界不同）：整批丢弃。 */
    CHANGED,
    /**
     * 接收连接已换代（同名世界重连），或世界身份换代但名字相同（世界重载）。
     *
     * <p>两者都与快照新鲜度无关：前者不能在「新连接还没有第一条位置快照」时被降级成「世界未知」，
     * 后者说明固定下来的旧上下文已经失效。结论一致：不投递给当前连接（新世界/新连接会重新下发区块）。
     */
    PREVIOUS_CONNECTION;

    /**
     * 判定「这批坐标能不能交给当前连接」。
     *
     * <p><b>为什么必须两源</b>：索引由玩家事件驱动（切维/重连时才换代），位置快照由拥有线程每 tick
     * 读实体。只认索引，会把「索引漏接事件」当成没换世界（把旧世界坐标发进新世界 = 鬼影方块）；
     * 只认快照，会把「刷新任务跑在旧实体上」当成换了世界（整批丢弃 = 永久缺格，实测 69.5 万包）。
     *
     * @param pinned         入缓冲时固定的世界上下文（可为 {@code null}：历史缓冲）
     * @param now            此刻的索引上下文（{@code null} = 索引未知）
     * @param snapshotFresh  位置快照是否存在且心跳新鲜
     * @param snapshotWorld  位置快照里的世界名（{@code snapshotFresh=false} 时必须为 {@code null}）
     * @param sameConnection 此刻的接收连接是否仍是入缓冲时的那个连接（见 {@code Pending#connection}）
     */
    static FlushWorld classify(WorldContext pinned, WorldContext now, boolean snapshotFresh,
        String snapshotWorld, boolean sameConnection) {
      if (!sameConnection) {
        // 接收者已换代：这与世界、快照新鲜度都无关——新连接会重新收到区块，绝不能把上一连接的
        // 已取消批次发给它。这一条必须排在最前：新连接刚上线时往往还没有第一条位置快照，
        // 若先看快照新鲜度，就会落到 UNKNOWN 分支而被「按不丢交付」发出去。
        return PREVIOUS_CONNECTION;
      }
      if (pinned == null) {
        // 没有固定上下文（历史缓冲 / 停用瞬间）：按原行为交付，绝不凭猜测丢弃
        return SAME;
      }
      if (now == null || !snapshotFresh || snapshotWorld == null) {
        return UNKNOWN;
      }
      if (now.session() == pinned.session()) {
        // 索引没换代：同一世界仍是唯一可证明的结论。快照若不同意，那是「两源冲突」，不是换世界。
        return snapshotWorld.equals(pinned.worldName()) ? SAME : CONFLICT;
      }
      if (!shouldDiscardAtFlush(pinned.worldName(), now.worldName())) {
        // 世界身份换了一代但名字相同（世界重载 / 高度变化）：固定下来的旧上下文已失效，不按新世界投递
        return PREVIOUS_CONNECTION;
      }
      // 索引换代、世界不同：还必须活快照独立同意，否则是快照陈旧（冲突），不丢
      return snapshotWorld.equals(now.worldName()) ? CHANGED : CONFLICT;
    }
  }


  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final BandwidthConfig.BlockChanges config;
  private final ThrottleStats stats;
  private final ScheduledThreadPoolExecutor flusher;
  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
  /**
   * 最近处置环（取证用，容量固定 32 条）：只记多坐标包与「丢弃 / 补发 / 合批校验失败」。
   *
   * <p>面板上的总数回答不了「这两片门到底怎么样了」——实机反馈「门只开一半」时，一份 dump 只能显示
   * 丢弃与合批的总量。环里留下最近 32 次处置的坐标与结论，下一次 dump 就能直接指出那两片门是
   * PASS、MERGE、DISCARD 还是 VERIFY_FAIL。
   */
  private final BlockUpdateTrace trace = new BlockUpdateTrace();
  /**
   * 玩家位置快照（由实体调度器在玩家所属线程每 tick 刷新，封包线程只读）。
   *
   * <p>取代旧的 {@code PlayerMoveEvent} 坐标缓存：传送有独立 HandlerList，缓存会陈旧。
   *
   * @see PlayerPositionSnapshots
   */
  private final PlayerPositionSnapshots positions = new PlayerPositionSnapshots();

  /**
   * 实体归属判定：刷新任务只允许在<b>拥有该玩家</b>的线程写快照。
   *
   * <p>生产实现是 {@code Bukkit.isOwnedByCurrentRegion}；离线单测可注入假实现（同 {@code EntityCuller}
   * 的做法）。这道闸针对的<b>候选根因</b>是：Folia 上被移出旧 world 的实体任务仍可能再跑一次，它在非拥有
   * 线程读到的 {@code getWorld()} 还是旧世界，却会把心跳刷成新鲜的（「心跳新鲜、世界陈旧」的僵尸快照）。
   * 1.34.13 生产 dump 只能证明两源冲突（「快照重同步 1234102 / 冲刷 2428522」），不能单独证明这条路径；
   * 另一种同形原因是索引漏接切维事件，由 {@code ThrottleStats#indexRepaired} 与 {@code #worldConflict} 区分。
   */
  @FunctionalInterface
  interface PlayerOwnership {
    boolean isOwnedByCurrentRegion(Player player);
  }

  /** 生产实现：{@link Bukkit#isOwnedByCurrentRegion(org.bukkit.entity.Entity)}（判定不可用时按「属于本区域」）。 */
  private static boolean ownedByCurrentRegion(Player player) {
    try {
      return Bukkit.isOwnedByCurrentRegion(player);
    } catch (Throwable throwable) {
      // 判定不可用（离线单测无服务端实例等）：退回「属于本区域」，与改动前行为一致
      return true;
    }
  }

  private final PlayerOwnership ownership;

  /** 每玩家的位置刷新注册（会话 + 句柄；退役回调按注册身份摘除，见 {@link RefreshSlot}）。 */
  private final ConcurrentHashMap<UUID, RefreshSlot> positionTasks = new ConcurrentHashMap<>();
  /**
   * 停用中标记：{@link #stop()} 首行置位，供 {@link #schedulePositionRefresh} 做「入账后再读一次」收口。
   *
   * <p>Folia 上 stop() 跑在命令线程、onJoin→schedulePositionRefresh 跑在玩家区域线程，两者真正并发；
   * 没有这道闸，stop() 的取消循环可能先跑完，之后入表的位置刷新任务（<b>每 tick</b> 执行）
   * 就永远无人取消。
   */
  private volatile boolean stopping;
  /**
   * 位置刷新看门狗（全局区域线程，低频）。
   *
   * <p>刷新任务只在 登录 / 复活完成 / 切维 / 启用 四个时机登记。若某次登记落在已退役实体上（调度返回
   * {@code null}）或任务被服务端取消，该玩家会永久停在「快照过期 → 入口一律即时放行」的 fail-open 态：
   * 不丢更新、不制造延迟，但块变更合并对他完全停摆。看门狗把这个停摆限制在 ≤5 秒。
   */
  private volatile ScheduledTask watchdog;
  private final AtomicInteger errorCounter = new AtomicInteger();
  /** 「位置快照不可用 → 近身判定一律立即放行」的一次性提示闸。 */
  private final AtomicBoolean snapshotFailOpenNoticed = new AtomicBoolean();
  /**
   * 「方块数据契约探测」的<b>进程内</b>一次性闸门（{@code static}）。
   *
   * <p><b>为什么必须静态</b>：热重载会重建 {@link BlockChangeMerger} 实例，实例级闸门会被重置 →
   * 每次 reload 都重新探测一遍 ProtocolLib 的静态契约、并（在失败时）重复刷屏同一条 WARN。
   * 该契约在一个 JVM 进程内不会变（同一份 ProtocolLib/服务端类加载结果），因此探测结果天然可复用：
   * 进程内只探测一次，reload 不重复探测、不重复告警。
   */
  private static final AtomicBoolean blockDataProbeDone = new AtomicBoolean();

  /** 是否已注册（同步监听器没有句柄，用标志位保证 start/stop 幂等）。 */
  private volatile boolean registered;

  public BlockChangeMerger(Plugin plugin, ProtocolManager protocolManager,
      BandwidthConfig.BlockChanges config, ThrottleStats stats) {
    this(plugin, protocolManager, config, stats, BlockChangeMerger::ownedByCurrentRegion);
  }

  /**
   * 完整构造：追加实体归属判定（离线单测可注入假实现，用于覆盖「刷新跑在非拥有线程」的僵尸快照分支）。
   *
   * <p>生产装配链（{@code ThrottlePipeline}）只用上面的公开构造，注入点仅为离线用例存在——与
   * {@code EntityCuller} 的 {@code EntityOwnership} 同一做法。
   */
  BlockChangeMerger(Plugin plugin, ProtocolManager protocolManager,
      BandwidthConfig.BlockChanges config, ThrottleStats stats, PlayerOwnership ownership) {
    super(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.stats = stats;
    this.ownership = ownership == null ? BlockChangeMerger::ownedByCurrentRegion : ownership;
    this.flusher = new ScheduledThreadPoolExecutor(1, runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-BlockMerge");
      thread.setDaemon(true);
      return thread;
    });
    this.flusher.setRemoveOnCancelPolicy(true);
  }

  /**
   * 注册<b>同步</b>监听器与每 tick 的位置快照刷新任务，并做一次启动期契约探测。
   *
   * <p><b>为什么必须同步</b>：异步注册会把<b>所有</b>该类封包（包括本该直通、不参与合并的近身变更）
   * 扣进异步队列。服务端随后在同一 tick 下发的 {@code LIGHT_UPDATE} 不受我们约束、原样先到，
   * 客户端就会按「旧方块 + 新光照」渲染一帧——真机观感是刚放/刚挖的那一格闪一下。
   * 改成同步后：直通分支原包就地流出（线序不变）；需要合并的分支<b>取消</b>原包并在窗口末补发合并包
   * （合并包用 {@code sendServerPacket(..., filters=false)} 发出，不会再进本监听器）。
   */
  public void start() {
    // 启动期探测一次：让「服务端结构变更导致合并静默失效」可见（不改变任何行为）
    probeBlockDataContract();
    protocolManager.addPacketListener(this);
    registered = true;
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    // 位置快照任务「始终登记」：它不只服务近身判定，还承载「玩家当前世界」——入口一致闸与冲刷判定
    // 都要靠它（见 refreshPosition / verifiedSnapshot）。immediate-radius=0 时快照只用于世界归属：
    // 静止玩家的每 tick 开销仅为一次字段读（方块坐标与世界都未变时不发布快照），可忽略。
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      // 启用即一代新会话（热重载会重建本类实例，旧实例的会话令牌不跨实例复用）
      positions.beginSession(player.getUniqueId());
      schedulePositionRefresh(player);
    }
    startWatchdog();
    plugin.getLogger().fine("带宽模块已启用：方块变更合并（时间窗 " + config.mergeWindowMillis()
        + "ms，邻域半径 " + config.mergeRadius() + "，立即放行半径 " + config.immediateRadius()
        + " 格，缓冲上限 " + config.maxPendingEntries() + "）");
  }

  /** 注销监听器并偿还所有正在延迟中的原包。 */
  public void stop() {
    // 首行置位：此后任何迟到的 schedulePositionRefresh 都会自行取消（见 stopping 字段说明）。
    stopping = true;
    // 顺序很关键：先注销监听器（此后不再有新包被取消），再排空已取消的批次并补发。
    if (registered) {
      registered = false;
      try {
        protocolManager.removePacketListener(this);
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "注销方块变更监听器时出现异常（通常可忽略）", throwable);
      }
    }
    flushAll();
    HandlerList.unregisterAll(this);
    cancelWatchdog();
    cancelAllPositionRefreshTasks();
    positions.clear();
    flusher.shutdownNow();
    // 兜底再排空一次：注销与 shutdownNow 之间若仍有在途包成功入缓冲并 schedule 成功（定时器随后被
    // shutdownNow 丢弃），这里把它们放行；此后 flusher 已 shutdown，再入缓冲会因 schedule 抛异常而
    // 立即原样放行（见 onPacketSending 的 fail-open 处理），因此停用瞬间绝不滞留任何原包（恰好放行一次）。
    flushAll();
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    // 新连接 = 新会话：旧连接（同 UUID）迟到的刷新/退役从此写不进、也删不掉这一代会话。
    // 同名世界重连时世界名一模一样，只有会话能区分两代连接——这是「同世界不同连接不能串」的起点。
    positions.beginSession(player.getUniqueId());
    // 与 start() 一致：位置快照同时承担世界归属判定，必须始终登记（见 start() 的说明）。
    schedulePositionRefresh(player);
  }

  /**
   * 切维丢弃（主路径）：世界已经切换的那一刻，由<b>玩家所属线程</b>同步丢弃旧世界的待发缓冲。
   *
   * <p><b>为什么用事件，而不是在冲刷线程里补判世界</b>：合并冲刷跑在普通异步线程
   * （{@code MikuXrayNet-BlockMerge}）上，Folia 下它读 {@code player.getWorld()} 属于跨区域读实体状态
   * （会先打 ERROR 再抛），而同一窗口内位置快照本身也是陈旧的（快照由玩家线程每 tick 刷新，切维瞬间
   * 仍停在旧世界）——冲刷侧没有任何可靠的世界来源可判。{@code PlayerChangedWorldEvent} 在
   * {@code ServerPlayer#changeDimension} 里、{@code setServerLevel(newLevel)} <b>之后</b>同步触发，
   * 因此事件到来时玩家世界已是新世界、而缓冲里的坐标必然全部属于旧世界：此刻丢弃既准确又及时，
   * 把「合并窗口定时器按墙钟触发」那条路径的窗口归零。
   *
   * <p>这是三道证据里的<b>主路径</b>（零窗口，覆盖传送门 / 跨世界传送等一切经由
   * {@code changeDimension} 的切维）：
   * <ol>
   *   <li>本事件：拿玩家此刻的真实世界名做 destination，直接判定「旧世界缓冲还要不要」；</li>
   *   <li>{@link #refreshPosition} 的每 tick 快照比对（覆盖「漏接本事件」的实现差异），同样用刚读到的
   *       真实世界名做 destination；</li>
   *   <li>冲刷前的 {@link FlushWorld#classify}（覆盖「切维后仍有旧世界旧包到达」）：只有索引换代 +
   *       活快照同世界才算证明。</li>
   * </ol>
   * 命中本事件后，旧世界封包会被入口一致闸按「原包即时放行」处理，不会重新入缓冲；若缓冲已存在，
   * 本事件内联丢弃（不聚簇、不构包、不发包）。
   */
  @EventHandler(ignoreCancelled = true)
  public void onChangedWorld(PlayerChangedWorldEvent event) {
    Player player = event.getPlayer();
    String destination = null;
    try {
      // 事件在实体所属线程触发，读世界合法；读不到时交回索引判定（discard 内部按 destination == null 处理）
      destination = player.getWorld() == null ? null : player.getWorld().getName();
    } catch (Throwable ignored) {
      // 世界正在卸载等极端情形：退回索引世界名
    }
    Pending target = pending.get(player.getUniqueId());
    if (target != null) {
      discard(target, ThrottleStats.DiscardSource.DIMENSION_EVENT, destination);
    }
    // 重排刷新任务：Folia 的实体调度器任务会随实体被移出旧 world 而退役，退役不保证回调到
    // retirePositionTask。不重排的话，那个玩家的位置快照就永远停在切维前那一刻——近身判定把所有变更
    // 判成「远」，入口一致闸又拿旧世界名与索引比对新世界名。重排后由拥有线程按真实世界重新发布快照，
    // 并顺带对账修正世界索引（见 refreshPosition）。
    schedulePositionRefresh(event.getPlayer());
  }

  /**
   * 复活完成：Folia 上玩家重生会重建实体，旧实体的调度器随之退役，位置刷新任务同样需要重排。
   *
   * <p><b>为什么挂 {@code PlayerPostRespawnEvent}</b>：{@code PlayerRespawnEvent} 触发时玩家尚未落到
   * 复活点（上游文档亦指明「改玩家状态请用 post 事件」），此时登记的任务会随旧实体退役、等于白排一次；
   * 本模块另两个每玩家周期任务（{@code EntityCuller} / {@code LatencyMonitor}）已按同一时序修正。
   *
   * <p>没有这条时，死亡一次就等于该玩家此后所有方块变更都按「冻结快照」判定（见上）。
   */
  @EventHandler(ignoreCancelled = true)
  public void onPostRespawn(PlayerPostRespawnEvent event) {
    schedulePositionRefresh(event.getPlayer());
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    cancelPositionRefresh(event.getPlayer().getUniqueId());
    // 玩家离线后再没有「新封包」会把残留在 Pending 里的原包冲刷掉（冲刷只由窗口定时器 / 溢出 /
    // 跨世界守卫触发），而那条 Pending 会一直挂在表里直到下一轮窗口定时器——期间它持有的
    // PacketEvent 在异步监听器的登记表里多留一份。这里直接把它的原包主动放行，不留悬空引用。
    flushNow(event.getPlayer().getUniqueId());
  }

  /**
   * 为玩家启动「每 tick 刷新位置快照」的实体调度任务。
   *
   * <p><b>为什么用实体调度器</b>：{@code player.getScheduler().runAtFixedRate(...)} 在 Paper 与 Folia
   * 上是同一套 API——Paper 上「实体所属线程」即服务端主线程，Folia 上即该玩家所在区域的区域线程，
   * 因此刷新永远运行在拥有该玩家的线程上（Folia 上绝不跨区域读实体状态），无需按平台二选一。
   *
   * <p><b>幂等</b>：同会话的注册仍存活时直接复用——同一 tick 里「复活完成」与「切维」各来一次时，
   * 不得给同一玩家登记两条每 tick 任务。
   */
  private void schedulePositionRefresh(Player player) {
    if (player == null || stopping) {
      return;
    }
    UUID playerId = player.getUniqueId();
    // 只复用「登录时建立的会话」，绝不在这里补建：看门狗/迟到回调拿着已退出玩家的 UUID 过来时，
    // 补建会话等于给一个已经离线的 UUID 留下永不清理的令牌与任务（退出事件早已走完）。
    PlayerPositionSnapshots.Session session = positions.session(playerId);
    if (session == null) {
      return;
    }
    EntityScheduler owner;
    try {
      owner = player.getScheduler();
    } catch (Throwable throwable) {
      // 实体已退役：登记无意义，读取侧 fail-open，看门狗下一轮用当前实体再补
      return;
    }
    RefreshSlot existing = positionTasks.get(playerId);
    // 同会话 + 同 owner + 句柄仍存活才复用：owner 换了（实体被重建）说明旧注册已不可能再服务这个玩家。
    if (existing != null && existing.session == session && existing.isLive(owner)) {
      return;
    }
    RefreshSlot slot = new RefreshSlot(session, owner);
    ScheduledTask task;
    try {
      task = Schedulers.repeatOnEntity(plugin, player, 1L, 1L,
          () -> refreshPosition(player, playerId, slot), () -> retirePositionTask(playerId, slot));
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return;
    }
    if (task == null) {
      // 实体已退役（调度失败）：无句柄可存，读取侧会因缺快照而 fail-open；看门狗会在 ≤5 秒内补上
      return;
    }
    slot.handle = task;
    RefreshSlot previous = positionTasks.put(playerId, slot);
    if (previous != null && previous != slot) {
      // 旧注册已被新注册替换：它的退役回调按身份摘不到新条目（见 retirePositionTask），句柄必须由这里取消，
      // 否则旧任务会继续按自己的周期跑（同一玩家两条刷新）
      previous.retired = true;
      cancelQuietly(previous.handle);
    }
    // 入账后再读一次 volatile stopping（理由同 EntityCuller.scheduleRecheck）：若 stop() 的取消循环
    // 已经跑完，它看不到这条任务，必须自行取消。
    if (stopping || slot.retired || !positions.isCurrent(session)) {
      positionTasks.remove(playerId, slot);
      cancelQuietly(task);
    }
  }

  /**
   * 玩家所属线程：用<b>零分配 getter</b>读取位置写入快照。
   *
   * <p>刻意不用 {@code getLocation()}——那会每 tick 每玩家 new 一个 {@code Location}。
   * 读取或写入异常只丢弃该玩家的快照（读取侧随即 fail-open，下一 tick 自动重建），
   * 任务句柄仍保留以便退出/停用时正常取消，且绝不把异常抛回调度器。
   *
   * <p><b>黑名单世界只维护世界名</b>：黑名单世界不做任何带宽优化，连坐标都不必读；但「当前世界」必须
   * 继续每 tick 写进快照，否则世界归属判定（切维事件 / 本方法比对 / 入口一致闸）全部失效。因此该分支走
   * {@link PlayerPositionSnapshots#refreshWorldOnly}（坐标标记为未知、近身判定 fail-open）；切出黑名单
   * 世界后自动回到下面的正常分支，下一次刷新即恢复坐标捕获。
   *
   * <p><b>三道闸，缺一不可</b>：
   * <ol>
   *   <li><b>注册身份</b>：本任务已被替换/退役就立刻停手（旧注册不得写新状态）；</li>
   *   <li><b>会话</b>：连接已换代（同名世界重连）就停手——旧会话的坐标绝不能进新连接；</li>
   *   <li><b>区域归属</b>：{@link PlayerOwnership} 判定不属于本区域时<b>不写快照、不刷心跳</b>，并重建任务。
   *       若缺这道闸，被移出旧 world 的实体任务仍可能继续把 {@code getWorld()} 的旧世界写进快照、同时把
   *       心跳刷成新鲜的（「僵尸快照」候选根因）。1.34.13 生产 dump 只能证明两源冲突
   *       （「快照重同步 1234102 / 冲刷 2428522」，处置环反复出现「位置快照（world）与索引
   *       （minecraft_lobby）矛盾」），<b>不能</b>据此断定一定是这种写法持续发生——另一种同形可能是
   *       索引漏接切维事件（由 {@code ThrottleStats#indexRepaired} 区分）。</li>
   * </ol>
   * 三道闸都只做内存读，不额外读世界、不分配对象。
   *
   * <p><b>失败只失效「位置 + 心跳」，保留会话令牌</b>：读不到世界 / 读实体抛异常都是一次性故障，
   * 读者立刻 fail-open 就够；若顺手把会话也结束掉，下游会把已有待发批判成「上一代连接」而整批不投递，
   * 等于用一次瞬时读失败换永久损失。
   */
  private void refreshPosition(Player player, UUID playerId, RefreshSlot slot) {
    if (stopping || slot.retired || positionTasks.get(playerId) != slot) {
      return; // 已被替换 / 退役的注册：不再写任何状态
    }
    if (!positions.isCurrent(slot.session)) {
      // 会话已换代（同一 UUID 的新连接）：本任务的坐标属于上一条连接，一条都不许写
      retirePositionTask(playerId, slot);
      return;
    }
    if (!ownership.isOwnedByCurrentRegion(player)) {
      // 非拥有线程：不写快照、不刷心跳（否则就是僵尸快照）。用自己的当前实体重建一次即可。
      if (!slot.ownerLost) {
        slot.ownerLost = true;
        positionTasks.remove(playerId, slot);
        rescheduleOnCurrentEntity(playerId);
      }
      return;
    }
    try {
      // 世界必须与坐标在同一线程（拥有该玩家的线程）读取：封包线程只读快照，
      // 绝不自己调 player.getWorld()（Folia 上那是跨区域读实体状态）。
      World world = player.getWorld();
      if (world == null) {
        positions.invalidate(slot.session);
        return;
      }
      String worldName = world.getName();
      // 黑名单世界跳过坐标读取（省掉每 tick 三次 getter + 三次 floor 比较），只更新世界名。
      String previousWorld = WorldBlacklistConfig.isBlacklistedNow(worldName)
          ? positions.refreshWorldOnly(slot.session, worldName)
          : positions.refresh(slot.session, worldName,
              player.getX(), player.getY(), player.getZ());
      // 跨世界切换：立即丢弃旧世界的待发缓冲。只靠封包路径的入口一致闸会漏掉「切维之后不再有旧世界
      // 方块包到达」的情形——那种情形下窗口定时器到期仍会把旧世界坐标发进新世界（鬼影方块）。
      if (previousWorld != null && !previousWorld.equals(worldName)) {
        discardPending(playerId, ThrottleStats.DiscardSource.POSITION_SNAPSHOT, worldName);
      }
      // 两源对账：用「同一次 player.getWorld()」修正世界索引（事件遗漏 / 错序 / 迟到）。
      // 世界身份没变时这里只花一次 CHM 读 + 一次字段比较，不 new 记录、不写表、不换代次。
      if (PlayerWorldIndex.reconcile(player, world)) {
        stats.indexRepaired.increment();
      }
    } catch (Throwable throwable) {
      positions.invalidate(slot.session);
      noticeSnapshotFailOpen(throwable);
    }
  }

  /** 用「当前在线实体」重建刷新任务（旧实体已退役/移出本区域，旧注册里的 player 不再是 owner）。 */
  private void rescheduleOnCurrentEntity(UUID playerId) {
    if (stopping) {
      return;
    }
    Player current;
    try {
      current = Bukkit.getPlayer(playerId);
      if (current == null || !current.isOnline()) {
        return;
      }
    } catch (Throwable ignored) {
      // 在线表读不到：交给看门狗下一轮再补
      return;
    }
    schedulePositionRefresh(current);
  }

  /** 玩家退出：取消其刷新任务并结束会话。 */
  private void cancelPositionRefresh(UUID playerId) {
    RefreshSlot slot = positionTasks.remove(playerId);
    if (slot != null) {
      slot.retired = true;
      cancelQuietly(slot.handle);
    }
    positions.endSession(playerId);
  }


  /**
   * 退役回调：只做内存记账（关键代码路径，不得操作实体/世界）。
   *
   * <p><b>按注册身份摘除</b>：{@code remove(key, value)} 只在表里仍是本次注册时才成功——旧实体迟到的退役
   * 因此摘不到重建后的新注册。按键盲删会把重建后的新注册一起摘掉，表现为「重生后近身判定永久失效」。
   *
   * <p><b>只失效位置与心跳，不结束会话</b>：退役说明「这个实体的刷新任务没了」，不代表玩家断线；
   * 结束会话会把该玩家已有待发批误判成上一代连接而整批不投递（见 {@code FlushWorld}）。
   */
  private void retirePositionTask(UUID playerId, RefreshSlot slot) {
    slot.retired = true;
    if (positionTasks.remove(playerId, slot)) {
      positions.invalidate(slot.session);
    }
  }

  /**
   * 位置刷新看门狗（全局区域线程，每 100 tick）：只补「没有登记的玩家」。
   *
   * <p>刷新任务只在 登录 / 复活完成 / 切维 / 启用 四个时机登记。若某次登记落在已退役实体上（调度返回
   * {@code null}）或任务被服务端取消，该玩家会永久停在「快照过期 → 入口一律即时放行」的 fail-open 态：
   * 不丢更新、不制造延迟，但块变更合并对他完全停摆。看门狗把这段停摆限制在 ≤5 秒。
   *
   * <p><b>不读实体状态、不碰世界</b>：只读在线玩家列表与自己的注册表；世界/坐标读取仍只发生在实体所属
   * 线程的刷新任务里，因此这里不会制造每 tick 世界读。
   */
  private void startWatchdog() {
    if (stopping || watchdog != null) {
      return;
    }
    try {
      watchdog = plugin.getServer().getGlobalRegionScheduler()
          .runAtFixedRate(plugin, task -> sweepPositionTasks(), 100L, 100L);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.FINE, "位置刷新看门狗未能启动（刷新任务仍由登录/复活/切维重排）", throwable);
    }
  }

  private void sweepPositionTasks() {
    if (stopping) {
      return;
    }
    try {
      for (Player player : plugin.getServer().getOnlinePlayers()) {
        if (player == null || positionTasks.containsKey(player.getUniqueId())) {
          continue;
        }
        schedulePositionRefresh(player);
      }
    } catch (Throwable ignored) {
      // 在线列表读取失败：下一轮再补
    }
  }

  private void cancelWatchdog() {
    ScheduledTask task = watchdog;
    watchdog = null;
    cancelQuietly(task);
  }

  /** 取消任务失败可忽略（任务可能已随实体退役结束）；单条失败不得中断整轮取消。 */
  private void cancelQuietly(ScheduledTask task) {
    if (task == null) {
      return;
    }
    try {
      task.cancel();
    } catch (Throwable ignored) {
      // 已退役 / 已结束
    }
  }

  /**
   * 停用兜底：取消全部位置刷新任务。
   *
   * <p><b>逐项 remove 而非遍历后 clear</b>：本方法在命令线程执行，{@code onJoin} → {@link #schedulePositionRefresh}
   * 在玩家所属区域线程执行（Folia 上真正并发）。若先迭代再 clear，迭代之后才入表的任务会被直接丢弃
   * 且从未 cancel——而它是<b>每 tick</b> 执行的，会持续空跑到玩家退出并钉住整个已停用实例。
   */
  private void cancelAllPositionRefreshTasks() {
    for (UUID playerId : new ArrayList<>(positionTasks.keySet())) {
      RefreshSlot slot = positionTasks.remove(playerId);
      if (slot != null) {
        slot.retired = true;
        cancelQuietly(slot.handle);
      }
    }
  }

  /**
   * 快照不可用时的一次性中文提示。语义是 fail-open：一律按近身处理（立即放行），
   * 宁可少合并、绝不制造延迟。
   */
  private void noticeSnapshotFailOpen(Throwable cause) {
    if (!snapshotFailOpenNoticed.compareAndSet(false, true)) {
      return;
    }
    String text = "方块变更合并：玩家位置快照暂不可用，近身判定一律按「立即放行」处理"
        + "（fail-open——宁可少合并、绝不制造延迟）。快照由玩家所属线程每 tick 重建，"
        + "通常一两秒内自动恢复；此提示只打印一次。";
    if (cause == null) {
      plugin.getLogger().warning(text);
    } else {
      plugin.getLogger().log(Level.WARNING, text, cause);
    }
  }

  /**
   * 启动期探测一次「ProtocolLib 的 {@code WrappedBlockData$NewBlockData} 静态契约」是否成立。
   *
   * <p><b>为什么需要</b>：该类 {@code <clinit>} 会用 {@code FuzzyReflection} 在 {@code CraftMagicNumbers}
   * 上硬查 7 个方法契约（{@code MATERIAL_FROM_BLOCK} / {@code BLOCK_FROM_MATERIAL} /
   * {@code TO_LEGACY_DATA} / {@code FROM_LEGACY_DATA} / {@code GET_BLOCK} /
   * {@code DEFAULT_BLOCK_DATA} / {@code GET_HANDLE}）；服务端结构一变就可能整体失败。失败后
   * {@link #readUpdates} 会把出站包判为「解析不了」并原样放行（既有 fail-open），于是
   * <b>合并能力静默消失</b>——日志里看不到任何异常，排查时极易误判为「配置没生效」。
   *
   * <p><b>只让静默降级可见</b>：探测结果不改变任何行为（不注册/不注销该模块、不抛异常、不影响启动），
   * 失败只打一次中文 WARN。用 {@code initialize=true} 触发 clinit——这与生产路径（首个出站变更包）
   * 触发的是同一个初始化，因此不引入新的失败面；探测本身抛出的异常一律吞掉并记为该探针失败。
   */
  private void probeBlockDataContract() {
    if (!blockDataProbeDone.compareAndSet(false, true)) {
      return;
    }
    try {
      Class.forName("com.comphenix.protocol.wrappers.WrappedBlockData$NewBlockData", true,
          WrappedBlockData.class.getClassLoader());
    } catch (Throwable throwable) {
      // 异常吞掉：探测失败绝不影响启动
      plugin.getLogger().log(Level.WARNING,
          "变更合并模块已停用（服务端结构变更）：ProtocolLib 的 WrappedBlockData$NewBlockData 静态契约"
              + "探测失败（CraftMagicNumbers 的 7 个方法契约未全部探到），方块变更合并将静默失效、"
              + "原包照常放行。请更新 ProtocolLib 或本插件到匹配当前服务端的版本后重启。",
          throwable);
    }
  }

  /**
   * 立即冲刷该玩家的合并缓冲（仅在「玩家退出」时使用）。
   *
   * <p>绝不能像 {@link #flushInternal} 那样内联在本（封包）线程跑：聚簇 + 构包 + 发送会阻塞所有玩家的
   * 出站封包。这里只是把「提前到期」交给唯一的冲刷线程；执行器已关闭（停用中）时退回内联，
   * 语义与 {@code onPacketSending} 的溢出冲刷一致（恰好放行一次）。
   */
  private void flushNow(UUID playerId) {
    Pending target = pending.get(playerId);
    if (target == null) {
      return;
    }
    try {
      flusher.execute(() -> flush(target));
    } catch (Throwable rejected) {
      flush(target);
    }
  }

  /**
   * 丢弃某玩家当前的待发缓冲（没有缓冲时无操作）。三条入口（切维事件 / 位置快照比对 / 黑名单）
   * 都只做一行转发，语义完全一致。
   *
   * @return 是否确有缓冲被丢弃
   */
  boolean discardPending(UUID playerId, ThrottleStats.DiscardSource source) {
    return discardPending(playerId, source, null);
  }

  /**
   * 同 {@link #discardPending(UUID, ThrottleStats.DiscardSource)}，但由调用方给出「玩家此刻所在世界」。
   *
   * <p>事件 / 每 tick 刷新都在拥有线程上刚读到世界，比让 {@link PlayerWorldIndex} 再回答一次更可信
   * （索引可能还没跟上事件）。{@code null} 表示由索引回答。
   */
  boolean discardPending(UUID playerId, ThrottleStats.DiscardSource source, String destination) {
    Pending target = pending.get(playerId);
    if (target == null) {
      return false;
    }
    discard(target, source, destination);
    return true;
  }

  /**
   * 丢弃某玩家当前缓冲：取消全部被延迟的原包，但<b>不发送任何合并包</b>。
   *
   * <p><b>为什么不能用 {@link #flushNow} 冲刷</b>：跨世界切换 / 进入黑名单世界时，缓冲里的方块坐标都
   * 属于<b>旧世界</b>，而合并包（{@code BLOCK_CHANGE} / {@code MULTI_BLOCK_CHANGE}）不含世界标识。
   * 这些原包在发送流中的位置本在切维包之前，被扣下后无论怎样重发都只会落在切维包之后——客户端会把
   * 旧世界坐标按新世界解释（鬼影方块）。玩家离开该世界后区块会整列重建，丢弃这批旧态不构成丢更新。
   *
   * <p><b>为什么在调用线程内联执行</b>：判定为「真切维」时它只清空、不聚簇、不构包、不发包，内联才能保证
   * 取消发生在任何定时冲刷之前（异步投递会重新打开「旧世界数据落在切维包之后」的窗口）；只有证明世界
   * 没变（{@code redeem}）时才在同一次调用里补发，且聚簇已移到封存锁之外，不额外占用封包线程要抢的那把锁。
   */
  private void discard(Pending target, ThrottleStats.DiscardSource source, String destination) {
    ScheduledFuture<?> timer = null;
    int cancelled = 0;
    int buffered = 0;
    List<List<Update<WrappedBlockData>>> clusters = List.of();
    Set<Coord> passed = Set.of();
    Set<Coord> passedSealed = null;
    boolean redeem = false;
    boolean clustersDeferred = false;
    String currentWorld = null;
    try {
      synchronized (target) {
        // 与 flush 共用同一个「恰好处理一次」闸：谁先拿到 flushed 谁负责，另一方成 no-op
        if (!target.flushed.compareAndSet(false, true)) {
          return;
        }
        pending.remove(target.uuid, target);
        timer = target.timer;
        target.timer = null;
        cancelled = target.cancelledPackets;
        buffered = target.batch.size();
        currentWorld = destination == null ? PlayerWorldIndex.worldName(target.uuid) : destination;
        // 取消即欠账：原包在入缓冲时已经被取消，这里任何「直接丢弃」都必须先证明客户端会拿到
        // 等价信息。只有真切维（客户端收到新世界的整列区块）满足这个前提；世界名没变却丢弃，
        // 就是把这些方块变更永久抹掉——客户端会一直停在旧方块上（门只开一半、另一半还挡人即此症状），
        // 而且不会自愈。因此世界名相同即改走既有交付路径补发（见 deliver，失败自带逐条补发）。
        redeem = cancelled > 0 && shouldRedeem(target.worldName(), currentWorld);
        if (redeem) {
          // 判据已在上面取完，这里只交出「已封存」的引用：聚簇与 passed 的只读复制都移到锁外
          // （封存已完成 —— 见 flushInternal 里对「为什么锁外等价」的说明）。
          clustersDeferred = true;
          passedSealed = target.passed.isEmpty() ? null : target.passed;
        } else {
          target.batch.clear();
          target.passed.clear();
        }
      }
      if (clustersDeferred) {
        clusters = target.batch.drainClusters(config.mergeRadius());
        passed = passedSealed == null ? Set.of() : new HashSet<>(passedSealed);
        // 复制之后才清：对象已封存，锁外清空与锁内清空等价（只影响可达性，不参与任何判定）
        target.passed.clear();
      }
      if (timer != null) {
        timer.cancel(false);
      }
      if (redeem) {
        stats.discardRedeemed.add(cancelled);
        recordTrace(BlockUpdateTrace.Decision.REDEEM, target.worldName(), clusters, buffered, 0, 0, 0,
            "补发 " + cancelled + " 个原包；来源 " + source + "；缓冲世界 " + target.worldName()
                + " = 当前世界 " + currentWorld);
        deliver(target.uuid, target.connection, target.context, clusters, passed, cancelled);
        return;
      }
      stats.discarded(source, cancelled);
      trace.record(BlockUpdateTrace.Decision.DISCARD, target.worldName(), false, buffered, 0, 0, 0,
          "丢弃 " + cancelled + " 个原包；来源 " + source + "；缓冲世界 " + target.worldName()
              + " ≠ 当前世界 " + currentWorld + "（旧世界区块将整列重建）");
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 当前世界与缓冲世界均已知且不同时，才允许丢弃已取消的原包。 */
  static boolean shouldRedeem(String bufferedWorld, String currentWorld) {
    return !shouldDiscardAtFlush(bufferedWorld, currentWorld);
  }

  /**
   * 纯函数：两侧世界名是否<b>足以证明换了世界</b>（唯一的名称比较实现，事件路径与冲刷路径共用）。
   *
   * <p><b>它只是一个必要条件，不是充分条件</b>：名称不同只说明「有一侧说世界变了」。真正允许整批丢弃
   * 还要满足 {@link FlushWorld#classify} 的另外两条——索引已换代（玩家事件真的前进过）、活快照也同意
   * 这个新世界。缺了这两条就只看名称，等于把「索引漏接事件」或「快照冻结」当成真切维：
   * 历史事故（69.5 万包）正是拿冻结快照当证据整批丢的，玩家侧表现是假方块挖不掉。
   *
   * @param bufferedWorld 缓冲里这些坐标所属的世界名，可为 {@code null}
   * @param indexWorld    此刻索引里的世界名，可为 {@code null}
   * @return true 表示两侧都已知且不同（事件路径据此外加 destination 证据；冲刷路径还要再走判定表）
   */
  static boolean shouldDiscardAtFlush(String bufferedWorld, String indexWorld) {
    if (bufferedWorld == null || indexWorld == null) {
      // 任一侧未知：无法证明客户端会重建这些坐标 → 不丢（取消即欠账）
      return false;
    }
    return !indexWorld.equals(bufferedWorld);
  }

  /** 记一条处置环（簇非空时取首坐标；任何异常都不影响发包）。 */
  private void recordTrace(BlockUpdateTrace.Decision decision, String world,
      List<List<Update<WrappedBlockData>>> clusters, int fallbackCount, int x, int y, int z,
      String note) {
    if (clusters != null) {
      for (List<Update<WrappedBlockData>> cluster : clusters) {
        if (cluster != null && !cluster.isEmpty()) {
          Update<WrappedBlockData> first = cluster.get(0);
          trace.record(decision, world, cluster.size() >= 2, cluster.size(), first.x(), first.y(),
              first.z(), note);
          return;
        }
      }
    }
    trace.record(decision, world, false, fallbackCount, x, y, z, note);
  }

  /** 记一条「直通」处置（只有多坐标包值得留痕：单坐标变更每秒上万条，会把环冲干净）。 */
  private void recordTracePass(String world, List<Update<WrappedBlockData>> updates) {
    if (updates == null || updates.size() < 2) {
      return;
    }
    Update<WrappedBlockData> first = updates.get(0);
    trace.record(BlockUpdateTrace.Decision.PASS, world, true, updates.size(),
        first.x(), first.y(), first.z(), null);
  }

  /** 记一条「直通」处置（坐标数组版，供只解坐标的快速路径复用）。 */
  private void recordTracePass(String world, boolean multi, int[] coordinates, int count) {
    if (coordinates == null || count < 2 || coordinates.length < 3) {
      return;
    }
    trace.record(BlockUpdateTrace.Decision.PASS, world, multi, count,
        coordinates[0], coordinates[1], coordinates[2], null);
  }

  /** 记一条「直通」处置（packed short 版，供「已聚合批量包」短路复用）。 */
  private void recordTracePass(String world, boolean multi, short[] positions,
      BlockPosition section) {
    if (positions == null || section == null || positions.length < 2) {
      return;
    }
    int raw = SectionCoords.unsigned(positions[0]);
    trace.record(BlockUpdateTrace.Decision.PASS, world, multi, positions.length,
        (section.getX() << 4) + SectionCoords.x(raw),
        (section.getY() << 4) + SectionCoords.y(raw),
        (section.getZ() << 4) + SectionCoords.z(raw), null);
  }

  /**
   * 只解析坐标、不构造 {@code WrappedBlockData}：近身判定只需要坐标。
   *
   * <p>为什么值得单独一条路径：玩家挖/放方块是最常见的方块变更（每包 1~8 个坐标），而完整解析会为
   * 每个坐标构造一个 ProtocolLib 的反射包装 + 一条 {@code Update} + ArrayList——这些对象在「近身立即
   * 放行」分支里<b>一个都不会被使用</b>。先只解坐标、命中近身就直接放行，非近身才走完整解析。
   *
   * @return 绝对坐标三元组（{@code x,y,z} 连续）；读不到结构时返回 {@code null}（调用方退回完整解析）
   */
  private static int[] positionsOnly(PacketType type, PacketContainer packet) {
    try {
      if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        BlockPosition position = packet.getBlockPositionModifier().readSafely(0);
        return position == null ? null
            : new int[] {position.getX(), position.getY(), position.getZ()};
      }
      BlockPosition section = packet.getSectionPositions().readSafely(0);
      short[] positions = packet.getShortArrays().readSafely(0);
      if (section == null || positions == null || positions.length == 0) {
        return null;
      }
      int baseX = section.getX() << 4;
      int baseY = section.getY() << 4;
      int baseZ = section.getZ() << 4;
      int[] coordinates = new int[positions.length * 3];
      for (int i = 0; i < positions.length; i++) {
        int raw = SectionCoords.unsigned(positions[i]);
        coordinates[i * 3] = baseX + SectionCoords.x(raw);
        coordinates[i * 3 + 1] = baseY + SectionCoords.y(raw);
        coordinates[i * 3 + 2] = baseZ + SectionCoords.z(raw);
      }
      return coordinates;
    } catch (Throwable throwable) {
      // 读不出结构：退回完整解析（那里同样 fail-open 放行原包）
      return null;
    }
  }

  /**
   * 入口的两源一致闸：只有「索引世界与活快照世界一致、坐标已知、心跳新鲜」时才返回快照。
   *
   * <p>任一侧未知或两侧冲突时返回 {@code null}，调用方必须<b>原包即时放行</b>：不进合并窗口、不按任何
   * 一侧的旧世界名打标、不延迟。计数按「未知」/「冲突」分开（{@code ThrottleStats#worldUnknown} /
   * {@code #worldConflict}）——生产 dump 里两者排查方向完全不同：前者多是一两个 tick 的登录/切维过渡
   * （自愈），后者说明两份来源长期不一致（要么索引漏接事件，要么刷新跑在非拥有线程上，见
   * {@code ThrottleStats#indexRepaired} 与 {@code #snapshotResync}）。
   *
   * <p>封包线程不触碰实体状态：只读两份快照，一次 CHM 读 + 一次心跳读，零分配。
   */
  private PlayerPositionSnapshots.Position verifiedSnapshot(UUID playerId, String indexWorld) {
    try {
      PlayerPositionSnapshots.Position position = positions.get(playerId);
      if (position == null || !position.positionKnown() || positions.isStale(playerId)) {
        // 冻结/缺失的快照比没有快照更危险：坐标停在切维前的位置，会把玩家脚下的变更判成「远」。
        noticeSnapshotFailOpen(null);
        stats.worldUnknown.increment();
        return null;
      }
      if (!position.world().equals(indexWorld)) {
        stats.worldConflict.increment();
        return null;
      }
      return position;
    } catch (Throwable throwable) {
      noticeSnapshotFailOpen(throwable);
      stats.worldUnknown.increment();
      return null;
    }
  }

  /**
   * 近身判定：<b>只读已通过一致闸的快照</b>（封包线程不触碰任何 Bukkit API）。
   *
   * <p>{@code radius <= 0} 表示关闭立即放行，与旧行为一致（全部走合并）。
   */
  private boolean shouldPassThroughImmediately(PlayerPositionSnapshots.Position snapshot,
      List<Update<WrappedBlockData>> updates) {
    int radius = config.immediateRadius();
    return radius > 0 && PlayerPositionSnapshots.immediatePass(snapshot, updates, radius);
  }

  /** 坐标版近身判定（快速路径；不构造任何方块数据包装）。 */
  private boolean shouldPassThroughImmediately(PlayerPositionSnapshots.Position snapshot,
      int[] coordinates, int count) {
    int radius = config.immediateRadius();
    return radius > 0 && PlayerPositionSnapshots.immediatePass(snapshot, coordinates, count, radius);
  }

  static boolean fitsPendingWindow(int pending, int incoming, int limit) {
    return pending >= 0 && incoming > 0 && pending <= limit && incoming <= limit - pending;
  }

  /**
   * 缓冲固定的世界上下文与入参是否<b>同一代</b>（世界名 + 代次都比对）。
   *
   * <p>{@code null}（尚未固定）时一律返回 false：没有固定的世界就没有可覆盖的旧态，也绝不能拿一个
   * 不确定的世界名去记账。代次比较是必需的——同名世界重连后世界名完全相同，只比名字会把上一代连接
   * 的旧批当成「同一世界」而误记覆盖标记。
   */
  private static boolean sameWorldGeneration(Pending target, WorldContext context) {
    if (target == null || context == null || target.flushed.get()) {
      return false;
    }
    WorldContext pinned = target.context;
    return pinned != null && pinned.session() == context.session()
        && pinned.worldName().equals(context.worldName());
  }

  /** 记录「这些绝对坐标已有新态下发」（快速路径用，不经过 {@code Update} 列表）。 */
  private void rememberDeliveredCoords(UUID playerId, WorldContext context, int[] coordinates,
      int count) {
    Pending target = pending.get(playerId);
    if (target == null) {
      return;
    }
    synchronized (target) {
      if (!sameWorldGeneration(target, context)) {
        return;
      }
      int usable = Math.min(count, coordinates.length / 3);
      for (int i = 0; i < usable; i++) {
        target.rememberPass(coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]);
      }
    }
  }

  /**
   * 世界归属未知 / 冲突时的<b>坐标-only 覆盖登记</b>。
   *
   * <p><b>为什么不能省</b>：这条路径把原包直接放行（不延迟、不入缓冲），但同一连接的旧批可能仍在缓冲里
   * 等着冲刷；不登记「这些坐标已有新态下发」，窗口末的旧值就会把刚下发的新态盖回去——正是
   * {@code passed} 这张表存在的理由。旧实现只在「快照可用」的两条路径登记，未知/冲突路径会漏记。
   *
   * <p><b>只在确有待发缓冲时解析坐标</b>：没有缓冲就没有可覆盖的旧态，没必要为一次 fail-open 分配
   * 坐标数组（这条路径通常只持续一两个 tick）。
   */
  private void rememberDeliveredOnUnverified(PacketEvent event, WorldContext context) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    Pending target = pending.get(player.getUniqueId());
    if (target == null) {
      return;
    }
    synchronized (target) {
      if (!sameWorldGeneration(target, context)) {
        // 缓冲属于另一代世界（切维 / 重连）：不记标记，也不会冲刷进当前世界
        return;
      }
    }
    int[] coordinates = positionsOnly(event.getPacketType(), event.getPacket());
    if (coordinates == null || coordinates.length < 3) {
      return;
    }
    rememberDeliveredCoords(player.getUniqueId(), context, coordinates, coordinates.length / 3);
  }

  /**
   * 合并窗口的主判定：命中窗口的变更<b>先入缓冲、再取消原包</b>（两步在同一把锁内）。
   *
   * <p>顺序不能颠倒：先取消后入缓冲，中间一旦抛异常这条变更就既没进缓冲也没下发（永久丢更新）。
   * 入缓冲失败或窗口定时器排不上时退回「原样放行」，绝不取消原包。
   */
  @Override
  public void onPacketSending(PacketEvent event) {
    if (!config.merge() || event.isCancelled()) {
      return;
    }
    Player player = event.getPlayer();
    // 统一走直通名单：只读并发集合，封包线程不触碰 Bukkit 权限 API（名单仅由登录/退出与启用快照维护，无周期刷新）
    if (player == null || BypassRegistry.isBypassedNow(player.getUniqueId())) {
      return;
    }
    // 世界上下文只查一次：后续打标、黑名单与冲刷判定都用同一份不可变快照（PlayerWorldIndex 是事件驱动）。
    //
    // 封包线程不读实体状态（Folia 会抛 No currently ticking region）：只查两份快照。
    // 上下文缺失（刚登录 / 取不到）按「无法判定」直通：原包照常下发，绝不把方块包卡进缓冲。
    WorldContext context = PlayerWorldIndex.context(player.getUniqueId());
    if (context == null) {
      stats.worldUnknown.increment();
      stats.blockChangesPassed.increment();
      return;
    }
    String worldName = context.worldName();
    if (WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      // 黑名单世界不做任何带宽优化；同时丢弃可能来自「上一个非黑名单世界」的待发缓冲——
      // 否则窗口到期会把旧世界坐标发进黑名单世界（同样是鬼影方块）。
      discardPending(player.getUniqueId(), ThrottleStats.DiscardSource.BLACKLIST, worldName);
      return;
    }
    // 接收连接（登录建立、退出结束；重生/切维不换代）——与「世界代次」是两件事，不能互相顶替。
    // 它只回答「这批该发给哪条连接」，世界归属另由下面的一致闸回答。
    PlayerPositionSnapshots.Session connection = positions.session(player.getUniqueId());
    // 入口两源一致闸（本模块的世界归属判定）：索引世界与活快照世界必须一致、坐标已知、心跳新鲜。
    // 任一侧未知或冲突 → 原包即时放行（见 verifiedSnapshot）。方块变更包不含世界标识，封包线程没有
    // 第二种信息来源；旧实现在这里比较「快照世界」与「快照世界」（恒相等），那道守卫从未生效。
    PlayerPositionSnapshots.Position snapshot = verifiedSnapshot(player.getUniqueId(), worldName);
    if (snapshot == null || connection == null) {
      // 原包即时放行，但若同一世界代次的旧批已经取消待发，仍必须记「这些坐标已有新态下发」，
      // 否则窗口末的旧值会把刚下发的新态盖回去（方块回退）。登记只按坐标并复核代次，绝不据此丢整批。
      rememberDeliveredOnUnverified(event, context);
      stats.blockChangesPassed.increment();
      return;
    }

    // 解析前短路：MULTI_BLOCK_CHANGE 的坐标数与「段原点 + 每格一个 packed short」都能直接读出，
    // 无需把整包解析成 List<Update<WrappedBlockData>>（每坐标一个 WrappedBlockData 反射包装 + 一条
    // Update + ArrayList 扩容）。反矿透显形包常有数十到数千个坐标，多人同时显形时这份分配是封包线程上
    // 可见的 GC 抖动源。
    // 有待发缓冲时必须登记覆盖标记（rememberDeliveredCoords），否则窗口到期会把旧态写回去（方块回退）；
    // 读不到坐标数组或段原点时退回下面的完整解析路径——那条路径自己会记标记或 fail-open，绝不漏记。
    if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
      short[] coordShorts;
      BlockPosition section;
      try {
        coordShorts = event.getPacket().getShortArrays().readSafely(0);
        section = coordShorts == null ? null : event.getPacket().getSectionPositions().readSafely(0);
      } catch (Throwable ignored) {
        // 读不出坐标数组：跳过短路，走原路径（由 readUpdates 自行 fail-open）
        coordShorts = null;
        section = null;
      }
      if (coordShorts != null && bypassesMerge(true, coordShorts.length)) {
        recordTracePass(worldName, true, coordShorts, section);
        // 本包已是成形批量包：无论有无待发缓冲都直接放行。有缓冲时只需记「这些坐标的新态已下发」，
        // 用 short[] 逐格解包即可——不必为记账把整包解析成 WrappedBlockData[]（每坐标一个反射包装 + 一条 Update）。
        if (pending.get(player.getUniqueId()) == null) {
          stats.blockChangesPassed.increment();
          return;
        }
        if (section != null) {
          rememberDeliveredCoords(player.getUniqueId(), context, section, coordShorts);
          stats.blockChangesPassed.increment();
          return;
        }
        // 有缓冲却读不到段原点：无法记覆盖标记，退回下面的完整解析路径（那里会记标记或自行 fail-open）
      }
    }

    // 近身快速路径：先只解坐标；命中近身就直接放行，完全跳过 WrappedBlockData / Update 的构造。
    // 这是玩家挖/放方块（最常见的小批量）的主路径，省下的反射包装与集合扩容都发生在封包线程上。
    int[] quickCoordinates = positionsOnly(event.getPacketType(), event.getPacket());
    if (quickCoordinates != null) {
      int quickCount = quickCoordinates.length / 3;
      if (shouldPassThroughImmediately(snapshot, quickCoordinates, quickCount)) {
        rememberDeliveredCoords(player.getUniqueId(), context, quickCoordinates, quickCount);
        stats.blockChangesPassed.increment();
        recordTracePass(worldName, event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE,
            quickCoordinates, quickCount);
        return;
      }
    }

    List<Update<WrappedBlockData>> updates = readUpdates(event.getPacketType(), event.getPacket());
    if (updates == null || updates.isEmpty()) {
      stats.blockChangesPassed.increment();
      return;
    }

    // 近身变更立即放行：不登记延迟、不入缓冲，原包按原样流出。
    // 这样玩家自己挖/放方块时的方块更新不会被合并窗口拖延，客户端预测得以立即确认（消除顿感）。
    if (shouldPassThroughImmediately(snapshot, updates)) {
      // 记录本窗口内已有更新下发的坐标：合并窗口内可能有同一方块的旧状态仍在缓冲，
      // 若不标记会在窗口到期后晚到并把新态覆盖回去（方块短暂回退）。
      rememberDelivered(player.getUniqueId(), context, updates);
      stats.blockChangesPassed.increment();
      recordTracePass(worldName, updates);
      return;
    }

    // 兜底分支：短路未命中（例如读不到段原点）时才走到这里。语义与上面的短路完全一致——
    // 已聚合的批量包不进 Pending、不登记延迟、不重编码，原包按原样流出。
    //
    // 这类包同样要登记「这些坐标已有更新下发」：否则若缓冲里还留着同坐标的<b>旧态</b>条目，
    // 窗口到期会把旧态写回客户端，覆盖刚由本包下发的<b>新态</b>（方块回退）。
    // 不必担心误删新态：同坐标若之后又被重新缓冲，缓冲循环会撤销该标记（见上面的 remove）。
    if (bypassesMerge(event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE, updates.size())) {
      rememberDelivered(player.getUniqueId(), context, updates);
      stats.blockChangesPassed.increment();
      recordTracePass(worldName, updates);
      return;
    }

    int maxPending = config.maxPendingEntries();
    if (!fitsPendingWindow(0, updates.size(), maxPending)) {
      rememberDelivered(player.getUniqueId(), context, updates);
      stats.blockChangesPassed.increment();
      return;
    }
    Pending target;
    boolean scheduleOverflowFlush = false;
    // 与 flush() 在同一把锁内判定「缓冲是否已被冲刷」：若已冲刷则重取新缓冲，
    // 否则会把原包加进已废弃的缓冲里，导致该包永远得不到放行（卡包）。
    while (true) {
      target = pending.computeIfAbsent(player.getUniqueId(),
          uuid -> new Pending(uuid, config.maxPendingEntries()));
      synchronized (target) {
        if (target.flushed.get()) {
          continue;
        }
        WorldContext pinned = target.context;
        if (pinned != null && pinned.session() != context.session()) {
          // 待发缓冲属于另一个世界代次（切维 / 同名世界重连）。当前原包直接发，绝不覆盖旧窗口的
          // 世界键再混合两批——那会把两个世界的坐标写进同一个合并包。
          stats.blockChangesPassed.increment();
          return;
        }
        if (!fitsPendingWindow(target.batch.size(), updates.size(), maxPending)) {
          for (Update<WrappedBlockData> update : updates) {
            target.rememberPass(update.x(), update.y(), update.z());
          }
          stats.blockChangesPassed.increment();
          return;
        }
        boolean overflow = false;
        // 先确保窗口定时器在位：排不上就<b>不取消</b>原包（它照常流出），绝不因合并链路不可用而丢更新。
        if (target.timer == null) {
          long window = Math.max(1L, config.mergeWindowMillis());
          final Pending scheduled = target;
          try {
            target.timer = flusher.schedule(() -> flush(scheduled), window, TimeUnit.MILLISECONDS);
          } catch (Throwable rejected) {
            logThrottled(rejected);
            return;
          }
        }
        for (Update<WrappedBlockData> update : updates) {
          // 直接复用解析阶段已构造的 Update 实例（避免同一坐标再造一个等值 record）
          overflow |= target.batch.add(update);
          Coord coord = new Coord(update.x(), update.y(), update.z());
          target.buffered.add(coord);
          target.passed.remove(coord);
        }
        // 入缓冲成功后才取消原包：此后由窗口末的合并包交付；合并失败会逐条补发（见 deliver），绝不丢更新。
        event.setCancelled(true);
        target.cancelledPackets++;
        if (updates.size() >= 2) {
          Update<WrappedBlockData> firstMerged = updates.get(0);
          trace.record(BlockUpdateTrace.Decision.MERGE, worldName, true, updates.size(),
              firstMerged.x(), firstMerged.y(), firstMerged.z(), null);
        }
        // 固定本批坐标的世界上下文（世界名 + 代次）与接收连接：冲刷/送出前据此判定
        // SAME/CHANGED/PREVIOUS_CONNECTION（见 FlushWorld）。幂等：同一代次的重复写入是同一个不可变实例。
        target.context = context;
        target.connection = connection;

        // 溢出调度闸（synchronized(target) 内置位）：只有「本 Pending 尚未排过溢出冲刷」才排任务。
        // add 达上限后每个后续包都返回 overflow=true，但首个任务执行前的窗口内重复排队只是空转
        //（flushed CAS 全 no-op，还会把 blockMergeFlushes 统计注水）。置位与判定同锁，不丢排不重排。
        if (overflow && !target.overflowFlushScheduled) {
          target.overflowFlushScheduled = true;
          scheduleOverflowFlush = true;
        }
        break;
      }
    }

    // 条目超限：立即冲刷，避免缓冲无界增长。
    // 关键：绝不在这条共享的异步封包线程上内联跑整段合并（聚簇 + 构包 + 可能数千方块的发送）——
    // 那会阻塞所有玩家的出站封包处理。改投到唯一的 MikuXrayNet-BlockMerge 线程执行；
    // flush(Pending) 自带「恰好一次」闸（Pending.flushed 的 CAS + 同步块），与窗口定时器并发触发也不会重复交付。
    if (scheduleOverflowFlush) {
      Pending flushTarget = target;
      try {
        flusher.execute(() -> flush(flushTarget));
      } catch (Throwable rejected) {
        // 执行器已 shutdown（拒绝提交）：退回内联冲刷，语义与改动前一致，保证原包不滞留（恰好放行一次）
        flush(flushTarget);
      }
    }
  }

  /**
   * 记录本窗口内「已有更新下发」的坐标（供 {@link #sendOrPass} 做 last-write-wins）。
   *
   * <p>两条路径都会调用：近身<b>立即放行</b>与远距离<b>直通批量包</b>——它们都把新状态直接交给了
   * 客户端，而缓冲里可能还留着同一坐标的旧状态。
   *
   * <p>语义是「此刻缓冲里的同坐标条目已过时」，不是「该坐标永久不可再发」：同坐标若之后又被缓冲，
   * 缓冲循环会立即撤销该标记（见 {@code onPacketSending} 的 {@code passed.remove}），
   * 因此不会误删更新后的新态。
   *
   * <p>只在已有待发缓冲时记录：没有缓冲就不可能有会被旧态覆盖的条目。与 {@code flush}
   * 共用同一把锁，保证「记录」与「冲刷时读取」不交错。
   */
  private void rememberDelivered(UUID playerId, WorldContext context,
      List<Update<WrappedBlockData>> updates) {
    Pending target = pending.get(playerId);
    if (target == null) {
      return;
    }
    synchronized (target) {
      if (!sameWorldGeneration(target, context)) {
        // 窗口已冲刷 / 世界已换代：缓冲内容已定型或不属于这一代（本次记录再无处生效），跳过
        return;
      }
      for (Update<WrappedBlockData> update : updates) {
        target.rememberPass(update.x(), update.y(), update.z());
      }
    }
  }

  /**
   * 同 {@link #rememberDelivered}，但入参是「段原点 + 每格一个 packed short」而不是已解析的更新列表。
   *
   * <p><b>为什么需要它</b>：批量直通包（{@code MULTI_BLOCK_CHANGE} 且坐标数达阈值）必须记「这些坐标的新态
   * 已下发」，否则冲刷时会把缓冲里的旧态写回去（方块回退）。而记账只需要坐标——{@code positions} 本身就是
   * 每坐标一个 short（高 4 位 y、次 4 位 z、低 4 位 x，与 {@link #readUpdates} 的解包完全一致），
   * 于是不必为了三个 int 去解析 {@code WrappedBlockData[]}。
   *
   * <p>上限闸与 {@link #rememberDelivered} 同口径：超限即整体清空（最多让窗口到期时把某个坐标的旧态再下发
   * 一次，不影响正确性）。与 {@code flush} 共用 {@code synchronized(target)}。
   */
  private void rememberDeliveredCoords(UUID playerId, WorldContext context, BlockPosition section,
      short[] positions) {
    Pending target = pending.get(playerId);
    if (target == null) {
      return;
    }
    synchronized (target) {
      if (!sameWorldGeneration(target, context)) {
        return;
      }
      int baseX = section.getX() << 4;
      int baseY = section.getY() << 4;
      int baseZ = section.getZ() << 4;
      for (short packed : positions) {
        int raw = SectionCoords.unsigned(packed);
        target.rememberPass(baseX + SectionCoords.x(raw), baseY + SectionCoords.y(raw), baseZ + SectionCoords.z(raw));
      }
    }
  }

  /**
   * 冲刷某玩家的缓冲：构造合并包并放行/取消原包。
   *
   * <p>冲刷跑在唯一的 {@code MikuXrayNet-BlockMerge} 线程上，是极端配置下的延迟瓶颈。这里只加
   * 「次数 + 累计耗时」两个无锁计数（纯观测，不改变任何行为、不引入任何并发），
   * 供 {@code /mxnet status} 观测「平均一次冲刷的成本」是否随配置/负载恶化。
   *
   * <p><b>统计口径</b>：只统计<b>确实处理了被延迟原包</b>的调用（{@code flushInternal} 抢到写入权且
   * 缓冲非空）。窗口定时器与溢出冲刷可能对同一 Pending 并发触发、重复调用本方法——那些 no-op 不得
   * 计入「冲刷次数/耗时」，否则突发流量下 flushes 虚高、平均耗时被 0 耗时空转稀释，诊断口径失真。
   */
  private void flush(Pending target) {
    long startNanos = System.nanoTime();
    boolean performed = flushInternal(target);
    if (performed) {
      stats.blockMergeFlushes.increment();
      stats.blockMergeFlushNanos.add(System.nanoTime() - startNanos);
    }
  }

  /**
   * 冲刷主体；返回 {@code true} 表示本次<b>确实处理了被延迟的原包</b>（调用方据此计入统计），
   * {@code false} = 无实包可处理（CAS 已被并发抢占，或缓冲为空——后者只做摘除 Pending 与取消
   * 定时器的清理，不该算作一次「冲刷」）。
   *
   * <p>CAS 成功后写入权归本线程、且已从 pending 摘除，后续所有 {@code flush}（含 {@code stop()} 的
   * flushAll）都会因 CAS 失败变成空转，因此本方法必须把「已取消的包一定交付」这条兜在自己身上：
   * 构包/发送失败由 {@link #deliver} 逐条补发同一批更新（幂等），异常只记限流日志。
   */
  private boolean flushInternal(Pending target) {
    List<List<Update<WrappedBlockData>>> clusters = List.of();
    Set<Coord> passed = Set.of();
    int cancelled = 0;
    int discarded = 0;
    FlushWorld verdict = FlushWorld.SAME;
    String bufferedWorld = null;
    String indexWorld = null;
    String currentWorld = null;
    boolean clustersDeferred = false;
    Set<Coord> passedSealed = null;
    ScheduledFuture<?> timer = null;
    try {
      synchronized (target) {
        // 「是否已冲刷」与缓冲读写共用同一把锁，保证不会边冲刷边追加
        if (!target.flushed.compareAndSet(false, true)) {
          return false;
        }
        pending.remove(target.uuid, target);
        timer = target.timer;
        target.timer = null;
        // 冲刷前的唯一判定表（纯函数）：入缓冲时固定的世界上下文 + 此刻索引上下文 + 此刻活快照。
        // 三段证据缺一不可——任何单侧证据都不构成丢弃理由（历史事故 69.5 万包正是拿冻结快照整批丢的）。
        WorldContext pinned = target.context;
        bufferedWorld = target.worldName();
        if (pinned != null) {
          // 索引上下文与快照心跳都只在这里读一次：下面的判定与留痕共用这两个值，锁外不再重读。
          // 两次读会把「决策依据」与「取证文字」拉到两个不同时刻，现场就会出现自相矛盾的丢弃记录。
          WorldContext now = PlayerWorldIndex.context(target.uuid);
          indexWorld = now == null ? null : now.worldName();
          PlayerPositionSnapshots.Position snapshot = positions.get(target.uuid);
          boolean snapshotFresh = snapshot != null && !positions.isStale(target.uuid);
          currentWorld = snapshotFresh ? snapshot.world() : null;
          // 接收连接是否还是入缓冲时那条（与世界名、快照新鲜度都无关；判定表里排在最前）
          boolean sameConnection = target.connection == null
              || positions.session(target.uuid) == target.connection;
          verdict = FlushWorld.classify(pinned, now, snapshotFresh, currentWorld, sameConnection);
        }
        if (verdict == FlushWorld.CHANGED) {
          discarded = target.cancelledPackets;
          target.batch.clear();
          target.passed.clear();
        } else {
          cancelled = target.cancelledPackets;
          // 封存到此完成：target 已被 pending.remove 摘除、flushed 已置位。此后任何写入方都必须先经
          // pending 表取到对象，再在锁内复核 flushed（onPacketSending 的重取循环、rememberDelivered*），
          // 拿不到这个旧对象；同一把 flushed CAS 也让 discard 无法再进来。所以这里只交出引用与计数，
          // 聚簇（最坏 O(n²)、配置上限 8192 时千万级比较）与 passed 的只读复制都挪到锁外执行——
          // 那把锁正是该玩家封包线程 onPacketSending 要抢的锁。
          clustersDeferred = true;
          passedSealed = target.passed.isEmpty() ? null : target.passed;
        }
      }
      // 锁外：纯聚类与对已封存集合的只读复制。旧对象与新窗口（此后新建的 Pending）已彻底分离，
      // 这段不读任何共享可变状态，也不改变「何时封存、丢弃哪一批、last-write-wins 剔除哪条」的结论；
      // 锁时点仍是上面那次 CAS，封存内容与锁内聚簇逐位相同。
      if (clustersDeferred) {
        clusters = target.batch.drainClusters(config.mergeRadius());
        // 本窗口内「已有更新下发覆盖过」的坐标：缓冲里的同一坐标属旧状态，必须剔除
        passed = passedSealed == null ? Set.of() : new HashSet<>(passedSealed);
      }
      if (timer != null) {
        timer.cancel(false);
      }
      if (verdict == FlushWorld.CHANGED) {
        stats.discarded(ThrottleStats.DiscardSource.FLUSH_SNAPSHOT, discarded);
        // 四条丢弃入口一律留痕：这是「门只开一半」排查里最先要看的东西，不能只有总数
        trace.record(BlockUpdateTrace.Decision.DISCARD, bufferedWorld, false, discarded, 0, 0, 0,
            "冲刷前判定：缓冲世界 " + bufferedWorld + " → 索引世界 " + indexWorld
                + "（索引已换代且活快照同世界：真切维，旧世界区块将整列重建）");
        return false; // 旧世界的被延迟包按丢弃处理：既未下发、也未合并
      }
      if (cancelled <= 0) {
        return false; // 只有清理、没有实包：不计入「冲刷次数/耗时」
      }
      if (verdict == FlushWorld.PREVIOUS_CONNECTION) {
        // 接收连接已换代（同名世界重连时世界名完全相同，只有连接会话能区分两代）。这批属于上一代连接，
        // 不能串给新连接——否则就是「同世界不同连接串包」。新连接会重新收到区块，未投递不构成丢更新。
        stats.pendingOldSession.add(cancelled);
        stats.blockChangesMerged.add(cancelled);
        if (cancelled >= 2) {
          trace.record(BlockUpdateTrace.Decision.REDEEM, bufferedWorld, false, cancelled, 0, 0, 0,
              "缓冲属于上一个连接（世界 " + bufferedWorld + "，接收连接已换代），未投递给新连接");
        }
        return false;
      }
      if (verdict == FlushWorld.CONFLICT) {
        // 两源冲突：这批照常交付（绝不丢），但保留快照——冲突本身要靠拥有线程每 tick 对账修正，
        // 丢掉快照只会让近身判定白 fail-open 一个 tick，下一次冲刷仍是同样的冲突。
        stats.snapshotResync.increment();
        if (cancelled >= 2) {
          trace.record(BlockUpdateTrace.Decision.REDEEM, bufferedWorld, false, cancelled, 0, 0, 0,
              "位置快照（" + currentWorld + "）与索引（" + indexWorld
                  + "）不一致，已按不丢处理并保留快照（索引由拥有线程每 tick 对账修正）");
        }
      } else if (verdict == FlushWorld.UNKNOWN) {
        stats.flushUnknown.increment();
        if (cancelled >= 2) {
          trace.record(BlockUpdateTrace.Decision.REDEEM, bufferedWorld, false, cancelled, 0, 0, 0,
              "世界未知（索引或快照缺失/心跳过期），已按不丢处理");
        }
      }
      deliver(target.uuid, target.connection, target.context, clusters, passed, cancelled);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
    return true;
  }

  /**
   * 冲刷的投递主体：把缓冲结果构造成合并包发给玩家；构造/发送失败时<b>逐条补发</b>。
   *
   * <p>原包在入缓冲时已经取消（见 {@code onPacketSending}），因此这里必须保证「这些变更一定交付」：
   * 合并包成功即交付；失败则按 section 逐条重发同样的更新（幂等），绝不因合并链路故障而丢方块更新。
   */
  private void deliver(UUID playerId, PlayerPositionSnapshots.Session connection, WorldContext pinned,
      List<List<Update<WrappedBlockData>>> clusters, Set<Coord> passed, int cancelled) {
    Player player;
    try {
      player = Bukkit.getPlayer(playerId);
    } catch (Throwable throwable) {
      // 连玩家实例都取不到：这批已取消的更新无法投递。历史实现同样投不了，但那时它被当成
      // 「玩家已离线」静默吞掉；这里改成显式计数 + 留痕，绝不把一次异常伪装成正常离线。
      stats.sendFailed.add(cancelled);
      trace.record(BlockUpdateTrace.Decision.VERIFY_FAIL, null, false, cancelled, 0, 0, 0,
          "取玩家实例失败，整批未投递（" + cancelled + " 个原包）：" + throwable);
      return;
    }
    // ① 先核对接收连接：同 UUID 的新连接会拿到一个新的 Player 实例，只按 uuid 找人不区分两代连接。
    // 这一条不依赖世界名、也不依赖新连接是否已经产出位置快照——新连接刚上线时往往还没有，
    // 若先看快照新鲜度就会落到「世界未知 → 按不丢交付」而把上一连接的批发给它。
    PlayerPositionSnapshots.Session current = positions.session(playerId);
    if (connection != null && current != connection) {
      stats.pendingOldSession.add(cancelled);
      stats.blockChangesMerged.add(cancelled);
      if (cancelled >= 2) {
        trace.record(BlockUpdateTrace.Decision.REDEEM,
            pinned == null ? null : pinned.worldName(), false, cancelled, 0, 0, 0,
            "接收连接已换代（同一 UUID 的新连接），上一连接的已取消批次不投递");
      }
      return;
    }
    if (player == null || Boolean.FALSE.equals(onlineOf(player))) {
      // 客户端不存在（玩家已离线 / 已断连）：取消原包不构成丢更新。
      // 注意 onlineOf 返回 null 表示「探测失败」而非离线——那种情况会继续往下发送，
      // 因为「探测抛异常」绝不能等价于「客户端不需要这批更新」。
      stats.blockChangesMerged.add(cancelled);
      return;
    }
    // ② 送出前最后一次复核（「等待过期 → 封存 → 真正发包」之间还有一段聚簇/构包时间）：
    // 若这段时间里两份来源已经一致地证明换了世界，就不再把旧世界坐标画到新世界（鬼影方块）。
    // 判据与冲刷入口同一张表（FlushWorld.classify），因此这里的丢弃同样要求两源一致，不是单侧猜测；
    // 世界暂时未知 / 冲突时保留「不丢」语义（同一有效连接上的已取消批次绝不因此蒸发）。
    if (!stillSameWorld(playerId, pinned)) {
      stats.discarded(ThrottleStats.DiscardSource.FLUSH_SNAPSHOT, cancelled);
      trace.record(BlockUpdateTrace.Decision.DISCARD,
          pinned == null ? null : pinned.worldName(), false, cancelled, 0, 0, 0,
          "送出前复核：缓冲世界已换代（两源一致），本批不再下发");
      return;
    }
    List<List<Update<WrappedBlockData>>> survivors =
        passed.isEmpty() ? clusters : dropPassed(clusters, passed);
    boolean anySurvivor = false;
    for (List<Update<WrappedBlockData>> cluster : survivors) {
      if (!cluster.isEmpty()) {
        anySurvivor = true;
        break;
      }
    }
    if (!anySurvivor) {
      // 所有坐标都已有更新的下发：无需发包（不计 blockMergeBatches，避免「合并批次」虚高）
      stats.blockChangesMerged.add(cancelled);
      return;
    }
    if (trySendMerged(player, survivors)) {
      stats.blockMergeBatches.increment();
      stats.blockChangesMerged.add(cancelled);
      return;
    }
    // 合并包构造/发送失败：逐条补发。原包入缓冲时已取消，这里必须把变更交付出去（幂等重发）。
    // 逐条投递由 UpdateDelivery 承担：每条独立兜底，任一条抛出或失败都不影响其余条目——
    // 裸循环在中途抛出会把剩下的条目一起丢掉，而那正是「门只开一半」的最后一条成因。
    int total = 0;
    for (List<Update<WrappedBlockData>> cluster : survivors) {
      total += cluster.size();
    }
    int resent = UpdateDelivery.deliverEach(survivors,
        update -> sendSection(player,
            new SectionKey(update.x() >> 4, update.y() >> 4, update.z() >> 4), List.of(update)),
        stats.sendFailed);
    stats.blockChangesMerged.add(cancelled);
    if (resent < total) {
      Update<WrappedBlockData> first = firstUpdate(survivors);
      trace.record(BlockUpdateTrace.Decision.VERIFY_FAIL, null, false, total - resent,
          first == null ? 0 : first.x(), first == null ? 0 : first.y(), first == null ? 0 : first.z(),
          "逐条补发失败 " + (total - resent) + " 条（客户端已确实缺这些方块更新）");
      if (errorCounter.getAndIncrement() < Constants.MAX_ERROR_LOGS) {
        plugin.getLogger().warning("合并包构造失败后逐条补发仍有 " + (total - resent)
            + " 条未发出（这些方块更新已丢失，客户端会停在旧方块上）");
      }
    }
  }

  /**
   * 送出前复核：此刻的两份来源是否仍指向缓冲时固定的那个世界。
   *
   * <p>只回答一件事：<b>是否已证明换了世界</b>（{@link FlushWorld#CHANGED}）。未知 / 冲突一律照常发送——
   * 宁可让客户端短暂看到旧世界坐标（随区块重建自愈），也不把已取消的更新丢掉（永久缺格）。
   *
   * @param pinned 入缓冲时固定的世界上下文（{@code null} = 历史缓冲，直接放行）
   */
  private boolean stillSameWorld(UUID playerId, WorldContext pinned) {
    if (pinned == null) {
      return true;
    }
    try {
      WorldContext now = PlayerWorldIndex.context(playerId);
      PlayerPositionSnapshots.Position snapshot = positions.get(playerId);
      boolean fresh = snapshot != null && !positions.isStale(playerId);
      // 连接身份已在 deliver 入口核对过（本方法只处理「同一连接」的世界复核），故 sameConnection 传 true
      return FlushWorld.classify(pinned, now, fresh, fresh ? snapshot.world() : null, true)
          != FlushWorld.CHANGED;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return true; // 读不到 → 照常发送（绝不把异常当「已换世界」）
    }
  }

  /** 取第一批非空簇的第一条更新（仅用于取证记录首坐标）；没有时返回 {@code null}。 */
  private static Update<WrappedBlockData> firstUpdate(
      List<List<Update<WrappedBlockData>>> clusters) {
    for (List<Update<WrappedBlockData>> cluster : clusters) {
      if (cluster != null && !cluster.isEmpty()) {
        return cluster.get(0);
      }
    }
    return null;
  }

  /**
   * 玩家是否仍在线；{@code null} = <b>探测失败</b>（不等于离线）。
   *
   * <p>为什么要包一层：本方法在合并线程 {@code MikuXrayNet-BlockMerge} 上被调用，而 Folia 对「在非该实体
   * 所属区域线程上读实体状态」是先打 ERROR 再抛异常。探测一旦抛出，旧实现会让整批已取消的更新随着
   * {@code flushInternal} 的兜底 catch 一起消失；这里把异常折成「未知」，调用方按「照常发送」处理——
   * 对已断开的连接发送会被 Netty 直接丢弃，代价只是一个无副作用的写操作。
   */
  private static Boolean onlineOf(Player player) {
    if (player == null) {
      return Boolean.FALSE;
    }
    try {
      return player.isOnline();
    } catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * 从簇中剔除「本窗口内已有更新下发覆盖过」的坐标（last-write-wins：只保留未被更新下发者）。
   *
   * <p>包可见：供离线单测直接驱动该纯逻辑（真实链路依赖 ProtocolLib 封包，离线不可用）。
   */
  static List<List<Update<WrappedBlockData>>> dropPassed(
      List<List<Update<WrappedBlockData>>> clusters, Set<Coord> passed) {
    List<List<Update<WrappedBlockData>>> survivors = new ArrayList<>(clusters.size());
    for (List<Update<WrappedBlockData>> cluster : clusters) {
      List<Update<WrappedBlockData>> kept = new ArrayList<>(cluster.size());
      for (Update<WrappedBlockData> update : cluster) {
        if (!passed.contains(new Coord(update.x(), update.y(), update.z()))) {
          kept.add(update);
        }
      }
      survivors.add(kept);
    }
    return survivors;
  }


  private void flushAll() {
    for (Pending target : new ArrayList<>(pending.values())) {
      try {
        flush(target);
      } catch (Throwable throwable) {
        logThrottled(throwable);
      }
    }
  }

  /**
   * 构造并发送合并包。
   *
   * @return true 表示本批次全部构造成功且通过回读校验
   */
  private boolean trySendMerged(Player player, List<List<Update<WrappedBlockData>>> clusters) {
    try {
      if (player == null || Boolean.FALSE.equals(onlineOf(player))) {
        // 探测失败（null）按「仍在线」继续发送：见 onlineOf 的说明，绝不把异常当离线。
        return false;
      }
      boolean sentAny = false;
      for (List<Update<WrappedBlockData>> cluster : clusters) {
        Map<SectionKey, List<Update<WrappedBlockData>>> sections = groupBySection(cluster);
        for (Map.Entry<SectionKey, List<Update<WrappedBlockData>>> section : sections.entrySet()) {
          List<Update<WrappedBlockData>> blocks = section.getValue();
          int limit = Math.max(1, config.maxPerPacket());
          if (blocks.size() <= limit) {
            if (!sendSection(player, section.getKey(), blocks)) {
              return false;
            }
            sentAny = true;
            continue;
          }
          if (!config.resendOnOverflow()) {
            // 不允许拆分：放弃本次合并，原包照常放行
            return false;
          }
          for (int from = 0; from < blocks.size(); from += limit) {
            if (!sendSection(player, section.getKey(), blocks.subList(from, Math.min(blocks.size(), from + limit)))) {
              return false;
            }
            sentAny = true;
          }
        }
      }
      return sentAny;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
  }

  /**
   * 纯函数：合并包写回的 packed 坐标是否与意图逐项一致，且（小包）无重复坐标。
   *
   * <p><b>为什么必须校验内容</b>：合并包把绝对方块坐标压成段内 {@code short}。若两个坐标落进同一个
   * packed short（例如同一 x/z、y 与 y+1 的两片门被算错），客户端会把两条状态都应用到同一个位置上：
   * 另一片方块永远收不到更新，表现为「门只开一半、没开的那半还挡人」，而长度校验看不出来。
   *
   * <p>重复检测只在不超过 {@link #DUPLICATE_SCAN_LIMIT} 条时做：这条路径在封包线程上，排序 + 扫描的
   * 成本必须与包大小相称；超过该规模的都是显形批量包（每坐标只出现一次），重复检测的价值远低于分配成本。
   *
   * @param written  实际写进包的 packed 坐标（回读所得），可为 {@code null}
   * @param intended 意图写入的坐标序列（顺序必须一致）
   * @return true 表示一致；{@code null} / 长度不符 / 任一项坐标不符 / 小包内出现重复坐标 → false
   */
  static boolean verifyPositions(short[] written, List<Update<WrappedBlockData>> intended) {
    if (written == null || intended == null || written.length != intended.size()
        || written.length == 0) {
      return false;
    }
    for (int i = 0; i < written.length; i++) {
      int raw = SectionCoords.unsigned(written[i]);
      Update<WrappedBlockData> update = intended.get(i);
      if ((update.x() & SectionCoords.LOCAL_MASK) != SectionCoords.x(raw)
          || (update.y() & SectionCoords.LOCAL_MASK) != SectionCoords.y(raw)
          || (update.z() & SectionCoords.LOCAL_MASK) != SectionCoords.z(raw)) {
        return false;
      }
    }
    if (written.length <= DUPLICATE_SCAN_LIMIT) {
      short[] sorted = written.clone();
      java.util.Arrays.sort(sorted);
      for (int i = 1; i < sorted.length; i++) {
        if (sorted[i] == sorted[i - 1]) {
          return false;
        }
      }
    }
    return true;
  }

  /** 合并包重复坐标检测的规模上限（见 {@link #verifyPositions}）。 */
  static final int DUPLICATE_SCAN_LIMIT = 512;

  /** 最近处置环（诊断用）。 */
  public BlockUpdateTrace trace() {
    return trace;
  }

  /** 发送单个 section 的合并结果：多条走 MULTI_BLOCK_CHANGE，单条走 BLOCK_CHANGE。 */
  private boolean sendSection(Player player, SectionKey section, List<Update<WrappedBlockData>> blocks) {
    PacketContainer packet;
    if (blocks.size() == 1) {
      Update<WrappedBlockData> only = blocks.get(0);
      BlockPosition position = new BlockPosition(only.x(), only.y(), only.z());
      packet = new PacketContainer(PacketType.Play.Server.BLOCK_CHANGE);
      // writeSafely 的返回值是 StructureModifier（可链式调用），**不是**「是否写成功」——
      // 访问器映射失效（字段类型/布局变化导致该 modifier 为空）时它同样静默 no-op 而不抛异常。
      // 因此这里不能用返回值判断，必须回读：位置与方块数据都要读回比对，缺一不可。只校验位置的话，
      // 合并包会带一个默认/空方块状态发出，而原包已被取消——客户端看到一块被改成空气或错误方块的方块，
      // 且 verified 会被置 true、此后不再保留原包。多条路径的长度回读能挡住同类问题，单条路径必须自己校验。
      packet.getBlockPositionModifier().writeSafely(0, position);
      packet.getBlockData().writeSafely(0, only.value());
      BlockPosition readPosition = packet.getBlockPositionModifier().readSafely(0);
      if (readPosition == null || !readPosition.equals(position)
          || packet.getBlockData().readSafely(0) == null) {
        return false;
      }
    } else {
      WrappedBlockData[] data = new WrappedBlockData[blocks.size()];
      short[] positions = new short[blocks.size()];
      for (int i = 0; i < blocks.size(); i++) {
        Update<WrappedBlockData> update = blocks.get(i);
        data[i] = update.value();
        // 与协议一致：section 内相对坐标打包为 (x & 15) << 8 | (z & 15) << 4 | (y & 15)。
        // 早前把 x 与 y 放反了——合并包会把方块画到错误高度，客户端表现为方块错位/闪一下。
        positions[i] = (short) SectionCoords.pack(update.x(), update.y(), update.z());
      }
      BlockPosition expectedSection = new BlockPosition(section.x(), section.y(), section.z());
      packet = new PacketContainer(PacketType.Play.Server.MULTI_BLOCK_CHANGE);
      packet.getSectionPositions().writeSafely(0, expectedSection);
      packet.getBlockDataArrays().writeSafely(0, data);
      packet.getShortArrays().writeSafely(0, positions);
      // trustEdges 不写：编译期 ProtocolLib（5.3.0）未为本包提供该字段的类型化访问器
      // （MultiBlockChangeInfo 只含 location/data/chunk，jar 内也搜不到 trustEdges），其结构由运行时
      // 服务端包类反射得出，无法在编译期确认存在；此处不发明 API（不盲写 getBooleans 的某个下标，
      // 以免在字段布局不同的服务端上写错字段）。合并包由本模块自行构造，未设置该布尔时沿用默认值，
      // 语义为「不信任边缘」，安全侧成立。

      // 回读自检：字段必须真实存在、section 位置正确、条目数与写入一致，否则视为结构不可用（fail-open）。
      // 位置也必须比对：映射失效时若只有位置写丢，所有坐标会落到默认 section (0,0,0)，而原包已被取消。
      short[] readPositions = packet.getShortArrays().readSafely(0);
      WrappedBlockData[] readData = packet.getBlockDataArrays().readSafely(0);
      BlockPosition readSection = packet.getSectionPositions().readSafely(0);
      if (readPositions == null || readData == null || readSection == null
          || !readSection.equals(expectedSection)
          || readPositions.length != positions.length || readData.length != data.length) {
        return false;
      }
      // 内容级校验（长度相符之后才做）：旧实现只比长度，坐标内容对不上照样发出去。若两个相邻坐标
      // （门/床的上半与下半）被压成同一个 packed short，客户端只会应用其中一片，另一片保持旧状态并
      // 继续阻挡玩家——长度校验对此完全无感。因此这里逐项解码回坐标与意图比对，并要求小包内无重复，
      // 任一不符即放弃本合并包、退回逐条补发（原包已被取消，逐条补发是唯一能保证不丢更新的路）。
      if (!verifyPositions(readPositions, blocks)) {
        stats.mergeVerifyFailed.increment();
        Update<WrappedBlockData> first = blocks.get(0);
        trace.record(BlockUpdateTrace.Decision.VERIFY_FAIL, null, true, blocks.size(),
            first.x(), first.y(), first.z(),
            "合并包坐标回读与意图不一致（section " + section.x() + "," + section.y() + ","
                + section.z() + "，共 " + blocks.size() + " 条），已改逐条补发");
        return false;
      }
    }

    // filters=false：合并包由本模块自行发出，不能再触发（本插件的）监听器，否则会自我递归
    protocolManager.sendServerPacket(player, packet, false);
    return true;
  }

  /** 解析出站变更包为统一条目；任何异常或结构不符都返回 null（调用方放行原包）。 */
  private List<Update<WrappedBlockData>> readUpdates(PacketType type, PacketContainer packet) {
    try {
      List<Update<WrappedBlockData>> updates = new ArrayList<>();
      if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        BlockPosition position = packet.getBlockPositionModifier().read(0);
        WrappedBlockData data = packet.getBlockData().read(0);
        if (position == null || data == null || data.getHandle() == null) {
          return null;
        }
        updates.add(new Update<>(position.getX(), position.getY(), position.getZ(), data));
        return updates;
      }

      BlockPosition section = packet.getSectionPositions().read(0);
      short[] positions = packet.getShortArrays().read(0);
      WrappedBlockData[] data = packet.getBlockDataArrays().read(0);
      if (section == null || positions == null || data == null || positions.length != data.length) {
        return null;
      }
      int baseX = section.getX() << 4;
      int baseY = section.getY() << 4;
      int baseZ = section.getZ() << 4;
      for (int i = 0; i < positions.length; i++) {
        // 协议：(x & 15) << 8 | (z & 15) << 4 | (y & 15)，唯一实现在 SectionCoords。
        // 早前这里把 bit8..11 当 x、低 4 位当 y（x/y 对调），与 sendSection 的对调恰好抵消才没有暴露。
        int raw = SectionCoords.unsigned(positions[i]);
        int x = baseX + SectionCoords.x(raw);
        int z = baseZ + SectionCoords.z(raw);
        int y = baseY + SectionCoords.y(raw);
        WrappedBlockData state = data[i];
        if (state == null || state.getHandle() == null) {
          return null; // 无法完整接管时保留本来的包。
        }
        updates.add(new Update<>(x, y, z, state));
      }
      return updates;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return null;
    }
  }

  private static Map<SectionKey, List<Update<WrappedBlockData>>> groupBySection(
      List<Update<WrappedBlockData>> cluster) {
    Map<SectionKey, List<Update<WrappedBlockData>>> sections = new LinkedHashMap<>();
    for (Update<WrappedBlockData> update : cluster) {
      SectionKey key = new SectionKey(update.x() >> 4, update.y() >> 4, update.z() >> 4);
      sections.computeIfAbsent(key, ignored -> new ArrayList<>()).add(update);
    }
    return sections;
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "方块变更合并失败，已按原包放行", throwable);
    }
  }
}