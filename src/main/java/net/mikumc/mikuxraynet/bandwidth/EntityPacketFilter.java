package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * 零位移实体包抑制：出站的<b>纯位移</b>包（{@code REL_ENTITY_MOVE}）位移全为 0 时取消发送。
 *
 * <p><b>朝向包不做零取消</b>：{@code REL_ENTITY_MOVE_LOOK} / {@code ENTITY_LOOK} 里的 yaw/pitch 是
 * 绝对量化角（0 表示朝正南 / 平视），不是「相对上次的增量」；按全 0 取消会让客户端保留旧朝向，
 * 产生持久朝向错误。故这两个包一律放行（判定依据见 {@link MovementDelta} 类注释）。
 *
 * <p>拦截对象为 ProtocolLib 的 {@code REL_ENTITY_MOVE} / {@code REL_ENTITY_MOVE_LOOK} /
 * {@code ENTITY_LOOK} 三个包（PacketEvents 中对应 ENTITY_RELATIVE_MOVE /
 * ENTITY_RELATIVE_MOVE_AND_ROTATION / ENTITY_ROTATION，ProtocolLib 常量名不同）。
 *
 * <p>字段读取一律走 {@code getSpecificModifier} 并做数量与类型容错：字段形态与预期不符
 * （不同服务端版本的字段布局差异）时直接放行原包，绝不误判取消。
 *
 * <p><b>白名单索引的维护方式</b>：实体 id → 类型 的索引需要 Bukkit 世界访问，因此按平台分别处理：
 * <ul>
 *   <li><b>Paper 系</b>（含 Leaf 等下游分支）：<b>增量维护</b>——由
 *       {@link EntityAddToWorldEvent} / {@link EntityRemoveFromWorldEvent} 在实体加入/离开世界时
 *       即时登记/摘除（不再等下一个全量刷新周期），另保留一个<b>低频兜底全量重建</b>
 *       （{@link #INDEX_REBUILD_TICKS}）补齐万一漏接的事件。启动时先做一次全量建索引，
 *       让插件启用前就已存在的实体立即生效。</li>
 *   <li><b>Folia 系</b>：不做索引（无法跨区域安全枚举全服实体，且该平台是否为所有实体触发上述事件
 *       未经核实），白名单退化为「对所有实体生效」，只影响保守性、不影响正确性——与改动前一致。</li>
 * </ul>
 */
public final class EntityPacketFilter extends PacketAdapter implements Listener {

  /**
   * 兜底全量重建周期（tick）。
   *
   * <p>增量事件已覆盖日常增删，兜底只用于补「万一漏接的事件」，因此刻意取低频（600 tick = 30 秒）。
   * 旧实现对全服实体每 100 tick（5 秒）枚举一次世界，是主线程上的固定开销；改为低频后这笔开销
   * 降到约 1/6，同时白名单准确性反而更高（新实体秒级生效，而非等下一个刷新周期）。
   */
  private static final long INDEX_REBUILD_TICKS = 600L;

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final BandwidthConfig.EntityPackets config;
  private final ThrottleStats stats;
  private final Set<String> whitelist;
  /** 启动期解析出的「白名单命中的实体类型」：刷新索引时先用 {@code entity.getType()} 直接比对，零字符串分配。 */
  private final Set<EntityType> whitelistedTypes;
  /**
   * 白名单里「解析不出对应实体类型」的归一化条目（通常为空）。
   *
   * <p>只有它非空时才需要退回「逐实体取 key 字符串再归一化比较」的旧路径——以此保证：
   * 即使白名单里混入了拼错的/未知的类型名，语义也与旧实现完全一致。
   */
  private final Set<String> unresolvedWhitelist;
  private final AtomicInteger errorCounter = new AtomicInteger();

  /**
   * 实体 id → 归一化类型键。
   *
   * <p><b>为什么用并发容器</b>：增量事件可能在实体所属的区域线程（Folia）或主线程触发，而读取来自
   * 封包线程；{@link ConcurrentHashMap} 保证三者安全，且未命中即 {@code null}（等同旧 volatile 快照）。
   */
  private final Map<Integer, String> entityTypeIndex = new ConcurrentHashMap<>();
  private ScheduledTask indexTask;

  public EntityPacketFilter(Plugin plugin, ProtocolManager protocolManager,
      BandwidthConfig.EntityPackets config, ThrottleStats stats) {
    super(plugin, ListenerPriority.LOW, PacketType.Play.Server.REL_ENTITY_MOVE,
        PacketType.Play.Server.REL_ENTITY_MOVE_LOOK, PacketType.Play.Server.ENTITY_LOOK);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.stats = stats;
    this.whitelist = normalizeAll(config.whitelist());
    // 启动期把白名单解析成实体类型集合：刷新索引时先用 entity.getType() 直接比对，
    // 只有解析不出的条目才回退到字符串比较（见 unresolvedWhitelist）。
    Set<EntityType> types = EnumSet.noneOf(EntityType.class);
    Set<String> unresolved = new HashSet<>(this.whitelist);
    for (EntityType type : EntityType.values()) {
      // EntityType 的 path（不带命名空间、全小写）正是白名单归一化后的形态，取它不产生字符串分配
      String path = type.getKey().getKey();
      if (this.whitelist.contains(path)) {
        types.add(type);
        unresolved.remove(path);
      }
    }
    this.whitelistedTypes = types;
    this.unresolvedWhitelist = unresolved;
  }

  /** 注册监听器；白名单非空时启动实体类型索引的增量维护与低频兜底重建。 */
  public void start() {
    protocolManager.addPacketListener(this);
    if (!whitelist.isEmpty()) {
      if (PlatformSupport.isFolia()) {
        // 保留平台差异：Folia 下无法从单一线程安全枚举全服实体，因此不做类型索引（白名单退化为对所有实体生效）
        plugin.getLogger().warning("Folia 下无法安全枚举实体，零位移包白名单暂不生效（不影响其余逻辑）");
      } else {
        // 增量事件（实体加入/离开世界）由本类作为 Listener 接收；事件在实体所属线程触发，只更新并发索引
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 启动时先全量建一次索引：让「插件启用前就已存在」的实体立即命中白名单
        refreshEntityTypeIndex();
        // GlobalRegionScheduler：Paper 上落在主线程；周期以 tick 计。低频兜底只补漏接的事件。
        indexTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
            scheduled -> refreshEntityTypeIndex(), INDEX_REBUILD_TICKS, INDEX_REBUILD_TICKS);
      }
    }
    plugin.getLogger().info("带宽模块已启用：零位移实体包取消（白名单 " + whitelist.size() + " 项）");
  }

  /** 注销监听器与索引任务。 */
  public void stop() {
    if (indexTask != null) {
      indexTask.cancel();
      indexTask = null;
    }
    try {
      HandlerList.unregisterAll(this);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销零位移实体事件监听时出现异常（通常可忽略）", throwable);
    }
    try {
      protocolManager.removePacketListener(this);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销零位移监听器时出现异常（通常可忽略）", throwable);
    }
    entityTypeIndex.clear();
  }

  /**
   * 实体加入世界：即时登记其类型键（增量维护）。
   *
   * <p>事件在实体所属线程触发（Paper 主线程 / Folia 区域线程），此处只读实体自身的 id 与类型，
   * 不触碰其它实体或世界，因此线程安全；登记进并发索引后封包线程立即可读。
   */
  @EventHandler
  public void onEntityAddToWorld(EntityAddToWorldEvent event) {
    try {
      Entity entity = event.getEntity();
      String typeKey = typeKeyFor(entity.getType());
      if (typeKey != null) {
        entityTypeIndex.put(entity.getEntityId(), typeKey);
      }
    } catch (Throwable throwable) {
      // fail-open：登记失败最多让该实体走「未命中白名单」（保守侧），绝不影响封包主链路
      logThrottled(throwable);
    }
  }

  /** 实体离开世界：摘除其类型索引，避免实体 id 被复用后残留错误映射。 */
  @EventHandler
  public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
    try {
      entityTypeIndex.remove(event.getEntity().getEntityId());
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    if (!config.skipZeroMovement() || event.isCancelled() || event.getPlayer() == null) {
      return;
    }
    // 统一走直通名单：只读并发集合，封包线程不触碰 Bukkit 权限 API
    if (BypassRegistry.isBypassedNow(event.getPlayer().getUniqueId())) {
      return;
    }

    try {
      PacketContainer packet = event.getPacket();
      PacketType type = event.getPacketType();

      StructureModifier<Short> deltas = packet.getSpecificModifier(short.class);
      if (deltas.size() < 3) {
        // ENTITY_LOOK（只有朝向字节、没有 short 位移字段）会走到这里 → 放行；
        // 其它字段形态不符的情况同样 fail-open 放行，绝不误判取消。
        pass();
        return;
      }

      // 只有纯位移包（REL_ENTITY_MOVE）可做零位移取消；朝向包（REL_ENTITY_MOVE_LOOK / ENTITY_LOOK）
      // 里的 yaw/pitch 是绝对量化角（0 表示朝正南 / 平视），把全 0 当作「无转向」取消会让客户端
      // 保留旧朝向（持久朝向错误），因此一律放行（依据见 MovementDelta 类注释）。
      boolean carriesRotation = type != PacketType.Play.Server.REL_ENTITY_MOVE;
      boolean redundant = MovementDelta.isRedundantEntityUpdate(carriesRotation,
          deltas.read(0), deltas.read(1), deltas.read(2));

      if (!redundant || isWhitelisted(packet.getIntegers().read(0))) {
        pass();
        return;
      }

      event.setCancelled(true);
      stats.entityPacketsCancelled.increment();
    } catch (Throwable throwable) {
      // fail-open：字段形态与预期不符，放行原包
      pass();
      logThrottled(throwable);
    }
  }

  private void pass() {
    stats.entityPacketsPassed.increment();
  }

  private boolean isWhitelisted(int entityId) {
    String typeKey = entityTypeIndex.get(entityId);
    return typeKey != null && whitelist.contains(typeKey);
  }

  /**
   * 低频兜底：主线程全量枚举世界、重建实体类型索引（只读世界与实体，不修改任何状态）。
   *
   * <p>采用「对账」而不是「清空重填」：清空会让封包线程短暂看到空索引（把白名单实体误判为非白名单），
   * 且增量事件恰在此时登记的新实体会被清掉。对账只摘除「已不在世界的 id」、覆盖登记「当前仍在世界的 id」，
   * 因此即使上一周期漏接了事件，也能在一个兜底周期内收敛，且不会丢掉同时到达的增量登记。
   */
  private void refreshEntityTypeIndex() {
    try {
      Map<Integer, String> found = new HashMap<>();
      for (World world : Bukkit.getWorlds()) {
        for (Entity entity : world.getEntities()) {
          String typeKey = typeKeyFor(entity.getType());
          if (typeKey != null) {
            found.put(entity.getEntityId(), typeKey);
          }
        }
      }
      entityTypeIndex.keySet().removeIf(entityId -> !found.containsKey(entityId));
      entityTypeIndex.putAll(found);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /**
   * 该实体类型是否命中白名单；命中返回归一化类型键，未命中返回 {@code null}。
   *
   * <p>先用解析出的实体类型直接比对（零字符串分配）：白名单通常只有个位数条目，全服实体动辄上千，
   * 对每个实体都做一次 {@code getKey().toString() + normalize()} 字符串分配是遍历的主要成本。
   * 只有白名单里存在「解析不出类型」的条目时才回退到归一化字符串比较，以保证语义与旧实现一致。
   */
  private String typeKeyFor(EntityType type) {
    if (whitelistedTypes.contains(type)) {
      // 命中项取 EntityType 的 path（引用既有常量，不新建字符串）
      return type.getKey().getKey();
    }
    if (!unresolvedWhitelist.isEmpty()) {
      String typeKey = normalize(type.getKey().toString());
      if (whitelist.contains(typeKey)) {
        return typeKey;
      }
    }
    return null;
  }

  private static Set<String> normalizeAll(Set<String> values) {
    Set<String> normalized = new HashSet<>(Math.max(4, values.size()));
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        normalized.add(normalize(value));
      }
    }
    return normalized;
  }

  /** 统一为 {@code armor_stand} 这类不带命名空间、全小写的键。 */
  private static String normalize(String value) {
    String lower = value.toLowerCase(Locale.ROOT).trim();
    int colon = lower.indexOf(':');
    return colon >= 0 ? lower.substring(colon + 1) : lower;
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "零位移判定失败，已按原包放行", throwable);
    }
  }
}