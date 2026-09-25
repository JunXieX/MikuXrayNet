package net.mikumc.mikuxraynet.cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * BufferedLinearV3 区域文件格式：自包含实现，<b>只做格式与编解码</b>，
 * 不含任何平台耦合部分，也不使用 NMS 的 {@code ChunkPos}。
 *
 * <p><b>文件布局</b>
 * <pre>
 *   偏移 0     : 魔数 u64 = MAGIC
 *   偏移 8     : 版本 u8 = 0x04
 *   偏移 9     : 压缩方案 u8（0x01 = Deflate 历史格式；0x02 = Zstd 当前写入，见下）
 *   偏移 10    : 校验种子 i32（默认 0x0721）
 *   偏移 14    : 16 × u64 bucket 偏移表（每个 bucket 覆盖 64 个区块，共 1024 个区块 = 32×32）
 *   偏移 142   : 数据区，每个 bucket 为 [u32 原始长度][u32 压缩长度][压缩数据]
 * </pre>
 * 未写入的 bucket 偏移为 0。bucket 原始内容为 64 个槽位依次排列的 {@code [i32 条目长度][条目字节]}，
 * 长度 {@code <= 0} 表示空槽。条目字节为
 * {@code [i32 负载长度][i64 区块代次][i64 写入时间][i32 配置指纹][i32 负载校验和][负载]}，
 * 校验和为 {@link XXHash32}（与格式头种子一致）。
 *
 * <p><b>版本历史</b>：{@code 0x03} 为初版；{@code 0x04} 起明确「负载信封必须携带被伪装坐标清单」
 * （见 {@code DiskPayload}：{@code [i64 指纹][i32 坐标数][i32×N 坐标][区块字节]}），
 * 否则磁盘缓存命中的区块不会进入显形索引。读取 {@code 0x03} 旧文件时明确拒绝并（只提示一次）
 * 由调用方删除重建，避免复用与当前信封约定不一致的历史条目。
 *
 * <p><b>压缩方案（偏移 9 的那个字节）</b>：该字节决定整个文件里所有 bucket 的压缩算法。
 * 当前写入统一使用 <b>zstd</b>（{@code 0x02}），用的是服务端自带的 zstd-jni（{@code scope=provided}，
 * 不 shade、不进插件 jar；服务端若没有则由 {@link ZstdSupport} 在启动期自动下载到
 * {@code plugins/MikuXrayNet/lib}，详见该类的来源解析顺序）；历史文件里的 Deflate（{@code 0x01}）
 * 仍被完整支持，因此老的 {@code .b_linear} 缓存不会被当成损坏文件删除。读路径按该字节选择解压器：
 * {@code 0x01} 走 JDK {@link Inflater}，{@code 0x02} 走 zstd。
 *
 * <p><b>fail-open</b>：运行期若 zstd 不可用（服务端没有该库、自动下载失败、或原生库缺失导致类初始化抛
 * {@link Throwable} 等），压缩自动回退 JDK {@link Deflater}（{@link #currentCompression()} 因此返回
 * {@code 0x01}，保证「头部方案字节」与实际写入的压缩算法始终一致），并只提示一次中文日志。
 * 任何压缩/解压异常都只记日志并降级，绝不影响封包链路。
 */
public final class BufferedLinearV3Format {

  /** 与格式头一致的日志通道（本类为纯格式工具，不依赖外部注入的 Logger）。 */
  private static final Logger LOGGER = Logger.getLogger("MikuXrayNet");

  /** 仅提示一次的「zstd 回退 Deflater」日志开关（运行期压缩/解压抛异常的路径）。 */
  private static final AtomicBoolean ZSTD_FALLBACK_WARNED = new AtomicBoolean();

  /** 仅提示一次的「旧版（0x03）区域文件已被拒绝、将重建」日志开关。 */
  private static final AtomicBoolean LEGACY_VERSION_WARNED = new AtomicBoolean();

  /** 初版格式版本：其负载信封未约定必须携带被伪装坐标，读取时明确拒绝。 */
  private static final byte LEGACY_VERSION = 0x03;

  /**
   * zstd 压缩等级：<b>3 = 速度优先</b>（用户指定最快档）。
   *
   * <p>本项目取 3 而非更高等级：磁盘缓存属「可选加速」，压缩发生在上游重编码路径之后，
   * 等级越高越省磁盘字节但越拖慢写入；3 在压缩率与耗时之间取速度优先。
   * 该等级只影响压缩产物字节，不改动文件头的方案字节（{@link #COMPRESSION_ZSTD}），
   * 因此与既有 {@code 0x02} 缓存文件完全兼容、可互相解压。
   */
  private static final int ZSTD_LEVEL = 3;

  /** 与参考实现一致的文件魔数。 */
  public static final long MAGIC = 0xFFFFDFF7EDDAFD97L;

  /** 与参考实现一致的格式版本（bucket 化布局 + 负载信封携带伪装坐标）。 */
  public static final byte VERSION = 0x04;

  /** 压缩方案：JDK Deflater（历史格式，仅用于读取旧文件）。 */
  public static final byte COMPRESSION_DEFLATE = 0x01;

  /** 压缩方案：zstd（zstd-jni，服务端自带；新写入统一使用）。 */
  public static final byte COMPRESSION_ZSTD = 0x02;

  /** 文件头长度：魔数 8 + 版本 1 + 压缩方案 1 + 种子 4。 */
  public static final int HEADER_SIZE = 14;

  /** bucket 覆盖的区块数（2 的 6 次方）。 */
  public static final int BUCKET_SHIFT = 6;

  /** 单个 bucket 的区块槽位数。 */
  public static final int BUCKET_SIZE = 1 << BUCKET_SHIFT;

  /** bucket 数量：1024 个区块 / 64。 */
  public static final int BUCKET_COUNT = 1024 / BUCKET_SIZE;

  /** 偏移表在文件中的位置（紧随头部）。 */
  public static final int POS_TABLE_OFFSET = HEADER_SIZE;

  /** 偏移表长度：16 × u64。 */
  public static final int POS_TABLE_SIZE = BUCKET_COUNT * Long.BYTES;

  /** 数据区起始偏移。 */
  public static final long DATA_AREA_OFFSET = POS_TABLE_OFFSET + POS_TABLE_SIZE;

  /** 默认校验种子（与参考实现一致）。 */
  public static final int DEFAULT_HASH_SEED = 0x0721;

  /** 单个条目负载的上限（防止损坏数据造成超大分配）：16 MiB。 */
  public static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

  /** 单个 bucket 解压后原始数据的上限（64 个槽位）：512 MiB。 */
  public static final int MAX_RAW_SIZE = 512 * 1024 * 1024;

  /**
   * 单条缓存条目：区块代次 + 写入时间 + 配置指纹 + 负载。
   */
  public record Entry(long generation, long writtenAtMillis, int configHash, byte[] payload) {
  }

  /** 已解析的文件头：校验种子 + 压缩方案字节。 */
  public record Header(int hashSeed, byte compression) {
  }

  private BufferedLinearV3Format() {
  }

  // ------------------------------------------------------------------ 压缩方案

  /**
   * zstd 不可用/异常时的唯一一次中文提示（并发安全）。
   *
   * <p>启动期的「是否可用」结论由 {@link ZstdSupport} 负责（它有三种来源，并会输出检测结果日志）；
   * 这里的提示只针对「zstd 明明可用却在运行期抛了异常」这一路径，避免同一件事刷两遍屏。
   */
  private static void warnZstdFallbackOnce(Throwable throwable) {
    if (ZSTD_FALLBACK_WARNED.compareAndSet(false, true)) {
      LOGGER.warning("zstd（zstd-jni）运行期异常，磁盘缓存区块压缩已自动回退 JDK Deflater："
          + throwable + "（缓存读写不受影响，绝不阻塞封包链路）");
    }
  }

  /** 旧版（0x03）文件被拒绝时的唯一一次中文提示（并发安全）；由调用方删除重建。 */
  private static void warnLegacyVersionOnce(byte version) {
    if (version == LEGACY_VERSION && LEGACY_VERSION_WARNED.compareAndSet(false, true)) {
      LOGGER.warning("检测到旧版磁盘缓存格式（0x03）：新版（0x04）要求负载信封携带被伪装坐标清单，"
          + "旧文件将被拒绝并删除重建（缓存只是可选加速，不影响封包链路，也绝不影响反矿透本身）");
    }
  }

  /**
   * 当前写入使用的压缩方案字节：zstd 可用时为 {@link #COMPRESSION_ZSTD}，否则 {@link #COMPRESSION_DEFLATE}。
   *
   * <p>{@link #encodeHeader(int)} 与实际压缩都取此值，因此「头部声明的方案」与「bucket 实际算法」永远一致。
   */
  public static byte currentCompression() {
    return ZstdSupport.available() ? COMPRESSION_ZSTD : COMPRESSION_DEFLATE;
  }

  // ------------------------------------------------------------------ 坐标换算

  /** 区块坐标 → region 内槽位序号（0..1023）；与参考实现一致（x 低位、z 高位）。 */
  public static int chunkIndex(int chunkX, int chunkZ) {
    return (chunkX & 31) + ((chunkZ & 31) << 5);
  }

  /** 槽位序号 → 所属 bucket（0..15）。 */
  public static int bucketIndex(int chunkIndex) {
    return chunkIndex >>> BUCKET_SHIFT;
  }

  /** 区块坐标 → region 坐标。 */
  public static int regionCoordinate(int chunkCoordinate) {
    return chunkCoordinate >> 5;
  }

  // ------------------------------------------------------------------ 文件头

  /** 编码 14 字节文件头（压缩方案字节取 {@link #currentCompression()}）。 */
  public static byte[] encodeHeader(int hashSeed) {
    ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
    buffer.putLong(MAGIC);
    buffer.put(VERSION);
    buffer.put(currentCompression());
    buffer.putInt(hashSeed);
    return buffer.array();
  }

  /** 解析文件头，返回校验种子（兼容 0x01/0x02 两种压缩方案）；等价于 {@link #decodeHeaderInfo(byte[])} 的种子。 */
  public static int decodeHeader(byte[] raw) throws IOException {
    return decodeHeaderInfo(raw).hashSeed();
  }

  /**
   * 解析文件头，返回校验种子与压缩方案字节。
   *
   * @throws IOException 长度不足、魔数/版本不符，或压缩方案既不是 Deflate 也不是 Zstd
   */
  public static Header decodeHeaderInfo(byte[] raw) throws IOException {
    if (raw == null || raw.length < HEADER_SIZE) {
      throw new IOException("区域文件头不足 " + HEADER_SIZE + " 字节，已损坏");
    }
    ByteBuffer buffer = ByteBuffer.wrap(raw);
    long magic = buffer.getLong();
    if (magic != MAGIC) {
      throw new IOException("未知魔数 0x" + Long.toHexString(magic));
    }
    byte version = buffer.get();
    if (version != VERSION) {
      warnLegacyVersionOnce(version);
      throw new IOException("不支持的格式版本 " + version + "（当前支持 " + VERSION
          + (version == LEGACY_VERSION ? "；旧版缓存将被删除重建" : "") + "）");
    }
    byte compression = buffer.get();
    if (compression != COMPRESSION_DEFLATE && compression != COMPRESSION_ZSTD) {
      throw new IOException("不支持的压缩方案 " + compression);
    }
    return new Header(buffer.getInt(), compression);
  }

  // ------------------------------------------------------------------ 偏移表

  /** 编码偏移表（16 个 u64）。 */
  public static byte[] encodePosTable(long[] offsets) {
    ByteBuffer buffer = ByteBuffer.allocate(POS_TABLE_SIZE);
    for (int i = 0; i < BUCKET_COUNT; i++) {
      buffer.putLong(i < offsets.length ? offsets[i] : 0L);
    }
    return buffer.array();
  }

  /** 解析偏移表。 */
  public static long[] decodePosTable(byte[] raw) throws IOException {
    if (raw == null || raw.length < POS_TABLE_SIZE) {
      throw new IOException("偏移表不足 " + POS_TABLE_SIZE + " 字节，已损坏");
    }
    ByteBuffer buffer = ByteBuffer.wrap(raw);
    long[] offsets = new long[BUCKET_COUNT];
    for (int i = 0; i < BUCKET_COUNT; i++) {
      offsets[i] = buffer.getLong();
    }
    return offsets;
  }

  // ------------------------------------------------------------------ 压缩

  /**
   * 压缩一段数据：优先 zstd（{@link #ZSTD_LEVEL} = 3，速度优先），
   * zstd 不可用或其抛异常时回退 {@link Deflater#BEST_SPEED}（fail-open）。
   */
  public static byte[] compress(byte[] raw) {
    if (ZstdSupport.available()) {
      try {
        return ZstdSupport.compress(raw, ZSTD_LEVEL);
      } catch (Throwable throwable) {
        warnZstdFallbackOnce(throwable);
      }
    }
    return deflate(raw);
  }

  /** JDK Deflater（BEST_SPEED）压缩：历史格式的写入路径，也是 zstd 不可用时的回退路径。 */
  private static byte[] deflate(byte[] raw) {
    Deflater deflater = new Deflater(Deflater.BEST_SPEED);
    try {
      deflater.setInput(raw);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2 + 64));
      byte[] chunk = new byte[8192];
      while (!deflater.finished()) {
        int produced = deflater.deflate(chunk);
        if (produced <= 0) {
          break;
        }
        out.write(chunk, 0, produced);
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
  }

  /** 已知原始长度时解压；按 {@link #currentCompression()} 选择解压器（写读配对的便捷入口）。 */
  public static byte[] decompress(byte[] compressed, int rawLength) throws IOException {
    return decompress(compressed, rawLength, currentCompression());
  }

  /**
   * 已知原始长度与压缩方案时解压。
   *
   * <p>{@code compression} 来自文件头偏移 9 的字节：{@code 0x01} 走 {@link Inflater}（旧格式兼容），
   * {@code 0x02} 走 zstd。这样用户既有的 Deflate 缓存文件仍能被正确读取。
   *
   * @throws IOException 长度非法、数据截断、损坏或解压长度与声明不符
   */
  public static byte[] decompress(byte[] compressed, int rawLength, byte compression)
      throws IOException {
    if (rawLength < 0 || rawLength > MAX_RAW_SIZE) {
      throw new IOException("解压长度非法：" + rawLength);
    }
    if (rawLength == 0) {
      return new byte[0];
    }
    if (compression == COMPRESSION_ZSTD) {
      return zstdInflate(compressed, rawLength);
    }
    return inflate(compressed, rawLength);
  }

  /** zstd 解压（已知原始长度）。任何缺失/损坏都转成 {@link IOException}，交由调用方 fail-open。 */
  private static byte[] zstdInflate(byte[] compressed, int rawLength) throws IOException {
    if (!ZstdSupport.available()) {
      throw new IOException("文件声明使用 zstd，但当前环境无 zstd（zstd-jni 不可用）");
    }
    try {
      byte[] out = ZstdSupport.decompress(compressed, rawLength);
      if (out == null || out.length != rawLength) {
        throw new IOException("zstd 解压长度不符：期望 " + rawLength + "，实际 "
            + (out == null ? -1 : out.length));
      }
      return out;
    } catch (IOException exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw new IOException("zstd 压缩数据损坏", throwable);
    }
  }

  /** JDK Inflater 解压（旧格式 Deflate 的读取路径）。 */
  private static byte[] inflate(byte[] compressed, int rawLength) throws IOException {
    byte[] out = new byte[rawLength];
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(compressed);
      int written = 0;
      while (written < rawLength) {
        int produced = inflater.inflate(out, written, rawLength - written);
        if (produced == 0) {
          if (inflater.needsInput() || inflater.needsDictionary()) {
            throw new IOException("压缩数据不完整（已解出 " + written + " / " + rawLength + " 字节）");
          }
          if (inflater.finished()) {
            break;
          }
        }
        written += produced;
      }
      if (written != rawLength) {
        throw new IOException("解压长度不符：期望 " + rawLength + "，实际 " + written);
      }
      return out;
    } catch (DataFormatException exception) {
      throw new IOException("压缩数据损坏", exception);
    } finally {
      inflater.end();
    }
  }

  // ------------------------------------------------------------------ 条目

  /** 单条条目的头部长度：负载长度 4 + 代次 8 + 写入时间 8 + 配置指纹 4 + 校验和 4。 */
  private static final int ENTRY_HEADER_SIZE = 4 + 8 + 8 + 4 + 4;

  /** 编码一条条目（含校验和）。 */
  public static byte[] encodeEntry(Entry entry, int hashSeed) {
    byte[] payload = entry.payload() == null ? new byte[0] : entry.payload();
    ByteBuffer buffer = ByteBuffer.allocate(ENTRY_HEADER_SIZE + payload.length);
    buffer.putInt(payload.length);
    buffer.putLong(entry.generation());
    buffer.putLong(entry.writtenAtMillis());
    buffer.putInt(entry.configHash());
    buffer.putInt(XXHash32.hash(payload, hashSeed));
    buffer.put(payload);
    return buffer.array();
  }

  /**
   * 解析一条条目；负载校验和不符时抛异常（阻止把坏数据当作命中返回）。
   *
   * @throws IOException 截断、长度非法或校验失败
   */
  public static Entry decodeEntry(byte[] raw, int hashSeed) throws IOException {
    if (raw == null || raw.length < ENTRY_HEADER_SIZE) {
      throw new IOException("条目截断（" + (raw == null ? 0 : raw.length) + " 字节）");
    }
    ByteBuffer buffer = ByteBuffer.wrap(raw);
    int payloadLength = buffer.getInt();
    if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE
        || payloadLength > raw.length - ENTRY_HEADER_SIZE) {
      throw new IOException("条目负载长度非法：" + payloadLength);
    }
    long generation = buffer.getLong();
    long writtenAt = buffer.getLong();
    int configHash = buffer.getInt();
    int expectedHash = buffer.getInt();

    byte[] payload = new byte[payloadLength];
    buffer.get(payload);
    int actualHash = XXHash32.hash(payload, hashSeed);
    if (expectedHash != actualHash) {
      throw new IOException("条目校验失败：期望 " + expectedHash + "，实际 " + actualHash);
    }
    return new Entry(generation, writtenAt, configHash, payload);
  }

  // ------------------------------------------------------------------ bucket

  /**
   * 编码一个 bucket（64 个槽位；空槽写长度为 0）。
   *
   * @param slots    长度应为 {@link #BUCKET_SIZE}，不足部分按空槽处理
   * @param hashSeed 条目校验种子
   */
  public static byte[] encodeBucket(Entry[] slots, int hashSeed) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
    for (int i = 0; i < BUCKET_SIZE; i++) {
      Entry entry = slots != null && i < slots.length ? slots[i] : null;
      if (entry == null || entry.payload() == null || entry.payload().length == 0) {
        writeInt(out, 0);
        continue;
      }
      byte[] encoded = encodeEntry(entry, hashSeed);
      writeInt(out, encoded.length);
      out.write(encoded, 0, encoded.length);
    }
    return out.toByteArray();
  }

  /**
   * 解析一个 bucket；单个槽位损坏时只把该槽位视为空槽（fail-open，不影响其它槽位）。
   *
   * @return 长度为 {@link #BUCKET_SIZE} 的槽位数组（无数据的槽位为 {@code null}）
   * @throws IOException bucket 整体结构损坏
   */
  public static Entry[] decodeBucket(byte[] raw, int hashSeed) throws IOException {
    final Entry[] slots = new Entry[BUCKET_SIZE];
    if (raw == null) {
      return slots;
    }
    int offset = 0;
    for (int i = 0; i < BUCKET_SIZE; i++) {
      if (offset + 4 > raw.length) {
        break;
      }
      int length = readInt(raw, offset);
      offset += 4;
      if (length <= 0) {
        continue;
      }
      if (length > raw.length - offset) {
        throw new IOException("bucket 槽位截断：槽位 " + i + " 需要 " + length + " 字节");
      }
      try {
        slots[i] = decodeEntry(Arrays.copyOfRange(raw, offset, offset + length), hashSeed);
      } catch (IOException exception) {
        // 单槽损坏：视为空槽，其它槽位照常可用
        slots[i] = null;
      }
      offset += length;
    }
    return slots;
  }

  private static void writeInt(ByteArrayOutputStream out, int value) {
    out.write(value >>> 24 & 0xFF);
    out.write(value >>> 16 & 0xFF);
    out.write(value >>> 8 & 0xFF);
    out.write(value & 0xFF);
  }

  private static int readInt(byte[] raw, int offset) {
    return (raw[offset] & 0xFF) << 24
        | (raw[offset + 1] & 0xFF) << 16
        | (raw[offset + 2] & 0xFF) << 8
        | (raw[offset + 3] & 0xFF);
  }
}