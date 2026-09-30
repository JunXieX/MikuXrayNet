package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 显形「先标记、再发包、失败回滚」的顺序契约（P1/P2 修复）：
 * 出站监听器会回显我们自己发出的显形包并摘除索引 + 标记——只要标记先行，摘除就能命中并一并摘掉标记，
 * 不会留下孤儿标记；发送失败则回滚标记，保证该坐标仍被后续周期重试。
 *
 * <p>离线可直接驱动 {@link ProximityRevealer#markThenSend} / {@link ProximityRevealer#markThenSendAll}
 * （包级可见的发送缝），用布尔桩模拟「发包返回前异步监听器先跑摘除」的交错顺序，无需真实 Player/World。
 */
class RevealMarkOrderingTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final String WORLD = "world";
  private static final int MIN_HEIGHT = -64;
  private static final UUID PLAYER = UUID.randomUUID();

  private final long[] clock = {0L};
  private final ObfuscatedChunkIndex index =
      new ObfuscatedChunkIndex(1_000_000, 300 * SECOND_NANOS, () -> clock[0]);
  private final RevealedSet revealed =
      new RevealedSet(1_000_000, 300 * SECOND_NANOS, () -> clock[0]);
  private final ProximityRevealer revealer = new ProximityRevealer(null, null, config(), index,
      revealed, new ProximityStats(), null, null);

  private static AntiXrayConfig config() {
    return AntiXrayConfig.from(yaml("enabled: true\n"));
  }

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException(exception);
    }
    return configuration;
  }

  private static int local(int x, int y, int z) {
    return ((y - MIN_HEIGHT) << 8) | ((z & 15) << 4) | (x & 15);
  }

  /**
   * 交错顺序：发包桩里先跑出站监听器的摘除（模拟异步线程先于 markRevealed 执行）。
   * 标记先行 → 监听器命中并一并摘掉标记 → <b>无孤儿标记</b>、计数与区块清单自洽。
   */
  @Test
  void listenerRemovingDuringSendLeavesNoOrphanMark() {
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(1, 64, 1)});
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);
    assertEquals(1, index.entry(key).size(), "前置：该坐标在伪装清单里");

    boolean sent = revealer.markThenSend(PLAYER, WORLD, 1, 64, 1, () -> {
      // 模拟出站监听器（异步）处理我们刚发出的显形包：同步摘除伪装清单与已显形标记
      index.removePosition(WORLD, 1, 64, 1);
      revealed.removePosition(WORLD, 1, 64, 1);
      return true;
    });

    assertTrue(sent, "桩返回 true：视为已发出");
    assertFalse(revealed.contains(PLAYER, key, 1, 64, 1),
        "标记先行 → 监听器摘除时命中并一并摘掉，不留孤儿标记");
    assertEquals(0, revealed.sizeFor(PLAYER, key), "已显形数为 0，不与「区块清单已摘空」自相矛盾");
    assertNull(index.entry(key), "唯一坐标被摘除后整条区块条目移除");
    long listSize = index.entry(key) == null ? 0 : index.entry(key).size();
    assertTrue(revealed.sizeFor(PLAYER, key) <= listSize,
        "不变式：已显形坐标 ⊆ 区块伪装清单（两者都为空）");
  }

  /** 发送失败必须回滚标记：坐标仍在伪装清单里，后续周期仍会选出重试（不因标记而永久跳过）。 */
  @Test
  void failedSendRollsBackMarkSoCoordinateIsRetried() {
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(1, 64, 1)});
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);

    boolean sent = revealer.markThenSend(PLAYER, WORLD, 1, 64, 1, () -> false);

    assertFalse(sent, "桩返回 false：视为未发出");
    assertFalse(revealed.contains(PLAYER, key, 1, 64, 1), "未发出 → 标记必须回滚，计数不虚增");
    assertEquals(0, revealed.sizeFor(PLAYER, key));
    assertTrue(index.containsPosition(WORLD, 1, 64, 1), "该坐标仍在伪装清单里");
    assertTrue(ProximityScanner.candidates(index, revealed, PLAYER, WORLD, 1, 64, 1, 16.0D, 64,
        0, 1, null).contains(new ObfuscatedChunkIndex.Position(1, 64, 1)),
        "发送失败的坐标后续周期必须仍会被选出重试");
  }

  /** 批量合并包：先标记整批再发；失败整批回滚、成功整批保留。 */
  @Test
  void batchMarksBeforeSendAndRollsBackAllOnFailure() {
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(1, 64, 1), local(2, 64, 1)});
    ChunkKey key = ChunkKey.ofBlock(WORLD, 1, 1);
    List<int[]> positions = List.of(new int[] {1, 64, 1}, new int[] {2, 64, 1});

    assertFalse(revealer.markThenSendAll(PLAYER, WORLD, positions, () -> false),
        "合并包未发出");
    assertEquals(0, revealed.sizeFor(PLAYER, key), "合并包失败 → 整批标记回滚，不留虚增计数");

    assertTrue(revealer.markThenSendAll(PLAYER, WORLD, positions, () -> true),
        "合并包已发出");
    assertEquals(2, revealed.sizeFor(PLAYER, key), "合并包成功 → 整批标记保留");
  }
}