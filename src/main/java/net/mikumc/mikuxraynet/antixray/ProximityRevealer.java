package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.ProtocolManager;
import io.papermc.paper.math.Position;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bandwidth.Schedulers;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 邻近显形：周期巡检每个在线玩家，把其附近「曾被反矿透伪装过」的坐标的真实方块状态发回客户端。
 *
 * <p><b>为什么需要</b>：被完全掩埋的方块在区块封包里已被替换为伪装方块，客户端会一直看到伪装结果；
 * 只有服务端下发方块变更时才会纠正。主动把玩家附近的真实方块发回去，体验才与原版一致。
 *
 * <p><b>局部扫描</b>：候选来自「玩家身边 {@code ceil(distance/16)} 个区块半径」内的
 * {@link ObfuscatedChunkIndex}（按区块共享、只存一份），再减去该玩家 {@link RevealedSet} 里已显形的
 * 坐标。整块已全部显形的区块直接跳过，因此单周期候选评估量与「该玩家的探索历史」脱钩，
 * 只与「身边区块的伪装坐标数」相关（旧实现遍历全部历史坐标，是真机上评估量暴涨的根因）。
 * 附近区块还会按稳定哈希分成 {@value #SCAN_SHARDS} 片轮转评估（见 {@link #SCAN_SHARDS}）：
 * 被候选窗口截断在外的坐标不再被「永远看不见的坐标」长期挤掉，窗口覆盖范围随之扩大约
 * {@value #SCAN_SHARDS} 倍。
 *
 * <p><b>筛选（可选、可配）</b>：
 * <ul>
 *   <li><b>视锥剔除</b>（默认开启，110° 竖直全角，16 格内豁免）：只显形玩家视野锥内的候选坐标；
 *       默认值取「客户端 FOV 上限 + 16:9」的可视范围，因此<b>玩家看得见的方向不会被剔除</b>；
 *       两项配置都有安全下限（配得更窄会被抬升并 WARN，见 {@code AntiXrayConfig}），
 *       因为「看得见却是假的方块」是本功能最严重的失效模式（真机反馈：点击/挖掘才变回来）。
 *       水平方向按 16:9 宽高比换算（110° → 水平半角约 68°），与客户端观感一致；</li>
 *   <li><b>可见性判定</b>（默认开启，多候选点 + Paper 原生射线）：先识别方块的暴露面，只在暴露面上取
   *       候选点（正对玩家的面中心 → 包围盒最近点 → 面四角），每个候选点用
   *       {@code World#rayTraceBlocks} 做原生射线检测，任一条通畅即显形；候选点数由
   *       {@code proximity.raycast.samples} 限制（<b>有效上限 5</b>，即
   *       {@code ProximitySelector.MAX_SAMPLE_POINTS}，配置给得再大也会被截断）；六面全被遮挡（完全掩埋）时一个点都不采
   *       → 直接判不可见，绝不隔着墙还原。</li>
 *   <li><b>流体覆盖</b>（默认开启，{@code occlusion.fluid-cover}）：目标方块上方紧邻方块是流体
 *       （水/岩浆）时不显形——刷在岩浆里的下界残骸本就被伪装，显形侧若还原就等于把它亮给玩家。</li>
 * </ul>
 *
 * <p><b>线程纪律</b>：位置读取、方块读取、原生射线与发包都在主线程 / Folia 区域线程（非 Folia 为统一主线程任务，
 * Folia 为各玩家的区域任务）；纯计算（视锥角度）交给 {@link MikuWorkPool} 的工作线程，
 * 算完再回到玩家所属线程。可见性判定（含 {@code rayTraceBlocks}）必须在主线程 / 区域线程做——
 * 它是 Bukkit API，且要先读 6 个邻块判暴露面。工作队列已满时退化为「主线程直接做纯计算」，
 * 功能不降级、绝不阻塞。
 *
 * <p><b>发包纪律</b>：显形包改用 <b>Paper 原生 API</b>（{@code Player#sendMultiBlockChange} /
 * {@code Player#sendBlockChange}）直接发出，不再自拼包结构——因此不触碰 ProtocolLib 的
 * {@code WrappedBlockData}（它内部硬查 {@code CraftMagicNumbers} 的方法契约，属「未来某版 MC
 * 一改就整体失效」的脆弱面）。
 * <b>注意</b>：原生通道同样会经过本插件的出站观察链路（{@link BlockChangeRevealListener}），
 * 它会把这个坐标从显形索引里摘掉（与真实方块变更的处理相同，都是「该坐标已发回真实方块」）。
 * 磁盘缓存不受影响：条目是否可用只由负载里的原始区块字节指纹判定，与这里无关。
 * {@code proximity.batch-reveal-sends}（默认开启）时把一个周期内通过筛选的坐标累积起来、
 * 周期末一次 {@code sendMultiBlockChange} 发出（Paper 按 16³ 区块段自动合并，每个涉及的段一个包），
 * 失败时退化为逐坐标 {@code sendBlockChange}；关闭时逐坐标发单方块变更包（旧行为，供 A/B 与回退）。
 * 同一坐标只显形一次（<b>先写入该玩家的 {@link RevealedSet} 标记、再发包，发送失败则回滚标记</b>，
 * 理由见 {@link #markThenSend}）；区块被重新下发或被卸载时
 * 该区块的已显形标记一并失效（客户端又会看到伪装结果，必须重新显形）。单个坐标失败只记日志并跳过，
 * 绝不中断整体。
 */
public final class ProximityRevealer implements Listener {

  /** 每多少次巡检清理一次过期条目（默认周期 4 tick 时约 4 秒一次）。 */
  private static final int EXPIRE_EVERY_PASSES = 20;
  /**
   * 候选坐标的超额倍数：视锥/射线会剔除部分候选，因此按额度的该倍数取候选，
   * 避免「远处最多、近处还没轮到」的候选把名额占满。
   */
  private static final int CANDIDATE_OVERSAMPLE = 4;
  private static final int MAX_CANDIDATES = 512;
  /**
   * 单玩家单周期「候选评估」硬上限（与发包额度解耦）。
   *
   * <p><b>为什么需要它</b>：旧的循环只在发包额度 {@code budget.remaining() <= 0} 时断环，而可见性判定
   * 失败、区块未加载都是直接 {@code return}、<b>不扣发包额度</b>——于是发包额度只限「发了多少包」，
   * 不限「评估了多少候选」。每评估一个候选要在主线程 / 区域线程上读 6 个邻块并行 ≤4 条原生射线，是
   * 本功能最重的部分；一批「永远看不见的坐标」（整块埋住、上方流体等）会把评估量顶到候选上限
   * {@link #MAX_CANDIDATES} 而一个包都不发。这里单独设一个评估额度：无论是否发得出包，评估次数触顶即
   * 停。候选本就按距离由近到远取（见 ProximityScanner），因此超出的候选只是延后到后续周期（分片轮转）
   * 评估，<b>可见的矿仍会显形，最坏多等一个周期</b>——绝不丢显形。
   */
  private static final int MAX_EVALUATIONS_PER_PASS = 256;

  /**
   * 单个 section 的邻域初筛（封包线程）允许的最大「探测」次数：每个变更坐标 × 每个曼哈顿偏移算一次。
   *
   * <p><b>为什么需要</b>：{@link #hasDisguisedNearSection} 对每个变更坐标遍历全部曼哈顿偏移
   * （半径 8 时约 833 个），且无任何次数上限；当邻域内没有伪装坐标（短路失效）时，满载的
   * {@code MULTI_BLOCK_CHANGE}（最多 4096 个坐标）会在 netty 线程上造成约 340 万次索引查找。
   * 实体侧同类循环有 {@link #MAX_EVALUATIONS_PER_PASS} 兜底，这里同样加一个硬上限。
   *
   * <p>取 {@code 64K}：足以完整扫描约 {@code 78} 个变更坐标的邻域——现实中的变更包坐标数远小于此
   * （方块交互个位数、爆炸几十个），因此正常路径不会被截断；同时把最坏工作量压到原值的约五十分之一。
   */
  private static final int MAX_NEARBY_PROBES_PER_PACKET = 64 * 1024;

  /**
   * 附近区块的分片数：每次巡检只看其中一片（{@link ProximityScanner#shardOf} 决定某个区块属于哪片）。
   *
   * <p><b>为什么必须分片</b>：被视锥/射线剔除的候选不发包也不记已显形，而候选窗口又有硬上限
   * （{@link #MAX_CANDIDATES}）。若每次都从「最近的 N 个」里挑，玩家身边一旦有大量永远看不见的伪装
   * 坐标（整块埋住、上方是流体等），这批坐标就会<b>每个周期</b>重复占满窗口，窗口外的坐标一直轮不到
   * 评估——真机表现为「明明在眼前的方块一直是假的，点一下才变回来」。分片后同一批坐标改为每
   * {@value #SCAN_SHARDS} 个周期才重复占用一次，窗口的有效覆盖范围因此扩大到约
   * {@value #SCAN_SHARDS} 倍，每个分片每 {@value #SCAN_SHARDS} 个周期至少被访问一次。
   *
   * <p>取 4 是「覆盖速度」与「及时性」的折中：分片越多，单周期覆盖的范围越窄（脚边的坐标最多晚到
   * 一个分片周期 = 默认 4 tick × 4 = 0.8 秒），越少则窗口外坐标轮到的越慢。真正被玩家触碰到的方块由
   * 事件显形当 tick 还原，因此这里宁可偏向覆盖速度。
   */
  private static final int SCAN_SHARDS = 4;

  /**
   * 显形回显的判定窗口（纳秒，2 秒）。
   *
   * <p>只需覆盖「显形包发出 → 本插件出站监听器看到该包」的极短延迟；取得过长会把「玩家紧接着挖掉
   * 刚显形的那块」误判成回显，从而留下一枚陈旧索引条目（可自愈但不必要）。
   */
  private static final long REVEAL_ECHO_WINDOW_NANOS = 2_000_000_000L;

  /** 每玩家回显标记条数上界：正常只需容纳「一个周期内发出的显形数」，超限整体清空（只少判一次回显）。 */
  private static final int REVEAL_ECHO_MAX_PER_PLAYER = 8192;

  /**
   * 单次巡检的发包额度（非 Folia 为全服合计，Folia 为每个玩家各自的巡检）。
   *
   * <p><b>为什么必须用 {@link AtomicInteger}</b>：非 Folia 的一次巡检里，同一份 {@code Budget} 会被
   * 多个玩家的异步视锥任务并发持有（{@code workPool.execute} 把后续发包推迟到工作线程 / 玩家线程），
   * 普通 int 字段的读写无同步、会丢失更新，导致 {@code max-reveals-per-tick} 的「全服合计」语义被突破
   * （超发若干显形包）。改为原子计数后，扣减不再丢失，「近的先发」的排序与额度上限保持一致。
   */
  static final class Budget {

    private final AtomicInteger remaining;

    Budget(int remaining) {
      this.remaining = new AtomicInteger(remaining);
    }

    /** 剩余额度（跨线程读取）。 */
    int remaining() {
      return remaining.get();
    }

    /** 扣减一个额度（跨线程安全的原子扣减，不会丢失更新）。 */
    void decrement() {
      remaining.decrementAndGet();
    }

    /** 归还一个额度（批量显形里「预扣但最终未发出」的坐标；跨线程安全，与 {@link #decrement()} 对称）。 */
    void refund() {
      remaining.incrementAndGet();
    }
  }

  /** 工作线程算出的显形计划：仅含通过视锥剔除的候选三元组（可见性判定在主线程做，见类注释）。 */
  private static final class Plan {

    private final int[] coordinates;
    /** 本次取到的候选总数与其中被视锥剔除的数量（仅供一次性诊断日志使用）。 */
    private final int candidateCount;
    private final int frustumCulled;

    private Plan(int[] coordinates, int candidateCount, int frustumCulled) {
      this.coordinates = coordinates;
      this.candidateCount = candidateCount;
      this.frustumCulled = frustumCulled;
    }

    private int count() {
      return coordinates.length / 3;
    }
  }

  /** 单次巡检的计数（局部扫描 / 射线剔除 / 实际发送），仅供一次性诊断日志使用。 */
  private static final class PassTally {

    private int chunksScanned;
    private int chunksSkipped;
    private int chunksDeferred;
    private int positionsEvaluated;
    private int rayCulled;
    private int sent;
  }

  /**
   * 一个周期（或一次事件触发）内待发的显形批次：只累积「已通过全部筛选、待发包」的坐标与真实方块状态，
   * 周期末由 {@link #flushBatch} 一次发出并逐个写回「已显形」标记。
   *
   * <p><b>为什么先累积再发</b>：批量合并的前提是「先知道本周期有哪些坐标要发」。发包时按
   * 「<b>先写标记、再发包、失败回滚</b>」的顺序处理（见 {@link #markThenSend}）：标记先行是为了与
   * 出站监听器的摘除路径对齐，杜绝孤儿标记；发送失败回滚则保证未发出的坐标仍会被后续周期重试。
   * 坐标为绝对方块坐标；方块状态在玩家所属线程读取（见 {@link #sendOne}）。
   */
  private static final class RevealBatch {

    private final World world;
    private final List<int[]> positions = new ArrayList<>();
    private final List<BlockData> states = new ArrayList<>();

    private RevealBatch(World world) {
      this.world = world;
    }

    private void add(int x, int y, int z, BlockData data) {
      positions.add(new int[] {x, y, z});
      states.add(data);
    }

    private int size() {
      return positions.size();
    }
  }

  private final Plugin plugin;
  private final AntiXrayConfig config;
  private final AntiXrayConfig.Proximity proximity;
  private final AntiXrayConfig.InstantReveal instant;
  /** 流体覆盖开关（{@code occlusion.fluid-cover}）：上方是流体时不显形（保持伪装）。 */
  private final boolean fluidCover;
  /** 是否把同一周期的显形合并成一个 Paper 原生多方块变更包发出（{@code proximity.batch-reveal-sends}）。 */
  private final boolean batchRevealSends;
  private final ObfuscatedChunkIndex chunkIndex;
  private final RevealedSet revealedSet;
  private final ProximityStats stats;
  private final BypassRegistry bypassRegistry;
  private final MikuWorkPool workPool;
  private final AtomicInteger errorCounter = new AtomicInteger();
  /**
   * Paper 侧的巡检轮次计数（用于扫描分片轮转）。
   *
   * <p>Paper 只有一条全局任务（{@code passAll}），因此一个全局轮次计数即可；Folia 各玩家任务独立，
   * 分片计数必须<b>按玩家</b>保存在 {@link #entityScans} 里（原因见 {@link #pass}）。
   */
  private final AtomicLong globalPasses = new AtomicLong();
  /**
   * 上一次过期清理的纳秒时刻（过期清理的时间门控；见 {@link #maybeExpire}）。
   *
   * <p>过期清理必须与在线人数无关，因此用独立的时刻计数 + CAS，而不是「每 N 次巡检」。
   */
  private final AtomicLong lastExpireNanos = new AtomicLong();
  /**
   * 过期清理的周期窗口（纳秒）= 巡检周期 × {@value #EXPIRE_EVERY_PASSES}。
   *
   * <p>按「巡检周期」而非固定墙钟常数计算：巡检周期配得长（省 CPU）时，过期清理也相应变稀疏，
   * 二者保持同一节奏，不会出现「几天才巡检一次却每秒全量扫描」的反常组合。
   */
  private final long expirePeriodNanos;
  private final AtomicBoolean firstRevealDiagnosed = new AtomicBoolean();
  /** 「显形索引安全阀触发」只提示一次（CAS 抢占），避免每轮巡检刷屏。 */
  private final AtomicBoolean capacityWarned = new AtomicBoolean();
  /** 「可见性判定失败」只提示一次（CAS 抢占）：原生射线改造后新增的失败模式必须可见但不刷屏。 */
  private final AtomicBoolean visibilityWarned = new AtomicBoolean();
  /** 事件显形的每玩家每 tick 限额（默认 16，防爆刷；见 {@link TickQuota}）。 */
  private final TickQuota instantQuota = new TickQuota();
  /** 过度显形抽样器（1/N，只计数不改行为；N=0 关闭）。 */
  private final OverRevealSampler overRevealSampler;

  private ScheduledTask globalTask;

  /**
   * Folia 每玩家的区域巡检任务句柄 + 该玩家的分片轮转计数。
   *
   * <p><b>为什么把分片计数放在这里（与任务句柄同一个条目）</b>：它与任务严格同生命周期——任务被取消
   * 就该一并丢弃。合成一条既省一次 map 查找，也保证「取消任务」与「清理计数」永远不会漏掉一半
   * （若单独放一张表，就必须在 {@code stop()} 与 {@code onQuit()} 两处同步清理，漏一处即无界增长）。
   *
   * <p><b>为什么必须保存句柄</b>：{@code repeatOnEntity} 返回的句柄若被丢弃，{@link #stop()} 就只取消了
   * 全局任务，Folia 上各玩家的区域任务会在插件停用后继续跑到实体退役为止——句柄泄漏 + 停用后
   * 仍在发包。这里逐一保存，停用与玩家退出时全部取消。
   */
  private record EntityScan(ScheduledTask task, AtomicLong passes) {
  }

  private final ConcurrentHashMap<UUID, EntityScan> entityScans = new ConcurrentHashMap<>();

  /**
   * 显形回显窗口：玩家 → (世界 + 坐标) → 过期时刻（纳秒）。
   *
   * <p><b>为什么需要它</b>：我们发出的显形包会经 {@link BlockChangeRevealListener} 回显。若把回显当成
   * 「真实方块变更」，监听器会把该坐标从<b>按区块全服共享</b>的伪装索引里摘掉——于是甲玩家被显形后，
   * 乙玩家对同一坐标再也无法被主动显形（乙客户端仍持伪装区块，只能等区块重载才恢复）。
   * 有了这个短期标记，监听器就能区分「我们自己刚发的回显」与「真实变更」：前者只保留、不摘共享索引。
   *
   * <p><b>有界</b>：条目在窗口到期后由「消费即删」或整体清空回收，每玩家另设条数上界；
   * 玩家退出与插件停用时整体释放。
   */
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<EchoKey, Long>> revealEcho =
      new ConcurrentHashMap<>();

  /** 回显标记的键：世界名 + 方块坐标（record 自带 equals/hashCode，避免手工位打包的碰撞风险）。 */
  private record EchoKey(String world, int x, int y, int z) {
  }

  /**
   * @param protocolManager ProtocolLib 协议管理器。<b>保留形参以维持既有装配签名</b>——显形发包已改用
   *                        Paper 原生 API（见类注释「发包纪律」），封包通道由本插件其它模块继续使用；
   *                        本类不再读取它。
   */
  public ProximityRevealer(Plugin plugin, ProtocolManager protocolManager, AntiXrayConfig config,
      ObfuscatedChunkIndex chunkIndex, RevealedSet revealedSet, ProximityStats stats,
      BypassRegistry bypassRegistry, MikuWorkPool workPool) {
    this.plugin = plugin;
    this.config = config;
    this.proximity = config.proximity();
    this.instant = proximity.instantReveal();
    this.fluidCover = config.occlusion().fluidCover();
    this.batchRevealSends = proximity.batchRevealSends();
    this.overRevealSampler = new OverRevealSampler(Math.max(0, proximity.overRevealSampling()));
    this.chunkIndex = chunkIndex;
    this.revealedSet = revealedSet;
    this.stats = stats;
    this.bypassRegistry = bypassRegistry;
    this.workPool = workPool;
    // 过期清理窗口 = 巡检周期 × EXPIRE_EVERY_PASSES（tick→纳秒：1 tick = 50ms）。
    // 对巡检周期做上限钳制（≤1 小时）再相乘，避免极端配置把乘法推到溢出。
    long ticks = Math.max(1L, Math.min(72_000L, proximity.intervalTicks()));
    this.expirePeriodNanos = ticks * 50_000_000L * EXPIRE_EVERY_PASSES;
  }

  /** 启动巡检：非 Folia 为统一主线程任务；Folia 为各玩家的区域任务（句柄与分片计数按 UUID 保存，见 entityScans）。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    long interval = Math.max(1, proximity.intervalTicks());
    if (PlatformSupport.isFolia()) {
      // 保留平台差异（策略分支，非 API 分支）：Folia 无法从全局线程安全读取玩家世界/位置，
      // 因此每个玩家一条区域任务（实体调度器，延迟与周期都以 tick 计）。
      for (Player player : Bukkit.getOnlinePlayers()) {
        scheduleEntityTask(player, interval);
      }
    } else {
      // Paper：单条全局任务，保留「全服共享发包额度」的既有语义（延迟与周期都以 tick 计）。
      // 合并为每玩家任务会把 max-reveals-per-tick 从全服合计改成每玩家额度，属可观测的封包行为变化，故不合并。
      globalTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> passAll(),
          interval, interval);
    }
    plugin.getLogger().info("邻近显形已启用：距离 " + proximity.distance() + " 格，周期 " + interval
        + " tick，单次上限 " + proximity.maxRevealsPerTick() + " 个；视锥 "
        + (proximity.frustumEnabled() ? proximity.frustumFov() + "°（最小距离 "
            + proximity.frustumMinDistance() + " 格内豁免）" : "已关闭")
        + "，可见性判定 " + (proximity.raycastEnabled()
            ? "已开启（Paper 原生射线 rayTraceBlocks，暴露面上至多 " + proximity.raycastSamples()
                + " 个候选点）" : "已关闭")
        + "，显形发包 " + (batchRevealSends ? "已合并（Paper 原生多方块变更包）" : "逐坐标单包"));
  }

  /** 停用：注销任务与事件监听，并清空两个显形索引（不留副作用）。 */
  public void stop() {
    if (globalTask != null) {
      globalTask.cancel();
      globalTask = null;
    }
    // Folia 每玩家任务句柄全部取消：句柄若被丢弃，区域任务会在停用后继续跑到实体退役（泄漏）
    for (EntityScan scan : entityScans.values()) {
      try {
        scan.task().cancel();
      } catch (Throwable ignored) {
        // 任务可能已随实体退役结束，取消失败可忽略
      }
    }
    entityScans.clear();
    HandlerList.unregisterAll(this);
    chunkIndex.clear();
    revealedSet.clear();
    revealEcho.clear();
  }

  /** 在玩家所属线程上启动周期巡检并保存句柄与分片计数（调度失败则无句柄可存）。 */
  private void scheduleEntityTask(Player player, long interval) {
    ScheduledTask task = Schedulers.repeatOnEntity(plugin, player, interval, interval, () -> pass(player));
    if (task != null) {
      // 计数随句柄一起建：任务首次执行至少在一个周期之后，故 pass() 读到的一定是本条目
      entityScans.put(player.getUniqueId(), new EntityScan(task, new AtomicLong()));
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    if (!PlatformSupport.isFolia()) {
      return;
    }
    scheduleEntityTask(event.getPlayer(), Math.max(1, proximity.intervalTicks()));
  }

  /** 玩家登出：取消其巡检任务句柄，并清理其已显形标记，避免为离线玩家保留坐标。 */
  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    EntityScan scan = entityScans.remove(event.getPlayer().getUniqueId());
    if (scan != null) {
      try {
        scan.task().cancel();
      } catch (Throwable ignored) {
        // 任务可能已随实体退役结束，取消失败可忽略
      }
    }
    revealedSet.clearPlayer(event.getPlayer().getUniqueId());
    instantQuota.clear(event.getPlayer().getUniqueId());
    // 离线玩家的回显记号一并释放（否则会滞留到窗口过期，白白占内存）
    revealEcho.remove(event.getPlayer().getUniqueId());
  }

  /**
   * 区块卸载：该区块的伪装清单与各玩家的已显形标记一并失效。
   *
   * <p>区块重新加载时会被重新编译（并重新进入伪装清单），此时旧清单里的坐标已不可信；
   * 已显形标记同理必须清掉，否则玩家会在「区块重载后早已显示为伪装」的位置漏显形。
   * 本回调在主线程 / Folia 区域线程执行，只做两个索引的失效，不读世界内容、不做任何计算。
   */
  @EventHandler(ignoreCancelled = true)
  public void onChunkUnload(ChunkUnloadEvent event) {
    try {
      Chunk chunk = event.getChunk();
      String worldName = chunk.getWorld().getName();
      chunkIndex.invalidateChunk(worldName, chunk.getX(), chunk.getZ());
      revealedSet.clearChunk(new ChunkKey(worldName, chunk.getX(), chunk.getZ()));
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 世界卸载：整体清理该世界的已显形标记（伪装清单由改写链路一并失效）。 */
  @EventHandler(ignoreCancelled = true)
  public void onWorldUnload(WorldUnloadEvent event) {
    try {
      String worldName = event.getWorld().getName();
      chunkIndex.invalidateWorld(worldName);
      revealedSet.clearWorld(worldName);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 全服巡检（非 Folia：主线程，遍历在线玩家并共享本次发包额度）。 */
  private void passAll() {
    warnIfCapacityExceeded();
    maybeExpire();
    // 本轮看哪一片：同一轮里所有玩家看同一片（分片轮转的节奏只与巡检次数有关，见 SCAN_SHARDS）。
    // 按「巡检轮次」推进，而不是按墙钟时间取模——服务器掉 tick 时墙钟会跑在实际巡检前面，
    // 反而会让一部分分片被整轮跳过。
    int shard = shardFor(globalPasses.incrementAndGet());
    Budget budget = new Budget(limit());
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (budget.remaining() <= 0) {
        return;
      }
      try {
        reveal(player, budget, shard);
      } catch (Throwable throwable) {
        logThrottled(throwable);
      }
    }
  }

  /** 单个玩家的巡检（Folia：在玩家所属区域线程执行）。 */
  private void pass(Player player) {
    warnIfCapacityExceeded();
    maybeExpire();
    // 分片计数必须<b>按玩家</b>推进：Folia 下每个玩家各有一条独立任务，若共用一个全局计数，
    // 在线人数恰为 SCAN_SHARDS 的倍数时，单个玩家在相邻两次巡检之间会被其他玩家把计数推进正好
    // SCAN_SHARDS 次，于是它每轮都读到同一分片——其周围 (SCAN_SHARDS-1)/SCAN_SHARDS 的伪装区块
    // 再也不会被周期扫描到（洞穴里裸露的矿一直显示为伪装方块）。
    EntityScan scan = entityScans.get(player.getUniqueId());
    long round = scan == null ? 1L : scan.passes().incrementAndGet();
    try {
      reveal(player, new Budget(limit()), shardFor(round));
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /**
   * 由「本次是第几次巡检」算出本轮的分片下标（纯函数，可离线单测）。
   *
   * <p><b>为什么按巡检次数而不是墙钟时间取模</b>：掉 tick 时墙钟窗口会跑在实际巡检前面（任务间隔按
   * tick 计），按时间取模会让一部分分片整轮被跳过；按次数推进则严格每 {@value #SCAN_SHARDS} 次巡检
   * 覆盖全部区块，与类注释的保证一致。
   *
   * <p><b>为什么调用方必须提供「按玩家」的巡检次数</b>：见 {@link #pass}——Folia 下每个玩家各有独立
   * 任务，共用全局计数会让在线人数为 {@value #SCAN_SHARDS} 倍数时单个玩家的分片恒定不变。
   */
  static int shardFor(long round) {
    return (int) Math.floorMod(round, (long) SCAN_SHARDS);
  }

  /**
   * 显形索引安全阀触发时提示<b>一次</b>。
   *
   * <p>新结构下不再有「按玩家容量淘汰」这种正常调优行为：伪装坐标按区块共享、已显形集合只记
   * 实际发过包的坐标，两者天然有界（分别随「已加载的伪装区块数」与「玩家身边的显形数」增长）。
   * 因此 {@code proximity.max-positions*} 只剩<b>安全阀</b>作用——正常运营下这两个计数恒为 0，
   * 一旦非 0 就说明索引管理有 bug（而不是「需要调大上限」）。只读两个 {@code LongAdder} 与一次 CAS。
   */
  private void warnIfCapacityExceeded() {
    long evicted = chunkIndex.evictedByCapacity();
    long dropped = revealedSet.droppedByCapacity();
    if ((evicted <= 0L && dropped <= 0L) || !capacityWarned.compareAndSet(false, true)) {
      return;
    }
    plugin.getLogger().warning("显形索引安全阀触发：伪装坐标淘汰 " + evicted + " 个、已显形丢弃 "
        + dropped + " 个。正常运营下两者都应恒为 0——触发说明索引管理存在 bug，请连同 /mxnet dump "
        + "一并反馈（调大 antixray.yml 的 proximity.max-positions* 只是掩盖问题，不是解决办法）。");
  }

  /** 主线程 / 区域线程：局部扫描取候选 → （可选）工作线程筛选 → 读真实方块 → 发包 → 标记已显形。 */
  private void reveal(Player player, Budget budget, int shard) {
    if (!player.isOnline()
        || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
      return;
    }

    World world = player.getWorld();
    String worldName = world.getName();
    // 黑名单世界不做任何邻近显形（周期巡检）：antiXrayAppliesTo = 总开关 且 不在黑名单。
    if (!config.antiXrayAppliesTo(worldName)) {
      return;
    }

    Location location = player.getLocation();
    ProximityScanner.Tally scanTally = new ProximityScanner.Tally();
    List<ObfuscatedChunkIndex.Position> candidates = ProximityScanner.candidates(chunkIndex,
        revealedSet, player.getUniqueId(), worldName, location.getBlockX(), location.getBlockY(),
        location.getBlockZ(), proximity.distance(), fetchLimit(budget.remaining()), shard,
        SCAN_SHARDS, scanTally);
    if (candidates.isEmpty()) {
      return;
    }

    if (!proximity.frustumEnabled() && !proximity.raycastEnabled()) {
      sendCandidates(player, world, candidates, null, null, budget, scanTally);
      return;
    }

    // 只把纯值交给工作线程；这里的 Player 引用仅供「交回所属线程」调度使用，工作线程不对它做任何调用
    ProximitySelector.Eye eye = eyeOf(player);

    if (!proximity.frustumEnabled()) {
      // 只启用可见性判定：视锥的纯计算没有可做的，直接在当前线程逐个判定
      sendCandidates(player, world, candidates, null, eye, budget, scanTally);
      return;
    }

    int[] coordinates = flatten(candidates);

    if (workPool == null || !workPool.hasCapacity()) {
      // 队列已满：保底路径——主线程直接做纯计算（视锥剔除），可见性判定本来就在主线程做，功能不降级
      sendCandidates(player, world, null, computePlan(eye, coordinates), eye, budget, scanTally);
      return;
    }

    try {
      workPool.execute(() -> {
        Plan plan = computePlan(eye, coordinates);
        // 读方块与发包必须回到玩家所属线程。世界在候选筛选时已捕获（见 reveal 的 world 局部量），
        // 这里绝不再取 player.getWorld()：异步回调期间玩家可能已切换世界，重取会让「旧世界的候选坐标」
        // 配上「新世界的 ChunkKey」（污染已显形集合）并发出冗余包。改用捕获的世界，且校验玩家仍在同一
        // 世界——跨世界则放弃本轮（fail-open，绝不误发/误标）。
        Schedulers.onEntity(plugin, player, () -> {
          try {
            if (player.isOnline() && player.getWorld() == world) {
              sendCandidates(player, world, null, plan, eye, budget, scanTally);
            }
          } catch (Throwable throwable) {
            logThrottled(throwable);
          }
        });
      });
    } catch (Throwable throwable) {
      // 入队失败（线程池关闭等）：退化为主线程纯计算，绝不丢显形
      logThrottled(throwable);
      sendCandidates(player, world, null, computePlan(eye, coordinates), eye, budget, scanTally);
    }
  }

  /** 工作线程：视锥剔除（纯数学，不触碰任何 Bukkit API）。 */
  private Plan computePlan(ProximitySelector.Eye eye, int[] coordinates) {
    boolean frustum = proximity.frustumEnabled();
    double minDistance = proximity.frustumMinDistance();
    double fov = proximity.frustumFov();

    int count = coordinates.length / 3;
    int[] kept = new int[coordinates.length];
    int keptCount = 0;

    for (int i = 0; i < count; i++) {
      int x = coordinates[i * 3];
      int y = coordinates[i * 3 + 1];
      int z = coordinates[i * 3 + 2];

      if (frustum && !ProximitySelector.withinFrustum(eye, x, y, z, minDistance, fov)) {
        stats.revealsFrustumCulled.increment();
        continue;
      }

      kept[keptCount * 3] = x;
      kept[keptCount * 3 + 1] = y;
      kept[keptCount * 3 + 2] = z;
      keptCount++;
    }

    int[] result = new int[keptCount * 3];
    System.arraycopy(kept, 0, result, 0, result.length);
    return new Plan(result, count, count - keptCount);
  }

  /** 逐个候选做可见性判定 → 发包 → 标记已显形；{@code eye} 为 {@code null} 时不启用可见性判定。 */
  private void sendCandidates(Player player, World world,
      List<ObfuscatedChunkIndex.Position> candidates, Plan plan, ProximitySelector.Eye eye,
      Budget budget, ProximityScanner.Tally scanTally) {
    PassTally tally = new PassTally();
    tally.chunksScanned = scanTally.chunksScanned;
    tally.chunksSkipped = scanTally.chunksSkipped;
    tally.chunksDeferred = scanTally.chunksDeferred;
    tally.positionsEvaluated = scanTally.positionsEvaluated;
    int candidateCount = plan != null
        ? plan.candidateCount
        : (candidates == null ? 0 : candidates.size());
    int frustumCulled = plan != null ? plan.frustumCulled : 0;
    RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
    // 评估额度（本玩家本周期）：每次 sendOne 都算一次评估（含区块未加载 / 不可见的失败评估），
    // 因此评估量有独立于发包额度的明确上界；触顶即停，余下候选留在索引里等下一个周期。
    int evaluationsLeft = MAX_EVALUATIONS_PER_PASS;

    if (candidates != null) {
      for (ObfuscatedChunkIndex.Position position : candidates) {
        if (budget.remaining() <= 0 || evaluationsLeft <= 0) {
          break;
        }
        sendOne(player, world, position.x(), position.y(), position.z(), eye, budget, tally, batch);
        evaluationsLeft--;
      }
      flushBatch(player, batch, tally, budget);
      logFirstRevealDiagnostic(candidateCount, frustumCulled, tally);
      return;
    }
    if (plan == null) {
      return;
    }

    int count = plan.count();
    for (int i = 0; i < count; i++) {
      if (budget.remaining() <= 0 || evaluationsLeft <= 0) {
        break;
      }
      sendOne(player, world, plan.coordinates[i * 3], plan.coordinates[i * 3 + 1],
          plan.coordinates[i * 3 + 2], eye, budget, tally, batch);
      evaluationsLeft--;
    }
    flushBatch(player, batch, tally, budget);
    logFirstRevealDiagnostic(candidateCount, frustumCulled, tally);
  }

  /** 单个坐标：区块未加载则跳过、不可见则跳过，否则读真实方块；批量模式累积、否则立即发包并标记。 */
  private void sendOne(Player player, World world, int x, int y, int z, ProximitySelector.Eye eye,
      Budget budget, PassTally tally, RevealBatch batch) {
    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
      // 区块未加载：本次跳过，坐标留在索引里等玩家真正靠近
      stats.revealsSkipped.increment();
      return;
    }

    if (eye != null && proximity.raycastEnabled() && !isVisible(world, eye, x, y, z)) {
      stats.revealsRayCulled.increment();
      tally.rayCulled++;
      return;
    }

    // 过度显形量化（P2-6b，只计数不改行为）：发包前按 1/N 抽样，若 RevealedSet 已含该坐标，
    // 说明客户端应已可见（或刚被其它路径显形过），本次显形包按「过度」计数。
    if (overRevealSampler.sample()) {
      stats.overRevealSampled.increment();
      if (revealedSet.contains(player.getUniqueId(),
          ChunkKey.ofBlock(world.getName(), x, z), x, y, z)) {
        stats.overRevealWasted.increment();
      }
    }

    // 真实方块状态必须在玩家所属线程读取（本方法即运行在该线程上）
    BlockData data;
    try {
      data = world.getBlockAt(x, y, z).getBlockData();
    } catch (Throwable throwable) {
      logThrottled(throwable);
      data = null;
    }
    if (data == null) {
      stats.revealsSkipped.increment();
      return;
    }

    if (batch != null) {
      // 批量模式：先累积，周期末由 flushBatch 一次发出（按「先标记、再发包、失败回滚」处理）。
      // 这里先预扣额度以封住单周期上限；若最终未能发出，由 flushBatch 归还（见其「额度归还」说明）。
      batch.add(x, y, z, data);
      budget.decrement();
      return;
    }

    // 单包路径同样「先标记、再发包、失败回滚」（见 markThenSend）
    BlockData state = data;
    if (markThenSend(player.getUniqueId(), world.getName(), x, y, z,
        () -> sendSingle(player, world, x, y, z, state))) {
      stats.revealsSent.increment();
      tally.sent++;
      budget.decrement();
    } else {
      stats.revealsSkipped.increment();
    }
  }

  /**
   * 「先标记、再发包」的单一坐标实现（包级可见，便于离线单测注入可控发送结果验证顺序与回滚）。
   *
   * <p><b>为什么先标记</b>：显形包同样会经过本插件的出站监听器（{@link BlockChangeRevealListener}）——
   * 它看到我们发出的包后会把该坐标从伪装清单与已显形集合同步摘除。若<b>先发包后标记</b>，异步监听器
   * 可能在发包返回后、标记写入前先跑摘除：那时标记还不存在，摘除对已显形集合是空操作；随后标记才写入，
   * 于是给一个「索引里已不存在」的坐标留下<b>孤儿标记</b>（已显形数虚增 → 「整块跳过」提前成立 →
   * 真实未显形坐标被长期漏显形）。先标记后，监听器摘除时能命中并一并摘掉标记，两结构始终自洽。
   *
   * <p><b>失败回滚</b>：发送返回 false（未真正发出）时回滚标记，保证该坐标仍被后续周期重试、计数不虚增。
   *
   * <p><b>发包前复核（恰好一次）</b>：标记写入用的是原子「复核 + 标记」（{@link RevealedSet#markIfAbsent}），
   * 若该坐标已被本 tick 的另一条路径（周期巡检 / 事件即时）显形，则直接返回 false 跳过发送——
   * 既不重复发包、也不把这次计入「发送」（调用方按失败处理，计入「跳过」且不扣发包额度）。
   *
   * @param send 返回 true = 确实发出（保留标记）；false = 未发出（回滚标记）
   * @return {@code send} 的结果；该坐标已显形而未发送时返回 false
   */
  boolean markThenSend(UUID playerId, String worldName, int x, int y, int z,
      java.util.function.BooleanSupplier send) {
    if (revealedSet != null) {
      boolean claimed;
      try {
        // 发包前的原子复核：在同一临界区（RevealedSet.markIfAbsent）判重 + 标记，
        // 已显形者（本 tick 另一条路径刚发过）直接跳过发送，既不重复发包也不误改计数。
        claimed = revealedSet.markIfAbsent(playerId, ChunkKey.ofBlock(worldName, x, z), x, y, z);
      } catch (Throwable throwable) {
        // 复核失败 fail-open：无法判重就照常发送，绝不漏显形（最坏重复一次，客户端无感）
        logThrottled(throwable);
        claimed = true;
      }
      if (!claimed) {
        return false;
      }
    }
    if (send.getAsBoolean()) {
      // 记号：本次确实为该玩家发出了该坐标的显形包，出站监听器看到的同坐标变更即判为回显
      markOurReveal(playerId, worldName, x, y, z);
      return true;
    }
    if (revealedSet != null) {
      // 只回滚「本次这个玩家」的标记：其它玩家此前成功收到的显形仍有效，连带摘除只会让它们重复显形
      revealedSet.removePosition(playerId, worldName, x, y, z);
    }
    return false;
  }

  /**
   * 记下「刚为该玩家发出了该坐标的显形包」，供 {@link BlockChangeRevealListener} 识别回显。
   *
   * <p>本条不影响任何显形判定，纯粹是一枚短寿命记号：漏记只会退化为「照旧摘共享索引」（即修复前的
   * 行为），绝不产生新故障。因此任何异常都只记日志。
   */
  private void markOurReveal(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      return;
    }
    try {
      ConcurrentHashMap<EchoKey, Long> perPlayer =
          revealEcho.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
      if (perPlayer.size() >= REVEAL_ECHO_MAX_PER_PLAYER) {
        // 上界兜底：满即整体清空（只可能少判一次回显，不影响正确性）
        perPlayer.clear();
      }
      perPlayer.put(new EchoKey(worldName, x, y, z),
          System.nanoTime() + REVEAL_ECHO_WINDOW_NANOS);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /**
   * 判断该坐标的变更是否为「本插件刚发出的显形回显」，命中即<b>消费</b>该记号（保证同坐标只判一次：
   * 之后真正的方块变更不会被误判为回显）。
   *
   * <p>语义：返回 {@code true} = 这是回显，服务端内容没变，调用方<b>不得</b>摘除共享伪装索引。
   * 记号缺失或已过期返回 {@code false}（按真实变更处理），异常同样返回 false（fail-open 到既有行为）。
   */
  public boolean consumeOurRevealEcho(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      return false;
    }
    try {
      ConcurrentHashMap<EchoKey, Long> perPlayer = revealEcho.get(playerId);
      if (perPlayer == null || perPlayer.isEmpty()) {
        // 稳态下记号当 tick 即被消费、内层 map 为空：空即早退，省去 netty 线程上每个变更坐标的
        // EchoKey 分配（满载多方块变更包最多 4096 个坐标）与 remove 查找。
        return false;
      }
      Long expiry = perPlayer.remove(new EchoKey(worldName, x, y, z));
      return expiry != null && expiry >= System.nanoTime();
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
  }

  /**
   * 批量显形的原子「复核 + 标记」步（包级可见，便于离线单测）。
   *
   * <p>逐个坐标在写入标记的同一临界区内（{@link RevealedSet#markIfAbsent}）确认它本周期尚未显形：
   * 只把「本次真正新登记、需要发包」的坐标下标返回，已显形者被跳过（不标记、不发包），
   * 从而拦下周期巡检与事件即时显形跨路径同 tick 的重复候选（见 {@link #flushBatch}）。
   *
   * <p>复核抛异常时按「需要发包」处理（fail-open）：无法判重就照常发送，绝不漏显形。
   *
   * @return 需要发包的坐标下标（对应 {@code positions}）；无新登记时为空数组
   */
  int[] claimForSend(UUID playerId, String worldName, List<int[]> positions) {
    int[] claimed = new int[positions.size()];
    int count = 0;
    for (int i = 0; i < positions.size(); i++) {
      int[] position = positions.get(i);
      boolean fresh;
      if (revealedSet == null) {
        fresh = true;
      } else {
        try {
          fresh = revealedSet.markIfAbsent(playerId,
              ChunkKey.ofBlock(worldName, position[0], position[2]),
              position[0], position[1], position[2]);
        } catch (Throwable throwable) {
          logThrottled(throwable);
          fresh = true;
        }
      }
      if (fresh) {
        // 这里只登记「已显形」标记（供跨路径判重）。回显记号必须等真正发包成功后再写
        // （见 sendBatchOrRollback / flushBatch）：先写会在发包失败时留下「记号在、包没发成」的
        // ≤2 秒窗口，其间该坐标的真实方块变更会被误判为回显而漏摘共享索引。
        claimed[count++] = i;
      }
    }
    return java.util.Arrays.copyOf(claimed, count);
  }

  /**
   * 合并包的「发包 + 成功补记回显记号 / 失败整批回滚」步（包级可见，便于离线单测）。
   *
   * <p>调用方已在 {@link #claimForSend} 里完成原子复核与「已显形」标记，这里只负责把 {@code fresh} 这批
   * 坐标一次发出：<b>发出成功</b>后才为它们补记回显记号（记号必须后于发包，见 {@link #claimForSend}）；
   * 发送失败则回滚这些坐标的「已显形」标记（返回 false，调用方随后退化为逐坐标 {@link #markThenSend}，
   * 未发出的坐标后续周期仍会重试）。
   */
  boolean sendBatchOrRollback(UUID playerId, String worldName, List<int[]> positions, int[] fresh,
      java.util.function.BooleanSupplier send) {
    if (send.getAsBoolean()) {
      // 回显记号写在发包成功之后：失败时不留记号，避免「记号在、包没发成」把随后的真实变更误判为回显
      for (int index : fresh) {
        int[] position = positions.get(index);
        markOurReveal(playerId, worldName, position[0], position[1], position[2]);
      }
      return true;
    }
    if (revealedSet != null) {
      for (int index : fresh) {
        int[] position = positions.get(index);
        // 只回滚本玩家的标记（同 markThenSend）：其它玩家的有效显形不该被连带作废
        revealedSet.removePosition(playerId, worldName, position[0], position[1], position[2]);
      }
    }
    return false;
  }

  /**
   * 发出本周期累积的显形（批量模式）：优先一次 {@code Player#sendMultiBlockChange}——Paper 会按
   * 16³ 区块段自行合并，因此「本周期 N 个坐标」通常只产生「涉及的段数」个多方块变更包。
   *
   * <p>只有一个坐标时直接用 {@code sendBlockChange}（单包更小，也与旧行为一致）。
   * 批量调用整体失败时退化为逐坐标 {@code sendBlockChange}（同一条原生通道），单个坐标失败只计数并
   * 跳过。发包一律走「先标记、再发包、失败回滚」（见 {@link #markThenSend}）：成功发出的坐标保留标记，
   * 未发出的坐标回滚标记，因此发包失败既不影响其它坐标、也不污染已显形索引，且失败坐标后续仍会重试。
   *
   * <p><b>发包前复核（恰好一次）</b>：批次在累积时仅按「候选未显形」过滤，跨路径（周期巡检与事件即时
   * 显形共用本方法）同 tick 仍可能对同一坐标各累积一次。因此发包容纳集合由 {@link #claimForSend} 在
   * 「判重 + 标记」的同一临界区内定夺：已显形者不进入本次发包集合（既不重复发包，也不计入「发送」）。
   *
   * <p><b>额度归还</b>：批次坐标是在 {@link #sendOne} 里「先预扣额度再累积」的（用于封住单周期上限），
   * 因此这里对<b>未能发出</b>的坐标（被复核跳过、或退化路径里 {@code sendBlockChange} 失败）逐个
   * {@link Budget#refund()} 归还额度；成功发出的不归还，维持「每周期上限」语义。
   */
  private void flushBatch(Player player, RevealBatch batch, PassTally tally, Budget budget) {
    if (batch == null || batch.size() == 0) {
      return;
    }
    World world = batch.world;
    UUID playerId = player.getUniqueId();
    String worldName = world.getName();

    // 原子复核 + 标记：只对「本周期尚未显形、本次真正新登记」的坐标发包（见 claimForSend）。
    int[] fresh = claimForSend(playerId, worldName, batch.positions);
    int skipped = batch.size() - fresh.length;
    if (skipped > 0) {
      // 已显形者：不计入「发送」，归还 sendOne 里预扣的额度（额度只为真正发出的显形保留）
      stats.revealsSkipped.add(skipped);
      for (int i = 0; i < skipped; i++) {
        budget.refund();
      }
    }
    if (fresh.length == 0) {
      return;
    }

    // 「已显形」标记由 claimForSend 写好；合并包失败时 sendBatchOrRollback 会整批回滚，届时逐坐标重新「复核 + 标记」。
    boolean revealedMarksWritten = true;
    if (fresh.length > 1) {
      Map<Position, BlockData> changes;
      try {
        changes = new LinkedHashMap<>(fresh.length * 2);
        for (int index : fresh) {
          int[] position = batch.positions.get(index);
          changes.put(Position.block(position[0], position[1], position[2]),
              batch.states.get(index));
        }
      } catch (Throwable throwable) {
        logThrottled(throwable);
        changes = null;
      }
      if (changes != null) {
        Map<Position, BlockData> payload = changes;
        boolean sent = sendBatchOrRollback(playerId, worldName, batch.positions, fresh, () -> {
          try {
            player.sendMultiBlockChange(payload);
            return true;
          } catch (Throwable throwable) {
            // 退化路径：仍走 Paper 原生单方块变更包，不退回封包自拼
            logThrottled(throwable);
            return false;
          }
        });
        if (sent) {
          for (int i = 0; i < fresh.length; i++) {
            stats.revealsSent.increment();
            tally.sent++;
          }
          return;
        }
        revealedMarksWritten = false; // 合并包未发出 → 本批「已显形」标记已整批回滚
      }
      // changes 构建失败：「已显形」标记仍在（revealedMarksWritten 保持 true），落到下面的逐坐标单包
    }

    for (int index : fresh) {
      int[] position = batch.positions.get(index);
      BlockData state = batch.states.get(index);
      boolean sent;
      if (revealedMarksWritten) {
        // 「已显形」标记已在 claimForSend 写好：发送成功即保留并补记回显记号（记号必须后于发包），
        // 失败则回滚（该坐标仍在伪装清单里，后续周期重试）
        sent = sendSingle(player, world, position[0], position[1], position[2], state);
        if (sent) {
          markOurReveal(playerId, worldName, position[0], position[1], position[2]);
        } else if (revealedSet != null) {
          // 只回滚本玩家的标记（同 markThenSend），避免连带作废其它玩家的有效显形
          revealedSet.removePosition(playerId, worldName, position[0], position[1], position[2]);
        }
      } else {
        // 合并包已回滚本批标记：逐坐标重新走「先复核 + 标记、再发包、失败回滚」（见 markThenSend）
        sent = markThenSend(playerId, worldName, position[0], position[1], position[2],
            () -> sendSingle(player, world, position[0], position[1], position[2], state));
      }
      if (!sent) {
        stats.revealsSkipped.increment();
        // 未发出：归还先前在 sendOne 里预扣的额度（本周期上限只应计入真正发出的显形）
        budget.refund();
        continue;
      }
      stats.revealsSent.increment();
      tally.sent++;
    }
  }

  /** Paper 原生单方块变更包（{@code Player#sendBlockChange}）；任何异常只返回 false，绝不外抛。 */
  private boolean sendSingle(Player player, World world, int x, int y, int z, BlockData data) {
    try {
      player.sendBlockChange(new Location(world, x, y, z), data);
      return true;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
  }

  /**
   * 一次性诊断：首次进入显形流程（取到候选）时打印各层拦截数，绝不刷屏。
   *
   * <p><b>为什么要它</b>：真机反馈「矿洞里一个裸露矿物都看不到」，而「候选 0 个」与「候选很多但全被
   * 视锥/射线拦掉」是完全不同的两种失效。这一行给出决定性判据：
   * <ul>
   *   <li>扫描区块 / 整块跳过 → 局部扫描是否生效、稳态下是否真的「只处理新进入视野的区块」；</li>
   *   <li>坐标评估 N → 本周期实际评估的候选量（应与「身边区块的伪装坐标数」同量级）；</li>
   *   <li>候选 N = 0 → 显形索引里根本没有该玩家身边的坐标（区块改写没记录 / 索引已被清理）；</li>
   *   <li>候选 N &gt; 0 但视锥剔除 X 很大 → 视锥太窄（fov/min-distance 需放宽）；</li>
   *   <li>候选 N &gt; 0 但射线剔除 Y 很大 → 射线判定过严（射线目标点/参数需要复核）；</li>
   *   <li>实际发送 Z 明显小于 N → 单次发包额度或区块未加载在拦（max-reveals-per-tick / 视距）。</li>
   * </ul>
   * 只在第一次显形尝试时打印一次（CAS 抢占），此后不再产生任何开销。
   */
  private void logFirstRevealDiagnostic(int candidateCount, int frustumCulled, PassTally tally) {
    if (!firstRevealDiagnosed.compareAndSet(false, true)) {
      return;
    }
    plugin.getLogger().info("首次显形诊断：扫描区块 " + tally.chunksScanned + " 个（整块跳过 "
        + tally.chunksSkipped + "，分片推迟 " + tally.chunksDeferred + "／共 " + SCAN_SHARDS
        + " 片），坐标评估 " + tally.positionsEvaluated + " 个，候选 " + candidateCount
        + " 个，视锥剔除 " + frustumCulled + "，射线剔除 " + tally.rayCulled
        + "，实际发送 " + tally.sent);
  }

  /**
   * 主线程 / 区域线程做可见性判定：先判「上方是否流体」（流体覆盖开启时，是则不显形），
   * 再读 6 个邻块判暴露面，然后对暴露面上的至多 {@code proximity.raycast.samples} 个候选点
   * 用 Paper 原生射线（{@code World#rayTraceBlocks}）逐一检测，任一条通畅即可见。
   *
   * <p><b>绝不隔墙还原</b>：任何异常/歧义一律按「不显形」处理（保守）。
   */
  private boolean isVisible(World world, ProximitySelector.Eye eye, int x, int y, int z) {
    try {
      // 候选点数（由 proximity.raycast.samples 提供；有效上限 5，即 ProximitySelector.MAX_SAMPLE_POINTS）
      int maxPoints = proximity.raycastSamples();
      // 射线起点必须是带世界的 Location：rayTraceBlocks 依赖它定位起始体素
      Location eyeLocation = new Location(world, eye.x(), eye.y(), eye.z());
      ProximitySelector.RayQuery query = new ProximitySelector.RayQuery() {
        @Override
        public boolean isOccluding(int blockX, int blockY, int blockZ) {
          BlockData data = world.getBlockData(blockX, blockY, blockZ);
          return data != null && data.isOccluding();
        }

        @Override
        public boolean isFluid(int blockX, int blockY, int blockZ) {
          // 只认本体就是流体的材质（水/岩浆/水柱）：含水的台阶/栅栏等不算（与注册表的流体覆盖掩码同义）。
          Material material = world.getBlockData(blockX, blockY, blockZ).getMaterial();
          return material == Material.WATER || material == Material.LAVA
              || material == Material.BUBBLE_COLUMN;
        }
      };
      return ProximitySelector.isVisible(eye, x, y, z, query, maxPoints, fluidCover,
          (pointX, pointY, pointZ) -> rayClear(world, eyeLocation, x, y, z, pointX, pointY, pointZ));
    } catch (Throwable throwable) {
      // 无法判定 → 按「不可见」保守处理（红线：绝不隔着墙还原）
      logVisibilityFailure(throwable);
      return false;
    }
  }

  /**
   * 单点原生射线检测：从眼睛射向世界坐标 {@code (pointX, pointY, pointZ)}。
   *
   * <p>返回 true = 途中未被任何方块截住（可视为通畅）；命中「目标方块自身」也容忍为通畅
   * （候选点正落在方块表面上时的边界情形，射线恰好在端点处碰到本方块）。
   * 命中其它方块（含玻璃等非遮挡方块）一律判为「不通畅」——宁可漏显形，也绝不隔着墙还原。
   */
  private static boolean rayClear(World world, Location eyeLocation, int targetX, int targetY,
      int targetZ, double pointX, double pointY, double pointZ) {
    double dx = pointX - eyeLocation.getX();
    double dy = pointY - eyeLocation.getY();
    double dz = pointZ - eyeLocation.getZ();
    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (distance < 1.0E-6D) {
      // 眼位与候选点重合：视为通畅（贴得太近，无从遮挡）
      return true;
    }
    Vector direction = new Vector(dx, dy, dz).normalize();
    // FluidCollisionMode.NEVER：流体不参与射线（流体规则由 isVisible 显式判定）
    RayTraceResult hit = world.rayTraceBlocks(eyeLocation, direction, distance,
        FluidCollisionMode.NEVER, true);
    if (hit == null || hit.getHitBlock() == null) {
      return true;
    }
    Block block = hit.getHitBlock();
    return block.getX() == targetX && block.getY() == targetY && block.getZ() == targetZ;
  }

  /** 可见性判定失败（异常）只提示一次：新增失败模式必须可见，但不刷屏。 */
  private void logVisibilityFailure(Throwable throwable) {
    if (visibilityWarned.compareAndSet(false, true)) {
      plugin.getLogger().log(Level.WARNING,
          "邻近显形可见性判定失败：本次一律按「不显形」处理（绝不隔着墙还原）。"
              + "改用 Paper 原生 rayTraceBlocks 后，此类失败通常源于世界读取异常，请反馈此日志。",
          throwable);
    }
  }

  /**
   * 周期清理过期条目（区块卸载未触发时的兜底）：伪装清单与已显形标记都要清，避免长期堆积。
   *
   * <p>两个结构的活跃时间都会在扫描到该区块时刷新，因此玩家身边的条目不会被误清。
   *
   * <p><b>为什么挪进工作池</b>：expire 是 O(全服存活条目) 的全量扫描，旧的实现把它放在主线程 /
   * Folia 区域线程上执行——大服每 {@value #EXPIRE_EVERY_PASSES} 个周期就会出现一次与「全服状态量」
   * 成正比的毛刺（区域线程被占住 = 该区域玩家卡顿）。两个结构的 expire 都是纯并发容器操作
   * （不触碰任何 Bukkit API、可在任意线程运行），因此改投 {@link MikuWorkPool}；提交是非阻塞的，
   * 绝不让调用线程等待。工作队列满或未配置工作池时退化为当前线程直接清理，<b>过期清除语义不变</b>。
   *
   * <p>并发安全：多轮 expire 可能重叠（工作线程慢时），但两者都只用 {@code map.remove(k, v)} 原子判活，
   * 同一 entry 只会被真正移除一次，计数扣减不会重复。
   */
  private void maybeExpire() {
    // 「距上次清理是否已过一个完整周期窗口」用<b>时间</b>判断，而不是「每 N 次巡检」：
    // Folia 下每个玩家各有一条区域任务、各自调用本方法，若按巡检次数计数，过期频率会随在线人数
    // 线性放大（20 人时几乎每周期触发一次 O(全服条目) 的全量扫描 + 一次工作池提交），
    // 与类注释声明的「约 4 秒一次」严重不符。时间门控在两种平台、任意在线人数下频率都恒定；
    // CAS 保证并发调用中只有一条路径真正执行。
    long now = System.nanoTime();
    long last = lastExpireNanos.get();
    if (now - last < expirePeriodNanos || !lastExpireNanos.compareAndSet(last, now)) {
      return;
    }
    if (workPool != null && workPool.hasCapacity()) {
      try {
        workPool.execute(this::expireNow);
        return;
      } catch (Throwable throwable) {
        // 入队失败（队列刚被填满 / 线程池关闭）：退回当前线程直接清理，绝不丢过期清除
        logThrottled(throwable);
      }
    }
    expireNow();
  }

  /** 实际执行两个结构的过期清理（工作线程或退化时的调用线程；纯并发结构操作，任意线程安全）。 */
  private void expireNow() {
    try {
      chunkIndex.expire();
      revealedSet.expire();
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  private int limit() {
    return Math.max(1, proximity.maxRevealsPerTick());
  }

  /** 取候选时按额度的若干倍取（视锥/射线会剔除一部分），并限制总量避免单次扫描过重。 */
  private int fetchLimit(int budget) {
    return Math.max(1, Math.min(MAX_CANDIDATES, Math.max(1, budget) * CANDIDATE_OVERSAMPLE));
  }

  /** 玩家眼睛位置与视线方向快照（主线程读取，之后只传纯值）。 */
  private static ProximitySelector.Eye eyeOf(Player player) {
    Location eye = player.getEyeLocation();
    Vector direction = eye.getDirection();
    return ProximitySelector.eye(eye.getX(), eye.getY(), eye.getZ(),
        direction.getX(), direction.getY(), direction.getZ());
  }

  private static int[] flatten(List<ObfuscatedChunkIndex.Position> candidates) {
    int[] coordinates = new int[candidates.size() * 3];
    for (int i = 0; i < candidates.size(); i++) {
      ObfuscatedChunkIndex.Position position = candidates.get(i);
      coordinates[i * 3] = position.x();
      coordinates[i * 3 + 1] = position.y();
      coordinates[i * 3 + 2] = position.z();
    }
    return coordinates;
  }

  // ============================== 事件驱动即时显形（P1-4）==============================

  /**
   * 出站方块变更回调（由 {@link BlockChangeRevealListener} 在封包解析线程调用）：
   * 玩家收到某个方块变更包时，若变更坐标的邻域内（曼哈顿 ≤ {@code instant-reveal.radius}）
   * 仍有伪装坐标，则调度到玩家所属线程当 tick 补发显形。
   *
   * <p><b>为什么只在这里做初筛</b>：封包线程不能读 World / 玩家位置，但伪装索引是纯内存并发结构，
   * 「邻域内是否还有伪装坐标」的初筛可以安全地在这一步完成——邻域内全是非伪装方块时
   * （如在地表插火把）不产生任何调度；命中时也只调度一次，最终判定（身边半径、射线、限额、去重）
   * 全部在玩家所属线程的 {@link #revealImmediately} 里做。
   *
   * <p><b>与周期巡检的关系</b>：事件触发是<b>补充</b>（把「挖开/爆炸后要等最多一个周期」的窗口压到 0），
   * 周期巡检兜底不变；两者共用 {@link #sendOne} 与已显形标记，同一坐标当 tick 只发一次。
   */
  public void onBlockChangeObserved(Player player, String worldName, int x, int y, int z) {
    if (player == null || worldName == null
        || !instant.enabled() || instant.maxPerTick() <= 0) {
      return;
    }
    // 黑名单世界不做事件即时显形（在调度前就返回，避免产生任何任务）：纯判定，不触碰 Bukkit。
    if (config.isBlacklisted(worldName)) {
      return;
    }
    if (chunkIndex == null || revealedSet == null) {
      return;
    }
    if (hasDisguisedNearby(chunkIndex, worldName, x, y, z, instant.radius())) {
      Schedulers.onEntity(plugin, player, () -> revealImmediately(player, worldName, x, y, z));
    }
  }

  /**
   * 初筛（纯函数，可离线单测）：变更坐标的曼哈顿邻域内是否仍有伪装坐标。
   * 只读并发索引，供封包线程在调度前过滤「邻域内全是非伪装方块」的无效变更。
   */
  static boolean hasDisguisedNearby(ObfuscatedChunkIndex index, String worldName,
      int x, int y, int z, int radius) {
    // 邻域偏移是固定的、且只落在至多 4 个区块里：按区块复用 ChunkKey，避免每个偏移都新建一个
    // （一个塞满 4096 坐标的 MULTI_BLOCK_CHANGE 默认约 10 万次查找，是封包线程上最大的一笔分配）。
    ChunkKeyCache keys = ChunkKeyCache.LOCAL.get();
    keys.begin(worldName);
    for (int[] offset : instantOffsets(radius)) {
      int blockX = x + offset[0];
      int blockY = y + offset[1];
      int blockZ = z + offset[2];
      if (index.containsPosition(keys.keyFor(blockX, blockZ), blockX, blockY, blockZ)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 邻域初筛的区块键小缓存（线程本地复用，零分配）。
   *
   * <p>半径上限为 {@code 8}（配置解析已钳制，见 {@link #instantOffsets}），故一次邻域查找涉及的方块
   * 横向只跨 ±8 格（不足一个区块），涉及的区块数至多 {@code 2×2 = 4} 个（{@code x>>4} 与 {@code z>>4}
   * 各两个取值）。容量 4 正好覆盖；万一超出（防御性）则退化为每次新建键，语义不变。
   */
  private static final class ChunkKeyCache {

    private static final ThreadLocal<ChunkKeyCache> LOCAL =
        ThreadLocal.withInitial(ChunkKeyCache::new);
    private static final int CAPACITY = 4;

    private final int[] chunkXs = new int[CAPACITY];
    private final int[] chunkZs = new int[CAPACITY];
    private final ChunkKey[] keys = new ChunkKey[CAPACITY];
    private String worldName;
    private int size;

    /** 开始一次邻域初筛（重置缓存）。 */
    void begin(String worldName) {
      this.worldName = worldName;
      this.size = 0;
    }

    /** 取该方块坐标所在区块的键（命中已缓存的区块则复用，避免每次查找都新建）。 */
    ChunkKey keyFor(int x, int z) {
      int cx = x >> 4;
      int cz = z >> 4;
      for (int i = 0; i < size; i++) {
        if (chunkXs[i] == cx && chunkZs[i] == cz) {
          return keys[i];
        }
      }
      ChunkKey key = new ChunkKey(worldName, cx, cz);
      if (size < CAPACITY) {
        chunkXs[size] = cx;
        chunkZs[size] = cz;
        keys[size] = key;
        size++;
      }
      return key;
    }
  }

  /**
   * 单个 section（一组变更坐标）的即时显形入口（由 {@link BlockChangeRevealListener} 在封包线程调用）。
   *
   * <p>语义与 {@link #onBlockChangeObserved} 完全一致（邻域内仍有伪装坐标才调度），但把同一 section 的
   * 多个变更坐标聚合为「<b>一次初筛 + 一次调度</b>」：逐坐标版本会对每个变更坐标各初筛一次、各调度一个
   * 任务（一个 section 最多 4096 次），此处只做一次。最终判定仍在玩家所属线程做（见
   * {@link #revealSectionImmediately}），`恰好一次放行`由 {@link #sendOne} 与已显形标记保证。
   *
   * @param coordinates 同一 section 的变更坐标（已去重，扁平三元组，前 {@code count} 个有效）
   */
  public void onSectionChangeObserved(Player player, String worldName, int[] coordinates, int count) {
    if (player == null || worldName == null || coordinates == null || count <= 0
        || !instant.enabled() || instant.maxPerTick() <= 0) {
      return;
    }
    // 黑名单世界不做事件即时显形（在调度前就返回，避免产生任何任务）：纯判定，不触碰 Bukkit。
    if (config.isBlacklisted(worldName)) {
      return;
    }
    if (chunkIndex == null || revealedSet == null) {
      return;
    }
    if (hasDisguisedNearSection(chunkIndex, worldName, coordinates, count, instant.radius())) {
      Schedulers.onEntity(plugin, player,
          () -> revealSectionImmediately(player, worldName, coordinates, count));
    }
  }

  /**
   * 聚合初筛（纯函数）：整 section 只调用一次，任一变更坐标的曼哈顿邻域内仍有伪装坐标即命中。
   * 与逐坐标 {@link #hasDisguisedNearby} 的判定逐条等价，命中即短路返回。
   *
   * <p>带总探测次数上限（见 {@link #MAX_NEARBY_PROBES_PER_PACKET}）。<b>触顶时返回 true</b>：本方法的用途是
   * 「判断附近是否还有伪装方块、以决定要不要做显形」，方向是<b>宁可返回「有」也不能漏</b>——漏判会让该次
   * 区块变更后本应显形的坐标没显形（真正是否显形仍由后续链路按限额与去重决定）。
   */
  static boolean hasDisguisedNearSection(ObfuscatedChunkIndex index, String worldName,
      int[] coordinates, int count, int radius) {
    ChunkKeyCache keys = ChunkKeyCache.LOCAL.get();
    keys.begin(worldName);
    int[][] offsets = instantOffsets(radius);
    int probesLeft = MAX_NEARBY_PROBES_PER_PACKET;
    for (int i = 0; i < count; i++) {
      int x = coordinates[i * 3];
      int y = coordinates[i * 3 + 1];
      int z = coordinates[i * 3 + 2];
      for (int o = 0; o < offsets.length; o++) {
        if (probesLeft <= 0) {
          return true; // 触顶：保守判定为「附近有伪装坐标」，绝不因限额而漏显形
        }
        probesLeft--;
        int blockX = x + offsets[o][0];
        int blockY = y + offsets[o][1];
        int blockZ = z + offsets[o][2];
        if (index.containsPosition(keys.keyFor(blockX, blockZ), blockX, blockY, blockZ)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 玩家所属线程：事件显形的最终判定与发包。
   *
   * <p>顺序：直通/在线 → 世界生效 → 曼哈顿半径（变更不在身边则交给周期兜底）→ 区块已加载 →
   * 取本 tick 剩余额度 → 逐偏移「仍在伪装清单 + 该玩家未显形过」→ 复用 {@link #sendOne}
   * （射线判定、发包、标记、统计全在既有链路里）。失败只记日志，绝不抛出。
   */
  private void revealImmediately(Player player, String worldName, int cx, int cy, int cz) {
    try {
      if (!player.isOnline()
          || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
        return;
      }
      World world = player.getWorld();
      // 黑名单世界不做任何邻近显形（事件即时）：与周期巡检共用同一判定出口。
      if (!config.antiXrayAppliesTo(world.getName())) {
        return;
      }
      int radius = instant.radius();
      Location location = player.getLocation();
      if (!withinManhattanRadius(location.getBlockX(), location.getBlockY(), location.getBlockZ(),
          cx, cy, cz, radius)) {
        return;
      }
      if (!world.isChunkLoaded(cx >> 4, cz >> 4)) {
        return;
      }

      UUID playerId = player.getUniqueId();
      long tick = Bukkit.getCurrentTick();
      Budget budget = new Budget(instantQuota.remaining(playerId, tick, instant.maxPerTick()));
      if (budget.remaining() <= 0) {
        return;
      }

      ProximitySelector.Eye eye = proximity.raycastEnabled() ? eyeOf(player) : null;
      PassTally tally = new PassTally();
      RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
      String liveWorld = world.getName();
      for (int[] offset : instantOffsets(radius)) {
        if (budget.remaining() <= 0) {
          break;
        }
        int x = cx + offset[0];
        int y = cy + offset[1];
        int z = cz + offset[2];
        if (!isInstantCandidate(playerId, liveWorld, x, y, z)) {
          continue;
        }
        sendOne(player, world, x, y, z, eye, budget, tally, batch);
      }
      flushBatch(player, batch, tally, budget);
      instantQuota.setRemaining(playerId, tick, Math.max(0, budget.remaining()));
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /**
   * 玩家所属线程：一个 section 全部变更坐标的即时显形（与逐坐标 {@link #revealImmediately} 等价，
   * 但只调度一个任务）。对每个变更坐标走同一套判定（身边半径、区块已加载、仍在伪装清单且未显形过），
   * 候选坐标跨变更坐标去重后复用 {@link #sendOne}，因此结果与逐坐标处理逐条一致。
   */
  private void revealSectionImmediately(Player player, String worldName, int[] coordinates, int count) {
    try {
      if (!player.isOnline()
          || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
        return;
      }
      World world = player.getWorld();
      // 黑名单世界不做任何邻近显形（事件即时）：与周期巡检共用同一判定出口。
      if (!config.antiXrayAppliesTo(world.getName())) {
        return;
      }
      int radius = instant.radius();
      Location location = player.getLocation();
      UUID playerId = player.getUniqueId();
      long tick = Bukkit.getCurrentTick();
      Budget budget = new Budget(instantQuota.remaining(playerId, tick, instant.maxPerTick()));
      if (budget.remaining() <= 0) {
        return;
      }

      ProximitySelector.Eye eye = proximity.raycastEnabled() ? eyeOf(player) : null;
      PassTally tally = new PassTally();
      RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
      String liveWorld = world.getName();
      int[][] offsets = instantOffsets(radius);
      // 去重：同一候选坐标可能同时落在多个变更坐标的邻域里，只评估一次（保证「恰好一次放行」）。
      // 去重集合用 HashSet<Long>，代价是每个候选一次 long 装箱 + 哈希节点分配（一个 section 变更的
      // 邻域候选量级为数千），换来的是与「逐变更坐标各判一遍」的**逐条等价**：同一坐标只评估/发包一次。
      // 之所以不换位图：候选坐标是绝对坐标、可能跨 section，位图需要以变更点为中心开辟随半径增长的
      // 三维空间（半径 8 时 17³≈5k 格 × 大量变更点），哈希反而更省；若将来改位图，必须证明去掉装箱后
      // 「每个绝对坐标仍只评估一次」——否则会重复发包（over-reveal）或漏去重（同一坐标多次 sendOne）。
      java.util.HashSet<Long> evaluated = new java.util.HashSet<>();
      // 评估次数上限：budget 只在「成功发包」时递减，因此若候选<b>全部未命中</b>（邻域里大多是已显形、
      // 或已不在伪装清单里的坐标），内层循环会一路跑满 count × offsets——radius=8 时每个变更坐标有
      // 数百个偏移，一个 section（最多 4096 个变更坐标）可达数十万次判定，且全部落在玩家实体线程
      // （Paper 主线程 / Folia 区域线程）。周期巡检有 MAX_EVALUATIONS_PER_PASS 兜底，这里同样加硬上限：
      // 触顶即停，剩余候选交给同 tick 的后续事件或下一个巡检周期处理（显形只延迟、不丢失）。
      int evaluationsLeft = MAX_EVALUATIONS_PER_PASS;
      boolean exhausted = false;
      for (int i = 0; i < count && !exhausted; i++) {
        int cx = coordinates[i * 3];
        int cy = coordinates[i * 3 + 1];
        int cz = coordinates[i * 3 + 2];
        // 与逐坐标版本等价：变更坐标不在身边 / 其区块未加载时，跳过该变更坐标的邻域
        if (!withinManhattanRadius(location.getBlockX(), location.getBlockY(), location.getBlockZ(),
            cx, cy, cz, radius)) {
          continue;
        }
        if (!world.isChunkLoaded(cx >> 4, cz >> 4)) {
          continue;
        }
        for (int o = 0; o < offsets.length; o++) {
          if (budget.remaining() <= 0 || evaluationsLeft <= 0) {
            exhausted = true;
            break;
          }
          int x = cx + offsets[o][0];
          int y = cy + offsets[o][1];
          int z = cz + offsets[o][2];
          evaluationsLeft--;
          if (!isInstantCandidate(playerId, liveWorld, x, y, z)) {
            continue;
          }
          if (!evaluated.add(packCoordinate(x, y, z))) {
            continue;
          }
          sendOne(player, world, x, y, z, eye, budget, tally, batch);
        }
      }
      flushBatch(player, batch, tally, budget);
      instantQuota.setRemaining(playerId, tick, Math.max(0, budget.remaining()));
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 候选坐标打包（仅供聚合显形去重；与 {@link RevealedSet} 同一编码：x/z 各 26 位、y 12 位）。 */
  private static long packCoordinate(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
  }

  /** 纯数据判定：坐标仍在伪装清单（已伪装）且该玩家尚未显形过（与周期显形不重复、同 tick 不重复）。 */
  boolean isInstantCandidate(UUID playerId, String worldName, int x, int y, int z) {
    if (!chunkIndex.containsPosition(worldName, x, y, z)) {
      return false;
    }
    return !revealedSet.contains(playerId, ChunkKey.ofBlock(worldName, x, z), x, y, z);
  }

  /** 曼哈顿距离 ≤ radius 即命中（含恰好等于；纯函数，供触发判定与单测复用）。 */
  static boolean withinManhattanRadius(int px, int py, int pz, int cx, int cy, int cz, int radius) {
    return Math.abs(px - cx) + Math.abs(py - cy) + Math.abs(pz - cz) <= radius;
  }

  /** 曼哈顿半径偏移表的缓存（键为钳制后的半径；表只建一次，热路径零分配）。 */
  private static final ConcurrentHashMap<Integer, int[][]> INSTANT_OFFSETS_CACHE =
      new ConcurrentHashMap<>();

  /**
   * 曼哈顿半径内全部偏移（含原点，按距离由近到远排序），按半径缓存。
   * 半径在配置解析时已钳制到 1~8，这里再兜底一次，防止异常配置撑爆偏移表。
   */
  static int[][] instantOffsets(int radius) {
    int clamped = Math.max(1, Math.min(8, radius));
    int[][] cached = INSTANT_OFFSETS_CACHE.get(clamped);
    if (cached != null) {
      return cached;
    }
    java.util.List<int[]> offsets = new java.util.ArrayList<>();
    for (int dx = -clamped; dx <= clamped; dx++) {
      for (int dy = -clamped; dy <= clamped; dy++) {
        for (int dz = -clamped; dz <= clamped; dz++) {
          int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
          if (distance <= clamped) {
            offsets.add(new int[] {dx, dy, dz, distance});
          }
        }
      }
    }
    // 由近到远：限额不够时优先显形离变更点最近的坐标（排序只在构建缓存时做一次）
    offsets.sort(java.util.Comparator.comparingInt(offset -> offset[3]));
    int[][] table = offsets.toArray(new int[0][]);
    INSTANT_OFFSETS_CACHE.putIfAbsent(clamped, table);
    return INSTANT_OFFSETS_CACHE.get(clamped);
  }

  /**
   * 每玩家每 tick 的事件显形限额（纯数据结构，可离线单测）。
   *
   * <p>槽位 {@code long[2] = [tick, 剩余额度]}，tick 变化即自动重置为满额；额度按「事件显形实际
   * 发包数」扣减（读剩余 → 发包 → 写回剩余），因此限额是「每玩家每 tick 真实发包数」的口径。
   * 条目随玩家退出精确清理（见 {@link #onQuit}）。
   */
  static final class TickQuota {

    private final ConcurrentHashMap<UUID, long[]> slots = new ConcurrentHashMap<>();

    /** 本 tick 的剩余额度；本 tick 尚未使用过或已进入下一 tick 时返回满额。 */
    int remaining(UUID player, long tick, int maxPerTick) {
      long[] slot = slots.get(player);
      if (slot == null) {
        return maxPerTick;
      }
      synchronized (slot) {
        return slot[0] == tick ? (int) slot[1] : maxPerTick;
      }
    }

    /** 写回本 tick 的剩余额度（发包后调用；tick 变化时写回值即新 tick 的满额起点）。 */
    void setRemaining(UUID player, long tick, int remaining) {
      long[] slot = slots.computeIfAbsent(player, ignored -> new long[2]);
      synchronized (slot) {
        slot[0] = tick;
        slot[1] = remaining;
      }
    }

    /** 玩家退出时精确清理其槽位。 */
    void clear(UUID player) {
      slots.remove(player);
    }
  }

  /**
   * 过度显形抽样器（P2-6b，纯逻辑，可离线单测）：按 1/{@code rate} 抽样。
   * {@code rate <= 0} 表示关闭（永不抽样）。只决定「是否复核一次」，不影响任何发包行为。
   */
  static final class OverRevealSampler {

    private final java.util.concurrent.atomic.AtomicLong counter = new AtomicLong();
    private final int rate;

    OverRevealSampler(int rate) {
      this.rate = Math.max(0, rate);
    }

    boolean enabled() {
      return rate > 0;
    }

    /** 本显形包是否被抽样复核（第 1、N+1、2N+1… 个命中）。 */
    boolean sample() {
      return enabled() && counter.getAndIncrement() % rate == 0L;
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING,
          "邻近显形失败，已跳过本次处理（不影响反矿透主流程）", throwable);
    }
  }
}