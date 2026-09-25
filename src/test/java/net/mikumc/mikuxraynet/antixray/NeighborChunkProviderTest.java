package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider.ChunkLoadedCheck;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider.ColumnTopQuery;
import net.mikumc.mikuxraynet.antixray.NeighborChunkProvider.OcclusionQuery;
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
}