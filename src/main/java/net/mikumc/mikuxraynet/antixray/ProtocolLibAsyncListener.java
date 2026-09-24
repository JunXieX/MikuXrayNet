package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * ProtocolLib 异步区块监听器：反矿透唯一的封包拦截入口。
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

    private final int maxPending;
    private final LinkedHashMap<UUID, ChunkBatchGate> gates = new LinkedHashMap<>();

    BatchTable() {
      this(MAX_PENDING_BATCHES);
    }

    /** 测试用构造：可指定容量上限。 */
    BatchTable(int maxPending) {
      this.maxPending = Math.max(1, maxPending);
    }

    /** 取某玩家当前未配对的闸门；无则返回 {@code null}。 */
    synchronized ChunkBatchGate get(UUID playerId) {
      return gates.get(playerId);
    }

    /** 打开某玩家的批次闸门；已达上限时先淘汰最旧的未完成条目（只清一个）。 */
    synchronized void open(UUID playerId) {
      while (gates.size() >= maxPending) {
        Iterator<UUID> iterator = gates.keySet().iterator();
        if (!iterator.hasNext()) {
          break;
        }
        iterator.next();
        iterator.remove();
      }
      gates.put(playerId, new ChunkBatchGate());
    }

    /** 移除并返回某玩家的闸门（批次结束 / 玩家退出时调用）。 */
    synchronized ChunkBatchGate remove(UUID playerId) {
      return gates.remove(playerId);
    }

    synchronized int size() {
      return gates.size();
    }

    /** 清空全部闸门（停用时调用；此时不会再有批次包到达）。 */
    synchronized void clear() {
      gates.clear();
    }
  }

  private final AntiXrayConfig config;
  private final ObfuscationProcessor processor;
  private final MikuWorkPool workPool;
  private final AsynchronousManager asynchronousManager;
  private final RewriteCache<CachedChunk> cache;
  private final NeighborChunkProvider neighborProvider;
  private final boolean neighborsEnabled;
  private final boolean handleChunkBatch;
  private final ObfuscatedChunkIndex obfuscatedChunkIndex;
  private final RevealedSet revealedSet;
  private final BypassRegistry bypassRegistry;
  private final DiskCacheStore diskCache;
  private final RewriteStats stats = new RewriteStats();
  private final BatchTable batches = new BatchTable();
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
  private final AtomicInteger errorLogs = new AtomicInteger();
  /** 「首次改写诊断」只打一次（CAS 抢占），避免每区块刷屏。 */
  private final AtomicBoolean firstRewriteDiagnosed = new AtomicBoolean();

  /**
   * @param neighborProvider 邻区块贴边快照提供者；仅在 {@code neighbors.enabled} 时被使用
   * @param handleChunkBatch 是否拦截 1.20.2+ 的区块批量包（不可用时由装配方降级为 false）
   * @param obfuscatedChunkIndex 伪装区块索引；{@code null} 表示不做邻近显形
   * @param revealedSet      已显形集合；{@code null} 表示不做邻近显形
   * @param bypassRegistry   直通名单；{@code null} 表示退化为无直通（仍按世界范围判定）
   * @param diskCache        磁盘缓存；{@code null} 表示只用内存缓存
   */
  public ProtocolLibAsyncListener(Plugin plugin, AntiXrayConfig config, ObfuscationProcessor processor,
      MikuWorkPool workPool, AsynchronousManager asynchronousManager,
      NeighborChunkProvider neighborProvider, boolean handleChunkBatch,
      ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet, BypassRegistry bypassRegistry,
      DiskCacheStore diskCache) {
    super(plugin, ListenerPriority.NORMAL, packetTypes(handleChunkBatch));
    this.config = config;
    this.processor = processor;
    this.workPool = workPool;
    this.asynchronousManager = asynchronousManager;
    this.cache = new RewriteCache<>(config.cacheMaximumSize(), config.cacheExpireAfterAccessSeconds());
    this.neighborProvider = neighborProvider;
    this.neighborsEnabled = neighborProvider != null && config.neighbors().enabled();
    this.handleChunkBatch = handleChunkBatch;
    this.obfuscatedChunkIndex = obfuscatedChunkIndex;
    this.revealedSet = revealedSet;
    this.bypassRegistry = bypassRegistry;
    this.diskCache = diskCache;
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
    if (event.isCancelled() || !processor.isActive()) {
      return;
    }

    PacketType type = event.getPacketType();
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
    if (player == null || !appliesTo(player)) {
      return;
    }

    AsyncMarker marker = event.getAsyncMarker();
    if (marker == null) {
      return;
    }
    // 队列满：直接放行原包，不登记延迟
    if (!workPool.hasCapacity()) {
      return;
    }

    World world = player.getWorld();
    ChunkPacketAccessor accessor;
    try {
      accessor = new ChunkPacketAccessor(event.getPacket());
    } catch (Throwable throwable) {
      logThrottled("区块封包结构解析失败，已按原包放行", throwable);
      return;
    }

    ChunkBatchGate gate = batches.get(player.getUniqueId());

    int minHeight = world.getMinHeight();
    int sectionCount = (world.getMaxHeight() - minHeight) / 16;
    RewriteTask task = new RewriteTask(accessor.chunkX(), accessor.chunkZ(),
        world.getName(), minHeight, sectionCount, config.timeoutMillis(),
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
      }, config.timeoutMillis());
    } catch (Throwable throwable) {
      // 已登记延迟但看门狗没注册成功（通常为插件停用中）：走一次放行动作把刚登记的延迟
      // 「用掉」（signalPacketTransmission 即放行本封包），等价于旧实现的「未登记延迟直接放行」。
      // 此时批次计数尚未登记（chunkStarted 在下一步才调用），release 动作里的 gate.chunkDone()
      // 会被 ChunkBatchGate 的 max(0, ...) 防护钳回 0，无副作用。
      logThrottled("超时看门狗登记失败，已按原包放行", throwable);
      task.signalOnce();
      return;
    }

    if (gate != null) {
      gate.chunkStarted();
    }

    // 邻块快照未命中：先转主线程 / Folia 区域线程抓取，抓完再交给工作线程
    // （写入权仍在看门狗手里，抓取期间超时照样会放行原包）
    if (neighborsEnabled
        && neighborProvider.cached(world.getName(), accessor.chunkX(), accessor.chunkZ()) == null
        && scheduleCaptureThenRewrite(task, accessor, world, timeout)) {
      return;
    }

    try {
      workPool.execute(task, () -> handleAsync(task, accessor, timeout));
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
    if (player == null || !appliesTo(player)) {
      return;
    }

    ChunkBatchGate gate = batches.remove(player.getUniqueId());
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
      ScheduledFuture<?> timeout = workPool.scheduleTimeout(gate::forceRelease, config.timeoutMillis());
      gate.finish(() -> {
        timeout.cancel(false);
        asynchronousManager.signalPacketTransmission(event);
      });
    } catch (Throwable throwable) {
      // 登记失败（例如插件正在停用）：立即归还这次延迟，绝不把批次结束包卡住
      logThrottled("批次闸门登记失败，已立即放行 CHUNK_BATCH_FINISHED", throwable);
      asynchronousManager.signalPacketTransmission(event);
    }
  }

  /** 工作线程：缓存命中 → 直接回填；未命中 → 解码/判定/重编码（邻块快照此时已就绪或按缺失策略处理）。 */
  private void handleAsync(RewriteTask task, ChunkPacketAccessor accessor, ScheduledFuture<?> timeout) {
    if (!task.tryBeginWrite()) {
      // 已被看门狗超时放行：原包已发出，绝不能再改写
      timeout.cancel(false);
      return;
    }

    try {
      if (!task.expired()) {
        byte[] source = accessor.buffer();
        long sourceHash = hash(source);

        CachedChunk cached = cache.get(task.worldName(), task.chunkX(), task.chunkZ(),
            config.configHash());
        if (cached == null || cached.sourceHash() != sourceHash) {
          cached = loadFromDisk(task, sourceHash);
        }

        if (cached != null) {
          writeBack(accessor, task, cached.data(), cached.positions());
        } else {
          NeighborEdges neighbors = neighborsEnabled
              ? neighborProvider.cached(task.worldName(), task.chunkX(), task.chunkZ())
              : null;
          rewrite(task, accessor, source, sourceHash, neighbors);
        }
      }
    } catch (Throwable throwable) {
      logThrottled("区块改写失败，已按原包放行", throwable);
    }

    timeout.cancel(false);
    task.signalOnce();
  }

  /**
   * 内存未命中时尝试磁盘缓存；命中则回填内存缓存。
   *
   * <p>磁盘读带 50ms 预算（见 {@code DiskCacheStore}），超时/异常一律按未命中降级，
   * 因此不会把封包处理拖过时限。负载里带原始字节指纹（指纹不符即视为未命中）与<b>被伪装坐标</b>
   * （回填后由 {@link #writeBack} 写回显形索引——否则磁盘命中的区块永远不进索引）。
   */
  private CachedChunk loadFromDisk(RewriteTask task, long sourceHash) {
    if (diskCache == null || !diskCache.usable()) {
      return null;
    }
    try {
      DiskPayload.Decoded decoded = DiskPayload.decode(
          diskCache.get(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash()),
          sourceHash);
      if (decoded == null) {
        return null;
      }
      CachedChunk fromDisk = new CachedChunk(decoded.sourceHash(), decoded.data(), decoded.positions());
      cache.put(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash(), fromDisk);
      return fromDisk;
    } catch (Throwable throwable) {
      logThrottled("读取磁盘缓存失败，已按未命中处理", throwable);
      return null;
    }
  }

  /** 改写并回填内存缓存与磁盘缓存。 */
  private void rewrite(RewriteTask task, ChunkPacketAccessor accessor, byte[] source, long sourceHash,
      NeighborEdges neighbors) {
    ObfuscationProcessor.Result result = processor.rewrite(source, task.sectionCount(),
        seed(task.worldName(), task.chunkX(), task.chunkZ()), neighbors);

    if (result.failed()) {
      // 解码/重编码异常：必须可观测——否则「本该伪装却失败」会被并进「跳过」里，看起来一切正常
      stats.chunksFailed.increment();
      logThrottled("区块改写异常（已按原包放行）：" + result.failure(), null);
    }
    if (result.changed()) {
      stats.chunksRewritten.increment();
      stats.blocksReplaced.add(result.obfuscatedPositions().length);
    } else {
      stats.chunksSkipped.increment();
    }

    logFirstRewriteDiagnostic(task, source, result);

    CachedChunk value = new CachedChunk(sourceHash, result.data(), result.obfuscatedPositions());
    cache.put(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash(), value);
    if (diskCache != null) {
      // 磁盘写入是「提交即返回」的异步操作（自有磁盘线程），不阻塞本工作线程；
      // 负载里带上被伪装坐标，磁盘缓存命中时才能重新写入显形索引（见 DiskPayload）。
      diskCache.put(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash(),
          DiskPayload.encode(sourceHash, result.obfuscatedPositions(), result.data()));
    }
    writeBack(accessor, task, value.data(), value.positions());
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
      ObfuscationProcessor.Diagnostic diagnostic = processor.diagnose(source, task.sectionCount());
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
      ScheduledFuture<?> timeout) {
    if (world == null) {
      return false;
    }

    Runnable capture = () -> {
      try {
        neighborProvider.capture(world, task.chunkX(), task.chunkZ());
      } catch (Throwable throwable) {
        logThrottled("邻区块贴边快照抓取失败，已按缺失策略降级", throwable);
      }

      try {
        workPool.execute(task, () -> handleAsync(task, accessor, timeout));
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

  private void writeBack(ChunkPacketAccessor accessor, RewriteTask task, byte[] data, int[] positions) {
    if (positions.length == 0) {
      return;
    }
    if (!accessor.update(data, positions, task.minHeight(), config.removeBlockEntities())) {
      // 「算了但没写」：setBuffer 回读不一致（ProtocolLib 版本/封包结构不符），必须留痕
      stats.writeBackFailures.increment();
      logThrottled("区块改写结果未能写回封包（setBuffer 回读不一致），本轮按原包内容放行", null);
    }

    // 记录「这个区块被伪装过的坐标」（按区块共享，只存一份；纯内存写入，可在工作线程执行）
    if (obfuscatedChunkIndex != null) {
      obfuscatedChunkIndex.recordChunk(task.worldName(), task.chunkX(), task.chunkZ(),
          task.minHeight(), positions);
      if (revealedSet != null) {
        // 区块被重新下发 → 客户端又拿回了伪装结果，该区块的已显形标记必须作废（与旧行为一致：
        // 重新记录后这些坐标会再次被显形）
        revealedSet.clearChunk(new ChunkKey(task.worldName(), task.chunkX(), task.chunkZ()));
      }
    }
  }

  /** 该玩家是否受本模块影响（直通名单绕过 + 世界范围）。 */
  private boolean appliesTo(Player player) {
    if (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId())) {
      return false;
    }
    return config.appliesTo(player.getWorld().getName());
  }

  /** 使全部改写缓存、邻块快照与显形结构立即失效（配置热重载时调用）。 */
  public void invalidateAll() {
    cache.invalidateAll();
    if (neighborProvider != null) {
      neighborProvider.invalidateAll();
    }
    if (obfuscatedChunkIndex != null) {
      obfuscatedChunkIndex.clear();
    }
    if (revealedSet != null) {
      revealedSet.clear();
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
    if (obfuscatedChunkIndex != null) {
      obfuscatedChunkIndex.invalidateWorld(worldName);
    }
    if (revealedSet != null) {
      revealedSet.clearWorld(worldName);
    }
  }

  /** 当前缓存条目数（诊断用）。 */
  public int cacheSize() {
    return cache.size();
  }

  private void logThrottled(String message, Throwable throwable) {
    if (errorLogs.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
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

  /** FNV-1a 64：用于识别「同一区块位置的字节内容是否已变化」，避免缓存返回过期内容。 */
  private static long hash(byte[] data) {
    long hash = 0xcbf29ce484222325L;
    for (byte value : data) {
      hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
    }
    return hash;
  }
}