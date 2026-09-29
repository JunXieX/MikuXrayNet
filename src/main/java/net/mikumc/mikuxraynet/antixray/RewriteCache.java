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
 * @param <V> 缓存值类型；由调用方保证值本身也不持有世界/封包引用
 */
public final class RewriteCache<V> {

  /** 缓存键：只含基本类型与不可变字符串。 */
  public record Key(String worldName, int x, int z, int configHash) {
  }

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
   * <p>只在 {@link #put} / 失效方法的同步块内增删；读路径（{@link #get}）完全不碰它，
   * 因此读取不需要任何锁。队首是「最久未被再访问」的候选。
   */
  private final ArrayDeque<CacheEntry<V>> clockQueue = new ArrayDeque<>();

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

  /** 写入（覆盖同键旧值）。 */
  public synchronized void put(String worldName, int x, int z, int configHash, V value) {
    Key key = new Key(worldName, x, z, configHash);
    long now = clock.getAsLong();
    CacheEntry<V> existing = entries.get(key);
    if (existing != null) {
      // 同键覆盖：原地更新，避免在环形队列里留下重复/陈旧节点
      existing.value = value;
      existing.lastAccessNanos = now;
      existing.referenced = true;
      return;
    }

    CacheEntry<V> entry = new CacheEntry<>(key, value, now);
    entries.put(key, entry);
    clockQueue.addLast(entry);
    evictOverflow();
  }

  /** second-chance 淘汰：队列里已被移除的陈旧节点直接丢弃，被访问过的条目让一次机会。 */
  private void evictOverflow() {
    while (entries.size() > maximumSize) {
      CacheEntry<V> candidate = clockQueue.pollFirst();
      if (candidate == null) {
        // 理论上不会发生：每个存活条目在创建时都已入队
        return;
      }
      if (entries.get(candidate.key) != candidate) {
        continue; // 陈旧节点（该键已被失效或过期移除）
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
  public synchronized void invalidateWorld(String worldName) {
    entries.keySet().removeIf(key -> key.worldName().equals(worldName));
    clockQueue.removeIf(entry -> entry.key.worldName().equals(worldName));
  }

  /** 使全部条目失效（配置重载等场景）。 */
  public synchronized void invalidateAll() {
    entries.clear();
    clockQueue.clear();
  }

  public int size() {
    return entries.size();
  }

  public long hitCount() {
    return hits.sum();
  }

  public long missCount() {
    return misses.sum();
  }
}