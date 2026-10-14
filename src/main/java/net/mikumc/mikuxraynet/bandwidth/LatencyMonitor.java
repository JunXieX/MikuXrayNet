package net.mikumc.mikuxraynet.bandwidth;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
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
 *
 * <p><b>读值的基线口径</b>：{@code getSendViewDistance()} 在玩家<b>从未单独设置过</b> send 视距时返回
 * {@code -1}（API 明确定义 -1 为「回到服务端默认」）。因此降级判定的<b>基线</b>必须取「生效视距」
 * {@code getViewDistance()}——否则未设置过的玩家（占绝大多数）会命中 {@code -1 <= floor} 而永不降级，
 * 模块形同虚设。但记录与还原仍写回<b>原始 raw</b>（可能为 -1）：只有把 -1 原样回写，
 * API 才会把该玩家的 send 视距真正「还原为服务端默认」，还原语义不变。
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
  /** 「停用时跨区域（Folia）无法同步还原视距」的中文 WARN 一次性闸门。 */
  private final AtomicBoolean crossRegionRestoreNoticed = new AtomicBoolean();
  /**
   * 停用中标记：{@code stop()} 首行置位，用于闭合停用期并发——正在跑的 tick 若在此之后才走到
   * {@code reduce()}，会被拒绝，绝不把刚被还原的视距再次降低。
   */
  private volatile boolean stopping;

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
    // 先取消在途任务、再同步还原：setEnabled(false) 先置 isEnabled=false 再调 onDisable，
    // 停用链路的 EntityScheduler 一律被拒（旧实现经 Schedulers 静默吞掉 → 视距卡在降级值直到重登）。
    stopping = true;
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
    if (stopping || !player.isOnline()) {
      return;
    }
    // 世界黑名单：命中世界不做任何带宽优化。若该玩家此前已被降过视距（例如从普通世界走进黑名单
    // 世界），此处立即还原，避免黑名单世界仍处于降级状态；否则直接跳过采样。
    if (WorldBlacklistConfig.isBlacklistedNow(player.getWorld().getName())) {
      restore(player);
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
    if (stopping) {
      // 停用已开始：绝不再下调视距（否则会把刚被 restoreAll 还原的视距又降回去）
      return;
    }
    try {
      // 读 send 视距并保留原始值 raw：未单独设置过的玩家返回 -1（= 服端默认），此时必须以「生效视距」
      // getViewDistance() 作降级基线，否则 -1 会命中下面的 floor 比较而永不降级（见类注释）。
      int raw = player.getSendViewDistance();
      int current = raw < 0 ? player.getViewDistance() : raw;
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
      // 记录原始 raw（可能为 -1）：还原时原样回写，API 才会把 send 视距还原为服端默认（见类注释）。
      originalViewDistance.put(player.getUniqueId(), raw);
      player.setSendViewDistance(target);
      // 停用竞态收口（Folia：stop() 可能在另一条区域线程上并发执行）：<b>写入之后再读一次</b> volatile
      // stopping。这样保证「本次读到 false ⇒ stop() 尚未置位 ⇒ 其 restoreAll 必然发生在本次写入之后、
      // 一定看得到这条记录」；读到 true 则说明 stop() 已在还原，必须自行回滚——否则这条记录被旧实例
      // 一起丢弃、新实例又不知道，玩家会<b>永久卡在降级后的视距</b>。
      if (stopping) {
        // restoreAll 若已把该记录摘走／随整表 clear() 丢弃，就再也不会替我们还原，因此这里<b>无条件</b>
        // 回滚：若它已经还原过，重复写回同一个 raw 值只是幂等重复，无害。回滚必须写回 raw（可能为 -1），
        // 与记录值同源，才能保证还原语义一致。
        originalViewDistance.remove(player.getUniqueId(), raw);
        try {
          player.setSendViewDistance(raw);
        } catch (Throwable throwable) {
          plugin.getLogger().fine("停用竞态回滚视距失败：" + throwable.getMessage());
        }
        return;
      }
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
      // 与 reduce 配对：还原也写 send 视距（originalViewDistance 记录的正是原始 raw，含未设置时的 -1
      // = 服端默认；-1 由 API 明确定义为「回到服务端默认」，故还原语义不变）
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
    boolean stillEnabled = plugin.isEnabled();
    for (Map.Entry<UUID, Integer> entry : originalViewDistance.entrySet()) {
      Player player = Bukkit.getPlayer(entry.getKey());
      if (player == null) {
        continue;
      }
      int original = entry.getValue();
      if (stillEnabled) {
        // 热重载路径：回到玩家所属线程执行（Paper 上即主线程，Folia 上即区域线程）
        Schedulers.onEntity(plugin, player, () -> setSendViewDistance(player, original));
      } else {
        // 停用路径：调度器已不可用，同步还原
        restoreNow(player, original);
      }
    }
    originalViewDistance.clear();
  }

  /**
   * 停用路径的<b>同步</b>还原（不再经调度器）：Paper 主线程恒可同步；Folia 仅当玩家属于当前区域时才可同步，
   * 跨区域者无法在当前线程安全写视距，打一次性中文 WARN 提示「需重登才能还原」。
   */
  private void restoreNow(Player player, int original) {
    if (!ownsCurrentRegion(player)) {
      if (crossRegionRestoreNoticed.compareAndSet(false, true)) {
        plugin.getLogger().warning("插件停用时检测到跨区域（Folia）玩家视距无法同步还原："
            + "个别玩家的视距可能需重新登录后才还原。此为 Folia 区域化线程的限制，非本插件故障。");
      }
      return;
    }
    setSendViewDistance(player, original);
  }

  /** 当前线程是否拥有该玩家（Paper 上恒真；Folia 上即「该玩家所在区域线程」）。判定异常时按「拥有」处理。 */
  private static boolean ownsCurrentRegion(Player player) {
    try {
      return Bukkit.isOwnedByCurrentRegion(player);
    } catch (Throwable throwable) {
      return true;
    }
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