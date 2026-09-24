package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bandwidth.Schedulers;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
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
 *   <li><b>可见性判定</b>（默认开启，多候选点采样）：先识别方块的暴露面，只在暴露面上取至多 5 个采样点
 *       （正对玩家的面中心 → 包围盒最近点 → 面四角）分别做体素步进，任一条通畅即显形；六面全被遮挡
 *       （完全掩埋）时一个点都不采 → 直接判不可见，绝不隔着墙还原。</li>
 * </ul>
 *
 * <p><b>线程纪律</b>：位置读取、方块读取与发包都在主线程 / Folia 区域线程（非 Folia 为统一主线程任务，
 * Folia 为各玩家的区域任务）；纯计算（视锥角度）交给 {@link MikuWorkPool} 的工作线程，
 * 算完再回到玩家所属线程。可见性判定必须在主线程 / 区域线程做——它要先读 6 个邻块判暴露面，
 * 因此不再像旧实现那样把射线路径预计算在工作线程（那时只有单点采样、无需读邻块）。
 * 工作队列已满时退化为「主线程直接做纯计算」，功能不降级、绝不阻塞。
 *
 * <p><b>发包纪律</b>：ProtocolLib 是唯一封包通道，包用 {@code createPacket} 构造、用
 * {@code sendServerPacket(..., filters=false)} 发出，因此显形包不会再次进入本插件的出站监听器。
 * 同一坐标只显形一次（发送成功后立即写入该玩家的 {@link RevealedSet}）；区块被重新下发或被卸载时
 * 该区块的已显形标记一并失效（客户端又会看到伪装结果，必须重新显形）。单个坐标失败只记日志并跳过，
 * 绝不中断整体。
 */
public final class ProximityRevealer implements Listener {

  private static final int MAX_ERROR_LOGS = 3;
  /** 每多少次巡检清理一次过期条目（默认周期 5 tick 时约 5 秒一次）。 */
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

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final AntiXrayConfig config;
  private final AntiXrayConfig.Proximity proximity;
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

  private ScheduledTask globalTask;

  public ProximityRevealer(Plugin plugin, ProtocolManager protocolManager, AntiXrayConfig config,
      ObfuscatedChunkIndex chunkIndex, RevealedSet revealedSet, ProximityStats stats,
      BypassRegistry bypassRegistry, MikuWorkPool workPool) {
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.proximity = config.proximity();
    this.chunkIndex = chunkIndex;
    this.revealedSet = revealedSet;
    this.stats = stats;
    this.bypassRegistry = bypassRegistry;
    this.workPool = workPool;
  }

  /** 启动巡检：非 Folia 为统一主线程任务；Folia 为各玩家的区域任务（玩家退役后自动失效）。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    long interval = Math.max(1, proximity.intervalTicks());
    if (PlatformSupport.isFolia()) {
      // 保留平台差异（策略分支，非 API 分支）：Folia 无法从全局线程安全读取玩家世界/位置，
      // 因此每个玩家一条区域任务（实体调度器，延迟与周期都以 tick 计）。
      for (Player player : Bukkit.getOnlinePlayers()) {
        Schedulers.repeatOnEntity(plugin, player, interval, interval, () -> pass(player));
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
            ? "已开启（多候选点，暴露面上至多 5 点）" : "已关闭"));
  }

  /** 停用：注销任务与事件监听，并清空两个显形索引（不留副作用）。 */
  public void stop() {
    if (globalTask != null) {
      globalTask.cancel();
      globalTask = null;
    }
    HandlerList.unregisterAll(this);
    chunkIndex.clear();
    revealedSet.clear();
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    if (!PlatformSupport.isFolia()) {
      return;
    }
    long interval = Math.max(1, proximity.intervalTicks());
    Player player = event.getPlayer();
    Schedulers.repeatOnEntity(plugin, player, interval, interval, () -> pass(player));
  }

  /** 玩家登出即清理其已显形标记，避免为离线玩家保留坐标。 */
  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    revealedSet.clearPlayer(event.getPlayer().getUniqueId());
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

  /** 统计计数（供 {@code /mikuxraynet status} 读取）。 */
  public ProximityStats stats() {
    return stats;
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
    if (!config.appliesTo(worldName)) {
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
      stats.revealsQueuedSkipped.increment();
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

    if (candidates != null) {
      for (ObfuscatedChunkIndex.Position position : candidates) {
        if (budget.remaining <= 0) {
          break;
        }
        sendOne(player, world, position.x(), position.y(), position.z(), eye, budget, tally);
      }
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
          plan.coordinates[i * 3 + 2], eye, budget, tally);
    }
    logFirstRevealDiagnostic(candidateCount, frustumCulled, tally);
  }

  /** 单个坐标：区块未加载则跳过、不可见则跳过，否则读真实方块并发包、成功后标记该玩家已显形。 */
  private void sendOne(Player player, World world, int x, int y, int z, ProximitySelector.Eye eye,
      Budget budget, PassTally tally) {
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

    if (send(player, world, x, y, z)) {
      revealedSet.mark(player.getUniqueId(), ChunkKey.ofBlock(world.getName(), x, z), x, y, z);
      stats.revealsSent.increment();
      tally.sent++;
      budget.remaining--;
    } else {
      stats.revealsSkipped.increment();
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
        + tally.chunksSkipped + "），坐标评估 " + tally.positionsEvaluated + " 个，候选 " + candidateCount
        + " 个，视锥剔除 " + frustumCulled + "，射线剔除 " + tally.rayCulled
        + "，实际发送 " + tally.sent);
  }

  /**
   * 主线程 / 区域线程读方块做可见性判定：先读 6 个邻块判暴露面，再对暴露面上的至多 5 个采样点做体素步进，
   * 任一条通畅即可见。任何异常都按「可见」处理（fail-open，宁可多显形）。
   */
  private boolean isVisible(World world, ProximitySelector.Eye eye, int x, int y, int z) {
    try {
      int samples = proximity.raycastSamples();
      return ProximitySelector.isVisible(eye, x, y, z, (blockX, blockY, blockZ) -> {
        BlockData data = world.getBlockData(blockX, blockY, blockZ);
        return data != null && data.isOccluding();
      }, samples);
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return true;
    }
  }

  /** 构造并发送一条方块变更包；任何异常都只返回 false，不影响原包流程与其它坐标。 */
  private boolean send(Player player, World world, int x, int y, int z) {
    try {
      BlockData data = world.getBlockData(x, y, z);
      if (data == null) {
        return false;
      }

      BlockPosition blockPosition = new BlockPosition(x, y, z);
      PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
      packet.getBlockPositionModifier().writeSafely(0, blockPosition);
      packet.getBlockData().writeSafely(0, WrappedBlockData.createData(data));

      // 回读自检：结构不符宁可跳过，也不能把错坐标或错方块发给客户端
      if (!blockPosition.equals(packet.getBlockPositionModifier().readSafely(0))
          || packet.getBlockData().readSafely(0) == null) {
        return false;
      }

      // filters=false：显形包由本模块自行发出，不能再触发本插件的出站监听器
      protocolManager.sendServerPacket(player, packet, false);
      return true;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
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

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING,
          "邻近显形失败，已跳过本次处理（不影响反矿透主流程）", throwable);
    }
  }
}