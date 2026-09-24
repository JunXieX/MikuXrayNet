package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        local(8, 73, 8)});  // 距离 9（必须排除）；注意仍落在区块 0 内，用于验证「含等号」而非半径

    List<ObfuscatedChunkIndex.Position> found = scan(index, revealed, PLAYER, 8.0D, 64, null);

    assertEquals(2, found.size());
    assertTrue(found.contains(new ObfuscatedChunkIndex.Position(8, 72, 8)), "距离恰好等于阈值必须命中");
    assertFalse(found.contains(new ObfuscatedChunkIndex.Position(8, 73, 8)), "超出阈值必须排除");
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

  /**
   * 连续 N 次显形后：该玩家该区块的已显形集合恰好 N 条，且此后每周期同一区块<b>重复发送 = 0</b>
   * （一个坐标都不再评估）。这正是「发送计数与已显形计数必须一一对应」的守门测试。
   */
  @Test
  void everyRevealedCoordinateIsCountedOnceAndNeverResent() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT,
        new int[] {local(1, 64, 1), local(2, 64, 1), local(3, 64, 1)});

    int sent = 0;
    for (int pass = 0; pass < 3; pass++) {
      List<ObfuscatedChunkIndex.Position> candidates = scan(index, revealed, PLAYER, 16.0D, 1, null);
      sent += candidates.size();
      revealAll(candidates, revealed, PLAYER);
    }

    assertEquals(3, sent, "三个坐标恰好各发送一次（额度 1 时需 3 次巡检）");
    assertEquals(3, revealed.sizeFor(PLAYER, key), "连续 N 次显形后该区块的已显形集合恰有 N 条");

    for (int pass = 0; pass < 5; pass++) {
      ProximityScanner.Tally tally = new ProximityScanner.Tally();
      assertTrue(scan(index, revealed, PLAYER, 16.0D, 8, tally).isEmpty(), "已整块显形不得重复发送");
      assertEquals(0, tally.positionsEvaluated, "重复发送 = 0：被跳过的区块一个坐标都不评估");
      assertEquals(1, tally.chunksSkipped);
    }
  }

  /**
   * 「整块跳过」必须能持续生效：区块在扫描半径内时，无论是否被跳过都要刷新已显形标记的活跃时间，
   * 否则该标记会在 expire-seconds 后被过期清掉，「整块跳过」周期性失效并导致同一批坐标重复发包。
   */
  @Test
  void skippedChunkKeepsItsMarkerFreshAgainstExpiry() {
    long window = 10 * SECOND_NANOS;
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1_000_000, 300 * SECOND_NANOS, () -> clock[0]);
    RevealedSet revealed = new RevealedSet(1_000_000, window, () -> clock[0]);
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(1, 64, 1), local(2, 64, 1)});

    // t=0 显形；t=9s（窗口内）再巡检一次 → 该区块已被整块跳过，但活跃时间必须同时被刷新
    revealAll(scan(index, revealed, PLAYER, 16.0D, 64, null), revealed, PLAYER);
    clock[0] = 9 * SECOND_NANOS;
    ProximityScanner.Tally tally = new ProximityScanner.Tally();
    assertTrue(scan(index, revealed, PLAYER, 16.0D, 64, tally).isEmpty());
    assertEquals(1, tally.chunksSkipped);

    // t=11s：距「最近一次刷新」仅 2 秒 → 标记不得被清（未刷新时距 t=0 已 11 秒，会被清掉）
    clock[0] = 11 * SECOND_NANOS;
    revealed.expire();
    assertEquals(1, revealed.markerCount(), "被跳过的区块也必须刷新活跃时间，否则整块跳过会周期性失效");
    assertEquals(2, revealed.sizeFor(PLAYER, ChunkKey.ofBlock(WORLD, 1, 1)));

    // t=21s：距最近一次刷新已超窗口 → 兜底过期仍必须生效
    clock[0] = 21 * SECOND_NANOS;
    revealed.expire();
    assertEquals(0, revealed.markerCount(), "长期未扫描到的标记仍必须被过期清理");
  }

  /**
   * 「已显形坐标 ⊆ 区块伪装清单」这一不变式必须在三处维护点都成立（否则整块跳过会误跳过）：
   * ① 服务端自行下发方块变更时的 removePosition（两个结构同步摘除）；
   * ② 区块被重新下发（writeBack：先登记新区块清单，再清掉该区块的已显形标记）；
   * ③ 区块卸载（清单失效 + 标记失效）。
   */
  @Test
  void revealedStaysSubsetOfChunkListingAtEveryMaintenancePoint() {
    ObfuscatedChunkIndex index = index();
    RevealedSet revealed = revealed();
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT,
        new int[] {local(1, 64, 1), local(2, 64, 1), local(3, 64, 1)});
    revealAll(scan(index, revealed, PLAYER, 16.0D, 64, null), revealed, PLAYER);
    assertEquals(index.entry(key).size(), revealed.sizeFor(PLAYER, key), "开始前必须整块显形");

    // 维护点①：BlockChangeRevealListener 的真实顺序（命中才摘除，两者同步）
    assertTrue(index.removePosition(WORLD, 1, 64, 1));
    revealed.removePosition(WORLD, 1, 64, 1);
    assertEquals(index.entry(key).size(), revealed.sizeFor(PLAYER, key));

    // 维护点②：ProtocolLibAsyncListener#writeBack 的真实顺序（先 recordChunk，再 clearChunk）
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(2, 64, 1), local(3, 64, 1)});
    revealed.clearChunk(key);
    assertTrue(revealed.sizeFor(PLAYER, key) <= index.entry(key).size(),
        "已显形坐标不得多于区块清单");
    assertEquals(0, revealed.sizeFor(PLAYER, key), "区块重发后必须清掉旧标记（客户端又拿回了伪装结果）");

    // 维护点③：ChunkUnloadEvent 的真实顺序（清单与标记一并失效）
    index.invalidateChunk(WORLD, 0, 0);
    revealed.clearChunk(key);
    assertNull(index.entry(key));
    assertEquals(0, revealed.sizeFor(PLAYER, key));
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