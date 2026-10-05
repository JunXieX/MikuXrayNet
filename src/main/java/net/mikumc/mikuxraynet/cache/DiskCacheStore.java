package net.mikumc.mikuxraynet.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 收尾（{@link #flush}/{@link #invalidateWorld}）带 50ms 预算、超时只停等不取消任务（由磁盘线程自行跑完），
 * 因此既不会阻塞 Netty 线程，也不会把封包工作线程拖过处理时限，更不会在世界卸载时卡住主线程。
 * 该线程的身份用 {@link #DISK_THREAD} 标记判定（不靠线程名，避免被改名/同名外来线程误导）。
 *
 * <p><b>fail-open</b>：任何异常/超时都只记日志并计数，读写统统退化为「未命中 / 不写入」，
 * 绝不影响封包链路与反矿透主流程。
 */
public final class DiskCacheStore implements AutoCloseable {

  private static final String THREAD_NAME = "MikuXrayNet-DiskCache";
  /** 单次读取的等待预算（毫秒）：超时按未命中处理，保证封包路径不被磁盘拖住。 */
  private static final long READ_TIMEOUT_MILLIS = 50L;
  /**
   * flush / 世界失效（{@link #flush}/{@link #invalidateWorld}）的等待预算（毫秒）。
   *
   * <p><b>为什么压到毫秒级</b>：这两条路径会在<b>世界卸载（主线程）</b>与配置热重载上被调用，而磁盘线程
   * 可能正忙于压缩（整文件重写 + {@code force(true)} + {@code move} 的长任务）。旧实现等 5 秒，
   * 等于在世界卸载时把主线程卡住最长 5 秒（玩家可感的卡服）。这里只等一小段时间，超时后
   * <b>不取消任务</b>（见 {@link #submitBounded}）——落盘与关句柄仍由磁盘线程自行跑完，
   * 因此「最终落盘」语义不变，只是调用方不再干等。
   */
  private static final long FLUSH_WAIT_MILLIS = 50L;
  /**
   * 关闭（{@link #close()}）排空磁盘线程的等待预算（毫秒）。
   *
   * <p>与上面的 {@link #FLUSH_WAIT_MILLIS} 不同，这里必须等得足够久把脏数据真正落盘：close 走
   * {@code shutdown()}（有序关闭）而不是 {@code shutdownNow()}，超过本预算只记一条中文 WARN，
   * <b>绝不中断</b>正在进行的 FileChannel 写——FileChannel 一旦被中断即永久失效，脏 bucket 反而会
   * 彻底丢失（与类注释承诺的「最终落盘」直接矛盾）。
   */
  private static final long CLOSE_TIMEOUT_MILLIS = 5000L;
  /** 触发压缩回收的垃圾占比（垃圾字节 > 活数据的一半）。 */
  private static final double COMPACT_GARBAGE_RATIO = 0.5D;
  /**
   * 两次「单文件压缩」之间的让出时间（毫秒）。
   *
   * <p>压缩是「整文件重写 + force + move」的长任务，与读取共用同一个磁盘线程。若把一轮的全部候选连续
   * 做完，这期间提交的读取会在队列里枯等到 50ms 预算耗尽、记未命中，上层于是回退重写——缓存近乎失效。
   * 因此一次只压缩一个文件，压完隔一小段时间再排下一个：空档里新提交的读取能立刻执行（见
   * {@link #compactGarbage()}）。
   */
  private static final long COMPACT_YIELD_MILLIS = 20L;

  /** 区域缓存文件后缀（BufferedLinearV3 的线性 bucket 布局）。 */
  private static final String REGION_FILE_SUFFIX = ".b_linear";

  /**
   * 「当前线程就是磁盘线程」的身份标记，<b>只由磁盘线程自身在启动时写入</b>（见构造器里的线程工厂）。
   *
   * <p><b>为什么不能用线程名判定</b>：线程名是外部可改的。任何外来线程（例如 Folia 的区域线程、
   * Netty 线程）都能把自己命名为 {@code MikuXrayNet-DiskCache}，从而被误判为磁盘线程并绕过
   * {@link #submit} 的排队，直接<b>在调用方线程上执行磁盘 IO</b>（拖住热路径）；反过来磁盘线程一旦
   * 被改名，它自己提交的读取就会去排队等自己 → 必然超时、缓存失效。ThreadLocal 由工作线程写入、
   * 外来线程读到的恒为 {@code null}，无法伪造，也与 Folia「一区域一线程」的语义天然兼容
   * （区域线程不会被判成缓存线程）。
   */
  private static final ThreadLocal<Boolean> DISK_THREAD = new ThreadLocal<>();

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

    /**
     * 每个 bucket 已计入 {@link #pendingBytes} 的整桶编码长度（只有磁盘线程访问）。
     *
     * <p><b>为什么按桶而非按负载记账</b>：flush 追加的是<b>整块 bucket</b>（最多 64 个槽位），旧实现只把
     * 本次 payload 长度累加进 {@link #pendingBytes}，严重低估了实际会写入文件的字节数——文件早已冲破
     * {@code max-file-size-mb}，上限判定却迟迟不触发、压缩回收也被一并推迟。改为：每次写入把「该桶整桶
     * 编码长度」相对上次的增量并入 {@link #pendingBytes}，使上限判定反映真正会被 append 的数据量。
     */
    private final long[] pendingBucketBytes = new long[BufferedLinearV3Format.BUCKET_COUNT];

    /** 待落盘记账清零（该文件的整桶数据已随 flush / 压缩完整落到文件）。 */
    private void clearPending() {
      pendingBytes = 0L;
      Arrays.fill(pendingBucketBytes, 0L);
    }

    /**
     * 本句柄当前「计入」{@link #approximateEntries} 的条目数（只有磁盘线程访问）。
     *
     * <p>句柄关闭（闲置 / 世界卸载 / 停用 / 通道失效）时据此把该文件的额度收回，
     * 避免条目计数只增不减、上限随句柄开关不断抬高。重新打开时会重新扫描磁盘并补计。
     */
    private long accountedEntries;

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
   * 本进程内「已做过磁盘既有条目补计、且句柄仍打开」的区域文件集合。
   *
   * <p>重启后 {@link #approximateEntries} 从 0 开始；首次打开某个已存在的区域文件时把磁盘上的
   * 真实条目数补计进来，否则 {@code max-entries} 上限在重启后完全失效（旧条目不占额度）。
   * 句柄关闭时该键会被移除、其额度一并收回（见 {@link #removeAndClose}），因此重开时会重新扫描补计。
   */
  private final Set<RegionKey> entryCounted = ConcurrentHashMap.newKeySet();
  private final long maxFileSizeBytes;
  private final long readTimeoutMillis;
  /** flush / 世界失效的等待预算（毫秒）；生产为 {@link #FLUSH_WAIT_MILLIS}，测试可放宽。 */
  private final long flushWaitMillis;
  private final ScheduledExecutorService executor;
  /**
   * 压缩会话是否进行中。
   *
   * <p>压缩被拆成「一轮一个文件、自链式排程」的多步任务后，可能跨过维护周期（默认 30 秒）；用该标志
   * 避免下一轮维护再起一条会话与旧会话叠加。
   */
  private final AtomicBoolean compactionRunning = new AtomicBoolean();

  private volatile boolean closed;

  /**
   * {@link #close()} 是否已开始（CAS 保证并发/重复调用只真正排空一次）。
   *
   * <p><b>为什么单独需要它</b>：{@code closed} 必须在排空<b>之前</b>置位（见 {@link #close()}），
   * 而「先置位再排空」会引入「已进入关闭、但排空尚未完成」的中间态；用 CAS 保证这个中间态只被
   * 一个线程拥有，另一个并发 close 直接返回，不会重复排空。
   */
  private final AtomicBoolean closeRequested = new AtomicBoolean();

  public DiskCacheStore(Path rootDir, AntiXrayConfig.DiskCache config, Logger logger) {
    this(rootDir, config, logger, READ_TIMEOUT_MILLIS, FLUSH_WAIT_MILLIS);
  }

  /**
   * 测试用构造：可指定读取等待预算（收尾操作沿用同一预算，避免 CI 磁盘抖动被误判为超时）。
   *
   * @param readTimeoutMillis 单次读取的等待上限（毫秒），超时按未命中降级
   */
  DiskCacheStore(Path rootDir, AntiXrayConfig.DiskCache config, Logger logger,
      long readTimeoutMillis) {
    this(rootDir, config, logger, readTimeoutMillis, readTimeoutMillis);
  }

  /**
   * 测试用构造：可分别指定读取与收尾（flush / 世界失效）的等待预算。
   *
   * @param readTimeoutMillis 单次读取的等待上限（毫秒），超时按未命中降级
   * @param flushWaitMillis   flush / 世界失效的等待上限（毫秒）；超时后任务仍由磁盘线程自行跑完
   */
  DiskCacheStore(Path rootDir, AntiXrayConfig.DiskCache config, Logger logger,
      long readTimeoutMillis, long flushWaitMillis) {
    this.rootDir = rootDir;
    this.config = config;
    this.logger = logger;
    this.readTimeoutMillis = Math.max(1L, readTimeoutMillis);
    this.flushWaitMillis = Math.max(1L, flushWaitMillis);
    this.maxFileSizeBytes = Math.max(1L, config.maxFileSizeMb()) * 1024L * 1024L;
    this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(() -> {
        // 由磁盘线程自身写入身份标记：只有在磁盘线程内执行时才为 true，外来线程无法伪造（见 DISK_THREAD）
        DISK_THREAD.set(Boolean.TRUE);
        try {
          runnable.run();
        } finally {
          DISK_THREAD.remove();
        }
      }, THREAD_NAME);
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

  /**
   * 世界卸载：落盘 + 关闭该世界的全部句柄（文件保留，下次加载可复用）。
   *
   * <p>该调用发生在<b>主线程</b>的世界卸载事件里：最多等 {@link #flushWaitMillis} 毫秒即返回，
   * 超时后关句柄仍由磁盘线程跑完（见 {@link #submitBounded}），因此不会为等缓存而卡住主线程。
   */
  public void invalidateWorld(String worldName) {
    if (worldName == null || !usable()) {
      return;
    }
    submitBounded(() -> {
      closeWorldHandles(worldName);
      return null;
    }, flushWaitMillis);
  }

  /**
   * 把所有脏数据落盘（配置热重载后调用）。
   *
   * <p>最多等 {@link #flushWaitMillis} 毫秒即返回（该调用通常在主线程）：超时后落盘仍会由磁盘线程
   * 自行完成，最终落盘语义不变（见 {@link #submitBounded}）。
   */
  public void flush() {
    if (!usable()) {
      return;
    }
    submitBounded(() -> {
      flushAll();
      return null;
    }, flushWaitMillis);
  }

  /** 全部落盘并关闭；可重复调用。 */
  @Override
  public void close() {
    // 必须「先置 closed、再排空」：旧实现把 closed 放在 future.get(...) 之后才置位，于是排空期间
    // （最长 CLOSE_TIMEOUT_MILLIS ≈ 5s）usable() 仍为 true，工作线程提交的 put/get 会被排到排空任务
    // 之后执行，在 closeAllHandles 之后重新打开句柄并留在 open 表里——shutdownNow 后无人再关闭它们
    // （句柄泄漏、文件锁悬挂）。现在 closed 先置位，usable() 立即为 false，新提交一律被拒；
    // 下面 execute()/submit()/handle() 也都以 closed 为闸，保证「重新打开路径」在 closed 后不可达。
    if (!closeRequested.compareAndSet(false, true)) {
      return;
    }
    closed = true;
    try {
      executor.submit(() -> {
        try {
          flushAll();
          closeAllHandles();
        } catch (Throwable throwable) {
          fail("关闭磁盘缓存时出现异常", throwable);
        }
      });
    } catch (Throwable throwable) {
      fail("关闭磁盘缓存的最终落盘任务提交失败", throwable);
    }
    // 有序关闭（shutdown 而非 shutdownNow）：不再接受新任务，但让正在执行/已入队的落盘任务自然跑完，
    // 从而兑现「close 会把脏数据最终落盘」的承诺。绝不 shutdownNow——那会中断正在进行的 FileChannel
    // 写，而 FileChannel 被中断即永久失效，脏 bucket 反而彻底丢失。
    executor.shutdown();
    try {
      if (!executor.awaitTermination(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        // 硬截止：只提示一次，且不做任何中断。中断只会毁掉正在进行的写、把「可能未持久化」变成
        // 「彻底丢失」；不打断则磁盘线程会在队列排空后自行结束（fail-open，缓存只是可选加速）。
        logger.warning("磁盘缓存关闭超过 " + CLOSE_TIMEOUT_MILLIS + "ms 仍未排空，"
            + "部分缓存条目可能未能持久化（缓存只是可选加速，不影响封包链路与反矿透本身）");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
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
      handle.accountedEntries = Math.max(0L, handle.accountedEntries - 1L);
      approximateEntries.updateAndGet(value -> Math.max(0, value - 1));
    }
  }

  private void doPut(String worldName, int chunkX, int chunkZ, int configHash, long writtenAt,
      byte[] payload) throws IOException {
    int regionX = BufferedLinearV3Format.regionCoordinate(chunkX);
    int regionZ = BufferedLinearV3Format.regionCoordinate(chunkZ);
    Handle handle = handle(worldName, regionX, regionZ, true);
    if (handle == null) {
      // 已关闭（handle() 在 closed 后返回 null）：不再打开句柄，直接跳过本次写入
      return;
    }
    if (handle.file.lockUnavailable()) {
      // 该文件被其它服务端实例占用：本实例对它停用读写（见 RegionFile#lockUnavailable）。
      // 必须在这里就返回，否则下面会把「写了但没落盘」的条目计进 approximateEntries，白白耗尽额度。
      return;
    }
    if (handle.file.sizeBytes() + handle.pendingBytes >= maxFileSizeBytes) {
      // 单文件上限：既看已落盘字节，也看尚未 append 的待落盘字节
      // （bucket 是整块追加的，只看已落盘大小会让首次落盘一次性冲破上限）
      stats.rejectedBySize.increment();
      return;
    }

    BufferedLinearV3Format.Entry entry =
        new BufferedLinearV3Format.Entry(0L, writtenAt, configHash, payload);
    int chunkIndex = BufferedLinearV3Format.chunkIndex(chunkX, chunkZ);
    boolean replaced = handle.file.put(chunkIndex, entry);
    if (!replaced) {
      handle.accountedEntries++;
      approximateEntries.incrementAndGet();
    }
    // 该负载会在 flushDirty 时随所属 bucket <b>整块</b> append：按「该桶整桶编码长度」的增量记账，
    // 而不是只加本次 payload 长度——bucket 是整块追加的，后者会严重低估上限判定所需的数据量。
    // 桶重写产生的旧副本计入垃圾，由压缩回收。
    int bucket = BufferedLinearV3Format.bucketIndex(chunkIndex);
    long bucketBytes = handle.file.encodedBucketSizeEstimate(bucket);
    handle.pendingBytes += bucketBytes - handle.pendingBucketBytes[bucket];
    handle.pendingBucketBytes[bucket] = bucketBytes;
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
        handle.clearPending();
      } catch (Throwable throwable) {
        if (!handle.file.channelOpen()) {
          // 通道已永久失效（FileChannel 一旦被线程中断或外部关闭就无法再用）。此时句柄留在表里、
          // 脏标记还在，旧实现会「每轮维护都抛一次同样的异常」——真机上表现为每 30 秒一条
          // ClosedChannelException。这里直接丢弃句柄并只提示一次，下次访问自动重建（fail-open）。
          removeAndClose(entry.getKey(), handle);
          stats.errors.increment();
          logger.warning("磁盘缓存区域文件通道已失效，已丢弃句柄（下次访问自动重建）："
              + handle.file.path() + "（原因：" + throwable + "）");
          continue;
        }
        fail("磁盘缓存落盘失败（该区域文件将在下次维护重试）", throwable);
      }
    }
  }

  /**
   * 压缩回收：把垃圾占比过高的区域文件整文件重写，顺带丢弃过期/旧代次条目。
   *
   * <p><b>为什么把「一轮多个文件」拆成多个自链式任务</b>：压缩是「整文件重写 + {@code force(true)} +
   * move」的长任务，与读取共用同一个磁盘线程（{@code MikuXrayNet-DiskCache}）。旧实现把候选文件在一个
   * 任务里连续压完，这期间提交的读取全部排在长任务之后、50ms 预算内跑不到 → 记未命中 → 上层回退重写，
   * 缓存近乎失效。这里改为：一次只压一个文件（单轮工作量上限仍由 {@code compact-per-pass} 决定），
   * 压完隔 {@link #COMPACT_YIELD_MILLIS} 毫秒再排下一个——空档里新提交的读取能被立刻处理，长阻塞被
   * 切到「单文件」粒度。全部磁盘 IO 仍只在该单线程上执行，因此不引入任何并发（RegionFile 的单线程
   * 纪律、关闭时的排空顺序、fail-open 与计数语义全部不变）。
   */
  private void compactGarbage() {
    if (!compactionRunning.compareAndSet(false, true)) {
      // 上一条压缩会话尚未结束（跨维护周期）：跳过本轮，避免叠加
      return;
    }
    try {
      int budget = Math.max(1, config.compactPerPass());
      List<RegionKey> candidates = new ArrayList<>();
      for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
        Handle handle = entry.getValue();
        // 数据区总字节 = 活数据 + 垃圾；据此反推真正的活数据字节。
        // 旧实现把「数据区总字节」当成活数据当分母，等价于判据「垃圾 > 活数据」（阈值 0.5 时），
        // 与常量/注释声明的「垃圾 > 活数据的一半」差了一倍，压缩迟迟不触发。
        long dataArea = Math.max(0L, handle.file.sizeBytes() - BufferedLinearV3Format.DATA_AREA_OFFSET);
        long garbage = handle.file.garbageBytes();
        long live = Math.max(0L, dataArea - garbage);
        if (garbage > 0L && (double) garbage > (double) live * COMPACT_GARBAGE_RATIO) {
          candidates.add(entry.getKey());
          if (candidates.size() >= budget) {
            break;
          }
        }
      }
      if (candidates.isEmpty()) {
        compactionRunning.set(false);
        return;
      }
      scheduleCompactStep(candidates, 0);
    } catch (Throwable throwable) {
      compactionRunning.set(false);
      fail("磁盘缓存压缩回收调度失败（已跳过本轮）", throwable);
    }
  }

  /** 排程压缩会话的第 {@code index} 个文件；每步之间让出磁盘线程，见 {@link #compactGarbage()} 说明。 */
  private void scheduleCompactStep(List<RegionKey> candidates, int index) {
    if (closed || index >= candidates.size()) {
      compactionRunning.set(false);
      return;
    }
    RegionKey key = candidates.get(index);
    try {
      executor.schedule(() -> {
        try {
          if (!closed) {
            compactOne(key);
          }
        } finally {
          scheduleCompactStep(candidates, index + 1);
        }
      }, COMPACT_YIELD_MILLIS, TimeUnit.MILLISECONDS);
    } catch (Throwable throwable) {
      compactionRunning.set(false);
      fail("磁盘缓存压缩回收排程失败（已中止本轮）", throwable);
    }
  }

  /** 压缩单个区域文件（在磁盘线程上执行，与读写天然串行）。 */
  private void compactOne(RegionKey key) {
    Handle handle = open.get(key);
    if (handle == null) {
      // 句柄可能已被闲置关闭 / 世界卸载关闭：跳过（数据已在之前的落盘中持久化）
      return;
    }
    try {
      int dropped = handle.file.compact((chunkIndex, entry) -> keepEntry(entry));
      if (dropped > 0) {
        handle.accountedEntries = Math.max(0L, handle.accountedEntries - dropped);
        approximateEntries.updateAndGet(value -> Math.max(0, value - dropped));
      }
      // 压缩会把内存里的全部 bucket（含脏的）整文件重写：待落盘记账随之清零
      handle.clearPending();
    } catch (Throwable throwable) {
      // 压缩失败：句柄可能已不可用（例如文件被外部删除），直接关闭并让它下次重新打开
      fail("磁盘缓存压缩回收失败，已关闭该区域文件句柄：" + handle.file.path(), throwable);
      removeAndClose(key, handle);
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
      removeAndClose(entry.getKey(), handle);
    }
  }

  private void closeWorldHandles(String worldName) {
    // 键里存的是 sanitize 后的世界名：这里同样归一化后再比较，保证 World/world 命中同一文件的句柄
    String world = sanitize(worldName);
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      if (!entry.getKey().worldName().equals(world)) {
        continue;
      }
      removeAndClose(entry.getKey(), entry.getValue());
    }
  }

  private void closeAllHandles() {
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      removeAndClose(entry.getKey(), entry.getValue());
    }
    approximateEntries.set(0);
  }

  /**
   * 从打开表摘除句柄并关闭它，同时<b>收回该句柄计入的条目额度</b>并把该键从「已补计」集合移除——
   * 让下次打开时重新扫描磁盘、重新补计（否则计数只增不减，{@code max-entries} 会随句柄开关不断抬高）。
   *
   * <p>只有磁盘线程调用；{@link ConcurrentHashMap#remove(Object, Object)} 返回 false（句柄已被别处移除）时不做任何事。
   */
  private void removeAndClose(RegionKey key, Handle handle) {
    if (!open.remove(key, handle)) {
      return;
    }
    long accounted = handle.accountedEntries;
    if (accounted > 0L) {
      handle.accountedEntries = 0L;
      approximateEntries.updateAndGet(value -> (int) Math.max(0L, value - accounted));
    }
    entryCounted.remove(key);
    closeHandleQuietly(handle);
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
    if (closed) {
      // 关闭后绝不再打开新句柄：这是「重新打开路径在 closed 后不可达」的最后一道闸（前两道是
      // usable() 与 execute()/submit()）。返回 null 由调用方按未命中/跳过处理。
      return null;
    }
    // 键里存<b>sanitize 后</b>的世界名：物理文件就是按 sanitize 名落盘的，若键里存原始名，
    // 「World」「world」两个键会各自解析到同一路径、开出两个句柄互踩（Windows 上 ATOMIC_MOVE 还会失败）。
    String world = sanitize(worldName);
    RegionKey key = new RegionKey(world, regionX, regionZ);
    Handle existing = open.get(key);
    if (existing != null) {
      existing.touch();
      return existing;
    }

    Path path = pathFor(world, regionX, regionZ);
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
        handle.accountedEntries += count;
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

  /**
   * 世界名 → 目录名：只保留安全字符，避免路径穿越或非法文件名。
   *
   * <p><b>目录名碰撞（既定取舍，靠源指纹兜底）</b>：非安全字符统一替换为 {@code '_'} 并转小写，因此
   * 形如 {@code a/b}、{@code a_b}、{@code A_B} 的不同世界名会落到同一目录、共用同一物理文件，彼此
   * 可能命中对方写下的条目。这是刻意接受的：条目新鲜度由负载里的<b>原始区块字节指纹</b>判定
   * （见 {@code DiskPayload.decode}），只有「原始字节完全相同」才会命中——而原始字节相同意味着改写结果
   * 也相同，跨世界复用它们不会产生错误内容；指纹不符则按未命中处理，最多损失一点命中率，绝不错服。
   */
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
    // closed 也视为「不可提交」：关闭排队期间仍可能有工作线程越过 usable() 判定才提交，
    // 若放行就会在排空之后重新打开句柄（见 close() 说明）。
    if (closed || executor == null || executor.isShutdown()) {
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
    // 与 execute() 同理：closed 后一律不再提交（关闭中的排空任务由 close() 直接 submit，不走这里）。
    if (closed || executor == null || executor.isShutdown()) {
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

  /**
   * 提交一个「最终必须完成、但调用方最多只等 {@code waitMillis}」的收尾任务
   * （落盘 {@link #flush()} / 关闭指定世界句柄 {@link #invalidateWorld(String)}）。
   *
   * <p>与 {@link #submit} 的两点差别：
   * <ol>
   *   <li><b>超时不取消任务</b>：落盘与关句柄是必须发生的收尾工作；若像读取那样 {@code cancel(false)}
   *       把任务从队列里摘掉，世界卸载后的脏数据与句柄就永远无人处理。超时只代表「调用方不再等」，
   *       任务仍由磁盘线程自行跑完，最终一致性不变；</li>
   *   <li><b>等待预算压到毫秒级</b>（见 {@link #FLUSH_WAIT_MILLIS}）：世界卸载与配置热重载都在主线程上，
   *       不能为等磁盘线程（可能正在压缩）而卡住主线程。</li>
   * </ol>
   * 队列满时直接跳过、异常只记日志并计数——与 {@link #submit} 一致的 fail-open。
   */
  private void submitBounded(Callable<?> task, long waitMillis) {
    // 与 execute()/submit() 同理：closed 后一律不再提交（关闭中的排空由 close() 直接 submit，不走这里）。
    if (closed || executor == null || executor.isShutdown()) {
      return;
    }
    if (isCacheThread()) {
      // 已在磁盘线程内：直接执行，避免自等自（与 submit() 同理）
      try {
        task.call();
      } catch (Throwable throwable) {
        fail("磁盘缓存收尾操作失败（已在磁盘线程内直接降级）", throwable);
      }
      return;
    }
    if (pendingOps.get() >= config.queueCapacity()) {
      return;
    }

    pendingOps.incrementAndGet();
    Future<?> future;
    try {
      future = executor.submit(() -> {
        try {
          task.call();
        } catch (Throwable throwable) {
          // 在这里就地记录：调用方超时返回后没人再看 future 的异常，否则失败会被静默吞掉
          fail("磁盘缓存收尾操作失败（本次按无操作跳过，不影响链路）", throwable);
        } finally {
          pendingOps.decrementAndGet();
        }
      });
    } catch (Throwable throwable) {
      pendingOps.decrementAndGet();
      fail("磁盘缓存收尾任务提交失败", throwable);
      return;
    }

    try {
      future.get(waitMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      // 不取消：任务继续排在磁盘线程上跑完（取消会让这次收尾永久丢失，见方法说明）
      stats.errors.increment();
    } catch (ExecutionException exception) {
      // 任务体已自行捕获 Throwable，这里只为满足受检异常签名（正常不可达）
      fail("磁盘缓存收尾任务执行失败", exception.getCause() == null ? exception : exception.getCause());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean isCacheThread() {
    // 身份判定而非线程名判定：标记只由磁盘线程自己写入，名字被改或被伪造都影响不到它
    return Boolean.TRUE.equals(DISK_THREAD.get());
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