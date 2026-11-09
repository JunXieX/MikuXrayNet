package net.mikumc.mikuxraynet.antixray;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * 玩家当前世界名快照：封包线程只查这张表，<b>绝不读 {@code player.getWorld()}</b>。
 *
 * <p><b>为什么必须这样</b>：Folia（以及区域化线程的 Paper 分支）在非该实体所属区域线程上读实体状态会抛
 * {@code IllegalStateException: No currently ticking region}。三个出站监听器（方块变更注销 / 遮挡补丁 /
 * 泄露过滤）都跑在 netty 线程上，原先直接调 {@code player.getWorld()}，真机上就是这条 WARN：
 * 解析失败 → 按原包放行 → 该坐标不从伪装索引注销 → 显形包把旧状态发回去（表现为那一格闪一下）。
 *
 * <p>写入只发生在玩家事件（区域线程）与启用时的在线玩家快照；读取是无锁并发查表。
 */
public final class PlayerWorldIndex implements Listener {

  private static final ConcurrentHashMap<UUID, String> WORLDS = new ConcurrentHashMap<>();
  private static volatile PlayerWorldIndex instance;
  private static volatile boolean registered;

  private PlayerWorldIndex() {
  }

  /** 注册并快照当前在线玩家（幂等）。 */
  public static synchronized void start(Plugin plugin) {
    if (plugin == null || registered) {
      return;
    }
    try {
      for (Player player : Bukkit.getOnlinePlayers()) {
        remember(player);
      }
      PlayerWorldIndex listener = new PlayerWorldIndex();
      instance = listener;
      Bukkit.getPluginManager().registerEvents(listener, plugin);
      registered = true;
    } catch (Throwable throwable) {
      plugin.getLogger().log(java.util.logging.Level.WARNING,
          "玩家世界快照初始化失败（方块变更注销将按原包放行）", throwable);
    }
  }

  /** 注销并清空（停用/热重载）。 */
  public static synchronized void stop() {
    registered = false;
    WORLDS.clear();
    PlayerWorldIndex listener = instance;
    instance = null;
    if (listener != null) {
      try {
        // 只注销本监听器：HandlerList.unregisterAll(plugin) 会把该插件的其它监听一并摘掉
        HandlerList.unregisterAll(listener);
      } catch (Throwable ignored) {
        // 停用期注销失败不影响正确性
      }
    }
  }

  /** 封包线程读取：未知玩家返回 {@code null}（调用方按「无法判定」放行）。 */
  public static String worldName(UUID playerId) {
    return playerId == null ? null : WORLDS.get(playerId);
  }

  private static void remember(Player player) {
    if (player == null) {
      return;
    }
    try {
      WORLDS.put(player.getUniqueId(), player.getWorld().getName());
    } catch (Throwable ignored) {
      // 读不到世界（实体内退役等）：保留旧值
    }
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    remember(event.getPlayer());
  }

  @EventHandler
  public void onChangedWorld(PlayerChangedWorldEvent event) {
    remember(event.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    WORLDS.remove(event.getPlayer().getUniqueId());
  }
}
