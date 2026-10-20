package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bootstrap.DependencyGuard;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.occlusion.OcclusionStats;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
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
   * @param afk            AFK 降级是否会注册
   * @param latency        高延迟降视距是否会注册
   */
  public record ModulePlan(boolean entityPackets, boolean blockChanges, boolean entityCulling,
      boolean afk, boolean latency) {
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
        // AFK 模块只看总开关：即使两个 drop-* 行为开关全为 false，它仍会注册以跟踪并统计 AFK 状态
        // （「仅计时不丢包」），因此不能在 plan 里直接判为「未启用」。实际会丢弃哪些包由
        // AfkTracker 的启动日志与 /mxnet status 的「AFK 降级」回显明确写出，避免被误读成「丢包已生效」。
        master && config.afk().enabled(),
        master && config.latency().enabled());
  }

  private final Plugin plugin;
  private final BandwidthConfig config;
  private final ThrottleStats stats;
  /**
   * 异步遮挡数据仓库注册表（由反矿透侧持有、随反矿透启用而建）；未启用时为 {@code null}。
   *
   * <p>管线只做一次「转交」：实体剔除模块据它创建专用池引擎。两个构造默认给 {@code null}
   * （离线单测与「未接线」场景），因此不会有任何半接线状态。
   */
  private final OcclusionStores occlusionStores;
  /** 异步遮挡计数（插件级唯一，跨热重载复用；未接线时不影响任何行为）。 */
  private final OcclusionStats occlusionStats;

  private volatile EntityPacketFilter entityPacketFilter;
  private volatile BlockChangeMerger blockChangeMerger;
  private volatile EntityCuller entityCuller;
  private volatile AfkTracker afkTracker;
  private volatile LatencyMonitor latencyMonitor;

  /**
   * 是否已启动；用 CAS 而非普通布尔，使 {@link #stop()} 在并发调用下恰好执行一次清理。
   *
   * <p>模块字段同样 volatile：命令线程（reload 的停旧建新）写、全局区域线程（周期诊断）读，
   * 与 {@code MikuXrayNet#throttlePipeline} 是同一类线程关系。
   */
  private final AtomicBoolean started = new AtomicBoolean();

  /**
   * start()/stop() 的串行闸门。
   *
   * <p><b>为什么 CAS 不够</b>：CAS 只保证「同一个动作最多执行一次」，不保证两个动作之间不交错。
   * 热重载路径是「停旧管线 → 建新管线」，而停用/重载可能来自不同线程（命令线程、区域线程、关服线程）：
   * 若 {@code start()} 正在逐个子模块装配时另一线程执行了 {@code stop()}，stop 会把已装配的字段清空并
   * 注销它们，随后 start 继续装配剩余模块并写字段 —— 结果是「一半模块被注销、字段却指向它们」，
   * 既漏清理又可能重复注册监听器。用一把可重入锁把整段装配/拆除串起来即可，且两段都不在封包热路径上。
   */
  private final Object lifecycleLock = new Object();

  public ThrottlePipeline(Plugin plugin, BandwidthConfig config) {
    this(plugin, config, new ThrottleStats());
  }

  /** 无遮挡位图的构造（离线单测 / 反矿透未启用）：实体剔除只做视锥剔除。 */
  public ThrottlePipeline(Plugin plugin, BandwidthConfig config, ThrottleStats stats) {
    this(plugin, config, stats, null, null);
  }

  /**
   * 生产装配构造：追加「遮挡数据仓库 + 计数」。
   *
   * @param occlusionStores 反矿透侧按世界分域的遮挡位图仓库；{@code null} = 位图不可用（仅做视锥剔除）
   * @param occlusionStats  遮挡判定计数（插件级唯一实例，跨热重载复用）
   */
  public ThrottlePipeline(Plugin plugin, BandwidthConfig config, ThrottleStats stats,
      OcclusionStores occlusionStores, OcclusionStats occlusionStats) {
    this.plugin = plugin;
    this.config = config;
    this.stats = stats == null ? new ThrottleStats() : stats;
    this.occlusionStores = occlusionStores;
    this.occlusionStats = occlusionStats == null ? new OcclusionStats() : occlusionStats;
  }

  /**
   * <b>为什么要把 {@link ThrottleStats} 与管线解耦</b>（上面 3 参构造的由来）：热重载会 {@code stop()}
   * 旧管线、再以新配置重建一个新管线。若统计仍由管线自己 new，则每次 reload 计数都归零，「累计取消／
   * 合并／隐藏」等计数失去连续性（管理员会误以为功能刚上线或计数丢失）。因此由插件持有唯一的统计实例、
   * 传入管线，管线重建后统计照常累计，{@code /mxnet status} 字段语义不变。异步遮挡计数同理由插件持有。
   */

  /** 装配全部子模块；总开关关闭时不做任何事。 */
  public void start() {
    synchronized (lifecycleLock) {
      startLocked();
    }
  }

  /** 装配主体（调用方已持有 {@link #lifecycleLock}）。 */
  private void startLocked() {
    if (!config.enabled()) {
      plugin.getLogger().fine("带宽优化已在配置中关闭（bandwidth.yml: enabled=false）");
      return;
    }
    // 先置位再装配：装配中途若抛出非预期异常（例如子模块工厂里的日志/反射失败之外的路径），
    // 调用方的 stop() 仍能进入清理分支，撤销已注册的模块；若把它留在末尾，半途异常会让
    // started 保持 false，stop() 直接返回，已注册的监听与任务永久残留（反复 reload 持续累积）。
    //
    // 用 CAS 而不是 set(true)：重复 start 会再 new 一整套子模块并覆盖字段，旧模块的 PacketAdapter /
    // 异步监听 / 每 tick 任务句柄仍挂在 ProtocolLib 与调度器里，且 stop() 再也拿不到它们——永久泄漏。
    // 当前调用方每次 reload 都新建实例，因此这只是纵深防御；被忽略时明确记一条，避免静默。
    if (!started.compareAndSet(false, true)) {
      plugin.getLogger().fine("带宽优化管线已处于运行状态，忽略重复的 start()（已启动的模块不会被重复注册）");
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
      // 与方块变更合并同一判空：EntityPacketFilter.start() 会先做一次全服实体索引构建，
      // 再在 addPacketListener 上因 manager==null 抛 NPE——先扫描后失败既白烧一次尖峰，
      // 又让日志出现「注册失败 + NPE」这种掩盖真实原因（缺 ProtocolLib）的误导信息。
      if (manager == null) {
        plugin.getLogger().warning("零位移实体包取消需要 ProtocolLib，已停用");
      } else {
        entityPacketFilter = register(
            () -> new EntityPacketFilter(plugin, manager, config.entityPackets(), stats),
            EntityPacketFilter::start, EntityPacketFilter::stop);
      }
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
      // 「遮挡位图不可用 → 跳过射线遮挡判定」的提示不在这里打：EntityCuller.start() 会依据
      // raycastReady() 给出更准确的一行 WARNING（它同时覆盖「反矿透未启用」与「引擎启动失败」两种成因）。
      entityCuller = register(
          () -> new EntityCuller(plugin, config.entityCulling(), stats, occlusionStores, occlusionStats),
          EntityCuller::start, EntityCuller::stop);
    } else {
      logDisabled("实体剔除（射线遮挡 / 视锥剔除）", config.entityCulling().enabled()
          ? "entity-culling.raycast 与 entity-culling.frustum.enabled 均为 false"
          : "entity-culling.enabled");
    }
    if (plan.afk()) {
      // 同上：AfkTracker 也依赖 ProtocolLib 的封包监听，缺前置时直接跳过而不是等 start() 抛 NPE
      if (manager == null) {
        plugin.getLogger().warning("AFK 降级需要 ProtocolLib，已停用");
      } else {
        afkTracker = register(
            () -> new AfkTracker(plugin, manager, config.afk(), stats),
            AfkTracker::start, AfkTracker::stop);
      }
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
  }

  /** 注销全部子模块；可重复调用，异常安全（并发调用时恰好一个线程执行清理）。 */
  public void stop() {
    synchronized (lifecycleLock) {
      stopLocked();
    }
  }

  /** 拆除主体（调用方已持有 {@link #lifecycleLock}）。 */
  private void stopLocked() {
    if (!started.compareAndSet(true, false)) {
      return;
    }
    close("高延迟降视距", latencyMonitor, LatencyMonitor::stop);
    close("AFK 降级", afkTracker, AfkTracker::stop);
    close("实体剔除（射线遮挡 / 视锥剔除）", entityCuller, EntityCuller::stop);
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

  /**
   * 异步遮挡是否已真正接线（引擎已起 + 位图仓库可用）；未接线时为 {@code false}。
   *
   * <p>与「配置里开了」不是一回事：反矿透未启用、或引擎启动失败时本值为 false，诊断据此如实回显。
   */
  public boolean asyncOcclusionActive() {
    EntityCuller culler = entityCuller;
    return culler != null && culler.asyncOcclusionActive();
  }

  /** 异步遮挡专用池当前排队的判定数（诊断用）；未接线时为 0。 */
  public int occlusionQueueDepth() {
    EntityCuller culler = entityCuller;
    return culler == null ? 0 : culler.occlusionQueueDepth();
  }

  /** 当前驻留的遮挡 section 条目总数（诊断用；跨世界求和，未接线时为 0）。 */
  public int occlusionSectionsResident() {
    OcclusionStores stores = occlusionStores;
    return stores == null ? 0 : stores.residentSections();
  }

  /**
   * 异步遮挡计数容器（供诊断读取；未接线时也返回实例，其计数保持为 0）。
   *
   * <p>与 {@link #stats()} 一样是插件级唯一实例，跨热重载复用。
   */
  public OcclusionStats occlusionStats() {
    return occlusionStats;
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

  /** 模块被配置关闭时的一次性中文提示（正常启动的常规信息，降为 FINE；不会打印任何「已启用」行）。 */
  private void logDisabled(String name, String reasonKey) {
    plugin.getLogger().fine("带宽模块已关闭（配置）：" + name + "（bandwidth.yml: " + reasonKey + "=false）");
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