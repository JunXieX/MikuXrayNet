package net.mikumc.mikuxraynet.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.mikumc.mikuxraynet.cache.BufferedLinearV3Format;

/**
 * <b>一次性工具（真机取证用，非回归测试、无 main 之外的入口，不会被 CI 执行）</b>。
 *
 * <p><b>用途</b>：把真机磁盘缓存（{@code plugins/MikuXrayNet/cache/<世界>/r.x.z.b_linear}）里的
 * 真实缓存条目解出来，挑一条尺寸中等的负载写入 {@code src/test/resources/real-chunk-payload.bin}，
 * 供 {@link RealChunkPayloadRegressionTest} 做离线实证。
 *
 * <p><b>为什么需要它</b>：真机上「反矿透失效但自检全绿」，唯一能拿到的实物证据就是这份缓存负载——
 * 它就是「我们算完准备发给客户端的字节（含伪装坐标清单）」。只有拿真实的字节去解开，才能区分
 * 「一个方块都没匹配到」与「算了但没写进 NMS 对象」。
 *
 * <p><b>条目负载（磁盘缓存的 payload）布局</b>（见 {@code ProtocolLibAsyncListener#encodeDiskPayload}）：
 * {@code [i64 原始字节指纹][i32 伪装坐标数][i32 × N 伪装坐标][区块 section 原始字节]}。
 * 本工具把「整条负载（含上面的信封）」原样写出，测试据此即可读出「伪装坐标数」这一决定性指标。
 *
 * <p><b>运行方式</b>（离线，不依赖网络）：
 * <pre>
 *   mvn -o -q test-compile
 *   java -cp "target/classes;target/test-classes" net.mikumc.mikuxraynet.tools.RealCachePayloadExtractor
 * </pre>
 * 可选参数：{@code [缓存目录] [输出文件]}。
 */
public final class RealCachePayloadExtractor {

  /** 真机缓存目录（Leaf 26.2 / 插件 1.0.2）。 */
  private static final String DEFAULT_CACHE_DIR = "E:\\Server\\test\\plugins\\MikuXrayNet\\cache\\world";

  /** 输出资源（相对当前工作目录 = 项目根）。 */
  private static final String DEFAULT_OUTPUT = "src\\test\\resources\\real-chunk-payload.bin";

  private RealCachePayloadExtractor() {
  }

  /** 一条已解析的缓存条目。 */
  private record Candidate(String regionFile, int bucket, int slot, int chunkIndex, long sourceHash,
      int positionCount, byte[] payload) {
  }

  public static void main(String[] args) throws IOException {
    Path cacheDir = Path.of(args.length > 0 ? args[0] : DEFAULT_CACHE_DIR);
    Path output = Path.of(args.length > 1 ? args[1] : DEFAULT_OUTPUT);
    if (!Files.isDirectory(cacheDir)) {
      System.out.println("[提取] 缓存目录不存在：" + cacheDir.toAbsolutePath());
      return;
    }

    List<Path> regionFiles = new ArrayList<>();
    try (var stream = Files.list(cacheDir)) {
      stream.filter(path -> path.getFileName().toString().endsWith(".b_linear"))
          .sorted()
          .forEach(regionFiles::add);
    }
    System.out.println("[提取] 缓存目录：" + cacheDir.toAbsolutePath());
    System.out.println("[提取] 区域文件数：" + regionFiles.size());

    List<Candidate> all = new ArrayList<>();
    for (Path regionFile : regionFiles) {
      System.out.println("\n[提取] ---- " + regionFile.getFileName() + "（" + Files.size(regionFile) + " 字节）----");
      all.addAll(readRegionFile(regionFile));
    }

    if (all.isEmpty()) {
      System.out.println("\n[提取] 结论：缓存里没有任何可解析条目（可能全为空桶）。");
      return;
    }

    // 逐条打印证据：伪装坐标数是区分「没匹配到目标」与「算了但没生效」的第一手指标
    Map<Integer, Integer> positionCountHistogram = new HashMap<>();
    for (Candidate candidate : all) {
      positionCountHistogram.merge(candidate.positionCount(), 1, Integer::sum);
    }
    System.out.println("\n[提取] 条目总数：" + all.size());
    System.out.println("[提取] 伪装坐标数分布（数量 → 条目数）：");
    positionCountHistogram.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> System.out.println("         " + entry.getKey() + " → " + entry.getValue()));
    int zero = positionCountHistogram.getOrDefault(0, 0);
    System.out.println("[提取] 伪装坐标数 = 0 的条目：" + zero + " / " + all.size()
        + "（=0 表示『一个方块都没被替换』，>0 表示『替换算出来了』）");

    // 尺寸中等的条目（剔除最大与最小，避免挑到极端样本）
    List<Candidate> bySize = new ArrayList<>(all);
    bySize.sort(Comparator.comparingInt(candidate -> candidate.payload().length));
    Candidate chosen = bySize.get(bySize.size() / 2);
    Candidate smallest = bySize.get(0);
    Candidate largest = bySize.get(bySize.size() - 1);

    System.out.println("\n[提取] 尺寸区间：最小 " + smallest.payload().length + " 字节，最大 "
        + largest.payload().length + " 字节");
    System.out.println("[提取] 选中（中位）：" + chosen.regionFile() + " bucket=" + chosen.bucket()
        + " slot=" + chosen.slot() + " chunkIndex=" + chosen.chunkIndex()
        + " 负载 " + chosen.payload().length + " 字节"
        + "｜伪装坐标数=" + chosen.positionCount()
        + "｜原始字节指纹=" + Long.toHexString(chosen.sourceHash())
        + "｜区块字节=" + (chosen.payload().length - 12 - chosen.positionCount() * 4) + " 字节");

    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.write(output, chosen.payload());
    System.out.println("[提取] 已写出：" + output.toAbsolutePath());

    System.out.println("\n[提取] 负载前 64 字节（十六进制）：");
    System.out.println("         " + hex(chosen.payload(), 64));
  }

  /** 解析一个区域文件，返回其中全部可解析条目。 */
  private static List<Candidate> readRegionFile(Path regionFile) {
    List<Candidate> candidates = new ArrayList<>();
    byte[] raw;
    try {
      raw = Files.readAllBytes(regionFile);
    } catch (IOException exception) {
      System.out.println("[提取] 读取失败：" + exception.getMessage());
      return candidates;
    }

    int hashSeed;
    byte compression;
    try {
      BufferedLinearV3Format.Header header = BufferedLinearV3Format.decodeHeaderInfo(raw);
      hashSeed = header.hashSeed();
      compression = header.compression();
    } catch (IOException exception) {
      System.out.println("[提取] ★ 无法按本项目格式解析该区域文件：" + exception.getMessage());
      System.out.println("[提取] ★ 前 64 字节：" + hex(raw, 64));
      return candidates;
    }
    System.out.println("[提取] 文件头 OK：校验种子=0x" + Integer.toHexString(hashSeed)
        + "，压缩方案=0x" + Integer.toHexString(compression & 0xFF)
        + "，文件长度=" + raw.length);

    long[] offsets;
    try {
      // decodePosTable 期望「刚好是偏移表本身」的切片（见 RegionFile.loadIndex 的用法）
      byte[] table = new byte[BufferedLinearV3Format.POS_TABLE_SIZE];
      System.arraycopy(raw, BufferedLinearV3Format.POS_TABLE_OFFSET, table, 0, table.length);
      offsets = BufferedLinearV3Format.decodePosTable(table);
    } catch (IOException | RuntimeException exception) {
      System.out.println("[提取] ★ 偏移表损坏：" + exception.getMessage());
      return candidates;
    }
    int usedBuckets = 0;
    for (long offset : offsets) {
      if (offset >= BufferedLinearV3Format.DATA_AREA_OFFSET) {
        usedBuckets++;
      }
    }
    System.out.println("[提取] 偏移表 OK：非空 bucket 数=" + usedBuckets + "/" + offsets.length);

    for (int bucket = 0; bucket < offsets.length; bucket++) {
      long offset = offsets[bucket];
      if (offset < BufferedLinearV3Format.DATA_AREA_OFFSET || offset + 8L > raw.length) {
        continue;
      }
      try {
        ByteBuffer head = ByteBuffer.wrap(raw, (int) offset, 8);
        int rawLength = head.getInt();
        int compressedLength = head.getInt();
        if (rawLength <= 0 || compressedLength <= 0 || offset + 8L + compressedLength > raw.length) {
          continue;
        }
        byte[] compressed = new byte[compressedLength];
        System.arraycopy(raw, (int) offset + 8, compressed, 0, compressedLength);
        byte[] bucketRaw = BufferedLinearV3Format.decompress(compressed, rawLength, compression);
        BufferedLinearV3Format.Entry[] slots = BufferedLinearV3Format.decodeBucket(bucketRaw, hashSeed);
        for (int slot = 0; slot < slots.length; slot++) {
          BufferedLinearV3Format.Entry entry = slots[slot];
          if (entry == null) {
            continue;
          }
          Candidate candidate = parsePayload(regionFile.getFileName().toString(), bucket, slot,
              bucket << BufferedLinearV3Format.BUCKET_SHIFT | slot, entry);
          if (candidate != null) {
            candidates.add(candidate);
          }
        }
      } catch (IOException | RuntimeException exception) {
        System.out.println("[提取] bucket " + bucket + " 解析失败（跳过）：" + exception);
      }
    }
    System.out.println("[提取] 解析条目数：" + candidates.size());
    return candidates;
  }

  /** 解析条目负载的信封：{@code [i64 指纹][i32 坐标数][i32… 坐标][区块字节]}。 */
  private static Candidate parsePayload(String regionFile, int bucket, int slot, int chunkIndex,
      BufferedLinearV3Format.Entry entry) {
    byte[] payload = entry.payload();
    if (payload == null || payload.length < 12) {
      System.out.println("[提取] 条目 " + regionFile + " bucket=" + bucket + " slot=" + slot
          + " 长度不足 12 字节（" + (payload == null ? 0 : payload.length) + "），跳过");
      return null;
    }
    try {
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      long sourceHash = buffer.getLong();
      int count = buffer.getInt();
      if (count < 0 || count > buffer.remaining() / Integer.BYTES) {
        System.out.println("[提取] 条目 " + regionFile + " bucket=" + bucket + " slot=" + slot
            + " 坐标数非法（" + count + "），跳过");
        return null;
      }
      buffer.position(buffer.position() + count * Integer.BYTES);
      int dataLength = buffer.remaining();
      if (dataLength <= 0) {
        System.out.println("[提取] 条目 " + regionFile + " bucket=" + bucket + " slot=" + slot
            + " 区块字节为空，跳过");
        return null;
      }
      return new Candidate(regionFile, bucket, slot, chunkIndex, sourceHash, count, payload);
    } catch (RuntimeException exception) {
      System.out.println("[提取] 条目 " + regionFile + " bucket=" + bucket + " slot=" + slot
          + " 结构异常：" + exception);
      return null;
    }
  }

  /** 前 {@code limit} 字节的十六进制（不足则全部）。 */
  private static String hex(byte[] data, int limit) {
    int length = Math.min(limit, data.length);
    StringBuilder builder = new StringBuilder(length * 2);
    for (int index = 0; index < length; index++) {
      builder.append(String.format(Locale.ROOT, "%02X", data[index]));
    }
    return builder.toString();
  }
}