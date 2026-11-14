package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 位宽入参的守门用例：{@code 64 / bitsPerEntry} 在 65..127 上先得到 0，随后「乘魔数」分支按 0 取模抛
 * {@link ArithmeticException}——信息量为零，堆栈看起来更像数据损坏而不是代码/配置错误。
 *
 * <p>合法位宽是 1..64（64 由 {@code adjustmentMask = -1L} 特化）；越界必须在构造处显式拒绝。
 */
class VarBitBufferWidthValidationTest {

  @Test
  void rejectsBitsPerEntryAboveSixtyFour() {
    assertThrows(IllegalArgumentException.class,
        () -> new SimpleVarBitBuffer(65, 16, new long[16]));
  }

  @Test
  void rejectsZeroBits() {
    assertThrows(IllegalArgumentException.class,
        () -> new SimpleVarBitBuffer(0, 16, new long[16]));
  }

  @Test
  void acceptsBoundaryWidths() {
    // 缓冲区长度必须由 calculateArraySize 给出（构造处会校验），这里只需确认边界位宽本身可用
    for (int bits : new int[] {1, 4, 8, 32, 64}) {
      new SimpleVarBitBuffer(bits, 16, new long[SimpleVarBitBuffer.calculateArraySize(bits, 16)]);
    }
  }
}
