package net.mikumc.mikuxraynet.cache;

import java.nio.ByteBuffer;

/**
 * 磁盘缓存条目的<b>负载信封</b>编解码：把「改写后的区块字节」与其「被伪装坐标清单」一起持久化。
 *
 * <p><b>为什么必须带坐标</b>：显形索引只在区块被<b>实际改写</b>时才会记录坐标。若磁盘缓存命中后
 * 只回填字节、不带坐标，那么该区块就永远不会进入显形索引 —— 玩家走到裸露矿旁边也永不还原。
 * 因此信封里必须原样带上 {@code obfuscatedPositions}，命中时由 {@code writeBack} 重新写入索引。
 *
 * <p><b>布局</b>：{@code [i64 原始字节指纹][i32 坐标个数][i32 × N 伪装坐标][改写后的区块字节]}。
 * 指纹用于识别「同一区块位置下发的封包字节是否变了」，不符即视为未命中。
 *
 * <p>本类是纯工具（无平台耦合、无状态、可离线单测）；解码遇到截断/非法长度一律返回 {@code null}
 * （交由调用方按「未命中」降级，fail-open）。
 */
public final class DiskPayload {

  /** 信封固定头部长度：指纹 8 + 坐标个数 4。 */
  public static final int HEADER_SIZE = 8 + Integer.BYTES;

  private DiskPayload() {
  }

  /** 解码结果：原始字节指纹 + 被伪装坐标（打包为区块内相对编码的 {@code int}）+ 改写后的区块字节。 */
  public record Decoded(long sourceHash, int[] positions, byte[] data) {
  }

  /**
   * 一条负载编码后的字节数（与 {@link #encode} 的定容公式逐项一致，但<b>不实际编码</b>）。
   *
   * <p>供上层在真正编码前做容量/限长预判：编码要分配并整块拷贝改写后的区块字节（几十~几百 KB），
   * 若磁盘缓存此刻已被拒收（条目达上限 / 磁盘线程积压），这次分配与拷贝纯属浪费——登录风暴下
   * 会变成可观的 GC 压力。用本方法先算长度、再决定是否编码。
   */
  public static int encodedLength(int[] positions, byte[] data) {
    int count = positions == null ? 0 : positions.length;
    int chunkLength = data == null ? 0 : data.length;
    return HEADER_SIZE + count * Integer.BYTES + chunkLength;
  }

  /**
   * 编码一条负载。
   *
   * <p><b>编解码刻意不对称，空负载由上层拒绝</b>：本方法允许 {@code data} 为 {@code null} 或空（照常写出
   * 完整头部），而 {@link #decode} 对空 data 返回 {@code null}。契约是「空负载在上层就被拒绝」——
   * {@code DiskCacheStore#put} 会丢弃 {@code null}/空 payload，因此正常路径根本不会写出空负载；编码侧
   * 保持宽容只是为了不因入参抛异常，读写两侧的不对称兜底统一交由上层完成。
   *
   * @param sourceHash 原始（未改写）区块字节的指纹
   * @param positions  被伪装坐标；{@code null} 视为空
   * @param data       改写后的区块字节；{@code null} 视为空（但正常路径不应传空，见上）
   */
  public static byte[] encode(long sourceHash, int[] positions, byte[] data) {
    int[] coordinates = positions == null ? new int[0] : positions;
    byte[] chunk = data == null ? new byte[0] : data;
    ByteBuffer buffer = ByteBuffer.allocate(
        HEADER_SIZE + coordinates.length * Integer.BYTES + chunk.length);
    buffer.putLong(sourceHash);
    buffer.putInt(coordinates.length);
    for (int position : coordinates) {
      buffer.putInt(position);
    }
    buffer.put(chunk);
    return buffer.array();
  }

  /**
   * 解码一条负载。
   *
   * @param expectedSourceHash 期望的原始字节指纹；不符直接返回 {@code null}
   * @return 解码结果；截断、指纹不符、坐标数非法或区块字节为空时返回 {@code null}（按未命中处理）
   */
  public static Decoded decode(byte[] payload, long expectedSourceHash) {
    if (payload == null || payload.length < HEADER_SIZE) {
      return null;
    }
    try {
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      long sourceHash = buffer.getLong();
      if (sourceHash != expectedSourceHash) {
        return null;
      }
      int count = buffer.getInt();
      if (count < 0 || count > buffer.remaining() / Integer.BYTES) {
        return null;
      }
      int[] positions = new int[count];
      for (int index = 0; index < count; index++) {
        positions[index] = buffer.getInt();
      }
      byte[] data = new byte[buffer.remaining()];
      buffer.get(data);
      if (data.length == 0) {
        return null;
      }
      return new Decoded(sourceHash, positions, data);
    } catch (RuntimeException exception) {
      return null;
    }
  }
}