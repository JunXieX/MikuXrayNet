package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * 反矿透改写缓存（自研）。
 *
 * <p><b>生命周期设计与上游实现的核心差异</b>：键只由 {@code (世界名, chunkX, chunkZ, 配置指纹)}
 * 组成，全部为基本类型或不可变字符串，绝不持有 {@code World / Chunk / Player / PacketContainer}
 * 等强引用；因此条目不会把世界对象钉在堆上，世界卸载时再显式整体失效即可。
 *
 * <p>容量与过期策略：<b>近似 LRU</b>（second-chance / CLOCK）+ {@code maximumSize} +
 * {@code maximumBytes} + {@code expireAfterAccess}。读取走无锁 {@link ConcurrentHashMap}（不再全局同步、
 * 不再每次访问改动链表），写入与淘汰在同步块内串行维护一条 second-chance 环形队列——因此多个工作线程
 * 可并发读取同一缓存，多条区块包的读路径不再被逐个串行化（旧实现是 {@code synchronized get} +
 * accessOrder {@code LinkedHashMap}，每次 get 都独占锁、改动链表并新建 {@code Key}；本实现的热路径改用
 * 线程本地探针查询，见 {@link Key}）。淘汰语义仍为
 * 「优先淘汰最久未使用」：被访问过的条目获得一次「第二次机会」，未被再次访问即被淘汰。
 *
 * <p><b>为什么除了条目数还要有字节上限</b>：改写结果的单条体积由区块大小决定（改写后字节 +
 * 伪装坐标数组 + 整列遮挡负载，实测量级 30~60 KB），只按条目数封顶时「条目上限 × 单条体积」会随
 * 大区块/密集矿区漂移到 GB 级——而本缓存与玩家视野区块数直接相关，大服上很容易填满。故同时按
 * {@code weigher} 累计的字节数淘汰：两个上限谁先到顶就以谁为准（与磁盘缓存的 max-entries +
 * max-file-size-mb 同一思路）。{@code weigher == null} 时不做字节记账（离线单测与旧行为）。
 *
 * <p><b>过期回收为什么不能只靠 get / 顶满淘汰</b>：多数区块只下发一次、此后永不 {@code get}，这类
 * 「冷条目」既不触发 {@code get} 的过期检查、也在容量未顶满时不经过淘汰扫描，于是「按过期时间回收」
 * 对它们失效、条目会无限期驻留到顶满上限，{@code size()} 也会与真实占用背离。因此在每次新建条目的
 * {@link #put} 里做一次<b>摊还清理</b>（{@link #amortizedCleanup()}）：按 lastAccessNanos 巡检固定
 * 条数的环节点，回收过期冷条目并丢弃已从主存储消失的死节点。成本按 put 摊还为 O(1)，且沿用既有
 * {@link #clockLock}，不引入新锁。
 *
 * @param <V> 缓存值类型；由调用方保证值本身也不持有世界/封包引用
 */
public final class RewriteCache<V> {

  /**
   * 缓存键：只含基本类型与不可变字符串。
   *
   * <p><b>为什么是手写 final 类而不是 {@code record}</b>：{@link #get} / {@link #put} 的热路径改用
   * 线程本地可变探针（{@link Probe}）作为查询键，以消除每条区块包 1~2 次短命 {@code Key} 分配；而
   * {@code ConcurrentHashMap} 的<b>树化桶</b>会用「存储键的 equals」比较查询键
   * （{@code TreeNode.find} → {@code Key.equals(Probe)}）。{@code record} 自动生成的 equals 形如
   * {@code if (!(o instanceof Key)) return false;}，届时探针永远查不到已存在的条目——且只在桶内条目数
   * 超过树化阈值时才复现，属极难排查的隐性故障。手写 equals 双向识别 {@link Probe} 后该路径才安全
   * （与 {@code ChunkKey}/{@code ChunkKey.Probe} 同一范式）。
   *
   * <p>字段全部 final、无 setter，因此仍满足「不可变、可安全作为 Map 键」的语义。
   */
  public static final class Key {

    private final String worldName;
    private final int x;
    private final int z;
    private final int configHash;

    Key(String worldName, int x, int z, int configHash) {
      this.worldName = worldName;
      this.x = x;
      this.z = z;
      this.configHash = configHash;
    }

    public String worldName() {
      return worldName;
    }

    public int x() {
      return x;
    }

    public int z() {
      return z;
    }

    public int configHash() {
      return configHash;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      // 必须同时识别 Probe：CHM 树化后会用「存储键的 equals」比较查询键（见类注释）
      if (other instanceof Probe probe) {
        return this.x == probe.x && this.z == probe.z && this.configHash == probe.configHash
            && Objects.equals(this.worldName, probe.worldName);
      }
      if (other instanceof Key key) {
        return this.x == key.x && this.z == key.z && this.configHash == key.configHash
            && Objects.equals(this.worldName, key.worldName);
      }
      return false;
    }

    @Override
    public int hashCode() {
      // 与 Probe.hashCode 必须逐字段一致，否则 CHM 查不到（见 Probe 的说明）
      int result = Objects.hashCode(worldName);
      result = 31 * result + x;
      result = 31 * result + z;
      result = 31 * result + configHash;
      return result;
    }

    @Override
    public String toString() {
      return "Key[" + worldName + ", " + x + ", " + z + ", configHash=" + configHash + "]";
    }

    /**
     * <b>可复用的查询探针</b>：与 {@link Key} 语义完全一致（逐字段 equals/hashCode），但字段可变。
     *
     * <p><b>为什么需要它</b>：{@link #get} 每条区块包都要查一次缓存，{@link #put} 的「是否已存在」也要
     * 查一次；每次 {@code new Key(...)} 都是一个短命对象。探针按线程复用后这两处查询零分配——只有真正
     * 新建条目时才需要 new 一个不可变的 {@link Key} 存进 Map。
     *
     * <p><b>为什么安全</b>：探针只作为 {@code ConcurrentHashMap} 的<b>查询键</b>传入，绝不会被存进任何
     * Map（存进去的永远是真正不可变的 {@link Key}），因此「键内容被改」不可能污染任何持久结构。
     * hashCode 与相等判定与 {@link Key} 逐字段一致，且两边 equals 都互相识别（见 {@link Key} 的类注释）。
     * 线程封闭（{@link ThreadLocal} 持有），无需同步。
     */
    static final class Probe {

      private static final ThreadLocal<Probe> LOCAL = ThreadLocal.withInitial(Probe::new);

      private String worldName;
      private int x;
      private int z;
      private int configHash;

      private Probe() {
      }

      /** 取当前线程的复用探针（线程封闭，无需同步）。 */
      static Probe local() {
        return LOCAL.get();
      }

      /** 指向另一个缓存键；返回自身以便链式使用。 */
      Probe set(String worldName, int x, int z, int configHash) {
        this.worldName = worldName;
        this.x = x;
        this.z = z;
        this.configHash = configHash;
        return this;
      }

      @Override
      public boolean equals(Object other) {
        if (this == other) {
          return true;
        }
        if (other instanceof Key key) {
          return this.x == key.x && this.z == key.z && this.configHash == key.configHash
              && Objects.equals(this.worldName, key.worldName);
        }
        if (other instanceof Probe probe) {
          return this.x == probe.x && this.z == probe.z && this.configHash == probe.configHash
              && Objects.equals(this.worldName, probe.worldName);
        }
        return false;
      }

      @Override
      public int hashCode() {
        // 与 Key.hashCode 必须逐字段一致，否则 CHM 查不到（见类注释）
        int result = Objects.hashCode(worldName);
        result = 31 * result + x;
        result = 31 * result + z;
        result = 31 * result + configHash;
        return result;
      }

      @Override
      public String toString() {
        return "Key[" + worldName + ", " + x + ", " + z + ", configHash=" + configHash + "]";
      }
    }
  }

  /**
   * 每次 {@link #put}（新建条目）摊还清理的环节点数上限。
   *
   * <p>为什么要「摊还」而非一次性全扫：全扫是 O(n)、会把并发回填重新串行化并让单次 put 抖动；固定小
   * 常数则让每次 put 只付 O(1)。环上 n 个节点约需 n/该常数 次 put 被完整巡检一遍，冷条目与死节点据此
   * 被渐进回收。取 8 是在「回收及时性」与「单次 put 开销」之间取的折中：对默认 40960 上限，约 5000 次
   * 新建即可扫完一圈，远快于条目过期本身的时间尺度。
   */
  private static final int CLEANUP_NODES_PER_PUT = 8;

  private static final class CacheEntry<V> {

    private final Key key;
    /** 值可被同键覆盖（原地更新）——避免覆盖时在环形队列里留下重复节点。 */
    private volatile V value;
    /** 该条目的近似字节占用（{@code weigher} 给出；不做字节记账时恒为 0）。 */
    private volatile long sizeBytes;
    private volatile long lastAccessNanos;
    /** second-chance 位：被访问过即置 true；淘汰扫描时给一次「第二次机会」。 */
    private volatile boolean referenced;

    private CacheEntry(Key key, V value, long sizeBytes, long nowNanos) {
      this.key = key;
      this.value = value;
      this.sizeBytes = sizeBytes;
      this.lastAccessNanos = nowNanos;
      this.referenced = false;
    }
  }

  private final int maximumSize;
  /** 字节上限；{@link Long#MAX_VALUE} 表示不限制（无 weigher 时）。 */
  private final long maximumBytes;
  /** 条目体积函数；{@code null} = 不做字节记账（离线单测/旧行为）。 */
  private final ToLongFunction<V> weigher;
  private final long expireAfterAccessNanos;
  private final LongSupplier clock;

  /** 主存储：读取完全无锁。 */
  private final ConcurrentHashMap<Key, CacheEntry<V>> entries = new ConcurrentHashMap<>();
  /**
   * second-chance 环形队列（CLOCK 手）。
   *
   * <p>只在「真正新建条目」的 {@link #put} 分支 / 失效方法的同步块内增删；同键覆盖与读路径
   * （{@link #get}）完全不碰它，因此读写不需要任何锁。队首是「最久未被再访问」的候选。
   */
  private final ArrayDeque<CacheEntry<V>> clockQueue = new ArrayDeque<>();
  /**
   * CLOCK 环形队列 / 淘汰结构的专用锁。
   *
   * <p><b>为什么从「方法级 synchronized」缩小到这把锁</b>：旧实现整个 {@link #put} 都串行化，
   * 多个工作线程并发回填不同区块时被无谓地逐个排队。实际需要互斥的只有「环形队列增删 + 淘汰扫描」
   * 这一小段；同键覆盖只在条目自身上做一次极短的同步（保证「值 / 体积 / 字节记账」三者自洽），
   * 插入 {@code entries} 由 ConcurrentHashMap 保证线程安全，因此它们都在全局锁之外，
   * 不同键的并发回填不再互相阻塞，CLOCK 语义保持不变。
   */
  private final Object clockLock = new Object();

  /** 当前条目占用的近似字节总数（与 {@code entries} 口径一致：只统计仍驻留在主存储里的条目）。 */
  private final AtomicLong bytes = new AtomicLong();

  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();

  /**
   * 生产构造：条目数 + 字节数双上限。
   *
   * @param maximumSize              条目上限
   * @param maximumBytes             字节上限（按 {@code weigher} 累计）
   * @param expireAfterAccessSeconds 最后一次访问后的过期秒数
   * @param weigher                  单条体积函数（不得返回负数；返回 0 表示不计账）
   */
  public RewriteCache(int maximumSize, long maximumBytes, long expireAfterAccessSeconds,
      ToLongFunction<V> weigher) {
    this(maximumSize, maximumBytes,
        TimeUnit.SECONDS.toNanos(Math.max(1L, expireAfterAccessSeconds)), System::nanoTime, weigher);
  }

  /** 测试用构造：可注入时钟与纳秒级过期时间，不做字节记账。 */
  RewriteCache(int maximumSize, long expireAfterAccessNanos, LongSupplier clock) {
    this(maximumSize, Long.MAX_VALUE, expireAfterAccessNanos, clock, null);
  }

  /** 测试用构造：可注入时钟与体积函数（验证字节上限的淘汰顺序）。 */
  RewriteCache(int maximumSize, long maximumBytes, long expireAfterAccessNanos,
      LongSupplier clock, ToLongFunction<V> weigher) {
    this.maximumSize = Math.max(1, maximumSize);
    this.maximumBytes = Math.max(1L, maximumBytes);
    this.expireAfterAccessNanos = Math.max(1L, expireAfterAccessNanos);
    this.clock = clock;
    this.weigher = weigher;
  }

  /** 读取；未命中或已过期返回 {@code null}（过期条目会被立即移除）。 */
  public V get(String worldName, int x, int z, int configHash) {
    // 热路径：用线程本地探针查询，不新建 Key（探针只作查询键，绝不会被存进 Map）
    CacheEntry<V> entry = entries.get(Key.Probe.local().set(worldName, x, z, configHash));
    long now = clock.getAsLong();

    if (entry == null) {
      misses.increment();
      return null;
    }
    if (now - entry.lastAccessNanos > expireAfterAccessNanos) {
      // 复核后再删：put 对同键是「原地刷新同一个 CacheEntry」（lastAccessNanos / value 都可能刚被更新）。
      // 若在「读取 lastAccessNanos」与「删除」之间恰好被刷新，直接 remove(entry.key, entry) 会把刚刷新
      // 的条目整条删掉（只多一次重算，非数据错误，但属无谓浪费）。computeIfPresent 在 bin 锁内以
      // 「当前值」复核，保证只删此刻确实仍过期的条目。
      long[] evictedBytes = {0L};
      CacheEntry<V> live = entries.computeIfPresent(entry.key, (key, current) -> {
        if (now - current.lastAccessNanos > expireAfterAccessNanos) {
          // 在 bin 锁内同时扣账：移除发生后本线程是唯一见过该体积的一方，不扣会让字节数永久虚高。
          evictedBytes[0] = current.sizeBytes;
          return null;
        }
        return current;
      });
      if (live != null) {
        // 期间被同键 put 刷新（value / 访问时间已更新）：按刷新后的值命中，不计一次虚假未命中
        live.lastAccessNanos = now;
        live.referenced = true;
        hits.increment();
        return live.value;
      }
      if (evictedBytes[0] != 0L) {
        bytes.addAndGet(-evictedBytes[0]);
      }
      misses.increment();
      return null;
    }

    entry.lastAccessNanos = now;
    entry.referenced = true;
    hits.increment();
    return entry.value;
  }

  /** 写入（覆盖同键旧值）。同键覆盖与 {@code entries} 插入不加全局锁，仅新建条目时才同步维护 CLOCK 队列。 */
  public void put(String worldName, int x, int z, int configHash, V value) {
    long now = clock.getAsLong();
    long sizeBytes = weigher == null ? 0L : Math.max(0L, weigher.applyAsLong(value));
    if (weigher != null && sizeBytes > maximumBytes) {
      // 单条就超过总预算：不缓存。否则它一入缓存就会立刻触发淘汰、把它之前的条目全部挤掉，
      // 表现为「缓存命中率莫名其妙归零」；调用方按未命中处理即可（只多算一次改写）。
      return;
    }
    // 同键覆盖（热路径）：用线程本地探针查询，不新建 Key
    CacheEntry<V> existing = entries.get(Key.Probe.local().set(worldName, x, z, configHash));
    if (existing != null) {
      overwrite(existing, value, sizeBytes, now);
      return;
    }

    // 只有真正新建条目才需要不可变的 Key（它会被 Map 长期持有）
    Key key = new Key(worldName, x, z, configHash);
    CacheEntry<V> entry = new CacheEntry<>(key, value, sizeBytes, now);
    CacheEntry<V> previous = entries.putIfAbsent(key, entry);
    if (previous != null) {
      // 并发插入同键：以先入者为存活条目，仅更新其值，绝不重复入队（否则队列会长出陈旧节点）。
      overwrite(previous, value, sizeBytes, now);
      return;
    }
    // 字节记账必须与 CLOCK 队列维护在同一把锁内：invalidateAll/invalidateWorld 会在 clockLock 内
    // 清表并按剩余条目重算字节；若增账在锁外，交错时会出现「条目已被清掉、账却仍被加上」的永久偏高
    // （该节点随后被当死节点丢弃、永不扣回 → 累积越过上限后缓存被持续挤空，直到重启）。
    synchronized (clockLock) {
      if (entries.get(key) != entry) {
        // 并发失效已先一步摘除本条目：不入队、不记账，否则留下永不回收的账目
        return;
      }
      if (sizeBytes != 0L) {
        bytes.addAndGet(sizeBytes);
      }
      clockQueue.addLast(entry);
      evictOverflow();
      // 顶满淘汰只在超容量时触发，冷条目的过期回收必须另有着落：每次新建条目顺带巡检少量节点
      amortizedCleanup();
    }
  }

  /**
   * 同键原地覆盖：值 / 体积 / 字节记账必须在同一临界区内一起更新。
   *
   * <p>只锁<b>这一条</b>条目（不进入 {@link #clockLock}）：同键并发覆盖只可能来自「同一区块的两条
   * 区块包同时改写完成」，锁竞争极低；换来的好处是字节总数不会因为读到旧体积而永久漂移。
   */
  private void overwrite(CacheEntry<V> entry, V value, long sizeBytes, long now) {
    synchronized (entry) {
      long delta = sizeBytes - entry.sizeBytes;
      entry.value = value;
      entry.sizeBytes = sizeBytes;
      entry.lastAccessNanos = now;
      entry.referenced = true;
      // 字节增账必须与 invalidateWorld/invalidateAll 的「清表 + 重算」互斥：它们持 clockLock 改
      // entries 与 bytes；若这里在锁外「判活 → 加账」，交错时会出现「条目已被清掉、账却仍被加上」的
      // 永久偏高（该节点随后被当死节点丢弃、永不扣回，淘汰阈值被一路推高，直到 reload 才归零）。
      // 锁序 entry → clockLock 安全：淘汰/移除路径（removeEntry/evictOverflow）只动 CHM 与 bytes，
      // 不会再取 entry 监视器，不存在反向加锁。同体积覆盖（delta == 0）不进锁，热路径不变。
      if (delta != 0L) {
        synchronized (clockLock) {
          if (entries.get(entry.key) == entry) {
            bytes.addAndGet(delta);
          }
        }
      }
    }
  }

  /**
   * 摊还清理：从 CLOCK 环队首起最多巡检 {@value #CLEANUP_NODES_PER_PUT} 个节点，处理三件事——
   * <ul>
   *   <li><b>丢弃死节点</b>：该键已从主存储消失（被 {@link #get} 的过期检查或失效方法移除）却仍留在环里
   *       的陈旧引用，直接丢弃，避免环随冷条目读取无限增长；</li>
   *   <li><b>主动剔除过期冷条目</b>：{@code put} 一次后永不再被 {@code get} 的条目，按 {@code lastAccessNanos}
   *       判定已过期即从主存储移除，兑现 {@code expireAfterAccess} 的回收承诺；</li>
   *   <li><b>保留仍有效的节点</b>：原样轮转回队尾，<b>不改动其 second-chance 位</b>，淘汰语义与顺序保持不变。</li>
   * </ul>
   * 只在 {@link #clockLock} 内调用；每次最多常量条，均摊成本 O(1)。
   */
  private void amortizedCleanup() {
    long now = clock.getAsLong();
    for (int inspected = 0; inspected < CLEANUP_NODES_PER_PUT; inspected++) {
      CacheEntry<V> candidate = clockQueue.pollFirst();
      if (candidate == null) {
        return;
      }
      if (entries.get(candidate.key) != candidate) {
        continue; // 死节点：键已被移除，环里只剩陈旧引用，直接丢弃
      }
      if (now - candidate.lastAccessNanos > expireAfterAccessNanos) {
        removeEntry(candidate.key, candidate); // 过期冷条目：主动回收，不再无限期驻留
        continue;
      }
      clockQueue.addLast(candidate); // 仍有效：原样轮转回队尾（保留 referenced 位）
    }
  }

  /**
   * second-chance 淘汰：<b>先剔除已过期条目</b>（它们绝不占用淘汰名额、也不该挤掉仍有效的条目），
   * 队列里已被移除的陈旧节点直接丢弃，被访问过的条目让一次机会。
   *
   * <p>两个上限（条目数 / 字节数）任一超限即继续淘汰；字节数只由 {@code weigher} 累计，
   * 未做字节记账时 {@code maximumBytes} 为 {@link Long#MAX_VALUE}，条件恒不成立。
   */
  private void evictOverflow() {
    long now = clock.getAsLong();
    while (entries.size() > maximumSize || bytes.get() > maximumBytes) {
      CacheEntry<V> candidate = clockQueue.pollFirst();
      if (candidate == null) {
        // 已知窗口（非「理论上不会发生」）：并发 put 可能在「已 entries.putIfAbsent、尚未入队」之间
        // 被本方法看到——此时 entries.size() 已超标但队列暂时为空。此刻直接放弃本轮淘汰是安全的：
        // 那个 put 紧接着就会入队并自己再调一次 evictOverflow；超额量上界 = 同时在途的 put 数
        // （≤ 工作线程数），对内存影响可忽略。把入队也塞进本锁虽能消除该窗口，但会把所有 put
        // 串行化，代价远大于收益。
        return;
      }
      if (entries.get(candidate.key) != candidate) {
        continue; // 陈旧节点（该键已被失效或过期移除）
      }
      if (now - candidate.lastAccessNanos > expireAfterAccessNanos) {
        // 已过期：直接移除并释放名额——过期条目不应被 CLOCK 当作有效候选
        removeEntry(candidate.key, candidate);
        continue;
      }
      if (candidate.referenced) {
        candidate.referenced = false;
        clockQueue.addLast(candidate);
        continue;
      }
      removeEntry(candidate.key, candidate);
    }
  }

  /**
   * 从主存储移除并扣减字节账；只有真正移除成功的那一次才扣（并发下另一个线程可能已先删）。
   *
   * @return 是否真的移除了
   */
  private boolean removeEntry(Key key, CacheEntry<V> entry) {
    if (!entries.remove(key, entry)) {
      return false;
    }
    if (entry.sizeBytes != 0L) {
      bytes.addAndGet(-entry.sizeBytes);
    }
    return true;
  }

  /** 使某个世界的全部条目失效（世界卸载时调用）。 */
  public void invalidateWorld(String worldName) {
    synchronized (clockLock) {
      entries.keySet().removeIf(key -> key.worldName().equals(worldName));
      clockQueue.removeIf(entry -> entry.key.worldName().equals(worldName));
      // 移除路径无法逐条扣账（removeIf 不回报被删条目），这里按剩余条目重算一次；
      // 世界卸载是低频事件，O(n) 重算可忽略，换来的是字节账绝不漂移。
      recomputeBytes();
    }
  }

  /** 使全部条目失效（配置重载等场景）。 */
  public void invalidateAll() {
    synchronized (clockLock) {
      entries.clear();
      clockQueue.clear();
      bytes.set(0L);
    }
  }

  /** 按主存储里的现存条目重算字节总数（只在失效等低频路径调用）。 */
  private void recomputeBytes() {
    if (weigher == null) {
      return;
    }
    long total = 0L;
    for (CacheEntry<V> entry : entries.values()) {
      total += entry.sizeBytes;
    }
    bytes.set(total);
  }

  /** 当前条目占用的近似字节总数（诊断用；{@code weigher == null} 时恒为 0）。 */
  public long bytes() {
    return bytes.get();
  }

  /**
   * 当前<b>有效</b>缓存条目数（诊断用）。
   *
   * <p>过期条目在未被摊还清理巡检到之前仍可能短暂驻留，本方法据此按访问时间过滤，避免「虚高」的条目数
   * 误导诊断；这与淘汰时的过期剔除口径一致（过期条目既不计入 size，也不占淘汰名额）。
   */
  public int size() {
    long now = clock.getAsLong();
    int live = 0;
    for (CacheEntry<V> entry : entries.values()) {
      if (now - entry.lastAccessNanos <= expireAfterAccessNanos) {
        live++;
      }
    }
    return live;
  }

  /**
   * 当前<b>原始</b>条目数（含尚未被摊还清理巡检到的过期条目）。本方法当前仅单测在用，保留以免破坏测试。
   *
   * <p>与 {@link #size()}（仅活跃条目）<b>口径分离</b>：两者之差即「已过期、但尚未被巡检回收」的驻留量。
   * 持久占用只会以本值为上界（受 {@code maximumSize} 与 {@code maximumBytes} 共同约束）。
   */
  public int rawSize() {
    return entries.size();
  }

  public long hitCount() {
    return hits.sum();
  }

  public long missCount() {
    return misses.sum();
  }
}
