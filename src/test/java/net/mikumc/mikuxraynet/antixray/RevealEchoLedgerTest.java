package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 「自己发出的显形包」账本测试：一次性消费、按玩家/世界/坐标精确匹配、TTL 过期、
 * 容量有界（最坏只少抑制一次，不影响正确性）。
 *
 * <p>它守的语义是：<b>显形回显不推进磁盘缓存代次，服务端真实方块变更必须推进</b>——
 * 因此「命中即移除」（同坐标随后的真实变更照常被判为真实变更）与「超期即不命中」
 * 都必须被钉住。
 */
class RevealEchoLedgerTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private final long[] clock = {0L};

  private RevealEchoLedger ledger(int maxEntries, long ttlNanos) {
    return new RevealEchoLedger(maxEntries, ttlNanos, () -> clock[0]);
  }

  @Test
  void consumeIsOneShot() {
    RevealEchoLedger ledger = ledger(64, SECOND_NANOS);
    ledger.record(PLAYER, "world", 10, 64, -20);

    assertTrue(ledger.consume(PLAYER, "world", 10, 64, -20), "刚发出的显形回显必须被认出");
    assertFalse(ledger.consume(PLAYER, "world", 10, 64, -20),
        "一次性语义：同坐标随后的服务端真实变更不得再被当成回显（否则真实变更不推进代次）");
    assertEquals(0, ledger.size(), "命中即移除");
  }

  @Test
  void matchesPlayerWorldAndCoordinatesExactly() {
    RevealEchoLedger ledger = ledger(64, SECOND_NANOS);
    ledger.record(PLAYER, "world", 10, 64, -20);

    assertFalse(ledger.consume(OTHER, "world", 10, 64, -20), "别的玩家看到的同坐标变更照常推进代次");
    assertFalse(ledger.consume(PLAYER, "world_nether", 10, 64, -20), "世界名不同不命中");
    assertFalse(ledger.consume(PLAYER, "world", 11, 64, -20), "x 不同不命中");
    assertFalse(ledger.consume(PLAYER, "world", 10, 65, -20), "y 不同不命中");
    assertFalse(ledger.consume(PLAYER, "world", 10, 64, -21), "z 不同不命中");
    assertTrue(ledger.consume(PLAYER, "world", 10, 64, -20), "未命中的查询不得消耗原记录");
  }

  @Test
  void expiredEntryIsNotConsumed() {
    long ttl = TimeUnit.MILLISECONDS.toNanos(200);
    RevealEchoLedger ledger = ledger(64, ttl);
    ledger.record(PLAYER, "world", 0, 64, 0);

    clock[0] = ttl + 1;
    assertFalse(ledger.consume(PLAYER, "world", 0, 64, 0),
        "超期未消费的条目视为过期：宁可多推进一次代次，也不放过真实变更");
  }

  @Test
  void capacityIsBoundedAndEvictsOldest() {
    RevealEchoLedger ledger = ledger(4, SECOND_NANOS);
    for (int i = 0; i < 10; i++) {
      ledger.record(PLAYER, "world", i, 64, 0);
    }

    assertEquals(4, ledger.size(), "条目上限生效（显形风暴下内存必须可控）");
    assertFalse(ledger.consume(PLAYER, "world", 0, 64, 0), "最旧条目已被淘汰（最坏只是少抑制一次）");
    assertTrue(ledger.consume(PLAYER, "world", 9, 64, 0), "最新条目仍在");
  }

  @Test
  void nullsAndClearAreSafe() {
    RevealEchoLedger ledger = ledger(4, SECOND_NANOS);
    ledger.record(null, "world", 0, 64, 0);
    ledger.record(PLAYER, null, 0, 64, 0);
    assertFalse(ledger.consume(null, "world", 0, 64, 0));
    assertFalse(ledger.consume(PLAYER, null, 0, 64, 0));

    ledger.record(PLAYER, "world", 1, 64, 1);
    ledger.clear();
    assertEquals(0, ledger.size(), "停用/热重载后不留副作用");
  }
}