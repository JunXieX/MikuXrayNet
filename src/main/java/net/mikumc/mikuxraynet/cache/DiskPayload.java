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
 * <p><b>格式变更</b>：本信封新增了「遮挡负载」字段，旧格式条目会被新的 {@code decode} 判为畸形
 * （长度校验不匹配）而按未命中处理；同时容器版本号已同步上调（见 {@code BufferedLinearV3Format.VERSION}），
 * 旧缓存文件会被整体删除重建（属正常现象）。
 *
 * <p>本类是纯工具（无平台耦合、无状态、可离线单测）；解码遇到截断/非法长度一律返回 {@code null}
 * （交由调用方按「未命中」降级，fail-open）。
 */
public final class DiskPayload {

  /** 信封固定头部长度：指纹 8 + 坐标个数 4。 */
  public static final int HEADER_SIZE = 8 + Integer.BYTES;

  private DiskPayload() {
  }

  /**
   * 解码结果：原始字节指纹 + 被伪装坐标（打包为区块内相对编码的 {@code int}）
   * + 整列遮挡数据负载（可为 {@code null}）+ 改写后的区块字节。
   */
  public record Decoded(long sourceHash, int[] positions, byte[] occlusion, byte[] data) {
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
   * @param expectedSourceHash 期望的原始字节指纹；不符直接返回 {@code null}
   * @return 解码结果；截断、指纹不符、坐标数非法、遮挡负载长度非法或区块字节为空时返回 {@code null}
   *         （按未命中处理）
   */
  public static Decoded decode(byte[] payload, long expectedSourceHash) {
    if (payload == null || payload.length < HEADER_SIZE + Integer.BYTES) {
      return null;
    }
    try {
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      long sourceHash = buffer.getLong();
      if (sourceHash != expectedSourceHash) {
        return null;
      }
      int count = buffer.getInt();
      // 剩余至少要容得下 count 个坐标 + 4 字节遮挡长度 + 非空区块字节
      if (count < 0 || count > (buffer.remaining() - Integer.BYTES - 1) / Integer.BYTES) {
        return null;
      }
      int[] positions = new int[count];
      for (int index = 0; index < count; index++) {
        positions[index] = buffer.getInt();
      }
      int occlusionLength = buffer.getInt();
      // 遮挡负载可以吃掉全部剩余字节（其后是否允许没有区块字节由下面的坐标数决定）
      if (occlusionLength < 0 || occlusionLength > buffer.remaining()) {
        return null;
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
        return null;
      }
      return new Decoded(sourceHash, positions, occlusion, data);
    } catch (RuntimeException exception) {
      return null;
    }
  }
}