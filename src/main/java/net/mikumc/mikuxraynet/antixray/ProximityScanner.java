package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
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
 * <p><b>活跃时间刷新</b>：只要区块在扫描半径内，无论它是否被「整块跳过」，都会刷新该玩家在此区块的
 * 已显形标记活跃时间——否则整块显形的区块会因长期未被刷新而被过期清掉，「整块跳过」随之周期性失效。
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

  /** 带距离与到达序号的候选（堆选择需要次级键来保持「等距候选先到者在前」的稳定顺序）。 */
  private record Scored(ObfuscatedChunkIndex.Position position, long distanceSquared, int sequence) {
  }

  private ProximityScanner() {
  }

  /**
   * 取距离玩家不超过 {@code maxDistance}（含等于）且尚未显形的候选坐标，按距离由近到远排序。
   *
   * <p>实现说明：取前 {@code limit} 条用「容量上限的最大堆」做部分选择（O(n·log limit)），
   * 取代旧实现的全量排序（O(n·log n)）——limit 是调用方按「每 tick 上限 × 4」的超额口径传入的
   * （见 {@code ProximityRevealer} 的 CANDIDATE_OVERSAMPLE，另有 512 硬顶），候选数可达数千，
   * 绝大多数注定落在 limit 之外。语义与全量排序逐条等价：比较键同为距离平方；等距候选按
   * 「先扫描者在前」稳定输出（次级键 sequence 即到达序号，等价于旧稳定排序保留的遍历序）。
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
    // 最大堆：堆顶是「最差」的候选——距离最大；等距时后到者优先（腾位置时保留先到者）
    PriorityQueue<Scored> worst = new PriorityQueue<>((left, right) -> {
      int byDistance = Long.compare(right.distanceSquared(), left.distanceSquared());
      return byDistance != 0 ? byDistance : Integer.compare(right.sequence(), left.sequence());
    });
    int sequence = 0;

    for (int chunkX = centerX - radius; chunkX <= centerX + radius; chunkX++) {
      for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; chunkZ++) {
        ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
        ObfuscatedChunkIndex.ChunkEntry entry = index.entry(key);
        if (entry == null) {
          continue;
        }
        // 刷新该玩家在此区块的活跃时间必须放在「整块跳过」判定之前：整块已显形的区块不再逐坐标评估，
        // 若此时不刷新，它的标记会在 expire-seconds 后被过期清掉，导致「整块跳过」周期性失效、
        // 同一批坐标被反复重新评估与重复发包（真机上表现为「20 万次判定只换来百余次发送」）。
        revealed.touch(playerId, key);
        if (revealed.sizeFor(playerId, key) >= entry.size()) {
          // 整块已全部显形：稳态下每周期只处理「新进入视野的区块」
          if (tally != null) {
            tally.chunksSkipped++;
          }
          continue;
        }
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
          offer(worst, new ObfuscatedChunkIndex.Position(blockX, blockY, blockZ),
              distanceSquared(blockX, blockY, blockZ, x, y, z), sequence++, limit);
        }
      }
    }

    // 按旧实现的输出契约还原：距离升序、等距按到达序
    Scored[] ordered = worst.toArray(new Scored[0]);
    Arrays.sort(ordered, (left, right) -> {
      int byDistance = Long.compare(left.distanceSquared(), right.distanceSquared());
      return byDistance != 0 ? byDistance : Integer.compare(left.sequence(), right.sequence());
    });
    List<ObfuscatedChunkIndex.Position> result = new ArrayList<>(ordered.length);
    for (Scored scored : ordered) {
      result.add(scored.position());
    }
    return result;
  }

  /** 往容量上限为 {@code limit} 的最大堆里放一个候选：堆满时淘汰堆顶（最差者）。 */
  private static void offer(PriorityQueue<Scored> worst, ObfuscatedChunkIndex.Position position,
      long distanceSquared, int sequence, int limit) {
    Scored candidate = new Scored(position, distanceSquared, sequence);
    if (worst.size() < limit) {
      worst.add(candidate);
      return;
    }
    Scored currentWorst = worst.peek();
    // candidate 优于堆顶（距离更近；等距时更早到达）才值得挤掉它
    if (Long.compare(distanceSquared, currentWorst.distanceSquared()) < 0
        || (distanceSquared == currentWorst.distanceSquared()
            && sequence < currentWorst.sequence())) {
      worst.poll();
      worst.add(candidate);
    }
  }

  private static long distanceSquared(int x, int y, int z, int otherX, int otherY, int otherZ) {
    long dx = (long) x - otherX;
    long dy = (long) y - otherY;
    long dz = (long) z - otherZ;
    return dx * dx + dy * dy + dz * dz;
  }
}