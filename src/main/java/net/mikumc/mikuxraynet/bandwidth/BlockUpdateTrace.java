package net.mikumc.mikuxraynet.bandwidth;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 最近「块更新处置」的定长环：记录每个多坐标包，以及任何一次丢弃 / 补发 / 合批校验失败。
 *
 * <p><b>为什么需要它</b>：实机反馈「门只开一半、另一半还挡人」，而面板只能给出总数
 * （丢弃 62.8 万、合批 77 万批次），既说不出某一个坐标到底走了哪条路，也说不出它最后有没有发出去。
 * 一份 dump 于是只能证明「整体在跑」，无法回答「这两片门怎么样了」。环形日志把最近 32 次处置
 * 连同坐标一起留下，下一次 dump 就能直接看到那两片门是 PASS、MERGE、DISCARD 还是 VERIFY_FAIL。
 *
 * <p><b>为什么只记多坐标包与异常</b>：单坐标的普通变更每秒上万条，全记会把环冲干净、还平白增加
 * 封包线程的分配。真正需要取证的是「一个包带多个坐标」（门 / 床 / 双箱 / 活塞这类成对结构）
 * 与「被丢弃 / 被补发 / 校验不过」这三种情形。
 *
 * <p><b>线程</b>：封包线程写、诊断线程读；只做无锁原子数组读写，不持锁、不触碰 Bukkit。
 * 容量固定，内存约 32 × 一条记录，与在线人数无关。
 */
public final class BlockUpdateTrace {

  /** 一次处置的结论。 */
  public enum Decision {
    /** 原包按原样放行（近身变更 / 已聚合批量包 / 无法判定世界的直通）。 */
    PASS,
    /** 原包被取消并进入合并窗口。 */
    MERGE,
    /** 整批丢弃，且按「旧世界区块会重建」处理（只有真切维时才允许）。 */
    DISCARD,
    /** 本应丢弃，但世界未变 → 改为补发（{@link #REDEEM}）。 */
    REDEEM,
    /** 合并包回读校验不通过 → 放弃该包并逐条补发。 */
    VERIFY_FAIL
  }

  /**
   * 一条处置记录。
   *
   * @param seq        全局递增序号（判断新旧与「期间是否发生过处置」）
   * @param decision   结论
   * @param world      世界名（未知为 {@code null}）
   * @param chunkX     首坐标所在区块 X
   * @param chunkZ     首坐标所在区块 Z
   * @param coordCount 本包的坐标数
   * @param multi      是否为多坐标包（{@code MULTI_BLOCK_CHANGE}）
   * @param x          首坐标 X
   * @param y          首坐标 Y
   * @param z          首坐标 Z
   * @param note       附加说明（丢弃来源 / 失败原因），可为 {@code null}
   */
  public record Entry(long seq, Decision decision, String world, int chunkX, int chunkZ,
      int coordCount, boolean multi, int x, int y, int z, String note) {

    /** 单行文本（dump 用；不含时间戳，序号即顺序）。 */
    public String line() {
      StringBuilder builder = new StringBuilder(96);
      builder.append('#').append(seq).append(' ').append(decision.name());
      if (world != null) {
        builder.append(' ').append(world);
      }
      builder.append(" chunk(").append(chunkX).append(',').append(chunkZ).append(')');
      builder.append(' ').append(coordCount).append(" 格");
      if (multi) {
        builder.append("（多坐标包）");
      }
      builder.append(" 首坐标(").append(x).append(',').append(y).append(',').append(z).append(')');
      if (note != null && !note.isEmpty()) {
        builder.append(" — ").append(note);
      }
      return builder.toString();
    }
  }

  /** 环容量（必须是 2 的幂，便于用掩码定位）。 */
  static final int CAPACITY = 32;
  private static final int MASK = CAPACITY - 1;

  private final AtomicLong sequence = new AtomicLong();
  private final AtomicReferenceArray<Entry> ring = new AtomicReferenceArray<>(CAPACITY);

  /**
   * 记录一次处置。
   *
   * @param decision 结论；{@code null} 时忽略（缺省不记，绝不因取证而抛异常）
   * @param world    世界名，可为 {@code null}
   * @param multi    是否多坐标包
   * @param x        首坐标（没有坐标时传 0）
   */
  public void record(Decision decision, String world, boolean multi, int coordCount,
      int x, int y, int z, String note) {
    if (decision == null) {
      return;
    }
    try {
      long seq = sequence.incrementAndGet();
      ring.set((int) (seq & MASK), new Entry(seq, decision, world, x >> 4, z >> 4,
          Math.max(0, coordCount), multi, x, y, z, note));
    } catch (Throwable ignored) {
      // 取证绝不能影响发包
    }
  }

  /**
   * 以坐标数组记录一次处置（首坐标取自数组前三项）。
   *
   * @param coordinates 绝对坐标三元组，可为 {@code null}
   * @param count       有效坐标数
   */
  public void record(Decision decision, String world, boolean multi, int[] coordinates, int count,
      String note) {
    if (coordinates == null || count <= 0 || coordinates.length < 3) {
      record(decision, world, multi, Math.max(0, count), 0, 0, 0, note);
      return;
    }
    record(decision, world, multi, count, coordinates[0], coordinates[1], coordinates[2], note);
  }

  /** 按「最新在前」导出当前环内容（跳过空槽）。 */
  public List<Entry> snapshot() {
    long newest = sequence.get();
    long oldest = Math.max(1L, newest - CAPACITY + 1L);
    List<Entry> entries = new ArrayList<>(CAPACITY);
    for (long seq = newest; seq >= oldest; seq--) {
      Entry entry = ring.get((int) (seq & MASK));
      if (entry != null && entry.seq() == seq) {
        entries.add(entry);
      }
    }
    return entries;
  }

  /** 已记录条数（含被覆盖的）；测试用。 */
  public long recorded() {
    return sequence.get();
  }
}
