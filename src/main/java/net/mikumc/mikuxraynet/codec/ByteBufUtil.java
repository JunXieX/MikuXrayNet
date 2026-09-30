
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * Netty ByteBuf 上的 Minecraft VarInt 读写工具。
 *
 * <p>关键约束：最多 5 字节（32 位），超出即抛 {@link IndexOutOfBoundsException}。
 */
public class ByteBufUtil {

  public static int readVarInt(ByteBuf buffer) {
    int out = 0;
    int bytes = 0;
    while (true) {
      // 读前判界：32 位 VarInt 最多 5 字节。旧实现先读第 6 字节再判 bytes>5，会多消费 1 字节才抛
      // （虽随后必然抛出、无实际后果，但多消费的字节会影响调用方对缓冲位置的判断）。这里在读取之前
      // 就拦下，异常类型与语义保持不变（仍是 IndexOutOfBoundsException，交由上层 fail-open 兜底）。
      if (bytes >= 5) {
        throw new IndexOutOfBoundsException("varint32 too long");
      }
      byte in = buffer.readByte();
      out |= (in & 0x7F) << bytes * 7;
      bytes++;
      if ((in & 0x80) == 0) {
        return out;
      }
    }
  }

  public static void writeVarInt(ByteBuf buffer, int value) {
    while ((value & -0x80) != 0) {
      buffer.writeByte(value & 0x7F | 0x80);
      value >>>= 7;
    }
    buffer.writeByte(value);
  }
}