package net.mikumc.mikuxraynet.bootstrap;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.antixray.ChunkPacketAccessor;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider;
import net.mikumc.mikuxraynet.antixray.ObfuscatedChunkIndex;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.antixray.ProtocolLibAsyncListener;
import net.mikumc.mikuxraynet.antixray.RevealedSet;
import net.mikumc.mikuxraynet.antixray.RewriteStats;
import net.mikumc.mikuxraynet.cache.DiskCacheStore;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import org.bukkit.plugin.Plugin;

/**
 * ProtocolLib 接入点：反矿透唯一封包通道的注册与注销。
 *
 * <p>注册流程置于 try/finally：任一步骤失败都会回滚已注册的监听器并返回 false，
 * 不会留下「半注册」状态（半注册会导致封包被登记延迟却无人放行 → 客户端卡加载）。
 */
public final class ProtocolLibHook {

  private final Plugin plugin;
  private final Logger logger;

  private ProtocolManager protocolManager;
  private AsynchronousManager asynchronousManager;
  private AsyncListenerHandler asyncListenerHandler;
  private ProtocolLibAsyncListener listener;

  public ProtocolLibHook(Plugin plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  /**
   * 注册异步区块监听器（真异步扣包）。
   *
   * <p>先尝试把 1.20.2+ 的 {@code CHUNK_BATCH_START/FINISHED} 与 {@code MAP_CHUNK} 一起注册；
   * 若当前 ProtocolLib / 服务端不支持这两个包类型而注册失败，则降级为仅拦截 {@code MAP_CHUNK}
   * （区块改写照常工作，只是失去批次闸门）。
   *
   * @param obfuscatedChunkIndex 伪装区块索引的<b>可变引用</b>；{@code get()} 返回 {@code null} 表示不做邻近显形
   * @param revealedSet          已显形集合的<b>可变引用</b>；{@code get()} 返回 {@code null} 表示不做邻近显形
   * @param bypassRegistry 直通名单；{@code null} 表示无直通
   * @param diskCache 磁盘缓存；{@code null} 表示只用内存缓存
   * @param liveConfig 实时配置源（热重载后世界黑名单即时生效）；可为 null（回落启动期配置）
   * @return true 表示注册成功并已启动异步分发
   */
  public boolean register(AntiXrayConfig config, ObfuscationProcessor processor, MikuWorkPool workPool,
      NeighborChunkProvider neighborProvider, Supplier<ObfuscatedChunkIndex> obfuscatedChunkIndex,
      Supplier<RevealedSet> revealedSet, BypassRegistry bypassRegistry, DiskCacheStore diskCache,
      Supplier<AntiXrayConfig> liveConfig) {
    // 启动期解析并缓存区块数据字段（字段直读，不依赖 ProtocolLib 自带的区块数据包装类）。
    // 取数全命中属正常启动（降为 FINE）；降级（方块实体剔除不可用）或不可用（fail-open 放行原包、
    // 矿物以真实状态下发）都是「功能受损」，必须打 WARNING 点名——绝不静默。
    String accessorState = ChunkPacketAccessor.resolveAndDescribe();
    if (ChunkPacketAccessor.accessorState() == ChunkPacketAccessor.AccessorState.READY) {
      logger.fine(accessorState);
    } else {
      logger.warning(accessorState);
    }

    Throwable batchFailure = tryRegister(config, processor, workPool, neighborProvider,
        obfuscatedChunkIndex, revealedSet, bypassRegistry, diskCache, liveConfig, true);
    if (batchFailure == null) {
      return true;
    }

    logger.log(Level.WARNING,
        "区块批量包（CHUNK_BATCH_START/FINISHED）拦截注册失败，降级为仅拦截 MAP_CHUNK", batchFailure);
    Throwable fallbackFailure = tryRegister(config, processor, workPool, neighborProvider,
        obfuscatedChunkIndex, revealedSet, bypassRegistry, diskCache, liveConfig, false);
    if (fallbackFailure == null) {
      return true;
    }

    logger.log(Level.SEVERE, "ProtocolLib 异步拦截注册失败，反矿透模块停用", fallbackFailure);
    return false;
  }

  /** 单次注册尝试；成功返回 {@code null}，失败返回异常并回滚已注册的监听器。 */
  private Throwable tryRegister(AntiXrayConfig config, ObfuscationProcessor processor,
      MikuWorkPool workPool, NeighborChunkProvider neighborProvider,
      Supplier<ObfuscatedChunkIndex> obfuscatedChunkIndex, Supplier<RevealedSet> revealedSet,
      BypassRegistry bypassRegistry, DiskCacheStore diskCache, Supplier<AntiXrayConfig> liveConfig,
      boolean handleChunkBatch) {
    try {
      this.protocolManager = ProtocolLibrary.getProtocolManager();
      this.asynchronousManager = protocolManager.getAsynchronousManager();
      this.listener = new ProtocolLibAsyncListener(plugin, config, liveConfig, processor, workPool,
          asynchronousManager, neighborProvider, handleChunkBatch, obfuscatedChunkIndex, revealedSet,
          bypassRegistry, diskCache);
      this.asyncListenerHandler = asynchronousManager.registerAsyncHandler(listener);
      // 必须显式 start()，否则异步监听器不会真正开始分发封包
      this.asyncListenerHandler.start();

      logger.fine("反矿透封包拦截已启用（ProtocolLib 异步通道，监听 "
          + (handleChunkBatch ? "MAP_CHUNK + CHUNK_BATCH_START/FINISHED" : "MAP_CHUNK") + "）");
      return null;
    } catch (Throwable throwable) {
      unregister();
      return throwable;
    }
  }

  /** ProtocolLib 协议管理器（供邻近显形与方块变更注销复用同一入口）。 */
  public ProtocolManager protocolManager() {
    return protocolManager;
  }

  /**
   * 装配/卸下「区块包放行后」回调（邻近显形侧的区块下发即显形）；{@code hook == null} 表示停用。
   *
   * <p>未接入改写链路（{@code listener == null}）时为空操作：此时没有区块包经过本插件，回调也无从触发。
   */
  public void setChunkDeliveryHook(ProtocolLibAsyncListener.ChunkDeliveryHook hook) {
    ProtocolLibAsyncListener current = listener;
    if (current != null) {
      current.setChunkDeliveryHook(hook);
    }
  }

  /** 注销拦截；可重复调用，异常安全。 */
  public void unregister() {
    ProtocolLibAsyncListener current = listener;
    AsyncListenerHandler handler = asyncListenerHandler;
    AsynchronousManager asynchronous = asynchronousManager;
    listener = null;
    asyncListenerHandler = null;
    asynchronousManager = null;
    // 两步注销各自 try：Bukkit 钩子清理失败不应连带跳过异步监听器注销（否则 handler 永久留在
    // ProtocolLib 里，回调继续跑，热重载逐代累积）。
    if (current != null) {
      try {
        // 先注销监听器自己的 Bukkit 事件监听（玩家退出清理），与构造时的注册配对
        current.unregisterBukkitHooks();
      } catch (Throwable throwable) {
        logger.log(Level.WARNING, "注销 Bukkit 事件钩子时出现异常（继续注销封包监听器）", throwable);
      }
    }
    if (asynchronous != null && handler != null) {
      try {
        asynchronous.unregisterAsyncHandler(handler);
      } catch (Throwable throwable) {
        logger.log(Level.WARNING, "注销 ProtocolLib 异步拦截时出现异常（通常可忽略）", throwable);
      }
    }
  }

  /** 世界卸载时整体失效该世界的改写缓存。 */
  public void invalidateWorld(String worldName) {
    ProtocolLibAsyncListener current = listener;
    if (current != null) {
      current.invalidateWorld(worldName);
    }
  }

  /**
   * 整体失效<b>派生缓存</b>（改写缓存 + 邻块快照；配置热重载时调用）。
   *
   * <p><b>伪装索引 / 已显形集合刻意保留</b>——见 {@link ProtocolLibAsyncListener#invalidateAll}
   * 里「为什么绝不能同时清」的说明：热重载不会让客户端重收已加载区块，清掉会让已下发的假矿无法还原。
   */
  public void invalidateAll() {
    ProtocolLibAsyncListener current = listener;
    if (current != null) {
      current.invalidateAll();
    }
  }

  /** 改写统计计数；未启用时为 {@code null}。 */
  public RewriteStats stats() {
    ProtocolLibAsyncListener current = listener;
    return current == null ? null : current.stats();
  }

  /** 缓存命中数（诊断用）。 */
  public long cacheHits() {
    ProtocolLibAsyncListener current = listener;
    return current == null ? 0L : current.cacheHits();
  }

  /** 缓存未命中数（诊断用）。 */
  public long cacheMisses() {
    ProtocolLibAsyncListener current = listener;
    return current == null ? 0L : current.cacheMisses();
  }

  /** 缓存条目数（诊断用）。 */
  public int cacheSize() {
    ProtocolLibAsyncListener current = listener;
    return current == null ? 0 : current.cacheSize();
  }

  /** 缓存当前占用的近似字节数（诊断用；与 {@code cache.maximum-mb} 换算出的字节上限对照即可看出预算是否吃紧）。 */
  public long cacheBytes() {
    ProtocolLibAsyncListener current = listener;
    return current == null ? 0L : current.cacheBytes();
  }
}