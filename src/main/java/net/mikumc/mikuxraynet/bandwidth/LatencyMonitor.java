package net.mikumc.mikuxraynet.bandwidth;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * 高延迟降视距：延迟持续超过阈值的玩家降低视距，降低其下游带宽占用；延迟恢复后还原。
 *
 * <p>判定为纯状态机 {@link PingState}（时钟可注入，可单测）：先连续观察，
 * 只有「不低于阈值且持续超过 sustain 秒」才触发一次降级。
 *
 * <p>视距的读写与还原全部在玩家所属线程执行（统一走每个玩家一条实体调度任务：Paper 上落在主线程，
 * Folia 上落在区域线程）；原始视距只存内存，玩家退出即清理，插件停用时统一还原。
 *
 * <p><b>读写必须成对</b>：本类只动「send 视距」（{@code getSendViewDistance()} /
 * {@code setSendViewDistance()}）—— Paper 上 {@code view-distance} 与 {@code send-view-distance}
 * 是两个独立配置。旧实现「读 send、写 view」会在一次降级+还原后把玩家的 {@code view-distance}
 * 永久改成原 send 值（服主把 send 配得低于 view 时尤其明显），故读写与还原统一到 send 这一对。
 */
public final class LatencyMonitor implements Listener {

  private final Plugin plugin;
  private final BandwidthConfig.Latency config;
  private final ThrottleStats stats;
  private final long sustainMillis;

  private final ConcurrentHashMap<UUID, PingState> watches = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Integer> originalViewDistance = new ConcurrentHashMap<>();

  /** 每个在线玩家的周期检查任务（Paper 与 Folia 同一套实体调度器；玩家退出即随实体退役失效）。 */
  private final ConcurrentHashMap<UUID, ScheduledTask> checkTasks = new ConcurrentHashMap<>();

  public LatencyMonitor(Plugin plugin, BandwidthConfig.Latency config, ThrottleStats stats) {
    this.plugin = plugin;
    this.config = config;
    this.stats = stats;
    this.sustainMillis = Math.max(0L, config.sustainSeconds()) * 1000L;
  }

  /** 注册监听与周期检查。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    long period = Math.max(20L, config.checkIntervalSeconds() * 20L);
    // 统一为「每个玩家一条实体调度任务」：Paper 上落在主线程、Folia 上落在区域线程（同一套 API，无需分支）
    for (Player player : Bukkit.getOnlinePlayers()) {
      watches.put(player.getUniqueId(), new PingState());
      scheduleCheck(player, period);
    }
    plugin.getLogger().info("带宽模块已启用：高延迟降视距（阈值 " + config.thresholdMillis() + "ms，持续 "
        + config.sustainSeconds() + " 秒，降 " + config.reduceViewDistance() + " 区块）");
  }

  /** 停用：取消任务并还原所有被下调的视距。 */
  public void stop() {
    cancelCheckTasks();
    HandlerList.unregisterAll(this);
    restoreAll();
    watches.clear();
    originalViewDistance.clear();
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    watches.put(player.getUniqueId(), new PingState());
    scheduleCheck(player, Math.max(20L, config.checkIntervalSeconds() * 20L));
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    cancelCheckTask(playerId);
    watches.remove(playerId);
    originalViewDistance.remove(playerId);
  }

  /** 为单个玩家登记周期检查任务（初始延迟与周期都以 tick 计）；同一玩家重复登记时以最新任务为准。 */
  private void scheduleCheck(Player player, long periodTicks) {
    ScheduledTask task = Schedulers.repeatOnEntity(plugin, player, periodTicks, periodTicks,
        () -> tick(player));
    if (task == null) {
      return;
    }
    ScheduledTask previous = checkTasks.put(player.getUniqueId(), task);
    if (previous != null) {
      previous.cancel();
    }
  }

  private void cancelCheckTask(UUID playerId) {
    ScheduledTask task = checkTasks.remove(playerId);
    if (task != null) {
      task.cancel();
    }
  }

  private void cancelCheckTasks() {
    for (ScheduledTask task : checkTasks.values()) {
      task.cancel();
    }
    checkTasks.clear();
  }

  /** 单个玩家的采样与降级/还原（必须在玩家所属线程执行）。 */
  private void tick(Player player) {
    if (!player.isOnline()) {
      return;
    }
    PingState state = watches.computeIfAbsent(player.getUniqueId(), uuid -> new PingState());
    int ping;
    try {
      ping = player.getPing();
    } catch (Throwable throwable) {
      return;
    }
    long now = System.currentTimeMillis();

    if (state.shouldReduce(ping, now, config.thresholdMillis(), sustainMillis)) {
      reduce(player);
    } else if (state.shouldRestore(ping, config.thresholdMillis())) {
      restore(player);
    }
  }

  private void reduce(Player player) {
    try {
      // 读的必须是 send 视距：与下面写入、以及 restore 的还原严格配对，
      // 否则一次降级+还原会把 view-distance 永久改成原 send 值（见类注释）
      int current = player.getSendViewDistance();
      // 生效下限 = max(API 硬下限 2, 配置的 min-view-distance)：配置下限是弱网玩家的体验兜底，
      // 必须与 API 硬下限取并集，绝不能因为某次钳制被击穿。
      int floor = Math.max(2, config.minViewDistance());
      if (current <= floor) {
        return;
      }
      int target = Math.max(floor, current - Math.max(1, config.reduceViewDistance()));
      // setSendViewDistance 要求目标 ≤ 玩家当前 view-distance 且落在 [2,32]：按 API 上限收敛，
      // 只降不升（target 必 < current）。
      int apiMax = Math.min(32, player.getViewDistance());
      target = Math.min(target, apiMax);
      // 收敛后若仍不低于当前值（无实际降低），或已被压到生效下限之下（服务端 view-distance 比下限还小，
      // 属异常状态），则放弃本次降级——宁可不动，也绝不把视距降到配置下限以下。
      if (target >= current || target < floor) {
        return;
      }
      originalViewDistance.put(player.getUniqueId(), current);
      player.setSendViewDistance(target);
      stats.viewDistanceReduced.increment();
    } catch (Throwable throwable) {
      // 视距调整失败不影响其它功能
      plugin.getLogger().fine("降低视距失败：" + throwable.getMessage());
    }
  }

  private void restore(Player player) {
    // 用 get（而非 remove）取原值：还原失败时保留原值，下一次 tick 仍可重试——
    // 旧实现先 remove 再 set，一旦 set 抛异常原视距就永久丢失，玩家被永久卡在降级后的视距。
    Integer original = originalViewDistance.get(player.getUniqueId());
    if (original == null) {
      return;
    }
    try {
      // 与 reduce 配对：还原也写 send 视距（originalViewDistance 记录的正是原 send 值）
      player.setSendViewDistance(original);
    } catch (Throwable throwable) {
      plugin.getLogger().fine("还原视距失败（保留原值，下次检查重试）：" + throwable.getMessage());
      return;
    }
    originalViewDistance.remove(player.getUniqueId(), original);
    stats.viewDistanceRestored.increment();
  }

  /** 停用兜底：还原全部被下调的视距。 */
  private void restoreAll() {
    for (Map.Entry<UUID, Integer> entry : originalViewDistance.entrySet()) {
      Player player = Bukkit.getPlayer(entry.getKey());
      if (player == null) {
        continue;
      }
      int original = entry.getValue();
      // 一律回到玩家所属线程执行（Paper 上即主线程，Folia 上即区域线程）
      Schedulers.onEntity(plugin, player, () -> setSendViewDistance(player, original));
    }
    originalViewDistance.clear();
  }

  /** 与 {@link #reduce} 配对地还原 send 视距（停用兜底路径）。 */
  private void setSendViewDistance(Player player, int distance) {
    try {
      player.setSendViewDistance(distance);
      stats.viewDistanceRestored.increment();
    } catch (Throwable throwable) {
      plugin.getLogger().fine("还原视距失败：" + throwable.getMessage());
    }
  }
}