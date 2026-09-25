package net.mikumc.mikuxraynet.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * bucket 压缩用 zstd（服务端自带的 zstd-jni，{@code scope=provided}，不打进插件 jar；
 * 运行期不可用时自动回退 JDK Deflater），旧 Deflate 缓存仍可读取（详见该类的压缩方案说明）。
 *
 * <p><b>缓存键与失效</b>：键 = {@code (世界名, chunkX, chunkZ, 配置指纹)}。
 * 配置指纹来自 {@code antixray.yml} 中影响改写结果的配置项，且只用「哈希有规范定义」的稳定分量
 * （字符串/数字/列表/映射/枚举名），因此同一份配置在<b>任意进程</b>上都算出同一个指纹。
 *
 * <p><b>内容新鲜度由负载自己证明</b>：条目负载里带着原始区块字节指纹，与本次要改写的字节不符即视为
 * 未命中（见 {@code DiskPayload}）。这是唯一的「内容是否过期」判据——它天然覆盖「区块被挖过/被重建」
 * 等一切修改，且跨进程成立。
 *
 * <p>历史包袱说明：本类曾用「区块代次」（{@code markBlockChange} + 一个内存 LRU）参与键，
 * 但代次表只在进程内存在：重启后所有代次归零，于是<b>上一进程写下的、代次非 0 的条目会被逐条判为
 * 过期并删除</b>（真机实测：重启后命中 0／未命中 586、过期清理 0、异常 0，磁盘上 665 条对不上号）。
 * 而它的唯一价值（「区块内容变了」）本就由原始字节指纹覆盖，因此该机制已整体移除。
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
  private final AtomicInteger pendingOps = new AtomicInteger();
  private final AtomicInteger approximateEntries = new AtomicInteger();
  /**
   * 已做过「磁盘既有条目补计」的区域文件（每进程每文件至多一次）。
   *
   * <p>重启后 {@link #approximateEntries} 从 0 开始；首次打开某个已存在的区域文件时把磁盘上的
   * 真实条目数补计进来，否则 {@code max-entries} 上限在重启后完全失效（旧条目不占额度）。
   * 句柄闲置关闭后重开不会重扫（文件内容未变，计数仍准确）。
   */
  private final Set<RegionKey> entryCounted = ConcurrentHashMap.newKeySet();
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
    cleanupCrashLeftoverTempFiles();
  }

  /**
   * 启动清理：删除上次进程崩溃 / 被强杀时残留的 {@code *.tmp} 压缩临时文件。
   *
   * <p>压缩回收先把整文件写到 {@code r.X.Z.b_linear.tmp} 再原子替换正式文件
   * （见 {@link RegionFile#compact}）；进程若在「tmp 已写出、正式文件尚未替换」之间崩溃，
   * 孤儿 tmp 会永远残留在磁盘上（重启后无任何路径再认领它，而正式文件自身是一致的）。
   * 因此在缓存初始化时按目录扫描一次：只删缓存目录（含世界子目录）下的 {@code *.tmp}，
   * 删除失败只记日志（缓存可丢，链路不能断）。
   */
  private void cleanupCrashLeftoverTempFiles() {
    if (!Files.isDirectory(rootDir)) {
      // 全新安装还没有缓存目录：没有可清理的对象，静默跳过
      return;
    }
    int removed = 0;
    try (var paths = Files.walk(rootDir, 2)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        if (!path.getFileName().toString().endsWith(".tmp")) {
          continue;
        }
        try {
          if (Files.deleteIfExists(path)) {
            removed++;
          }
        } catch (Throwable throwable) {
          logger.warning("清理磁盘缓存临时文件失败（不影响缓存功能）：" + path + "（" + throwable + "）");
        }
      }
    } catch (Throwable throwable) {
      logger.warning("扫描磁盘缓存临时文件失败（不影响缓存功能）：" + throwable);
      return;
    }
    if (removed > 0) {
      logger.info("已清理 " + removed + " 个上次崩溃残留的磁盘缓存临时文件（*.tmp）");
    }
  }

  // ------------------------------------------------------------------ 对外 API

  /**
   * 读取一个区块的缓存负载。
   *
   * @param configHash 配置指纹（参与键，配置变了就自然不命中）
   * @return 负载字节；未命中、过期或任何异常时返回 {@code null}
   */
  public byte[] get(String worldName, int chunkX, int chunkZ, int configHash) {
    if (!usable() || worldName == null) {
      return null;
    }
    byte[] payload = submit(() -> doGet(worldName, chunkX, chunkZ, configHash), readTimeoutMillis);
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
      return;
    }

    long writtenAt = System.currentTimeMillis();
    execute(() -> doPut(worldName, chunkX, chunkZ, configHash, writtenAt, payload));
  }

  /** 世界卸载：落盘 + 关闭该世界的全部句柄（文件保留，下次加载可复用）。 */
  public void invalidateWorld(String worldName) {
    if (worldName == null || !usable()) {
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

  /** 是否处于可用状态（配置开启且未关闭）。 */
  public boolean usable() {
    return !closed && config.enabled();
  }

  // ------------------------------------------------------------------ 磁盘线程执行体

  private byte[] doGet(String worldName, int chunkX, int chunkZ, int configHash)
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
    if (entry.configHash() != configHash) {
      // 配置变了：惰性清理，绝不返回旧配置改写的负载
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

  private void doPut(String worldName, int chunkX, int chunkZ, int configHash, long writtenAt,
      byte[] payload) throws IOException {
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
        new BufferedLinearV3Format.Entry(0L, writtenAt, configHash, payload);
    boolean replaced = handle.file.put(BufferedLinearV3Format.chunkIndex(chunkX, chunkZ), entry);
    if (!replaced) {
      approximateEntries.incrementAndGet();
    }
    // 该负载会在 flushDirty 时随所属 bucket 整块 append；bucket 重写产生的旧副本计入垃圾，由压缩回收
    handle.pendingBytes += payload.length;
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
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      Handle handle = entry.getValue();
      try {
        if (handle.file.isDirty()) {
          handle.file.flushDirty();
        }
        // 无论是否真的写过，此刻内存里的负载都已（随 bucket 整块）落到文件：待落盘记账清零
        handle.pendingBytes = 0L;
      } catch (Throwable throwable) {
        if (!handle.file.channelOpen()) {
          // 通道已永久失效（FileChannel 一旦被线程中断或外部关闭就无法再用）。此时句柄留在表里、
          // 脏标记还在，旧实现会「每轮维护都抛一次同样的异常」——真机上表现为每 30 秒一条
          // ClosedChannelException。这里直接丢弃句柄并只提示一次，下次访问自动重建（fail-open）。
          open.remove(entry.getKey(), handle);
          closeHandleQuietly(handle);
          stats.errors.increment();
          logger.warning("磁盘缓存区域文件通道已失效，已丢弃句柄（下次访问自动重建）："
              + handle.file.path() + "（原因：" + throwable + "）");
          continue;
        }
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
        int dropped = handle.file.compact((chunkIndex, entry) -> keepEntry(entry));
        if (dropped > 0) {
          approximateEntries.updateAndGet(value -> Math.max(0, value - dropped));
        }
        // 压缩会把内存里的全部 bucket（含脏的）整文件重写：待落盘记账随之清零
        handle.pendingBytes = 0L;
        done++;
      } catch (Throwable throwable) {
        // 压缩失败：句柄可能已不可用（例如文件被外部删除），直接关闭并让它下次重新打开
        fail("磁盘缓存压缩回收失败，已关闭该区域文件句柄：" + handle.file.path(), throwable);
        open.remove(key, handle);
        closeHandleQuietly(handle);
      }
    }
  }

  /** 条目保留判定：只按过期时间丢弃（内容新鲜度由负载里的原始字节指纹保证，见类注释）。 */
  private boolean keepEntry(BufferedLinearV3Format.Entry entry) {
    if (isExpired(entry)) {
      stats.expiredRemoved.increment();
      return false;
    }
    return true;
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
    if (entryCounted.add(key) && file.sizeBytes() > BufferedLinearV3Format.DATA_AREA_OFFSET) {
      // 重启后 approximateEntries 从 0 开始：必须把磁盘上已有的条目补计进来，
      // 否则 max-entries 上限在重启后完全失效（旧条目不占额度，磁盘可无限增长）。
      // 异步一次性扫描（逐 bucket 解码计数），不阻塞触发本次打开的读写；此后由
      // put/remove/compact 增量维护。排程与扫描之间恰被落盘的新写入会被重复计入一次——
      // 计数本就是近似值，偏差方向是「提前拒绝新写入」，保守无害。
      try {
        executor.execute(() -> countDiskEntries(key, created));
      } catch (Throwable throwable) {
        // 排程失败（停用等）：放回待计数集合，下次打开重试
        entryCounted.remove(key);
      }
    }
    Handle raced = open.putIfAbsent(key, created);
    if (raced != null) {
      // 理论上不会发生（全部在磁盘线程串行），兜底：保留先到者。
      // 只 close 不 delete——文件可能正被先到者使用，删除会破坏对方句柄（A9）。
      try {
        created.file.close();
      } catch (Throwable throwable) {
        fail("关闭重复打开的磁盘缓存区域文件失败（不影响先到者）", throwable);
      }
      return raced;
    }
    return created;
  }

  /**
   * 首次打开某区域文件时，把磁盘上已有的条目数补进 {@link #approximateEntries}（一次性后台扫描）。
   *
   * <p>计数取「文件里的全部条目」而不过滤过期/旧代次——这些条目随后被惰性清理或压缩回收时
   * 会正常递减，多计的部分只是暂时的保守值；宁可计数偏大提前拒绝写入，也不偏小放任磁盘增长。
   *
   * <p>只读字节流数长度前缀（{@link RegionFile#countEntriesOnDisk()}）：既不解码负载也不进 LRU，
   * 因此启动期统计不会把别的区域文件的脏桶挤出去写盘（旧实现逐个 {@code get} 会触发整文件解码）。
   */
  private void countDiskEntries(RegionKey key, Handle handle) {
    if (closed || open.get(key) != handle) {
      // 句柄已被世界卸载/停用关闭或被重建：放回待计数集合，下次打开时重试
      entryCounted.remove(key);
      return;
    }
    try {
      int count = handle.file.countEntriesOnDisk();
      if (count > 0) {
        approximateEntries.addAndGet(count);
      }
    } catch (Throwable throwable) {
      // 计数失败只影响上限的准确度（偏小），绝不影响缓存读写（fail-open）
      fail("统计磁盘缓存既有条目失败（max-entries 计数可能偏小，不影响缓存功能）", throwable);
    }
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

  // ------------------------------------------------------------------ 调度

  /** 提交一个不等待结果的任务（写入、落盘等）。 */
  private void execute(ThrowingRunnable task) {
    if (executor == null || executor.isShutdown()) {
      return;
    }
    if (pendingOps.get() >= config.queueCapacity()) {
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
      // 只取消、不中断：磁盘线程是共享的单线程，interrupt=true 会打断它正在进行的 FileChannel IO，
      // 而 FileChannel 一被中断就永久关闭（ClosedByInterruptException）→ 该区域文件句柄作废，
      // 且脏 bucket 无法再落盘（真机上表现为每 30 秒一条 ClosedChannelException）。
      // 读超时的语义本来就是「本次按未命中降级」，任务稍后自行跑完即可，无需打断。
      future.cancel(false);
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