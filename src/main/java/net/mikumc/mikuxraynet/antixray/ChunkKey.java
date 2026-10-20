package net.mikumc.mikuxraynet.antixray;

import java.util.Objects;

/**
 * 区块键：只含不可变类型（世界名 + 区块坐标），不可能间接钉住 World / Chunk / Player 等对象。
 *
 * <p>由 {@link ObfuscatedChunkIndex}（按区块共享被伪装坐标）与 {@link RevealedSet}（按「区块 × 玩家」
 * 记录已显形坐标）共用，保证两个结构对「同一个区块」的判断完全一致。
 *
 * <p><b>为什么是手写 final 类而不是 {@code record}</b>：本类的 {@link #equals(Object)} 必须能识别
 * {@link Probe}（热路径复用探针，见其说明）。{@code record} 自动生成的 equals 形如
 * {@code if (!(o instanceof ChunkKey)) return false;}，一旦探针落进 {@code ConcurrentHashMap} 的
 * <b>树化桶</b>，{@code TreeNode.find} 会调用<b>存储键</b>的 equals（即 {@code ChunkKey.equals(Probe)}），
 * 探针就会查不到已存在的条目——表现为「区块明明在索引里，扫描却永远命中不了」，且只在桶内条目数
 * 超过树化阈值（默认 8）时才复现，属极难排查的隐性故障。手写 equals 后双向判等，该路径才安全。
 *
 * <p>字段全部 final、无 setter，因此仍满足「不可变、可安全作为 Map 键」的语义。
 */
final class ChunkKey {

  private final String worldName;
  private final int chunkX;
  private final int chunkZ;

  ChunkKey(String worldName, int chunkX, int chunkZ) {
    this.worldName = worldName;
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
  }

  /** 由世界内绝对方块坐标导出所在区块的键。 */
  static ChunkKey ofBlock(String worldName, int x, int z) {
    return new ChunkKey(worldName, x >> 4, z >> 4);
  }

  String worldName() {
    return worldName;
  }

  int chunkX() {
    return chunkX;
  }

  int chunkZ() {
    return chunkZ;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    // 必须同时识别 Probe：ConcurrentHashMap 树化后会用「存储键的 equals」比较查询键（见类注释）
    if (other instanceof Probe probe) {
      return this.chunkX == probe.chunkX() && this.chunkZ == probe.chunkZ()
          && Objects.equals(this.worldName, probe.worldName());
    }
    if (other instanceof ChunkKey key) {
      return this.chunkX == key.chunkX && this.chunkZ == key.chunkZ
          && Objects.equals(this.worldName, key.worldName);
    }
    return false;
  }

  @Override
  public int hashCode() {
    // 与 Probe.hashCode 必须逐字段一致，否则 ConcurrentHashMap 查不到（见 Probe 的说明）
    int result = Objects.hashCode(worldName);
    result = 31 * result + chunkX;
    result = 31 * result + chunkZ;
    return result;
  }

  @Override
  public String toString() {
    return "ChunkKey[" + worldName + ", " + chunkX + ", " + chunkZ + "]";
  }

  /**
   * <b>可复用的查询探针</b>：与 {@link ChunkKey} 内容语义完全一致（同 {@code equals}/{@code hashCode}），
   * 但字段可变，专供「按区块批量查询」的热循环使用。
   *
   * <p><b>为什么需要它</b>：{@link ChunkKey} 不可变，每次查询都必须新建实例。邻近显形扫描每周期要遍历
   * 玩家周围 13×13 = 169 个区块，逐块新建即 169 个短命对象；50 人在线、每 4 tick 一轮时约 4.2 万个/秒，
   * 全部落在主线程 / Folia 区域线程上。用可变探针后，每条扫描线程只需一个实例（{@link ThreadLocal} 持有），
   * 热路径零分配。
   *
   * <p><b>为什么安全</b>：探针只作为 {@code ConcurrentHashMap} 的<b>查询键</b>传入，绝不会被存进任何 Map
   * （存进去的永远是真正不可变的 {@link ChunkKey}），因此「键内容被改」不可能污染任何持久结构。
   * 哈希与相等判定与 {@link ChunkKey} 逐字段一致，且两边 equals 都互相识别（见 {@link ChunkKey} 的类注释）。
   */
  static final class Probe {

    private static final ThreadLocal<Probe> LOCAL = ThreadLocal.withInitial(Probe::new);

    private String worldName;
    private int chunkX;
    private int chunkZ;

    private Probe() {
    }

    /** 取当前线程的复用探针（线程封闭，无需同步）。 */
    static Probe local() {
      return LOCAL.get();
    }

    /** 指向另一个区块；返回自身以便链式使用。 */
    Probe set(String worldName, int chunkX, int chunkZ) {
      this.worldName = worldName;
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      return this;
    }

    String worldName() {
      return worldName;
    }

    int chunkX() {
      return chunkX;
    }

    int chunkZ() {
      return chunkZ;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other instanceof ChunkKey key) {
        return this.chunkX == key.chunkX() && this.chunkZ == key.chunkZ()
            && Objects.equals(this.worldName, key.worldName());
      }
      if (other instanceof Probe probe) {
        return this.chunkX == probe.chunkX && this.chunkZ == probe.chunkZ
            && Objects.equals(this.worldName, probe.worldName);
      }
      return false;
    }

    @Override
    public int hashCode() {
      int result = Objects.hashCode(worldName);
      result = 31 * result + chunkX;
      result = 31 * result + chunkZ;
      return result;
    }

    @Override
    public String toString() {
      return "ChunkKey[" + worldName + ", " + chunkX + ", " + chunkZ + "]";
    }
  }
}
