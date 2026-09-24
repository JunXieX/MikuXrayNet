package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.concurrency.RewriteTask;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * ProtocolLib 异步区块监听器：反矿透唯一的封包拦截入口。
 *
 * <p>线程模型：网络线程（本监听回调）只做「读坐标 → 建任务 → 登记延迟 → 入队」，
 * 解码/判定/重编码全部交给 {@link MikuWorkPool} 的工作线程；工作线程不触碰任何 Bukkit API。
 *
 * <p>fail-open：队列满 / 封包解析失败 / 处理超时 / 任何异常，一律放行原包；
 * 所有出口共用 {@link RewriteTask#signalOnce()}，保证「恰好放行一次」。
 */
public final class ProtocolLibAsyncListener extends PacketAdapter {

  private static final String BYPASS_PERMISSION = "mikuxraynet.bypass";
  private static final int MAX_ERROR_LOGS = 3;

  /** 缓存值：源字节指纹 + 改写结果（不含任何封包或世界引用）。 */
  private record CachedChunk(long sourceHash, byte[] data, int[] positions) {
  }

  private final AntiXrayConfig config;
  private final ObfuscationProcessor processor;
  private final MikuWorkPool workPool;
  private final AsynchronousManager asynchronousManager;
  private final RewriteCache<CachedChunk> cache;
  private final AtomicInteger errorLogs = new AtomicInteger();

  public ProtocolLibAsyncListener(Plugin plugin, AntiXrayConfig config, ObfuscationProcessor processor,
      MikuWorkPool workPool, AsynchronousManager asynchronousManager) {
    super(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.MAP_CHUNK);
    this.config = config;
    this.processor = processor;
    this.workPool = workPool;
    this.asynchronousManager = asynchronousManager;
    this.cache = new RewriteCache<>(config.cacheMaximumSize(), config.cacheExpireAfterAccessSeconds());
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    if (event.isCancelled() || event.getPacketType() != PacketType.Play.Server.MAP_CHUNK) {
      return;
    }
    if (!processor.isActive()) {
      return;
    }

    Player player = event.getPlayer();
    if (player == null || player.hasPermission(BYPASS_PERMISSION)) {
      return;
    }

    World world = player.getWorld();
    String worldName = world.getName();
    if (!config.appliesTo(worldName)) {
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

    ChunkPacketAccessor accessor;
    try {
      accessor = new ChunkPacketAccessor(event.getPacket());
    } catch (Throwable throwable) {
      logThrottled("区块封包结构解析失败，已按原包放行", throwable);
      return;
    }

    int minHeight = world.getMinHeight();
    int sectionCount = (world.getMaxHeight() - minHeight) / 16;
    RewriteTask task = new RewriteTask(accessor.chunkX(), accessor.chunkZ(), worldName, minHeight,
        sectionCount, config.timeoutMillis(),
        () -> asynchronousManager.signalPacketTransmission(event));

    ScheduledFuture<?> timeout = workPool.scheduleTimeout(task::releaseOnTimeout, config.timeoutMillis());
    marker.incrementProcessingDelay();

    try {
      workPool.execute(() -> handleAsync(task, accessor, timeout));
    } catch (RejectedExecutionException exception) {
      // 已登记延迟却没能入队：必须立即放行，否则该封包永久卡住
      timeout.cancel(false);
      task.signalOnce();
    }
  }

  /** 工作线程：缓存命中 → 直接回填；未命中 → 解码/判定/重编码并写回缓存。 */
  private void handleAsync(RewriteTask task, ChunkPacketAccessor accessor, ScheduledFuture<?> timeout) {
    if (!task.tryBeginWrite()) {
      // 已被看门狗超时放行：原包已发出，绝不能再改写
      timeout.cancel(false);
      return;
    }

    try {
      if (task.expired()) {
        return;
      }

      byte[] source = accessor.buffer();
      long sourceHash = hash(source);

      CachedChunk cached = cache.get(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash());
      if (cached != null && cached.sourceHash() == sourceHash) {
        writeBack(accessor, task, cached.data(), cached.positions());
        return;
      }

      task.markDecoded();
      ObfuscationProcessor.Result result = processor.rewrite(source, task.sectionCount(),
          seed(task.worldName(), task.chunkX(), task.chunkZ()));
      task.markEncoded();

      CachedChunk value = new CachedChunk(sourceHash, result.data(), result.obfuscatedPositions());
      cache.put(task.worldName(), task.chunkX(), task.chunkZ(), config.configHash(), value);
      writeBack(accessor, task, value.data(), value.positions());
    } catch (Throwable throwable) {
      logThrottled("区块改写失败，已按原包放行", throwable);
    } finally {
      timeout.cancel(false);
      task.signalOnce();
    }
  }

  private void writeBack(ChunkPacketAccessor accessor, RewriteTask task, byte[] data, int[] positions) {
    if (positions.length == 0) {
      return;
    }
    accessor.update(data, positions, task.minHeight());
  }

  /** 使某个世界的缓存整体失效（世界卸载时调用）。 */
  public void invalidateWorld(String worldName) {
    cache.invalidateWorld(worldName);
  }

  /** 当前缓存条目数（诊断用）。 */
  public int cacheSize() {
    return cache.size();
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