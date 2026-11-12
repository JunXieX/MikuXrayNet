package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.mikumc.mikuxraynet.antixray.RewriteGate.Decision;
import org.junit.jupiter.api.Test;

/**
 * 入口判定：短路顺序与语义（先玩家/世界/直通，再维度，最后队列容量）。
 */
class RewriteGateTest {

  @Test
  void proceedsOnlyWhenEverythingPasses() {
    assertEquals(Decision.PROCEED, RewriteGate.decide(true, true, false, true, true, true, true));
  }

  @Test
  void missingPlayerOrWorldIsRejectedBeforeAnythingElse() {
    assertEquals(Decision.SKIP_NO_PLAYER,
        RewriteGate.decide(false, false, false, false, false, false, false));
    assertEquals(Decision.SKIP_NO_WORLD,
        RewriteGate.decide(true, false, false, false, false, false, false));
  }

  @Test
  void bypassAndBlacklistComeBeforeDimensionAndQueue() {
    assertEquals(Decision.SKIP_BYPASS,
        RewriteGate.decide(true, true, true, false, false, false, false));
    assertEquals(Decision.SKIP_WORLD_NOT_ALLOWED,
        RewriteGate.decide(true, true, false, false, false, false, false));
  }

  @Test
  void dimensionIsCheckedBeforeQueueCapacity() {
    assertEquals(Decision.SKIP_DIMENSION,
        RewriteGate.decide(true, true, false, true, false, true, false));
    assertEquals(Decision.SKIP_DIMENSION,
        RewriteGate.decide(true, true, false, true, true, false, false));
  }

  @Test
  void queueFullIsLast() {
    assertEquals(Decision.SKIP_QUEUE_FULL,
        RewriteGate.decide(true, true, false, true, true, true, false));
  }
}
