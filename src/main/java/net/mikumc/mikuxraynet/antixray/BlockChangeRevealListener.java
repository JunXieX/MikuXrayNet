package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.util.Constants;
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
 * <p><b>为什么走异步通道（而不是同步监听）</b>：
 * <ol>
 *   <li>同步出站监听器会把该包类型登记为「需要主线程」，于是本插件自己用
 *       {@code sendServerPacket(..., filters=false)} 发出的合并包与显形包会被 ProtocolLib 推迟一 tick
 *       并改到主线程发送——合并模块会因此平白多一次主线程写入；异步注册没有这个副作用；</li>
 *   <li>异步链路上的监听器按优先级排定执行顺序，本类优先级最高，正常先于合并模块执行；</li>
 *   <li>即便顺序相反也不会漏：同一条异步链路上每个监听器都会看到同一个变更包，
 *       合并模块的取消只决定「原包最终是否下发」，不影响本类读到坐标。</li>
 * </ol>
 * 本类<b>不</b>登记处理延迟、不取消、不改包，纯观察，因此不会与合并时间窗互相打架。
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
 * 由邻近显形当 tick 补发「玩家身边、已伪装且视线可见」的坐标（周期巡检兜底不变）。
 * 未传入（{@code null}）时行为与旧版完全一致。
 */
public final class BlockChangeRevealListener extends PacketAdapter {

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final ObfuscatedChunkIndex obfuscatedChunkIndex;
  private final RevealedSet revealedSet;
  private final ProximityStats stats;
  /**
   * 反矿透配置：用于世界黑名单判定。本监听器随热重载重建（见 {@code AntiXrayRuntime#restartProximity}），
   * 因此持有的引用始终是「当前」配置，黑名单改动即时生效。{@code null} 表示不做黑名单判定。
   */
  private final AntiXrayConfig config;
  /**
   * 事件驱动即时显形（P1-4，可选）：观测到方块变更时交给邻近显形做「当 tick 补发邻域」。
   * {@code null} 表示不启用（只做索引注销，行为与旧版一致）。
   */
  private final ProximityRevealer instantRevealer;
  private final AtomicInteger errorCounter = new AtomicInteger();

  private AsyncListenerHandler handler;

  /**
   * @param obfuscatedChunkIndex 伪装区块索引；{@code null} 表示不做邻近显形（只做索引注销）
   * @param revealedSet          已显形集合；{@code null} 表示不做邻近显形
   * @param stats                显形统计；{@code null} 表示不统计
   */
  public BlockChangeRevealListener(Plugin plugin, ProtocolManager protocolManager,
      ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet, ProximityStats stats) {
    this(plugin, protocolManager, null, obfuscatedChunkIndex, revealedSet, stats, null);
  }

  /**
   * 完整构造（反矿透配置 + 事件驱动即时显形）。
   *
   * @param config          反矿透配置（用于世界黑名单判定）；{@code null} 表示不做黑名单判定
   * @param instantRevealer 邻近显形器；{@code null} 表示不启用事件显形
   */
  public BlockChangeRevealListener(Plugin plugin, ProtocolManager protocolManager,
      AntiXrayConfig config, ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet,
      ProximityStats stats, ProximityRevealer instantRevealer) {
    super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.obfuscatedChunkIndex = obfuscatedChunkIndex;
    this.revealedSet = revealedSet;
    this.stats = stats;
    this.instantRevealer = instantRevealer;
  }

  /** 注册异步监听器（真异步扣包，与合并模块同一条链路）。 */
  public void start() {
    handler = protocolManager.getAsynchronousManager().registerAsyncHandler(this);
    handler.start();
    plugin.getLogger().info("方块变更观察已启用：命中已伪装坐标即从显形索引移除");
  }

  /** 注销监听器；可重复调用，异常安全。 */
  public void stop() {
    AsyncListenerHandler current = handler;
    handler = null;
    if (current == null) {
      return;
    }
    try {
      protocolManager.getAsynchronousManager().unregisterAsyncHandler(current);
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
    observeChange(player, worldName, position.getX(), position.getY(), position.getZ());
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
    if (isBlacklisted(config, worldName)) {
      return;
    }

    int baseX = section.getX() << 4;
    int baseY = section.getY() << 4;
    int baseZ = section.getZ() << 4;

    // 坐标去重由 processChanges/SectionBuffer 统一完成（同一 section 内坐标编码唯一）
    int[] coordinates = new int[positions.length * 3];
    for (int i = 0; i < positions.length; i++) {
      short packed = positions[i];
      coordinates[i * 3] = baseX + (packed >> 8 & 15);
      coordinates[i * 3 + 1] = baseY + (packed & 15);
      coordinates[i * 3 + 2] = baseZ + (packed >> 4 & 15);
    }

    processChanges(worldName, coordinates, positions.length, obfuscatedChunkIndex, revealedSet, stats,
        (changed, changedCount) -> {
          if (instantRevealer != null && changedCount > 0) {
            instantRevealer.onSectionChangeObserved(player, worldName, changed, changedCount);
          }
        });
  }

  /**
   * 一个 section 的调度回调：生产实现交给 {@link ProximityRevealer#onSectionChangeObserved}；
   * 单测注入计数桩，用来断言「每个 section 恰好调度一次」。
   */
  @FunctionalInterface
  interface SectionDispatch {

    /** 一个 section 的全部变更坐标（已去重，扁平三元组，前 {@code count} 个有效）。 */
    void dispatch(int[] coordinates, int count);
  }

  /** 单个 section 收集到的变更坐标（可增长的扁平三元组缓冲，并按 section 内编码去重）。 */
  private static final class SectionBuffer {

    private final long key;
    /** 本 section 专属的去重代次（见 {@link SeenScratch}）；同一线程内每个 section 都不同。 */
    private final int generation;
    private final SeenScratch scratch;
    private int[] data = new int[48];
    private int count;

    private SectionBuffer(long key, int generation, SeenScratch scratch) {
      this.key = key;
      this.generation = generation;
      this.scratch = scratch;
    }

    private void add(int x, int y, int z) {
      int slot = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
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
   * 去重 scratch（线程本地，复用避免为每个 section 新分配一个 {@code boolean[4096]}）：
   * 用「代次戳数组」而非每次清零的布尔数组——同一 16³ 槽位只记一个 {@code stamp[slot] == generation}
   * 即视为已见，换一个 section 只递增代次，无需清空 4096 个槽。仅 netty 事件循环线程会用到，
   * 线程数量少且长生存，因此 ThreadLocal 复用是安全的（每个线程各自持有）。
   */
  private static final class SeenScratch {

    private static final ThreadLocal<SeenScratch> LOCAL = ThreadLocal.withInitial(SeenScratch::new);

    private final int[] stamp = new int[4096];
    private int generation;

    private static SeenScratch current() {
      return LOCAL.get();
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
   * 把一批变更坐标（绝对坐标三元组，可能跨多个 section）按 section（chunk + sectionY）分组后聚合处理：
   * 每个 section 只做一次批量索引摘除、一次邻域初筛、一次调度。
   *
   * <p>生产路径的多方块变更包本就只含一个 section，跨 section 分组是为将来/其它调用方保留的通用性；
   * 包级可见以便离线单测直接喂跨 section 坐标并注入调度计数桩（{@link SectionDispatch}）。
   *
   * <p><b>不变式</b>：无论索引是否命中，都要把该坐标的已显形标记同步摘除——索引未命中而标记仍在，
   * 就是会让「整块跳过」提前成立的孤儿标记（见 {@link #unregisterCoordinate}）。
   */
  static void processChanges(String worldName, int[] coordinates, int count,
      ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet, ProximityStats stats,
      SectionDispatch dispatch) {
    if (worldName == null || coordinates == null || count <= 0) {
      return;
    }

    java.util.List<SectionBuffer> sections = new java.util.ArrayList<>();
    SeenScratch scratch = SeenScratch.current();
    for (int i = 0; i < count; i++) {
      int x = coordinates[i * 3];
      int y = coordinates[i * 3 + 1];
      int z = coordinates[i * 3 + 2];
      long key = sectionKey(x, y, z);
      SectionBuffer buffer = null;
      for (int s = 0; s < sections.size(); s++) {
        if (sections.get(s).key == key) {
          buffer = sections.get(s);
          break;
        }
      }
      if (buffer == null) {
        buffer = new SectionBuffer(key, scratch.nextGeneration(), scratch);
        sections.add(buffer);
      }
      buffer.add(x, y, z);
    }

    for (int s = 0; s < sections.size(); s++) {
      processSection(worldName, sections.get(s), obfuscatedChunkIndex, revealedSet, stats, dispatch);
    }
  }

  /** 处理单个 section：一次批量摘除 + 一次调度。 */
  private static void processSection(String worldName, SectionBuffer section,
      ObfuscatedChunkIndex obfuscatedChunkIndex, RevealedSet revealedSet, ProximityStats stats,
      SectionDispatch dispatch) {
    int removed = 0;
    if (obfuscatedChunkIndex != null) {
      removed = obfuscatedChunkIndex.removePositions(worldName, section.data, section.count);
    }
    // 已显形标记必须与伪装清单同步摘除：维持「已显形坐标 ⊆ 区块伪装清单」不变式。
    // 索引未命中也要摘（宁可多摘一次也不能留孤儿标记，见 unregisterCoordinate）。
    // 一个 SectionBuffer 的坐标同属一个区块，故一次批量摘除即可（与索引侧 removePositions 对称）：
    // 逐坐标调用会为每个坐标重复一次 ChunkKey.ofBlock + 遍历全部玩家 + 逐 marker 加锁，
    // 一个 section 最多 4096 个坐标时会把这段工作量在 netty 线程上确定性放大。
    if (revealedSet != null) {
      revealedSet.removePositions(worldName, section.data, section.count);
    }
    if (removed > 0 && stats != null) {
      stats.unregistered.add(removed);
    }
    if (dispatch != null) {
      dispatch.dispatch(section.data, section.count);
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
   * 统一的变更观察入口：先注销（既有行为），再触发事件显形（P1-4，可选）。
   *
   * <p>两个动作共用同一次坐标解析，热路径上不产生任何额外分配；事件显形的全部筛选
   * （邻域初筛、身边半径、射线、限额、去重）都在 {@link ProximityRevealer#onBlockChangeObserved}
   * 内部完成，未启用时只有一个空引用判断的开销。
   */
  private void observeChange(Player player, String worldName, int x, int y, int z) {
    // 黑名单世界不做任何反矿透工作：不注销显形、不触发事件即时显形。
    if (isBlacklisted(config, worldName)) {
      return;
    }
    // 1.1.6 起显形包走 Paper 原生通道（sendMultiBlockChange / sendBlockChange），因此本监听器
    // 也会看到「我们自己发出的显形回显」。回显不改变服务端内容，因此只摘索引（与真实变更相同），
    // 不再有任何「磁盘缓存失效」动作——磁盘条目的有效性由负载里的原始区块字节指纹判定
    // （见 DiskCacheStore 类注释），回显不改变区块字节，天然不需要作废任何缓存条目。
    unregisterCoordinate(worldName, x, y, z);
    if (instantRevealer != null) {
      instantRevealer.onBlockChangeObserved(player, worldName, x, y, z);
    }
  }

  /**
   * 世界是否在反矿透黑名单内（可离线单测；黑名单世界完全不参与反矿透）。
   * 复用 {@link AntiXrayConfig#isBlacklisted(String)}，不在此处重复实现匹配逻辑。
   */
  static boolean isBlacklisted(AntiXrayConfig config, String worldName) {
    return config != null && config.isBlacklisted(worldName);
  }

  /**
   * 从「伪装区块清单」与「各玩家的已显形标记」中同步摘除该坐标。
   *
   * <p>两者必须同步摘除，才能维持「已显形坐标 ⊆ 区块伪装清单」这一不变式——「整块已全部显形
   * 则跳过该区块」的判断依赖它。只摘变更的那一个坐标，该区块其它坐标照常显形（与既有行为一致）。
   *
   * <p><b>为什么索引未命中也要摘已显形标记（容忍「标记先行」）</b>：邻近显形改为「先标记、再发包」
   * 后，我们发出的显形包会回显到这里。若另一个玩家已先把该坐标从共享索引里摘掉，本次 {@code removePosition}
   * 就未命中，但本玩家刚写入的标记仍在——那是一枚孤儿标记（已显形数虚增会让「整块跳过」提前成立，
   * 真实坐标被长期漏显形）。因此这里无条件摘标记：正常情况无副作用，异常情况恰好清掉孤儿。
   */
  private void unregisterCoordinate(String worldName, int x, int y, int z) {
    if (worldName == null || obfuscatedChunkIndex == null) {
      return;
    }
    boolean removedFromIndex = obfuscatedChunkIndex.removePosition(worldName, x, y, z);
    if (revealedSet != null) {
      revealedSet.removePosition(worldName, x, y, z);
    }
    if (removedFromIndex && stats != null) {
      stats.unregistered.increment();
    }
  }

  /** 世界名（封包线程读取一个不可变字符串，不触碰任何世界对象内容）。 */
  private static String worldNameOf(Player player) {
    try {
      World world = player.getWorld();
      return world == null ? null : world.getName();
    } catch (Throwable throwable) {
      return null;
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "方块变更注销解析失败，已按原包放行", throwable);
    }
  }
}