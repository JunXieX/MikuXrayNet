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
 * <p><b>压缩方案（偏移 9 的那个字节）</b>：该字节在文件<b>创建时</b>确定，并决定整个文件里所有 bucket
 * 的压缩算法；此后<b>追加入写必须沿用该字节</b>（调用 {@link #compress(byte[], byte)} 传入它，
 * 而不是重新取全局 {@link #currentCompression()}）—— 否则会出现「头部声明 0x01(Deflate)、
 * 桶却按 zstd 写」的错配，读回时按 0x01 走 Inflater 解压失败、整个 bucket 被当作空，
 * 刚写入的条目静默丢失。新文件创建时取 {@link #currentCompression()}：zstd 可用取 <b>zstd</b>
 * （{@code 0x02}，服务端自带的 zstd-jni，{@code scope=provided}，不 shade、不进插件 jar；
 * 服务端若没有则由 {@link ZstdSupport} 在启动期自动下载到 {@code plugins/MikuXrayNet/lib}），
 * 否则取 Deflate（{@code 0x01}）。历史文件里的 Deflate 仍被完整支持，因此老的
 * {@code .b_linear} 缓存不会被当成损坏文件删除。读路径按该字节选择解压器：{@code 0x01} 走 JDK
 * {@link Inflater}，{@code 0x02} 走 zstd。
 *
 * <p><b>fail-open</b>：运行期若 zstd 不可用（服务端没有该库、自动下载失败、或原生库缺失导致类初始化抛
 * {@link Throwable} 等），新文件的 {@link #currentCompression()} 返回 {@code 0x01}（回退 JDK
 * {@link Deflater}）。对<b>既有</b>文件：若其声明的方案在本进程不可用（头=zstd 但 zstd 库缺失），
 * 由 {@code RegionFile#open} 整文件迁移为可行方案（见其注释），保证「读回一定能解开」。
 * 任何压缩/解压异常都只记日志并降级，绝不影响封包链路。
 */
public final class BufferedLinearV3Format {

  /** 与格式头一致的日志通道（本类为纯格式工具，不依赖外部注入的 Logger）。 */
  private static final Logger LOGGER = Logger.getLogger("MikuXrayNet");

  /** 仅提示一次的「zstd 回退 Deflater」日志开关（运行期压缩/解压抛异常的路径）。 */
  private static final AtomicBoolean ZSTD_FALLBACK_WARNED = new AtomicBoolean();

  /** 仅提示一次的「旧版（0x03）区域文件已被拒绝、将重建」日志开关。 */
  private static final AtomicBoolean LEGACY_VERSION_WARNED = new AtomicBoolean();

  /** 仅提示一次的「文件声明方案在本进程不可用、已整文件迁移」日志开关。 */
  private static final AtomicBoolean SCHEME_DOWNGRADE_WARNED = new AtomicBoolean();

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

  /** 文件魔数。 */
  public static final long MAGIC = 0xFFFFDFF7EDDAFD97L;

  /** 格式版本（bucket 化布局 + 负载信封携带伪装坐标）。 */
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

  /** 默认校验种子。 */
  public static final int DEFAULT_HASH_SEED = 0x0721;

  /** 单个条目负载的上限（防止损坏数据造成超大分配）：16 MiB。 */
  public static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

  /**
   * 单个 bucket 解压后原始数据的上限（防御损坏数据造成超大分配）：128 MiB。
   *
   * <p><b>为什么从 512 MiB 收紧到 128 MiB</b>：桶头里的 {@code rawLength} 直接来自（可能损坏的）文件，
   * {@link #inflate} 会先 {@code new byte[rawLength]} 再解压——旧上限下「大 rawLength + 极小
   * compressedLength」的损坏头可以让小堆服务端先发生数百 MiB 的分配、异常才被兜住（分配已经发生）。
   * 依据：真实区块负载受 {@link #MAX_PAYLOAD_SIZE}（16 MiB）与区块实际编码规模双重约束——Paper/原版
   * 一个区块的原始网络字节通常几十 KiB、最坏（24 段 × 15 位直接调色板 + 调色板/群系开销，384 高世界）
   * 也仅约 200 KiB；一个桶最多 64 个区块，实际远小于 13 MiB。128 MiB 对任一现实负载都有 &gt;10 倍余量，
   * 同时把单次分配的防护上限压到可控范围。理论上限 64 × (4 + 28 + 16 MiB) ≈ 1024 MiB 只在本进程
   * 主动写入 16 MiB 级负载时才可能触及，而 {@code DiskCacheStore} 的单文件上限与负载上限已先行拦截。
   */
  public static final int MAX_RAW_SIZE = 128 * 1024 * 1024;

  /**
   * 解压前的「压缩比 sanity」上限：声明原始长度超过压缩长度 {@code 8192} 倍即判损坏。
   *
   * <p><b>为什么需要</b>：损坏/伪造的桶头可以声明一个很大的 {@code rawLength} 却只带几字节压缩数据，
   * 单靠 {@link #MAX_RAW_SIZE} 仍会让 {@code inflate} 先分配 128 MiB 才失败。此检查在任何分配之前
   * 完成，直接把这类头拒之门外（与 {@link #MAX_RAW_SIZE} 一起构成两道闸）。
   *
   * <p><b>阈值为什么取 8192，且只对 &gt; {@link #COMPRESSION_RATIO_MIN_RAW} 的声明生效</b>：桶内每个
   * 条目都带 8 字节「原始字节指纹」（{@code DiskPayload}），指纹近似随机、不可压缩，因此一个桶的压缩
   * 长度至少有「条目数 × 8 字节」的熵下限；再叠加坐标 int 的熵与区块字节的结构，真实负载的压缩比远
   * 达不到 8192。反过来，只对 &gt; 4 MiB 的声明校验，可完全避开「小桶（如全空区块）压缩比天然很高」
   * 的误判（小数据即使压缩比异常也不构成 OOM 风险）。据此取值，正常大区块（含高建筑高度）不会被误判。
   */
  static final long MAX_COMPRESSION_RATIO = 8192L;

  /** 触发 {@link #MAX_COMPRESSION_RATIO} 校验所需的最小声明原始长度（4 MiB；见其说明）。 */
  private static final int COMPRESSION_RATIO_MIN_RAW = 4 << 20;

  /**
   * 单条缓存条目：区块代次（<b>已废弃，恒为 0，仅为格式兼容保留</b>）+ 写入时间 + 配置指纹 + 负载。
   *
   * <p>代次字段曾是「该区块内容已变」的判据，但它只在进程内有效（重启归零），反而让上一进程写下的
   * 条目在重启后被逐条误判失效；内容新鲜度现由负载里的原始字节指纹判定（见 {@code DiskCacheStore}）。
   * 字段继续保留是为了让文件布局与历史版本一致（读写都走同一偏移），不再承载任何语义。
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
   * 文件声明的压缩方案在本进程不可用、整文件迁移为可行方案时的唯一一次中文提示（并发安全）。
   *
   * <p>例：头=0x02(zstd) 但本进程没有 zstd-jni。此时该文件既有的 zstd bucket 本就解不开
   * （读路径必抛异常、整桶当空），因此无从保留；迁移只是把「声明」修正为可行方案并重建索引。
   */
  static void warnSchemeDowngradeOnce(byte declared, byte used) {
    if (SCHEME_DOWNGRADE_WARNED.compareAndSet(false, true)) {
      LOGGER.warning("区域缓存文件声明的压缩方案 0x" + Integer.toHexString(declared & 0xFF)
          + " 在本进程不可用，已整文件迁移为可行方案 0x" + Integer.toHexString(used & 0xFF)
          + "：按原方案写入的旧 bucket 本就无法解压，迁移后头部与后续写入保持一致、读回一定能解开"
          + "（缓存只是可选加速，不影响封包链路）");
    }
  }

  /**
   * 当前写入使用的压缩方案字节：zstd 可用时为 {@link #COMPRESSION_ZSTD}，否则 {@link #COMPRESSION_DEFLATE}。
   *
   * <p><b>只代表「新文件的方案」</b>：新文件创建时由 {@link #encodeHeader(int)} 写入此值。既有文件一旦创建，
   * 其头部方案字节便固定；此后写入一律走 {@link #encodeHeader(int, byte)} / {@link #compress(byte[], byte)}
   * 并传入该文件声明的方案，不得再取此值 —— 否则会造成「头部方案」与「bucket 实际算法」错配（见类注释）。
   */
  public static byte currentCompression() {
    return ZstdSupport.available() ? COMPRESSION_ZSTD : COMPRESSION_DEFLATE;
  }

  /**
   * 给定压缩方案在本进程是否可用：Deflate 由 JDK 提供、恒可用；zstd 取决于 zstd-jni 是否就绪。
   *
   * <p>{@code RegionFile#open} 用它判定「文件声明方案能否在本进程读/写」，
   * 不可用则整文件迁移为可行方案（见 {@link #currentCompression()}）。
   */
  public static boolean compressionAvailable(byte compression) {
    return compression != COMPRESSION_ZSTD || ZstdSupport.available();
  }

  // ------------------------------------------------------------------ 坐标换算

  /** 区块坐标 → region 内槽位序号（0..1023）；x 低位、z 高位。 */
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

  /** 编码 14 字节文件头（压缩方案字节取 {@link #currentCompression()}，即「新文件」方案）。 */
  public static byte[] encodeHeader(int hashSeed) {
    return encodeHeader(hashSeed, currentCompression());
  }

  /**
   * 编码 14 字节文件头（压缩方案字节<b>显式指定</b>）。
   *
   * <p>改写既有文件头（压缩回收 / 迁移）时必须传该文件声明的方案，保证「头部」与「bucket 数据」一致；
   * 只有创建新文件时才用 {@link #encodeHeader(int)}（自动取 {@link #currentCompression()}）。
   */
  public static byte[] encodeHeader(int hashSeed, byte compression) {
    ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
    buffer.putLong(MAGIC);
    buffer.put(VERSION);
    buffer.put(compression);
    buffer.putInt(hashSeed);
    return buffer.array();
  }

  /**
   * 解析文件头，返回校验种子（兼容 0x01/0x02 两种压缩方案）；等价于 {@link #decodeHeaderInfo(byte[])} 的种子。
   *
   * <p>测试专用豁免：生产路径只用 {@link #decodeHeaderInfo(byte[])}（还需压缩方案字节），
   * 本方法当前仅单测在用，保留以免破坏测试。
   */
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
   *
   * <p><b>测试与「无固定头部」场景专用</b>：生产写入路径（{@code RegionFile}）一律走
   * {@link #compress(byte[], byte)} 并传入文件声明的方案，绝不能用本方法 —— 否则会写出与头部不符的
   * 数据，读回时整桶丢失。
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

  /**
   * 按<b>指定方案</b>压缩；写入侧必须传「文件头声明的方案」，绝不能用全局 {@link #currentCompression()}。
   *
   * @param compression {@link #COMPRESSION_ZSTD} 或 {@link #COMPRESSION_DEFLATE}
   * @throws IOException 声明 zstd 但本进程无 zstd（zstd-jni 不可用），或 zstd 运行期异常。
   *         <b>刻意抛出而非回退 Deflate</b>：回退会写出与头部方案不符的数据，读回时整桶丢失；
   *         抛出让调用方 fail-open（保留脏标记、下轮重试），绝不产生静默损坏。
   */
  public static byte[] compress(byte[] raw, byte compression) throws IOException {
    if (compression == COMPRESSION_ZSTD) {
      if (!ZstdSupport.available()) {
        throw new IOException("文件声明使用 zstd，但当前环境无 zstd（zstd-jni 不可用）");
      }
      try {
        return ZstdSupport.compress(raw, ZSTD_LEVEL);
      } catch (Throwable throwable) {
        throw new IOException("zstd 压缩失败", throwable);
      }
    }
    return deflate(raw);
  }

  /**
   * 按线程复用的 Deflater：避免「每个桶新建一个 Deflater」。
   *
   * <p>Deflater 构造会分配原生内存与内部状态，而 {@link #deflate} 在历史 Deflate 文件写入 / zstd 不可用时
   * 会被每个桶反复调用（一个区域文件最多 16 个桶，且会被反复重压缩）。复用同一实例并每次 {@code reset()}
   * 即可消除这部分分配，输出字节与每次新建完全相同（Deflate 是确定性算法）。
   *
   * <p><b>为什么不 {@code end()}</b>：{@code end()} 之后该 Deflater 不可再用；按线程复用的实例刻意不释放，
   * 让其原生内存随线程存活（使用它的只有磁盘线程等少数长驻线程，数量有界），换取热路径零分配。
   */
  private static final ThreadLocal<Deflater> REUSABLE_DEFLATER =
      ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));

  /** JDK Deflater（BEST_SPEED）压缩：历史格式的写入路径，也是 zstd 不可用时的回退路径。 */
  private static byte[] deflate(byte[] raw) {
    Deflater deflater = REUSABLE_DEFLATER.get();
    deflater.reset();
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
  }

  /**
   * 已知原始长度时解压；按 {@link #currentCompression()} 选择解压器。
   *
   * <p><b>测试专用豁免</b>：生产读取路径一律走 {@link #decompress(byte[], int, byte)} 并传「文件头声明的
   * 方案」（{@code RegionFile}），绝不假定文件方案等于全局当前方案。
   */
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
    // 压缩比 sanity：必须在任何分配之前完成（inflate 会先 new byte[rawLength] 才解压）。
    // 声明长度 > 4 MiB 且压缩长度不足其 1/8192 时，只可能来自损坏/伪造的桶头——真实区块负载的
    // 压缩比远达不到该量级（见常量注释），故直接判损坏，交由调用方 fail-open（该桶当空）。
    if (rawLength > COMPRESSION_RATIO_MIN_RAW
        && (long) compressed.length * MAX_COMPRESSION_RATIO < rawLength) {
      throw new IOException("压缩比异常（声明 " + rawLength + " 字节，压缩数据仅 "
          + compressed.length + " 字节），已按损坏处理");
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
  static final int ENTRY_HEADER_SIZE = 4 + 8 + 8 + 4 + 4;

  /** 单条非空条目的编码长度：长度前缀 {@link Integer#BYTES} + 条目头 {@link #ENTRY_HEADER_SIZE} + 负载。 */
  static int entryEncodedLength(int payloadLength) {
    return Integer.BYTES + ENTRY_HEADER_SIZE + payloadLength;
  }

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
   * <p>测试专用豁免：生产端一律走「按区间解析」的重载（bucket 内联解析、不为每个槽位复制数组），
   * 本重载当前仅单测在用，保留以免破坏测试。
   *
   * @throws IOException 截断、长度非法或校验失败
   */
  public static Entry decodeEntry(byte[] raw, int hashSeed) throws IOException {
    if (raw == null) {
      throw new IOException("条目截断（0 字节）");
    }
    return decodeEntry(raw, 0, raw.length, hashSeed);
  }

  /**
   * 从缓冲区区间 {@code [offset, offset + length)} 解析一条条目（<b>不</b>复制数组）。
   *
   * <p>与 2 参重载语义完全一致，只是把「先 {@code Arrays.copyOfRange} 再解析」合并成原地解析：
   * 一个 bucket 最多 64 个槽位，原先每次载入桶都要多出最多 64 次数组复制与对应的垃圾。
   *
   * @throws IOException 截断、长度非法或校验失败
   */
  static Entry decodeEntry(byte[] raw, int offset, int length, int hashSeed) throws IOException {
    if (raw == null || offset < 0 || length < ENTRY_HEADER_SIZE || offset > raw.length - length) {
      throw new IOException("条目截断（" + Math.max(0, length) + " 字节）");
    }
    ByteBuffer buffer = ByteBuffer.wrap(raw, offset, length);
    int payloadLength = buffer.getInt();
    if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE
        || payloadLength > length - ENTRY_HEADER_SIZE) {
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
    // 容量已按内容精确算好（见 encodedBucketLength），直接把每条条目写进结果数组——不再走
    // ByteArrayOutputStream + toByteArray() 的「先写进中间缓冲、再整块复制一份」双重拷贝，也不再为每个
    // 条目单独分配一个编码临时数组（桶是热路径：一个区域文件最多 16 个桶会被反复重编码）。
    // 写出字节与 encodeEntry 完全一致。
    byte[] out = new byte[encodedBucketLength(slots)];
    int offset = 0;
    for (int i = 0; i < BUCKET_SIZE; i++) {
      Entry entry = slots != null && i < slots.length ? slots[i] : null;
      if (entry == null || entry.payload() == null || entry.payload().length == 0) {
        offset = writeInt(out, offset, 0);
        continue;
      }
      byte[] payload = entry.payload();
      offset = writeInt(out, offset, ENTRY_HEADER_SIZE + payload.length);
      offset = writeInt(out, offset, payload.length);
      offset = writeLong(out, offset, entry.generation());
      offset = writeLong(out, offset, entry.writtenAtMillis());
      offset = writeInt(out, offset, entry.configHash());
      offset = writeInt(out, offset, XXHash32.hash(payload, hashSeed));
      System.arraycopy(payload, 0, out, offset, payload.length);
      offset += payload.length;
    }
    return out;
  }

  /**
   * 一个 bucket 编码后的字节长度（与 {@link #encodeBucket} 的定容公式逐项一致，但<b>不实际编码</b>）。
   *
   * <p>公式：每个空槽 = 1 个长度为 0 的 i32 前缀（4 字节）；每个非空槽 =
   * 4（长度前缀）+ {@link #ENTRY_HEADER_SIZE}（条目头，含校验和）+ 负载长度。
   *
   * <p>供 {@code DiskCacheStore} 做「单文件大小上限」的待落盘记账：flush 追加的是<b>整块 bucket</b>，
   * 必须按整桶长度而非单条 payload 估算，否则文件会冲破上限而判定不触发（见该类 Handle 的记账说明）。
   */
  static int encodedBucketLength(Entry[] slots) {
    int capacity = 0;
    for (int i = 0; i < BUCKET_SIZE; i++) {
      Entry slot = slots != null && i < slots.length ? slots[i] : null;
      if (slot == null || slot.payload() == null || slot.payload().length == 0) {
        capacity += Integer.BYTES;
      } else {
        capacity += entryEncodedLength(slot.payload().length);
      }
    }
    return capacity;
  }

  /**
   * 只数条数、不解码：按「每槽位一个 int 长度前缀」扫一遍，返回长度大于 0 的槽位数。
   *
   * <p>遍历规则与 {@link #decodeBucket(byte[], int)} 一致（长度 ≤ 0 视为空槽、越界即停止），
   * 但既不复制也不校验、更不分配负载数组——供启动期统计磁盘既有条目数使用（避免为一个计数
   * 把整份区域文件的桶全部解码进内存）。
   */
  public static int countBucketEntries(byte[] raw) {
    if (raw == null) {
      return 0;
    }
    int count = 0;
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
        // 截断：这里不抛异常（与 decodeBucket 的差异），直接停止计数——调用方只损失计数精度
        break;
      }
      count++;
      offset += length;
    }
    return count;
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
        slots[i] = decodeEntry(raw, offset, length, hashSeed);
      } catch (IOException exception) {
        // 单槽损坏：视为空槽，其它槽位照常可用
        slots[i] = null;
      }
      offset += length;
    }
    return slots;
  }

  /** 把一个大端 i32 写入 {@code out[offset..]}，返回写入后的新偏移（与 {@link #readInt} 同字节序）。 */
  private static int writeInt(byte[] out, int offset, int value) {
    out[offset] = (byte) (value >>> 24);
    out[offset + 1] = (byte) (value >>> 16);
    out[offset + 2] = (byte) (value >>> 8);
    out[offset + 3] = (byte) value;
    return offset + 4;
  }

  /** 把一个大端 i64 写入 {@code out[offset..]}，返回写入后的新偏移。 */
  private static int writeLong(byte[] out, int offset, long value) {
    out[offset] = (byte) (value >>> 56);
    out[offset + 1] = (byte) (value >>> 48);
    out[offset + 2] = (byte) (value >>> 40);
    out[offset + 3] = (byte) (value >>> 32);
    out[offset + 4] = (byte) (value >>> 24);
    out[offset + 5] = (byte) (value >>> 16);
    out[offset + 6] = (byte) (value >>> 8);
    out[offset + 7] = (byte) value;
    return offset + 8;
  }

  private static int readInt(byte[] raw, int offset) {
    return (raw[offset] & 0xFF) << 24
        | (raw[offset + 1] & 0xFF) << 16
        | (raw[offset + 2] & 0xFF) << 8
        | (raw[offset + 3] & 0xFF);
  }
}