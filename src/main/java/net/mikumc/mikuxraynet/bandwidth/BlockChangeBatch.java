package net.mikumc.mikuxraynet.bandwidth;

import java.util.ArrayList;
import java.util.List;

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

  public int size() {
    return pending.size();
  }

  public boolean isEmpty() {
    return pending.isEmpty();
  }

  /** 追加一条变更；返回 {@code true} 表示已达到条目上限，调用方应立即冲刷。 */
  public boolean add(int x, int y, int z, V value) {
    pending.add(new Update<>(x, y, z, value));
    return pending.size() >= maxEntries;
  }

  /**
   * 按「曼哈顿距离不超过 radius」的邻域把待发变更聚成若干簇，并清空缓冲。
   *
   * @return 簇列表；每簇内的条目集合即为一次可合并发送的更新集合
   */
  public List<List<Update<V>>> drainClusters(int radius) {
    int distance = Math.max(0, radius);
    List<List<Update<V>>> clusters = new ArrayList<>();
    boolean[] assigned = new boolean[pending.size()];

    for (int i = 0; i < pending.size(); i++) {
      if (assigned[i]) {
        continue;
      }
      List<Update<V>> cluster = new ArrayList<>();
      cluster.add(pending.get(i));
      assigned[i] = true;

      boolean grew = true;
      while (grew) {
        grew = false;
        for (int j = 0; j < pending.size(); j++) {
          if (assigned[j] || !withinRadius(cluster, pending.get(j), distance)) {
            continue;
          }
          cluster.add(pending.get(j));
          assigned[j] = true;
          grew = true;
        }
      }
      clusters.add(cluster);
    }

    pending.clear();
    return clusters;
  }

  private static boolean withinRadius(List<? extends Update<?>> cluster, Update<?> candidate, int radius) {
    for (Update<?> member : cluster) {
      int manhattan = Math.abs(member.x() - candidate.x())
          + Math.abs(member.y() - candidate.y())
          + Math.abs(member.z() - candidate.z());
      if (manhattan <= radius) {
        return true;
      }
    }
    return false;
  }
}