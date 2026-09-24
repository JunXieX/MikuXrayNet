package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * AFK 降级：跟踪玩家活动，长时间无操作即视为 AFK，对 AFK 玩家按距离丢弃「低价值」出站包。
 *
 * <p>活动跟踪使用 {@link PlayerMoveEvent}，并以「方块坐标是否变化」过滤高频事件，
 * 保证空闲时几乎零开销；一旦发生有效移动立即退出 AFK。
 *
 * <p>距离判定所需的玩家位置来自状态缓存（在主线程刷新），因此封包线程无需访问实体位置，
 * 符合 Folia「worker 与网络线程不触碰实体」的要求。默认丢弃的包类型保守且数量少：
 * 世界粒子与方块破坏动画。
 */
public final class AfkTracker implements Listener {

  private static final String BYPASS_PERMISSION = "mikuxraynet.bypass";
  private static final int MAX_ERROR_LOGS = 3;

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final BandwidthConfig.Afk config;
  private final ThrottleStats stats;
  private final long timeoutMillis;
  private final double distanceSquared;
  private final ConcurrentHashMap<UUID, AfkState> states = new ConcurrentHashMap<>();
  private final AtomicInteger errorCounter = new AtomicInteger();

  private PacketAdapter packetFilter;

  public AfkTracker(Plugin plugin, ProtocolManager protocolManager, BandwidthConfig.Afk config,
      ThrottleStats stats) {
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.stats = stats;
    this.timeoutMillis = Math.max(1L, config.seconds()) * 1000L;
    this.distanceSquared = config.distance() * config.distance();
  }

  /** 注册事件监听与（可选的）低价值包过滤监听。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      remember(player);
    }

    List<PacketType> types = new ArrayList<>(2);
    if (config.dropParticles()) {
      types.add(PacketType.Play.Server.WORLD_PARTICLES);
    }
    if (config.dropBlockBreakAnimation()) {
      types.add(PacketType.Play.Server.BLOCK_BREAK_ANIMATION);
    }
    if (protocolManager != null && !types.isEmpty()) {
      this.packetFilter = new AfkPacketFilter(this, plugin, types.toArray(new PacketType[0]));
      protocolManager.addPacketListener(packetFilter);
    }
    plugin.getLogger().info("带宽模块已启用：AFK 降级（" + config.seconds() + " 秒，距离 " + config.distance() + " 格）");
  }

  /** 注销监听，清空状态。 */
  public void stop() {
    HandlerList.unregisterAll(this);
    if (packetFilter != null && protocolManager != null) {
      try {
        protocolManager.removePacketListener(packetFilter);
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "注销 AFK 包过滤监听器时出现异常（通常可忽略）", throwable);
      }
      packetFilter = null;
    }
    states.clear();
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    remember(event.getPlayer());
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    states.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    Location to = event.getTo();
    if (to == null) {
      return;
    }
    AfkState state = states.get(event.getPlayer().getUniqueId());
    if (state == null) {
      return;
    }
    int blockX = to.getBlockX();
    int blockY = to.getBlockY();
    int blockZ = to.getBlockZ();
    // 同一方块内的细微移动不视为活动，避免高频事件的额外开销
    if (state.isSameBlock(blockX, blockY, blockZ)) {
      return;
    }
    state.touch(System.currentTimeMillis(), true, blockX, blockY, blockZ, to.getX(), to.getY(), to.getZ());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onTeleport(PlayerTeleportEvent event) {
    Location to = event.getTo();
    AfkState state = states.get(event.getPlayer().getUniqueId());
    if (to == null || state == null) {
      return;
    }
    state.touch(System.currentTimeMillis(), true, to.getBlockX(), to.getBlockY(), to.getBlockZ(),
        to.getX(), to.getY(), to.getZ());
  }

  private void remember(Player player) {
    Location location = player.getLocation();
    states.put(player.getUniqueId(), new AfkState(System.currentTimeMillis(),
        location.getBlockX(), location.getBlockY(), location.getBlockZ(),
        location.getX(), location.getY(), location.getZ()));
  }

  /** 当前是否处于 AFK 状态（供诊断/命令查询）。 */
  public boolean isAfk(UUID playerId) {
    AfkState state = states.get(playerId);
    return state != null && state.isTimedOut(System.currentTimeMillis(), timeoutMillis);
  }

  /** AFK 期间按距离丢弃低价值包；字段读不出或异常一律放行。 */
  void handleLowValuePacket(PacketEvent event) {
    if (event.isCancelled() || event.getPlayer() == null) {
      return;
    }
    Player player = event.getPlayer();
    if (player.hasPermission(BYPASS_PERMISSION)) {
      return;
    }
    AfkState state = states.get(player.getUniqueId());
    if (state == null) {
      return;
    }
    if (state.enterIfTimedOut(System.currentTimeMillis(), timeoutMillis)) {
      stats.afkEntered.increment();
    }
    if (!state.afk()) {
      return;
    }
    try {
      double distanceSquaredFromPlayer = readDistanceSquared(event.getPacketType(), event.getPacket(),
          state.x(), state.y(), state.z());
      if (Double.isNaN(distanceSquaredFromPlayer) || distanceSquaredFromPlayer <= distanceSquared) {
        return;
      }
      event.setCancelled(true);
      stats.afkPacketsDropped.increment();
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 低价值包过滤监听器（静态嵌套，避免在 super(...) 中引用外部实例）。 */
  private static final class AfkPacketFilter extends PacketAdapter {

    private final AfkTracker tracker;

    private AfkPacketFilter(AfkTracker tracker, Plugin plugin, PacketType[] types) {
      super(plugin, ListenerPriority.LOW, types);
      this.tracker = tracker;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
      tracker.handleLowValuePacket(event);
    }
  }

  /** 读取包内位置并计算与玩家的距离平方；无法确定位置时返回 NaN（放行）。 */
  private static double readDistanceSquared(PacketType type, PacketContainer packet,
      double playerX, double playerY, double playerZ) {
    if (type == PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
      BlockPosition position = packet.getBlockPositionModifier().read(0);
      if (position == null) {
        return Double.NaN;
      }
      return squared(position.getX() + 0.5D - playerX)
          + squared(position.getY() + 0.5D - playerY)
          + squared(position.getZ() + 0.5D - playerZ);
    }

    // 世界粒子：位置为 double（旧版本可能为 float），读不到即放行
    StructureModifier<Double> doubles = packet.getSpecificModifier(double.class);
    if (doubles.size() >= 3) {
      return squared(doubles.read(0) - playerX) + squared(doubles.read(1) - playerY)
          + squared(doubles.read(2) - playerZ);
    }
    StructureModifier<Float> floats = packet.getSpecificModifier(float.class);
    if (floats.size() >= 3) {
      return squared(floats.read(0) - playerX) + squared(floats.read(1) - playerY)
          + squared(floats.read(2) - playerZ);
    }
    return Double.NaN;
  }

  private static double squared(double value) {
    return value * value;
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "AFK 距离判定失败，已按原包放行", throwable);
    }
  }
}