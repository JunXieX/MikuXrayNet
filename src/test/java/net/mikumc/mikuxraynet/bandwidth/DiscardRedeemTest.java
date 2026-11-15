package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.mikumc.mikuxraynet.bandwidth.ThrottleStats.DiscardSource;
import org.junit.jupiter.api.Test;

/**
 * 「取消即欠账」判定表的守门用例。
 *
 * <p>原包一旦入缓冲就被取消，此后任何「直接丢弃」都等于永久丢失这些方块更新——客户端会一直停在旧方块上
 * （门只开一半、另一半还挡人），直到某次区块重发才自愈。丢弃唯一站得住的前提是「玩家真的换了世界，
 * 客户端会收到新世界的整列区块」。这张表就是那条前提的可执行形态。
 */
class DiscardRedeemTest {

  @Test
  void genuineDimensionChangeStillDiscards() {
    // 切维事件在 setServerLevel 之后同步触发：此刻缓冲坐标必属旧世界，客户端会重建 → 丢弃正确
    assertFalse(BlockChangeMerger.shouldRedeem(DiscardSource.DIMENSION_EVENT, "world", "world_nether"),
        "真切维必须照旧丢弃（否则旧世界坐标会被当成新世界坐标下发，出现鬼影方块）");
    assertFalse(BlockChangeMerger.shouldRedeem(DiscardSource.DIMENSION_EVENT, "world", "world"));
  }

  @Test
  void sameWorldIsRedeemedInsteadOfDiscarded() {
    assertTrue(BlockChangeMerger.shouldRedeem(DiscardSource.POSITION_SNAPSHOT, "world", "world"),
        "世界名没变说明坐标仍然有效，丢弃就是永久丢更新 → 必须补发");
    assertTrue(BlockChangeMerger.shouldRedeem(DiscardSource.FLUSH_SNAPSHOT, "world", "world"));
    assertTrue(BlockChangeMerger.shouldRedeem(DiscardSource.BLACKLIST, "world", "world"),
        "世界被热切进黑名单时，玩家并没有离开这个世界，坐标仍然有效");
  }

  @Test
  void differentWorldOutsideDimensionEventStillDiscards() {
    assertFalse(BlockChangeMerger.shouldRedeem(DiscardSource.POSITION_SNAPSHOT, "world",
        "world_nether"), "快照比对发现世界真变了：与真切维同等处理");
    assertFalse(BlockChangeMerger.shouldRedeem(DiscardSource.BLACKLIST, "world", "spawn"));
  }

  @Test
  void flushTimeGuardSharesTheSameTable() {
    // 冲刷前的第三道守卫此前是一段独立判断，与 discard() 各写一遍；现在两者都走这张表，
    // 因此这里显式钉住「同一世界不丢、不同世界才丢」，避免以后只改一处。
    assertTrue(BlockChangeMerger.shouldRedeem(DiscardSource.FLUSH_SNAPSHOT, "world", "world"),
        "冲刷前比对的缓冲世界与当前世界相同 → 不得丢弃");
    assertFalse(BlockChangeMerger.shouldRedeem(DiscardSource.FLUSH_SNAPSHOT, "world", "world_nether"),
        "冲刷前比对确认世界已变 → 与真切维同等处理");
    assertTrue(BlockChangeMerger.shouldRedeem(DiscardSource.FLUSH_SNAPSHOT, "world", null),
        "快照缺失 → 无法证明客户端会重建，按不丢处理（fail-open）");
  }

  @Test
  void unknownWorldOnEitherSideRedeems() {
    assertTrue(BlockChangeMerger.shouldRedeem(DiscardSource.POSITION_SNAPSHOT, null, "world"),
        "缓冲侧世界未知：无法证明客户端会重建 → 宁可多发一次");
    assertTrue(BlockChangeMerger.shouldRedeem(DiscardSource.POSITION_SNAPSHOT, "world", null),
        "快照缺失：同样按不丢处理");
    assertTrue(BlockChangeMerger.shouldRedeem(DiscardSource.POSITION_SNAPSHOT, null, null));
  }
}
