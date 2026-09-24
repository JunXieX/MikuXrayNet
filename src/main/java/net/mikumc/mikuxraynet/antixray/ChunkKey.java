package net.mikumc.mikuxraynet.antixray;

/**
 * 区块键：只含不可变类型（世界名 + 区块坐标），不可能间接钉住 World / Chunk / Player 等对象。
 *
 * <p>由 {@link ObfuscatedChunkIndex}（按区块共享被伪装坐标）与 {@link RevealedSet}（按「区块 × 玩家」
 * 记录已显形坐标）共用，保证两个结构对「同一个区块」的判断完全一致。
 */
record ChunkKey(String worldName, int chunkX, int chunkZ) {

  /** 由世界内绝对方块坐标导出所在区块的键。 */
  static ChunkKey ofBlock(String worldName, int x, int z) {
    return new ChunkKey(worldName, x >> 4, z >> 4);
  }
}