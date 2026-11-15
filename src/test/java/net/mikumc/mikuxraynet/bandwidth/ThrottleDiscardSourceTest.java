package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 丢弃来源拆分的守门用例。
 *
 * <p>实机 dump 报「丢弃（切维/黑名单）628 049」，占全部方块变更包约四分之一，但四条入口共用一个计数时
 * 完全无法判断：是玩家真的在频繁切维（正常），还是守卫误判（缺陷——玩家没切世界却丢掉在途更新，
 * 客户端会停在旧方块上直到区块重发）。拆分的前提是「四源之和恒等于总数」，这条不变量必须锁住，
 * 否则面板会出现「总数 100，拆分加起来 90」这种自相矛盾。
 */
class ThrottleDiscardSourceTest {

  @Test
  void sourcesSumToTotal() {
    ThrottleStats stats = new ThrottleStats();
    stats.discarded(ThrottleStats.DiscardSource.DIMENSION_EVENT, 7L);
    stats.discarded(ThrottleStats.DiscardSource.POSITION_SNAPSHOT, 3L);
    stats.discarded(ThrottleStats.DiscardSource.BLACKLIST, 2L);
    stats.discarded(ThrottleStats.DiscardSource.FLUSH_SNAPSHOT, 1L);
    assertEquals(13L, stats.blockChangesDiscarded.sum());
    assertEquals(7L, stats.discardedByDimensionEvent.sum());
    assertEquals(3L, stats.discardedByPositionSnapshot.sum());
    assertEquals(2L, stats.discardedByBlacklist.sum());
    assertEquals(1L, stats.discardedByFlushSnapshot.sum());
  }

  @Test
  void nonPositiveCountsAreIgnored() {
    ThrottleStats stats = new ThrottleStats();
    stats.discarded(ThrottleStats.DiscardSource.DIMENSION_EVENT, 0L);
    stats.discarded(ThrottleStats.DiscardSource.DIMENSION_EVENT, -5L);
    stats.discarded(null, 5L);
    assertEquals(0L, stats.blockChangesDiscarded.sum(), "0 / 负数 / 空来源都不该改变总数");
    assertEquals(0L, stats.discardedByDimensionEvent.sum());
  }

  @Test
  void repeatedSameSourceAccumulates() {
    ThrottleStats stats = new ThrottleStats();
    for (int i = 0; i < 10; i++) {
      stats.discarded(ThrottleStats.DiscardSource.POSITION_SNAPSHOT, 2L);
    }
    assertEquals(20L, stats.discardedByPositionSnapshot.sum());
    assertEquals(20L, stats.blockChangesDiscarded.sum());
  }
}
