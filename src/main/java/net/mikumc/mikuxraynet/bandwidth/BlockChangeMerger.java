package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
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
 * <p><b>同步监听无法延迟放行</b>，因此走 ProtocolLib 异步通道：
 * {@code incrementProcessingDelay()} 登记延迟 → 变更入有界缓冲 → 时间窗到期（或条目超限）时冲刷 →
 * 合并包通过 {@link ProtocolManager#sendServerPacket}（Netty 安全）发出，原包被取消。
 *
 * <p><b>近身变更立即放行</b>：距离玩家 {@code block-changes.immediate-radius}（默认 8 格）以内的
 * 方块变更<b>不进合并窗口</b>，原包立即放行——玩家自己挖/放方块时目标就在脚边，被窗口延迟会让
 * 客户端预测得不到确认，表现为「挖掘时顿一下」。半径外的变更（爆炸、大面积刷新等）照常合并。
 * 判定只读一份由实体调度器<b>每 tick 刷新</b>的位置快照（见 {@link PlayerPositionSnapshots}）：
 * 封包线程不触碰实体状态，且刷新跟随实体/区域归属，因此传送等任何位置变化都会在一个 tick 内生效
 * （旧实现依赖 {@code PlayerMoveEvent}，而传送有独立 HandlerList，会按陈旧坐标判错）。
 *
 * <p><b>fail-open</b>：读不出字段、构造失败、玩家离线 —— 一律原样放行原包，绝不丢更新。
 * 原包放行通过 {@link AsyncMarker} 的 {@code signalPacketTransmission} 完成，并用一次性闸保证
 * 「恰好放行一次」。位置快照不可用（尚未建立 / 刷新或读取异常）时同样 fail-open：近身判定按
 * 「立即放行」处理，宁可少合并、绝不制造延迟。
 *
 * <p><b>fail-open 的唯一例外</b>：合并包构造/发送失败时，对「携带的坐标已全部经立即放行先行下发」
 * 的原包会予以取消（见 {@link #flush} 的 last-write-wins 分支）——此时这些坐标的新态已交付，取消旧态
 * 原包不构成丢更新，反而避免旧态晚到覆盖新态（方块回退）；未全部先行下发的原包仍照常放行。
 *
 * <p><b>结构自检是「每次发送」而非「首包」</b>：{@code sendSection} 每次构造完合并包都会回读刚写入的
 * 字段做结构校验（单条与多条的路径都做）。校验通过会置 {@code verified}；在第一次成功发送之前
 * （{@code verified} 仍为 false）合并包照发，但<em>同时</em>保留原包（重复下发相同方块状态是无害的
 * 幂等操作），此后才开始取消原包。
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
 * 持锁的临界区里「读取 + 剔除旧态」的，但把合并包发到客户端、以及取消原包，都发生在<b>释放锁之后</b>。
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
   * <p><b>有界</b>：条目上限见 {@link #passedLimit}。窗口期若持续有近身直通 / 批量直通包（大规模显形、
   * 流水、爆炸连续发生），集合会一直增长；没有上限就等于让「内存有界」这条约束失效。
   */
  private static final class Pending {
    private final UUID uuid;
    private final List<Held> held = new ArrayList<>();
    private final Set<Coord> passed = new HashSet<>();
    /**
     * {@link #passed} 的条目上限；超限即整体清空（见 {@link #rememberDelivered}）。
     *
     * <p><b>为什么按 {@code maxPendingEntries × 4}</b>：正常窗口里「已直通坐标」与「缓冲条目」同量级
     * （每条被直通的变更至多对应一条缓冲条目），4 倍缓冲上限留足余量，不至于让正常流量频繁触发清空；
     * 同时保证上限随配置缩放，且不低于 {@link #MIN_PASSED_LIMIT} 这一「极小缓冲配置下也不至于每包清空」的下限。
     */
    private final int passedLimit;
    private final BlockChangeBatch<WrappedBlockData> batch;
    private ScheduledFuture<?> timer;

    /** {@link #passed} 的条目下限（极小缓冲配置下也不至于每包都触发清空）。 */
    private static final int MIN_PASSED_LIMIT = 1024;

    /**
     * 由缓冲条目上限推出「已直通坐标」上限：{@code maxPendingEntries × 4}，并不低于
     * {@link #MIN_PASSED_LIMIT}。
     *
     * <p><b>为什么需要上限</b>：{@link #passed} 是本窗口内「已直通下发」的坐标集合。窗口期内若持续有
     * 近身直通包或远距离批量直通包（大规模显形、爆炸、流水），它会一直增长——违反「内存有界」不变量。
     * 超限时整体清空：代价只是窗口到期时可能把某个坐标的<b>旧态</b>再下发一次（客户端短暂回退一格，
     * 下一次变更即修正），绝不影响正确性；这与 {@code revealEcho} 满即清空是同一策略。
     */
    private static int passedLimit(int maxPendingEntries) {
      long scaled = (long) Math.max(1, maxPendingEntries) * 4L;
      return (int) Math.min(Integer.MAX_VALUE, Math.max(MIN_PASSED_LIMIT, scaled));
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
      this.passedLimit = passedLimit(maxEntries);
    }
  }

  /** 被延迟的原包与其一次性放行闸。 */
  private static final class Held {
    private final PacketEvent event;
    private final AtomicBoolean signalled = new AtomicBoolean();
    /**
     * 该原包携带的更新。
     *
     * <p><b>为什么存原始更新而不是折算好的坐标清单</b>：坐标清单只在 fail-open 的
     * {@link #allCoordsPassed} 判定里用得到（罕见路径），而对每个被延迟的包都折算一遍，
     * 会在封包热路径上产生「ArrayList + 每个坐标一个 Coord」的固定分配（每 tick 全服大量相对/方块
     * 变更包都会走这里）。改为惰性折算，把这份开销推迟到真正需要时（{@link #coordsOf}）。
     */
    private final List<Update<WrappedBlockData>> updates;

    private Held(PacketEvent event, List<Update<WrappedBlockData>> updates) {
      this.event = event;
      this.updates = updates;
    }

    List<Update<WrappedBlockData>> updates() {
      return updates;
    }
  }

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final AsynchronousManager asynchronousManager;
  private final BandwidthConfig.BlockChanges config;
  private final ThrottleStats stats;
  private final ScheduledExecutorService flusher;
  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
  /**
   * 玩家位置快照（由实体调度器在玩家所属线程每 tick 刷新，封包线程只读）。
   *
   * <p>取代旧的 {@code PlayerMoveEvent} 坐标缓存：传送有独立 HandlerList，缓存会陈旧。
   *
   * @see PlayerPositionSnapshots
   */
  private final PlayerPositionSnapshots positions = new PlayerPositionSnapshots();
  /** 每玩家的位置刷新任务句柄（退出 / 实体退役 / 停用时逐一取消）。 */
  private final ConcurrentHashMap<UUID, ScheduledTask> positionTasks = new ConcurrentHashMap<>();
  /**
   * 停用中标记：{@link #stop()} 首行置位，供 {@link #schedulePositionRefresh} 做「入账后再读一次」收口。
   *
   * <p>Folia 上 stop() 跑在命令线程、onJoin→schedulePositionRefresh 跑在玩家区域线程，两者真正并发；
   * 没有这道闸，stop() 的取消循环可能先跑完，之后入表的位置刷新任务（<b>每 tick</b> 执行）
   * 就永远无人取消。
   */
  private volatile boolean stopping;
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

  private volatile boolean verified;
  private AsyncListenerHandler handler;

  public BlockChangeMerger(Plugin plugin, ProtocolManager protocolManager,
      AsynchronousManager asynchronousManager, BandwidthConfig.BlockChanges config, ThrottleStats stats) {
    super(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.asynchronousManager = asynchronousManager;
    this.config = config;
    this.stats = stats;
    this.flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-BlockMerge");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** 注册异步监听器（真异步扣包）与每 tick 的位置快照刷新任务，并做一次启动期契约探测。 */
  public void start() {
    // 启动期探测一次：让「服务端结构变更导致合并静默失效」可见（不改变任何行为）
    probeBlockDataContract();
    handler = asynchronousManager.registerAsyncHandler(this);
    handler.start();
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    // 位置快照任务「始终登记」：它不只服务近身判定，还承载「玩家当前世界」，而跨世界守卫必须靠它
    // 才能发现「玩家已切维」（见 refreshPosition / isCrossWorld）。immediate-radius=0 时快照只用于
    // 世界判定：静止玩家的每 tick 开销仅为一次 CHM 读（方块坐标与世界都未变时不发布快照），可忽略。
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      schedulePositionRefresh(player);
    }
    plugin.getLogger().fine("带宽模块已启用：方块变更合并（时间窗 " + config.mergeWindowMillis()
        + "ms，邻域半径 " + config.mergeRadius() + "，立即放行半径 " + config.immediateRadius()
        + " 格，缓冲上限 " + config.maxPendingEntries() + "）");
  }

  /** 注销监听器并偿还所有正在延迟中的原包。 */
  public void stop() {
    // 首行置位：此后任何迟到的 schedulePositionRefresh 都会自行取消（见 stopping 字段说明）。
    stopping = true;
    // 顺序很关键：必须先注销异步监听器、再排空。旧顺序（先 flush 再 unregister）在两者之间留了一个
    // 窗口：封包线程仍可为新建的 Pending 成功 schedule 定时器，随后 shutdownNow 把定时器连其队列一起
    // 丢弃——该批 held 原包既没被取消也没被放行，只能等 ProtocolLib 超时兜底（停用瞬间滞留原包）。
    if (handler != null) {
      try {
        asynchronousManager.unregisterAsyncHandler(handler);
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "注销方块变更异步监听器时出现异常（通常可忽略）", throwable);
      }
      handler = null;
    }
    flushAll();
    HandlerList.unregisterAll(this);
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
    // 与 start() 一致：位置快照同时承担跨世界检测，必须始终登记（见 start() 的说明）。
    schedulePositionRefresh(event.getPlayer());
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
   * <p>这是三道兜底的<b>主路径</b>：
   * <ol>
   *   <li>本事件（零窗口，覆盖传送门 / 跨世界传送等一切经由 {@code changeDimension} 的切维；若某条
   *       实现路径不触发本事件，下面两道兜底仍然生效，只是窗口退化回「≤1 tick」）；</li>
   *   <li>{@link #refreshPosition} 的每 tick 快照比对（覆盖「漏接本事件」的实现差异）；</li>
   *   <li>封包线程的 {@code isCrossWorld} 守卫（覆盖「切维后仍有旧世界旧包到达」）。</li>
   * </ol>
   * 命中本事件后，旧世界封包会被第 3 道守卫按「直通 + 丢弃」处理，不会重新入缓冲。
   */
  @EventHandler(ignoreCancelled = true)
  public void onChangedWorld(PlayerChangedWorldEvent event) {
    discardPending(event.getPlayer().getUniqueId());
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
   */
  private void schedulePositionRefresh(Player player) {
    UUID playerId = player.getUniqueId();
    long period = 1L;
    ScheduledTask task = Schedulers.repeatOnEntity(plugin, player, period, period,
        () -> refreshPosition(player, playerId), () -> discardPositionSnapshot(playerId));
    if (task == null) {
      // 实体已退役（调度失败）：无句柄可存，读取侧会因缺快照而 fail-open
      return;
    }
    ScheduledTask previous = positionTasks.put(playerId, task);
    if (previous != null) {
      previous.cancel();
    }
    // 入账后再读一次 volatile stopping（理由同 EntityCuller.scheduleRecheck）：若 stop() 的取消循环
    // 已经跑完，它看不到这条任务，必须自行取消。
    if (stopping) {
      positionTasks.remove(playerId, task);
      task.cancel();
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
   * 继续每 tick 写进快照，否则跨世界丢弃（切维事件 / 本方法比对 / 封包线程守卫）全部失效。因此该分支走
   * {@link PlayerPositionSnapshots#refreshWorldOnly}（坐标标记为未知、近身判定 fail-open）；切出黑名单
   * 世界后自动回到下面的正常分支，下一次刷新即恢复坐标捕获。
   */
  private void refreshPosition(Player player, UUID playerId) {
    try {
      // 世界名必须与坐标在同一线程（拥有该玩家的线程）读取后一起写进快照：封包线程只读快照，
      // 绝不自己调 player.getWorld()（Folia 上那是跨区域读实体状态）。
      String worldName = player.getWorld().getName();
      // 黑名单世界跳过坐标读取（省掉每 tick 三次 getter + 三次 floor 比较），只更新世界名。
      String previousWorld = WorldBlacklistConfig.isBlacklistedNow(worldName)
          ? positions.refreshWorldOnly(playerId, worldName)
          : positions.refresh(playerId, worldName, player.getX(), player.getY(), player.getZ());
      // 跨世界切换：立即丢弃旧世界的待发缓冲。只靠封包路径的跨世界守卫会漏掉「切维之后不再有旧世界
      // 方块包到达」的情形——那种情形下窗口定时器到期仍会把旧世界坐标发进新世界（鬼影方块）。
      if (previousWorld != null && !previousWorld.equals(worldName)) {
        discardPending(playerId);
      }
    } catch (Throwable throwable) {
      positions.discard(playerId);
      noticeSnapshotFailOpen(throwable);
    }
  }

  /** 玩家退出 / 实体退役：取消其刷新任务并丢弃快照。 */
  private void cancelPositionRefresh(UUID playerId) {
    ScheduledTask task = positionTasks.remove(playerId);
    if (task != null) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已随实体退役结束，取消失败可忽略
      }
    }
    positions.discard(playerId);
  }

  /** 实体退役回调：摘掉任务句柄并丢弃快照（此后读取侧一律 fail-open）。 */
  private void discardPositionSnapshot(UUID playerId) {
    positionTasks.remove(playerId);
    positions.discard(playerId);
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
      ScheduledTask task = positionTasks.remove(playerId);
      if (task != null) {
        try {
          task.cancel();
        } catch (Throwable ignored) {
          // 任务可能已随实体退役结束，取消失败可忽略
        }
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
   * 本包所属世界是否已不是该玩家「当前」所在的世界（跨世界切换窗口）。
   *
   * <p>判据只有一份：玩家所属线程每 tick 写入快照的世界名。快照缺失（刚登录 / 刚重置 / 读取异常）时
   * 无法判断，按「不是跨世界」处理（即照常走后续流程）——这与其它快照缺失点的 fail-open 方向一致：
   * 宁可按旧逻辑合并，也不凭空丢弃合并能力。
   */
  private boolean isCrossWorld(UUID playerId, String packetWorld) {
    PlayerPositionSnapshots.Position snapshot = positions.get(playerId);
    return snapshot != null && snapshot.world() != null && !snapshot.world().equals(packetWorld);
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
   * 丢弃某玩家当前的待发缓冲（没有缓冲时无操作）。三条入口（切维事件 / 位置快照比对 / 封包守卫）
   * 都只做一行转发，语义完全一致。
   *
   * @return 是否确有缓冲被丢弃
   */
  boolean discardPending(UUID playerId) {
    return discardPendingFor(pending, playerId, this::discard);
  }

  /**
   * 「按玩家丢弃待发缓冲」的<b>纯逻辑</b>：取出该玩家的缓冲并交给 {@code disposer}。
   *
   * <p><b>为什么抽成包级静态方法</b>：离线单测不构造本类（避免初始化 ProtocolLib 的 {@code PacketType}、
   * 起冲刷线程、伪造 {@code PacketEvent}/{@code AsyncMarker}），只驱动这条路由逻辑——按 uuid <b>精确</b>
   * 取到该玩家的缓冲、交给丢弃回调、其它玩家不受影响、重复调用幂等。事件处理器只做一行转发，
   * 因此这里测到的就是切维丢弃的全部判定逻辑。
   *
   * <p>签名用泛型而不出现 {@code Pending}：单测可用任意值类型驱动，不必为了测一行路由把私有缓冲类型
   * 暴露出去；生产调用点传的就是 {@code Pending}。另外本方法<b>没有任何发包入口</b>（不收
   * ProtocolManager/Player），因此「切维时绝不发送合并包」由签名本身保证。
   *
   * @param buffers  待发缓冲表（生产为 {@link #pending}）
   * @param playerId 玩家
   * @param disposer 真正的丢弃动作（生产为 {@link #discard(Pending)}）
   * @return 是否确有缓冲被丢弃
   */
  static <B> boolean discardPendingFor(Map<UUID, B> buffers, UUID playerId, Consumer<B> disposer) {
    if (buffers == null || playerId == null || disposer == null) {
      return false;
    }
    B buffer = buffers.get(playerId);
    if (buffer == null) {
      return false;
    }
    disposer.accept(buffer);
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
   * <p><b>为什么在调用线程内联执行</b>：它不聚簇、不构包、不发送，只做「取消原包 + 恰好放行一次」，
   * 没有 {@link #flushInternal} 那种阻塞封包线程的风险；而内联才能保证取消发生在任何定时冲刷之前
   * （异步投递会重新打开「旧世界数据落在切维包之后」的窗口）。
   */
  private void discard(Pending target) {
    List<Held> held = List.of();
    ScheduledFuture<?> timer = null;
    try {
      synchronized (target) {
        // 与 flush 共用同一个「恰好处理一次」闸：谁先拿到 flushed 谁负责，另一方成 no-op
        if (!target.flushed.compareAndSet(false, true)) {
          return;
        }
        pending.remove(target.uuid, target);
        timer = target.timer;
        target.timer = null;
        held = new ArrayList<>(target.held);
        target.held.clear();
        target.batch.clear();
        target.passed.clear();
      }
      if (timer != null) {
        timer.cancel(false);
      }
      // 口径：这次被<b>整批丢弃</b>的原包数（切维 / 黑名单切换）。本方法结构上没有任何发包入口，
      // 因此 CAS 成功时拿到多少个 Held，就是「既未下发、也未合并」的多少个原包。
      stats.blockChangesDiscarded.add(held.size());
      if (held.isEmpty()) {
        return;
      }
      // 取消 = 丢弃：这些坐标属于玩家已离开的世界，绝不能按新世界下发。release 负责「恰好放行一次」。
      for (Held entry : held) {
        entry.event.setCancelled(true);
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
    } finally {
      // 兜底：任何未放行过的原包都必须放行（release 为一次性，重复调用无副作用）
      for (Held entry : held) {
        release(entry);
      }
    }
  }

  /**
   * 该玩家的近身变更判定：<b>只读位置快照</b>（封包线程不触碰任何 Bukkit API）。
   *
   * <p>fail-open：快照尚未建立（刚登录 / 刚重置）或读取异常 → 按近身处理（立即放行）。
   * 与旧实现的唯一语义差别就在这里：旧实现快照缺失时退化为「照常合并」（会延迟），
   * 现在改为「立即放行」——无法判断距离时宁可少合并，绝不制造延迟。
   */
  private boolean shouldPassThroughImmediately(UUID playerId, List<Update<WrappedBlockData>> updates) {
    int radius = config.immediateRadius();
    if (radius <= 0) {
      // 配置关闭立即放行：与旧行为一致（全部走合并）
      return false;
    }
    try {
      PlayerPositionSnapshots.Position position = positions.get(playerId);
      if (position == null) {
        noticeSnapshotFailOpen(null);
      }
      return PlayerPositionSnapshots.immediatePass(position, updates, radius);
    } catch (Throwable throwable) {
      noticeSnapshotFailOpen(throwable);
      return true;
    }
  }

  /**
   * 延迟登记与放行：<b>先入 held 缓冲、再登记延迟</b>。
   *
   * <p>顺序不能颠倒：{@code flush()} 的兜底只遍历 {@code target.held} 来放行原包，若先
   * {@code incrementProcessingDelay()} 却没能把条目放进 {@code held}（登记后构造/入队抛异常），
   * 该包就永远等不到放行（永久卡包）。把 {@code held.add} 提到登记之前后，两者都在
   * {@code synchronized(target)} 内、且 {@code flush()} 持同一把锁，因此顺序调整不产生竞态；
   * 登记/调度整段再包 {@code try/catch}，异常时撤销条目并放行原包，保证「恰好放行一次」。
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
    // 世界黑名单：命中世界不做任何带宽优化（变更合并一并跳过，原包照常放行）。
    // 名单为空（默认）时 isBlacklistedNow 直接早退，热路径近乎零开销。
    //
    // 封包线程读 player.getWorld() 属跨线程读取（Folia 上是跨区域读实体状态），异常一律 fail-open
    // 直通：取不到世界名就无法判定黑名单与跨世界，此时绝不把变更包卡进缓冲，原包原样放行。
    String worldName;
    try {
      worldName = player.getWorld().getName();
    } catch (Throwable ignored) {
      // 无法判定世界：直通（原包照常下发，不入缓冲、不登记延迟）
      stats.blockChangesPassed.increment();
      return;
    }
    if (WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      // 黑名单世界不做任何带宽优化；同时丢弃可能来自「上一个非黑名单世界」的待发缓冲——
      // 否则窗口到期会把旧世界坐标发进黑名单世界（同样是鬼影方块）。
      discardPending(player.getUniqueId());
      return;
    }
    // 跨世界守卫：封包里的方块坐标<b>不含世界标识</b>。若玩家已经离开该包所属的世界（合并窗口内
    // 传送门 / /tp 跨世界），把该包照常合并、或只入缓冲等窗口到期，都会在客户端按<b>新世界</b>坐标
    // 解释这些变更 → 新世界对应位置出现「鬼影方块」，直到该区块被重新下发。
    // 因此：本包立即原样放行（它此刻仍在旧世界的发送流里，语义正确），并把该玩家在旧世界攒下的缓冲
    // <b>整体丢弃</b>（不发送、不重编码）——玩家离开后旧世界区块会整列重建，丢弃不构成丢更新。
    // 世界名取自玩家所属线程写入的快照，不在封包线程读实体状态。
    if (isCrossWorld(player.getUniqueId(), worldName)) {
      discardPending(player.getUniqueId());
      stats.blockChangesPassed.increment();
      return;
    }

    AsyncMarker marker = event.getAsyncMarker();
    if (marker == null) {
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
        // 本包已是成形批量包：无论有无待发缓冲都直接放行。有缓冲时只需记「这些坐标的新态已下发」，
        // 用 short[] 逐格解包即可——不必为记账把整包解析成 WrappedBlockData[]（每坐标一个反射包装 + 一条 Update）。
        if (pending.get(player.getUniqueId()) == null) {
          stats.blockChangesPassed.increment();
          return;
        }
        if (section != null) {
          rememberDeliveredCoords(player.getUniqueId(), section, coordShorts);
          stats.blockChangesPassed.increment();
          return;
        }
        // 有缓冲却读不到段原点：无法记覆盖标记，退回下面的完整解析路径（那里会记标记或自行 fail-open）
      }
    }

    List<Update<WrappedBlockData>> updates = readUpdates(event.getPacketType(), event.getPacket());
    if (updates == null || updates.isEmpty()) {
      stats.blockChangesPassed.increment();
      return;
    }

    // 近身变更立即放行：不登记延迟、不入缓冲，原包按原样流出。
    // 这样玩家自己挖/放方块时的方块更新不会被合并窗口拖延，客户端预测得以立即确认（消除顿感）。
    if (shouldPassThroughImmediately(player.getUniqueId(), updates)) {
      // 记录本窗口内已有更新下发的坐标：合并窗口内可能有同一方块的旧状态仍在缓冲，
      // 若不标记会在窗口到期后晚到并把新态覆盖回去（方块短暂回退）。
      rememberDelivered(player.getUniqueId(), updates);
      stats.blockChangesPassed.increment();
      return;
    }

    // 兜底分支：短路未命中（例如读不到段原点）时才走到这里。语义与上面的短路完全一致——
    // 已聚合的批量包不进 Pending、不登记延迟、不重编码，原包按原样流出。
    //
    // 这类包同样要登记「这些坐标已有更新下发」：否则若缓冲里还留着同坐标的<b>旧态</b>条目，
    // 窗口到期会把旧态写回客户端，覆盖刚由本包下发的<b>新态</b>（方块回退）。
    // 不必担心误删新态：同坐标若之后又被重新缓冲，缓冲循环会撤销该标记（见上面的 remove）。
    if (bypassesMerge(event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE, updates.size())) {
      rememberDelivered(player.getUniqueId(), updates);
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
        boolean overflow = false;
        // 「已直通下发的坐标」集合在绝大多数窗口里是空的（本窗口没有近身立即放行、也没有远距离直通批量包）。
        // isEmpty 与下面的 remove 同处 synchronized(target) 临界区（passed 的写入方 rememberDelivered 也持它），
        // 因此循环内不可能有并发写入，据此把它提出循环即可省掉「每 update 一个 Coord 分配 + 一次哈希查找」的纯开销。
        boolean hasPassed = !target.passed.isEmpty();
        for (Update<WrappedBlockData> update : updates) {
          // 直接复用解析阶段已构造的 Update 实例（避免同一坐标再造一个等值 record）
          overflow |= target.batch.add(update);
          // 本坐标刚被重新缓冲：此刻缓冲条目才是最新的，必须撤销「已被更新下发覆盖」的标记。
          // 否则上一轮「立即放行 / 直通批量包」留下的标记会在 flush 时把这条更新的变更当旧态误删（丢更新）。
          if (hasPassed) {
            target.passed.remove(new Coord(update.x(), update.y(), update.z()));
          }
        }
        // 先入缓冲、再登记延迟：held.add 若在此之后抛异常，登记过的延迟就再也没人放行（永久卡包）。
        // flush() 的兜底只遍历 target.held，所以条目必须先在里面，登记才能生效。
        Held entry = new Held(event, updates);
        target.held.add(entry);
        // delayRegistered 记录「延迟是否真的登记成功」：未登记则从未延迟，不能 release（那会多减一次）。
        boolean delayRegistered = false;
        try {
          marker.incrementProcessingDelay();
          delayRegistered = true;
          if (target.timer == null) {
            long window = Math.max(1L, config.mergeWindowMillis());
            final Pending scheduled = target;
            target.timer = flusher.schedule(() -> flush(scheduled), window, TimeUnit.MILLISECONDS);
          }
        } catch (Throwable throwable) {
          // 登记或调度失败（含冲刷线程已失效的 RejectedExecutionException）：撤销缓冲条目，
          // 已登记过延迟则立即原样放行，绝不卡包；未登记则原包本就照常流出，无需 release。
          target.held.remove(entry);
          if (delayRegistered) {
            release(entry);
          }
          logThrottled(throwable);
        }
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
  private void rememberDelivered(UUID playerId, List<Update<WrappedBlockData>> updates) {
    Pending target = pending.get(playerId);
    if (target == null) {
      return;
    }
    synchronized (target) {
      if (target.flushed.get()) {
        // 窗口已冲刷：缓冲内容已定型（本次记录再无处生效），跳过
        return;
      }
      // 上限闸：超限即整体清空（只可能让窗口到期时把某个坐标的旧态再下发一次，不影响正确性）。
      // 放在循环前判断，避免「先涨到上限再多加一批」这种瞬时超额。
      if (target.passed.size() + updates.size() > target.passedLimit) {
        target.passed.clear();
      }
      for (Update<WrappedBlockData> update : updates) {
        target.passed.add(new Coord(update.x(), update.y(), update.z()));
      }
    }
  }

  /**
   * 同 {@link #rememberDelivered}，但入参是「段原点 + 每格一个 packed short」而不是已解析的更新列表。
   *
   * <p><b>为什么需要它</b>：批量直通包（{@code MULTI_BLOCK_CHANGE} 且坐标数达阈值）必须记「这些坐标的新态
   * 已下发」，否则冲刷时会把缓冲里的旧态写回去（方块回退）。而记账只需要坐标——{@code positions} 本身就是
   * 每坐标一个 short（高 4 位 x、次 4 位 z、低 4 位 y，与 {@link #readUpdates} 的解包完全一致），
   * 于是不必为了三个 int 去解析 {@code WrappedBlockData[]}。
   *
   * <p>上限闸与 {@link #rememberDelivered} 同口径：超限即整体清空（最多让窗口到期时把某个坐标的旧态再下发
   * 一次，不影响正确性）。与 {@code flush} 共用 {@code synchronized(target)}。
   */
  private void rememberDeliveredCoords(UUID playerId, BlockPosition section, short[] positions) {
    Pending target = pending.get(playerId);
    if (target == null) {
      return;
    }
    synchronized (target) {
      if (target.flushed.get()) {
        return;
      }
      if (target.passed.size() + positions.length > target.passedLimit) {
        target.passed.clear();
      }
      int baseX = section.getX() << 4;
      int baseY = section.getY() << 4;
      int baseZ = section.getZ() << 4;
      for (short packed : positions) {
        target.passed.add(new Coord(baseX + (packed >> 8 & 15),
            baseY + (packed & 15), baseZ + (packed >> 4 & 15)));
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
   * <p><b>为什么整个主体都在 try/finally 内</b>：CAS 一旦成功（写入权归本线程）且已从 pending 摘除，
   * 后续任何步骤（拷贝 held、{@code drainClusters}、取定时器、构包发送）抛异常都会让这些原包
   * <b>永久无人放行</b>——因为后续所有 {@code flush}（含 {@code stop()} 的 flushAll）都会因 CAS 失败
   * 变成 no-op，而异常还会被调度执行器静默吞掉（无日志）。把 held 先置为空列表、让 finally 无条件
   * 遍历放行，即可保证「取得写入权 ⇒ 恰好放行一次」这一不变量在任何路径（含 Error）下都成立。
   */
  private boolean flushInternal(Pending target) {
    List<Held> held = List.of();
    List<List<Update<WrappedBlockData>>> clusters = List.of();
    Set<Coord> passed = Set.of();
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
        held = new ArrayList<>(target.held);
        target.held.clear();
        clusters = target.batch.drainClusters(config.mergeRadius());
        // 本窗口内「已有更新下发覆盖过」的坐标：缓冲里的同一坐标属旧状态，必须剔除
        passed = target.passed.isEmpty() ? Set.of() : new HashSet<>(target.passed);
      }
      if (timer != null) {
        timer.cancel(false);
      }
      if (held.isEmpty()) {
        return false; // 只有清理、没有实包：不计入「冲刷次数/耗时」
      }
      sendOrPass(held, clusters, passed);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    } finally {
      // 兜底：任何未被取消的原包都必须放行（release 为一次性，重复调用无副作用）
      for (Held entry : held) {
        release(entry);
      }
    }
    return true;
  }

  /**
   * 冲刷的投递主体：按 last-write-wins 把缓冲结果构造成合并包发出，并决定各原包取消还是放行。
   *
   * <p><b>不自行放行</b>：所有出口的兜底放行由 {@link #flushInternal} 的 finally 统一完成。
   */
  private void sendOrPass(List<Held> held, List<List<Update<WrappedBlockData>>> clusters,
      Set<Coord> passed) {
    if (!passed.isEmpty()) {
      // 本窗口内有坐标经「更新于缓冲条目」的路径先行下发（近身立即放行 / 远距离直通批量包）：
      // 做 last-write-wins——合并包只保留未被更新下发覆盖的坐标，其余原包一律取消（其数据要么已由
      // 更新的包交付、要么由下方的合并结果交付），绝不把旧状态晚发回去覆盖新态。
      // 注意：此处不套用「首个成功发送前保留原包」的宽松策略——对新态与旧态同坐标的情形，
      // 重复下发旧态即等于回退；改为完全信任 {@link #trySendMerged} 的回读自检（失败即 fail-open 放行）。
      List<List<Update<WrappedBlockData>>> survivors = dropPassed(clusters, passed);
      boolean anySurvivor = false;
      for (List<Update<WrappedBlockData>> cluster : survivors) {
        if (!cluster.isEmpty()) {
          anySurvivor = true;
          break;
        }
      }
      if (!anySurvivor) {
        // 所有坐标都已有更新的下发：无需发包，取消原包即可（更新已交付）。
        // 不计 blockMergeBatches——本次没有发出任何合并包，计入会让「合并批次」虚高。
        stats.blockChangesMerged.add(held.size());
        for (Held entry : held) {
          entry.event.setCancelled(true);
        }
        return;
      }
      if (trySendMerged(held.get(0).event.getPlayer(), survivors)) {
        stats.blockMergeBatches.increment();
        stats.blockChangesMerged.add(held.size());
        for (Held entry : held) {
          entry.event.setCancelled(true);
        }
        return;
      }
      // 构造/发送失败：退回既有 fail-open（原包照常放行，绝不丢更新）——但本窗口内已有更新下发的
      // 坐标，其<b>旧态原包必须一并取消</b>：否则旧态晚到会把先到的新态覆盖回去（方块回退）。
      // 注意：passed 只记坐标不记值，无法重发新态，所以只能「整包取消」——仅当该原包携带的坐标
      // 全部已有更新下发时才取消，避免误丢未下发坐标的更新（跨坐标混合包只能照常放行）。
      int cancelled = 0;
      for (Held entry : held) {
        if (allCoordsPassed(coordsOf(entry.updates()), passed)) {
          entry.event.setCancelled(true);
          cancelled++;
        }
      }
      // 只把真正放行的计入「放行」：被取消的原包并未下发，计入会让「放行数」虚高
      stats.blockChangesPassed.add(held.size() - cancelled);
      return;
    }

    boolean mergeable = false;
    if (held.size() >= 2) {
      for (List<Update<WrappedBlockData>> cluster : clusters) {
        if (cluster.size() >= 2) {
          mergeable = true;
          break;
        }
      }
    }
    if (mergeable) {
      boolean wasVerified = verified;
      if (trySendMerged(held.get(0).event.getPlayer(), clusters) && wasVerified) {
        // 已通过结构自检：取消原包（更新已由合并包交付）
        stats.blockMergeBatches.increment();
        stats.blockChangesMerged.add(held.size());
        for (Held entry : held) {
          entry.event.setCancelled(true);
        }
        return;
      }
    }
    stats.blockChangesPassed.add(held.size());
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

  /** 把一批更新折算为坐标清单（供 {@link #allCoordsPassed} 判定原包携带的坐标是否已全部先行下发）。 */
  private static List<Coord> coordsOf(List<Update<WrappedBlockData>> updates) {
    List<Coord> coords = new ArrayList<>(updates.size());
    for (Update<WrappedBlockData> update : updates) {
      coords.add(new Coord(update.x(), update.y(), update.z()));
    }
    return coords;
  }

  /**
   * fail-open 放行时：一个原包携带的坐标是否<b>全部</b>已经由「立即放行」路径先行下发。
   *
   * <p>是则应取消该原包——其旧状态晚到会把先到的新状态覆盖回去（方块回退）；不是则必须照常放行，
   * 否则会丢掉未放行坐标的更新。空清单按「不可取消」处理（保守放行）。
   *
   * <p>包可见：供离线单测直接驱动该纯逻辑（真实链路依赖 ProtocolLib 封包，离线不可用）。
   */
  static boolean allCoordsPassed(List<Coord> coords, Set<Coord> passed) {
    if (coords.isEmpty()) {
      return false;
    }
    for (Coord coord : coords) {
      if (!passed.contains(coord)) {
        return false;
      }
    }
    return true;
  }

  /** 放行一个被延迟的原包，恰好一次。 */
  private void release(Held entry) {
    if (!entry.signalled.compareAndSet(false, true)) {
      return;
    }
    try {
      asynchronousManager.signalPacketTransmission(entry.event);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
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
      if (player == null || !player.isOnline()) {
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
      if (sentAny) {
        verified = true;
      }
      return sentAny;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
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
        // 与协议一致：section 内相对坐标打包为 (x & 15) << 8 | (z & 15) << 4 | (y & 15)
        positions[i] = (short) (((update.x() & 15) << 8) | ((update.z() & 15) << 4) | (update.y() & 15));
      }
      packet = new PacketContainer(PacketType.Play.Server.MULTI_BLOCK_CHANGE);
      packet.getSectionPositions().writeSafely(0, new BlockPosition(section.x(), section.y(), section.z()));
      packet.getBlockDataArrays().writeSafely(0, data);
      packet.getShortArrays().writeSafely(0, positions);
      // trustEdges 不写：编译期 ProtocolLib（5.3.0）未为本包提供该字段的类型化访问器
      // （MultiBlockChangeInfo 只含 location/data/chunk，jar 内也搜不到 trustEdges），其结构由运行时
      // 服务端包类反射得出，无法在编译期确认存在；此处不发明 API（不盲写 getBooleans 的某个下标，
      // 以免在字段布局不同的服务端上写错字段）。合并包由本模块自行构造，未设置该布尔时沿用默认值，
      // 语义为「不信任边缘」，安全侧成立。

      // 回读自检：字段必须真实存在且条目数与写入一致，否则视为结构不可用（fail-open）
      short[] readPositions = packet.getShortArrays().readSafely(0);
      WrappedBlockData[] readData = packet.getBlockDataArrays().readSafely(0);
      if (readPositions == null || readData == null
          || readPositions.length != positions.length || readData.length != data.length) {
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
        if (position == null || data == null) {
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
        short packed = positions[i];
        int x = baseX + (packed >> 8 & 15);
        int z = baseZ + (packed >> 4 & 15);
        int y = baseY + (packed & 15);
        updates.add(new Update<>(x, y, z, data[i]));
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