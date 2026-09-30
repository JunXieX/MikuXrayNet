package net.mikumc.mikuxraynet.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * 反矿透直通名单：持有 {@value #PERMISSION} 权限的玩家在此登记，其区块封包走直通路径（不改写、不伪装）。
 *
 * <p><b>为什么用「按玩家维度直通」而不是改缓存键</b>：改写缓存是按「世界名 + 区块坐标 + 配置指纹」
 * 共享的，若把绕过状态混进缓存键，会让每个玩家的缓存互相隔离，命中率崩塌且内存翻倍。因此这里只在
 * 玩家维度做一次短路判定——缓存本身完全不受影响，一致性不被破坏。
 *
 * <p><b>线程纪律</b>：名单是无锁并发集合。写入来自主线程 / Folia 区域线程（登录、退出、定时巡检）
 * 与任意读线程（封包改写路径），只做 {@code contains} 查询，不触碰任何 Bukkit API。
 *
 * <p><b>如何「及时反映」权限变更</b>：登录/退出事件即时增删；此外<b>每 tick</b>巡检在线玩家的权限，
 * 因此权限插件在运行期授予/收回 bypass 至多延迟一个 tick（约 50ms，对操作者无感）生效。
 *
 * <p><b>为什么从「5 秒一巡检」收紧到「每 tick」</b>：本类此前为省开销把巡检放宽到 100 tick，
 * 运行期改权限最长 5 秒不生效。带宽三条热路径（变更合并 / AFK / 零位移）原先在<b>封包线程逐包</b>
 * 调用 {@code player.hasPermission}（见各模块注释），既不满足「netty 线程不碰 Bukkit API」的纪律，
 * 也把正确性押在第三方权限插件的线程安全上。改为统一读本名单后，若仍保留 5 秒窗口，
 * 就会把「逐包即时」退化成「最长 5 秒」，故这里把巡检收紧到每 tick：时效对操作者无感，
 * 且「每玩家每秒 20 次」仍远低于原「每封包一次」的开销（封包速率通常远高于 20 次/秒/玩家）。
 *
 * <p><b>共享入口</b>：{@link #isBypassedNow(UUID)} 暴露「当前活动名单」的静态查询，供带宽模块
 * 在封包线程复用（这些模块由 {@code bandwidth.ThrottlePipeline} 装配，其构造签名不在本类职责范围，
 * 故以静态入口共享同一份名单，而不是让每个模块各自查权限）。名单未装配或已停用时返回 false
 * （与「无直通」一致，fail-open）。
 */
public final class BypassRegistry implements Listener {

  /** 绕过反矿透所必需的权限节点（唯一出处见 {@link Constants#BYPASS_PERMISSION}）。 */
  public static final String PERMISSION = Constants.BYPASS_PERMISSION;

  /**
   * 在线玩家权限巡检周期（tick）：<b>每 tick</b>一次，把运行期权限变更的生效延迟压到一个 tick 内。
   *
   * <p>巡检本身是零分配遍历（不重建在线集合），每 100 tick 才做一次离线残留清理（体积 O(玩家数)）。
   */
  private static final long REFRESH_INTERVAL_TICKS = 1L;

  /** 离线残留清理的周期（tick）：正常退出由退出事件即时处理，这里只兜底异常遗漏。 */
  private static final long SWEEP_INTERVAL_TICKS = 100L;

  /** 当前活动的直通名单（登录/退出/巡检由其维护）；插件未启用或已停用时为 null。 */
  private static volatile BypassRegistry active;

  private final Plugin plugin;
  private final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();

  private ScheduledTask refreshTask;
  /** 巡检计数器（仅调度线程访问），用于低频触发离线残留清理。 */
  private long refreshTicks;

  public BypassRegistry(Plugin plugin) {
    this.plugin = plugin;
  }

  /** 注册登录/退出监听并启动在线玩家权限巡检。可重复调用，异常安全。 */
  public void start() {
    stop();
    active = this;
    try {
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      refreshOnline();
      // GlobalRegionScheduler：Paper 上落在主线程；延迟与周期都以 tick 计（与旧 runTaskTimer 一致）
      this.refreshTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
          scheduled -> refresh(), REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS);
    } catch (Throwable throwable) {
      // 巡检只是「更快反映权限变更」，失败不影响登录/退出事件维护的名单
      plugin.getLogger().warning("直通名单权限巡检启动失败（不影响按登录/退出维护）：" + throwable.getMessage());
    }
  }

  /** 注销监听、取消巡检并清空名单。可重复调用。 */
  public void stop() {
    if (active == this) {
      active = null;
    }
    HandlerList.unregisterAll(this);
    ScheduledTask task = refreshTask;
    refreshTask = null;
    if (task != null) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已结束，忽略
      }
    }
    bypassed.clear();
  }

  /**
   * 当前活动的直通名单的统一查询入口（供带宽等模块在封包线程使用）。
   *
   * <p>只读并发集合，不触碰任何 Bukkit API；名单未装配或已停用时返回 false（与「无直通」一致）。
   */
  public static boolean isBypassedNow(UUID playerId) {
    BypassRegistry registry = active;
    return registry != null && registry.isBypassed(playerId);
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    refresh(player.getUniqueId(), player.hasPermission(PERMISSION));
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    remove(event.getPlayer().getUniqueId());
  }

  /**
   * 按某玩家当前的权限刷新直通名单（登录、权限巡检与单测桩共用此入口）。
   *
   * @param playerId  玩家标识
   * @param isBypassed 是否持有 bypass 权限
   */
  public void refresh(UUID playerId, boolean isBypassed) {
    if (playerId == null) {
      return;
    }
    if (isBypassed) {
      bypassed.add(playerId);
    } else {
      bypassed.remove(playerId);
    }
  }

  /** 从名单中移除（玩家退出）。 */
  public void remove(UUID playerId) {
    if (playerId != null) {
      bypassed.remove(playerId);
    }
  }

  /** 该玩家当前是否直通（不伪装）。 */
  public boolean isBypassed(UUID playerId) {
    return playerId != null && bypassed.contains(playerId);
  }

  /** 当前直通玩家数（诊断用）。 */
  public int size() {
    return bypassed.size();
  }

  /** 巡检一步：每 tick 复查在线玩家权限，并按低频周期清理离线残留。 */
  private void refresh() {
    refreshOnline();
    if (++refreshTicks % SWEEP_INTERVAL_TICKS == 0L) {
      sweepOffline();
    }
  }

  /** 复查全部在线玩家的 bypass 权限（零分配遍历；登录/退出即时维护之外的兜底）。 */
  private void refreshOnline() {
    try {
      for (Player player : Bukkit.getOnlinePlayers()) {
        refresh(player.getUniqueId(), player.hasPermission(PERMISSION));
      }
    } catch (Throwable ignored) {
      // 枚举失败：保留现有名单，下次巡检再修正
    }
  }

  /** 清掉已下线玩家的残留条目（正常退出由退出事件处理，这里只兜底异常遗漏）。 */
  private void sweepOffline() {
    try {
      Set<UUID> online = new HashSet<>();
      for (Player player : Bukkit.getOnlinePlayers()) {
        online.add(player.getUniqueId());
      }
      bypassed.retainAll(online);
    } catch (Throwable ignored) {
      // 枚举失败：保留现有名单，下次清扫再修正
    }
  }
}