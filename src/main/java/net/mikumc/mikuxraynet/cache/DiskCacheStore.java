package net.mikumc.mikuxraynet.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;

/**
 * 磁盘缓存：把「已改写完成的区块负载」持久化到
 * {@code plugins/MikuXrayNet/cache/<世界名>/r.<regionX>.<regionZ>.b_linear}，重启后可直接复用，
 * 省掉重复的重解码与遮挡判定（CPU 开销）。
 *
 * <p><b>文件格式</b>：{@link BufferedLinearV3Format}（32×32 区块一区），
 * 压缩用 JDK 自带的 Deflater 而非 zstd——本项目<b>不新增第三方依赖</b>（详见该类的差异说明）。
 *
 * <p><b>缓存键与失效</b>：键 = {@code (世界名, chunkX, chunkZ, 配置指纹, 区块代次)}。
 * 配置指纹来自 {@code antixray.yml} 中影响改写结果的配置项；区块代次由本插件<b>已经拦截的方块变更</b>
 * 驱动（{@link #markBlockChange}，出站 {@code BLOCK_CHANGE}/{@code MULTI_BLOCK_CHANGE} 封包路径），
 * 某区块观测到变更即递增 → 该区块的旧代次条目在读取时自然失效并被惰性清理。
 * 代次跟踪表是有界 LRU（可配 {@code generation-tracker-size}），被挤出的键退化为「代次 0」，
 * 只会多一次未命中、不会返回脏数据（读写双方还都会校验原始字节指纹与配置指纹）。
 *
 * <p><b>生命周期（本项目最看重的一点）</b>：绝不持有 {@code World / Chunk / Player} 引用（键只有
 * 字符串与整数）；世界卸载 → {@link #invalidateWorld} 落盘并关闭该世界的全部句柄；插件停用 →
 * {@link #close} 全部落盘并关闭；后台维护任务负责「落盘 + 压缩回收 + 关闭闲置句柄」，另有
 * 总条目上限、单文件大小上限、条目过期时间三重限额（单文件上限同时计入「已接受但尚未落盘」的字节，
 * 因为 bucket 是整块追加的），{@link #stats()} 暴露持有量与命中率供
 * {@code /mikuxraynet status} 自查。
 *
 * <p><b>线程纪律</b>：全部磁盘 IO 都在本类自有的单线程
 * （{@code MikuXrayNet-DiskCache}）上执行；写入是「提交即返回」，读取带 50ms 预算，超时按未命中降级，
 * 因此既不会阻塞 Netty 线程，也不会把封包工作线程拖过处理时限。
 *
 * <p><b>fail-open</b>：任何异常/超时都只记日志并计数，读写统统退化为「未命中 / 不写入」，
 * 绝不影响封包链路与反矿透主流程。
 */
public final class DiskCacheStore implements AutoCloseable {

  private static final String THREAD_NAME = "MikuXrayNet-DiskCache";
  /** 单次读取的等待预算（毫秒）：超时按未命中处理，保证封包路径不被磁盘拖住。 */
  private static final long READ_TIMEOUT_MILLIS = 50L;
  /** flush / 关闭类操作的等待预算（毫秒）。 */
  private static final long FLUSH_TIMEOUT_MILLIS = 5000L;
  /** 触发压缩回收的垃圾占比（垃圾字节 > 活数据的一半）。 */
  private static final double COMPACT_GARBAGE_RATIO = 0.5D;

  /** 区域缓存文件后缀（BufferedLinearV3 的线性 bucket 布局）。 */
  private static final String REGION_FILE_SUFFIX = ".b_linear";

  /** 区域坐标键（只含不可变类型，不可能钉住世界对象）。 */
  private record RegionKey(String worldName, int regionX, int regionZ) {
  }

  /** 区块坐标键（用于区块代次跟踪的有界 LRU）。 */
  private record ChunkRef(String worldName, int chunkX, int chunkZ) {
  }

  /** 已打开的区域文件句柄（仅磁盘线程改动，诊断读取只取近似值）。 */
  private static final class Handle {

    private final RegionFile file;
    private volatile long lastAccessNanos;

    /**
     * 已接受但尚未 append 到文件里的负载字节（只有磁盘线程访问）。
     *
     * <p><b>为什么必须单独记账</b>：{@link RegionFile#put} 只改内存并标脏，真正 append 发生在
     * {@link RegionFile#flushDirty()}（维护周期 / 显式 flush / 关闭）。只看
     * {@link RegionFile#sizeBytes()} 会漏掉整批待落盘数据，导致「单文件上限」在首次落盘时被一次性冲破
     * （实测出现过 16MB 上限却写出 30MB 文件）。
     */
    private long pendingBytes;

    private Handle(RegionFile file) {
      this.file = file;
      this.lastAccessNanos = System.nanoTime();
    }

    private void touch() {
      this.lastAccessNanos = System.nanoTime();
    }
  }

  private final Path rootDir;
  private final AntiXrayConfig.DiskCache config;
  private final Logger logger;
  private final DiskCacheStats stats = new DiskCacheStats();
  private final ConcurrentHashMap<RegionKey, Handle> open = new ConcurrentHashMap<>();
  private final Map<ChunkRef, Long> generations = new LinkedHashMap<>(1024, 0.75f, true);
  private final AtomicInteger pendingOps = new AtomicInteger();
  private final AtomicInteger approximateEntries = new AtomicInteger();
  private final long maxFileSizeBytes;
  private final long readTimeoutMillis;
  private final ScheduledExecutorService executor;

  private volatile boolean closed;

  public DiskCacheStore(Path rootDir, AntiXrayConfig.DiskCache config, Logger logger) {
    this(rootDir, config, logger, READ_TIMEOUT_MILLIS);
  }

  /**
   * 测试用构造：可指定读取等待预算。
   *
   * @param readTimeoutMillis 单次读取的等待上限（毫秒），超时按未命中降级
   */
  DiskCacheStore(Path rootDir, AntiXrayConfig.DiskCache config, Logger logger,
      long readTimeoutMillis) {
    this.rootDir = rootDir;
    this.config = config;
    this.logger = logger;
    this.readTimeoutMillis = Math.max(1L, readTimeoutMillis);
    this.maxFileSizeBytes = Math.max(1L, config.maxFileSizeMb()) * 1024L * 1024L;
    this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, THREAD_NAME);
      thread.setDaemon(true);
      return thread;
    });

    long interval = Math.max(1, config.maintenanceIntervalSeconds());
    try {
      this.executor.scheduleWithFixedDelay(this::maintenance, interval, interval, TimeUnit.SECONDS);
    } catch (Throwable throwable) {
      fail("磁盘缓存维护任务登记失败（缓存仍可用，仅失去周期落盘与回收）", throwable);
    }
  }

  // ------------------------------------------------------------------ 对外 API

  /**
   * 读取一个区块的缓存负载。
   *
   * @param configHash 配置指纹（参与键，配置变了就自然不命中）
   * @return 负载字节；未命中、过期、代次不符或任何异常时返回 {@code null}
   */
  public byte[] get(String worldName, int chunkX, int chunkZ, int configHash) {
    if (!usable() || worldName == null) {
      return null;
    }
    stats.requests.increment();
    long generation = generation(worldName, chunkX, chunkZ);
    byte[] payload = submit(() -> doGet(worldName, chunkX, chunkZ, configHash, generation),
        readTimeoutMillis);
    if (payload == null || payload.length == 0) {
      stats.misses.increment();
      return null;
    }
    stats.hits.increment();
    return payload;
  }

  /**
   * 写入一个区块的缓存负载（提交即返回，实际落盘由磁盘线程完成）。
   *
   * <p>负载由调用方自行编码（含原始字节指纹），本类只当作不透明字节保存。
   */
  public void put(String worldName, int chunkX, int chunkZ, int configHash, byte[] payload) {
    if (!usable() || worldName == null || payload == null || payload.length == 0) {
      return;
    }
    if (payload.length > BufferedLinearV3Format.MAX_PAYLOAD_SIZE) {
      stats.rejectedByCapacity.increment();
      return;
    }
    if (approximateEntries.get() >= config.maxEntries()) {
      stats.rejectedByCapacity.increment();
      return;
    }
    if (pendingOps.get() >= config.queueCapacity()) {
      stats.droppedByBacklog.increment();
      return;
    }

    long generation = generation(worldName, chunkX, chunkZ);
    long writtenAt = System.currentTimeMillis();
    execute(() -> doPut(worldName, chunkX, chunkZ, configHash, generation, writtenAt, payload));
  }

  /**
   * 记录「该区块发生了方块变更」：递增其区块代次，使旧代次缓存条目在读取时失效。
   *
   * <p>由出站方块变更封包路径驱动（服务端已下发变更 = 该区块内容已变），不做任何磁盘操作。
   */
  public void markBlockChange(String worldName, int chunkX, int chunkZ) {
    if (!usable() || worldName == null) {
      return;
    }
    ChunkRef ref = new ChunkRef(worldName, chunkX, chunkZ);
    synchronized (generations) {
      Long current = generations.get(ref);
      generations.put(ref, current == null ? 1L : current + 1L);
      int cap = Math.max(1, config.generationTrackerSize());
      while (generations.size() > cap) {
        Iterator<ChunkRef> iterator = generations.keySet().iterator();
        if (!iterator.hasNext()) {
          break;
        }
        iterator.next();
        iterator.remove();
      }
    }
    stats.generationBumps.increment();
  }

  /** 世界卸载：清掉该世界的代次跟踪，并落盘 + 关闭该世界的全部句柄（文件保留，下次加载可复用）。 */
  public void invalidateWorld(String worldName) {
    if (worldName == null) {
      return;
    }
    synchronized (generations) {
      generations.keySet().removeIf(ref -> ref.worldName().equals(worldName));
    }
    if (!usable()) {
      return;
    }
    submit(() -> {
      closeWorldHandles(worldName);
      return null;
    }, FLUSH_TIMEOUT_MILLIS);
  }

  /** 立即把所有脏数据落盘（插件停用前置步骤、配置热重载后调用）。 */
  public void flush() {
    if (!usable()) {
      return;
    }
    submit(() -> {
      flushAll();
      return null;
    }, FLUSH_TIMEOUT_MILLIS);
  }

  /** 全部落盘并关闭；可重复调用。 */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    try {
      Future<?> future = executor.submit(() -> {
        try {
          flushAll();
          closeAllHandles();
        } catch (Throwable throwable) {
          fail("关闭磁盘缓存时出现异常", throwable);
        }
      });
      future.get(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (Throwable throwable) {
      fail("关闭磁盘缓存超时或异常，已强制结束", throwable);
    }
    closed = true;
    executor.shutdownNow();
    synchronized (generations) {
      generations.clear();
    }
  }

  /** 计数（命中率、拒绝与异常等）。 */
  public DiskCacheStats stats() {
    return stats;
  }

  /** 当前近似条目数（诊断用）。 */
  public int entries() {
    return Math.max(0, approximateEntries.get());
  }

  /** 当前打开的区域文件数（诊断用）。 */
  public int openRegionFiles() {
    return open.size();
  }

  /** 当前跟踪代次的区块数（诊断用）。 */
  public int trackedChunks() {
    synchronized (generations) {
      return generations.size();
    }
  }

  /** 当前区域文件占用字节（诊断用，近似值）。 */
  public long diskBytes() {
    long total = 0L;
    for (Handle handle : open.values()) {
      total += handle.file.sizeBytes();
    }
    return total;
  }

  /** 是否处于可用状态（配置开启且未关闭）。 */
  public boolean usable() {
    return !closed && config.enabled();
  }

  // ------------------------------------------------------------------ 磁盘线程执行体

  private byte[] doGet(String worldName, int chunkX, int chunkZ, int configHash, long generation)
      throws IOException {
    int regionX = BufferedLinearV3Format.regionCoordinate(chunkX);
    int regionZ = BufferedLinearV3Format.regionCoordinate(chunkZ);
    Handle handle = handle(worldName, regionX, regionZ, false);
    if (handle == null) {
      return null;
    }

    int chunkIndex = BufferedLinearV3Format.chunkIndex(chunkX, chunkZ);
    BufferedLinearV3Format.Entry entry = handle.file.get(chunkIndex);
    if (entry == null) {
      return null;
    }
    if (entry.configHash() != configHash || entry.generation() != generation) {
      // 配置变了或该区块已发生变更：惰性清理，绝不返回脏数据
      removeEntry(handle, chunkIndex);
      return null;
    }
    if (isExpired(entry)) {
      removeEntry(handle, chunkIndex);
      stats.expiredRemoved.increment();
      return null;
    }

    return entry.payload();
  }

  /** 惰性清理一个条目并同步近似计数（计数只作容量判断，夹在 0 以上）。 */
  private void removeEntry(Handle handle, int chunkIndex) throws IOException {
    if (handle.file.clear(chunkIndex)) {
      approximateEntries.updateAndGet(value -> Math.max(0, value - 1));
    }
  }

  private void doPut(String worldName, int chunkX, int chunkZ, int configHash, long generation,
      long writtenAt, byte[] payload) throws IOException {
    int regionX = BufferedLinearV3Format.regionCoordinate(chunkX);
    int regionZ = BufferedLinearV3Format.regionCoordinate(chunkZ);
    Handle handle = handle(worldName, regionX, regionZ, true);
    if (handle.file.sizeBytes() + handle.pendingBytes >= maxFileSizeBytes) {
      // 单文件上限：既看已落盘字节，也看尚未 append 的待落盘字节
      // （bucket 是整块追加的，只看已落盘大小会让首次落盘一次性冲破上限）
      stats.rejectedBySize.increment();
      return;
    }

    BufferedLinearV3Format.Entry entry =
        new BufferedLinearV3Format.Entry(generation, writtenAt, configHash, payload);
    boolean replaced = handle.file.put(BufferedLinearV3Format.chunkIndex(chunkX, chunkZ), entry);
    if (!replaced) {
      approximateEntries.incrementAndGet();
    }
    // 该负载会在 flushDirty 时随所属 bucket 整块 append；bucket 重写产生的旧副本计入垃圾，由压缩回收
    handle.pendingBytes += payload.length;
    stats.puts.increment();
  }

  /** 维护任务：落盘 → 压缩回收 → 关闭闲置句柄（全部在磁盘线程执行）。 */
  private void maintenance() {
    if (closed) {
      return;
    }
    try {
      flushAll();
      compactGarbage();
      closeIdleHandles();
    } catch (Throwable throwable) {
      fail("磁盘缓存维护任务异常（已跳过本轮）", throwable);
    }
  }

  private void flushAll() {
    for (Handle handle : open.values()) {
      try {
        if (handle.file.isDirty()) {
          handle.file.flushDirty();
        }
        // 无论是否真的写过，此刻内存里的负载都已（随 bucket 整块）落到文件：待落盘记账清零
        handle.pendingBytes = 0L;
      } catch (Throwable throwable) {
        fail("磁盘缓存落盘失败（该区域文件将在下次维护重试）", throwable);
      }
    }
  }

  /** 压缩回收：把垃圾占比过高的区域文件整文件重写，顺带丢弃过期/旧代次条目。 */
  private void compactGarbage() {
    int budget = Math.max(1, config.compactPerPass());
    List<Map.Entry<RegionKey, Handle>> candidates = new ArrayList<>();
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      Handle handle = entry.getValue();
      long garbage = handle.file.garbageBytes();
      long live = Math.max(0L, handle.file.sizeBytes() - BufferedLinearV3Format.DATA_AREA_OFFSET);
      if (garbage > 0L && (double) garbage > (double) live * COMPACT_GARBAGE_RATIO) {
        candidates.add(entry);
      }
    }

    int done = 0;
    for (Map.Entry<RegionKey, Handle> candidate : candidates) {
      if (done >= budget) {
        return;
      }
      RegionKey key = candidate.getKey();
      Handle handle = candidate.getValue();
      try {
        int dropped = handle.file.compact((chunkIndex, entry) ->
            keepEntry(key.worldName(), key.regionX(), key.regionZ(), chunkIndex, entry));
        if (dropped > 0) {
          approximateEntries.updateAndGet(value -> Math.max(0, value - dropped));
        }
        // 压缩会把内存里的全部 bucket（含脏的）整文件重写：待落盘记账随之清零
        handle.pendingBytes = 0L;
        stats.compactions.increment();
        done++;
      } catch (Throwable throwable) {
        // 压缩失败：句柄可能已不可用（例如文件被外部删除），直接关闭并让它下次重新打开
        fail("磁盘缓存压缩回收失败，已关闭该区域文件句柄：" + handle.file.path(), throwable);
        open.remove(key, handle);
        closeHandleQuietly(handle);
      }
    }
  }

  /** 条目保留判定：过期或代次不符即丢弃（惰性清理的另一半，配合读取时的清理）。 */
  private boolean keepEntry(String worldName, int regionX, int regionZ, int chunkIndex,
      BufferedLinearV3Format.Entry entry) {
    if (isExpired(entry)) {
      stats.expiredRemoved.increment();
      return false;
    }
    int chunkX = (regionX << 5) | (chunkIndex & 31);
    int chunkZ = (regionZ << 5) | (chunkIndex >>> 5);
    return entry.generation() == generation(worldName, chunkX, chunkZ);
  }

  /** 关闭长时间未使用的句柄，避免打开的文件描述符与内存随世界规模增长。 */
  private void closeIdleHandles() {
    long idleNanos = TimeUnit.SECONDS.toNanos(Math.max(1, config.idleCloseSeconds()));
    long now = System.nanoTime();
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      Handle handle = entry.getValue();
      if (now - handle.lastAccessNanos < idleNanos) {
        continue;
      }
      if (!open.remove(entry.getKey(), handle)) {
        continue;
      }
      closeHandleQuietly(handle);
    }
  }

  private void closeWorldHandles(String worldName) {
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      if (!entry.getKey().worldName().equals(worldName)) {
        continue;
      }
      if (!open.remove(entry.getKey(), entry.getValue())) {
        continue;
      }
      closeHandleQuietly(entry.getValue());
    }
  }

  private void closeAllHandles() {
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      if (open.remove(entry.getKey(), entry.getValue())) {
        closeHandleQuietly(entry.getValue());
      }
    }
    approximateEntries.set(0);
  }

  /** 落盘 + 关闭，并删掉「空且不脏」的文件，避免磁盘上残留无意义的小文件。 */
  private void closeHandleQuietly(Handle handle) {
    try {
      boolean empty = handle.file.isEmptyFile();
      handle.file.close();
      if (empty) {
        Files.deleteIfExists(handle.file.path());
      }
    } catch (Throwable throwable) {
      fail("关闭磁盘缓存区域文件失败", throwable);
    }
  }

  private boolean isExpired(BufferedLinearV3Format.Entry entry) {
    long expireMillis = Math.max(1L, config.expireSeconds()) * 1000L;
    return System.currentTimeMillis() - entry.writtenAtMillis() > expireMillis;
  }

  /** 打开（或复用）区域文件句柄；文件头损坏时删除重建（缓存可丢，但不能卡住链路）。 */
  private Handle handle(String worldName, int regionX, int regionZ, boolean create)
      throws IOException {
    RegionKey key = new RegionKey(worldName, regionX, regionZ);
    Handle existing = open.get(key);
    if (existing != null) {
      existing.touch();
      return existing;
    }

    Path path = pathFor(worldName, regionX, regionZ);
    if (!create && !Files.isRegularFile(path)) {
      return null;
    }
    if (create) {
      Path dir = path.getParent();
      if (dir != null && !Files.isDirectory(dir)) {
        Files.createDirectories(dir);
      }
    }

    RegionFile file;
    try {
      file = RegionFile.open(path, config.bucketCacheSize());
    } catch (IOException firstFailure) {
      stats.errors.increment();
      logger.warning("磁盘缓存区域文件损坏，已删除重建：" + path + "（" + firstFailure.getMessage() + "）");
      Files.deleteIfExists(path);
      file = RegionFile.open(path, config.bucketCacheSize());
    }

    Handle created = new Handle(file);
    Handle raced = open.putIfAbsent(key, created);
    if (raced != null) {
      // 理论上不会发生（全部在磁盘线程串行），兜底：保留先到者
      closeHandleQuietly(created);
      return raced;
    }
    return created;
  }

  private Path pathFor(String worldName, int regionX, int regionZ) {
    return rootDir.resolve(sanitize(worldName))
        .resolve("r." + regionX + "." + regionZ + REGION_FILE_SUFFIX);
  }

  /** 世界名 → 目录名：只保留安全字符，避免路径穿越或非法文件名。 */
  private static String sanitize(String worldName) {
    StringBuilder builder = new StringBuilder(worldName.length());
    for (int index = 0; index < worldName.length(); index++) {
      char character = worldName.charAt(index);
      boolean safe = character >= 'a' && character <= 'z'
          || character >= 'A' && character <= 'Z'
          || character >= '0' && character <= '9'
          || character == '.' || character == '_' || character == '-';
      builder.append(safe ? character : '_');
    }
    return builder.length() == 0 ? "unknown" : builder.toString().toLowerCase(Locale.ROOT);
  }

  // ------------------------------------------------------------------ 代次与调度

  private long generation(String worldName, int chunkX, int chunkZ) {
    ChunkRef ref = new ChunkRef(worldName, chunkX, chunkZ);
    synchronized (generations) {
      Long value = generations.get(ref);
      return value == null ? 0L : value;
    }
  }

  /** 提交一个不等待结果的任务（写入、落盘等）。 */
  private void execute(ThrowingRunnable task) {
    if (executor == null || executor.isShutdown()) {
      return;
    }
    if (pendingOps.get() >= config.queueCapacity()) {
      stats.droppedByBacklog.increment();
      return;
    }
    pendingOps.incrementAndGet();
    try {
      executor.execute(() -> {
        try {
          task.run();
        } catch (Throwable throwable) {
          stats.errors.increment();
        } finally {
          pendingOps.decrementAndGet();
        }
      });
    } catch (Throwable throwable) {
      pendingOps.decrementAndGet();
      stats.errors.increment();
    }
  }

  /** 提交一个带预算的任务；超时或异常都返回 {@code null}（fail-open）。 */
  private <T> T submit(Callable<T> task, long timeoutMillis) {
    if (executor == null || executor.isShutdown()) {
      return null;
    }
    if (isCacheThread()) {
      try {
        return task.call();
      } catch (Throwable throwable) {
        fail("磁盘缓存操作失败（已在磁盘线程内直接降级）", throwable);
        return null;
      }
    }
    if (pendingOps.get() >= config.queueCapacity()) {
      stats.droppedByBacklog.increment();
      return null;
    }

    pendingOps.incrementAndGet();
    Future<T> future;
    try {
      future = executor.submit(() -> {
        try {
          return task.call();
        } finally {
          pendingOps.decrementAndGet();
        }
      });
    } catch (Throwable throwable) {
      pendingOps.decrementAndGet();
      fail("磁盘缓存任务提交失败", throwable);
      return null;
    }

    try {
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      stats.errors.increment();
      return null;
    } catch (ExecutionException exception) {
      fail("磁盘缓存任务执行失败", exception.getCause() == null ? exception : exception.getCause());
      return null;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static boolean isCacheThread() {
    return THREAD_NAME.equals(Thread.currentThread().getName());
  }

  private void fail(String message, Throwable throwable) {
    stats.errors.increment();
    logger.log(Level.WARNING, message + "（磁盘缓存已降级为纯内存路径，不影响封包链路）", throwable);
  }

  /** 允许抛出受检异常的 Runnable（磁盘操作签名统一）。 */
  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws IOException;
  }
}