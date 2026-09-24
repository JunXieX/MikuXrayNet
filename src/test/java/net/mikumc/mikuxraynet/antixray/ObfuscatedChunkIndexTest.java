package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 伪装区块索引测试：按区块共享（不随玩家数复制）、坐标摘除、区块/世界失效、过期兜底、安全阀，
 * 以及「不持有平台强引用」的结构性断言（自证不会钉住 World / Chunk / Player 等对象）。
 *
 * <p>本文件取代旧的 {@code RevealedBlockIndexTest}：旧结构「按玩家各存一份被伪装坐标」已被
 * {@link ObfuscatedChunkIndex}（按区块共享）+ {@link RevealedSet}（按玩家记录已显形坐标）拆替，
 * 因此旧测试里针对「单玩家容量淘汰 / 按玩家清理 / 跨玩家候选」的断言不再适用，改为断言新结构的不变式。
 */
class ObfuscatedChunkIndexTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final String WORLD = "world";
  private static final int MIN_HEIGHT = -64;

  /** 索引不得以任何形式持有这些类型的引用（含泛型实参）。 */
  private static final Set<String> FORBIDDEN_TYPES = Set.of(
      "org.bukkit.entity.Player", "org.bukkit.entity.Entity", "org.bukkit.World",
      "org.bukkit.Chunk", "org.bukkit.block.Block");

  private final long[] clock = {0L};

  private ObfuscatedChunkIndex index(int maxPositions, long expireNanos) {
    return new ObfuscatedChunkIndex(maxPositions, expireNanos, () -> clock[0]);
  }

  /** 由世界内绝对方块坐标算出区块内相对坐标编码（{@code 相对Y << 8 | z << 4 | x}）。 */
  private static int local(int minHeight, int x, int y, int z) {
    return ((y - minHeight) << 8) | ((z & 15) << 4) | (x & 15);
  }

  /** 生成 {@code count} 个互不相同的区块内相对坐标（编码本身即序号，解码后仍是唯一坐标）。 */
  private static int[] positions(int count) {
    int[] locals = new int[count];
    for (int i = 0; i < count; i++) {
      locals[i] = i;
    }
    return locals;
  }

  // ------------------------------------------------------------------ 按区块共享

  /**
   * <b>核心断言</b>：同一区块被多个玩家收到时只覆盖同一条记录，坐标<b>不随玩家数复制</b>。
   * 这是内存从 {@code O(玩家数 × 各自看过的区块数)} 降到 {@code O(有伪装的已加载区块数)} 的结构性保证。
   */
  @Test
  void sameChunkIsStoredOnceRegardlessOfPlayerCount() {
    ObfuscatedChunkIndex index = index(1_000_000, 60 * SECOND_NANOS);
    int[] locals = {local(MIN_HEIGHT, 3, 10, 4), local(MIN_HEIGHT, 5, 10, 6)};

    // 模拟 100 个玩家先后收到同一区块（改写结果按区块共享，因此每次传入的是同一份坐标）
    for (int i = 0; i < 100; i++) {
      assertEquals(locals.length, index.recordChunk(WORLD, 2, 3, MIN_HEIGHT, locals));
    }

    assertEquals(1, index.chunkCount(), "同一区块只保留一条条目");
    assertEquals(locals.length, index.positionCount(), "坐标总量不随玩家数增长");
    assertSame(locals, index.entry(new ChunkKey(WORLD, 2, 3)).locals(),
        "直接共享改写结果的 int[]，不做逐玩家复制");
  }

  @Test
  void recordingSameChunkAgainReplacesItsPositions() {
    ObfuscatedChunkIndex index = index(1_000_000, 60 * SECOND_NANOS);

    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, positions(3));
    assertEquals(3, index.positionCount());

    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, positions(5));
    assertEquals(1, index.chunkCount());
    assertEquals(5, index.positionCount(), "重新下发以新清单为准（覆盖而非追加）");

    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[0]);
    assertEquals(5, index.positionCount(), "空清单不建条目，也不清空既有条目");
  }

  @Test
  void keyedByWorldAndChunkCoordinates() {
    ObfuscatedChunkIndex index = index(1_000_000, 60 * SECOND_NANOS);
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, positions(2));
    index.recordChunk("world_nether", 0, 0, MIN_HEIGHT, positions(2));
    index.recordChunk(WORLD, 1, 0, MIN_HEIGHT, positions(2));

    assertEquals(3, index.chunkCount(), "不同世界 / 不同区块互不覆盖");
    assertEquals(6, index.positionCount());
  }

  // ------------------------------------------------------------------ 失效时机

  @Test
  void removePositionOnlyDropsThatCoordinate() {
    ObfuscatedChunkIndex index = index(1_000_000, 60 * SECOND_NANOS);
    int[] locals = {
        local(MIN_HEIGHT, 1, 10, 1),
        local(MIN_HEIGHT, 2, 10, 1),
        local(MIN_HEIGHT, 1, 11, 1)};
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, locals);

    assertTrue(index.removePosition(WORLD, 2, 10, 1), "方块变更：命中并摘除该坐标");
    assertFalse(index.removePosition(WORLD, 2, 10, 1), "重复摘除必须返回 false");
    assertEquals(2, index.positionCount(), "同区块其它坐标不受影响");
    assertEquals(1, index.chunkCount(), "清单非空，条目保留");
    assertFalse(index.removePosition(WORLD, 30, 10, 1), "其它区块的坐标不命中");
    assertEquals(2, index.positionCount());
  }

  @Test
  void removingLastCoordinateDropsChunkEntry() {
    ObfuscatedChunkIndex index = index(1_000_000, 60 * SECOND_NANOS);
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(MIN_HEIGHT, 1, 10, 1)});

    assertTrue(index.removePosition(WORLD, 1, 10, 1));
    assertEquals(0, index.positionCount());
    assertEquals(0, index.chunkCount(), "空清单必须整条摘除");
    assertNull(index.entry(new ChunkKey(WORLD, 0, 0)));
  }

  @Test
  void invalidateChunkAndWorldDropEntries() {
    ObfuscatedChunkIndex index = index(1_000_000, 60 * SECOND_NANOS);
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, positions(2));
    index.recordChunk(WORLD, 1, 0, MIN_HEIGHT, positions(2));
    index.recordChunk("world_nether", 0, 0, MIN_HEIGHT, positions(2));

    assertTrue(index.invalidateChunk(WORLD, 1, 0), "区块卸载：命中并移除");
    assertFalse(index.invalidateChunk(WORLD, 1, 0), "重复失效返回 false");
    assertEquals(2, index.chunkCount());
    assertEquals(4, index.positionCount());

    index.invalidateWorld(WORLD);
    assertEquals(1, index.chunkCount(), "世界卸载：只清该世界");
    assertEquals(2, index.positionCount());

    index.clear();
    assertEquals(0, index.chunkCount());
    assertEquals(0, index.positionCount());
  }

  @Test
  void entriesExpireAfterWindowUnlessScanned() {
    long window = 10 * SECOND_NANOS;
    ObfuscatedChunkIndex index = index(1_000_000, window);
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, positions(2));

    clock[0] = 9 * SECOND_NANOS;
    index.expire();
    assertEquals(1, index.chunkCount(), "窗口内不应清理");

    clock[0] = 10 * SECOND_NANOS;
    index.expire();
    assertEquals(1, index.chunkCount(), "恰好到达窗口边界仍保留");

    // 扫描一次（entry 会刷新活跃时间）→ 过期窗口重新计时
    assertNotNull(index.entry(new ChunkKey(WORLD, 0, 0)));
    clock[0] = 19 * SECOND_NANOS;
    index.expire();
    assertEquals(1, index.chunkCount(), "被扫描到的条目必须刷新活跃时间，不得被误清");

    clock[0] = 20 * SECOND_NANOS + 1;
    index.expire();
    assertEquals(0, index.chunkCount(), "长期未被扫描的条目必须清理（兜底）");
    assertEquals(0, index.positionCount());
  }

  // ------------------------------------------------------------------ 安全阀

  /**
   * 真机量级（1506 区块 / 609040 个替换方块 ≈ 每区块 404 个）下，全服安全阀<b>不得触发</b>。
   */
  @Test
  void safetyValveNeverTriggersAtProductionScale() {
    ObfuscatedChunkIndex index = index(4194304, 300 * SECOND_NANOS);
    int chunks = 1506;
    int perChunk = 404;

    for (int i = 0; i < chunks; i++) {
      index.recordChunk(WORLD, i % 40, i / 40, MIN_HEIGHT, positions(perChunk));
    }

    assertEquals(chunks, index.chunkCount());
    assertEquals(chunks * perChunk, index.positionCount());
    assertEquals(0L, index.evictedByCapacity(), "正常运营下安全阀必须恒为 0");
  }

  /** 上限被刻意设小时才触发：按「最旧优先」淘汰其它区块来给新记录腾位置。 */
  @Test
  void safetyValveEvictsOldestChunkOnlyWhenOverCapacity() {
    ObfuscatedChunkIndex index = index(12, 60 * SECOND_NANOS);

    clock[0] = 1;
    assertEquals(10, index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, positions(10)));
    clock[0] = 2;
    assertEquals(10, index.recordChunk(WORLD, 1, 0, MIN_HEIGHT, positions(10)));

    assertEquals(1, index.chunkCount(), "放不下时必须淘汰，保证新记录可写入");
    assertNull(index.entry(new ChunkKey(WORLD, 0, 0)), "被淘汰的是最旧的区块");
    assertNotNull(index.entry(new ChunkKey(WORLD, 1, 0)));
    assertEquals(10L, index.evictedByCapacity(), "淘汰数必须可诊断");
  }

  // ------------------------------------------------------------------ 结构断言

  /**
   * 结构断言：{@link ObfuscatedChunkIndex}、{@link ChunkKey} 与其全部嵌套类型的任何字段，
   * 都不能是平台类型，也不能把平台类型当泛型实参——从根上杜绝「键悄悄钉住 World/Chunk/Player」。
   */
  @Test
  void obfuscatedIndexHoldsNoPlatformReferences() {
    assertNoPlatformReferences(4, "ObfuscatedChunkIndex + ChunkKey",
        ObfuscatedChunkIndex.class, ChunkKey.class);
  }

  /** 同上，覆盖 {@link RevealedSet} 与其嵌套条目。 */
  @Test
  void revealedSetHoldsNoPlatformReferences() {
    assertNoPlatformReferences(2, "RevealedSet", RevealedSet.class);
  }

  private static void assertNoPlatformReferences(int expectedTypes, String label, Class<?>... roots) {
    Deque<Class<?>> pending = new ArrayDeque<>(Arrays.asList(roots));
    Set<Class<?>> visited = new HashSet<>();

    while (!pending.isEmpty()) {
      Class<?> type = pending.poll();
      if (!visited.add(type)) {
        continue;
      }
      pending.addAll(Arrays.asList(type.getDeclaredClasses()));

      for (Field field : type.getDeclaredFields()) {
        assertNoPlatformType(field.getGenericType(), type.getSimpleName() + "." + field.getName());
        if (type.isRecord()) {
          assertTrue(field.getType().isPrimitive() || field.getType() == String.class,
              "不可变键/坐标 record 只允许基本类型或 String：" + field.getName());
        }
      }
    }

    assertEquals(expectedTypes, visited.size(), label + " 覆盖的类数不符：" + visited);
  }

  private static void assertNoPlatformType(Type type, String owner) {
    if (type instanceof ParameterizedType parameterized) {
      assertNoPlatformType(parameterized.getRawType(), owner);
      for (Type argument : parameterized.getActualTypeArguments()) {
        assertNoPlatformType(argument, owner);
      }
      return;
    }
    if (type instanceof GenericArrayType array) {
      assertNoPlatformType(array.getGenericComponentType(), owner);
      return;
    }
    if (!(type instanceof Class<?> raw)) {
      return;
    }
    if (raw.isArray()) {
      assertNoPlatformType(raw.getComponentType(), owner);
      return;
    }

    String name = raw.getName();
    assertFalse(FORBIDDEN_TYPES.contains(name), owner + " 不得持有平台引用：" + name);
    assertFalse(name.startsWith("org.bukkit.") || name.startsWith("com.comphenix.")
            || name.startsWith("io.papermc."),
        owner + " 不得持有平台类型：" + name);
  }
}