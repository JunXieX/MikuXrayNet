package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 方块变更合并逻辑：邻域聚簇、超限冲刷、冲刷后缓冲清空与条目集合正确。 */
class BlockChangeBatchTest {

  /**
   * 复用已构造的 {@link Update} 实例（热路径省掉同一坐标的第二次等值分配）必须与四参重载语义一致：
   * 上限判定与簇分组完全相同。
   */
  @Test
  @DisplayName("add(Update) 重载与四参 add 语义一致")
  void addUpdateOverloadMatchesFourArgSemantics() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(2);

    assertFalse(batch.add(new Update<>(0, 64, 0, 1)), "未达上限应返回 false");
    assertTrue(batch.add(new Update<>(1, 64, 0, 2)), "达到条目上限应返回 true");

    List<List<Update<Integer>>> clusters = batch.drainClusters(2);
    assertEquals(1, clusters.size(), "曼哈顿距离 1 内的两条变更应合并为一簇");
    assertEquals(2, clusters.get(0).size());
    assertTrue(batch.isEmpty(), "冲刷后缓冲必须清空");
  }

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

  /** B2 支撑：跨世界时必须能「不聚簇地」丢掉整批待发条目（既不发送、也不再构造簇）。 */
  @Test
  @DisplayName("clear() 丢弃全部待发条目（不聚簇、不留残余）")
  void clearDropsAllPendingEntries() {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(8);
    batch.add(0, 64, 0, 1);
    batch.add(1, 64, 0, 2);
    assertEquals(2, batch.size());

    batch.clear();

    assertTrue(batch.isEmpty(), "clear() 后缓冲必须为空");
    assertEquals(0, batch.size());
    assertTrue(batch.drainClusters(2).isEmpty(), "clear() 后不得再聚出任何簇");
    assertFalse(batch.add(0, 64, 0, 3), "clear() 之后仍可继续接收新条目");
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

  @Test
  @DisplayName("立即放行半径：半径内存在变更即命中（近身变更不走合并）")
  void immediateRadiusDetectsNearbyUpdate() {
    List<Update<Integer>> mixed = List.of(new Update<>(40, 64, 0, 1), new Update<>(1, 65, 1, 2));

    assertTrue(BlockChangeBatch.anyWithinRadius(mixed, 0, 64, 0, 8),
        "只要有一条落在半径内（玩家脚下/身旁的挖掘更新）就必须命中");
    assertFalse(BlockChangeBatch.anyWithinRadius(List.of(new Update<>(40, 64, 0, 1)), 0, 64, 0, 8),
        "全部在半径外时照常合并");
  }

  @Test
  @DisplayName("立即放行半径：含边界命中、半径 0 关闭、空/null 安全")
  void immediateRadiusBoundaryAndDisabled() {
    List<Update<Integer>> boundary = List.of(new Update<>(8, 64, 0, 1));

    assertTrue(BlockChangeBatch.anyWithinRadius(boundary, 0, 64, 0, 8), "恰好 8 格（含边界）必须命中");
    assertFalse(BlockChangeBatch.anyWithinRadius(boundary, 0, 64, 0, 7), "超出半径不得命中");
    assertFalse(BlockChangeBatch.anyWithinRadius(boundary, 0, 64, 0, 0), "半径 0 表示关闭立即放行");
    assertFalse(BlockChangeBatch.anyWithinRadius(List.of(), 0, 64, 0, 8), "空列表不得命中");
    assertFalse(BlockChangeBatch.anyWithinRadius(null, 0, 64, 0, 8), "null 不得命中");
  }

  /**
   * 参照实现：不做任何剪枝、只枚举 {@code i < j} 的无序对，用同一套曼哈顿判据求连通分量。
   *
   * <p>{@link BlockChangeBatch#drainClusters} 现在同样只访问每个无序边一次（{@code j <= i} 短路），
   * 因此两者的簇集合、簇顺序（按簇内最小插入下标）与簇内成员顺序必须逐项相同——这条差分就是
   * 「比较次数减半没有改变语义」的证据。
   */
  private static List<List<Update<Integer>>> referenceClusters(List<Update<Integer>> updates, int radius) {
    int distance = Math.max(0, radius);
    int size = updates.size();
    int[] parent = new int[size];
    for (int i = 0; i < size; i++) {
      parent[i] = i;
    }
    for (int i = 0; i < size; i++) {
      Update<Integer> left = updates.get(i);
      for (int j = i + 1; j < size; j++) {
        Update<Integer> right = updates.get(j);
        int manhattan = Math.abs(left.x() - right.x()) + Math.abs(left.y() - right.y())
            + Math.abs(left.z() - right.z());
        if (manhattan <= distance) {
          union(parent, i, j);
        }
      }
    }
    Map<Integer, List<Update<Integer>>> byRoot = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      byRoot.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(updates.get(i));
    }
    return new ArrayList<>(byRoot.values());
  }

  private static int find(int[] parent, int value) {
    int root = value;
    while (parent[root] != root) {
      root = parent[root];
    }
    while (parent[value] != root) {
      int next = parent[value];
      parent[value] = root;
      value = next;
    }
    return root;
  }

  private static void union(int[] parent, int a, int b) {
    int rootA = find(parent, a);
    int rootB = find(parent, b);
    if (rootA != rootB) {
      parent[rootB] = rootA;
    }
  }

  /** 与生产调用同形：先把全部条目 add 进新缓冲，再一次 drainClusters。 */
  private static List<List<Update<Integer>>> drain(List<Update<Integer>> updates, int radius) {
    BlockChangeBatch<Integer> batch = new BlockChangeBatch<>(Math.max(1, updates.size()));
    for (Update<Integer> update : updates) {
      batch.add(update);
    }
    return batch.drainClusters(radius);
  }

  @Test
  @DisplayName("差分：随机邻域聚簇与暴力参照实现的簇集合/簇序/成员序完全一致")
  void matchesReferenceImplementationOnRandomNeighborhoods() {
    Random random = new Random(20261119L);
    for (int round = 0; round < 60; round++) {
      int count = 1 + random.nextInt(36);
      int spread = 1 + random.nextInt(8);
      List<Update<Integer>> updates = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        updates.add(new Update<>(
            random.nextInt(spread * 2 + 1) - spread,
            64 + random.nextInt(spread * 2 + 1) - spread,
            -200 + random.nextInt(spread * 2 + 1) - spread,
            i));
      }
      for (int radius = 0; radius <= 4; radius++) {
        assertEquals(referenceClusters(updates, radius), drain(updates, radius),
            "第 " + round + " 组、半径 " + radius + " 的聚簇必须与参照实现一致");
      }
    }
  }

  @Test
  @DisplayName("负坐标：跨 -1/0 边界的邻域仍与参照实现一致")
  void negativeCoordinatesClusterLikeReference() {
    List<Update<Integer>> updates = List.of(
        new Update<>(-1, 63, -1, 1),
        new Update<>(-2, 64, -2, 2),
        new Update<>(0, 64, 0, 3),
        new Update<>(-17, 40, -33, 4),
        new Update<>(1, 64, 1, 5));
    for (int radius = 0; radius <= 4; radius++) {
      assertEquals(referenceClusters(updates, radius), drain(updates, radius),
          "半径 " + radius + " 下负坐标聚簇必须与参照一致（floorDiv 分桶不得漏对）");
    }
  }

  @Test
  @DisplayName("重复坐标：半径 0 也必须同簇，且不与远处坐标混簇")
  void duplicateCoordinatesStayTogether() {
    List<Update<Integer>> updates = List.of(
        new Update<>(5, 70, 5, 1),
        new Update<>(5, 70, 5, 2),
        new Update<>(5, 70, 5, 3),
        new Update<>(9, 70, 5, 4));
    List<List<Update<Integer>>> atZero = drain(updates, 0);
    assertEquals(2, atZero.size(), "半径 0：三条同坐标同簇，9 单独成簇");
    assertEquals(3, atZero.get(0).size());
    assertEquals(1, atZero.get(1).size());
    for (int radius = 0; radius <= 4; radius++) {
      assertEquals(referenceClusters(updates, radius), drain(updates, radius),
          "半径 " + radius + " 下重复坐标聚簇必须与参照一致");
    }
  }

  @Test
  @DisplayName("链式连通：跨网格边界的传递簇仍是一簇（不因只判一次而断链）")
  void chainAcrossBucketBoundariesStaysOneCluster() {
    List<Update<Integer>> positive = new ArrayList<>();
    for (int x = 0; x <= 8; x += 2) {
      positive.add(new Update<>(x, 64, 0, x));
    }
    // 半径 2 → 网格边长 3：0/2 在格 0、4 在格 1、6/8 在格 2；单对相邻 ≤ 2，首尾相距 8
    List<List<Update<Integer>>> clusters = drain(positive, 2);
    assertEquals(1, clusters.size(), "链式相邻必须传递成一簇");
    assertEquals(5, clusters.get(0).size());
    assertEquals(referenceClusters(positive, 2), clusters, "正半轴链式聚簇必须与参照一致");

    List<Update<Integer>> negative = new ArrayList<>();
    for (int x = -8; x <= 0; x += 2) {
      negative.add(new Update<>(x, 64, 0, x));
    }
    List<List<Update<Integer>>> negativeClusters = drain(negative, 2);
    assertEquals(1, negativeClusters.size(), "负半轴链式相邻同样必须传递成一簇");
    assertEquals(referenceClusters(negative, 2), negativeClusters, "负半轴链式聚簇必须与参照一致");
  }

  @Test
  @DisplayName("坐标上限：世界边界量级坐标仍与参照一致，且差值不溢出 int")
  void worldBorderScaleCoordinatesClusterLikeReference() {
    List<Update<Integer>> updates = List.of(
        new Update<>(-30_000_000, -64, -30_000_000, 1),
        new Update<>(-29_999_999, -64, -30_000_000, 2),
        new Update<>(30_000_000, 319, 30_000_000, 3),
        new Update<>(30_000_001, 319, 30_000_001, 4));
    for (int radius = 0; radius <= 2; radius++) {
      assertEquals(referenceClusters(updates, radius), drain(updates, radius),
          "半径 " + radius + " 下世界边界量级坐标必须与参照一致");
    }
    assertEquals(60_000_001, Math.abs(30_000_001 - (-30_000_000)),
        "世界边界量级的坐标差仍在 int 内（三轴差之和最大 1.8e8，远低于 2^31-1）");
  }

  @Test
  @DisplayName("封存后锁外聚簇：旧对象结果不受新窗口并发写入影响（正常场景回归）")
  void sealedBatchStaysStableWhileNewWindowAccumulates() throws Exception {
    List<Update<Integer>> sealedUpdates = new ArrayList<>();
    BlockChangeBatch<Integer> sealed = new BlockChangeBatch<>(4096);
    for (int i = 0; i < 512; i++) {
      Update<Integer> update = new Update<>(i, 64, -i, i);
      sealedUpdates.add(update);
      sealed.add(update);
    }
    List<List<Update<Integer>>> expected = referenceClusters(sealedUpdates, 2);

    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread newWindow = new Thread(() -> {
      try {
        for (int round = 0; round < 40; round++) {
          BlockChangeBatch<Integer> fresh = new BlockChangeBatch<>(8192);
          for (int i = 0; i < 512; i++) {
            fresh.add(new Update<>(i, 64, i, i));
          }
          fresh.drainClusters(2);
        }
      } catch (Throwable throwable) {
        failure.set(throwable);
      }
    }, "mikuxraynet-new-window-churn");
    newWindow.start();
    List<List<Update<Integer>>> actual;
    try {
      // 锁外聚簇：与「新窗口」的并发写入/聚簇同时进行
      actual = sealed.drainClusters(2);
    } finally {
      newWindow.join(10_000L);
    }
    assertNull(failure.get(), "「新窗口」并发聚簇自身不得抛出");
    assertEquals(expected, actual, "旧对象已封存：新窗口的并发写入不得改变它的簇结构");
    assertTrue(sealed.isEmpty(), "聚簇完成后旧对象必须清空");
  }
}