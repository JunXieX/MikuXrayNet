package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.wrappers.BlockPosition;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 隐藏坐标的「二次泄露包」过滤：方块实体更新、世界事件、方块破坏动画、位置音效、粒子、爆炸。
 *
 * <p><b>为什么需要它</b>：区块字节被改写、方块实体从区块包剔除之后，服务端仍会单独下发
 * {@code TILE_ENTITY_DATA}/{@code WORLD_EVENT}/{@code NAMED_SOUND_EFFECT} 等包；它们带着
 * <b>绝对坐标</b>，能直接暴露被伪装成石头/深板岩的箱子、刷怪笼、矿洞音效与爆炸位置。Miku
 * 之前只在 AFK 降级里丢粒子/破坏动画，正常玩家没有任何过滤。
 *
 * <p><b>判定口径</b>：坐标必须同时满足 ① 仍在 {@link ObfuscatedChunkIndex} 的伪装清单里；
 * ② 该玩家尚未收到该坐标的显形包（{@link RevealedSet}）。任一不满足都放行——已显形的真实方块
 * 当然可以带音效/方块实体；解析失败一律 fail-open，绝不误吞正常包。
 *
 * <p>只处理「坐标就是被伪装格」的包；不做按 Y 的整区段过滤，避免把正常矿洞音效/粒子全部吞掉。
 */
public final class LeakFilterListener extends PacketAdapter {

  private final ProtocolManager protocolManager;
  private final ObfuscatedChunkIndex obfuscatedChunkIndex;
  private final RevealedSet revealedSet;
  private final AtomicInteger errorCounter = new AtomicInteger();
  private volatile AsyncListenerHandler handler;

  public LeakFilterListener(Plugin plugin, ProtocolManager protocolManager,
      ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet) {
    super(plugin, ListenerPriority.HIGHEST,
        PacketType.Play.Server.TILE_ENTITY_DATA,
        PacketType.Play.Server.WORLD_EVENT,
        PacketType.Play.Server.BLOCK_BREAK_ANIMATION,
        PacketType.Play.Server.NAMED_SOUND_EFFECT,
        PacketType.Play.Server.CUSTOM_SOUND_EFFECT,
        PacketType.Play.Server.WORLD_PARTICLES,
        PacketType.Play.Server.EXPLOSION);
    this.protocolManager = protocolManager;
    this.obfuscatedChunkIndex = obfuscatedChunkIndex;
    this.revealedSet = revealedSet;
  }

  /** 注册异步监听器（与方块变更观察同一条链路）。 */
  public void start() {
    handler = protocolManager.getAsynchronousManager().registerAsyncHandler(this);
    handler.start();
    getPlugin().getLogger().fine("隐藏坐标二次泄露包过滤已启用（方块实体/世界事件/音效/粒子/爆炸）");
  }

  /** 注销监听器；可重复调用，异常安全。 */
  public void stop() {
    AsyncListenerHandler current = handler;
    handler = null;
    if (current == null) {
      return;
    }
    try {
      protocolManager.getAsynchronousManager().unregisterAsyncHandler(current);
    } catch (Throwable throwable) {
      getPlugin().getLogger().log(Level.WARNING, "注销二次泄露包过滤监听器时出现异常（通常可忽略）", throwable);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    Player player = event.getPlayer();
    if (player == null || event.isCancelled()) {
      return;
    }
    String worldName = worldNameOf(player);
    if (worldName == null) {
      return;
    }
    try {
      if (shouldFilter(player, worldName, event.getPacketType(), event.getPacket())) {
        event.setCancelled(true);
      }
    } catch (Throwable throwable) {
      // fail-open：解析失败只放行本包，绝不误吞正常音效/方块实体
      logThrottled(throwable);
    }
  }

  /**
   * 坐标是否仍在某玩家的「未显形伪装清单」里（包可见，供离线单测直接验证判定口径）。
   */
  static boolean isHidden(ObfuscatedChunkIndex index, RevealedSet revealed, UUID playerId,
      String worldName, int x, int y, int z) {
    if (index == null || revealed == null || playerId == null || worldName == null) {
      return false;
    }
    if (!index.containsPosition(worldName, x, y, z)) {
      return false;
    }
    return !revealed.contains(playerId, worldName, x >> 4, z >> 4, x, y, z);
  }

  /** 按包类型取坐标并判定；字段缺失/形态不符返回 {@code false}（放行）。 */
  private boolean shouldFilter(Player player, String worldName, PacketType type, PacketContainer packet) {
    UUID playerId = player.getUniqueId();
    if (type == PacketType.Play.Server.TILE_ENTITY_DATA
        || type == PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
      BlockPosition position = packet.getBlockPositionModifier().readSafely(0);
      return position != null && isHidden(obfuscatedChunkIndex, revealedSet, playerId, worldName,
          position.getX(), position.getY(), position.getZ());
    }
    if (type == PacketType.Play.Server.WORLD_EVENT) {
      Integer x = packet.getIntegers().readSafely(1);
      Integer y = packet.getIntegers().readSafely(2);
      Integer z = packet.getIntegers().readSafely(3);
      return x != null && y != null && z != null
          && isHidden(obfuscatedChunkIndex, revealedSet, playerId, worldName, x, y, z);
    }
    if (type == PacketType.Play.Server.NAMED_SOUND_EFFECT
        || type == PacketType.Play.Server.CUSTOM_SOUND_EFFECT) {
      Integer x = packet.getIntegers().readSafely(0);
      Integer y = packet.getIntegers().readSafely(1);
      Integer z = packet.getIntegers().readSafely(2);
      return x != null && y != null && z != null
          && isHidden(obfuscatedChunkIndex, revealedSet, playerId, worldName,
              Math.floorDiv(x, 8), Math.floorDiv(y, 8), Math.floorDiv(z, 8));
    }
    if (type == PacketType.Play.Server.WORLD_PARTICLES
        || type == PacketType.Play.Server.EXPLOSION) {
      Double x = packet.getDoubles().readSafely(0);
      Double y = packet.getDoubles().readSafely(1);
      Double z = packet.getDoubles().readSafely(2);
      return x != null && y != null && z != null
          && isHidden(obfuscatedChunkIndex, revealedSet, playerId, worldName,
              (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
    return false;
  }

  /** 世界名（封包线程读取一个不可变字符串，不触碰世界对象内容）。 */
  private static String worldNameOf(Player player) {
    try {
      World world = player.getWorld();
      return world == null ? null : world.getName();
    } catch (Throwable throwable) {
      return null;
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      getPlugin().getLogger().log(Level.WARNING, "二次泄露包解析失败（已放行本包）", throwable);
    }
  }
}