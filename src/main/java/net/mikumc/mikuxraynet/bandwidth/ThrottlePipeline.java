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
 *   <li>{@link EntityCuller} 实体剔除（{@code entity-culling.enabled}，强制可见距离 32 格；
 *       含 {@code entity-culling.frustum} 视锥剔除子项）；</li>
 *   <li>{@link EntityMetadataFilter} 实体元数据不变值剔除（{@code entity-metadata.enabled}）；</li>
 *   <li>{@link AfkTracker} AFK 降级 + 低价值包按距离丢弃（{@code afk.enabled}，丢包类型保守）；</li>
 *   <li>{@link LatencyMonitor} 高延迟降视距（{@code latency.enabled}，需持续超阈值）。</li>
 * </ul>
 * 任一模块的 {@code enabled=false} 都意味着它<b>完全不注册</b>（不建监听器、不起任务、不产生任何开销），
 * 而不是「注册了但不生效」。不具备 ProtocolLib 时：变更合并在 {@link #start()} 里被显式停用，
 * 零位移取消与 AFK 丢包则由 {@link #register} 捕获构造/启动期异常兜底停用（启动失败会先对该实例
 * 调用 {@code stop()} 撤销已注册的监听与已调度的任务），其余模块仍可用。
 */
public final class ThrottlePipeline {

  /**
   * 各带宽子模块的注册决策（纯函数，仅由配置推导，便于离线单测「关闭即不注册」）。
   *
   * @param entityPackets  零位移实体包取消是否会注册
   * @param blockChanges   方块变更合并是否会注册
   * @param entityCulling  实体剔除（射线遮挡 / 视锥剔除任一开启）是否会注册
   * @param entityMetadata 实体元数据不变值剔除是否会注册
   * @param afk            AFK 降级是否会注册
   * @param latency        高延迟降视距是否会注册
   */
  public record ModulePlan(boolean entityPackets, boolean blockChanges, boolean entityCulling,
      boolean entityMetadata, boolean afk, boolean latency) {
  }

  /**
   * 依配置推导各子模块的注册决策：总开关或模块 {@code enabled} 关闭（以及其行为开关关闭）时，
   * 对应模块<b>不会注册</b>，因此不产生任何开销。依赖可用性（ProtocolLib）不在此判定：由
   * {@link #start()} 对变更合并显式停用，零位移取消 / AFK 则靠 {@link #register} 捕获构造/启动期异常兜底停用。
   */
  public static ModulePlan plan(BandwidthConfig config) {
    boolean master = config.enabled();
    // 实体剔除：射线遮挡与视锥剔除共用同一套「追踪登记 + 周期复检」骨架，任一开启就必须注册；
    // 两者都关时完全不注册（零开销）。
    boolean entityCulling = config.entityCulling().enabled()
        && (config.entityCulling().raycast()
            || (config.entityCulling().frustum() != null && config.entityCulling().frustum().enabled()));
    return new ModulePlan(
        master && config.entityPackets().enabled() && config.entityPackets().skipZeroMovement(),
        master && config.blockChanges().enabled() && config.blockChanges().merge(),
        master && entityCulling,
        master && config.entityMetadata().enabled(),
        // AFK 模块只看总开关：即使两个 drop-* 行为开关全为 false，它仍会注册以跟踪并统计 AFK 状态
        // （「仅计时不丢包」），因此不能在 plan 里直接判为「未启用」。实际会丢弃哪些包由
        // AfkTracker 的启动日志与 /mxnet status 的「AFK 降级」回显明确写出，避免被误读成「丢包已生效」。
        master && config.afk().enabled(),
        master && config.latency().enabled());
  }

  private final Plugin plugin;
  private final BandwidthConfig config;
  private final ThrottleStats stats;

  private EntityPacketFilter entityPacketFilter;
  private BlockChangeMerger blockChangeMerger;
  private EntityCuller entityCuller;
  private EntityMetadataFilter entityMetadataFilter;
  private AfkTracker afkTracker;
  private LatencyMonitor latencyMonitor;

  private boolean started;

  public ThrottlePipeline(Plugin plugin, BandwidthConfig config) {
    this(plugin, config, new ThrottleStats());
  }

  /**
   * 装配方注入统计持有者的构造。
   *
   * <p><b>为什么要把 {@link ThrottleStats} 与管线解耦</b>：热重载会 {@code stop()} 旧管线、再以新配置
   * 重建一个新管线。若统计仍由管线自己 new，则每次 reload 计数都归零，「累计取消／合并／隐藏」等
   * 计数失去连续性（管理员会误以为功能刚上线或计数丢失）。因此由插件持有唯一的统计实例、传入管线，
   * 管线重建后统计照常累计，{@code /mxnet status} 字段语义不变。
   */
  public ThrottlePipeline(Plugin plugin, BandwidthConfig config, ThrottleStats stats) {
    this.plugin = plugin;
    this.config = config;
    this.stats = stats == null ? new ThrottleStats() : stats;
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
      entityPacketFilter = register(
          () -> new EntityPacketFilter(plugin, manager, config.entityPackets(), stats),
          EntityPacketFilter::start, EntityPacketFilter::stop);
    } else {
      logDisabled("零位移实体包取消", config.entityPackets().enabled()
          ? "entity-packets.skip-zero-movement" : "entity-packets.enabled");
    }
    if (plan.blockChanges()) {
      if (manager == null || asynchronous == null) {
        plugin.getLogger().warning("方块变更合并需要 ProtocolLib，已停用");
      } else {
        blockChangeMerger = register(
            () -> new BlockChangeMerger(plugin, manager, asynchronous, config.blockChanges(),
                stats),
            BlockChangeMerger::start, BlockChangeMerger::stop);
      }
    } else {
      logDisabled("方块变更合并", config.blockChanges().enabled()
          ? "block-changes.merge" : "block-changes.enabled");
    }
    if (plan.entityCulling()) {
      entityCuller = register(
          () -> new EntityCuller(plugin, config.entityCulling(), stats),
          EntityCuller::start, EntityCuller::stop);
    } else {
      logDisabled("实体剔除（射线遮挡 / 视锥剔除）", config.entityCulling().enabled()
          ? "entity-culling.raycast 与 entity-culling.frustum.enabled 均为 false"
          : "entity-culling.enabled");
    }
    if (plan.entityMetadata()) {
      if (manager == null) {
        plugin.getLogger().warning("实体元数据不变值剔除需要 ProtocolLib，已停用");
      } else {
        entityMetadataFilter = register(
            () -> new EntityMetadataFilter(plugin, manager, config.entityMetadata(), stats),
            EntityMetadataFilter::start, EntityMetadataFilter::stop);
      }
    } else {
      logDisabled("实体元数据不变值剔除", "entity-metadata.enabled");
    }
    if (plan.afk()) {
      afkTracker = register(
          () -> new AfkTracker(plugin, manager, config.afk(), stats),
          AfkTracker::start, AfkTracker::stop);
    } else {
      logDisabled("AFK 降级", "afk.enabled");
    }
    if (plan.latency()) {
      latencyMonitor = register(
          () -> new LatencyMonitor(plugin, config.latency(), stats),
          LatencyMonitor::start, LatencyMonitor::stop);
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
    close("实体元数据不变值剔除", entityMetadataFilter, EntityMetadataFilter::stop);
    close("实体剔除（射线遮挡 / 视锥剔除）", entityCuller, EntityCuller::stop);
    close("方块变更合并", blockChangeMerger, BlockChangeMerger::stop);
    close("零位移实体包取消", entityPacketFilter, EntityPacketFilter::stop);
    latencyMonitor = null;
    afkTracker = null;
    entityMetadataFilter = null;
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

  /**
   * 注册单个子模块：{@code factory} 构造 → {@code starter} 启动。
   *
   * <p><b>启动失败必须先清理</b>：各模块的 {@code start()} 都是「先注册监听/调度、后可能抛」
   * （见 {@link EntityCuller} / {@link EntityPacketFilter} / {@link BlockChangeMerger}）。若直接返回
   * {@code null}，字段会保持为 null，之后 {@link #stop()} 便无从注销已注册的监听与已调度的玩家任务——
   * 反复 reload 会持续累积泄漏。因此这里在返回 null 前先对<b>同一实例</b>调用其 {@code stop()}
   * （三处 stop 均已幂等/判空），撤销启动期已产生的副作用；清理或启动失败都<b>不阻塞</b>其它模块注册。
   */
  <T> T register(ModuleFactory<T> factory, ModuleStarter<T> starter, ModuleStopper<T> stopper) {
    T module = null;
    try {
      module = factory.create();
      starter.start(module);
      return module;
    } catch (Throwable throwable) {
      if (module != null) {
        try {
          stopper.stop(module);
        } catch (Throwable cleanupFailure) {
          plugin.getLogger().log(Level.WARNING, "带宽子模块启动失败后的清理出现异常（已忽略）",
              cleanupFailure);
        }
      }
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

  interface ModuleFactory<T> {
    T create();
  }

  interface ModuleStarter<T> {
    void start(T module);
  }

  interface ModuleStopper<T> {
    void stop(T module);
  }
}