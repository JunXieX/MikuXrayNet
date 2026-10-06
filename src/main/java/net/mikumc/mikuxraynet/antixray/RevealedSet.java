package net.mikumc.mikuxraynet.antixray;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 已显形集合（按玩家）：{@code (世界名, chunkX, chunkZ) → 玩家 UUID → 已发过显形包的坐标}。
 *
 * <p><b>为什么按玩家</b>：显形是「每个玩家各自看到什么」的私有状态——玩家 A 已显形过的坐标
 * 绝不能让玩家 B 跳过显形。因此这里只记录<b>实际发过显形包的坐标</b>，天然有界
 * （不会随「玩家探索过的历史」增长，只与「玩家身边确实解析过的坐标」相关），并随
 * 区块索引失效 / 玩家退出 / 世界卸载 / 过期而清理。
 *
 * <p><b>整块跳过</b>：{@link #sizeFor} 给出「该玩家在该区块已显形多少坐标」；邻近显形扫描时拿它与
 * 区块条目的大小比较——相等即说明整块都已显形，直接跳过该区块（稳态下每周期只处理「新进入视野的
 * 区块」）。该比较成立的前提是「已显形坐标 ⊆ 该区块当前的伪装清单」，这一不变式由三处共同保证：
 * ① 只有从区块清单里取出的坐标才会被标记；② 区块清单被覆盖（重新下发）或被摘除坐标时，
 * 本结构会同步清理（{@link #clearChunk} / {@link #removePosition}）。
 *
 * <p><b>线程纪律</b>：标记来自主线程 / Folia 区域线程（发包成功后）；清理来自主线程 / 区域线程
 * （区块卸载、玩家退出、世界卸载）与出站封包线程（方块变更注销）；读取来自区域线程 / 主线程。
 * 内部只用并发容器与两级锁——「逐条目锁」（保护单条「玩家 × 区块」标记的读写）与「逐区块映射监视器」
 * （保护映射在 {@code chunks} 中的判活 / 摘除，使并发 mark 绝不写进已脱管的孤儿映射）；纯数据结构，
 * 不触碰任何 Bukkit API，可离线单测。
 */
public final class RevealedSet {

  /**
   * 单个玩家在一个区块内已显形的坐标（打包为 long）；读写都在条目锁内进行。
   *
   * <p><b>为什么是「long 开放寻址哈希集」而不是数组线性扫描</b>：{@link #contains} / {@link #mark} 是显形
   * 热路径上最重的纯内存操作，原先每格都要在数组里持锁线性扫描（真机单区块可达数百个坐标）。改成
   * 均摊 O(1) 的哈希集后，判重与命中都不再与坐标数线性相关；对外语义（大小、判重、注销、去重）与
   * 并发纪律（读写一律在 {@link RevealedSet} 的条目锁内）完全不变，且天然无装箱、无额外对象。
   *
   * <p>装载因子保持 ≤ 0.5（{@link #add} 翻倍扩容）：线性探测在低装载下冲突极少，实现简单；
   * 容量为 2 的幂，用位与取模。空槽哨兵用独立的 {@code used} 数组——打包值本身可以是任意 long
   * （含 0），不能拿某个特殊值当空槽，否则会在坐标恰为该值时误判。
   */
  static final class Marker {

    private long[] keys = new long[8];
    private boolean[] used = new boolean[8];
    private int size;
    private volatile long updatedAtNanos;

    /** 已登记坐标数。 */
    int size() {
      return size;
    }

    /** 是否已登记该坐标。 */
    boolean contains(long value) {
      int mask = keys.length - 1;
      int index = probe(value) & mask;
      while (used[index]) {
        if (keys[index] == value) {
          return true;
        }
        index = (index + 1) & mask;
      }
      return false;
    }

    /**
     * 登记一个坐标。
     *
     * @return {@code true} 表示本次真正新增；已存在（重复标记）返回 {@code false}
     */
    boolean add(long value) {
      int mask = keys.length - 1;
      int index = probe(value) & mask;
      while (used[index]) {
        if (keys[index] == value) {
          return false;
        }
        index = (index + 1) & mask;
      }
      keys[index] = value;
      used[index] = true;
      size++;
      if (size * 2 > keys.length) {
        grow();
      }
      return true;
    }

    /**
     * 注销一个坐标。
     *
     * <p>用「向后移位删除」而不是墓碑：同一探测簇里位于空洞之后的元素依次前移，保持线性探测的
     * 「从理想位置出发必能连续走到元素」不变式，因此无需墓碑、也不会让后续查找退化。
     *
     * @return {@code true} 表示确实存在并被移除
     */
    boolean remove(long value) {
      int mask = keys.length - 1;
      int index = probe(value) & mask;
      while (used[index]) {
        if (keys[index] == value) {
          backwardShiftRemove(index, mask);
          size--;
          return true;
        }
        index = (index + 1) & mask;
      }
      return false;
    }

    /** 翻倍扩容并重哈希（仅在持有条目锁时调用）。 */
    private void grow() {
      long[] oldKeys = keys;
      boolean[] oldUsed = used;
      keys = new long[oldKeys.length << 1];
      used = new boolean[keys.length];
      int mask = keys.length - 1;
      for (int i = 0; i < oldKeys.length; i++) {
        if (!oldUsed[i]) {
          continue;
        }
        long value = oldKeys[i];
        int index = probe(value) & mask;
        while (used[index]) {
          index = (index + 1) & mask;
        }
        keys[index] = value;
        used[index] = true;
      }
    }

    /** 经典开放寻址删除（Knuth）：把空洞之后同一簇的元素前移，最后把尾部空洞置空。 */
    private void backwardShiftRemove(int hole, int mask) {
      int i = hole;
      int j = i;
      while (true) {
        j = (j + 1) & mask;
        if (!used[j]) {
          break;
        }
        int home = probe(keys[j]) & mask;
        boolean forward = j > i;
        if ((forward && (home <= i || home > j)) || (!forward && (home <= i && home > j))) {
          keys[i] = keys[j];
          i = j;
        }
      }
      used[i] = false;
      keys[i] = 0L;
    }

    /** 打包值的混合哈希：低位常是区块内小坐标，必须充分扩散后再取低位索引。 */
    private static int probe(long value) {
      long mixed = value * 0x9E3779B97F4A7C15L;
      return (int) (mixed ^ (mixed >>> 32));
    }
  }

  private final ConcurrentHashMap<ChunkKey, ConcurrentHashMap<UUID, Marker>> chunks =
      new ConcurrentHashMap<>();
  /** 每个玩家已显形的坐标总数（仅供安全阀判断；并发清理时夹在 0 以上）。 */
  private final ConcurrentHashMap<UUID, AtomicInteger> playerTotals = new ConcurrentHashMap<>();
  /** 安全阀：仅当单玩家已显形坐标越过上限（正常运营不该发生）时被放弃标记的坐标数。 */
  private final LongAdder droppedByCapacity = new LongAdder();
  /**
   * 插件运行期「累计登记」的已显形坐标数：<b>只增不减</b>，因此不随登出 / 过期 / 区块失效而回落。
   *
   * <p><b>为什么要它</b>：{@link #positionCount()} 是实时口径——玩家一登出（{@link #clearPlayer}）、
   * 或标记超过 expire-seconds 未被扫描到，就会归零。真机上因此出现过「邻近显形写了『发送 108』
   * 但显形索引显示『已显形 0』」的误读。把「实时坐标数」与「累计登记数」并列显示，
   * 数字才能自证「标记确实在记录」，而不是被当成「没记录 / 重复发包」的 bug 证据。
   */
  private final LongAdder registeredTotal = new LongAdder();

  private final int maxPositionsPerPlayer;
  private final long expireNanos;
  private final LongSupplier clock;

  /**
   * @param maxPositionsPerPlayer 单玩家已显形坐标上限（<b>安全阀</b>：正常运营下应为 0 触发）
   * @param expireSeconds         条目过期秒数（区块卸载未触发时的兜底）
   */
  public RevealedSet(int maxPositionsPerPlayer, int expireSeconds) {
    this(maxPositionsPerPlayer, TimeUnit.SECONDS.toNanos(Math.max(1L, expireSeconds)),
        System::nanoTime);
  }

  /** 测试用构造：可注入时钟与纳秒级过期时间。 */
  RevealedSet(int maxPositionsPerPlayer, long expireNanos, LongSupplier clock) {
    this.maxPositionsPerPlayer = Math.max(1, maxPositionsPerPlayer);
    this.expireNanos = Math.max(1L, expireNanos);
    this.clock = clock;
  }

  /** 该玩家在该区块已显形的坐标数（无记录返回 0）。 */
  int sizeFor(UUID playerId, ChunkKey key) {
    Marker marker = markerOf(playerId, key);
    if (marker == null) {
      return 0;
    }
    synchronized (marker) {
      return marker.size();
    }
  }

  /** 该玩家在该区块的该坐标是否已显形过。 */
  boolean contains(UUID playerId, ChunkKey key, int x, int y, int z) {
    Marker marker = markerOf(playerId, key);
    if (marker == null) {
      return false;
    }
    long packedValue = pack(x, y, z);
    synchronized (marker) {
      return marker.contains(packedValue);
    }
  }

  /**
   * 标记一个坐标已显形（发包成功后调用）。
   *
   * <p>单玩家已显形坐标越界（安全阀）时放弃本次标记：最坏结果是该坐标下次被重复显形一次，
   * 功能不降级、更不会漏显形。
   */
  void mark(UUID playerId, ChunkKey key, int x, int y, int z) {
    markIfAbsent(playerId, key, x, y, z);
  }

  /**
   * 原子「复核 + 标记」：在同一临界区内判断该坐标是否已登记，未登记才写入。
   *
   * <p><b>为什么需要它</b>：显形发包前必须复核「本坐标是否已被（同 tick 的另一条路径）显形过」——
   * 若先 {@link #contains} 再 {@link #mark}，两个动作之间有窗口，跨路径同 tick 竞争时会重复发包
   * （客户端无感但纯属浪费）。把「判重 + 写入」并进同一把条目锁，发送侧即可依据返回值做到「恰好一次」：
   * 返回 {@code false} 表示已被别人登记，本次不应再发。
   *
   * <p>锁序与 {@link #mark} 完全一致（{@code chunks} 映射监视器 → 条目锁），不引入新的加锁顺序，
   * 因此不会与 {@code expire} / {@code clearChunk} / {@code removePositions} 形成死锁。
   *
   * @return {@code true} = 本次真正新登记（或安全阀放弃登记 / 无玩家标识，按「可发送」处理，fail-open）；
   *         {@code false} = 该坐标本已登记，调用方应跳过本次发包
   */
  boolean markIfAbsent(UUID playerId, ChunkKey key, int x, int y, int z) {
    if (playerId == null) {
      // 无玩家标识：无法按玩家去重，按「可发送」处理（fail-open，绝不漏显形）
      return true;
    }
    long packedValue = pack(x, y, z);
    while (true) {
      ConcurrentHashMap<UUID, Marker> players =
          chunks.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
      // 持「映射监视器」写入。expire / clearPlayer 在把变空的映射从 chunks 摘除前也必须取得同一监视器，
      // 因此「判活 + 写入」与「摘除」互斥：新标记绝不会写进一张已脱管的 map（那样的标记不被任何路径
      // 枚举，成为孤儿，使 playerTotals 虚高）。这是保证「计数 = 存活集合大小」的关键一半。
      synchronized (players) {
        if (chunks.get(key) != players) {
          continue; // 映射已被并行摘除：重取（computeIfAbsent 会新建一张已挂载的映射）
        }
        Marker marker = players.computeIfAbsent(playerId, ignored -> new Marker());
        synchronized (marker) {
          // 并发的 expire / clearPlayer 仍可能把这条「玩家 × 区块」标记摘掉，需在 marker 锁内再确认一次。
          if (players.get(playerId) != marker) {
            continue;
          }
          marker.updatedAtNanos = clock.getAsLong();
          if (marker.contains(packedValue)) {
            return false;
          }
          AtomicInteger total = playerTotals.computeIfAbsent(playerId, ignored -> new AtomicInteger());
          if (total.get() >= maxPositionsPerPlayer) {
            droppedByCapacity.increment();
            // 安全阀放弃登记：本次未记录，按「可发送」处理（fail-open，绝不漏显形）
            return true;
          }
          marker.add(packedValue);
          total.incrementAndGet();
          registeredTotal.increment();
          return true;
        }
      }
    }
  }

  /** 刷新该玩家在该区块的活跃时间（扫描到该区块时调用，避免身边的标记被过期清掉）。 */
  void touch(UUID playerId, ChunkKey key) {
    Marker marker = markerOf(playerId, key);
    if (marker != null) {
      marker.updatedAtNanos = clock.getAsLong();
    }
  }

  /**
   * 注销一个坐标的已显形标记（服务端自行下发了该坐标的方块变更时调用）。
   *
   * <p>与 {@link ObfuscatedChunkIndex#removePosition} 配套：两者必须同步摘除，才能维持
   * 「已显形坐标 ⊆ 区块伪装清单」这一不变式。委托给批量接口，保证与 {@link #removePositions}
   * 用同一把锁、同一套判活逻辑。
   */
  void removePosition(String worldName, int x, int y, int z) {
    removePositions(worldName, new int[] {x, y, z}, 1);
  }

  /**
   * 只注销<b>某一个玩家</b>在该坐标的已显形标记（发包失败回滚时调用）。
   *
   * <p><b>为什么不能复用全局版本</b>：{@link #removePosition(String, int, int, int)} 会摘除该坐标上
   * <b>所有玩家</b>的标记。回滚场景里失败的只是「本次这个玩家」的发包——其它玩家此前成功收到的显形
   * 仍然有效，把它们一并摘掉只会让它们在下一个巡检周期重复显形一遍（白白多发包 + 多占主线程）。
   * 摘除方向本身是安全的（不会造成漏显形、也不破坏不变式），因此这只是效率问题，但成本极低。
   *
   * <p>锁序与计数口径完全复用 {@link #clearChunk(ChunkKey, UUID)} 的「判活 + 实例匹配 + 扣减」。
   */
  void removePosition(UUID playerId, String worldName, int x, int y, int z) {
    if (playerId == null || worldName == null) {
      // 无玩家标识（理论上不会发生）：保守退回全局摘除，语义不弱于调用方预期
      removePosition(worldName, x, y, z);
      return;
    }
    ChunkKey key = ChunkKey.ofBlock(worldName, x, z);
    ConcurrentHashMap<UUID, Marker> players = chunks.get(key);
    if (players == null) {
      return;
    }
    synchronized (players) {
      // 判活同构：映射已被并行摘除时它已按 size 扣减过，这里不再重复扣
      if (chunks.get(key) != players) {
        return;
      }
      Marker marker = players.get(playerId);
      if (marker == null) {
        return;
      }
      synchronized (marker) {
        if (marker.remove(pack(x, y, z))) {
          AtomicInteger total = playerTotals.get(playerId);
          if (total != null) {
            subtract(total, 1);
          }
        }
      }
    }
  }

  /**
   * 批量注销<b>同一区块</b>内的多个坐标的已显形标记（出站多方块变更包路径，一个 section 只调用一次）。
   *
   * <p><b>为什么需要它</b>：逐坐标调用会为每个坐标重复一次 {@code ChunkKey.ofBlock} + 映射查找
   * + 遍历全部玩家 + 逐 marker 加锁；一个 section 最多 4096 个坐标时会被确定性放大。这里一次定位、
   * 一次遍历、逐个 marker 内成批摘除，与 {@link ObfuscatedChunkIndex#removePositions} 的批量口径对称。
   *
   * <p><b>判活同构</b>：进入遍历前先持「逐区块映射监视器」并校验 {@code chunks.get(key) == players}
   * （与 {@link #mark} / {@link #expire} / {@link #clearChunk} 完全同一判据）。这样「判活 + 摘标记 +
   * 扣减」与 {@link #clearChunk} 的「整图摘除 + 按 {@code marker.size()} 扣减」严格互斥——
   * 否则并发（区块卸载 vs 同区块变更包）时，同一坐标会在 {@code removePosition} 里被摘一次（扣 1），
   * 又被 {@code clearChunk} 按旧 {@code size} 计一次，导致 {@code playerTotals} 被多扣（低估）。
   *
   * @param coordinates 同一区块的绝对坐标三元组（{@code x,y,z} 连续存放）；调用方保证它们同属一个区块
   * @param count       有效坐标个数（前 {@code count} 个三元组）
   */
  void removePositions(String worldName, int[] coordinates, int count) {
    if (worldName == null || coordinates == null || count <= 0 || coordinates.length < count * 3) {
      return;
    }
    // 同一批坐标必然落在同一区块：由首坐标导出区块键
    ChunkKey key = ChunkKey.ofBlock(worldName, coordinates[0], coordinates[2]);
    ConcurrentHashMap<UUID, Marker> players = chunks.get(key);
    if (players == null) {
      return;
    }
    synchronized (players) {
      if (chunks.get(key) != players) {
        // 映射已被并行的 clearChunk / expire 摘除：它们已按其 size 扣减，这里不再重复扣。
        return;
      }
      for (Map.Entry<UUID, Marker> entry : players.entrySet()) {
        Marker marker = entry.getValue();
        synchronized (marker) {
          int removed = 0;
          for (int i = 0; i < count; i++) {
            if (marker.remove(pack(coordinates[i * 3], coordinates[i * 3 + 1],
                coordinates[i * 3 + 2]))) {
              removed++;
            }
          }
          if (removed > 0) {
            AtomicInteger total = playerTotals.get(entry.getKey());
            if (total != null) {
              subtract(total, removed);
            }
          }
        }
      }
    }
  }

  /**
   * 只清<b>某个玩家</b>在该区块的已显形标记（区块封包重发给该玩家时调用）。
   *
   * <p><b>为什么需要它</b>：{@link #clearChunk(ChunkKey)} 会清掉该区块<b>所有</b>玩家的标记，
   * 但区块封包通常只重发给一个玩家——其余玩家的客户端仍保留我们此前发回的真实方块，把它们一并作废
   * 只会让它们在下一个巡检周期里把同样的坐标<b>重复显形</b>一遍（纯浪费带宽与主线程开销）。
   *
   * <p>锁序与 {@link #mark} / {@link #clearChunk} 完全一致（区块映射监视器 → 条目锁），
   * 并复用同一套判活与计数扣减口径（见 {@link #clearPlayer}）。
   */
  void clearChunk(ChunkKey key, UUID playerId) {
    if (playerId == null) {
      // 无玩家标识（理论上不会发生）：退回整体失效，语义更保守
      clearChunk(key);
      return;
    }
    ConcurrentHashMap<UUID, Marker> players = chunks.get(key);
    if (players == null) {
      return;
    }
    synchronized (players) {
      if (chunks.get(key) != players) {
        return; // 已被并行摘除：它已按其 size 扣减过，这里不再重复扣
      }
      Marker marker = players.get(playerId);
      if (marker != null) {
        synchronized (marker) {
          if (players.remove(playerId, marker)) {
            AtomicInteger total = playerTotals.get(playerId);
            if (total != null) {
              subtract(total, marker.size());
            }
          }
        }
      }
      // 摘除空映射前必须仍持「映射监视器」（mark 写入时也持它），避免并发 mark 写进被摘掉的孤儿映射
      if (players.isEmpty()) {
        chunks.remove(key, players);
      }
    }
  }

  /** 使一个区块的全部玩家的已显形标记失效（区块卸载 / 世界卸载时调用）。 */
  void clearChunk(ChunkKey key) {
    ConcurrentHashMap<UUID, Marker> players = chunks.get(key);
    if (players == null) {
      return;
    }
    // 与 expire / clearPlayer 同构：摘除整张映射前持「映射监视器」（mark 写入时也持它），使
    // 「判空 + 摘除 + 扣减」与「判活 + 写入」互斥，避免并发 mark 把新标记写进这张被摘掉的孤儿映射。
    synchronized (players) {
      if (!chunks.remove(key, players)) {
        return; // 已被并行摘除：无需重复扣减
      }
      for (Map.Entry<UUID, Marker> entry : players.entrySet()) {
        AtomicInteger total = playerTotals.get(entry.getKey());
        if (total == null) {
          continue;
        }
        Marker marker = entry.getValue();
        synchronized (marker) {
          subtract(total, marker.size());
        }
      }
    }
  }

  /** 玩家退出时清理其全部标记。 */
  void clearPlayer(UUID playerId) {
    if (playerId == null) {
      return;
    }
    for (Map.Entry<ChunkKey, ConcurrentHashMap<UUID, Marker>> chunk : chunks.entrySet()) {
      ChunkKey key = chunk.getKey();
      ConcurrentHashMap<UUID, Marker> players = chunk.getValue();
      // 持「映射监视器」（与 mark 同一把）：使「摘除标记 + 扣减计数」与「判活 + 写入」互斥，
      // 并发 mark 不会把新标记写进正被摘掉的孤儿对象（配合 mark 侧 marker 判活，保证计数与集合自洽）。
      synchronized (players) {
        Marker marker = players.get(playerId);
        if (marker != null) {
          synchronized (marker) {
            if (players.remove(playerId, marker)) {
              AtomicInteger total = playerTotals.get(playerId);
              if (total != null) {
                subtract(total, marker.size());
              }
            }
          }
        }
        if (chunks.get(key) == players && players.isEmpty()) {
          chunks.remove(key, players);
        }
      }
    }
    // 玩家退出：计数对象一并丢弃（锁内已按实时 size 扣减；并发 mark 若重建计数，由孤儿检测兜底）
    playerTotals.remove(playerId);
  }

  /** 世界卸载时清理该世界的全部标记。 */
  void clearWorld(String worldName) {
    if (worldName == null) {
      return;
    }
    for (ChunkKey key : chunks.keySet()) {
      if (key.worldName().equals(worldName)) {
        clearChunk(key);
      }
    }
  }

  /** 清空全部标记（插件停用 / 热重载时调用，不留副作用）。 */
  void clear() {
    chunks.clear();
    playerTotals.clear();
  }

  /** 清理超过过期时间的标记（巡检任务周期调用）。 */
  void expire() {
    expire(clock.getAsLong());
  }

  /**
   * 清理超过过期时间的标记。
   *
   * @param nowNanos 当前时刻（纳秒），必须与构造本结构时的时钟同一来源
   */
  void expire(long nowNanos) {
    for (Map.Entry<ChunkKey, ConcurrentHashMap<UUID, Marker>> chunk : chunks.entrySet()) {
      ChunkKey key = chunk.getKey();
      ConcurrentHashMap<UUID, Marker> players = chunk.getValue();
      for (Map.Entry<UUID, Marker> entry : players.entrySet()) {
        Marker marker = entry.getValue();
        // 「摘除 + 统计扣减」必须在同一把 marker 锁内完成：mark 也在这把锁里做「登记 + 计数加一」，
        // 两者严格串行。若像旧实现那样在锁外 players.remove，并发的 mark 可在「读 size」与「remove」
        // 之间写入新标记，使整条被整批移除（丢一次标记，良性）却按旧 size 扣减，playerTotals 只增不减
        // 地漂高，最终让安全阀 droppedByCapacity 误报。锁内 marker.size() 即移除那一刻的实时值，
        // 计数与集合始终自洽。锁粒度仍是逐「玩家 × 区块」。
        synchronized (marker) {
          if (nowNanos - marker.updatedAtNanos <= expireNanos) {
            continue;
          }
          if (players.remove(entry.getKey(), marker)) {
            AtomicInteger total = playerTotals.get(entry.getKey());
            if (total != null) {
              subtract(total, marker.size());
            }
          }
        }
      }
      // 摘除空映射前必须取得「映射监视器」（mark 写入时也持它）：否则可能在「判定已空」与「实际移除」
      // 之间被并发 mark 写入新标记，使整张脱管 map 上的标记成为孤儿、playerTotals 虚高。
      synchronized (players) {
        if (chunks.get(key) == players && players.isEmpty()) {
          chunks.remove(key, players);
        }
      }
    }
  }

  /**
   * 当前「玩家 × 区块」标记条目数（诊断用）。
   *
   * <p>测试专用豁免：生产诊断走 {@link #positionCount()}（坐标口径），
   * 本方法当前仅单测在用，保留以免破坏测试。
   */
  public int markerCount() {
    int total = 0;
    for (ConcurrentHashMap<UUID, Marker> players : chunks.values()) {
      total += players.size();
    }
    return total;
  }

  /**
   * 当前仍生效的已显形坐标总数（所有玩家合计，<b>实时口径</b>）。
   *
   * <p>玩家登出、标记过期、区块重发 / 卸载、世界卸载、热重载清空都会让这个数字回落，
   * 因此它天然会小于「累计登记」数；两者并列才能看出「标记在不在记录」。
   */
  public int positionCount() {
    int total = 0;
    for (AtomicInteger count : playerTotals.values()) {
      total += Math.max(0, count.get());
    }
    return total;
  }

  /** 插件运行期累计登记过的已显形坐标数（<b>只增不减</b>，不与登出 / 过期绑定）。 */
  public long registeredTotal() {
    return registeredTotal.sum();
  }

  /** 安全阀触发时被放弃标记的坐标数（诊断用；正常运营下应为 0，触发即说明有 bug）。 */
  public long droppedByCapacity() {
    return droppedByCapacity.sum();
  }

  private Marker markerOf(UUID playerId, ChunkKey key) {
    if (playerId == null) {
      return null;
    }
    ConcurrentHashMap<UUID, Marker> players = chunks.get(key);
    return players == null ? null : players.get(playerId);
  }

  /** 计数器只作安全阀与诊断用途：并发清理可能重叠，故夹在 0 以上。 */
  private static void subtract(AtomicInteger counter, int amount) {
    if (amount <= 0) {
      return;
    }
    counter.updateAndGet(current -> Math.max(0, current - amount));
  }

  /** x / z 各占 26 位、y 占 12 位：与旧索引同一编码，覆盖世界边界 ±3000 万。 */
  private static final int XZ_BITS = 26;
  private static final int XZ_MASK = (1 << XZ_BITS) - 1;
  private static final int Y_MASK = (1 << 12) - 1;

  private static long pack(int x, int y, int z) {
    return ((long) (x & XZ_MASK) << 38)
        | ((long) (y & Y_MASK) << 26)
        | (z & XZ_MASK);
  }
}