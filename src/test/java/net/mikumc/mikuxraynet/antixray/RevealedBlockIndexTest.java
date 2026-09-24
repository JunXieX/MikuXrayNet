package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 显形索引测试：写入/注销/去重、距离筛选（含边界）、过期清理、按玩家与世界清理、容量上限，
 * 以及「不持有平台强引用」的结构性断言（自证不会钉住 World / Chunk / Player 等对象）。
 */
class RevealedBlockIndexTest {

  private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final UUID PLAYER = UUID.randomUUID();
  private static final UUID OTHER_PLAYER = UUID.randomUUID();
  private static final String WORLD = "world";

  /** 索引不得以任何形式持有这些类型的引用（含泛型实参）。 */
  private static final Set<String> FORBIDDEN_TYPES = Set.of(
      "org.bukkit.entity.Player", "org.bukkit.entity.Entity", "org.bukkit.World",
      "org.bukkit.Chunk", "org.bukkit.block.Block");

  private final long[] clock = {0L};

  private RevealedBlockIndex index(int maxPositions, int maxPositionsPerPlayer, long expireNanos) {
    return new RevealedBlockIndex(maxPositions, maxPositionsPerPlayer, expireNanos, () -> clock[0]);
  }

  @Test
  void recordsCoordinatesAndFiltersByDistance() {
    RevealedBlockIndex index = index(64, 64, 60 * SECOND_NANOS);
    assertTrue(index.record(PLAYER, WORLD, 10, 20, 30));
    assertTrue(index.record(PLAYER, WORLD, 10, 20, 34));
    // 3-4-5：距离恰好等于阈值 5.0，属于命中
    assertTrue(index.record(PLAYER, WORLD, 13, 24, 30));
    // 3-4-1：距离平方 26，刚超过阈值，必须排除
    assertTrue(index.record(PLAYER, WORLD, 13, 24, 31));
    // 距离 10，远在阈值之外
    assertTrue(index.record(PLAYER, WORLD, 20, 20, 30));

    List<RevealedBlockIndex.Position> candidates =
        index.candidates(PLAYER, WORLD, 10, 20, 30, 5.0D, 8);

    assertEquals(3, candidates.size(), "只应返回距离不超过 5 格的坐标");
    assertEquals(new RevealedBlockIndex.Position(10, 20, 30), candidates.get(0), "距离 0 排在最前");
    assertEquals(new RevealedBlockIndex.Position(10, 20, 34), candidates.get(1), "按距离由近到远排序");
    assertEquals(new RevealedBlockIndex.Position(13, 24, 30), candidates.get(2), "距离恰好等于阈值必须命中");
  }

  @Test
  void unregisterRemovesCoordinateOnlyOnce() {
    RevealedBlockIndex index = index(64, 64, 60 * SECOND_NANOS);
    index.record(PLAYER, WORLD, 1, 65, 2);

    assertTrue(index.unregister(PLAYER, WORLD, 1, 65, 2));
    assertFalse(index.unregister(PLAYER, WORLD, 1, 65, 2), "重复注销必须返回 false");
    assertEquals(0, index.size());
    assertEquals(0, index.chunkCount(), "空区块条目必须被摘除");
    assertTrue(index.candidates(PLAYER, WORLD, 1, 65, 2, 8.0D, 8).isEmpty());
  }

  @Test
  void unregisterWithoutWorldMatchesByChunkCoordinates() {
    RevealedBlockIndex index = index(64, 64, 60 * SECOND_NANOS);
    index.record(PLAYER, WORLD, 33, 70, -17);
    index.record(PLAYER, WORLD, 34, 70, -17);

    assertTrue(index.unregister(PLAYER, 33, 70, -17), "区块坐标匹配即可命中");
    assertEquals(1, index.size(), "同区块的其它坐标不受影响");
    assertFalse(index.unregister(PLAYER, 40, 70, -17), "区块坐标不同则不命中");
    assertFalse(index.unregister(OTHER_PLAYER, 34, 70, -17), "其它玩家不受影响");
  }

  @Test
  void duplicateRecordKeepsSingleEntry() {
    RevealedBlockIndex index = index(64, 64, 60 * SECOND_NANOS);

    assertTrue(index.record(PLAYER, WORLD, 7, 33, 7));
    assertFalse(index.record(PLAYER, WORLD, 7, 33, 7), "同一坐标只记录一次");
    assertEquals(1, index.size());
    assertEquals(1, index.candidates(PLAYER, WORLD, 7, 33, 7, 1.0D, 8).size());
  }

  @Test
  void batchRecordMapsChunkRelativePositions() {
    RevealedBlockIndex index = index(64, 64, 60 * SECOND_NANOS);
    int minHeight = -64;
    // 相对坐标编码 y << 8 | z << 4 | x：区块 (-1,-1) 的角落 (x=15,z=15) 第 1 层
    int local = (1 << 8) | (15 << 4) | 15;

    assertEquals(1, index.record(PLAYER, WORLD, -1, -1, minHeight, new int[] {local}));
    assertEquals(List.of(new RevealedBlockIndex.Position(-1, -63, -1)),
        index.candidates(PLAYER, WORLD, -1, -63, -1, 0.5D, 4));
  }

  @Test
  void extremeCoordinatesSurvivePacking() {
    RevealedBlockIndex index = index(64, 64, 60 * SECOND_NANOS);
    int far = 30_000_000;

    assertTrue(index.record(PLAYER, WORLD, far, 320, -far));
    assertTrue(index.record(PLAYER, WORLD, far, -64, -far + 1));

    assertEquals(List.of(new RevealedBlockIndex.Position(far, 320, -far)),
        index.candidates(PLAYER, WORLD, far, 320, -far, 0.5D, 4));
    assertEquals(List.of(new RevealedBlockIndex.Position(far, -64, -far + 1)),
        index.candidates(PLAYER, WORLD, far, -64, -far + 1, 0.5D, 4));
  }

  @Test
  void scopedByPlayerAndWorld() {
    RevealedBlockIndex index = index(64, 64, 60 * SECOND_NANOS);
    index.record(PLAYER, WORLD, 0, 64, 0);
    index.record(PLAYER, "world_nether", 0, 64, 0);
    index.record(OTHER_PLAYER, WORLD, 0, 64, 0);

    assertEquals(1, index.candidates(PLAYER, WORLD, 0, 64, 0, 1.0D, 8).size());
    assertEquals(1, index.candidates(PLAYER, "world_nether", 0, 64, 0, 1.0D, 8).size());
    assertTrue(index.candidates(OTHER_PLAYER, "world_nether", 0, 64, 0, 1.0D, 8).isEmpty(),
        "不同玩家的坐标互不可见");
  }

  @Test
  void entriesExpireAfterWindow() {
    long window = 10 * SECOND_NANOS;
    RevealedBlockIndex index = index(64, 64, window);
    index.record(PLAYER, WORLD, 5, 64, 5);

    clock[0] = 9 * SECOND_NANOS;
    index.expire();
    assertEquals(1, index.size(), "窗口内不应清理");

    clock[0] = 10 * SECOND_NANOS;
    index.expire();
    assertEquals(1, index.size(), "恰好到达窗口边界仍保留");

    clock[0] = 11 * SECOND_NANOS;
    index.expire();
    assertEquals(0, index.size(), "超过窗口必须清理");
    assertEquals(0, index.chunkCount(), "过期条目的区块条目必须被摘除");
    assertTrue(index.candidates(PLAYER, WORLD, 5, 64, 5, 1.0D, 8).isEmpty());
  }

  @Test
  void repeatedRecordRefreshesExpiry() {
    RevealedBlockIndex index = index(64, 64, 10 * SECOND_NANOS);
    index.record(PLAYER, WORLD, 5, 64, 5);

    clock[0] = 9 * SECOND_NANOS;
    assertFalse(index.record(PLAYER, WORLD, 5, 64, 5), "重复写入不算新增");

    clock[0] = 18 * SECOND_NANOS;
    index.expire();
    assertEquals(1, index.size(), "重复写入（区块被重新下发）应刷新活跃时间");
  }

  @Test
  void capacityLimitsAreEnforced() {
    UUID thirdPlayer = UUID.randomUUID();
    RevealedBlockIndex index = index(2, 1, 60 * SECOND_NANOS);

    assertTrue(index.record(PLAYER, WORLD, 0, 64, 0));
    assertFalse(index.record(PLAYER, WORLD, 1, 64, 0), "单玩家上限已满");
    assertTrue(index.record(OTHER_PLAYER, WORLD, 0, 64, 0));
    assertFalse(index.record(OTHER_PLAYER, WORLD, 1, 64, 0), "单玩家上限已满");
    assertFalse(index.record(thirdPlayer, WORLD, 0, 64, 0), "全服上限已满");

    assertEquals(2, index.size(), "总坐标数不得超过全服上限");
    assertEquals(2, index.playerCount(), "被全局容量拒绝的记录不应留下空条目");
    assertEquals(2, index.chunkCount());
    assertEquals(3, index.droppedByCapacity(), "丢弃次数必须可诊断");
  }

  /**
   * 登录规模回归（本 bug 的回归测试）：玩家重进服务器会连续收到数百个区块，
   * {@code mode=all} 下每区块约 174 个坐标（约 7 万，超过单玩家上限 65536）。
   * 即便发生容量淘汰，玩家身边 12 格内的坐标也必须仍能被选出——否则「走到裸露矿旁边永不还原」。
   */
  @Test
  void loginScaleKeepsNearbyCoordinatesSelectable() {
    int maxPositionsPerPlayer = 65536;
    int chunks = 400;
    int perChunk = 174;
    RevealedBlockIndex index = index(512000, maxPositionsPerPlayer, 300 * SECOND_NANOS);

    // 玩家站在 (8,64,8)：先记一个身边坐标并查询一次（真机上巡检每 5 tick 查询一次，会刷新玩家位置）
    assertTrue(index.record(PLAYER, WORLD, 8, 64, 8));
    assertEquals(1, index.candidates(PLAYER, WORLD, 8, 64, 8, 12.0D, 8).size());

    // 连续写入 400 个远在 12 格之外的区块（每区块 174 个坐标，均落在 40..59 号区块）
    int[] locals = new int[perChunk];
    for (int i = 0; i < perChunk; i++) {
      locals[i] = i;
    }
    int written = 0;
    for (int chunk = 0; chunk < chunks; chunk++) {
      int chunkX = 40 + chunk % 20;
      int chunkZ = 40 + chunk / 20;
      written += index.record(PLAYER, WORLD, chunkX, chunkZ, -64, locals);
    }

    assertTrue(written > maxPositionsPerPlayer, "写入量必须超过单玩家上限，否则没覆盖淘汰路径：" + written);
    assertTrue(index.droppedByCapacity() > 0, "超过上限必须累加丢弃计数");
    assertTrue(index.size() <= maxPositionsPerPlayer, "总坐标数不得超过单玩家上限：" + index.size());

    List<RevealedBlockIndex.Position> nearby =
        index.candidates(PLAYER, WORLD, 8, 64, 8, 12.0D, 8);
    assertTrue(nearby.contains(new RevealedBlockIndex.Position(8, 64, 8)),
        "登录规模下玩家身边的坐标必须仍在索引里（bug 根因）：" + nearby);
  }

  /**
   * 容量上限触发时：计数必须增加，且「离玩家最近」的坐标不得被淘汰、新写入的近处坐标不得被立刻挤出。
   */
  @Test
  void capacityEvictionDropsFarthestAndKeepsNearCoordinates() {
    RevealedBlockIndex index = index(64, 8, 60 * SECOND_NANOS);

    // 让索引知道玩家在 (0,64,0)（查询一次即刷新位置）
    assertTrue(index.record(PLAYER, WORLD, 0, 64, 0));
    assertEquals(1, index.candidates(PLAYER, WORLD, 0, 64, 0, 1.0D, 4).size());
    long droppedBefore = index.droppedByCapacity();

    // 单玩家上限 8：连续写入 20 个远处区块的坐标，最远的应先被淘汰、新记录必须被接受
    for (int i = 0; i < 20; i++) {
      assertTrue(index.record(PLAYER, WORLD, 100 + i, 64, 0), "新写入的坐标不得被立刻挤出（第 " + i + " 个）");
    }

    assertTrue(index.droppedByCapacity() > droppedBefore, "容量上限触发必须累加丢弃计数");
    assertTrue(index.size() <= 8, "总坐标数不得超过单玩家上限：" + index.size());
    assertTrue(index.candidates(PLAYER, WORLD, 0, 64, 0, 1.0D, 4)
        .contains(new RevealedBlockIndex.Position(0, 64, 0)), "离玩家最近的坐标不得被淘汰");
  }

  @Test
  void clearsByPlayerAndWorld() {
    RevealedBlockIndex index = index(64, 64, 60 * SECOND_NANOS);
    index.record(PLAYER, WORLD, 0, 64, 0);
    index.record(PLAYER, "world_nether", 0, 64, 0);
    index.record(OTHER_PLAYER, WORLD, 1, 64, 1);

    index.clearPlayer(PLAYER);
    assertEquals(1, index.size(), "只清理该玩家");
    assertTrue(index.candidates(PLAYER, WORLD, 0, 64, 0, 1.0D, 8).isEmpty());

    index.clearWorld(WORLD);
    assertEquals(0, index.size(), "世界卸载必须整体清理该世界");
    assertEquals(0, index.chunkCount());

    index.record(PLAYER, WORLD, 0, 64, 0);
    index.clear();
    assertEquals(0, index.size());
    assertEquals(0, index.playerCount());
  }

  /**
   * 结构断言：主类与其全部嵌套类型（键、坐标、玩家索引、区块条目）的任何字段，
   * 都不能是平台类型，也不能把平台类型当泛型实参——从根上杜绝「键悄悄钉住 World/Chunk/Player」。
   */
  @Test
  void holdsNoPlatformReferences() {
    Deque<Class<?>> pending = new ArrayDeque<>();
    Set<Class<?>> visited = new HashSet<>();
    pending.add(RevealedBlockIndex.class);

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

    assertEquals(5, visited.size(), "应覆盖主类与 4 个嵌套类型（坐标、键、玩家索引、区块条目）");
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