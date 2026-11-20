package net.mikumc.mikuxraynet.cache;

import java.nio.ByteBuffer;

/**
 * 磁盘缓存条目的<b>负载信封</b>编解码：把「改写后的区块字节」与其「被伪装坐标清单」一起持久化。
 *
 * <p><b>为什么必须带坐标</b>：显形索引只在区块被<b>实际改写</b>时才会记录坐标。若磁盘缓存命中后
 * 只回填字节、不带坐标，那么该区块就永远不会进入显形索引 —— 玩家走到裸露矿旁边也永不还原。
 * 因此信封里必须原样带上 {@code obfuscatedPositions}，命中时由 {@code writeBack} 重新写入索引。
 *
 * <p><b>布局</b>：{@code [i64 原始字节指纹][i32 坐标个数][i32 × N 伪装坐标][i32 遮挡负载长度][遮挡负载]
 * [改写后的区块字节]}。指纹用于识别「同一区块位置下发的封包字节是否变了」，不符即视为未命中。
 *
 * <p><b>遮挡负载（长度 0 = 无）</b>：异步实体遮挡的「整列遮挡数据」（见 {@code occlusion.OcclusionPayload}）。
 * 位图只在反矿透解码时生成，而缓存命中不会解码；若不带它，命中区块就永远没有遮挡数据
 * （实体不再被隐藏）。带上后，命中路径可把遮挡数据恢复进遮挡仓库。
 *
 * <p><b>改动本布局必须同时上调容器版本号</b>（见 {@code BufferedLinearV3Format.VERSION}），
 * 否则旧条目会以「长度校验不匹配」被逐条判为未命中，且旧文件不会整体重建。
 *
 * <p><b>拒绝原因</b>：实机 dump 里「负载被拒」只有一个总数，看不出是「缓存内容过期」（区块被改过，属正常
 * 失效、会被重写覆盖）还是「信封/数据故障」（负载损坏或与容器格式不符：同一区块每次都重写，命中率永远上不去）。
 * 因此解码在拒绝时除给出「不可用」外还带回 {@link Decoded#reject()}：原因由<b>同一次解码</b>给出，
 * 不追加第二次解析、不追加读盘；正常（可用）路径的分配与改动前完全一致，只有失败分支新建一个结果对象。
 *
 * <p>本类是纯工具（无平台耦合、无状态、可离线单测）；解码遇到截断/非法长度一律返回不可用结果
 * （交由调用方按「未命中」降级，fail-open）。
 */
public final class DiskPayload {

  /** 信封固定头部长度：指纹 8 + 坐标个数 4。 */
  public static final int HEADER_SIZE = 8 + Integer.BYTES;

  private DiskPayload() {
  }

  /**
   * 解码结果：原始字节指纹 + 被伪装坐标（打包为区块内相对编码的 {@code int}）
   * + 整列遮挡数据负载（可为 {@code null}）+ 改写后的区块字节 + 拒绝原因（可用时为 {@code null}）。
   *
   * <p><b>先判 {@link #usable()}</b>：解码恒返回对象，被拒时 {@link #reject()} 给出原因，
   * {@code positions} / {@code occlusion} / {@code data} 全为 {@code null}。误把拒绝当命中会立刻空指针，
   * 不会让写回路径拿到空字节而静默放行原包（反矿透失效却毫无痕迹）。
   */
  public record Decoded(long sourceHash, int[] positions, byte[] occlusion, byte[] data, Reject reject) {

    /** 兼容构造：可用结果（拒绝原因为 {@code null}）。 */
    public Decoded(long sourceHash, int[] positions, byte[] occlusion, byte[] data) {
      this(sourceHash, positions, occlusion, data, null);
    }

    /** 是否可用（未被拒）；只有可用时其余四个字段才有意义。 */
    public boolean usable() {
      return reject == null;
    }
  }

  /**
   * 拒绝原因：把「缓存内容过期」与「信封/数据故障」这两类完全不同的处置分开。
   *
   * <p>前者属正常失效（区块内容变了 → 照常重写并覆盖该条），后者说明负载损坏、容器格式与代码不一致或
   * 写入被截断。两者混在同一个总数里时，运维既看不到真实的损坏率，也无法判断命中率为何上不去。
   */
  public enum Reject {

    /** 负载短于信封头（指纹 8 + 坐标个数 4 + 遮挡长度 4）：读到半条记录，或文件被截短。 */
    TRUNCATED,

    /** 条目记录的原始字节指纹与本次要改写的字节不一致：缓存内容已过期，属正常失效。 */
    SOURCE_HASH_MISMATCH,

    /**
     * 坐标个数为负，或剩余长度放不下「坐标个数 × 4 + 遮挡长度 4 + 至少 1 字节区块数据」：
     * 坐标被截断，或「声明了坐标却没有区块字节」这类畸形条目。
     */
    POSITION_COUNT_INVALID,

    /** 遮挡负载长度为负或超出剩余长度：信封结构损坏。 */
    OCCLUSION_LENGTH_INVALID,

    /** 信封自洽（遮挡长度把剩余字节吃光）但声明了伪装坐标却没有区块字节：命中后会按空字节写回，必须拒绝。 */
    EMPTY_DATA_WITH_POSITIONS,

    /** 解码过程抛出运行时异常（缓冲区越界等）：按未命中降级，不改变放行语义。 */
    DECODE_ERROR;

    /** 是否属于「缓存内容过期」（正常失效、照常重写覆盖），而不是信封/数据故障。 */
    public boolean sourceHashMismatch() {
      return this == SOURCE_HASH_MISMATCH;
    }
  }

  /**
   * 一条负载编码后的字节数（与 {@link #encode} 的定容公式逐项一致，但<b>不实际编码</b>）。
   *
   * <p>供上层在真正编码前做容量/限长预判：编码要分配并整块拷贝改写后的区块字节（几十~几百 KB），
   * 若磁盘缓存此刻已被拒收（条目达上限 / 磁盘线程积压），这次分配与拷贝纯属浪费——登录风暴下
   * 会变成可观的 GC 压力。用本方法先算长度、再决定是否编码。
   */
  public static int encodedLength(int[] positions, byte[] occlusion, byte[] data) {
    int count = positions == null ? 0 : positions.length;
    int occlusionLength = occlusion == null ? 0 : occlusion.length;
    int chunkLength = data == null ? 0 : data.length;
    return HEADER_SIZE + count * Integer.BYTES + Integer.BYTES + occlusionLength + chunkLength;
  }

  /**
   * 编码一条负载。
   *
   * <p><b>空区块字节的语义</b>：{@code data} 为空只对「没有任何伪装坐标」的条目合法——这类条目命中后
   * 不做任何写回（{@code writeBack} 见空坐标清单直接返回），因此省掉整块区块字节；反之「有伪装坐标却
   * 没有字节」是畸形，{@link #decode} 一律拒绝（否则命中后会按空字节写回封包）。
   *
   * @param sourceHash 原始（未改写）区块字节的指纹
   * @param positions  被伪装坐标；{@code null} 视为空
   * @param occlusion  整列遮挡数据负载；{@code null} 视为「无」（写出长度 0）
   * @param data       改写后的区块字节；{@code null} 视为空。无伪装坐标的条目按空字节写（见 {@link #decode}）
   */
  public static byte[] encode(long sourceHash, int[] positions, byte[] occlusion, byte[] data) {
    int[] coordinates = positions == null ? new int[0] : positions;
    byte[] occlusionBytes = occlusion == null ? new byte[0] : occlusion;
    byte[] chunk = data == null ? new byte[0] : data;
    ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + coordinates.length * Integer.BYTES
        + Integer.BYTES + occlusionBytes.length + chunk.length);
    buffer.putLong(sourceHash);
    buffer.putInt(coordinates.length);
    for (int position : coordinates) {
      buffer.putInt(position);
    }
    buffer.putInt(occlusionBytes.length);
    buffer.put(occlusionBytes);
    buffer.put(chunk);
    return buffer.array();
  }

  /**
   * 解码一条负载。
   *
   * @param expectedSourceHash 期望的原始字节指纹；不符按 {@link Reject#SOURCE_HASH_MISMATCH} 拒绝
   * @return 恒不为 {@code null}：{@link Decoded#usable()} 为 {@code false} 即表示按未命中降级，原因见
   *         {@link Decoded#reject()}；{@code sourceHash} 在能读出时带回条目记录的指纹（读不出为 0）
   */
  public static Decoded decode(byte[] payload, long expectedSourceHash) {
    if (payload == null || payload.length < HEADER_SIZE + Integer.BYTES) {
      return rejected(Reject.TRUNCATED, 0L);
    }
    try {
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      long sourceHash = buffer.getLong();
      if (sourceHash != expectedSourceHash) {
        return rejected(Reject.SOURCE_HASH_MISMATCH, sourceHash);
      }
      int count = buffer.getInt();
      // 剩余至少要容得下 count 个坐标 + 4 字节遮挡长度 + 非空区块字节（判定与改动前逐字一致）
      if (count < 0 || count > (buffer.remaining() - Integer.BYTES - 1) / Integer.BYTES) {
        return rejected(Reject.POSITION_COUNT_INVALID, sourceHash);
      }
      int[] positions = new int[count];
      for (int index = 0; index < count; index++) {
        positions[index] = buffer.getInt();
      }
      int occlusionLength = buffer.getInt();
      // 遮挡负载可以吃掉全部剩余字节（其后是否允许没有区块字节由下面的坐标数决定）
      if (occlusionLength < 0 || occlusionLength > buffer.remaining()) {
        return rejected(Reject.OCCLUSION_LENGTH_INVALID, sourceHash);
      }
      byte[] occlusion = null;
      if (occlusionLength > 0) {
        occlusion = new byte[occlusionLength];
        buffer.get(occlusion);
      }
      byte[] data = new byte[buffer.remaining()];
      buffer.get(data);
      // 空区块字节合法，且只对应「没有任何伪装坐标」的条目：这类条目命中后不需要写回字节
      // （writeBack 按空坐标清单直接返回），写盘时省掉整块区块字节（几十~几百 KB）。
      // 反之「有伪装坐标却没有字节」是畸形，必须拒绝——否则命中后会按空字节写回封包。
      if (data.length == 0 && count > 0) {
        return rejected(Reject.EMPTY_DATA_WITH_POSITIONS, sourceHash);
      }
      return new Decoded(sourceHash, positions, occlusion, data);
    } catch (RuntimeException exception) {
      return rejected(Reject.DECODE_ERROR, 0L);
    }
  }

  /**
   * 构造一条「被拒」结果：只在失败分支分配（正常命中路径的分配与改动前一致），
   * {@code storedSourceHash} 尽量带上条目记录的指纹，便于运维对照「缓存是哪一版内容」。
   */
  private static Decoded rejected(Reject reason, long storedSourceHash) {
    return new Decoded(storedSourceHash, null, null, null, reason);
  }
}