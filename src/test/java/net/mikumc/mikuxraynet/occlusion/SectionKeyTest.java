package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

  /**
   * {@link SectionKey#columnKeys} 使用线程本地复用缓冲：两次调用之间不得残留上一次的键，且同线程
   * 重复调用复用同一数组。
   *
   * <p>背景：该方法被区块卸载路径调用（主线程 / Folia 区域线程），此前每次 {@code new long[256]}
   * 是 2 KB 的可避免短命分配。复用的前提是「每次调用都<b>完整覆盖</b> 256 个槽位」——本用例正是钉住
   * 这一点：先取 (5,-9) 的键并克隆保存，再用 (7,3) 调用，逐槽校验结果全部属于 (7,3)（不残留旧键），
   * 最后再取 (5,-9)，校验与第一次的克隆逐元素一致（确定性）。
   */
  @Test
  void columnKeysReusesThreadLocalBufferWithoutLeakingOldValues() {
    long[] firstSnapshot = SectionKey.columnKeys(5, -9).clone();
    long[] second = SectionKey.columnKeys(7, 3);

    // (1) 复用不残留：第二次调用的每个键都必须属于 (7,3)，且覆盖全部 256 个 sectionY
    Set<Integer> secondSectionYs = new HashSet<>();
    for (long key : second) {
      assertEquals(7, SectionKey.chunkX(key), "第二次调用不得残留上一次的 chunkX");
      assertEquals(3, SectionKey.chunkZ(key), "第二次调用不得残留上一次的 chunkZ");
      secondSectionYs.add(SectionKey.sectionY(key));
    }
    assertEquals(SectionKey.SECTION_Y_COUNT, secondSectionYs.size(),
        "第二次调用必须完整覆盖全部 sectionY（不得只覆盖一部分）");

    // (2) 确实是复用（同线程返回同一数组），而不是退化成每次分配
    assertSame(second, SectionKey.columnKeys(7, 3), "同线程重复调用应返回同一复用缓冲");

    // (3) 确定性：再次以 (5,-9) 调用，结果必须与第一次的克隆逐元素相同
    long[] again = SectionKey.columnKeys(5, -9);
    assertArrayEquals(firstSnapshot, again, "同一坐标重复调用结果必须一致（无残留、无顺序漂移）");
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
