package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * {@link ChunkKey.Probe} 的等价性守门。
 *
 * <p>探针是「可变的查询键」，用来消除邻近显形扫描热循环里的短命 {@link ChunkKey} 分配。
 * 它<b>必须</b>与 record 版本的 {@code hashCode} / {@code equals} 逐字段一致——否则
 * {@code ConcurrentHashMap} 会查不到已经存在的条目，表现为「区块明明在索引里，扫描却永远命中不了」，
 * 而这正是本测试要钉住的回归。
 */
class ChunkKeyProbeTest {

  @Test
  void probeHashCodeMatchesRecord() {
    // 覆盖负数、零、大值：世界坐标可以任意大，负坐标是最常见的（主世界出生点附近就有）
    int[][] coords = {
        {0, 0}, {1, 0}, {-1, -1}, {15, 15}, {-16, 32}, {123456, -654321},
        {Integer.MAX_VALUE, Integer.MIN_VALUE}, {-2000000, 1999999},
    };
    String[] worlds = {"world", "world_nether", "", "a-very-long-world-name-with-suffix"};
    for (String world : worlds) {
      for (int[] c : coords) {
        ChunkKey key = new ChunkKey(world, c[0], c[1]);
        ChunkKey.Probe probe = ChunkKey.Probe.local().set(world, c[0], c[1]);
        assertEquals(key.hashCode(), probe.hashCode(),
            "探针 hashCode 必须与 record 一致：" + world + "/" + c[0] + "/" + c[1]);
      }
    }
  }

  @Test
  void probeEqualsRecordBothDirections() {
    ChunkKey key = new ChunkKey("world", 3, -7);
    ChunkKey.Probe probe = ChunkKey.Probe.local().set("world", 3, -7);
    assertEquals(key, probe, "record.equals(probe) 必须为真");
    assertEquals(probe, key, "probe.equals(record) 必须为真");
    assertEquals(probe, ChunkKey.Probe.local().set("world", 3, -7), "探针与探针判等");
  }

  @Test
  void probeDistinguishesDifferentChunks() {
    ChunkKey.Probe probe = ChunkKey.Probe.local().set("world", 1, 1);
    assertNotEquals(probe, new ChunkKey("world", 1, 2));
    assertNotEquals(probe, new ChunkKey("world", 2, 1));
    assertNotEquals(probe, new ChunkKey("other", 1, 1));
    assertNotEquals(probe, null);
    assertNotEquals(probe, "world/1/1");
  }

  @Test
  void probeLooksUpExistingRecordKeyInHashMap() {
    Map<ChunkKey, String> map = new HashMap<>();
    map.put(new ChunkKey("world", 5, 6), "hit");
    assertEquals("hit", map.get(ChunkKey.Probe.local().set("world", 5, 6)),
        "用探针必须能查到以 record 为键写入的条目");
  }

  @Test
  void probeLooksUpInConcurrentHashMap() {
    ConcurrentHashMap<ChunkKey, Integer> map = new ConcurrentHashMap<>();
    map.put(new ChunkKey("world", -3, 4), 42);
    assertEquals(Integer.valueOf(42), map.get(ChunkKey.Probe.local().set("world", -3, 4)));
  }

  /**
   * <b>树化桶回归</b>：{@code ConcurrentHashMap} 在单个桶内条目数超过树化阈值（默认 8）后，
   * 会把链表转成红黑树；{@code TreeNode.find} 在比较时会调用<b>存储键</b>的 {@code equals}
   * （即 {@code ChunkKey.equals(Probe)}），而不只是查询键的 equals。
   *
   * <p>若 {@code ChunkKey} 用 {@code record}（其 equals 只认 {@code instanceof ChunkKey}），
   * 探针在树化桶里就会查不到已存在的条目——表现为「区块明明在索引里，扫描却永远命中不了」，
   * 且只在桶内条目数较多时才复现，是极难排查的隐性故障。本测试用「大量哈希冲突的键」强制树化。
   */
  @Test
  void probeFindsEntryInsideTreeifiedBucket() {
    ConcurrentHashMap<ChunkKey, Integer> map = new ConcurrentHashMap<>();
    // 构造大量「hashCode 相同但 equals 不同」的键：全部落进同一个桶，必然触发树化。
    // ChunkKey.hashCode = Objects.hashCode(world) * 31^2 + chunkX * 31 + chunkZ，
    // 因此固定 world、令 chunkX*31 + chunkZ 恒定即可制造冲突（chunkZ 随 chunkX 递减 31）。
    List<ChunkKey> keys = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      keys.add(new ChunkKey("world", i, -31 * i));
    }
    // 前置自检：确认这批键确实同哈希（否则本测试就退化成「普通查找」，测不到树化路径）
    int firstHash = keys.get(0).hashCode();
    for (ChunkKey key : keys) {
      assertEquals(firstHash, key.hashCode(), "前置条件：这批键必须同哈希才会落进同一个桶");
    }
    for (int i = 0; i < keys.size(); i++) {
      map.put(keys.get(i), i);
    }
    assertEquals(64, map.size(), "前置条件：64 个键都应写入");

    ChunkKey.Probe probe = ChunkKey.Probe.local();
    for (int i = 0; i < 64; i++) {
      assertEquals(Integer.valueOf(i), map.get(probe.set("world", i, -31 * i)),
          "树化桶内用探针必须能命中第 " + i + " 个条目");
    }
    assertFalse(map.containsKey(probe.set("world", 999, 0)), "树化桶内不存在的键不得误命中");
  }

  @Test
  void probeIsReusableAcrossLookups() {
    ConcurrentHashMap<ChunkKey, Integer> map = new ConcurrentHashMap<>();
    for (int i = 0; i < 10; i++) {
      map.put(new ChunkKey("world", i, i * 2), i);
    }
    // 同一个探针实例反复改指向：每次都必须在表中命中正确条目
    ChunkKey.Probe probe = ChunkKey.Probe.local();
    for (int i = 9; i >= 0; i--) {
      assertEquals(Integer.valueOf(i), map.get(probe.set("world", i, i * 2)),
          "复用探针第 " + i + " 次查询必须命中");
    }
    assertFalse(map.containsKey(probe.set("world", 99, 99)), "不存在的区块不得误命中");
    assertTrue(map.containsKey(probe.set("world", 0, 0)));
  }
}
