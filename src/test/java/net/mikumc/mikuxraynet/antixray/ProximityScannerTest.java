package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 局部扫描测试：只遍历玩家身边 {@code ceil(distance/16)} 个区块半径内的区块、整块已显形则跳过、
 * 玩家之间互不影响、候选按距离由近到远排序并受上限约束。
 *
 * <p>计数用 {@link ProximityScanner.Tally} 断言「扫描了几个区块、跳过了几个区块、评估了几个坐标」，
 * 这正是重构后「每周期候选评估量」的可观测口径。
 */
class ProximityScannerTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final String WORLD = "world";
  private static final UUID PLAYER = UUID.randomUUID();
  private static final UUID OTHER_PLAYER = UUID.randomUUID();
  private static final int MIN_HEIGHT = -64;

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

  /** 把候选坐标全部标记为「已显形」（模拟一次成功的发包）。 */
  private static void revealAll(List<ObfuscatedChunkIndex.Position> candidates, RevealedSet revealed,
      UUID player) {
    for (ObfuscatedChunkIndex.Position position : candidates) {
      revealed.mark(player, ChunkKey.ofBlock(WORLD, position.x(), position.z()),
          position.x(), position.y(), position.z());
    }
  }

  private static List<ObfuscatedChunkIndex.Position> scan(ObfuscatedChunkIndex index,
      RevealedSet revealed, UUID player, double distance, int limit, ProximityScanner.Tally tally) {
    return ProximityScanner.candidates(index, revealed, player, WORLD, 8, 64, 8, distance, limit, tally);
  }

  // ------------------------------------------------------------------ 局部扫描边界

  @Test
  void scansOnlyChunksWithinRadius() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    // 玩家在 (8,64,8)（区块 0,0），distance=16 → 半径 1（只应有区块 -1..1）
    index.recordChunk(WORLD, 1, 0, MIN_HEIGHT, new int[] {local(16, 64, 8)});
    index.recordChunk(WORLD, 2, 0, MIN_HEIGHT, new int[] {local(32, 64, 8)});

    ProximityScanner.Tally tally = new ProximityScanner.Tally();
    List<ObfuscatedChunkIndex.Position> found = scan(index, revealed, PLAYER, 16.0D, 64, tally);

    assertEquals(List.of(new ObfuscatedChunkIndex.Position(16, 64, 8)), found,
        "只应取到半径 1 内、距离不超过 16 的坐标");
    assertEquals(1, tally.chunksScanned, "半径外的区块一个都不该被访问");
    assertEquals(1, tally.positionsEvaluated, "半径外区块的坐标一个都不该被评估");
  }

  @Test
  void radiusGrowsWithConfiguredDistance() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    // 48 格 → 半径 3：区块 3 在半径内、区块 4 在半径外
    index.recordChunk(WORLD, 3, 0, MIN_HEIGHT, new int[] {local(48, 64, 8)});
    index.recordChunk(WORLD, 4, 0, MIN_HEIGHT, new int[] {local(64, 64, 8)});

    ProximityScanner.Tally tally = new ProximityScanner.Tally();
    List<ObfuscatedChunkIndex.Position> found = scan(index, revealed, PLAYER, 48.0D, 64, tally);

    assertEquals(List.of(new ObfuscatedChunkIndex.Position(48, 64, 8)), found);
    assertEquals(1, tally.chunksScanned);
    assertEquals(1, tally.positionsEvaluated);
  }

  @Test
  void distanceBoundaryIsInclusive() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {
        local(8, 64, 8),   // 距离 0
        local(8, 72, 8),   // 距离恰好 8（含等于必须命中）
        local(9, 64, 8)});  // 距离 9（必须排除）

    List<ObfuscatedChunkIndex.Position> found = scan(index, revealed, PLAYER, 8.0D, 64, null);

    assertEquals(2, found.size());
    assertTrue(found.contains(new ObfuscatedChunkIndex.Position(8, 72, 8)), "距离恰好等于阈值必须命中");
    assertFalse(found.contains(new ObfuscatedChunkIndex.Position(9, 64, 8)), "超出阈值必须排除");
  }

  // ------------------------------------------------------------------ 整块跳过

  @Test
  void wholeRevealedChunkIsSkipped() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(1, 64, 1), local(2, 64, 1)});

    ProximityScanner.Tally first = new ProximityScanner.Tally();
    List<ObfuscatedChunkIndex.Position> candidates = scan(index, revealed, PLAYER, 16.0D, 64, first);
    assertEquals(2, candidates.size());
    assertEquals(1, first.chunksScanned);
    assertEquals(0, first.chunksSkipped);

    revealAll(candidates, revealed, PLAYER);

    ProximityScanner.Tally second = new ProximityScanner.Tally();
    assertTrue(scan(index, revealed, PLAYER, 16.0D, 64, second).isEmpty());
    assertEquals(1, second.chunksSkipped, "整块已全部显形 → 该区块被跳过");
    assertEquals(0, second.chunksScanned);
    assertEquals(0, second.positionsEvaluated, "被跳过的区块一个坐标都不评估");
  }

  @Test
  void partiallyRevealedChunkStillYieldsTheRest() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT,
        new int[] {local(1, 64, 1), local(2, 64, 1), local(3, 64, 1)});

    List<ObfuscatedChunkIndex.Position> first = scan(index, revealed, PLAYER, 16.0D, 2, null);
    assertEquals(2, first.size(), "单次上限必须生效");
    assertEquals(new ObfuscatedChunkIndex.Position(3, 64, 1), first.get(0), "先返回最近的坐标");
    revealAll(first, revealed, PLAYER);

    ProximityScanner.Tally tally = new ProximityScanner.Tally();
    assertEquals(List.of(new ObfuscatedChunkIndex.Position(1, 64, 1)),
        scan(index, revealed, PLAYER, 16.0D, 2, tally), "剩余坐标留到下次");
    assertEquals(1, tally.chunksScanned, "尚未整块显形，不能跳过该区块");
  }

  // ------------------------------------------------------------------ 玩家隔离

  @Test
  void revealedCoordinatesAreNotSharedAcrossPlayers() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(1, 64, 1), local(2, 64, 1)});

    revealAll(scan(index, revealed, PLAYER, 16.0D, 64, null), revealed, PLAYER);

    assertTrue(scan(index, revealed, PLAYER, 16.0D, 64, null).isEmpty(),
        "自己显形过的坐标不得再次选出");
    assertEquals(2, scan(index, revealed, OTHER_PLAYER, 16.0D, 64, null).size(),
        "玩家 A 显形过的坐标不影响玩家 B（各自独立）");
  }

  @Test
  void nearestCoordinatesComeFirst() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT,
        new int[] {local(8, 64, 11), local(8, 64, 9), local(8, 64, 12)});

    List<ObfuscatedChunkIndex.Position> found = scan(index, revealed, PLAYER, 16.0D, 64, null);

    assertEquals(new ObfuscatedChunkIndex.Position(8, 64, 9), found.get(0), "先返回最近的坐标");
    assertEquals(new ObfuscatedChunkIndex.Position(8, 64, 11), found.get(1));
    assertEquals(new ObfuscatedChunkIndex.Position(8, 64, 12), found.get(2));
  }
}