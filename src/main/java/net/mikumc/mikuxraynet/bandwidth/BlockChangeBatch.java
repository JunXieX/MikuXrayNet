package net.mikumc.mikuxraynet.bandwidth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单玩家的方块变更待发缓冲与邻域合并分组（纯逻辑，不依赖 Bukkit / ProtocolLib，便于单元测试）。
 *
 * <p>合并规则：两条变更若「曼哈顿距离不超过 {@code radius}」即归入同一簇；邻域关系可传递，
 * 因此与簇内任一成员相邻的变更都会并入该簇。簇最终由调用方按 section 分组构造成合并包。
 *
 * <p>条目上限由调用方通过 {@link #add} 的返回值感知：达到上限时返回 {@code true}，
 * 调用方应立即冲刷，避免缓冲无界增长。
 *
 * @param <V> 方块数据载荷类型（纯逻辑层不关心其具体形态）
 */
public final class BlockChangeBatch<V> {

  /** 一条方块变更：绝对方块坐标 + 不透明载荷。 */
  public record Update<T>(int x, int y, int z, T value) {
  }

  private final int maxEntries;
  private final List<Update<V>> pending = new ArrayList<>();

  public BlockChangeBatch(int maxEntries) {
    this.maxEntries = Math.max(1, maxEntries);
  }

  /**
   * 当前待发条目数。
   *
   * <p>测试专用豁免：生产路径不读该值（只依 {@link #add} 的返回值与 {@link #drainClusters} 驱动），
   * 本方法当前仅单测在用，保留以免破坏测试。
   */
  public int size() {
    return pending.size();
  }

  /**
   * 待发缓冲是否为空。
   *
   * <p>测试专用豁免：生产路径不读该值，本方法当前仅单测在用，保留以免破坏测试。
   */
  public boolean isEmpty() {
    return pending.isEmpty();
  }

  /** 追加一条变更；返回 {@code true} 表示已达到条目上限，调用方应立即冲刷。 */
  public boolean add(int x, int y, int z, V value) {
    pending.add(new Update<>(x, y, z, value));
    return pending.size() >= maxEntries;
  }

  /**
   * 追加一条<b>已构造好</b>的变更（与 {@link #add(int, int, int, Object)} 语义、上限判定完全一致）。
   *
   * <p>供热路径直接复用封包解析阶段已构造的记录，省掉同一坐标的第二次等值分配。
   */
  public boolean add(Update<V> update) {
    pending.add(update);
    return pending.size() >= maxEntries;
  }

  /**
   * 按「曼哈顿距离不超过 radius」的邻域把待发变更聚成若干簇，并清空缓冲。
   *
   * <p><b>为什么改成网格分桶 + 并查集</b>：旧实现是「每簇 × while(grew) × 遍历簇内成员」三重循环，
   * 同簇 256 条的最坏情形要做约 1.6e7 次曼哈顿比较，且整段在 {@code synchronized(target)} 内执行。
   * 新实现求的是同一个图（顶点=变更，边=曼哈顿距离 ≤ radius）的连通分量：先用边长 {@code radius+1}
   * 的网格分桶（距离 ≤ radius 的两点只可能落在相邻的 3×3×3 格内），再对 27 邻格内成对判定并入并查集，
   * 复杂度降到 O(变更数 × 邻域内平均桶占用)。
   *
   * <p><b>顺序语义保持不变</b>：簇列表按「簇内最小插入下标」升序，簇内成员按插入下标升序。
   *
   * @return 簇列表；每簇内的条目集合即为一次可合并发送的更新集合
   */
  public List<List<Update<V>>> drainClusters(int radius) {
    int distance = Math.max(0, radius);
    int size = pending.size();
    List<List<Update<V>>> clusters = new ArrayList<>();
    if (size == 0) {
      pending.clear();
      return clusters;
    }

    // 网格边长取 radius+1：曼哈顿距离 ≤ radius 的两个点，其 x/y/z 格下标至多相差 1，故只需查 27 邻格
    int cell = distance + 1;
    Map<Long, List<Integer>> buckets = new HashMap<>();
    for (int i = 0; i < size; i++) {
      Update<V> update = pending.get(i);
      long key = cellKey(Math.floorDiv(update.x(), cell), Math.floorDiv(update.y(), cell),
          Math.floorDiv(update.z(), cell));
      buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
    }

    int[] parent = new int[size];
    for (int i = 0; i < size; i++) {
      parent[i] = i;
    }
    for (int i = 0; i < size; i++) {
      Update<V> update = pending.get(i);
      int cellX = Math.floorDiv(update.x(), cell);
      int cellY = Math.floorDiv(update.y(), cell);
      int cellZ = Math.floorDiv(update.z(), cell);
      for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
          for (int dz = -1; dz <= 1; dz++) {
            List<Integer> bucket = buckets.get(cellKey(cellX + dx, cellY + dy, cellZ + dz));
            if (bucket == null) {
              continue;
            }
            for (int j : bucket) {
              if (j == i) {
                continue;
              }
              Update<V> other = pending.get(j);
              // 桶仅用于剪枝：是否相连仍以精确的曼哈顿距离判定（哈希冲突只会多比几次，不影响正确性）
              int manhattan = Math.abs(update.x() - other.x())
                  + Math.abs(update.y() - other.y())
                  + Math.abs(update.z() - other.z());
              if (manhattan <= distance) {
                union(parent, i, j);
              }
            }
          }
        }
      }
    }

    // 按插入顺序归组：外层 LinkedHashMap 保证「簇按最小下标升序」，内层按 i 升序添加保证成员插入序
    Map<Integer, List<Update<V>>> byRoot = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      byRoot.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(pending.get(i));
    }
    clusters.addAll(byRoot.values());

    pending.clear();
    return clusters;
  }

  /** 网格单元键：对三个格下标做混合散列（冲突安全——见 {@link #drainClusters} 的距离判定）。 */
  private static long cellKey(int cellX, int cellY, int cellZ) {
    long hash = cellX * 0x9E3779B97F4A7C15L;
    hash ^= (cellY + 0x165667B19E3779F9L) * 0xC2B2AE3D27D4EB4FL;
    hash ^= (cellZ + 0x27D4EB2F165667C5L) * 0x9E3779B97F4A7C15L;
    return hash;
  }

  private static int find(int[] parent, int value) {
    int root = value;
    while (parent[root] != root) {
      root = parent[root];
    }
    // 路径压缩：把沿途节点直接挂到根上
    while (parent[value] != root) {
      int next = parent[value];
      parent[value] = root;
      value = next;
    }
    return root;
  }

  private static void union(int[] parent, int a, int b) {
    int rootA = find(parent, a);
    int rootB = find(parent, b);
    if (rootA != rootB) {
      parent[rootB] = rootA;
    }
  }

  /**
   * 是否存在任一变更落在「玩家所在方块坐标」的 {@code radius} 欧氏邻域内（含边界）。
   *
   * <p><b>为什么单独抽成纯函数</b>：合并模块用它决定「本次封包是立即放行还是进入合并窗口」，
   * 而这个判定不依赖 Bukkit / ProtocolLib，抽出来即可离线单测。
   *
   * <p>语义：返回值 true 表示「该封包内含近身变更」——调用方应原样立即放行，不进合并窗口；
   * false 表示照常合并。{@code radius <= 0} 表示关闭立即放行，恒为 false。
   *
   * @param updates  一次封包解析出的全部变更（可能为 null/空，此时恒为 false）
   * @param radius   立即放行半径（格）；0 或负数即关闭该特性
   */
  public static <V> boolean anyWithinRadius(List<Update<V>> updates, int playerX, int playerY, int playerZ,
      int radius) {
    if (updates == null || updates.isEmpty() || radius <= 0) {
      return false;
    }
    long limit = (long) radius * radius;
    for (Update<V> update : updates) {
      long dx = (long) update.x() - playerX;
      long dy = (long) update.y() - playerY;
      long dz = (long) update.z() - playerZ;
      if (dx * dx + dy * dy + dz * dz <= limit) {
        return true;
      }
    }
    return false;
  }
}