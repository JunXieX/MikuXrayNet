package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiscardRedeemTest {

  @Test
  void genuinelyOldWorldCanBeDiscarded() {
    assertFalse(BlockChangeMerger.shouldRedeem("world", "world_nether"));
  }

  @Test
  void alreadyBufferedDestinationUpdatesStayDeliverable() {
    assertTrue(BlockChangeMerger.shouldRedeem("world_nether", "world_nether"),
        "先到达的新世界更新不能被随后到达的切维监听再丢掉");
  }

  @Test
  void unknownWorldIsNotEvidenceOfATransition() {
    assertTrue(BlockChangeMerger.shouldRedeem(null, "world"));
    assertTrue(BlockChangeMerger.shouldRedeem("world", null));
    assertTrue(BlockChangeMerger.shouldRedeem(null, null));
  }

  @Test
  void delayedFlushDoesNotAllowAnotherPacketToExceedTheWindow() {
    assertTrue(BlockChangeMerger.fitsPendingWindow(0, 256, 256));
    assertTrue(BlockChangeMerger.fitsPendingWindow(255, 1, 256));
    assertFalse(BlockChangeMerger.fitsPendingWindow(256, 1, 256));
    assertFalse(BlockChangeMerger.fitsPendingWindow(255, 2, 256));
    assertFalse(BlockChangeMerger.fitsPendingWindow(0, 257, 256));
    assertFalse(BlockChangeMerger.fitsPendingWindow(Integer.MAX_VALUE, 2, Integer.MAX_VALUE));
  }
}
