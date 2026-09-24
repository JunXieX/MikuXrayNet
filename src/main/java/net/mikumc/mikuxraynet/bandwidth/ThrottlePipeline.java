package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bootstrap.DependencyGuard;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.plugin.Plugin;

/**
 * 带宽优化管线：统一装配、注销全部带宽子模块，并提供统计计数。
 *
 * <p>异常安全：每个子模块独立注册，任一环节失败只禁用该子模块并打印中文原因，不影响其它模块，
 * 更不影响反矿透。停用时按注册的逆序注销，并保证「偿还所有被延迟的包、恢复被隐藏的实体与视距」。
 *
 * <p>子模块与默认开关（均可在 bandwidth.yml 中配置）：
 * <ul>
 *   <li>{@link EntityPacketFilter} 零位移实体包取消（默认开启）；</li>
 *   <li>{@link BlockChangeMerger} 方块变更合并（默认开启，时间窗与缓冲上限保守）；</li>
 *   <li>{@link EntityCuller} 实体射线剔除（默认开启，强制可见距离 32 格）；</li>
 *   <li>{@link AfkTracker} AFK 降级 + 低价值包按距离丢弃（默认开启，丢包类型保守）；</li>
 *   <li>{@link LatencyMonitor} 高延迟降视距（默认开启，需持续超阈值）。</li>
 * </ul>
 * 不具备 ProtocolLib 时，零位移取消、变更合并与 AFK 丢包自动停用，其余模块仍可用。
 */
public final class ThrottlePipeline {

  private final Plugin plugin;
  private final BandwidthConfig config;
  private final ThrottleStats stats = new ThrottleStats();

  private EntityPacketFilter entityPacketFilter;
  private BlockChangeMerger blockChangeMerger;
  private EntityCuller entityCuller;
  private AfkTracker afkTracker;
  private LatencyMonitor latencyMonitor;

  private boolean started;

  public ThrottlePipeline(Plugin plugin, BandwidthConfig config) {
    this.plugin = plugin;
    this.config = config;
  }

  /** 装配全部子模块；总开关关闭时不做任何事。 */
  public void start() {
    if (!config.enabled()) {
      plugin.getLogger().info("带宽优化已在配置中关闭（bandwidth.yml: enabled=false）");
      return;
    }

    ProtocolManager protocolManager = null;
    AsynchronousManager asynchronousManager = null;
    if (DependencyGuard.isProtocolLibPresent()) {
      try {
        protocolManager = ProtocolLibrary.getProtocolManager();
        asynchronousManager = protocolManager.getAsynchronousManager();
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "ProtocolLib 初始化失败，依赖封包通道的带宽子模块停用", throwable);
        protocolManager = null;
        asynchronousManager = null;
      }
    } else {
      plugin.getLogger().warning("未检测到 ProtocolLib：零位移取消、方块变更合并、AFK 丢包停用");
    }

    // 供 lambda 捕获的最终引用（上面的探测过程可能重新赋值，故此处固化为 final）
    final ProtocolManager manager = protocolManager;
    final AsynchronousManager asynchronous = asynchronousManager;

    if (config.entityPackets().skipZeroMovement()) {
      entityPacketFilter = register(() -> {
        EntityPacketFilter module = new EntityPacketFilter(plugin, manager,
            config.entityPackets(), stats);
        module.start();
        return module;
      });
    }
    if (config.blockChanges().merge()) {
      if (manager == null || asynchronous == null) {
        plugin.getLogger().warning("方块变更合并需要 ProtocolLib，已停用");
      } else {
        blockChangeMerger = register(() -> {
          BlockChangeMerger module = new BlockChangeMerger(plugin, manager, asynchronous,
              config.blockChanges(), stats);
          module.start();
          return module;
        });
      }
    }
    if (config.entityCulling().raycast()) {
      entityCuller = register(() -> {
        EntityCuller module = new EntityCuller(plugin, config.entityCulling(), stats);
        module.start();
        return module;
      });
    }
    afkTracker = register(() -> {
      AfkTracker module = new AfkTracker(plugin, manager, config.afk(), stats);
      module.start();
      return module;
    });
    latencyMonitor = register(() -> {
      LatencyMonitor module = new LatencyMonitor(plugin, config.latency(), stats);
      module.start();
      return module;
    });

    started = true;
  }

  /** 注销全部子模块；可重复调用，异常安全。 */
  public void stop() {
    if (!started) {
      return;
    }
    started = false;
    close("高延迟降视距", latencyMonitor, LatencyMonitor::stop);
    close("AFK 降级", afkTracker, AfkTracker::stop);
    close("实体射线剔除", entityCuller, EntityCuller::stop);
    close("方块变更合并", blockChangeMerger, BlockChangeMerger::stop);
    close("零位移实体包取消", entityPacketFilter, EntityPacketFilter::stop);
    latencyMonitor = null;
    afkTracker = null;
    entityCuller = null;
    blockChangeMerger = null;
    entityPacketFilter = null;
  }

  /** 统计计数（供后续 /mikuxraynet status 读取）。 */
  public ThrottleStats stats() {
    return stats;
  }

  /** 管线是否已装配。 */
  public boolean started() {
    return started;
  }

  private <T> T register(ModuleFactory<T> factory) {
    try {
      return factory.create();
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "带宽子模块注册失败，已单独停用该模块", throwable);
      return null;
    }
  }

  private <T> void close(String name, T module, ModuleStopper<T> stopper) {
    if (module == null) {
      return;
    }
    try {
      stopper.stop(module);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销带宽子模块「" + name + "」时出现异常", throwable);
    }
  }

  private interface ModuleFactory<T> {
    T create();
  }

  private interface ModuleStopper<T> {
    void stop(T module);
  }
}