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
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, locals);
    RevealedSet revealed = revealed();
    ProximityStats stats = new ProximityStats();
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);

    // 该玩家此前已显形过 (1,64,1)、(1,80,1)（会被本次变更同步摘除）；(3,70,3) 不在变更里，必须保留
    revealed.mark(PLAYER, key, 1, 64, 1);
    revealed.mark(PLAYER, key, 1, 80, 1);
    revealed.mark(PLAYER, key, 3, 70, 3);

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
    reference.recordChunk(WORLD, 0, 0, MIN_HEIGHT, locals);
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
    revealed.mark(PLAYER, key, 1, 64, 1);
    assertEquals(1, revealed.sizeFor(PLAYER, key));

    BlockChangeRevealListener.processChanges(WORLD, new int[] {1, 64, 1}, 1, index, revealed,
        new ProximityStats(), null);

    assertEquals(0, revealed.sizeFor(PLAYER, key), "索引未命中也要摘标记，杜绝孤儿标记");
  }

  /** 批量摘除：与逐坐标摘除等价，且重复坐标只计一次命中。 */
  @Test
  void removePositionsMatchesRepeatedRemovePosition() {
    int[] locals = {local(1, 10, 1), local(2, 10, 1), local(3, 10, 1), local(1, 11, 1)};
    ObfuscatedChunkIndex index = index();
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, locals);
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