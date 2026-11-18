package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import org.junit.jupiter.api.Test;

class SectionCoordsTest {

  @Test
  void packetAndBitmapCoordinatesHaveDifferentLayouts() {
    // 协议样本固定值：局部 (1, 2, 3) = 0x132；区块数组下标为 0x231。
    assertEquals(0x132, SectionCoords.pack(1, 2, 3));
    assertEquals(0x231, SectionOcclusion.packLocal(1, 2, 3));
    assertNotEquals(SectionOcclusion.packLocal(1, 2, 3), SectionCoords.pack(1, 2, 3));
    assertEquals(0xF1E, SectionCoords.pack(-1, -2, -15));
  }

  @Test
  void decodesPacketSamplesWithNegativeChunkAndHeight() {
    int[] out = new int[9];
    SectionCoords.unpack(-32, -64, -16, new short[] {0x132, 0xF00, 0x0FF}, out);
    assertArrayEquals(new int[] {-31, -62, -13, -17, -64, -16, -32, -49, -1}, out);
  }

  @Test
  void packingRoundTripsAllCoordinates() {
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
}
