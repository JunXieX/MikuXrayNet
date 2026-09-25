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
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 出站方块变更监听：服务端自己下发了某个「曾被伪装的坐标」的变更时，把该坐标从伪装区块索引中注销，
 * 并（可选）触发事件驱动即时显形。
 *
 * <p><b>为什么需要注销</b>：玩家挖掉一个伪装方块时，服务端会下发真实的变更包；若该坐标仍留在
 * 伪装清单里，邻近显形稍后还会用陈旧数据重复发包（多花带宽，观感也可能闪一下）。所以命中即注销。
 * 这里只摘除<b>变更的那一个坐标</b>（与既有行为一致），该区块其它坐标照常显形。
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
 * <p><b>线程与内存纪律</b>：回调里只做「读包内坐标 + 查内存索引」，不读 World 的方块；
 * 注销按「世界名 + 坐标」精确定位，因此不需要扫描全部玩家或全部区块。不检查取消状态：即使原包被
 * 别的插件取消，最坏结果也只是少发一个冗余显形包，不会丢失更新。
 *
 * <p><b>自己发的显形包也会被看到（1.1.6 起）</b>：显形发包改用 Paper 原生
 * {@code Player#sendMultiBlockChange} / {@code Player#sendBlockChange}，这些包同样进入本监听器
 * （实测「变更注销」增量与「显形发送」增量逐次相等即为此证据）。两条路径对「注销索引」的处理完全
 * 相同（回显不改变服务端内容，但该坐标确实已被我们发回真实方块，摘掉索引是正确的）；
 * 磁盘缓存的有效性则完全由负载里的原始区块字节指纹判定，与本文观察无关。
 *
 * <p><b>事件驱动即时显形（P1-4，可选）</b>：构造时传入 {@link ProximityRevealer} 后，每次观测到
 * 变更还会调用 {@link ProximityRevealer#onBlockChangeObserved}——变更邻域内仍有伪装坐标时，
 * 由邻近显形当 tick 补发「玩家身边、已伪装且视线可见」的坐标（周期巡检兜底不变）。
 * 未传入（{@code null}）时行为与旧版完全一致。
 */
public final class BlockChangeRevealListener extends PacketAdapter {

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final ObfuscatedChunkIndex obfuscatedChunkIndex;
  private final RevealedSet revealedSet;
  private final ProximityStats stats;
  /**
   * 反矿透配置：用于世界黑名单判定。本监听器随热重载重建（见 {@code AntiXrayRuntime#restartProximity}），
   * 因此持有的引用始终是「当前」配置，黑名单改动即时生效。{@code null} 表示不做黑名单判定。
   */
  private final AntiXrayConfig config;
  /**
   * 事件驱动即时显形（P1-4，可选）：观测到方块变更时交给邻近显形做「当 tick 补发邻域」。
   * {@code null} 表示不启用（只做索引注销，行为与旧版一致）。
   */
  private final ProximityRevealer instantRevealer;
  private final AtomicInteger errorCounter = new AtomicInteger();

  private AsyncListenerHandler handler;

  /**
   * @param obfuscatedChunkIndex 伪装区块索引；{@code null} 表示不做邻近显形（只做索引注销）
   * @param revealedSet          已显形集合；{@code null} 表示不做邻近显形
   * @param stats                显形统计；{@code null} 表示不统计
   */
  public BlockChangeRevealListener(Plugin plugin, ProtocolManager protocolManager,
      ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet, ProximityStats stats) {
    this(plugin, protocolManager, null, obfuscatedChunkIndex, revealedSet, stats, null);
  }

  /**
   * 完整构造（反矿透配置 + 事件驱动即时显形）。
   *
   * @param config          反矿透配置（用于世界黑名单判定）；{@code null} 表示不做黑名单判定
   * @param instantRevealer 邻近显形器；{@code null} 表示不启用事件显形
   */
  public BlockChangeRevealListener(Plugin plugin, ProtocolManager protocolManager,
      AntiXrayConfig config, ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet,
      ProximityStats stats, ProximityRevealer instantRevealer) {
    super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.obfuscatedChunkIndex = obfuscatedChunkIndex;
    this.revealedSet = revealedSet;
    this.stats = stats;
    this.instantRevealer = instantRevealer;
  }

  /** 注册异步监听器（真异步扣包，与合并模块同一条链路）。 */
  public void start() {
    handler = protocolManager.getAsynchronousManager().registerAsyncHandler(this);
    handler.start();
    plugin.getLogger().info("方块变更观察已启用：命中已伪装坐标即从显形索引移除");
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
        unregisterSingle(player, event.getPacket(), worldName);
      } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        unregisterMulti(player, event.getPacket(), worldName);
      }
    } catch (Throwable throwable) {
      // fail-open：解析失败只跳过本次注销，绝不取消或改写原包
      logThrottled(throwable);
    }
  }

  /** 单方块变更：坐标即绝对方块坐标。 */
  private void unregisterSingle(Player player, PacketContainer packet, String worldName) {
    BlockPosition position = packet.getBlockPositionModifier().readSafely(0);
    if (position == null) {
      return;
    }
    observeChange(player, worldName, position.getX(), position.getY(), position.getZ());
  }

  /** 多方块变更：section 坐标 + 区块内相对坐标（与本插件合并包一致的位置编码）。 */
  private void unregisterMulti(Player player, PacketContainer packet, String worldName) {
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
      observeChange(player, worldName, x, y, z);
    }
  }

  /**
   * 统一的变更观察入口：先注销（既有行为），再触发事件显形（P1-4，可选）。
   *
   * <p>两个动作共用同一次坐标解析，热路径上不产生任何额外分配；事件显形的全部筛选
   * （邻域初筛、身边半径、射线、限额、去重）都在 {@link ProximityRevealer#onBlockChangeObserved}
   * 内部完成，未启用时只有一个空引用判断的开销。
   */
  private void observeChange(Player player, String worldName, int x, int y, int z) {
    // 黑名单世界不做任何反矿透工作：不注销显形、不触发事件即时显形。
    if (isBlacklisted(config, worldName)) {
      return;
    }
    // 1.1.6 起显形包走 Paper 原生通道（sendMultiBlockChange / sendBlockChange），因此本监听器
    // 也会看到「我们自己发出的显形回显」。回显不改变服务端内容，因此只摘索引（与真实变更相同），
    // 不再有任何「磁盘缓存失效」动作——磁盘条目的有效性由负载里的原始区块字节指纹判定
    // （见 DiskCacheStore 类注释），回显不改变区块字节，天然不需要作废任何缓存条目。
    unregisterCoordinate(worldName, x, y, z);
    if (instantRevealer != null) {
      instantRevealer.onBlockChangeObserved(player, worldName, x, y, z);
    }
  }

  /**
   * 世界是否在反矿透黑名单内（可离线单测；黑名单世界完全不参与反矿透）。
   * 复用 {@link AntiXrayConfig#isBlacklisted(String)}，不在此处重复实现匹配逻辑。
   */
  static boolean isBlacklisted(AntiXrayConfig config, String worldName) {
    return config != null && config.isBlacklisted(worldName);
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
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "方块变更注销解析失败，已按原包放行", throwable);
    }
  }
}