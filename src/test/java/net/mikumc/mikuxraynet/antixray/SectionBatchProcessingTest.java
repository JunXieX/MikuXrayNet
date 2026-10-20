package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 多方块变更按 section 聚合处理（{@link BlockChangeRevealListener#processChanges}）：
 * 一个跨 section 的坐标批次只产生「每 section 一次」的批量摘除与调度，且最终结果与逐坐标处理逐条等价，
 * 重复坐标被去重。单测直接喂绝对坐标并注入 {@link BlockChangeRevealListener.SectionDispatch} 计数桩。
 *
 * <p>也覆盖 {@link ObfuscatedChunkIndex#removePositions} 批量摘除与逐坐标摘除的等价性。
 */
class SectionBatchProcessingTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final String WORLD = "world";
  private static final int MIN_HEIGHT = -64;
  private static final UUID PLAYER = UUID.randomUUID();

  private final long[] clock = {0L};

  private ObfuscatedChunkIndex index() {
    return new ObfuscatedChunkIndex(1_000_000, 300 * SECOND_NANOS, () -> clock[0]);
  }

  private RevealedSet revealed() {
    return new RevealedSet(1_000_000, 300 * SECOND_NANOS, () -> clock[0]);
  }

  private static int local(int x, int y, int z) {
    return ((y - MIN_HEIGHT) << 8) | ((z & 15) << 4) | (x & 15);
  }

  /**
   * 跨 section 的一批变更：断言每 section 只调度一次、结果与逐坐标摘除等价、重复坐标去重、
   * 索引未命中的坐标也不留已显形标记（容忍「标记先行」）。
   */
  @Test
  void crossSectionChangesAggregatePerSectionAndMatchPerCoordinate() {
    // 伪装清单（同一区块 chunk 0,0；locals 必须严格升序）。
    // section A(y=64..79)：(1,64,1)、(2,64,1)；section B(y=80..95)：(1,80,1)；另加一个不被变更的 (3,70,3)
    int[] locals = {local(1, 64, 1), local(2, 64, 1), local(3, 70, 3), local(1, 80, 1)};
    ObfuscatedChunkIndex index = index();
    index.recordChunkWithRemovals(WORLD, 0, 0, MIN_HEIGHT, locals);
    RevealedSet revealed = revealed();
    ProximityStats stats = new ProximityStats();
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);

    // 该玩家此前已显形过 (1,64,1)、(1,80,1)（会被本次变更同步摘除）；(3,70,3) 不在变更里，必须保留
    revealed.markIfAbsent(PLAYER, key, 1, 64, 1);
    revealed.markIfAbsent(PLAYER, key, 1, 80, 1);
    revealed.markIfAbsent(PLAYER, key, 3, 70, 3);

    // 变更坐标（跨 section A/B）；其中 (9,64,9)、(5,80,5) 不在伪装清单里（测试「索引未命中」路径），
    // (1,64,1) 重复出现一次（测试去重）
    int[] changes = {
        1, 64, 1, 2, 64, 1, 9, 64, 9,   // section A
        1, 80, 1, 5, 80, 5,             // section B
        1, 64, 1,                       // 重复
    };

    List<int[]> dispatched = new ArrayList<>();
    BlockChangeRevealListener.processChanges(WORLD, changes, changes.length / 3, index, revealed,
        stats, (coords, count) -> dispatched.add(java.util.Arrays.copyOf(coords, count * 3)));

    assertEquals(2, dispatched.size(), "两个 section → 恰好调度两次（每 section 一次）");
    assertEquals(Set.of(pack(1, 64, 1), pack(2, 64, 1), pack(9, 64, 9)), packAll(dispatched.get(0)),
        "section A 的变更坐标（已去重）");
    assertEquals(Set.of(pack(1, 80, 1), pack(5, 80, 5)), packAll(dispatched.get(1)),
        "section B 的变更坐标");

    // 结果与「逐坐标 removePosition」等价
    ObfuscatedChunkIndex reference = index();
    reference.recordChunkWithRemovals(WORLD, 0, 0, MIN_HEIGHT, locals);
    reference.removePosition(WORLD, 1, 64, 1);
    reference.removePosition(WORLD, 2, 64, 1);
    reference.removePosition(WORLD, 1, 80, 1);
    assertArrayEquals(reference.entry(key).locals(), index.entry(key).locals(),
        "批量摘除的剩余伪装坐标必须与逐坐标摘除完全一致");
    assertEquals(3L, stats.unregistered.sum(), "命中并摘除的坐标数为 3");

    // 已显形标记：变更坐标（含索引未命中的）全部摘除，未变更的保留 → 无孤儿标记
    assertFalse(revealed.contains(PLAYER, key, 1, 64, 1));
    assertFalse(revealed.contains(PLAYER, key, 1, 80, 1));
    assertTrue(revealed.contains(PLAYER, key, 3, 70, 3), "未被变更的坐标标记必须保留");
    assertEquals(1, revealed.sizeFor(PLAYER, key), "计数与剩余伪装清单自洽（均为 1）");

    reference.removePosition(WORLD, 3, 70, 3);
    assertNull(reference.entry(key), "摘空后条目移除");
  }

  /** 索引未命中而标记仍在（孤儿标记）：processChanges 必须无条件把标记清掉，恢复自洽。 */
  @Test
  void orphanMarkIsClearedEvenWhenIndexMisses() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);

    // 制造孤儿：索引里没有该坐标（区块条目都不存在），但已显形标记存在
    revealed.markIfAbsent(PLAYER, key, 1, 64, 1);
    assertEquals(1, revealed.sizeFor(PLAYER, key));

    BlockChangeRevealListener.processChanges(WORLD, new int[] {1, 64, 1}, 1, index, revealed,
        new ProximityStats(), null);

    assertEquals(0, revealed.sizeFor(PLAYER, key), "索引未命中也要摘标记，杜绝孤儿标记");
  }

  /**
   * 复用去重 scratch 的跨调用正确性：同一线程连续两次处理同一批坐标，第二次不得把上一轮的 seen
   * 状态带进来（否则所有坐标被误判为「已见」而整批漏掉）。这锁死「代次戳」替换「每 section new
   * boolean[4096]」后语义不变。
   */
  @Test
  void repeatedCallsDoNotLeakSeenScratchState() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    // 含一个重复坐标（1,64,1）：应被去重为 2 个
    int[] changes = {1, 64, 1, 1, 64, 1, 2, 64, 1};

    List<int[]> first = new ArrayList<>();
    BlockChangeRevealListener.processChanges(WORLD, changes, 3, index, revealed, null,
        (coords, count) -> first.add(java.util.Arrays.copyOf(coords, count * 3)));
    List<int[]> second = new ArrayList<>();
    BlockChangeRevealListener.processChanges(WORLD, changes, 3, index, revealed, null,
        (coords, count) -> second.add(java.util.Arrays.copyOf(coords, count * 3)));

    assertEquals(1, first.size());
    assertEquals(2, first.get(0).length / 3, "同一调用内重复坐标被去重");
    assertEquals(1, second.size());
    assertEquals(2, second.get(0).length / 3, "跨调用不得泄漏上一轮的 seen 状态（否则会漏掉整批坐标）");
  }

  /** 批量摘除：与逐坐标摘除等价，且重复坐标只计一次命中。 */
  @Test
  void removePositionsMatchesRepeatedRemovePosition() {
    int[] locals = {local(1, 10, 1), local(2, 10, 1), local(3, 10, 1), local(1, 11, 1)};
    ObfuscatedChunkIndex index = index();
    index.recordChunkWithRemovals(WORLD, 0, 0, MIN_HEIGHT, locals);
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);

    // 含重复坐标：只应计一次命中
    int[] batch = {1, 10, 1, 2, 10, 1, 2, 10, 1, 9, 10, 9};
    assertEquals(2, index.removePositions(WORLD, batch, batch.length / 3), "重复坐标只计一次命中");
    assertEquals(2, index.positionCount());
    assertArrayEquals(new int[] {local(3, 10, 1), local(1, 11, 1)}, index.entry(key).locals());

    // 摘空最后一个坐标 → 整条条目移除
    assertEquals(2, index.removePositions(WORLD, new int[] {3, 10, 1, 1, 11, 1}, 2));
    assertEquals(0, index.positionCount());
    assertNull(index.entry(key));
  }

  /**
   * 解析缓冲改为线程本地复用后，{@code dispatch} 必须拿到<b>独立快照</b>：第一次派发的数组要留着，
   * 再处理第二批坐标，第一批内容不得被第二次解析覆盖（生产实现会把该数组交给实体调度器、在玩家线程
   * 延后执行——被覆盖就会把显形发到错误位置）。这里刻意不在桩里拷贝，直接依赖 dispatch 契约。
   */
  @Test
  void dispatchHandsOutIndependentSnapshotsAcrossReusedScratch() {
    List<int[]> dispatched = new ArrayList<>();
    BlockChangeRevealListener.processChanges(WORLD, new int[] {1, 64, 1, 2, 64, 1}, 2,
        index(), revealed(), new ProximityStats(),
        (coords, count) -> dispatched.add(coords));
    BlockChangeRevealListener.processChanges(WORLD, new int[] {5, 70, 5}, 1,
        index(), revealed(), new ProximityStats(),
        (coords, count) -> dispatched.add(coords));

    assertEquals(2, dispatched.size(), "两次调用各派发一次");
    assertArrayEquals(new int[] {1, 64, 1, 2, 64, 1}, dispatched.get(0),
        "第一批快照不得被后续解析覆盖（否则排队中的显形会发到错误位置）");
    assertArrayEquals(new int[] {5, 70, 5}, dispatched.get(1), "第二批内容必须正确");
  }

  /** 停用清理入口可重复调用，且清理后复用缓冲会重新创建、功能照常。 */
  @Test
  void threadLocalCleanupIsIdempotentAndReusable() {
    ProximityRevealer.clearThreadLocals();
    ProximityRevealer.clearThreadLocals(); // 幂等：重复调用不抛异常、不留失效状态

    // 清理后同一线程再次处理变更：SeenScratch / ParseScratch 会被重新创建，派发内容仍正确
    int[] dispatched = new int[3];
    int[] seen = {0};
    BlockChangeRevealListener.processChanges(WORLD, new int[] {1, 64, 1}, 1, index(), revealed(),
        new ProximityStats(), (coords, count) -> {
          System.arraycopy(coords, 0, dispatched, 0, count * 3);
          seen[0] = count;
        });
    assertEquals(1, seen[0], "清理后仍能正常派发");
    assertArrayEquals(new int[] {1, 64, 1}, dispatched, "清理后解析结果必须正确");
  }

  private static long pack(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
  }

  private static Set<Long> packAll(int[] coordinates) {
    Set<Long> result = new HashSet<>();
    for (int i = 0; i < coordinates.length; i += 3) {
      result.add(pack(coordinates[i], coordinates[i + 1], coordinates[i + 2]));
    }
    return result;
  }
}