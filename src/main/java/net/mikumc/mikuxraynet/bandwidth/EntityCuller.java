package net.mikumc.mikuxraynet.bandwidth;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
 * <p>线程模型（严格遵守「worker 只做纯计算」）：
 * <ol>
 *   <li>主线程/区域线程：从 {@link PlayerTrackEntityEvent} 或周期任务里抓取「眼睛坐标 + 包围盒顶点」；</li>
 *   <li>工作线程：{@link OcclusionRaytracer} 纯数学求出每条射线的体素序列；</li>
 *   <li>主线程/区域线程：读方块判定遮挡，再执行 {@link Player#hideEntity} / {@link Player#showEntity}。</li>
 * </ol>
 *
 * <p>安全策略：强制可见距离内的实体一律可见；只有当包围盒的所有可见顶点射线都被遮挡时才隐藏
 * （宁可少隐藏，也不隐藏可见实体）；玩家自身、其它玩家与烟花不做剔除。
 *
 * <p><b>周期复检的两条通道</b>（修复「先可见、之后才被挡住」实体永不隐藏的缺陷）：
 * <ol>
 *   <li>已隐藏实体：<b>每周期全部复检</b>，可见即尽快恢复（既有行为，不退化）；</li>
 *   <li>其余追踪实体：按<b>轮转分片</b>每周期只复检 {@code entity-culling.recheck-budget} 个，
 *       游标推进，故 {@code ceil(追踪数 / budget)} 个周期内必然覆盖全部追踪实体——
 *       这样实体入场时可见、之后才被墙/地形挡住的场景也能被收敛到隐藏。</li>
 * </ol>
 * 两条通道都复用同一套评估链路（worker 算射线、玩家所在线程读方块并 hide/show），不另写一套。
 */
public final class EntityCuller implements Listener {

  private static final String BYPASS_PERMISSION = "mikuxraynet.bypass";
  private static final int MAX_ERROR_LOGS = 3;

  private final Plugin plugin;
  private final BandwidthConfig.EntityCulling config;
  private final ThrottleStats stats;
  private final ExecutorService workers;
  private final double forceVisibleSquared;
  private final AtomicInteger errorCounter = new AtomicInteger();

  /** 玩家 → 已被隐藏的实体（entityId → Entity）。 */
  private final ConcurrentHashMap<UUID, Map<Integer, Entity>> hidden = new ConcurrentHashMap<>();
  /** 玩家 → 该玩家当前追踪的实体轮转队列（周期复检切分片用，见 {@link TrackedRotation}）。 */
  private final ConcurrentHashMap<UUID, TrackedRotation> rotations = new ConcurrentHashMap<>();
  /** 正在计算的 (玩家, 实体) 组合，避免重复排队。 */
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  /** 每个在线玩家的周期复检任务（Paper 与 Folia 同一套实体调度器；玩家退出即随实体退役失效）。 */
  private final ConcurrentHashMap<UUID, ScheduledTask> recheckTasks = new ConcurrentHashMap<>();

  public EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats) {
    this.plugin = plugin;
    this.config = config;
    this.stats = stats;
    this.forceVisibleSquared = config.forceVisibleDistance() * config.forceVisibleDistance();
    int threads = config.threads() > 0
        ? config.threads()
        : Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
    this.workers = Executors.newFixedThreadPool(threads, runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-Culler");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** 注册事件监听与周期复检任务。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    long interval = Math.max(1, config.updateIntervalTicks());
    // 统一为「每个玩家一条实体调度任务」：Paper 上落在主线程、Folia 上落在区域线程（同一套 API，无需分支）
    for (Player player : Bukkit.getOnlinePlayers()) {
      scheduleRecheck(player, interval);
    }
    plugin.getLogger().info("带宽模块已启用：实体射线剔除（强制可见距离 " + config.forceVisibleDistance() + " 格）");
  }

  /** 注销监听、恢复全部被隐藏实体并关闭线程池。 */
  public void stop() {
    cancelRecheckTasks();
    HandlerList.unregisterAll(this);
    restoreAll();
    inFlight.clear();
    rotations.clear();
    workers.shutdownNow();
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    if (!config.raycast()) {
      return;
    }
    scheduleRecheck(event.getPlayer(), Math.max(1, config.updateIntervalTicks()));
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    cancelRecheckTask(player.getUniqueId());
    for (Entity entity : drainPlayer(player.getUniqueId()).values()) {
      show(player, entity);
    }
    rotations.remove(player.getUniqueId());
    purgeInFlight(player.getUniqueId());
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
    for (Map<Integer, Entity> map : hidden.values()) {
      total += map.size();
    }
    return total;
  }

  /** 取出并从登记表移除某玩家的全部隐藏实体（玩家退出 / 插件停用时的全量恢复入口）；无记录返回空表。 */
  Map<Integer, Entity> drainPlayer(UUID playerId) {
    Map<Integer, Entity> map = hidden.get(playerId);
    if (map == null) {
      return Map.of();
    }
    Map<Integer, Entity> drained = new LinkedHashMap<>(map);
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
  }

  private void cancelRecheckTask(UUID playerId) {
    ScheduledTask task = recheckTasks.remove(playerId);
    if (task != null) {
      task.cancel();
    }
  }

  private void cancelRecheckTasks() {
    for (ScheduledTask task : recheckTasks.values()) {
      task.cancel();
    }
    recheckTasks.clear();
  }

  @EventHandler(ignoreCancelled = true)
  public void onTrack(PlayerTrackEntityEvent event) {
    if (!config.raycast()) {
      return;
    }
    Player player = event.getPlayer();
    if (player.hasPermission(BYPASS_PERMISSION)) {
      return;
    }
    Entity entity = event.getEntity();
    // 玩家可见性交给原版与其它插件，避免干扰 PvP；烟花被隐藏会破坏鞘翅飞行体验
    if (entity instanceof Player || entity.getEntityId() == player.getEntityId()
        || entity instanceof Firework) {
      return;
    }
    // 登记进轮转队列：这是「入场那一刻评估一次」之外的兜底，保证之后出现的遮挡也能被发现
    rotation(player.getUniqueId()).add(entity);
    submitRaycast(player, entity, false);
  }

  /** 实体离开追踪范围时从轮转队列摘除（否则队列会无界增长，并浪费复检预算）。 */
  @EventHandler(ignoreCancelled = true)
  public void onUntrack(PlayerUntrackEntityEvent event) {
    if (!config.raycast()) {
      return;
    }
    TrackedRotation rotation = rotations.get(event.getPlayer().getUniqueId());
    if (rotation != null) {
      rotation.remove(event.getEntity().getEntityId());
    }
  }

  private TrackedRotation rotation(UUID playerId) {
    return rotations.computeIfAbsent(playerId, uuid -> new TrackedRotation());
  }

  /**
   * 周期复检：① 已隐藏实体每周期全部复检（尽快恢复可见，行为不退化）；
   * ② 其余追踪实体按轮转分片复检，每周期不超过 {@code recheck-budget} 个，
   * 因此在 {@code ceil(追踪数 / budget)} 个周期内覆盖全部追踪实体。
   *
   * <p>包可见：供离线单测直接驱动「轮转分片 + 预算」账本行为（真实链路依赖 Bukkit 调度，离线不可用）。
   */
  void recheck(Player player) {
    if (!config.raycast() || !player.isOnline()) {
      return;
    }
    UUID playerId = player.getUniqueId();

    // ① 已隐藏实体：每周期全量复检（既有行为）
    Map<Integer, Entity> hiddenMap = hidden.get(playerId);
    if (hiddenMap != null && !hiddenMap.isEmpty()) {
      for (Entity entity : new ArrayList<>(hiddenMap.values())) {
        if (!entity.isValid()) {
          hiddenMap.remove(entity.getEntityId());
          continue;
        }
        stats.recheckSubmitted.increment();
        submitRaycast(player, entity, true);
      }
    }

    // ② 其余追踪实体：轮转分片，每周期只取一小批（已隐藏者由 ① 负责，这里跳过）
    TrackedRotation rotation = rotations.get(playerId);
    if (rotation == null) {
      return;
    }
    Set<Integer> hiddenIds = hiddenMap == null ? Set.of() : hiddenMap.keySet();
    for (Entity entity : rotation.nextBatch(config.recheckBudget(), hiddenIds)) {
      if (!entity.isValid()) {
        rotation.remove(entity.getEntityId());
        continue;
      }
      stats.recheckSubmitted.increment();
      submitRaycast(player, entity, true);
    }
  }

  /** 抓取纯数据后交给工作线程求体素序列；{@code fromRecheck} 仅用于统计归属（不影响判定逻辑）。 */
  private void submitRaycast(Player player, Entity entity, boolean fromRecheck) {
    // 烟花被隐藏会破坏鞘翅飞行体验，直接跳过
    if (entity instanceof Firework) {
      return;
    }
    String key = player.getUniqueId() + ":" + entity.getEntityId();
    if (!inFlight.add(key)) {
      return;
    }

    double[] eye;
    double[][] vertices;
    try {
      // 强制可见距离内一律不剔除——复检通道同样适用，避免轮转复检把近处实体误藏
      if (player.getLocation().distanceSquared(entity.getLocation()) <= forceVisibleSquared) {
        inFlight.remove(key);
        showIfHidden(player, entity);
        return;
      }
      Location eyeLocation = player.getEyeLocation();
      eye = new double[] {eyeLocation.getX(), eyeLocation.getY(), eyeLocation.getZ()};
      BoundingBox box = entity.getBoundingBox();
      vertices = OcclusionRaytracer.visibleVertices(eye,
          box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    } catch (Throwable throwable) {
      inFlight.remove(key);
      logThrottled(throwable);
      return;
    }

    try {
      workers.execute(() -> {
        List<int[]> paths;
        try {
          paths = OcclusionRaytracer.traceAll(eye, vertices, config.raySamples());
        } catch (Throwable throwable) {
          inFlight.remove(key);
          return;
        }
        Schedulers.onEntity(plugin, player, () -> {
          try {
            evaluate(player, entity, paths, fromRecheck);
          } finally {
            inFlight.remove(key);
          }
        });
      });
    } catch (RejectedExecutionException exception) {
      // 线程池已满：本实体不剔除（fail-open）
      inFlight.remove(key);
    }
  }

  /** 主线程/区域线程：读方块判定遮挡并执行隐藏/恢复（包可见：供离线单测直接驱动账本行为）。 */
  void evaluate(Player player, Entity entity, List<int[]> paths) {
    evaluate(player, entity, paths, false);
  }

  /**
   * 评估入口（包可见：供离线单测直接驱动「复检通道」的计数归属）。
   *
   * @param fromRecheck 是否来自周期复检：仅用于把计数归入「复检致隐藏 / 复检致恢复」，不影响判定逻辑
   */
  void evaluate(Player player, Entity entity, List<int[]> paths, boolean fromRecheck) {
    if (!entity.isValid() || !player.isOnline()) {
      Map<Integer, Entity> map = hidden.get(player.getUniqueId());
      if (map != null) {
        map.remove(entity.getEntityId());
      }
      return;
    }
    if (paths.isEmpty()) {
      if (showIfHidden(player, entity) && fromRecheck) {
        stats.recheckShown.increment();
      }
      return;
    }

    World world = entity.getWorld();
    boolean allBlocked = true;
    for (int[] path : paths) {
      // 起点与目标同一体素：视为无遮挡
      if (path.length == 0) {
        allBlocked = false;
        break;
      }
      boolean blocked = false;
      for (int i = 0; i + 2 < path.length; i += 3) {
        if (isOccluding(world, path[i], path[i + 1], path[i + 2])) {
          blocked = true;
          break;
        }
      }
      if (!blocked) {
        allBlocked = false;
        break;
      }
    }

    if (allBlocked) {
      if (hide(player, entity) && fromRecheck) {
        stats.recheckHidden.increment();
      }
    } else if (showIfHidden(player, entity) && fromRecheck) {
      stats.recheckShown.increment();
    }
  }

  /** @return 是否真的新登记并执行了 hideEntity（已隐藏 / 失败时为 false）。 */
  private boolean hide(Player player, Entity entity) {
    Map<Integer, Entity> map = hidden.computeIfAbsent(player.getUniqueId(),
        uuid -> new ConcurrentHashMap<>());
    if (map.containsKey(entity.getEntityId())) {
      return false;
    }
    try {
      player.hideEntity(plugin, entity);
      map.put(entity.getEntityId(), entity);
      stats.entitiesHidden.increment();
      return true;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
  }

  /** @return 是否真的从账本摘除并执行了 showEntity（未隐藏 / 实体已失效时为 false）。 */
  private boolean showIfHidden(Player player, Entity entity) {
    Map<Integer, Entity> map = hidden.get(player.getUniqueId());
    if (map == null || map.remove(entity.getEntityId()) == null) {
      return false;
    }
    return show(player, entity);
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

  /** 停用时的兜底：把所有被隐藏的实体恢复显示，不留副作用。 */
  private void restoreAll() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      Map<Integer, Entity> drained = drainPlayer(player.getUniqueId());
      if (drained.isEmpty()) {
        continue;
      }
      for (Entity entity : drained.values()) {
        // 一律回到玩家所属线程执行（Paper 上即主线程，Folia 上即区域线程）
        Schedulers.onEntity(plugin, player, () -> show(player, entity));
      }
    }
    // 离线玩家（停用时已不在线）的登记一并丢弃：它们已随退出被恢复，这里只兜底清账本
    hidden.clear();
  }

  private void purgeInFlight(UUID playerId) {
    String prefix = playerId + ":";
    inFlight.removeIf(key -> key.startsWith(prefix));
  }

  private static boolean isOccluding(World world, int x, int y, int z) {
    try {
      return world.getBlockData(x, y, z).isOccluding();
    } catch (Throwable throwable) {
      return false;
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= MAX_ERROR_LOGS) {
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
   * <p>线程模型：事件与周期任务都落在玩家所属线程上；加锁仅为防御两者线程不一致（Folia 区域线程）。
   */
  static final class TrackedRotation {

    private final List<Entity> order = new ArrayList<>();
    private int cursor;

    /** 加入一个被追踪实体（按 entityId 去重）。 */
    synchronized void add(Entity entity) {
      int entityId = entity.getEntityId();
      for (Entity existing : order) {
        if (existing.getEntityId() == entityId) {
          return;
        }
      }
      order.add(entity);
    }

    /** 摘除一个不再被追踪的实体，并修正游标使「下一页」不被跳过。 */
    synchronized void remove(int entityId) {
      for (int i = 0; i < order.size(); i++) {
        if (order.get(i).getEntityId() == entityId) {
          order.remove(i);
          if (cursor > i) {
            cursor--;
          }
          return;
        }
      }
    }

    synchronized int size() {
      return order.size();
    }

    /**
     * 取出本轮要复检的一小批追踪实体（跳过已隐藏者——它们由「每周期全量复检」通道负责），
     * 并把游标推进一批，保证有限周期内覆盖全部追踪实体。
     */
    synchronized List<Entity> nextBatch(int budget, Set<Integer> hiddenIds) {
      int size = order.size();
      if (size == 0) {
        cursor = 0;
        return List.of();
      }
      int step = Math.min(Math.max(1, budget), size);
      int start = Math.floorMod(cursor, size);
      List<Entity> batch = new ArrayList<>(step);
      for (int i = 0; i < step; i++) {
        Entity entity = order.get((start + i) % size);
        if (!hiddenIds.contains(entity.getEntityId())) {
          batch.add(entity);
        }
      }
      cursor = (start + step) % size;
      return batch;
    }
  }
}