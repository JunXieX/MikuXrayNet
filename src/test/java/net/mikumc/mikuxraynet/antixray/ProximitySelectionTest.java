package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 显形选择逻辑测试：把巡检任务的「取候选 → 发送 → 注销」三步按真实顺序复现，
 * 固定住三条契约——同一坐标只选一次、超出距离不选、单次巡检上限生效且剩余留到下次。
 */
class ProximitySelectionTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final String WORLD = "world";
  private static final double DISTANCE = 8.0D;

  private final long[] clock = {0L};
  private final RevealedBlockIndex index =
      new RevealedBlockIndex(128, 128, 60 * TimeUnit.SECONDS.toNanos(1), () -> clock[0]);

  /** 模拟一次巡检：挑选候选坐标，全部视为发送成功并注销，返回本次挑选结果。 */
  private List<RevealedBlockIndex.Position> revealOnce(int budget) {
    List<RevealedBlockIndex.Position> selected =
        index.candidates(PLAYER, WORLD, 0, 64, 0, DISTANCE, budget);
    for (RevealedBlockIndex.Position position : selected) {
      assertTrue(index.unregister(PLAYER, WORLD, position.x(), position.y(), position.z()),
          "发送成功后必须能注销该坐标：" + position);
    }
    return selected;
  }

  @Test
  void eachCoordinateIsSelectedOnlyOnce() {
    index.record(PLAYER, WORLD, 1, 64, 1);
    index.record(PLAYER, WORLD, -2, 65, 3);

    assertEquals(2, revealOnce(8).size());
    assertTrue(revealOnce(8).isEmpty(), "已显形的坐标不得再次被选出");
    assertEquals(0, index.size());
  }

  @Test
  void coordinatesBeyondDistanceAreNotSelected() {
    index.record(PLAYER, WORLD, 0, 64, 0);
    index.record(PLAYER, WORLD, 9, 64, 0);
    index.record(PLAYER, WORLD, 0, 72, 0);

    List<RevealedBlockIndex.Position> selected = revealOnce(8);

    assertEquals(2, selected.size(), "只选距离不超过 8 格的坐标");
    assertTrue(selected.contains(new RevealedBlockIndex.Position(0, 72, 0)), "距离恰好 8 必须命中");
    assertFalse(selected.contains(new RevealedBlockIndex.Position(9, 64, 0)), "距离 9 不得命中");
    assertTrue(index.unregister(PLAYER, WORLD, 9, 64, 0), "未选中的坐标仍留在索引里");
  }

  @Test
  void budgetLimitsSinglePassAndKeepsTheRest() {
    index.record(PLAYER, WORLD, 0, 64, 0);
    index.record(PLAYER, WORLD, 1, 64, 0);
    index.record(PLAYER, WORLD, 2, 64, 0);

    List<RevealedBlockIndex.Position> first = revealOnce(2);
    assertEquals(2, first.size(), "单次巡检的发包上限必须生效");
    assertEquals(new RevealedBlockIndex.Position(0, 64, 0), first.get(0), "先发送最近的坐标");
    assertEquals(new RevealedBlockIndex.Position(1, 64, 0), first.get(1));

    assertEquals(List.of(new RevealedBlockIndex.Position(2, 64, 0)), revealOnce(2),
        "超出发包上限的坐标留到下次巡检");
    assertTrue(revealOnce(2).isEmpty(), "全部显形后不再有候选");
  }

  @Test
  void clearedPlayerStopsBeingSelected() {
    index.record(PLAYER, WORLD, 0, 64, 0);

    index.clearPlayer(PLAYER);

    assertTrue(revealOnce(8).isEmpty(), "玩家登出清理后不得再选出其坐标");
  }
}