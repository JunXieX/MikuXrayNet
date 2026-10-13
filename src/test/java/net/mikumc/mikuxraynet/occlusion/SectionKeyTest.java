package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link SectionKey} 位布局与打包取值的锁定测试：key 布局一旦被改动，遮挡数据会整体错位，
 * 因此这里把「负坐标 / 边界 / 回绕」逐项钉死。
 */
class SectionKeyTest {

  @Test
  void roundTripAcrossNegativeAndBoundaryCoordinates() {
    int[] horizontals = {
        0, 1, -1, 15, -16, SectionKey.HORIZONTAL_MIN, SectionKey.HORIZONTAL_MAX,
        SectionKey.HORIZONTAL_MIN + 1, SectionKey.HORIZONTAL_MAX - 1
    };
    int[] sectionYs = {0, 1, -1, SectionKey.SECTION_Y_MIN, SectionKey.SECTION_Y_MAX};

    for (int chunkX : horizontals) {
      for (int sectionY : sectionYs) {
        for (int chunkZ : horizontals) {
          long key = SectionKey.packSection(chunkX, sectionY, chunkZ);
          assertEquals(chunkX, SectionKey.chunkX(key), "chunkX 往返");
          assertEquals(sectionY, SectionKey.sectionY(key), "sectionY 往返");
          assertEquals(chunkZ, SectionKey.chunkZ(key), "chunkZ 往返");
        }
      }
    }
  }

  @Test
  void distinctSectionsDoNotCollideOnBoundaryValues() {
    // 边界值最容易因符号扩展/掩码写错而撞键，逐对确认互不相等
    Set<Long> keys = new HashSet<>();
    int[] values = {SectionKey.HORIZONTAL_MIN, SectionKey.HORIZONTAL_MIN + 1, -1, 0, 1,
        SectionKey.HORIZONTAL_MAX - 1, SectionKey.HORIZONTAL_MAX};
    for (int x : values) {
      for (int z : values) {
        assertTrue(keys.add(SectionKey.packSection(x, 0, z)), "水平键不重复：" + x + "," + z);
      }
    }
    for (int y = SectionKey.SECTION_Y_MIN; y <= SectionKey.SECTION_Y_MAX; y++) {
      assertTrue(keys.add(SectionKey.packSection(3, y, 7)), "sectionY 键不重复：" + y);
    }
  }

  @Test
  void packBlockMatchesPackingItsSection() {
    int[][] samples = {
        {0, 0, 0}, {15, 15, 15}, {-1, -1, -1}, {-16, -16, -16}, {31, -33, 47}, {-64, 320, 1023}
    };
    for (int[] sample : samples) {
      int x = sample[0];
      int y = sample[1];
      int z = sample[2];
      assertEquals(SectionKey.packSection(x >> 4, y >> 4, z >> 4), SectionKey.packBlock(x, y, z),
          "packBlock 与 packSection(x>>4,y>>4,z>>4) 一致：" + x + "," + y + "," + z);
    }
  }

  @Test
  void columnKeysCoverEverySectionYExactlyOnceWithoutDuplicates() {
    long[] keys = SectionKey.columnKeys(5, -9);
    assertEquals(SectionKey.SECTION_Y_COUNT, keys.length);
    Set<Long> uniqueKeys = new HashSet<>();
    Set<Integer> coveredSectionY = new HashSet<>();
    for (long key : keys) {
      assertTrue(uniqueKeys.add(key), "列键不重复");
      // 逐 +1 的过程中绝不能进位污染水平字段
      assertEquals(5, SectionKey.chunkX(key), "chunkX 必须保持不变");
      assertEquals(-9, SectionKey.chunkZ(key), "chunkZ 必须保持不变");
      coveredSectionY.add(SectionKey.sectionY(key));
    }
    // 并集恰好覆盖 8 位可表示的全部 sectionY（含两端）
    assertEquals(SectionKey.SECTION_Y_COUNT, coveredSectionY.size());
    for (int y = SectionKey.SECTION_Y_MIN; y <= SectionKey.SECTION_Y_MAX; y++) {
      assertTrue(coveredSectionY.contains(y), "缺少 sectionY=" + y);
    }
    assertEquals(SectionKey.SECTION_Y_COUNT, uniqueKeys.size());
  }

  @Test
  void outOfRangeCoordinatesAreRejectedRatherThanWrapped() {
    assertThrows(IllegalArgumentException.class,
        () -> SectionKey.packSection(SectionKey.HORIZONTAL_MAX + 1, 0, 0));
    assertThrows(IllegalArgumentException.class,
        () -> SectionKey.packSection(0, SectionKey.SECTION_Y_MIN - 1, 0));
    assertThrows(IllegalArgumentException.class,
        () -> SectionKey.packSection(0, 0, SectionKey.HORIZONTAL_MIN - 1));
    // 相邻但不越界的一对必须不同（若不检查会静默回绕成同一个键）
    assertNotEquals(SectionKey.packSection(SectionKey.HORIZONTAL_MAX, 0, 0),
        SectionKey.packSection(SectionKey.HORIZONTAL_MIN, 0, 0));
  }
}
