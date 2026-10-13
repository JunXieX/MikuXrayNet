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

  private volatile long[] keys;
  private volatile Object[] values;
  private volatile byte[] slots;
  private volatile int size;
  private volatile int tombstones;

  public SwmrLongMap() {
    this(MIN_CAPACITY);
  }

  /** 以「期望条目数」构造（实际容量向上取到 2 的幂，并按负载因子放大）。 */
  public SwmrLongMap(int expectedEntries) {
    int capacity = capacityFor(Math.max(MIN_CAPACITY, expectedEntries));
    this.keys = new long[capacity];
    this.values = new Object[capacity];
    this.slots = new byte[capacity];
  }

  /**
   * 查询（乐观读，无锁）。
   *
   * @return 对应值；键不存在返回 {@code null}
   */
  @SuppressWarnings("unchecked")
  public V get(long key) {
    long stamp = lock.tryOptimisticRead();
    long[] k = keys;
    Object[] v = values;
    byte[] s = slots;
    V result = probeGet(k, v, s, key);
    if (!lock.validate(stamp)) {
      stamp = lock.readLock();
      try {
        result = probeGet(keys, values, slots, key);
      } finally {
        lock.unlockRead(stamp);
      }
    }
    return result;
  }

  /** 键是否存在（乐观读）。本方法当前仅单测在用，保留以免破坏测试。 */
  public boolean containsKey(long key) {
    return get(key) != null;
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
      int capacity = keys.length;
      int mask = capacity - 1;
      int index = spread(key) & mask;
      while (slots[index] != 0) {
        if (slots[index] == USED && keys[index] == key) {
          Object previous = values[index];
          slots[index] = TOMBSTONE;
          values[index] = null;
          size--;
          tombstones++;
          return (V) previous;
        }
        index = (index + 1) & mask;
      }
      return null;
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /** 当前条目数（不含墓碑）。 */
  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /** 清空（独占）。 */
  public void clear() {
    long stamp = lock.writeLock();
    try {
      int capacity = keys.length;
      for (int i = 0; i < capacity; i++) {
        values[i] = null;
        slots[i] = 0;
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
    int capacity = keys.length;
    int mask = capacity - 1;
    int index = spread(key) & mask;
    int firstTombstone = -1;
    while (slots[index] != 0) {
      if (slots[index] == USED) {
        if (keys[index] == key) {
          @SuppressWarnings("unchecked")
          V previous = (V) values[index];
          values[index] = value;
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
    keys[index] = key;
    values[index] = value;
    slots[index] = USED;
    size++;

    if ((size + tombstones) * 1.0f / capacity >= LOAD_FACTOR) {
      rehash(capacity << 1);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private V getLocked(long key) {
    int capacity = keys.length;
    int mask = capacity - 1;
    int index = spread(key) & mask;
    while (slots[index] != 0) {
      if (slots[index] == USED && keys[index] == key) {
        return (V) values[index];
      }
      index = (index + 1) & mask;
    }
    return null;
  }

  private void rehash(int newCapacity) {
    long[] oldKeys = keys;
    Object[] oldValues = values;
    byte[] oldSlots = slots;
    int oldCapacity = oldKeys.length;

    long[] newKeys = new long[newCapacity];
    Object[] newValues = new Object[newCapacity];
    byte[] newSlots = new byte[newCapacity];
    int newMask = newCapacity - 1;

    for (int i = 0; i < oldCapacity; i++) {
      if (oldSlots[i] != USED) {
        continue;
      }
      long key = oldKeys[i];
      int index = spread(key) & newMask;
      while (newSlots[index] != 0) {
        index = (index + 1) & newMask;
      }
      newKeys[index] = key;
      newValues[index] = oldValues[i];
      newSlots[index] = USED;
    }

    this.keys = newKeys;
    this.values = newValues;
    this.slots = newSlots;
    this.tombstones = 0;
  }

  // ------------------------------------------------------------------
  // 无锁探测
  // ------------------------------------------------------------------

  private static <V> V probeGet(long[] keys, Object[] values, byte[] slots, long key) {
    int mask = keys.length - 1;
    int index = spread(key) & mask;
    while (slots[index] != 0) {
      if (slots[index] == USED && keys[index] == key) {
        @SuppressWarnings("unchecked")
        V value = (V) values[index];
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
