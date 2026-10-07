package net.mikumc.mikuxraynet.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 单个区域缓存文件（{@code r.<regionX>.<regionZ>.b_linear}）的读写句柄，格式见 {@link BufferedLinearV3Format}。
 *
 * <p><b>线程纪律</b>：所有方法都只在 {@link DiskCacheStore} 的磁盘线程上调用（单线程串行），
 * 因此内部只用普通字段与一把逻辑串行化，不做额外的线程安全处理。
 *
 * <p><b>内存纪律</b>：bucket 按需（懒）加载，并用一个受 {@code bucketCacheSize} 限制的 LRU 持有
 * 已加载槽位；被淘汰的脏 bucket 会先落盘再释放，因此内存占用有界且不会丢写入。
 * 类本身只持有文件路径与字节数据，不持有 World / Chunk / Player 引用。
 *
 * <p><b>落盘时机</b>：{@link #put} 只改内存并标脏；真正写盘发生在 {@link #flushDirty()}（由维护任务、
 * 显式 flush、世界卸载与关闭触发）。这是为了把「每条区块包都重压缩一个 bucket」的高开销摊薄。
 *
 * <p><b>fail-open</b>：读路径遇到任何结构损坏都退化为「未命中」（单个槽位损坏只丢该槽位）；
 * 写路径失败由调用方降级为纯内存缓存。
 */
final class RegionFile implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(RegionFile.class.getName());

  /** 「未取得文件锁」的 WARN 只打一次（多文件被占用时不刷屏）。 */
  private static final AtomicBoolean LOCK_WARNING = new AtomicBoolean();

  /**
   * 桶头声明的 {@code rawLength} 相对<b>整文件大小</b>的许可倍数上限。
   *
   * <p><b>为什么需要</b>：{@code rawLength} 直接来自（可能损坏/被篡改的）文件，解压会按它先分配原始
   * 缓冲区。单靠 {@code BufferedLinearV3Format} 的 {@link BufferedLinearV3Format#MAX_RAW_SIZE}（128 MiB）
   * 与「压缩比」闸门仍留有一个洞：压缩长度约 16 KB 的桶恰好能声明到约 128 MiB raw（8192 × 16 KB）通过
   * 压缩比检查，16 个桶叠加即约 2 GiB 的分配尖峰。用一个与文件实际大小挂钩的钳制即可堵住它——
   * 桶占文件字节数很小，却声明出远超文件的原始长度，只可能是伪造头。
   *
   * <p><b>为什么取 1024</b>：真实负载里「单桶原始长度 / 整文件字节」远小于 100（区块负载压缩比通常个位数
   * 到几十；即便单个高重复桶，其原始长度也不会超过整文件的千倍），1024 留足 >10 倍余量、绝不误伤合法桶；
   * 同时它远小于 8192，可把上述「16 KB 声明 128 MiB」的伪造头直接拒之门外（该类伪造需要 8192 倍）。
   */
  private static final long MAX_RAW_LENGTH_RATIO = 1024L;

  /**
   * 单次 {@link #compact} 允许累计解压的原始字节上限（256 MiB）。
   *
   * <p>压缩要先把全部 bucket 载入内存（见 {@link #compact} 的内存尖峰说明）。即便每个桶都通过了
   * {@link #MAX_RAW_LENGTH_RATIO} 钳制，16 个桶叠加仍可能很大；这里对「累计解压字节」再加一道总量闸：
   * 超限即中止本次压缩、直接抛异常（此时尚未创建临时文件，原文件毫发无损，交由调用方走既有安全路径）。
   * 取值依据：合法文件的活动数据受 {@code max-file-size-mb}（默认远小于此）与逐条目负载上限共同约束，
   * 整文件解压后极少超过 256 MiB，故它只拦异常/伪造输入。
   */
  private static final long MAX_COMPACT_TOTAL_RAW = 256L * 1024 * 1024;

  /** 条目的保留判定（用于压缩回收时丢弃过期/旧代次条目）。 */
  @FunctionalInterface
  interface EntryFilter {
    boolean keep(int chunkIndex, BufferedLinearV3Format.Entry entry);
  }

  /**
   * 读路径懒加载的桶：持有解压后的整桶原始字节与已解析好的槽位表，请求哪个槽位才解码哪个。
   *
   * <p><b>与 {@link #slots} 的关系</b>：一个桶要么是「已整桶解码」（{@code slots[bucket] != null}），
   * 要么是「只解析了槽位表」的 {@code LazyBucket}，二者互斥；需要整桶时（写 / 压缩回收）由
   * {@link #ensureLoaded} 把 LazyBucket 一次性解码并转入 {@code slots}。
   */
  private static final class LazyBucket {

    /** 解压后的整桶原始字节（槽位表与全部条目数据都在其中）。 */
    final byte[] raw;
    /** 每个槽位条目在 {@link #raw} 中的起始偏移；{@code -1} = 空槽（或结构损坏后的整桶作废）。 */
    final int[] offsets;
    /** 每个槽位条目的字节长度；{@code 0} = 空槽。 */
    final int[] lengths;

    LazyBucket(byte[] raw, int[] offsets, int[] lengths) {
      this.raw = raw;
      this.offsets = offsets;
      this.lengths = lengths;
    }
  }

  private final Path path;
  private final int hashSeed;
  private final int bucketCacheSize;
  /**
   * 本文件使用的压缩方案字节（文件头偏移 9）。<b>读取与写入都只用它</b>：读取据此选解压器，
   * 写入（{@link #flushBucket} / {@link #compact}）也传它去压缩 —— 绝不用全局
   * {@code currentCompression()}，否则既有 Deflate 文件在 zstd 可用后追加写会写出 zstd 数据、
   * 头部却仍是 0x01，读回整桶当空、刚写入的条目静默丢失。
   * 仅在「创建新文件」与「声明方案不可用时的整文件迁移」时改写。
   */
  private byte compression;
  private final long[] positions = new long[BufferedLinearV3Format.BUCKET_COUNT];
  private final long[] bucketSizes = new long[BufferedLinearV3Format.BUCKET_COUNT];
  private final BufferedLinearV3Format.Entry[][] slots =
      new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_COUNT][];
  private final boolean[] dirty = new boolean[BufferedLinearV3Format.BUCKET_COUNT];
  /**
   * 读路径懒加载的桶（与 {@link #slots} 互斥，见 {@link LazyBucket}）：只解析槽位表、按需解码单个槽位。
   * 写 / 压缩需要整桶时由 {@link #ensureLoaded} 一次性解码并转入 {@link #slots}。
   */
  private final LazyBucket[] lazyBuckets = new LazyBucket[BufferedLinearV3Format.BUCKET_COUNT];

  /** 已加载 bucket 的 LRU（accessOrder=true，值恒为 FALSE，仅借其顺序）。 */
  private final LinkedHashMap<Integer, Boolean> loaded = new LinkedHashMap<>(8, 0.75f, true);

  private FileChannel channel;
  /**
   * 跨进程排他锁：持有强引用以防 {@link FileLock} 被 GC 而提前释放；
   * 通道关闭（close / compact 替换文件）时由 JVM 自动释放，故不做显式 release。
   * 「同 JVM 自持」时不重复持锁，保持 {@code null}（见 {@link #acquireLock(java.nio.channels.FileChannel)}）。
   */
  private FileLock lock;
  /**
   * 跨进程锁被其它进程占用（{@code tryLock} 返回 null 或抛异常）时置 true：本实例对该文件
   * <b>读写全部停用</b>——读一律视为未命中、写一律跳过，只打一次性中文 WARN。
   * <p>这是「两个服务端实例共享同一缓存目录」这一误配下的正确行为：既然锁不在本进程手里，
   * 就不能再读写该文件，否则两个实例会互相踩（旧行为是「只 WARN 后照常读写」）。
   */
  private boolean lockUnavailable;
  private long fileSize;
  private long liveBytes;
  private long garbageBytes;
  private boolean compacting;
  private boolean closed;

  private RegionFile(Path path, FileChannel channel, int hashSeed, byte compression,
      int bucketCacheSize, boolean lockUnavailable) {
    this.path = path;
    this.channel = channel;
    this.hashSeed = hashSeed;
    this.compression = compression;
    this.bucketCacheSize = Math.max(1, bucketCacheSize);
    this.lockUnavailable = lockUnavailable;
  }

  /** 取锁结果：{@code lock} 为取得的锁（自持或占用时为 {@code null}）；{@code occupied} = 被其它进程占用。 */
  private record LockHold(FileLock lock, boolean occupied) {
  }

  /**
   * 尝试取跨进程排他文件锁，并把结果归为两类（<b>必须在读取文件内容之前调用</b>）。
   *
   * <p><b>被占用（{@code occupied=true}）就停用本实例的读写</b>（见 {@link #lockUnavailable}），而不是旧
   * 行为的「只 WARN 后照常读写」：「两个服务端实例共享同一缓存目录」时，锁在对方手里说明该文件此刻归
   * 对方所有，本实例再去读写只会与对方互相踩。停用后读退化为未命中、写被跳过，交给上层回退重算，功能
   * 毫发无损（fail-open）。
   *
   * <p><b>关键例外——同 JVM 自持</b>：{@code tryLock()} 抛 {@link OverlappingFileLockException} 表示锁
   * 被<b>本 JVM 的另一个句柄</b>持有（reload / 重开时旧句柄尚未关闭，或并发开同一文件），这不是「另一个
   * 服务端实例」。此时按<b>可用</b>处理且本实例不重复持锁，否则会把本进程自己的磁盘缓存永久停用。
   * 判定依据：{@code FileLock} 的重叠检测在同一 JVM 内以文件为键，抛此异常即证明锁属于本进程；
   * 跨进程占用只会让 {@code tryLock()} 返回 {@code null}。
   */
  private static LockHold acquireLock(FileChannel channel) {
    Boolean override = lockOutcomeOverrideForTest;
    if (override != null) {
      // 测试注入（见 lockOutcomeOverrideForTest）：只替换「判定」，读写降级逻辑仍是生产代码
      return new LockHold(null, !override); // true = 按自持（可用）；false = 按占用（停用）
    }
    try {
      FileLock acquired = channel.tryLock();
      return new LockHold(acquired, acquired == null);
    } catch (Throwable throwable) {
      return new LockHold(null, !isSelfHeld(throwable));
    }
  }

  /**
   * 同 JVM 自持判定：只有 {@link OverlappingFileLockException} 表示锁被<b>本 JVM 的其它句柄</b>持有
   * （{@code FileLock} 的重叠检测在同一 JVM 内以文件为键）；跨进程占用则表现为 {@code tryLock()} 返回
   * {@code null}。此处单独成函数以便回归测试直接断言该映射。
   */
  static boolean isSelfHeld(Throwable throwable) {
    return throwable instanceof OverlappingFileLockException;
  }

  /** 锁不可用的中文 WARN 只打一次（多文件被占用时不刷屏）。 */
  private static void warnLockUnavailableOnce() {
    if (LOCK_WARNING.compareAndSet(false, true)) {
      LOGGER.warning("磁盘缓存区域文件已被其它进程占用，本实例对它的磁盘缓存已停用"
          + "（读视为未命中、写被跳过，功能不受影响）；请勿让两个服务端实例共享同一缓存目录");
    }
  }

  /**
   * <b>仅测试用</b>：强制 {@link #acquireLock(FileChannel)} 的判定结果（{@code null} = 走真实
   * {@code tryLock()}；{@code true} = 按「同 JVM 自持」；{@code false} = 按「被其它进程占用」）。
   *
   * <p><b>为什么必须注入</b>：本机（Windows）的 {@code FileLock} 是<b>强制锁</b>——同一 JVM 的另一
   * 句柄持锁时，新句柄的读写会被操作系统直接拒绝（实测：「另一个程序已锁定文件的一部分，进程无法访问」），
   * 因此无法用真实锁在同一进程内复现「自持」与「被占用」两种判定并完成读写。注入只替换「判定」本身，
   * 读/写降级与提示仍是生产代码，测试因此是确定性的。
   */
  static volatile Boolean lockOutcomeOverrideForTest;

  /**
   * 打开（必要时创建）区域文件。
   *
   * <p>文件头损坏时抛 {@link IOException}，由调用方决定「删掉重建」还是「跳过」。
   */
  static RegionFile open(Path path, int bucketCacheSize) throws IOException {
    FileChannel channel = FileChannel.open(path,
        StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    // 先取锁、再读文件内容：被占用时立即停用，绝不尝试读取对方正在写的文件。
    // 顺序很关键——Windows 的 FileLock 是强制锁，读被占用文件的区域会直接抛 IOException
    //（实测「另一个程序已锁定文件的一部分」）；若先读头，open 会失败而不是优雅停用。
    LockHold hold = acquireLock(channel);
    if (hold.occupied()) {
      warnLockUnavailableOnce();
      // 停用态：不读头、不建索引，返回一个只在内存里的「空壳」句柄——后续 get 一律未命中、
      // put/flush/compact 一律跳过（见各方法开头的判定），功能不受影响（fail-open）。
      return new RegionFile(path, channel, BufferedLinearV3Format.DEFAULT_HASH_SEED,
          BufferedLinearV3Format.currentCompression(), bucketCacheSize, true);
    }

    int hashSeed = BufferedLinearV3Format.DEFAULT_HASH_SEED;
    byte compression = BufferedLinearV3Format.currentCompression();
    long size = channel.size();
    try {
      if (size > 0) {
        if (size < BufferedLinearV3Format.DATA_AREA_OFFSET) {
          throw new IOException("区域文件过小（" + size + " 字节）");
        }
        // 读取种子与压缩方案：文件头里「格式版本」与「压缩方案」是两个独立字段——
        // 格式版本当前为 0x04（0x03 等旧版本由 decodeHeaderInfo 明确拒绝）；
        // 压缩方案 0x02(zstd) 为当前写入值，0x01(Deflate) 是历史格式，两者都要能读。
        BufferedLinearV3Format.Header header = BufferedLinearV3Format.decodeHeaderInfo(
            readAt(channel, 0, BufferedLinearV3Format.HEADER_SIZE));
        hashSeed = header.hashSeed();
        compression = header.compression();
      }
    } catch (IOException exception) {
      closeQuietly(channel);
      throw exception;
    }

    RegionFile file = new RegionFile(path, channel, hashSeed, compression, bucketCacheSize, false);
    // 已取得的锁必须持强引用（防止被 GC 提前释放）；「同 JVM 自持」时 hold.lock() 为 null（不重复持锁）。
    file.lock = hold.lock();
    try {
      if (size <= 0L) {
        // 新建（或刚被清空）的文件：必须先落「文件头 + 全零偏移表」，
        // 否则后续 append 会从偏移 0 开始写，直接把头部与偏移表踩掉（重开时被判为损坏）。
        file.writeHeaderAndEmptyTable();
      } else {
        file.loadIndex(size);
        // 文件声明的方案在本进程不可用（头=zstd 但 zstd 库缺失）→ 整文件迁移为可行方案。
        // 选「open 时迁移」而不是「写入时逐桶降级」（二选一）：头部方案字节是整文件唯一的，
        // 逐桶降级无法改变头部，只会再次造成「头与数据错配」；迁移后 compression 恒为可行方案，
        // 后续所有写入都按它压缩，读回一定能解开。
        if (!BufferedLinearV3Format.compressionAvailable(compression)) {
          file.migrateToFeasibleCompression();
        }
      }
    } catch (IOException exception) {
      file.closeQuietly();
      throw exception;
    }
    return file;
  }

  /**
   * 写入文件头与<b>当前偏移表</b>，并把数据区起始位置作为当前文件长度。
   *
   * <p>写的是 {@link #positions} 这一刻的内容（方法名里的 EmptyTable 只描述「新建/迁移」这两个主要调用路径：
   * 此时 positions 全为 0）；{@link #flushBucket} 的兜底路径也复用它，若那里 positions 已非全零，写出的
   * 就是当前索引而非全零表——因此按「当前偏移表」理解，不能假定恒为全零。
   *
   * <p>头部方案字节<b>取本实例的 {@link #compression}</b>（新文件在构造时已定为
   * {@code currentCompression()}；迁移时已先改为可行方案），保证「声明的方案」与随后 bucket
   * 的实际压缩一致。
   */
  private void writeHeaderAndEmptyTable() throws IOException {
    writeFully(channel, ByteBuffer.wrap(BufferedLinearV3Format.encodeHeader(hashSeed, compression)), 0L);
    writeFully(channel, ByteBuffer.wrap(BufferedLinearV3Format.encodePosTable(positions)),
        BufferedLinearV3Format.POS_TABLE_OFFSET);
    channel.force(false);
    this.fileSize = BufferedLinearV3Format.DATA_AREA_OFFSET;
  }

  /**
   * 把「声明方案在本进程不可用」的区域文件整文件迁移为可行方案。
   *
   * <p><b>为什么是「重建」而非「逐桶换算法」</b>：按原方案（zstd）写入的 bucket 在本进程根本解不开
   * （{@code decompress} 必抛异常、读路径本就把整桶当空），因此不存在可保留的旧数据。直接把头部方案
   * 改写为可行方案并清空偏移表，即可让「头部声明」与「后续写入」重新一致，失败模式从
   * 「静默丢新写入」变成「明确丢弃本就读不到的旧数据」，并严守「读回一定能解开」这一更强不变式。
   */
  private void migrateToFeasibleCompression() throws IOException {
    byte feasible = BufferedLinearV3Format.currentCompression(); // zstd 不可用时为 Deflate
    BufferedLinearV3Format.warnSchemeDowngradeOnce(compression, feasible);
    this.compression = feasible;
    // 先丢弃不可读的旧索引，再重写头部与全零偏移表（顺序不能反，否则会把旧偏移写回偏移表）
    for (int bucket = 0; bucket < BufferedLinearV3Format.BUCKET_COUNT; bucket++) {
      positions[bucket] = 0L;
      bucketSizes[bucket] = 0L;
      dirty[bucket] = false;
      slots[bucket] = null;
      lazyBuckets[bucket] = null;
    }
    loaded.clear();
    writeHeaderAndEmptyTable();
    liveBytes = 0L;
    garbageBytes = 0L;
  }

  /** 读入偏移表并统计有效/垃圾字节数（损坏的引用按「空桶 + 垃圾」处理）。 */
  private void loadIndex(long size) throws IOException {
    this.fileSize = size;
    if (size <= 0) {
      return;
    }

    long[] table = BufferedLinearV3Format.decodePosTable(
        readAt(channel, BufferedLinearV3Format.POS_TABLE_OFFSET, BufferedLinearV3Format.POS_TABLE_SIZE));
    long live = 0L;
    for (int bucket = 0; bucket < table.length; bucket++) {
      long offset = table[bucket];
      positions[bucket] = offset;
      bucketSizes[bucket] = 0L;
      if (offset < BufferedLinearV3Format.DATA_AREA_OFFSET
          || offset + 8L > size) {
        positions[bucket] = 0L;
        continue;
      }
      try {
        byte[] lengths = readAt(channel, offset, 8);
        ByteBuffer buffer = ByteBuffer.wrap(lengths);
        buffer.getInt();
        int compressedLength = buffer.getInt();
        if (compressedLength <= 0 || offset + 8L + compressedLength > size) {
          positions[bucket] = 0L;
          continue;
        }
        bucketSizes[bucket] = 8L + compressedLength;
        live += bucketSizes[bucket];
      } catch (IOException exception) {
        positions[bucket] = 0L;
      }
    }
    this.liveBytes = live;
    this.garbageBytes = Math.max(0L, size - BufferedLinearV3Format.DATA_AREA_OFFSET - live);
  }

  // ------------------------------------------------------------------ 读

  /**
   * 读取一个区块的条目；不存在、损坏、或该文件已被其它进程占用时为 {@code null}。
   *
   * <p><b>读路径不再整桶解码</b>：桶未在内存时只解析槽位表、只解码本次请求的那一个槽位（见
   * {@link #readSlot}）。因此返回的条目在「桶已整桶解码」时与内部缓存共享同一实例、在懒加载时是本次
   * 新解出的实例；两种情况下调用方都必须<b>只读</b>对待它，或在使用前自行复制。若要跨后续的
   * {@code put}/{@code clear}/{@code compact} 长期持有该 payload，应先复制。实际安全由上层保证：
   * {@code DiskPayload.decode} 解码时已把 payload 复制成独立的字节数组，因此正常读写路径不会就地改写
   * 本方法返回的字节。
   */
  BufferedLinearV3Format.Entry get(int chunkIndex) {
    if (lockUnavailable) {
      // 该文件归其它进程所有：一律视为未命中，交给上层回退重算（fail-open）
      return null;
    }
    return readSlot(bucketIndex(chunkIndex), slotInBucket(chunkIndex));
  }

  /**
   * 读取一个槽位：桶已在内存（整桶已解码）时直接返回该槽位；否则走懒加载——只解析槽位表、只解码本次
   * 请求的那一个槽位。
   *
   * <p><b>为什么要懒解码</b>：旧实现每次读取都 {@link #ensureLoaded} 整桶 64 个槽位（逐槽分配 payload、
   * 复制、XXHash 校验），单次区块读取要付出最多 64 个区块的工作量；而读取只有 50ms 预算，常常跑不完
   * 而被记未命中，上层于是回退整块重写，磁盘缓存几乎失效。现在只解码被请求的槽位，其余槽位的校验/复制
   * 都推迟到真正需要（写、压缩回收）时。
   */
  private BufferedLinearV3Format.Entry readSlot(int bucket, int slot) {
    BufferedLinearV3Format.Entry[] bucketSlots = slots[bucket];
    if (bucketSlots != null) {
      loaded.get(bucket);
      return bucketSlots[slot];
    }
    LazyBucket lazy = lazyBuckets[bucket];
    if (lazy == null) {
      lazy = loadLazyBucket(bucket);
      lazyBuckets[bucket] = lazy;
      loaded.put(bucket, Boolean.FALSE);
      // 正在加载的桶不得被本轮驱逐（与 ensureLoaded 同理，否则返回的字节会变成孤儿）
      evictIfNeeded(bucket);
    } else {
      loaded.get(bucket);
    }
    return decodeLazySlot(lazy, slot);
  }

  /**
   * 懒加载一个桶：读桶头 → 边界与 {@code rawLength} 钳制校验 → 解压 → <b>只解析槽位表</b>。
   *
   * <p>校验口径与 {@link #ensureLoaded} 完全一致（桶尾边界、{@link #rawLengthWithinFileBudget}、
   * {@link BufferedLinearV3Format#decompress} 内部的 {@code MAX_RAW_SIZE} 与压缩比闸门）；任何损坏或
   * 异常都退化为「全空槽位的桶」（fail-open）。桶未写入（偏移为 0）时同样返回空桶，以保持「空桶也会
   * 进入 LRU」的既有语义（LRU 计数不变）。
   */
  private LazyBucket loadLazyBucket(int bucket) {
    if (positions[bucket] <= 0L) {
      return emptyLazyBucket();
    }
    try {
      byte[] lengths = readAt(channel, positions[bucket], 8);
      ByteBuffer buffer = ByteBuffer.wrap(lengths);
      int rawLength = buffer.getInt();
      int compressedLength = buffer.getInt();
      if (rawLength > 0 && compressedLength > 0
          && positions[bucket] + 8L + compressedLength <= fileSize
          && rawLengthWithinFileBudget(rawLength)) {
        byte[] compressed = readAt(channel, positions[bucket] + 8L, compressedLength);
        byte[] raw = BufferedLinearV3Format.decompress(compressed, rawLength, compression);
        return parseSlotTable(raw);
      }
    } catch (IOException | RuntimeException exception) {
      // 结构损坏：整桶视为空（fail-open，绝不因此影响封包链路）
    }
    return emptyLazyBucket();
  }

  /**
   * 解析一个桶的槽位表（只读长度前缀，不解码、不复制任何条目负载）。
   *
   * <p>遍历规则与 {@link BufferedLinearV3Format#decodeBucket} 一致：长度 ≤ 0 视为空槽；尾部不足一个长度
   * 前缀即停止（其余按空槽）；某个槽位声明的长度越过桶尾属<b>结构性损坏</b>，此时整桶按空处理——
   * 与旧「整桶解码抛异常 → 整桶当空」的结论一致，保证损坏桶不会返回半截数据。
   */
  private static LazyBucket parseSlotTable(byte[] raw) {
    int[] offsets = new int[BufferedLinearV3Format.BUCKET_SIZE];
    int[] lengths = new int[BufferedLinearV3Format.BUCKET_SIZE];
    for (int slot = 0; slot < offsets.length; slot++) {
      offsets[slot] = -1;
    }
    int offset = 0;
    for (int slot = 0; slot < BufferedLinearV3Format.BUCKET_SIZE; slot++) {
      if (offset + Integer.BYTES > raw.length) {
        break;
      }
      int length = readIntAt(raw, offset);
      offset += Integer.BYTES;
      if (length <= 0) {
        continue;
      }
      if (length > raw.length - offset) {
        // 结构性损坏：整桶作废（偏移全部置空），与 decodeBucket 抛异常后整桶当空的结论一致
        for (int index = 0; index < offsets.length; index++) {
          offsets[index] = -1;
        }
        return new LazyBucket(raw, offsets, lengths);
      }
      offsets[slot] = offset;
      lengths[slot] = length;
      offset += length;
    }
    return new LazyBucket(raw, offsets, lengths);
  }

  /** 全空槽位的懒加载桶（桶未写入 / 结构损坏时使用）。 */
  private static LazyBucket emptyLazyBucket() {
    return parseSlotTable(new byte[0]);
  }

  /**
   * 解码懒加载桶中的一个槽位；槽位为空、偏移非法或条目损坏（长度非法、XXHash 校验失败等）都只丢该槽位。
   *
   * <p>不复用整桶的槽位数组，因此每次读取只分配「被请求的那一条负载」，而不是最多 64 条。
   */
  private BufferedLinearV3Format.Entry decodeLazySlot(LazyBucket lazy, int slot) {
    int offset = lazy.offsets[slot];
    if (offset < 0) {
      return null;
    }
    try {
      return BufferedLinearV3Format.decodeEntry(lazy.raw, offset, lazy.lengths[slot], hashSeed);
    } catch (IOException | RuntimeException exception) {
      // 单槽损坏：只丢该槽位（与整桶解码时「单槽损坏只丢该槽位」口径一致）
      return null;
    }
  }

  /** 把懒加载桶整体解码为槽位数组（写 / 压缩回收需要整桶时调用）。 */
  private BufferedLinearV3Format.Entry[] decodeLazyBucket(LazyBucket lazy) {
    BufferedLinearV3Format.Entry[] bucketSlots =
        new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
    for (int slot = 0; slot < bucketSlots.length; slot++) {
      bucketSlots[slot] = decodeLazySlot(lazy, slot);
    }
    return bucketSlots;
  }

  /** 读取一个大端 i32（与 {@link BufferedLinearV3Format} 内部的长度前缀编码同字节序）。 */
  private static int readIntAt(byte[] raw, int offset) {
    return (raw[offset] & 0xFF) << 24
        | (raw[offset + 1] & 0xFF) << 16
        | (raw[offset + 2] & 0xFF) << 8
        | (raw[offset + 3] & 0xFF);
  }

  // ------------------------------------------------------------------ 写

  /**
   * 写入一个区块的条目（只改内存并标脏，落盘由 {@link #flushDirty()} 完成）。
   *
   * @return true 表示覆盖了已有条目
   */
  boolean put(int chunkIndex, BufferedLinearV3Format.Entry entry) {
    if (lockUnavailable) {
      return false; // 该文件归其它进程所有：跳过写（不落盘、不报错），交给上层
    }
    BufferedLinearV3Format.Entry[] bucketSlots = ensureLoaded(bucketIndex(chunkIndex));
    int slot = slotInBucket(chunkIndex);
    boolean replaced = bucketSlots[slot] != null;
    bucketSlots[slot] = entry;
    dirty[bucketIndex(chunkIndex)] = true;
    return replaced;
  }

  /**
   * 预判：把一条 {@code payloadLength} 字节的条目写入 {@code chunkIndex} 后，该桶编码后的<b>原始</b>字节
   * 长度是否仍不超过读取侧上界 {@link BufferedLinearV3Format#MAX_RAW_SIZE}。
   *
   * <p><b>为什么写入侧要判</b>：读取侧对 {@code rawLength} 超过该上界的桶一律判为损坏、整桶当空；写入侧
   * 若不先行拦下，一个合法写入（单条负载 ≤ 16 MiB × 单桶 64 条）就可能撑出超限的桶，表现为
   * 「写成功却读不回」。这里复用与读取侧<b>同一个常量</b>，越界即由调用方按既有拒绝路径丢弃本次写入。
   */
  boolean bucketRawLengthFitsAfterPut(int chunkIndex, int payloadLength) {
    if (lockUnavailable) {
      return true; // 该文件已停用：调用方本就跳过写入，这里不制造额外拒绝
    }
    BufferedLinearV3Format.Entry[] bucketSlots = ensureLoaded(bucketIndex(chunkIndex));
    int current = BufferedLinearV3Format.encodedBucketLength(bucketSlots);
    BufferedLinearV3Format.Entry existing = bucketSlots[slotInBucket(chunkIndex)];
    int existingBytes = existing == null || existing.payload() == null || existing.payload().length == 0
        ? Integer.BYTES
        : BufferedLinearV3Format.entryEncodedLength(existing.payload().length);
    long projected = (long) current - existingBytes
        + BufferedLinearV3Format.entryEncodedLength(payloadLength);
    return projected <= BufferedLinearV3Format.MAX_RAW_SIZE;
  }

  /**
   * 清空一个区块的条目（惰性清理过期/旧代次条目时使用）。
   *
   * <p><b>三种桶状态</b>：
   * <ul>
   *   <li>已整桶解码（{@code slots[bucket] != null}）→ 直接就地清空槽位并置 {@code dirty}（轻量）；</li>
   *   <li>懒加载过（{@link #readSlot} 只物化了 {@code lazyBuckets}）→ <b>先物化整桶再清</b>。物化只
   *       解已在内存的 {@code lazy.raw}（<b>无磁盘 IO</b>），且只在「确实要删」这条罕见路径发生
   *       （条目过期 / 换配置）；若不物化，陈旧条目会滞留并在此后<b>每次读取都重复单槽解码</b>
   *       （直到 put 覆盖或维护压缩），且 clear 必须返回 {@code true} 才能让调用方的条目计数
   *       正确扣减（{@code DiskCacheStore#removeEntry}），否则磁盘与计数会背离。清完置 dirty，
   *       下次 {@link #flushDirty} 把该桶从磁盘真正删除；</li>
   *   <li>从未加载 → 返回 {@code false}（<b>绝不为清一个槽位把整桶从磁盘解码</b>，见
   *       {@code RegionFileHardeningTest#clearOnUnloadedBucketDoesNotForceDecode}）。</li>
   * </ul>
   */
  boolean clear(int chunkIndex) {
    if (lockUnavailable) {
      return false; // 该文件归其它进程所有：跳过（不落盘、不报错）
    }
    int bucket = bucketIndex(chunkIndex);
    BufferedLinearV3Format.Entry[] bucketSlots = slots[bucket];
    if (bucketSlots == null) {
      if (lazyBuckets[bucket] == null) {
        return false; // 从未加载：不为它解码（磁盘 IO 一律不发生），交给维护期回收
      }
      // 懒加载桶：物化（仅内存解码，无磁盘 IO）后走正常清除，使 dirty 能真正落盘删掉陈旧条目
      ensureLoaded(bucket);
      bucketSlots = slots[bucket];
      if (bucketSlots == null) {
        return false; // 物化失败（防御性）：按未清除处理，计数不扣
      }
    }
    loaded.get(bucket); // 与 ensureLoaded 一致地标记为最近使用，保持 LRU 语义
    int slot = slotInBucket(chunkIndex);
    if (bucketSlots[slot] == null) {
      return false;
    }
    bucketSlots[slot] = null;
    dirty[bucket] = true;
    return true;
  }

  /** 把所有脏 bucket 追加写入文件并回填偏移表；返回是否真的写过。 */
  boolean flushDirty() throws IOException {
    if (lockUnavailable) {
      return false; // 该文件归其它进程所有：跳过落盘（不报错）
    }
    boolean wrote = false;
    // 记录本轮真正追加落盘的桶，等偏移表也写成功后再统一清 dirty（见方法说明）。
    boolean[] flushed = new boolean[BufferedLinearV3Format.BUCKET_COUNT];
    for (int bucket = 0; bucket < BufferedLinearV3Format.BUCKET_COUNT; bucket++) {
      if (dirty[bucket] && slots[bucket] != null) {
        if (flushBucket(bucket)) {
          wrote = true;
          flushed[bucket] = true;
        }
      }
    }
    if (wrote) {
      // 顺序关键：先把偏移表写成功，再清 dirty。若先清 dirty、后写偏移表，一旦 writePosTable/force
      // 失败，新副本就成了「偏移表里没有、脏标记也没了」的孤儿——重启会回退到旧版本条目（仅靠源指纹
      // 兜底）。现在失败时 dirty 保持为真，下轮 flushDirty 会重试（重试会再追加一份新副本，旧副本计入垃圾）。
      writePosTable();
      channel.force(false);
      for (int bucket = 0; bucket < flushed.length; bucket++) {
        if (flushed[bucket]) {
          dirty[bucket] = false;
        }
      }
      garbageBytes = Math.max(0L, fileSize - BufferedLinearV3Format.DATA_AREA_OFFSET - liveBytes);
    }
    return wrote;
  }

  /**
   * 追加写入单个 bucket（append-only：旧副本变成垃圾，由 {@link #compact} 回收）。
   *
   * <p><b>刻意不清 {@code dirty[bucket]}</b>：本方法只负责写数据，脏标记的清除由调用方在「偏移表也写成功」
   * 之后统一完成（{@link #flushDirty} / {@link #selectEvictableVictim}），从而保证「新副本已可被索引到」
   * 之前不会被误判为已落盘（见 {@link #flushDirty} 的顺序说明）。
   */
  private boolean flushBucket(int bucket) throws IOException {
    if (fileSize < BufferedLinearV3Format.DATA_AREA_OFFSET) {
      // 兜底不变式：数据区之前永远先有头部与偏移表，绝不把 bucket 写进元数据区
      writeHeaderAndEmptyTable();
    }

    byte[] raw = BufferedLinearV3Format.encodeBucket(slots[bucket], hashSeed);
    // 必须按本文件头部声明的方案压缩（不能用全局 currentCompression）：否则头与数据错配、读回整桶丢失
    byte[] compressed = BufferedLinearV3Format.compress(raw, compression);
    ByteBuffer buffer = ByteBuffer.allocate(8 + compressed.length);
    buffer.putInt(raw.length);
    buffer.putInt(compressed.length);
    buffer.put(compressed);
    buffer.flip();

    long offset = fileSize;
    writeFully(channel, buffer, offset);

    liveBytes -= bucketSizes[bucket];
    bucketSizes[bucket] = 8L + compressed.length;
    liveBytes += bucketSizes[bucket];
    positions[bucket] = offset;
    fileSize = offset + bucketSizes[bucket];
    return true; // dirty 的清除交给调用方在偏移表写成功之后，见方法说明
  }

  /**
   * 整文件重写，丢弃 {@code keep} 判定为不需要的条目；返回丢弃的条目数。
   *
   * <p><b>内存尖峰</b>：本方法先把<b>全部 16 个 bucket（共 1024 个区块槽）</b>的条目一次性
   * {@link #ensureLoaded} 进内存（{@code all} 数组持引用，加载后即使 LRU 淘汰也不释放），峰值内存约等于「整文件的未压缩负载」——
   * 满文件（16MB 压缩）时可达数百 MB 级。这是刻意的取舍：压缩必须以「整文件一致」为前提，
   * 不能边读边写（读着旧文件、写着新文件会踩坏尚未搬完的 bucket）。内存护栏由两层上游限制兜底：
   * {@code max-file-size-mb}（单文件上限）与 {@code compact-per-pass}（每轮最多几个文件）。
   *
   * <p><b>写入放大</b>：追加写路径是 append-only——同一条目被反复 {@link #put} 时，每次都会在文件尾
   * 追加一个新副本（旧副本变垃圾）；垃圾只有占比过半（{@code DiskCacheStore} 的阈值）才触发本方法，
   * 触发后<b>整个文件的活动数据</b>会被重写一遍。因此「写入放大 = 最多约 2 倍」：一条数据最多被
   * 写两次（一次追加 + 一次随压缩重写）。把压缩阈值从「每次落盘」放宽到「垃圾过半」，正是用
   * 可控的放大换掉高频整文件重写。
   */
  int compact(EntryFilter keep) throws IOException {
    if (lockUnavailable) {
      return 0; // 该文件归其它进程所有：跳过整文件重写（不落盘、不报错）
    }
    compacting = true;
    try {
      BufferedLinearV3Format.Entry[][] all =
          new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_COUNT][];
      // 累计解压字节的记账数组：即使每个桶都过了 rawLength 钳制，16 个桶叠加仍可能过大，
      // 因此再加一道总量闸。超限即在此抛异常——此刻临时文件尚未创建，原文件毫发无损。
      long[] compactRawBudget = {0L};
      for (int bucket = 0; bucket < BufferedLinearV3Format.BUCKET_COUNT; bucket++) {
        all[bucket] = ensureLoaded(bucket, compactRawBudget);
        if (compactRawBudget[0] > MAX_COMPACT_TOTAL_RAW) {
          throw new IOException("压缩回收累计解压字节 " + compactRawBudget[0]
              + " 超过上限 " + MAX_COMPACT_TOTAL_RAW + "，已中止本次压缩（保持原文件不变）");
        }
      }

      int dropped = 0;
      if (keep != null) {
        for (int bucket = 0; bucket < all.length; bucket++) {
          for (int slot = 0; slot < all[bucket].length; slot++) {
            if (all[bucket][slot] != null
                && !keep.keep(bucket << BufferedLinearV3Format.BUCKET_SHIFT | slot, all[bucket][slot])) {
              all[bucket][slot] = null;
              dropped++;
            }
          }
        }
      }

      Path temp = path.resolveSibling(path.getFileName() + ".tmp");
      long[] newPositions = new long[BufferedLinearV3Format.BUCKET_COUNT];
      long[] newSizes = new long[BufferedLinearV3Format.BUCKET_COUNT];
      long offset = BufferedLinearV3Format.DATA_AREA_OFFSET;

      try (FileChannel out = FileChannel.open(temp,
          StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        for (int bucket = 0; bucket < all.length; bucket++) {
          if (isEmpty(all[bucket])) {
            continue;
          }
          byte[] raw = BufferedLinearV3Format.encodeBucket(all[bucket], hashSeed);
          // 与 flushBucket 同口径：按本文件声明的方案压缩，压缩回收不改变文件的压缩方案
          byte[] compressed = BufferedLinearV3Format.compress(raw, compression);
          ByteBuffer buffer = ByteBuffer.allocate(8 + compressed.length);
          buffer.putInt(raw.length);
          buffer.putInt(compressed.length);
          buffer.put(compressed);
          buffer.flip();
          writeFully(out, buffer, offset);
          newPositions[bucket] = offset;
          newSizes[bucket] = 8L + compressed.length;
          offset += newSizes[bucket];
        }
        // 头部沿用本文件声明的方案（不是 currentCompression）：压缩回收只重排数据，不改变压缩方案
        writeFully(out, ByteBuffer.wrap(BufferedLinearV3Format.encodeHeader(hashSeed, compression)), 0L);
        writeFully(out, ByteBuffer.wrap(BufferedLinearV3Format.encodePosTable(newPositions)),
            BufferedLinearV3Format.POS_TABLE_OFFSET);
        out.force(true);
      } catch (IOException exception) {
        Files.deleteIfExists(temp);
        throw exception;
      }

      try {
        channel.close();
        try {
          Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
          // 原子移动不被支持（AtomicMoveNotSupportedException）或失败：退回普通替换移动
          Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException failure) {
        // 关闭旧通道或替换失败：目标文件保持原样（未被替换），但 temp 会残留成孤儿文件——失败路径必须
        // 清理它，否则每次压缩失败都在磁盘上留一个废弃的 .tmp（虽有启动期兜底扫描，但不该依赖它）。
        // 只删 temp、绝不触碰 path，保证「不破坏原文件」；把 channel.close() 与 move 收拢进同一清理路径，
        // 避免「关闭通道抛异常」时既漏删 temp、又漏掉句柄失效的收尾。
        deleteQuietly(temp);
        throw failure;
      }
      reopenChannel();

      for (int bucket = 0; bucket < all.length; bucket++) {
        positions[bucket] = newPositions[bucket];
        bucketSizes[bucket] = newSizes[bucket];
        dirty[bucket] = false;
        loaded.remove(bucket);
      }
      slotsLoad(all);
      // 整文件已按本文件声明的方案重写（头部 + 全部 bucket 都是 compression），方案字段保持不变
      fileSize = offset;
      liveBytes = offset - BufferedLinearV3Format.DATA_AREA_OFFSET;
      garbageBytes = 0L;
      return dropped;
    } finally {
      compacting = false;
      evictIfNeeded(-1);
    }
  }

  /** 压缩回收后把全部 bucket 放回 LRU（超限部分由随后的一次驱逐处理）。 */
  private void slotsLoad(BufferedLinearV3Format.Entry[][] all) {
    for (int bucket = 0; bucket < all.length; bucket++) {
      slots[bucket] = all[bucket];
      // 整桶已解码，懒加载表示不再需要（两者互斥，见 LazyBucket 说明）
      lazyBuckets[bucket] = null;
      loaded.put(bucket, Boolean.FALSE);
    }
  }

  // ------------------------------------------------------------------ 状态

  long sizeBytes() {
    return fileSize;
  }

  /** 垃圾字节数（旧 bucket 副本，压缩回收可释放）。 */
  long garbageBytes() {
    return garbageBytes;
  }

  /**
   * 该桶若此刻落盘、追加到文件尾的字节长度估算（= 8 字节桶头 + 整桶编码长度）。
   *
   * <p>用的是<b>未压缩</b>长度：真实 append 的是压缩后的字节，用未压缩长度是保守上界（只会让「单文件
   * 大小上限」更早触发，绝不放过超限增长）。供 {@code DiskCacheStore} 的待落盘记账使用（bucket 是整块
   * 追加的，必须按整桶而非单条负载估算）。
   */
  long encodedBucketSizeEstimate(int bucket) {
    if (lockUnavailable) {
      return 0L;
    }
    return 8L + BufferedLinearV3Format.encodedBucketLength(slots[bucket]);
  }

  boolean isDirty() {
    for (boolean value : dirty) {
      if (value) {
        return true;
      }
    }
    return false;
  }

  boolean isEmptyFile() {
    if (lockUnavailable) {
      // 该文件归其它进程所有：绝不能因「看起来空」而被本进程删除（那会毁掉对方的缓存）
      return false;
    }
    return liveBytes <= 0L && !isDirty();
  }

  Path path() {
    return path;
  }

  /**
   * 该文件是否因跨进程锁被其它进程占用而被停用（读一律未命中、写一律跳过）。
   * 供调用方跳过「写了但没落盘」的记账，避免白白耗尽条目额度（见 {@code DiskCacheStore#doPut}）。
   */
  boolean lockUnavailable() {
    return lockUnavailable;
  }

  // ------------------------------------------------------------------ 关闭

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      // 通道已被关闭（被线程中断打断、或外部删除/关闭）时不再尝试写盘：
      // 否则每次关闭都会再抛一次 ClosedChannelException，把「一次失效」放大成持续刷屏的 WARN。
      if (channel.isOpen()) {
        flushDirty();
      }
    } finally {
      closeQuietly();
    }
  }

  /**
   * 底层通道是否仍然可用。
   *
   * <p><b>为什么需要它</b>：{@code FileChannel} 一旦因线程被中断（{@code ClosedByInterruptException}）
   * 或外部关闭而失效，就是<b>永久</b>失效；此时本就算「句柄还在、脏标记还在」，
   * 每轮维护都会在同一条通道上再抛一次 {@code ClosedChannelException}。
   * 调用方据此识别失效句柄并丢弃重建（fail-open：缓存可重建，但绝不静默刷屏）。
   */
  boolean channelOpen() {
    return channel.isOpen();
  }

  /**
   * <b>仅测试用</b>：直接关闭底层通道且不改动 {@code closed} 标志，用于复现「通道被中断/外部关闭后，
   * 句柄仍留在维护队列里、脏 bucket 仍在」这一真机故障态（每 30 秒一条 ClosedChannelException）。
   */
  void killChannelForTest() throws IOException {
    channel.close();
  }

  private void closeQuietly() {
    try {
      channel.close();
    } catch (IOException ignored) {
      // 关闭失败无可补救：交给操作系统回收
    }
    closed = true;
  }

  private static void closeQuietly(FileChannel channel) {
    try {
      channel.close();
    } catch (IOException ignored) {
      // 打开失败后的关闭异常无需上报
    }
  }

  /** 重新打开底层通道（{@link #compact} 用临时文件替换后调用），并重新取文件锁。 */
  private void reopenChannel() throws IOException {
    channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE);
    LockHold hold = acquireLock(channel);
    this.lock = hold.lock();
    if (hold.occupied()) {
      // 替换后（若还能替换成功）锁已被别的进程抢走：同样停用读写，避免与对方互相踩
      this.lockUnavailable = true;
      warnLockUnavailableOnce();
    } else {
      this.lockUnavailable = false;
    }
  }

  /** 尽力删除一个文件；失败只忽略（临时文件删除失败不影响功能）。 */
  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (Throwable ignored) {
      // 删除失败无副作用：启动期的 *.tmp 兜底扫描会再清理一次
    }
  }

  // ------------------------------------------------------------------ 内部

  private static int bucketIndex(int chunkIndex) {
    return BufferedLinearV3Format.bucketIndex(chunkIndex);
  }

  private static int slotInBucket(int chunkIndex) {
    return chunkIndex & (BufferedLinearV3Format.BUCKET_SIZE - 1);
  }

  private static boolean isEmpty(BufferedLinearV3Format.Entry[] bucketSlots) {
    if (bucketSlots == null) {
      return true;
    }
    for (BufferedLinearV3Format.Entry entry : bucketSlots) {
      if (entry != null) {
        return false;
      }
    }
    return true;
  }

  private BufferedLinearV3Format.Entry[] ensureLoaded(int bucket) {
    return ensureLoaded(bucket, null);
  }

  /**
   * 载入一个 bucket（必要时从磁盘解压）。
   *
   * @param compactRawBudget 非 {@code null} 时用于 {@link #compact} 的累计解压字节记账：每成功解压一个桶
   *                         就把其声明原始长度累加进去，供调用方实施 {@link #MAX_COMPACT_TOTAL_RAW} 总量闸
   */
  private BufferedLinearV3Format.Entry[] ensureLoaded(int bucket, long[] compactRawBudget) {
    BufferedLinearV3Format.Entry[] bucketSlots = slots[bucket];
    if (bucketSlots != null) {
      loaded.get(bucket);
      return bucketSlots;
    }

    // 读路径懒加载过的桶：需要整桶（写 / 压缩回收）时在此一次性解码，避免两套表示长期并存
    LazyBucket lazy = lazyBuckets[bucket];
    if (lazy != null) {
      lazyBuckets[bucket] = null;
      if (compactRawBudget != null) {
        // 该桶的原始字节已由懒加载解压并常驻内存：计入压缩总量闸，避免内存尖峰被漏算
        compactRawBudget[0] += lazy.raw.length;
      }
      bucketSlots = decodeLazyBucket(lazy);
    } else {
      bucketSlots = new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
      if (positions[bucket] > 0L) {
        try {
          byte[] lengths = readAt(channel, positions[bucket], 8);
          ByteBuffer buffer = ByteBuffer.wrap(lengths);
          int rawLength = buffer.getInt();
          int compressedLength = buffer.getInt();
          if (rawLength > 0 && compressedLength > 0
              && positions[bucket] + 8L + compressedLength <= fileSize
              && rawLengthWithinFileBudget(rawLength)) {
            if (compactRawBudget != null) {
              // 只在「确实要按 rawLength 分配解压缓冲」时才计入，避免把已内存驻留的桶重复计数
              compactRawBudget[0] += rawLength;
            }
            byte[] compressed = readAt(channel, positions[bucket] + 8L, compressedLength);
            bucketSlots = BufferedLinearV3Format.decodeBucket(
                BufferedLinearV3Format.decompress(compressed, rawLength, compression), hashSeed);
          }
        } catch (IOException | RuntimeException exception) {
          // 结构损坏：整个 bucket 视为空（fail-open，绝不因此影响封包链路）
          bucketSlots = new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
        }
      }
    }

    slots[bucket] = bucketSlots;
    loaded.put(bucket, Boolean.FALSE);
    // 正在加载的 bucket 不得被本轮驱逐（否则返回给调用方的数组会变成孤儿、后续写入丢失）
    evictIfNeeded(bucket);
    return bucketSlots;
  }

  /**
   * 桶头 {@code rawLength} 是否落在「按整文件大小放大的合理上界」内（见 {@link #MAX_RAW_LENGTH_RATIO}）。
   * 不满足即视为损坏/伪造头，由调用方 fail-open（该桶当空），<b>在分配任何解压缓冲之前</b>拦下。
   */
  private boolean rawLengthWithinFileBudget(int rawLength) {
    return rawLength > 0 && (long) rawLength <= fileSize * MAX_RAW_LENGTH_RATIO;
  }

  /**
   * 统计本区域文件里磁盘上已有的条目数：只读各非空 bucket 的字节流并数「槽位长度前缀」，
   * <b>不</b>把桶载入内存缓存、不解码条目负载、不触发脏桶落盘。
   *
   * <p>与逐个 {@code get(chunkIndex)} 的区别（这正是本方法存在的理由）：后者会把整份区域文件的
   * 桶全部解码进 LRU，可能把<b>别的</b>区域文件的脏桶挤出去写盘，还顺带产生大量负载数组垃圾；
   * 启动期只为「把已有条目数补进近似计数」不值得付这些代价。
   *
   * <p>fail-open：单个 bucket 损坏只少计它自己（计数偏小只影响写入上限的保守度，不影响缓存读写）。
   */
  int countEntriesOnDisk() {
    if (lockUnavailable) {
      return 0; // 该文件归其它进程所有：不读它的内容，计为 0（计数偏小只影响上限保守度）
    }
    int count = 0;
    for (int bucket = 0; bucket < BufferedLinearV3Format.BUCKET_COUNT; bucket++) {
      if (positions[bucket] <= 0L) {
        continue;
      }
      try {
        byte[] lengths = readAt(channel, positions[bucket], 8);
        ByteBuffer buffer = ByteBuffer.wrap(lengths);
        int rawLength = buffer.getInt();
        int compressedLength = buffer.getInt();
        if (rawLength <= 0 || compressedLength <= 0
            || positions[bucket] + 8L + compressedLength > fileSize
            || !rawLengthWithinFileBudget(rawLength)) {
          continue; // 含「声明原始长度远超文件」的伪造头：与 ensureLoaded 同口径，避免按它分配
        }
        byte[] compressed = readAt(channel, positions[bucket] + 8L, compressedLength);
        count += BufferedLinearV3Format.countBucketEntries(
            BufferedLinearV3Format.decompress(compressed, rawLength, compression));
      } catch (IOException | RuntimeException exception) {
        // 结构损坏：只少计这一个桶
      }
    }
    return count;
  }

  /**
   * LRU 驱逐：脏 bucket 先落盘再释放，避免丢写入。
   *
   * @param pinned 本轮正在加载、<b>不得驱逐</b>的桶下标（{@code -1} = 无）。
   *               驱逐绝不能碰正在加载的桶，否则调用方持有的 {@code bucketSlots} 会变成孤儿数组、
   *               随后的写入丢失（且会留下「脏标记在、slots 为 null」的不一致态）。
   */
  private void evictIfNeeded(int pinned) {
    if (compacting) {
      return;
    }
    while (loaded.size() > bucketCacheSize) {
      int victim = selectEvictableVictim(pinned);
      if (victim < 0) {
        // 当前没有「可安全驱逐」的桶（剩下的全是脏且落盘失败）：放弃本轮。
        // 这些桶必须留在 loaded 记账内——若把它们移出却不释放 slots，就会脱离 LRU 记账，
        // 使实际内存桶数突破 bucketCacheSize（旧缺陷正是如此）；等下次 flush/close 再重试落盘。
        return;
      }
      // 先确认能安全移除再移除，保证不变式：桶要么在 loaded 记账内，要么 slots 已释放。
      loaded.remove(victim);
      slots[victim] = null;
      lazyBuckets[victim] = null;
    }
  }

  /**
   * 选一个「可安全驱逐」的牺牲桶（按 LRU 序从最久未用者开始；跳过 {@code pinned}）：
   * 非脏桶无需落盘可直接释放；脏桶先尝试落盘，<b>失败则跳过它另选</b>——绝不把它移出
   * {@code loaded}，否则它会脱离 LRU 记账但 {@code slots} 仍在内存里，实际桶数会突破上限；
   * 同时保留其脏标记，以便下次 flush/close 重试。
   *
   * @return 可安全移除的桶下标；{@code -1} 表示当前没有可安全驱逐的桶
   */
  private int selectEvictableVictim(int pinned) {
    // 第一趟：优先选「干净」的牺牲桶——驱逐它不需要任何磁盘 IO。
    // 为什么必须先找干净的：本方法也会被<b>读路径</b>调用（懒加载新桶时的 LRU 驱逐），而下面那条
    // 「脏桶先落盘再驱逐」的路径会同步执行 flushBucket（encodeBucket + zstd 压缩 + append 写）
    // 与 writePosTable——一次区块读取就可能内联一次桶压缩与磁盘写，足以击穿 50 ms 读预算
    // （超时即记未命中 → 上层回退整块重写，命中率下降）。先挑干净的即可在绝大多数情况下完全避开它。
    // 直接遍历 loaded：循环体只读 dirty/slots（不触碰 loaded），无需先复制 keySet 快照。
    for (Integer candidate : loaded.keySet()) {
      if (candidate != pinned && (!dirty[candidate] || slots[candidate] == null)) {
        return candidate;
      }
    }
    // 第二趟：已加载的桶全是脏的（写入密集期），只能落盘后驱逐——脏数据必须先写入，否则丢失。
    // 逐个尝试（某个桶落盘失败时保留其脏标记并换下一个），全部失败才放弃本轮驱逐。
    for (Integer candidate : loaded.keySet()) {
      if (candidate == pinned || !dirty[candidate] || slots[candidate] == null) {
        continue;
      }
      try {
        flushBucket(candidate);
        writePosTable();
        // 与 flushDirty 同口径：偏移表写成功后才清 dirty（失败则保持为真、下轮重试，避免新副本成为孤儿）
        dirty[candidate] = false;
        // 驱逐落盘后旧副本成为垃圾：与 flushDirty/compact 口径一致地重算垃圾字节数，
        // 否则压缩回收的「垃圾占比」判定会低估，垃圾迟迟得不到回收
        garbageBytes = Math.max(0L,
            fileSize - BufferedLinearV3Format.DATA_AREA_OFFSET - liveBytes);
        return candidate;
      } catch (IOException exception) {
        // 落盘（或偏移表写入）失败：保留脏标记与内存态，等下次 flush/close 再试；跳过它另选
        dirty[candidate] = true;
      }
    }
    return -1;
  }

  private void writePosTable() throws IOException {
    writeFully(channel, ByteBuffer.wrap(BufferedLinearV3Format.encodePosTable(positions)),
        BufferedLinearV3Format.POS_TABLE_OFFSET);
  }

  private static void writeFully(FileChannel channel, ByteBuffer buffer, long startOffset)
      throws IOException {
    long offset = startOffset;
    while (buffer.hasRemaining()) {
      int written = channel.write(buffer, offset);
      if (written <= 0) {
        throw new IOException("写入区域文件失败（偏移 " + offset + "）");
      }
      offset += written;
    }
  }

  private static byte[] readAt(FileChannel channel, long startOffset, int length) throws IOException {
    byte[] out = new byte[length];
    ByteBuffer buffer = ByteBuffer.wrap(out);
    long offset = startOffset;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, offset);
      if (read < 0) {
        throw new IOException("区域文件在偏移 " + offset + " 处意外结束");
      }
      offset += read;
    }
    return out;
  }
}