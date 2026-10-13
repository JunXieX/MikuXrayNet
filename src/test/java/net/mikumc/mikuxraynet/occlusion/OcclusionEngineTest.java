package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link OcclusionEngine} 的行为测试：判定语义、时间预算、超时丢弃、队列拒收、停机。
 *
 * <p>全部离线、无 Bukkit 依赖；工作线程为守护线程，各测试结束都会 {@code close()} 收尾。
 *
 * <p>遮挡数据源按世界分域（{@link OcclusionStores}），故各用例都走 {@code stores.of(世界名)} 取仓库，
 * 「世界隔离」本身由 {@link OcclusionStoresTest} 覆盖。
 */
class OcclusionEngineTest {

  private static final UUID PLAYER = UUID.randomUUID();
  /** 全部用例统一使用的世界名（与 {@code request(...)} 里写死的一致）。 */
  private static final String WORLD = "world";

  @Test
  void solidWorldIsFullyOccluded() {
    OcclusionStores stores = new OcclusionStores();
    // 覆盖 (0,0) 区块的 sectionY=0（世界方块 x,z∈[0,15]、y∈[0,15]）
    stores.of(WORLD).replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);
    RecordingSink sink = new RecordingSink(1);
    OcclusionEngine engine = engine(stores, 1, 16);
    try {
      engine.beginCycle(Long.MAX_VALUE);
      assertTrue(engine.submit(request(2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5}, sink)));
      assertTrue(sink.await(), "判定必须回投");
      assertTrue(sink.results.get(0), "实心世界内任意两点之间应判为完全遮挡");
      assertEquals(1L, engine.stats().computed.sum());
    } finally {
      engine.close();
    }
  }

  @Test
  void emptyWorldIsVisibleAndCountsUnknownSections() {
    OcclusionStores stores = new OcclusionStores();
    RecordingSink sink = new RecordingSink(1);
    OcclusionEngine engine = engine(stores, 1, 16);
    try {
      engine.beginCycle(Long.MAX_VALUE);
      engine.submit(request(2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5}, sink));
      assertTrue(sink.await());
      assertFalse(sink.results.get(0), "查不到遮挡数据时必须判为可见（fail-open）");
      assertTrue(engine.stats().unknownSection.sum() > 0,
          "空仓库下射线必然查不到条目，应计入「遮挡数据缺失」");
    } finally {
      engine.close();
    }
  }

  @Test
  void anyClearVertexMeansVisible() {
    OcclusionStores stores = new OcclusionStores();
    OcclusionStore store = stores.of(WORLD);
    // 只在 x==3 这一整片放遮挡（其余为空）
    BitmapOcclusion slab = new BitmapOcclusion();
    for (int z = 0; z < 16; z++) {
      for (int y = 0; y < 16; y++) {
        slab.setOccluding(3, y, z, true);
      }
    }
    store.replaceSection(0, 0, 0, slab);

    RecordingSink sink = new RecordingSink(1);
    OcclusionEngine engine = engine(stores, 1, 16);
    try {
      engine.beginCycle(Long.MAX_VALUE);
      // 顶点 1 沿 x 轴穿过薄片（被挡）；顶点 2 沿 z 轴前进、全程 x=0（不被挡）
      engine.submit(request(0.5, 0.5, 0.5,
          new double[] {10.5, 0.5, 0.5, 0.5, 0.5, 10.5}, sink));
      assertTrue(sink.await());
      assertFalse(sink.results.get(0), "任一顶点通畅就必须判为可见（保守语义）");
    } finally {
      engine.close();
    }
  }

  @Test
  void expiredRequestsAreDiscardedWithoutDelivery() {
    OcclusionStores stores = new OcclusionStores();
    RecordingSink sink = new RecordingSink(1);
    OcclusionEngine engine = engine(stores, 1, 16);
    try {
      engine.beginCycle(Long.MAX_VALUE);
      // 截止时刻已是过去 → 必须在开工前被丢弃
      engine.submit(new OcclusionEngine.Request(PLAYER, 1, WORLD, System.nanoTime() - 1_000_000L,
          2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5}, sink));
      // 再提交一条正常的，确保工作线程已跑起来（顺序保证：先处理过期那条）
      engine.submit(request(2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5}, sink));
      assertTrue(sink.await());
      assertEquals(1L, engine.stats().timeoutDiscarded.sum(), "过期任务必须被丢弃并计数");
      assertEquals(1L, engine.stats().computed.sum(), "丢弃的任务不算完成");
    } finally {
      engine.close();
    }
  }

  @Test
  void queueFullRejectsInsteadOfBlocking() {
    OcclusionStores stores = new OcclusionStores();
    OcclusionEngine engine = engine(stores, 1, 1);
    try {
      // 未开启周期 → 工作线程不会取任务 → 队列保持满
      RecordingSink sink = new RecordingSink(1);
      assertTrue(engine.submit(request(2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5}, sink)));
      assertFalse(engine.submit(request(2.5, 2.5, 2.5, new double[] {9.5, 2.5, 2.5}, sink)),
          "队列满必须直接拒收（调用方按可见处理），绝不阻塞提交方");
      assertEquals(1L, engine.stats().rejectedQueueFull.sum());
      assertEquals(1, engine.queueDepth());
    } finally {
      engine.close();
    }
  }

  @Test
  void zeroBudgetDefersTasksUntilNextCycleWithoutDropping() throws Exception {
    OcclusionStores stores = new OcclusionStores();
    RecordingSink sink = new RecordingSink(1);
    OcclusionEngine engine = engine(stores, 1, 16);
    try {
      engine.beginCycle(0L);
      assertTrue(engine.submit(request(2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5}, sink)));
      Thread.sleep(60L);
      assertEquals(0L, engine.stats().computed.sum(), "预算为 0 的周期不得处理任何任务");
      assertEquals(0L, engine.stats().timeoutDiscarded.sum(), "顺延 ≠ 丢弃");
      assertEquals(1, engine.queueDepth(), "任务必须留在队列里顺延");

      // 下一周期给出充足预算 → 同一任务被处理
      engine.beginCycle(Long.MAX_VALUE);
      assertTrue(sink.await(), "顺延的任务必须在下一周期被处理（不丢判定）");
      assertEquals(1L, engine.stats().computed.sum());
    } finally {
      engine.close();
    }
  }

  /**
   * 世界隔离：某一世界的实心位图<b>不得</b>影响另一个世界的同名区块。
   *
   * <p>这正是多世界服务器上「不分域会把可见实体误藏」的回归用例：若引擎忽略
   * {@link OcclusionEngine.Request#worldName()}，本用例的第二个断言会失败。
   */
  @Test
  void occlusionDataIsScopedPerWorld() {
    OcclusionStores stores = new OcclusionStores();
    // 只有主世界 (0,0) 是实心的；下界同名区块没有任何数据
    stores.of(WORLD).replaceSection(0, 0, 0, SolidOcclusion.INSTANCE);

    RecordingSink sink = new RecordingSink(2);
    OcclusionEngine engine = engine(stores, 1, 16);
    try {
      engine.beginCycle(Long.MAX_VALUE);
      engine.submit(new OcclusionEngine.Request(PLAYER, 1, WORLD, 0L,
          2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5}, sink));
      engine.submit(new OcclusionEngine.Request(PLAYER, 2, "world_nether", 0L,
          2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5}, sink));
      assertTrue(sink.await());
      assertTrue(sink.results.get(0), "主世界该段已建表为实心 → 应判完全遮挡");
      assertFalse(sink.results.get(1),
          "下界同名区块没有数据 → 必须按可见（fail-open），绝不能套用主世界的位图");
    } finally {
      engine.close();
    }
  }

  @Test
  void closeStopsAcceptingAndTerminatesWorkers() {
    OcclusionStores stores = new OcclusionStores();
    OcclusionEngine engine = engine(stores, 2, 16);
    engine.close();
    assertFalse(engine.submit(request(2.5, 2.5, 2.5, new double[] {8.5, 2.5, 2.5},
        new RecordingSink(1))), "停机后不得再接受任务");
    assertEquals(0, engine.queueDepth());
    engine.close(); // 可重复调用
  }

  private static OcclusionEngine engine(OcclusionStores stores, int threads, int queueCapacity) {
    // 最远射线长度给一个大值：本测试的用例都远在 128 格内，取上限只为让断言不受截断影响。
    return new OcclusionEngine(stores, new OcclusionStats(), threads, 0, 1_000_000.0D, queueCapacity);
  }

  private static OcclusionEngine.Request request(double eyeX, double eyeY, double eyeZ,
      double[] vertices, RecordingSink sink) {
    return new OcclusionEngine.Request(PLAYER, 1, WORLD, 0L, eyeX, eyeY, eyeZ, vertices, sink);
  }

  /** 记录回投结果并支持等待（{@code expected} 为期望的回投条数）。 */
  private static final class RecordingSink implements OcclusionEngine.ResultSink {
    private final CountDownLatch latch;
    private final List<Boolean> results = new CopyOnWriteArrayList<>();

    RecordingSink(int expected) {
      this.latch = new CountDownLatch(expected);
    }

    @Override
    public void accept(OcclusionEngine.Request request, boolean fullyOccluded) {
      results.add(fullyOccluded);
      latch.countDown();
    }

    boolean await() {
      try {
        return latch.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }
}
