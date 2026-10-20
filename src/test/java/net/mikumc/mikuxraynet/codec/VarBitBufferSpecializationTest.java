package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link SimpleVarBitBuffer} 的「无除法定位」特化守门。
 *
 * <p>{@code get}/{@code set} 把每格一次整数除法换成「2 的幂走移位、非 2 的幂走乘魔数取高 32 位」。
 * 本测试用<b>朴素除法</b>作为参照实现，对全部合法位宽逐格对拍，确保特化没有改变任何取值。
 *
 * <p><b>为什么必须有这个测试</b>：该优化首版曾把「右移位数」也塞进同一个 {@code >>>}，
 * 而 {@code index * magic} 已是 long，对 2 的幂再右移 32+log2 位会把结果抹成 0——
 * 位宽 8 期望 255 实得 7。那类错误在正常跑图时表现为「区块内容错乱」，极难定位。
 */
class VarBitBufferSpecializationTest {

  /** 全部需要覆盖的位宽：4..8 是间接调色板的合法档位，1 是 FAWE 兼容档。 */
  private static final int[] BIT_WIDTHS = {1, 4, 5, 6, 7, 8};
  private static final int SIZE = 4096;

  @Test
  void getMatchesNaiveDivisionForEveryBitWidth() {
    for (int bits : BIT_WIDTHS) {
      long[] storage = new long[SimpleVarBitBuffer.calculateArraySize(bits, SIZE)];
      SimpleVarBitBuffer buffer = new SimpleVarBitBuffer(bits, SIZE, storage);
      int mask = (1 << bits) - 1;
      int entriesPerLong = 64 / bits;

      // 先用朴素除法写入一份确定性数据（同时验证 set 的定位）
      for (int i = 0; i < SIZE; i++) {
        int value = (i * 37 + 11) & mask;
        buffer.set(i, value);
        int position = i / entriesPerLong;
        int offset = (i - position * entriesPerLong) * bits;
        long expected = storage[position] >>> offset & mask;
        assertEquals(expected, value,
            "位宽 " + bits + " 第 " + i + " 格：set 后按朴素定位读出的值必须等于写入值");
      }

      // 再逐格对拍 get
      for (int i = 0; i < SIZE; i++) {
        int position = i / entriesPerLong;
        int offset = (i - position * entriesPerLong) * bits;
        int expected = (int) (storage[position] >>> offset & mask);
        assertEquals(expected, buffer.get(i),
            "位宽 " + bits + " 第 " + i + " 格：特化 get 必须与朴素除法结果相同");
      }
    }
  }

  @Test
  void setOverwritesPreviousValueInPlace() {
    for (int bits : BIT_WIDTHS) {
      long[] storage = new long[SimpleVarBitBuffer.calculateArraySize(bits, SIZE)];
      SimpleVarBitBuffer buffer = new SimpleVarBitBuffer(bits, SIZE, storage);
      int mask = (1 << bits) - 1;
      // 先把全部格子填成最大值，再逐个改成 0：验证写入不会污染相邻格子的位
      for (int i = 0; i < SIZE; i++) {
        buffer.set(i, mask);
      }
      for (int i = 0; i < SIZE; i++) {
        buffer.set(i, 0);
        assertEquals(0, buffer.get(i), "位宽 " + bits + " 第 " + i + " 格应被改写为 0");
      }
      for (int i = 0; i < SIZE; i++) {
        assertEquals(0, buffer.get(i), "位宽 " + bits + " 第 " + i + " 格不得被后续写入污染");
      }
    }
  }

  @Test
  void boundaryIndicesAreLocatedCorrectly() {
    // 每个 long 的首尾项最容易因「乘法取高位」的边界误差算错位置
    for (int bits : BIT_WIDTHS) {
      long[] storage = new long[SimpleVarBitBuffer.calculateArraySize(bits, SIZE)];
      SimpleVarBitBuffer buffer = new SimpleVarBitBuffer(bits, SIZE, storage);
      int mask = (1 << bits) - 1;
      int entriesPerLong = 64 / bits;
      for (int i = 0; i < SIZE; i += entriesPerLong) {
        // 该 long 的第一项与最后一项
        buffer.set(i, mask);
        assertEquals(mask, buffer.get(i),
            "位宽 " + bits + " 第 " + i + " 格（long 首项）定位错误");
        int last = i + entriesPerLong - 1;
        if (last < SIZE) {
          buffer.set(last, mask);
          assertEquals(mask, buffer.get(last),
              "位宽 " + bits + " 第 " + last + " 格（long 末项）定位错误");
        }
      }
    }
  }

  @Test
  void fullWidthMaskHandlesSixtyFourBits() {
    // bitsPerEntry = 64 时 adjustmentMask 必须是 -1L（(1L << 64) 会退化成 1L）
    long[] storage = new long[SimpleVarBitBuffer.calculateArraySize(64, 2)];
    SimpleVarBitBuffer buffer = new SimpleVarBitBuffer(64, 2, storage);
    buffer.set(0, -1);
    assertEquals(-1, buffer.get(0), "64 位满掩码必须能完整往返");
  }

  @Test
  void illegalBitWidthIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> SimpleVarBitBuffer.calculateArraySize(65, SIZE), "位宽 > 64 必须显式拒绝");
    assertThrows(IllegalArgumentException.class,
        () -> SimpleVarBitBuffer.calculateArraySize(-1, SIZE), "负位宽必须显式拒绝");
  }
}
