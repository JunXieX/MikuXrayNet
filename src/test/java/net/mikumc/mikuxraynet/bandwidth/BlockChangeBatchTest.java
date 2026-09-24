package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 方块变更合并逻辑：邻域聚簇、超限冲刷、冲刷后缓冲清空与条目集合正确。 */
class BlockChangeBatchTest {

  @Test
  @DisplayName("曼哈顿距离 2 内的邻域更新被合并为一簇")
  void mergesNeighborsWithinManhattanRadius() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(64);
    batch.add(0, 64, 0, 1);
    batch.add(1, 64, 0, 2);
    batch.add(0, 64, 2, 3);

    List<List<Update<Integer>>> clusters = batch.drainClusters(2);

    assertEquals(1, clusters.size());
    assertEquals(3, clusters.get(0).size());
  }

  @Test
  @DisplayName("超出邻域半径的更新不会被合并")
  void keepsDistantUpdatesInSeparateClusters() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(64);
    batch.add(0, 64, 0, 1);
    batch.add(10, 64, 0, 2);

    List<List<Update<Integer>>> clusters = batch.drainClusters(2);

    assertEquals(2, clusters.size());
    assertEquals(1, clusters.get(0).size());
    assertEquals(1, clusters.get(1).size());
  }

  @Test
  @DisplayName("邻域关系可传递：链式相邻的更新归入同一簇")
  void clusteringIsTransitive() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(64);
    batch.add(0, 64, 0, 1);
    batch.add(2, 64, 0, 2);
    batch.add(4, 64, 0, 3);

    List<List<Update<Integer>>> clusters = batch.drainClusters(2);

    assertEquals(1, clusters.size());
    assertEquals(3, clusters.get(0).size());
  }

  @Test
  @DisplayName("达到条目上限时返回 true（调用方应立即冲刷）")
  void addSignalsOverflowAtEntryLimit() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(3);

    assertFalse(batch.add(0, 0, 0, 1));
    assertFalse(batch.add(1, 0, 0, 2));
    assertTrue(batch.add(2, 0, 0, 3));
  }

  @Test
  @DisplayName("冲刷后缓冲立即清空（超窗/超限冲刷语义）")
  void drainClearsPendingBuffer() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(8);
    batch.add(0, 0, 0, 1);
    batch.add(1, 0, 0, 2);
    assertEquals(2, batch.size());

    batch.drainClusters(2);

    assertTrue(batch.isEmpty());
    assertEquals(0, batch.size());
    assertTrue(batch.drainClusters(2).isEmpty());
  }

  @Test
  @DisplayName("合并后各簇的条目集合与写入内容完全一致")
  void clusterContentsMatchInsertedUpdates() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(64);
    batch.add(0, 64, 0, 11);
    batch.add(1, 64, 0, 22);
    batch.add(40, 64, 0, 33);

    List<List<Update<Integer>>> clusters = batch.drainClusters(2);

    Set<Update<Integer>> merged = new HashSet<>();
    for (List<Update<Integer>> cluster : clusters) {
      merged.addAll(cluster);
    }
    assertEquals(Set.of(
        new Update<>(0, 64, 0, 11),
        new Update<>(1, 64, 0, 22),
        new Update<>(40, 64, 0, 33)), merged);
  }

  @Test
  @DisplayName("半径 0 时只有坐标完全相同的更新才会同簇")
  void radiusZeroKeepsOnlyIdenticalPositions() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(64);
    batch.add(5, 64, 5, 1);
    batch.add(5, 64, 5, 2);
    batch.add(6, 64, 5, 3);

    List<List<Update<Integer>>> clusters = batch.drainClusters(0);

    assertEquals(2, clusters.size());
    assertEquals(2, clusters.get(0).size());
    assertEquals(1, clusters.get(1).size());
  }
}