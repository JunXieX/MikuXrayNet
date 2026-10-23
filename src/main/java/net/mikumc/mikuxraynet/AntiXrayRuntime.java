package net.mikumc.mikuxraynet;

import com.comphenix.protocol.ProtocolManager;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.antixray.BlockChangeRevealListener;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider;
import net.mikumc.mikuxraynet.antixray.ObfuscatedChunkIndex;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.antixray.OcclusionPatchListener;
import net.mikumc.mikuxraynet.antixray.ProximityRevealer;
import net.mikumc.mikuxraynet.antixray.ProximityStats;
import net.mikumc.mikuxraynet.antixray.RevealedSet;
import net.mikumc.mikuxraynet.bootstrap.DependencyGuard;
import net.mikumc.mikuxraynet.bootstrap.PacketEventsHook;
import net.mikumc.mikuxraynet.bootstrap.ProtocolLibHook;
import net.mikumc.mikuxraynet.cache.DiskCacheStore;
import net.mikumc.mikuxraynet.cache.ZstdSupport;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkScratch;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.MikuConfig;
import net.mikumc.mikuxraynet.occlusion.OcclusionStats;
import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionKey;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;

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

  // 以下装配字段由 onEnable（全局区域线程）/ 热重载（命令线程，可来自玩家区域线程）写入，
  // 由诊断任务（全局区域线程）读取——与 MikuXrayNet#throttlePipeline 完全相同的线程关系，
  // 因此同样必须是 volatile（否则诊断可能读到旧引用或 null 之外的陈旧值）。
  private volatile ProtocolLibHook protocolLibHook;
  private volatile MikuWorkPool workPool;
  private volatile ProximityRevealer proximityRevealer;
  private volatile BlockChangeRevealListener blockChangeRevealListener;
  /**
   * 按区块共享的伪装坐标索引（内存 = O(有伪装的已加载区块数)）。
   *
   * <p><b>volatile</b>：热重载会把它置空（关闭 proximity），而改写链路的工作线程通过可变引用读取它——
   * 需要跨线程立即可见，避免「已关闭但工作线程仍在一小段时间内登记」的可见性竞态。
   */
  private volatile ObfuscatedChunkIndex obfuscatedChunkIndex;
  /** 按玩家的已显形集合（内存 = O(玩家身边确实显形过的坐标数)）；volatile 原因同上。 */
  private volatile RevealedSet revealedSet;
  private volatile ProximityStats proximityStats;
  private volatile DiskCacheStore diskCacheStore;
  /**
   * 遮挡数据仓库（按世界分域）；反矿透未启用时为 {@code null}。
   *
   * <p>由反矿透解码工作线程持续写入（见 {@code ObfuscationProcessor#captureOcclusion}），
   * 由带宽侧的遮挡判定引擎与显形侧只读消费。
   *
   * <p><b>为什么按世界分域</b>：位图键只有区块坐标，而本插件全服单例、同时服务多个世界；
   * 不分域会让主世界与下界的同名区块互相覆盖，进而隐藏<b>本可见</b>的实体（安全方向错误）。
   * 世界卸载时整体摘除该世界的表（见 {@link #invalidateWorld}）。
   */
  private volatile OcclusionStores occlusionStores;
  /**
   * 出站方块变更 → 遮挡位图单格补丁监听器；未装配时为 {@code null}。
   *
   * <p>它让「玩家挖开遮挡物」这类变更即时反映到遮挡位图，避免陈旧「遮挡」位把<b>本已可见</b>的实体
   * 继续隐藏（属正确性要求，见 {@link OcclusionPatchListener}）。
   */
  private volatile OcclusionPatchListener occlusionPatchListener;
  /** 遮挡判定计数（插件级唯一实例，与带宽侧共用；补丁计数也写在这里）。 */
  private final OcclusionStats occlusionStats;
  private volatile BypassRegistry bypassRegistry;
  private volatile boolean antiXrayActive;
  /**
   * 「反矿透总开关已关闭、但邻近显形仍需继续兜底」的一次性提示闸门。
   *
   * <p><b>为什么需要一次性提示</b>：这是本类唯一「模块已关闭却仍有任务在跑」的例外，必须让管理员
   * 看见取舍；但它每次 reload 都成立，逐次打印会把控制台刷满，故只在进程内首次出现时说明一次。
   */
  private final AtomicBoolean masterSwitchOffNotice = new AtomicBoolean();

  AntiXrayRuntime(MikuXrayNet plugin, OcclusionStats occlusionStats) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
    this.occlusionStats = occlusionStats == null ? new OcclusionStats() : occlusionStats;
  }

  /** 直通名单装配：登录/退出即时维护，并在启用时对「当时已在线」玩家做一次快照；不做周期巡检。 */
  // 权限只在进入服务器时判定一次：运行期改权限需重进服务器生效（见 BypassRegistry 类注释）。
  void startBypassRegistry() {
    BypassRegistry registry = new BypassRegistry(plugin);
    boolean started;
    try {
      started = registry.start();
    } catch (Throwable throwable) {
      // start() 已对可预期失败（注册被拒、快照调度失败）兜底并打出**准确**的后果说明；
      // 这里只接「不该发生」的 Throwable，目的仅是保证本字段永远非 null、不阻断后面的反矿透装配。
      // 旧实现把这一 catch 当成注册失败的唯一提示，但 start() 内部从不外抛 —— 那段文案既不可达，
      // 又把后果说轻（只写「无直通」，没写「登录/退出也不会再维护名单」）。
      started = false;
      logger.log(Level.WARNING, "直通名单初始化出现未预期异常（本次启用按「无直通」继续）", throwable);
    }
    if (!started) {
      // 不重复打印 start() 内部已给出的详细原因，只补一句调用方视角的后果与恢复方式。
      logger.warning("直通名单未生效：持有 " + BypassRegistry.PERMISSION
          + " 的玩家本次启用期间不会直通；重载 / 重启插件可重试");
    }
    this.bypassRegistry = registry;
  }

  /** 反矿透装配：任一环节不可用都只停用该模块并打印中文原因，不影响其它功能。 */
  void startAntiXray() {
    logDependencyAdvice();
    MikuConfig config = plugin.mikuConfig();
    AntiXrayConfig antiXray = config.antiXray();
    if (!antiXray.enabled()) {
      // 配置主动关闭：不是异常，降为 FINE（需要确认时看 /mikuxraynet status）
      logger.fine("反矿透已在配置中关闭（antixray.yml: enabled=false）");
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
    // 调色板压缩/位宽预算封顶（含单值/低位宽降级）由 bandwidth.yml 的 palette 段控制（与反矿透共用同一次编码）。
    // palette.enabled=false（或带宽总开关关闭）时 widthBudget=false，即完全不收缩、不降级，零开销。
    BandwidthConfig.Palette palette = config.bandwidth().palette();
    boolean paletteUsable = config.bandwidth().enabled() && palette.enabled();
    // 遮挡位图恒建：它是实体剔除与邻近显形可见性判定的唯一数据源（原生命中射线已移除）。
    // 数据仓库在此创建并交给改写链路写入（ObfuscationProcessor#captureOcclusion）；
    // 带宽侧的遮挡判定引擎只读消费（见 ThrottlePipeline），显形侧只读消费（见 ProximityRevealer）。
    OcclusionStores occlusionStores = new OcclusionStores();
    ObfuscationProcessor processor = ObfuscationProcessor.create(codec, registry, antiXray, logger,
        new ObfuscationProcessor.PaletteOptions(paletteUsable && palette.strictVerify(),
            // P0-1 位宽预算封顶（默认开）：修包体膨胀 + 拿降位宽/单值降级收益
            paletteUsable && palette.widthBudget()),
        occlusionStores);
    if (!processor.isActive()) {
      logger.warning("未解析到有效的隐藏方块或伪装方块，反矿透模块停用（请检查 antixray.yml）");
      return;
    }

    if (!DependencyGuard.isProtocolLibPresent()) {
      logger.warning("未检测到 ProtocolLib，反矿透模块停用（它是唯一的封包拦截通道）");
      return;
    }

    NeighborChunkProvider neighborProvider = antiXray.neighbors().enabled()
        ? new NeighborChunkProvider(antiXray.neighbors().cacheMaximumSize(),
            antiXray.occlusion().extraOccluding(), antiXray.occlusion().extraNonOccluding())
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
      try {
        ZstdSupport.initialize(new File(plugin.getDataFolder(), "lib").toPath(),
            diskCacheConfig.zstdAutoDownload(), diskCacheConfig.zstdDownloadUrl(),
            diskCacheConfig.zstdSha256(), diskCacheConfig.zstdTimeoutSeconds(), logger);
        diskCache = new DiskCacheStore(new File(plugin.getDataFolder(), "cache").toPath(),
            diskCacheConfig, logger);
      } catch (Throwable throwable) {
        // 磁盘缓存只是可选加速：zstd 解析（含自动下载写盘）或缓存目录初始化失败时，必须只关掉缓存并
        // 继续启用反矿透与带宽模块。旧实现让异常上抛到 onEnable —— 一个可选的加速层失败会拖垮整个插件。
        diskCache = null;
        logger.log(java.util.logging.Level.WARNING,
            "磁盘缓存初始化失败，已按「不使用磁盘缓存」继续启用（改写结果仍走内存缓存）", throwable);
      }
    }

    MikuWorkPool pool = new MikuWorkPool(antiXray.threads(), antiXray.queueCapacity());
    ProtocolLibHook hook = new ProtocolLibHook(plugin);
    // 先把索引/已显形集合落到运行时字段，再以「可变引用」传给改写链路：热重载关闭 proximity 时
    // restartProximity 会把这两个字段置空，改写链路随即读到 null（跳过 recordChunkWithRemovals），
    // 从而真正做到「关闭即零开销」，且与面板读到的字段一致。提前赋值还可避免启动瞬间的读取竞态。
    this.obfuscatedChunkIndex = chunkIndex;
    this.revealedSet = revealed;
    // 传实时配置源：世界黑名单热重载后必须即时生效（否则纳入黑名单的世界仍会被改写）。
    if (!hook.register(antiXray, processor, pool, neighborProvider,
        () -> this.obfuscatedChunkIndex, () -> this.revealedSet,
        bypassRegistry, diskCache, () -> plugin.mikuConfig().antiXray())) {
      pool.close();
      closeDiskCache(diskCache);
      // 注册失败：清掉刚赋值的索引字段，保持「未接入改写链路」的原语义（诊断据此回落 EMPTY）
      this.obfuscatedChunkIndex = null;
      this.revealedSet = null;
      return;
    }

    this.workPool = pool;
    this.protocolLibHook = hook;
    this.diskCacheStore = diskCache;
    this.occlusionStores = occlusionStores;
    // 遮挡位图的 sectionY 只有 8 位（Y ∈ [-2048, 2047]）：超高/超低世界不会被建表，
    // 显形侧按「缺数据 = 遮挡」会让该区域矿物永不显形。启动期一次性告知，绝不静默。
    warnUnsupportedWorldHeight();
    this.proximityStats = new ProximityStats();
    this.antiXrayActive = true;
    // world-unload 监听仍由主类持有（监听器生命周期与插件一致），这里只触发注册；
    // 注册时机（反矿透装配成功后、邻近显形装配前）与拆分前一致。
    plugin.registerWorldUnloadInvalidation();
    // 启用路径：索引刚建、此刻清空无害（失败即等于「显形未启用」），故允许失败时清空。
    startProximity(antiXray, chunkIndex, revealed, proximityStats, true);
    startOcclusionPatch(registry);
    logger.fine("反矿透已启用（按维度分段）：" + dimensionSummary(antiXray)
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
   * <p>方块变更观察监听器只在「显形索引已建立」时注册：它的全部动作都以该索引与已显形集合为对象
   * （磁盘缓存的有效性由负载里的原始字节指纹判定，与本监听器无关），索引为 null 时注册只会让每个
   * 方块变更包在封包线程上白走一遍解析与空判定。
   */
  private void startProximity(AntiXrayConfig antiXray, ObfuscatedChunkIndex chunkIndex,
      RevealedSet revealed, ProximityStats stats, boolean clearOnFailure) {
    ProtocolManager protocolManager = protocolLibHook == null ? null : protocolLibHook.protocolManager();
    if (protocolManager == null) {
      logger.warning("未取得 ProtocolLib 协议管理器，邻近显形停用");
      return;
    }

    if (chunkIndex == null || revealed == null) {
      // 配置主动关闭：不是异常，降为 FINE
      logger.fine("邻近显形已在配置中关闭（antixray.yml: proximity.enabled=false）");
      return;
    }

    // 先建邻近显形器（持有显形链路），再把它交给方块变更观察监听器做事件驱动即时显形（P1-4）。
    // 遮挡位图仓库一并传入：显形可见性判定恒走该位图（原生命中射线已移除）。
    //
    // 构造与启动整体落在 try 内：构造期异常（例如 PacketAdapter 注册被拒、配置字段异常）若冒出
    // startAntiXray，会一路顶到 onEnable 让整个插件启用失败——而此时 ProtocolLib 拦截、线程池、
    // 磁盘缓存均已装配却永远不会被 stopForDisable 清理（线程/句柄泄漏，且带宽与命令也一并失效）。
    // 这与本类「任一环节不可用都只停用该模块，不影响其它功能」的契约矛盾。
    try {
      ProximityRevealer revealer = new ProximityRevealer(plugin, antiXray, chunkIndex, revealed, stats,
          bypassRegistry, workPool, occlusionStores);
      BlockChangeRevealListener listener = new BlockChangeRevealListener(plugin, protocolManager,
          chunkIndex, revealed, stats, revealer);
      // 先落到字段再启动：start() 中途抛异常时 stopProximity() 必须能停掉已启动的那一半
      this.proximityRevealer = revealer;
      this.blockChangeRevealListener = listener;
      listener.start();
      revealer.start();
      // 区块包放行后立即显形（M2）：把「该区块中玩家当前可见」的坐标紧随区块包补发，
      // 消掉「区块先伪装、等巡检才还原」的凭空出现窗口。失败只关这一项，周期巡检不受影响。
      try {
        protocolLibHook.setChunkDeliveryHook(revealer::onChunkDelivered);
      } catch (Throwable throwable) {
        protocolLibHook.setChunkDeliveryHook(null);
        logger.log(Level.WARNING, "区块下发即显形装配失败（不影响周期巡检）", throwable);
      }
    } catch (Throwable throwable) {
      logger.log(Level.WARNING, "邻近显形装配失败（不影响反矿透主体）", throwable);
      // 失败清理必须沿用调用方的 clearIndexes 口径：热重载路径传 false。
      // 旧实现在这里无条件 stopProximity()（= clearIndexes true），会在热重载的失败路径上清空
      // 「与改写链路共享」的伪装索引与已显形集合——而热重载不会让服务端重发已加载区块，
      // 这些区块里已记录的伪装坐标就此永久失联：玩家眼前本该被邻近显形还原的矿会一直显示成假方块，
      // 直到该区块因走远/重登/切维度被重新下发。成功路径守住了这条纪律，失败路径也必须守住。
      stopProximity(clearOnFailure);
    }
  }

  /**
   * 装配「出站方块变更 → 遮挡位图单格补丁」监听器（仅在启用异步遮挡时）。
   *
   * <p>补丁的遮挡判定走<b>状态级</b>路径：把 {@code registry::isEntityOccluding} 注入监听器，即用
   * 封包状态的全局 id 查<b>与遮挡位图同一张表</b>（逐状态一致，见 {@code OcclusionPatchListener}）。
   *
   * <p>失败只打一次中文 WARN：位图会因此只在区块重载时自愈，而不会即时反映方块变更——属「少藏」
   * 方向（射线仍按位图判定，陈旧「遮挡」位可能多藏，故必须把失败显式告知，不能静默）。
   */
  private void startOcclusionPatch(BlockStateRegistry registry) {
    if (occlusionStores == null) {
      return;
    }
    ProtocolManager protocolManager =
        protocolLibHook == null ? null : protocolLibHook.protocolManager();
    if (protocolManager == null) {
      logger.warning("未取得 ProtocolLib 协议管理器，遮挡位图方块补丁停用"
          + "（位图只在区块重载时重建，方块变更不会即时反映）");
      return;
    }
    // 构造同样纳入 try：构造期异常不得冒出 startAntiXray（理由见 startProximity）。
    try {
      OcclusionPatchListener listener = new OcclusionPatchListener(plugin, protocolManager,
          occlusionStores, occlusionStats, registry::isEntityOccluding);
      this.occlusionPatchListener = listener;
      listener.start();
    } catch (Throwable throwable) {
      // start() 可能已把 AsyncListenerHandler 注册进 ProtocolLib（在 handler.start() 抛出的场合），
      // 不注销会让它永久留在异步管理器里；stop() 对 handler 为 null 的情形是安全的空操作。
      stopOcclusionPatch();
      logger.log(Level.WARNING, "遮挡位图方块补丁装配失败（位图只在区块重载时重建）", throwable);
    }
  }

  /** 停用补丁监听器（可重复调用）。 */
  private void stopOcclusionPatch() {
    OcclusionPatchListener listener = occlusionPatchListener;
    occlusionPatchListener = null;
    if (listener != null) {
      listener.stop();
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

  /** 停用邻近显形：注销巡检任务与变更注销监听，并清空显形索引（释放内存）。 */
  private void stopProximity() {
    stopProximity(true);
  }

  /**
   * 停用邻近显形。
   *
   * @param clearIndexes 是否同时清空<b>与改写链路共享</b>的伪装索引 / 已显形集合。
   *                     插件停用或配置关闭时必须为 {@code true}（释放内存、做到「关闭即零开销」）；
   *                     热重载必须为 {@code false}（见 {@link ProximityRevealer#stop(boolean)}：
   *                     索引只在区块下发时写入，清空会让已加载区块的伪装坐标永久失联）。
   */
  private void stopProximity(boolean clearIndexes) {
    // 先摘「区块包放行后」回调：它持有 revealer 的实例引用，必须早于 revealer 停用清掉，
    // 否则停用后仍可能有在途区块包的回调把已停用的 revealer 唤醒（stop() 的闸门虽能兜住，但没必要留引用）。
    if (protocolLibHook != null) {
      try {
        protocolLibHook.setChunkDeliveryHook(null);
      } catch (Throwable throwable) {
        logger.log(Level.FINE, "卸下区块下发即显形回调失败（已忽略）", throwable);
      }
    }
    if (blockChangeRevealListener != null) {
      blockChangeRevealListener.stop();
      blockChangeRevealListener = null;
    }
    if (proximityRevealer != null) {
      proximityRevealer.stop(clearIndexes);
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
   * 注销封包拦截 → 停邻近显形 → 关磁盘缓存 → 关工作池 → 停直通名单。
   */
  void stopForDisable() {
    if (protocolLibHook != null) {
      // 先停区块改写：之后不会再有坐标被写进显形索引
      protocolLibHook.unregister();
      protocolLibHook = null;
    }
    // 最后停邻近显形：撤销巡检任务、注销变更注销监听并清空索引，不留副作用
    stopProximity();
    // 方块补丁监听同样先注销：之后不再有位图写入（此时遮挡位图仍会被下面的 clear 清空）
    stopOcclusionPatch();
    // 磁盘缓存最后关闭：先落盘再关句柄（此时已不会再有新的写入与代次变更）
    closeDiskCache();
    if (workPool != null) {
      workPool.close();
      workPool = null;
    }
    // 工作池已关：本线程之后不会再借还区块 scratch，顺手丢掉当前线程的 ThreadLocal 复用缓存。
    // 只在停用 / 热重载时调用——ThreadLocal 条目不会随工作池重建自行消失，放进热路径会
    // 丢掉空闲池、让下一次编解码重新分配几百 KB 数组，反而抵消复用收益。
    ChunkScratch.clearThreadLocals();
    // 邻近显形侧的线程本地复用实例（位图判定器 / 区块键探针 / 候选点缓冲 / netty 解析缓冲）同理：
    // 条目随线程存活并钉住旧类加载器，停用 / 热重载时必须显式清掉当前线程这一份。
    // 其它线程（netty 事件循环 / Folia 区域线程）的条目无法从这里枚举，会在其下次访问
    // ThreadLocalMap 时被惰性 expunge——这正是这里只清调用线程的原因。
    try {
      ProximityRevealer.clearThreadLocals();
      // 遮挡补丁缓冲：静态有界并发池（非 ThreadLocal），clear() 即全部释放
      OcclusionPatchListener.releasePooledBuffers();
    } catch (Throwable throwable) {
      logger.log(Level.WARNING, "清理邻近显形线程本地缓存失败（忽略）", throwable);
    }
    if (bypassRegistry != null) {
      bypassRegistry.stop();
      bypassRegistry = null;
    }
    // 遮挡数据仓库最后清空并置空（此时改写链路已注销、异步引擎已先停，不会再有读写）
    if (occlusionStores != null) {
      occlusionStores.clear();
      occlusionStores = null;
    }
    antiXrayActive = false;
  }

  /**
   * 按新配置重建邻近显形（复用同一套显形结构，避免与改写链路脱钩）。
   *
   * <p><b>为什么必须读新配置的开关（而不是凭「字段非 null」）</b>：索引与已显形集合在启动期一旦建立就
   * 一直非 null，若只凭「字段非 null」判定就重启显形，则热重载把开关改成 false 时显形仍会照常启动——
   * 「关闭即零开销」的承诺在 reload 路径失效（旧缺陷：条件恒真、索引从不置空）。
   * 现在关闭时释放索引并停用显形；启用时复用既有索引重启显形。
   *
   * <p><b>总开关关闭不在此门闸内（刻意如此）</b>：{@code antixray.yml: enabled=false} 只表示
   * 「不再改写新区块」——改写链路会整体放行（{@code ProtocolLibAsyncListener#appliesTo} 读实时配置），
   * 索引从此不再增长；但<b>客户端里已经存在的伪装方块只能靠邻近显形换回真实方块</b>。若这时把显形
   * 停掉并清空索引，那些假矿就永远无法被还原（要等区块重发才换回真方块），属本项目禁止的
   * 「该显形不显形 / 玩家体验异常」。存量索引会随区块卸载收缩、任务本身成本很小，因此宁可留着兜底；
   * 只在控制台打一条一次性说明（见 {@link #masterSwitchOffNotice}），把这层取舍写清楚。
   */
  void restartProximity() {
    if (!antiXrayActive || proximityStats == null) {
      // 反矿透整体未启用（或尚未装配完成）时邻近显形无从接入改写链路，重启项静默无效。
      // 必须显式告知管理员「为什么没生效」，否则「重启邻近显形」这类操作会被误读为已生效。
      stopProximity();
      logger.fine("反矿透未启用，proximity 重启项已跳过");
      return;
    }
    AntiXrayConfig antiXray = plugin.mikuConfig().antiXray();
    if (!proximitySwitchOn(antiXray)) {
      // 关闭（只看 proximity.enabled）：停周期任务、注销事件监听并清空索引内容；
      // 这里再置空字段，之后不再登记/发送任何显形——与启动路径「关闭即不建索引、零开销」保持一致。
      stopProximity();
      obfuscatedChunkIndex = null;
      revealedSet = null;
      logger.fine("邻近显形已在配置中关闭（antixray.yml: proximity.enabled=false），邻近显形已停用并释放索引");
      return;
    }
    // 总开关关闭、显形开关仍开：**继续运行**（见方法注释里的取舍说明）。
    // 改写链路此时已整体放行，索引不会再增长，只有「已下发的假矿」需要兜底；
    // 重启后 startAntiXray 走不到这里（enabled=false 会提前返回），故显形不会再启动。
    if (!antiXray.enabled() && masterSwitchOffNotice.compareAndSet(false, true)) {
      logger.info("反矿透总开关已关闭（antixray.yml: enabled=false）：不再改写新区块，"
          + "但已下发的伪装区块仍需显形兜底，故邻近显形任务继续运行（重启后不再启动）");
    }
    // 启用：只停任务、绝不清空共享索引——索引只在区块封包下发时写入，而热重载不会让服务端重发
    // 已加载区块；清空会让这些区块的伪装坐标永久失联（玩家眼前的假方块再也不显形，直到区块重发）。
    stopProximity(false);
    AntiXrayConfig.Proximity proximity = antiXray.proximity();
    if (obfuscatedChunkIndex == null || revealedSet == null) {
      // 启动时 proximity 是关闭的、此刻才首次启用：新建索引并赋给字段即可——改写链路持有的是
      // 「读字段」的 Supplier（见 startAntiXray 里的 hook.register），新对象立刻生效，无需重启服务端。
      obfuscatedChunkIndex =
          new ObfuscatedChunkIndex(proximity.maxPositions(), proximity.expireSeconds());
      revealedSet =
          new RevealedSet(proximity.maxPositionsPerPlayer(), proximity.expireSeconds());
      logger.fine("邻近显形已按热重载配置首次启用（已新建伪装索引与已显形集合）");
    }
    // 热重载路径：失败时<b>绝不</b>清空共享索引（见 startProximity 的 catch 说明）。
    startProximity(antiXray, obfuscatedChunkIndex, revealedSet, proximityStats, false);
  }

  /**
   * 邻近显形开关是否开启：<b>只反映 {@code proximity.enabled}，刻意不含反矿透总开关</b>。
   *
   * <p><b>为什么总开关不参与本门闸</b>：{@code enabled=false} 只意味着「不再改写新的区块」，
   * 而客户端里已经存在的伪装方块仍必须靠邻近显形换回真实方块。若把总开关并进来，reload 就会停掉
   * 显形并清空索引，那些假矿将永远无法还原（要等区块重发）——属本项目禁止的「该显形不显形」。
   * 总开关只影响是否打印一条取舍说明（见 {@code restartProximity}），不参与启停判定。
   *
   * <p>抽成包级可见的<b>纯函数</b>（不触碰 Bukkit / 实例字段）是为了可离线单测：
   * {@link AntiXrayRuntime} 实例的构造需要 {@code Plugin}，单测无法构造，而「总开关不得参与启停」
   * 这一语义必须被测试钉住（否则很容易被后人"顺手"改回总开关优先）。
   *
   * @param config 当前代的反矿透配置（{@code null} 视为不运行：配置尚未加载时不应起任何巡检任务）
   */
  static boolean proximitySwitchOn(AntiXrayConfig config) {
    return config != null && config.proximity().enabled();
  }

  /**
   * 使<b>派生缓存</b>（改写缓存 + 邻块快照）失效并落盘磁盘缓存（配置热重载时调用）。
   *
   * <p><b>伪装索引 / 已显形集合刻意保留</b>：热重载不会让客户端重收已加载区块，清掉它们会让已下发的
   * 假矿永远无法被还原（要等区块重发）。详见 {@code ProtocolLibAsyncListener#invalidateAll} 的说明。
   */
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
    // 三步各自兜底，且把<b>安全相关</b>的那一步（遮挡表摘除）放在最前：
    //
    // 遮挡位图的键里没有世界维度（世界分域只在外层 map），所以旧世界的「实心石头」几何会在同名世界
    // 重新加载后被当成新世界的遮挡数据读走，把<b>本可见</b>的实体误藏——安全方向错误。
    // 旧实现是一条单链：改写缓存或磁盘缓存任一步抛错（IO 失败、目录被占用），后面的遮挡表摘除就被跳过，
    // 这个「安全方向错误」随之发生。故拆成独立的三步，且先做最要紧的那一步。
    OcclusionStores stores = occlusionStores;
    if (stores != null) {
      try {
        stores.removeWorld(worldName);
      } catch (Throwable throwable) {
        logger.log(Level.WARNING, "摘除世界遮挡位图时出现异常（已忽略）", throwable);
      }
    }
    ProtocolLibHook hook = protocolLibHook;
    if (hook != null) {
      try {
        hook.invalidateWorld(worldName);
      } catch (Throwable throwable) {
        logger.log(Level.WARNING, "摘除世界改写缓存时出现异常（已忽略）", throwable);
      }
    }
    DiskCacheStore store = diskCacheStore;
    if (store != null) {
      try {
        store.invalidateWorld(worldName);
      } catch (Throwable throwable) {
        logger.log(Level.WARNING, "摘除世界磁盘缓存时出现异常（已忽略）", throwable);
      }
    }
  }

  /**
   * 区块卸载：摘除该区块整列的遮挡位图，使内存驻留量有界。
   *
   * <p><b>为什么按卸载驱逐是安全的</b>：区块卸载意味着附近已无玩家，而射线只服务于「玩家周围的
   * 被追踪实体」，因此这份几何数据此刻无人使用；区块下次被重新发送（解码或缓存命中）时会整列重建。
   *
   * <p>由主类持有的 {@code ChunkUnloadEvent} 监听触发，在主线程 / Folia 区域线程执行，只做一次
   * 内存表的整列摘除，不读世界内容、不做任何计算。
   */
  void invalidateChunk(String worldName, int chunkX, int chunkZ) {
    // 1) 遮挡位图：整列摘除（本区块已无玩家，几何数据无人使用，下次下发时整列重建）。
    OcclusionStores stores = occlusionStores;
    if (stores != null) {
      try {
        OcclusionStore store = stores.lookup(worldName);
        if (store != null) {
          store.removeColumn(chunkX, chunkZ);
        }
      } catch (Throwable throwable) {
        logger.log(Level.WARNING, "摘除区块遮挡位图时出现异常（已忽略）", throwable);
      }
    }
    // 2) 显形索引：同步摘除该区块的伪装清单。
    //
    // 这是「索引只随区块卸载清理」策略的<b>唯一</b>回收路径（时间过期已被取消，见
    // ObfuscatedChunkIndex 的说明）：漏了这一步，索引就会随「本局游戏曾改写过的区块数」无界增长。
    // 为什么可以安全摘除：区块卸载意味着该区块已不在任何玩家的客户端里，其伪装坐标此刻不可能被显形；
    // 区块下次被下发时会重新改写并重新登记（改写结果走缓存时同样会重新记录）。
    ObfuscatedChunkIndex index = obfuscatedChunkIndex;
    if (index != null) {
      try {
        if (index.invalidateChunk(worldName, chunkX, chunkZ)) {
          // 该区块的清单消失 ⇒ 其上所有玩家的「已显形」标记必须同步作废，否则成为孤儿标记：
          // sizeFor 虚增会让邻近显形的「整块跳过」提前成立，真实坐标被周期跳过（漏显形）。
          RevealedSet revealed = revealedSet;
          if (revealed != null) {
            // 用「世界名 + 坐标」的公开入口：ChunkKey 是本插件 antixray 包的内部表示，不对外暴露。
            revealed.clearChunk(worldName, chunkX, chunkZ);
          }
        }
      } catch (Throwable throwable) {
        logger.log(Level.WARNING, "摘除区块显形索引时出现异常（已忽略）", throwable);
      }
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

  /**
   * 遮挡数据仓库注册表（供带宽侧的遮挡判定引擎与显形侧只读消费，见 {@code ThrottlePipeline}）；
   * 反矿透未启用时为 {@code null}。
   */
  public OcclusionStores occlusionStores() {
    return occlusionStores;
  }

  /**
   * 遮挡判定计数（<b>插件级唯一实例</b>，与带宽侧共用、跨热重载复用）。
   *
   * <p><b>为什么必须暴露</b>：遮挡位图的方块补丁监听（{@code OcclusionPatchListener}）随反矿透装配，
   * 与「带宽管线是否建成」无关——带宽装配失败时它仍会持续往本实例写入。诊断若只能经
   * {@code ThrottlePipeline#occlusionStats()} 取值，就会在这条异常路径上把「补丁正常工作」显示成
   * 「未接线、补丁 0 格」。恒返回非 null（未启用时计数器恒为 0）。
   */
  public OcclusionStats occlusionStats() {
    return occlusionStats;
  }

  /**
   * 当前驻留的遮挡 section 条目总数（跨世界求和）。遮挡数据仓库未建立（反矿透未启用）时为 0。
   *
   * <p>与 {@link #occlusionStats()} 同理由：位图驻留量由反矿透侧持有，带宽管线缺失时也应可观测。
   */
  public int occlusionSectionsResident() {
    OcclusionStores stores = occlusionStores;
    return stores == null ? 0 : stores.residentSections();
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

  /** 超高世界告警的一次性闸门。 */
  private static final java.util.concurrent.atomic.AtomicBoolean WORLD_HEIGHT_WARNED =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * 超高/超低世界的一次性告警：遮挡位图的 sectionY 只有 8 位（Y ∈ [-2048, 2047]），超出该范围的
   * 世界不会建立位图——显形侧按「缺数据 = 遮挡」会让该区域矿物永不显形、实体侧射线按缺数据降级。
   * 默认 384 高世界不受影响；这里只提示，不做兼容（扩位需要同步改 OcclusionPayload/RevealedSet 布局）。
   */
  private void warnUnsupportedWorldHeight() {
    // 只提示一次（startAntiXray() 每次热重载都会执行）；闸门放在「遍历成功之后」，
    // 首次因世界列表不可读而失败时不消费这次提示，下次重载还能再试。
    if (WORLD_HEIGHT_WARNED.get()) {
      return;
    }
    try {
      for (World world : Bukkit.getWorlds()) {
        int minSection = world.getMinHeight() >> 4;
        int maxSection = (world.getMaxHeight() - 1) >> 4;
        if (minSection < SectionKey.SECTION_Y_MIN || maxSection > SectionKey.SECTION_Y_MAX) {
          logger.warning("世界 " + world.getName() + " 的高度范围（" + world.getMinHeight() + ".."
              + world.getMaxHeight() + "）超出台阶位图支持范围：该世界的遮挡位图与邻近显形不可用");
        }
      }
      WORLD_HEIGHT_WARNED.set(true);
    } catch (Throwable throwable) {
      // 告警失败不影响主流程（世界列表不可读时只丢一条提示，闸门不置位以便下次重试）
      logger.log(Level.FINE, "检查世界高度范围时出现异常（已忽略）", throwable);
    }
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
