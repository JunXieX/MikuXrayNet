package net.mikumc.mikuxraynet.antixray;

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
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
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
 *   <li><b>视锥剔除</b>（默认开启，120° 竖直全角，24 格内豁免）：只显形玩家视野锥内的候选坐标；
 *       默认值取「客户端 FOV 上限 + 16:9」的可视范围再放宽，因此<b>玩家看得见的方向不会被剔除</b>；
 *       两项配置都有安全下限（配得更窄会被抬升并 WARN，见 {@code AntiXrayConfig}），
 *       因为「看得见却是假的方块」是本功能最严重的失效模式（真机反馈：点击/挖掘才变回来）。
 *       水平方向按 16:9 宽高比换算（120° → 水平半角约 72°），与客户端观感一致；</li>
 *   <li><b>可见性判定</b>（默认开启，多候选点 + 遮挡位图步进射线）：先识别方块的暴露面，只在暴露面上取
 *       候选点（正对玩家的面中心 → 包围盒最近点 → 面四角），每个候选点用步进射线检测，任一条通畅即显形；
 *       候选点数由 {@code proximity.raycast.samples} 限制（<b>有效上限 5</b>，即
 *       {@code ProximitySelector.MAX_SAMPLE_POINTS}，配置给得再大也会被截断）；六面全被遮挡（完全掩埋）时一个点都不采
 *       → 直接判不可见，绝不隔着墙还原。判定走反矿透已建好的<b>遮挡位图</b>（{@link BitmapVisibility}），
 *       因此可整段在工作线程上跑（不需要任何 Bukkit API）。<b>这是唯一的可见性判定路径</b>——
 *       原生命中射线（{@code World#rayTraceBlocks}）已移除；位图缺失（罕见故障）时按「遮挡」处理
 *       （严格方向：不显形），绝不裸发。</li>
 *   <li><b>岩浆遮挡</b>（统一入口）：岩浆视觉不透明（服务端 {@code isOccluding()} 却返回 false），
 *       现由 {@code OcclusionRules} 的视觉口径并入遮挡，因此位图天然把岩浆当遮挡——
 *       刷在岩浆里的下界残骸在显形侧不会被还原，不必再单独补判。<b>水不参与</b>（透明，玩家应当能看见
 *       水下的矿物）。</li>
 * </ul>
 *
 * <p><b>缺数据的方向（与实体遮挡路径刻意相反）</b>：位图查不到 section、或该世界尚无位图时一律按「遮挡」
 * 处理（见 {@link BitmapVisibility}），即<b>本轮不显形</b>。「无数据 ≠ 通畅」——若按通畅处理，整片尚未
 * 建表的区域会被一次性还原成真实方块，等于把矿亮给透视端；而不显形只是延迟到区块建表之后，该坐标仍留在
 * 伪装清单里等待后续周期。这与隐藏侧「无数据 = 不遮挡 = 少藏」的方向故意相反：显形侧多还原一块的代价
 * 远大于少藏一个实体。
 *
 * <p><b>线程纪律</b>：位置读取、方块读取与发包都在主线程 / Folia 区域线程（非 Folia 为统一主线程任务，
 * Folia 为各玩家的区域任务）；纯计算（视锥角度 + 遮挡位图可见性）交给 {@link MikuWorkPool} 的工作线程，
 * 算完再回到玩家所属线程，只做「区块加载检查 + 读真实方块 + 发包」。位图判定（{@link BitmapVisibility}）
 * 只读无锁位图与纯浮点步进、不需要任何 Bukkit API，因而可与视锥一同被搬到工作线程。
 * <b>工作队列已满时本轮直接跳过</b>（计入 {@code ProximityStats#passesSkippedNoCapacity}，下一巡检周期
 * 继续）：绝不在玩家线程上同步跑满额判定——那会把本模块刻意搬走的整轮射线 CPU 压回主线程 / 区域线程，
 * 恰好在服务器最忙（区块集中下发把改写队列塞满）的时刻造成毛刺。显形只延迟、不丢失。
 *
 * <p><b>发包纪律</b>：显形包改用 <b>Paper 原生 API</b>（{@code Player#sendMultiBlockChange} /
 * {@code Player#sendBlockChange}）直接发出，不再自拼包结构——因此不触碰 ProtocolLib 的
 * {@code WrappedBlockData}（它内部硬查 {@code CraftMagicNumbers} 的方法契约，属「未来某版 MC
 * 一改就整体失效」的脆弱面）。
 * <b>注意</b>：原生通道同样会经过本插件的出站观察链路（{@link BlockChangeRevealListener}），
 * 它会把这个坐标从显形索引里摘掉（与真实方块变更的处理相同，都是「该坐标已发回真实方块」）。
 * 磁盘缓存不受影响：条目是否可用只由负载里的原始区块字节指纹判定，与这里无关。
 * {@code proximity.batch-reveal-sends}（默认开启）时把一个周期内通过筛选的坐标累积起来、
 * 周期末按 16³ 区块段分组、逐段 {@code sendMultiBlockChange} 发出（该 API 的一个包只承载一个段，
 * 跨段调用会被整包拒绝），某段失败时该段退化为逐坐标 {@code sendBlockChange}；
 * 关闭时逐坐标发单方块变更包（旧行为，供 A/B 与回退）。
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
  /**
   * 单周期候选窗口的硬上限：<b>取候选、评估、发包三者共用同一个数</b>。
   *
   * <p><b>为什么三者必须对齐</b>：窗口 = {@code fetchLimit(budget)}，评估与发包都以它为界。旧实现另有
   * 一个 256 的「评估上限」，于是①窗口后一半候选每周期被取出又丢弃（距离排序与堆选择白做，类注释
   * 宣称的窗口覆盖也只剩一半）；②{@code proximity.max-reveals-per-tick} 配到 &gt;256 时被静默截断到
   * 256（配置键形同虚设）。现在评估预算 = 取到的候选数：配置值在 ≤ 本值时完整生效，超出部分由
   * {@link #effectiveRevealCeiling(int)} 钳制并在启动时 WARN 一次（绝不静默改配置）。
   *
   * <p>包级可见：单测据此断言「三个上限一致」，避免日后再次漂移。
   */
  static final int MAX_CANDIDATES = 512;
  /**
   * <b>事件即时显形</b>（单次事件）的评估次数上限，与周期巡检的窗口无关。
   *
   * <p><b>为什么事件路径必须单独设上限</b>：它跑在玩家所属线程（Paper 主线程 / Folia 区域线程），候选
   * 来自「一个 section 内全部变更坐标 × 曼哈顿偏移」——半径 8 时可达数十万次判定。周期巡检的评估跑在
   * 工作线程且已受候选窗口约束，不需要也不应共用这个数。触顶即停，剩余候选交给同 tick 的后续事件或
   * 下一个巡检周期（显形只延迟、不丢失）。
   */
  private static final int MAX_INSTANT_EVALUATIONS_PER_EVENT = 256;

  /**
   * 单个 section 的邻域初筛（封包线程）允许的最大「探测」次数：每个变更坐标 × 每个曼哈顿偏移算一次。
   *
   * <p><b>为什么需要</b>：{@link #hasDisguisedNearSection} 对每个变更坐标遍历全部曼哈顿偏移
   * （半径 8 时约 833 个），且无任何次数上限；当邻域内没有伪装坐标（短路失效）时，满载的
   * {@code MULTI_BLOCK_CHANGE}（最多 4096 个坐标）会在 netty 线程上造成约 340 万次索引查找。
   * 事件显形循环有 {@link #MAX_INSTANT_EVALUATIONS_PER_EVENT} 兜底，这里同样加一个硬上限。
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
   * 坐标（整块埋住、上方是岩浆等），这批坐标就会<b>每个周期</b>重复占满窗口，窗口外的坐标一直轮不到
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
   * 读取玩家视距失败时的兜底（区块）：取原版默认 view-distance 10。
   *
   * <p>视距读取本身是纯 Bukkit 读（玩家所属线程上），失败只可能是平台异常；此时按「常见默认值」兜底，
   * 比退化成 0（完全不显形）安全——最多多发几个本可省略的显形包。
   */
  private static final int FALLBACK_VIEW_DISTANCE_CHUNKS = 10;

  /**
   * 显形回显的判定窗口（纳秒，500 毫秒）。
   *
   * <p>只需覆盖「显形包发出 → 本插件出站监听器看到该包」的极短延迟；取得过长会把「玩家紧接着挖掉
   * 刚显形的那块」误判成回显，从而留下一枚陈旧索引条目（可自愈但不必要）。
   */
  private static final long REVEAL_ECHO_WINDOW_NANOS = 500_000_000L;

  /** 每玩家回显标记条数上界：正常只需容纳「一个周期内发出的显形数」，超限整体清空（只少判一次回显）。 */
  private static final int REVEAL_ECHO_MAX_PER_PLAYER = 8192;

  /**
   * 单次巡检的发包额度（非 Folia 为全服合计，Folia 为每个玩家各自的巡检）。
   *
   * <p><b>为什么必须用 {@link AtomicInteger}</b>：非 Folia 的一次巡检里，同一份 {@code Budget} 会被
   * 多个玩家的异步视锥任务并发持有（{@code workPool.execute} 把后续发包推迟到工作线程 / 玩家线程），
   * 普通 int 字段的读写无同步、会丢失更新，导致 {@code max-reveals-per-tick} 的「全服合计」语义被突破
   * （超发若干显形包）。改为原子计数后，扣减不再丢失，「近的先发」的排序与额度上限保持一致。
   *
   * <p><b>为什么没有 refund（归还额度）</b>：批量路径在 {@code sendOne} 里「先预扣、再累积」，确实可能有
   * 「预扣但最终没发出」的坐标；但所有潜在归还点都在 {@link #flushBatch} 内、即发包循环结束之后，而
   * {@code Budget} 是每周期新建、用后即弃的临时对象——归还与否不会改变本轮任何后续发包；事件路径的
   * {@code TickQuota} 撤销用的是「实际发出计数」而非本对象，因此归还属无行为效果的死逻辑，已移除。
   * 预扣保持「保守占用本周期额度」的原语义。
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

    /** 扣减一个额度（跨线程安全的原子扣减，不会丢失更新；本周期内不归还，见类注释）。 */
    void decrement() {
      remaining.decrementAndGet();
    }
  }

  /**
   * 工作线程算出的显形计划：仅含通过筛选（视锥剔除 + 位图可见性）的候选三元组。
   *
   * <p>{@link #rayCulled} 为并入本步的位图可见性剔除数。
   */
  private static final class Plan {

    private final int[] coordinates;
    /**
     * 有效候选条数（每条 3 个 int）。
     *
     * <p><b>为什么显式保存而不是用 {@code coordinates.length / 3}</b>：{@code computePlan} 直接在
     * 「取候选时分配的那个 int[]」上原地压缩（保留项前移，写下标恒 ≤ 读下标），因此数组可能比实际条数
     * 长；尾段是已评估过的陈旧值，只允许由本字段限定长度、绝不读取。
     */
    private final int count;
    /** 本次取到的候选总数与其中被视锥剔除的数量（仅供一次性诊断日志使用）。 */
    private final int candidateCount;
    private final int frustumCulled;
    /** 并入本步的位图可见性剔除数（未并入时为 0）。 */
    private final int rayCulled;

    private Plan(int[] coordinates, int count, int candidateCount, int frustumCulled, int rayCulled) {
      this.coordinates = coordinates;
      this.count = count;
      this.candidateCount = candidateCount;
      this.frustumCulled = frustumCulled;
      this.rayCulled = rayCulled;
    }

    private int count() {
      return count;
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
  // 注意：本类不再持有 AntiXrayConfig 字段——入口门闸只看世界黑名单（见 shouldRevealInWorld），
  // 构造参数只用于一次性快照 proximity / instant / batchRevealSends。
  private final AntiXrayConfig.Proximity proximity;
  private final AntiXrayConfig.InstantReveal instant;
  /** 是否把同一周期的显形合并成一个 Paper 原生多方块变更包发出（{@code proximity.batch-reveal-sends}）。 */
  private final boolean batchRevealSends;
  private final ObfuscatedChunkIndex chunkIndex;
  private final RevealedSet revealedSet;
  private final ProximityStats stats;
  private final BypassRegistry bypassRegistry;
  private final MikuWorkPool workPool;
  /**
   * 遮挡位图注册表（与实体剔除共用同一份）。可见性判定恒走位图（{@link BitmapVisibility}，可搬离玩家
   * 线程）；{@code null} 表示位图不可用（罕见故障），此时按「遮挡」处理（严格方向：不显形）。
   */
  private final OcclusionStores occlusionStores;
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
  /** 「工作线程无容量导致跳过巡检」只提示一次（CAS 抢占），避免队列持续打满时每周期刷屏。 */
  private final AtomicBoolean noCapacityWarned = new AtomicBoolean();
  /** 事件显形的每玩家每 tick 限额（默认 16，防爆刷；见 {@link TickQuota}）。 */
  private final TickQuota instantQuota = new TickQuota();
  /** 视野变化增量巡检的每玩家每 tick 发包限额（与事件显形分开，避免互相挤占）。 */
  private final TickQuota viewChangeQuota = new TickQuota();
  /** 区块下发即显形的每玩家每 tick 发包限额（与事件显形分开，避免区块集中下发吃光即时显形额度）。 */
  private final TickQuota chunkDeliveryQuota = new TickQuota();
  /** 视野变化触发状态（按玩家；只在其所属线程读写，退出/停用时整体清理）。 */
  private final ConcurrentHashMap<UUID, ViewMark> viewMarks = new ConcurrentHashMap<>();
  /**
   * 开掘预显形的去重记号：{@code long[2] = {坐标打包, tick}}，按玩家保存。
   *
   * <p>{@code BlockDamageEvent} 在持续挖掘同一格时会重复触发；不去重就会每 tick 重复做一遍
   * 「6 邻索引查询 + 射线判定」。窗口 {@value #DIG_DEDUP_TICKS} tick 内同一格只放行一次。
   */
  private final ConcurrentHashMap<UUID, long[]> digMarks = new ConcurrentHashMap<>();
  /** 过度显形抽样器（1/N，只计数不改行为；N=0 关闭）。 */
  private final OverRevealSampler overRevealSampler;

  private volatile ScheduledTask globalTask;
  /**
   * 停用闸：{@code stop()} 置位后，任何在途的调度（尤其是 Folia 上 {@code start()} 的遍历期间、
   * 或玩家加入事件的并发回调）都必须立即放弃，否则一条「停用后才建起来」的区域任务无人取消、
   * 会空转到实体退役并钉住本实例（热重载逐代累积）。
   */
  private volatile boolean stopping;

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
   * <p><b>有界</b>：<b>记号</b>在「被消费」「同坐标再次被查询」或巡检周期的 {@link #pruneExpiredEchoes()}
   * 里回收，每玩家另设条数上界。内层 map 本身只在玩家退出（{@link #onQuit}）与插件停用
   * （{@link #stop(boolean)} 的 clearIndexes 路径）整体释放——<b>周期清理刻意不摘除空 map</b>，理由见
   * {@link #pruneExpiredEchoes()}（摘了会与 {@link #markOurReveal} 的「取引用 → put」形成孤儿记号窗口）。
   * 因此驻留量上界是「在线玩家数 × 一个空 ConcurrentHashMap」，可忽略；窗口到期却始终没被消费的
   * 记号只可能来自「发包未被出站监听器观察到」的异常路径（例如停用瞬间的在途任务），周期清理保证
   * 它不会滞留。热重载会重建本类实例，旧实例的整张表随实例一起回收。
   */
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<EchoKey, Long>> revealEcho =
      new ConcurrentHashMap<>();

  /**
   * 回显标记的键：世界名 + 方块坐标（record 自带 equals/hashCode，避免手工位打包的碰撞风险）。
   *
   * <p>包级可见：{@link #pruneExpiredEchoes(ConcurrentHashMap, long)} 的离线单测需要构造键。
   */
  record EchoKey(String world, int x, int y, int z) {
  }

  /**
   * <p><b>为什么不需要 ProtocolLib 协议管理器</b>：显形发包已改用 Paper 原生 API（见类注释「发包纪律」），
   * 封包通道由本插件其它模块继续使用，本类不读取它——因此不留无用形参。
   *
   * @param occlusionStores 遮挡位图注册表（与实体剔除共用）；{@code null} 表示位图不可用（罕见故障），
   *                        此时 {@link BitmapVisibility} 按「遮挡」处理（严格方向：不显形）
   */
  public ProximityRevealer(Plugin plugin, AntiXrayConfig config,
      ObfuscatedChunkIndex chunkIndex, RevealedSet revealedSet, ProximityStats stats,
      BypassRegistry bypassRegistry, MikuWorkPool workPool, OcclusionStores occlusionStores) {
    this.plugin = plugin;
    this.proximity = config.proximity();
    this.instant = proximity.instantReveal();
    this.batchRevealSends = proximity.batchRevealSends();
    this.overRevealSampler = new OverRevealSampler(Math.max(0, proximity.overRevealSampling()));
    this.chunkIndex = chunkIndex;
    this.revealedSet = revealedSet;
    this.stats = stats;
    this.bypassRegistry = bypassRegistry;
    this.workPool = workPool;
    this.occlusionStores = occlusionStores;
    // 过期清理窗口 = 巡检周期 × EXPIRE_EVERY_PASSES（tick→纳秒：1 tick = 50ms）。
    // 对巡检周期做上限钳制（≤1 小时）再相乘，避免极端配置把乘法推到溢出。
    long ticks = Math.max(1L, Math.min(72_000L, proximity.intervalTicks()));
    this.expirePeriodNanos = ticks * 50_000_000L * EXPIRE_EVERY_PASSES;
  }

  /** 启动巡检：非 Folia 为统一主线程任务；Folia 为各玩家的区域任务（句柄与分片计数按 UUID 保存，见 entityScans）。 */
  public void start() {
    stopping = false;
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
    // 单周期实际生效的发包上限 = min(配置, 候选窗口)；配置超过窗口时只提示一次，绝不静默改配置，
    // 也绝不为了兑现一个配得过大的值去放大取候选/评估量（那会把射线 CPU 无上限地推高）。
    int configuredReveals = proximity.maxRevealsPerTick();
    int effectiveReveals = effectiveRevealCeiling(configuredReveals);
    if (configuredReveals > effectiveReveals) {
      plugin.getLogger().warning("邻近显形：proximity.max-reveals-per-tick=" + configuredReveals
          + " 超过候选窗口上限 " + MAX_CANDIDATES + "，单周期实际按 " + effectiveReveals
          + " 生效（窗口 = min(上限, 额度 × " + CANDIDATE_OVERSAMPLE + ")）");
    }
    plugin.getLogger().fine("邻近显形已启用：半径 " + (proximity.distanceChunks() <= 0
        ? "跟随服务器视距" : proximity.distanceChunks() + " 区块")
        + "（运行时再按玩家有效视距钳制），周期 " + interval
        + " tick，单次上限 " + effectiveReveals + " 个（配置 " + configuredReveals + "）；视锥 "
        + (proximity.frustumEnabled() ? proximity.frustumFov() + "°（最小距离 "
            + proximity.frustumMinDistance() + " 格内豁免）" : "已关闭")
        + "，可见性判定 " + visibilitySummary()
        + "，显形发包 " + (batchRevealSends ? "已合并（Paper 原生多方块变更包）" : "逐坐标单包"));
  }

  /** 启动摘要里的「可见性判定」一句话。 */
  private String visibilitySummary() {
    if (!proximity.raycastEnabled()) {
      return "已关闭";
    }
    return "已开启（遮挡位图步进射线 + 暴露面上至多 " + proximity.raycastSamples() + " 个候选点，工作线程执行）";
  }

  /** 停用：注销任务与事件监听，并清空两个显形索引（不留副作用）。 */
  public void stop() {
    stop(true);
  }

  /**
   * 停止巡检并注销事件监听。
   *
   * <p><b>{@code clearIndexes} 必须区分「停用」与「热重载」</b>：{@code chunkIndex} 与
   * {@code revealedSet} 是<b>与改写链路共享</b>的结构，且只在该区块封包被下发时写入
   * （{@code ProtocolLibAsyncListener#writeBack}）。热重载不会让服务端重发已加载区块，因此若在
   * reload 时清空它们，这些区块里的伪装坐标就永久失联：玩家眼前本该被「邻近显形」还原的矿物会一直
   * 显示成假方块，直到该区块因走远/重登/切维度被重新下发。故热重载路径必须传 {@code false}——
   * 只停任务、保留数据；只有插件停用（或配置把 proximity 关掉）时才清空以释放内存。
   *
   * @param clearIndexes 是否同时清空「按区块共享的伪装索引 / 按玩家的已显形集合 / 回显记号」
   */
  public void stop(boolean clearIndexes) {
    // 先置闸再取消：在途的 scheduleEntityTask（Folia start() 遍历 / 并发 onJoin）会在入表后复查并自行取消，
    // 否则该句柄无人知晓、任务空转到实体退役并钉住本实例。
    stopping = true;
    if (globalTask != null) {
      globalTask.cancel();
      globalTask = null;
    }
    // Folia 每玩家任务句柄全部取消：句柄若被丢弃，区域任务会在停用后继续跑到实体退役（泄漏）
    for (EntityScan scan : entityScans.values()) {
      cancelQuietly(scan.task());
    }
    entityScans.clear();
    digMarks.clear();
    viewMarks.clear();
    pendingChunkReveals.clear();
    HandlerList.unregisterAll(this);
    if (clearIndexes) {
      chunkIndex.clear();
      revealedSet.clear();
      revealEcho.clear();
    }
  }

  /** 在玩家所属线程上启动周期巡检并保存句柄与分片计数（调度失败则无句柄可存）。 */
  private void scheduleEntityTask(Player player, long interval) {
    if (stopping) {
      return;
    }
    ScheduledTask task = Schedulers.repeatOnEntity(plugin, player, interval, interval, () -> pass(player));
    if (task == null) {
      return;
    }
    // 计数随句柄一起建：任务首次执行至少在一个周期之后，故 pass() 读到的一定是本条目
    EntityScan mine = new EntityScan(task, new AtomicLong());
    EntityScan previous = entityScans.put(player.getUniqueId(), mine);
    if (previous != null) {
      // 同一玩家重复调度（重复的加入事件等）：旧句柄留着就会空转到实体退役
      cancelQuietly(previous.task());
    }
    if (stopping) {
      // 入账后复查停用闸：stop() 可能已清完表，这条晚到的句柄不会有人取消。
      // 只按「本条目」精确移除并取消，绝不 remove(key) 后盲取消——那会把并发中
      // 另一个线程刚为同一玩家换上的新句柄一并摘掉（随后它既无人取消、也不再被 pass() 读到）。
      if (entityScans.remove(player.getUniqueId(), mine)) {
        cancelQuietly(task);
      }
    }
  }

  private static void cancelQuietly(ScheduledTask task) {
    try {
      task.cancel();
    } catch (Throwable ignored) {
      // 任务可能已随实体退役结束，取消失败可忽略
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
    chunkDeliveryQuota.clear(event.getPlayer().getUniqueId());
    digMarks.remove(event.getPlayer().getUniqueId());
    viewChangeQuota.clear(event.getPlayer().getUniqueId());
    viewMarks.remove(event.getPlayer().getUniqueId());
    pendingChunkReveals.remove(event.getPlayer().getUniqueId());
    // 离线玩家的回显记号一并释放（否则会滞留到窗口过期，白白占内存）
    revealEcho.remove(event.getPlayer().getUniqueId());
  }

  /**
   * 区块卸载：该区块的伪装清单与各玩家的已显形标记一并失效。
   *
   * <p>区块重新加载时会被重新编译（并重新进入伪装清单），此时旧清单里的坐标已不可信；
   * 已显形标记同理必须清掉，否则玩家会在「区块重载后早已显示为伪装」的位置漏显形。
   * 本回调在主线程 / Folia 区域线程执行，只做两个索引的失效，不读世界内容、不做任何计算。
   *
   * <p><b>与 {@code AntiXrayRuntime#invalidateChunk} 的分工</b>：主类那条监听负责「遮挡位图 + 伪装清单」
   * （它对清单是否真的被摘除有判断，命中才顺带清已显形标记）；本回调负责「伪装清单 + 已显形标记」这一对，
   * 且<b>无条件</b>清标记——因为清单即使本来就不存在，标记也必须失效：区块卸载意味着该区块已不在任何玩家
   * 客户端里，任何残留的「已显形」记录都只会在重载后被当成「这块已经显形过」而漏发显形包。
   * 两处都做是幂等的（{@code clearChunk} 对不存在的键直接返回），不重复扣减计数。
   */
  @EventHandler(ignoreCancelled = true)
  public void onChunkUnload(ChunkUnloadEvent event) {
    try {
      Chunk chunk = event.getChunk();
      String worldName = chunk.getWorld().getName();
      chunkIndex.invalidateChunk(worldName, chunk.getX(), chunk.getZ());
      revealedSet.clearChunk(worldName, chunk.getX(), chunk.getZ());
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

  /**
   * 全服巡检（非 Folia：主线程，遍历在线玩家）。
   *
   * <p><b>额度是「每个玩家各自独享」的，不是全服共享</b>（旧行为见下方说明）。
   *
   * <p><b>为什么必须改成独享</b>：旧实现只在循环外建一个 {@code Budget}，全服共用
   * {@code max-reveals-per-tick}（默认 512）。50 人在线时每人每周期实际只摊到约 10 个额度，
   * 且循环里一旦 {@code remaining <= 0} 就 <b>直接 return，把后面还没遍历到的玩家整个跳过</b>——
   * 他们这一轮一个显形都拿不到。虽然候选按距离排序、分片也在轮转，但「谁排在后面谁吃亏」
   * 不是硬保证：真机表现就是「同样的矿，有的玩家走近立刻还原，有的要等好几轮」。
   *
   * <p>改成每玩家独享后，每个在线玩家每周期都能拿到完整的 {@code max-reveals-per-tick} 额度。
   * 代价是理论总发包上限随人数线性增长（50 人 × 512 = 25,600/周期），但实际上限由候选数决定：
   * 单玩家每周期候选已被 {@link #fetchLimit} 截断到至多 {@link #MAX_CANDIDATES}，且只有真正
   * 通过视锥 + 位图可见性判定的坐标才会发包，绝大多数周期远达不到额度。
   *
   * <p>若服主确实需要「全服总量」上限，应通过降低 {@code max-reveals-per-tick} 来实现——
   * 那是明确的、可预期的旋钮；而旧行为让额度含义随在线人数漂移，反而不可控。
   */
  private void passAll() {
    if (stopping) {
      return;
    }
    warnIfCapacityExceeded();
    maybeExpire();
    // 本轮看哪一片：同一轮里所有玩家看同一片（分片轮转的节奏只与巡检次数有关，见 SCAN_SHARDS）。
    // 按「巡检轮次」推进，而不是按墙钟时间取模——服务器掉 tick 时墙钟会跑在实际巡检前面，
    // 反而会让一部分分片被整轮跳过。
    int shard = shardFor(globalPasses.incrementAndGet());
    // 每个玩家各自一份额度：不再因「前面的玩家用光了」而跳过后面的玩家。
    for (Player player : Bukkit.getOnlinePlayers()) {
      try {
        reveal(player, new Budget(limit()), shard);
      } catch (Throwable throwable) {
        logThrottled(throwable);
      }
    }
  }

  /** 单个玩家的巡检（Folia：在玩家所属区域线程执行）。 */
  private void pass(Player player) {
    if (stopping) {
      return;
    }
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

  /**
   * 邻近显形是否应作用于该世界：<b>只排除世界黑名单</b>。
   *
   * <p><b>为什么不再叠加 {@code AntiXrayConfig#antiXrayAppliesTo}（总开关 + world-overrides）</b>：
   * 候选本来就只来自「我们写过伪装索引」的区块——索引只会由改写链路登记，因此总开关关闭 / 某世界被
   * 覆盖段关闭时索引为空，<b>天然零发包</b>。反过来，总开关（或某世界的覆盖段）在热重载后被关闭时，
   * 服务端<b>不会重发</b>已加载的区块，客户端手里仍是伪装后的假矿；此时保留索引与巡检，正是为了让这些
   * 假矿还能被邻近显形还原——否则玩家要等区块重发才看得到真方块，属「该显形不显形」的硬红线。
   * 黑名单世界仍一律不显形（名单内的世界不做任何反矿透工作）。
   *
   * <p>这是周期巡检与两条事件即时显形入口共用的唯一门闸（WorldBlacklistConfig 为无锁静态查询）。
   */
  boolean shouldRevealInWorld(String worldName) {
    return worldName != null && !WorldBlacklistConfig.isBlacklistedNow(worldName);
  }

  /** 主线程 / 区域线程：局部扫描取候选 → （可选）工作线程筛选 → 读真实方块 → 发包 → 标记已显形。 */
  private void reveal(Player player, Budget budget, int shard) {
    if (!player.isOnline()
        || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
      return;
    }

    World world = player.getWorld();
    String worldName = world.getName();
    // 只排除黑名单世界：总开关关闭后仍保留索引与巡检，好让已下发到客户端的假矿还能被还原
    //（索引为空时天然零发包，见 shouldRevealInWorld 的说明）。
    if (!shouldRevealInWorld(worldName)) {
      return;
    }

    Location location = player.getLocation();
    ProximityScanner.Tally scanTally = new ProximityScanner.Tally();
    // 显形半径按「区块」计，并钳到该玩家的有效视距（服务器视距与客户端自报视距取小）：
    // 显形包只在客户端已加载该区块时才会被应用，越界的包会被客户端丢弃。见 {@link #effectiveRevealChunks}。
    int revealChunks = effectiveRevealChunks(proximity.distanceChunks(),
        playerViewDistance(player), playerClientViewDistance(player));
    double maxDistance = revealChunks * 16.0D;
    List<ObfuscatedChunkIndex.Position> candidates = ProximityScanner.candidates(chunkIndex,
        revealedSet, player.getUniqueId(), worldName, location.getBlockX(), location.getBlockY(),
        location.getBlockZ(), maxDistance, fetchLimit(budget.remaining()), shard,
        SCAN_SHARDS, scanTally);
    if (candidates.isEmpty()) {
      return;
    }

    boolean frustum = proximity.frustumEnabled();
    boolean visibility = proximity.raycastEnabled();
    // 可见性判定唯一走遮挡位图（原生命中射线已移除）。位图缺失（罕见故障）时 BitmapVisibility 按「遮挡」
    // 处理（严格方向：不显形），绝不裸发——「无数据 ≠ 通畅」是显形侧的既定安全方向。
    if (!frustum && !visibility) {
      // 两项筛选都关闭：无任何可做的判定，直接发包
      sendCandidates(player, world, candidates, null, null, budget, scanTally, false);
      return;
    }

    // 只把纯值交给工作线程；这里的 Player 引用仅供「交回所属线程」调度使用，工作线程不对它做任何调用
    ProximitySelector.Eye eye = eyeOf(player);

    int[] coordinates = flatten(candidates);

    if (!canOffloadPlanning(workPool)) {
      // 队列已满 / 未装配工作池：本轮直接跳过（计入统计），候选仍在伪装索引里，下一巡检周期会重新取到。
      // 为什么不在当前线程同步算完：这里的候选筛选最多要做 MAX_CANDIDATES 次「暴露面 + 多候选点射线」，
      // 正是本模块刻意搬到工作线程的那笔 CPU；退化到主线程 / 区域线程，等于在「改写队列已塞满」这个
      // 服务器最忙的时刻把它整笔还回去。显形只延迟一个周期、绝不丢失——宁可延迟也不能阻塞。
      skipPassBecauseNoCapacity();
      return;
    }

    try {
      workPool.execute(() -> {
        Plan plan = computePlan(eye, coordinates, worldName, visibility);
        // 读方块与发包必须回到玩家所属线程。世界在候选筛选时已捕获（见 reveal 的 world 局部量），
        // 这里绝不再取 player.getWorld()：异步回调期间玩家可能已切换世界，重取会让「旧世界的候选坐标」
        // 配上「新世界的 ChunkKey」（污染已显形集合）并发出冗余包。改用捕获的世界，且校验玩家仍在同一
        // 世界——跨世界则放弃本轮（fail-open，绝不误发/误标）。
        Schedulers.onEntity(plugin, player, () -> {
          try {
            if (player.isOnline() && player.getWorld() == world) {
              sendCandidates(player, world, null, plan, eye, budget, scanTally, visibility);
            }
          } catch (Throwable throwable) {
            logThrottled(throwable);
          }
        });
      });
    } catch (Throwable throwable) {
      // 入队失败（线程池关闭 / 停用中）：与「无容量」同一处置——本轮跳过，绝不在玩家线程同步算完。
      // 此时旧实例本就不会再发包（sendCandidates 入口的 stopping 闸），跳过不丢任何新实例的显形。
      logThrottled(throwable);
      skipPassBecauseNoCapacity();
    }
  }

  /** 本轮是否有可用的工作线程来执行纯计算筛选（包级可见，便于离线单测）。 */
  static boolean canOffloadPlanning(MikuWorkPool workPool) {
    // 用「为改写预留后的余量」准入：显形规划不值得把关键路径挤成原包直发（反矿透失效）
    return workPool != null && workPool.hasSpareCapacity();
  }

  /** 无可用工作线程时跳过本轮巡检：计入统计 + 一次性提示（正常负载下只会是队列瞬时打满）。 */
  private void skipPassBecauseNoCapacity() {
    stats.passesSkippedNoCapacity.increment();
    if (noCapacityWarned.compareAndSet(false, true) && plugin != null) {
      plugin.getLogger().fine("邻近显形：工作线程队列持续无容量，本轮巡检已跳过（显形只延迟，不丢失）");
    }
  }

  /**
   * 工作线程：候选筛选（纯计算，不触碰任何 Bukkit API）——视锥角度 + 遮挡位图可见性。
   * 「队列无容量就在玩家线程同步算」的保底路径已移除（见 {@link #reveal}），因此本方法只会在工作线程
   * （或测试线程）上运行。
   *
   * <p><b>为什么可见性也能在这里做</b>：位图判定只读 {@link OcclusionStores}（无锁读）与纯浮点步进
   * （见 {@link BitmapVisibility}），不是 Bukkit API——因此可与视锥一同落到工作线程，把本功能最重的一笔
   * CPU 从玩家线程搬走。
   *
   * <p><b>缺数据即不可见</b>：位图查不到 section / 该世界尚无位图时按「遮挡」处理，候选本轮被剔除、
   * 留在伪装清单里等下一个周期——「无数据 ≠ 通畅」，否则整片未建表的区域会被一次性还原成真实方块。
   *
   * @param worldName  候选所属世界名（位图按世界分域，须与写入侧口径一致）
   * @param visibility 本轮是否做可见性判定（= {@code proximity.raycast.enabled}，由 {@link #reveal}
   *                   传入，保证整轮取值一致）
   */
  private Plan computePlan(ProximitySelector.Eye eye, int[] coordinates, String worldName,
      boolean visibility) {
    boolean frustum = proximity.frustumEnabled();
    double minDistance = proximity.frustumMinDistance();
    double fov = proximity.frustumFov();
    int maxPoints = proximity.raycastSamples();
    // 每线程复用的位图判定器（内部 OcclusionRay 游标不可跨线程共享）：这里只取当前线程那一份。
    BitmapVisibility bitmap = visibility ? BitmapVisibility.local() : null;

    int count = coordinates.length / 3;
    int keptCount = 0;
    int frustumCulled = 0;
    int rayCulled = 0;
    // 评估额度 = 取到的候选数（窗口已由 fetchLimit 封顶 MAX_CANDIDATES）：不再另设更小的上限。
    // 旧实现另设 256，会让窗口后一半候选「取出即丢」，并使 max-reveals-per-tick 在 >256 时静默失效。
    // 原地压缩：保留项写回 coordinates 的前段，写下标 keptCount 恒 ≤ 读下标 i，因此不会覆盖尚未读取的
    // 候选；省掉一份与窗口等长的 int[]（默认 512×3），Plan.count() 限定有效长度。
    for (int i = 0; i < count; i++) {
      int x = coordinates[i * 3];
      int y = coordinates[i * 3 + 1];
      int z = coordinates[i * 3 + 2];

      if (frustum && !ProximitySelector.withinFrustum(eye, x, y, z, minDistance, fov)) {
        stats.revealsFrustumCulled.increment();
        frustumCulled++;
        continue;
      }

      if (bitmap != null
          && !bitmap.isVisible(eye, occlusionStores, worldName, maxPoints, x, y, z)) {
        stats.revealsRayCulled.increment();
        // 上界式归因：判定期间触到过「无数据」section 的剔除单列一笔，使「严格缺数据规则是否在伤人」
        // 从日志可判（见 ProximityStats#revealsRayCulledMissingData）；混在总数里无法区分。
        if (bitmap.missingDataConsulted()) {
          stats.revealsRayCulledMissingData.increment();
        }
        rayCulled++;
        continue;
      }

      coordinates[keptCount * 3] = x;
      coordinates[keptCount * 3 + 1] = y;
      coordinates[keptCount * 3 + 2] = z;
      keptCount++;
    }

    return new Plan(coordinates, keptCount, count, frustumCulled, rayCulled);
  }

  /**
   * 逐个候选做可见性判定 → 发包 → 标记已显形；{@code eye} 为 {@code null} 时不启用可见性判定。
   *
   * @param visibilityInPlan 可见性是否已在 {@link #computePlan} 里按遮挡位图判完（true 时 sendOne 不再
   *                         重复判定；false 时 sendOne 在当前线程用位图判定，如事件即时显形路径）
   */
  private void sendCandidates(Player player, World world,
      List<ObfuscatedChunkIndex.Position> candidates, Plan plan, ProximitySelector.Eye eye,
      Budget budget, ProximityScanner.Tally scanTally, boolean visibilityInPlan) {
    if (stopping) {
      // 停用 / 热重载已开始：绝不继续发包。本方法可能在异步工作池算完 computePlan 后才被回投到玩家线程
      // 执行（见 reveal 的 Schedulers.onEntity 回调），此时旧实例的索引已被 stop() 清空，
      // 若仍按快照里的候选发包，会：① 停用后继续向客户端发送显形包；② 往已清空的 revealedSet
      // 重新写入标记（内存残留）。停机后未显形的坐标由新实例的下一个巡检周期补上。
      return;
    }
    PassTally tally = new PassTally();
    tally.chunksScanned = scanTally.chunksScanned;
    tally.chunksSkipped = scanTally.chunksSkipped;
    tally.chunksDeferred = scanTally.chunksDeferred;
    tally.positionsEvaluated = scanTally.positionsEvaluated;
    // 位图可见性的剔除发生在 computePlan（见其 rayCulled）；未走计划的路径在 sendOne 里累计
    tally.rayCulled = plan != null ? plan.rayCulled : 0;
    int candidateCount = plan != null
        ? plan.candidateCount
        : (candidates == null ? 0 : candidates.size());
    int frustumCulled = plan != null ? plan.frustumCulled : 0;
    RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
    // 候选量已被取候选窗口（fetchLimit ≤ MAX_CANDIDATES）封顶，这里只需发包额度闸门；
    // 旧实现再套一层 256 的评估上限，等于把配置的 max-reveals-per-tick 静默砍半。
    if (candidates != null) {
      for (ObfuscatedChunkIndex.Position position : candidates) {
        if (budget.remaining() <= 0) {
          break;
        }
        sendOne(player, world, position.x(), position.y(), position.z(), eye, budget, tally, batch,
            visibilityInPlan);
      }
      flushBatch(player, batch, tally);
      logFirstRevealDiagnostic(candidateCount, frustumCulled, tally);
      return;
    }
    if (plan == null) {
      return;
    }

    int count = plan.count();
    for (int i = 0; i < count; i++) {
      if (budget.remaining() <= 0) {
        break;
      }
      sendOne(player, world, plan.coordinates[i * 3], plan.coordinates[i * 3 + 1],
          plan.coordinates[i * 3 + 2], eye, budget, tally, batch, visibilityInPlan);
    }
    flushBatch(player, batch, tally);
    logFirstRevealDiagnostic(candidateCount, frustumCulled, tally);
  }

  /**
   * 当前线程是否拥有该区块（Folia 区域化线程模型的访问前置条件）。
   *
   * <p>非 Folia 恒为 {@code true}（单主线程，不存在跨 region 问题）；判定本身抛异常时按
   * <b>「不拥有」</b>处理——宁可少显形一个坐标，也绝不跨 region 读世界（那会触发 TickThread 校验）。
   */
  private static boolean ownsChunk(World world, int chunkX, int chunkZ) {
    if (!PlatformSupport.isFolia()) {
      return true;
    }
    try {
      return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    } catch (Throwable throwable) {
      return false;
    }
  }

  /**
   * 单个坐标：区块未加载则跳过、不可见则跳过，否则读真实方块；批量模式累积、否则立即发包并标记。
   *
   * @param visibilityInPlan 可见性是否已在工作线程按遮挡位图判完（见 {@link #sendCandidates}）；
   *                         为 true 时此处不再重复判定；为 false 时在当前线程用位图判定
   *                         （事件即时显形路径；位图缺失时按「遮挡」处理，严格方向：不显形）
   */
  private void sendOne(Player player, World world, int x, int y, int z, ProximitySelector.Eye eye,
      Budget budget, PassTally tally, RevealBatch batch, boolean visibilityInPlan) {
    // Folia 线程纪律：本方法运行在<b>玩家所属</b> region 线程上，但候选坐标可能远至显形半径
    // （默认跟随服务器视距，最多 32 区块）——那些区块可能属于另一个 region。跨 region 调
    // world.isChunkLoaded / world.getBlockData 会触发 Folia 的 TickThread 校验（先打一条 ERROR 日志
    // 再抛异常）：异常虽会被下面的 catch 吞掉，但后果是「玩家看得见的假方块永远不会被还原」
    // （少显形 = 安全方向错误），且每次抓取刷一条 ERROR。与 NeighborChunkProvider 同一处理口径：
    // 先问归属，不属于本 region 就跳过——该坐标留在索引里，下一轮巡检在本 region 接管后仍会补上。
    if (!ownsChunk(world, x >> 4, z >> 4)) {
      stats.revealsSkipped.increment();
      return;
    }
    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
      // 区块未加载：本次跳过，坐标留在索引里等玩家真正靠近
      stats.revealsSkipped.increment();
      return;
    }

    // 可见性判定（事件即时显形等未走 computePlan 的路径）：在当前线程用遮挡位图判定。位图缺失时按
    // 「遮挡」处理（严格方向：不显形），绝不裸发——「无数据 ≠ 通畅」。
    if (eye != null && proximity.raycastEnabled() && !visibilityInPlan) {
      BitmapVisibility bitmap = BitmapVisibility.local();
      if (!bitmap.isVisible(eye, occlusionStores, world.getName(), proximity.raycastSamples(),
          x, y, z)) {
        stats.revealsRayCulled.increment();
        // 与周期路径同一归因口径：因「缺数据」而剔除的候选单列一笔。
        // 事件显形正是「玩家挖开面前方块」那条最该立刻还原的路径，缺数据在此静默剔除的后果
        // 比周期路径更直接可感（眼前假矿不还原）。只统计不改行为，让面板能自证是否踩到。
        if (bitmap.missingDataConsulted()) {
          stats.revealsRayCulledMissingData.increment();
        }
        tally.rayCulled++;
        return;
      }
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

    // 真实方块状态必须在玩家所属线程读取（本方法即运行在该线程上）。
    // 用 World#getBlockData(x,y,z) 直取，避免 getBlockAt(...) 为每次显形多分配一个即用即丢的 Block 包装对象。
    BlockData data;
    try {
      data = world.getBlockData(x, y, z);
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
    // 回显记号必须在<b>发包之前</b>写：显形包离开 send* 时就已经进了服务端发送队列，出站监听器可能在
    // 本方法返回前就在另一线程上看完它——若等 send 返回再补记号，那次「回显」会被当成真实变更，
    // 把该坐标从<b>全服共享</b>的伪装索引里摘掉（于是其他玩家再也无法被主动显形该坐标）。
    // 与上方 revealed 标记同一条「先标记、后动作」纪律。代价：发包失败时会留下一条短寿命记号，
    // 需在下方回滚分支里撤销（否则同坐标 ≤2 秒内的真实变更会被误判为回显而漏摘索引）。
    long echoExpiry = markOurReveal(playerId, worldName, x, y, z);
    if (send.getAsBoolean()) {
      return true;
    }
    unmarkOurReveal(playerId, worldName, x, y, z, echoExpiry);
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
   *
   * @return 本次写入的过期时刻（纳秒），调用方必须原样交给 {@link #unmarkOurReveal} 做条件删除；
   *         入参为空或写入异常时返回 {@code 0}——它不是合法的过期时刻（早于任何当前时刻，
   *         即便残留也会被 {@link #consumeOurRevealEcho} 判为已过期），调用方据此跳过撤销
   */
  private long markOurReveal(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      return 0L;
    }
    try {
      ConcurrentHashMap<EchoKey, Long> perPlayer =
          revealEcho.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
      long now = System.nanoTime();
      if (perPlayer.size() >= REVEAL_ECHO_MAX_PER_PLAYER) {
        // 先只清「已过窗口」的记号：满表绝大多数是这些陈旧项，清完仍满才整体清空。
        // 不先清理就直接 clear() 会把仍在 2 秒窗口内的有效记号一并抹掉——那些在途显形包随后会被当成
        // 真实变更，从全服共享的伪装索引里摘掉（其它玩家只能等区块重发），正是本机制要防的失效。
        pruneExpiredEchoes(perPlayer, now);
        if (perPlayer.size() >= REVEAL_ECHO_MAX_PER_PLAYER) {
          perPlayer.clear();
        }
      }
      long expiry = now + REVEAL_ECHO_WINDOW_NANOS;
      perPlayer.put(new EchoKey(worldName, x, y, z), expiry);
      return expiry;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return 0L;
    }
  }

  /**
   * 撤销一条回显记号（发包失败时调用）。
   *
   * <p><b>只删「值仍是本次写入的那枚记号」</b>（{@code remove(key, value)} 条件删除）：同一坐标在极短
   * 窗口内可能被另一条路径重新标记过——它写入的是新的过期时刻，代表一次<b>正在生效</b>的回显保护。
   * 若这里无条件 {@code remove(key)}，就会把那枚新记号一并抹掉：该坐标随后的回显会被
   * {@link BlockChangeRevealListener} 当成真实变更，从<b>按区块全服共享</b>的伪装索引里摘除，
   * 此后该坐标再也不会被主动显形（该玩家与其它玩家一起漏显形，直到区块重载）。
   * 条件删除保证「谁写的谁撤销」：值已被改写就说明有更新的有效记号，绝不越权删除。
   *
   * @param expiry {@link #markOurReveal} 返回的过期时刻；{@code 0} 表示当时未写入，本方法直接返回
   */
  private void unmarkOurReveal(UUID playerId, String worldName, int x, int y, int z, long expiry) {
    if (playerId == null || worldName == null || expiry == 0L) {
      return;
    }
    try {
      ConcurrentHashMap<EchoKey, Long> perPlayer = revealEcho.get(playerId);
      if (perPlayer != null) {
        // 两参 remove 先比较值再删除（值为 Long，按 equals 比较）：期间已被改写则保留那枚新记号
        perPlayer.remove(new EchoKey(worldName, x, y, z), expiry);
      }
    } catch (Throwable throwable) {
      // 撤销失败只会多留一条 ≤2 秒的记号（极小概率漏摘一次索引），不影响正确性
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
   * 合并包的「预写回显记号 + 发包 + 失败整批撤销」步（包级可见，便于离线单测）。
   *
   * <p>调用方已在 {@link #claimForSend} 里完成原子复核与「已显形」标记。这里先为 {@code fresh} 这批
   * 坐标写好回显记号<b>再</b>发出（记号必须先于发包：包一离开 send* 就进了服务端发送队列，出站监听器
   * 可能在本次调用返回前就已判定，详见方法内注释）；发送失败则撤销记号并回滚「已显形」标记，
   * 返回 false（调用方随后退化为逐坐标 {@link #markThenSend}，未发出的坐标后续周期仍会重试）。
   *
   * <p>本方法以<b>一个 16³ 区块段</b>为一批调用（见 {@link #flushBatch} 的分组），{@code fresh} 必须
   * 全部落在同一个段内——跨段的坐标属于另一个 {@code sendMultiBlockChange} 包，不能混进同一次发送。
   */
  boolean sendBatchOrRollback(UUID playerId, String worldName, List<int[]> positions, int[] fresh,
      java.util.function.BooleanSupplier send) {
    // 回显记号必须在发包<b>之前</b>全部写好：一次 sendMultiBlockChange 会把 N 个坐标同时推进服务端
    // 发送队列，出站监听器可能在本次调用返回前就逐坐标判完——若等发包成功后再补，批量越大越必然
    // 全部判为「真实变更」，从而把整批坐标从全服共享的伪装索引里摘掉（其他玩家再也无法主动显形）。
    long[] echoExpiries = new long[fresh.length];
    for (int i = 0; i < fresh.length; i++) {
      int[] position = positions.get(fresh[i]);
      echoExpiries[i] = markOurReveal(playerId, worldName, position[0], position[1], position[2]);
    }
    if (send.getAsBoolean()) {
      return true;
    }
    // 发包失败：撤销刚写的记号（否则 ≤2 秒内同坐标的真实变更会被误判为回显而漏摘索引）。
    // 按「本次写入的过期时刻」条件删除：期间被别的路径重新标记过的坐标保留那枚新记号。
    for (int i = 0; i < fresh.length; i++) {
      int[] position = positions.get(fresh[i]);
      unmarkOurReveal(playerId, worldName, position[0], position[1], position[2], echoExpiries[i]);
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
   * 发出本周期累积的显形（批量模式）：先按 {@code (x>>4, y>>4, z>>4)} 分成若干区块段，再逐段
   * {@code Player#sendMultiBlockChange}——该 API 的一个包只承载一个段，跨段调用会被整包拒绝，
   * 因此分组是发包的前提，而不是可选的优化。
   *
   * <p>只有一个坐标的组直接用 {@code sendBlockChange}（单包更小，也与旧行为一致）。
   * 某段发送失败时只回滚该段并退化为逐坐标 {@code sendBlockChange}（同一条原生通道），其余段照常保留；
   * 单个坐标失败只计数并跳过。发包一律走「先标记、再发包、失败回滚」（见 {@link #markThenSend}）：
   * 成功发出的坐标保留标记，未发出的坐标回滚标记，因此发包失败既不影响其它坐标、也不污染已显形索引，
   * 且失败坐标后续仍会重试。
   *
   * <p><b>发包前复核（恰好一次）</b>：批次在累积时仅按「候选未显形」过滤，跨路径（周期巡检与事件即时
   * 显形共用本方法）同 tick 仍可能对同一坐标各累积一次。因此发包容纳集合由 {@link #claimForSend} 在
   * 「判重 + 标记」的同一临界区内定夺：已显形者不进入本次发包集合（既不重复发包，也不计入「发送」）。
   *
   * <p><b>额度不归还</b>：批次坐标是在 {@link #sendOne} 里「先预扣额度再累积」的（用于封住单周期上限）；
   * 「被复核跳过 / 退化路径发包失败」的坐标<b>不</b>归还额度——归还点都在发包循环结束之后，而
   * {@code Budget} 每周期新建、用后即弃，归还没有任何行为效果（见 {@link Budget} 类注释）。
   */
  private void flushBatch(Player player, RevealBatch batch, PassTally tally) {
    if (batch == null || batch.size() == 0) {
      return;
    }
    UUID playerId = player.getUniqueId();
    String worldName = batch.world.getName();

    // 原子复核 + 标记：只对「本周期尚未显形、本次真正新登记」的坐标发包（见 claimForSend）。
    int[] fresh = claimForSend(playerId, worldName, batch.positions);
    int skipped = batch.size() - fresh.length;
    if (skipped > 0) {
      // 已显形者不计入「发送」；它们在 sendOne 里预扣的额度不予归还（归还没有行为效果，见 Budget 类注释）
      stats.revealsSkipped.add(skipped);
    }
    if (fresh.length == 0) {
      return;
    }

    // 「已显形」标记由 claimForSend 写好；某段的合并包失败时 sendBatchOrRollback 只回滚该段的标记，
    // 该段随后逐坐标重新「复核 + 标记」。段与段互不影响：已发成功的段不重发，失败的段不漏发。
    for (List<Integer> section : groupBySection(batch, fresh).values()) {
      sendSection(player, batch, playerId, worldName, section, tally);
    }
  }

  /** 冲刷批次内的 section 分组键。 */
  private record SectionKey(int x, int y, int z) {
  }

  /**
   * 把本批待发坐标按 16³ 区块段分组，<b>组内与组间都保持 {@code fresh} 的原有顺序</b>
   * （{@link LinkedHashMap} 保序，组内下标按 {@code fresh} 的先后追加）。
   *
   * <p><b>为什么必须分组</b>：{@code Player#sendMultiBlockChange} 的一个包只承载一个区块段的坐标，
   * 跨段调用会被整包拒绝。若把整批坐标塞进一次调用，只要本周期跨了两个段（现实中几乎必然），
   * 整批就会失败并退化成逐坐标单发——功能不丢，但包数与 CPU 随显形数线性暴涨，且没有单独的观测点。
   */
  private static Map<SectionKey, List<Integer>> groupBySection(RevealBatch batch, int[] fresh) {
    Map<SectionKey, List<Integer>> sections = new LinkedHashMap<>();
    for (int index : fresh) {
      int[] position = batch.positions.get(index);
      SectionKey key = new SectionKey(position[0] >> 4, position[1] >> 4, position[2] >> 4);
      sections.computeIfAbsent(key, ignored -> new ArrayList<>(2)).add(index);
    }
    return sections;
  }

  /**
   * 发出同一 16³ 区块段内的一组显形坐标（见 {@link #flushBatch} 的分组说明）。
   *
   * <p>组内多于一个坐标时走一次 {@link #sendBatchOrRollback}（先写回显记号 → 发包 → 失败整组回滚）；
   * 只有一个坐标、或合并包构建失败时走逐坐标单发包。两条路径都保持「先标记、后发包、失败回滚」，
   * 且同一个坐标只会被其中一条路径发出：合并成功即返回，失败组的标记已整组回滚，随后由
   * {@link #markThenSend} 重新复核后单发——既不重复发包，也不漏发包。
   *
   * <p>本方法不接收 {@link Budget}：发包额度在 {@link #sendOne} 里「先预扣再入批」时就已扣完，
   * 失败 / 跳过不归还（见 {@link Budget} 类注释），因此这里没有可用的额度语义。
   */
  private void sendSection(Player player, RevealBatch batch, UUID playerId, String worldName,
      List<Integer> group, PassTally tally) {
    // 「已显形」标记由 claimForSend 写好；本段合并包失败时 sendBatchOrRollback 只回滚本段的标记，
    // 届时逐坐标重新「复核 + 标记」（见下方 else 分支）。
    boolean revealedMarksWritten = true;
    if (group.size() > 1) {
      // 下标数组只在真的要发合并包时才分配：稀疏显形的常态是每段一个坐标，那种情况走下面的单包路径。
      int[] indexes = new int[group.size()];
      int slot = 0;
      for (int index : group) {
        indexes[slot++] = index;
      }
      Map<Position, BlockData> changes;
      try {
        changes = new LinkedHashMap<>(indexes.length * 2);
        for (int index : indexes) {
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
        boolean sent = sendBatchOrRollback(playerId, worldName, batch.positions, indexes, () -> {
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
          stats.revealsSent.add(indexes.length);
          tally.sent += indexes.length;
          return;
        }
        revealedMarksWritten = false; // 本段合并包未发出 → 本段「已显形」标记已整段回滚
      }
      // changes 构建失败：本段「已显形」标记仍在（revealedMarksWritten 保持 true），落到下面的逐坐标单包
    }

    for (int index : group) {
      int[] position = batch.positions.get(index);
      BlockData state = batch.states.get(index);
      boolean sent;
      if (revealedMarksWritten) {
        // 「已显形」标记已在 claimForSend 写好。回显记号同样必须先于发包（理由见 sendBatchOrRollback）：
        // 包一离开 sendSingle 就进了服务端发送队列，出站监听器可能在本方法返回前就已判定。
        long echoExpiry = markOurReveal(playerId, worldName, position[0], position[1], position[2]);
        sent = sendSingle(player, batch.world, position[0], position[1], position[2], state);
        if (!sent) {
          unmarkOurReveal(playerId, worldName, position[0], position[1], position[2], echoExpiry);
          if (revealedSet != null) {
            // 只回滚本玩家的标记（同 markThenSend），避免连带作废其它玩家的有效显形
            revealedSet.removePosition(playerId, worldName, position[0], position[1], position[2]);
          }
        }
      } else {
        // 合并包已回滚本段标记：逐坐标重新走「先复核 + 标记、再发包、失败回滚」（见 markThenSend）
        sent = markThenSend(playerId, worldName, position[0], position[1], position[2],
            () -> sendSingle(player, batch.world, position[0], position[1], position[2], state));
      }
      if (!sent) {
        stats.revealsSkipped.increment();
        // 未发出：预扣的额度不予归还（归还没有行为效果，见 Budget 类注释）
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
    // 一次性就绪诊断（正常运营信息，降为 FINE）：开服/运行日志只保留必要内容与异常提示。
    plugin.getLogger().fine("首次显形诊断：扫描区块 " + tally.chunksScanned + " 个（整块跳过 "
        + tally.chunksSkipped + "，分片推迟 " + tally.chunksDeferred + "／共 " + SCAN_SHARDS
        + " 片），坐标评估 " + tally.positionsEvaluated + " 个，候选 " + candidateCount
        + " 个，视锥剔除 " + frustumCulled + "，射线剔除 " + tally.rayCulled
        + "，实际发送 " + tally.sent);
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
    if (workPool == null) {
      // 未装配工作池（极少见）：没有可用的异步通道，只能就地清理（语义优先于毛刺）
      expireNow();
      return;
    }
    if (!workPool.hasSpareCapacity()) {
      // 队列紧张 = 区块集中下发、服务器最忙的时刻，恰好是本方法搬进工作池要避开的那种时刻：
      // 本轮跳过（过期清理只是内存卫生），下个窗口再试，绝不把 O(全服条目) 的全量扫描退回
      // 主线程 / Folia 区域线程。
      return;
    }
    try {
      workPool.execute(this::expireNow);
    } catch (Throwable throwable) {
      // 入队失败（队列刚被填满 / 线程池关闭）：同样跳过本轮，下个窗口重试
      logThrottled(throwable);
    }
  }

  /**
   * 实际执行过期清理（工作线程或退化时的调用线程；纯并发结构操作，任意线程安全）。
   *
   * <p><b>伪装索引不再按时间过期</b>（玩家体验优先）：索引条目现在<b>只随区块卸载清理</b>
   * （见 {@code AntiXrayRuntime#invalidateChunk}）。原因是时间过期会让「玩家走远超过 expire-seconds
   * 又折返」的区块在未重新下发时丢失伪装清单——那些矿就再也不会被显形，玩家会一直看到假方块。
   * 玩家折返是极常见的操作（挖矿来回、跑图回头），不能靠「区块大概会重发」来赌。
   *
   * <p>内存仍是有界的：区块卸载即整列摘除（服务端卸载区块本就意味着附近无玩家），
   * 因此索引驻留量 ≈ 当前已加载且被改写过的区块数。
   *
   * <p>「已显形集合」仍按时间过期：它是<b>按玩家</b>记录的，玩家长期不下线时若不清理会随其活动范围
   * 无界增长；而它过期只意味着「可能重复显形一次」（浪费一点带宽），不会让玩家看到假方块——
   * 方向安全。
   */
  private void expireNow() {
    try {
      revealedSet.expire();
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
    try {
      pruneExpiredEchoes();
    } catch (Throwable throwable) {
      // 清理回显记号失败绝不能影响已显形集合的过期清理：本块独立兜底、只记日志
      logThrottled(throwable);
    }
  }

  /**
   * 清理所有已过窗口的回显记号（随巡检周期由 {@link #expireNow()} 调用）。
   *
   * <p><b>为什么需要显式清理</b>：记号本应「包被出站监听器看到即消费」，但停用瞬间的在途发包、玩家连接
   * 恰好关闭等异常路径会让记号永远留在表里；只靠「同坐标再次被查询」回收意味着可能长期滞留。清理跑在
   * 工作线程、与 {@link RevealedSet#expire()} 同频率；稳态下每个玩家的表都是空的（记号当 tick 即被
   * 消费），因此通常命中 {@code isEmpty()} 早退，开销可忽略。
   *
   * <p><b>为什么清空后也不把内层 map 从 {@link #revealEcho} 摘除</b>：{@link #markOurReveal} 是
   * 「{@code computeIfAbsent} 取到 map 引用 → 再 {@code put} 记号」，这两步之间没有原子性。若本方法
   * 在清空后把 map 摘掉，恰好落在这两步之间的那条记号就会写进一张已脱管的 map——出站监听器
   * （{@link #consumeOurRevealEcho}）只查 {@code revealEcho.get(playerId)}，看不到它，于是会把我们
   * 自己刚发的显形包当成「真实变更」：从<b>按区块全服共享</b>的伪装索引里摘掉该坐标、并清掉所有玩家
   * 在该坐标的已显形标记，其它玩家从此无法被主动显形（只能等区块重发）。反过来，空 map 的驻留上界
   * 是「在线玩家数 × 一个空 ConcurrentHashMap」，而玩家退出（{@link #onQuit}）与插件停用
   * （{@link #stop(boolean)} 的 clearIndexes 路径）会整体释放、热重载重建实例时整张表随旧实例回收
   * ——留着不摘没有任何内存代价，却彻底消除上述孤儿窗口，因此这里<b>只删记号、不摘 map</b>。
   */
  private void pruneExpiredEchoes() {
    long now = System.nanoTime();
    for (ConcurrentHashMap<EchoKey, Long> marks : revealEcho.values()) {
      if (marks.isEmpty()) {
        continue;
      }
      pruneExpiredEchoes(marks, now);
    }
  }

  /**
   * 清理一张回显表里已过窗口的记号（包级可见的纯集合操作，便于离线单测；时钟由调用方给出）。
   *
   * @param nowNanos 当前时刻（纳秒），必须与 {@link #markOurReveal} 写入过期时刻的时钟同源（{@code System.nanoTime()}）
   */
  static void pruneExpiredEchoes(ConcurrentHashMap<EchoKey, Long> marks, long nowNanos) {
    marks.entrySet().removeIf(entry -> entry.getValue() < nowNanos);
  }

  private int limit() {
    return Math.max(1, proximity.maxRevealsPerTick());
  }

  /**
   * 单周期实际生效的发包上限：取候选窗口（{@link #MAX_CANDIDATES}）是硬上界。
   *
   * <p>取候选、评估、发包三者共用同一个窗口，因此配置值 ≤ 窗口时<b>完整生效</b>（旧实现另有 256 的
   * 评估上限，会让配置值在 &gt;256 时静默失效）；超过窗口的部分无法兑现，由调用方在启动时 WARN 一次。
   * 包级可见：单测据此断言「三个上限一致、不再漂移」。
   */
  static int effectiveRevealCeiling(int configured) {
    return Math.max(1, Math.min(configured, MAX_CANDIDATES));
  }

  /**
   * 有效显形半径（区块）：配置 0（默认）= 跟随玩家视距；正数也不超过玩家视距。
   *
   * <p><b>为什么必须钳到视距</b>：显形包只有在客户端<b>已加载该区块</b>时才会被应用，超出视距的包会被
   * 客户端直接丢弃——既浪费带宽，又可能让「已显形」标记落在一个客户端根本没见过的坐标上。
   * 服务器视距与客户端自报视距取小：客户端可以把自己的视距调得比服务器小
   * （{@code Player#getClientViewDistance()}，未知时返回 -1）。
   *
   * @param configuredChunks   配置值（区块）；≤0 表示跟随视距
   * @param serverViewDistance 服务器侧对该玩家的生效视距（{@code Player#getViewDistance()}；≤0 视为无效）
   * @param clientViewDistance 客户端自报视距；≤0 表示未知（此时只看服务器视距）
   */
  static int effectiveRevealChunks(int configuredChunks, int serverViewDistance, int clientViewDistance) {
    int server = serverViewDistance > 0 ? serverViewDistance : FALLBACK_VIEW_DISTANCE_CHUNKS;
    int limit = clientViewDistance > 0 ? Math.min(server, clientViewDistance) : server;
    int chunks = configuredChunks <= 0 ? limit : Math.min(configuredChunks, limit);
    return Math.max(1, chunks);
  }

  /** 服务器侧对该玩家的生效视距（跟随 server.properties 的 view-distance；world 级覆盖时取该世界值）。 */
  private static int playerViewDistance(Player player) {
    try {
      return player.getViewDistance();
    } catch (Throwable fallbackFailure) {
      return FALLBACK_VIEW_DISTANCE_CHUNKS;
    }
  }

  /** 客户端自报视距；未知（-1）或读取失败时返回 -1（调用方按「未知」处理，只看服务器视距）。 */
  private static int playerClientViewDistance(Player player) {
    try {
      return player.getClientViewDistance();
    } catch (Throwable fallbackFailure) {
      return -1;
    }
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
        || !instant.enabled() || instant.maxPerTick() <= 0 || stopping) {
      return;
    }
    // 黑名单世界不做事件即时显形（在调度前就返回，避免产生任何任务）：纯判定，不触碰 Bukkit。
    if (WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      return;
    }
    if (chunkIndex == null || revealedSet == null) {
      return;
    }
    if (hasDisguisedNearby(chunkIndex, worldName, x, y, z, instant.radius())) {
      Schedulers.onEntity(plugin, player, () -> revealImmediately(player, x, y, z));
    }
  }

  /**
   * 初筛（纯函数，可离线单测）：变更坐标的曼哈顿邻域内是否仍有伪装坐标。
   * 只读并发索引，供封包线程在调度前过滤「邻域内全是非伪装方块」的无效变更。
   */
  static boolean hasDisguisedNearby(ObfuscatedChunkIndex index, String worldName,
      int x, int y, int z, int radius) {
    // 邻域偏移只落在至多 4 个区块里：containsPosition(String,...) 内部复用线程本地 Probe，逐偏移零分配
    // （一个塞满 4096 坐标的 MULTI_BLOCK_CHANGE 默认约 10 万次查找，是封包线程上最大的一笔分配）。
    for (int[] offset : instantOffsets(radius)) {
      int blockX = x + offset[0];
      int blockY = y + offset[1];
      int blockZ = z + offset[2];
      if (index.containsPosition(worldName, blockX, blockY, blockZ)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 释放当前线程上「邻近显形侧」全部线程本地复用实例（插件停用 / 热重载时由
   * {@code AntiXrayRuntime#stopForDisable} 调用）。
   *
   * <p>涉及：{@link ChunkKey.Probe}、{@link BitmapVisibility} 的判定器、
   * {@link ProximitySelector} 的候选点缓冲、{@link BlockChangeRevealListener} 的去重 / 解析缓冲。
   * ThreadLocal 条目挂在<b>线程</b>的 ThreadLocalMap 上，而主线程 / Folia 区域线程 / netty 事件循环
   * 线程都比插件活得久：停用或热重载后旧实例会经这些条目钉住旧类加载器，直到线程下次访问
   * ThreadLocalMap 才被惰性 expunge。这里只能清「调用线程」这一份（其它线程无法从这里枚举），
   * 至少保证停用线程自己不再持有旧实例。
   */
  public static void clearThreadLocals() {
    ChunkKey.Probe.clearThreadLocals();
    BitmapVisibility.clearThreadLocals();
    ProximitySelector.clearThreadLocals();
    BlockChangeRevealListener.clearThreadLocals();
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
        || !instant.enabled() || instant.maxPerTick() <= 0 || stopping) {
      return;
    }
    // 黑名单世界不做事件即时显形（在调度前就返回，避免产生任何任务）：纯判定，不触碰 Bukkit。
    if (WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      return;
    }
    if (chunkIndex == null || revealedSet == null) {
      return;
    }
    if (hasDisguisedNearSection(chunkIndex, worldName, coordinates, count, instant.radius())) {
      Schedulers.onEntity(plugin, player,
          () -> revealSectionImmediately(player, coordinates, count));
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
        if (index.containsPosition(worldName, blockX, blockY, blockZ)) {
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
   *
   * <p><b>为什么事件路径的可见性判定就地做</b>：本路径由「玩家刚挖开/放置的方块」触发，其价值就是同
   * tick 纠正，因此不异步化；可见性判定直接在当前线程用遮挡位图完成（{@code visibilityInPlan=false}），
   * 省去一次线程往返。位图缺失时按「不显形」处理（严格方向），绝不裸发。周期巡检不受此影响（见类注释）。
   */
  private void revealImmediately(Player player, int cx, int cy, int cz) {
    try {
      if (stopping || !player.isOnline()
          || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
        return;
      }
      World world = player.getWorld();
      // 只排除黑名单世界：与周期巡检共用同一门闸（总开关关闭后仍要还原已下发的假矿）。
      if (!shouldRevealInWorld(world.getName())) {
        return;
      }
      int radius = instant.radius();
      Location location = player.getLocation();
      if (!withinManhattanRadius(location.getBlockX(), location.getBlockY(), location.getBlockZ(),
          cx, cy, cz, radius)) {
        return;
      }
      // 与周期路径同一 Folia 纪律：先判 region 归属，再问区块是否加载（跨 region 读会触发 TickThread 校验）
      if (!ownsChunk(world, cx >> 4, cz >> 4) || !world.isChunkLoaded(cx >> 4, cz >> 4)) {
        return;
      }

      UUID playerId = player.getUniqueId();
      long tick = Bukkit.getCurrentTick();
      // 原子「取额度 + 预扣」（见 TickQuota#claim）：并发事件不会各自拿到满额，限额不会被突破。
      int maxPerTick = instant.maxPerTick();
      int granted = instantQuota.claim(playerId, tick, maxPerTick, maxPerTick);
      Budget budget = new Budget(granted);
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
        // 事件路径不预先算计划（见 revealImmediately 注释）：false = sendOne 在当前线程用遮挡位图判定
        sendOne(player, world, x, y, z, eye, budget, tally, batch, false);
      }
      flushBatch(player, batch, tally);
      instantQuota.release(playerId, tick, maxPerTick, granted, tally.sent);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /**
   * 玩家所属线程：一个 section 全部变更坐标的即时显形（与逐坐标 {@link #revealImmediately} 等价，
   * 但只调度一个任务）。对每个变更坐标走同一套判定（身边半径、区块已加载、仍在伪装清单且未显形过），
   * 候选坐标跨变更坐标去重后复用 {@link #sendOne}，因此结果与逐坐标处理逐条一致。
   */
  private void revealSectionImmediately(Player player, int[] coordinates, int count) {
    try {
      if (stopping || !player.isOnline()
          || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
        return;
      }
      World world = player.getWorld();
      // 只排除黑名单世界：与周期巡检共用同一门闸（总开关关闭后仍要还原已下发的假矿）。
      if (!shouldRevealInWorld(world.getName())) {
        return;
      }
      int radius = instant.radius();
      Location location = player.getLocation();
      UUID playerId = player.getUniqueId();
      long tick = Bukkit.getCurrentTick();
      // 与逐坐标路径共用同一份原子额度（见 TickQuota#claim）：两条路径并发时不会各拿满额。
      int maxPerTick = instant.maxPerTick();
      int granted = instantQuota.claim(playerId, tick, maxPerTick, maxPerTick);
      Budget budget = new Budget(granted);
      if (budget.remaining() <= 0) {
        return;
      }

      ProximitySelector.Eye eye = proximity.raycastEnabled() ? eyeOf(player) : null;
      PassTally tally = new PassTally();
      RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
      String liveWorld = world.getName();
      // 候选判定复用线程本地区块键缓存（键只用于 CHM 查询、绝不入表），避免逐候选 new ChunkKey。
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
      // （Paper 主线程 / Folia 区域线程）。因此事件路径单独设硬上限（周期巡检跑在工作线程、且受候选
      // 窗口约束，不需要共用这个数）：触顶即停，剩余候选交给同 tick 的后续事件或下一个巡检周期处理。
      int evaluationsLeft = MAX_INSTANT_EVALUATIONS_PER_EVENT;
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
        if (!ownsChunk(world, cx >> 4, cz >> 4) || !world.isChunkLoaded(cx >> 4, cz >> 4)) {
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
          // 去重必须放在昂贵判定之前：同一绝对坐标会从多个变更坐标的邻域反复到达，
          // 先做纯位运算去重，可省下重复的「索引查表 + 已显形集合加锁」。
          if (!evaluated.add(packCoordinate(x, y, z))) {
            continue;
          }
          if (!isInstantCandidate(playerId, liveWorld, x, y, z)) {
            continue;
          }
          // 事件路径不预先算计划（见 revealImmediately 注释）：false = sendOne 在当前线程用遮挡位图判定
          sendOne(player, world, x, y, z, eye, budget, tally, batch, false);
        }
      }
      flushBatch(player, batch, tally);
      instantQuota.release(playerId, tick, maxPerTick, granted, tally.sent);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  // ============================== 区块下发即显形 / 开掘预显形 ==============================

  /** 6 邻偏移（开掘预显形：只关心「挖开这一格之后会露出来的 6 个面」）。 */
  private static final int[] DIG_DX = {1, -1, 0, 0, 0, 0};
  private static final int[] DIG_DY = {0, 0, 1, -1, 0, 0};
  private static final int[] DIG_DZ = {0, 0, 0, 0, 1, -1};
  /** 开掘预显形最多评估的邻接数（固定 6 邻，留常量只为可读性）。 */
  private static final int DIG_NEIGHBOUR_COUNT = 6;
  /** 开掘去重窗口（tick）：同一格在该窗口内只预显形一次。 */
  private static final long DIG_DEDUP_TICKS = 10L;
  /**
   * 区块下发即显形：每个区块最多评估的伪装坐标数（硬上限）。
   *
   * <p>该路径在玩家区域线程上执行（要读真实方块状态、要发包），一个区块的伪装坐标通常只有个位数到
   * 几十个；矿洞密集区块可能上百，逐一做「暴露面 + 射线」会明显加重区域线程。取 96 作上限，超出部分
   * 交给下一轮周期巡检（仍会被还原，只是最坏晚一个巡检周期），不会漏。
   */
  private static final int MAX_CHUNK_DELIVERY_EVALUATIONS = 96;
  /**
   * 区块下发即显形：每玩家每 tick 的<b>总</b>评估上限（跨所有刚下发的区块合并计算）。
   *
   * <p><b>为什么需要总上限</b>：一次传送 / 首次进服会连续下发上百个区块。若每个区块各排一个区域任务、
   * 各自做满 96 次评估，单 tick 会在玩家线程上跑上万次判定（数毫秒毛刺）。合并成「每 tick 一次、共享
   * 128 次评估」后，最坏开销被钉死，落下的区块交给下一 tick 或周期巡检，不会漏。
   */
  private static final int MAX_CHUNK_DELIVERY_EVALUATIONS_PER_TICK = 128;
  /**
   * 区块下发即显形：每玩家每 tick 的发包上限。
   *
   * <p>与事件显形（挖掘/变更触发的即时显形）<b>分开计量</b>：那条路径是「客户端已经渲染出来才补发」，
   * 时间窗只有一个 tick；若与本路径共用额度，一次区块集中下发就会把当 tick 的即时显形额度吃光，
   * 反而放大「挖开看到假方块」的窗口。两条路径各 16 个/ tick，互不挤占。
   */
  private static final int MAX_CHUNK_DELIVERY_SENDS_PER_TICK = 16;
  /** 待处理区块键的合并上限（防止极端情况下无界堆积；超出部分由周期巡检兜底）。 */
  private static final int MAX_PENDING_CHUNK_REVEALS = 256;

  /**
   * 「区块下发即显形」的待处理队列（按玩家合并，每 tick 只处理一次）。
   *
   * <p><b>为什么合并</b>：见 {@link #MAX_CHUNK_DELIVERY_EVALUATIONS_PER_TICK}；此外合并后每 tick 只调度
   * 一个区域任务，避免传送时上百个任务排队。
   */
  static final class PendingChunkReveals {

    private String worldName;
    private final java.util.LinkedHashSet<Long> chunkKeys = new java.util.LinkedHashSet<>();
    private boolean scheduled;
    private boolean draining;
  }

  private final ConcurrentHashMap<UUID, PendingChunkReveals> pendingChunkReveals =
      new ConcurrentHashMap<>();

  /**
   * 区块包<b>放行之后</b>的回调（由 {@code ProtocolLibAsyncListener} 的放行动作调用，可能运行在工作线程上）：
   * 把「该区块中玩家当前看得见」的伪装坐标尽快显形。
   *
   * <p><b>为什么需要</b>：区块包是共享的伪装版本，玩家收到后先看到石头/伪装方块，要等下一轮巡检
   * （最坏约 0.8 秒）才被还原——这就是「矿石/箱子凭空出现」的来源。区块包放行后立刻补发一个小批量显形包，
   * 客户端通常在同一 tick 内先后应用，这个窗口基本消失。
   *
   * <p><b>为什么只入队、不在这里判定</b>：本方法可能运行在封包工作线程上，而判定要读玩家眼睛、读真实方块
   * 状态、发包，全部必须在玩家所属线程完成；这里只做纯内存门闸（不发包、不读 Bukkit 世界数据）。
   *
   * <p>受 {@code proximity.instant-reveal} 的总开关与每 tick 限额约束：即时显形整体关闭时本路径也关闭。
   */
  public void onChunkDelivered(Player player, String worldName, int chunkX, int chunkZ) {
    if (player == null || worldName == null || stopping || !proximity.revealOnChunkSend()
        || !instant.enabled() || instant.maxPerTick() <= 0) {
      return;
    }
    if (chunkIndex == null || revealedSet == null || WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      return;
    }
    UUID playerId = player.getUniqueId();
    PendingChunkReveals pending = pendingChunkReveals.computeIfAbsent(playerId,
        ignored -> new PendingChunkReveals());
    boolean schedule = false;
    synchronized (pending) {
      if (!worldName.equals(pending.worldName)) {
        // 切世界：旧世界的区块键作废（同一玩家不可能同时收两个世界的区块）
        pending.chunkKeys.clear();
        pending.worldName = worldName;
      }
      if (pending.chunkKeys.size() < MAX_PENDING_CHUNK_REVEALS) {
        pending.chunkKeys.add(packChunkKey(chunkX, chunkZ));
      }
      if (!pending.scheduled && !pending.draining) {
        pending.scheduled = true;
        schedule = true;
      }
    }
    if (schedule) {
      Schedulers.onEntity(plugin, player, () -> drainChunkReveals(player));
    }
  }

  /** 区块键打包（区块坐标 → long；仅用于合并队列去重）。 */
  private static long packChunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }

  /**
   * 玩家所属线程：排空「区块下发即显形」队列（见 {@link #onChunkDelivered}）。
   *
   * <p>每 tick 合并处理一次，并共享同一份评估预算（{@link #MAX_CHUNK_DELIVERY_EVALUATIONS_PER_TICK}）；
   * 队列里还有剩余时顺延到下一 tick，避免传送时的集中开销。
   */
  private void drainChunkReveals(Player player) {
    try {
      PendingChunkReveals pending = pendingChunkReveals.get(player.getUniqueId());
      if (pending == null) {
        return;
      }
      if (stopping || !player.isOnline()) {
        // 离线/停用：直接摘掉条目。若只 return，scheduled 会永远为 true（离线后没有下一次 drain），
        // 重登时 computeIfAbsent 取到的仍是这条卡死条目 → M2 对该玩家整局失效，且条目泄漏。
        pendingChunkReveals.remove(player.getUniqueId(), pending);
        return;
      }
      java.util.List<Long> batch;
      String worldName;
      synchronized (pending) {
        pending.scheduled = false;
        if (pending.chunkKeys.isEmpty()) {
          return;
        }
        pending.draining = true;
        batch = new java.util.ArrayList<>(pending.chunkKeys);
        pending.chunkKeys.clear();
        worldName = pending.worldName;
      }
      int consumed = 0;
      try {
        consumed = revealChunks(player, worldName, batch);
      } finally {
        boolean reschedule = false;
        synchronized (pending) {
          pending.draining = false;
          if (consumed < batch.size()) {
            // 预算没判完的区块按原序回填：下一 tick 继续，绝不丢弃（传送/首次进服会一次下发上百个区块）
            pending.chunkKeys.addAll(batch.subList(consumed, batch.size()));
          }
          if (!pending.chunkKeys.isEmpty() && !pending.scheduled) {
            pending.scheduled = true;
            reschedule = true;
          }
        }
        if (reschedule) {
          Schedulers.onEntity(plugin, player, () -> drainChunkReveals(player));
        }
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /**
   * 玩家所属线程：把一批刚下发的区块里「当前可见」的伪装坐标显形。
   *
   * <p>判定口径与周期巡检一致：仍在伪装清单、该玩家尚未显形、视锥内、位图射线通畅（{@code sendOne} 判定）。
   * 硬上限：每区块 {@link #MAX_CHUNK_DELIVERY_EVALUATIONS}、整批
   * {@link #MAX_CHUNK_DELIVERY_EVALUATIONS_PER_TICK}、发包受 {@code instant-reveal.max-per-tick} 限额。
   */
  private int revealChunks(Player player, String worldName, java.util.List<Long> chunkKeys) {
    try {
      if (stopping || !player.isOnline()
          || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
        return chunkKeys.size();
      }
      World world = player.getWorld();
      if (!world.getName().equals(worldName) || !shouldRevealInWorld(worldName)) {
        return chunkKeys.size();
      }
      UUID playerId = player.getUniqueId();
      long tick = Bukkit.getCurrentTick();
      int granted = chunkDeliveryQuota.claim(playerId, tick, MAX_CHUNK_DELIVERY_SENDS_PER_TICK,
          MAX_CHUNK_DELIVERY_SENDS_PER_TICK);
      Budget budget = new Budget(granted);
      if (budget.remaining() <= 0) {
        return 0;
      }
      // 视锥与射线解耦：raycast 关闭时眼睛也不能为 null（否则 withinFrustum 恒真 → 过度显形）；
      // 射线判定由 sendOne 内部按 raycastEnabled 自行门闸。
      ProximitySelector.Eye eye = eyeOf(player);
      boolean frustum = proximity.frustumEnabled();
      PassTally tally = new PassTally();
      RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
      int evaluated = 0;
      int consumed = 0;
      for (int i = 0; i < chunkKeys.size(); i++) {
        if (budget.remaining() <= 0 || evaluated >= MAX_CHUNK_DELIVERY_EVALUATIONS_PER_TICK) {
          break;
        }
        int chunkX = (int) (chunkKeys.get(i) >> 32);
        int chunkZ = (int) (long) chunkKeys.get(i);
        ObfuscatedChunkIndex.ChunkEntry entry = chunkIndex.entry(worldName, chunkX, chunkZ);
        if (entry == null || entry.size() == 0) {
          consumed = i + 1;
          continue;
        }
        int[] locals = entry.locals();
        int minHeight = entry.minHeight();
        int perChunk = 0;
        boolean complete = true;
        for (int local : locals) {
          if (budget.remaining() <= 0 || evaluated >= MAX_CHUNK_DELIVERY_EVALUATIONS_PER_TICK
              || perChunk >= MAX_CHUNK_DELIVERY_EVALUATIONS) {
            complete = false;
            break;
          }
          int x = (chunkX << 4) | (local & 15);
          int z = (chunkZ << 4) | ((local >> 4) & 15);
          int y = minHeight + (local >> 8);
          evaluated++;
          perChunk++;
          if (!isInstantCandidate(playerId, worldName, x, y, z)) {
            continue;
          }
          if (frustum && !ProximitySelector.withinFrustum(eye, x, y, z,
              proximity.frustumMinDistance(), proximity.frustumFov())) {
            continue;
          }
          sendOne(player, world, x, y, z, eye, budget, tally, batch, false);
        }
        consumed = complete ? i + 1 : i;
        if (!complete) {
          break;
        }
      }
      flushBatch(player, batch, tally);
      chunkDeliveryQuota.release(playerId, tick, MAX_CHUNK_DELIVERY_SENDS_PER_TICK, granted,
          tally.sent);
      return consumed;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      // 异常时视为已消费：避免「每 tick 重排同一批 → 每 tick 抛异常」的热循环，剩余交给周期巡检
      return chunkKeys.size();
    }
  }

  /**
   * 开掘预显形入口（{@link BlockDamageEvent}，玩家所属线程）：玩家开始挖某个方块时，把该方块 6 邻中
   * 「挖开后会看见」的伪装坐标提前显形。
   *
   * <p><b>为什么必须提前</b>：客户端对挖掘有本地预测——方块一破，客户端立刻用本地状态渲染邻接方块。
   * 服务端若等「方块被破坏」后再补发显形，必然晚一个网络往返，那一瞬玩家会看到邻接的伪装方块。
   * 提前到「开始挖」发送，正常挖掘速度（≥ 0.2 秒）下显形包会先于破坏到达客户端。
   *
   * <p>同一格在 {@value #DIG_DEDUP_TICKS} tick 内只处理一次（该事件在持续挖掘时会重复触发）。
   * 仅在射线判定开启时生效：否则「挖开后会看见」无从判定，等于把 6 邻全部裸发（会过度显形）。
   */
  @EventHandler(ignoreCancelled = true)
  public void onBlockDamage(BlockDamageEvent event) {
    if (stopping || !proximity.preRevealOnDig() || !proximity.raycastEnabled()
        || !instant.enabled() || instant.maxPerTick() <= 0) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null
        || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
      return;
    }
    try {
      Block block = event.getBlock();
      int x = block.getX();
      int y = block.getY();
      int z = block.getZ();
      if (!digDedupAllows(player.getUniqueId(), x, y, z, Bukkit.getCurrentTick())) {
        return;
      }
      revealAfterDig(player, x, y, z);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 开掘去重（纯逻辑，可离线单测）：同一格在窗口内只放行一次；窗口外的旧记号直接覆盖。 */
  boolean digDedupAllows(UUID playerId, int x, int y, int z, long tick) {
    long packed = packCoordinate(x, y, z);
    long[] mark = digMarks.computeIfAbsent(playerId, ignored -> new long[2]);
    synchronized (mark) {
      if (mark[1] != 0L && mark[0] == packed && tick >= mark[1]
          && tick - mark[1] < DIG_DEDUP_TICKS) {
        return false;
      }
      mark[0] = packed;
      mark[1] = tick;
      return true;
    }
  }

  /**
   * 玩家所属线程：把「正在挖的那一格」6 邻中挖开后会看见的伪装坐标显形（见 {@link #onBlockDamage}）。
   *
   * <p><b>判定口径</b>：把正在挖的那一格按空气处理（{@link BitmapVisibility#beginIgnoring}），于是
   * 朝向它的那个面成为暴露面、步进射线也不再被它挡住；其余仍是严格口径（缺数据 = 遮挡、不显形）。
   * 这样只会提前还原「挖开之后真能看见」的坐标，不会隔着墙泄漏。
   */
  private void revealAfterDig(Player player, int bx, int by, int bz) {
    try {
      if (stopping || !player.isOnline()) {
        return;
      }
      World world = player.getWorld();
      String worldName = world.getName();
      if (!shouldRevealInWorld(worldName)) {
        return;
      }
      UUID playerId = player.getUniqueId();
      long tick = Bukkit.getCurrentTick();
      int maxPerTick = Math.max(1, instant.maxPerTick());
      int granted = instantQuota.claim(playerId, tick, maxPerTick, DIG_NEIGHBOUR_COUNT);
      Budget budget = new Budget(granted);
      if (budget.remaining() <= 0) {
        return;
      }
      ProximitySelector.Eye eye = eyeOf(player);
      PassTally tally = new PassTally();
      RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
      BitmapVisibility bitmap = BitmapVisibility.local();
      bitmap.beginIgnoring(bx, by, bz);
      try {
        for (int i = 0; i < DIG_NEIGHBOUR_COUNT; i++) {
          if (budget.remaining() <= 0) {
            break;
          }
          int x = bx + DIG_DX[i];
          int y = by + DIG_DY[i];
          int z = bz + DIG_DZ[i];
          if (!isInstantCandidate(playerId, worldName, x, y, z)) {
            continue;
          }
          sendOne(player, world, x, y, z, eye, budget, tally, batch, false);
        }
      } finally {
        bitmap.endIgnoring();
      }
      flushBatch(player, batch, tally);
      instantQuota.release(playerId, tick, maxPerTick, granted, tally.sent);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  // ============================== 视野变化增量巡检（M3）==============================

  /** 视野变化增量巡检：单次最多评估的候选数（硬上限；同时也是取候选窗口）。 */
  private static final int MAX_VIEW_CHANGE_CANDIDATES = 32;
  /** 视野变化增量巡检：每玩家每 tick 最多发包数（防旋转刷包）。 */
  private static final int MAX_VIEW_CHANGE_SENDS_PER_TICK = 16;
  /** 触发阈值：与上次已扫描视点的眼位位移（格²）。 */
  private static final double VIEW_CHANGE_DISTANCE_SQUARED = 1.0D;
  /** 触发阈值：与上次已扫描视点的视线夹角（弧度，25°）。 */
  private static final double VIEW_CHANGE_ANGLE_RADIANS = Math.toRadians(25.0D);

  /** 视野变化触发状态：上一次「已扫描」的视点 + 冷却/待补扫标记。 */
  static final class ViewMark {

    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private double yaw;
    private double pitch;
    /** 上次增量巡检的 tick；初值取远小于当前 tick 的值，保证首帧即可触发。 */
    private long lastScanTick = -1_000L;
    /** 因冷却被跳过的待补扫标记（由 retry 链消费）。 */
    private boolean pending;
    /** 是否已有一条 retry 链在跑（避免重复调度）。 */
    private boolean scheduled;
  }

  /**
   * 纯函数：视野变化是否达到「值得跑一次增量巡检」的阈值（可离线单测）。
   *
   * @param distanceSquared 与上次已扫描视点的眼位位移平方（格²）
   * @param angleRadians    与上次已扫描视点的视线夹角（弧度）
   */
  static boolean viewChangeExceeds(double distanceSquared, double angleRadians) {
    return distanceSquared >= VIEW_CHANGE_DISTANCE_SQUARED || angleRadians >= VIEW_CHANGE_ANGLE_RADIANS;
  }

  /** 纯函数：两段视线（yaw/pitch，度）的夹角（弧度，0..π）；yaw 环绕由三角函数自然处理。 */
  static double viewAngle(double fromYaw, double fromPitch, double toYaw, double toPitch) {
    double fy = Math.toRadians(fromYaw);
    double fp = Math.toRadians(fromPitch);
    double ty = Math.toRadians(toYaw);
    double tp = Math.toRadians(toPitch);
    // Minecraft 视线：x = -sin(yaw)·cos(pitch)，y = -sin(pitch)，z = cos(yaw)·cos(pitch)
    double ax = -Math.sin(fy) * Math.cos(fp);
    double ay = -Math.sin(fp);
    double az = Math.cos(fy) * Math.cos(fp);
    double bx = -Math.sin(ty) * Math.cos(tp);
    double by = -Math.sin(tp);
    double bz = Math.cos(ty) * Math.cos(tp);
    double dot = ax * bx + ay * by + az * bz;
    return Math.acos(Math.max(-1.0D, Math.min(1.0D, dot)));
  }

  /**
   * 视野变化入口（{@link PlayerMoveEvent}，玩家所属线程）：移动/转向超过阈值时，当 tick 对近场做一次
   * 受限增量巡检。
   *
   * <p><b>为什么不新开调度任务</b>：移动事件本来就在玩家所属线程上派发（Paper 主线程 / Folia 区域线程），
   * 直接复用可省掉一次调度往返并把延迟压到 0；成本由 {@code budget-ms} 硬封顶，不会因事件密集而失控。
   *
   * <p><b>与周期巡检的关系</b>：增量巡检只补「刚进入视野」的近场坐标，周期巡检仍是兜底；两者共用
   * {@code sendOne} 与已显形标记，同一坐标当 tick 只发一次。
   */
  @EventHandler(ignoreCancelled = true)
  public void onPlayerMove(PlayerMoveEvent event) {
    AntiXrayConfig.ViewChange viewChange = proximity.viewChange();
    if (stopping || !viewChange.enabled()) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null || (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId()))) {
      return;
    }
    Location to = event.getTo();
    if (to == null) {
      return;
    }
    ViewMark mark = viewMarks.computeIfAbsent(player.getUniqueId(), ignored -> new ViewMark());
    double dx = to.getX() - mark.eyeX;
    double dy = to.getY() - mark.eyeY;
    double dz = to.getZ() - mark.eyeZ;
    double distanceSquared = dx * dx + dy * dy + dz * dz;
    // 便宜的粗筛：位移不足 1 格且朝向变化不足 15°（yaw+pitch 绝对值之和）时，连三角函数都不算
    double yawDelta = Math.abs(to.getYaw() - mark.yaw);
    double pitchDelta = Math.abs(to.getPitch() - mark.pitch);
    if (distanceSquared < VIEW_CHANGE_DISTANCE_SQUARED && yawDelta + pitchDelta < 15.0D) {
      return;
    }
    if (!viewChangeExceeds(distanceSquared, viewAngle(mark.yaw, mark.pitch, to.getYaw(), to.getPitch()))) {
      return;
    }
    long tick = Bukkit.getCurrentTick();
    if (tick - mark.lastScanTick < viewChange.cooldownTicks()) {
      // 冷却中：记下待补扫，由 retry 链在冷却结束时补一次（玩家可能在这之后就停下不动）
      mark.pending = true;
      scheduleViewChangeRetry(player, mark);
      return;
    }
    mark.lastScanTick = tick;
    mark.pending = false;
    rememberView(mark, to);
    runViewChangeScan(player, viewChange);
  }

  /** 记录「已扫描」的视点（之后的变化都以它为基准）。 */
  private static void rememberView(ViewMark mark, Location location) {
    mark.eyeX = location.getX();
    mark.eyeY = location.getY();
    mark.eyeZ = location.getZ();
    mark.yaw = location.getYaw();
    mark.pitch = location.getPitch();
  }

  /** 冷却期内的补扫链：每 tick 复查一次，冷却结束且仍待补扫时补一次（最多 cooldown 次跳转）。 */
  private void scheduleViewChangeRetry(Player player, ViewMark mark) {
    if (mark.scheduled) {
      return;
    }
    mark.scheduled = true;
    Schedulers.onEntity(plugin, player, () -> retryViewChange(player));
  }

  private void retryViewChange(Player player) {
    try {
      if (stopping || !player.isOnline() || !proximity.viewChange().enabled()) {
        return;
      }
      ViewMark mark = viewMarks.get(player.getUniqueId());
      if (mark == null) {
        return;
      }
      mark.scheduled = false;
      if (!mark.pending) {
        return;
      }
      long tick = Bukkit.getCurrentTick();
      if (tick - mark.lastScanTick < proximity.viewChange().cooldownTicks()) {
        scheduleViewChangeRetry(player, mark);
        return;
      }
      mark.pending = false;
      mark.lastScanTick = tick;
      rememberView(mark, player.getLocation());
      runViewChangeScan(player, proximity.viewChange());
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /**
   * 玩家所属线程：近场受限增量巡检（见 {@link #onPlayerMove}）。
   *
   * <p><b>成本纪律（三道闸）</b>：① 候选上限 {@value #MAX_VIEW_CHANGE_CANDIDATES}；
   * ② 时间预算 {@code proximity.view-change.budget-ms}（每判 ≤4 个候选检查一次，超时即停并计入
   * 「预算耗尽」）；③ 每 tick 发包上限 {@value #MAX_VIEW_CHANGE_SENDS_PER_TICK}。任一闸门触发都只是
   * 「本轮少补几个」：坐标仍在伪装索引里，周期巡检会兜底。
   */
  private void runViewChangeScan(Player player, AntiXrayConfig.ViewChange viewChange) {
    // 先做门闸再计时：门闸失败的调用不算一次「扫描」，否则面板里的平均耗时会被大量空调用稀释
    if (stopping || !player.isOnline() || chunkIndex == null || revealedSet == null) {
      return;
    }
    World world = player.getWorld();
    String worldName = world.getName();
    if (!shouldRevealInWorld(worldName)) {
      return;
    }
    long start = System.nanoTime();
    int sent = 0;
    try {
      long deadline = start + (long) (viewChange.budgetMs() * 1_000_000.0D);
      Location eyeLocation = player.getEyeLocation();
      ProximityScanner.Tally scanTally = new ProximityScanner.Tally();
      // 取候选也受同一时间预算约束（deadline 传入）：密集矿区单趟遍历就可能超过预算。
      // 截断时不再「整轮放弃」——取到多少判多少，只记一次预算耗尽（否则 M3 在矿区反而完全不工作）。
      List<ObfuscatedChunkIndex.Position> candidates = ProximityScanner.candidates(chunkIndex, revealedSet,
          player.getUniqueId(), worldName, eyeLocation.getBlockX(), eyeLocation.getBlockY(),
          eyeLocation.getBlockZ(), viewChange.radius(), MAX_VIEW_CHANGE_CANDIDATES, 0, 1, scanTally,
          deadline);
      if (candidates.isEmpty()) {
        return;
      }
      if (scanTally.deadlineTruncated) {
        stats.viewChangeBudgetExhausted.increment();
      }
      UUID playerId = player.getUniqueId();
      long tick = Bukkit.getCurrentTick();
      int granted = viewChangeQuota.claim(playerId, tick, MAX_VIEW_CHANGE_SENDS_PER_TICK,
          MAX_VIEW_CHANGE_SENDS_PER_TICK);
      Budget budget = new Budget(granted);
      if (budget.remaining() <= 0) {
        return;
      }
      // 视锥与射线解耦：raycast 关闭时 eye 也不能为 null（否则 withinFrustum 恒真 → 把玩家背后/墙后
      // 的真实状态下发，削弱反矿透）；射线判定由 sendOne 内部按 raycastEnabled 自行门闸。
      ProximitySelector.Eye eye = eyeOf(player);
      boolean frustum = proximity.frustumEnabled();
      PassTally tally = new PassTally();
      RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
      int evaluated = 0;
      boolean exhausted = false;
      for (ObfuscatedChunkIndex.Position position : candidates) {
        if (budget.remaining() <= 0) {
          break;
        }
        // 至少兑现 4 个候选：取候选若已吃掉预算，也不能一个都不判（语义与周期巡检一致）
        if (evaluated >= 4 && (evaluated & 3) == 0 && System.nanoTime() > deadline) {
          exhausted = true;
          break;
        }
        evaluated++;
        if (frustum && !ProximitySelector.withinFrustum(eye, position.x(), position.y(), position.z(),
            proximity.frustumMinDistance(), proximity.frustumFov())) {
          continue;
        }
        sendOne(player, world, position.x(), position.y(), position.z(), eye, budget, tally, batch, false);
      }
      flushBatch(player, batch, tally);
      sent = tally.sent;
      viewChangeQuota.release(playerId, tick, MAX_VIEW_CHANGE_SENDS_PER_TICK, granted, sent);
      if (exhausted) {
        stats.viewChangeBudgetExhausted.increment();
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
    } finally {
      long elapsed = System.nanoTime() - start;
      stats.viewChangeScans.increment();
      stats.viewChangeNanos.add(elapsed);
      long current = stats.viewChangeMaxNanos.get();
      while (elapsed > current && !stats.viewChangeMaxNanos.compareAndSet(current, elapsed)) {
        current = stats.viewChangeMaxNanos.get();
      }
    }
  }

  /** 候选坐标打包（仅供聚合显形去重；与 {@link RevealedSet} 同一编码：x/z 各 26 位、y 12 位）。 */
  private static long packCoordinate(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
  }

  /**
   * 纯数据判定：坐标仍在伪装清单（已伪装）且该玩家尚未显形过（与周期显形不重复、同 tick 不重复）。
   *
   * <p>两条查询都用「世界名 + 分量」的零分配入口：{@link ObfuscatedChunkIndex#containsPosition(String, int, int, int)}
   * （内部复用 {@link ChunkKey.Probe}）与 {@link RevealedSet#contains(UUID, String, int, int, int, int, int)}。
   * 旧实现每个候选 new 两个 {@link ChunkKey}（一个满载 section 事件可达上千个短命对象，且全在玩家所属线程）。
   */
  boolean isInstantCandidate(UUID playerId, String worldName, int x, int y, int z) {
    if (!chunkIndex.containsPosition(worldName, x, y, z)) {
      return false;
    }
    return !revealedSet.contains(playerId, worldName, x >> 4, z >> 4, x, y, z);
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
   * <p>槽位 {@code long[2] = [tick, 剩余额度]}，tick 变化即自动重置为满额；额度由 {@link #claim} 在
   * <b>同一临界区</b>内「读剩余 + 预扣」，因此即便同一玩家的事件显形在 Folia 上被不同区域线程并发触发，
   * 限额也不会被成倍突破。条目随玩家退出精确清理（见 {@link #onQuit}）。
   */
  static final class TickQuota {

    private final ConcurrentHashMap<UUID, long[]> slots = new ConcurrentHashMap<>();

    /**
     * 原子地「取本 tick 剩余额度 + 预扣 {@code wanted}」：返回实际可用的额度（{@code 0..wanted}）。
     *
     * <p><b>为什么必须一次完成</b>：若拆成「先读剩余 → 发包 → 写回剩余」，那是读-改-写三步，中间没有
     * 任何互斥。Folia 上同一玩家的事件显形可能由不同区域线程并发触发（单包路径与 section 批量路径），
     * 两次调用都读到满额 → 限额被突破到 {@code maxPerTick × 并发数}，防爆刷失效；后写回者还会把
     * 先写回者的扣减覆盖掉。
     *
     * <p>语义：本 tick 未发完的额度<b>不退还</b>——预扣即代表「本 tick 这一份额度已被占用」，
     * 方向保守（只会少发、绝不多发）。调用方若本次一个包都没发（{@code spent == 0}），可用
     * {@link #release} 撤销这次预扣，避免让「路过但没发包」的事件白占一个 tick 的份额。
     *
     * @param wanted 本次希望领取的额度上限
     * @return 本次实际可用的额度；调用方据此创建 {@link Budget}
     */
    int claim(UUID player, long tick, int maxPerTick, int wanted) {
      long[] slot = slots.computeIfAbsent(player, ignored -> new long[2]);
      synchronized (slot) {
        int available = slot[0] == tick ? (int) slot[1] : maxPerTick;
        int granted = Math.max(0, Math.min(wanted, available));
        slot[0] = tick;
        slot[1] = available - granted;
        return granted;
      }
    }

    /**
     * 撤销一次「领取后一个包都没发」的预扣（{@code spent} 为实际发出的包数）。
     *
     * <p>只回补本次领走的份额，且只在本 tick 内生效（tick 已推进时槽位表示的是新 tick 的额度，
     * 不得把上一 tick 的剩余加回去）。槽位回补到满额时顺手摘除条目，避免「只扫描不显形」的玩家
     * 在表里留下空槽位。
     */
    void release(UUID player, long tick, int maxPerTick, int granted, int spent) {
      int unused = granted - Math.max(0, spent);
      if (unused <= 0) {
        return;
      }
      long[] slot = slots.get(player);
      if (slot == null) {
        return;
      }
      synchronized (slot) {
        if (slot[0] != tick) {
          return;
        }
        long restored = Math.min(maxPerTick, slot[1] + unused);
        slot[1] = restored;
        if (restored >= maxPerTick) {
          slots.remove(player, slot);
        }
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