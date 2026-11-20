package net.mikumc.mikuxraynet.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Spark 采样文件的<b>定位与严格识别</b>：只负责「在给定目录里找到属于本次采样的那一个 profile」，
 * 不联网、不依赖 Spark 的类、不读其它路径。
 *
 * <p><b>为什么不用文件名/时间戳判断归属</b>：Spark 的文件名只精确到秒（{@code profile-yyyy-MM-dd_HH.mm.ss.sparkprofile}），
 * 同一秒内两次采样、或同一台服务器上别人的采样，文件名完全可能一样或相邻。唯一可靠的相关性是
 * Spark 写进 protobuf 元数据的 {@code metadata.comment}（本次开始时我们塞进去的随机标记），
 * 所以归属判定只认「解析出的 comment 与本次标记<b>逐字节相等</b>」，不做前缀、包含、大小写折叠等模糊匹配。
 *
 * <p><b>解析为什么这么小</b>：只需要 Spark 采样文件（{@code spark.SamplerData}）的头两层——
 * 顶层 {@code metadata = field 1, wire type 2}，其内部 {@code comment = field 6, wire type 2}
 * （见 spark 仓库 {@code spark-common/src/main/proto/spark/spark_sampler.proto}）。因此只读文件前
 * {@link #MAX_HEADER_BYTES} 字节，按 protobuf 线格式逐字段推进；字段顺序在 protobuf 编码里按字段号升序，
 * comment（6）排在 platform_metadata（7）之前，必然落在前缀内。任何长度越界、线类型非法（组 3/4）、
 * 声明长度大于真实文件长度，一律判为「不是本文件」并放弃——宁可漏认，也不把别人的数据当成自己的。
 *
 * <p><b>不碰符号链接</b>：目录本身与每个候选条目都做 {@code NOFOLLOW_LINKS} 判定，并要求
 * {@code toRealPath()} 仍落在该目录内；打不开、读一半、是目录、是链接、超大，全部跳过。
 *
 * <p>包内可见：只有 {@link SparkDumpCapture} 使用，不对外暴露。
 */
final class SparkProfileFiles {

  /** Spark 采样文件名前缀（{@code resolveSaveFile("profile", "sparkprofile")} 的两段）。 */
  private static final String PREFIX = "profile-";

  /** Spark 采样文件扩展名。 */
  private static final String SUFFIX = ".sparkprofile";

  /** 文件名长度上限：真实名字约 39 字符，留富余但拒绝异常长名。 */
  private static final int MAX_FILE_NAME = 96;

  /** 只读头部这么多字节用于解析元数据；comment 位于元数据前段，64 KiB 足够且严格有界。 */
  static final int MAX_HEADER_BYTES = 64 * 1024;

  /** 允许解析/上传的采样文件上限：30 秒 CPU 采样的真实体积通常在数百 KB 量级。 */
  static final long MAX_PROFILE_BYTES = 32L * 1024 * 1024;

  /** 一次扫描最多看多少个目录条目（防止把超量目录全枚举一遍）。 */
  static final int MAX_SCAN_ENTRIES = 512;

  /** comment 字段允许的最大字节数（我们自己的标记只有 38 字节，留足别人注释的余量）。 */
  private static final int MAX_COMMENT_BYTES = 512;

  /** 标记前缀：一眼能看出是本插件写进 Spark comment 的。 */
  private static final String MARKER_PREFIX = "mxnet-";

  /** 标记的随机部分：16 字节 = 128 位，跨会话撞车概率可忽略。 */
  private static final int MARKER_RANDOM_BYTES = 16;

  /** 标记合法形态（命令参数里不能出现空格，故限制为字母数字与短横线）。 */
  private static final Pattern MARKER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{7,63}");

  /** 文件名合法形态：只接受保守字符集，避免把同目录里无关文件当候选去读。 */
  private static final Pattern FILE_NAME =
      Pattern.compile(PREFIX + "[A-Za-z0-9._-]{1," + MAX_FILE_NAME + "}\\." + SUFFIX.substring(1));

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static final SecureRandom RANDOM = new SecureRandom();

  /** protobuf：SamplerData.metadata 的字段号。 */
  private static final int FIELD_METADATA = 1;

  /** protobuf：SamplerMetadata.comment 的字段号。 */
  private static final int FIELD_COMMENT = 6;

  /** protobuf：SamplerMetadata.start_time 的字段号。 */
  private static final int FIELD_START_TIME = 2;

  private static final int WIRE_VARINT = 0;
  private static final int WIRE_FIXED64 = 1;
  private static final int WIRE_LENGTH = 2;
  private static final int WIRE_FIXED32 = 5;

  private SparkProfileFiles() {
  }

  /**
   * 解析出的元数据片段。
   *
   * @param comment         {@code metadata.comment}；文件中没有该字段时为空串（与「comment 为空」等价）
   * @param startTimeMillis Spark 的采样开始时间（{@code TimeUtil.monotonicCurrentTimeMillis()}），仅供取证
   */
  record Header(String comment, long startTimeMillis) {

    boolean matches(String marker) {
      return marker != null && !marker.isEmpty() && marker.equals(this.comment);
    }
  }

  /** 生成本次采样的随机标记（{@code mxnet-} + 32 位十六进制）。 */
  static String newMarker() {
    byte[] random = new byte[MARKER_RANDOM_BYTES];
    RANDOM.nextBytes(random);
    StringBuilder builder = new StringBuilder(MARKER_PREFIX.length() + MARKER_RANDOM_BYTES * 2);
    builder.append(MARKER_PREFIX);
    for (byte value : random) {
      builder.append(HEX[(value >> 4) & 0xF]).append(HEX[value & 0xF]);
    }
    return builder.toString();
  }

  /** 标记是否可用于命令参数与匹配（只接受保守字符集，避免命令行分词与日志注入）。 */
  static boolean isMarker(String value) {
    return value != null && MARKER.matcher(value).matches();
  }

  /** 文件名是否是 Spark 的 profiler 落盘文件。 */
  static boolean isProfileName(String name) {
    return name != null && name.length() <= MAX_FILE_NAME && FILE_NAME.matcher(name).matches();
  }

  /** 文件大小是否在接受范围内（扫描与上传共用同一口径）。 */
  static boolean acceptableSize(long size) {
    return size > 0L && size <= MAX_PROFILE_BYTES;
  }

  /**
   * 读取文件头部并解析出 {@code metadata.comment} / {@code metadata.start_time}。
   *
   * <p>任何异常、链接、非普通文件、空文件、超大文件、非法线格式都返回空 {@link Optional}：
   * 调用方据此判定「这不是我们能认领的文件」，绝不外抛。
   */
  static Optional<Header> readHeader(Path file) {
    if (file == null) {
      return Optional.empty();
    }
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      long size = Files.size(file);
      if (!acceptableSize(size)) {
        return Optional.empty();
      }
      return readHeader(file, size);
    } catch (IOException | RuntimeException unreadable) {
      return Optional.empty();
    }
  }

  /** 已确认是普通文件、且大小在界内的读取路径（避免重复 stat）。 */
  private static Optional<Header> readHeader(Path file, long size) {
    int headLength = (int) Math.min(size, MAX_HEADER_BYTES);
    byte[] head = new byte[headLength];
    int read = 0;
    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      while (read < headLength) {
        int count = input.read(head, read, headLength - read);
        if (count < 0) {
          break;
        }
        read += count;
      }
    } catch (IOException | RuntimeException unreadable) {
      return Optional.empty();
    }
    return parseHeader(head, read, size);
  }

  /**
   * 纯字节解析（可离线单测）：{@code data} 只含文件前 {@code headLength} 字节，{@code fileSize} 是真实文件大小。
   *
   * <p>结构约束：顶层第一个字段必须就是 {@code metadata}；元数据内部按字段号推进，遇到 comment 即取，
   * 越界/组/非法线类型一律放弃。
   */
  static Optional<Header> parseHeader(byte[] data, int headLength, long fileSize) {
    if (data == null || headLength <= 0 || fileSize <= 0L) {
      return Optional.empty();
    }
    int limit = Math.min(headLength, data.length);
    Cursor cursor = new Cursor(data, limit);
    if (cursor.readTag() != lengthDelimited(FIELD_METADATA)) {
      // 顶层第一个字段不是 metadata：不是 Spark 采样文件（或结构已变），放弃
      return Optional.empty();
    }
    long metadataLength = cursor.readVarint();
    if (metadataLength <= 0L) {
      return Optional.empty();
    }
    long metadataEnd = (long) cursor.position() + metadataLength;
    if (metadataEnd > fileSize) {
      // 声明的元数据长度超过真实文件：截断/伪造，放弃
      return Optional.empty();
    }
    int scanEnd = (int) Math.min(metadataEnd, limit);
    String comment = "";
    long startTime = 0L;
    while (cursor.position() < scanEnd) {
      int tag = cursor.readTag();
      if (tag < 0) {
        return Optional.empty();
      }
      int field = tag >>> 3;
      int wireType = tag & 0x7;
      switch (wireType) {
        case WIRE_VARINT -> {
          long value = cursor.readVarint();
          if (value < 0L) {
            return Optional.empty();
          }
          if (field == FIELD_START_TIME) {
            startTime = value;
          }
        }
        case WIRE_FIXED64 -> {
          if (!cursor.skipStrict(8)) {
            return Optional.empty();
          }
        }
        case WIRE_FIXED32 -> {
          if (!cursor.skipStrict(4)) {
            return Optional.empty();
          }
        }
        case WIRE_LENGTH -> {
          long length = cursor.readVarint();
          if (length < 0L || length > scanEnd - cursor.position()) {
            // 单个字段越过本段可读范围（截断或伪造）：放弃
            return Optional.empty();
          }
          if (field == FIELD_COMMENT && length <= MAX_COMMENT_BYTES) {
            comment = cursor.readString((int) length);
          } else if (!cursor.skipStrict((int) length)) {
            return Optional.empty();
          }
        }
        default -> {
          // 组（3/4）与保留线类型（6/7）不该出现在采样元数据里：放弃
          return Optional.empty();
        }
      }
    }
    return Optional.of(new Header(comment, startTime));
  }

  /** 候选条目：文件与它的修改时间（修改时间用于「同一文件只解析一次」的去重键）。 */
  record Candidate(Path path, long modifiedMillis) {
  }

  /**
   * 枚举目录里「可能属于某次采样」的候选文件（<b>不解析内容</b>）。
   *
   * <p>过滤全部是廉价的元数据判定：文件名、是否普通文件、是否软链接、大小范围、realpath 是否仍在目录内、
   * 修改时间是否不早于 {@code minModifiedMillis}。真正的归属判定留给 {@link #matches(Path, String)}，
   * 这样调用方可以只对新候选读一次头部，而不是每轮把整个目录重读一遍。
   *
   * @param directory          Spark 的采样目录（本身是软链接或不存在时返回空）
   * @param minModifiedMillis  只接受修改时间不早于该时刻的条目（{@link Long#MIN_VALUE} 表示不过滤）
   * @param maxCandidates      最多返回多少个候选（同时限制枚举的条目数）
   */
  static List<Candidate> candidates(Path directory, long minModifiedMillis, int maxCandidates) {
    if (directory == null || maxCandidates <= 0) {
      return List.of();
    }
    List<Candidate> found = new ArrayList<>(Math.min(maxCandidates, 8));
    try {
      if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        return List.of();
      }
      Path realDirectory = directory.toRealPath();
      int scanned = 0;
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
        for (Path candidate : entries) {
          if (++scanned > MAX_SCAN_ENTRIES || found.size() >= maxCandidates) {
            break;
          }
          try {
            Path fileName = candidate.getFileName();
            if (fileName == null || !isProfileName(fileName.toString())) {
              continue;
            }
            if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
              continue;
            }
            long size = Files.size(candidate);
            if (!acceptableSize(size)) {
              continue;
            }
            long modified = Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (modified < minModifiedMillis) {
              continue;
            }
            Path real = candidate.toRealPath();
            if (real.equals(realDirectory) || !real.startsWith(realDirectory)) {
              // realpath 跑出 spark 目录（软链接/挂载点）：不读
              continue;
            }
            found.add(new Candidate(candidate, modified));
          } catch (IOException | RuntimeException unreadable) {
            // 单个条目读不了只跳过它：同目录里其它候选仍可能属于本次采样
          }
        }
      }
    } catch (IOException | RuntimeException unreadable) {
      return List.of();
    }
    return List.copyOf(found);
  }

  /** {@code file} 是否就是标记 {@code marker} 对应的采样文件（逐字节相等，绝不模糊匹配）。 */
  static boolean matches(Path file, String marker) {
    if (file == null || !isMarker(marker)) {
      return false;
    }
    Optional<Header> header = readHeader(file);
    return header.isPresent() && header.get().matches(marker);
  }

  /**
   * 在 {@code directory} 里找出 comment 等于 {@code marker} 的采样文件；多个匹配取最新的一个。
   *
   * <p>目录本身是符号链接、不是目录、条目数超过上限、单个条目异常，都不外抛：返回空表示「这一轮没找到」。
   */
  static Optional<Path> findOwned(Path directory, String marker) {
    return findOwned(directory, marker, MAX_SCAN_ENTRIES);
  }

  /** 同 {@link #findOwned(Path, String)}，但候选上限可指定（单测用）。 */
  static Optional<Path> findOwned(Path directory, String marker, int maxEntries) {
    if (!isMarker(marker) || maxEntries <= 0) {
      return Optional.empty();
    }
    Path best = null;
    long bestModified = Long.MIN_VALUE;
    for (Candidate candidate : candidates(directory, Long.MIN_VALUE, maxEntries)) {
      if (!matches(candidate.path(), marker)) {
        continue;
      }
      if (best == null || candidate.modifiedMillis() > bestModified) {
        best = candidate.path();
        bestModified = candidate.modifiedMillis();
      }
    }
    return Optional.ofNullable(best);
  }

  /** {@code candidate} 是否确实位于 {@code base} 之内（按 realpath 判定，拒绝软链接逃逸）。 */
  static boolean isInside(Path base, Path candidate) {
    if (base == null || candidate == null) {
      return false;
    }
    try {
      Path realBase = base.toRealPath();
      Path realCandidate = candidate.toRealPath();
      return !realCandidate.equals(realBase) && realCandidate.startsWith(realBase);
    } catch (IOException | RuntimeException unreadable) {
      return false;
    }
  }

  private static int lengthDelimited(int field) {
    return (field << 3) | WIRE_LENGTH;
  }

  /**
   * 只前进、不回溯的 protobuf 线格式游标。
   *
   * <p>所有读取都对着 {@code limit} 做边界检查；越界的字段一律让调用方走「放弃」分支，
   * 绝不越界读、也不按声明长度分配大缓冲。
   */
  private static final class Cursor {

    private final byte[] data;
    private final int limit;
    private int position;

    Cursor(byte[] data, int limit) {
      this.data = data;
      this.limit = limit;
    }

    int position() {
      return this.position;
    }

    /** 读一个字段 tag；越界、长度为 0 或字段号为 0 返回 {@code -1}。 */
    int readTag() {
      long value = readVarint();
      if (value <= 0L || value > Integer.MAX_VALUE) {
        return -1;
      }
      int tag = (int) value;
      return (tag >>> 3) == 0 ? -1 : tag;
    }

    /** 读 varint（最多 10 字节）；越界或超长返回 {@code -1}。 */
    long readVarint() {
      long result = 0L;
      for (int shift = 0; shift < 64; shift += 7) {
        if (this.position >= this.limit) {
          return -1L;
        }
        int current = this.data[this.position++] & 0xFF;
        result |= (long) (current & 0x7F) << shift;
        if ((current & 0x80) == 0) {
          return result;
        }
      }
      return -1L;
    }

    /** 跳过 {@code count} 字节；越过可读范围返回 {@code false} 且位置保持不变。 */
    boolean skipStrict(int count) {
      if (count < 0 || count > this.limit - this.position) {
        return false;
      }
      this.position += count;
      return true;
    }

    String readString(int length) {
      int safeLength = Math.max(0, Math.min(length, this.limit - this.position));
      String value = new String(this.data, this.position, safeLength, StandardCharsets.UTF_8);
      this.position += safeLength;
      return value;
    }
  }
}
