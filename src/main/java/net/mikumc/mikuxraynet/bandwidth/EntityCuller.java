package net.mikumc.mikuxraynet.bandwidth;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
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
    Map<Integer, Entity> map = hidden.remove(player.getUniqueId());
    if (map != null) {
      for (Entity entity : map.values()) {
        show(player, entity);
      }
    }
    purgeInFlight(player.getUniqueId());
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
    // 玩家可见性交给原版与其它插件，避免干扰 PvP
    if (entity instanceof Player || entity.getEntityId() == player.getEntityId()) {
      return;
    }

    try {
      double distanceSquared = player.getLocation().distanceSquared(entity.getLocation());
      if (distanceSquared <= forceVisibleSquared) {
        showIfHidden(player, entity);
        return;
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return;
    }
    submitRaycast(player, entity);
  }

  /** 周期复检：对已隐藏的实体重新判定，可见即恢复。 */
  private void recheck(Player player) {
    if (!config.raycast() || !player.isOnline()) {
      return;
    }
    Map<Integer, Entity> map = hidden.get(player.getUniqueId());
    if (map == null || map.isEmpty()) {
      return;
    }
    for (Entity entity : new ArrayList<>(map.values())) {
      if (!entity.isValid()) {
        map.remove(entity.getEntityId());
        continue;
      }
      submitRaycast(player, entity);
    }
  }

  /** 抓取纯数据后交给工作线程求体素序列。 */
  private void submitRaycast(Player player, Entity entity) {
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
            evaluate(player, entity, paths);
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

  /** 主线程/区域线程：读方块判定遮挡并执行隐藏/恢复。 */
  private void evaluate(Player player, Entity entity, List<int[]> paths) {
    if (!entity.isValid() || !player.isOnline()) {
      Map<Integer, Entity> map = hidden.get(player.getUniqueId());
      if (map != null) {
        map.remove(entity.getEntityId());
      }
      return;
    }
    if (paths.isEmpty()) {
      showIfHidden(player, entity);
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
      hide(player, entity);
    } else {
      showIfHidden(player, entity);
    }
  }

  private void hide(Player player, Entity entity) {
    Map<Integer, Entity> map = hidden.computeIfAbsent(player.getUniqueId(),
        uuid -> new ConcurrentHashMap<>());
    if (map.containsKey(entity.getEntityId())) {
      return;
    }
    try {
      player.hideEntity(plugin, entity);
      map.put(entity.getEntityId(), entity);
      stats.entitiesHidden.increment();
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  private void showIfHidden(Player player, Entity entity) {
    Map<Integer, Entity> map = hidden.get(player.getUniqueId());
    if (map == null || map.remove(entity.getEntityId()) == null) {
      return;
    }
    show(player, entity);
  }

  private void show(Player player, Entity entity) {
    try {
      if (entity.isValid()) {
        player.showEntity(plugin, entity);
        stats.entitiesShown.increment();
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 停用时的兜底：把所有被隐藏的实体恢复显示，不留副作用。 */
  private void restoreAll() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      Map<Integer, Entity> map = hidden.remove(player.getUniqueId());
      if (map == null) {
        continue;
      }
      for (Entity entity : map.values()) {
        // 一律回到玩家所属线程执行（Paper 上即主线程，Folia 上即区域线程）
        Schedulers.onEntity(plugin, player, () -> show(player, entity));
      }
    }
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
}