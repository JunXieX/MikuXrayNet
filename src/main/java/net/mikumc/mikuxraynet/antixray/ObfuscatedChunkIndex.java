package net.mikumc.mikuxraynet.antixray;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 伪装区块索引（按区块共享）：{@code (世界名, chunkX, chunkZ) → 该区块被伪装过的坐标}。
 *
 * <p><b>为什么按区块共享</b>：同一区块对所有玩家的伪装结果相同（改写由「区块原始字节 + 配置」决定，
 * 且改写缓存本就按区块共享），因此<b>只存一份</b>。内存从原来的
 * {@code O(玩家数 × 各自看过的区块数)} 降到 {@code O(有伪装的已加载区块数)}——100 个玩家看同一张地图
 * 也只占 1 份。
 *
 * <p><b>失效时机</b>：① 世界卸载（{@link #invalidateWorld}）；② 区块卸载（{@link #invalidateChunk}，
 * 由 {@code ChunkUnloadEvent} 驱动，另有 {@link #expire} 过期兜底）；③ 该区块观测到方块变更
 * （{@link #removePosition}，出站方块变更封包路径，只摘除变更的那一个坐标，与既有行为一致）；
 * ④ 区块被重新下发（{@link #recordChunk} 直接覆盖该区块条目）。
 *
 * <p><b>共享索引的影响面（既有设计，非 bug）</b>：本索引按区块共享一份，而「方块变更 → 摘除该坐标」
 * 也发生在这一份上。因此<b>显形即摘除对其它玩家也有影响</b>——玩家 A 挖开某矿（或邻近显形把某坐标
 * 发回真实方块）时，该坐标会从共享清单里摘掉，玩家 B 的邻近显形随后就再也找不到它。真机若反馈
 * 「别人挖开的矿/别人那边显形过的矿，我看不见（仍是伪装方块）」，根源即在此：B 需要自己靠近触发
 * 事件显形、或该坐标被重新下发的区块重新登记，才会再次显形。这是「按区块只存一份」换内存的取舍，
 * 不是漏显形红线被破坏——B 看到的仍是伪装方块，绝不会因为 A 的动作而透视到未挖开的矿。
 *
 * <p><b>内存纪律</b>：键只有世界名与两个整数，值是 {@code int[]}（改写结果的原始数组，直接共享不复制）；
 * 全程不持有 Player / World / Chunk / 封包 的强引用。
 *
 * <p><b>线程纪律</b>：写入来自反矿透工作线程；读取来自主线程 / Folia 区域线程；失效来自主线程 /
 * 区域线程与出站封包线程。内部只用并发容器与不可变条目（活跃时间用 volatile），不触碰任何 Bukkit API。
 */
public final class ObfuscatedChunkIndex {

  /** 世界内的绝对方块坐标。 */
  public record Position(int x, int y, int z) {
  }

  /** 单个区块的伪装坐标清单：坐标不可变，活跃时间可变（扫描时刷新，供过期兜底）。 */
  static final class ChunkEntry {

    private final int minHeight;
    /** 相对坐标，编码为 {@code 区块内相对Y << 8 | 区块内z << 4 | 区块内x}。 */
    private final int[] locals;
    private volatile long updatedAtNanos;

    ChunkEntry(int minHeight, int[] locals, long updatedAtNanos) {
      this.minHeight = minHeight;
      this.locals = locals;
      this.updatedAtNanos = updatedAtNanos;
    }

    int minHeight() {
      return minHeight;
    }

    int[] locals() {
      return locals;
    }

    int size() {
      return locals.length;
    }

    long updatedAtNanos() {
      return updatedAtNanos;
    }

    void touch(long nowNanos) {
      this.updatedAtNanos = nowNanos;
    }
  }

  private final ConcurrentHashMap<ChunkKey, ChunkEntry> chunks = new ConcurrentHashMap<>();
  private final AtomicInteger totalPositions = new AtomicInteger();
  /** 安全阀：仅当坐标总量越过上限（正常运营不该发生）时按「最旧优先」淘汰掉的坐标数。 */
  private final LongAdder evictedByCapacity = new LongAdder();

  private final int maxPositions;
  private final long expireNanos;
  private final LongSupplier clock;

  /**
   * @param maxPositions 全服伪装坐标总量上限（<b>安全阀</b>：正常运营下应为 0 触发，见类注释）
   * @param expireSeconds 条目过期秒数（区块卸载未触发时的兜底）
   */
  public ObfuscatedChunkIndex(int maxPositions, int expireSeconds) {
    this(maxPositions, TimeUnit.SECONDS.toNanos(Math.max(1L, expireSeconds)), System::nanoTime);
  }

  /** 测试用构造：可注入时钟与纳秒级过期时间。 */
  ObfuscatedChunkIndex(int maxPositions, long expireNanos, LongSupplier clock) {
    this.maxPositions = Math.max(1, maxPositions);
    this.expireNanos = Math.max(1L, expireNanos);
    this.clock = clock;
  }

  /** 无移除时共享的空数组（避免常见路径——覆盖后集合未变——的每次分配）。 */
  private static final int[] NO_REMOVALS = new int[0];

  /**
   * 记录/覆盖一个区块的被伪装坐标（区块封包改写路径，工作线程调用）。
   *
   * <p>无论哪个玩家触发的改写，都只是覆盖这一条——同一区块只存一份，内存不随玩家数增长。
   * 传入数组是改写结果里的数组（此后不再被修改），直接共享、不复制。
   *
   * <p>覆盖会改变清单内容，因此<b>需要同步维护各玩家已显形标记</b>的调用方应改用
   * {@link #recordChunkWithRemovals}（见其说明）；本方法保持原语义，仅供「不关心移除差集」的
   * 调用方与单测使用。
   *
   * @param minHeight      该世界最低建筑高度
   * @param localPositions 区块内相对坐标，编码为 {@code 区块内相对Y << 8 | z << 4 | x}
   * @return 真正记录下来的坐标数（空清单返回 0，不建条目）
   */
  public int recordChunk(String worldName, int chunkX, int chunkZ, int minHeight,
      int[] localPositions) {
    int[] removed = recordChunkInternal(worldName, chunkX, chunkZ, minHeight, localPositions, null);
    return removed == null ? 0 : localPositions.length;
  }

  /**
   * 记录/覆盖，并返回「本次覆盖<b>移除掉</b>的旧坐标」（同为区块内相对坐标编码）。
   *
   * <p><b>为什么需要移除差集</b>：覆盖共享索引条目后，其它玩家在「已被移出清单的坐标」上的已显形
   * 标记若不同步摘除，就会成为孤儿标记——{@code RevealedSet.sizeFor} 虚增会让邻近显形的「整块跳过」
   * （{@code sizeFor >= entry.size()}）提前成立，真实未显形坐标被周期性跳过（标记又被扫描 touch 续期，
   * 玩家留在扫描半径内时永不过期）。这正是 {@link RevealedSet} 类注释声明的
   * 「已显形坐标 ⊆ 区块当前清单」不变式的破坏。
   *
   * <p>差集在 {@code chunks.compute}（与覆盖同一临界区）内计算，与覆盖原子生效；两个数组均为升序
   * （生产写入不变式：改写按 section→元素序生成、摘除保持相对顺序），故用归并求差，常见路径
   * （集合未变）零分配。
   *
   * @return 被移除的旧坐标（升序）；未记录（入参非法或容量安全阀放弃，清单未变）与「已记录但无移除」
   *         两种情况都返回共享空数组，调用方以 {@code length == 0} 判定「无需摘除标记」即可
   */
  public int[] recordChunkWithRemovals(String worldName, int chunkX, int chunkZ, int minHeight,
      int[] localPositions) {
    return recordChunkWithRemovals(worldName, chunkX, chunkZ, minHeight, localPositions, null);
  }

  /**
   * 记录/覆盖，并返回移除差集；同时把「因容量安全阀被淘汰的其它区块键」收集到 {@code evictedOut}。
   *
   * <p><b>为什么需要淘汰清单</b>：{@link #makeRoomFor} 在坐标总量触顶时会按「最旧优先」<b>整体移除</b>
   * 其它区块的索引条目。这些区块的清单随之消失，而其坐标上各玩家的已显形标记仍在——若不摘除就成了
   * 孤儿标记，会让邻近显形的「整块跳过」提前成立、真实坐标漏显形（与覆盖差集同一类不变式破坏）。
   * 调用方（{@code writeBack}）据此对每个被淘汰的区块执行一次「全部玩家标记作废」。
   *
   * <p>常态（未触顶）零分配：调用方可先用 {@link #mayEvict} 判断是否需要准备容器，
   * 只有真的可能淘汰时才传入非 null 的列表。
   *
   * @param evictedChunksOut 非 null 时收集被淘汰的区块键（可能为空列表）；null 表示调用方不关心
   */
  public int[] recordChunkWithRemovals(String worldName, int chunkX, int chunkZ, int minHeight,
      int[] localPositions, List<ChunkKey> evictedChunksOut) {
    int[] removed = recordChunkInternal(worldName, chunkX, chunkZ, minHeight, localPositions,
        evictedChunksOut);
    return removed == null ? NO_REMOVALS : removed;
  }

  /**
   * 本次记录是否会触发容量安全阀淘汰（{@code 坐标总量 + 本次量 > 上限}）。
   *
   * <p>供调用方在热路径上做「是否需要准备淘汰收集容器」的零分配预判：常态下只读一次原子计数。
   *
   * <p><b>已知且刻意接受的竞态</b>：预判为 false（不准备容器）与真正 {@code recordChunkWithRemovals}
   * 之间，若有并发记录推高总量，本次淘汰会带 null 容器静默发生——调用方拿不到被淘汰区块键，
   * 无法作废其已显形标记（成为孤儿标记，最坏让「整块跳过」提前成立、个别坐标漏显形一次）。
   * 触发条件双重稀有：安全阀正常运营恒为 0，且必须恰有并发记录同时触顶；为此在热路径多一次容器
   * 分配（或让正常记录莫名失败）不划算，故按项目惯例明确接受并在此文档化。
   */
  public boolean mayEvict(int incoming) {
    return totalPositions.get() + incoming > maxPositions;
  }

  /**
   * 覆盖记录的公共实现：返回 {@code null} 表示未记录；否则返回「旧清单 ∖ 新清单」的移除差集
   * （升序，可能为共享空数组 {@link #NO_REMOVALS}）。仅供本类公开入口委托使用。
   */
  private int[] recordChunkInternal(String worldName, int chunkX, int chunkZ, int minHeight,
      int[] localPositions, List<ChunkKey> evictedChunksOut) {
    if (worldName == null || localPositions == null || localPositions.length == 0) {
      return null;
    }
    // 安全阀：正常运营下永不触发；触顶时按「最旧优先」淘汰其它区块来腾位置，仍放不下才放弃本次记录
    if (!makeRoomFor(localPositions.length, evictedChunksOut)) {
      evictedByCapacity.add(localPositions.length);
      return null;
    }

    ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
    ChunkEntry entry = new ChunkEntry(minHeight, localPositions, clock.getAsLong());
    // 用 compute 原子地「取旧值 + 装新值」：旧实现先 put 再另算 delta，同键并发 recordChunk 时
    // 两个线程可能读到同一个旧条目，导致 totalPositions 被重复加减（只影响安全阀诊断口径，
    // 且已夹在 0 以上；这里改为原子口径，与 RevealedSet 的计数纪律对齐）。
    // 移除差集同样在 compute 内算：与覆盖原子生效，并发覆盖不会拿到过期的旧清单去算差集。
    int[] delta = {0};
    int[][] removedHolder = {NO_REMOVALS};
    chunks.compute(key, (ignored, previous) -> {
      delta[0] = localPositions.length - (previous == null ? 0 : previous.size());
      removedHolder[0] = previous == null ? NO_REMOVALS
          : removedByOverwrite(previous.locals(), localPositions);
      return entry;
    });
    totalPositions.updateAndGet(current -> Math.max(0, current + delta[0]));
    return removedHolder[0];
  }

  /**
   * 求「旧清单 ∖ 新清单」的移除差集（两者均升序、无重复——生产写入不变式）。
   *
   * <p>两趟归并：第一趟只计数（集合未变时常见路径——改写确定性重登记同一份数组——零分配直接返回），
   * 有移除才分配精确长度的数组走第二趟。
   *
   * <p><b>无序入参的失败方向（仅可能来自绕过生产写入方的外部调用）</b>：错序会让指针越过本该匹配的
   * 元素，产生<b>越摘</b>——把仍存在于新清单里的坐标误报为 removed。后果只是多摘一枚标记，
   * 该坐标下次巡检<b>重复显形一次</b>（浪费一次带宽，仍满足 revealed ⊆ listing 不变式），
   * 属安全方向；而「本应摘除却漏摘」（会虚增 sizeFor、破坏不变式）在此算法下结构性不可能——
   * 旧清单元素在新清单中找不到匹配位必然计入。生产输入（改写输出 + 摘除保序）恒为严格升序，不可达。
   */
  private static int[] removedByOverwrite(int[] previousLocals, int[] newLocals) {
    if (previousLocals == newLocals) {
      return NO_REMOVALS;
    }
    int count = 0;
    int j = 0;
    for (int i = 0; i < previousLocals.length; i++) {
      int value = previousLocals[i];
      while (j < newLocals.length && newLocals[j] < value) {
        j++;
      }
      if (j >= newLocals.length || newLocals[j] > value) {
        count++;
      } else {
        j++;
      }
    }
    if (count == 0) {
      return NO_REMOVALS;
    }
    int[] removed = new int[count];
    int write = 0;
    j = 0;
    for (int i = 0; i < previousLocals.length; i++) {
      int value = previousLocals[i];
      while (j < newLocals.length && newLocals[j] < value) {
        j++;
      }
      if (j >= newLocals.length || newLocals[j] > value) {
        removed[write++] = value;
      } else {
        j++;
      }
    }
    return removed;
  }

  /** 取出区块条目，并刷新其活跃时间（供邻近显形扫描使用；未命中返回 {@code null}）。 */
  ChunkEntry entry(ChunkKey key) {
    ChunkEntry entry = chunks.get(key);
    if (entry != null) {
      entry.touch(clock.getAsLong());
    }
    return entry;
  }

  /**
   * 注销一个坐标（服务端自行下发了该坐标的方块变更时调用）。
   *
   * <p>只从「该坐标所属区块」的清单里摘除这一个坐标，其它坐标不受影响；清单被摘空时整条移除。
   *
   * @return 是否命中并注销
   */
  public boolean removePosition(String worldName, int x, int y, int z) {
    if (worldName == null) {
      return false;
    }
    ChunkKey key = ChunkKey.ofBlock(worldName, x, z);
    boolean[] hit = {false};
    chunks.computeIfPresent(key, (ignored, entry) -> {
      int local = ((y - entry.minHeight()) << 8) | ((z & 15) << 4) | (x & 15);
      int[] locals = entry.locals();
      // 二分查找依赖「locals 严格升序」不变式，该不变式已在写入路径核实成立：
      // 唯一的生产写入方 ObfuscationProcessor#rewrite 按「section 升序 → 段内 index 升序」逐个登记，
      // 编码 {@code 相对Y << 8 | z << 4 | x} 随遍历严格递增；摘除一个元素（保持其余相对顺序）
      // 与重新 recordChunk（整表替换）都不破坏有序性。原线性查找 O(n) 改为二分 O(log n)。
      // 注意：recordChunk 是公开 API、不校验顺序——若有外部调用方传入无序数组，二分可能漏摘
      // （返回 false，即「未命中」），本项目内不存在这样的调用方。
      int found = Arrays.binarySearch(locals, local);
      if (found < 0) {
        return entry;
      }
      hit[0] = true;
      subtract(totalPositions, 1);
      if (locals.length == 1) {
        return null;
      }
      int[] reduced = new int[locals.length - 1];
      System.arraycopy(locals, 0, reduced, 0, found);
      System.arraycopy(locals, found + 1, reduced, found, locals.length - found - 1);
      return new ChunkEntry(entry.minHeight(), reduced, entry.updatedAtNanos());
    });
    return hit[0];
  }

  /**
   * 批量注销<b>同一区块</b>内的多个坐标（出站多方块变更包路径，一个 section 只调用一次）。
   *
   * <p><b>为什么需要它</b>：{@link #removePosition} 每摘一个坐标都要二分定位后把整个 {@code locals}
   * 数组整段复制一次；一个 section 最多 4096 个坐标时会退化成 {@code O(k·n)} 的元素拷贝（最坏千万级）。
   * 这里把同一区块的一批坐标合并成「一次定位 + 一次重建」：先用二分在原始数组里标出全部命中位置，
   * 再一次性重建，净成本从 {@code O(k·n)} 降到 {@code O(n + k·log n)}。
   *
   * <p>与 {@link #removePosition} 语义一致：只摘传入坐标，其它坐标不受影响；清单被摘空时整条移除。
   * 传入坐标允许重复（重复项只计一次命中）。
   *
   * @param coordinates 绝对坐标三元组（{@code x,y,z} 连续存放）；调用方保证它们同属一个区块
   * @param count       有效坐标个数（前 {@code count} 个三元组）
   * @return 实际命中并摘除的坐标数
   */
  public int removePositions(String worldName, int[] coordinates, int count) {
    if (worldName == null || coordinates == null || count <= 0 || coordinates.length < count * 3) {
      return 0;
    }
    // 同一 section 的坐标必然落在同一区块：由首坐标导出区块键
    ChunkKey key = ChunkKey.ofBlock(worldName, coordinates[0], coordinates[2]);
    int[] hitCount = {0};
    chunks.computeIfPresent(key, (ignored, entry) -> {
      int[] locals = entry.locals();
      int minHeight = entry.minHeight();
      boolean[] removed = new boolean[locals.length];
      int hits = 0;
      for (int i = 0; i < count; i++) {
        int local = ((coordinates[i * 3 + 1] - minHeight) << 8)
            | ((coordinates[i * 3 + 2] & 15) << 4) | (coordinates[i * 3] & 15);
        int found = Arrays.binarySearch(locals, local);
        if (found >= 0 && !removed[found]) {
          removed[found] = true;
          hits++;
        }
      }
      if (hits == 0) {
        return entry;
      }
      hitCount[0] = hits;
      subtract(totalPositions, hits);
      if (hits == locals.length) {
        return null;
      }
      int[] reduced = new int[locals.length - hits];
      int write = 0;
      for (int i = 0; i < locals.length; i++) {
        if (!removed[i]) {
          reduced[write++] = locals[i];
        }
      }
      return new ChunkEntry(minHeight, reduced, entry.updatedAtNanos());
    });
    return hitCount[0];
  }

  /**
   * 查询一个绝对方块坐标当前是否仍在伪装清单里（只读，不修改任何状态）。
   *
   * <p>供事件驱动即时显形的触发判定使用：出站方块变更包的解析线程会调用本方法做
   * 「邻域内是否还有伪装坐标」的初筛，因此实现必须是线程安全的纯读（与 {@link #removePosition}
   * 使用同一套编码与查找逻辑）。
   */
  public boolean containsPosition(String worldName, int x, int y, int z) {
    if (worldName == null) {
      return false;
    }
    return containsPosition(new ChunkKey(worldName, x >> 4, z >> 4), x, y, z);
  }

  /**
   * 以「调用方已算好的区块键」查询（只读）。
   *
   * <p><b>为什么要这个重载</b>：邻域初筛会对同一个坐标的每个曼哈顿偏移各查一次（默认半径 2 → 25 次），
   * 而这些偏移只落在至多 4 个区块里。若每次都走上面那个重载，就会为每次查找新建一个 {@link ChunkKey}
   * ——一个塞满 4096 坐标的 {@code MULTI_BLOCK_CHANGE} 因此产生约 10 万个短命对象，全部落在共享的
   * 封包解析线程上。调用方按区块缓存键后，这次分配降到 O(涉及区块数)。
   */
  public boolean containsPosition(ChunkKey key, int x, int y, int z) {
    if (key == null) {
      return false;
    }
    ChunkEntry entry = chunks.get(key);
    if (entry == null) {
      return false;
    }
    int local = ((y - entry.minHeight()) << 8) | ((z & 15) << 4) | (x & 15);
    return Arrays.binarySearch(entry.locals(), local) >= 0;
  }

  /**
   * 使一个区块的伪装清单失效（区块卸载时调用）。
   *
   * @return 是否命中并移除
   */
  public boolean invalidateChunk(String worldName, int chunkX, int chunkZ) {
    if (worldName == null) {
      return false;
    }
    ChunkEntry removed = chunks.remove(new ChunkKey(worldName, chunkX, chunkZ));
    if (removed == null) {
      return false;
    }
    subtract(totalPositions, removed.size());
    return true;
  }

  /** 使某个世界的全部条目失效（世界卸载时调用）。 */
  public void invalidateWorld(String worldName) {
    if (worldName == null) {
      return;
    }
    for (ChunkKey key : chunks.keySet()) {
      if (!key.worldName().equals(worldName)) {
        continue;
      }
      ChunkEntry removed = chunks.remove(key);
      if (removed != null) {
        subtract(totalPositions, removed.size());
      }
    }
  }

  /** 清理超过过期时间且近期未被扫描到的条目（巡检任务周期调用）。 */
  public void expire() {
    expire(clock.getAsLong());
  }

  /**
   * 清理超过过期时间的条目。
   *
   * @param nowNanos 当前时刻（纳秒），必须与构造索引时的时钟同一来源
   */
  public void expire(long nowNanos) {
    for (Map.Entry<ChunkKey, ChunkEntry> entry : chunks.entrySet()) {
      ChunkEntry value = entry.getValue();
      if (nowNanos - value.updatedAtNanos() <= expireNanos) {
        continue;
      }
      if (chunks.remove(entry.getKey(), value)) {
        subtract(totalPositions, value.size());
      }
    }
  }

  /** 清空全部条目（插件停用 / 热重载时调用，不留副作用）。 */
  public void clear() {
    chunks.clear();
    totalPositions.set(0);
  }

  /** 当前伪装区块数（诊断用）。 */
  public int chunkCount() {
    return chunks.size();
  }

  /** 当前累计伪装坐标数（诊断用）。 */
  public int positionCount() {
    return Math.max(0, totalPositions.get());
  }

  /** 安全阀触发时淘汰掉的坐标数（诊断用；正常运营下应为 0，触发即说明有 bug）。 */
  public long evictedByCapacity() {
    return evictedByCapacity.sum();
  }

  /**
   * 安全阀：坐标总量越界时按「最旧优先」淘汰其它区块，直到放得下本次记录。
   *
   * <p>只在越过上限时执行（正常运营永不进入），因此这里的 O(条目数) 扫描不在热路径上。
   *
   * @return 是否已为 {@code incoming} 个坐标腾出空间
   */
  private boolean makeRoomFor(int incoming, List<ChunkKey> evictedChunksOut) {
    if (totalPositions.get() + incoming <= maxPositions) {
      return true;
    }
    while (totalPositions.get() + incoming > maxPositions) {
      ChunkKey oldestKey = null;
      long oldest = Long.MAX_VALUE;
      for (Map.Entry<ChunkKey, ChunkEntry> entry : chunks.entrySet()) {
        if (entry.getValue().updatedAtNanos() < oldest) {
          oldest = entry.getValue().updatedAtNanos();
          oldestKey = entry.getKey();
        }
      }
      if (oldestKey == null) {
        break;
      }
      ChunkEntry removed = chunks.remove(oldestKey);
      if (removed != null) {
        subtract(totalPositions, removed.size());
        evictedByCapacity.add(removed.size());
        if (evictedChunksOut != null) {
          // 交给调用方去作废该区块上所有玩家的已显形标记（索引条目已整体消失，标记会成孤儿）
          evictedChunksOut.add(oldestKey);
        }
      }
    }
    return totalPositions.get() + incoming <= maxPositions;
  }

  /** 计数器只作诊断用途：并发失效与整体清理可能重叠，故夹在 0 以上。 */
  private static void subtract(AtomicInteger counter, int amount) {
    if (amount <= 0) {
      return;
    }
    counter.updateAndGet(current -> Math.max(0, current - amount));
  }
}