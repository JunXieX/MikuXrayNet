package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.cache.DiskCacheStore;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.concurrency.RewriteTask;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
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

  private static final int MAX_ERROR_LOGS = 3;
  /** 未配对的批次闸门数量上限（异常情况下防止无界增长）。 */
  private static final int MAX_PENDING_BATCHES = 256;

  /** 缓存值：源字节指纹 + 改写结果（不含任何封包或世界引用）。 */
  private record CachedChunk(long sourceHash, byte[] data, int[] positions) {
  }

  private final AntiXrayConfig config;
  private final ObfuscationProcessor processor;
  private final MikuWorkPool workPool;
  private final AsynchronousManager asynchronousManager;
  private final RewriteCache<CachedChunk> cache;
  private final NeighborChunkProvider neighborProvider;
  private final boolean neighborsEnabled;
  private final boolean handleChunkBatch;
  private final RevealedBlockIndex revealedIndex;
  private final BypassRegistry bypassRegistry;
  private final DiskCacheStore diskCache;
  private final RewriteStats stats = new RewriteStats();
  private final ConcurrentHashMap<UUID, ChunkBatchGate> batches = new ConcurrentHashMap<>();
  private final AtomicInteger errorLogs = new AtomicInteger();

  /**
   * @param neighborProvider 邻区块贴边快照提供者；仅在 {@code neighbors.enabled} 时被使用
   * @param handleChunkBatch 是否拦截 1.20.2+ 的区块批量包（不可用时由装配方降级为 false）
   * @param revealedIndex    显形索引；{@code null} 表示不做邻近显形
   * @param bypassRegistry   直通名单；{@code null} 表示退化为无直通（仍按世界范围判定）
   * @param diskCache        磁盘缓存；{@code null} 表示只用内存缓存
   */
  public ProtocolLibAsyncListener(Plugin plugin, AntiXrayConfig config, ObfuscationProcessor processor,
      MikuWorkPool workPool, AsynchronousManager asynchronousManager,
      NeighborChunkProvider neighborProvider, boolean handleChunkBatch,
      RevealedBlockIndex revealedIndex, BypassRegistry bypassRegistry, DiskCacheStore diskCache) {
    super(plugin, ListenerPriority.NORMAL, packetTypes(handleChunkBatch));
    this.config = config;
    this.processor = processor;
    this.workPool = workPool;
    this.asynchronousManager = asynchronousManager;
    this.cache = new RewriteCache<>(config.cacheMaximumSize(), config.cacheExpireAfterAccessSeconds());
    this.neighborProvider = neighborProvider;
    this.neighborsEnabled = neighborProvider != null && config.neighbors().enabled();
    this.handleChunkBatch = handleChunkBatch;
    this.revealedIndex = revealedIndex;
    this.bypassRegistry = bypassRegistry;
    this.diskCache = diskCache;
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
    RewriteTask task = new RewriteTask(player.getUniqueId(), accessor.chunkX(), accessor.chunkZ(),
        world.getName(), minHeight, sectionCount, config.timeoutMillis(),
        gate == null
            ? () -> asynchronousManager.signalPacketTransmission(event)
            : () -> {
              gate.chunkDone();
              asynchronousManager.signalPacketTransmission(event);
            });

    ScheduledFuture<?> timeout;
    try {
      timeout = workPool.scheduleTimeout(() -> {
        if (task.releaseOnTimeout()) {
          stats.chunksTimedOut.increment();
        }
      }, config.timeoutMillis());
    } catch (Throwable throwable) {
      // 尚未登记延迟，直接放行原包
      logThrottled("超时看门狗登记失败，已按原包放行", throwable);
      return;
    }

    // 延迟登记之后不再有任何可能失败的步骤，避免「登记了延迟却无人放行」
    marker.incrementProcessingDelay();
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
      workPool.execute(() -> handleAsync(task, accessor, timeout));
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
    if (batches.size() > MAX_PENDING_BATCHES) {
      // 异常情况（例如玩家在批次中途掉线）下的兜底：丢弃陈旧闸门，避免无界增长
      batches.clear();
    }
    batches.put(player.getUniqueId(), new ChunkBatchGate());
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
   * 因此不会把封包处理拖过时限。负载里带原始字节指纹，指纹不符即视为未命中。
   */
  private CachedChunk loadFromDisk(RewriteTask task, long sourceHash) {
    if (diskCache == null || !diskCache.usable()) {
      return null;
    }
    try {
      CachedChunk fromDisk = decodeDiskPayload(
          diskCache.get(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash()),
          sourceHash);
      if (fromDisk != null) {
        cache.put(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash(), fromDisk);
      }
      return fromDisk;
    } catch (Throwable throwable) {
      logThrottled("读取磁盘缓存失败，已按未命中处理", throwable);
      return null;
    }
  }

  /** 改写并回填内存缓存与磁盘缓存。 */
  private void rewrite(RewriteTask task, ChunkPacketAccessor accessor, byte[] source, long sourceHash,
      NeighborEdges neighbors) {
    task.markDecoded();
    ObfuscationProcessor.Result result = processor.rewrite(source, task.sectionCount(),
        seed(task.worldName(), task.chunkX(), task.chunkZ()), neighbors);
    task.markEncoded();

    if (result.failed()) {
      // 解码/重编码异常：必须可观测——否则「本该伪装却失败」会被并进「跳过」里，看起来一切正常
      stats.chunksFailed.increment();
      logThrottled("区块改写异常（已按原包放行）：" + result.failure(), null);
    }
    if (result.changed()) {
      stats.chunksRewritten.increment();
    } else {
      stats.chunksSkipped.increment();
    }

    CachedChunk value = new CachedChunk(sourceHash, result.data(), result.obfuscatedPositions());
    cache.put(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash(), value);
    if (diskCache != null) {
      // 磁盘写入是「提交即返回」的异步操作（自有磁盘线程），不阻塞本工作线程
      diskCache.put(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash(),
          encodeDiskPayload(sourceHash, result.obfuscatedPositions(), result.data()));
    }
    writeBack(accessor, task, value.data(), value.positions());
  }

  /**
   * 磁盘缓存负载编码：{@code [i64 原始字节指纹][i32 伪装坐标数][i32… 伪装坐标][改写后的 section 字节]}。
   *
   * <p>指纹用于识别「同一区块位置的封包字节是否变了」；伪装坐标用于回填时剔除方块实体，
   * 也用于重新写入显形索引。
   */
  private static byte[] encodeDiskPayload(long sourceHash, int[] positions, byte[] data) {
    ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + positions.length * Integer.BYTES + data.length);
    buffer.putLong(sourceHash);
    buffer.putInt(positions.length);
    for (int position : positions) {
      buffer.putInt(position);
    }
    buffer.put(data);
    return buffer.array();
  }

  /** 解码磁盘缓存负载；指纹不符、截断或结构异常都返回 {@code null}（按未命中处理）。 */
  private static CachedChunk decodeDiskPayload(byte[] payload, long expectedSourceHash) {
    if (payload == null || payload.length < 12) {
      return null;
    }
    try {
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      long sourceHash = buffer.getLong();
      if (sourceHash != expectedSourceHash) {
        return null;
      }
      int count = buffer.getInt();
      if (count < 0 || count > buffer.remaining() / Integer.BYTES) {
        return null;
      }
      int[] positions = new int[count];
      for (int index = 0; index < count; index++) {
        positions[index] = buffer.getInt();
      }
      byte[] data = new byte[buffer.remaining()];
      buffer.get(data);
      if (data.length == 0) {
        return null;
      }
      return new CachedChunk(sourceHash, data, positions);
    } catch (RuntimeException exception) {
      return null;
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
        workPool.execute(() -> handleAsync(task, accessor, timeout));
      } catch (Throwable throwable) {
        // 任何意外都必须归还这次延迟，否则该封包永久卡住（signalOnce 保证恰好放行一次）
        logThrottled("邻区块抓取后的改写调度失败，已按原包放行", throwable);
        timeout.cancel(false);
        task.signalOnce();
      }
    };

    try {
      if (PlatformSupport.isFolia()) {
        // Folia：在该区块所属的区域线程上抓取（跨区域读取失败会由 provider 按缺失降级）
        Bukkit.getRegionScheduler().execute(getPlugin(), world, task.chunkX(), task.chunkZ(), capture);
      } else {
        Bukkit.getScheduler().runTask(getPlugin(), capture);
      }
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
    if (!accessor.update(data, positions, task.minHeight())) {
      // 「算了但没写」：setBuffer 回读不一致（ProtocolLib 版本/封包结构不符），必须留痕
      stats.writeBackFailures.increment();
      logThrottled("区块改写结果未能写回封包（setBuffer 回读不一致），本轮按原包内容放行", null);
    }

    // 记录「该玩家在这个区块里被伪装过的坐标」，供邻近显形使用（纯内存写入，可在工作线程执行）
    if (revealedIndex != null) {
      revealedIndex.record(task.playerId(), task.worldName(), task.chunkX(), task.chunkZ(),
          task.minHeight(), positions);
    }
  }

  /** 该玩家是否受本模块影响（直通名单绕过 + 世界范围）。 */
  private boolean appliesTo(Player player) {
    if (bypassRegistry != null && bypassRegistry.isBypassed(player.getUniqueId())) {
      return false;
    }
    return config.appliesTo(player.getWorld().getName());
  }

  /** 使全部改写缓存、邻块快照与显形记录立即失效（配置热重载时调用）。 */
  public void invalidateAll() {
    cache.invalidateAll();
    if (neighborProvider != null) {
      neighborProvider.invalidateAll();
    }
    if (revealedIndex != null) {
      revealedIndex.clear();
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

  /** 使某个世界的缓存整体失效（世界卸载时调用），同时清掉该世界的显形记录。 */
  public void invalidateWorld(String worldName) {
    cache.invalidateWorld(worldName);
    if (neighborProvider != null) {
      neighborProvider.invalidateWorld(worldName);
    }
    if (revealedIndex != null) {
      revealedIndex.clearWorld(worldName);
    }
  }

  /** 当前缓存条目数（诊断用）。 */
  public int cacheSize() {
    return cache.size();
  }

  /** 当前邻块快照条目数（诊断用）。 */
  public int neighborCacheSize() {
    return neighborProvider == null ? 0 : neighborProvider.size();
  }

  private void logThrottled(String message, Throwable throwable) {
    if (errorLogs.incrementAndGet() <= MAX_ERROR_LOGS) {
      getPlugin().getLogger().log(Level.WARNING, message + "（同类错误最多提示 " + MAX_ERROR_LOGS + " 次）", throwable);
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