package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MiningSightTest {

  @Test
  void checksAnotherEyeFacingFaceWhenPreferredFaceIsBlocked() {
    // 只有顶部留有通路。先试西面失败后，必须查朝向眼睛的上表面，不受玩家朝向影响。
    assertTrue(MiningSight.anyHit(-2.0, 2.5, 0.5, 0, 0, 0, 6.5,
        (x, y, z) -> y > 0.99));
  }

  @Test
  void checksCornerWhenCenterAndNearestSamplesAreBlocked() {
    assertTrue(MiningSight.anyHit(-2.0, 0.5, 0.5, 0, 0, 0, 6.5,
        (x, y, z) -> y > 0.9 && z < 0.1));
  }

  @Test
  void wallAndOutOfRangeDoNotBecomeVisible() {
    AtomicInteger probes = new AtomicInteger();
    assertFalse(MiningSight.anyHit(-2.0, 2.5, -1.0, 0, 0, 0, 6.5,
        (x, y, z) -> { probes.incrementAndGet(); return false; }));
    assertTrue(probes.get() > 0 && probes.get() <= 18);
    assertFalse(MiningSight.anyHit(-15.0, 0.5, 0.5, 0, 0, 0, 6.5,
        (x, y, z) -> { throw new AssertionError("半径外不得读世界"); }));
  }

  @Test
  void pointsStayInsideTargetInNegativeCoordinates() {
    assertTrue(MiningSight.anyHit(-18.5, -61.0, -32.5, -16, -64, -33, 6.5,
        (x, y, z) -> x > -16 && x < -15 && y > -64 && y < -63 && z > -33 && z < -32));
  }
}
