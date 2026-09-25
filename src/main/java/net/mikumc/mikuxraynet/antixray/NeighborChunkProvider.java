package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.World;

/**
 * 邻区块贴边快照提供者：只抓取 4 个水平邻区块「贴边那一层」（1 格厚）的遮挡信息，用于消除区块边界泄漏。
 *
 * <p><b>为什么需要它</b>：6 面遮挡判定在区块边界处必须知道邻块方块，否则只能保守判为「暴露」——结果就是
 * 区块边界那一圈方块永远不伪装，透视客户端可沿 16×16 网格线看到矿物。缺口只在「邻块数据缺失」时出现，
 * 因此本类既负责抓取，也把「缺失」这件事显式暴露给调用方按策略处理。
 *
 * <p><b>线程纪律</b>：{@link #capture(World, int, int)} 会访问 Bukkit 世界，<b>只能</b>在主线程 / Folia
 * 区域线程调用；{@link #cached(String, int, int)} 与返回的 {@link NeighborEdges} 是纯数据，可在工作线程
 * 任意读取。本类不持有 World / Chunk / Player 引用（键只有世界名与两个整数），世界卸载时整体失效即可。
 *
 * <p><b>容量</b>：LRU 有界缓存，键为 {@code (世界名, chunkX, chunkZ)}；每格只占 1 bit，
 * 故每条约 {@code 4 × 16 × 世界高度 / 8} 字节 = {@code 4 × 高度 / 2} 字节
 * （主世界 384 高度约 3 KB），默认上限见 {@code antixray.yml} 的 {@code neighbors.cache-maximum-size}。
 */
public final class NeighborChunkProvider {

  /** 遮挡查询（实现方必须只在允许的线程上访问世界；单测可注入假实现）。 */
  @FunctionalInterface
  interface OcclusionQuery {
    boolean isOccluding(int x, int y, int z);
  }

  /** 邻区块是否已加载（避免抓取时触发同步加载导致主线程卡顿）。 */
  @FunctionalInterface
  interface ChunkLoadedCheck {
    boolean isLoaded(int chunkX, int chunkZ);
  }

  private static final int SIDE_LENGTH = 16;

  private final int cacheMaximumSize;
  private final Map<ChunkKey, NeighborEdges> cache;

  /** 缓存键：只含不可变类型，不钉住世界对象。 */
  private record ChunkKey(String worldName, int chunkX, int chunkZ) {
  }

  public NeighborChunkProvider(int cacheMaximumSize) {
    this.cacheMaximumSize = Math.max(1, cacheMaximumSize);
    this.cache = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<ChunkKey, NeighborEdges> eldest) {
        return size() > NeighborChunkProvider.this.cacheMaximumSize;
      }
    };
  }

  /** 读取已缓存的快照；未命中返回 {@code null}（不做任何 Bukkit 访问，可在任意线程调用）。 */
  public NeighborEdges cached(String worldName, int chunkX, int chunkZ) {
    synchronized (cache) {
      return cache.get(new ChunkKey(worldName, chunkX, chunkZ));
    }
  }

  /**
   * 在主线程 / Folia 区域线程抓取该区块 4 个水平邻块的贴边层并写入缓存。
   *
   * @return 快照；个别邻块不可用时对应平面为 {@code null}（由缺失策略兜底）
   */
  public NeighborEdges capture(World world, int chunkX, int chunkZ) {
    String worldName = world.getName();
    NeighborEdges existing = cached(worldName, chunkX, chunkZ);
    if (existing != null) {
      return existing;
    }

    int baseY = world.getMinHeight();
    int height = Math.max(SIDE_LENGTH, world.getMaxHeight() - baseY);
    return capture(worldName, baseY, height, chunkX, chunkZ, world::isChunkLoaded,
        (x, y, z) -> world.getBlockData(x, y, z).isOccluding());
  }

  /**
   * 抓取核心（纯逻辑，不触碰 Bukkit；由 {@link #capture(World, int, int)} 提供真实世界查询）。
   *
   * @param baseY       世界最低建筑高度（快照内 y=0 对应它）
   * @param height      世界总高度（section 数 × 16）
   * @param chunkLoaded 邻块加载判断；未加载的邻块对应平面为 {@code null}
   * @param query       遮挡查询
   */
  NeighborEdges capture(String worldName, int baseY, int height, int chunkX, int chunkZ,
      ChunkLoadedCheck chunkLoaded, OcclusionQuery query) {
    NeighborEdges existing = cached(worldName, chunkX, chunkZ);
    if (existing != null) {
      return existing;
    }

    NeighborEdges edges = new NeighborEdges(height,
        captureSide(baseY, height, chunkX, chunkZ, NeighborEdges.Side.X_MINUS, chunkLoaded, query),
        captureSide(baseY, height, chunkX, chunkZ, NeighborEdges.Side.X_PLUS, chunkLoaded, query),
        captureSide(baseY, height, chunkX, chunkZ, NeighborEdges.Side.Z_MINUS, chunkLoaded, query),
        captureSide(baseY, height, chunkX, chunkZ, NeighborEdges.Side.Z_PLUS, chunkLoaded, query));

    synchronized (cache) {
      cache.put(new ChunkKey(worldName, chunkX, chunkZ), edges);
    }
    return edges;
  }

  private long[] captureSide(int baseY, int height, int chunkX, int chunkZ, NeighborEdges.Side side,
      ChunkLoadedCheck chunkLoaded, OcclusionQuery query) {
    try {
      int[] neighborChunk = neighborChunk(side, chunkX, chunkZ);
      if (!chunkLoaded.isLoaded(neighborChunk[0], neighborChunk[1])) {
        return null;
      }
      return capturePlane(baseY, height, side, chunkX, chunkZ, query);
    } catch (Throwable throwable) {
      // Folia 跨区域访问、世界卸载等：一律按缺失处理，交由缺失策略决定（默认 hide，不留泄漏）
      return null;
    }
  }

  /**
   * 抓取请求方某一侧的贴边层（纯逻辑；坐标换算见 {@link #worldPosition}）。
   *
   * @return 位打包的遮挡平面（长度为 {@code ceil(height × 16 / 64)}），位下标为 {@code y << 4 | localOther}
   */
  static long[] capturePlane(int baseY, int height, NeighborEdges.Side side, int chunkX, int chunkZ,
      OcclusionQuery query) {
    long[] plane = new long[NeighborEdges.planeLongCount(height)];
    for (int local = 0; local < SIDE_LENGTH; local++) {
      int[] position = worldPosition(side, chunkX, chunkZ, local);
      for (int y = 0; y < height; y++) {
        if (query.isOccluding(position[0], baseY + y, position[1])) {
          NeighborEdges.setOccluding(plane, y << 4 | local);
        }
      }
    }
    return plane;
  }

  /** 该侧邻块所在的区块坐标。 */
  static int[] neighborChunk(NeighborEdges.Side side, int chunkX, int chunkZ) {
    return switch (side) {
      case X_MINUS -> new int[] {chunkX - 1, chunkZ};
      case X_PLUS -> new int[] {chunkX + 1, chunkZ};
      case Z_MINUS -> new int[] {chunkX, chunkZ - 1};
      case Z_PLUS -> new int[] {chunkX, chunkZ + 1};
    };
  }

  /**
   * 请求方某一侧的贴边格子在邻块中的世界坐标（x, z）。
   *
   * <p>与遮挡判定一一对应：请求方 {@code x=16} 对应 {@code X_PLUS} 侧邻块的 {@code localX=0}；
   * 请求方 {@code x=-1} 对应 {@code X_MINUS} 侧邻块的 {@code localX=15}；z 方向同理。
   *
   * @param localOther 另一水平轴的区块内相对坐标（0..15）
   */
  static int[] worldPosition(NeighborEdges.Side side, int chunkX, int chunkZ, int localOther) {
    return switch (side) {
      case X_MINUS -> new int[] {(chunkX - 1) << 4 | 15, (chunkZ << 4) + localOther};
      case X_PLUS -> new int[] {(chunkX + 1) << 4, (chunkZ << 4) + localOther};
      case Z_MINUS -> new int[] {(chunkX << 4) + localOther, (chunkZ - 1) << 4 | 15};
      case Z_PLUS -> new int[] {(chunkX << 4) + localOther, (chunkZ + 1) << 4};
    };
  }

  /** 使某个世界的全部快照失效（世界卸载时调用）。 */
  public void invalidateWorld(String worldName) {
    synchronized (cache) {
      cache.keySet().removeIf(key -> key.worldName().equals(worldName));
    }
  }

  /** 使全部快照失效（配置热重载时调用）。 */
  public void invalidateAll() {
    synchronized (cache) {
      cache.clear();
    }
  }

  /** 当前缓存条目数（诊断用）。 */
  public int size() {
    synchronized (cache) {
      return cache.size();
    }
  }
}