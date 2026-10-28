package net.mikumc.mikuxraynet.bandwidth;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.antixray.ProximitySelector;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import net.mikumc.mikuxraynet.occlusion.OcclusionEngine;
import net.mikumc.mikuxraynet.occlusion.OcclusionStats;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * 实体射线剔除：被不透明方块完全遮挡的实体对玩家隐藏，节省下行的实体跟踪带宽。
 *
 * <p><b>唯一的判定路径（异步位图）</b>——数据源是「反矿透解码时旁路生成的区块遮挡位图」
 * （{@link OcclusionStores}），射线由 {@link OcclusionEngine} 的<b>专用池</b>执行（纯内存步进，零 Bukkit
 * 访问），原生命中射线（{@code World#rayTraceBlocks}）已彻底移除：
 * <ol>
 *   <li>实体所属线程：只抓几何快照（眼睛 + 包围盒候选顶点），组成本质为纯数值的
 *       {@link OcclusionEngine.Request} 入队（{@link #submitAsync}）；</li>
 *   <li>专用池线程：按世界取该区块的遮挡位图逐顶点步进判定（{@link OcclusionEngine}）；</li>
 *   <li>回投：经 {@code Schedulers.onEntity} 跳回实体所属线程，逐条校验
 *       （在线 / 归属 / 世界未变 / 实体有效）后再 hide/show（{@link #publishAsync}）。</li>
 * </ol>
 * 因此本模块<b>不在实体线程读任何方块</b>，也就<b>没有条数封顶</b>（每周期提交全部追踪实体，收敛延迟
 * 不随实体数线性增长）；射线 CPU 由专用池的<b>时间预算</b>封顶（超预算者顺延到下一周期，不丢判定）。
 * 代价是引入「结果陈旧窗口」，由截止时间丢弃 + 世界作废守卫 + 可选防抖（{@code occlusion-hysteresis-cycles}）
 * 压制。
 *
 * <p><b>位图缺失时只做视锥剔除</b>：反矿透是本插件的主打功能、恒处于开启状态，因此遮挡位图正常情况下
 * 总是可用（未接线 = 引擎启动失败等罕见故障）。此时射线遮挡判定整体跳过（fail-open：不隐藏），
 * 仅保留下面的视锥剔除——这与「无数据 = 不遮挡 = 少藏」的安全方向一致，绝不误藏实体。
 *
 * <p>安全策略：强制可见距离内的实体一律可见；只有当包围盒的所有可见顶点射线都被遮挡时才隐藏
 * （宁可少隐藏，也不隐藏可见实体）；玩家自身、其它玩家与烟花不做剔除。
 * 「强制可见距离内的恢复」是<b>实体线程上的同步 {@code show}</b>，绝不经过异步链路，
 * 因此近身安全下限不受异步延迟影响。
 *
 * <p><b>视锥剔除（{@code entity-culling.frustum}）</b>：在「被墙完全挡住」之外，再剔除
 * 「位于视野锥之外、且在距离门之外」的实体（玩家背后 / 侧后根本看不到的实体），连射线都省下。
 * 视锥数学直接复用邻近显形的 {@link ProximitySelector#withinFrustum}，保证与反矿透侧行为一致；
 * 距离门取 {@code max(frustum.min-distance, force-visible-distance)}，因此近身实体绝不会因视锥被剔除
 * （与「强制可见距离内一律可见」这条安全策略一致）。
 *
 * <p><b>视锥隐藏的恢复为什么要单独记账</b>：实体在锥外只说明「此刻看不到」，玩家一转头就会重新看到，
 * 而周期复检的通道①对远距离实体一律不打射线（成本有界）。若视锥隐藏与射线隐藏共用一套账本，
 * 通道①就无从区分「该在回到视野内时恢复」与「该等它靠近再恢复」。因此账本为每条记录带上
 * {@code frustumCulled} 标记：只有视锥隐藏的条目才在通道①里做<b>纯数学</b>的锥内复判，一旦实体重新
 * 回到视野锥内（或不再满足距离门）立刻恢复显示——这就是「转头后 ≤ 1 个复检周期补发」的实现。
 *
 * <p><b>周期复检的两条通道</b>（修复「先可见、之后才被挡住」实体永不隐藏的缺陷）：
 * <ol>
 *   <li><b>通道①·已隐藏实体</b>：每周期全部复检，但只做<b>近距离恢复</b>——实体重新进入强制
 *       可见距离时 {@code showIfHidden}；超出该距离一律不打射线（成本有界，见 {@link RecheckMode}）。
 *       额外只对<b>视锥隐藏</b>的条目做一次纯数学的锥内复判（不打射线）：一旦它回到视野锥内立即恢复，
 *       保证「转头后 ≤ 1 个复检周期重新出现」。</li>
 *   <li><b>通道②·全部追踪实体（含已隐藏者）</b>：每周期提交<b>全部</b>追踪实体（无条数封顶——
 *       射线在专用池里跑，成本由时间预算封顶）。承担两件事：<b>①</b> 把「入场时可见、之后才被墙/地形
 *       挡住」的实体收敛到隐藏；<b>②</b> 把「曾因遮挡被隐藏、之后遮挡物被炸开/挖开」的实体恢复可见
 *       ——这是远距离已隐藏实体唯一的再评估入口（通道①对远距离条目不射线）。</li>
 * </ol>
 * 两条通道都复用同一套评估链路（提交快照 → 专用池判定 → 回投 hide/show），不另写一套。
 * <b>距离闸门</b>：任何通道内、处于强制可见距离内的实体一律可见（{@code showIfHidden}），绝不隐藏——
 * 这是既有的安全策略。入场那一刻的首评（{@code onTrack}）不受距离闸门限制，故「远距离实体照常被剔除」
 * 这一行为不变。
 */
public final class EntityCuller implements Listener {

  /** 单个小体积实体（如物品、投掷物）只取中心顶点即可。 */
  private static final double SMALL_BOX = 0.25D;

  /** 候选顶点上限（与配置项的钳制上限同源，见 {@link BandwidthConfig#MAX_RAY_SAMPLES}）。 */
  private static final int MAX_RAY_SAMPLES = BandwidthConfig.MAX_RAY_SAMPLES;

  /**
   * 单条遮挡射线的最大长度（格）。射线只用于判断「眼睛与实体之间是否有遮挡方块」；实体一旦超出该长度，
   * 说明它已远超常见实体追踪距离（通常 48~128 格），此时把射线截断只会退回 fail-open（判为通畅、保持可见），
   * 绝不误藏。加上限是为了避免「玩家走远、实体仍留在常加载区块」时每条射线都射向极远处带来的无谓开销。
   */
  private static final double MAX_RAY_LENGTH = 128.0D;

  private final Plugin plugin;
  private final BandwidthConfig.EntityCulling config;
  private final ThrottleStats stats;
  /** 实体归属判定（Folia 跨区域实体不得触碰；见 {@link EntityOwnership}）。 */
  private final EntityOwnership ownership;
  /** 直通判定（只读 BypassRegistry 的登录期快照；见 {@link BypassLookup}）。 */
  private final BypassLookup bypass;
  /**
   * 遮挡数据仓库注册表（按世界分域）；{@code null} = 引擎启动失败等罕见故障（此时跳过射线遮挡判定，
   * 只做视锥剔除，见类注释）。
   */
  private final OcclusionStores occlusionStores;
  /** 遮挡计数（引擎与诊断共用）。 */
  private final OcclusionStats occlusionStats;
  /**
   * 异步遮挡引擎；{@link #start()} 时创建、{@link #stop()} 时关闭，{@code null} = 引擎未起（罕见）。
   *
   * <p><b>为什么是 volatile</b>：工作线程的 {@code ResultSink} 会读它；停用/热重载会在命令线程把它置空
   * 并关闭。读侧只需判断「引擎是否还在」，读到一个过期引用也不会出错（引擎自身在 {@code close()} 后
   * 拒收任务、且回投前会再校验 epoch/状态）。
   */
  private volatile OcclusionEngine engine;
  /** 防抖：同一实体的判定需连续该周期数一致才允许切换显示状态（0/1 = 立即，见配置说明）。 */
  private final int hysteresisCycles;
  /** 结果超时丢弃窗口（纳秒）= {@code occlusion-timeout-cycles × 提交周期}。 */
  private final long timeoutNanos;
  /** 本周期引擎的时间预算（纳秒）= {@code occlusion-time-budget-ms × 1e6}；{@code 0} = 引擎未接线。 */
  private final long budgetNanos;
  private final double forceVisibleSquared;
  /** 视锥剔除子项是否启用。 */
  private final boolean frustumCulling;
  /** 视锥竖直张开全角（度），直接交给 {@link ProximitySelector#withinFrustum}。 */
  private final double frustumFov;
  /** 视锥剔除距离门的平方（格²）＝ {@code max(frustum.min-distance, force-visible-distance)}²。 */
  private final double frustumGateSquared;
  private final AtomicInteger errorCounter = new AtomicInteger();

  /**
   * 账本条目：被隐藏的实体 + 它是否「因视锥剔除而隐藏」。
   *
   * <p>标记决定周期复检通道①如何对待远距离条目：{@code frustumCulled=true} 的条目在实体重新回到
   * 视野锥内时要立刻恢复（纯数学复判）；{@code false}（射线遮挡隐藏）的条目保持既有行为（等实体靠近）。
   * 没有这个标记就无从区分，只能二选一，必然造成「转头后远处实体长期不出现」或「远距离射线成本无界」。
   *
   * <p><b>刻意不缓存 entityId</b>：账本键本身就是 id（跨区域时读实体取 id 会触发 Folia 线程校验），
   * 而跨区域条目<b>不得摘除</b>（见 {@code recheck} 通道①的说明），因此从未有「不触碰实体地按 id 删」
   * 这种需求——多存一个字段只会让每次隐藏都多一次读实体。
   */
  record HiddenEntry(Entity entity, boolean frustumCulled) {
  }

  /** 玩家 → 已被隐藏的实体（entityId → {@link HiddenEntry}）。 */
  private final ConcurrentHashMap<UUID, Map<Integer, HiddenEntry>> hidden = new ConcurrentHashMap<>();
  /** 玩家 → 该玩家当前追踪的实体轮转队列（周期复检切分片用，见 {@link TrackedRotation}）。 */
  private final ConcurrentHashMap<UUID, TrackedRotation> rotations = new ConcurrentHashMap<>();
  /**
   * 玩家 → (实体 id → 最近判定与连续次数)，异步路径的防抖账本（仅 {@code hysteresisCycles ≥ 2} 时使用）。
   *
   * <p><b>为什么需要它</b>：异步判定天然可能「刚好被挡 / 刚好可见」反复摇摆，直接按每条结果切换
   * hide/show 会出现闪烁。要求同一结论连续 N 个周期才允许切换即可压住抖动。
   * {@code hysteresisCycles ≤ 1}（默认）时本表<b>完全不使用</b>（零开销，见 {@link #publishAsync}）。
   *
   * <p>条目随 {@code onUntrack} / {@code onQuit} / 实体失效 / 停用清理，不会无界增长。
   */
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, VerdictStreak>> streaks =
      new ConcurrentHashMap<>();
  /**
   * 玩家 → (实体 id → 已应用的判定提交序号)。用于丢弃「后完成但更旧」的异步结果。
   *
   * <p><b>为什么需要它</b>：{@code occlusion-threads ≥ 2} 时专用池并发处理同一实体的多份判定，结果按
   * <b>完成顺序</b>回投——先提交的旧结论可能后完成。旧结论若照常应用，会把新结论已经写好的显示状态
   * 覆盖回去（典型表现：刚判定为可见而恢复的实体又被打回隐藏，违反「实体绝不能被误隐藏」）。
   * 提交序号单调递增，{@link #publishAsync} 只应用不小于已应用序号的结论，旧结论整条丢弃
   * （不改变显示状态，与超时丢弃 / 拒收同一语义）。
   *
   * <p>条目随 untrack / 失效 / 退出 / 停用清理，不会无界增长。
   */
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, Long>> appliedSeq =
      new ConcurrentHashMap<>();
  /** 判定提交序号计数器（唯一用途见 {@link #appliedSeq}）。 */
  private final AtomicLong submitSeqCounter = new AtomicLong();
  /**
   * 「无序号」哨兵：仅供单测直接驱动回投的兼容入口使用（不做序判定、也不写序号账本）。
   *
   * <p>生产路径一律走带 {@code submitSeq} 的重载，否则乱序保护失效。
   */
  private static final long NO_SUBMIT_SEQ = Long.MIN_VALUE;

  /** 防抖账本条目：最近一次判定结论与已连续一致的周期数（只在玩家/实体所属线程上读写）。 */
  private static final class VerdictStreak {
    private boolean verdict;
    private int count;

    /** 记入一次新判定，返回「是否已达到可切换所需的连续次数」。 */
    boolean record(boolean next, int required) {
      if (next == verdict) {
        if (count < required) {
          count++;
        }
      } else {
        verdict = next;
        count = 1;
      }
      return count >= required;
    }
  }

  /** 每个在线玩家的周期复检任务（Paper 与 Folia 同一套实体调度器；玩家退出即随实体退役失效）。 */
  private final ConcurrentHashMap<UUID, ScheduledTask> recheckTasks = new ConcurrentHashMap<>();
  /**
   * 热重载恢复链路（每个「账本被 drain 但尚未恢复」的实体一条）：{@code stop()} 必须把它们一起取消。
   *
   * <p><b>为什么必须登记</b>：这些链会在热重载后继续每秒 {@code showEntity} 直到该实体跨回玩家所属区域。
   * 若只存在局部变量里，第二次热重载就无法取消上一代——旧链仍以 1 Hz 对同一实体反复下发显示包，
   * 且随着每次重载叠加一条。
   */
  private final Set<ScheduledTask> restoreTasks = ConcurrentHashMap.newKeySet();
  /** 「停用时恢复失败」的中文 WARN 一次性闸门。 */
  private final AtomicBoolean restoreFailureNoticed = new AtomicBoolean();
  /** 「停用时存在跨区域被隐藏实体（Folia）无法同步恢复」的中文 WARN 一次性闸门。 */
  private final AtomicBoolean crossRegionRestoreNoticed = new AtomicBoolean();
  /**
   * 停用中标记：{@code stop()} 首行置位，用于闭合停用期并发——正在跑的复检若在此之后才走到
   * {@code hide()}，会被拒绝，绝不把实体重新藏回（否则刚刚 {@code restoreAll()} 恢复过的实体会再次不可见）。
   */
  private volatile boolean stopping;

  public EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats) {
    this(plugin, config, stats, EntityCuller::ownedByCurrentRegion);
  }

  /** 完整构造（追加实体归属判定；离线单测可注入假实现，见 {@link EntityOwnership}）。 */
  EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats,
      EntityOwnership ownership) {
    this(plugin, config, stats, ownership, BypassRegistry::isBypassedNow);
  }

  /**
   * 完整构造（追加实体归属判定与直通判定；离线单测可对两者注入假实现）。
   *
   * <p><b>为什么直通判定默认走 {@link BypassRegistry#isBypassedNow} 这份静态共享名单，而不是把
   * {@link BypassRegistry} 穿透装配链</b>：带宽侧其余模块（{@code EntityPacketFilter} /
   * {@code BlockChangeMerger} / {@code AfkTracker}）已统一读这份共享快照，本类保持一致；直通名单由
   * {@code MikuXrayNet} 持有、只在登录/启用时做一次快照，带宽模块只做一次纯内存读，为此再扩大
   * {@code ThrottlePipeline} → {@code MikuXrayNet} 的构造签名不划算。本构造的注入点仅为离线单测提供
   * （同 {@link EntityOwnership} 的做法）。
   */
  EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats,
      EntityOwnership ownership, BypassLookup bypass) {
    this(plugin, config, stats, ownership, bypass, null, null);
  }

  /** 生产装配入口（追加遮挡仓库与计数）。 */
  EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats,
      OcclusionStores occlusionStores, OcclusionStats occlusionStats) {
    this(plugin, config, stats, EntityCuller::ownedByCurrentRegion, BypassRegistry::isBypassedNow,
        occlusionStores, occlusionStats);
  }

  /**
   * 完整构造（追加遮挡仓库与计数；离线单测可对归属 / 直通 / 遮挡数据三者全部注入假实现）。
   *
   * <p>{@code occlusionStores}/{@code occlusionStats} 两者要么同时给出，要么同时为 {@code null}：
   * 任一缺失都视为「引擎不可用」，此时跳过射线遮挡判定（只做视锥剔除），绝不半接线（否则会出现
   * 「引擎起来了却没有数据源」这类难查的静默失效）。
   */
  EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats,
      EntityOwnership ownership, BypassLookup bypass,
      OcclusionStores occlusionStores, OcclusionStats occlusionStats) {
    this.plugin = plugin;
    this.config = config;
    this.stats = stats;
    this.ownership = ownership;
    this.bypass = bypass == null ? BypassRegistry::isBypassedNow : bypass;
    this.forceVisibleSquared = config.forceVisibleDistance() * config.forceVisibleDistance();
    BandwidthConfig.EntityCulling.Frustum frustum = config.frustum();
    this.frustumCulling = frustum != null && frustum.enabled();
    this.frustumFov = frustum == null ? 0.0D : frustum.fov();
    // 距离门 = max(视锥距离门, 强制可见距离)：强制可见距离内一律不剔除是硬安全策略，
    // 因此视锥距离门配得更小时只会被强制可见距离兜住，绝不会削弱安全策略。
    double gate = frustum == null
        ? config.forceVisibleDistance()
        : Math.max(frustum.minDistance(), config.forceVisibleDistance());
    this.frustumGateSquared = gate * gate;
    // 遮挡接线：仓库与计数齐备、且确实要做射线判定时才算「已接线」（见字段说明）。
    BandwidthConfig.EntityCulling.AsyncOcclusion async = config.asyncOcclusion();
    boolean wired = async != null && config.raycast()
        && occlusionStores != null && occlusionStats != null;
    this.occlusionStores = wired ? occlusionStores : null;
    this.occlusionStats = wired ? occlusionStats : null;
    this.hysteresisCycles = wired ? Math.max(0, async.hysteresisCycles()) : 0;
    long intervalNanos = Math.max(1, config.updateIntervalTicks()) * NANOS_PER_TICK;
    this.timeoutNanos = wired ? Math.max(1, async.timeoutCycles()) * intervalNanos : 0L;
    this.budgetNanos = wired ? Math.max(0L, (long) (async.timeBudgetMillis() * 1_000_000.0D)) : 0L;
  }

  /** 一 tick 的纳秒数（超时窗口与提交周期都以 tick 计，这里统一换算）。 */
  private static final long NANOS_PER_TICK = 50_000_000L;

  /**
   * 异步遮挡专用池的任务队列容量。
   *
   * <p><b>为什么给这么大</b>：异步路径每周期提交「该玩家全部追踪实体」（没有条数封顶），队列容量必须
   * 明显大于「在线玩家 × 追踪实体数」的常见规模，否则会频繁拒收（拒收 = 保留原显示状态，属安全方向，
   * 但会拖慢收敛）。容量本身只是内存上界（每条任务不到 100 字节），4096 条已足以吸收数秒的尖峰。
   * 真机上若观测到 {@code rejectedQueueFull} 持续增长，说明引擎线程跟不上（应调大预算/线程数）。
   */
  private static final int OCCLUSION_QUEUE_CAPACITY = 4096;

  /**
   * 本模块是否需要运行（射线剔除与视锥剔除任一开启）。
   *
   * <p>两者共用同一套「追踪登记 + 周期复检 + 线程归属」骨架，因此只要有一个开启就必须注册并调度；
   * 两个都关时本模块完全不注册（由 {@code ThrottlePipeline.plan} 决定，零开销）。
   */
  private boolean active() {
    return config.raycast() || frustumCulling;
  }

  /**
   * 实体归属判定：该实体当前是否由本线程所属区域拥有（Paper 上恒为 true）。
   *
   * <p><b>为什么必须在「碰之前」先问</b>：Folia 上读取非本区域实体的状态会走
   * {@code TickThread.ensureTickThread} 校验——它<b>先打一条 ERROR 日志再抛异常</b>，所以 try/catch
   * 兜住并不能避免刷屏（真机 Lophine 26.3 上表现为每周期一次 ERROR + 任务异常）。
   * 周期复检跑在玩家所在区域线程，而玩家走远/传送后，其已隐藏列表里的实体可能已在别的区域，
   * 甚至已被服务端 DISCARDED。
   */
  @FunctionalInterface
  public interface EntityOwnership {
    boolean isOwnedByCurrentRegion(Entity entity);
  }

  /** 生产实现：{@link Bukkit#isOwnedByCurrentRegion(Entity)}（内部用 getHandleRaw + TickThread 判定，不写日志）。 */
  private static boolean ownedByCurrentRegion(Entity entity) {
    try {
      return Bukkit.isOwnedByCurrentRegion(entity);
    } catch (Throwable throwable) {
      // 判定不可用（离线单测无服务端实例等）：按「属于本区域」处理，退回本改动之前的行为
      return true;
    }
  }

  /**
   * 直通判定查询：读「登录期快照」的线程安全只读入口，生产实现为 {@link BypassRegistry#isBypassedNow}。
   *
   * <p><b>语义（与全局一致，请勿当 bug「修」回去）</b>：直通权限只在玩家<b>进入服务器</b>时判定一次，
   * 运行期由权限插件授予 / 收回 {@code mikuxraynet.bypass} 都<b>不会即时生效</b>，必须重新进入服务器
   * （退出→重登）才按新权限重算（详见 {@link BypassRegistry}）。因此「复检恢复可见」只对
   * <b>登录时已带权限</b>的玩家生效，这是既定语义而非缺陷。
   *
   * <p><b>线程纪律</b>：读侧只做一次并发集合查询，是纯内存读、不触碰任何 Bukkit API，因此可安全用于
   * Folia 区域线程上的周期复检；本类<b>不做任何周期性权限查询</b>。
   *
   * <p><b>失败语义 fail-open</b>：名单未装配 / 已停用时 {@link BypassRegistry#isBypassedNow} 返回
   * false（按「无直通」处理，与既有兜底语义一致）。
   */
  @FunctionalInterface
  interface BypassLookup {
    boolean isBypassed(UUID playerId);
  }

  /**
   * 周期复检通道语义：决定「实体超出强制可见距离时」是否还做遮挡判定。
   *
   * <p><b>为什么必须显式区分</b>：本轮修复前用单一的 {@code fromRecheck} 布尔同时承担了「计数归属」与
   * 「超出强制可见距离后不再判定」两件事，导致<b>两条</b>复检通道都在到达 {@code evaluate} 前提前返回，
   * 实体一旦「入场时可见、之后才被挡住」就再也不会被隐藏，{@code recheckHidden} 恒为 0（与类注释矛盾）。
   */
  private enum RecheckMode {
    /** 入场首评（{@code onTrack}）：不受距离闸门限制，远距离实体照常判定并可能隐藏。 */
    INITIAL,
    /**
     * 通道①·已隐藏实体复检：只做「强制可见距离内的恢复」，超出距离一律不提交判定。
     * 这些账本条目可能随玩家走远而长期滞留，若每周期都提交则成本随会话无界增长。
     */
    RESTORE_ONLY,
    /**
     * 通道②·轮转复检：提交全部追踪实体，允许对超出强制可见距离的实体做遮挡判定——既能隐藏「先可见、
     * 之后才被挡住」的实体，也能恢复「遮挡物已消失」的已隐藏实体（方向由判定结果决定）；成本由专用池的
     * 时间预算封顶（超预算者顺延到下一周期）。
     */
    ROTATION
  }

  /** 注册事件监听与周期复检任务。 */
  public void start() {
    // 异步引擎必须先于周期任务创建：首个复检周期就会向它提交（见 submitAsync）。
    startEngine();
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    long interval = Math.max(1, config.updateIntervalTicks());
    // 与 onJoin 保持一致：两个行为开关（raycast / frustum）全关时不登记任何周期复检任务——
    // recheck 首行即因 !active() 返回，若仍为每个在线玩家挂一条任务，只是每周期空跑的纯调度开销。
    if (active()) {
      // 统一为「每个玩家一条实体调度任务」：Paper 上落在主线程、Folia 上落在区域线程（同一套 API，无需分支）
      for (Player player : Bukkit.getOnlinePlayers()) {
        scheduleRecheck(player, interval);
      }
    }
    // 位图仓库未接线（反矿透未启用 → 没有遮挡位图）时射线遮挡判定整体跳过：这是「实体不再被墙后剔除」
    // 的功能受损，必须 WARNING 点名。引擎自身创建失败的情况由 startEngine() 另行 WARN（此处不重复报）。
    if (config.raycast() && occlusionStores == null) {
      plugin.getLogger().warning("实体剔除：遮挡位图不可用（反矿透未启用），"
          + "射线遮挡判定已跳过，仅做视锥剔除——墙后的实体不会被剔除（少藏而非误藏）");
    }
    plugin.getLogger().fine("带宽模块已启用：实体剔除（强制可见距离 " + config.forceVisibleDistance()
        + " 格；射线遮挡 " + (config.raycast()
            ? raycastReady()
                ? "开（遮挡位图：专用池 " + engineWorkerCount() + " 线程，每周期时间预算 "
                    + formatBudgetMillis() + " ms/玩家，无条数封顶）"
                : "开（遮挡位图不可用：本轮跳过射线遮挡判定，仅做视锥剔除）"
            : "关")
        + "；视锥剔除 " + (frustumCulling
            ? "开（距离门 " + Math.sqrt(frustumGateSquared) + " 格，FOV " + frustumFov + "°）" : "关")
        + "）");
  }

  /**
   * 注销监听、恢复全部被隐藏实体，并关闭异步引擎。
   *
   * <p><b>顺序不可调</b>：先置 {@code stopping} 闭合并发 → 取消在途任务 → 注销监听 → 同步恢复 →
   * 最后才关引擎。引擎放在最后关，保证「取消任务之后、引擎结束之前」不会有新任务被投进来
   * （{@code stopping} 已拒绝一切新隐藏，且在途回投会先被 {@link #publishAsync} 的状态校验挡掉）。
   */
  public void stop() {
    // 先取消在途任务、再同步恢复：Bukkit 的 setEnabled(false) 先置 isEnabled=false 再调 onDisable，
    // 因此停用链路里的 EntityScheduler 一律抛 IllegalPluginAccessException（旧实现被 Schedulers 静默吞掉，
    // 导致恢复完全无声）。这里改为「先 cancel 在途任务闭合并发 → 检测到已停用则同步恢复」。
    stopping = true;
    cancelRecheckTasks();
    // 先取消上一代的重载恢复链，再由 restoreAll() 重新起本代（顺序不可颠倒，否则会取消刚建的那条）
    cancelRestoreTasks();
    HandlerList.unregisterAll(this);
    restoreAll();
    rotations.clear();
    streaks.clear();
    appliedSeq.clear();
    closeEngine();
  }

  /**
   * 创建遮挡判定引擎（仅当装配方给出了仓库与计数时）。
   *
   * <p>引擎自带专用线程池（线程名 {@code MikuXrayNet-Occlusion-N}）：<b>刻意与反矿透改写池隔离</b>——
   * 改写有 50 ms 级预算，共享池会造成互相饿死。创建失败只打一次中文 WARN（此时射线遮挡判定整体跳过，
   * 仅保留视锥剔除）。
   *
   * <p>包可见：供离线单测直接起引擎（{@link #start} 依赖 Bukkit 的事件注册，离线不可用）。
   */
  void startEngine() {
    OcclusionStores stores = this.occlusionStores;
    OcclusionStats counters = this.occlusionStats;
    if (stores == null || counters == null) {
      return;
    }
    try {
      BandwidthConfig.EntityCulling.AsyncOcclusion async = config.asyncOcclusion();
      engine = new OcclusionEngine(stores, counters, async.threads(), async.maxOccludingBlocks(),
          MAX_RAY_LENGTH, OCCLUSION_QUEUE_CAPACITY);
    } catch (Throwable throwable) {
      engine = null;
      plugin.getLogger().log(Level.WARNING,
          "遮挡判定引擎启动失败，射线遮挡判定已跳过（仅做视锥剔除；不影响实体剔除的其它行为）", throwable);
    }
  }

  /**
   * 关闭异步引擎（可重复调用）；引擎停机后其工作线程不再回投，因此必须排在恢复之后（见 {@link #stop()}）。
   *
   * <p>包可见：供离线单测在不起完整停用链路的前提下回收引擎线程。
   */
  void closeEngine() {
    OcclusionEngine current = engine;
    engine = null;
    if (current != null) {
      try {
        current.close();
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "关闭异步遮挡引擎时出现异常（已强制结束）", throwable);
      }
    }
  }

  /** 启动摘要里的引擎线程数（未接线时为 0，仅用于日志文案）。 */
  private int engineWorkerCount() {
    OcclusionEngine current = engine;
    return current == null ? 0 : current.workerCount();
  }

  /** 启动摘要里的时间预算文案（毫秒，最多一位小数）。 */
  private String formatBudgetMillis() {
    return String.format(java.util.Locale.ROOT, "%.1f", budgetNanos / 1_000_000.0D);
  }

  /** 射线遮挡判定是否可用（引擎已起 + 数据源已接线；{@link #stop()} 后恒为 false）。 */
  private boolean raycastReady() {
    return engine != null && occlusionStores != null;
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    if (!active()) {
      return;
    }
    scheduleRecheck(event.getPlayer(), Math.max(1, config.updateIntervalTicks()));
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    cancelRecheckTask(player.getUniqueId());
    // 退出无需恢复：hideEntity 是「每玩家」的可见性状态，玩家一断开连接，服务端即随实体释放该状态，
    // 再逐个 showEntity 纯属徒劳（还要在实体所属线程逐个执行）。这里只清账本，语义与「离线玩家
    // 判定只清账本、不产生恢复计数」一致（见 evaluate 的失效/离线分支）。
    hidden.remove(player.getUniqueId());
    rotations.remove(player.getUniqueId());
    // 防抖账本与提交序账本随退出整体丢弃（玩家已离线，任何判定结果都不可能再用于切换显示状态）
    streaks.remove(player.getUniqueId());
    appliedSeq.remove(player.getUniqueId());
  }

  /**
   * 恢复某玩家当前被隐藏的实体（复检发现该玩家直通 / 身处黑名单世界时使用）。
   *
   * <p><b>为什么不能先整表摘走、再逐条发</b>：Folia 上玩家跨区域 / 跨世界（进黑名单世界、直通分支、
   * 传送）时，账本里的实体可能已落在别的区域线程。旧实现先用 {@link #drainPlayer} 把整张表摘走，再对
   * {@code !owns(entity)} 的条目直接跳过——这些条目既没有下发 {@code showEntity}，账本里也不再有任何
   * 记录；等玩家回到该区域，{@code showIfHidden} 再也找不到它，实体对该玩家<b>永久不可见</b>（直到重登）。
   *
   * <p>因此这里按归属分成两组：<b>本区域</b>的条目就地恢复——{@code showEntity} 成功才摘账本，失败则
   * 保留条目等下一轮重试（与 {@link #showIfHidden} 的「写入成功才删原值」同一语义）；<b>跨区域</b>的条目
   * <b>原样保留</b>，本线程一次都不碰它。跨区域条目不在本线程补发，是因为 {@code showEntity} 同时作用于
   * 玩家连接与实体两侧，而本线程既不拥有该实体、也不保证拥有玩家所在区域（Folia 会先打 ERROR 再抛）；
   * 等归属变回 true 后，本方法（直通 / 黑名单玩家每周期都会走到这里）与通道①自然会补发。
   * 保留条目的代价是会话内有界的（玩家退出、插件停用都会整体清空），远小于「条目没了、包也没发」。
   */
  private void restorePlayer(Player player) {
    UUID playerId = player.getUniqueId();
    Map<Integer, HiddenEntry> map = hidden.get(playerId);
    if (map == null) {
      return;
    }
    for (HiddenEntry entry : map.values()) {
      Entity entity = entry.entity();
      if (!owns(entity)) {
        // 跨区域（Folia）：不能读它的状态，更不能摘条目（见方法注释）；归属恢复后由后续复检补发
        continue;
      }
      if (!entity.isValid()) {
        // 实体已被服务端回收：只摘账本——玩家的隐藏状态随实体一同释放，既无需也无法再 showEntity
        removeLedgerEntry(playerId, entity);
        continue;
      }
      // 先真正下发 showEntity、成功后才摘条目：下发失败时条目留在账本里，下一轮复检会重试
      showIfHidden(player, entity);
    }
  }

  /**
   * 当前处于隐藏中的实体总数（诊断用实时值）。
   *
   * <p><b>为什么需要它</b>：{@code entitiesHidden} 与 {@code entitiesShown} 是累计计数，两者天然不等——
   * 实体死亡 / 区块卸载 / 离场会被服务端自然回收，此时**不需要**也不应该再发 showEntity；
   * 只有「当前隐藏中」这个实时值才能让两个累计数自证（累计隐藏 = 累计恢复 + 当前隐藏中 + 自然回收）。
   */
  public int hiddenCount() {
    int total = 0;
    for (Map<Integer, HiddenEntry> map : hidden.values()) {
      total += map.size();
    }
    return total;
  }

  /**
   * 遮挡判定引擎是否已真正接线（引擎已起 + 数据源可用）——诊断用；未接线时为 {@code false}。
   *
   * <p>正常情况下恒为 true（遮挡判定是本模块唯一的射线路径）。为 false 表示引擎启动失败等罕见故障，
   * 此时射线遮挡判定整体跳过、仅做视锥剔除——诊断据此如实回显。
   */
  public boolean asyncOcclusionActive() {
    return raycastReady();
  }

  /** 遮挡判定专用池当前排队的判定数（诊断用）；未接线时为 0。 */
  public int occlusionQueueDepth() {
    OcclusionEngine current = engine;
    return current == null ? 0 : current.queueDepth();
  }

  /** 取出并从登记表移除某玩家的全部隐藏实体（玩家退出 / 插件停用时的全量恢复入口）；无记录返回空表。 */
  Map<Integer, HiddenEntry> drainPlayer(UUID playerId) {
    Map<Integer, HiddenEntry> map = hidden.get(playerId);
    if (map == null) {
      return Map.of();
    }
    Map<Integer, HiddenEntry> drained = new LinkedHashMap<>(map);
    hidden.remove(playerId, map);
    return drained;
  }

  /** 为单个玩家登记周期复检任务；同一玩家重复登记时以最新任务为准（旧任务取消）。 */
  private void scheduleRecheck(Player player, long interval) {
    ScheduledTask task = Schedulers.repeatOnEntity(plugin, player, interval, interval,
        () -> recheck(player));
    if (task == null) {
      return;
    }
    ScheduledTask previous = recheckTasks.put(player.getUniqueId(), task);
    if (previous != null) {
      previous.cancel();
    }
    // 与 hide() 同一套「入账后再读一次 volatile」收口：若 stop() 的取消循环已在我们入表<b>之前</b>
    // 跑完，它永远看不到这条任务，必须由我们自己取消（否则任务空跑到玩家退出并钉住旧实例）。
    if (stopping) {
      recheckTasks.remove(player.getUniqueId(), task);
      task.cancel();
    }
  }

  private void cancelRecheckTask(UUID playerId) {
    ScheduledTask task = recheckTasks.remove(playerId);
    if (task != null) {
      task.cancel();
    }
  }

  /**
   * 取消全部复检任务。
   *
   * <p><b>逐项 remove 而非遍历后 clear</b>：本方法在命令线程执行，而 {@code onJoin} → {@link #scheduleRecheck}
   * 在玩家所属区域线程执行（Folia 上是真正并发）。「先遍历 values()、后 clear()」会把迭代之后才 put
   * 进来的句柄直接扔掉——那个任务从未被 cancel，会随实体调度器一直跑到玩家退出，并让整个已停用的
   * 旧实例无法被 GC。逐项 {@code remove} 是原子的：并发 put 的那条要么被本方法看到并取消，
   * 要么留给 {@link #scheduleRecheck} 的 {@code stopping} 收口自行取消。
   */
  private void cancelRecheckTasks() {
    for (UUID playerId : new ArrayList<>(recheckTasks.keySet())) {
      ScheduledTask task = recheckTasks.remove(playerId);
      if (task != null) {
        task.cancel();
      }
    }
  }

  /**
   * 取消全部「热重载恢复」重试链（见 {@link #restoreTasks}）。
   *
   * <p>与 {@link #cancelRecheckTasks()} 同一套「先摘再取消」：并发注册的链要么被本方法看到并取消，
   * 要么留在表里由下一次 {@code stop()} 处理——本方法只在停用/重载时调用，不做忙等。
   */
  private void cancelRestoreTasks() {
    for (ScheduledTask task : new ArrayList<>(restoreTasks)) {
      if (restoreTasks.remove(task)) {
        cancelQuietly(task);
      }
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onTrack(PlayerTrackEntityEvent event) {
    if (!active()) {
      return;
    }
    Player player = event.getPlayer();
    // 直通判定只读登录期快照（纯内存、线程安全），语义与复检路径一致：
    // 运行期授予 / 撤销权限都需重进服务器才生效（见 BypassLookup）。
    if (bypass.isBypassed(player.getUniqueId())) {
      return;
    }
    Entity entity = event.getEntity();
    // 玩家可见性交给原版与其它插件，避免干扰 PvP；烟花被隐藏会破坏鞘翅飞行体验
    if (entity instanceof Player || entity instanceof Firework) {
      return;
    }
    // 世界黑名单：命中世界不做任何带宽优化（不登记、不提交判定）。
    // 读玩家自己的世界（追踪事件里玩家与实体必然同世界），避免在 owns(entity) 之前触碰实体状态——
    // 跨区域实体读 getWorld() 同样会触发 Folia 线程校验（先打 ERROR 再抛异常）。
    if (WorldBlacklistConfig.isBlacklistedNow(player.getWorld().getName())) {
      return;
    }
    // 跨区域实体不登记也不评估：读它们的任何状态都会触发 Folia 线程校验（先打 ERROR 再抛异常）
    if (!owns(entity)) {
      return;
    }
    if (entity.getEntityId() == player.getEntityId()) {
      return;
    }
    // 登记进轮转队列：这是「入场那一刻评估一次」之外的兜底，保证之后出现的遮挡也能被发现
    rotation(player.getUniqueId()).add(entity);
    submitRaycast(player, entity, RecheckMode.INITIAL);
  }

  /** 实体离开追踪范围时从轮转队列摘除（否则队列会无界增长，并浪费复检预算）。 */
  @EventHandler(ignoreCancelled = true)
  public void onUntrack(PlayerUntrackEntityEvent event) {
    if (!active()) {
      return;
    }
    UUID playerId = event.getPlayer().getUniqueId();
    TrackedRotation rotation = rotations.get(playerId);
    if (rotation != null) {
      // 按实例摘除：实体此时可能已经离开本区域，读它的 entityId 会触发 Folia 线程校验并刷 ERROR。
      // 摘除动作顺带返回「加入时捕获的 entityId」，供防抖账本清除对应条目——同样无需触碰实体本身。
      int removedId = rotation.remove(event.getEntity());
      if (removedId < 0) {
        return;
      }
      removeEntityState(playerId, removedId);
      // 关键：该实体仍在本玩家的「已隐藏」账本里时，必须把它留在轮转队列中。
      //
      // 为什么：hideEntity 的隐藏状态跨 untrack 仍然生效（服务端不再给它发追踪包，但客户端那边仍是隐藏的）。
      // 通道②是「远距离已隐藏实体唯一的再评估入口」——玩家在 40 格外把挡墙挖开后，只有轮转复检能把它
      // 恢复可见。若这里无条件摘除，该实体就再也进不了通道②，玩家必须走近 32 格才看得见（该显示却仍隐藏）。
      // 保留的代价是有界的：账本条目在 showIfHidden 成功后即摘除，实体真正失效/玩家退出也会整体清空。
      Map<Integer, HiddenEntry> hiddenMap = hidden.get(playerId);
      if (hiddenMap != null && hiddenMap.containsKey(removedId)) {
        // 重新入队：用「加入时捕获的 removedId」，绝不在此读实体状态（它可能已跨区域）。
        rotation.add(event.getEntity(), removedId);
      }
    }
  }

  private TrackedRotation rotation(UUID playerId) {
    return rotations.computeIfAbsent(playerId, uuid -> new TrackedRotation());
  }

  /**
   * 把玩家当前已追踪的实体补种进轮转队列（启用/热重载后调用一次）。
   *
   * <p><b>为什么需要</b>：{@link PlayerTrackEntityEvent} 只在「新进入追踪范围」时触发，热重载重建本模块后
   * 既有实体不会补发事件，于是轮转队列为空——通道②（远距离已隐藏实体唯一的再评估入口）对它们零提交，
   * 表现为 {@code recheckSubmitted} 长期停在 0、远处遮挡变化不再收敛。
   *
   * <p>只在玩家所属线程可安全调用（{@code start()} 在 Paper 主线程 / Folia 各玩家区域线程上执行）；
   * 任何失败都只降级为「该玩家本轮不补种」，绝不影响其它功能。
   */
  private TrackedRotation seedRotationLazily(Player player) {
    // 用「玩家周围一定半径内的实体」补种，而不是 Player#getTrackedEntities()：后者在当前编译目标
    // （paper-api 26.2）上不存在。只在「该玩家尚无轮转队列」时执行一次（热重载后首次复检），是一次性开销。
    TrackedRotation target = rotation(player.getUniqueId());
    // 半径 = 射线硬上限：模块<b>不可能</b>隐藏比 {@link #MAX_RAY_LENGTH} 更远的实体（超长射线一律 fail-open），
    // 而服务端的实体追踪距离（players/display 128，其它 ≤96）也都在这个半径内——因此它恰好覆盖
    // 「本模块隐藏得了、且客户端真的在渲染」的全部实体。
    // 不能取得更大：追踪范围外的实体永远收不到 untrack 事件，补种进来只会永久占用轮转槽位、稀释配额
    // （旧实现取强制可见距离的 8 倍 = 256 格，其中一半以上永不产生任何事件）。
    double radius = MAX_RAY_LENGTH;
    for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius)) {
      // 只补种「本模块会剔除的实体」：其它玩家与烟花在 onTrack / submitRaycast 里被明确排除，
      // 若把它们补进轮转，通道② 会对其它玩家执行 hideEntity（热重载/启用后玩家短暂消失，PvP 可被利用）；
      // 玩家自己也要跳过（原实现用 entity != player，漏了其它玩家）。
      if (entity == player || entity instanceof Player || entity instanceof Firework) {
        continue;
      }
      target.add(entity);
    }
    return target;
  }

  /**
   * 周期复检：① 已隐藏实体每周期全部复检（尽快恢复可见，行为不退化）；
   * ② 其余追踪实体每周期全部提交遮挡判定（无条数封顶，成本由专用池时间预算封顶）。
   *
   * <p>包可见：供离线单测直接驱动复检账本与提交流程（真实链路依赖 Bukkit 调度，离线不可用）。
   */
  void recheck(Player player) {
    if (stopping || !active() || !player.isOnline()) {
      return;
    }
    UUID playerId = player.getUniqueId();

    // 世界黑名单：命中世界不做任何带宽优化。玩家此刻就在黑名单世界时，恢复其全部<b>本区域</b>已隐藏
    // 实体（避免从普通世界走进来时残留隐藏；跨区域条目保留在账本里，见 restorePlayer），直接返回；
    // 不再开周期预算、不再提交任何判定。
    if (WorldBlacklistConfig.isBlacklistedNow(player.getWorld().getName())) {
      restorePlayer(player);
      return;
    }

    // 异步路径：先把本周期的时间预算交给引擎（工作线程据此决定处理多少条，超预算者顺延到下一周期）。
    // 每玩家每周期各调一次，因此有效总预算 ≈ 每玩家预算 × 在线玩家数（正是配置项「每周期每玩家」的语义）。
    // 刻意放在直通判定<b>之前</b>：直通玩家虽然不再提交新任务，但引擎队列里可能仍有早先提交的判定，
    // 若因「本玩家直通」就不开周期，这些任务会一直顺延（直到有别的玩家开周期），白白积压。
    beginEngineCycle();

    // 直通玩家恢复其全部本区域已隐藏实体（跨区域条目保留，见 restorePlayer），且不再提交任何射线复检。
    // 判定只读登录期快照（纯内存、线程安全），不再调用任何 Bukkit 权限 API —— 因此本类周期路径上
    // 不存在任何权限查询。
    // 语义一致性：运行期改权限需重进服务器才生效，故「复检恢复可见」只对登录时已带权限的玩家生效
    // （见 BypassLookup，这是既定语义，不是缺陷）。
    if (bypass.isBypassed(playerId)) {
      restorePlayer(player);
      return;
    }

    // ① 已隐藏实体：每周期全量复检（既有行为）。
    //    用「加入时捕获的 entityId + 归属判定」遍历：玩家走远/传送后这些实体可能已在别的区域，
    //    读它们的任何状态都会触发 Folia 线程校验（先打 ERROR 再抛异常），因此先问能不能碰，不能则整轮跳过。
    //    本通道只做「强制可见距离内的恢复」（RESTORE_ONLY）：远距离不打射线，成本有界。
    Map<Integer, HiddenEntry> hiddenMap = hidden.get(playerId);
    // 通道①本周期已计入「复检提交」的实体 id（供通道②去重，避免同一实体同周期被计两次）。
    // 仅当通道①确实计过至少一条时才分配，避免无隐藏实体的常见情形产生无谓分配。
    Set<Integer> countedByFirstPass = null;
    if (hiddenMap != null && !hiddenMap.isEmpty()) {
      List<Entity> stale = null;
      // hiddenMap 是并发 Map，遍历中 submitRaycast / showIfHidden 可能增删；弱一致迭代不会抛异常。
      for (HiddenEntry entry : hiddenMap.values()) {
        Entity entity = entry.entity();
        if (!owns(entity)) {
          // 跨区域（Folia）：<b>不能触碰实体</b>（读任何状态都会先打 ERROR 再抛），也<b>绝不能摘除条目</b>。
          // 归属是「当前线程是否拥有该实体的 region」，而玩家移动会让 owns 从 false 变回 true；
          // 一旦把条目删掉，等它回到本区域时 showIfHidden 再也找不到记录，该实体将永久保持隐藏
          // 直到玩家重登——「该显示却仍隐藏」是本项目明令禁止的方向。
          // 保留条目的代价是会话内有界的：{@code hidden} 在玩家退出与停用时整体清空，不会跨会话增长。
          // hiddenCount() 因此可能略高于「此刻真正可见性已生效」的实体数（仅跨区域期间）。
          continue;
        }
        if (!entity.isValid()) {
          if (stale == null) {
            stale = new ArrayList<>(4);
          }
          stale.add(entity);
          continue;
        }
        // 计数口径不变：每条「本区域 + 有效」的已隐藏记录每周期都算一次复检提交
        stats.recheckSubmitted.increment();
        if (countedByFirstPass == null) {
          countedByFirstPass = new HashSet<>();
        }
        countedByFirstPass.add(entity.getEntityId());
        if (withinForceVisible(player, entity)) {
          submitRaycast(player, entity, RecheckMode.RESTORE_ONLY);
          continue;
        }
        // 远距离：只有「因视锥剔除而隐藏」的条目才做廉价的锥内复判（纯数学，不打射线）。
        // 一旦实体重新回到视野锥内、或不再满足距离门，立刻恢复显示——这就是「转头后 ≤ 1 个复检周期补发」。
        // 射线遮挡隐藏的条目（frustumCulled=false）不在本通道打射线（成本有界），改由通道②的轮转
        // 复检周期性重估（遮挡物消失后即可恢复可见），因此这里不必也不应打射线。
        if (entry.frustumCulled() && !frustumCullable(player, entity)
            && showIfHidden(player, entity)) {
          stats.recheckShown.increment();
          stats.frustumShown.increment();
        }
      }
      if (stale != null) {
        for (Entity entity : stale) {
          // 仅摘除「同一实例」：entityId 会被复用，按 id 删会把后来占用该 id 的新实体误删
          removeLedgerEntry(playerId, entity);
        }
      }
    }

    // ② 全部追踪实体（<b>含已隐藏者</b>）：每周期提交<b>全部</b>（无条数封顶——射线在专用池里跑，
    //    提交只做「实体线程抓快照 + 入队」，成本由引擎的时间预算封顶）。
    //    本通道允许对远距离实体做判定（ROTATION），承担两件事：
    //      · 把「先可见、之后才被挡住」的实体收敛到隐藏；
    //      · 把「曾因遮挡被隐藏、之后遮挡物消失（被炸开/被挖开）」的实体恢复可见。通道①对远距离条目
    //        刻意不提交判定（成本有界），因此这里是远距离已隐藏实体唯一的再评估入口。
    //    超预算者顺延到下一周期、不丢判定，故「提交全部」不会让开销无界。
    TrackedRotation rotation = rotations.get(playerId);
    if (rotation == null) {
      // 热重载会重建本模块，而 PlayerTrackEntityEvent 不会为「已追踪实体」补发 —— 队列为空时通道②
      // 对既有实体零提交（静止实体如盔甲架/展示框永不补入，recheckSubmitted 长期停在 0）。
      // 这里在玩家所属线程懒补种一次；失败只降级为「本轮不补种」，不影响其它功能。
      try {
        rotation = seedRotationLazily(player);
      } catch (Throwable throwable) {
        plugin.getLogger().fine("实体剔除：补种轮转队列失败（该玩家本轮跳过）：" + throwable.getMessage());
        return;
      }
    }
    // 每周期限量提交：队列容量是固定值（OCCLUSION_QUEUE_CAPACITY），而「提交全部追踪实体」在多人时
    // 必然超过它（按类注释的定容规则，50 人 × 100 追踪即 5000 > 4096）——超出的部分被引擎拒收后
    // 本周期就再没有机会，通道②的「先可见后被挡收敛」与「遮挡物消失后恢复」会随人数增长而退化。
    // 改为按在线人数分摊配额 + 轮转游标：队列不再溢出，未提交的实体顺延到下一周期（不丢判定）。
    // 代价是远处实体的再评估周期变长（观感上慢几拍），换来的是任何人数下都不再丢判定。
    // 用「本类已追踪的玩家数」而不是 Bukkit.getOnlinePlayers()：后者要碰服务端实例，
    // 离线单测里 Bukkit.server 为 null 会直接 NPE；而 rotations 是本类自己维护的纯内存结构，
    // 语义上正是「当前参与剔除的玩家数」，与队列容量相除得到每人配额，离线与真机行为一致。
    int trackedPlayers = Math.max(1, rotations.size());
    int perCycle = Math.max(8, OCCLUSION_QUEUE_CAPACITY / trackedPlayers);
    for (Entity entity : rotation.nextBatch(perCycle)) {
      if (!owns(entity)) {
        // 跨区域（Folia）：不能触碰实体，也<b>不得摘除轮转条目</b>——通道②是远距离实体唯一的再评估
        // 入口（通道①对远距离条目不提交判定）；删掉后该实体既不会被重新判可见，也不会随 owns 恢复
        // 而自动回来，等于永久隐藏。归属会随玩家移动变回 true，届时本条自然重新参与轮转。
        continue;
      }
      if (!entity.isValid()) {
        rotation.remove(entity.getEntityId());
        removeEntityState(playerId, entity.getEntityId());
        continue;
      }
      // 去重：本周期已被通道①计过的实体（既已隐藏又在轮转队列）不再重复计数，避免「复检提交」虚增。
      // 射线评估照旧执行（该实体可能因遮挡物消失而恢复可见）。
      if (countedByFirstPass == null || !countedByFirstPass.contains(entity.getEntityId())) {
        stats.recheckSubmitted.increment();
      }
      submitRaycast(player, entity, RecheckMode.ROTATION);
    }
  }

  /**
   * 在实体所属线程评估遮挡：距离闸门 → 视锥剔除 → 提交遮挡判定（专用池）→ 回投 hide/show。
   *
   * <p>{@code mode} 决定「实体超出强制可见距离时」是否做判定（见 {@link RecheckMode}）：
   * <ul>
   *   <li>{@link RecheckMode#INITIAL}（入场首评）与 {@link RecheckMode#ROTATION}（轮转复检）：
   *       允许对远距离实体做判定并隐藏，因此 {@code evaluate} 里的 hide 分支可达——这正是复检能够
   *       真正收敛到隐藏的入口；</li>
   *   <li>{@link RecheckMode#RESTORE_ONLY}（已隐藏实体复检）：超出距离一律不提交判定，只等实体重新
   *       回到强制可见距离内再恢复，成本有界。</li>
   * </ul>
   * 无论哪条通道，处于强制可见距离内的实体一律 {@code showIfHidden}（安全策略：绝不隐藏近处实体）。
   */
  private void submitRaycast(Player player, Entity entity, RecheckMode mode) {
    // 不变式守卫（不可依赖调用方）：玩家可见性交给原版与其它插件（PvP），烟花被隐藏会破坏鞘翅飞行。
    // 补种路径曾经漏掉 Player 过滤，导致其它玩家被轮转队列隐藏；这里再挡一道，防止未来新增入口绕过。
    if (entity instanceof Player || entity instanceof Firework) {
      return;
    }
    try {
      // 强制可见距离内一律可见（安全策略）：实体只要在近处，任何通道都不得隐藏它，最多恢复显示。
      // 这里用坐标 getter 手算距离（不再 new 两个 Location）：既省下每次评估的固定分配，
      // 也避免 Location#distanceSquared 在「实体未加载 / 两侧世界不一致」时抛异常——失败方向一致
      // （判不出距离时宁可当作「近」→ 只 show 不 hide，绝不误藏）。fromRecheck 仅用于计数归属。
      if (withinForceVisible(player, entity)) {
        evaluate(player, entity, false, mode != RecheckMode.INITIAL);
        return;
      }
      // 超出强制可见距离：
      //  ① RESTORE_ONLY：在调用点（recheck 通道①）就已跳过远距离条目，走不到这里——账本条目可能长期
      //     滞留，每周期判定会让成本随会话无界增长。账本条目仍保留：Paper 的 hideEntity 状态跨 untrack
      //     仍生效，账本是「重新进入强制可见距离时该不该 showEntity」的唯一依据；实体一旦回到近处，
      //     上面的 showIfHidden 分支即恢复它。视锥隐藏条目的「回到视野锥内即恢复」由通道①的纯数学复判负责。
      //  ② ROTATION：提交全部追踪实体，成本由专用池的时间预算封顶（超预算者顺延，不丢判定）。
      //  ③ INITIAL：入场首评不受距离闸门限制，远距离实体照常剔除（既有行为不变）。
      // 视锥剔除优先：锥外 + 超距的实体根本看不到，直接隐藏，连射线都省下（判定成本仅一次视锥数学）。
      if (frustumCullable(player, entity)) {
        evaluate(player, entity, true, mode != RecheckMode.INITIAL, true);
        return;
      }
      // 遮挡判定：实体线程只抓快照并提交，判定与发布在专用池 / 回投里完成（见 submitAsync /
      // publishAsync）。位图不可用（引擎未起，罕见）时 submitAsync 直接返回、不改变显示状态，
      // 即「无数据 = 不遮挡 = 少藏」的安全方向（实体保持可见）。
      if (!config.raycast()) {
        // 只开视锥剔除（raycast=false）：不做遮挡判定，实体保持可见。
        evaluate(player, entity, false, mode != RecheckMode.INITIAL);
        return;
      }
      submitAsync(player, entity, mode != RecheckMode.INITIAL);
    } catch (Throwable throwable) {
      // 任意失败都按「保持可见」处理（fail-open：绝不误藏实体）
      logThrottled(throwable);
    }
  }

  /** 给异步引擎开一个新周期并给出本玩家的时间预算（未接线时空操作）。 */
  private void beginEngineCycle() {
    OcclusionEngine current = engine;
    if (current != null) {
      current.beginCycle(budgetNanos);
    }
  }

  /**
   * 遮挡判定的<b>提交侧</b>（运行在实体所属线程）：抓几何快照 → 组纯值任务 → 入队。
   *
   * <p><b>为什么只抓快照</b>：{@code getEyeLocation} / {@code getBoundingBox} 都是 Bukkit API，必须在实体
   * 所属线程读取；读出的坐标是纯数值，交给专用池后工作线程<b>绝不触碰任何 Bukkit API</b>（Folia 上跨区域
   * 读会先打 ERROR 再抛异常）。
   *
   * <p><b>失败与拒收都不改变显示状态</b>：抓几何失败、或任务因队列满被拒收时，一律<b>保持实体当前
   * 显示状态</b>（与引擎「超时丢弃同样不改变显示状态」一致）：当前状态由此前一次成功判定决定，
   * 而「该恢复却仍隐藏」由通道①的每周期全量复检兜底。绝不因一次取不到判定就把已隐藏实体强行显示——
   * 那会与通道②的隐藏判定反复争夺，表现为闪烁。
   *
   * <p>包可见：供离线单测验证「提交全部追踪实体 / 组请求 / 拒收不影响状态」这段链路。
   */
  void submitAsync(Player player, Entity entity, boolean fromRecheck) {
    OcclusionEngine current = engine;
    if (current == null || stopping) {
      return;
    }
    try {
      // 眼睛坐标用零分配 getter（getEyeLocation 每次提交都会新建一个 Location）；提交频率是
      // 「每玩家每周期每远处实体」，属热路径。
      // eyeY = 脚部 Y + getEyeHeight()：与 LivingEntity#getEyeLocation() 的算法完全一致
      // （getEyeLocation 内部就是 getLocation().add(0, getEyeHeight(), 0)），因此几何不变。
      double eyeX = player.getX();
      double eyeY = player.getY() + player.getEyeHeight();
      double eyeZ = player.getZ();
      String worldName = entity.getWorld().getName();
      BoundingBox box = entity.getBoundingBox();
      // 顶点直接写进「最多 ray-samples 个点」的一维数组：旧写法先建 double[][]（+ 最多 7 个内层数组）、
      // 再展平成第二个数组，每次提交 9 份短命分配。这里只有一份，且长度即提交长度。
      int limit = Math.min(Math.max(1, config.raySamples()), MAX_RAY_SAMPLES);
      double[] scratch = new double[MAX_RAY_SAMPLES * 3];
      int count = Math.min(limit, visibleVerticesFlat(eyeX, eyeY, eyeZ,
          box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ(),
          scratch));
      double[] vertices = count * 3 == scratch.length ? scratch : Arrays.copyOf(scratch, count * 3);
      // 截止时刻：超过 timeoutCycles 个提交周期仍未回投的判定由引擎丢弃（不改变显示状态），
      // 避免用陈旧几何去隐藏实体（见 OcclusionEngine 的截止时间说明）。
      long deadline = System.nanoTime() + timeoutNanos;
      // 提交序号：回投时据此丢弃「后完成但更旧」的结论（见 appliedSeq）。序号取自本类的单调计数器，
      // 不放进 Request（引擎不关心它），由回投闭包携带，因此不需要改引擎契约。
      long submitSeq = submitSeqCounter.incrementAndGet();
      OcclusionEngine.Request request = new OcclusionEngine.Request(player.getUniqueId(),
          entity.getEntityId(), worldName, deadline, eyeX, eyeY, eyeZ, vertices,
          (result, occluded) ->
              // 回投发生在专用池线程：先跳回玩家/实体所属线程，再触碰任何 Bukkit API
              Schedulers.onEntity(plugin, player,
                  () -> publishAsync(player, entity, worldName, result, occluded, fromRecheck,
                      submitSeq)));
      // 拒收（队列满）不改变显示状态，由引擎计入 rejectedQueueFull 供真机观测（见诊断面板）。
      current.submit(request);
    } catch (Throwable throwable) {
      // 抓快照失败 = 本次拿不到判定：不改变显示状态（fail-open 的安全方向）
      logThrottled(throwable);
    }
  }

  /**
   * 异步射线路径的<b>发布侧</b>（已跳回玩家/实体所属线程）：逐条状态校验 → 防抖 → hide/show。
   *
   * <p><b>每一步校验都不能省</b>：结果从提交到回投之间可能已过若干周期——期间玩家可能退出、实体可能
   * 失效 / 被卸载 / 离开本区域，甚至被传送到<b>另一个世界</b>（按旧世界几何算出的遮挡结论毫无意义，
   * 必须作废）。此外<b>玩家自己也可能走到实体身边</b>：遮挡结论本身没变，但近处实体一律可见是硬安全
   * 策略，因此 hide 之前还要再过一次强制可见距离闸门（见下方）。任何一条校验不通过都<b>不改变显示
   * 状态</b>（fail-open 的安全方向）。
   *
   * <p>包可见：真实回投要经 {@code Schedulers.onEntity}（离线不可用），单测直接调用本方法模拟
   * 「已跳回实体线程」这一步，从而覆盖全部状态校验与防抖判定。
   */
  void publishAsync(Player player, Entity entity, String worldName,
      OcclusionEngine.Request request, boolean occluded, boolean fromRecheck) {
    // 兼容入口（仅单测直接驱动回投时使用）：不做提交序判定，其余校验与正式入口完全一致。
    publishAsync(player, entity, worldName, request, occluded, fromRecheck, NO_SUBMIT_SEQ);
  }

  /**
   * 异步射线路径的<b>发布侧</b>正式入口：在做各项状态校验之前先做「结论新旧」判定（玩家在线之后）。
   *
   * @param submitSeq 该判定的提交序号（单调递增）；比已应用的序号更旧的结果会被丢弃
   */
  void publishAsync(Player player, Entity entity, String worldName,
      OcclusionEngine.Request request, boolean occluded, boolean fromRecheck, long submitSeq) {
    if (stopping || !active()) {
      return;
    }
    if (!player.isOnline()) {
      return; // 退出已清账本；玩家已看不到该实体，无需（也不该）再动可见性
    }
    // 乱序保护（放在「玩家在线」之后：离线玩家的迟到结果不会为它建序号账本，避免退出后再长出
    // 无人回收的条目）：多工作线程下同一实体的两份判定可能按完成顺序回投，旧结论若照常应用会把
    // 新结论写好的状态覆盖回去（把已可见实体重新藏起）。更旧者整条丢弃，不改显示状态——
    // 与超时丢弃 / 队列满拒收同一语义。
    if (!acceptSubmitSeq(player.getUniqueId(), request.entityId(), submitSeq)) {
      return;
    }
    if (!owns(entity)) {
      return; // 跨区域（Folia）：读它的状态会先打 ERROR 再抛，直接放弃本轮结果
    }
    // 世界作废守卫：迟到结果只对「提交时所属世界」成立；实体换世界后必须丢弃。
    // 读侧用提交时捕获的世界名做精确比较（世界名由服务端保证唯一），无需任何额外世代编号。
    if (!worldName.equals(entity.getWorld().getName())) {
      return;
    }
    if (!entity.isValid()) {
      removeLedgerEntry(player.getUniqueId(), entity);
      removeEntityState(player.getUniqueId(), request.entityId());
      return;
    }
    // 迟到结果的距离闸门：提交判定时实体还在强制可见距离之外，回投前玩家可能已经走近到该距离以内
    // （步行、坐船、末影珍珠都能在几个 tick 内跨过几十格）。遮挡结论本身不会因此改变，但「绝不隐藏
    // 近处实体」是硬安全策略：此时必须整条丢弃本次 hide，否则近身实体会凭空消失，直到下一个复检
    // 周期才被通道①恢复。距离用<b>当前</b>坐标重算（复用零分配的 {@link #withinForceVisible}），
    // 不看提交时的陈旧快照——要防的正是「提交之后玩家又走近」这一段。
    if (occluded && withinForceVisible(player, entity)) {
      return;
    }
    if (!passesHysteresis(player.getUniqueId(), request.entityId(), occluded)) {
      return; // 判据尚未连续够次数：维持现状，压住「刚好被挡 / 刚好可见」的抖动
    }
    evaluate(player, entity, occluded, fromRecheck);
  }

  /**
   * 提交序闸门：把该实体的「已应用序号」推进到 {@code submitSeq}，返回本次结论是否仍然有效。
   *
   * <p>只在玩家所属线程（回投调度目标）调用，因此同一实体的「比较 + 写入」天然串行，无需额外同步；
   * 序号更小的结论一律判为过期（返回 false，调用方不得改变显示状态）。序号在<b>状态校验之前</b>推进：
   * 即使该结论随后被在线/归属/世界/有效性校验丢弃，下一次复检也会用更新的判定补上，不会影响收敛，
   * 反倒避免了「旧结论在中间插队」。
   *
   * <p>{@code NO_SUBMIT_SEQ} 是兼容入口的哨兵：既不比较也不写账本。
   *
   * <p><b>已知的微量残留（刻意不额外清理）</b>：若某实体已被 untrack（{@link #removeEntityState}
   * 已清过它的账本）之后仍有在途判定回投，这里会为它重新建一条序号条目，而该实体已不在轮转队列、
   * 不会再被 {@code removeEntityState} 清到，于是这条条目要留到玩家退出 / 插件停用才释放。这与既有
   * {@code streaks} 的同类残留一致：每条只是一个 {@code Integer → Long} 条目，有界（≤ 该玩家追踪过的
   * 实体数）且随退出整体释放。额外清理需要额外记录「刚摘除的 id」并引入新的竞态窗口，成本大于收益，
   * 故不做。
   */
  private boolean acceptSubmitSeq(UUID playerId, int entityId, long submitSeq) {
    if (submitSeq == NO_SUBMIT_SEQ) {
      return true;
    }
    ConcurrentHashMap<Integer, Long> applied =
        appliedSeq.computeIfAbsent(playerId, uuid -> new ConcurrentHashMap<>());
    Long previous = applied.get(entityId);
    if (previous != null && submitSeq <= previous) {
      return false;
    }
    applied.put(entityId, submitSeq);
    return true;
  }

  /**
   * 防抖闸门：同一实体的判定需连续 {@code hysteresisCycles} 个周期一致才允许切换显示状态。
   *
   * <p>{@code hysteresisCycles ≤ 1}（默认）时直接放行，<b>既不分配也不访问</b>防抖账本（零开销）。
   * 达到要求前一律维持现状——注意这也会推迟「恢复可见」，但延迟有硬上界
   * （{@code hysteresisCycles × update-interval-ticks}），且强制可见距离内的恢复完全不走本路径
   * （{@link #submitRaycast} 在近处即同步 {@code showIfHidden}），故安全下限不受影响。
   */
  private boolean passesHysteresis(UUID playerId, int entityId, boolean verdict) {
    int required = hysteresisCycles;
    if (required <= 1) {
      return true;
    }
    ConcurrentHashMap<Integer, VerdictStreak> map =
        streaks.computeIfAbsent(playerId, uuid -> new ConcurrentHashMap<>());
    VerdictStreak streak = map.computeIfAbsent(entityId, id -> new VerdictStreak());
    return streak.record(verdict, required);
  }

  /**
   * 摘除某实体的全部「按实体」辅助状态：防抖账本 + 提交序账本（失效 / 解除追踪 / 退出时调用）。
   *
   * <p>两本账都必须清：只清防抖而留下提交序，会让 entityId 被复用后新实体的序号永远小于旧条目、
   * 判定被永久丢弃（该实体再也无法被隐藏/恢复）；反之只清提交序而留下防抖则会残留陈旧连线计数。
   */
  private void removeEntityState(UUID playerId, int entityId) {
    ConcurrentHashMap<Integer, VerdictStreak> map = streaks.get(playerId);
    if (map != null) {
      map.remove(entityId);
    }
    ConcurrentHashMap<Integer, Long> applied = appliedSeq.get(playerId);
    if (applied != null) {
      applied.remove(entityId);
    }
  }

  /**
   * 该实体此刻是否满足「视锥剔除」条件：超出距离门、且位于玩家视野锥之外。
   *
   * <p>距离门为 {@code max(frustum.min-distance, force-visible-distance)}（构造期已算好平方），
   * 因此强制可见距离内永不为 true——与「近距强制可见」这条安全策略一致。
   *
   * <p>视锥判定复用邻近显形的 {@link ProximitySelector#withinFrustum}（同一套数学，两处行为一致）；
   * 任何读取失败都按「在锥内」处理（返回 false，不剔除），遵循 fail-open。
   */
  private boolean frustumCullable(Player player, Entity entity) {
    if (!frustumCulling) {
      return false;
    }
    double dx = player.getX() - entity.getX();
    double dy = player.getY() - entity.getY();
    double dz = player.getZ() - entity.getZ();
    if (dx * dx + dy * dy + dz * dz <= frustumGateSquared) {
      return false;
    }
    return !insideFrustum(player, entity);
  }

  /**
   * 实体是否位于玩家视野锥内（复用邻近显形的视锥数学）。
   *
   * <p><b>先中心、后包围盒角（快路径 + 慢路径）</b>：绝大多数实体中心在锥内，此时只做一次视锥判定即返回，
   * 成本与只看中心时一致；只有当中心已在锥外时，才额外试包围盒<b>水平 4 个角</b>（取包围盒中高）。
   * 只看中心会让大型实体（末影龙、巨人等）在「中心出锥、身体仍占屏幕边缘」时被误藏，表现为平移视角
   * 时「闪现」——补上角点判定后，只要有一角在锥内即判为可见，绝不误藏。
   *
   * <p>失败方向：任何异常都返回 {@code true}（视为「在锥内」→ 不剔除），绝不因判定失败而误藏实体。
   * 距离豁免传 0——距离门已在 {@link #frustumCullable} 里判过，这里只做纯角度判定。
   */
  private boolean insideFrustum(Player player, Entity entity) {
    if (!frustumCulling) {
      return true;
    }
    try {
      ProximitySelector.Eye snapshot = eyeOf(player);
      // 快路径：中心在锥内 → 直接可见（常见情形只多这一次判定，成本不变）
      if (ProximitySelector.withinFrustum(snapshot, entity.getX(), entity.getY(), entity.getZ(),
          0.0D, frustumFov)) {
        return true;
      }
      // 慢路径：中心已在锥外，再试包围盒水平 4 个角——任一在锥内即判可见（大型实体不被误藏）。
      // 直接逐个传参而不建两个临时 double[]：本方法在「每玩家每周期每远距离实体」上被调用，
      // 两处小数组分配纯属热路径垃圾。
      BoundingBox box = entity.getBoundingBox();
      double midY = (box.getMinY() + box.getMaxY()) / 2.0D;
      double minX = box.getMinX();
      double maxX = box.getMaxX();
      double minZ = box.getMinZ();
      double maxZ = box.getMaxZ();
      return ProximitySelector.withinFrustum(snapshot, minX, midY, minZ, 0.0D, frustumFov)
          || ProximitySelector.withinFrustum(snapshot, maxX, midY, minZ, 0.0D, frustumFov)
          || ProximitySelector.withinFrustum(snapshot, minX, midY, maxZ, 0.0D, frustumFov)
          || ProximitySelector.withinFrustum(snapshot, maxX, midY, maxZ, 0.0D, frustumFov);
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return true;
    }
  }

  /**
   * 零中间分配地取「眼睛位置 + 单位视线方向」。
   *
   * <p><b>为什么不用 {@code player.getEyeLocation().getDirection()}</b>：那会为每个实体每次评估
   * 构造一个 {@code Location} 与一个 {@code Vector}；本方法是「每玩家每周期每实体」的热路径
   * （默认 10 tick、数十个追踪实体），属于纯垃圾。这里用 {@code getX/getY/getZ/getEyeHeight/
   * getYaw/getPitch} 直接算，与 {@code Location#getDirection()} 的公式逐项一致
   * （{@code dx = -cos(pitch)·sin(yaw)}，{@code dy = -sin(pitch)}，{@code dz = cos(pitch)·cos(yaw)}），
   * 结果本身就是单位向量；再交给 {@link ProximitySelector#eye} 做一次极廉价的归一化兜底。
   */
  private static ProximitySelector.Eye eyeOf(Player player) {
    double yaw = Math.toRadians(player.getYaw());
    double pitch = Math.toRadians(player.getPitch());
    double horizontal = Math.cos(pitch);
    return ProximitySelector.eye(player.getX(), player.getY() + player.getEyeHeight(),
        player.getZ(), -horizontal * Math.sin(yaw), -Math.sin(pitch),
        horizontal * Math.cos(yaw));
  }

  /**
   * 玩家与实体的欧氏距离是否在强制可见距离内（零分配：直接用坐标 getter，不构造 {@code Location}）。
   *
   * <p>刻意不用 {@code Location#distanceSquared}：那会为玩家与实体各构造一个 {@code Location}，
   * 是每次评估的一笔固定分配，且它要求两侧都带世界，实体未加载时会直接抛异常。坐标 getter 在任何
   * 情况下都可用，失败方向也一致（判不出距离时按「近」处理 → 只 showIfHidden，绝不误藏）。
   * 生产环境里被追踪的实体必与玩家同世界（追踪事件只对同世界实体触发），故无需再做世界比较。
   */
  private boolean withinForceVisible(Player player, Entity entity) {
    double dx = player.getX() - entity.getX();
    double dy = player.getY() - entity.getY();
    double dz = player.getZ() - entity.getZ();
    return dx * dx + dy * dy + dz * dz <= forceVisibleSquared;
  }

  /**
   * 取实体包围盒上「朝向玩家一侧」的可见顶点，用于多射线判定（任一射线不被遮挡即视为可见）。
   *
   * <p>一次判定最多尝试 {@code ray-samples} 个顶点。
   *
   * <p><b>测试专用豁免</b>：生产路径走 {@link #visibleVerticesFlat}（直接写进一维数组，只分配一次）；
   * 本方法当前仅单测在用，保留以免破坏测试。
   *
   * @return 顶点数组，每项为 (x, y, z)
   */
  static double[][] visibleVertices(double eyeX, double eyeY, double eyeZ,
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    double[] flat = new double[MAX_RAY_SAMPLES * 3];
    int count = visibleVerticesFlat(eyeX, eyeY, eyeZ, minX, minY, minZ, maxX, maxY, maxZ, flat);
    double[][] vertices = new double[count][3];
    for (int i = 0; i < count; i++) {
      vertices[i][0] = flat[i * 3];
      vertices[i][1] = flat[i * 3 + 1];
      vertices[i][2] = flat[i * 3 + 2];
    }
    return vertices;
  }

  /**
   * 顶点选取的<b>生产实现</b>：把候选顶点按 {@code x,y,z} 连续写进 {@code out}，返回顶点数。
   *
   * <p>与 {@link #visibleVertices} 逐点等价，但只分配调用方给出的那一个一维数组（由 {@link #submitAsync}
   * 按 {@code ray-samples} 定长创建并随后随请求交给工作线程）。旧写法先建 {@code double[][]} + 最多 7 个
   * 内层数组、再展平成第三个数组——每次提交 9 份垃圾，而提交频率是「每玩家每周期每远处实体」。
   *
   * @param out 输出数组，长度须 ≥ {@link #MAX_RAY_SAMPLES} × 3
   * @return 写入的顶点数（1 = 小包围盒退化为中心点；否则 7）
   */
  static int visibleVerticesFlat(double eyeX, double eyeY, double eyeZ,
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double[] out) {
    double sizeX = maxX - minX;
    double sizeY = maxY - minY;
    double sizeZ = maxZ - minZ;
    if (sizeX <= SMALL_BOX && sizeY <= SMALL_BOX && sizeZ <= SMALL_BOX) {
      out[0] = (minX + maxX) / 2.0D;
      out[1] = (minY + maxY) / 2.0D;
      out[2] = (minZ + maxZ) / 2.0D;
      return 1;
    }

    boolean nearestXIsMin = Math.abs(eyeX - minX) < Math.abs(eyeX - maxX);
    boolean nearestYIsMin = Math.abs(eyeY - minY) < Math.abs(eyeY - maxY);
    boolean nearestZIsMin = Math.abs(eyeZ - minZ) < Math.abs(eyeZ - maxZ);

    double nearX = nearestXIsMin ? minX : maxX;
    double nearY = nearestYIsMin ? minY : maxY;
    double nearZ = nearestZIsMin ? minZ : maxZ;
    double farX = nearestXIsMin ? maxX : minX;
    double farY = nearestYIsMin ? maxY : minY;
    double farZ = nearestZIsMin ? maxZ : minZ;

    put(out, 0, nearX, nearY, nearZ);
    put(out, 1, farX, nearY, nearZ);
    put(out, 2, nearX, farY, nearZ);
    put(out, 3, nearX, nearY, farZ);
    put(out, 4, farX, farY, nearZ);
    put(out, 5, farX, nearY, farZ);
    put(out, 6, nearX, farY, farZ);
    return 7;
  }

  private static void put(double[] out, int index, double x, double y, double z) {
    out[index * 3] = x;
    out[index * 3 + 1] = y;
    out[index * 3 + 2] = z;
  }

  /**
   * 主线程/区域线程：按遮挡结论执行隐藏/恢复（包可见：供离线单测直接驱动账本行为）。
   *
   * <p>测试专用豁免：生产路径一律传入 {@code fromRecheck}（调 4 参重载），本重载当前仅单测在用，
   * 保留以免破坏测试。
   */
  void evaluate(Player player, Entity entity, boolean allBlocked) {
    evaluate(player, entity, allBlocked, false);
  }

  /**
   * 评估入口（包可见：供离线单测直接驱动「复检通道」的计数归属）。
   *
   * @param allBlocked  是否所有可见顶点都被遮挡（true = 可隐藏）
   * @param fromRecheck 是否来自周期复检：仅用于把计数归入「复检致隐藏 / 复检致恢复」，不影响判定逻辑
   */
  void evaluate(Player player, Entity entity, boolean allBlocked, boolean fromRecheck) {
    evaluate(player, entity, allBlocked, fromRecheck, false);
  }

  /**
   * 评估入口（追加「是否因视锥剔除而隐藏」的标记；包可见：供离线单测驱动账本标记行为）。
   *
   * @param frustumCulled 本次隐藏是否由视锥剔除给出（仅在该次确实发生隐藏时被记入账本）
   */
  void evaluate(Player player, Entity entity, boolean allBlocked, boolean fromRecheck,
      boolean frustumCulled) {
    if (!entity.isValid() || !player.isOnline()) {
      removeLedgerEntry(player.getUniqueId(), entity);
      removeEntityState(player.getUniqueId(), entity.getEntityId());
      return;
    }

    // 载具/乘客关系：整条骑乘链必须一律可见。隐藏「载有乘客的载具」会让未被隐藏的乘客（玩家永不被隐藏）
    // 看起来悬空骑行；隐藏「本身是乘客的实体」会让载具看起来无人。因此只要该实体与任何乘客/载具存在
    // 关系，就把 allBlocked 视为无效 → 走 showIfHidden 保持/恢复可见（fail-open 的确定性规则）。
    if (allBlocked && !hasVehicleRelation(entity)) {
      if (hide(player, entity, frustumCulled, fromRecheck)) {
        if (fromRecheck) {
          stats.recheckHidden.increment();
        }
        if (frustumCulled) {
          stats.frustumHidden.increment();
        }
      }
    } else if (showIfHidden(player, entity) && fromRecheck) {
      stats.recheckShown.increment();
    }
  }

  /**
   * 该实体是否与载具/乘客存在关系（自身载有乘客，或本身是乘客 / 骑在载具上）。
   *
   * <p>读取失败时按「存在关系」处理（不剔除）：剔除是可选优化，fail-open 保持可见最安全。
   */
  private static boolean hasVehicleRelation(Entity entity) {
    try {
      return !entity.getPassengers().isEmpty() || entity.getVehicle() != null;
    } catch (Throwable throwable) {
      return true;
    }
  }

  /**
   * 从账本摘除某实体的条目（仅在条目正是<b>同一实例</b>时才摘）。
   *
   * <p>entityId 会被服务端复用：若只按 id 摘除，可能误删「后来占用该 id 的另一个实体」的条目。
   */
  private void removeLedgerEntry(UUID playerId, Entity entity) {
    Map<Integer, HiddenEntry> map = hidden.get(playerId);
    if (map != null) {
      HiddenEntry existing = map.get(entity.getEntityId());
      if (existing != null && existing.entity() == entity) {
        map.remove(entity.getEntityId(), existing);
      }
    }
  }

  /**
   * 隐藏该实体（必要时只升级账本标记）。
   *
   * @param fromRecheck 本次是否来自周期复检：仅用于把「标记升级」也正确归入「复检致隐藏」口径
   * @return 是否真的新登记并执行了 hideEntity（已隐藏 / 失败时为 false）
   */
  private boolean hide(Player player, Entity entity, boolean frustumCulled, boolean fromRecheck) {
    if (stopping) {
      // 停用已开始：绝不再新登记隐藏（否则会把刚被 restoreAll 恢复的实体重新藏回）
      return false;
    }
    Map<Integer, HiddenEntry> map = hidden.computeIfAbsent(player.getUniqueId(),
        uuid -> new ConcurrentHashMap<>());
    // 仅当账本里该 id 正是「同一实例」时才算已隐藏。若该 id 对应的是一枚已消失的旧实例（entityId 被复用），
    // 则覆盖登记新实体——否则复用该 id 的新实体会永远无法被隐藏（账本被旧条目挡住）。
    HiddenEntry existing = map.get(entity.getEntityId());
    if (existing != null && existing.entity() == entity) {
      // 已隐藏。仅升级标记：由射线遮挡改判为「视锥隐藏」时必须改写标记，否则通道①会把它当成射线隐藏，
      // 导致玩家转头后它永远不会被恢复（这正是标记存在的意义）。
      if (frustumCulled && !existing.frustumCulled()) {
        map.put(entity.getEntityId(), new HiddenEntry(entity, true));
        // 这里必须自己计数：hide() 返回 false（没有新发生隐藏），所以 evaluate() 里受 if (hide(...))
        // 保护的 frustumHidden 不会递增；而该条目此后会被通道①按视锥条目恢复并计 frustumShown。
        // 不补这一笔，/mxnet status 的「视锥剔除 隐藏/恢复」会出现「恢复 > 隐藏」的自相矛盾。
        stats.frustumHidden.increment();
        if (fromRecheck) {
          // 同一次「标记升级」也属于复检发现的新遮挡（此前该实体被当作射线隐藏、远距离不复检），
          // 因此「复检致隐藏」必须同步 +1，否则该口径系统性偏低（恢复侧不会漏计）。
          stats.recheckHidden.increment();
        }
      }
      return false;
    }
    try {
      int entityId = entity.getEntityId();
      HiddenEntry entry = new HiddenEntry(entity, frustumCulled);
      player.hideEntity(plugin, entity);
      map.put(entityId, entry);
      // 停用竞态收口（Folia：stop() 可能在另一条区域线程上并发执行）：<b>入账之后再读一次</b> volatile
      // stopping。这样保证「本次读到 false ⇒ stop() 尚未置位 ⇒ 其 restoreAll 必然发生在本次入账之后、
      // 一定看得到本条目」；读到 true 则说明 stop() 已在恢复，必须自行回滚本次隐藏——否则该实体既不在
      // 新管线账本里、也没被旧管线恢复，将<b>永久不可见</b>（直到玩家重登）。
      if (stopping) {
        if (map.remove(entityId, entry)) {
          // 条目仍在账本里 ⇒ restoreAll 无从看到它（账本已被摘走/清空），必须自行恢复。
          // 本次隐藏从未计入 entitiesHidden，故这里也不计 entitiesShown（两侧口径保持对称）。
          undoHide(player, entity);
        }
        // 条目已被 drain 摘走 ⇒ restoreAll 会（或已经）恢复它，此处不得重复操作
        return false;
      }
      stats.entitiesHidden.increment();
      return true;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
  }

  /**
   * 回滚一次「刚发生、但已来不及计入账本」的隐藏（停用竞态收口专用）。
   *
   * <p>不记统计：该次隐藏从未计入 {@code entitiesHidden}，若这里计 {@code entitiesShown} 会让
   * 「累计隐藏 = 累计恢复 + 当前隐藏中 + 自然回收」出现无谓偏差。
   */
  private void undoHide(Player player, Entity entity) {
    try {
      if (entity.isValid()) {
        player.showEntity(plugin, entity);
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** @return 是否真的从账本摘除并执行了 showEntity（未隐藏 / 实体已失效时为 false）。 */
  private boolean showIfHidden(Player player, Entity entity) {
    Map<Integer, HiddenEntry> map = hidden.get(player.getUniqueId());
    if (map == null) {
      return false;
    }
    // 只认「同一实例」的账本条目：若按 id 摘除，会把后来复用该 id 的另一个实体的条目一并删掉，
    // 从而对从未被隐藏的新实体误发 showEntity，且该新实体再也无法被隐藏（账本被误清）。
    HiddenEntry existing = map.get(entity.getEntityId());
    if (existing == null || existing.entity() != entity) {
      return false;
    }
    // 先真正下发 showEntity，成功后再摘账本（对照 LatencyMonitor.restore 的「写入成功才删原值」）：
    // 若先摘再发而下发失败（show 内部已吞异常并返回 false），账本条目就没了，此后 evaluate 的恢复分支
    // 再也找不到「同一实例」的记录，该实体将永久不可见——这是最严重的一类可见性故障。
    // 失败时条目保留，下一轮复检会重试；实体已失效的条目由别处的 isValid 清理分支移除。
    if (!show(player, entity)) {
      return false;
    }
    map.remove(entity.getEntityId(), existing);
    return true;
  }

  /** @return 是否真的下发了 showEntity（实体已失效时为 false）。 */
  private boolean show(Player player, Entity entity) {
    try {
      if (entity.isValid()) {
        player.showEntity(plugin, entity);
        stats.entitiesShown.increment();
        return true;
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
    return false;
  }

  /**
   * 停用时的兜底：把所有被隐藏的实体恢复显示，不留副作用。
   *
   * <p><b>停用路径必须同步恢复</b>：Bukkit 的 {@code setEnabled(false)} 先置 {@code isEnabled=false}
   * 再调 {@code onDisable()}，此时 {@code EntityScheduler} 一律抛 {@link org.bukkit.plugin.IllegalPluginAccessException}
   * （旧实现被 {@link Schedulers} 静默吞掉 → 关服 / reload / 停用后实体对玩家持续不可见，且「恢复失败必 WARN」永不触发）。
   * 因此这里按「插件是否仍启用」分流：仍启用（热重载）时回到玩家所属线程调度；
   * 已停用时改为<b>同步</b>恢复——Paper 主线程恒可同步；Folia 仅当实体属于当前区域（{@code owns}）时才同步，
   * 跨区域者无法在当前线程安全操作，打一次性中文 WARN 提示「需重登才能恢复」。
   */
  private void restoreAll() {
    boolean stillEnabled = plugin.isEnabled();
    for (Player player : Bukkit.getOnlinePlayers()) {
      Map<Integer, HiddenEntry> drained = drainPlayer(player.getUniqueId());
      if (drained.isEmpty()) {
        continue;
      }
      for (HiddenEntry entry : drained.values()) {
        Entity entity = entry.entity();
        if (stillEnabled) {
          // 热重载路径：回到玩家所属线程执行（Paper 上即主线程，Folia 上即区域线程）
          Schedulers.onEntity(plugin, player, () -> restoreWithRetry(player, entity, 0));
        } else {
          // 停用路径：调度器已不可用（plugin.isEnabled()=false），同步恢复
          restoreNow(player, entity);
        }
      }
    }
    // 离线玩家（停用时已不在线）的登记一并丢弃：它们已随退出被恢复，这里只兜底清账本
    hidden.clear();
  }

  /**
   * 停用路径的<b>同步</b>恢复单个被隐藏实体（不再经调度器）。
   *
   * <p>Paper：主线程拥有全部实体，{@code owns} 恒真，直接 {@code showEntity}。
   * Folia：当前（停用）线程未必拥有该实体所在区域——{@code owns} 为 false 时<b>绝不</b>触碰该实体状态
   * （否则会触发 TickThread 校验先打 ERROR 再抛），只记一次性中文 WARN，说明该实体需玩家重登才能恢复。
   */
  private void restoreNow(Player player, Entity entity) {
    if (!owns(entity)) {
      warnCrossRegionRestore();
      return;
    }
    boolean restored;
    try {
      restored = show(player, entity) || !entity.isValid();
    } catch (Throwable throwable) {
      restored = false;
    }
    if (!restored && restoreFailureNoticed.compareAndSet(false, true)) {
      plugin.getLogger().warning("插件停用时同步恢复被隐藏实体失败："
          + "个别实体可能需玩家重新登录后才可见；不影响其它功能。");
    }
  }

  /** Folia 停用时跨区域实体无法同步恢复的一次性中文 WARN（进程级闸门，绝不刷屏）。 */
  private void warnCrossRegionRestore() {
    if (crossRegionRestoreNoticed.compareAndSet(false, true)) {
      plugin.getLogger().warning("检测到被隐藏的实体未能恢复（Folia 跨区域，或实体刚刚退役）："
          + "若玩家此后仍看不到某个实体，请让其重新登录。此为 Folia 区域化线程的限制，非本插件故障。");
    }
  }

  /** 热重载恢复的重试节奏：每秒一拍。 */
  private static final long RESTORE_RETRY_DELAY_TICKS = 20L;
  /** 等待超过该拍数（60 秒）仍未恢复时，输出一次性诊断。 */
  private static final int RESTORE_DIAGNOSTIC_TICKS = 20 * 60;

  /**
   * 热重载路径恢复单个被隐藏实体：成功即止；跨区域或暂时失败时在玩家所属线程上每秒重试。
   *
   * <p><b>跨区域为什么必须重试、而不是当成「已恢复」</b>：{@link #restoreAll()} 已用 {@code drainPlayer}
   * 把账本摘走，重建后的新管线里没有这条记录——一旦放弃，该实体对这个玩家就会<b>一直不可见</b>
   * （客户端的 hideEntity 状态跨 untrack 仍生效，只有重新登录才会清）。而 Folia 上实体与玩家不在同一区域
   * 时本线程不能触碰该实体（TickThread 校验会先打 ERROR 再抛），所以唯一安全的做法是等它跨回来再恢复。
   *
   * <p><b>为什么不设重试上限</b>：这条轮询就是该实体唯一的恢复入口（等价于此前的「通道①每周期复检」），
   * 按次数截断只会把「玩家稍后走回来」变成「必须重登」。终止条件只有四个：恢复成功、玩家离线
   * （隐藏状态随会话清空）、实体已失效（同区域才读得到 {@code isValid} 并据此判定）、
   * 以及插件停用/再次热重载（由 {@link #cancelRestoreTasks()} 统一取消）。
   *
   * <p>注意 {@link #show} 内部已捕获异常并返回 {@code false}，因此这里以「返回值」判断成败。
   *
   * @param waitedTicks 已等待的 tick 数，仅用于一次性慢恢复诊断
   */
  private void restoreWithRetry(Player player, Entity entity, int waitedTicks) {
    if (!player.isOnline()) {
      return;
    }
    boolean restored;
    if (!owns(entity)) {
      // 跨区域：本线程不能触碰该实体，等它跨回玩家所属区域
      diagnoseSlowRestore(waitedTicks);
      scheduleRestoreRetry(player, entity, waitedTicks);
      return;
    }
    try {
      restored = show(player, entity) || !entity.isValid();
    } catch (Throwable throwable) {
      restored = false;
    }
    if (!restored) {
      diagnoseSlowRestore(waitedTicks);
      scheduleRestoreRetry(player, entity, waitedTicks);
    }
  }

  /**
   * 在玩家所属线程上按秒重试：仍跨区域就继续下一拍，同区域则立刻恢复。
   *
   * <p>调度被拒（插件已停用 / 实体已退役）时无法再重试，只在插件仍启用时如实告警一次——
   * 停用路径由 {@link #restoreNow} 负责，实体退役本就无需恢复。
   */
  private void scheduleRestoreRetry(Player player, Entity entity, int waitedTicks) {
    int[] waited = {waitedTicks};
    ScheduledTask[] self = new ScheduledTask[1];
    self[0] = Schedulers.repeatOnEntity(plugin, player, RESTORE_RETRY_DELAY_TICKS,
        RESTORE_RETRY_DELAY_TICKS, () -> {
          waited[0] += (int) RESTORE_RETRY_DELAY_TICKS;
          if (!player.isOnline()) {
            // 客户端隐藏状态随会话结束：无需再恢复
            finishRestoreTask(self[0]);
            return;
          }
          if (!owns(entity)) {
            diagnoseSlowRestore(waited[0]);
            return; // 仍跨区域：下一拍再试（任务继续）
          }
          finishRestoreTask(self[0]);
          restoreWithRetry(player, entity, waited[0]);
        },
        // 玩家退出时服务端会直接撤销周期任务，任务体往往抢不到最后一拍——退役回调是这条链唯一的摘除点，
        // 缺了它 restoreTasks 会一直持有（runnable 闭包还钉着已下线的 Player/Entity 引用）。
        () -> finishRestoreTask(self[0]));
    if (self[0] == null) {
      // 调度被拒：插件已停用（停用路径由 restoreNow 负责，不必告警），或实体已退役（本就无需恢复）；
      // 玩家已离线同理不必告警。三者都不该冒出一条「跨区域未能恢复」。
      if (plugin.isEnabled() && player.isOnline()) {
        warnCrossRegionRestore();
      }
      return;
    }
    restoreTasks.add(self[0]);
  }

  /** 等了一分钟还没恢复：打一次性诊断，说明它仍在等该实体跨回玩家所属区域。 */
  private void diagnoseSlowRestore(int waitedTicks) {
    if (waitedTicks >= RESTORE_DIAGNOSTIC_TICKS && crossRegionRestoreNoticed.compareAndSet(false, true)) {
      plugin.getLogger().warning("热重载后有被隐藏实体尚未恢复（跨区域等待，或恢复调用持续失败）："
          + "跨区域的会在该实体回到玩家所属区域时自动恢复，轮询持续到恢复成功或玩家离线；"
          + "若玩家此后仍看不到该实体，请让其重新登录。");
    }
  }

  /** 结束一条恢复重试链：先从登记表摘除，再取消（重复调用无副作用）。 */
  private void finishRestoreTask(ScheduledTask task) {
    if (task != null && restoreTasks.remove(task)) {
      cancelQuietly(task);
    }
  }

  /** 取消重试任务；失败只意味着下一拍多看一次同样的状态，不影响语义。 */
  private static void cancelQuietly(ScheduledTask task) {
    if (task != null) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务已结束/已取消：忽略
      }
    }
  }

  /** 本线程是否可以安全读取该实体的状态；判定本身异常时按「可以」处理（退回改动前行为）。 */
  private boolean owns(Entity entity) {
    try {
      return ownership.isOwnedByCurrentRegion(entity);
    } catch (Throwable throwable) {
      return true;
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "实体剔除判定失败，已按「保持可见」处理", throwable);
    }
  }

  /**
   * 单玩家的「追踪实体轮转队列」：按加入顺序保存该玩家正在追踪的实体，用游标把周期复检切成小分片。
   *
   * <p><b>覆盖保证</b>：每周期只推进 {@code min(budget, size)} 个<b>位置</b>（与是否已隐藏无关），
   * 因此 {@code ceil(size / budget)} 个周期内必然遍历到所有位置——这正是「先可见、之后才被挡住」
   * 的实体能被收敛到隐藏的原因；同时每周期真正提交的复检数不超过 {@code budget}。
   *
   * <p><b>键是加入时捕获的 entityId</b>：实体可能已经离开本区域，届时读 {@code entity.getEntityId()}
   * （走 {@code CraftEntity#getHandle}）会触发 Folia 线程校验并刷 ERROR 日志；加入那一刻它必然可达
   * （追踪事件由本区域发出），因此 id 在那一刻取好，之后只读键、不碰实体。
   *
   * <p>线程模型：事件与周期任务都落在玩家所属线程上；加锁仅为防御两者线程不一致（Folia 区域线程）。
   */
  static final class TrackedRotation {

    /** 队列条目：加入时捕获的 entityId + 实体引用；之后只读 id，绝不读可能已跨区域的实体状态。 */
    private record Tracked(int id, Entity entity) {
    }

    /**
     * 环形数组：按加入顺序保存，游标在 {@code [0, size)} 上推进。
     *
     * <p>为什么用数组 + 游标而不是 {@code LinkedHashMap}：旧实现每周期全量扫描整个队列、再按预算过滤，
     * 每周期开销 O(队列长度)；改为直接按下标取 {@code step} 个位置后降到 O(step)，语义（位置推进、
     * 已隐藏按 id 跳过、有限周期内覆盖全部）完全不变。
     */
    private final List<Tracked> order = new ArrayList<>();
    private int cursor;

    /** 加入一个被追踪实体（按加入时捕获的 entityId 去重）。 */
    synchronized void add(Entity entity) {
      add(entity, entity.getEntityId());
    }

    /**
     * 加入一个被追踪实体，<b>entityId 由调用方给出</b>。
     *
     * <p>用于 untrack 场景：实体此时可能已经离开本区域，读 {@code getEntityId()} 会触发 Folia 线程校验
     * 并刷 ERROR。调用方手里已有「加入时捕获的 id」，直接传进来即可，完全不碰实体状态。
     */
    synchronized void add(Entity entity, int id) {
      for (Tracked tracked : order) {
        if (tracked.id() == id) {
          return;
        }
      }
      order.add(new Tracked(id, entity));
    }

    /**
     * 按实例摘除（不读实体状态，供可能已跨区域的 untrack 事件使用）。
     *
     * @return 被摘除条目的 entityId（加入时捕获）；未找到返回 {@code -1}。
     *         调用方据此清除该实体的辅助状态（防抖账本），无需再去读可能已跨区域的实体本身。
     */
    synchronized int remove(Entity entity) {
      for (int index = 0; index < order.size(); index++) {
        if (order.get(index).entity() == entity) {
          int id = order.get(index).id();
          order.remove(index);
          if (cursor > index) {
            cursor--;
          }
          return id;
        }
      }
      return -1;
    }

    /** 按 id 摘除一个不再被追踪的实体，并修正游标使「下一页」不被跳过。 */
    synchronized void remove(int entityId) {
      for (int index = 0; index < order.size(); index++) {
        if (order.get(index).id() == entityId) {
          order.remove(index);
          if (cursor > index) {
            cursor--;
          }
          return;
        }
      }
    }

    /**
     * 当前轮转队列里的追踪实体数。
     *
     * <p>测试专用豁免：生产路径不读该值（复检只走 {@link #nextBatch(int)}），
     * 本方法当前仅单测在用，保留以免破坏测试。
     */
    synchronized int size() {
      return order.size();
    }

    /**
     * 取出本轮要复检的一小批追踪实体，并把游标推进一批，保证有限周期内覆盖全部追踪实体。
     *
     * <p><b>不再跳过已隐藏者</b>：隐藏条目也必须排到复检轮次，否则「遮挡物消失后恢复可见」在远距离
     * 永远没有入口（通道①对远距离条目不打射线）。覆盖保证（{@code ceil(size / budget)} 个周期内遍历
     * 全部位置）与射线成本上界（每周期至多 {@code budget} 次）都不受影响。
     *
     * <p>环形数组直接取 {@code step} 个位置：{@code start = cursor % size}，依次取
     * {@code [start, start+step)}（回绕），位置数而非提交数推进，覆盖保证与旧实现一致。
     */
    synchronized List<Entity> nextBatch(int budget) {
      int size = order.size();
      if (size == 0) {
        cursor = 0;
        return List.of();
      }
      int step = Math.min(Math.max(1, budget), size);
      int start = Math.floorMod(cursor, size);
      List<Entity> batch = new ArrayList<>(step);
      for (int offset = 0; offset < step; offset++) {
        batch.add(order.get(Math.floorMod(start + offset, size)).entity());
      }
      cursor = (start + step) % size;
      return batch;
    }
  }
}