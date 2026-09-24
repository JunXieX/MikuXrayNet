package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bandwidth.Schedulers;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 邻近显形：周期巡检每个在线玩家，把其附近「曾被反矿透伪装过」的坐标的真实方块状态发回客户端。
 *
 * <p><b>为什么需要</b>：被完全掩埋的方块在区块封包里已被替换为伪装方块，客户端会一直看到伪装结果；
 * 只有服务端下发方块变更时才会纠正。主动把玩家附近的真实方块发回去，体验才与原版一致。
 *
 * <p><b>线程纪律</b>：本类只在主线程 / Folia 区域线程执行——位置读取、方块读取与发包都在
 * 玩家所属线程完成（非 Folia 为统一主线程任务，Folia 为各玩家的区域任务）。
 * 纯计算部分（距离筛选）交给 {@link RevealedBlockIndex}，它不触碰任何 Bukkit API。
 *
 * <p><b>发包纪律</b>：ProtocolLib 是唯一封包通道，包用 {@code createPacket} 构造、用
 * {@code sendServerPacket(..., filters=false)} 发出，因此显形包不会再次进入本插件的出站监听器。
 * 同一坐标只显形一次（发送成功后立即从索引中注销）；单个坐标失败只记日志并跳过，绝不中断整体。
 */
public final class ProximityRevealer implements Listener {

  private static final int MAX_ERROR_LOGS = 3;
  /** 每多少次巡检清理一次过期条目（默认周期 5 tick 时约 5 秒一次）。 */
  private static final int EXPIRE_EVERY_PASSES = 20;

  /** 单次巡检的发包额度（非 Folia 为全服合计，Folia 为每个玩家各自的巡检）。 */
  private static final class Budget {

    private int remaining;

    private Budget(int remaining) {
      this.remaining = remaining;
    }
  }

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final AntiXrayConfig config;
  private final AntiXrayConfig.Proximity proximity;
  private final RevealedBlockIndex index;
  private final ProximityStats stats;
  private final BypassRegistry bypassRegistry;
  private final AtomicInteger errorCounter = new AtomicInteger();
  private final AtomicLong passes = new AtomicLong();

  private BukkitTask globalTask;

  public ProximityRevealer(Plugin plugin, ProtocolManager protocolManager, AntiXrayConfig config,
      RevealedBlockIndex index, ProximityStats stats, BypassRegistry bypassRegistry) {
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.proximity = config.proximity();
    this.index = index;
    this.stats = stats;
    this.bypassRegistry = bypassRegistry;
  }

  /** 启动巡检：非 Folia 为统一主线程任务；Folia 为各玩家的区域任务（玩家退役后自动失效）。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    long interval = Math.max(1, proximity.intervalTicks());
    if (PlatformSupport.isFolia()) {
      for (Player player : Bukkit.getOnlinePlayers()) {
        Schedulers.repeatOnEntity(plugin, player, interval, interval, () -> pass(player));
      }
    } else {
      globalTask = Bukkit.getScheduler().runTaskTimer(plugin, this::passAll, interval, interval);
    }
    plugin.getLogger().info("邻近显形已启用：距离 " + proximity.distance() + " 格，周期 " + interval
        + " tick，单次上限 " + proximity.maxRevealsPerTick() + " 个");
  }

  /** 停用：注销任务与事件监听，并清空显形索引（不留副作用）。 */
  public void stop() {
    if (globalTask != null) {
      globalTask.cancel();
      globalTask = null;
    }
    HandlerList.unregisterAll(this);
    index.clear();
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

  /** 玩家登出即清理其索引条目，避免为离线玩家保留坐标。 */
  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    index.clearPlayer(event.getPlayer().getUniqueId());
  }

  /** 显形索引（供诊断读取持有量）。 */
  public RevealedBlockIndex index() {
    return index;
  }

  /** 统计计数（供 {@code /mikuxraynet status} 读取）。 */
  public ProximityStats stats() {
    return stats;
  }

  /** 全服巡检（非 Folia：主线程，遍历在线玩家并共享本次发包额度）。 */
  private void passAll() {
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
    maybeExpire();
    try {
      reveal(player, new Budget(limit()));
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 主线程 / 区域线程：取候选坐标 → 读真实方块 → 发包 → 注销该坐标。 */
  private void reveal(Player player, Budget budget) {
    if (!player.isOnline()) {
      return;
    }
    if (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId())) {
      return;
    }

    World world = player.getWorld();
    String worldName = world.getName();
    if (!config.appliesTo(worldName)) {
      return;
    }

    Location location = player.getLocation();
    List<RevealedBlockIndex.Position> candidates = index.candidates(player.getUniqueId(), worldName,
        location.getBlockX(), location.getBlockY(), location.getBlockZ(), proximity.distance(),
        budget.remaining);
    if (candidates.isEmpty()) {
      return;
    }

    for (RevealedBlockIndex.Position position : candidates) {
      if (budget.remaining <= 0) {
        return;
      }
      if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
        // 区块未加载：本次跳过，坐标留在索引里等玩家真正靠近
        stats.revealsSkipped.increment();
        continue;
      }
      if (send(player, world, position)) {
        index.unregister(player.getUniqueId(), worldName, position.x(), position.y(), position.z());
        stats.revealsSent.increment();
        budget.remaining--;
      } else {
        stats.revealsSkipped.increment();
      }
    }
  }

  /** 构造并发送一条方块变更包；任何异常都只返回 false，不影响原包流程与其它坐标。 */
  private boolean send(Player player, World world, RevealedBlockIndex.Position position) {
    try {
      BlockData data = world.getBlockData(position.x(), position.y(), position.z());
      if (data == null) {
        return false;
      }

      BlockPosition blockPosition = new BlockPosition(position.x(), position.y(), position.z());
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

  /** 周期清理过期条目，避免长期不触发显形的坐标一直留在索引里。 */
  private void maybeExpire() {
    if (passes.incrementAndGet() % EXPIRE_EVERY_PASSES == 0L) {
      try {
        index.expire();
      } catch (Throwable throwable) {
        logThrottled(throwable);
      }
    }
  }

  private int limit() {
    return Math.max(1, proximity.maxRevealsPerTick());
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING,
          "邻近显形失败，已跳过本次处理（不影响反矿透主流程）", throwable);
    }
  }
}