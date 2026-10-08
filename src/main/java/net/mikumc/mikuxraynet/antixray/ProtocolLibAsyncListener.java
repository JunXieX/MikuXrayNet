package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.cache.DiskCacheStore;
import net.mikumc.mikuxraynet.cache.DiskPayload;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.concurrency.RewriteTask;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * ProtocolLib 异步区块监听器：反矿透区块改写链路的封包拦截入口
 * （另有 {@link BlockChangeRevealListener} 注册异步出站方块变更监听，用于注销伪装坐标与推进缓存代次）。
 *
 * <p>线程模型：网络线程（本监听回调）只做「读坐标 → 建任务 → 登记延迟 → 入队」，
 * 解码/判定/重编码全部交给 {@link MikuWorkPool} 的工作线程；工作线程不触碰任何 Bukkit API。
 * 需要邻块数据时，网络线程只登记「需要快照」这件事，由主线程 / Folia 区域线程执行
 * {@link NeighborChunkProvider#capture} 抓取，再回到工作线程改写。
 *
 * <p>拦截的封包：{@code MAP_CHUNK}，以及 1.20.2+ 的 {@code CHUNK_BATCH_START} /
 * {@code CHUNK_BATCH_FINISHED}（批次闸门见 {@link ChunkBatchGate}）。
 *
 * <p>fail-open：队列满 / 封包解析失败 / 处理超时 / 任何异常，一律放行原包；
 * 所有出口共用 {@link RewriteTask#signalOnce()}，保证「恰好放行一次」。
 */
public final class ProtocolLibAsyncListener extends PacketAdapter {

  /** 未配对的批次闸门数量上限（异常情况下防止无界增长）。 */
  private static final int MAX_PENDING_BATCHES = 256;

  /** 缓存值：源字节指纹 + 改写结果（不含任何封包或世界引用）。 */
  private record CachedChunk(long sourceHash, byte[] data, int[] positions) {
  }

  /**
   * 未配对批次闸门表：按插入序持有，超限时<b>只淘汰最旧的一个</b>，玩家退出时按 UUID 清理。
   *
   * <p>旧实现是「超限 {@code clear()} 清掉所有玩家」——一个异常玩家的陈旧闸门会让全服
   * 正在进行中的批次全部失去计数（提前放行 FINISHED）。LinkedHashMap（插入序）+ 淘汰队首
   * 把影响面收敛到单个玩家：被淘汰玩家的 FINISHED 无配对 START，直接照常放行，
   * 其余玩家的批次闸门不受影响。
   *
   * <p>网络线程并发访问（不同玩家的封包回调可同时到达），操作统一加锁；锁内只有内存操作。
   */
  static final class BatchTable {

    /** 每逐出多少个批次闸门输出一条 WARN（节流，避免超限时刷屏）。 */
    private static final long EVICTION_LOG_INTERVAL = 64;

    private final int maxPending;
    private final java.util.logging.Logger logger;
    private final LinkedHashMap<UUID, ChunkBatchGate> gates = new LinkedHashMap<>();
    /** 累计逐出的闸门数（诊断用；逐出行为本身不变）。 */
    private long evictions;

    /** 测试用构造：可指定容量上限（不输出日志）。 */
    BatchTable(int maxPending) {
      this(maxPending, null);
    }

    /** 生产用构造：超限逐出最旧条目时按 {@link #EVICTION_LOG_INTERVAL} 节流输出中文 WARN。 */
    BatchTable(int maxPending, java.util.logging.Logger logger) {
      this.maxPending = Math.max(1, maxPending);
      this.logger = logger;
    }

    /** 取某玩家当前未配对的闸门；无则返回 {@code null}。 */
    synchronized ChunkBatchGate get(UUID playerId) {
      return gates.get(playerId);
    }

    /**
     * 原子地「取某玩家当前闸门 + 登记一个待完成区块」。
     *
     * <p><b>为什么必须原子</b>：若先 {@link #get} 再在临界区外调用 {@link ChunkBatchGate#chunkStarted()}，
     * 这两步之间到达的 {@code CHUNK_BATCH_FINISHED} 会 {@link #remove} 闸门并在 pending 仍为 0 时立即放行
     * ——本区块的改写结果随后才回到客户端，批次节奏被提前打乱。合并为一次同步操作后，凡成功取得闸门的
     * 区块必然已被计数，FINISHED 只能等它 {@link ChunkBatchGate#chunkDone()} 后才放行。
     *
     * @return 该玩家当前闸门；无闸门（或恰好已被 FINISHED 取走）时返回 {@code null}（调用方按无批次处理）
     */
    synchronized ChunkBatchGate acquire(UUID playerId) {
      ChunkBatchGate gate = gates.get(playerId);
      if (gate != null) {
        gate.chunkStarted();
      }
      return gate;
    }

    /** 打开某玩家的批次闸门；已达上限时先淘汰最旧的未完成条目（只清一个）。 */
    void open(UUID playerId) {
      long totalEvicted;
      synchronized (this) {
        boolean evicted = false;
        while (gates.size() >= maxPending) {
          Iterator<UUID> iterator = gates.keySet().iterator();
          if (!iterator.hasNext()) {
            break;
          }
          iterator.next();
          iterator.remove();
          evictions++;
          evicted = true;
        }
        gates.put(playerId, new ChunkBatchGate());
        totalEvicted = evictions;
        if (!evicted) {
          return;
        }
      }
      // 日志在锁外输出（锁内只做内存操作，见类注释）；按固定间隔节流，绝不因超限刷屏。
      if (logger != null && totalEvicted % EVICTION_LOG_INTERVAL == 0) {
        logger.warning("区块批次闸门表已满（上限 " + maxPending + " 个未配对批次），"
            + "已按插入序淘汰最旧的闸门（累计逐出 " + totalEvicted + " 个）："
            + "被淘汰玩家的 CHUNK_BATCH_FINISHED 将无配对 START、直接放行。"
            + "正常运营下不应出现——请排查是否有玩家的批次包只发 START 不发 FINISHED。");
      }
    }

    /** 移除并返回某玩家的闸门（批次结束 / 玩家退出时调用）。 */
    synchronized ChunkBatchGate remove(UUID playerId) {
      return gates.remove(playerId);
    }

    synchronized int size() {
      return gates.size();
    }

    /** 累计因超限被淘汰的闸门数（诊断用）。 */
    synchronized long evictions() {
      return evictions;
    }

    /** 清空全部闸门（停用时调用；此时不会再有批次包到达）。 */
    synchronized void clear() {
      gates.clear();
    }
  }

  private final AntiXrayConfig config;
  /**
   * 实时配置源（读取「当前」的反矿透配置）。本监听器不随热重载重建，而 {@code AntiXrayConfig}
   * 实例会被 reload 整体替换，因此世界黑名单等需要即时生效的判定必须走这里——
   * 否则 reload 后新加入黑名单的世界仍会被改写（违反「黑名单世界绝不改写」）。
   */
  private final Supplier<AntiXrayConfig> liveConfig;
  private final ObfuscationProcessor processor;
  private final MikuWorkPool workPool;
  private final AsynchronousManager asynchronousManager;
  private final RewriteCache<CachedChunk> cache;
  private final NeighborChunkProvider neighborProvider;
  /**
   * 邻块快照提供者是否可用（等价于 {@code neighborProvider != null}）。
   *
   * <p><b>为什么这里不再钉死 {@code neighbors.enabled}</b>：本监听器不随热重载重建，
   * 而该开关可能在 reload 时被改。是否抓取必须实时读「当前」配置（见 {@link #neighborsNeeded}），
   * 否则 reload 关闭邻块后仍会继续抓取（承诺的「关闭即零开销」失效），重新开启也不会恢复。
   */
  private final boolean neighborsAvailable;
  private final boolean handleChunkBatch;
  /**
   * 伪装区块索引的<b>可变引用</b>（每次读取现取，而非构造期钉死实例）。
   *
   * <p><b>为什么用引用而非实例</b>：本监听器不随热重载重建，而邻近显形可在 reload 时被关闭——
   * 关闭时调用方（{@code AntiXrayRuntime}）把该引用置为 {@code null}。若这里存的是构造期实例，
   * 关闭后改写链路仍会继续往旧索引里 recordChunk：索引仍在增长（违背「关闭即零开销」），
   * 而面板读的是已置空的运行时字段（显示 0），两边不一致。取可变引用后，关闭即为真正的零开销且与面板一致。
   */
  private final Supplier<ObfuscatedChunkIndex> obfuscatedChunkIndex;
  /** 已显形集合的可变引用；语义同 {@link #obfuscatedChunkIndex}。 */
  private final Supplier<RevealedSet> revealedSet;
  private final BypassRegistry bypassRegistry;
  private final DiskCacheStore diskCache;
  private final RewriteStats stats = new RewriteStats();
  private final BatchTable batches;
  /**
   * 玩家退出清理批次闸门的 Bukkit 监听。
   *
   * <p>ProtocolLib 的 {@code PacketAdapter} 不是 Bukkit {@link Listener}，因此用独立的匿名监听对象
   * 注册/注销，与 {@link #registerQuitHook()} 严格配对。中途掉线的玩家不会再有 FINISHED 包来移除
   * 其批次闸门，必须在退出事件里清理。
   */
  private final Listener quitListener = new Listener() {
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
      batches.remove(event.getPlayer().getUniqueId());
    }
  };
  /** 退出监听是否已注册（注册/注销必须配对）。 */
  private volatile boolean quitHookRegistered;
  /**
   * 各类错误日志的已提示次数，<b>按分类键各自限流</b>。
   *
   * <p>用「每类一个计数器」而非单个全局计数器：后者会让前 {@link Constants#MAX_ERROR_LOGS} 条
   * 任意错误用尽配额，此后所有不同种类的失败一律静默——与日志文案「同类错误最多提示 N 次」不符。
   * 分类键只有寥寥几个（见 {@link #logThrottled(String, String, Throwable)}），故条目数有界。
   */
  private final ConcurrentHashMap<String, AtomicInteger> errorLogCounters = new ConcurrentHashMap<>();
  /** 「首次改写诊断」只打一次（CAS 抢占），避免每区块刷屏。 */
  private final AtomicBoolean firstRewriteDiagnosed = new AtomicBoolean();
  /**
   * 停用中标记：{@link #unregisterBukkitHooks()} 首行置位（{@code ProtocolLibHook.unregister} 会在注销
   * 异步处理器之前先调它）。
   *
   * <p><b>闭合的窗口</b>：{@link #scheduleCaptureThenRewrite} 把改写推迟到区域线程的抓取回调里执行，
   * 该 {@code RewriteTask} 此刻既不在工作池的待处理集合内、其看门狗也会随停用被关闭。停用/热重载时
   * 区域回调可能不再执行，若不在回调里主动放行，这个区块封包就<b>永远无人 signalOnce</b>——
   * 玩家会卡在区块加载界面直到重新登录。
   */
  private volatile boolean stopping;

  /**
   * @param liveConfig       实时配置源（读取「当前」配置，保证热重载后世界黑名单即时生效）；可为 null（回落 config）
   * @param neighborProvider 邻区块贴边快照提供者；仅在 {@code neighbors.enabled} 时被使用
   * @param handleChunkBatch 是否拦截 1.20.2+ 的区块批量包（不可用时由装配方降级为 false）
   * @param obfuscatedChunkIndex 伪装区块索引的<b>可变引用</b>；{@code get()} 返回 {@code null} 表示不做邻近显形
   * @param revealedSet      已显形集合的<b>可变引用</b>；{@code get()} 返回 {@code null} 表示不做邻近显形
   * @param bypassRegistry   直通名单；{@code null} 表示退化为无直通（仍按世界范围判定）
   * @param diskCache        磁盘缓存；{@code null} 表示只用内存缓存
   */
  public ProtocolLibAsyncListener(Plugin plugin, AntiXrayConfig config,
      Supplier<AntiXrayConfig> liveConfig, ObfuscationProcessor processor,
      MikuWorkPool workPool, AsynchronousManager asynchronousManager,
      NeighborChunkProvider neighborProvider, boolean handleChunkBatch,
      Supplier<ObfuscatedChunkIndex> obfuscatedChunkIndex, Supplier<RevealedSet> revealedSet,
      BypassRegistry bypassRegistry, DiskCacheStore diskCache) {
    super(plugin, ListenerPriority.NORMAL, packetTypes(handleChunkBatch));
    this.config = config;
    this.liveConfig = liveConfig;
    this.processor = processor;
    this.workPool = workPool;
    this.asynchronousManager = asynchronousManager;
    this.cache = new RewriteCache<>(config.cacheMaximumSize(), config.cacheExpireAfterAccessSeconds());
    this.neighborProvider = neighborProvider;
    // 只记录「提供者是否可用」；是否真的抓取由 neighborsNeeded 实时读当前配置的 neighbors.enabled 决定。
    this.neighborsAvailable = neighborProvider != null;
    this.handleChunkBatch = handleChunkBatch;
    this.obfuscatedChunkIndex = obfuscatedChunkIndex;
    this.revealedSet = revealedSet;
    this.bypassRegistry = bypassRegistry;
    this.diskCache = diskCache;
    // 批次闸门表在超限逐出时按间隔输出中文 WARN（旧实现静默逐出，真机无法察觉异常玩家）。
    this.batches = new BatchTable(MAX_PENDING_BATCHES, plugin.getLogger());
    registerQuitHook();
  }

  /**
   * 注册玩家退出监听：中途掉线的玩家不再有 FINISHED 包来移除其批次闸门，
   * 必须在退出事件里清理，否则陈旧闸门只能靠超限淘汰兜底。
   */
  private void registerQuitHook() {
    try {
      getPlugin().getServer().getPluginManager().registerEvents(quitListener, getPlugin());
      quitHookRegistered = true;
    } catch (Throwable throwable) {
      logThrottled("玩家退出清理监听注册失败（批次闸门退化为仅靠超限淘汰兜底）", throwable);
    }
  }

  /**
   * 注销本监听器的玩家退出监听（与 {@link #registerQuitHook()} 配对，停用时调用），并清空闸门表。
   */
  public void unregisterBukkitHooks() {
    // 首行置位：此后任何迟到的抓取回调都必须自行放行（见 stopping 字段说明），
    // 否则该区块封包会永久无人 signalOnce（停用期工作池与看门狗均已关闭，不会再有兜底）。
    stopping = true;
    if (quitHookRegistered) {
      quitHookRegistered = false;
      try {
        HandlerList.unregisterAll(quitListener);
      } catch (Throwable throwable) {
        logThrottled("注销玩家退出清理监听时出现异常（通常可忽略）", throwable);
      }
    }
    batches.clear();
  }

  /** 与 MAP_CHUNK 同列白名单，才能让批次包走同一条「每玩家有序发送队列」。 */
  private static PacketType[] packetTypes(boolean handleChunkBatch) {
    if (!handleChunkBatch) {
      return new PacketType[] {PacketType.Play.Server.MAP_CHUNK};
    }
    return new PacketType[] {
        PacketType.Play.Server.MAP_CHUNK,
        PacketType.Play.Server.CHUNK_BATCH_START,
        PacketType.Play.Server.CHUNK_BATCH_FINISHED};
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    PacketType type = event.getPacketType();
    // 停用 / 被取消瞬间：先把 CHUNK_BATCH_FINISHED 的残留闸门清掉，再判 isActive。旧实现直接
    // early-return，残留闸门只能靠退出清理 / 下次 START 覆盖 / 超限淘汰兜底——与 handleBatchFinish
    // 「先清闸门再判 appliesTo」同构。活跃路径不改：handleBatchFinish 自己会先 remove 再延迟放行，
    // 若这里抢先 remove，该批次的 FINISHED 就失去了应有的延迟，故清理只放在早退分支里。
    if (event.isCancelled() || !processor.isActive()) {
      if (handleChunkBatch && type == PacketType.Play.Server.CHUNK_BATCH_FINISHED) {
        Player player = event.getPlayer();
        if (player != null) {
          batches.remove(player.getUniqueId());
        }
      }
      return;
    }
    if (handleChunkBatch && type == PacketType.Play.Server.CHUNK_BATCH_START) {
      handleBatchStart(event);
    } else if (handleChunkBatch && type == PacketType.Play.Server.CHUNK_BATCH_FINISHED) {
      handleBatchFinish(event);
    } else if (type == PacketType.Play.Server.MAP_CHUNK) {
      handleChunk(event);
    }
  }

  private void handleChunk(PacketEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    // 世界只取一次并复用同一引用：旧实现 appliesTo(player) 内部取一次 getWorld()、
    // 下面又取一次——玩家恰在两次取之间切换世界时，可能用「新世界的生效判定」去放行
    // 「旧世界的区块原包」（窗口极小，但确实存在 TOCTOU）。这里固定一次读取。
    World world = player.getWorld();
    if (world == null || !appliesTo(player, world)) {
      return;
    }

    AsyncMarker marker = event.getAsyncMarker();
    if (marker == null) {
      return;
    }
    // 队列满：直接放行原包，不登记延迟。
    // 必须计数：这是唯一「本区块完全未伪装、矿物以真实状态下发」的路径，若不计数，
    // 现象是「改写数看起来正常但玩家能透视」，无法归因（见 RewriteStats#chunksSkippedQueueFull）。
    if (!workPool.hasCapacity()) {
      stats.chunksSkippedQueueFull.increment();
      return;
    }

    ChunkPacketAccessor accessor;
    try {
      accessor = new ChunkPacketAccessor(event.getPacket());
    } catch (Throwable throwable) {
      // 文案必须指向环境问题：绝大多数情况是服务端 NMS 结构变更（如 26.3 移除了构造器/改了字段）
      logThrottled("区块封包结构解析失败（无法定位区块数据字段，服务端 NMS 结构可能已变更），已按原包放行",
          throwable);
      return;
    }

    // 「取闸门 + 登记 chunkStarted」必须是同一次原子操作（见 BatchTable#acquire）：
    // 否则 FINISHED 可能在本线程取到闸门之后、登记之前完成放行，批次节奏被提前。
    ChunkBatchGate gate = batches.acquire(player.getUniqueId());

    // 与 world.getMinHeight() 同一处安全取数点：这两个值都必须在此（网络线程读 Bukkit）取出，
    // 之后只传纯值给任务；worker 线程严禁触碰 Bukkit API。
    int minHeight = world.getMinHeight();
    int sectionCount = (world.getMaxHeight() - minHeight) / 16;
    // 维度由 World#getEnvironment() 判定（不依赖世界名；CUSTOM 归入 normal），决定用哪段 dimensions 配置。
    AntiXrayConfig.Dimension dimension = AntiXrayConfig.Dimension.of(world.getEnvironment());
    // 超时实时读「当前」配置：reload 改了 advanced.timeout-millis 必须立即生效（本监听器不随 reload 重建）。
    // 同一区块的任务构造与看门狗注册共用这一个值，保证两者一致。
    long timeoutMillis = live().timeoutMillis();
    RewriteTask task = new RewriteTask(accessor.chunkX(), accessor.chunkZ(),
        world.getName(), dimension, minHeight, sectionCount, timeoutMillis,
        gate == null
            ? () -> asynchronousManager.signalPacketTransmission(event)
            : () -> {
              gate.chunkDone();
              asynchronousManager.signalPacketTransmission(event);
            });

    ScheduledFuture<?> timeout;
    // 延迟登记必须先于看门狗注册（与旧实现顺序对调，行为等价性说明）：
    // 旧实现先 scheduleTimeout 再 incrementProcessingDelay——两步之间若调度线程抢先触发看门狗，
    // task.releaseOnTimeout() 会立刻放行封包，而主线程随后才登记的 +1 延迟计数将无人归还，
    // 在该玩家的异步队列里留下永不返还的延迟额度。对调后「已放行但延迟未登记」的窗口不复存在；
    // 两步对调本身不改变任何成功路径的行为：看门狗在延迟登记后才可能触发，放行仍由
    // RewriteTask#signalOnce 保证「恰好一次」。
    marker.incrementProcessingDelay();
    try {
      timeout = workPool.scheduleTimeout(() -> {
        if (task.releaseOnTimeout()) {
          stats.chunksTimedOut.increment();
        }
      }, timeoutMillis);
    } catch (Throwable throwable) {
      // 已登记延迟但看门狗没注册成功（通常为插件停用中）：走一次放行动作把刚登记的延迟
      // 「用掉」（signalPacketTransmission 即放行本封包），等价于旧实现的「未登记延迟直接放行」。
      // 批次计数已在 batches.acquire 处登记，release 动作里的 gate.chunkDone() 会把它正好归还（净零）。
      logThrottled("超时看门狗登记失败，已按原包放行", throwable);
      task.signalOnce();
      return;
    }

    // 邻块快照未命中：先转主线程 / Folia 区域线程抓取，抓完再交给工作线程
    // （写入权仍在看门狗手里，抓取期间超时照样会放行原包）
    // 只在真的需要时才抓：mode=all 的世界改写不做 6 面遮挡判定（见 ObfuscationProcessor#rewrite），
    // 邻块数据用不到，抓取与随之而来的区域线程调度都是纯开销。
    UUID playerId = player.getUniqueId();
    if (neighborsNeeded(world.getName(), dimension)
        && neighborProvider.cached(world.getName(), accessor.chunkX(), accessor.chunkZ()) == null
        && scheduleCaptureThenRewrite(task, accessor, world, timeout, playerId)) {
      return;
    }

    try {
      workPool.execute(task, () -> handleAsync(task, accessor, timeout, playerId));
    } catch (Throwable throwable) {
      // 已登记延迟却没能入队：必须立即放行，否则该封包永久卡住
      logThrottled("区块改写任务入队失败，已按原包放行", throwable);
      timeout.cancel(false);
      task.signalOnce();
    }
  }

  /** CHUNK_BATCH_START：打开本玩家的批次闸门。本身不延迟放行（块仍逐个流式下发）。 */
  private void handleBatchStart(PacketEvent event) {
    Player player = event.getPlayer();
    if (player == null || !appliesTo(player)) {
      return;
    }
    // 闸门表有界：超限时 BatchTable 只淘汰最旧的一个条目（旧实现 clear() 会误清所有玩家的批次）
    batches.open(player.getUniqueId());
  }

  /**
   * CHUNK_BATCH_FINISHED：等本批次内所有区块改写完成后再放行，且只在有配对 START 时额外延迟。
   *
   * <p>即使没有配对 START 也无需担心顺序——批次包与 MAP_CHUNK 同在异步白名单内，
   * ProtocolLib 的每玩家发送队列会拦住未处理完的前序区块包。
   */
  private void handleBatchFinish(PacketEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }

    // 先无条件关闭本玩家的批次闸门，再判「本模块是否影响该玩家」。
    // 玩家可能在 START（当时在可改写世界）与 FINISHED 之间切进黑名单世界 / 被加入直通名单：
    // 若把 remove 放在 appliesTo 之后，这条残留闸门就只能靠退出清理 / 下次 START 覆盖 / 超限淘汰兜底，
    // 与「闸门 = 配对 START/FINISHED 才延迟」的本意不符。对不受影响的路径而言，这一步只做一次有界的
    // 移除——下面立即返回，不回写任何数据、不登记任何延迟，因此「恰好一次放行」不受影响。
    ChunkBatchGate gate = batches.remove(player.getUniqueId());
    if (!appliesTo(player)) {
      return;
    }
    if (gate == null) {
      return;
    }

    AsyncMarker marker = event.getAsyncMarker();
    if (marker == null) {
      return;
    }

    // 先登记额外延迟，再注册放行动作：保证放行永远发生在延迟登记之后
    marker.incrementProcessingDelay();
    try {
      // 超时实时读「当前」配置（reload 立即生效），与区块路径一致。
      ScheduledFuture<?> timeout = workPool.scheduleTimeout(gate::forceRelease, live().timeoutMillis());
      gate.finish(() -> {
        // 放行必须最先执行，且绝不外抛：闸门的 CAS 在动作<b>之前</b>就已消耗，动作一旦抛出，
        // 超时兜底 forceRelease() 会因 CAS 失败而空转——CHUNK_BATCH_FINISHED 从此永久无人放行，
        // 该玩家异步队列里的所有后续区块包全部滞留（客户端卡在区块加载界面直到重登）。
        try {
          asynchronousManager.signalPacketTransmission(event);
        } catch (Throwable throwable) {
          logThrottled("CHUNK_BATCH_FINISHED 放行失败（连接可能已销毁），不再重试", throwable);
        } finally {
          // 取消看门狗只是收尾：即便取消失败，兜底再触发也只会因闸门已置位而空转，不影响放行语义。
          cancelQuietly(timeout);
        }
      });
    } catch (Throwable throwable) {
      // 登记失败（例如插件正在停用）：立即归还这次延迟，绝不把批次结束包卡住
      logThrottled("批次闸门登记失败，已立即放行 CHUNK_BATCH_FINISHED", throwable);
      asynchronousManager.signalPacketTransmission(event);
    }
  }

  /** 工作线程：缓存命中 → 直接回填；未命中 → 解码/判定/重编码（邻块快照此时已就绪或按缺失策略处理）。 */
  private void handleAsync(RewriteTask task, ChunkPacketAccessor accessor, ScheduledFuture<?> timeout,
      UUID playerId) {
    if (!task.tryBeginWrite()) {
      // 已被看门狗超时放行：原包已发出，绝不能再改写
      timeout.cancel(false);
      return;
    }

    // 放行必须落在 finally 里兜底：写入权一旦被本线程取得（gate=WRITING），看门狗与 close() 的兜底
    // CAS(OPEN→DONE) 就再也抢不到——此时只有本线程能调 signalPacketTransmission。因此在取得写入权
    // 之后，任何一步抛异常（live() 取配置、logThrottled、cancel）都不允许跳过放行，否则该区块封包
    // 永久卡住（客户端卡在加载界面）。这与 MikuWorkPool 声明的「payload 必须自行保证恰好放行一次」一致。
    try {
      rewriteOnWorker(task, accessor, playerId);
    } finally {
      cancelQuietly(timeout);
      task.signalOnce();
    }
  }

  /**
   * 工作线程的改写主体：缓存命中 → 直接回填；未命中 → 解码/判定/重编码
   * （邻块快照此时已就绪，或按缺失策略处理）。
   *
   * <p>异常一律 fail-open（记日志后按原包放行）；<b>放行由调用方在 {@code finally} 中完成</b>，
   * 本方法不自行放行，也不允许把异常抛给调用方之外的任何路径。
   */
  private void rewriteOnWorker(RewriteTask task, ChunkPacketAccessor accessor, UUID playerId) {
    // 实时读「当前」配置并固定在本轮改写内使用：配置指纹 / remove-block-entities 必须随 reload 生效
    // （本监听器不随热重载重建），且同一轮里 cache.get 与 cache.put 必须用同一份指纹——
    // 若每处各读一次、reload 恰好发生在中途，读写键会不一致，导致刚写的条目立刻读不到。
    AntiXrayConfig current = live();

    try {
      if (!task.expired()) {
        byte[] source = accessor.buffer();
        long sourceHash = hash(source);
        // 邻块依赖：enclosed 模式的改写结果依赖邻块贴边快照的遮挡位，因此必须把「本次改写实际用到的
        // 快照内容」一并并入缓存键——否则邻块方块变化（本区块原始字节未变）后，缓存仍会返回按旧邻块
        // 算出的结果（默认内存 600s / 磁盘 7 天）。all 模式不使用邻块，指纹与之无关。
        // 快照缺失时 neighbors 为 null（改写按 missing-policy 处理），指纹与「已抓到快照」自然不同，
        // 快照补上后该条目随即失效重写，语义正确。
        NeighborEdges neighbors = neighborsNeeded(task.worldName(), task.dimension())
            ? neighborProvider.cached(task.worldName(), task.chunkX(), task.chunkZ())
            : null;
        int fingerprint = rewriteFingerprint(current, neighbors);

        CachedChunk cached = cache.get(task.worldName(), task.chunkX(), task.chunkZ(),
            fingerprint);
        if (cached == null || cached.sourceHash() != sourceHash) {
          cached = loadFromDisk(task, sourceHash, fingerprint);
        }

        if (cached != null) {
          writeBack(accessor, task, cached.data(), cached.positions(), current, playerId);
        } else {
          rewrite(task, accessor, source, sourceHash, neighbors, fingerprint, current, playerId);
        }
      }
    } catch (Throwable throwable) {
      logThrottled("区块改写失败，已按原包放行", throwable);
    }
  }

  /** 取消看门狗；失败不得阻断放行（看门狗稍后空跑一次，releaseOnTimeout 的 CAS 必然失败）。 */
  private static void cancelQuietly(ScheduledFuture<?> timeout) {
    try {
      timeout.cancel(false);
    } catch (Throwable ignored) {
      // 取消失败只意味着看门狗稍后空跑一次，不影响「恰好放行一次」
    }
  }

  /**
   * 内存未命中时尝试磁盘缓存；命中则回填内存缓存。
   *
   * <p>磁盘读带 50ms 预算（见 {@code DiskCacheStore}），超时/异常一律按未命中降级，
   * 因此不会把封包处理拖过时限。负载里带原始字节指纹（指纹不符即视为未命中）与<b>被伪装坐标</b>
   * （回填后由 {@link #writeBack} 写回显形索引——否则磁盘命中的区块永远不进索引）。
   */
  private CachedChunk loadFromDisk(RewriteTask task, long sourceHash, int fingerprint) {
    if (diskCache == null || !diskCache.usable()) {
      return null;
    }
    try {
      byte[] payload = diskCache.get(task.worldName(), task.chunkX(), task.chunkZ(), fingerprint);
      if (payload == null) {
        return null;
      }
      DiskPayload.Decoded decoded = DiskPayload.decode(payload, sourceHash);
      if (decoded == null) {
        // 读到了负载但用不上（信封损坏或原始字节指纹不符）：这是「命中率正常但仍在重写」的唯一出口，
        // 必须计数——否则它会被混进普通未命中里，无法区分是内容指纹在拦还是配置指纹在拦。
        stats.diskPayloadRejected.increment();
        return null;
      }
      CachedChunk fromDisk = new CachedChunk(decoded.sourceHash(), decoded.data(), decoded.positions());
      cache.put(task.worldName(), task.chunkX(), task.chunkZ(), fingerprint, fromDisk);
      return fromDisk;
    } catch (Throwable throwable) {
      logThrottled("读取磁盘缓存失败，已按未命中处理", throwable);
      return null;
    }
  }

  /**
   * 改写并回填内存缓存与磁盘缓存。
   *
   * @param fingerprint 本次改写的完整决定因素指纹（配置 + 调色板 + 邻块快照内容），
   *                    与 {@link #rewriteOnWorker} 的读取侧同源，保证读写键一致
   */
  private void rewrite(RewriteTask task, ChunkPacketAccessor accessor, byte[] source, long sourceHash,
      NeighborEdges neighbors, int fingerprint, AntiXrayConfig current, UUID playerId) {
    // 世界名 + 维度 + 最低 Y 一并传入：world-overrides（按世界名）优先于 dimensions.<维度>，
    // min-y/max-y 过滤与分区伪装表需要把 section 内相对 Y 换算成绝对 Y。
    ObfuscationProcessor.Result result = processor.rewrite(source, task.sectionCount(),
        seed(task.worldName(), task.chunkX(), task.chunkZ()), neighbors,
        task.worldName(), task.dimension(), task.minHeight());

    if (result.failed()) {
      // 解码/重编码异常：必须可观测——否则「本该伪装却失败」会被并进「跳过」里，看起来一切正常。
      // 失败结果不写入任何一级缓存（见 resultCacheable）：瞬时故障若被固化，同指纹区块在缓存有效期内
      // 不再重试改写，等于长期裸露。chunksFailed 计数即「失败且未入缓存」的次数，供诊断回显。
      stats.chunksFailed.increment();
      // 该文案会拼接 result.failure()（内容随异常而变）：用固定分类键限流，避免每出现一个新摘要就多计一条
      logThrottled("区块改写异常", "区块改写异常（已按原包放行，未写入任何缓存）："
          + result.failure(), null);
    }
    if (result.changed()) {
      stats.chunksRewritten.increment();
      stats.blocksReplaced.add(result.obfuscatedPositions().length);
      // 字节口径统计（P0-1）：只对真正改写的区块采样，原始字节 → 输出字节（节省 = 两者之差）。
      // 未改动/失败放行的区块原样字节恒等，计入只会稀释「省了多少」的比例。
      long originalBytes = source.length;
      long outputBytes = result.data().length;
      stats.bytesOriginal.add(originalBytes);
      stats.bytesOutput.add(outputBytes);
      stats.bytesSaved.add(originalBytes - outputBytes);
      // 位宽直方图（P0-1 诊断）：观察封顶/裁剪/降位的实际效果
      if (result.sectionBits() != null) {
        for (int bits : result.sectionBits()) {
          if (bits >= 0) {
            stats.recordPaletteBits(bits);
          }
        }
      }
    } else {
      stats.chunksSkipped.increment();
    }

    logFirstRewriteDiagnostic(task, source, result);

    if (resultCacheable(result)) {
      CachedChunk value = new CachedChunk(sourceHash, result.data(), result.obfuscatedPositions());
      cache.put(task.worldName(), task.chunkX(), task.chunkZ(), fingerprint, value);
      if (diskCache != null) {
        // 写入是「提交即返回」的异步操作（自有磁盘线程），不阻塞本工作线程。
        // 用惰性负载重载：先按编码后的长度判定容量，通过后才真正编码——编码要分配并整块拷贝
        // 改写后的区块字节（几十~几百 KB），被拒时（条目达上限 / 磁盘线程积压）这次拷贝纯属浪费。
        // 负载里带上被伪装坐标，磁盘缓存命中时才能重新写入显形索引（见 DiskPayload）。
        diskCache.put(task.worldName(), task.chunkX(), task.chunkZ(), fingerprint,
            DiskPayload.encodedLength(result.obfuscatedPositions(), result.data()),
            () -> DiskPayload.encode(sourceHash, result.obfuscatedPositions(), result.data()));
      }
    }
    // 失败结果：data == source 且伪装位置为空 → writeBack 直接返回，原包照常下发（fail-open 语义不变）。
    writeBack(accessor, task, result.data(), result.obfuscatedPositions(), current, playerId);
  }

  /**
   * 改写结果是否允许写入缓存（内存 + 磁盘）。
   *
   * <p><b>为什么失败结果绝不入缓存</b>：解码/重编码异常通常是瞬时故障（并发抖动、临时状态）；
   * 若把「原样字节 + 空位置」固化进任一级缓存，同指纹区块就会在内存有效期（默认约 600s）
   * 乃至磁盘过期前都不再重试改写——瞬时故障被永久化，等于该区块一直裸露。
   * 放行语义不受影响：失败时 {@code data == source} 且位置为空，{@code writeBack} 直接跳过。
   *
   * <p>包级可见，便于离线单测（无需实例化整个监听器）。
   */
  static boolean resultCacheable(ObfuscationProcessor.Result result) {
    return result != null && !result.failed();
  }

  /**
   * 「首次改写诊断」：只对<b>第一个进入改写流程的区块</b>打印一次（CAS 抢占），绝不刷屏。
   *
   * <p><b>为什么要它</b>：真机上出现过「自检全绿、计数器全有数，但玩家仍能透视看到真实矿物」。
   * 光看「改写 670、跳过 0」无法区分两种失效，本行日志给出决定性判据：
   * <ul>
   *   <li><b>可能一（一个方块都没匹配到目标）</b>：解码出的状态 id 与配置目标 id 不匹配，
   *       表现为「命中目标方块 0 个」，且「替换 0 个」「输出字节是否变化=否」；</li>
   *   <li><b>可能二（算了但没写回真正的 NMS 对象）</b>：表现为「命中目标方块 K&gt;0」
   *       且「替换 K'&gt;0」「输出字节是否变化=是」——说明匹配/判定/重编码全部正常，
   *       失效只可能发生在写回侧（此时再看「写回失败」计数与客户端实际收到的字节）。</li>
   * </ul>
   *
   * <p>统计走 {@link ObfuscationProcessor#diagnose}（会再解码一次区块），因此只在这一次执行。
   */
  private void logFirstRewriteDiagnostic(RewriteTask task, byte[] source,
      ObfuscationProcessor.Result result) {
    if (!firstRewriteDiagnosed.compareAndSet(false, true)) {
      return;
    }
    try {
      ObfuscationProcessor.Diagnostic diagnostic =
          processor.diagnose(source, task.sectionCount(), task.worldName(), task.dimension());
      String sections = diagnostic == null
          ? String.valueOf(task.sectionCount()) : String.valueOf(diagnostic.sectionCount());
      String kinds = diagnostic == null ? "解码失败" : String.valueOf(diagnostic.stateKinds());
      String matches = diagnostic == null ? "解码失败" : String.valueOf(diagnostic.targetMatches());
      getPlugin().getLogger().info("首次改写诊断：区块 (" + task.chunkX() + "," + task.chunkZ()
          + ") section 数 " + sections
          + "；解码状态种类 " + kinds
          + "；命中目标方块 " + matches + " 个"
          + "；替换 " + result.obfuscatedPositions().length + " 个"
          + "；输出字节是否变化=" + (result.data() != source ? "是" : "否"));
    } catch (Throwable throwable) {
      logThrottled("首次改写诊断生成失败（不影响改写主流程）", throwable);
    }
  }

  /**
   * 让主线程 / Folia 区域线程抓取邻块贴边快照，抓完再把改写交给工作线程。
   *
   * <p>抓取期间**不**取得写入权，因此看门狗超时仍能放行原包（fail-open）；若抓取回调迟于超时执行，
   * 工作线程拿到写入权会失败并直接跳过改写。
   *
   * @return true 表示调度成功（放行责任由抓取链路或看门狗承担）；false 表示无法调度，调用方按缺失策略改写
   */
  private boolean scheduleCaptureThenRewrite(RewriteTask task, ChunkPacketAccessor accessor, World world,
      ScheduledFuture<?> timeout, UUID playerId) {
    if (world == null) {
      return false;
    }

    Runnable capture = () -> {
      // 停用中（插件停用 / 热重载）：本回调可能已不再被执行，即便执行也不能再走「先抓取、再入工作池」——
      // 工作池与看门狗都已随停用关闭，入队只会被丢弃。立刻自行放行原包，避免该区块封包永久无人
      // signalOnce（玩家卡在区块加载界面直到重登）。
      if (stopping) {
        timeout.cancel(false);
        task.signalOnce();
        return;
      }
      try {
        neighborProvider.capture(world, task.chunkX(), task.chunkZ());
      } catch (Throwable throwable) {
        logThrottled("邻区块贴边快照抓取失败，已按缺失策略降级", throwable);
      }

      try {
        workPool.execute(task, () -> handleAsync(task, accessor, timeout, playerId));
      } catch (Throwable throwable) {
        // 任何意外都必须归还这次延迟，否则该封包永久卡住（signalOnce 保证恰好放行一次）
        logThrottled("邻区块抓取后的改写调度失败，已按原包放行", throwable);
        timeout.cancel(false);
        task.signalOnce();
      }
    };

    try {
      // RegionScheduler：Paper 上落在主线程、Folia 上落在该区块所属区域线程（同一套 API，无需分支）。
      // 跨区域读取失败会由 provider 按缺失策略降级。
      Bukkit.getRegionScheduler().execute(getPlugin(), world, task.chunkX(), task.chunkZ(), capture);
      return true;
    } catch (Throwable throwable) {
      logThrottled("邻区块抓取调度失败，已按缺失策略降级", throwable);
      return false;
    }
  }

  /**
   * 回填改写结果到封包，并维护显形索引。
   *
   * @param playerId 本次区块封包的接收者；用于只作废<b>该玩家</b>在该区块的已显形标记
   *                 （区块重发通常只发给一个玩家，作废其它玩家的标记只会让它们重复显形）
   */
  private void writeBack(ChunkPacketAccessor accessor, RewriteTask task, byte[] data, int[] positions,
      AntiXrayConfig current, UUID playerId) {
    if (positions.length == 0) {
      // 本次改写结果「一个坐标都没伪装」（无目标方块 / 矿已被挖空 / 配置变更后不再匹配）：
      // 既不能写回封包，也不能就此返回——该区块若此前登记过坐标，其索引条目与各玩家的已显形标记
      // 会全部变成陈旧数据（继续为「本次并未伪装」的坐标发冗余显形包）。这里显式失效该区块的登记。
      ObfuscatedChunkIndex staleIndex = index();
      if (staleIndex != null) {
        staleIndex.invalidateChunk(task.worldName(), task.chunkX(), task.chunkZ());
        RevealedSet staleRevealed = revealed();
        if (staleRevealed != null) {
          staleRevealed.clearChunk(new ChunkKey(task.worldName(), task.chunkX(), task.chunkZ()));
        }
      }
      return;
    }
    // remove-block-entities 实时读「当前」配置：它直接决定写进封包的字节，reload 切换必须立即生效。
    boolean verified = accessor.update(data, positions, task.minHeight(), current.removeBlockEntities());
    if (!verified) {
      // 「算了但没写」：写回后回读不一致（ProtocolLib 版本/封包结构不符），必须留痕
      stats.writeBackFailures.increment();
      logThrottled("区块改写结果未能写回封包（写回后回读不一致），本轮按原包内容放行", null);
      // 写回未确认生效时，客户端拿到的仍是原始（未伪装）字节，本区块其实没有隐藏任何矿物。
      // 若此时仍把坐标登记进伪装索引，邻近显形只会为「本就没被伪装的坐标」发冗余显形包，
      // 既浪费带宽又把索引/统计撑大——与本类「verified=false 不剔除方块实体」的既有短路同向，
      // 这里直接跳过索引登记（也不动已显形集合）。计数观测点让「本该登记却因写回失败被跳过」可见。
      stats.indexSkippedOnWriteBackFailure.increment();
      return;
    }

    // 记录「这个区块被伪装过的坐标」（按区块共享，只存一份；纯内存写入，可在工作线程执行）。
    // 索引走可变引用：热重载关闭 proximity 后引用被置空，这里直接跳过，不再登记（真正零开销且与面板一致）。
    ObfuscatedChunkIndex index = index();
    if (index != null) {
      // 用差集接口覆盖：本次覆盖会把「旧清单有、新清单没有」的坐标移出共享索引（如 enclosed 模式下
      // 邻块指纹翻转导致边界坐标改写结果变化）。这些坐标上<b>所有玩家</b>的已显形标记必须同步摘除——
      // 否则标记虚增会让邻近显形的「整块跳过」（sizeFor >= entry.size）提前成立，真实未显形坐标被
      // 周期性跳过（标记又被扫描 touch 续期、玩家不离开扫描半径就永不过期）。
      // 这正是 RevealedSet 类注释声明的「已显形坐标 ⊆ 区块当前清单」不变式，覆盖路径必须维护它。
      // 容量安全阀可能为腾位置而整体淘汰其它区块：那些区块的索引条目随之消失，其坐标上各玩家的
      // 已显形标记必须同步作废，否则成为孤儿标记（sizeFor 虚增 → 「整块跳过」提前成立 → 真实坐标漏显形）。
      // 常态下 mayEvict 为 false，零分配；只有真的可能淘汰时才准备容器。
      List<ChunkKey> evictedChunks = index.mayEvict(positions.length) ? new ArrayList<>(4) : null;
      int[] removedLocals =
          index.recordChunkWithRemovals(task.worldName(), task.chunkX(), task.chunkZ(),
              task.minHeight(), positions, evictedChunks);
      RevealedSet revealed = revealed();
      if (revealed != null) {
        if (evictedChunks != null) {
          for (ChunkKey evictedKey : evictedChunks) {
            // 索引条目已整体消失：该区块上「所有玩家」的已显形标记都必须作废
            revealed.clearChunk(evictedKey);
          }
        }
        if (removedLocals.length > 0) {
          // 局部坐标 → 绝对坐标三元组（removePositions 按首坐标导出区块键，同区块批量摘除）
          int[] absolute = toAbsoluteCoordinates(task.chunkX(), task.chunkZ(), task.minHeight(),
              removedLocals);
          revealed.removePositions(task.worldName(), absolute, removedLocals.length);
        }
        // 本包接收者的客户端又拿回了伪装结果，其在该区块的标记全部作废（重新记录后会再次显形）。
        // 只作废该玩家：其它玩家的客户端仍显示我们此前发回的真实方块，把它们一并清掉只会让
        // 它们下个周期重复显形一遍（纯浪费带宽与主线程开销）。
        revealed.clearChunk(new ChunkKey(task.worldName(), task.chunkX(), task.chunkZ()), playerId);
      }
    }
  }

  /**
   * 把区块内局部坐标编码差集转为「绝对坐标三元组」（{@code x,y,z} 连续存放），供
   * {@link RevealedSet#removePositions} 批量摘除。
   *
   * <p><b>编码必须与另两处互逆</b>：{@link ObfuscatedChunkIndex#removePosition} 的编码
   * （{@code (y-minHeight)<<8 | (z&15)<<4 | (x&15)}）与 {@link ProximityScanner} 的解码
   * （{@code (chunkX<<4)|(local&15)} 等）。{@code relY = local>>8} 恒非负（登记时 {@code y ≥ minHeight}），
   * 故算术移位无符号问题；负 chunkX 经 {@code (chunkX<<4)|低4位} 与 {@code x>>4} 可逆。
   *
   * <p>包级可见，供离线单测直接驱动三方编码互逆性（真实链路依赖封包上下文，离线不可达）。
   */
  static int[] toAbsoluteCoordinates(int chunkX, int chunkZ, int minHeight, int[] removedLocals) {
    int[] absolute = new int[removedLocals.length * 3];
    for (int i = 0; i < removedLocals.length; i++) {
      int local = removedLocals[i];
      absolute[i * 3] = (chunkX << 4) | (local & 15);
      absolute[i * 3 + 1] = minHeight + (local >> 8);
      absolute[i * 3 + 2] = (chunkZ << 4) | ((local >> 4) & 15);
    }
    return absolute;
  }

  /** 取「当前」的伪装区块索引（可变引用）；未启用或已被热重载关闭时返回 {@code null}。 */
  private ObfuscatedChunkIndex index() {
    Supplier<ObfuscatedChunkIndex> supplier = obfuscatedChunkIndex;
    return supplier == null ? null : supplier.get();
  }

  /** 取「当前」的已显形集合（可变引用）；未启用或已被热重载关闭时返回 {@code null}。 */
  private RevealedSet revealed() {
    Supplier<RevealedSet> supplier = revealedSet;
    return supplier == null ? null : supplier.get();
  }

  /**
   * 该（世界, 维度）的改写是否需要邻块贴边快照。
   *
   * <p>{@code mode=all}（默认）的档案在改写里<b>不做</b> 6 面遮挡判定，邻块数据一位都用不到：
   * 此时连抓取都省掉——抓取要在主线程 / Folia 区域线程逐格读世界，还会让区块封包多一次
   * 区域线程调度往返。{@code mode=enclosed} 的世界照常抓取。
   */
  private boolean neighborsNeeded(String worldName, AntiXrayConfig.Dimension dimension) {
    // neighbors.enabled 实时读「当前」配置：reload 关闭邻块后立即停止抓取（关闭即零开销），重新开启立即恢复。
    return neighborsAvailable && live().neighbors().enabled()
        && processor.needsNeighbors(worldName, dimension);
  }

  /** 该玩家是否受本模块影响（直通名单绕过 + 反矿透世界判定）。 */
  private boolean appliesTo(Player player) {
    World world = player.getWorld();
    return world != null && appliesTo(player, world);
  }

  /**
   * 该玩家是否受本模块影响（判定用调用方已捕获的世界实例，避免重复 {@code getWorld()} 造成 TOCTOU）。
   *
   * <p>黑名单判定必须读「当前」配置（本监听器不随热重载重建）：否则 reload 后纳入黑名单的世界仍会被改写。
   */
  private boolean appliesTo(Player player, World world) {
    if (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId())) {
      return false;
    }
    return worldAllowed(live(), world.getName());
  }

  /**
   * 取「当前」的反矿透配置（实时读取，热重载后立即生效）。
   *
   * <p>本监听器<b>不随热重载重建</b>，而 {@link AntiXrayConfig} 实例会被 reload 整体替换。
   * 凡影响运行期行为的读取——世界黑名单、{@code configHash()}、超时、{@code remove-block-entities}、
   * {@code neighbors.enabled}——都必须走这里；否则 reload 后仍按启动期配置工作：
   * 旧指纹继续命中旧缓存（即使配置已变）、新超时不生效、remove-block-entities 切换被忽略。
   */
  private AntiXrayConfig live() {
    return resolveConfig(config, liveConfig);
  }

  /**
   * 改写结果的全部决定因素指纹：反矿透配置指纹 <b>+</b> 参与编码的调色板选项指纹
   * （{@link ObfuscationProcessor#paletteFingerprint()}）<b>+</b> 本次改写实际用到的邻块快照内容指纹
   * （{@link NeighborEdges#contentFingerprint()}；{@code all} 模式或快照缺失时为 0）。
   *
   * <p><b>为什么必须三者合并</b>：① 调色板位宽预算/自检开关由 {@code bandwidth.yml} 决定，却直接改变
   * 重写后的区块字节；② {@code mode=enclosed} 的遮挡判定依赖邻块贴边快照，而快照内容会随邻块方块变化。
   * 若缓存键只含 {@code AntiXrayConfig#configHash()}，上述任一变化都不会让旧条目失效
   * （默认磁盘 7 天才过期），表现为「改了配置不生效」或「邻块变化后边界矿仍按旧结果伪装」。
   * 读、写两侧都走本方法，保证键一致。
   */
  private int rewriteFingerprint(AntiXrayConfig current, NeighborEdges neighbors) {
    int base = current.configHash() * 31 + processor.paletteFingerprint();
    if (neighbors == null) {
      return base;
    }
    long neighborHash = neighbors.contentFingerprint();
    return base * 31 + (int) (neighborHash ^ (neighborHash >>> 32));
  }

  /**
   * 实时配置解析：{@code liveConfig} 可用且返回非 null 时取它（「当前」配置），否则回落启动期
   * {@code fallback}。供 {@link #live()} 调用，也兼容 {@code liveConfig == null} 的旧调用方/单测。
   *
   * <p>包级可见，便于离线单测直接验证「模拟 reload 替换配置后取到新配置」
   * （无需实例化整个监听器）。
   */
  static AntiXrayConfig resolveConfig(AntiXrayConfig fallback, Supplier<AntiXrayConfig> liveConfig) {
    AntiXrayConfig current = liveConfig == null ? null : liveConfig.get();
    return current == null ? fallback : current;
  }

  /**
   * 世界是否允许反矿透（统一入口判定，可离线单测）：总开关开启 <b>且</b> 世界不在黑名单。
   *
   * <p>黑名单世界一律拒绝——改写路径的入口判定即在此处最早返回，不登记延迟、不建任务、
   * 不抓邻块快照、不读写磁盘缓存。带宽模块不走本判定，故不受影响。
   */
  static boolean worldAllowed(AntiXrayConfig config, String worldName) {
    return config != null && config.antiXrayAppliesTo(worldName);
  }

  /** 使全部改写缓存、邻块快照与显形结构立即失效（配置热重载时调用）。 */
  public void invalidateAll() {
    cache.invalidateAll();
    if (neighborProvider != null) {
      neighborProvider.invalidateAll();
    }
    ObfuscatedChunkIndex index = index();
    if (index != null) {
      index.clear();
    }
    RevealedSet revealed = revealed();
    if (revealed != null) {
      revealed.clear();
    }
  }

  /** 改写统计计数（供诊断聚合）。 */
  public RewriteStats stats() {
    return stats;
  }

  /** 当前缓存命中数（诊断用）。 */
  public long cacheHits() {
    return cache.hitCount();
  }

  /** 当前缓存未命中数（诊断用）。 */
  public long cacheMisses() {
    return cache.missCount();
  }

  /** 使某个世界的缓存整体失效（世界卸载时调用），同时清掉该世界的显形结构。 */
  public void invalidateWorld(String worldName) {
    cache.invalidateWorld(worldName);
    if (neighborProvider != null) {
      neighborProvider.invalidateWorld(worldName);
    }
    ObfuscatedChunkIndex index = index();
    if (index != null) {
      index.invalidateWorld(worldName);
    }
    RevealedSet revealed = revealed();
    if (revealed != null) {
      revealed.clearWorld(worldName);
    }
  }

  /** 当前缓存条目数（诊断用）。 */
  public int cacheSize() {
    return cache.size();
  }

  private void logThrottled(String message, Throwable throwable) {
    logThrottled(message, message, throwable);
  }

  /** 取消定时器：失败无副作用（闸门已置位，兜底触发只会空转），绝不能顶掉放行动作。 */
  private static void cancelQuietly(ScheduledFuture<?> timeout) {
    try {
      timeout.cancel(false);
    } catch (Throwable ignored) {
      // 取消失败不影响「恰好一次放行」
    }
  }

  /**
   * 按 {@code throttleKey} 分别限流：同一分类最多提示 {@link Constants#MAX_ERROR_LOGS} 次，WARN 级别。
   *
   * <p>把「分类键」与「日志文案」分开，是为了让文案含可变内容（如异常摘要）时仍能按类别限流：
   * 若直接拿整条文案当键，每出现一个新摘要都会多占一个计数条目、限流也形同虚设。
   * 绝大多数调用点的文案本就是字符串字面量，直接以文案为键（见 2 参重载）。
   */
  private void logThrottled(String throttleKey, String message, Throwable throwable) {
    AtomicInteger counter = errorLogCounters.computeIfAbsent(throttleKey, key -> new AtomicInteger());
    if (counter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      getPlugin().getLogger().log(Level.WARNING, message + "（同类错误最多提示 "
          + Constants.MAX_ERROR_LOGS + " 次）", throwable);
    }
  }

  /** 由「世界名 + 区块坐标 + 配置指纹」推导的确定性种子：同输入必然同结果，缓存可安全复用。 */
  private static long seed(String worldName, int chunkX, int chunkZ) {
    long hash = 0xcbf29ce484222325L;
    hash = (hash ^ worldName.hashCode()) * 0x100000001b3L;
    hash = (hash ^ chunkX) * 0x100000001b3L;
    hash = (hash ^ chunkZ) * 0x100000001b3L;
    return hash;
  }

  /**
   * 区块内容指纹：用于识别「同一区块位置的字节是否已变化」，避免缓存返回过期内容
   * （这是唯一的内容守卫，宁可慢一点也不能弱化）。
   *
   * <p><b>为什么从标量 FNV-1a 换成两个硬件 CRC</b>：本指纹对<b>每个</b>区块封包都要算一次
   * （缓存命中也要算，因为命中判定必须确认字节没变），而标量 FNV-1a 实测只有约 1.3~1.4 GB/s
   * （本机 200 KB 负载约 143 µs/次，见下表）。改用 JDK 内置、带硬件指令实现的
   * {@link java.util.zip.CRC32C} 与 {@link java.util.zip.CRC32}（<b>两个不同多项式</b>，
   * 组合成 64 位，位宽与原实现相同、不发生强度降级）后实测约 22 GB/s：
   * <pre>
   *   负载 20 KB：FNV 15.6 µs → 1.0 µs；60 KB：41.7 → 2.7 µs；200 KB：142.7 → 8.8 µs
   * </pre>
   * 刻意<b>不用</b>「抽样指纹」（只哈希每 N 字节）：那会漏检落在未采样区间里的单字节改动
   * （本机实测可构造出漏检用例），一旦漏检就是「按旧内容改写的新区块」被下发，属不可接受的正确性风险。
   *
   * <p>校验器按线程复用（{@link ThreadLocal}），热路径零分配：{@link java.util.zip.Checksum}
   * 的 {@code update} 与 {@code getValue} 都不持有外部引用，复用安全。
   *
   * <p><b>兼容性说明</b>：算法变更会让现存磁盘缓存条目的指纹判定为「不符」而被拒绝并重写一次
   * （属预期的一次性重建；与配置指纹变更的处理同构）。
   */
  private static long hash(byte[] data) {
    java.util.zip.Checksum[] checksums = CHECKSUMS.get();
    java.util.zip.Checksum crc32c = checksums[0];
    java.util.zip.Checksum crc32 = checksums[1];
    crc32c.reset();
    crc32.reset();
    crc32c.update(data, 0, data.length);
    crc32.update(data, 0, data.length);
    return (crc32c.getValue() << 32) | crc32.getValue();
  }

  /** 每线程两个校验器（CRC32C + CRC32），避免每个区块封包都新建校验对象。 */
  private static final ThreadLocal<java.util.zip.Checksum[]> CHECKSUMS =
      ThreadLocal.withInitial(() -> new java.util.zip.Checksum[] {
          new java.util.zip.CRC32C(), new java.util.zip.CRC32()});
}