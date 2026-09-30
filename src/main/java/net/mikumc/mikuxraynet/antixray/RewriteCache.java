package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 反矿透改写缓存（自研）。
 *
 * <p><b>生命周期设计与上游实现的核心差异</b>：键只由 {@code (世界名, chunkX, chunkZ, 配置指纹)}
 * 组成，全部为基本类型或不可变字符串，绝不持有 {@code World / Chunk / Player / PacketContainer}
 * 等强引用；因此条目不会把世界对象钉在堆上，世界卸载时再显式整体失效即可。
 *
 * <p>容量与过期策略：<b>近似 LRU</b>（second-chance / CLOCK）+ {@code maximumSize} +
 * {@code expireAfterAccess}。读取走无锁 {@link ConcurrentHashMap}（不再全局同步、不再每次访问改动
 * 链表），写入与淘汰在同步块内串行维护一条 second-chance 环形队列——因此多个工作线程可并发读取同一
 * 缓存，多条区块包的读路径不再被逐个串行化（旧实现是 {@code synchronized get} + accessOrder
 * {@code LinkedHashMap}，每次 get 都独占锁并改动链表、每次新建 {@code Key}）。淘汰语义仍为「优先淘汰
 * 最久未使用」：被访问过的条目获得一次「第二次机会」，未被再次访问即被淘汰。
 *
 * <p><b>过期回收为什么不能只靠 get / 顶满淘汰</b>：多数区块只下发一次、此后永不 {@code get}，这类
 * 「冷条目」既不触发 {@code get} 的过期检查、也在容量未顶满时不经过淘汰扫描，于是「按过期时间回收」
 * 对它们失效、条目会无限期驻留到顶满上限，{@code size()} 也会与真实占用背离。因此在每次新建条目的
 * {@link #put} 里做一次<b>摊还清理</b>（{@link #amortizedCleanup()}）：按 lastAccessNanos 巡检固定
 * 条数的环节点，回收过期冷条目并丢弃已从主存储消失的死节点。成本按 put 摊还为 O(1)，且沿用既有
 * {@link #clockLock}，不引入新锁、不改动 {@code maximumSize} 的容量语义（它仍是条目总数上限）。
 *
 * @param <V> 缓存值类型；由调用方保证值本身也不持有世界/封包引用
 */
public final class RewriteCache<V> {

  /** 缓存键：只含基本类型与不可变字符串。 */
  public record Key(String worldName, int x, int z, int configHash) {
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
    private volatile long lastAccessNanos;
    /** second-chance 位：被访问过即置 true；淘汰扫描时给一次「第二次机会」。 */
    private volatile boolean referenced;

    private CacheEntry(Key key, V value, long nowNanos) {
      this.key = key;
      this.value = value;
      this.lastAccessNanos = nowNanos;
      this.referenced = false;
    }
  }

  private final int maximumSize;
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
   * 这一小段；同键覆盖只写 volatile 字段、插入 {@code entries} 由 ConcurrentHashMap 保证线程安全，
   * 因此把它们移出锁后，不同键的并发回填不再互相阻塞，CLOCK 语义保持不变。
   */
  private final Object clockLock = new Object();

  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();

  /**
   * @param maximumSize              条目上限
   * @param expireAfterAccessSeconds 最后一次访问后的过期秒数
   */
  public RewriteCache(int maximumSize, long expireAfterAccessSeconds) {
    this(maximumSize, TimeUnit.SECONDS.toNanos(Math.max(1L, expireAfterAccessSeconds)), System::nanoTime);
  }

  /** 测试用构造：可注入时钟与纳秒级过期时间。 */
  RewriteCache(int maximumSize, long expireAfterAccessNanos, LongSupplier clock) {
    this.maximumSize = Math.max(1, maximumSize);
    this.expireAfterAccessNanos = Math.max(1L, expireAfterAccessNanos);
    this.clock = clock;
  }

  /** 读取；未命中或已过期返回 {@code null}（过期条目会被立即移除）。 */
  public V get(String worldName, int x, int z, int configHash) {
    CacheEntry<V> entry = entries.get(new Key(worldName, x, z, configHash));
    long now = clock.getAsLong();

    if (entry == null) {
      misses.increment();
      return null;
    }
    if (now - entry.lastAccessNanos > expireAfterAccessNanos) {
      entries.remove(entry.key, entry);
      misses.increment();
      return null;
    }

    entry.lastAccessNanos = now;
    entry.referenced = true;
    hits.increment();
    return entry.value;
  }

  /** 写入（覆盖同键旧值）。同键覆盖与 {@code entries} 插入不加锁，仅新建条目时才同步维护 CLOCK 队列。 */
  public void put(String worldName, int x, int z, int configHash, V value) {
    Key key = new Key(worldName, x, z, configHash);
    long now = clock.getAsLong();
    CacheEntry<V> existing = entries.get(key);
    if (existing != null) {
      // 同键覆盖：原地更新，避免在环形队列里留下重复/陈旧节点。只写 volatile 字段，无需加锁。
      existing.value = value;
      existing.lastAccessNanos = now;
      existing.referenced = true;
      return;
    }

    CacheEntry<V> entry = new CacheEntry<>(key, value, now);
    CacheEntry<V> previous = entries.putIfAbsent(key, entry);
    if (previous != null) {
      // 并发插入同键：以先入者为存活条目，仅更新其值，绝不重复入队（否则队列会长出陈旧节点）。
      previous.value = value;
      previous.lastAccessNanos = now;
      previous.referenced = true;
      return;
    }
    // 只有「真正新建条目」才进入同步块维护环形队列并做淘汰扫描——把串行范围缩到最小。
    synchronized (clockLock) {
      clockQueue.addLast(entry);
      evictOverflow();
      // 顶满淘汰只在超容量时触发，冷条目的过期回收必须另有着落：每次新建条目顺带巡检少量节点
      amortizedCleanup();
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
        entries.remove(candidate.key, candidate); // 过期冷条目：主动回收，不再无限期驻留
        continue;
      }
      clockQueue.addLast(candidate); // 仍有效：原样轮转回队尾（保留 referenced 位）
    }
  }

  /**
   * second-chance 淘汰：<b>先剔除已过期条目</b>（它们绝不占用淘汰名额、也不该挤掉仍有效的条目），
   * 队列里已被移除的陈旧节点直接丢弃，被访问过的条目让一次机会。
   */
  private void evictOverflow() {
    long now = clock.getAsLong();
    while (entries.size() > maximumSize) {
      CacheEntry<V> candidate = clockQueue.pollFirst();
      if (candidate == null) {
        // 理论上不会发生：每个存活条目在创建时都已入队
        return;
      }
      if (entries.get(candidate.key) != candidate) {
        continue; // 陈旧节点（该键已被失效或过期移除）
      }
      if (now - candidate.lastAccessNanos > expireAfterAccessNanos) {
        // 已过期：直接移除并释放名额——过期条目不应被 CLOCK 当作有效候选
        entries.remove(candidate.key, candidate);
        continue;
      }
      if (candidate.referenced) {
        candidate.referenced = false;
        clockQueue.addLast(candidate);
        continue;
      }
      entries.remove(candidate.key, candidate);
    }
  }

  /** 使某个世界的全部条目失效（世界卸载时调用）。 */
  public void invalidateWorld(String worldName) {
    synchronized (clockLock) {
      entries.keySet().removeIf(key -> key.worldName().equals(worldName));
      clockQueue.removeIf(entry -> entry.key.worldName().equals(worldName));
    }
  }

  /** 使全部条目失效（配置重载等场景）。 */
  public void invalidateAll() {
    synchronized (clockLock) {
      entries.clear();
      clockQueue.clear();
    }
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
   * 当前<b>原始</b>条目数（含尚未被摊还清理巡检到的过期条目）——诊断用。
   *
   * <p>与 {@link #size()}（仅活跃条目）<b>口径分离</b>：两者之差即「已过期、但尚未被巡检回收」的驻留量，
   * 可用于判断过期回收是否跟得上写入速率。持久占用只会以本值为上界（受 {@code maximumSize} 约束）。
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