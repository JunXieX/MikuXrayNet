package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 最近方块变更的环形日志：用于「区块包在途期间该区块被改过」时的**放行后补发**。
 *
 * <p><b>为什么需要它</b>：反矿透的区块包是在异步管线上被扣住改写的（重编码 + 可能等待邻块，常态 1~10 ms，
 * 超时上限 2.5 秒）。这段时间里服务端若又对该区块下发了方块变更（最典型：玩家边走边铺路、边放边挖，
 * 或别人在同一区块施工、爆炸），客户端会先收到那条**新的方块变更**，随后才收到**改写时刻生成的旧区块字节**
 * ——后者把刚放/刚挖的那一格盖回旧状态，观感就是「方块闪一下」，直到下一次区块下发才自愈。
 *
 * <p><b>容量必须覆盖「一次批量施工」</b>：爆炸 / 大范围施工会把同一段的几十上百格在同一个 tick 里改掉，
 * 服务端按 section 打包成一条 {@code MULTI_BLOCK_CHANGE}（最多 4096 格）。补发上限若小于这个量级，
 * 超出部分既不补发、也没有别的路径会来纠正：客户端永久保留旧区块字节，真机表现是整段幽匿脉络等
 * <b>附着方块卡在空中</b>（右键被服务端纠正后才消失）。因此容量与单次补发上限都按「一整段」定，
 * 且环形溢出会被显式标记（{@link Replay#truncated()}），由调用方改用整块重发的兜底。
 *
 * <p><b>做法</b>：每条出站方块变更按顺序记一条（世界 + 区块 + 方块坐标），环形容量固定；区块包放行后，
 * 若全局序号比「持包时刻」前进了，就把该区块在这段时间里被改过的坐标取出来（去重、上限 {@link #MAX_REPLAY}），
 * 在玩家所属线程上补发一次当前状态。补发的是**当前**方块状态（与显形包同源），不会把伪装后的状态写回客户端：
 * 被改过的坐标在 {@code BlockChangeRevealListener} 里已从伪装索引注销。
 *
 * <p><b>线程</b>：{@link #record} 在封包 / 网络线程调用（<b>必须同步</b>：投递到后台线程会与区块放行竞速，
 * 晚一步落库就等于该格永不补发），{@link #changedSince} 在放行线程调用，两者都只做无锁的原子数组读写；
 * 绝不触碰 Bukkit。
 */
public final class ChunkChangeJournal {

  /**
   * 环形容量（必须是 2 的幂）。8192 条 ≈ 0.5 MB，足以覆盖「一个 tick 内一整段被改完（4096 格）」
   * 这种最坏批量施工，也不会被随后几百毫秒的零散变更挤掉。
   */
  private static final int CAPACITY = 8192;
  private static final int MASK = CAPACITY - 1;
  /**
   * 单次放行最多补发多少格：取一个 section 的上限（4096），不设更小的任意值——超出部分没有第二条
   * 补救路径（见类注释）。真正的上界仍由环形容量兜底。
   */
  public static final int MAX_REPLAY = 4096;

  private final AtomicLong sequence = new AtomicLong();
  private final AtomicReferenceArray<Entry> ring = new AtomicReferenceArray<>(CAPACITY);

  /** 一条方块变更记录（不可变）。 */
  private record Entry(long seq, String world, int chunkX, int chunkZ, int x, int y, int z) {
  }

  /**
   * 一次「放行后补发」的查询结果。
   *
   * @param cells     需要补发的坐标（{@code {x, y, z}}，已去重、按新到旧）
   * @param truncated 窗口内是否还有没能取到的变更（环形已被后来的变更覆盖，或触到 {@link #MAX_REPLAY}）。
   *                  为 true 时调用方必须改用<b>整块重发</b>兜底：逐格补发已经补不全，而客户端手里
   *                  还是旧区块字节。
   */
  public record Replay(List<int[]> cells, boolean truncated) {
  }

  /** 当前序号（持包时取一次，放行时再比一次；相等即这段时间没有任何方块变更）。 */
  public long sequence() {
    return sequence.get();
  }

  /** 记录一条方块变更（绝对坐标）。{@code world} 为 {@code null} 时忽略。 */
  public void record(String world, int x, int y, int z) {
    if (world == null) {
      return;
    }
    long seq = sequence.incrementAndGet();
    ring.set((int) (seq & MASK), new Entry(seq, world, x >> 4, z >> 4, x, y, z));
  }

  /**
   * 取出「序号大于 {@code sinceSeq}、且属于该区块」的方块坐标（去重、按新到旧、最多 {@link #MAX_REPLAY} 个）。
   *
   * @return 每个元素是 {@code {x, y, z}}；没有则返回空列表
   */
  public List<int[]> changedSince(long sinceSeq, String world, int chunkX, int chunkZ) {
    return changedSinceReplay(sinceSeq, world, chunkX, chunkZ).cells();
  }

  /**
   * 同 {@link #changedSince}，但额外报告「是否补不全」（见 {@link Replay#truncated()}）。
   *
   * <p>补不全有两条来源：① 序号跨度超过环形容量，窗口内最早的一段已被覆盖；② 命中去重后的坐标数触到
   * {@link #MAX_REPLAY}。两者都不能靠逐格补发解决，必须由调用方整块重发。
   */
  public Replay changedSinceReplay(long sinceSeq, String world, int chunkX, int chunkZ) {
    long newest = sequence.get();
    if (world == null || newest <= sinceSeq) {
      return new Replay(List.of(), false);
    }
    boolean truncated = newest - sinceSeq > CAPACITY;
    List<int[]> cells = new ArrayList<>(4);
    Set<Long> seen = new HashSet<>(8);
    int slots = (int) Math.min(CAPACITY, newest - sinceSeq);
    for (int i = 0; i < slots; i++) {
      Entry entry = ring.get((int) ((newest - i) & MASK));
      if (entry == null || entry.seq() <= sinceSeq) {
        continue;
      }
      if (!world.equals(entry.world()) || entry.chunkX() != chunkX || entry.chunkZ() != chunkZ) {
        continue;
      }
      if (!seen.add(pack(entry.x(), entry.y(), entry.z()))) {
        continue;
      }
      if (cells.size() >= MAX_REPLAY) {
        truncated = true;
        break;
      }
      cells.add(new int[] {entry.x(), entry.y(), entry.z()});
    }
    return new Replay(cells, truncated);
  }

  /** 坐标打包（仅用于本次查询内去重）。 */
  private static long pack(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
  }
}
