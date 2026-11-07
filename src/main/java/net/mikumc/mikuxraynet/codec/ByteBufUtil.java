
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
      // 读前判界：32 位 VarInt 最多 5 字节。先读后判会多消费 1 字节，影响调用方对缓冲位置的判断。
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