package net.mikumc.mikuxraynet.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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

  /** 条目的保留判定（用于压缩回收时丢弃过期/旧代次条目）。 */
  @FunctionalInterface
  interface EntryFilter {
    boolean keep(int chunkIndex, BufferedLinearV3Format.Entry entry);
  }

  private final Path path;
  private final int hashSeed;
  private final int bucketCacheSize;
  private final long[] positions = new long[BufferedLinearV3Format.BUCKET_COUNT];
  private final long[] bucketSizes = new long[BufferedLinearV3Format.BUCKET_COUNT];
  private final BufferedLinearV3Format.Entry[][] slots =
      new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_COUNT][];
  private final boolean[] dirty = new boolean[BufferedLinearV3Format.BUCKET_COUNT];

  /** 已加载 bucket 的 LRU（accessOrder=true，值恒为 FALSE，仅借其顺序）。 */
  private final LinkedHashMap<Integer, Boolean> loaded = new LinkedHashMap<>(8, 0.75f, true);

  private FileChannel channel;
  private long fileSize;
  private long liveBytes;
  private long garbageBytes;
  private boolean compacting;
  private boolean closed;

  private RegionFile(Path path, FileChannel channel, int hashSeed, int bucketCacheSize) {
    this.path = path;
    this.channel = channel;
    this.hashSeed = hashSeed;
    this.bucketCacheSize = Math.max(1, bucketCacheSize);
  }

  /**
   * 打开（必要时创建）区域文件。
   *
   * <p>文件头损坏时抛 {@link IOException}，由调用方决定「删掉重建」还是「跳过」。
   */
  static RegionFile open(Path path, int bucketCacheSize) throws IOException {
    FileChannel channel = FileChannel.open(path,
        StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    int hashSeed = BufferedLinearV3Format.DEFAULT_HASH_SEED;
    long size = channel.size();
    try {
      if (size > 0) {
        if (size < BufferedLinearV3Format.DATA_AREA_OFFSET) {
          throw new IOException("区域文件过小（" + size + " 字节）");
        }
        hashSeed = BufferedLinearV3Format.decodeHeader(
            readAt(channel, 0, BufferedLinearV3Format.HEADER_SIZE));
      }
    } catch (IOException exception) {
      closeQuietly(channel);
      throw exception;
    }

    RegionFile file = new RegionFile(path, channel, hashSeed, bucketCacheSize);
    try {
      if (size <= 0L) {
        // 新建（或刚被清空）的文件：必须先落「文件头 + 全零偏移表」，
        // 否则后续 append 会从偏移 0 开始写，直接把头部与偏移表踩掉（重开时被判为损坏）。
        file.writeHeaderAndEmptyTable();
      } else {
        file.loadIndex(size);
      }
    } catch (IOException exception) {
      file.closeQuietly();
      throw exception;
    }
    return file;
  }

  /** 写入文件头与全零偏移表，并把数据区起始位置作为当前文件长度。 */
  private void writeHeaderAndEmptyTable() throws IOException {
    writeFully(channel, ByteBuffer.wrap(BufferedLinearV3Format.encodeHeader(hashSeed)), 0L);
    writeFully(channel, ByteBuffer.wrap(BufferedLinearV3Format.encodePosTable(positions)),
        BufferedLinearV3Format.POS_TABLE_OFFSET);
    channel.force(false);
    this.fileSize = BufferedLinearV3Format.DATA_AREA_OFFSET;
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

  /** 读取一个区块的条目；不存在或损坏时为 {@code null}。 */
  BufferedLinearV3Format.Entry get(int chunkIndex) {
    BufferedLinearV3Format.Entry[] bucketSlots = ensureLoaded(bucketIndex(chunkIndex));
    return bucketSlots == null ? null : bucketSlots[slotInBucket(chunkIndex)];
  }

  /** 已加载 bucket 内的近似条目数（诊断用；未加载的 bucket 不计入）。 */
  int approximateEntryCount() {
    int count = 0;
    for (BufferedLinearV3Format.Entry[] bucketSlots : slots) {
      if (bucketSlots == null) {
        continue;
      }
      for (BufferedLinearV3Format.Entry entry : bucketSlots) {
        if (entry != null) {
          count++;
        }
      }
    }
    return count;
  }

  // ------------------------------------------------------------------ 写

  /**
   * 写入一个区块的条目（只改内存并标脏，落盘由 {@link #flushDirty()} 完成）。
   *
   * @return true 表示覆盖了已有条目
   */
  boolean put(int chunkIndex, BufferedLinearV3Format.Entry entry) {
    BufferedLinearV3Format.Entry[] bucketSlots = ensureLoaded(bucketIndex(chunkIndex));
    int slot = slotInBucket(chunkIndex);
    boolean replaced = bucketSlots[slot] != null;
    bucketSlots[slot] = entry;
    dirty[bucketIndex(chunkIndex)] = true;
    return replaced;
  }

  /** 清空一个区块的条目（惰性清理过期/旧代次条目时使用）。 */
  boolean clear(int chunkIndex) {
    BufferedLinearV3Format.Entry[] bucketSlots = ensureLoaded(bucketIndex(chunkIndex));
    int slot = slotInBucket(chunkIndex);
    if (bucketSlots[slot] == null) {
      return false;
    }
    bucketSlots[slot] = null;
    dirty[bucketIndex(chunkIndex)] = true;
    return true;
  }

  /** 把所有脏 bucket 追加写入文件并回填偏移表；返回是否真的写过。 */
  boolean flushDirty() throws IOException {
    boolean wrote = false;
    for (int bucket = 0; bucket < BufferedLinearV3Format.BUCKET_COUNT; bucket++) {
      if (dirty[bucket] && slots[bucket] != null) {
        wrote |= flushBucket(bucket);
      }
    }
    if (wrote) {
      writePosTable();
      channel.force(false);
      garbageBytes = Math.max(0L, fileSize - BufferedLinearV3Format.DATA_AREA_OFFSET - liveBytes);
    }
    return wrote;
  }

  /** 追加写入单个 bucket（append-only：旧副本变成垃圾，由 {@link #compact} 回收）。 */
  private boolean flushBucket(int bucket) throws IOException {
    if (fileSize < BufferedLinearV3Format.DATA_AREA_OFFSET) {
      // 兜底不变式：数据区之前永远先有头部与偏移表，绝不把 bucket 写进元数据区
      writeHeaderAndEmptyTable();
    }

    byte[] raw = BufferedLinearV3Format.encodeBucket(slots[bucket], hashSeed);
    byte[] compressed = BufferedLinearV3Format.compress(raw);
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
    dirty[bucket] = false;
    return true;
  }

  /** 整文件重写，丢弃 {@code keep} 判定为不需要的条目；返回丢弃的条目数。 */
  int compact(EntryFilter keep) throws IOException {
    compacting = true;
    try {
      BufferedLinearV3Format.Entry[][] all =
          new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_COUNT][];
      for (int bucket = 0; bucket < BufferedLinearV3Format.BUCKET_COUNT; bucket++) {
        all[bucket] = ensureLoaded(bucket);
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
          byte[] compressed = BufferedLinearV3Format.compress(raw);
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
        writeFully(out, ByteBuffer.wrap(BufferedLinearV3Format.encodeHeader(hashSeed)), 0L);
        writeFully(out, ByteBuffer.wrap(BufferedLinearV3Format.encodePosTable(newPositions)),
            BufferedLinearV3Format.POS_TABLE_OFFSET);
        out.force(true);
      } catch (IOException exception) {
        Files.deleteIfExists(temp);
        throw exception;
      }

      channel.close();
      try {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicFailure) {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
      channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
          StandardOpenOption.WRITE);

      for (int bucket = 0; bucket < all.length; bucket++) {
        positions[bucket] = newPositions[bucket];
        bucketSizes[bucket] = newSizes[bucket];
        dirty[bucket] = false;
        loaded.remove(bucket);
      }
      slotsLoad(all);
      fileSize = offset;
      liveBytes = offset - BufferedLinearV3Format.DATA_AREA_OFFSET;
      garbageBytes = 0L;
      return dropped;
    } finally {
      compacting = false;
      evictIfNeeded();
    }
  }

  /** 压缩回收后把全部 bucket 放回 LRU（超限部分由随后的一次驱逐处理）。 */
  private void slotsLoad(BufferedLinearV3Format.Entry[][] all) {
    for (int bucket = 0; bucket < all.length; bucket++) {
      slots[bucket] = all[bucket];
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

  boolean isDirty() {
    for (boolean value : dirty) {
      if (value) {
        return true;
      }
    }
    return false;
  }

  boolean isEmptyFile() {
    return liveBytes <= 0L && !isDirty();
  }

  Path path() {
    return path;
  }

  // ------------------------------------------------------------------ 关闭

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      flushDirty();
    } finally {
      closeQuietly();
    }
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
    BufferedLinearV3Format.Entry[] bucketSlots = slots[bucket];
    if (bucketSlots != null) {
      loaded.get(bucket);
      return bucketSlots;
    }

    bucketSlots = new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
    if (positions[bucket] > 0L) {
      try {
        byte[] lengths = readAt(channel, positions[bucket], 8);
        ByteBuffer buffer = ByteBuffer.wrap(lengths);
        int rawLength = buffer.getInt();
        int compressedLength = buffer.getInt();
        if (rawLength > 0 && compressedLength > 0
            && positions[bucket] + 8L + compressedLength <= fileSize) {
          byte[] compressed = readAt(channel, positions[bucket] + 8L, compressedLength);
          bucketSlots = BufferedLinearV3Format.decodeBucket(
              BufferedLinearV3Format.decompress(compressed, rawLength), hashSeed);
        }
      } catch (IOException | RuntimeException exception) {
        // 结构损坏：整个 bucket 视为空（fail-open，绝不因此影响封包链路）
        bucketSlots = new BufferedLinearV3Format.Entry[BufferedLinearV3Format.BUCKET_SIZE];
      }
    }

    slots[bucket] = bucketSlots;
    loaded.put(bucket, Boolean.FALSE);
    evictIfNeeded();
    return bucketSlots;
  }

  /** LRU 驱逐：脏 bucket 先落盘再释放，避免丢写入。 */
  private void evictIfNeeded() {
    if (compacting) {
      return;
    }
    while (loaded.size() > bucketCacheSize) {
      Iterator<Map.Entry<Integer, Boolean>> iterator = loaded.entrySet().iterator();
      if (!iterator.hasNext()) {
        return;
      }
      int victim = iterator.next().getKey();
      iterator.remove();
      if (dirty[victim] && slots[victim] != null) {
        try {
          if (flushBucket(victim)) {
            writePosTable();
          }
        } catch (IOException exception) {
          // 落盘失败：保留脏标记与内存态，等下次 flush/close 再试
          dirty[victim] = true;
          continue;
        }
      }
      slots[victim] = null;
    }
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