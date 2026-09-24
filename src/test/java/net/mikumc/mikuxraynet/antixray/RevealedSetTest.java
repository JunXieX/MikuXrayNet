package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 已显形集合测试：按玩家隔离、按区块隔离、去重、注销、区块/玩家/世界清理、过期兜底与安全阀。
 *
 * <p>该结构取代旧索引里「按玩家各存一份被伪装坐标」的那半边：它<b>只记录实际发过显形包的坐标</b>，
 * 因此内存随「玩家身边确实显形的坐标数」增长，而不是随「玩家探索过的历史」增长。
 */
class RevealedSetTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final String WORLD = "world";
  private static final UUID PLAYER = UUID.randomUUID();
  private static final UUID OTHER_PLAYER = UUID.randomUUID();

  private final long[] clock = {0L};

  private RevealedSet revealed(int maxPerPlayer, long expireNanos) {
    return new RevealedSet(maxPerPlayer, expireNanos, () -> clock[0]);
  }

  private static ChunkKey chunk(int chunkX, int chunkZ) {
    return new ChunkKey(WORLD, chunkX, chunkZ);
  }

  @Test
  void marksAreScopedByPlayer() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);

    revealed.mark(PLAYER, key, 1, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1);

    assertTrue(revealed.contains(PLAYER, key, 1, 64, 1));
    assertEquals(2, revealed.sizeFor(PLAYER, key));
    assertFalse(revealed.contains(OTHER_PLAYER, key, 1, 64, 1), "不同玩家的标记互不可见");
    assertEquals(0, revealed.sizeFor(OTHER_PLAYER, key));
    assertEquals(1, revealed.markerCount(), "只有玩家 A 产生了一条「玩家 × 区块」标记");
  }

  @Test
  void marksAreScopedByChunk() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);

    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(1, 0), 17, 64, 1);

    assertEquals(1, revealed.sizeFor(PLAYER, chunk(0, 0)));
    assertEquals(1, revealed.sizeFor(PLAYER, chunk(1, 0)));
    assertFalse(revealed.contains(PLAYER, chunk(0, 0), 17, 64, 1), "同坐标在不同区块下互不影响");
  }

  @Test
  void duplicateMarkKeepsSingleEntry() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);

    revealed.mark(PLAYER, key, 7, 33, 7);
    revealed.mark(PLAYER, key, 7, 33, 7);

    assertEquals(1, revealed.sizeFor(PLAYER, key));
  }

  @Test
  void removePositionDropsOnlyThatMark() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);
    revealed.mark(PLAYER, key, 1, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1);
    revealed.mark(OTHER_PLAYER, key, 1, 64, 1);

    revealed.removePosition(WORLD, 1, 64, 1);

    assertFalse(revealed.contains(PLAYER, key, 1, 64, 1), "命中即注销");
    assertFalse(revealed.contains(OTHER_PLAYER, key, 1, 64, 1), "所有玩家同一坐标都要注销（维持子集不变式）");
    assertTrue(revealed.contains(PLAYER, key, 2, 64, 1), "同区块其它坐标不受影响");
    assertEquals(1, revealed.sizeFor(PLAYER, key));
  }

  @Test
  void clearChunkClearsEveryPlayerOfThatChunk() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(OTHER_PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(1, 0), 17, 64, 1);

    revealed.clearChunk(chunk(0, 0));

    assertEquals(0, revealed.sizeFor(PLAYER, chunk(0, 0)));
    assertEquals(0, revealed.sizeFor(OTHER_PLAYER, chunk(0, 0)));
    assertEquals(1, revealed.sizeFor(PLAYER, chunk(1, 0)), "其它区块不受影响");
    assertEquals(1, revealed.markerCount());
  }

  @Test
  void clearPlayerAndWorldAreScoped() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(OTHER_PLAYER, chunk(0, 0), 1, 64, 1);
    // 其它世界的存活标记必须记在「不会被 clearPlayer 清掉」的玩家上，否则下面的世界作用域断言无意义：
    // clearPlayer(PLAYER) 会按契约清掉 PLAYER 在所有世界的标记（含 world_nether）。
    revealed.mark(OTHER_PLAYER, new ChunkKey("world_nether", 0, 0), 1, 64, 1);

    revealed.clearPlayer(PLAYER);
    assertEquals(0, revealed.sizeFor(PLAYER, chunk(0, 0)), "只清理该玩家");
    assertEquals(1, revealed.sizeFor(OTHER_PLAYER, chunk(0, 0)));

    revealed.clearWorld(WORLD);
    assertEquals(0, revealed.sizeFor(OTHER_PLAYER, chunk(0, 0)), "世界卸载清理该世界");
    assertEquals(1, revealed.sizeFor(OTHER_PLAYER, new ChunkKey("world_nether", 0, 0)),
        "其它世界不受影响");

    revealed.clear();
    assertEquals(0, revealed.markerCount());
  }

  @Test
  void markersExpireUnlessTouched() {
    long window = 10 * SECOND_NANOS;
    RevealedSet revealed = revealed(1024, window);
    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(1, 0), 17, 64, 1);

    clock[0] = 9 * SECOND_NANOS;
    revealed.expire();
    assertEquals(2, revealed.markerCount(), "窗口内不应清理");

    // 扫描到区块 (1,0)（touch）→ 只有它的过期窗口重新计时
    revealed.touch(PLAYER, chunk(1, 0));
    clock[0] = 11 * SECOND_NANOS;
    revealed.expire();

    assertEquals(1, revealed.markerCount(), "未被扫描到的标记必须清理");
    assertEquals(0, revealed.sizeFor(PLAYER, chunk(0, 0)));
    assertEquals(1, revealed.sizeFor(PLAYER, chunk(1, 0)), "被扫描到的标记必须保留");

    clock[0] = 21 * SECOND_NANOS + 1;
    revealed.expire();
    assertEquals(0, revealed.markerCount(), "长期未被扫描同样必须清理");
  }

  @Test
  void clearChunkResetsPlayerTotalForSafetyValve() {
    RevealedSet revealed = revealed(2, 60 * SECOND_NANOS);

    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(0, 0), 2, 64, 1);
    revealed.mark(PLAYER, chunk(0, 0), 3, 64, 1);
    assertEquals(1L, revealed.droppedByCapacity(), "单玩家安全阀触发必须可诊断");
    assertEquals(2, revealed.sizeFor(PLAYER, chunk(0, 0)));

    revealed.clearChunk(chunk(0, 0));
    revealed.mark(PLAYER, chunk(0, 0), 3, 64, 1);
    assertEquals(1, revealed.sizeFor(PLAYER, chunk(0, 0)), "清理后计数归零，可以重新标记");
  }

  /**
   * 「累计登记」与「实时坐标数」是两个口径：前者只增不减（不随登出 / 过期 / 失效回落），
   * 后者随清理实时回落。真机上的「发送 108 但已显形 0」正是只看实时值导致的误读，
   * 因此这里锁死：标记确实在记录（累计登记恒 &gt;= 实时值），且登出只影响实时值。
   */
  @Test
  void registeredTotalIsCumulativeWhilePositionCountIsLive() {
    RevealedSet revealed = revealed(1024, 60 * SECOND_NANOS);
    ChunkKey key = chunk(0, 0);

    revealed.mark(PLAYER, key, 1, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1);
    revealed.mark(PLAYER, key, 2, 64, 1); // 重复标记不计入累计登记

    assertEquals(2L, revealed.registeredTotal(), "只统计真正新登记的坐标（去重不重复计数）");
    assertEquals(2, revealed.positionCount(), "实时坐标数与累计登记一致（尚未清理）");

    revealed.clearPlayer(PLAYER);
    assertEquals(0, revealed.positionCount(), "实时坐标数随登出归零");
    assertEquals(2L, revealed.registeredTotal(), "累计登记不与登出绑定：这才能证明标记确实在记录");
  }

  /** 安全阀放弃标记的坐标不算「登记成功」，且累计登记只增不减（区块失效后不回落）。 */
  @Test
  void registeredTotalIgnoresCapacityDropsAndChunkInvalidation() {
    RevealedSet revealed = revealed(1, 60 * SECOND_NANOS);

    revealed.mark(PLAYER, chunk(0, 0), 1, 64, 1);
    revealed.mark(PLAYER, chunk(0, 0), 2, 64, 1); // 触发安全阀，未登记

    assertEquals(1L, revealed.droppedByCapacity());
    assertEquals(1L, revealed.registeredTotal(), "被安全阀放弃的坐标不得计入累计登记");

    revealed.clearChunk(chunk(0, 0));
    assertEquals(0, revealed.positionCount(), "区块失效后实时坐标数归零");
    assertEquals(1L, revealed.registeredTotal(), "累计登记不因区块失效而回落");
  }

  /** 正常运营量级（49 个区块 × 约 404 个坐标）下安全阀不得触发。 */
  @Test
  void safetyValveNeverTriggersAtProductionScale() {
    RevealedSet revealed = revealed(524288, 300 * SECOND_NANOS);
    for (int chunk = 0; chunk < 49; chunk++) {
      for (int i = 0; i < 404; i++) {
        revealed.mark(PLAYER, chunk(chunk % 7, chunk / 7), i & 15, 64 + (i >> 8), i >> 4 & 15);
      }
    }

    assertEquals(0L, revealed.droppedByCapacity(), "正常量级下安全阀必须恒为 0");
    assertEquals(49, revealed.markerCount());
  }
}