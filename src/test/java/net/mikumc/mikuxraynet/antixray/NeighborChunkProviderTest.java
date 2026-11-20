package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider.ChunkLoadedCheck;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider.ColumnTopQuery;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider.OcclusionQuery;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider.SideSnapshot;
import net.mikumc.mikuxraynet.antixray.NeighborEdges.Side;
import org.junit.jupiter.api.Test;

/**
 * 邻块贴边快照的纯逻辑测试：坐标换算、平面取值、加载判断、缺失标记、高度上界裁剪与缓存生命周期。
 *
 * <p>抓取核心（{@link NeighborChunkProvider#capture}）通过注入假世界查询即可离线验证，
 * 这样「邻块 x=0 的那一面应当对应请求方 x=16 的检查」这类最容易出错的坐标换算就被固定住了。
 */
class NeighborChunkProviderTest {

  private static final int HEIGHT = 32;

  /** 假世界：y ≥ 20 遮挡，其余不遮挡。 */
  private static final OcclusionQuery QUERY = (x, y, z) -> y >= 20;

  /** 列高度上界：全部列都取到世界顶部（等价于「不做裁剪」的旧行为）。 */
  private static final ColumnTopQuery UNBOUNDED = (x, z) -> Integer.MAX_VALUE;

  @Test
  void worldPositionMatchesRequestSide() {
    // 请求方 x=16 的那一面 = X_PLUS 侧邻块的 localX=0
    assertArrayEquals(new int[] {5 << 4, (7 << 4) + 3},
        NeighborChunkProvider.worldPosition(Side.X_PLUS, 4, 7, 3));
    // 请求方 x=-1 的那一面 = X_MINUS 侧邻块的 localX=15
    assertArrayEquals(new int[] {(3 << 4) + 15, (7 << 4) + 3},
        NeighborChunkProvider.worldPosition(Side.X_MINUS, 4, 7, 3));
    // 请求方 z=16 / z=-1 的那两面
    assertArrayEquals(new int[] {(4 << 4) + 9, 8 << 4},
        NeighborChunkProvider.worldPosition(Side.Z_PLUS, 4, 7, 9));
    assertArrayEquals(new int[] {(4 << 4) + 9, (6 << 4) + 15},
        NeighborChunkProvider.worldPosition(Side.Z_MINUS, 4, 7, 9));

    assertArrayEquals(new int[] {5, 7}, NeighborChunkProvider.neighborChunk(Side.X_PLUS, 4, 7));
    assertArrayEquals(new int[] {3, 7}, NeighborChunkProvider.neighborChunk(Side.X_MINUS, 4, 7));
    assertArrayEquals(new int[] {4, 8}, NeighborChunkProvider.neighborChunk(Side.Z_PLUS, 4, 7));
    assertArrayEquals(new int[] {4, 6}, NeighborChunkProvider.neighborChunk(Side.Z_MINUS, 4, 7));
  }

  @Test
  void planeValuesFollowWorldQuery() {
    for (Side side : Side.values()) {
      long[] plane = NeighborChunkProvider.capturePlane(0, HEIGHT, side, 4, 7, QUERY, UNBOUNDED);
      assertEquals(NeighborEdges.planeLongCount(HEIGHT), plane.length, "平面按位打包（每格 1 bit）");

      for (int local = 0; local < 16; local++) {
        int[] position = NeighborChunkProvider.worldPosition(side, 4, 7, local);
        for (int y = 0; y < HEIGHT; y++) {
          boolean expected = QUERY.isOccluding(position[0], y, position[1]);
          assertEquals(expected, NeighborEdges.isOccludingAt(plane, y << 4 | local),
              "side=" + side + " y=" + y + " local=" + local);
        }
      }
    }
  }

  @Test
  void loadedNeighborProducesPlaneAndUnloadedIsMissing() {
    NeighborChunkProvider provider = new NeighborChunkProvider(8);
    // 只让 X_PLUS 侧邻块（5,7）处于未加载状态
    ChunkLoadedCheck loaded = (chunkX, chunkZ) -> !(chunkX == 5 && chunkZ == 7);

    NeighborEdges edges = provider.capture("world", 0, HEIGHT, 4, 7, loaded, QUERY, UNBOUNDED);

    assertTrue(edges.has(Side.X_MINUS));
    assertFalse(edges.has(Side.X_PLUS));
    assertTrue(edges.has(Side.Z_MINUS));
    assertTrue(edges.has(Side.Z_PLUS));

    assertEquals(1, edges.occluding(Side.X_MINUS, 25, 3), "邻块 y=25 遮挡");
    assertEquals(0, edges.occluding(Side.X_MINUS, 5, 3), "邻块 y=5 不遮挡");
    assertEquals(NeighborEdges.MISSING, edges.occluding(Side.X_PLUS, 25, 3), "未加载的平面必须标记为缺失");
    assertEquals(NeighborEdges.MISSING, edges.occluding(Side.X_MINUS, HEIGHT, 3), "越界 Y");
    assertEquals(NeighborEdges.MISSING, edges.occluding(Side.X_MINUS, 25, 16), "越界另一轴");
  }

  @Test
  void captureIsCachedAndInvalidatedByWorld() {
    NeighborChunkProvider provider = new NeighborChunkProvider(2);
    ChunkLoadedCheck loaded = (chunkX, chunkZ) -> true;

    NeighborEdges first = provider.capture("world", 0, HEIGHT, 4, 7, loaded, QUERY, UNBOUNDED);
    assertSame(first, provider.cached("world", 4, 7), "第二次读取应命中缓存");
    assertSame(first, provider.capture("world", 0, HEIGHT, 4, 7, loaded, QUERY, UNBOUNDED), "重复抓取直接复用缓存");
    assertNull(provider.cached("elsewhere", 4, 7), "不同世界不共用缓存");

    provider.invalidateWorld("world");
    assertNull(provider.cached("world", 4, 7), "世界卸载后必须整体失效");
  }

  /** 快照带写时 TTL：窗口内命中，超过 TTL 后按未命中（不再永久缓存陈旧边界判定）。 */
  @Test
  void cachedSnapshotExpiresAfterTtl() {
    AtomicLong clock = new AtomicLong(0L);
    NeighborChunkProvider provider = new NeighborChunkProvider(8, 1000L, clock::get);
    ChunkLoadedCheck loaded = (chunkX, chunkZ) -> true;

    NeighborEdges first = provider.capture("world", 0, HEIGHT, 4, 7, loaded, QUERY, UNBOUNDED);
    assertSame(first, provider.cached("world", 4, 7), "TTL 窗口内应命中");

    clock.set(1_000_000_000L); // 距写入恰好 1000 ms = TTL，视为过期
    assertNull(provider.cached("world", 4, 7), "超过 TTL 后按未命中（不再永久缓存）");
    assertEquals(0, provider.size(), "复核确认过期时条目必须真的从主存储移除（而非只返回 null）");

    NeighborEdges refreshed = provider.capture("world", 0, HEIGHT, 4, 7, loaded, QUERY, UNBOUNDED);
    assertSame(refreshed, provider.cached("world", 4, 7), "过期后重抓应回到命中");
  }

  /**
   * <b>本轮修复回归（竞态复核分支）</b>：外层判定「已过期」后、bin 锁内复核之前条目被并发
   * {@code putCache} 刚刷新——复核必须<b>保留</b>该条目并把存活值返回给调用方，绝不能按过期删除
   * （旧实现锁外 {@code remove(key, entry)} 会 value 匹配命中同一对象、把刚刷新的条目整个误删，
   * 白白多抓一次）。
   *
   * <p>单线程确定性驱动：脚本时钟在武装后的<b>第一次读</b>（外层判定）返回过期时刻、之后
   * （bin 锁内复核）返回未过期时刻——语义上等价于「复核瞬间条目刚被刷新」。
   */
  @Test
  void recheckKeepsEntryIfRefreshedBetweenExpiryCheckAndRemoval() {
    AtomicLong clock = new AtomicLong(0L);
    java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean firstArmedRead =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.function.LongSupplier scripted = () -> {
      if (!armed.get()) {
        return clock.get();
      }
      // 武装后第一读 = cached() 外层判定：距写入 2000ms > TTL 1000ms → 判过期；
      // 第二读 = computeIfPresent 复核：返回写入后 500ms < TTL → 未过期（等价于刚被刷新）
      return firstArmedRead.getAndSet(false) ? 2_000_000_000L : 500_000_000L;
    };
    NeighborChunkProvider provider = new NeighborChunkProvider(8, 1000L, scripted);
    ChunkLoadedCheck loaded = (chunkX, chunkZ) -> true;

    NeighborEdges first = provider.capture("world", 0, HEIGHT, 4, 7, loaded, QUERY, UNBOUNDED);
    armed.set(true);

    assertSame(first, provider.cached("world", 4, 7),
        "复核发现未过期（刚被并发刷新）时必须采用存活值，绝不误删");
    assertEquals(1, provider.size(), "误删不会发生：条目仍在主存储中");
  }

  /**
   * 含 null 平面（邻块未加载）的快照在 TTL 过期后必须重建：邻块随后加载时缺失平面要补回，
   * 而不是被永久缓存（这正是报告里「边界遮挡判定长期失真」的根因）。
   */
  @Test
  void staleMissingPlaneSnapshotIsRefreshedAfterTtl() {
    AtomicLong clock = new AtomicLong(0L);
    NeighborChunkProvider provider = new NeighborChunkProvider(8, 500L, clock::get);

    // 第一次：X_PLUS 邻块 (5,7) 未加载 → 该平面为 null
    NeighborEdges first = provider.capture("world", 0, HEIGHT, 4, 7,
        (chunkX, chunkZ) -> !(chunkX == 5 && chunkZ == 7), QUERY, UNBOUNDED);
    assertFalse(first.has(Side.X_PLUS), "未加载的邻块对应平面缺失");

    // 邻块现已加载，但快照仍在 TTL 内 → 复用旧快照（缺失平面暂存，属预期）
    NeighborEdges withinWindow = provider.capture("world", 0, HEIGHT, 4, 7,
        (chunkX, chunkZ) -> true, QUERY, UNBOUNDED);
    assertSame(first, withinWindow, "TTL 窗口内直接复用缓存");

    // TTL 过期后重抓 → 缺失平面被补齐
    clock.set(500_000_000L);
    NeighborEdges refreshed = provider.capture("world", 0, HEIGHT, 4, 7,
        (chunkX, chunkZ) -> true, QUERY, UNBOUNDED);
    assertTrue(refreshed.has(Side.X_PLUS), "TTL 过期后缺失平面必须重抓补齐（不再被永久缓存）");
  }

  @Test
  void cacheIsBounded() {
    NeighborChunkProvider provider = new NeighborChunkProvider(2);
    for (int i = 0; i < 6; i++) {
      provider.capture("world", 0, HEIGHT, i, 0, (chunkX, chunkZ) -> true, QUERY, UNBOUNDED);
    }
    assertEquals(2, provider.size(), "缓存条目不得超过配置上限");
  }

  @Test
  void exceptionDuringCaptureDegradesToMissing() {
    NeighborChunkProvider provider = new NeighborChunkProvider(2);
    ChunkLoadedCheck throwing = (chunkX, chunkZ) -> {
      throw new IllegalStateException("模拟 Folia 跨区域访问");
    };

    NeighborEdges edges = provider.capture("world", 0, HEIGHT, 4, 7, throwing, QUERY, UNBOUNDED);

    assertFalse(edges.has(Side.X_MINUS), "抓取异常必须降级为缺失，而不是抛给调用方");
    assertFalse(edges.has(Side.X_PLUS));
    assertFalse(edges.has(Side.Z_MINUS));
    assertFalse(edges.has(Side.Z_PLUS));
  }

  /**
   * 四个邻块都不可用（四个平面全 {@code null}）的结果<b>不入缓存</b>：方向安全但无收益，
   * 缓存只会让「全缺失」这一瞬间状态滞留到 TTL 过期，邻块随后加载也得干等。
   */
  @Test
  void allMissingEdgesAreNotCached() {
    NeighborChunkProvider provider = new NeighborChunkProvider(8);

    NeighborEdges edges = provider.capture("world", 0, HEIGHT, 4, 7,
        (chunkX, chunkZ) -> false, QUERY, UNBOUNDED);
    assertFalse(edges.has(Side.X_MINUS));
    assertFalse(edges.has(Side.X_PLUS));
    assertFalse(edges.has(Side.Z_MINUS));
    assertFalse(edges.has(Side.Z_PLUS));
    assertNull(provider.cached("world", 4, 7), "全 null 平面不得入缓存（无收益，反让缺失态滞留）");
    assertEquals(0, provider.size(), "全 null 结果不应占用缓存条目");

    // 邻块随即加载：因未缓存 → 立刻重抓并补齐（无需等 TTL 过期）
    NeighborEdges refreshed = provider.capture("world", 0, HEIGHT, 4, 7,
        (chunkX, chunkZ) -> true, QUERY, UNBOUNDED);
    assertTrue(refreshed.has(Side.X_PLUS), "此前未缓存 → 邻块加载后立刻补齐");
    assertSame(refreshed, provider.cached("world", 4, 7), "含可用平面的结果正常入缓存");
  }

  /**
   * 高度上界裁剪的等价性：真实世界里「遮挡方块一定在列上界之内」（上界 = 该列最高非空气方块），
   * 因此裁剪只该省掉读取、不该改变任何一位。
   */
  @Test
  void columnTopCutsScanWithoutChangingBits() {
    // 假世界：y ∈ [20, 25] 遮挡，y > 25 由上界裁掉——与真实不变式一致（上界之上必为空气）
    OcclusionQuery band = (x, y, z) -> y >= 20 && y <= 25;
    AtomicInteger reads = new AtomicInteger();
    OcclusionQuery counting = (x, y, z) -> {
      reads.incrementAndGet();
      return band.isOccluding(x, y, z);
    };
    ColumnTopQuery topAt25 = (x, z) -> 25;

    for (Side side : Side.values()) {
      reads.set(0);
      long[] bounded = NeighborChunkProvider.capturePlane(0, HEIGHT, side, 4, 7, counting, topAt25);
      long[] full = NeighborChunkProvider.capturePlane(0, HEIGHT, side, 4, 7, band, UNBOUNDED);

      assertArrayEquals(full, bounded, "裁剪后平面必须与全高度扫描逐位一致：side=" + side);
      assertEquals(16 * 26, reads.get(),
          "只该读到上界那一格（含）：16 列 × y=0..25；side=" + side);
    }
  }

  /** 上界查询失败（Folia 跨区域等）退回全高度扫描：结果与旧行为一致，只损失优化。 */
  @Test
  void columnTopFailureFallsBackToFullHeight() {
    AtomicInteger reads = new AtomicInteger();
    OcclusionQuery counting = (x, y, z) -> {
      reads.incrementAndGet();
      return QUERY.isOccluding(x, y, z);
    };
    ColumnTopQuery throwing = (x, z) -> {
      throw new IllegalStateException("模拟 Folia 跨区域访问");
    };

    long[] plane = NeighborChunkProvider.capturePlane(0, HEIGHT, Side.Z_PLUS, 4, 7, counting, throwing);

    assertArrayEquals(NeighborChunkProvider.capturePlane(0, HEIGHT, Side.Z_PLUS, 4, 7, QUERY, UNBOUNDED),
        plane, "退回全高度后结果必须与旧行为一致");
    assertEquals(16 * HEIGHT, reads.get(), "退回后逐格读完整个高度");
  }

  /**
   * 快照来源（{@code ChunkSnapshot} 路径）的坐标换算：贴边层必须读邻块「区块内」对应的那一列
   * （X_PLUS → localX=0、X_MINUS → localX=15、Z_PLUS → localZ=0、Z_MINUS → localZ=15），
   * 且结果与「直接按世界坐标查询」逐位一致——这是把逐格世界读取换成邻块快照后最容易写错的地方。
   */
  @Test
  void sideSnapshotIsReadAtNeighborLocalCoordinates() {
    // 位置相关（不只是 y）的遮挡函数：若世界坐标→区块内坐标换算写错，结果就会与期望不一致
    OcclusionQuery band = (x, y, z) -> y >= 20 && ((x + z) & 1) == 0;
    for (Side side : Side.values()) {
      int[] neighbor = NeighborChunkProvider.neighborChunk(side, 4, 7);
      int originX = neighbor[0] << 4;
      int originZ = neighbor[1] << 4;
      Set<Integer> localXs = new HashSet<>();
      Set<Integer> localZs = new HashSet<>();
      SideSnapshot stub = new SideSnapshot() {
        @Override
        public boolean isOccluding(int localX, int localY, int localZ) {
          localXs.add(localX);
          localZs.add(localZ);
          return band.isOccluding(originX + localX, localY, originZ + localZ);
        }

        @Override
        public int highestBlockY(int localX, int localZ) {
          return 25;
        }
      };

      long[] fromSnapshot = NeighborChunkProvider.captureSideFromSnapshot(
          0, HEIGHT, side, 4, 7, neighbor[0], neighbor[1], stub);
      long[] expected = NeighborChunkProvider.capturePlane(0, HEIGHT, side, 4, 7, band, (x, z) -> 25);

      assertArrayEquals(expected, fromSnapshot, "快照路径必须与按世界坐标查询逐位一致：side=" + side);
      switch (side) {
        case X_PLUS -> assertEquals(Set.of(0), localXs, "X_PLUS 侧只读邻块 localX=0");
        case X_MINUS -> assertEquals(Set.of(15), localXs, "X_MINUS 侧只读邻块 localX=15");
        case Z_PLUS -> assertEquals(Set.of(0), localZs, "Z_PLUS 侧只读邻块 localZ=0");
        case Z_MINUS -> assertEquals(Set.of(15), localZs, "Z_MINUS 侧只读邻块 localZ=15");
      }
    }
  }

  /** 整列为空的列（上界在世界最低高度之下）一位都不读。 */
  @Test
  void columnTopBelowWorldSkipsAirColumnsEntirely() {
    AtomicInteger reads = new AtomicInteger();
    OcclusionQuery counting = (x, y, z) -> {
      reads.incrementAndGet();
      return y <= 3;
    };
    // X_PLUS 侧 16 列的世界 z = 112 + local：偶数列有地表（顶在 y=3），奇数列整列空气
    ColumnTopQuery sparse = (x, z) -> (z & 1) == 0 ? 3 : -5;

    long[] plane = NeighborChunkProvider.capturePlane(0, HEIGHT, Side.X_PLUS, 4, 7, counting, sparse);

    assertEquals(8 * 4, reads.get(), "只有有方块的 8 列各读 4 格（y=0..3）");
    for (int local = 0; local < 16; local++) {
      for (int y = 0; y < HEIGHT; y++) {
        assertEquals(local % 2 == 0 && y <= 3, NeighborEdges.isOccludingAt(plane, y << 4 | local),
            "local=" + local + " y=" + y);
      }
    }
  }

  /**
   * 慢速参照实现：<b>逐列</b>调用 {@link NeighborChunkProvider#worldPosition}（优化前的做法），用于锁住
   * 「固定轴 + 变轴基点 + local」与逐列生成<b>逐位等价</b>。
   *
   * <p>只用无上界裁剪（{@code columnTop} 恒为世界顶部），因此参照实现无需复制裁剪逻辑。
   */
  private static long[] referencePlane(int baseY, int height, NeighborEdges.Side side, int chunkX,
      int chunkZ, OcclusionQuery query) {
    long[] plane = new long[NeighborEdges.planeLongCount(height)];
    for (int local = 0; local < 16; local++) {
      int[] position = NeighborChunkProvider.worldPosition(side, chunkX, chunkZ, local);
      for (int y = 0; y < height; y++) {
        if (query.isOccluding(position[0], baseY + y, position[1])) {
          NeighborEdges.setOccluding(plane, y << 4 | local);
        }
      }
    }
    return plane;
  }

  /**
   * <b>本轮优化回归</b>：{@code capturePlane} 改为按「固定轴 + 变轴基点 + local」生成坐标（不再为每一列
   * 分配 {@code int[2]}）。四个方向 × 正/负区块坐标（含两者皆负的角）都必须与逐列 {@code worldPosition}
   * 逐位一致——位置相关的遮挡函数会让任何轴向或符号错误立刻显形。
   */
  @Test
  void planeCoordinatesFromBaseMatchPerColumnWorldPosition() {
    OcclusionQuery band = (x, y, z) -> y >= 20 && ((x + z) & 1) == 0 && (x & 3) != 1;
    int[][] columns = {{4, 7}, {0, 0}, {-1, -1}, {-5, 3}, {-37, 128}, {128, -37}};
    for (int[] column : columns) {
      for (NeighborEdges.Side side : NeighborEdges.Side.values()) {
        long[] optimized =
            NeighborChunkProvider.capturePlane(0, HEIGHT, side, column[0], column[1], band, UNBOUNDED);
        long[] reference = referencePlane(0, HEIGHT, side, column[0], column[1], band);
        assertArrayEquals(reference, optimized,
            "side=" + side + " chunk=(" + column[0] + "," + column[1] + ")");
      }
    }
  }

  /**
   * 变轴/固定轴不得互换（把「随列变化」误放到固定轴上时，整条平面会退化成同一条世界列）。
   * 这里用只与 X 或只与 Z 相关的遮挡函数，逐列断言取到的是预期的那条轴。
   */
  @Test
  void planeVariesAlongTheExpectedAxisOnly() {
    OcclusionQuery onZ = (x, y, z) -> (z & 1) == 0;
    long[] zPlane =
        NeighborChunkProvider.capturePlane(0, HEIGHT, NeighborEdges.Side.X_PLUS, 4, 7, onZ, UNBOUNDED);
    assertEquals(1, NeighborEdges.isOccludingAt(zPlane, 0 << 4 | 0) ? 1 : 0, "X 侧 local=0 → z 偶");
    assertEquals(0, NeighborEdges.isOccludingAt(zPlane, 0 << 4 | 1) ? 1 : 0, "X 侧 local=1 → z 奇");

    OcclusionQuery onX = (x, y, z) -> (x & 1) == 0;
    long[] xPlane =
        NeighborChunkProvider.capturePlane(0, HEIGHT, NeighborEdges.Side.Z_PLUS, 4, 7, onX, UNBOUNDED);
    assertEquals(1, NeighborEdges.isOccludingAt(xPlane, 0 << 4 | 0) ? 1 : 0, "Z 侧 local=0 → x 偶");
    assertEquals(0, NeighborEdges.isOccludingAt(xPlane, 0 << 4 | 1) ? 1 : 0, "Z 侧 local=1 → x 奇");
  }

  /**
   * <b>生产逐格路径守门</b>：真实抓取（{@code captureSideFromWorld} 里的匿名 {@link SideSnapshot}）必须只查
   * 构造期建好的真值表，<b>不得</b>再逐格取方块名/归一化/查覆盖表——也<b>不得残留任何一次旧入口调用</b>：
   * 留一条就足以把整格重新拖回「取名字 → 归一化 → 哈希查找」的开销（生产采样里名字归一化占 25.66s）。
   *
   * <p>离线没有 Bukkit 世界，这条路径取不到 {@code ChunkSnapshot}，无法做行为断言，因此退化为源码结构断言
   * ——与本仓 {@code RegressionWiringGuardTest} 同一思路。断言前先剥注释，避免「说明文字里提到」被当成真实
   * 调用；读文件时先把行尾归一化成 LF，避免 CRLF/LF 混用让切片失配。
   */
  @Test
  void hotCellPathOnlyConsultsThePrecomputedTable() throws IOException {
    String source = readSource("antixray/NeighborChunkProvider.java");
    String code = stripComments(source);
    String body = methodBody(source, "captureSideFromWorld");

    assertNotNull(body, "captureSideFromWorld 必须存在（生产逐格抓取的唯一入口）");
    body = stripComments(body);
    assertTrue(body.contains(".camouflage("),
        "逐格判定必须走预计算真值表（MaterialTable#camouflage）");
    assertTrue(body.contains("getBlockData("),
        "必须读快照的逐状态 BlockData：Material 级无法区分单/双台阶");
    assertFalse(body.contains(".name()"), "逐格不得再取方块名（那是归一化的前置开销）");
    assertFalse(body.contains("normalize("), "逐格不得再做名字归一化");
    assertFalse(body.contains("camouflageOccluding"), "旧的逐格字符串入口不得残留在热路径里");
    assertFalse(code.contains("camouflageOccluding"),
        "整个类都不得再引用旧入口：任何一条分支留着它就等于绕开真值表");
    assertTrue(code.contains("MaterialTable.build("),
        "真值表必须在构造期（规则重建点）一次性建立");
  }

  /**
   * 生产构造（带非空覆盖表）走的就是建表路径：离线也必须建得出表——真值表只能由 {@code Material} 名与覆盖表
   * 算出；一旦退回 {@code Material#isOccluding()} 这类需要服务端注册表的取真值方式，本用例会直接抛错。
   */
  @Test
  void productionConstructorBuildsTableWithoutServerRegistry() {
    NeighborChunkProvider provider = new NeighborChunkProvider(8,
        Set.of("minecraft:BARRIER", "oak_slab"), Set.of("stone"));

    NeighborEdges edges = provider.capture("world", 0, HEIGHT, 4, 7,
        (chunkX, chunkZ) -> true, QUERY, UNBOUNDED);

    assertTrue(edges.has(Side.X_MINUS), "建表不影响抓取流程");
    assertEquals(1, edges.occluding(Side.X_MINUS, 25, 3), "邻块 y=25 遮挡（注入的判定照旧生效）");
    assertEquals(0, edges.occluding(Side.X_MINUS, 5, 3));
  }

  /** 读源码（行尾归一化成 LF）：相对 {@code src/main/java/net/mikumc/mikuxraynet}。 */
  private static String readSource(String relativePath) throws IOException {
    return Files.readString(Path.of("src/main/java/net/mikumc/mikuxraynet").resolve(relativePath))
        .replace("\r\n", "\n");
  }

  /** 丢掉行注释与块注释行，避免「注释里提到过」被当成真实调用。 */
  private static String stripComments(String text) {
    StringBuilder builder = new StringBuilder(text.length());
    for (String line : text.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
        builder.append('\n');
        continue;
      }
      builder.append(line).append('\n');
    }
    return builder.toString();
  }

  /** 取方法体（含花括号配对）；未找到返回 {@code null}。 */
  private static String methodBody(String source, String name) {
    Matcher matcher = Pattern.compile("long\\[\\]\\s+" + Pattern.quote(name) + "\\s*\\(")
        .matcher(source);
    if (!matcher.find()) {
      return null;
    }
    return blockAt(source, matcher.start());
  }

  /** 从 {@code fromIndex} 之后第一个左花括号开始，返回到配对右花括号为止的片段。 */
  private static String blockAt(String text, int fromIndex) {
    int brace = text.indexOf('{', fromIndex);
    if (brace < 0) {
      return "";
    }
    int depth = 0;
    for (int i = brace; i < text.length(); i++) {
      char character = text.charAt(i);
      if (character == '{') {
        depth++;
      } else if (character == '}') {
        depth--;
        if (depth == 0) {
          return text.substring(brace, i + 1);
        }
      }
    }
    return text.substring(brace);
  }
}
