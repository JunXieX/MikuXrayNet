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
 *
 * <p><b>筛选（可选、可配）</b>：
 * <ul>
 *   <li><b>视锥剔除</b>（默认开启，80° 竖直全角）：只显形玩家视野锥内的候选坐标；
 *       水平方向按 16:9 宽高比更宽（约 112° 全角），与客户端观感一致；最小距离内豁免；</li>
 *   <li><b>可见性判定</b>（默认开启，多候选点 + Paper 原生射线）：先识别方块的暴露面，只在暴露面上取
 *       候选点（正对玩家的面中心 → 包围盒最近点 → 面四角），每个候选点用
 *       {@code World#rayTraceBlocks} 做原生射线检测，任一条通畅即显形；候选点数由
 *       {@code proximity.raycast.samples} 限制（已钳制 1..8）；六面全被遮挡（完全掩埋）时一个点都不采
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
 * 因此每个确实发出的坐标都会记入 {@link RevealEchoLedger}，让观察方认出「这是我们的回显」——
 * 只摘显形索引，不推进磁盘缓存区块代次（详见 {@link #consumeSelfSentEcho}）。
 * {@code proximity.batch-reveal-sends}（默认开启）时把一个周期内通过筛选的坐标累积起来、
 * 周期末一次 {@code sendMultiBlockChange} 发出（Paper 按 16³ 区块段自动合并，每个涉及的段一个包），
 * 失败时退化为逐坐标 {@code sendBlockChange}；关闭时逐坐标发单方块变更包（旧行为，供 A/B 与回退）。
 * 同一坐标只显形一次（发送成功后立即写入该玩家的 {@link RevealedSet}）；区块被重新下发或被卸载时
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

  /** 单次巡检的发包额度（非 Folia 为全服合计，Folia 为每个玩家各自的巡检）。 */
  private static final class Budget {

    private int remaining;

    private Budget(int remaining) {
      this.remaining = remaining;
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
    private int positionsEvaluated;
    private int rayCulled;
    private int sent;
  }

  /**
   * 一个周期（或一次事件触发）内待发的显形批次：只累积「已通过全部筛选、待发包」的坐标与真实方块状态，
   * 周期末由 {@link #flushBatch} 一次发出并逐个写回「已显形」标记。
   *
   * <p><b>为什么先累积再发</b>：批量合并的前提是「先知道本周期有哪些坐标要发」；同时把标记推迟到
   * 真正发出之后，保证「已显形标记 ⊆ 客户端真正收到过的坐标」这一不变式不被破坏。
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
  private final AtomicLong passes = new AtomicLong();
  private final AtomicBoolean firstRevealDiagnosed = new AtomicBoolean();
  /** 「显形索引安全阀触发」只提示一次（CAS 抢占），避免每轮巡检刷屏。 */
  private final AtomicBoolean capacityWarned = new AtomicBoolean();
  /** 「可见性判定失败」只提示一次（CAS 抢占）：原生射线改造后新增的失败模式必须可见但不刷屏。 */
  private final AtomicBoolean visibilityWarned = new AtomicBoolean();
  /** 事件显形的每玩家每 tick 限额（默认 16，防爆刷；见 {@link TickQuota}）。 */
  private final TickQuota instantQuota = new TickQuota();
  /** 过度显形抽样器（1/N，只计数不改行为；N=0 关闭）。 */
  private final OverRevealSampler overRevealSampler;
  /**
   * 「自己刚发出的显形包」账本：显形包走 Paper 原生通道后同样会被 {@link BlockChangeRevealListener}
   * 观察到，观察方据此区分「我们的回显」与「服务端真实方块变更」——回显不推进磁盘缓存区块代次。
   */
  private final RevealEchoLedger selfSentEchoes = new RevealEchoLedger();

  private ScheduledTask globalTask;

  /**
   * Folia 每玩家的区域巡检任务句柄（按 UUID 保存）。
   *
   * <p><b>为什么必须保存</b>：{@code repeatOnEntity} 返回的句柄若被丢弃，{@link #stop()} 就只取消了
   * 全局任务，Folia 上各玩家的区域任务会在插件停用后继续跑到实体退役为止——句柄泄漏 + 停用后
   * 仍在发包。这里逐一保存，停用与玩家退出时全部取消。
   */
  private final ConcurrentHashMap<UUID, ScheduledTask> entityTasks = new ConcurrentHashMap<>();

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
  }

  /** 启动巡检：非 Folia 为统一主线程任务；Folia 为各玩家的区域任务（句柄按 UUID 保存，见 entityTasks）。 */
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
    for (ScheduledTask task : entityTasks.values()) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已随实体退役结束，取消失败可忽略
      }
    }
    entityTasks.clear();
    HandlerList.unregisterAll(this);
    chunkIndex.clear();
    revealedSet.clear();
    selfSentEchoes.clear();
  }

  /** 在玩家所属线程上启动周期巡检并保存句柄（调度失败则无句柄可存）。 */
  private void scheduleEntityTask(Player player, long interval) {
    ScheduledTask task = Schedulers.repeatOnEntity(plugin, player, interval, interval, () -> pass(player));
    if (task != null) {
      entityTasks.put(player.getUniqueId(), task);
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
    ScheduledTask task = entityTasks.remove(event.getPlayer().getUniqueId());
    if (task != null) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已随实体退役结束，取消失败可忽略
      }
    }
    revealedSet.clearPlayer(event.getPlayer().getUniqueId());
    instantQuota.clear(event.getPlayer().getUniqueId());
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
    Budget budget = new Budget(limit());
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (budget.remaining <= 0) {
        return;
      }
      try {
        reveal(player, budget);
      } catch (Throwable throwable) {
        logThrottled(throwable);
      }
    }
  }

  /** 单个玩家的巡检（Folia：在玩家所属区域线程执行）。 */
  private void pass(Player player) {
    warnIfCapacityExceeded();
    maybeExpire();
    try {
      reveal(player, new Budget(limit()));
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
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
  private void reveal(Player player, Budget budget) {
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
        location.getBlockZ(), proximity.distance(), fetchLimit(budget.remaining), scanTally);
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
        // 读方块与发包必须回到玩家所属线程
        Schedulers.onEntity(plugin, player, () -> {
          try {
            if (player.isOnline()) {
              sendCandidates(player, player.getWorld(), null, plan, eye, budget, scanTally);
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
    tally.positionsEvaluated = scanTally.positionsEvaluated;
    int candidateCount = plan != null
        ? plan.candidateCount
        : (candidates == null ? 0 : candidates.size());
    int frustumCulled = plan != null ? plan.frustumCulled : 0;
    RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;

    if (candidates != null) {
      for (ObfuscatedChunkIndex.Position position : candidates) {
        if (budget.remaining <= 0) {
          break;
        }
        sendOne(player, world, position.x(), position.y(), position.z(), eye, budget, tally, batch);
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
      if (budget.remaining <= 0) {
        break;
      }
      sendOne(player, world, plan.coordinates[i * 3], plan.coordinates[i * 3 + 1],
          plan.coordinates[i * 3 + 2], eye, budget, tally, batch);
    }
    flushBatch(player, batch, tally);
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
      // 批量模式：先累积，周期末由 flushBatch 一次发出并在成功后才写入「已显形」标记
      batch.add(x, y, z, data);
      budget.remaining--;
      return;
    }

    if (sendSingle(player, world, x, y, z, data)) {
      markRevealed(player, world, x, y, z);
      stats.revealsSent.increment();
      tally.sent++;
      budget.remaining--;
    } else {
      stats.revealsSkipped.increment();
    }
  }

  /**
   * 发出本周期累积的显形（批量模式）：优先一次 {@code Player#sendMultiBlockChange}——Paper 会按
   * 16³ 区块段自行合并，因此「本周期 N 个坐标」通常只产生「涉及的段数」个多方块变更包。
   *
   * <p>只有一个坐标时直接用 {@code sendBlockChange}（单包更小，也与旧行为一致）。
   * 批量调用整体失败时退化为逐坐标 {@code sendBlockChange}（同一条原生通道），单个坐标失败只计数并
   * 跳过；标记与统计只在「确实发出」后写入，因此发包失败不影响其它坐标、也不污染已显形索引。
   */
  private void flushBatch(Player player, RevealBatch batch, PassTally tally) {
    if (batch == null || batch.size() == 0) {
      return;
    }
    World world = batch.world;
    boolean batched = false;
    if (batch.size() > 1) {
      try {
        Map<Position, BlockData> changes = new LinkedHashMap<>(batch.size() * 2);
        for (int i = 0; i < batch.size(); i++) {
          int[] position = batch.positions.get(i);
          changes.put(Position.block(position[0], position[1], position[2]), batch.states.get(i));
        }
        player.sendMultiBlockChange(changes);
        batched = true;
      } catch (Throwable throwable) {
        // 退化路径：仍走 Paper 原生单方块变更包，不退回封包自拼
        logThrottled(throwable);
      }
    }

    for (int i = 0; i < batch.size(); i++) {
      int[] position = batch.positions.get(i);
      if (!batched
          && !sendSingle(player, world, position[0], position[1], position[2], batch.states.get(i))) {
        stats.revealsSkipped.increment();
        continue;
      }
      markRevealed(player, world, position[0], position[1], position[2]);
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

  /** 写入「该玩家已显形」标记（与坐标注销、整块跳过判定共用同一不变式）。 */
  private void markRevealed(Player player, World world, int x, int y, int z) {
    UUID playerId = player.getUniqueId();
    String worldName = world.getName();
    revealedSet.mark(playerId, ChunkKey.ofBlock(worldName, x, z), x, y, z);
    // 同一个「确实发出之后」的时刻记账：出站观察链路随后看到这个坐标的变更时，
    // 能认出这是本插件自己的显形回显，从而只摘索引、不推进磁盘缓存代次。
    selfSentEchoes.record(playerId, worldName, x, y, z);
  }

  /**
   * 消费一次「自己刚发出的显形回显」记录（由 {@link BlockChangeRevealListener} 在封包解析线程调用）。
   *
   * <p>一次性语义：命中即移除，因此同坐标随后的服务端真实变更仍会被判为真实变更（照常推进代次）。
   *
   * @return true 表示这次观测到的方块变更就是本插件自己刚发的显形包
   */
  public boolean consumeSelfSentEcho(UUID playerId, String worldName, int x, int y, int z) {
    return selfSentEchoes.consume(playerId, worldName, x, y, z);
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
        + tally.chunksSkipped + "），坐标评估 " + tally.positionsEvaluated + " 个，候选 " + candidateCount
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
      // 候选点数（由 proximity.raycast.samples 提供，配置解析时已钳制 1..8）
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
   */
  private void maybeExpire() {
    if (passes.incrementAndGet() % EXPIRE_EVERY_PASSES == 0L) {
      try {
        chunkIndex.expire();
        revealedSet.expire();
      } catch (Throwable throwable) {
        logThrottled(throwable);
      }
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
    for (int[] offset : instantOffsets(radius)) {
      if (index.containsPosition(worldName, x + offset[0], y + offset[1], z + offset[2])) {
        return true;
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
      if (budget.remaining <= 0) {
        return;
      }

      ProximitySelector.Eye eye = proximity.raycastEnabled() ? eyeOf(player) : null;
      PassTally tally = new PassTally();
      RevealBatch batch = batchRevealSends ? new RevealBatch(world) : null;
      String liveWorld = world.getName();
      for (int[] offset : instantOffsets(radius)) {
        if (budget.remaining <= 0) {
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
      flushBatch(player, batch, tally);
      instantQuota.setRemaining(playerId, tick, Math.max(0, budget.remaining));
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
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