package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * 遮挡位图与三态表示（{@code null} / {@link SolidOcclusion} / {@link BitmapOcclusion}）的等价性测试。
 */
class BitmapOcclusionTest {

  @Test
  void setThenGetRoundTripsEveryCoordinate() {
    BitmapOcclusion bitmap = new BitmapOcclusion();
    // 分散在多个 long 上、含首尾坐标，确认索引换算（>>>6 与移位）无错位
    int[][] marked = {{0, 0, 0}, {15, 15, 15}, {0, 15, 0}, {1, 2, 3}, {15, 0, 15}, {7, 7, 7}};
    for (int[] position : marked) {
      bitmap.setOccluding(position[0], position[1], position[2], true);
    }
    for (int x = 0; x < 16; x++) {
      for (int y = 0; y < 16; y++) {
        for (int z = 0; z < 16; z++) {
          boolean expected = false;
          for (int[] position : marked) {
            if (position[0] == x && position[1] == y && position[2] == z) {
              expected = true;
              break;
            }
          }
          assertTrue(bitmap.isOccludingLocal(x, y, z) == expected,
              "坐标 (" + x + "," + y + "," + z + ") 应" + (expected ? "遮挡" : "不遮挡"));
        }
      }
    }
    // 清除后再查
    bitmap.setOccluding(7, 7, 7, false);
    assertFalse(bitmap.isOccludingLocal(7, 7, 7));
  }

  @Test
  void fromStatesReturnsThreeStates() {
    int[] states = new int[BitmapOcclusion.BLOCK_COUNT];

    // 全部不遮挡 → 共享的空快路径（表示「已知该段确实不遮挡」，与「没有该段的数据」区分开）
    assertSame(EmptyOcclusion.INSTANCE, BitmapOcclusion.fromStates(states, state -> false));

    // 全部遮挡 → 共享单例 SolidOcclusion
    assertSame(SolidOcclusion.INSTANCE, BitmapOcclusion.fromStates(states, state -> true));

    // 混合 → 逐格位图，且位点与谓词结论一致
    IntPredicate predicate = state -> state == 7;
    states[SectionOcclusion.packLocal(3, 4, 5)] = 7;
    SectionOcclusion built = BitmapOcclusion.fromStates(states, predicate);
    assertNotNull(built);
    assertFalse(built.isSolid());
    assertFalse(built.isEmpty());
    assertTrue(built.isOccludingLocal(3, 4, 5));
    assertFalse(built.isOccludingLocal(3, 4, 6));
  }

  @Test
  void isOccludingMasksOutOfRangeCoordinatesToLocalRange() {
    BitmapOcclusion bitmap = new BitmapOcclusion();
    bitmap.setOccluding(1, 1, 1, true);
    // 全局坐标 (-15, 17, 33) → 局部 (1, 1, 1)
    assertTrue(bitmap.isOccluding(-15, 17, 33));
    // 与全局坐标在同一 section 内的其它格子不遮挡
    assertFalse(bitmap.isOccluding(-14, 17, 33));
  }

  @Test
  void solidBitmapAgreesWithSolidOcclusion() {
    BitmapOcclusion solidBitmap = BitmapOcclusion.solid();
    for (int x = 0; x < 16; x += 5) {
      for (int y = 0; y < 16; y += 5) {
        for (int z = 0; z < 16; z += 5) {
          assertTrue(solidBitmap.isOccludingLocal(x, y, z));
          assertTrue(SolidOcclusion.INSTANCE.isOccludingLocal(x, y, z));
        }
      }
    }
    assertFalse(solidBitmap.isEmpty());
    assertTrue(SolidOcclusion.INSTANCE.isSolid());
  }

  @Test
  void emptyBitmapReportsEmpty() {
    assertTrue(new BitmapOcclusion().isEmpty());
    BitmapOcclusion bitmap = new BitmapOcclusion();
    bitmap.setOccluding(0, 0, 0, true);
    assertFalse(bitmap.isEmpty());
    bitmap.setOccluding(0, 0, 0, false);
    assertTrue(bitmap.isEmpty());
  }
}
