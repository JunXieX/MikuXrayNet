package net.mikumc.mikuxraynet.cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * BufferedLinearV3 区域文件格式（移植自 MIT 许可的 linear 项目的 {@code BufferedLinearV3RegionFile}，
 * <b>只取格式与编解码</b>，不含其 Mixin / 平台耦合部分，也不使用 NMS 的 {@code ChunkPos}）。
 *
 * <p><b>文件布局（与参考实现一致）</b>
 * <pre>
 *   偏移 0     : 魔数 u64 = MAGIC（与参考实现相同）
 *   偏移 8     : 版本 u8 = 0x03
 *   偏移 9     : 压缩方案 u8（参考实现此处是 zstd 等级；本项目固定为 Deflate）
 *   偏移 10    : 校验种子 i32（默认 0x0721）
 *   偏移 14    : 16 × u64 bucket 偏移表（每个 bucket 覆盖 64 个区块，共 1024 个区块 = 32×32）
 *   偏移 142   : 数据区，每个 bucket 为 [u32 原始长度][u32 压缩长度][压缩数据]
 * </pre>
 * 未写入的 bucket 偏移为 0。bucket 原始内容为 64 个槽位依次排列的 {@code [i32 条目长度][条目字节]}，
 * 长度 {@code <= 0} 表示空槽。条目字节为
 * {@code [i32 负载长度][i64 区块代次][i64 写入时间][i32 配置指纹][i32 负载校验和][负载]}，
 * 校验和为 {@link XXHash32}（与格式头种子一致）。
 *
 * <p><b>与参考实现的差异（重要）</b>：参考实现用 zstd（zstd-jni）压缩 bucket，本项目<b>不引入第三方依赖</b>，
 * 改用 JDK 自带的 {@link Deflater}（BEST_SPEED 档，与参考实现的 zstd level 1 定位一致）。
 * 布局与校验算法保持一致，仅压缩块互不兼容，因此文件不可与参考实现互换；压缩方案记录在头部第 10 字节，
 * 版本保持 0x03 以便阅读同一份格式说明。
 */
public final class BufferedLinearV3Format {

  /** 与参考实现一致的文件魔数。 */
  public static final long MAGIC = 0xFFFFDFF7EDDAFD97L;

  /** 与参考实现一致的格式版本（bucket 化布局）。 */
  public static final byte VERSION = 0x03;

  /** 压缩方案：JDK Deflater（参考实现为 zstd，其第 10 字节存的是等级）。 */
  public static final byte COMPRESSION_DEFLATE = 0x01;

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

  /** 单条缓存条目：区块代次 + 写入时间 + 配置指纹 + 负载。 */
  public record Entry(long generation, long writtenAtMillis, int configHash, byte[] payload) {
  }

  private BufferedLinearV3Format() {
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

  /** 编码 14 字节文件头。 */
  public static byte[] encodeHeader(int hashSeed) {
    ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
    buffer.putLong(MAGIC);
    buffer.put(VERSION);
    buffer.put(COMPRESSION_DEFLATE);
    buffer.putInt(hashSeed);
    return buffer.array();
  }

  /**
   * 解析文件头。
   *
   * @return 文件头里的校验种子
   * @throws IOException 长度不足、魔数/版本/压缩方案不符
   */
  public static int decodeHeader(byte[] raw) throws IOException {
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
      throw new IOException("不支持的格式版本 " + version);
    }
    byte compression = buffer.get();
    if (compression != COMPRESSION_DEFLATE) {
      throw new IOException("不支持的压缩方案 " + compression);
    }
    return buffer.getInt();
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

  /** 压缩一段数据（Deflater.BEST_SPEED，对应参考实现的 zstd level 1）。 */
  public static byte[] compress(byte[] raw) {
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

  /**
   * 已知原始长度时解压。
   *
   * @throws IOException 数据截断、损坏或解压长度与声明不符
   */
  public static byte[] decompress(byte[] compressed, int rawLength) throws IOException {
    if (rawLength < 0 || rawLength > MAX_RAW_SIZE) {
      throw new IOException("解压长度非法：" + rawLength);
    }
    if (rawLength == 0) {
      return new byte[0];
    }

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