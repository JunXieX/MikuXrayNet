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
 * <p><b>如何「及时反映」权限变更</b>：登录/退出事件即时增删；此外周期巡检在线玩家的权限，
 * 因此权限插件在运行期授予/收回 bypass 也能在很短时间内生效（无需重登录）。
 */
public final class BypassRegistry implements Listener {

  /** 绕过反矿透所必需的权限节点。 */
  public static final String PERMISSION = "mikuxraynet.bypass";

  /** 在线玩家权限巡检周期（tick）：约 2 秒一次，用于捕捉运行期的权限变更。 */
  private static final long REFRESH_INTERVAL_TICKS = 40L;

  private final Plugin plugin;
  private final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();

  private ScheduledTask refreshTask;

  public BypassRegistry(Plugin plugin) {
    this.plugin = plugin;
  }

  /** 注册登录/退出监听并启动在线玩家权限巡检。可重复调用，异常安全。 */
  public void start() {
    stop();
    try {
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      refreshAll();
      // GlobalRegionScheduler：Paper 上落在主线程；延迟与周期都以 tick 计（与旧 runTaskTimer 一致）
      this.refreshTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
          scheduled -> refreshAll(), REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS);
    } catch (Throwable throwable) {
      // 巡检只是「更快反映权限变更」，失败不影响登录/退出事件维护的名单
      plugin.getLogger().warning("直通名单权限巡检启动失败（不影响按登录/退出维护）：" + throwable.getMessage());
    }
  }

  /** 注销监听、取消巡检并清空名单。可重复调用。 */
  public void stop() {
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

  /** 重新评估全部在线玩家，并清掉已下线玩家的残留条目。 */
  private void refreshAll() {
    try {
      Set<UUID> online = new HashSet<>();
      for (Player player : Bukkit.getOnlinePlayers()) {
        UUID id = player.getUniqueId();
        online.add(id);
        refresh(id, player.hasPermission(PERMISSION));
      }
      bypassed.retainAll(online);
    } catch (Throwable ignored) {
      // 枚举失败：保留现有名单，下次巡检再修正
    }
  }
}