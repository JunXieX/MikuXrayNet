package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bandwidth.Schedulers;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import net.mikumc.mikuxraynet.util.Constants;
import net.mikumc.mikuxraynet.util.PlayerWorldIndex;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 出站方块变更监听：服务端自己下发了某个「曾被伪装的坐标」的变更时，把该坐标从伪装区块索引中注销，
 * 并（可选）触发事件驱动即时显形。
 *
 * <p><b>为什么需要注销</b>：玩家挖掉一个伪装方块时，服务端会下发真实的变更包；若该坐标仍留在
 * 伪装清单里，邻近显形稍后还会用陈旧数据重复发包（多花带宽，观感也可能闪一下）。所以命中即注销。
 * 单方块变更只摘<b>变更的那一个坐标</b>；多方块变更（一个 section）按 section 聚合，一次批量摘除
 * 该 section 的全部变更坐标（见 {@link #processChanges}），该区块其它坐标照常显形。
 *
 * <p><b>为什么走同步监听（而不是异步通道）</b>：异步注册会把本包<b>扣在异步队列</b>里，而服务端在
 * 方块变更之后紧跟着下发的 {@code LIGHT_UPDATE} 不受本监听器约束、原样先走——客户端于是「先拿到新光照、
 * 后拿到新方块」，把刚放/刚挖的那一格按旧方块 + 新光照渲染一帧（真机观感：那一格闪一下）。同步注册在
 * netty 管线内就地处理，线序不变（见 {@link #start()}）。本类<b>不</b>登记处理延迟、不取消、不改包，纯观察。
 *
 * <p><b>线程与内存纪律</b>：回调里只做「读包内坐标 + 查内存索引」，不读 World 的方块；
 * 注销按「世界名 + 坐标」精确定位，因此不需要扫描全部玩家或全部区块。不检查取消状态：即使原包被
 * 别的插件取消，最坏结果也只是少发一个冗余显形包，不会丢失更新。
 *
 * <p><b>自己发的显形包也会被看到（1.1.6 起）</b>：显形发包改用 Paper 原生
 * {@code Player#sendMultiBlockChange} / {@code Player#sendBlockChange}，这些包同样进入本监听器
 * （实测「变更注销」增量与「显形发送」增量逐次相等即为此证据）。两条路径对「注销索引」的处理完全
 * 相同（回显不改变服务端内容，但该坐标确实已被我们发回真实方块，摘掉索引是正确的）；
 * 磁盘缓存的有效性则完全由负载里的原始区块字节指纹判定，与本文观察无关。
 *
 * <p><b>事件驱动即时显形（P1-4，可选）</b>：构造时传入 {@link ProximityRevealer} 后，每次观测到
 * 变更还会调用 {@link ProximityRevealer#onBlockChangeObserved}——变更邻域内仍有伪装坐标时，
 * 由邻近显形立即调度到玩家所属线程补发「玩家身边、已伪装且视线可见」的坐标（不等巡检周期；周期巡检兜底不变）。
 * 未传入（{@code null}）时行为与旧版完全一致。
 */
public final class BlockChangeRevealListener extends PacketAdapter {

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  /** 清单与标记的唯一维护入口（封包线程批量注销走这里，避免两处各自维护同一不变式）。 */
  private final ChunkRevealBook book;
  private final ProximityStats stats;
  /**
   * 事件驱动即时显形（P1-4，可选）：观测到方块变更时交给邻近显形「立即投递到玩家线程补发邻域」。
   * {@code null} 表示不启用（只做索引注销，行为与旧版一致）。
   */
  private final ProximityRevealer instantRevealer;
  /**
   * 最近方块变更日志（可为 {@code null}）：区块包在途期间被改过的格子由它记录，放行后补发。
   *
   * <p>只记**真实**变更（回显已在 {@link #observeChange} / {@link #processSection} 剔除）：回显不改变服务端内容，记进去只会
   * 让放行后多补发一次同样的状态。
   */
  private final ChunkChangeJournal changeJournal;
  /** 单个多方块变更包最多记多少条（超出只少补发几格，不影响正确性）。 */
  private static final int MAX_JOURNAL_RECORDS_PER_PACKET = 64;
  private final AtomicInteger errorCounter = new AtomicInteger();

  /** 是否已注册（同步监听器没有句柄，用标志位保证 start/stop 幂等）。 */
  private volatile boolean registered;
  /**
   * 注销执行器：把「摘伪装索引 + 清已显形标记」移出封包线程。
   *
   * <p><b>为什么必须移出</b>：这两步要对该区块的坐标数组做二分 + 整段复制，并遍历该区块所有玩家的标记
   * 逐个加锁。单人低负载下是微秒级，几十人在线时会与区块解码（同区块 compute / 建表）和显形扫描抢锁，
   * 封包线程因此被挡住，方块包晚于同一 tick 的光照包写出——客户端按「旧方块 + 新光照」渲染一帧，
   * 真机观感就是「那一格亮度闪一下」。放到后台单线程后，封包线程只做解析与入队，线序不再受影响。
   *
   * <p>队列有界：极端积压时退回同步执行（宁可慢一次，也不丢注销——丢了会留下「索引还有、方块已变」的
   * 幽灵坐标，显形时反复发同一个位置）。
   */
  private volatile ExecutorService undoExecutor;

  /**
   * 完整构造（含方块变更日志）。
   *
   * @param changeJournal 区块包在途期间被改过的格子靠它补发（见 {@link ChunkChangeJournal}）；
   *                      {@code null} 表示不记录（未接入改写链路时无意义）
   */
  public BlockChangeRevealListener(Plugin plugin, ProtocolManager protocolManager,
      ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet,
      ProximityStats stats, ProximityRevealer instantRevealer, ChunkChangeJournal changeJournal) {
    super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.stats = stats;
    this.instantRevealer = instantRevealer;
    this.changeJournal = changeJournal;
    // book 直接捕获构造参数（索引/标记集合的可变引用），无需另存字段
    this.book = new ChunkRevealBook(() -> obfuscatedChunkIndex, () -> revealedSet);
  }

  /**
   * 注册<b>同步</b>出站监听器。
   *
   * <p>不能用异步：异步注册会把本包<b>扣在异步队列</b>里，而服务端在方块变更之后紧跟着下发的
   * {@code LIGHT_UPDATE} 不受本监听器约束、原样先走——客户端于是「先拿到新光照、后拿到新方块」，
   * 会把刚放/刚挖的那一格按旧方块 + 新光照渲染一帧（真机观感：那一格闪一下）。
   * 同步注册在 netty 管线内就地处理，线序不变。
   */
  public void start() {
    if (undoExecutor == null || undoExecutor.isShutdown()) {
      undoExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(8192), runnable -> {
            Thread thread = new Thread(runnable, "MikuXrayNet-BlockUndo");
            thread.setDaemon(true);
            return thread;
          }, new ThreadPoolExecutor.AbortPolicy());
    }
    // 同步监听：在 netty 管线内就地处理，绝不把封包扣在异步队列里（见类注释的「包序」说明）。
    protocolManager.addPacketListener(this);
    registered = true;
    plugin.getLogger().fine("方块变更观察已启用：命中已伪装坐标即从显形索引移除");
  }

  /** 注销监听器；可重复调用，异常安全。 */
  public void stop() {
    ExecutorService executor = undoExecutor;
    undoExecutor = null;
    if (executor != null) {
      executor.shutdownNow();
    }
    if (!registered) {
      return;
    }
    registered = false;
    try {
      protocolManager.removePacketListener(this);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销方块变更观察监听器时出现异常（通常可忽略）", throwable);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }

    try {
      String worldName = worldNameOf(player);
      PacketType type = event.getPacketType();
      if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        unregisterSingle(player, event.getPacket(), worldName);
      } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        unregisterMulti(player, event.getPacket(), worldName);
      }
    } catch (Throwable throwable) {
      // fail-open：解析失败只跳过本次注销，绝不取消或改写原包
      logThrottled(throwable);
    }
  }

  /** 单方块变更：坐标即绝对方块坐标。 */
  private void unregisterSingle(Player player, PacketContainer packet, String worldName) {
    BlockPosition position = packet.getBlockPositionModifier().readSafely(0);
    if (position == null) {
      return;
    }
    // 只有「变更后不再是遮挡方块」才可能露出后面的伪装矿；放置石头/泥土不会，
    // 此时再触发事件显形只会把同一格重绘一次（真机观感：放置时闪一下）。
    boolean exposes = true;
    try {
      exposes = exposes(packet.getBlockData().readSafely(0));
    } catch (Throwable ignored) {
      // 读不到状态（协议差异）：按「可能暴露」处理，行为与之前一致
    }
    observeChange(player, worldName, position.getX(), position.getY(), position.getZ(), exposes);
  }

  /**
   * 多方块变更：section 坐标 + 区块内相对坐标（与本插件合并包一致的位置编码）。
   *
   * <p><b>为什么按 section 聚合而不是逐坐标</b>：一个 section 最多 4096 个坐标。逐坐标调用会带来
   * ① 每坐标一次 {@code removePosition}（二分 + 整段数组复制，最坏 {@code O(4096·n)} 元素拷贝）；
   * ② 每坐标一次邻域初筛；③ 每命中各调度一次显形任务——全部压在 netty 线程上。这里改为：
   * 先对坐标去重，再交给 {@link #processChanges} 按 section 分组聚合（每 section 一次批量摘除、
   * 一次初筛、一次调度）。
   */
  private void unregisterMulti(Player player, PacketContainer packet, String worldName) {
    BlockPosition section = packet.getSectionPositions().readSafely(0);
    short[] positions = packet.getShortArrays().readSafely(0);
    if (section == null || positions == null || positions.length == 0) {
      return;
    }
    if (WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      return;
    }

    int baseX = section.getX() << 4;
    int baseY = section.getY() << 4;
    int baseZ = section.getZ() << 4;

    // 坐标去重由 processChanges/SectionBuffer 统一完成（同一 section 内坐标编码唯一）。
    // 绝对坐标缓冲从池化 scratch 取（acquire/release 成对）：满载包每包最多约 48KB，复用可避免
    // 短命大数组；只写满前 positions.length*3 个单元，读取以该长度为准，不残留上一次数据。
    ParseScratch parse = ParseScratch.acquire();
    try {
      int[] coordinates = parse.coordinates(positions.length * 3);
      for (int i = 0; i < positions.length; i++) {
        short packed = positions[i];
        // 协议编码（ClientboundSectionBlocksUpdatePacket）：(y << 8) | (z << 4) | x。
        // 早前这里把低 4 位当成了 y、把 bit8..11 当成了 x——x/y 对调，注销的是另一个坐标。
        int raw = SectionCoords.unsigned(packed);
        coordinates[i * 3] = baseX + SectionCoords.x(raw);
        coordinates[i * 3 + 1] = baseY + SectionCoords.y(raw);
        coordinates[i * 3 + 2] = baseZ + SectionCoords.z(raw);
      }

      // 回显判定绑定「本包的接收者」：显形回显只发给了这个玩家，因此也只会在他名下命中
      RevealEchoCheck echoCheck = instantRevealer == null || player == null
          ? null : (world, x, y, z) -> isOurRevealEcho(player, world, x, y, z);
      SectionDispatch dispatch = (changed, changedCount) -> {
        recordChanges(worldName, changed, changedCount);
        if (instantRevealer != null && changedCount > 0) {
          // 投递到玩家线程：onSectionChangeObserved 内部要读 Bukkit.getCurrentTick()，本回调跑在
          // BlockUndo 后台线程，Folia 上非区域线程读会抛 No currently ticking region（与单方块路径
          // observeChange 同一处理）。changed 已是独立快照，可安全跨线程持有。
          Schedulers.onEntity(plugin, player,
              () -> instantRevealer.onSectionChangeObserved(player, worldName, changed, changedCount));
        }
      };
      // 同上：批量摘除在几十人在线时可能与解码/显形抢锁，必须离开封包线程。
      // coordinates 来自池化复用缓冲，投递前先交出精确长度的独立快照。
      int[] snapshot = java.util.Arrays.copyOf(coordinates, positions.length * 3);
      int count = positions.length;
      submitUndo(() -> processChanges(worldName, snapshot, count, book, stats, dispatch, echoCheck));
    } finally {
      ParseScratch.release(parse);
    }
  }

  /**
   * 一个 section 的调度回调：生产实现交给 {@link ProximityRevealer#onSectionChangeObserved}；
   * 单测注入计数桩，用来断言「每个 section 恰好调度一次」。
   */
  @FunctionalInterface
  interface SectionDispatch {

    /**
     * 一个 section 的全部变更坐标（已去重，扁平三元组，前 {@code count} 个有效）。
     *
     * <p>数组是<b>本次调用的独立快照</b>，实现方可以安全持有或投递到其它线程（见 {@code processSection}
     * 里的拷贝说明）。
     */
    void dispatch(int[] coordinates, int count);
  }

  /**
   * 回显判定：该坐标是否为本插件刚为该玩家发出的显形回显（命中即消费该记号）。
   *
   * <p>生产实现绑定 {@link ProximityRevealer#consumeOurRevealEcho}；为 {@code null} 时表示「不做区分」，
   * 一律按真实变更处理（修复前的行为，供单测与未启用事件显形时回落）。
   */
  @FunctionalInterface
  interface RevealEchoCheck {

    boolean isEcho(String worldName, int x, int y, int z);
  }

  /** 单个 section 收集到的变更坐标（可增长的扁平三元组缓冲，并按 section 内编码去重）。 */
  private static final class SectionBuffer {

    private long key;
    /** 本 section 专属的去重代次（见 {@link SeenScratch}）；同一线程内每个 section 都不同。 */
    private int generation;
    /**
     * 去重表：<b>每次取用都要重绑</b>。池化后 SeenScratch 是跨包复用的共享实例，若只在构造时绑定，
     * 后续包拿到的可能是另一个实例（甚至已被其它线程取走），去重戳会写进无关数组、generation 编号错位
     * → 坐标被误判重复而丢弃（漏摘标记、漏显形）。
     */
    private SeenScratch scratch;
    private int[] data = new int[48];
    private int count;

    private SectionBuffer(SeenScratch scratch) {
      this.scratch = scratch;
    }

    /**
     * 复用前重置：换 section 键与去重代次，有效计数清零；{@code data} <b>保留已扩容的容量</b>
     * （读取一律以 {@code count} 为界，残留旧值不会被读到）。
     */
    private void reset(long key, int generation, SeenScratch scratch) {
      this.key = key;
      this.generation = generation;
      this.scratch = scratch;
      this.count = 0;
    }

    private void add(int x, int y, int z) {
      int slot = SectionCoords.pack(x, y, z);
      if (!scratch.mark(slot, generation)) {
        return;
      }
      if (count * 3 + 3 > data.length) {
        data = java.util.Arrays.copyOf(data, data.length * 2);
      }
      data[count * 3] = x;
      data[count * 3 + 1] = y;
      data[count * 3 + 2] = z;
      count++;
    }
  }

  /**
   * 去重 scratch（类静态有界池复用，避免为每个 section 新分配一个 {@code boolean[4096]}）：
   * 用「代次戳数组」而非每次清零的布尔数组——同一 16³ 槽位只记一个 {@code stamp[slot] == generation}
   * 即视为已见，换一个 section 只递增代次，无需清空 4096 个槽。acquire/release 成对、单回调独占，
   * netty 与 BlockUndo 两类线程先后取用同一池实例均安全（见 {@link #releasePooledBuffers()}）。
   */
  private static final class SeenScratch {

    private static final java.util.concurrent.ConcurrentLinkedQueue<SeenScratch> POOL =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int MAX_POOLED = 8;

    private final int[] stamp = new int[4096];
    private int generation;

    /** 取一份可复用的去重表（<b>不用 ThreadLocal</b>：条目会被长生存的 netty 线程钉住旧类加载器）。 */
    private static SeenScratch acquire() {
      SeenScratch pooled = POOL.poll();
      return pooled != null ? pooled : new SeenScratch();
    }

    private static void release(SeenScratch scratch) {
      if (scratch != null && POOL.size() < MAX_POOLED) {
        POOL.offer(scratch);
      }
    }

    private static void releaseAll() {
      POOL.clear();
    }

    /** 取得本 section 专属的新代次；代次回绕到 0 时整表清零重来（实际不可能到达）。 */
    private int nextGeneration() {
      if (++generation == 0) {
        java.util.Arrays.fill(stamp, 0);
        generation = 1;
      }
      return generation;
    }

    /** 标注并返回该槽位是否为「本代次首次出现」（false = 重复，调用方应跳过）。 */
    private boolean mark(int slot, int generation) {
      if (stamp[slot] == generation) {
        return false;
      }
      stamp[slot] = generation;
      return true;
    }
  }

  /**
   * 多方块变更解析的池化复用缓冲（类静态有界并发池，acquire/release 成对、单回调独占）。
   *
   * <p><b>为什么可以复用</b>：解析一个包期间不会重入（监听器自身不发包：显形调度只是
   * {@code Schedulers.onEntity} 投递，回显判定是纯 map 查询），同一回调内串行处理。池化省掉每包最多约
   * 48KB（绝对坐标）+ 48KB（section 数据）的短命分配。<b>若将来在回调里同步发出会回到本监听器的包，
   * 必须先把本缓冲改成不可重入或改用独立缓冲</b>（否则内层会覆盖外层正在解析的数据）。
   *
   * <p>每次使用只重置「有效区间」的计数（{@code bufferCount} 与各 buffer 的 {@code count}），
   * <b>不清零整段数组</b>：所有读取都以计数为界，残留旧值不会被读到；容量则保留，避免反复扩容拷贝。
   *
   * <p><b>复用数组的生命周期</b>：{@code SectionBuffer.data} 只在本次 {@code processChanges} 调用内
   * 有效（下一包会覆盖），因此派发处必须交出独立快照（见 {@code processSection}）——这正是
   * {@code dispatch} 契约里「独立快照」的由来，也是本缓冲能安全复用的前提。
   */
  private static final class ParseScratch {

    private static final java.util.concurrent.ConcurrentLinkedQueue<ParseScratch> POOL =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int MAX_POOLED = 8;

    private final java.util.ArrayList<SectionBuffer> buffers = new java.util.ArrayList<>();
    private int[] coordinates = new int[48];
    private int bufferCount;

    /** 取一份可复用的解析缓冲（<b>不用 ThreadLocal</b>，理由见 {@link SeenScratch#acquire()}）。 */
    private static ParseScratch acquire() {
      ParseScratch pooled = POOL.poll();
      return pooled != null ? pooled : new ParseScratch();
    }

    private static void release(ParseScratch scratch) {
      if (scratch != null && POOL.size() < MAX_POOLED) {
        POOL.offer(scratch);
      }
    }

    private static void releaseAll() {
      POOL.clear();
    }

    /** 取得容量 ≥ {@code required} 的绝对坐标缓冲（保留扩容后的容量）。 */
    private int[] coordinates(int required) {
      if (coordinates.length < required) {
        coordinates = new int[required];
      }
      return coordinates;
    }

    /** 开始解析新包：section 有效计数归零（buffer 实例与 data 容量保留）。 */
    private void beginSections() {
      bufferCount = 0;
    }

    /** 取得第 {@code bufferCount} 个 section 的可复用缓冲，并重置为本次的 section 键与去重代次。 */
    private SectionBuffer nextSection(long key, int generation, SeenScratch seen) {
      while (buffers.size() <= bufferCount) {
        buffers.add(new SectionBuffer(seen));
      }
      SectionBuffer buffer = buffers.get(bufferCount++);
      buffer.reset(key, generation, seen);
      return buffer;
    }

    private SectionBuffer bufferAt(int index) {
      return buffers.get(index);
    }
  }

  /**
   * 释放两处复用缓冲池（{@link SeenScratch} 的去重戳表与 {@link ParseScratch} 的解析缓冲）。
   *
   * <p>两处缓冲已<b>不用 ThreadLocal</b>：改用类静态的有界并发池 + acquire/release 成对使用。
   * 原因：ThreadLocal 条目挂在长生存的 netty 事件循环线程上，停用/热重载后旧实例会经条目钉住旧类加载器；
   * 池只被本类的类加载器持有，随插件卸载一起回收，停用时 clear 即可立即释放。
   * 由 {@link ProximityRevealer#clearThreadLocals()} 汇入。
   */
  static void releasePooledBuffers() {
    SeenScratch.releaseAll();
    ParseScratch.releaseAll();
  }

  /**
   * 把一批变更坐标（绝对坐标三元组，可能跨多个 section）按 section（chunk + sectionY）分组后聚合处理：
   * 每个 section 只做一次批量索引摘除、一次邻域初筛、一次调度。
   *
   * <p>生产路径的多方块变更包本就只含一个 section，跨 section 分组是为将来/其它调用方保留的通用性；
   * 包级可见以便离线单测直接喂跨 section 坐标并注入调度计数桩（{@link SectionDispatch}）。
   *
   * <p><b>不变式</b>：无论索引是否命中，都要把该坐标的已显形标记同步摘除——索引未命中而标记仍在，
   * 就是会让「整块跳过」提前成立的孤儿标记（见 {@link #observeChange}）。
   */
  static void processChanges(String worldName, int[] coordinates, int count, ChunkRevealBook book,
      ProximityStats stats, SectionDispatch dispatch, RevealEchoCheck echoCheck) {
    if (worldName == null || coordinates == null || count <= 0) {
      return;
    }

    SeenScratch seen = SeenScratch.acquire();
    ParseScratch parse = ParseScratch.acquire();
    try {
      parse.beginSections();
      int sectionCount = 0;
      for (int i = 0; i < count; i++) {
        int x = coordinates[i * 3];
        int y = coordinates[i * 3 + 1];
        int z = coordinates[i * 3 + 2];
        long key = sectionKey(x, y, z);
        SectionBuffer buffer = null;
        for (int s = 0; s < sectionCount; s++) {
          SectionBuffer candidate = parse.bufferAt(s);
          if (candidate.key == key) {
            buffer = candidate;
            break;
          }
        }
        if (buffer == null) {
          // 复用池中缓冲（见 ParseScratch）：生产路径一个包只有一个 section，因此稳态下零分配
          buffer = parse.nextSection(key, seen.nextGeneration(), seen);
          sectionCount++;
        }
        buffer.add(x, y, z);
      }

      for (int s = 0; s < sectionCount; s++) {
        processSection(worldName, parse.bufferAt(s), book, stats, dispatch, echoCheck);
      }
    } finally {
      ParseScratch.release(parse);
      SeenScratch.release(seen);
    }
  }

  /** 处理单个 section：过滤回显 → 一次批量摘除 → 一次调度。 */
  private static void processSection(String worldName, SectionBuffer section, ChunkRevealBook book,
      ProximityStats stats, SectionDispatch dispatch, RevealEchoCheck echoCheck) {
    if (echoCheck != null) {
      // 就地过滤：把「本插件刚发出的显形回显」从有效段里剔除（回显不改变服务端内容，绝不能摘索引）。
      // 复用同一数组前段，不新增任何分配；被剔除的坐标也不参与后续摘除与调度。
      int kept = 0;
      for (int i = 0; i < section.count; i++) {
        int x = section.data[i * 3];
        int y = section.data[i * 3 + 1];
        int z = section.data[i * 3 + 2];
        if (echoCheck.isEcho(worldName, x, y, z)) {
          continue;
        }
        section.data[kept * 3] = x;
        section.data[kept * 3 + 1] = y;
        section.data[kept * 3 + 2] = z;
        kept++;
      }
      if (kept == 0) {
        // 整段都是回显：没有任何真实变更，既不摘索引/标记，也不需要触发事件显形
        return;
      }
      section.count = kept;
    }
    // 清单与已显形标记一次摘除：维持「已显形坐标 ⊆ 区块伪装清单」不变式。
    // 索引未命中也要摘（宁可多摘一次也不能留孤儿标记，见 observeChange）。
    int removed = book.removePositions(worldName, section.data, section.count);
    if (removed > 0 && stats != null) {
      stats.unregistered.add(removed);
    }
    if (dispatch != null) {
      // <b>必须交出独立快照</b>：生产实现（{@link ProximityRevealer#onSectionChangeObserved}）会把坐标
      // 数组交给实体调度器、在玩家线程上延后执行，而 {@link SectionBuffer} 的 data 是池化复用缓冲
      // （见 {@link ParseScratch}）——若直接交出复用数组，下一个包解析时会把仍在排队的坐标覆盖掉，
      // 显形就会发到错误位置。快照按精确长度拷贝，活跃路径每包仍比「不复用」少一次等长数组分配。
      dispatch.dispatch(java.util.Arrays.copyOf(section.data, section.count * 3), section.count);
    }
  }

  /**
   * section 键：把 {@code (chunkX, sectionY, chunkZ)} 打成单个 long（chunkX/chunkZ 各 22 位、
   * sectionY 11 位），覆盖世界边界（±3000 万方块 ≈ 区块 ±190 万）与 16 位建筑高度范围，不存在碰撞。
   */
  private static long sectionKey(int x, int y, int z) {
    return ((long) ((x >> 4) & 0x3FFFFF) << 33)
        | ((long) ((y >> 4) & 0x7FF) << 22)
        | ((z >> 4) & 0x3FFFFF);
  }

  /**
   * 统一的变更观察入口：先注销（既有行为），再<b>仅对真实变更</b>触发事件显形（P1-4，可选）。
   *
   * <p>两个动作共用同一次坐标解析，热路径上不产生任何额外分配；事件显形的全部筛选
   * （邻域初筛、身边半径、射线、限额、去重）都在 {@link ProximityRevealer#onBlockChangeObserved}
   * 内部完成，未启用时只有一个空引用判断的开销。
   *
   * <p><b>回显不产生新的观察事件</b>：{@link #isOurRevealEcho} 会告诉我们这次变更是不是
   * 「本插件刚为该玩家发出的显形回显」。回显只代表服务端把玩家本就该看到的真实方块发了一遍，
   * 服务端内容没有任何变化，因此<b>不应</b>再触发一次事件显形——否则每发一个单方块显形包都会：
   * ① 在 netty 线程上白扫一遍变更点的曼哈顿邻域（默认半径 4 共 129 个偏移）；
   * ② 让 {@code revealImmediately} claim 掉该 tick 的整份即时显形额度，去显形「刚被我们显形
   * 过的那块」的邻居（那条路径绕过周期巡检的视锥剔除、分片轮转与候选窗口，会让显形带宽被自身
   * 回显放大）。多方块变更路径早已按同一口径过滤（见 {@code processSection}：整段回显直接返回、
   * 不派发），单方块路径此前漏了这一步。真实变更（玩家挖开 / 爆炸 / 放置）返回 {@code false}，
   * 仍照常触发邻域显形。
   */
  private void observeChange(Player player, String worldName, int x, int y, int z,
      boolean exposes) {
    // 黑名单世界不做任何反矿透工作：不注销显形、不触发事件即时显形。
    if (WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      return;
    }
    // 1.1.6 起显形包走 Paper 原生通道（sendMultiBlockChange / sendBlockChange），因此本监听器
    // 也会看到「我们自己发出的显形回显」。回显不改变服务端内容，必须与真实变更区别对待
    // （见 isOurRevealEcho）；磁盘缓存方面两者都不需要作废任何条目——条目有效性由负载里的
    // 原始区块字节指纹判定（见 DiskCacheStore 类注释），回显不改变区块字节。
    // 回显判定留在封包线程（只是一次并发 map 查/删），真实变更的注销投递到后台：
    // 摘索引与清标记会与解码 / 显形抢锁，留在封包线程上会把方块包压到光照包之后（见 undoExecutor 说明）。
    if (isOurRevealEcho(player, worldName, x, y, z)) {
      return; // 回显：服务端内容未变，不产生新的观察事件（理由见方法注释）
    }
    recordChange(worldName, x, y, z);
    submitUndo(() -> removeFromIndexAndMarks(worldName, x, y, z));
    if (exposes && instantRevealer != null) {
      // 投递到玩家线程：onBlockChangeObserved 内部要读 Bukkit.getCurrentTick()，Folia 上在封包线程读会抛
      // No currently ticking region——整条注销包被外层 catch 后 fail-open 放行，坐标就留在伪装索引里。
      // 投递失败（玩家离线/区域关闭）只放弃事件显形，周期巡检会兜底。
      Schedulers.onEntity(plugin, player,
          () -> instantRevealer.onBlockChangeObserved(player, worldName, x, y, z));
    }
  }

  /**
   * 该变更是否可能「露出后面的方块」：变更后不再是遮挡方块（空气、玻璃、水等）才可能。
   *
   * <p>读不到状态时按 {@code true} 处理（与改动前行为一致，宁可多一次事件显形也不漏显）。
   */
  private static boolean exposes(WrappedBlockData data) {
    if (data == null) {
      return true;
    }
    try {
      Material material = data.getType();
      return material == null || !material.isOccluding();
    } catch (Throwable throwable) {
      return true;
    }
  }

  /**
   * 世界是否在世界黑名单内（可离线单测；黑名单世界完全不参与反矿透）。
   * 统一走共享名单 {@link WorldBlacklistConfig}，不在此处重复实现匹配逻辑。
   */
  static boolean isBlacklisted(String worldName) {
    return WorldBlacklistConfig.isBlacklistedNow(worldName);
  }

  /**
   * 把注销任务交给后台单线程；执行器未就绪或队列满时退回同步执行（绝不丢注销）。
   */
  private void submitUndo(Runnable task) {
    ExecutorService executor = undoExecutor;
    if (executor == null) {
      task.run();
      return;
    }
    try {
      executor.execute(task);
    } catch (RejectedExecutionException rejected) {
      // 队列满 / 正在停用：退回同步（这一步本身不读世界，只是并发结构上的摘除）
      task.run();
    }
  }

  /**
   * 后台线程执行的真实变更注销：摘共享伪装索引 + 清该坐标在所有玩家上的已显形标记。
   *
   * <p><b>不再判回显</b>：调用方已在封包线程用 {@link #isOurRevealEcho} 判过，因此这里可以安全地
   * 在任意线程执行（索引与标记都是并发结构）。
   */
  private void removeFromIndexAndMarks(String worldName, int x, int y, int z) {
    if (worldName == null) {
      return;
    }
    // 清单与标记一次摘除（单一入口）。索引未命中也要摘标记——宁可多摘一次，也不能留孤儿标记
    // （sizeFor 虚增会让邻近显形的「整块跳过」提前成立，真实坐标被周期跳过）。
    boolean removedFromIndex = book.removePosition(worldName, x, y, z);
    if (removedFromIndex && stats != null) {
      stats.unregistered.increment();
    }
  }

  /** 记录一条真实方块变更（供区块包放行后补发；日志缺失时零开销）。 */
  private void recordChange(String worldName, int x, int y, int z) {
    ChunkChangeJournal journal = changeJournal;
    if (journal != null) {
      journal.record(worldName, x, y, z);
    }
  }

  /** 记录一个 section 的真实变更（上限 {@link #MAX_JOURNAL_RECORDS_PER_PACKET} 条）。 */
  private void recordChanges(String worldName, int[] coordinates, int count) {
    ChunkChangeJournal journal = changeJournal;
    if (journal == null || coordinates == null || count <= 0) {
      return;
    }
    int limit = Math.min(count, MAX_JOURNAL_RECORDS_PER_PACKET);
    for (int i = 0; i < limit; i++) {
      journal.record(worldName, coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]);
    }
  }

  /**
   * 该坐标的变更是否为本插件刚为该玩家发出的显形回显（见 {@link RevealEchoCheck}）。
   *
   * <p>判定不可用时返回 false（按真实变更处理，退回修复前行为）：宁可多摘一次索引，也绝不因判定
   * 失败而把真实变更漏掉（那会让索引残留已消失的坐标）。
   *
   * <p><b>未启用事件显形时不做区分</b>：此时本类拿不到显形器引用，只能按真实变更处理——这只影响
   * {@code proximity.instant-reveal.enabled=false} 的配置，默认配置不受影响。
   */
  private boolean isOurRevealEcho(Player player, String worldName, int x, int y, int z) {
    if (instantRevealer == null || player == null) {
      return false;
    }
    try {
      return instantRevealer.consumeOurRevealEcho(player.getUniqueId(), worldName, x, y, z);
    } catch (Throwable throwable) {
      return false;
    }
  }

  /** 世界名：只查玩家世界快照；封包线程不读 {@code player.getWorld()}（Folia 会抛 No currently ticking region）。 */
  private static String worldNameOf(Player player) {
    return player == null ? null : PlayerWorldIndex.worldName(player.getUniqueId());
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "方块变更注销解析失败，已按原包放行", throwable);
    }
  }
}
