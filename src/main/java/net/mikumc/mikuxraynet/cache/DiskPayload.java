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
   * 编码一条负载。
   *
   * @param sourceHash 原始（未改写）区块字节的指纹
   * @param positions  被伪装坐标；{@code null} 视为空
   * @param data       改写后的区块字节；{@code null} 视为空
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