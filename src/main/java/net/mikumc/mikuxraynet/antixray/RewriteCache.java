package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 反矿透改写缓存（自研）。
 *
 * <p><b>生命周期设计与上游实现的核心差异</b>：键只由 {@code (世界名, chunkX, chunkZ, 配置指纹)}
 * 组成，全部为基本类型或不可变字符串，绝不持有 {@code World / Chunk / Player / PacketContainer}
 * 等强引用；因此条目不会把世界对象钉在堆上，世界卸载时再显式整体失效即可。
 *
 * <p>容量与过期策略：LRU（{@code accessOrder=true}）+ {@code maximumSize} + {@code expireAfterAccess}。
 * 所有方法同步——缓存由多个工作线程并发访问。
 *
 * @param <V> 缓存值类型；由调用方保证值本身也不持有世界/封包引用
 */
public final class RewriteCache<V> {

  /** 缓存键：只含基本类型与不可变字符串。 */
  public record Key(String worldName, int x, int z, int configHash) {
  }

  private static final class Entry<V> {

    private final V value;
    private long lastAccessNanos;

    private Entry(V value, long nowNanos) {
      this.value = value;
      this.lastAccessNanos = nowNanos;
    }
  }

  private final int maximumSize;
  private final long expireAfterAccessNanos;
  private final LongSupplier clock;

  private final LinkedHashMap<Key, Entry<V>> entries;

  private long hits;
  private long misses;

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
    this.entries = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Key, Entry<V>> eldest) {
        return size() > RewriteCache.this.maximumSize;
      }
    };
  }

  /** 读取；未命中或已过期返回 {@code null}（过期条目会被立即移除）。 */
  public synchronized V get(String worldName, int x, int z, int configHash) {
    Key key = new Key(worldName, x, z, configHash);
    Entry<V> entry = entries.get(key);
    long now = clock.getAsLong();

    if (entry == null) {
      misses++;
      return null;
    }
    if (now - entry.lastAccessNanos > expireAfterAccessNanos) {
      entries.remove(key);
      misses++;
      return null;
    }

    entry.lastAccessNanos = now;
    hits++;
    return entry.value;
  }

  /** 写入（覆盖同键旧值）。 */
  public synchronized void put(String worldName, int x, int z, int configHash, V value) {
    entries.put(new Key(worldName, x, z, configHash), new Entry<>(value, clock.getAsLong()));
  }

  /** 使某个世界的全部条目失效（世界卸载时调用）。 */
  public synchronized void invalidateWorld(String worldName) {
    entries.keySet().removeIf(key -> key.worldName().equals(worldName));
  }

  /** 使全部条目失效（配置重载等场景）。 */
  public synchronized void invalidateAll() {
    entries.clear();
  }

  public synchronized int size() {
    return entries.size();
  }

  public synchronized long hitCount() {
    return hits;
  }

  public synchronized long missCount() {
    return misses;
  }
}