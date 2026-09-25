package net.mikumc.mikuxraynet.codec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 按线程复用的区块编解码 scratch：缓存一次「解码 + 重编码」中需要反复新建的大数组，供同线程的下一个区块复用。
 *
 * <p>动机：一次编解码里每个<b>间接调色板</b>的 section 都会新建调色板反查表（长度 = 唯一方块状态数，
 * 实测 32 KB 级），单值调色板不需要反查表；连同位打包 long 数组，24 个 section 叠加就是每区块
 * 几百 KB 到 1 MB 级的分配量。这些数组只服务于「当前区块」，区块关闭后即可整批复用，
 * 因此这里按线程缓存：同一线程处理下一个区块时按同样顺序取回，稳态下几乎不再分配。
 *
 * <p>并发与生命周期约束：
 * <ul>
 *   <li>每个线程各持独立实例与独立空闲池（{@link ThreadLocal}），不跨线程共享，无需任何同步；</li>
 *   <li>{@link Chunk} 构造时 {@link #acquire()} 取用，{@link Chunk#close()} 时 {@link #recycle()} 归还；
 *       归还只把 scratch 放回空闲池，不保留任何区块数据（数组下次借出时由借出方重新初始化）；</li>
 *   <li>调用方忘记 close，scratch 只是失去引用交给 GC，既不泄漏也不影响正确性（下次取用的是新实例）；</li>
 *   <li>空闲池每线程最多保留 {@value #MAX_IDLE_PER_THREAD} 个 scratch，占用有界。</li>
 * </ul>
 */
final class ChunkScratch {

  /** 每线程空闲池上限：正常串行处理时只需 1 个，留 2 个只作为嵌套解码（同线程同时开两个 Chunk）的余量。 */
  private static final int MAX_IDLE_PER_THREAD = 2;

  private static final ThreadLocal<ArrayDeque<ChunkScratch>> IDLE =
      ThreadLocal.withInitial(ArrayDeque::new);

  /** 已缓存的字节数组（借出顺序固定，归还后按同一顺序复用）。 */
  private final ArrayList<byte[]> byteArrays = new ArrayList<>();
  /** 已缓存的 long 数组（长度必须精确匹配，位打包的长度校验依赖它）。 */
  private final ArrayList<long[]> longArrays = new ArrayList<>();

  private int byteCursor;
  private int longCursor;

  private byte[] outputArray;

  private boolean inUse;

  /** 取一个 scratch（优先复用本线程空闲池里的）；用毕必须 {@link #recycle()} 归还。 */
  static ChunkScratch acquire() {
    ChunkScratch scratch = IDLE.get().pollLast();
    if (scratch == null) {
      scratch = new ChunkScratch();
    }
    scratch.inUse = true;
    scratch.byteCursor = 0;
    scratch.longCursor = 0;
    return scratch;
  }

  /** 归还 scratch；重复归还无效（第二次调用什么也不做）。 */
  void recycle() {
    if (!this.inUse) {
      return;
    }
    this.inUse = false;
    ArrayDeque<ChunkScratch> idle = IDLE.get();
    if (idle.size() < MAX_IDLE_PER_THREAD) {
      idle.addLast(this);
    }
  }

  /** 借一个长度不小于 {@code size} 的字节数组；内容未初始化，由借出方负责填充（例如全填 0xFF）。 */
  byte[] bytes(int size) {
    if (this.byteCursor < this.byteArrays.size()) {
      byte[] cached = this.byteArrays.get(this.byteCursor);
      this.byteCursor++;
      if (cached.length >= size) {
        return cached;
      }
      byte[] fresh = new byte[size];
      this.byteArrays.set(this.byteCursor - 1, fresh);
      return fresh;
    }
    byte[] fresh = new byte[size];
    this.byteArrays.add(fresh);
    this.byteCursor++;
    return fresh;
  }

  /**
   * 借一个长度恰为 {@code size} 且已清零的 long 数组。
   *
   * <p>长度必须精确：位打包数组的长度会参与写出与读取校验；清零是因为最后一段 long 可能只被部分写入，
   * 残留位会让重编码字节与预期不符。
   */
  long[] longs(int size) {
    long[] array;
    if (this.longCursor < this.longArrays.size()) {
      long[] cached = this.longArrays.get(this.longCursor);
      if (cached.length == size) {
        array = cached;
      } else {
        array = new long[size];
        this.longArrays.set(this.longCursor, array);
      }
    } else {
      array = new long[size];
      this.longArrays.add(array);
    }
    this.longCursor++;
    Arrays.fill(array, 0L);
    return array;
  }

  /** 输出缓冲区用的字节数组；容量不足时扩容（见 {@link #growOutput(int)}）。 */
  byte[] outputArray(int minCapacity) {
    if (this.outputArray == null || this.outputArray.length < minCapacity) {
      return growOutput(minCapacity);
    }
    return this.outputArray;
  }

  /** 输出缓冲区容量不足时扩容：至少翻倍，避免输出远大于输入时反复小步扩容。 */
  byte[] growOutput(int minCapacity) {
    int capacity = this.outputArray == null ? minCapacity : Math.max(minCapacity, this.outputArray.length * 2);
    this.outputArray = new byte[Math.max(1, capacity)];
    return this.outputArray;
  }

  /**
   * 记录一次写出实际用到的输出容量（只增不减）。
   *
   * <p>正常路径下写出直接用本 scratch 的数组；若底层缓冲区自行扩容（复用的数组偏小），
   * 这里把真实容量同步回来，使同线程随后的区块一次到位，不再重复扩容。
   */
  void keepOutputCapacity(int capacity) {
    if (this.outputArray == null || this.outputArray.length < capacity) {
      this.outputArray = new byte[Math.max(1, capacity)];
    }
  }
}