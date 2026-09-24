package net.mikumc.mikuxraynet.bootstrap;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.antixray.ProtocolLibAsyncListener;
import net.mikumc.mikuxraynet.antixray.RevealedBlockIndex;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
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
   * @param revealedIndex 显形索引；{@code null} 表示不做邻近显形
   * @return true 表示注册成功并已启动异步分发
   */
  public boolean register(AntiXrayConfig config, ObfuscationProcessor processor, MikuWorkPool workPool,
      NeighborChunkProvider neighborProvider, RevealedBlockIndex revealedIndex) {
    Throwable batchFailure = tryRegister(config, processor, workPool, neighborProvider, revealedIndex,
        true);
    if (batchFailure == null) {
      return true;
    }

    logger.log(Level.WARNING,
        "区块批量包（CHUNK_BATCH_START/FINISHED）拦截注册失败，降级为仅拦截 MAP_CHUNK", batchFailure);
    Throwable fallbackFailure = tryRegister(config, processor, workPool, neighborProvider,
        revealedIndex, false);
    if (fallbackFailure == null) {
      return true;
    }

    logger.log(Level.SEVERE, "ProtocolLib 异步拦截注册失败，反矿透模块停用", fallbackFailure);
    return false;
  }

  /** 单次注册尝试；成功返回 {@code null}，失败返回异常并回滚已注册的监听器。 */
  private Throwable tryRegister(AntiXrayConfig config, ObfuscationProcessor processor,
      MikuWorkPool workPool, NeighborChunkProvider neighborProvider, RevealedBlockIndex revealedIndex,
      boolean handleChunkBatch) {
    try {
      this.protocolManager = ProtocolLibrary.getProtocolManager();
      this.asynchronousManager = protocolManager.getAsynchronousManager();
      this.listener = new ProtocolLibAsyncListener(plugin, config, processor, workPool,
          asynchronousManager, neighborProvider, handleChunkBatch, revealedIndex);
      this.asyncListenerHandler = asynchronousManager.registerAsyncHandler(listener);
      // 必须显式 start()，否则异步监听器不会真正开始分发封包
      this.asyncListenerHandler.start();

      logger.info("反矿透封包拦截已启用（ProtocolLib 异步通道，监听 "
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

  /** 注销拦截；可重复调用，异常安全。 */
  public void unregister() {
    try {
      if (asynchronousManager != null && asyncListenerHandler != null) {
        asynchronousManager.unregisterAsyncHandler(asyncListenerHandler);
      }
    } catch (Throwable throwable) {
      logger.log(Level.WARNING, "注销 ProtocolLib 异步拦截时出现异常（通常可忽略）", throwable);
    } finally {
      asyncListenerHandler = null;
      asynchronousManager = null;
      listener = null;
    }
  }

  /** 世界卸载时整体失效该世界的改写缓存。 */
  public void invalidateWorld(String worldName) {
    ProtocolLibAsyncListener current = listener;
    if (current != null) {
      current.invalidateWorld(worldName);
    }
  }

  /** 缓存条目数（诊断用）。 */
  public int cacheSize() {
    ProtocolLibAsyncListener current = listener;
    return current == null ? 0 : current.cacheSize();
  }
}