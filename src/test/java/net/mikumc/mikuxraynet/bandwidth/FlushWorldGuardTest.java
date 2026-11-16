package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 冲刷前「整批丢弃」判定的守门用例 —— 这是生产事故的直接回归。
 *
 * <p><b>事故形态</b>（29 人 / 4 小时 dump）：丢弃 695 564 个原包，四源拆分显示<b>全部</b>来自「冲刷前比对」，
 * 而同期 {@code PlayerChangedWorldEvent} 计数为 0。逐条处置环写得很清楚：
 * 「缓冲世界 minecraft_lobby ≠ 当前世界 world」。也就是说没有任何玩家真的切维，却有几十万批被判成跨世界丢弃。
 *
 * <p><b>根因</b>：守卫拿<b>位置快照</b>当证据。快照由 Folia 实体调度器的每 tick 任务维护，而该任务会随实体
 * 被移出旧 world（切维 / 重生）退役、且退役不保证回调清理入口 —— 快照冻结在切换前那一刻，世界名与坐标都
 * 看起来正常。于是每一批在途变更都被判成「玩家已换世界」而整批丢掉，玩家侧表现就是假方块挖不掉、右键才消失。
 *
 * <p><b>结论</b>：只认世界索引（与打标同源、随事件前进）。索引未变时，快照说什么都不许丢。
 */
class FlushWorldGuardTest {

  @Test
  @DisplayName("索引未变：一律不丢（冻结快照不得成为丢弃依据）")
  void sameWorldIsNeverDiscarded() {
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush("minecraft_lobby", "minecraft_lobby"),
        "索引仍是同一个世界 → 不许丢弃（事故现场正是这一条被判成丢弃）");
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush("world", "world"));
  }

  @Test
  @DisplayName("索引已变：按真切维处理，允许丢弃")
  void changedWorldIsDiscarded() {
    assertTrue(BlockChangeMerger.shouldDiscardAtFlush("world", "world_nether"),
        "索引已随切维前进 → 与真切维同等处理（旧世界区块会整列重建）");
    assertTrue(BlockChangeMerger.shouldDiscardAtFlush("minecraft_lobby", "world"));
  }

  @Test
  @DisplayName("任一侧世界未知：不丢（取消即欠账）")
  void unknownWorldNeverDiscards() {
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush(null, "world"));
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush("world", null));
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush(null, null));
  }
}
