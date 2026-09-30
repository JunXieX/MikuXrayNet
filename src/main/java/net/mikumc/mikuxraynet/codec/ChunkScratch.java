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
 *       归还只把 scratch 放回空闲池，不保留任何区块数据（数组下次借出时由借出方重新初始化或校验）；</li>
 *   <li>调用方忘记 close，scratch 只是失去引用交给 GC，既不泄漏也不影响正确性（下次取用的是新实例）；</li>
 *   <li>空闲池每线程最多保留 {@value #MAX_IDLE_PER_THREAD} 个 scratch，占用有界。</li>
 * </ul>
 */
final class ChunkScratch {

  /** 每线程空闲池上限：正常串行处理时只需 1 个，留 2 个只作为嵌套解码（同线程同时开两个 Chunk）的余量。 */
  private static final int MAX_IDLE_PER_THREAD = 2;

  /**
   * 输出缓冲区容量的绝对上限（字节）。合法区块的未压缩负载远小于此值（典型几百 KB 到几 MB），
   * 上限只为挡住「容量不足 → 翻倍重写」在极端/损坏输入下的指数膨胀：若无上限，从几字节起翻倍
   * 数十次会先撞上 {@link OutOfMemoryError}，而 {@code Error} <b>不被</b>封包链路的
   * {@code catch (RuntimeException)} 兜住，会直接冒泡破坏 fail-open 纪律。
   */
  static final int MAX_OUTPUT_CAPACITY = 64 * 1024 * 1024;

  /**
   * 输出缓冲区<b>保留</b>上限（字节）：超过它的数组在本次借出后不再被 scratch 保留，下次按需重新分配。
   *
   * <p>为什么要设保留上限：{@link #outputArray} 只会「只增不减」地记住历史最大容量，一旦某个异常/损坏输入
   * 把它撑到接近 {@link #MAX_OUTPUT_CAPACITY}（64 MiB），该数组就会随线程常驻（每线程最多
   * {@value #MAX_IDLE_PER_THREAD} 个 scratch，单线程可常驻上百 MB）。
   *
   * <p>为什么取 4 MiB：合法区块的未压缩负载典型为几十 KB 到数百 KB（384 高世界的最坏个例也仅约 200 KB），
   * 4 MiB 对正常负载有 &gt;10 倍余量；超过它基本只可能是异常输入。因此只在「明显过大」时才丢弃，稳态下
   * 小数组照常复用、性能不受影响。
   */
  static final int MAX_RETAINED_OUTPUT_CAPACITY = 4 * 1024 * 1024;

  /**
   * 输出缓冲区容量已达绝对上限、无法再扩容。它与「数据损坏」区分开：表示「需要更大的输出缓冲而不可得」，
   * 由上层按 fail-open 放行原包；是 {@link RuntimeException} 而非 {@code Error}，能被封包链路兜住。
   */
  static final class OutputOverflowException extends RuntimeException {
    OutputOverflowException(String message) {
      super(message);
    }
  }

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

  /** 借一个长度不小于 {@code size} 的字节数组；内容未初始化，由借出方负责填充或校验。 */
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

  /**
   * 输出缓冲区容量不足时扩容：至少翻倍，避免输出远大于输入时反复小步扩容；容量封顶于
   * {@link #MAX_OUTPUT_CAPACITY}（见其说明，防止无限翻倍到 OOM）。
   *
   * @throws OutputOverflowException 需要的容量超过绝对上限、或已无法再增长（交给上层 fail-open）
   */
  byte[] growOutput(int minCapacity) {
    if (minCapacity > MAX_OUTPUT_CAPACITY) {
      throw new OutputOverflowException(
          "需要的输出容量 " + minCapacity + " 字节超过绝对上限 " + MAX_OUTPUT_CAPACITY);
    }
    int capacity = this.outputArray == null ? minCapacity : Math.max(minCapacity, this.outputArray.length * 2);
    if (capacity > MAX_OUTPUT_CAPACITY) {
      capacity = MAX_OUTPUT_CAPACITY;
    }
    if (this.outputArray != null && capacity <= this.outputArray.length) {
      // 容量已被上限锁死、无法再增长：显式报「容量不足」而不是返回一个仍不够用的数组
      throw new OutputOverflowException(
          "输出容量已达绝对上限 " + MAX_OUTPUT_CAPACITY + " 字节，无法继续扩容");
    }
    byte[] grown = new byte[Math.max(1, capacity)];
    // 只在容量未超过保留上限时才记住它；过大则本次照常使用、但不驻留（下次按需重分配），
    // 避免一个异常输入把近 64 MiB 的数组永久钉在“该线程的复用池”里。
    this.outputArray = grown.length > MAX_RETAINED_OUTPUT_CAPACITY ? null : grown;
    return grown;
  }

  /**
   * 放弃对当前输出数组的引用（{@link Chunk#finalizeOutput()} 零拷贝移交时调用）：把 {@link #outputArray}
   * 清空，使下一次 {@link #outputArray(int)} / {@link #growOutput(int)} 另分配新数组，
   * <b>绝不把已交给调用方独占的数组再借给后续区块复用</b>（否则下一个区块会覆盖正在被缓存/落盘的字节）。
   */
  void detachOutput() {
    this.outputArray = null;
  }

  /**
   * 记录一次写出实际用到的输出容量（只增不减）。
   *
   * <p>正常路径下写出直接用本 scratch 的数组；若底层缓冲区自行扩容（复用的数组偏小），
   * 这里把真实容量同步回来，使同线程随后的区块一次到位，不再重复扩容。
   */
  void keepOutputCapacity(int capacity) {
    if (this.outputArray != null && this.outputArray.length >= capacity) {
      return;
    }
    int size = Math.max(1, capacity);
    // 与 growOutput 同口径：超过保留上限的数组不驻留（下次按需重分配），避免大数组永久留在复用池里
    this.outputArray = size > MAX_RETAINED_OUTPUT_CAPACITY ? null : new byte[size];
  }
}