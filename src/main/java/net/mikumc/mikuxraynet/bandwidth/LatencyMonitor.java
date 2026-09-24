package net.mikumc.mikuxraynet.bandwidth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 高延迟降视距：延迟持续超过阈值的玩家降低视距，降低其下游带宽占用；延迟恢复后还原。
 *
 * <p>判定为纯状态机 {@link PingState}（时钟可注入，可单测）：先连续观察，
 * 只有「不低于阈值且持续超过 sustain 秒」才触发一次降级。
 *
 * <p>视距的读写与还原全部在玩家所属线程执行（非 Folia 为统一主线程任务，Folia 为各玩家的
 * 区域任务）；原始视距只存内存，玩家退出即清理，插件停用时统一还原。
 */
public final class LatencyMonitor implements Listener {

  private final Plugin plugin;
  private final BandwidthConfig.Latency config;
  private final ThrottleStats stats;
  private final long sustainMillis;

  private final ConcurrentHashMap<UUID, PingState> watches = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Integer> originalViewDistance = new ConcurrentHashMap<>();

  private BukkitTask globalTask;

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
    if (PlatformSupport.isFolia()) {
      for (Player player : Bukkit.getOnlinePlayers()) {
        watches.put(player.getUniqueId(), new PingState());
        Schedulers.repeatOnEntity(plugin, player, period, period, () -> tick(player));
      }
    } else {
      globalTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, period, period);
    }
    plugin.getLogger().info("带宽模块已启用：高延迟降视距（阈值 " + config.thresholdMillis() + "ms，持续 "
        + config.sustainSeconds() + " 秒，降 " + config.reduceViewDistance() + " 区块）");
  }

  /** 停用：取消任务并还原所有被下调的视距。 */
  public void stop() {
    if (globalTask != null) {
      globalTask.cancel();
      globalTask = null;
    }
    HandlerList.unregisterAll(this);
    restoreAll();
    watches.clear();
    originalViewDistance.clear();
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    watches.put(player.getUniqueId(), new PingState());
    if (PlatformSupport.isFolia()) {
      long period = Math.max(20L, config.checkIntervalSeconds() * 20L);
      Schedulers.repeatOnEntity(plugin, player, period, period, () -> tick(player));
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    watches.remove(playerId);
    originalViewDistance.remove(playerId);
  }

  private void tickAll() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      tick(player);
    }
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
      int current = player.getSendViewDistance();
      if (current <= config.minViewDistance()) {
        return;
      }
      int target = Math.max(config.minViewDistance(), current - Math.max(1, config.reduceViewDistance()));
      if (target >= current) {
        return;
      }
      originalViewDistance.put(player.getUniqueId(), current);
      player.setViewDistance(target);
      stats.viewDistanceReduced.increment();
    } catch (Throwable throwable) {
      // 视距调整失败不影响其它功能
      plugin.getLogger().fine("降低视距失败：" + throwable.getMessage());
    }
  }

  private void restore(Player player) {
    Integer original = originalViewDistance.remove(player.getUniqueId());
    if (original == null) {
      return;
    }
    try {
      player.setViewDistance(original);
      stats.viewDistanceRestored.increment();
    } catch (Throwable throwable) {
      plugin.getLogger().fine("还原视距失败：" + throwable.getMessage());
    }
  }

  /** 停用兜底：还原全部被下调的视距。 */
  private void restoreAll() {
    for (Map.Entry<UUID, Integer> entry : originalViewDistance.entrySet()) {
      Player player = Bukkit.getPlayer(entry.getKey());
      if (player == null) {
        continue;
      }
      int original = entry.getValue();
      if (PlatformSupport.isFolia()) {
        Schedulers.onEntity(plugin, player, () -> setViewDistance(player, original));
      } else {
        setViewDistance(player, original);
      }
    }
    originalViewDistance.clear();
  }

  private void setViewDistance(Player player, int distance) {
    try {
      player.setViewDistance(distance);
      stats.viewDistanceRestored.increment();
    } catch (Throwable throwable) {
      plugin.getLogger().fine("还原视距失败：" + throwable.getMessage());
    }
  }
}