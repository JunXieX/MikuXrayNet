package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import org.junit.jupiter.api.Test;

/**
 * 段内坐标编解码：协议顺序、互逆性、与位图编码同源。
 *
 * <p>顺序断言不是形式主义：x/y 对调曾在监听器与合并器里各出现一次，且因「两处对调互相抵消」而
 * 长期不可见；只有把布局钉死在一个测试里，改错时才必然失败。
 */
class SectionCoordsTest {

  @Test
  void packingMatchesTheProtocolLayout() {
    // (y << 8) | (z << 4) | x
    assertEquals((2 << 8) | (3 << 4) | 1, SectionCoords.pack(1, 2, 3));
    assertEquals(0x0FFF, SectionCoords.pack(15, 15, 15));
    assertEquals(0, SectionCoords.pack(0, 0, 0));
  }

  @Test
  void packUnpackRoundTrips() {
    for (int y = 0; y < 16; y++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          int packed = SectionCoords.pack(x, y, z);
          assertEquals(x, SectionCoords.x(packed));
          assertEquals(y, SectionCoords.y(packed));
          assertEquals(z, SectionCoords.z(packed));
        }
      }
    }
  }

  @Test
  void negativeShortIsTreatedAsUnsigned() {
    // 高位为 1 的 packed short（y=15）在符号扩展下不能被当成负坐标
    short packed = (short) SectionCoords.pack(3, 15, 5);
    int raw = SectionCoords.unsigned(packed);
    assertEquals(3, SectionCoords.x(raw));
    assertEquals(15, SectionCoords.y(raw));
    assertEquals(5, SectionCoords.z(raw));
  }

  @Test
  void sameEncodingAsBitmapLocalIndex() {
    for (int y = 0; y < 16; y++) {
      assertEquals(SectionOcclusion.packLocal(7, y, 9), SectionCoords.pack(7, y, 9));
    }
  }
}
