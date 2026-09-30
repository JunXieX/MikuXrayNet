package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * AFK 降级：跟踪玩家活动，长时间无操作即视为 AFK，对 AFK 玩家按距离丢弃「低价值」出站包。
 *
 * <p><b>活动来源（低成本，均只做状态标记）</b>：
 * <ul>
 *   <li>跨方块移动（{@link PlayerMoveEvent}，以「方块坐标是否变化」过滤高频事件，空闲时几乎零开销）；
 *   </li>
 *   <li>跨方块传送（{@link PlayerTeleportEvent}）；</li>
 *   <li>角落交互（{@link PlayerInteractEvent}：开箱/开门/使用物品）——原地操作也算活动；</li>
 *   <li>挥手（{@link PlayerAnimationEvent}：攻击/挖掘/空挥）——原地打怪/挖矿也算活动；</li>
 *   <li>聊天（{@link AsyncChatEvent}，异步事件，仅做状态标记）。</li>
 * </ul>
 * 后三类直接解决「原地聊天/开箱/打怪被误判为 AFK，导致粒子/动画被丢」的问题；它们都只更新
 * {@link AfkState} 的存活时间与 AFK 标志（volatile 字段，任意线程安全），不触碰任何实体状态，
 * 因此符合 Folia「worker 与网络线程不触碰实体」的要求。
 *
 * <p>距离判定所需的玩家位置来自状态缓存（在主线程刷新），因此封包线程无需访问实体位置。
 * 默认丢弃的包类型保守且数量少：世界粒子与方块破坏动画。
 */
public final class AfkTracker implements Listener {

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

  /** 交互（开箱 / 开门 / 使用物品）也算活动：原地操作不应被判为 AFK，否则粒子/动画会被丢。 */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    markActive(event.getPlayer());
  }

  /** 挥手（攻击 / 挖掘 / 空挥）也算活动：原地打怪/挖矿不应被判为 AFK。 */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onAnimation(PlayerAnimationEvent event) {
    markActive(event.getPlayer());
  }

  /**
   * 聊天也算活动：原地打字不应被判为 AFK。该事件是<b>异步</b>触发的，这里只做状态标记
   * （volatile 字段写入），不触碰任何实体/世界 API，符合线程纪律。
   */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    markActive(event.getPlayer());
  }

  /**
   * 低成本活动标记：只刷新存活时间并退出 AFK，<b>不改动缓存坐标</b>（玩家并未移动）。
   *
   * <p>这些事件落在玩家所属线程（主线程 / 区域线程），{@link AfkState} 字段为 volatile，写入安全；
   * 只是状态标记，不触碰任何实体状态或 Bukkit 世界 API。
   */
  private void markActive(Player player) {
    AfkState state = states.get(player.getUniqueId());
    if (state != null) {
      state.touch(System.currentTimeMillis(), false, 0, 0, 0, 0.0D, 0.0D, 0.0D);
    }
  }

  private void remember(Player player) {
    Location location = player.getLocation();
    states.put(player.getUniqueId(), new AfkState(System.currentTimeMillis(),
        location.getBlockX(), location.getBlockY(), location.getBlockZ(),
        location.getX(), location.getY(), location.getZ()));
  }

  /** 当前处于 AFK 状态的玩家数（诊断用，非热路径）。 */
  public int afkCount() {
    long now = System.currentTimeMillis();
    int count = 0;
    for (AfkState state : states.values()) {
      if (state.isTimedOut(now, timeoutMillis)) {
        count++;
      }
    }
    return count;
  }

  /** AFK 期间按距离丢弃低价值包；字段读不出或异常一律放行。 */
  void handleLowValuePacket(PacketEvent event) {
    if (event.isCancelled() || event.getPlayer() == null) {
      return;
    }
    Player player = event.getPlayer();
    // 统一走直通名单：只读并发集合，封包线程不触碰 Bukkit 权限 API
    if (BypassRegistry.isBypassedNow(player.getUniqueId())) {
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

    // 世界粒子：位置为 x/y/z 三个 double（偏移/速度/数量都是 float）。刻意要求「恰好 3 个 double」，
    // 而不是「>= 3 就取前三个」——后者在字段布局变化（多出/错位 double 字段）时会误取坐标并误判距离。
    // 数量不符即视为无法确定字段序，直接放行（返回 NaN），绝不误取。
    StructureModifier<Double> doubles = packet.getSpecificModifier(double.class);
    if (doubles.size() == 3) {
      return squared(doubles.read(0) - playerX) + squared(doubles.read(1) - playerY)
          + squared(doubles.read(2) - playerZ);
    }
    // 旧版本可能以 float 承载位置：同样要求恰好 3 个
    StructureModifier<Float> floats = packet.getSpecificModifier(float.class);
    if (floats.size() == 3) {
      return squared(floats.read(0) - playerX) + squared(floats.read(1) - playerY)
          + squared(floats.read(2) - playerZ);
    }
    return Double.NaN;
  }

  private static double squared(double value) {
    return value * value;
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "AFK 距离判定失败，已按原包放行", throwable);
    }
  }
}