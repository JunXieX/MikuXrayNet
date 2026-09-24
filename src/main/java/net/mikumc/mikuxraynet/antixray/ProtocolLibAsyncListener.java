package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.concurrency.RewriteTask;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
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

  private static final String BYPASS_PERMISSION = "mikuxraynet.bypass";
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
  private final ConcurrentHashMap<UUID, ChunkBatchGate> batches = new ConcurrentHashMap<>();
  private final AtomicInteger errorLogs = new AtomicInteger();

  /**
   * @param neighborProvider 邻区块贴边快照提供者；仅在 {@code neighbors.enabled} 时被使用
   * @param handleChunkBatch 是否拦截 1.20.2+ 的区块批量包（不可用时由装配方降级为 false）
   */
  public ProtocolLibAsyncListener(Plugin plugin, AntiXrayConfig config, ObfuscationProcessor processor,
      MikuWorkPool workPool, AsynchronousManager asynchronousManager,
      NeighborChunkProvider neighborProvider, boolean handleChunkBatch) {
    super(plugin, ListenerPriority.NORMAL, packetTypes(handleChunkBatch));
    this.config = config;
    this.processor = processor;
    this.workPool = workPool;
    this.asynchronousManager = asynchronousManager;
    this.cache = new RewriteCache<>(config.cacheMaximumSize(), config.cacheExpireAfterAccessSeconds());
    this.neighborProvider = neighborProvider;
    this.neighborsEnabled = neighborProvider != null && config.neighbors().enabled();
    this.handleChunkBatch = handleChunkBatch;
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
    RewriteTask task = new RewriteTask(accessor.chunkX(), accessor.chunkZ(), world.getName(), minHeight,
        sectionCount, config.timeoutMillis(),
        gate == null
            ? () -> asynchronousManager.signalPacketTransmission(event)
            : () -> {
              gate.chunkDone();
              asynchronousManager.signalPacketTransmission(event);
            });

    ScheduledFuture<?> timeout;
    try {
      timeout = workPool.scheduleTimeout(task::releaseOnTimeout, config.timeoutMillis());
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
        if (cached != null && cached.sourceHash() == sourceHash) {
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

  /** 改写并回填缓存。 */
  private void rewrite(RewriteTask task, ChunkPacketAccessor accessor, byte[] source, long sourceHash,
      NeighborEdges neighbors) {
    task.markDecoded();
    ObfuscationProcessor.Result result = processor.rewrite(source, task.sectionCount(),
        seed(task.worldName(), task.chunkX(), task.chunkZ()), neighbors);
    task.markEncoded();

    CachedChunk value = new CachedChunk(sourceHash, result.data(), result.obfuscatedPositions());
    cache.put(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash(), value);
    writeBack(accessor, task, value.data(), value.positions());
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
    accessor.update(data, positions, task.minHeight());
  }

  /** 该玩家是否受本模块影响（权限绕过 + 世界范围）。 */
  private boolean appliesTo(Player player) {
    return !player.hasPermission(BYPASS_PERMISSION) && config.appliesTo(player.getWorld().getName());
  }

  /** 使某个世界的缓存整体失效（世界卸载时调用）。 */
  public void invalidateWorld(String worldName) {
    cache.invalidateWorld(worldName);
    if (neighborProvider != null) {
      neighborProvider.invalidateWorld(worldName);
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