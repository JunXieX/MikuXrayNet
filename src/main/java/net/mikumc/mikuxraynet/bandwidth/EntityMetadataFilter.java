package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * 实体元数据「不变值剔除」：{@code ENTITY_METADATA} 包中「与上次已下发给该玩家的值完全相同」的条目不再重发；
 * 整包全部冗余时直接取消该包。
 *
 * <p>很多插件（记分板血条、自定义名牌、宠物/坐骑插件等）每 tick 重设实体元数据，而客户端其实早已持有
 * 同样的值，这些重复条目纯属浪费带宽。判定逻辑在纯函数 {@link MetadataDelta} 里，本类只负责读写封包字段
 * 与维护按玩家分组的缓存。
 *
 * <p><b>线程模型</b>：全部判定都在封包线程上完成，只读写封包字段与自有的并发缓存，<b>不触碰任何 Bukkit
 * 实体 / 世界 API</b>，因此 Folia 下同样安全；只有「玩家退出清账本」这一个纯账本操作走 Bukkit 事件。
 *
 * <p><b>正确性关键：客户端实体状态何时被重置</b>。玩家停止追踪某实体后重新追踪、或实体 id 被复用，客户端
 * 都会以<b>默认元数据</b>重新创建实体；此时若我们仍认为「这些值上次已下发过」而剔除它们，客户端就会一直
 * 缺这些数据（名牌/血量等不更新）。因此本模块额外监听全部 {@code SPAWN_*} 包：某实体对某玩家一旦重新生成，
 * 立刻作废该 (玩家, 实体) 的缓存，使随后的首个元数据包照常下发。这样既不依赖 Entity 对象（Folia 安全），
 * 也不依赖「追踪事件是否覆盖所有平台」。
 *
 * <p><b>内存有界</b>：每名玩家最多缓存 {@code entity-metadata.max-tracked-per-player} 个实体，超限即整体
 * 清空并计入安全阀计数（只短期少省一点包，不影响正确性）；玩家退出即释放。
 *
 * <p><b>失败语义 fail-open</b>：字段形态与预期不符（不同服务端版本的包布局差异）或任何异常，一律放行原包，
 * 绝不误删客户端需要的数据；且<b>一次失败即永久停用本过滤</b>（见 {@link #onPacketSending}）——包形态与
 * 预期不符通常是结构性的，继续运行只会在封包线程上反复抛异常、刷日志。
 */
public final class EntityMetadataFilter extends PacketAdapter implements Listener {

  /**
   * 希望拦截的包：元数据本身 + 全部实体生成包（生成意味着客户端实体状态被重置）。
   *
   * <p>其中若干生成包是旧版专属的：现代 MC 已把所有实体生成统一到 {@code SPAWN_ENTITY}，这些类型在本
   * 服务端并未注册。对未注册的类型注册监听会被 ProtocolLib 打 WARN（并在其内部「报告过滤」里抛
   * IllegalArgumentException），因此构造期用 {@link PacketType#isSupported()} 只保留本服务端实际存在的
   * 类型（见 {@link #supportedPacketTypes()}）。本项目只兼容最新版本，不做旧版兼容。
   */
  private static final PacketType[] CANDIDATE_TYPES = {
      PacketType.Play.Server.ENTITY_METADATA,
      PacketType.Play.Server.SPAWN_ENTITY,
      PacketType.Play.Server.SPAWN_ENTITY_LIVING,
      PacketType.Play.Server.SPAWN_ENTITY_PAINTING,
      PacketType.Play.Server.SPAWN_ENTITY_EXPERIENCE_ORB,
      PacketType.Play.Server.SPAWN_ENTITY_WEATHER,
      PacketType.Play.Server.NAMED_ENTITY_SPAWN};

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final BandwidthConfig.EntityMetadata config;
  private final ThrottleStats stats;
  /** 本服务端实际注册的包类型（构造期过滤，见 {@link #supportedPacketTypes()}）。 */
  private final PacketType[] packetTypes;
  private final AtomicInteger errorCounter = new AtomicInteger();
  /**
   * 本服务端 ProtocolLib 无法解析实体元数据时置位：此后永久跳过处理（fail-open，只记一次日志）。
   *
   * <p>判据见 {@link #onPacketSending}：一旦读取元数据条目抛异常，即说明该服务端的包形态与 ProtocolLib
   * 的预期不符，继续运行只会在封包线程上反复抛异常、刷日志。重载 / 重启会重建本模块，从而重新评估。
   */
  private volatile boolean disabled;

  /**
   * 玩家 → (实体 id → (元数据索引 → 上次已下发的值))。
   *
   * <p>两级都用并发容器：写侧有封包线程与退出事件（主线程）两条，读侧为封包线程。
   */
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, Map<Integer, Object>>> players =
      new ConcurrentHashMap<>();

  public EntityMetadataFilter(Plugin plugin, ProtocolManager protocolManager,
      BandwidthConfig.EntityMetadata config, ThrottleStats stats) {
    this(plugin, protocolManager, config, stats, supportedPacketTypes());
  }

  private EntityMetadataFilter(Plugin plugin, ProtocolManager protocolManager,
      BandwidthConfig.EntityMetadata config, ThrottleStats stats, PacketType[] packetTypes) {
    super(plugin, ListenerPriority.NORMAL, packetTypes);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.stats = stats;
    this.packetTypes = packetTypes;
  }

  /**
   * 只保留本服务端实际注册的包类型。
   *
   * <p>{@link PacketType#isSupported()}（内部为 {@code PacketRegistry.isSupported}）判定的正是「该类型在
   * 当前服务端是否有对应封包类」——对未注册类型注册监听即触发 ProtocolLib 的 unknown packet WARN 与其
   * 报告过滤异常。按当前版本动态筛选，而不是硬编码，可保证换版本后依然干净。
   */
  private static PacketType[] supportedPacketTypes() {
    List<PacketType> supported = new ArrayList<>(CANDIDATE_TYPES.length);
    for (PacketType type : CANDIDATE_TYPES) {
      if (type.isSupported()) {
        supported.add(type);
      }
    }
    return supported.toArray(new PacketType[0]);
  }

  /** 注册封包监听与退出清理。 */
  public void start() {
    if (packetTypes.length == 0) {
      plugin.getLogger().info("带宽模块已跳过：实体元数据不变值剔除（本服务端未注册所需的包类型）");
      return;
    }
    protocolManager.addPacketListener(this);
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    plugin.getLogger().info("带宽模块已启用：实体元数据不变值剔除（每玩家最多缓存 "
        + config.maxTrackedPerPlayer() + " 个实体）");
  }

  /** 注销监听并清空缓存。 */
  public void stop() {
    try {
      HandlerList.unregisterAll(this);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销实体元数据退出清理监听时出现异常（通常可忽略）", throwable);
    }
    try {
      protocolManager.removePacketListener(this);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销实体元数据监听器时出现异常（通常可忽略）", throwable);
    }
    players.clear();
  }

  /** 玩家退出：释放其缓存（否则离线玩家的条目会一直留在内存里）。 */
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    players.remove(event.getPlayer().getUniqueId());
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    if (disabled || event.isCancelled() || event.getPlayer() == null) {
      return;
    }
    // 统一走直通名单：只读并发集合，封包线程不触碰 Bukkit 权限 API
    if (BypassRegistry.isBypassedNow(event.getPlayer().getUniqueId())) {
      return;
    }
    try {
      if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
        handleMetadata(event);
      } else {
        // 实体生成：客户端会以默认元数据重建该实体，必须作废缓存（否则会漏发它的首个元数据）
        invalidate(event);
      }
    } catch (Throwable throwable) {
      // fail-open：字段形态与预期不符时放行原包，绝不误删；但一次失败即永久停用本过滤。
      // 若本服务端的 ProtocolLib 读不出实体元数据形态（实测其数据条目类解析停留在旧名
      // DataWatcher$Item / SynchedEntityData$DataItem，识别不了本服务端的 SynchedEntityData$DataValue），
      // 则每个元数据包都会在此抛异常——继续运行只会在封包线程上反复抛异常、刷日志，毫无收益。
      disabled = true;
      if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
        // 只打一行（不含堆栈）：这是已识别的、可预期的服务端/ProtocolLib 版本不匹配，不是需要排查的崩溃
        plugin.getLogger().warning(
            "实体元数据不变值剔除已停用：本服务端的 ProtocolLib 无法解析实体元数据（" + throwable
                + "）——fail-open，原包照常下发；如需该功能，请更新为与服务端版本匹配的 ProtocolLib");
      }
    }
  }

  /** 元数据包：剔除「与上次完全相同」的条目；整包冗余则取消该包。 */
  private void handleMetadata(PacketEvent event) {
    PacketContainer packet = event.getPacket();
    StructureModifier<Integer> integers = packet.getIntegers();
    if (integers.size() < 1) {
      return;
    }
    StructureModifier<List<WrappedWatchableObject>> watchable =
        packet.getWatchableCollectionModifier();
    if (watchable.size() < 1) {
      return;
    }
    List<WrappedWatchableObject> entries = watchable.read(0);
    if (entries == null || entries.isEmpty()) {
      return;
    }

    int entityId = integers.read(0);
    Map<Integer, Object> cache = cacheFor(event.getPlayer().getUniqueId(), entityId);
    int size = entries.size();
    int[] indices = new int[size];
    Object[] values = new Object[size];
    for (int i = 0; i < size; i++) {
      WrappedWatchableObject entry = entries.get(i);
      indices[i] = entry.getIndex();
      values[i] = entry.getValue();
    }

    int[] changed = MetadataDelta.changedEntries(indices, values, cache);
    if (changed.length == size) {
      // 全部条目都携带新信息（或无法比较）：原包照常下发
      return;
    }
    if (changed.length == 0) {
      // 整包冗余：直接不发
      event.setCancelled(true);
      stats.entityMetadataCancelled.increment();
      return;
    }
    // 部分冗余：只保留真正变化的条目重发（客户端按索引合并，删掉未变化的条目不影响它持有的状态）
    List<WrappedWatchableObject> kept = new ArrayList<>(changed.length);
    for (int index : changed) {
      kept.add(entries.get(index));
    }
    watchable.write(0, kept);
    stats.entityMetadataEntriesDropped.add(size - changed.length);
  }

  /** 实体生成包：作废该实体对应该玩家的元数据缓存（读不出实体 id 时忽略——最多少作废一次，不影响正确性）。 */
  private void invalidate(PacketEvent event) {
    StructureModifier<Integer> integers = event.getPacket().getIntegers();
    if (integers.size() < 1) {
      return;
    }
    ConcurrentHashMap<Integer, Map<Integer, Object>> entities =
        players.get(event.getPlayer().getUniqueId());
    if (entities != null) {
      entities.remove(integers.read(0));
    }
  }

  /** 取（必要时新建）某玩家某实体的元数据缓存；超过配置上限时整体清空并计入安全阀。 */
  private Map<Integer, Object> cacheFor(UUID playerId, int entityId) {
    ConcurrentHashMap<Integer, Map<Integer, Object>> entities =
        players.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
    if (entities.size() > config.maxTrackedPerPlayer()) {
      entities.clear();
      stats.entityMetadataEvicted.increment();
    }
    return entities.computeIfAbsent(entityId, id -> new ConcurrentHashMap<>());
  }
}
