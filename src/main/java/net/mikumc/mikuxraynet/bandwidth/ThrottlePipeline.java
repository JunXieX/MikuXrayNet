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
 * <p>子模块与默认开关（均可在 bandwidth.yml 中配置；每个模块都有独立总开关 {@code *.enabled}，默认全部开启）：
 * <ul>
 *   <li>{@link EntityPacketFilter} 零位移实体包取消（{@code entity-packets.enabled}）；</li>
 *   <li>{@link BlockChangeMerger} 方块变更合并（{@code block-changes.enabled}，时间窗与缓冲上限保守）；</li>
 *   <li>{@link EntityCuller} 实体射线剔除（{@code entity-culling.enabled}，强制可见距离 32 格）；</li>
 *   <li>{@link AfkTracker} AFK 降级 + 低价值包按距离丢弃（{@code afk.enabled}，丢包类型保守）；</li>
 *   <li>{@link LatencyMonitor} 高延迟降视距（{@code latency.enabled}，需持续超阈值）。</li>
 * </ul>
 * 任一模块的 {@code enabled=false} 都意味着它<b>完全不注册</b>（不建监听器、不起任务、不产生任何开销），
 * 而不是「注册了但不生效」。不具备 ProtocolLib 时：变更合并在 {@link #start()} 里被显式停用，
 * 零位移取消与 AFK 丢包则由 {@link #register} 捕获构造期异常兜底停用，其余模块仍可用。
 */
public final class ThrottlePipeline {

  /**
   * 各带宽子模块的注册决策（纯函数，仅由配置推导，便于离线单测「关闭即不注册」）。
   *
   * @param entityPackets 零位移实体包取消是否会注册
   * @param blockChanges  方块变更合并是否会注册
   * @param entityCulling 实体射线剔除是否会注册
   * @param afk           AFK 降级是否会注册
   * @param latency       高延迟降视距是否会注册
   */
  public record ModulePlan(boolean entityPackets, boolean blockChanges, boolean entityCulling,
      boolean afk, boolean latency) {
  }

  /**
   * 依配置推导各子模块的注册决策：总开关或模块 {@code enabled} 关闭（以及其行为开关关闭）时，
   * 对应模块<b>不会注册</b>，因此不产生任何开销。依赖可用性（ProtocolLib）不在此判定：由
 * {@link #start()} 对变更合并显式停用，零位移取消 / AFK 则靠 {@link #register} 捕获构造异常兜底停用。
   */
  public static ModulePlan plan(BandwidthConfig config) {
    boolean master = config.enabled();
    return new ModulePlan(
        master && config.entityPackets().enabled() && config.entityPackets().skipZeroMovement(),
        master && config.blockChanges().enabled() && config.blockChanges().merge(),
        master && config.entityCulling().enabled() && config.entityCulling().raycast(),
        master && config.afk().enabled(),
        master && config.latency().enabled());
  }

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

    // 依配置推导注册计划：未启用的模块在此完全不注册（不建监听器、不起任务、零开销）
    ModulePlan plan = plan(config);

    if (plan.entityPackets()) {
      entityPacketFilter = register(() -> {
        EntityPacketFilter module = new EntityPacketFilter(plugin, manager,
            config.entityPackets(), stats);
        module.start();
        return module;
      });
    } else {
      logDisabled("零位移实体包取消", config.entityPackets().enabled()
          ? "entity-packets.skip-zero-movement" : "entity-packets.enabled");
    }
    if (plan.blockChanges()) {
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
    } else {
      logDisabled("方块变更合并", config.blockChanges().enabled()
          ? "block-changes.merge" : "block-changes.enabled");
    }
    if (plan.entityCulling()) {
      entityCuller = register(() -> {
        EntityCuller module = new EntityCuller(plugin, config.entityCulling(), stats);
        module.start();
        return module;
      });
    } else {
      logDisabled("实体射线剔除", config.entityCulling().enabled()
          ? "entity-culling.raycast" : "entity-culling.enabled");
    }
    if (plan.afk()) {
      afkTracker = register(() -> {
        AfkTracker module = new AfkTracker(plugin, manager, config.afk(), stats);
        module.start();
        return module;
      });
    } else {
      logDisabled("AFK 降级", "afk.enabled");
    }
    if (plan.latency()) {
      latencyMonitor = register(() -> {
        LatencyMonitor module = new LatencyMonitor(plugin, config.latency(), stats);
        module.start();
        return module;
      });
    } else {
      logDisabled("高延迟降视距", "latency.enabled");
    }

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

  /** 当前处于 AFK 状态的玩家数（诊断用）；未启用 AFK 模块时为 0。 */
  public int afkPlayerCount() {
    AfkTracker tracker = afkTracker;
    return tracker == null ? 0 : tracker.afkCount();
  }

  /** 当前处于隐藏中的实体数（诊断用实时值）；未启用实体剔除模块时为 0。 */
  public int hiddenEntityCount() {
    EntityCuller culler = entityCuller;
    return culler == null ? 0 : culler.hiddenCount();
  }

  private <T> T register(ModuleFactory<T> factory) {
    try {
      return factory.create();
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "带宽子模块注册失败，已单独停用该模块", throwable);
      return null;
    }
  }

  /** 模块被配置关闭时的一次性中文提示（不会打印任何「已启用」行，因为该模块根本没注册）。 */
  private void logDisabled(String name, String reasonKey) {
    plugin.getLogger().info("带宽模块已关闭（配置）：" + name + "（bandwidth.yml: " + reasonKey + "=false）");
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