package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 批次闸门表（{@link ProtocolLibAsyncListener.BatchTable}）行为测试：
 * 超限时只淘汰最旧的一个条目（不清全部，旧实现 {@code clear()} 会误清所有玩家的批次）、
 * 玩家退出时按 UUID 精确清理。
 *
 * <p>用小容量构造走同样的淘汰路径，无需实例化整个监听器（离线无 ProtocolLib 运行时）。
 */
class ProtocolLibAsyncListenerBatchTest {

  @Test
  void openUnderLimitKeepsAllGates() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);

    for (int i = 0; i < 4; i++) {
      table.open(UUID.nameUUIDFromBytes(("p" + i).getBytes()));
    }

    assertEquals(4, table.size(), "未达上限时所有闸门都保留");
    for (int i = 0; i < 4; i++) {
      assertNotNull(table.get(UUID.nameUUIDFromBytes(("p" + i).getBytes())),
          "未达上限时既有闸门不得被清掉");
    }
  }

  @Test
  void openOverLimitEvictsOnlyTheOldestGate() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(3);
    UUID oldest = UUID.nameUUIDFromBytes("oldest".getBytes());
    UUID middle = UUID.nameUUIDFromBytes("middle".getBytes());
    UUID newer = UUID.nameUUIDFromBytes("newer".getBytes());

    table.open(oldest);
    table.open(middle);
    table.open(newer);
    assertEquals(3, table.size());

    // 达到上限后再开一个：只淘汰最旧的，其余闸门必须原样保留（旧实现 clear() 会全清）
    UUID fresh = UUID.nameUUIDFromBytes("fresh".getBytes());
    table.open(fresh);

    assertEquals(3, table.size(), "淘汰一个再放入一个：总量保持在上限内");
    assertNull(table.get(oldest), "只有最旧的闸门被淘汰");
    assertNotNull(table.get(middle), "较新的闸门不得被误清");
    assertNotNull(table.get(newer), "较新的闸门不得被误清");
    assertNotNull(table.get(fresh), "新打开的闸门必须存在");
  }

  @Test
  void openOverLimitKeepsEvictingTheOldestEachTime() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(2);
    UUID a = UUID.nameUUIDFromBytes("a".getBytes());
    UUID b = UUID.nameUUIDFromBytes("b".getBytes());
    UUID c = UUID.nameUUIDFromBytes("c".getBytes());
    UUID d = UUID.nameUUIDFromBytes("d".getBytes());

    table.open(a);
    table.open(b);
    table.open(c); // 淘汰 a
    table.open(d); // 淘汰 b

    assertNull(table.get(a));
    assertNull(table.get(b));
    assertNotNull(table.get(c));
    assertNotNull(table.get(d));
    assertEquals(2, table.size());
  }

  @Test
  void removeClearsExactlyOnePlayerGate() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);
    UUID player = UUID.nameUUIDFromBytes("quitter".getBytes());
    UUID other = UUID.nameUUIDFromBytes("stayer".getBytes());
    table.open(player);
    table.open(other);

    ChunkBatchGate removed = table.remove(player);

    assertNotNull(removed, "退出时必须取走该玩家的闸门");
    assertNull(table.get(player), "退出玩家的闸门必须被清理");
    assertNotNull(table.get(other), "其它玩家的闸门不得受影响");
    assertFalse(table.remove(player) == removed, "同一玩家的闸门只能清理一次");
    assertEquals(1, table.size());
  }

  @Test
  void reopenedGateForSamePlayerReplacesTheOldOne() {
    ProtocolLibAsyncListener.BatchTable table = new ProtocolLibAsyncListener.BatchTable(4);
    UUID player = UUID.nameUUIDFromBytes("player".getBytes());

    table.open(player);
    ChunkBatchGate first = table.get(player);
    table.open(player);
    ChunkBatchGate second = table.get(player);

    assertSame(second, table.get(player));
    assertTrue(first != second, "重新打开批次必须换新闸门（旧闸门随 FINISHED 释放）");
    assertEquals(1, table.size());
  }
}
