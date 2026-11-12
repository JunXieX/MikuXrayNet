package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 「伪装清单 + 已显形标记」的唯一维护入口。
 *
 * <p><b>不变式</b>：某玩家在区块上的已显形标记 ⊆ 该区块当前的伪装清单。它此前由三处各自维护
 * （改写写回、方块变更注销、区块/世界卸载），任一处漏清都会留下孤儿标记——{@code sizeFor} 虚增让
 * 「整块跳过」提前成立，真实未显形坐标被周期跳过（漏显形）。这些缺陷已经发生过两次（区块卸载、
 * 世界卸载），因此把成对操作收敛到本类：调用方不再直接分别调用两个索引。
 *
 * <p>线程纪律：本类只做纯内存读写，调用方负责在正确线程调用（与两个索引原语义一致）。
 */
public final class ChunkRevealBook {

  private final Supplier<ObfuscatedChunkIndex> index;
  private final Supplier<RevealedSet> revealed;

  /**
   * @param index    伪装清单（可变引用：热重载关闭邻近显形后读到 {@code null}）
   * @param revealed 已显形标记（同上）
   */
  public ChunkRevealBook(Supplier<ObfuscatedChunkIndex> index, Supplier<RevealedSet> revealed) {
    this.index = index;
    this.revealed = revealed;
  }

  /**
   * 覆盖某区块的伪装清单，并同步摘除「被移出清单」坐标上所有玩家的标记。
   *
   * @return 被移出的相对坐标（升序；空数组表示清单未缩小）
   */
  public int[] recordChunk(String worldName, int chunkX, int chunkZ, int minHeight,
      int[] localPositions) {
    ObfuscatedChunkIndex idx = index.get();
    if (idx == null) {
      return new int[0];
    }
    // 容量安全阀可能为腾位置整体淘汰其它区块：那些区块的清单消失，其标记必须同步作废。
    List<ChunkKey> evicted = idx.mayEvict(localPositions.length) ? new ArrayList<>(4) : null;
    int[] removed = idx.recordChunkWithRemovals(worldName, chunkX, chunkZ, minHeight,
        localPositions, evicted);
    RevealedSet marks = revealed.get();
    if (marks == null) {
      return removed;
    }
    if (evicted != null) {
      for (ChunkKey key : evicted) {
        marks.clearChunk(key);
      }
    }
    if (removed.length > 0) {
      int[] absolute = toAbsolute(chunkX, chunkZ, minHeight, removed);
      marks.removePositions(worldName, absolute, removed.length);
    }
    return removed;
  }

  /**
   * 注销同一区块内的多个坐标（服务端已下发这些方块变更）：清单与标记<b>一起</b>摘除。
   *
   * @return 清单中实际命中的坐标数
   */
  public int removePositions(String worldName, int[] coordinates, int count) {
    ObfuscatedChunkIndex idx = index.get();
    int removed = idx == null ? 0 : idx.removePositions(worldName, coordinates, count);
    RevealedSet marks = revealed.get();
    if (marks != null) {
      // 索引未命中也要摘标记（宁可多摘一次，也不能留孤儿标记）
      marks.removePositions(worldName, coordinates, count);
    }
    return removed;
  }

  /** 注销单个坐标；{@code true} 表示清单确实命中。 */
  public boolean removePosition(String worldName, int x, int y, int z) {
    return removePositions(worldName, new int[] {x, y, z}, 1) > 0;
  }

  /**
   * 区块失效：清单与<b>所有玩家</b>在该区块的标记一并作废。
   *
   * <p>标记清除是无条件的：区块卸载后客户端里已没有任何该区块的方块，"已显形"记录只会在重载后
   * 被当成"这块已经显形过"而漏发显形包。
   *
   * @return 是否命中并移除了清单
   */
  public boolean invalidateChunk(String worldName, int chunkX, int chunkZ) {
    ObfuscatedChunkIndex idx = index.get();
    boolean removed = idx != null && idx.invalidateChunk(worldName, chunkX, chunkZ);
    RevealedSet marks = revealed.get();
    if (marks != null) {
      marks.clearChunk(worldName, chunkX, chunkZ);
    }
    return removed;
  }

  /** 世界失效：清单（含墓碑）与所有玩家的标记一并作废。 */
  public void invalidateWorld(String worldName) {
    ObfuscatedChunkIndex idx = index.get();
    if (idx != null) {
      idx.invalidateWorld(worldName);
    }
    RevealedSet marks = revealed.get();
    if (marks != null) {
      marks.clearWorld(worldName);
    }
  }

  /** 只作废某玩家在某区块的标记（区块重发给该玩家时使用；不影响其它玩家）。 */
  public void clearChunkMarks(String worldName, int chunkX, int chunkZ, UUID playerId) {
    RevealedSet marks = revealed.get();
    if (marks != null) {
      marks.clearChunk(worldName, chunkX, chunkZ, playerId);
    }
  }

  /** 玩家退出：清理其全部标记。 */
  public void clearPlayer(UUID playerId) {
    RevealedSet marks = revealed.get();
    if (marks != null) {
      marks.clearPlayer(playerId);
    }
  }

  /**
   * 区块内相对坐标 → 绝对坐标三元组（同一区块批量摘除用）。
   *
   * <p>相对编码为 {@code 相对Y << 8 | (z & 15) << 4 | (x & 15)}；相对 Y 是整个区块内的高度偏移，
   * 不只 0..15，因此 X/Z 用段内掩码、Y 直接加最低高度。
   */
  public static int[] toAbsolute(int chunkX, int chunkZ, int minHeight, int[] localPositions) {
    int[] absolute = new int[localPositions.length * 3];
    for (int i = 0; i < localPositions.length; i++) {
      int local = localPositions[i];
      absolute[i * 3] = (chunkX << 4) | SectionCoords.x(local);
      absolute[i * 3 + 1] = minHeight + (local >> 8);
      absolute[i * 3 + 2] = (chunkZ << 4) | SectionCoords.z(local);
    }
    return absolute;
  }
}
