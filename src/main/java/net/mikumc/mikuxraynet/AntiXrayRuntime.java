package net.mikumc.mikuxraynet;

import com.comphenix.protocol.ProtocolManager;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.antixray.BlockChangeRevealListener;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider;
import net.mikumc.mikuxraynet.antixray.ObfuscatedChunkIndex;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.antixray.ProximityRevealer;
import net.mikumc.mikuxraynet.antixray.ProximityStats;
import net.mikumc.mikuxraynet.antixray.RevealedSet;
import net.mikumc.mikuxraynet.bootstrap.DependencyGuard;
import net.mikumc.mikuxraynet.bootstrap.PacketEventsHook;
import net.mikumc.mikuxraynet.bootstrap.ProtocolLibHook;
import net.mikumc.mikuxraynet.cache.DiskCacheStore;
import net.mikumc.mikuxraynet.cache.ZstdSupport;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.MikuConfig;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import org.bukkit.Bukkit;

/**
 * 反矿透运行时：持有反矿透侧的全部组件（ProtocolLib 接入点、工作线程池、伪装索引、已显形集合、
 * 邻近显形、磁盘缓存、直通名单）并负责它们的装配、停机与热重载重建。
 *
 * <p><b>为什么从主类拆出</b>：主类原先同时编排反矿透、带宽、命令与诊断，装配代码占了大半；
 * 拆出后主类只留生命周期、reload 编排、world-unload 监听与带宽装配，本类聚焦反矿透域。
 * 日志统一走主类传入的 {@code plugin.getLogger()}，前缀 {@code [MikuXrayNet]} 不变。
 *
 * <p><b>停机顺序（与拆分前的 onDisable 完全一致，不得调整）</b>：
 * 停区块改写（注销拦截）→ 停邻近显形 → 关磁盘缓存 → 关工作池 → 停直通名单巡检。
 */
public final class AntiXrayRuntime {

  private final MikuXrayNet plugin;
  private final Logger logger;

  private ProtocolLibHook protocolLibHook;
  private MikuWorkPool workPool;
  private ProximityRevealer proximityRevealer;
  private BlockChangeRevealListener blockChangeRevealListener;
  /** 按区块共享的伪装坐标索引（内存 = O(有伪装的已加载区块数)）。 */
  private ObfuscatedChunkIndex obfuscatedChunkIndex;
  /** 按玩家的已显形集合（内存 = O(玩家身边确实显形过的坐标数)）。 */
  private RevealedSet revealedSet;
  private ProximityStats proximityStats;
  private DiskCacheStore diskCacheStore;
  private BypassRegistry bypassRegistry;
  private boolean antiXrayActive;

  AntiXrayRuntime(MikuXrayNet plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  /** 直通名单装配：登录/退出即时维护，并周期巡检在线玩家权限，及时反映权限变更。 */
  void startBypassRegistry() {
    BypassRegistry registry = new BypassRegistry(plugin);
    try {
      registry.start();
    } catch (Throwable throwable) {
      logger.log(Level.WARNING, "直通名单初始化失败，反矿透将以「无直通」继续运行", throwable);
    }
    this.bypassRegistry = registry;
  }

  /** 反矿透装配：任一环节不可用都只停用该模块并打印中文原因，不影响其它功能。 */
  void startAntiXray() {
    logDependencyAdvice();
    MikuConfig config = plugin.mikuConfig();
    AntiXrayConfig antiXray = config.antiXray();
    if (!antiXray.enabled()) {
      logger.info("反矿透已在配置中关闭（antixray.yml: enabled=false）");
      return;
    }

    if (!DependencyGuard.isPacketEventsPresent()) {
      logger.warning("未检测到 PacketEvents，反矿透模块停用");
      return;
    }

    PacketEventsHook packetEventsHook = new PacketEventsHook(logger);
    if (!packetEventsHook.initialize(antiXray.occlusion().extraOccluding(),
        antiXray.occlusion().extraNonOccluding())) {
      return;
    }

    BlockStateRegistry registry = packetEventsHook.registry();
    ChunkCodec codec = new ChunkCodec(registry, versionFlags());
    // 调色板压缩重排/位宽预算封顶由 bandwidth.yml 的 palette 段控制（与反矿透共用同一次编码）。
    // palette.enabled=false（或带宽总开关关闭）时取 DISABLED，即完全不重排、不封顶，零开销。
    BandwidthConfig.Palette palette = config.bandwidth().palette();
    boolean paletteUsable = config.bandwidth().enabled() && palette.enabled();
    ObfuscationProcessor processor = ObfuscationProcessor.create(codec, registry, antiXray, logger,
        new ObfuscationProcessor.PaletteOptions(paletteUsable && palette.reorder(),
            paletteUsable && palette.strictVerify(),
            // P0-1 位宽预算封顶（默认开）：修包体膨胀 + 拿降位宽收益
            paletteUsable && palette.widthBudget()));
    if (!processor.isActive()) {
      logger.warning("未解析到有效的隐藏方块或伪装方块，反矿透模块停用（请检查 antixray.yml）");
      return;
    }

    if (!DependencyGuard.isProtocolLibPresent()) {
      logger.warning("未检测到 ProtocolLib，反矿透模块停用（它是唯一的封包拦截通道）");
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

    // 磁盘缓存：关闭时为 null，改写路径退化为纯内存缓存。
    // 启用前先解析 zstd（服务端自带 → 本地已下载 → 按配置自动下载 → 回退 Deflater），
    // 必须早于 DiskCacheStore 创建：文件头的「压缩方案字节」在首次写入时就要定下来。
    AntiXrayConfig.DiskCache diskCacheConfig = antiXray.diskCache();
    DiskCacheStore diskCache = null;
    if (diskCacheConfig.enabled()) {
      ZstdSupport.initialize(new File(plugin.getDataFolder(), "lib").toPath(),
          diskCacheConfig.zstdAutoDownload(), diskCacheConfig.zstdDownloadUrl(),
          diskCacheConfig.zstdTimeoutSeconds(), logger);
      diskCache = new DiskCacheStore(new File(plugin.getDataFolder(), "cache").toPath(),
          diskCacheConfig, logger);
    }

    MikuWorkPool pool = new MikuWorkPool(antiXray.threads(), antiXray.queueCapacity());
    ProtocolLibHook hook = new ProtocolLibHook(plugin);
    // 传实时配置源：世界黑名单热重载后必须即时生效（否则纳入黑名单的世界仍会被改写）。
    if (!hook.register(antiXray, processor, pool, neighborProvider, chunkIndex, revealed,
        bypassRegistry, diskCache, () -> plugin.mikuConfig().antiXray())) {
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
    // world-unload 监听仍由主类持有（监听器生命周期与插件一致），这里只触发注册；
    // 注册时机（反矿透装配成功后、邻近显形装配前）与拆分前一致。
    plugin.registerWorldUnloadInvalidation();
    startProximity(antiXray, chunkIndex, revealed, proximityStats);
    logger.info("反矿透已启用（按维度分段）：" + dimensionSummary(antiXray)
        + "；区块边界邻块快照 " + neighborState(neighborProvider, processor) + "；磁盘缓存 "
        + (diskCache != null ? "已启用（" + new File(plugin.getDataFolder(), "cache").getPath() + "）"
            : "已关闭"));
  }

  /**
   * 启动摘要里的「邻块快照」状态：已关闭 / 已启用 / 当前模式不抓取。
   *
   * <p>{@code mode=all}（默认）下改写不做 6 面遮挡判定，邻块数据一位都用不到，因此连抓取都省掉
   * （判定见 {@code ObfuscationProcessor#needsNeighbors}）——这一句让「邻块缓存为什么一直是空的」可见。
   */
  private static String neighborState(NeighborChunkProvider provider, ObfuscationProcessor processor) {
    if (provider == null) {
      return "已关闭";
    }
    for (AntiXrayConfig.Dimension dimension : AntiXrayConfig.Dimension.values()) {
      if (processor.needsNeighbors(null, dimension)) {
        return "已启用";
      }
    }
    return "不抓取（各维度 mode 都是 all，改写不做 6 面遮挡判定）";
  }

  /** 各维度一句话摘要（启用状态、隐藏项数与伪装方块数），供启动日志核对。 */
  private static String dimensionSummary(AntiXrayConfig config) {
    StringBuilder sb = new StringBuilder();
    for (AntiXrayConfig.Dimension dimension : AntiXrayConfig.Dimension.values()) {
      if (!sb.isEmpty()) {
        sb.append("｜");
      }
      sb.append(dimension.label());
      if (!config.dimensionEnabled(dimension)) {
        sb.append(" 未启用");
        continue;
      }
      AntiXrayConfig.EffectiveObfuscation effective = config.dimensionEffective(dimension);
      sb.append(" 目标 ").append(effective.hideBlocks().size()).append(" 种 / 伪装 ")
          .append(effective.replacementWeights().size()).append(" 种 / 模式 ").append(effective.mode());
    }
    return sb.toString();
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
      logger.warning("未取得 ProtocolLib 协议管理器，邻近显形停用");
      return;
    }

    if (chunkIndex == null || revealed == null) {
      logger.info("邻近显形已在配置中关闭（antixray.yml: proximity.enabled=false）");
    }

    if (diskCacheStore == null && chunkIndex == null) {
      return;
    }

    // 先建邻近显形器（持有显形链路），再把它交给方块变更观察监听器做事件驱动即时显形（P1-4）。
    ProximityRevealer revealer = chunkIndex != null && revealed != null
        ? new ProximityRevealer(plugin, protocolManager, antiXray, chunkIndex, revealed, stats,
            bypassRegistry, workPool)
        : null;
    this.blockChangeRevealListener = new BlockChangeRevealListener(plugin, protocolManager,
        antiXray, chunkIndex, revealed, stats, diskCacheStore, revealer);
    try {
      blockChangeRevealListener.start();
      if (revealer != null) {
        this.proximityRevealer = revealer;
        revealer.start();
      }
    } catch (Throwable throwable) {
      logger.warning("邻近显形装配失败（不影响反矿透主体）：" + throwable.getMessage());
      stopProximity();
    }
  }

  /**
   * 依赖守卫增强提示（P2-6d，启动期调用一次，只提示不阻断）：
   * a) 前置版本低于建议下限时提示「可能不兼容」（版本号从插件描述文件读取，读不到跳过）；
   * b) 环境里有其它反矿透插件时提示「双重改写区块包，建议只留一个」。
   */
  private void logDependencyAdvice() {
    try {
      DependencyGuard.logVersionCompatibilityWarning(logger);
      DependencyGuard.logConflictingAntiXrayWarning(logger);
    } catch (Throwable throwable) {
      logger.log(Level.WARNING, "依赖守卫提示输出失败（不影响启动）", throwable);
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
      logger.log(Level.WARNING, "关闭磁盘缓存时出现异常（已强制结束）", throwable);
    }
  }

  /**
   * 停机：按固定顺序释放反矿透侧全部资源（顺序由主类 onDisable 的既有纪律决定，不得调整）：
   * 注销封包拦截 → 停邻近显形 → 关磁盘缓存 → 关工作池 → 停直通名单巡检。
   */
  void stopForDisable() {
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
  }

  /** 按新配置重建邻近显形（复用同一套显形结构，避免与改写链路脱钩）。 */
  void restartProximity() {
    stopProximity();
    if (antiXrayActive && proximityStats != null
        && (obfuscatedChunkIndex != null || diskCacheStore != null)) {
      startProximity(plugin.mikuConfig().antiXray(), obfuscatedChunkIndex, revealedSet,
          proximityStats);
    }
  }

  /** 使全部改写缓存、邻块快照、显形结构失效并落盘磁盘缓存（配置热重载时调用）。 */
  void invalidateRewriteCaches() {
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

  /** 世界卸载：整体失效该世界的改写缓存与磁盘缓存句柄（落盘 + 关闭，不留已卸载世界的资源）。 */
  void invalidateWorld(String worldName) {
    ProtocolLibHook hook = protocolLibHook;
    if (hook != null) {
      hook.invalidateWorld(worldName);
    }
    DiskCacheStore store = diskCacheStore;
    if (store != null) {
      store.invalidateWorld(worldName);
    }
  }

  public ProtocolLibHook protocolLibHook() {
    return protocolLibHook;
  }

  /** 反矿透工作线程池；未启用时为 null。 */
  public MikuWorkPool workPool() {
    return workPool;
  }

  /** 磁盘缓存（供命令与诊断读取持有量与命中率）；未启用时为 null。 */
  public DiskCacheStore diskCacheStore() {
    return diskCacheStore;
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

  /** 直通名单。 */
  public BypassRegistry bypassRegistry() {
    return bypassRegistry;
  }

  /** 反矿透主体是否已生效。 */
  public boolean antiXrayActive() {
    return antiXrayActive;
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
