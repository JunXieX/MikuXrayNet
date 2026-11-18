package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ByteBufUtilTest {

  @Test
  void acceptsFullSignedRange() {
    ByteBuf buf = Unpooled.buffer();
    try {
      for (int value : new int[] {0, 127, 128, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
        ByteBufUtil.writeVarInt(buf, value);
        assertEquals(value, ByteBufUtil.readVarInt(buf));
      }
    } finally {
      buf.release();
    }
  }

  @Test
  void rejectsHighBitsWithoutConsumingNextField() {
    ByteBuf buf = Unpooled.wrappedBuffer(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10, 0x2a});
    try {
      assertThrows(IndexOutOfBoundsException.class, () -> ByteBufUtil.readVarInt(buf));
      assertEquals(0x2a, buf.readUnsignedByte());
    } finally {
      buf.release();
    }
  }
}
