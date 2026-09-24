package net.mikumc.mikuxraynet;

import com.comphenix.protocol.ProtocolManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.antixray.BlockChangeRevealListener;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider;
import net.mikumc.mikuxraynet.antixray.ObfuscatedChunkIndex;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.antixray.ProximityRevealer;
import net.mikumc.mikuxraynet.antixray.ProximityStats;
import net.mikumc.mikuxraynet.antixray.RevealedSet;
import net.mikumc.mikuxraynet.bandwidth.ThrottlePipeline;
import net.mikumc.mikuxraynet.bootstrap.DependencyGuard;
import net.mikumc.mikuxraynet.bootstrap.PacketEventsHook;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.bootstrap.ProtocolLibHook;
import net.mikumc.mikuxraynet.cache.DiskCacheStore;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.command.MikuCommand;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.MikuConfig;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Diagnostics;
import net.mikumc.mikuxraynet.util.ReloadCoordinator;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MikuXrayNet 插件主类。
 *
 * <p>本插件在 Paper 26.x 上提供两项能力：
 * <ul>
 *   <li><b>反矿透</b>：出站区块封包改写，把完全被掩埋的矿物替换为伪装方块，并在玩家靠近这些
 *       坐标时主动把真实方块发回客户端（邻近显形，见 {@code antixray.ProximityRevealer}）；</li>
 *   <li><b>带宽优化</b>：零位移包抑制、方块变更合并、实体射线剔除、AFK 降级与高延迟降视距
 *       （由 {@code bandwidth.ThrottlePipeline} 装配，可用 bandwidth.yml 分别开关）。</li>
 * </ul>
 *
 * <p><b>封包通道</b>：ProtocolLib 是唯一的拦截与改写通道（真异步扣包，工作线程在 Netty 管道之外运行）；
 * PacketEvents 只作为「库」在启动期提供方块状态映射，运行期不参与任何拦截。
 *
 * <p>装配细节见 {@code bootstrap} 包；本类只负责生命周期编排、管理命令与热重载的入口，不承载算法。
 */
public final class MikuXrayNet extends JavaPlugin {

  private MikuConfig config;
  private MikuWorkPool workPool;
  private ProtocolLibHook protocolLibHook;
  private ThrottlePipeline throttlePipeline;
  private ProximityRevealer proximityRevealer;
  private BlockChangeRevealListener blockChangeRevealListener;
  /** 按区块共享的伪装坐标索引（内存 = O(有伪装的已加载区块数)）。 */
  private ObfuscatedChunkIndex obfuscatedChunkIndex;
  /** 按玩家的已显形集合（内存 = O(玩家身边确实显形过的坐标数)）。 */
  private RevealedSet revealedSet;
  private ProximityStats proximityStats;
  private DiskCacheStore diskCacheStore;
  private BypassRegistry bypassRegistry;
  private Diagnostics diagnostics;
  /** 周期运行摘要任务（bandwidth.yml: diagnostics.interval-seconds；0 = 关闭）。 */
  private ScheduledTask diagnosticsTask;
  private boolean antiXrayActive;

  private final ReloadCoordinator reloadCoordinator = new ReloadCoordinator();

  @Override
  public void onLoad() {
    this.config = new MikuConfig(this);
    this.config.load();
  }

  @Override
  public void onEnable() {
    if (config == null) {
      onLoad();
    }

    // 平台判定必须最先做：调度已统一走 Paper 调度器（不再分支），但平台判定仍决定
    // 「能否安全枚举全服实体」（零位移包白名单）与「邻近显形的巡检策略」两处能力差异。
    // 依据是服务端品牌/版本标识（antixray.yml: advanced.platform 可手动指定），不再用「类存在性」，
    // 因为 Paper 及其下游分支（如 Leaf）都会自带 io.papermc.paper.threadedregions.* 的调度器 API 类。
    PlatformSupport.configure(config.antiXray().platform());
    getLogger().info("运行平台：" + PlatformSupport.platformDescription());
    startBypassRegistry();
    startAntiXray();
    startBandwidth();
    registerCommand();
    startDiagnosticsTask();
    getLogger().info("MikuXrayNet 已启用");
  }

  @Override
  public void onDisable() {
    stopDiagnosticsTask();
    if (throttlePipeline != null) {
      // 先停带宽管线：它需要偿还延迟中的包、恢复被隐藏的实体与视距
      throttlePipeline.stop();
      throttlePipeline = null;
    }
    if (protocolLibHook != null) {
      // 先停区块改写：之后不会再有坐标被写进显形索引
      protocolLibHook.unregister();
      protocolLibHook = null;
    }
    // 最后停邻近显形：撤销巡检任务、注销变更注销监听并清空索引，不留副作用
    stopProximity();
    // 磁盘缓存最后关闭：先落盘再关句柄（此时已不会再有新的写入与代次变更）
    closeDiskCache();
    if (workPool != null) {
      workPool.close();
      workPool = null;
    }
    if (bypassRegistry != null) {
      bypassRegistry.stop();
      bypassRegistry = null;
    }
    antiXrayActive = false;
    getLogger().info("MikuXrayNet 已停用");
  }

  /** 直通名单装配：登录/退出即时维护，并周期巡检在线玩家权限，及时反映权限变更。 */
  private void startBypassRegistry() {
    BypassRegistry registry = new BypassRegistry(this);
    try {
      registry.start();
    } catch (Throwable throwable) {
      getLogger().log(Level.WARNING, "直通名单初始化失败，反矿透将以「无直通」继续运行", throwable);
    }
    this.bypassRegistry = registry;
  }

  /** 反矿透装配：任一环节不可用都只停用该模块并打印中文原因，不影响其它功能。 */
  private void startAntiXray() {
    AntiXrayConfig antiXray = config.antiXray();
    if (!antiXray.enabled()) {
      getLogger().info("反矿透已在配置中关闭（antixray.yml: enabled=false）");
      return;
    }

    if (!DependencyGuard.isPacketEventsPresent()) {
      getLogger().warning("未检测到 PacketEvents，反矿透模块停用");
      return;
    }

    PacketEventsHook packetEventsHook = new PacketEventsHook(getLogger());
    if (!packetEventsHook.initialize(antiXray.occlusion().extraOccluding(),
        antiXray.occlusion().extraNonOccluding())) {
      return;
    }

    BlockStateRegistry registry = packetEventsHook.registry();
    ChunkCodec codec = new ChunkCodec(registry, versionFlags());
    // 调色板压缩重排由 bandwidth.yml 的 palette 段控制（与反矿透共用同一次编码）。
    // palette.enabled=false（或带宽总开关关闭）时取 DISABLED，即完全不重排、零开销。
    BandwidthConfig.Palette palette = config.bandwidth().palette();
    boolean paletteUsable = config.bandwidth().enabled() && palette.enabled();
    ObfuscationProcessor processor = ObfuscationProcessor.create(codec, registry, antiXray, getLogger(),
        new ObfuscationProcessor.PaletteOptions(paletteUsable && palette.reorder(),
            paletteUsable && palette.strictVerify()));
    if (!processor.isActive()) {
      getLogger().warning("未解析到有效的隐藏方块或伪装方块，反矿透模块停用（请检查 antixray.yml）");
      return;
    }

    if (!DependencyGuard.isProtocolLibPresent()) {
      getLogger().warning("未检测到 ProtocolLib，反矿透模块停用（它是唯一的封包拦截通道）");
      return;
    }

    NeighborChunkProvider neighborProvider = antiXray.neighbors().enabled()
        ? new NeighborChunkProvider(antiXray.neighbors().cacheMaximumSize())
        : null;

    // 邻近显形索引：拆成「按区块共享的伪装清单」+「按玩家的已显形集合」两个结构。
    // 关闭该功能时都传 null，改写路径完全不做任何额外记录。
    AntiXrayConfig.Proximity proximity = antiXray.proximity();
    ObfuscatedChunkIndex chunkIndex = proximity.enabled()
        ? new ObfuscatedChunkIndex(proximity.maxPositions(), proximity.expireSeconds())
        : null;
    RevealedSet revealed = proximity.enabled()
        ? new RevealedSet(proximity.maxPositionsPerPlayer(), proximity.expireSeconds())
        : null;

    // 磁盘缓存：关闭时为 null，改写路径退化为纯内存缓存
    DiskCacheStore diskCache = antiXray.diskCache().enabled()
        ? new DiskCacheStore(new File(getDataFolder(), "cache").toPath(), antiXray.diskCache(),
            getLogger())
        : null;

    MikuWorkPool pool = new MikuWorkPool(antiXray.threads(), antiXray.queueCapacity());
    ProtocolLibHook hook = new ProtocolLibHook(this);
    if (!hook.register(antiXray, processor, pool, neighborProvider, chunkIndex, revealed,
        bypassRegistry, diskCache)) {
      pool.close();
      closeDiskCache(diskCache);
      return;
    }

    this.workPool = pool;
    this.protocolLibHook = hook;
    this.obfuscatedChunkIndex = chunkIndex;
    this.revealedSet = revealed;
    this.diskCacheStore = diskCache;
    this.proximityStats = new ProximityStats();
    this.antiXrayActive = true;
    registerWorldUnloadInvalidation();
    startProximity(antiXray, chunkIndex, revealed, proximityStats);

    getLogger().info("反矿透已启用：目标方块 " + antiXray.hideBlocks().size() + " 种，伪装方块 "
        + antiXray.replacementWeights().size() + " 种；伪装模式 " + antiXray.obfuscationMode()
        + "；区块边界邻块快照 "
        + (neighborProvider != null ? "已启用" : "已关闭") + "；磁盘缓存 "
        + (diskCache != null ? "已启用（" + new File(getDataFolder(), "cache").getPath() + "）" : "已关闭"));
  }

  /**
   * 邻近显形装配：索引为 null（配置关闭）或拿不到 ProtocolLib 协议管理器时只跳过该子模块，
   * 反矿透主体照常工作。
   *
   * <p>方块变更观察监听器在「显形索引或磁盘缓存任一启用」时都会注册：它既负责注销已显形的坐标，
   * 也负责把「区块已变更」告诉磁盘缓存（递增区块代次）。
   */
  private void startProximity(AntiXrayConfig antiXray, ObfuscatedChunkIndex chunkIndex,
      RevealedSet revealed, ProximityStats stats) {
    ProtocolManager protocolManager = protocolLibHook == null ? null : protocolLibHook.protocolManager();
    if (protocolManager == null) {
      getLogger().warning("未取得 ProtocolLib 协议管理器，邻近显形停用");
      return;
    }

    if (chunkIndex == null || revealed == null) {
      getLogger().info("邻近显形已在配置中关闭（antixray.yml: proximity.enabled=false）");
    }

    if (diskCacheStore == null && chunkIndex == null) {
      return;
    }

    this.blockChangeRevealListener = new BlockChangeRevealListener(this, protocolManager, chunkIndex,
        revealed, stats, diskCacheStore);
    try {
      blockChangeRevealListener.start();
      if (chunkIndex != null && revealed != null) {
        this.proximityRevealer = new ProximityRevealer(this, protocolManager, antiXray, chunkIndex,
            revealed, stats, bypassRegistry, workPool);
        proximityRevealer.start();
      }
    } catch (Throwable throwable) {
      getLogger().warning("邻近显形装配失败（不影响反矿透主体）：" + throwable.getMessage());
      stopProximity();
    }
  }

  /** 停用邻近显形：注销巡检任务与变更注销监听，并清空显形索引。 */
  private void stopProximity() {
    if (blockChangeRevealListener != null) {
      blockChangeRevealListener.stop();
      blockChangeRevealListener = null;
    }
    if (proximityRevealer != null) {
      proximityRevealer.stop();
      proximityRevealer = null;
    }
  }

  /** 关闭磁盘缓存（落盘 + 关闭全部句柄）。 */
  private void closeDiskCache() {
    DiskCacheStore store = diskCacheStore;
    diskCacheStore = null;
    closeDiskCache(store);
  }

  private void closeDiskCache(DiskCacheStore store) {
    if (store == null) {
      return;
    }
    try {
      store.close();
    } catch (Throwable throwable) {
      getLogger().log(Level.WARNING, "关闭磁盘缓存时出现异常（已强制结束）", throwable);
    }
  }

  /** 磁盘缓存（供命令与诊断读取持有量与命中率）；未启用时为 null。 */
  public DiskCacheStore diskCacheStore() {
    return diskCacheStore;
  }

  /** 邻近显形（供命令与诊断读取统计计数与索引持有量）；未启用时为 null。 */
  public ProximityRevealer proximityRevealer() {
    return proximityRevealer;
  }

  /** 伪装区块索引（供诊断读取区块数 / 坐标数与安全阀计数）；未启用邻近显形时为 null。 */
  public ObfuscatedChunkIndex obfuscatedChunkIndex() {
    return obfuscatedChunkIndex;
  }

  /** 已显形集合（供诊断读取条目数与安全阀计数）；未启用邻近显形时为 null。 */
  public RevealedSet revealedSet() {
    return revealedSet;
  }

  /** 邻近显形统计计数；反矿透未启用时为 null。 */
  public ProximityStats proximityStats() {
    return proximityStats;
  }

  /** 带宽优化装配：各子模块独立注册，注册失败只停用该子模块，不影响其它功能。 */
  private void startBandwidth() {
    ThrottlePipeline pipeline = new ThrottlePipeline(this, config.bandwidth());
    try {
      pipeline.start();
    } catch (Throwable throwable) {
      getLogger().warning("带宽优化装配失败（不影响反矿透）：" + throwable.getMessage());
    }
    this.throttlePipeline = pipeline;
  }

  /** 带宽管线（供命令与诊断读取统计计数）。 */
  public ThrottlePipeline bandwidthPipeline() {
    return throttlePipeline;
  }

  /**
   * 启动周期运行摘要任务（{@code bandwidth.yml: diagnostics.interval-seconds}）：
   * 每 interval 秒输出一行 INFO 精简摘要（复用 {@link Diagnostics} 既有快照），
   * {@code 0} 表示完全不输出。该键此前是死配置，此方法是其真正消费点。
   */
  private void startDiagnosticsTask() {
    stopDiagnosticsTask();
    int intervalSeconds = Math.max(0, config.bandwidth().diagnostics().intervalSeconds());
    if (intervalSeconds == 0) {
      return;
    }
    if (diagnostics == null) {
      diagnostics = new Diagnostics(this);
    }
    long periodTicks = intervalSeconds * 20L;
    try {
      // GlobalRegionScheduler：Paper 上落在主线程，快照只读计数器与配置，安全
      diagnosticsTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,
          scheduled -> outputDiagnosticsLine(), periodTicks, periodTicks);
    } catch (Throwable throwable) {
      getLogger().warning("周期诊断任务登记失败（不影响其它功能）：" + throwable.getMessage());
    }
  }

  /** 停用周期运行摘要任务（可重复调用）。 */
  private void stopDiagnosticsTask() {
    ScheduledTask task = diagnosticsTask;
    diagnosticsTask = null;
    if (task != null) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已结束，忽略
      }
    }
  }

  private void outputDiagnosticsLine() {
    try {
      Diagnostics current = diagnostics;
      if (current != null) {
        getLogger().info(current.summaryLine());
      }
    } catch (Throwable throwable) {
      // 摘要只是可观测性输出：任何异常都不能影响插件主流程
      getLogger().log(Level.WARNING, "周期诊断摘要输出失败", throwable);
    }
  }

  /** 统一配置入口（供诊断读取有效值）。 */
  public MikuConfig mikuConfig() {
    return config;
  }

  /** 反矿透工作线程池；未启用时为 null。 */
  public MikuWorkPool workPool() {
    return workPool;
  }

  /** ProtocolLib 接入点；未启用时为 null。 */
  public ProtocolLibHook protocolLibHook() {
    return protocolLibHook;
  }

  /** 直通名单。 */
  public BypassRegistry bypassRegistry() {
    return bypassRegistry;
  }

  /** 反矿透主体是否已生效。 */
  public boolean antiXrayActive() {
    return antiXrayActive;
  }

  /**
   * 热重载两份配置：重读配置 → 失效改写缓存/邻块快照/显形索引 → 按新配置重启周期任务。
   *
   * <p>可重复执行、异常安全（fail-open）；返回给管理员看的中文结果行。启动期已固化的部分
   * （隐藏方块表、线程数、缓存容量等）不在本次生效范围内，结果中会显式列出。
   *
   * @return 中文结果行
   */
  public List<String> reloadConfigs() {
    return reloadCoordinator.reload(new ReloadCoordinator.Target() {
      @Override
      public int fingerprint() {
        return config.antiXray().configHash();
      }

      @Override
      public void reloadConfiguration() {
        config.load();
        // 平台判定可能与新配置的 advanced.platform 不同：先重新判定，随后的 restartPeriodicTasks 才会走对分支
        PlatformSupport.configure(config.antiXray().platform());
      }

      @Override
      public void invalidateCaches() {
        ProtocolLibHook hook = protocolLibHook;
        if (hook != null) {
          hook.invalidateAll();
        }
        // 配置指纹变化后旧磁盘条目不会再被命中：先落盘，之后由过期/旧代次清理回收
        DiskCacheStore store = diskCacheStore;
        if (store != null) {
          store.flush();
        }
      }

      @Override
      public void restartPeriodicTasks() {
        restartProximity();
        restartBandwidth();
        // 摘要间隔可随热重载变更（0 = 关闭）
        startDiagnosticsTask();
      }
    });
  }

  /** 按新配置重建邻近显形（复用同一套显形结构，避免与改写链路脱钩）。 */
  private void restartProximity() {
    stopProximity();
    if (antiXrayActive && proximityStats != null
        && (obfuscatedChunkIndex != null || diskCacheStore != null)) {
      startProximity(config.antiXray(), obfuscatedChunkIndex, revealedSet, proximityStats);
    }
  }

  /** 按新配置重建带宽管线（停机时偿还延迟中的包并恢复被隐藏实体/视距，再以新配置启动）。 */
  private void restartBandwidth() {
    if (throttlePipeline != null) {
      throttlePipeline.stop();
      throttlePipeline = null;
    }
    startBandwidth();
  }

  /** 管理命令名（两份插件描述与代码注册三处必须一致，由 PluginDescriptionConsistencyTest 守门）。 */
  private static final String COMMAND_NAME = "mikuxraynet";
  private static final String COMMAND_DESCRIPTION = "MikuXrayNet 管理命令";
  private static final String COMMAND_PERMISSION = "mikuxraynet.admin";
  private static final List<String> COMMAND_ALIASES = List.of("mxnet", "mxr");

  /**
   * 注册管理命令（执行器与补全器）。
   *
   * <p><b>现代路径（Paper / Folia）</b>：通过 Paper 生命周期事件 {@link LifecycleEvents#COMMANDS} 注册
   * Brigadier 命令（Paper 官方推荐做法）。paper-plugin.yml 下 Paper 不会从 YAML 注册任何命令
   * （PaperPluginClassLoader#init 以 Map.of() 填充 PluginDescriptionFile#commands），故命令只能由代码注册。
   *
   * <p><b>回退路径（纯 Bukkit/Spigot 或老核心）</b>：这些核心没有 {@code Plugin#getLifecycleManager()}，
   * 调用会抛 {@link NoSuchMethodError}；此时退回按 plugin.yml 的 commands 段取回 {@link PluginCommand}
   * 并绑定执行器（命令名 / 别名 / 权限 / 补全行为完全一致）。
   */
  private void registerCommand() {
    try {
      MikuCommand executor = new MikuCommand(this);
      if (registerLifecycleCommand(executor)) {
        getLogger().info("管理命令 /" + COMMAND_NAME + " 已按 paper-plugin.yml 现代方式注册");
        return;
      }
      PluginCommand legacyCommand = legacyPluginCommand();
      if (legacyCommand != null) {
        legacyCommand.setExecutor(executor);
        legacyCommand.setTabCompleter(executor);
        getLogger().info("管理命令 /" + COMMAND_NAME + " 已按 plugin.yml 传统方式注册");
        return;
      }
      getLogger().warning("注册管理命令失败（管理命令不可用）");
    } catch (Throwable throwable) {
      getLogger().log(Level.WARNING, "注册管理命令失败（管理命令不可用）", throwable);
    }
  }

  /**
   * 现代路径：在生命周期事件 {@code COMMANDS} 中注册命令（Paper 与 Folia 是同一套 API）。
   *
   * <p>返回 {@code false} 表示当前核心不是 Paper（{@code Plugin#getLifecycleManager()} 不可用），
   * 由调用方退回 plugin.yml 传统路径。注意求值顺序：{@code getLifecycleManager()} 先于 lambda 求值，
   * 因此非 Paper 核心上根本不会加载 {@link LifecycleEvents} / {@link Commands} / {@link BasicCommand} 等类。
   */
  private boolean registerLifecycleCommand(MikuCommand executor) {
    try {
      getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
        Commands commands = event.registrar();
        commands.register(COMMAND_NAME, COMMAND_DESCRIPTION, COMMAND_ALIASES,
            new PaperCommandBridge(executor));
      });
      return true;
    } catch (Throwable throwable) {
      // 纯 Bukkit/Spigot：Plugin#getLifecycleManager 不存在（NoSuchMethodError），改走传统路径
      return false;
    }
  }

  /**
   * 取传统描述（plugin.yml）注册的命令；现代描述（paper-plugin.yml）下无此类命令。
   *
   * <p>Paper 对「paper 插件」在 onEnable 内调用 {@code getCommand} 会直接抛
   * {@link UnsupportedOperationException}，并明确提示 paper 插件不支持 YAML 命令声明，
   * 因此把它当作「无传统命令」处理，转走现代注册路径。
   */
  private PluginCommand legacyPluginCommand() {
    try {
      return getCommand(COMMAND_NAME);
    } catch (UnsupportedOperationException unsupported) {
      return null;
    } catch (Throwable throwable) {
      getLogger().log(Level.WARNING, "查询传统命令时出现异常", throwable);
      return null;
    }
  }

  /**
   * Bukkit 执行器 → Paper Brigadier 命令的适配层。
   *
   * <p>两条路径复用同一份 {@link MikuCommand} 逻辑，避免出现两套命令实现而漂移；
   * 子命令的权限与中文回显都在 MikuCommand 内部判定，这里只透传发送者与参数。
   */
  private static final class PaperCommandBridge implements BasicCommand {

    private final MikuCommand delegate;

    PaperCommandBridge(MikuCommand delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
      delegate.onCommand(source.getSender(), null, COMMAND_NAME, args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
      List<String> suggestions = delegate.onTabComplete(source.getSender(), null, COMMAND_NAME, args);
      return suggestions == null ? List.of() : suggestions;
    }

    @Override
    public String permission() {
      return COMMAND_PERMISSION;
    }
  }

  /** 世界卸载时整体失效该世界的改写缓存与磁盘缓存句柄（落盘 + 关闭，避免把已卸载世界的资源留在堆上）。 */
  private void registerWorldUnloadInvalidation() {
    getServer().getPluginManager().registerEvents(new Listener() {
      @EventHandler
      public void onWorldUnload(WorldUnloadEvent event) {
        ProtocolLibHook hook = protocolLibHook;
        if (hook != null) {
          hook.invalidateWorld(event.getWorld().getName());
        }
        DiskCacheStore store = diskCacheStore;
        if (store != null) {
          store.invalidateWorld(event.getWorld().getName());
        }
      }
    }, this);
  }

  /** 按服务端上报的 Minecraft 版本构造区块二进制格式标志；无法识别时回落到本项目的目标版本。 */
  private static ChunkVersionFlags versionFlags() {
    String minecraftVersion = Bukkit.getMinecraftVersion();
    if (minecraftVersion == null || minecraftVersion.isBlank()) {
      return ChunkVersionFlags.PAPER_26_2;
    }
    return new ChunkVersionFlags(minecraftVersion);
  }
}