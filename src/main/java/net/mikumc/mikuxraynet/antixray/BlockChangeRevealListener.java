package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 出站方块变更监听：服务端自己下发了某个「曾被伪装的坐标」的变更时，把该坐标从显形索引中注销。
 *
 * <p><b>为什么需要</b>：玩家挖掉一个伪装方块时，服务端会下发真实的变更包；若该坐标仍留在索引里，
 * 邻近显形稍后还会用陈旧数据重复发包（多花带宽，观感也可能闪一下）。所以命中即注销。
 *
 * <p><b>为什么走异步通道（而不是同步监听）</b>：
 * <ol>
 *   <li>同步出站监听器会把该包类型登记为「需要主线程」，于是本插件自己用
 *       {@code sendServerPacket(..., filters=false)} 发出的合并包与显形包会被 ProtocolLib 推迟一 tick
 *       并改到主线程发送——合并模块会因此平白多一次主线程写入；异步注册没有这个副作用；</li>
 *   <li>异步链路上的监听器按优先级排定执行顺序，本类优先级最高，正常先于合并模块执行；</li>
 *   <li>即便顺序相反也不会漏：同一条异步链路上每个监听器都会看到同一个变更包，
 *       合并模块的取消只决定「原包最终是否下发」，不影响本类读到坐标。</li>
 * </ol>
 * 本类<b>不</b>登记处理延迟、不取消、不改包，纯观察，因此不会与合并时间窗互相打架。
 *
 * <p><b>线程与内存纪律</b>：回调里只做「读包内坐标 + 查内存索引」，不访问 World / Chunk / Block；
 * 注销按「区块坐标 + 相对坐标」匹配，因此不需要世界名（索引键里本来也只有世界名与整数）。
 * 不检查取消状态：即使原包被别的插件取消，最坏结果也只是少发一个冗余显形包，不会丢失更新。
 */
public final class BlockChangeRevealListener extends PacketAdapter {

  private static final int MAX_ERROR_LOGS = 3;

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final RevealedBlockIndex index;
  private final ProximityStats stats;
  private final AtomicInteger errorCounter = new AtomicInteger();

  private AsyncListenerHandler handler;

  public BlockChangeRevealListener(Plugin plugin, ProtocolManager protocolManager,
      RevealedBlockIndex index, ProximityStats stats) {
    super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.index = index;
    this.stats = stats;
  }

  /** 注册异步监听器（真异步扣包，与合并模块同一条链路）。 */
  public void start() {
    handler = protocolManager.getAsynchronousManager().registerAsyncHandler(this);
    handler.start();
    plugin.getLogger().info("方块变更注销已启用：命中已伪装坐标即从显形索引移除");
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
      plugin.getLogger().log(Level.WARNING, "注销方块变更注销监听器时出现异常（通常可忽略）", throwable);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }

    UUID playerId = player.getUniqueId();
    try {
      PacketType type = event.getPacketType();
      if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        unregisterSingle(playerId, event.getPacket());
      } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        unregisterMulti(playerId, event.getPacket());
      }
    } catch (Throwable throwable) {
      // fail-open：解析失败只跳过本次注销，绝不取消或改写原包
      logThrottled(throwable);
    }
  }

  /** 单方块变更：坐标即绝对方块坐标。 */
  private void unregisterSingle(UUID playerId, PacketContainer packet) {
    BlockPosition position = packet.getBlockPositionModifier().readSafely(0);
    if (position == null) {
      return;
    }
    if (index.unregister(playerId, position.getX(), position.getY(), position.getZ())) {
      stats.unregistered.increment();
    }
  }

  /** 多方块变更：section 坐标 + 区块内相对坐标（与本插件合并包一致的位置编码）。 */
  private void unregisterMulti(UUID playerId, PacketContainer packet) {
    BlockPosition section = packet.getSectionPositions().readSafely(0);
    short[] positions = packet.getShortArrays().readSafely(0);
    if (section == null || positions == null) {
      return;
    }

    int baseX = section.getX() << 4;
    int baseY = section.getY() << 4;
    int baseZ = section.getZ() << 4;
    for (short packed : positions) {
      int x = baseX + (packed >> 8 & 15);
      int z = baseZ + (packed >> 4 & 15);
      int y = baseY + (packed & 15);
      if (index.unregister(playerId, x, y, z)) {
        stats.unregistered.increment();
      }
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "方块变更注销解析失败，已按原包放行", throwable);
    }
  }
}