package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.cache.DiskCacheStore;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 出站方块变更监听：服务端自己下发了某个「曾被伪装的坐标」的变更时，把该坐标从伪装区块索引中注销；
 * 同时把「该区块内容已变」这件事告知磁盘缓存（递增区块代次，使旧代次缓存条目自然失效）。
 *
 * <p><b>为什么需要注销</b>：玩家挖掉一个伪装方块时，服务端会下发真实的变更包；若该坐标仍留在
 * 伪装清单里，邻近显形稍后还会用陈旧数据重复发包（多花带宽，观感也可能闪一下）。所以命中即注销。
 * 这里只摘除<b>变更的那一个坐标</b>（与既有行为一致），该区块其它坐标照常显形。
 *
 * <p><b>为什么需要代次</b>：已改写完成的区块负载会按 {@code (世界名, 区块坐标, 配置指纹, 区块代次)}
 * 落到磁盘（见 {@code cache.DiskCacheStore}）。区块内容一旦变化，旧代次的条目就不该再被复用；
 * 用「已拦截的方块变更」驱动代次递增，既零额外拦截成本，又能让旧条目被惰性清理。
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
 * <p><b>线程与内存纪律</b>：回调里只做「读包内坐标 + 查内存索引 + 递增内存代次」，不读 World 的方块；
 * 注销按「世界名 + 坐标」精确定位，因此不需要扫描全部玩家或全部区块。不检查取消状态：即使原包被
 * 别的插件取消，最坏结果也只是少发一个冗余显形包，不会丢失更新。
 */
public final class BlockChangeRevealListener extends PacketAdapter {

  private static final int MAX_ERROR_LOGS = 3;

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final ObfuscatedChunkIndex obfuscatedChunkIndex;
  private final RevealedSet revealedSet;
  private final ProximityStats stats;
  private final DiskCacheStore diskCache;
  private final AtomicInteger errorCounter = new AtomicInteger();

  private AsyncListenerHandler handler;

  /**
   * @param obfuscatedChunkIndex 伪装区块索引；{@code null} 表示不做邻近显形（只做代次递增）
   * @param revealedSet          已显形集合；{@code null} 表示不做邻近显形
   * @param stats                显形统计；{@code null} 表示不统计
   * @param diskCache            磁盘缓存；{@code null} 表示不做代次递增
   */
  public BlockChangeRevealListener(Plugin plugin, ProtocolManager protocolManager,
      ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet, ProximityStats stats,
      DiskCacheStore diskCache) {
    super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.obfuscatedChunkIndex = obfuscatedChunkIndex;
    this.revealedSet = revealedSet;
    this.stats = stats;
    this.diskCache = diskCache;
  }

  /** 注册异步监听器（真异步扣包，与合并模块同一条链路）。 */
  public void start() {
    handler = protocolManager.getAsynchronousManager().registerAsyncHandler(this);
    handler.start();
    plugin.getLogger().info("方块变更观察已启用：命中已伪装坐标即从显形索引移除，并驱动磁盘缓存区块代次");
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
      plugin.getLogger().log(Level.WARNING, "注销方块变更观察监听器时出现异常（通常可忽略）", throwable);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }

    try {
      String worldName = worldNameOf(player);
      PacketType type = event.getPacketType();
      if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        unregisterSingle(event.getPacket(), worldName);
      } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        unregisterMulti(event.getPacket(), worldName);
      }
    } catch (Throwable throwable) {
      // fail-open：解析失败只跳过本次注销，绝不取消或改写原包
      logThrottled(throwable);
    }
  }

  /** 单方块变更：坐标即绝对方块坐标。 */
  private void unregisterSingle(PacketContainer packet, String worldName) {
    BlockPosition position = packet.getBlockPositionModifier().readSafely(0);
    if (position == null) {
      return;
    }
    unregisterCoordinate(worldName, position.getX(), position.getY(), position.getZ());
    markChanged(worldName, position.getX(), position.getZ());
  }

  /** 多方块变更：section 坐标 + 区块内相对坐标（与本插件合并包一致的位置编码）。 */
  private void unregisterMulti(PacketContainer packet, String worldName) {
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
      unregisterCoordinate(worldName, x, y, z);
      markChanged(worldName, x, z);
    }
  }

  /**
   * 命中即从「伪装区块清单」与「各玩家的已显形标记」中摘除该坐标。
   *
   * <p>两者必须同步摘除，才能维持「已显形坐标 ⊆ 区块伪装清单」这一不变式——「整块已全部显形
   * 则跳过该区块」的判断依赖它。只摘变更的那一个坐标，该区块其它坐标照常显形（与既有行为一致）。
   */
  private void unregisterCoordinate(String worldName, int x, int y, int z) {
    if (worldName == null || obfuscatedChunkIndex == null) {
      return;
    }
    if (!obfuscatedChunkIndex.removePosition(worldName, x, y, z)) {
      return;
    }
    if (revealedSet != null) {
      revealedSet.removePosition(worldName, x, y, z);
    }
    if (stats != null) {
      stats.unregistered.increment();
    }
  }

  /** 通知磁盘缓存该区块内容已变（只递增内存代次，不做任何磁盘操作）。 */
  private void markChanged(String worldName, int x, int z) {
    if (diskCache != null && worldName != null) {
      diskCache.markBlockChange(worldName, x >> 4, z >> 4);
    }
  }

  /** 世界名（封包线程读取一个不可变字符串，不触碰任何世界对象内容）。 */
  private static String worldNameOf(Player player) {
    try {
      World world = player.getWorld();
      return world == null ? null : world.getName();
    } catch (Throwable throwable) {
      return null;
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "方块变更注销解析失败，已按原包放行", throwable);
    }
  }
}