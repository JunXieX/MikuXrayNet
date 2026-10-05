package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

/**
 * 邻区块贴边快照提供者：只抓取 4 个水平邻区块「贴边那一层」（1 格厚）的遮挡信息，用于消除区块边界泄漏。
 *
 * <p><b>为什么需要它</b>：6 面遮挡判定在区块边界处必须知道邻块方块，否则只能保守判为「暴露」——结果就是
 * 区块边界那一圈方块永远不伪装，透视客户端可沿 16×16 网格线看到矿物。缺口只在「邻块数据缺失」时出现，
 * 因此本类既负责抓取，也把「缺失」这件事显式暴露给调用方按策略处理。
 *
 * <p><b>线程纪律</b>：{@link #capture(World, int, int)} 会访问 Bukkit 世界，<b>只能</b>在主线程 / Folia
 * 区域线程调用；{@link #cached(String, int, int)} 与返回的 {@link NeighborEdges} 是纯数据，可在工作线程
 * 任意读取。本类不持有 World / Chunk / Player 引用（键只有世界名与两个整数），世界卸载时整体失效即可。
 *
 * <p><b>一次抓取、稳定读取</b>：生产路径先用 {@code Chunk#getChunkSnapshot(true,false,false)} 把每个邻块
 * 抓成一份快照（一次复制该区块的方块数据与高度图），随后所有逐格读取都走快照的区块内数组查询——
 * 不再经世界→区块解析（遮挡判定仍用 {@code BlockData#isOccluding()}，与旧实现逐格等价）。快照只在本
 * 方法内短暂存活，不进入缓存，抓取逻辑仍可离线构造桩验证（见 {@link SideSnapshot}）。
 *
 * <p><b>按列高度上界裁剪扫描</b>：每列先问一次该列的「最高非空气方块」Y（生产实现直接读快照自带
 * 高度图 {@code ChunkSnapshot#getHighestBlockYAt}），只读到那一格为止——上界之上必是空气、必不遮挡，
 * 位保持 0，与逐格读的结果<b>逐格等价</b>。主世界 384 高度里通常只有下半部分有方块，这一步省掉大部分
 * 读取（见 {@link #capturePlane} 的说明与 {@code antixray.yml} 中 {@code neighbors} 段的代价标注）。
 * 上界查询本身极便宜，失败时该列退回全高度扫描，只损失优化、不改变结果。
 *
 * <p><b>容量与过期</b>：近似 LRU 有界缓存，键为 {@code (世界名, chunkX, chunkZ)}；每格只占 1 bit，
 * 故每条约 {@code 4 × 16 × 世界高度 / 8} 字节 = {@code 4 × 高度 / 2} 字节
 * （主世界 384 高度约 3 KB），默认上限见 {@code antixray.yml} 的 {@code neighbors.cache-maximum-size}。
 *
 * <p><b>并发</b>：读取（{@link #cached}）走无锁 {@link ConcurrentHashMap}，只写条目上 volatile 的
 * second-chance 位；写入与淘汰（{@link #putCache}）在 {@link #clockLock} 内串行维护一条 CLOCK 环形
 * 队列（近似 LRU，与 {@link RewriteCache} 同一套手法）。这样 ProtocolLib 异步封包线程与所有改写工作
 * 线程可并发读取，{@code mode=enclosed} 下不再被全局 monitor 逐个串行化。
 *
 * <p><b>为什么还要 TTL</b>：快照里的平面可能含 {@code null}（邻块未加载 / Folia 跨区域 / 世界卸载），
 * 也可能在抓取后因邻块被挖开/放置而失真。<b>方块变更不会使本缓存失效</b>（不像改写缓存有内容指纹），
 * 若永久缓存，边界遮挡判定会长期失真（仅 {@code mode=enclosed} 用到）。因此每条快照带一个<b>写入时刻</b>
 * 起算的短 TTL（{@link #DEFAULT_CACHE_TTL_MILLIS}）：到期后按未命中处理并重抓，fail-open 语义不变
 * （取不到仍按既有缺失策略处理）。
 */
public final class NeighborChunkProvider {

  /** 遮挡查询（实现方必须只在允许的线程上访问世界；单测可注入假实现）。 */
  @FunctionalInterface
  interface OcclusionQuery {
    boolean isOccluding(int x, int y, int z);
  }

  /** 邻区块是否已加载（避免抓取时触发同步加载导致主线程卡顿）。 */
  @FunctionalInterface
  interface ChunkLoadedCheck {
    boolean isLoaded(int chunkX, int chunkZ);
  }

  /**
   * 列高度上界查询：返回该列<b>最高非空气方块</b>的 Y（该列在世界上界内没有方块时返回世界最低 Y 之下）。
   *
   * <p>生产实现读邻块快照自带的高度图（{@code ChunkSnapshot#getHighestBlockYAt}，直接读高度图数组，
 * 不做逐格扫描）；单测可注入假实现。语义要求只有一条：<b>返回值的上方不许再出现非空气方块</b>——
 * 上界之上必为空气，也就必不遮挡。
   */
  @FunctionalInterface
  interface ColumnTopQuery {
    int topNonAirY(int x, int z);
  }

  /**
   * 邻块贴边数据来源：生产实现包一层 {@link ChunkSnapshot}（区块内坐标，一次抓取、稳定读取），
   * 单测可注入桩以离线验证「世界坐标 → 邻块区块内坐标」的换算。
   */
  interface SideSnapshot {
    /** 区块内坐标（localX 0..15、localY 0..世界高度-1、localZ 0..15）的方块是否完全遮挡。 */
    boolean isOccluding(int localX, int localY, int localZ);

    /** 区块内列 (localX, localZ) 的最高非空气方块的世界 Y（快照自带高度图）。 */
    int highestBlockY(int localX, int localZ);
  }

  private static final int SIDE_LENGTH = 16;

  /**
   * 快照缓存的默认 TTL（毫秒，从写入时刻起算）。
   *
   * <p>取 30 秒：够短，能在邻块被挖开/放置、或邻块从「未加载」变为「已加载」后较快重建；
   * 又够长，不会每次巡检都触发重抓（单次抓取约 0.776 ms，且只在 {@code mode=enclosed} 世界发生）。
   */
  static final long DEFAULT_CACHE_TTL_MILLIS = 30_000L;

  /**
   * 每次新建条目时摊还清理的环节点数上限（与 {@link RewriteCache} 同一取舍）。
   *
   * <p>为什么要「摊还」而非一次性全扫：全扫是 O(n)、会把并发回填重新串行化；固定小常数让每次
   * {@link #putCache} 只付 O(1)。环上 n 个节点约需 n/该常数 次写入被完整巡检一遍。取 8 是在
   * 「死节点 / 过期冷条目回收及时性」与「单次写入开销」之间的折中。
   */
  private static final int CLEANUP_NODES_PER_PUT = 8;

  private final int cacheMaximumSize;
  private final long cacheTtlNanos;
  private final LongSupplier nanoClock;

  /** 主存储：读取完全无锁。 */
  private final ConcurrentHashMap<ChunkKey, CacheEntry> entries = new ConcurrentHashMap<>();
  /**
   * second-chance 环形队列（CLOCK 手）。只在「真正新建条目」与失效方法的同步块内增删；同键覆盖与
   * 读取路径均不碰它，因此读路径不需要任何锁。队首是「最久未被再访问」的淘汰候选。
   */
  private final ArrayDeque<CacheEntry> clockQueue = new ArrayDeque<>();
  /**
   * CLOCK 环形队列 / 淘汰结构的专用锁。
   *
   * <p><b>为什么从「方法级 {@code synchronized (cache)}」缩小到这把锁</b>：旧实现对访问序
   * {@code LinkedHashMap} 的读（{@link #cached}）与写（{@link #putCache}）都加全局 monitor，而本缓存
   * 会被 ProtocolLib 异步封包线程与所有改写工作线程读取，{@code mode=enclosed} 下所有工作线程因此被
   * 逐个串行化。读取改走 {@code ConcurrentHashMap} 后完全不加锁，真正需要互斥的只剩「环形队列增删 +
   * 淘汰扫描」，故收敛到本锁。
   */
  private final Object clockLock = new Object();

  /** 缓存键：只含不可变类型，不钉住世界对象。 */
  private record ChunkKey(String worldName, int chunkX, int chunkZ) {
  }

  /** 缓存条目：快照 + 到期时刻（纳秒）。{@code expiresAtNanos == Long.MAX_VALUE} 表示永不过期。 */
  private static final class CacheEntry {
    private final ChunkKey key;
    /** 值可被同键覆盖（原地更新），避免覆盖时在环形队列里留下重复节点。 */
    private volatile NeighborEdges edges;
    private volatile long expiresAtNanos;
    /** second-chance 位：被读取过即置 true；淘汰扫描时给一次「第二次机会」。 */
    private volatile boolean referenced;

    private CacheEntry(ChunkKey key, NeighborEdges edges, long expiresAtNanos) {
      this.key = key;
      this.edges = edges;
      this.expiresAtNanos = expiresAtNanos;
      this.referenced = false;
    }
  }

  public NeighborChunkProvider(int cacheMaximumSize) {
    this(cacheMaximumSize, DEFAULT_CACHE_TTL_MILLIS, System::nanoTime);
  }

  /** 带 TTL 的构造（毫秒，{@code <= 0} 表示不过期）；供装配方按需传入更短/更长的过期时间。 */
  public NeighborChunkProvider(int cacheMaximumSize, long cacheTtlMillis) {
    this(cacheMaximumSize, cacheTtlMillis, System::nanoTime);
  }

  /** 测试专用：额外注入时间源，便于离线验证过期语义（无需真实等待）。 */
  NeighborChunkProvider(int cacheMaximumSize, long cacheTtlMillis, LongSupplier nanoClock) {
    this.cacheMaximumSize = Math.max(1, cacheMaximumSize);
    this.cacheTtlNanos = cacheTtlMillis <= 0 ? 0L : cacheTtlMillis * 1_000_000L;
    this.nanoClock = nanoClock;
  }

  /**
   * 读取已缓存的快照；未命中或已过期返回 {@code null}（不做任何 Bukkit 访问，可在任意线程调用）。
   *
   * <p>过期条目在读取时立即移除，因此调用方拿到 {@code null} 后走既有缺失策略（fail-open）。
   */
  public NeighborEdges cached(String worldName, int chunkX, int chunkZ) {
    // 无锁读取：不再获取全局 monitor，也不再改动 LRU 链表（改由 volatile second-chance 位近似）。
    CacheEntry entry = entries.get(new ChunkKey(worldName, chunkX, chunkZ));
    if (entry == null) {
      return null;
    }
    if (entry.expiresAtNanos != Long.MAX_VALUE && nanoClock.getAsLong() >= entry.expiresAtNanos) {
      entries.remove(entry.key, entry);
      return null;
    }
    // 打标「被访问过」：仅写 volatile 位，无需加锁；淘汰扫描据此给一次「第二次机会」（近似 LRU）。
    entry.referenced = true;
    return entry.edges;
  }

  /**
   * 在主线程 / Folia 区域线程抓取该区块 4 个水平邻块的贴边层并写入缓存。
   *
   * @return 快照；个别邻块不可用时对应平面为 {@code null}（由缺失策略兜底）
   */
  public NeighborEdges capture(World world, int chunkX, int chunkZ) {
    String worldName = world.getName();
    NeighborEdges existing = cached(worldName, chunkX, chunkZ);
    if (existing != null) {
      return existing;
    }

    int baseY = world.getMinHeight();
    int height = Math.max(SIDE_LENGTH, world.getMaxHeight() - baseY);
    long[] xMinus = captureSideFromWorld(world, baseY, height, chunkX, chunkZ, NeighborEdges.Side.X_MINUS);
    long[] xPlus = captureSideFromWorld(world, baseY, height, chunkX, chunkZ, NeighborEdges.Side.X_PLUS);
    long[] zMinus = captureSideFromWorld(world, baseY, height, chunkX, chunkZ, NeighborEdges.Side.Z_MINUS);
    long[] zPlus = captureSideFromWorld(world, baseY, height, chunkX, chunkZ, NeighborEdges.Side.Z_PLUS);
    NeighborEdges edges = new NeighborEdges(height, xMinus, xPlus, zMinus, zPlus);
    cacheIfUseful(worldName, chunkX, chunkZ, edges, xMinus, xPlus, zMinus, zPlus);
    return edges;
  }

  /**
   * 抓取一侧邻块（生产路径）。
   *
   * <p><b>为什么改用 {@code Chunk#getChunkSnapshot}</b>：旧实现对贴边层逐格调用
   * {@code World#getBlockData(x,y,z).isOccluding()}——每次都要经世界→区块解析、并新建一个
   * {@code BlockData} 快照对象；贴边层在真实地形下常有上千次读取。改为先把邻块抓成一份
   * {@link ChunkSnapshot}（<b>一次</b>复制该区块的方块数据与高度图），随后所有读取都走快照的
   * 区块内数组查询：不再经世界→区块解析，且整层来自同一时刻的一致副本（不会边读边被方块变更改动）。
   *
   * <p><b>内存</b>：快照只在本方法内短暂存活（抓完即弃），不进入缓存——缓存里仍只有按位打包的
   * {@code long[]} 平面（每条约 {@code 4×16×高度/8} 字节）。因此峰值内存是一次快照的大小，与旧实现的
   * 「同时最多一个 BlockData」相比更高，但换取的是读取次数的显著下降（抓取仅在 {@code mode=enclosed}
   * 世界发生，且有 30 秒 TTL 缓存兜底）。
   *
   * <p><b>遮挡判定仍走 {@code BlockData#isOccluding()}</b>（与旧实现逐格等价）。刻意不用
   * {@code Material#isOccluding()}：新版 Paper 上它会经注册表解析出 BlockType，既依赖服务端运行时，
   * 也不保证与方块状态级判定完全一致——语义等价优先。
   */
  private long[] captureSideFromWorld(World world, int baseY, int height, int chunkX, int chunkZ,
      NeighborEdges.Side side) {
    try {
      int[] neighbor = neighborChunk(side, chunkX, chunkZ);
      if (!world.isChunkLoaded(neighbor[0], neighbor[1])) {
        return null;
      }
      // includeMaxBlockY=true 才能用快照自带高度图做「按列裁剪」（等价于旧 World#getHighestBlockYAt）
      ChunkSnapshot snapshot = world.getChunkAt(neighbor[0], neighbor[1])
          .getChunkSnapshot(true, false, false);
      return captureSideFromSnapshot(baseY, height, side, chunkX, chunkZ, neighbor[0], neighbor[1],
          new SideSnapshot() {
            @Override
            public boolean isOccluding(int localX, int localY, int localZ) {
              return snapshot.getBlockData(localX, localY, localZ).isOccluding();
            }

            @Override
            public int highestBlockY(int localX, int localZ) {
              return snapshot.getHighestBlockYAt(localX, localZ);
            }
          });
    } catch (Throwable throwable) {
      // Folia 跨区域访问、世界卸载等：一律按缺失处理，交由缺失策略决定（默认 hide，不留泄漏）
      return null;
    }
  }

  /**
   * 从「邻块区块内坐标」的贴边数据来源抓取一侧平面（纯逻辑，不触碰 Bukkit；单测可注入桩）。
   *
   * <p>把 {@link #capturePlane} 需要的「世界坐标查询」映射为快照的区块内坐标：世界 x/z 减去邻块原点
   * 得到 localX/localZ，世界 y 减去 {@code baseY} 得到快照的 y 索引。列高度上界由快照高度图直接给出
   * （已是世界 Y，与 {@link ColumnTopQuery} 的契约一致）。
   */
  static long[] captureSideFromSnapshot(int baseY, int height, NeighborEdges.Side side,
      int chunkX, int chunkZ, int neighborChunkX, int neighborChunkZ, SideSnapshot snapshot) {
    int originX = neighborChunkX << 4;
    int originZ = neighborChunkZ << 4;
    return capturePlane(baseY, height, side, chunkX, chunkZ,
        (x, y, z) -> snapshot.isOccluding(x - originX, y - baseY, z - originZ),
        (x, z) -> snapshot.highestBlockY(x - originX, z - originZ));
  }

  /**
   * 抓取核心（纯逻辑，不触碰 Bukkit；由 {@link #capture(World, int, int)} 提供真实世界查询）。
   *
   * @param baseY       世界最低建筑高度（快照内 y=0 对应它）
   * @param height      世界总高度（section 数 × 16）
   * @param chunkLoaded 邻块加载判断；未加载的邻块对应平面为 {@code null}
   * @param query       遮挡查询
   * @param columnTop   列高度上界查询（见 {@link ColumnTopQuery}）；用于把逐格扫描裁到「可能有方块」的范围
   */
  NeighborEdges capture(String worldName, int baseY, int height, int chunkX, int chunkZ,
      ChunkLoadedCheck chunkLoaded, OcclusionQuery query, ColumnTopQuery columnTop) {
    NeighborEdges existing = cached(worldName, chunkX, chunkZ);
    if (existing != null) {
      return existing;
    }

    long[] xMinus = captureSide(baseY, height, chunkX, chunkZ, NeighborEdges.Side.X_MINUS, chunkLoaded, query, columnTop);
    long[] xPlus = captureSide(baseY, height, chunkX, chunkZ, NeighborEdges.Side.X_PLUS, chunkLoaded, query, columnTop);
    long[] zMinus = captureSide(baseY, height, chunkX, chunkZ, NeighborEdges.Side.Z_MINUS, chunkLoaded, query, columnTop);
    long[] zPlus = captureSide(baseY, height, chunkX, chunkZ, NeighborEdges.Side.Z_PLUS, chunkLoaded, query, columnTop);
    NeighborEdges edges = new NeighborEdges(height, xMinus, xPlus, zMinus, zPlus);
    cacheIfUseful(worldName, chunkX, chunkZ, edges, xMinus, xPlus, zMinus, zPlus);
    return edges;
  }

  /**
   * 有选择地写入缓存：<b>四个平面全为 {@code null}</b>（四个邻块都不可用）的结果不入缓存。
   *
   * <p>方向上安全（缺失平面一律走缺失策略、不留泄漏），但没有任何收益：全缺失通常只出现在「邻块尚未
   * 加载 / Folia 跨区域」的瞬间，若把它缓存满 {@link #DEFAULT_CACHE_TTL_MILLIS} 的 TTL，邻块随后加载好
   * 也得等 TTL 过期才会重抓——白白让边界遮挡判定长时间停留在「缺失」态（{@code mode=enclosed} 下边界
   * 那一圈会迟迟补不齐）。因此只缓存至少含一个可用平面的结果；全缺失时直接返回，让下次访问立即重试。
   */
  private void cacheIfUseful(String worldName, int chunkX, int chunkZ, NeighborEdges edges,
      long[] xMinus, long[] xPlus, long[] zMinus, long[] zPlus) {
    if (xMinus == null && xPlus == null && zMinus == null && zPlus == null) {
      return;
    }
    putCache(worldName, chunkX, chunkZ, edges);
  }

  /**
   * 写入缓存：TTL 从写入时刻起算。
   *
   * <p>缓存不能永久持有陈旧（含 null 平面）的快照，到期后重抓——见类注释的 TTL 说明。
   */
  private void putCache(String worldName, int chunkX, int chunkZ, NeighborEdges edges) {
    long expiresAt = cacheTtlNanos <= 0L
        ? Long.MAX_VALUE
        : nanoClock.getAsLong() + cacheTtlNanos;
    ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
    CacheEntry entry = new CacheEntry(key, edges, expiresAt);
    CacheEntry previous = entries.putIfAbsent(key, entry);
    if (previous != null) {
      // 同键（含并发）覆盖：原地更新，绝不在环形队列里留下重复节点
      previous.edges = edges;
      previous.expiresAtNanos = expiresAt;
      previous.referenced = true;
      return;
    }
    // 只有「真正新建条目」才进入同步块维护环形队列并做淘汰/清理——把串行范围缩到最小
    synchronized (clockLock) {
      clockQueue.addLast(entry);
      evictOverflow();
      // 顶满淘汰只在超容量时触发，冷条目的过期回收与死节点清理由此摊还处理
      amortizedCleanup();
    }
  }

  /**
   * second-chance / CLOCK 淘汰：<b>先剔除已过期条目</b>（它们不该占淘汰名额、也不该挤掉仍有效的
   * 条目），队列里已被移除的陈旧节点直接丢弃，被访问过的条目让一次机会。
   */
  private void evictOverflow() {
    long now = nanoClock.getAsLong();
    while (entries.size() > cacheMaximumSize) {
      CacheEntry candidate = clockQueue.pollFirst();
      if (candidate == null) {
        // 理论上不会发生：每个存活条目在创建时都已入队
        return;
      }
      if (entries.get(candidate.key) != candidate) {
        continue; // 陈旧节点（该键已被过期移除 / 失效）
      }
      if (isExpired(candidate, now)) {
        entries.remove(candidate.key, candidate);
        continue;
      }
      if (candidate.referenced) {
        candidate.referenced = false;
        clockQueue.addLast(candidate);
        continue;
      }
      entries.remove(candidate.key, candidate);
    }
  }

  /**
   * 摊还清理：从 CLOCK 环队首起最多巡检 {@value #CLEANUP_NODES_PER_PUT} 个节点，处理两件事——
   * <ul>
   *   <li><b>丢弃死节点</b>：键已被 {@link #cached} 的过期检查或失效方法移除、却仍在环里的陈旧引用，
   *       直接丢弃，避免环随「读到过期即移除」的条目无限增长；</li>
   *   <li><b>主动剔除过期冷条目</b>：写入一次后永不再被 {@code cached} 的条目，按 {@code expiresAtNanos}
   *       判定已过期即从主存储移除，兑现 TTL 的回收承诺。</li>
   * </ul>
   * 仍有效的节点原样轮转回队尾（<b>不改动其 second-chance 位</b>）。只在 {@link #clockLock} 内调用，
   * 每次最多常量条，均摊成本 O(1)。
   */
  private void amortizedCleanup() {
    long now = nanoClock.getAsLong();
    for (int inspected = 0; inspected < CLEANUP_NODES_PER_PUT; inspected++) {
      CacheEntry candidate = clockQueue.pollFirst();
      if (candidate == null) {
        return;
      }
      if (entries.get(candidate.key) != candidate) {
        continue; // 死节点：键已被移除，环里只剩陈旧引用，直接丢弃
      }
      if (isExpired(candidate, now)) {
        entries.remove(candidate.key, candidate); // 过期冷条目：主动回收
        continue;
      }
      clockQueue.addLast(candidate); // 仍有效：原样轮转回队尾（保留 referenced 位）
    }
  }

  private static boolean isExpired(CacheEntry entry, long now) {
    return entry.expiresAtNanos != Long.MAX_VALUE && now >= entry.expiresAtNanos;
  }

  private long[] captureSide(int baseY, int height, int chunkX, int chunkZ, NeighborEdges.Side side,
      ChunkLoadedCheck chunkLoaded, OcclusionQuery query, ColumnTopQuery columnTop) {
    try {
      int[] neighborChunk = neighborChunk(side, chunkX, chunkZ);
      if (!chunkLoaded.isLoaded(neighborChunk[0], neighborChunk[1])) {
        return null;
      }
      return capturePlane(baseY, height, side, chunkX, chunkZ, query, columnTop);
    } catch (Throwable throwable) {
      // Folia 跨区域访问、世界卸载等：一律按缺失处理，交由缺失策略决定（默认 hide，不留泄漏）
      return null;
    }
  }

  /**
   * 抓取请求方某一侧的贴边层（纯逻辑；坐标换算见 {@link #worldPosition}）。
   *
   * <p><b>按列高度上界裁剪</b>：每列先问一次 {@link ColumnTopQuery}（生产实现读邻块快照自带的高度图
   * {@code ChunkSnapshot#getHighestBlockYAt}），只读到「该列最高非空气方块」
   * 那一格为止（<b>含</b>该格）。裁掉的那些格子必是空气、必不遮挡，位保持 0，因此平面内容与
   * 「全高度逐格读」逐格等价；主世界 384 高度里通常只有下半部分有方块，于是省掉大部分快照读取。
   *
   * @return 位打包的遮挡平面（长度为 {@code ceil(height × 16 / 64)}），位下标为 {@code y << 4 | localOther}
   */
  static long[] capturePlane(int baseY, int height, NeighborEdges.Side side, int chunkX, int chunkZ,
      OcclusionQuery query, ColumnTopQuery columnTop) {
    long[] plane = new long[NeighborEdges.planeLongCount(height)];
    for (int local = 0; local < SIDE_LENGTH; local++) {
      int[] position = worldPosition(side, chunkX, chunkZ, local);
      int top = topRelativeY(baseY, height, position[0], position[1], columnTop);
      for (int y = 0; y <= top; y++) {
        if (query.isOccluding(position[0], baseY + y, position[1])) {
          NeighborEdges.setOccluding(plane, y << 4 | local);
        }
      }
    }
    return plane;
  }

  /**
   * 该列扫描到哪一格（区块内相对 Y，含该格）。
   *
   * <p>返回值 {@code -1} 表示这列在世界上界内没有非空气方块，一位都不用读。上界查询异常
   * （Folia 跨区域、实现不完整等）时返回 {@code height - 1}——退回「全高度逐格读」的旧行为，
   * 只损失优化、绝不改变结果；真正的读方块异常仍由 {@link #captureSide} 统一转成「平面缺失」。
   */
  private static int topRelativeY(int baseY, int height, int x, int z, ColumnTopQuery columnTop) {
    try {
      int top = columnTop.topNonAirY(x, z) - baseY;
      return top >= height - 1 ? height - 1 : Math.max(top, -1);
    } catch (Throwable throwable) {
      return height - 1;
    }
  }

  /** 该侧邻块所在的区块坐标。 */
  static int[] neighborChunk(NeighborEdges.Side side, int chunkX, int chunkZ) {
    return switch (side) {
      case X_MINUS -> new int[] {chunkX - 1, chunkZ};
      case X_PLUS -> new int[] {chunkX + 1, chunkZ};
      case Z_MINUS -> new int[] {chunkX, chunkZ - 1};
      case Z_PLUS -> new int[] {chunkX, chunkZ + 1};
    };
  }

  /**
   * 请求方某一侧的贴边格子在邻块中的世界坐标（x, z）。
   *
   * <p>与遮挡判定一一对应：请求方 {@code x=16} 对应 {@code X_PLUS} 侧邻块的 {@code localX=0}；
   * 请求方 {@code x=-1} 对应 {@code X_MINUS} 侧邻块的 {@code localX=15}；z 方向同理。
   *
   * @param localOther 另一水平轴的区块内相对坐标（0..15）
   */
  static int[] worldPosition(NeighborEdges.Side side, int chunkX, int chunkZ, int localOther) {
    return switch (side) {
      case X_MINUS -> new int[] {(chunkX - 1) << 4 | 15, (chunkZ << 4) + localOther};
      case X_PLUS -> new int[] {(chunkX + 1) << 4, (chunkZ << 4) + localOther};
      case Z_MINUS -> new int[] {(chunkX << 4) + localOther, (chunkZ - 1) << 4 | 15};
      case Z_PLUS -> new int[] {(chunkX << 4) + localOther, (chunkZ + 1) << 4};
    };
  }

  /** 使某个世界的全部快照失效（世界卸载时调用）。 */
  public void invalidateWorld(String worldName) {
    synchronized (clockLock) {
      entries.keySet().removeIf(key -> key.worldName().equals(worldName));
      clockQueue.removeIf(entry -> entry.key.worldName().equals(worldName));
    }
  }

  /** 使全部快照失效（配置热重载时调用）。 */
  public void invalidateAll() {
    synchronized (clockLock) {
      entries.clear();
      clockQueue.clear();
    }
  }

  /** 当前缓存条目数（诊断用）。 */
  public int size() {
    return entries.size();
  }
}