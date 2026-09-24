package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * 零位移实体包抑制：出站的实体位置/朝向增量全为 0 时取消发送。
 *
 * <p>拦截对象为 ProtocolLib 的 {@code REL_ENTITY_MOVE} / {@code REL_ENTITY_MOVE_LOOK} /
 * {@code ENTITY_LOOK} 三个包（PacketEvents 中对应 ENTITY_RELATIVE_MOVE /
 * ENTITY_RELATIVE_MOVE_AND_ROTATION / ENTITY_ROTATION，ProtocolLib 常量名不同）。
 *
 * <p>字段读取一律走 {@code getSpecificModifier} 并做数量与类型容错：字段形态与预期不符
 * （不同服务端版本的字段布局差异）时直接放行原包，绝不误判取消。
 *
 * <p>白名单按实体类型生效，但实体 id → 类型 的索引需要 Bukkit 世界访问，因此只在主线程周期刷新：
 * <b>Paper 系</b>（含 Leaf 等下游分支）在主线程建索引并周期刷新，白名单完整生效；
 * <b>Folia 系</b>不做索引（无法跨区域安全枚举实体），白名单退化为「对所有实体生效」，
 * 只影响保守性，不影响正确性。
 */
public final class EntityPacketFilter extends PacketAdapter {

  private static final String BYPASS_PERMISSION = "mikuxraynet.bypass";
  private static final int MAX_ERROR_LOGS = 3;
  private static final long INDEX_REFRESH_TICKS = 100L;

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final BandwidthConfig.EntityPackets config;
  private final ThrottleStats stats;
  private final Set<String> whitelist;
  private final AtomicInteger errorCounter = new AtomicInteger();

  /** 实体 id → 归一化类型键（主线程刷新，网络线程只读）。 */
  private volatile Map<Integer, String> entityTypeIndex = Collections.emptyMap();
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
  }

  /** 注册监听器；白名单非空时启动实体类型索引刷新任务。 */
  public void start() {
    protocolManager.addPacketListener(this);
    if (!whitelist.isEmpty()) {
      if (PlatformSupport.isFolia()) {
        // 保留平台差异：Folia 下无法从单一线程安全枚举全服实体，因此不做类型索引（白名单退化为对所有实体生效）
        plugin.getLogger().warning("Folia 下无法安全枚举实体，零位移包白名单暂不生效（不影响其余逻辑）");
      } else {
        refreshEntityTypeIndex();
        // GlobalRegionScheduler：Paper 上落在主线程；延迟与周期都以 tick 计（与旧 runTaskTimer 一致）
        indexTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
            scheduled -> refreshEntityTypeIndex(), INDEX_REFRESH_TICKS, INDEX_REFRESH_TICKS);
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
      protocolManager.removePacketListener(this);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销零位移监听器时出现异常（通常可忽略）", throwable);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    if (!config.skipZeroMovement() || event.isCancelled() || event.getPlayer() == null) {
      return;
    }
    if (event.getPlayer().hasPermission(BYPASS_PERMISSION)) {
      return;
    }

    try {
      PacketContainer packet = event.getPacket();
      PacketType type = event.getPacketType();
      boolean redundant;

      if (type == PacketType.Play.Server.REL_ENTITY_MOVE) {
        StructureModifier<Short> deltas = packet.getSpecificModifier(short.class);
        if (deltas.size() < 3) {
          pass();
          return;
        }
        redundant = MovementDelta.isZeroMove(deltas.read(0), deltas.read(1), deltas.read(2));
      } else if (type == PacketType.Play.Server.REL_ENTITY_MOVE_LOOK) {
        StructureModifier<Short> deltas = packet.getSpecificModifier(short.class);
        StructureModifier<Byte> angles = packet.getSpecificModifier(byte.class);
        if (deltas.size() < 3 || angles.size() < 2) {
          pass();
          return;
        }
        redundant = MovementDelta.isZeroMove(deltas.read(0), deltas.read(1), deltas.read(2))
            && MovementDelta.isZeroRotation(angles.read(0), angles.read(1));
      } else {
        StructureModifier<Byte> angles = packet.getSpecificModifier(byte.class);
        if (angles.size() < 2) {
          pass();
          return;
        }
        redundant = MovementDelta.isZeroRotation(angles.read(0), angles.read(1));
      }

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

  /** 主线程重建实体类型索引（只读世界与实体，不修改任何状态）。 */
  private void refreshEntityTypeIndex() {
    try {
      Map<Integer, String> index = new HashMap<>();
      for (World world : Bukkit.getWorlds()) {
        for (Entity entity : world.getEntities()) {
          index.put(entity.getEntityId(), normalize(entity.getType().getKey().toString()));
        }
      }
      this.entityTypeIndex = index;
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
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
    if (errorCounter.incrementAndGet() <= MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "零位移判定失败，已按原包放行", throwable);
    }
  }
}