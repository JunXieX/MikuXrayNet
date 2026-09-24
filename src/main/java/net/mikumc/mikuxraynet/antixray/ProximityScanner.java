package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 邻近显形的「局部扫描」：只遍历玩家身边 {@code ceil(distance/16)} 个区块半径内的区块
 * （distance=48 → 半径 3 → 7×7=49 个区块），因此单周期候选评估量与「玩家的探索历史」完全脱钩
 * ——旧实现要遍历该玩家索引里的<b>全部</b>坐标，玩家走得越远、评估量越大。
 *
 * <p>每个区块的处理：
 * <ol>
 *   <li>整块已全部显形（已显形坐标数 == 区块清单大小）→ <b>直接跳过</b>，一个坐标都不评估；</li>
 *   <li>否则取该区块的伪装坐标，减去该玩家已显形的，做距离筛选；</li>
 *   <li>结果按距离由近到远排序并截断到 {@code limit}（与旧实现一致：总是先还原脚边的矿）。</li>
 * </ol>
 *
 * <p>纯计算，不触碰任何 Bukkit API，可在任意线程调用、可离线单测。
 */
final class ProximityScanner {

  /** 单次扫描的计数（供一次性诊断与单测断言「整块跳过确实生效」）。 */
  static final class Tally {

    /** 真正逐坐标评估的区块数。 */
    int chunksScanned;
    /** 因整块已显形而直接跳过的区块数。 */
    int chunksSkipped;
    /** 逐坐标评估（距离筛选）的次数，即「每周期候选评估量」。 */
    int positionsEvaluated;
  }

  private ProximityScanner() {
  }

  /**
   * 取距离玩家不超过 {@code maxDistance}（含等于）且尚未显形的候选坐标，按距离由近到远排序。
   *
   * @param limit 返回条数上限；小于等于 0 时返回空列表
   * @param tally 计数收集器；可为 {@code null}
   * @return 候选坐标（不超过 limit 条）；无候选时返回空列表
   */
  static List<ObfuscatedChunkIndex.Position> candidates(ObfuscatedChunkIndex index,
      RevealedSet revealed, UUID playerId, String worldName, int x, int y, int z,
      double maxDistance, int limit, Tally tally) {
    if (index == null || revealed == null || playerId == null || worldName == null
        || limit <= 0 || maxDistance < 0.0D) {
      return List.of();
    }

    int radius = (int) Math.ceil(maxDistance / 16.0D);
    int centerX = x >> 4;
    int centerZ = z >> 4;
    double maxDistanceSquared = maxDistance * maxDistance;
    List<ObfuscatedChunkIndex.Position> found = new ArrayList<>();

    for (int chunkX = centerX - radius; chunkX <= centerX + radius; chunkX++) {
      for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; chunkZ++) {
        ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
        ObfuscatedChunkIndex.ChunkEntry entry = index.entry(key);
        if (entry == null) {
          continue;
        }
        if (revealed.sizeFor(playerId, key) >= entry.size()) {
          // 整块已全部显形：稳态下每周期只处理「新进入视野的区块」
          if (tally != null) {
            tally.chunksSkipped++;
          }
          continue;
        }
        revealed.touch(playerId, key);
        if (tally != null) {
          tally.chunksScanned++;
        }

        int[] locals = entry.locals();
        int minHeight = entry.minHeight();
        for (int local : locals) {
          if (tally != null) {
            tally.positionsEvaluated++;
          }
          int blockX = (chunkX << 4) | (local & 15);
          int blockZ = (chunkZ << 4) | (local >> 4 & 15);
          int blockY = minHeight + (local >> 8);
          if (distanceSquared(blockX, blockY, blockZ, x, y, z) > maxDistanceSquared) {
            continue;
          }
          if (revealed.contains(playerId, key, blockX, blockY, blockZ)) {
            continue;
          }
          found.add(new ObfuscatedChunkIndex.Position(blockX, blockY, blockZ));
        }
      }
    }

    if (found.size() <= 1) {
      return found;
    }
    found.sort(Comparator.comparingLong((ObfuscatedChunkIndex.Position position) ->
        distanceSquared(position.x(), position.y(), position.z(), x, y, z)));
    return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
  }

  private static long distanceSquared(int x, int y, int z, int otherX, int otherY, int otherZ) {
    long dx = (long) x - otherX;
    long dy = (long) y - otherY;
    long dz = (long) z - otherZ;
    return dx * dx + dy * dy + dz * dz;
  }
}