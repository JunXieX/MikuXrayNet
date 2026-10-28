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
import java.util.concurrent.ExecutorService;
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
 * <p><b>缓存键与失效</b>：键 = {@code (sanitize 后的世界名, chunkX, chunkZ, 条目指纹)}；条目指纹 =
 * 调用方给的配置指纹混入<b>原始世界名</b>的稳定哈希（见 {@link #mixWorldName}）。配置指纹来自
 * {@code antixray.yml} 中影响改写结果的配置项，且只用「哈希有规范定义」的稳定分量
 * （字符串/数字/列表/映射/枚举名），因此同一份配置在<b>任意进程</b>上都算出同一个指纹。
 *
 * <p><b>为什么世界名必须进指纹</b>：目录名由 {@link #sanitize} 归一化得到（非法字符→{@code '_'}、
 * 转小写），{@code world-1} 与 {@code world_1} 会落到同一目录、共用同一个物理文件。而改写结果还依赖
 * 世界名（伪装方块的随机种子、{@code world-overrides} 的隐藏清单），所以「原始字节相同 ⇒ 改写结果相同」
 * 只在<b>同一个世界内</b>成立：两个世界若共用指纹，hide-blocks 更少的一方写下的负载就会被另一方读到，
 * 真矿随之泄露。混入原始世界名后，这类同名世界即使共用文件也必然指纹不符 → 按未命中惰性清理，
 * <b>绝不会互相命中</b>（代价只是各自的条目不再被对方复用，命中率略低）。
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
   * <p>压缩现已改由<b>独立的压缩线程</b>执行（见 {@link #compactionExecutor}），因此它不再阻塞读取；
   * 这里的间隔只用于把「同一轮压缩会话」的文件之间拉开一点距离，避免压缩线程长时间连轴转、
   * 也顺便让每一步都重新检查 {@code closed}。
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
     * 最近一次访问的墙钟毫秒（只有磁盘线程写、诊断与淘汰读）。
     *
     * <p><b>为什么除了 {@link #lastAccessNanos} 还要这一个</b>：纳秒那个是单调时钟，专门用于
     * 「闲置多久该关句柄」的差值判定（不受系统改时间影响）；而<b>文件级 LRU 淘汰</b>要在多个句柄之间
     * 比较先后、并写进诊断日志给服主看，墙钟毫秒更直观且可跨重启比较。两者都在 {@link #touch()} 里更新，
     * 不额外增加热路径开销。
     */
    private volatile long lastAccessMillis;

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
     * <p><b>为什么按记录而不是按负载记账</b>：flush 追加的是<b>整条记录</b>（含 8 字节记录头 + 编码后的槽位），
     * 本次 payload 长度累加进 {@link #pendingBytes}，严重低估了实际会写入文件的字节数——文件早已冲破
     * {@code max-file-size-mb}，上限判定却迟迟不触发、压缩回收也被一并推迟。改为：每次写入把「该桶整桶
     * 编码长度」相对上次的增量并入 {@link #pendingBytes}，使上限判定反映真正会被 append 的数据量。
     *
     * <p><b>按需分配</b>：只读句柄（重启后命中读路径是缓存的常态）从不写入，因此永远不需要这张
     * 1024 项 / 8 KB 的 long 表。改为首次写入时才分配，省下每个只读句柄的这部分常驻内存
     * （{@code max-region-files} 较大时按句柄数线性叠加）。
     */
    private long[] pendingBucketBytes;

    /** per-bucket 待落盘记账表；首次写入时分配（见字段说明）。 */
    private long[] pendingBucketBytes() {
      long[] table = this.pendingBucketBytes;
      if (table == null) {
        table = new long[BufferedLinearV3Format.BUCKET_COUNT];
        this.pendingBucketBytes = table;
      }
      return table;
    }

    /** 待落盘记账清零（该文件的整桶数据已随 flush / 压缩完整落到文件）。 */
    private void clearPending() {
      pendingBytes = 0L;
      long[] table = this.pendingBucketBytes;
      if (table != null) {
        Arrays.fill(table, 0L);
      }
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
      this.lastAccessMillis = System.currentTimeMillis();
    }

    private void touch() {
      this.lastAccessNanos = System.nanoTime();
      this.lastAccessMillis = System.currentTimeMillis();
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
   * 压缩（整文件重写）专用线程：<b>读写与压缩分线程</b>。
   *
   * <p><b>为什么必须分开</b>：压缩是「解压全部槽位 → 重新编码+zstd → 写临时文件 → {@code force(true)}
   * → move」的不可切分长任务（单文件可达数百 ms）。与读取共用一条线程时，这段时间内<b>所有</b>文件的
   * 读取都会在 50ms 预算内跑不完 → 全部记未命中 → 上层回退整块重写（最贵路径），表现为周期性的
   * 命中率骤降与 CPU 抖动。拆开后，压缩期间磁盘线程照常服务其它文件的读写。
   *
   * <p><b>两个线程如何互斥</b>：本类的 {@code open}/{@code stats}/{@code approximateEntries} 等都是
   * 线程安全的，唯一不能并发的是「同一个 {@code RegionFile} 的状态」。做法是磁盘线程在交付压缩任务
   * <b>之前</b>调 {@link RegionFile#beginCompact()} 置独占标志，此后磁盘线程对该文件的所有入口
   * （读/写/落盘/清理/计数）一律避让并 fail-open；压缩线程跑完 {@code compact} 时清标志（volatile 写，
   * 充当 release）。因此两个线程从不同时触碰同一份 {@code RegionFile} 状态，也不需要任何锁。
   */
  private final ExecutorService compactionExecutor;
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

    // 压缩专用线程：不写 DISK_THREAD 身份标记（它本就不是磁盘线程，submit() 因此不会把它误判成
    // 「已在磁盘线程内」而绕过排队；压缩路径也从不提交任务给磁盘线程，不存在自等自）。
    this.compactionExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-DiskCache-Compact");
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
      logger.fine("已清理 " + removed + " 个上次崩溃残留的磁盘缓存临时文件（*.tmp）");
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
   * 惰性负载：只在确认「会被接收」之后才求值编码。
   *
   * <p><b>为什么需要它</b>：调用方（区块改写路径）编码负载要 {@code ByteBuffer.allocate} 并整块拷贝
   * 改写后的区块字节（几十~几百 KB）。若此刻磁盘缓存必然拒收（条目达上限 / 磁盘线程积压 / 负载超长），
   * 这次分配与拷贝纯属浪费——登录风暴下会放大 GC 压力。把编码推迟到容量判定通过之后即可完全避免。
   */
  @FunctionalInterface
  public interface PayloadSupplier {
    byte[] get();
  }

  /**
   * 写入一个区块的缓存负载；{@code payload} 只在通过全部容量判定后才被求值。
   *
   * @param encodedBytes   编码后的字节数（用于「超长」与容量判定，必须先于编码算出，见
   *                       {@link DiskPayload#encodedLength}）
   * @param payload        惰性负载工厂（通过判定后调用一次）
   */
  public void put(String worldName, int chunkX, int chunkZ, int configHash, int encodedBytes,
      PayloadSupplier payload) {
    if (!usable() || worldName == null || encodedBytes <= 0) {
      return;
    }
    if (encodedBytes > BufferedLinearV3Format.MAX_PAYLOAD_SIZE) {
      stats.rejectedByCapacity.increment();
      return;
    }
    if (approximateEntries.get() >= config.maxEntries()) {
      // 已达条目上限即拒绝写入（不区分「新条目」与「覆盖已有条目」）。
      //
      // 曾考虑放行「覆盖已有条目」以免缓存停止更新，但唯一可靠的判据（该区块是否已有条目）只存在于
      // RegionFile 的槽位表，而那属于磁盘线程：非磁盘线程要么同步等待（在改写工作线程上多一个磁盘 IO，
      // 且磁盘线程自己调用时是自等自），要么只能猜。两者都比「满了就拒」更差——缓存只是可选加速，
      // 到了上限后不再更新旧条目不会影响任何正确性（内容新鲜度由源字节指纹保证，过期条目会被回收）。
      stats.rejectedByCapacity.increment();
      return;
    }
    if (pendingOps.get() >= config.queueCapacity()) {
      stats.rejectedByBacklog.increment(); // 此前静默返回：积压导致写入被丢弃必须可见
      return;
    }
    byte[] encoded;
    try {
      encoded = payload.get();
    } catch (Throwable throwable) {
      // 与类注释「任何异常都只记日志并计数」一致：负载工厂异常绝不上抛到封包链路。
      fail("磁盘缓存在编码负载时出错（本次写入按跳过处理）", throwable);
      return;
    }
    if (encoded == null || encoded.length == 0) {
      return;
    }
    long writtenAt = System.currentTimeMillis();
    execute(() -> doPut(worldName, chunkX, chunkZ, configHash, writtenAt, encoded));
  }

  /**
   * 写入一个区块的缓存负载（提交即返回，实际落盘由磁盘线程完成）。
   *
   * <p>负载由调用方自行编码（含原始字节指纹），本类只当作不透明字节保存。
   * 若调用方尚未编码、且希望「被拒时不白做整块拷贝」，请改用
   * {@link #put(String, int, int, int, int, PayloadSupplier)}。
   */
  public void put(String worldName, int chunkX, int chunkZ, int configHash, byte[] payload) {
    if (payload == null) {
      return;
    }
    put(worldName, chunkX, chunkZ, configHash, payload.length, () -> payload);
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
    // 顺序：先让压缩线程收工，再排空磁盘线程。压缩独占某个句柄的 RegionFile，若在它跑的时候关闭通道，
    // 压缩会以 ClosedChannelException 失败（数据无损，只是该文件本轮未被回收，且会多一条告警）。
    // 与下面磁盘线程的关闭同口径：只 shutdown（不再收新任务、让已排入的压缩自然跑完），绝不 shutdownNow
    // ——中断会毁掉正在进行的 FileChannel 写，把「可能未完成」变成「彻底失败」。
    compactionExecutor.shutdown();
    try {
      if (!compactionExecutor.awaitTermination(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        logger.warning("磁盘缓存压缩线程超过 " + CLOSE_TIMEOUT_MILLIS + "ms 仍未收工；"
            + "本次关闭不再等待它（该区域文件本轮未被压缩回收，缓存只是可选加速，不影响封包链路）");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    try {
      executor.submit(() -> {
        try {
          flushAll();
          closeAllHandles();
          // 压缩超时未收工 → closeAllHandles 会跳过那些句柄；这里在磁盘线程上兜底等待并关闭
          closeCompactingHandlesAfterShutdown();
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
    // 比对用的指纹必须与写入侧同源：配置指纹 + 原始世界名（见 mixWorldName）。
    // sanitize 后同名的两个世界会共用本文件，靠这一项把它们的条目彻底分开。
    int fingerprint = mixWorldName(worldName, configHash);
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
    if (entry.configHash() != fingerprint) {
      // 指纹不符：配置变了，或同名目录下的另一个世界写下的条目（见 mixWorldName）。两种情况都必须
      // 惰性清理，绝不返回不符合本次改写前提的负载。
      // 必须计数：这是「升级/改配置后命中率骤降」的唯一成因出口，否则该清理完全不可见
      //（过期清理恒为 0，管理员会误以为条目有效却读不到）。
      stats.configMismatchRemoved.increment();
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
    // 复核实际负载长度：put() 的上限判定用的是调用方给的 encodedBytes（惰性负载在判定后才真正编码）。
    // 若两者不一致（调用方估算公式漂移、supplier 返回了别的对象），超长负载会被写进去却永远读不回来
    //（decodeEntry 对 payloadLength > MAX_PAYLOAD_SIZE 一律抛异常）——"写成功却读不回"的不对称。
    // 在这里按真实长度再拦一次，把不变式收归存储层自己保证，成本仅一次比较。
    if (payload.length > BufferedLinearV3Format.MAX_PAYLOAD_SIZE) {
      stats.rejectedBySize.increment();
      return;
    }
    // 写入侧与读取侧必须同源：条目里存的是「配置指纹 + 原始世界名」混合后的指纹（见 mixWorldName）。
    // 只存配置指纹的话，sanitize 后同名的另一个世界会用完全相同的指纹读走这条负载。
    int fingerprint = mixWorldName(worldName, configHash);
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
    if (handle.file.isCompacting()) {
      // 该文件正被压缩线程独占（整文件重写）：本次写入整体跳过。
      // 必须在入口就返回，不能只依赖 RegionFile#put 内部的避让——put 返回 false 在本方法里意味着
      // 「未覆盖已有条目」，会让下面把一条根本没写进去的条目录进 accountedEntries / approximateEntries。
      // 语义：只是少缓存一条（fail-open），压缩结束后后续写入照常。
      return;
    }
    int chunkIndex = BufferedLinearV3Format.chunkIndex(chunkX, chunkZ);
    int bucket = BufferedLinearV3Format.bucketIndex(chunkIndex);
    // per-bucket 待落盘记账表按需分配：只读句柄从不需要它（见 Handle#pendingBucketBytes 字段说明）。
    // 这里取一次局部引用，下面的判定与记账共用同一张表。
    long[] pendingBucketBytes = handle.pendingBucketBytes();
    // 单文件上限：既看已落盘字节，也看尚未 append 的待落盘字节，并<b>把本次写入会引起的该桶编码增量
    // 一并投影进去</b>。bucket 是整块追加的，若只看「写入之前的待落盘字节」（旧实现），本次写入可新增
    // 整整一个桶的字节，于是上限随时可能被顶破（默认 16 MiB 上限下实测可到数十 MB）。
    // 投影口径与下方记账用的 encodedBucketSizeEstimate 完全一致，故判定与记账不会漂移。
    long projectedBucketBytes = handle.file.encodedBucketSizeEstimateAfterPut(chunkIndex, payload.length);
    long projectedFileBytes = handle.file.sizeBytes() + handle.pendingBytes
        - pendingBucketBytes[bucket] + projectedBucketBytes;
    if (projectedFileBytes >= maxFileSizeBytes) {
      stats.rejectedBySize.increment();
      return;
    }

    BufferedLinearV3Format.Entry entry =
        new BufferedLinearV3Format.Entry(0L, writtenAt, fingerprint, payload);
    if (!handle.file.bucketRawLengthFitsAfterPut(chunkIndex, payload.length)) {
      // 加入本条后该桶的原始长度会超过读取侧上界（BufferedLinearV3Format#MAX_RAW_SIZE）：读取时整桶会被
      // 判为损坏、整桶当空。写入侧先行拒绝（计入既有大小拒绝计数），避免「写成功却读不回」的不对称。
      stats.rejectedBySize.increment();
      return;
    }
    boolean replaced = handle.file.put(chunkIndex, entry);
    if (!replaced) {
      handle.accountedEntries++;
      approximateEntries.incrementAndGet();
    }
    // 该负载会在 flushDirty 时随所属 bucket <b>整块</b> append：按「该桶整桶编码长度」的增量记账，
    // 而不是只加本次 payload 长度——bucket 是整块追加的，后者会严重低估上限判定所需的数据量。
    // 桶重写产生的旧副本计入垃圾，由压缩回收。
    long bucketBytes = handle.file.encodedBucketSizeEstimate(bucket);
    handle.pendingBytes += bucketBytes - pendingBucketBytes[bucket];
    pendingBucketBytes[bucket] = bucketBytes;
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

  /**
   * 把所有已打开句柄的脏记录落盘（维护周期 / 显式 flush / 关闭都会调用）。
   *
   * <p>每个句柄单次只写 {@link RegionFile#FLUSH_BATCH_RECORDS} 条：一次写完整批之前，磁盘线程被这条
   * 任务独占，而读取预算只有 50 ms（量化见 {@link RegionFile#FLUSH_BATCH_RECORDS}）。未写完的余量由
   * {@link #flushHandle} 排到队尾续写，中间到达的读取因此能被优先服务。
   */
  private void flushAll() {
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      flushHandle(entry.getKey(), entry.getValue());
    }
  }

  /**
   * 【磁盘线程】分批落盘单个句柄；本批写满且仍有脏记录时自链式续排（把磁盘线程让给读取）。
   *
   * <p>续排失败（已关闭 / 队列积压）不丢数据：脏标记原样保留——关闭路径由 {@code close()} 排空任务里的
   * 全量 {@link RegionFile#flushDirty()} 兜底，运行期由下一轮维护重试。
   */
  private void flushHandle(RegionKey key, Handle handle) {
    if (open.get(key) != handle) {
      return; // 句柄已被关闭/替换：其脏数据由 close() 的全量落盘或重建路径处理
    }
    if (handle.file.isCompacting()) {
      // 压缩独占期间 isDirty()/hasDirtySlots() 恒为 false（槽位数组正被压缩线程重建），若照常走到
      // 下面的 clearPending()，就会在「压缩早退、什么都没写」时把待落盘记账清零 → doPut 的上限投影
      // 少算脏桶。与 doPut / closeIdleHandles / compactGarbage 保持同一避让纪律。
      return;
    }
    try {
      if (handle.file.isDirty()) {
        int written = handle.file.flushDirty(RegionFile.FLUSH_BATCH_RECORDS);
        if (written > 0 && handle.file.hasDirtySlots()) {
          // 本批已写满预算且仍有脏记录：把余下部分排到队尾再写，让中间到达的读取先被服务
          execute(() -> flushHandle(key, handle));
          return; // 尚未全部落盘：不清零待落盘记账
        }
      }
      // 走到这里有三种情形：(a) 没有脏数据；(b) 本批已把能写的都写完；(c) 一条都写不出去（编码失败等，
      // 脏标记仍在）。(c) 必须保留待落盘记账，否则上限判定会低估，故只在确实没有脏记录时清零。
      if (!handle.file.hasDirtySlots()) {
        // 无论是否真的写过，此刻内存里的负载都已（随 bucket 整块）落到文件：待落盘记账清零
        handle.clearPending();
      }
    } catch (Throwable throwable) {
      if (!handle.file.channelOpen()) {
        // 通道已永久失效（FileChannel 一旦被线程中断或外部关闭就无法再用）。此时句柄留在表里、
        // 脏标记还在，旧实现会「每轮维护都抛一次同样的异常」——真机上表现为每 30 秒一条
        // ClosedChannelException。这里直接丢弃句柄并只提示一次，下次访问自动重建（fail-open）。
        removeAndClose(key, handle);
        stats.errors.increment();
        logger.warning("磁盘缓存区域文件通道已失效，已丢弃句柄（下次访问自动重建）："
            + handle.file.path() + "（原因：" + throwable + "）");
        return;
      }
      fail("磁盘缓存落盘失败（该区域文件将在下次维护重试）", throwable);
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
        if (handle.file.isCompacting()) {
          // 该文件正被压缩线程独占：它的 fileSize/garbageBytes 正在被整体重写，此刻读到的是中间值；
          // 且它本来也不该再作为本轮压缩候选（压缩会话本就单文件串行）。直接跳过，避免无 happens-before
          // 的跨线程读（与 closeIdleHandles / evictRegionFilesIfNeeded 同口径）。
          continue;
        }
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
      executor.schedule(() -> startCompact(candidates, key, index), COMPACT_YIELD_MILLIS,
          TimeUnit.MILLISECONDS);
    } catch (Throwable throwable) {
      compactionRunning.set(false);
      fail("磁盘缓存压缩回收排程失败（已中止本轮）", throwable);
    }
  }

  /**
   * 【磁盘线程】把第 {@code index} 个文件交给压缩线程：<b>先置独占标志再交付</b>，然后继续排下一个。
   *
   * <p>顺序是本设计的全部要害：置标志发生在提交之前，因此「磁盘线程此后对该文件的每次访问都会看到
   * {@code true} 并避让」与「压缩线程开始改写该文件状态」之间不存在窗口（见 {@link RegionFile#beginCompact()}）。
   * 压缩完成后由压缩线程在 {@code compact} 的 finally 里清标志，并接着排下一个文件。
   */
  private void startCompact(List<RegionKey> candidates, RegionKey key, int index) {
    Handle handle = !closed ? open.get(key) : null;
    if (handle == null || handle.file.isCompacting()) {
      // 句柄已被闲置关闭 / 世界卸载关闭，或在途压缩未收工：跳过本文件，直接排下一个
      scheduleCompactStep(candidates, index + 1);
      return;
    }
    // 交付前置位：此后磁盘线程对该文件的一切入口（读/写/落盘/清理/计数）都会自行避让
    handle.file.beginCompact();
    try {
      compactionExecutor.execute(() -> runCompact(candidates, key, handle, index + 1));
    } catch (Throwable throwable) {
      // 压缩线程已关闭（本实例正在关闭）：必须撤销独占标志，否则该文件永久避让
      handle.file.endCompact();
      fail("磁盘缓存压缩任务提交失败（已跳过该文件）", throwable);
      scheduleCompactStep(candidates, index + 1);
    }
  }

  /**
   * 【压缩线程】整文件重写单个区域文件；收工后在 finally 里排下一个文件（会话是单线程串行的）。
   *
   * <p>与旧实现（跑在磁盘线程上）的唯一差别是执行线程：这里能安全地长跑，因为磁盘线程已被
   * {@link RegionFile#beginCompact()} 挡在该文件之外，且其余文件仍由磁盘线程正常服务。
   *
   * <p><b>{@code compact()} 返回后的一切收尾都必须回到磁盘线程执行</b>：{@code compact} 的 finally
   * 已经 {@code endCompact()}（volatile 清标志），从那一刻起磁盘线程对该文件的避让全部解除。
   * 若此时仍在压缩线程上改 {@code Handle} 的 {@code accountedEntries} / {@code pendingBytes}
   * 或调用 {@link #removeAndClose}（内部会 {@code flushDirty} 写 channel），就与磁盘线程的
   * {@code doPut}/{@code doGet} 构成无 happens-before 的数据竞争——轻则待落盘记账被清零、单文件
   * 大小上限被击穿，重则两个线程同时写同一 FileChannel 的偏移表而损坏缓存文件。
   */
  private void runCompact(List<RegionKey> candidates, RegionKey key, Handle handle, int nextIndex) {
    try {
      int dropped = 0;
      Throwable failure = null;
      try {
        dropped = handle.file.compact((chunkIndex, entry) -> keepEntry(entry));
      } catch (Throwable throwable) {
        failure = throwable;
      }
      // 收尾（记账 / 失败关闭）一律回投磁盘线程：那是 Handle 与 RegionFile 状态唯一的合法所有者。
      final int compactDropped = dropped;
      final Throwable compactFailure = failure;
      if (!execute(() -> finishCompact(key, handle, compactDropped, compactFailure))) {
        // 未入队有且只有两种成因，处理方式必须区分：
        // · 已关闭：最终落盘 + 关闭句柄的任务已由 close() 排入磁盘线程，句柄会被正常关闭，
        //   这里什么都不做即可（绝不在此线程 close——close() 只是"已提交"该任务，磁盘线程可能
        //   正在执行它，两个线程同时 close/flush 同一 FileChannel 会踩坏偏移表）；
        // · 积压被拒：磁盘线程仍然存活且照常使用该句柄。此时若在压缩线程上 close，就是与
        //   doPut/doGet/flushAll 并发写同一个 FileChannel —— 正是本轮要消除的那类竞争。
        // 跳过收尾的代价是记账偏保守（accountedEntries 多算、pendingBytes 偏大）：
        // 前者在句柄下次重开时按真实条目数重扫纠正，后者由下一次 flushAll 的 clearPending() 归零，
        // 两者都只是"少收几条缓存"，绝不会损坏文件，也不会卡住封包链路。
        logger.fine("磁盘缓存压缩收尾未能回投磁盘线程（已跳过本轮记账，缓存只是少收几条）");
      }
    } finally {
      scheduleCompactStep(candidates, nextIndex);
    }
  }

  /** 【磁盘线程】压缩收工后的记账与失败清理（见 {@link #runCompact} 的线程纪律说明）。 */
  private void finishCompact(RegionKey key, Handle handle, int dropped, Throwable failure) {
    if (failure != null) {
      fail("磁盘缓存压缩回收失败，已关闭该区域文件句柄：" + handle.file.path(), failure);
      removeAndClose(key, handle);
      return;
    }
    if (dropped > 0) {
      handle.accountedEntries = Math.max(0L, handle.accountedEntries - dropped);
      approximateEntries.updateAndGet(value -> Math.max(0, value - dropped));
    }
    // 只有「压缩确实整文件重写并落盘」才清零待落盘记账：compact 的两条早退（锁不可用 / 预算超限）
    // 并不会重写文件，脏槽位仍在内存——此时若清零，下一批 flush 的 projected 字节会漏算这些脏桶，
    // 文件可能在「判定未到上限」的情况下被顶破单文件上限。dirty 全空才视为已重写。
    if (!handle.file.hasDirtySlots()) {
      handle.clearPending();
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

  /** 压缩收尾兜底的最长等待（秒）。 */
  private static final int COMPACT_DRAIN_SECONDS = 5;

  /** 压缩收尾兜底的轮询间隔（毫秒）。 */
  private static final long COMPACT_DRAIN_POLL_MILLIS = 20L;

  /**
   * 关闭时若压缩线程超时仍未收工，对那些「因压缩独占而被 {@link #removeAndClose} 跳过」的句柄做兜底关闭。
   *
   * <p><b>为什么需要</b>：{@code close()} 先等压缩线程（{@code CLOSE_TIMEOUT_MILLIS}），超时后仍继续
   * 排空磁盘线程；{@code closeAllHandles} → {@code removeAndClose} 见到 {@code isCompacting()} 会整条跳过。
   * 而压缩结束后 {@code runCompact} 的收尾 {@code execute(finishCompact)} 会因 {@code closed} 被拒——
   * 于是该 {@code FileChannel} 与文件锁<b>永远没人关闭</b>（每次 reload 泄漏一个句柄与一把锁）。
   *
   * <p><b>安全前提</b>：本方法只在 {@code close()} 里、且仍在<b>磁盘线程</b>上执行；此刻压缩线程早已
   * {@code shutdown()} + {@code awaitTermination}，唯一可能的并发是「那一轮压缩马上跑完」。因此这里
   * 自旋等待 {@code isCompacting()} 变为 false（有硬上限），一旦看到 false，说明压缩线程已经不再触碰
   * 该 {@code RegionFile}，随后在同一线程上 close 不会与任何写者交错。
   */
  private void closeCompactingHandlesAfterShutdown() {
    // 绝大多数关闭都没有未收工的压缩：先做一次零分配的快速判断，避免每次关闭都多遍历一遍句柄表。
    boolean anyCompacting = false;
    for (Handle handle : open.values()) {
      if (handle.file.isCompacting()) {
        anyCompacting = true;
        break;
      }
    }
    if (!anyCompacting) {
      return;
    }
    // 截止时刻对整个循环只算一次：逐个文件各自等满 5 秒会让「N 个文件都卡住」变成 N×5 秒，
    // 而本方法跑在磁盘线程上、close() 还在等它收工。
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMPACT_DRAIN_SECONDS);
    boolean warned = false;
    for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
      Handle handle = entry.getValue();
      while (handle.file.isCompacting()) {
        if (System.nanoTime() >= deadline) {
          if (!warned) {
            warned = true;
            logger.warning("磁盘缓存关闭时仍有区域文件处于压缩中且未在 "
                + COMPACT_DRAIN_SECONDS + " 秒内收工，相关句柄留待进程退出时释放（缓存只是可选加速）");
          }
          break;
        }
        try {
          Thread.sleep(COMPACT_DRAIN_POLL_MILLIS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      if (handle.file.isCompacting()) {
        continue; // 仍是压缩独占：绝不能在压缩线程还在用它时关闭通道
      }
      removeAndClose(entry.getKey(), handle);
    }
  }

  /**
   * 从打开表摘除句柄并关闭它，同时<b>收回该句柄计入的条目额度</b>并把该键从「已补计」集合移除——
   * 让下次打开时重新扫描磁盘、重新补计（否则计数只增不减，{@code max-entries} 会随句柄开关不断抬高）。
   *
   * <p>只有磁盘线程调用；{@link ConcurrentHashMap#remove(Object, Object)} 返回 false（句柄已被别处移除）时不做任何事。
   */
  private void removeAndClose(RegionKey key, Handle handle) {
    removeAndClose(key, handle, false);
  }

  /**
   * 摘除并关闭句柄；{@code deleteFile} 为真时连磁盘文件一起删除（<b>文件级淘汰</b>专用）。
   *
   * <p><b>为什么淘汰必须删文件</b>：{@code max-region-files} 的语义是「磁盘上最多保留多少个区域文件」。
   * 若淘汰只关句柄不删文件，它实际只约束了「同时打开的句柄数」：被淘汰的文件仍在磁盘上，下次访问直接
   * 命中并重新计入条目额度，过期条目又只在被读到时才清理 —— 于是再也不会被访问的文件永久滞留，
   * 磁盘文件数随「访问过的 region 数」单调增长（单文件上限 16 MiB，探索型服务器可涨到数十 GB）。
   *
   * <p>删除是安全的：缓存只是可选加速层，丢掉的文件下次访问按未命中重建。只在
   * {@code !lockUnavailable && !isCompacting} 时删（前者说明文件归别的进程，后者说明压缩线程正独占它）。
   */
  private void removeAndClose(RegionKey key, Handle handle, boolean deleteFile) {
    if (handle.file.isCompacting()) {
      // 该文件正被压缩线程独占：绝不能在压缩进行中关闭它的通道（那会让压缩以 ClosedChannelException
      // 失败）。留给下一次维护重试；压缩线程自身的失败路径也会调本方法，那时标志已由 compact 的
      // finally 清除，不受此判定影响。
      return;
    }
    if (!open.remove(key, handle)) {
      return;
    }
    long accounted = handle.accountedEntries;
    if (accounted > 0L) {
      handle.accountedEntries = 0L;
      approximateEntries.updateAndGet(value -> (int) Math.max(0L, value - accounted));
    }
    entryCounted.remove(key);
    closeHandleQuietly(handle, deleteFile);
  }

  /** 落盘 + 关闭；空文件或「淘汰要求删文件」时删除磁盘文件。 */
  private void closeHandleQuietly(Handle handle, boolean deleteFile) {
    try {
      boolean empty = handle.file.isEmptyFile();
      boolean deletable = deleteFile && !handle.file.lockUnavailable();
      if (empty || deletable) {
        // 文件随后即删除：不落盘（避免整桶重编码 + 压缩 + fsync 的白做），只关通道让删除可行
        handle.file.closeDiscarding();
      } else {
        handle.file.close();
      }
      if (empty || deletable) {
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
    // 先入表、再排程补计：补计提交被拒时，句柄必须已经在表里，才能被 removeAndClose 真正摘除
    // （它只处理表内的句柄）。这里仍保留「先到者优先」的兜底——全部操作都在磁盘线程上串行，正常撞不上。
    Handle raced = open.putIfAbsent(key, created);
    if (raced != null) {
      // 只 close 不 delete——文件可能正被先到者使用，删除会破坏对方句柄（A9）。
      try {
        created.file.close();
      } catch (Throwable throwable) {
        fail("关闭重复打开的磁盘缓存区域文件失败（不影响先到者）", throwable);
      }
      return raced;
    }
    if (entryCounted.add(key) && file.sizeBytes() > BufferedLinearV3Format.DATA_AREA_OFFSET) {
      // 重启后 approximateEntries 从 0 开始：必须把磁盘上已有的条目补计进来，
      // 否则 max-entries 上限在重启后完全失效（旧条目不占额度，磁盘可无限增长）。
      // 异步一次性扫描（逐 bucket 解码计数），不阻塞触发本次打开的读写；此后由
      // put/remove/compact 增量维护。排程与扫描之间恰被落盘的新写入会被重复计入一次——
      // 计数本就是近似值，偏差方向是「提前拒绝新写入」，保守无害。
      // execute() 已把「队列满 / 已关闭 / RejectedExecutionException」统一折算成 false 并计入 errors，
      // 四个出口都只 return，不会抛——因此直接看返回值。
      boolean scheduled = execute(() -> countDiskEntries(key, created));
      if (!scheduled) {
        // 提交被拒时不能只清 entryCounted 就返回：句柄此刻已在 open 表里，之后 handle() 会直接命中
        // 这个已有句柄、再也不会走到本分支，注释承诺的「下次打开重试」永远不会发生——该文件的旧条目
        // 从此不占 max-entries 额度，磁盘可超上限增长。因此按既有语义把句柄摘除并关闭
        // （removeAndClose 会一并归还额度、清掉 entryCounted 标记），让下一次打开重新扫描补计；
        // 本次读写按未命中/跳过降级（fail-open）。
        removeAndClose(key, created);
        logger.warning("磁盘缓存补计任务未能提交，已摘除该区域文件句柄"
            + "（下次打开会重新补计，本次读写按未命中处理）：" + path);
        return null;
      }
    }
    // 新开一个文件后立刻检查「打开的文件数」上限：超限就按最久未访问淘汰整个区域文件。
    // 放在这里而不是维护周期，是因为磁盘占用增长只发生在「新开文件」这一刻；早一步回收就不会先涨后降。
    evictRegionFilesIfNeeded();
    if (open.get(key) != created) {
      // 极端情况：本毫秒内刚打开的这个句柄恰好被上面那次淘汰挑中（多个句柄时间戳相同）。
      // 句柄已被关闭，绝不能返回给调用方，按未命中/跳过降级（fail-open）。
      return null;
    }
    return created;
  }

  /**
   * 打开的区域文件数超上限时，按「最久未访问」淘汰整个文件（真正的磁盘回收）。
   *
   * <p><b>为什么必须有这一层</b>：{@code max-entries} 只统计<b>当前已打开</b>文件的条目——关句柄即归还
   * 额度、重开才由 {@code countDiskEntries} 补计，因此它约束的是「同时打开的文件的条目数」，
   * 而不是磁盘上的总量。若只靠它，磁盘条目可达「区域文件数 × 每文件条目数」，且触顶后旧实现只拒写、
   * 不淘汰，缓存会永久停更。{@code max-region-files} 直接约束磁盘上的文件数，二者共同界定磁盘占用：
   * 前者控「每个文件多满」，后者控「一共有几个文件」。
   *
   * <p><b>为什么淘汰整文件而不是逐条目</b>：缓存只是可选加速层，丢一个文件只影响命中率、不影响正确性；
   * 而逐条目淘汰需要重写文件（压缩），成本高得多。被淘汰的文件下次访问时按未命中重建。
   *
   * <p>只在磁盘线程上调用（{@link #handle} 内），因此对 {@code open} 的遍历与摘除无需额外加锁；
   * 压缩中的句柄一律跳过——它正独占该文件，此时关闭通道会让压缩失败。
   */
  private void evictRegionFilesIfNeeded() {
    int limit = Math.max(1, config.maxRegionFiles());
    int excess = open.size() - limit;
    if (excess <= 0) {
      return;
    }
    int before = open.size();
    while (open.size() > limit) {
      RegionKey victimKey = null;
      Handle victim = null;
      for (Map.Entry<RegionKey, Handle> entry : open.entrySet()) {
        Handle candidate = entry.getValue();
        if (candidate.file.isCompacting()) {
          continue; // 压缩独占：此时关闭通道会让压缩以 ClosedChannelException 失败
        }
        // 用单调时钟比较先后：墙钟毫秒会被系统对时/夏令时回拨，且同一毫秒内的多个句柄会退化成
        // 哈希顺序（可能误淘汰刚用过的热文件）。lastAccessNanos 与「闲置关句柄」用的是同一个时钟。
        if (victim == null || candidate.lastAccessNanos < victim.lastAccessNanos) {
          victim = candidate;
          victimKey = entry.getKey();
        }
      }
      if (victim == null) {
        // 全部句柄都在压缩中：本轮放弃，等下一次打开或维护周期再试（容量暂时超标可接受）
        return;
      }
      // deleteFile=true：这才是「真正的磁盘回收」——只关句柄不删文件等于没回收（见 removeAndClose 说明）。
      removeAndClose(victimKey, victim, true);
      if (open.size() >= before) {
        // 兜底：removeAndClose 未能摘除（句柄已被别处移除等），避免死循环
        return;
      }
      before = open.size();
    }
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
      // 计数失败只影响上限的准确度（偏小），绝不影响缓存读写（fail-open）。
      // 同时归还「已补计」标记：否则该句柄存续期间 max-entries 对这份文件完全失明（旧条目不占额度），
      // 与「宁可偏大提前拒绝」的取向相反。归还后下次句柄重开时会重新扫描补计。
      entryCounted.remove(key);
      fail("统计磁盘缓存既有条目失败（max-entries 计数可能偏小，不影响缓存功能）", throwable);
    }
  }

  private Path pathFor(String worldName, int regionX, int regionZ) {
    return rootDir.resolve(sanitize(worldName))
        .resolve("r." + regionX + "." + regionZ + REGION_FILE_SUFFIX);
  }

  /**
   * 把<b>原始</b>世界名混入配置指纹，得到真正写进条目的指纹（读、写两侧都必须走这里）。
   *
   * <p><b>为什么必须混</b>：缓存目录名是 {@link #sanitize} 归一化后的世界名，{@code world-1} 与
   * {@code world_1} 会落到同一目录、共用同一个区域文件；而改写结果还依赖世界名（伪装方块的随机种子、
   * {@code world-overrides} 的隐藏清单）。若指纹只由配置决定，两个世界就会互相命中：hide-blocks 更少的
   * 一方写下的负载被另一方读走，本该藏起来的矿直接露给客户端。混入世界名后，跨世界的条目必然指纹不符，
   * 按未命中惰性清理（计入 {@code configMismatchRemoved}），不会再互相命中。
   *
   * <p><b>为什么用 {@link String#hashCode()}</b>：它的算法由语言规范固定，同一世界名在任意进程、任意
   * JDK 上都得到同一个值，符合类注释「同一份配置在任意进程算出同一指纹」的要求，且零分配。世界名不同但
   * 哈希相同（一对世界名约 2^-32 量级）时只会退化成「两个世界共用条目」，即修复前的行为，不会更差；
   * 条目里能放指纹的字段只有 {@code int}，再叠加强摘要也换不来更多位宽，故不引入额外计算。
   *
   * <p>本条改动会让旧版本写下的磁盘条目一律指纹不符，首次读取时被惰性清理并重建一次
   * （缓存只是可选加速，这次重建的代价可以接受）。
   */
  static int mixWorldName(String worldName, int configHash) {
    return configHash * 31 + worldName.hashCode();
  }

  /**
   * 世界名 → 目录名：只保留安全字符，避免路径穿越或非法文件名。
   *
   * <p><b>目录名碰撞</b>：非安全字符统一替换为 {@code '_'} 并转小写，因此形如 {@code a/b}、{@code a_b}、
   * {@code A_B} 的不同世界名会落到同一目录、共用同一物理文件。本方法只负责「路径安全」，<b>不负责区分
   * 世界</b>：区分世界由条目指纹完成——读、写两侧都把<b>原始世界名</b>的哈希混进指纹（见
   * {@link #mixWorldName}），于是共用同一文件的另一个世界写下的条目在本世界看来指纹不符，按未命中惰性
   * 清理，绝不会被当作自己的改写结果返回（否则 hide-blocks 更少的一方会把真矿露给对方）。代价只是这类
   * 同名世界各自不复用对方的条目，命中率略低。
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
    String normalized = builder.length() == 0 ? "unknown" : builder.toString().toLowerCase(Locale.ROOT);
    // 只由点组成（"." / ".." / "..."）会解析成父目录或自身：pathFor 的 resolve 会逃出缓存根目录，
    // 之后 invalidateWorld 的 Files.deleteIfExists 甚至可能在缓存目录之外删文件。
    // 世界名由服主控制、现实触发面极小，但归一化代价为零，直接兜住。
    return normalized.chars().allMatch(character -> character == '.') ? "unknown" : normalized;
  }

  // ------------------------------------------------------------------ 调度

  /**
   * 提交一个不等待结果的任务（写入、落盘等）。
   *
   * @return true = 已成功入队（任务一定会被执行）；false = 未入队（已关闭 / 积压被拒 / 提交异常）。
   *         调用方若依赖「任务必然执行」来做收尾（如压缩后的记账），必须检查该返回值。
   */
  private boolean execute(ThrowingRunnable task) {
    // closed 也视为「不可提交」：关闭排队期间仍可能有工作线程越过 usable() 判定才提交，
    // 若放行就会在排空之后重新打开句柄（见 close() 说明）。
    if (closed || executor == null || executor.isShutdown()) {
      return false;
    }
    if (pendingOps.get() >= config.queueCapacity()) {
      stats.rejectedByBacklog.increment();
      return false;
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
      return true;
    } catch (Throwable throwable) {
      pendingOps.decrementAndGet();
      stats.errors.increment();
      return false;
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
      stats.rejectedByBacklog.increment(); // 读因积压被拒即降级为未命中，必须可见（否则只表现为命中率偏低）
      return null;
    }

    pendingOps.incrementAndGet();
    // 「本次提交的额度只归还一次」的令牌：超时路径与任务 finally 都靠它做 claim-once。
    //
    // 为什么不能用 future.cancel(false) 的返回值判断：FutureTask.cancel 对**正在运行**的任务同样返回
    // true（运行中状态仍是 NEW，只有 set() 才离开 NEW）。于是「任务在 50ms 内超时、但仍在磁盘线程上
    // 执行」时，读线程扣一次、任务 finally 再扣一次 → pendingOps 每次 -1 漂移，变负后三处队列上限
    // （读/写/收尾）永远为假，积压保护彻底失效且 rejectedByBacklog 不再自增。
    AtomicBoolean settled = new AtomicBoolean();
    Future<T> future;
    try {
      future = executor.submit(() -> {
        try {
          return task.call();
        } finally {
          if (settled.compareAndSet(false, true)) {
            pendingOps.decrementAndGet();
          }
        }
      });
    } catch (Throwable throwable) {
      if (settled.compareAndSet(false, true)) {
        pendingOps.decrementAndGet();
      }
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
      //
      // pendingOps 必须精确偿还：计数在提交前自增，唯一的递减点在任务 lambda 的 finally 里。
      // 若任务「尚未被工作线程取出」，cancel(false) 会把 FutureTask 直接置为 CANCELLED，
      // 该 callable 永不执行 → 其 finally 永不运行 → 计数每超时一次永久 +1；
      // 累积到 queueCapacity 后（put 与 submit 都以它为门限）磁盘缓存会静默彻底停用，直到重启。
      // 因此：cancel 返回 true（确认任务未运行、finally 不会执行）时由本线程补一次递减；
      // cancel 返回 false 说明任务已在运行或已完成，其 finally 会（或已经）递减，不得重复扣减。
      future.cancel(false);
      if (settled.compareAndSet(false, true)) {
        // 抢到令牌 ⇒ 任务尚未开始（其 finally 不会执行）⇒ 由本线程归还额度。
        // 抢不到 ⇒ 任务已开始或已结束，其 finally 已归还，绝不重复扣减。
        pendingOps.decrementAndGet();
      }
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
      stats.rejectedByBacklog.increment(); // 收尾操作（落盘/关句柄）被跳过：脏数据滞留内存更久，必须可见
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