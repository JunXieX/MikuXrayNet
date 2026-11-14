package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 开掘去重的槽位守门：单槽实现只记住「最后一格」，玩家在两个格之间交替点击时每次都会命中"换了坐标"，
 * 去重形同失效——相邻两格各触发一次即时显形，发包量变成两倍（且第二格本来就在显形窗口里）。
 *
 * <p>取 4 槽后，交替的两格都必须被记住；窗口外的旧记号仍可被最旧槽覆盖（不引入无界增长）。
 */
class DigDedupSlotsTest {

  private static final int SLOTS = 4;

  private static long packed(long x, long y, long z) {
    return (y << 8) | (z << 4) | x;
  }

  @Test
  void alternatingTargetsDoNotBypassDedup() {
    long[] marks = new long[SLOTS * 2];
    long first = packed(1, 2, 3);
    long second = packed(5, 6, 7);
    assertTrue(ProximityRevealer.digDedupAllows(marks, first, 100L));
    assertTrue(ProximityRevealer.digDedupAllows(marks, second, 101L));
    assertFalse(ProximityRevealer.digDedupAllows(marks, first, 102L),
        "窗口内同一格再次触发必须被挡（单槽时这里会放行）");
    assertFalse(ProximityRevealer.digDedupAllows(marks, second, 103L));
  }

  @Test
  void staleMarksAreOverwrittenNotAccumulated() {
    long[] marks = new long[SLOTS * 2];
    for (int i = 0; i < 32; i++) {
      assertTrue(ProximityRevealer.digDedupAllows(marks, packed(i, 0, 0), 1000L + i * 100L),
          "相隔很远的坐标不该被历史记号挡住");
    }
    assertTrue(ProximityRevealer.digDedupAllows(marks, packed(1, 0, 0), 9000L));
  }
}
