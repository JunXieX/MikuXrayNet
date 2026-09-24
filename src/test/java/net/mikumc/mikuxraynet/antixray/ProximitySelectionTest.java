package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 显形选择逻辑测试：把巡检任务的「局部扫描取候选 → 发送 → 标记已显形」三步按真实顺序复现，
 * 固定住三条契约——同一坐标只选一次、超出距离不选、单次巡检上限生效且剩余留到下次。
 *
 * <p>显形结构已拆为「按区块共享的伪装清单」+「按玩家的已显形集合」，因此本测试相应改为
 * 用两个结构复现同一契约（旧测试直接构造「单玩家一份索引」的写法已不再适用）。
 */
class ProximitySelectionTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final String WORLD = "world";
  private static final double DISTANCE = 8.0D;
  private static final int MIN_HEIGHT = -64;

  private final long[] clock = {0L};
  private final ObfuscatedChunkIndex index =
      new ObfuscatedChunkIndex(1_000_000, 60 * TimeUnit.SECONDS.toNanos(1), () -> clock[0]);
  private final RevealedSet revealed =
      new RevealedSet(1_000_000, 60 * TimeUnit.SECONDS.toNanos(1), () -> clock[0]);

  private static int local(int x, int y, int z) {
    return ((y - MIN_HEIGHT) << 8) | ((z & 15) << 4) | (x & 15);
  }

  /** 模拟一次巡检：挑选候选坐标，全部视为发送成功并标记已显形，返回本次挑选结果。 */
  private List<ObfuscatedChunkIndex.Position> revealOnce(int budget) {
    List<ObfuscatedChunkIndex.Position> selected = scan(DISTANCE, budget);
    for (ObfuscatedChunkIndex.Position position : selected) {
      ChunkKey key = ChunkKey.ofBlock(WORLD, position.x(), position.z());
      assertFalse(revealed.contains(PLAYER, key, position.x(), position.y(), position.z()),
          "选中前不得已是已显形状态：" + position);
      revealed.mark(PLAYER, key, position.x(), position.y(), position.z());
    }
    return selected;
  }

  private List<ObfuscatedChunkIndex.Position> scan(double distance, int limit) {
    return ProximityScanner.candidates(index, revealed, PLAYER, WORLD, 0, 64, 0, distance, limit, null);
  }

  /** 把若干绝对方块坐标登记为「曾被伪装」（按所在区块分组，同一区块共用一条条目）。 */
  private void record(int... coordinates) {
    Map<ChunkKey, List<Integer>> grouped = new LinkedHashMap<>();
    for (int i = 0; i < coordinates.length; i += 3) {
      int x = coordinates[i];
      int y = coordinates[i + 1];
      int z = coordinates[i + 2];
      grouped.computeIfAbsent(ChunkKey.ofBlock(WORLD, x, z), ignored -> new ArrayList<>())
          .add(local(x, y, z));
    }
    for (Map.Entry<ChunkKey, List<Integer>> entry : grouped.entrySet()) {
      int[] locals = new int[entry.getValue().size()];
      for (int i = 0; i < locals.length; i++) {
        locals[i] = entry.getValue().get(i);
      }
      index.recordChunk(WORLD, entry.getKey().chunkX(), entry.getKey().chunkZ(), MIN_HEIGHT, locals);
    }
  }

  @Test
  void eachCoordinateIsSelectedOnlyOnce() {
    record(1, 64, 1);
    record(-2, 65, 3);

    assertEquals(2, revealOnce(8).size());
    assertTrue(revealOnce(8).isEmpty(), "已显形的坐标不得再次被选出");
    assertEquals(2, revealed.markerCount());
  }

  @Test
  void coordinatesBeyondDistanceAreNotSelected() {
    record(0, 64, 0, 9, 64, 0, 0, 72, 0);

    List<ObfuscatedChunkIndex.Position> selected = revealOnce(8);

    assertEquals(2, selected.size(), "只选距离不超过 8 格的坐标");
    assertTrue(selected.contains(new ObfuscatedChunkIndex.Position(0, 72, 0)), "距离恰好 8 必须命中");
    assertFalse(selected.contains(new ObfuscatedChunkIndex.Position(9, 64, 0)), "距离 9 不得命中");

    // 未选中的坐标仍留在索引里，距离放宽后仍可被选出
    assertEquals(List.of(new ObfuscatedChunkIndex.Position(9, 64, 0)), scan(9.0D, 64),
        "未显形的坐标必须在放宽距离后可被选出");
  }

  @Test
  void budgetLimitsSinglePassAndKeepsTheRest() {
    record(0, 64, 0, 1, 64, 0, 2, 64, 0);

    List<ObfuscatedChunkIndex.Position> first = revealOnce(2);
    assertEquals(2, first.size(), "单次巡检的发包上限必须生效");
    assertEquals(new ObfuscatedChunkIndex.Position(0, 64, 0), first.get(0), "先发送最近的坐标");
    assertEquals(new ObfuscatedChunkIndex.Position(1, 64, 0), first.get(1));

    assertEquals(List.of(new ObfuscatedChunkIndex.Position(2, 64, 0)), revealOnce(2),
        "超出发包上限的坐标留到下次巡检");
    assertTrue(revealOnce(2).isEmpty(), "全部显形后不再有候选（整块跳过）");
  }

  /**
   * 玩家登出只清「已显形标记」，不动按区块共享的伪装清单：该玩家下次上线若重新收到区块，
   * 这些坐标会再次被显形（客户端此时又看到伪装结果，重发才是正确的）。
   */
  @Test
  void clearedPlayerIsTreatedAsNotYetRevealed() {
    record(1, 64, 1);

    assertEquals(1, revealOnce(8).size());

    revealed.clearPlayer(PLAYER);

    assertEquals(1, revealOnce(8).size(), "清掉已显形标记后必须能重新显形（对应区块重发）");
  }
}