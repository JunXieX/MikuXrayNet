package net.mikumc.mikuxraynet.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p><b>spark 自己的账本</b>：{@code <spark 数据目录>/activity.json} 是 spark 每次落盘后追加的
 * JSON 数组，其中 {@code data.value} 就是它<b>真实写出的路径</b>（相对服务器根）。这份账本比目录轮询更
 * 权威，也比命令回执更长寿（不依赖 sender 是否还活着）：{@link #readActivity(Path, int)} / {@link #parseActivity(String, int)}
 * 只读、有界（只读尾部 {@link #MAX_ACTIVITY_BYTES} 字节）、不跟随软链接，读不懂就返回空由调用方回落目录轮询。
 *
 * <p><b>四道校验</b>：无论是回执/账本给出的路径，还是目录轮询找到的文件，都必须过
 * {@link #verifyOwnedProfile(List, Path, String)}：真实路径落在 Spark 数据目录内、文件名合规、
 * {@code 0 < 大小 ≤ 上限}、注释与本轮标记逐字节相等。任何一道不过都不上传，并带回一句中文原因供 dump 自诊断。
 *
 * <p>包内可见：只有 {@link SparkDumpCapture} 使用，不对外暴露。
 */
final class SparkProfileFiles {

  /** Spark 采样文件名前缀（{@code resolveSaveFile("profile", "sparkprofile")} 的两段）。 */
  private static final String PREFIX = "profile-";

  /** Spark 采样文件扩展名。 */
  private static final String SUFFIX = ".sparkprofile";

  /** spark 自己记录落盘结果的文件名（位于 spark 数据目录内）。 */
  static final String ACTIVITY_FILE = "activity.json";

  /** 文件名长度上限：真实名字约 39 字符，留富余但拒绝异常长名。 */
  private static final int MAX_FILE_NAME = 96;

  /** 只读头部这么多字节用于解析元数据；comment 位于元数据前段，64 KiB 足够且严格有界。 */
  static final int MAX_HEADER_BYTES = 64 * 1024;

  /**
   * 允许解析/上传的采样文件上限：<b>64 MiB</b>。
   *
   * <p>30 秒 CPU 采样在零人小服上只有几十 KB，但生产（数十人、全部线程、10 毫秒间隔）完全可能到几十 MB；
   * 上传走 gzip 流式压缩，压缩后通常远小于此值，所以上限只用于挡住「明显不是一次正常采样」的文件。
   * 被这道上限挡下的文件<b>必须</b>写进 dump 的 Spark 段（自诊断），绝不静默跳过。
   */
  static final long MAX_PROFILE_BYTES = 64L * 1024 * 1024;

  /** 一次扫描最多看多少个目录条目（防止把超量目录全枚举一遍）。 */
  static final int MAX_SCAN_ENTRIES = 512;

  /** comment 字段允许的最大字节数（我们自己的标记只有 38 字节，留足别人注释的余量）。 */
  private static final int MAX_COMMENT_BYTES = 512;

  /** activity.json 单次最多读多少字节（只读尾部：账本是追加写的，最新条目在末尾）。 */
  static final int MAX_ACTIVITY_BYTES = 256 * 1024;

  /** activity.json 里一个路径值允许的最大字符数（真实值约 50 字符）。 */
  static final int MAX_ACTIVITY_PATH_CHARS = 512;

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

  /** 极简 JSON 读取的深度上限：activity.json 只用到「数组 → 对象 → 对象」三层。 */
  private static final int MAX_JSON_DEPTH = 6;

  /** 单个 JSON 字符串的字符上限：超过即判该值不可用（路径不可能这么长）。 */
  private static final int MAX_JSON_STRING = 4096;

  /** 单个 JSON 对象保留的字段上限（超出只扫描不保留，避免被构造出的超大对象撑爆内存）。 */
  private static final int MAX_JSON_FIELDS = 32;

  /** 单个 JSON 数组保留的元素上限（activity.json 只需要最近几条）。 */
  private static final int MAX_JSON_ELEMENTS = 64;

  /**
   * 一次解析最多尝试多少个对象的起点（防御「满篇都是 <code>{</code> 的垃圾文件」）。
   *
   * <p>必须大于「尾部字节数 / 最短条目长度」：256 KiB 的尾部最多塞得下约 4400 条紧凑记录，
   * 若上限比它小，扫不到末尾那条<b>最新</b>记录——而最新那条正是我们要的。
   */
  private static final int MAX_JSON_ATTEMPTS = 8192;

  /** activity 条目里认的 {@code type} 值。 */
  private static final String ACTIVITY_PROFILER = "Profiler";

  /** activity 条目里认的 {@code data.type} 值。 */
  private static final String ACTIVITY_DATA_FILE = "file";

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

  /** 上限的 MiB 口径（写进状态行/说明时与常量保持一致，避免文案与阈值各说一套）。 */
  static long maxProfileMegabytes() {
    return MAX_PROFILE_BYTES / (1024L * 1024L);
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

  /** 候选条目：文件、修改时间与字节数（后两者一起构成「同一份内容」的去重键）。 */
  record Candidate(Path path, long modifiedMillis, long size) {
  }

  /** 目录里的一个采样文件（<b>不解析内容</b>，用于诊断：连超上限的文件也要能报出来）。 */
  record Entry(Path path, long size, long modifiedMillis) {
  }

  /**
   * 去重键：{@code 文件名@修改时间@字节数}。
   *
   * <p><b>必须带 byte 数</b>：Spark 是「边写边可见」的——轮询可能在文件只写了一半时读到它（头部还不可解析）。
   * 若只看「文件名 + 修改时间」，半写文件在下一轮长齐后 mtime 可能没变（同毫秒/同秒精度），于是被永久跳过。
   */
  static String dedupeKey(Candidate candidate) {
    if (candidate == null) {
      return "";
    }
    Path name = candidate.path() == null ? null : candidate.path().getFileName();
    return (name == null ? "?" : name.toString()) + "@" + candidate.modifiedMillis() + "@" + candidate.size();
  }

  /**
   * 枚举目录里「可能属于某次采样」的候选文件（<b>不解析内容</b>）。
   *
   * <p>过滤全部是廉价的元数据判定：文件名、是否普通文件、是否软链接、大小范围、realpath 是否仍在目录内、
   * 修改时间是否不早于 {@code minModifiedMillis}。真正的归属判定留给 {@link #matches(Path, String)}，
   * 这样调用方可以只对新候选读一次头部，而不是每轮把整个目录重读一遍。
   *
   * <p><b>顺序不再是目录枚举顺序</b>：先收集合格候选（仍受 {@link #MAX_SCAN_ENTRIES} 限制），再按修改时间
   * <b>降序</b>取前 {@code maxCandidates} 个。{@code DirectoryStream} 的顺序未定义，历史文件一多，
   * 「取前 8 个」就可能整批拿不到本次刚写的那个。
   *
   * @param directory          Spark 的采样目录（本身是软链接或不存在时返回空）
   * @param minModifiedMillis  只接受修改时间不早于该时刻的条目（{@link Long#MIN_VALUE} 表示不过滤）
   * @param maxCandidates      最多返回多少个候选（同时限制枚举的条目数）
   */
  static List<Candidate> candidates(Path directory, long minModifiedMillis, int maxCandidates) {
    if (directory == null || maxCandidates <= 0) {
      return List.of();
    }
    List<Candidate> found = new ArrayList<>(Math.min(MAX_SCAN_ENTRIES, 16));
    try {
      if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        return List.of();
      }
      Path realDirectory = directory.toRealPath();
      int scanned = 0;
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
        for (Path candidate : entries) {
          if (++scanned > MAX_SCAN_ENTRIES) {
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
            found.add(new Candidate(candidate, modified, size));
          } catch (IOException | RuntimeException unreadable) {
            // 单个条目读不了只跳过它：同目录里其它候选仍可能属于本次采样
          }
        }
      }
    } catch (IOException | RuntimeException unreadable) {
      return List.of();
    }
    found.sort(Comparator.comparingLong(Candidate::modifiedMillis).reversed());
    return found.size() > maxCandidates ? List.copyOf(found.subList(0, maxCandidates)) : List.copyOf(found);
  }

  /**
   * 目录里「修改时间不早于 {@code minModifiedMillis}」的采样文件清单（按修改时间降序，最多 {@code maxEntries} 个）。
   *
   * <p>与 {@link #candidates(Path, long, int)} 的唯一区别是<b>不按大小过滤</b>：这条路径只为自诊断服务，
   * 目的正是把「超上限被拒」的文件也报出来（否则被拒的文件在 dump 里完全不可见）。
   */
  static List<Entry> recentProfiles(Path directory, long minModifiedMillis, int maxEntries) {
    if (directory == null || maxEntries <= 0) {
      return List.of();
    }
    List<Entry> found = new ArrayList<>(Math.min(MAX_SCAN_ENTRIES, 16));
    try {
      if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        return List.of();
      }
      Path realDirectory = directory.toRealPath();
      int scanned = 0;
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
        for (Path candidate : entries) {
          if (++scanned > MAX_SCAN_ENTRIES) {
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
            long modified = Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (modified < minModifiedMillis) {
              continue;
            }
            Path real = candidate.toRealPath();
            if (real.equals(realDirectory) || !real.startsWith(realDirectory)) {
              continue;
            }
            found.add(new Entry(candidate, Files.size(candidate), modified));
          } catch (IOException | RuntimeException unreadable) {
            // 单个条目读不了只跳过它
          }
        }
      }
    } catch (IOException | RuntimeException unreadable) {
      return List.of();
    }
    found.sort(Comparator.comparingLong(Entry::modifiedMillis).reversed());
    return found.size() > maxEntries ? List.copyOf(found.subList(0, maxEntries)) : List.copyOf(found);
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

  /**
   * 校验结果：通过时 {@code path} 非空；被拒时 {@code reason} 是一句中文短原因（写进 dump 的自诊断段）。
   *
   * <p>带原因而不是只返回 boolean，是因为「被拒」在排障时和「没找到」一样重要——生产上那句千篇一律的
   * 「超时」正是被拒原因被丢掉的后果。
   */
  record Checked(Path path, String reason) {

    boolean ok() {
      return this.path != null;
    }

    static Checked pass(Path path) {
      return new Checked(path, null);
    }

    static Checked reject(String reason) {
      return new Checked(null, reason);
    }
  }

  /**
   * 四道校验：<b>文件名合规</b> → <b>0 &lt; 大小 ≤ 上限</b> → <b>真实路径落在 Spark 数据目录内</b> →
   * <b>注释与本轮标记逐字节相等</b>。
   *
   * <p>顺序是「先廉价后昂贵」：名字与大小只 stat 一次，realpath 与头部解析才真正碰磁盘。
   * 任一道不过都返回带原因的被拒结果，调用方据此写自诊断，<b>绝不</b>上传。
   *
   * @param directories 允许的 Spark 数据目录（通常一到两个）；为空表示不限制（仅单测用）
   * @param candidate   待校验路径（可以来自命令回执、spark 账本或目录轮询）
   * @param marker      本轮标记
   */
  static Checked verifyOwnedProfile(List<Path> directories, Path candidate, String marker) {
    if (candidate == null) {
      return Checked.reject("没有可用路径");
    }
    Path fileName = candidate.getFileName();
    if (fileName == null) {
      return Checked.reject("路径不含文件名");
    }
    if (!isProfileName(fileName.toString())) {
      return Checked.reject("文件名不是 Spark 采样文件形态");
    }
    try {
      if (Files.isSymbolicLink(candidate)) {
        return Checked.reject("是符号链接（不跟随）");
      }
      if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
        return Checked.reject("不存在或不是普通文件");
      }
      long size = Files.size(candidate);
      if (size <= 0L) {
        return Checked.reject("文件为空（可能仍在写入）");
      }
      if (size > MAX_PROFILE_BYTES) {
        return Checked.reject("超出大小上限（" + size + " 字节 > " + maxProfileMegabytes() + " MiB）");
      }
    } catch (IOException | RuntimeException unreadable) {
      return Checked.reject("读取文件属性失败");
    }
    if (directories != null && !directories.isEmpty()) {
      boolean inside = false;
      for (Path directory : directories) {
        if (isInside(directory, candidate)) {
          inside = true;
          break;
        }
      }
      if (!inside) {
        return Checked.reject("真实路径不在 Spark 数据目录内");
      }
    }
    Optional<Header> header = readHeader(candidate);
    if (header.isEmpty()) {
      return Checked.reject("头部不可解析（可能仍在写入）");
    }
    if (!isMarker(marker) || !header.get().matches(marker)) {
      return Checked.reject("注释与本轮标记不一致");
    }
    return Checked.pass(candidate);
  }

  /** 文件的大小 + 修改时间戳（用于「内容没变就不重复解析 activity.json」）。 */
  record Stamp(long size, long modifiedMillis) {
  }

  /** 取文件戳：不存在/是链接/读不到一律 {@code null}（调用方按「没有账本」处理）。 */
  static Stamp stampOf(Path file) {
    if (file == null) {
      return null;
    }
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      return new Stamp(Files.size(file), Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis());
    } catch (IOException | RuntimeException unreadable) {
      return null;
    }
  }

  /**
   * activity.json 里的一条落盘记录。
   *
   * @param timeMillis Spark 记录的时间（纪元毫秒，与 {@code System.currentTimeMillis()} 同尺度）
   * @param type       条目类型（{@code Profiler} 才可能是采样落盘）
   * @param dataType   数据类型（{@code file} 表示 data.value 是文件路径）
   * @param value      文件路径原文（Windows 上是 {@code plugins\\spark\\profile-….sparkprofile}，按服务器根相对）
   */
  record ActivityEntry(long timeMillis, String type, String dataType, String value) {
  }

  /**
   * 读取 spark 的落盘账本，返回<b>按时间降序</b>的若干条 {@code Profiler/file} 记录。
   *
   * <p>有界且只读：只读文件尾部 {@link #MAX_ACTIVITY_BYTES} 字节（账本只追加，最新记录在末尾）、
   * 不跟随软链接、不创建/修改任何文件。读不到（不存在、是链接、权限不足、JSON 不完整）返回空表，
   * 调用方回落目录轮询。
   *
   * @param maxEntries 最多返回多少条（{@code <= 0} 表示不读）
   */
  static List<ActivityEntry> readActivity(Path activityFile, int maxEntries) {
    if (activityFile == null || maxEntries <= 0) {
      return List.of();
    }
    try {
      if (Files.isSymbolicLink(activityFile) || !Files.isRegularFile(activityFile, LinkOption.NOFOLLOW_LINKS)) {
        return List.of();
      }
      long size = Files.size(activityFile);
      if (size <= 0L) {
        return List.of();
      }
      int wanted = (int) Math.min(size, MAX_ACTIVITY_BYTES);
      byte[] tail = new byte[wanted];
      Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
      try (SeekableByteChannel channel = Files.newByteChannel(activityFile, options)) {
        channel.position(size - wanted);
        ByteBuffer buffer = ByteBuffer.wrap(tail);
        while (buffer.hasRemaining()) {
          if (channel.read(buffer) <= 0) {
            break;
          }
        }
      }
      return parseActivity(new String(tail, StandardCharsets.UTF_8), maxEntries);
    } catch (IOException | RuntimeException unreadable) {
      return List.of();
    }
  }

  /**
   * 解析 activity.json 文本（纯函数，可离线单测）。
   *
   * <p>只认「顶层对象里的 type=Profiler、data.type=file、data.value 是字符串」这种条目；
   * 前 1～2 个字符被截断（读尾部导致）时从下一个 <code>{</code> 重新开始，因此对「美化数组」与
   * 「一行一条」两种写法都能用。都是最坏 O(文本长度) 的有界扫描。
   */
  static List<ActivityEntry> parseActivity(String json, int maxEntries) {
    if (json == null || json.isEmpty() || maxEntries <= 0) {
      return List.of();
    }
    List<ActivityEntry> entries = new ArrayList<>(Math.min(maxEntries, 8));
    int cursor = 0;
    int attempts = 0;
    while (cursor < json.length() && attempts < MAX_JSON_ATTEMPTS) {
      int start = json.indexOf('{', cursor);
      if (start < 0) {
        break;
      }
      attempts++;
      JsonReader reader = new JsonReader(json);
      reader.position(start);
      try {
        ActivityEntry entry = toActivityEntry(reader.value(0));
        cursor = reader.position();
        if (entry != null) {
          entries.add(entry);
        }
      } catch (RuntimeException broken) {
        // 这一段不是合法对象：从下一个字符继续找（尾部截断的首个对象多半走到这里）
        cursor = start + 1;
      }
    }
    entries.sort(Comparator.comparingLong(ActivityEntry::timeMillis).reversed());
    return entries.size() > maxEntries ? List.copyOf(entries.subList(0, maxEntries)) : List.copyOf(entries);
  }

  private static ActivityEntry toActivityEntry(Object value) {
    if (!(value instanceof Map<?, ?> entry)) {
      return null;
    }
    Object type = entry.get("type");
    if (!(type instanceof String typeText) || !ACTIVITY_PROFILER.equalsIgnoreCase(typeText)) {
      return null;
    }
    Object time = entry.get("time");
    if (!(time instanceof Long timeMillis) || timeMillis <= 0L) {
      return null;
    }
    if (!(entry.get("data") instanceof Map<?, ?> data)) {
      return null;
    }
    Object dataType = data.get("type");
    if (!(dataType instanceof String dataTypeText) || !ACTIVITY_DATA_FILE.equalsIgnoreCase(dataTypeText)) {
      return null;
    }
    if (!(data.get("value") instanceof String path)) {
      return null;
    }
    return new ActivityEntry(timeMillis, typeText, dataTypeText, path);
  }

  /**
   * 把账本里的路径原文落成绝对路径：<b>相对路径按服务器根解析</b>（spark 写的就是相对服务器根的路径）。
   *
   * <p>只做形态收敛（反斜杠统一成 {@code /}、去掉前导 {@code ./}、{@code normalize()}），<b>不做任何信任判定</b>：
   * 是否允许读由 {@link #verifyOwnedProfile(List, Path, String)} 的四道校验决定。含控制字符、过长、
   * 需要服务器根但拿不到根，一律返回空。
   *
   * @param rawValue   activity 条目里的 {@code data.value}
   * @param serverRoot 服务器根目录（{@code plugins} 的父目录）；遇到相对路径且为 {@code null} 时返回空
   */
  static Optional<Path> activityPath(String rawValue, Path serverRoot) {
    if (rawValue == null) {
      return Optional.empty();
    }
    String value = rawValue.trim();
    if (value.isEmpty() || value.length() > MAX_ACTIVITY_PATH_CHARS) {
      return Optional.empty();
    }
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current < 0x20 || current == 0x7F) {
        return Optional.empty();
      }
    }
    String normalized = value.replace('\\', '/');
    while (normalized.startsWith("./")) {
      normalized = normalized.substring(2);
    }
    try {
      Path path = Path.of(normalized);
      if (!path.isAbsolute()) {
        if (serverRoot == null) {
          return Optional.empty();
        }
        path = serverRoot.resolve(path);
      }
      return Optional.of(path.toAbsolutePath().normalize());
    } catch (RuntimeException invalid) {
      return Optional.empty();
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

  /**
   * 极简 JSON 读取器（只认对象/数组/字符串/整数/字面量）。
   *
   * <p>存在的理由：activity.json 的结构只有三层，为它引入一个 JSON 库（或反射到服务端自带的那份）
   * 会把「读一个 spark 自家的小账本」变成新的依赖面；这里给一份带深度/长度/字段数上限的只读扫描，
   * 遇到任何不认识的形态就抛 {@link Malformed}，由调用方跳到下一个对象。<b>不分配未经验证的长度</b>。
   */
  private static final class JsonReader {

    private final String text;
    private int position;

    JsonReader(String text) {
      this.text = text;
    }

    int position() {
      return this.position;
    }

    void position(int next) {
      this.position = next;
    }

    Object value(int depth) {
      if (depth > MAX_JSON_DEPTH) {
        throw new Malformed();
      }
      skipWhitespace();
      if (this.position >= this.text.length()) {
        throw new Malformed();
      }
      char current = this.text.charAt(this.position);
      switch (current) {
        case '{' -> {
          return object(depth + 1);
        }
        case '[' -> {
          return array(depth + 1);
        }
        case '"' -> {
          return string();
        }
        case 't', 'f', 'n' -> {
          return literal();
        }
        default -> {
          return number();
        }
      }
    }

    private Map<String, Object> object(int depth) {
      Map<String, Object> fields = new LinkedHashMap<>();
      this.position++;
      skipWhitespace();
      if (peek() == '}') {
        this.position++;
        return fields;
      }
      while (true) {
        skipWhitespace();
        if (peek() != '"') {
          throw new Malformed();
        }
        String key = string();
        skipWhitespace();
        if (peek() != ':') {
          throw new Malformed();
        }
        this.position++;
        Object value = value(depth);
        if (fields.size() < MAX_JSON_FIELDS && key != null) {
          fields.put(key, value);
        }
        skipWhitespace();
        char next = take();
        if (next == '}') {
          return fields;
        }
        if (next != ',') {
          throw new Malformed();
        }
      }
    }

    private List<Object> array(int depth) {
      List<Object> elements = new ArrayList<>(4);
      this.position++;
      skipWhitespace();
      if (peek() == ']') {
        this.position++;
        return elements;
      }
      while (true) {
        Object value = value(depth);
        if (elements.size() < MAX_JSON_ELEMENTS) {
          elements.add(value);
        }
        skipWhitespace();
        char next = take();
        if (next == ']') {
          return elements;
        }
        if (next != ',') {
          throw new Malformed();
        }
      }
    }

    /** 读一个字符串；超长（> {@link #MAX_JSON_STRING}）返回 {@code null}（调用方按「不可用」处理）。 */
    private String string() {
      this.position++;
      StringBuilder out = new StringBuilder(32);
      boolean tooLong = false;
      while (true) {
        char current = take();
        if (current == '"') {
          return tooLong ? null : out.toString();
        }
        if (current == '\\') {
          char escape = take();
          switch (escape) {
            case '\\' -> tooLong |= append(out, '\\');
            case '/' -> tooLong |= append(out, '/');
            case '"' -> tooLong |= append(out, '"');
            case 'n' -> tooLong |= append(out, '\n');
            case 'r' -> tooLong |= append(out, '\r');
            case 't' -> tooLong |= append(out, '\t');
            case 'b' -> tooLong |= append(out, '\b');
            case 'f' -> tooLong |= append(out, '\f');
            case 'u' -> tooLong |= append(out, (char) unicode());
            default -> throw new Malformed();
          }
          continue;
        }
        tooLong |= append(out, current);
      }
    }

    /** 追加一个字符；超过上限返回 {@code true}（表示已截断过，值不可用）。 */
    private boolean append(StringBuilder out, char current) {
      if (out.length() >= MAX_JSON_STRING) {
        return true;
      }
      out.append(current);
      return false;
    }

    private int unicode() {
      if (this.position + 4 > this.text.length()) {
        throw new Malformed();
      }
      int parsed = 0;
      for (int index = 0; index < 4; index++) {
        int digit = Character.digit(this.text.charAt(this.position++), 16);
        if (digit < 0) {
          throw new Malformed();
        }
        parsed = (parsed << 4) | digit;
      }
      return parsed;
    }

    private Object number() {
      int start = this.position;
      if (peek() == '-') {
        this.position++;
      }
      boolean integral = true;
      while (this.position < this.text.length()) {
        char current = this.text.charAt(this.position);
        if (current >= '0' && current <= '9') {
          this.position++;
          continue;
        }
        if (current == '.' || current == 'e' || current == 'E' || current == '+' || current == '-') {
          integral = false;
          this.position++;
          continue;
        }
        break;
      }
      if (this.position == start) {
        throw new Malformed();
      }
      if (!integral || this.position - start > 20) {
        // 浮点/超长数字：我们不使用，但要把它消费掉，标为 null
        return null;
      }
      try {
        return Long.parseLong(this.text.substring(start, this.position));
      } catch (NumberFormatException invalid) {
        return null;
      }
    }

    private Object literal() {
      char current = this.text.charAt(this.position);
      if (this.text.startsWith("true", this.position)) {
        this.position += 4;
        return Boolean.TRUE;
      }
      if (this.text.startsWith("false", this.position)) {
        this.position += 5;
        return Boolean.FALSE;
      }
      if (this.text.startsWith("null", this.position)) {
        this.position += 4;
        return null;
      }
      throw new Malformed();
    }

    private char peek() {
      if (this.position >= this.text.length()) {
        throw new Malformed();
      }
      return this.text.charAt(this.position);
    }

    private char take() {
      if (this.position >= this.text.length()) {
        throw new Malformed();
      }
      return this.text.charAt(this.position++);
    }

    private void skipWhitespace() {
      while (this.position < this.text.length()) {
        char current = this.text.charAt(this.position);
        if (current != ' ' && current != '\t' && current != '\n' && current != '\r') {
          return;
        }
        this.position++;
      }
    }
  }

  /** JSON 读取的控制流异常（静态嵌套类：不需要栈信息，只用来「放弃这一段」）。 */
  private static final class Malformed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Malformed() {
      super(null, null, false, false);
    }
  }
}
