package net.mikumc.mikuxraynet.occlusion;

import java.util.Objects;
import java.util.concurrent.locks.StampedLock;

/**
 * 以基本类型 {@code long} 为键的「单写多读」哈希表，读走乐观读、写独占。
 *
 * <p><b>为什么不直接用 {@code ConcurrentHashMap<Long, V>}</b>：遮挡查询是射线热路径的一部分，
 * 每跨过一个 section 就要查一次。装箱 {@code Long} 键会让每次查询都产生一次（不可复用的）分配，
 * 在实体内循环里构成可观垃圾。这里自己维护「开放寻址 + long[] 键 + Object[] 值」，
 * 查询零装箱、零分配。
 *
 * <p><b>并发模型</b>（与参考实现同一套 SWMR 协议）：
 * <ul>
 *   <li><b>读</b>（{@link #get}）：{@code tryOptimisticRead()} 先取戳，读取数组引用与探测结果，
 *       最后 {@code validate(stamp)}。未被写入打断则直接用该结果（无锁、无 CAS）；被打断则退化为
 *       真正的读锁重读一次。写者持写锁期间，乐观戳为 0、校验必然失败，因此绝不会读到写入中的
 *       半成品。</li>
 *   <li><b>写</b>（{@link #put}/{@link #remove}/{@link #clear}）：全程持写锁。</li>
 * </ul>
 *
 * <p><b>删除用墓碑</b>（{@code TOMBSTONE}）：开放寻址下直接留空会切断探测链，使后续同链键查不到。
 * 墓碑计入负载，在 {@code size + tombstones} 超过阈值时整体 rehash（顺带丢弃墓碑）。
 *
 * <p><b>本类不保证值对象的线程安全</b>：它只同步表结构本身，返回的值按引用给出，由使用方
 * （{@link BitmapOcclusion} 的 opaque 位读写）自行保证。
 *
 * @param <V> 值类型
 */
public final class SwmrLongMap<V> {

  /** 触发 rehash 的负载因子（含墓碑）。偏低换更短的探测链，符合「读多写少」的访问形态。 */
  private static final float LOAD_FACTOR = 0.6f;

  /** 墓碑标记（该槽曾占用、现已被删）。 */
  private static final byte TOMBSTONE = 2;
  /** 占用标记。 */
  private static final byte USED = 1;
  /** 初始容量（2 的幂）。 */
  private static final int MIN_CAPACITY = 16;

  private final StampedLock lock = new StampedLock();

  /**
   * 当前表代（键/值/槽位三数组<b>一次</b>发布）。
   *
   * <p><b>为什么必须打包成一个不可变对象</b>：三个数组若各自用一个 volatile 字段发布，乐观读侧
   * 也是三次独立 volatile 读，StampedLock 的乐观戳只能保证「读前读后有写则重试」，
   * <b>挡不住「读到新一代的 keys + 旧一代的 slots」这种跨代组合</b>（三次读在同步序上可以分别落在
   * 写者的三次写之间）。此时 {@code mask} 取自新数组、下标却用于旧数组，会抛
   * {@link ArrayIndexOutOfBoundsException}——而异常在 {@code validate} 之前抛出，乐观读的
   * 「事后校验」完全失效。打包成单个对象后，一次 volatile 读必然拿到同一代，结构上不可能跨代。
   */
  private volatile Table<V> table;
  private volatile int size;
  private volatile int tombstones;

  /** 一代表结构：三个数组容量一致，构造完成后<b>整体不可变</b>（只允许原地改数组元素，不允许换数组）。 */
  private static final class Table<V> {
    final long[] keys;
    final Object[] values;
    final byte[] slots;

    Table(int capacity) {
      this.keys = new long[capacity];
      this.values = new Object[capacity];
      this.slots = new byte[capacity];
    }
  }

  public SwmrLongMap() {
    this(MIN_CAPACITY);
  }

  /** 以「期望条目数」构造（实际容量向上取到 2 的幂，并按负载因子放大）。 */
  public SwmrLongMap(int expectedEntries) {
    this.table = new Table<>(capacityFor(Math.max(MIN_CAPACITY, expectedEntries)));
  }

  /**
   * 查询（乐观读，无锁）。
   *
   * @return 对应值；键不存在返回 {@code null}
   */
  public V get(long key) {
    long stamp = lock.tryOptimisticRead();
    Table<V> t = table;
    V result = probeGet(t, key);
    if (!lock.validate(stamp)) {
      stamp = lock.readLock();
      try {
        result = probeGet(table, key);
      } finally {
        lock.unlockRead(stamp);
      }
    }
    return result;
  }

  /**
   * 写入（独占）。同键覆盖时仅替换值，不改变 {@link #size}.
   *
   * @return 该键此前的值；此前不存在返回 {@code null}
   */
  public V put(long key, V value) {
    Objects.requireNonNull(value, "value");
    long stamp = lock.writeLock();
    try {
      return putLocked(key, value);
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /** 键不存在时才写入（独占）。 */
  public V putIfAbsent(long key, V value) {
    Objects.requireNonNull(value, "value");
    long stamp = lock.writeLock();
    try {
      Object existing = getLocked(key);
      if (existing != null) {
        @SuppressWarnings("unchecked")
        V cast = (V) existing;
        return cast;
      }
      putLocked(key, value);
      return null;
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * 删除（独占）。
   *
   * @return 被删除的值；不存在返回 {@code null}
   */
  @SuppressWarnings("unchecked")
  public V remove(long key) {
    long stamp = lock.writeLock();
    try {
      return removeLocked(key);
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * 批量删除（<b>整批只取一次写锁</b>）。
   *
   * <p>供「整列摘除」这类一次删几十到几百个键的路径使用：逐个调 {@link #remove} 会为每个键各取一次
   * 写锁（区块卸载时固定 256 次），而这里的键数量虽大但**绝大多数并不在表里**（一个区块通常只有
   * 二十来段建过表）。因此先用无锁乐观读筛出真正存在的键，再有则取一次写锁批量删——
   * 表里没有该列时开销降到 256 次只读探测、零写锁。
   *
   * @return 实际删除的条目数
   */
  public int removeAll(long[] keys) {
    if (keys == null || keys.length == 0) {
      return 0;
    }
    long stamp = lock.tryOptimisticRead();
    Table<V> t = table;
    int present = 0;
    for (long key : keys) {
      if (probeGet(t, key) != null) {
        present++;
      }
    }
    if (!lock.validate(stamp)) {
      // 期间有写入：乐观结论不可信，直接按「全部可能存在」处理（多删几个不存在的键不影响语义）
      present = 1;
    }
    if (present == 0) {
      return 0;
    }
    int removed = 0;
    stamp = lock.writeLock();
    try {
      for (long key : keys) {
        if (removeLocked(key) != null) {
          removed++;
        }
      }
    } finally {
      lock.unlockWrite(stamp);
    }
    return removed;
  }

  @SuppressWarnings("unchecked")
  private V removeLocked(long key) {
    Table<V> t = table;
    int mask = t.keys.length - 1;
    int index = spread(key) & mask;
    while (t.slots[index] != 0) {
      if (t.slots[index] == USED && t.keys[index] == key) {
        Object previous = t.values[index];
        t.slots[index] = TOMBSTONE;
        t.values[index] = null;
        size--;
        tombstones++;
        return (V) previous;
      }
      index = (index + 1) & mask;
    }
    return null;
  }

  /** 当前条目数（不含墓碑）。 */
  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /** 清空（独占）。原地清空当前代，不换数组（换数组会让并发的乐观读跨代，见 {@link #table} 说明）。 */
  public void clear() {
    long stamp = lock.writeLock();
    try {
      Table<V> t = table;
      for (int i = 0; i < t.slots.length; i++) {
        t.values[i] = null;
        t.slots[i] = 0;
      }
      size = 0;
      tombstones = 0;
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  // ------------------------------------------------------------------
  // 写锁内实现
  // ------------------------------------------------------------------

  private V putLocked(long key, V value) {
    Table<V> t = table;
    int capacity = t.keys.length;
    int mask = capacity - 1;
    int index = spread(key) & mask;
    int firstTombstone = -1;
    while (t.slots[index] != 0) {
      if (t.slots[index] == USED) {
        if (t.keys[index] == key) {
          @SuppressWarnings("unchecked")
          V previous = (V) t.values[index];
          t.values[index] = value;
          return previous;
        }
      } else if (firstTombstone < 0) {
        firstTombstone = index;
      }
      index = (index + 1) & mask;
    }

    if (firstTombstone >= 0) {
      // 复用墓碑：修改指针的落入位置，但要先确认键确实不在表里
      // （上面已扫完整个探测链直到空槽，故此处必然不存在同键）
      index = firstTombstone;
      tombstones--;
    }
    t.keys[index] = key;
    t.values[index] = value;
    t.slots[index] = USED;
    size++;

    if ((size + tombstones) * 1.0f / capacity >= LOAD_FACTOR) {
      rehash(capacity << 1);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private V getLocked(long key) {
    Table<V> t = table;
    int mask = t.keys.length - 1;
    int index = spread(key) & mask;
    while (t.slots[index] != 0) {
      if (t.slots[index] == USED && t.keys[index] == key) {
        return (V) t.values[index];
      }
      index = (index + 1) & mask;
    }
    return null;
  }

  private void rehash(int newCapacity) {
    Table<V> old = table;
    int oldCapacity = old.slots.length;

    Table<V> fresh = new Table<>(newCapacity);
    int newMask = newCapacity - 1;

    for (int i = 0; i < oldCapacity; i++) {
      if (old.slots[i] != USED) {
        continue;
      }
      long key = old.keys[i];
      int index = spread(key) & newMask;
      while (fresh.slots[index] != 0) {
        index = (index + 1) & newMask;
      }
      fresh.keys[index] = key;
      fresh.values[index] = old.values[i];
      fresh.slots[index] = USED;
    }

    // 单次 volatile 写发布整代：乐观读要么看到旧代、要么看到新代，绝不可能跨代混用数组。
    this.table = fresh;
    this.tombstones = 0;
  }

  // ------------------------------------------------------------------
  // 无锁探测
  // ------------------------------------------------------------------

  private static <V> V probeGet(Table<V> t, long key) {
    int mask = t.keys.length - 1;
    int index = spread(key) & mask;
    byte[] slots = t.slots;
    while (slots[index] != 0) {
      if (slots[index] == USED && t.keys[index] == key) {
        @SuppressWarnings("unchecked")
        V value = (V) t.values[index];
        return value;
      }
      index = (index + 1) & mask;
    }
    return null;
  }

  /** 键 → 桶下标的混合（splitmix 风格）：低位质量差的世界坐标键也能均匀落桶。 */
  private static int spread(long key) {
    long h = key * 0x9E3779B97F4A7C15L;
    return (int) (h ^ (h >>> 32));
  }

  private static int capacityFor(int expectedEntries) {
    int capacity = MIN_CAPACITY;
    int target = (int) Math.ceil(expectedEntries / LOAD_FACTOR);
    while (capacity < target && capacity < (1 << 30)) {
      capacity <<= 1;
    }
    return capacity;
  }
}
