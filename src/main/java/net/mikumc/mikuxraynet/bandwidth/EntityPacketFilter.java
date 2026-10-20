package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

/**
 * 零位移实体包抑制：出站的<b>纯位移</b>包（{@code REL_ENTITY_MOVE}）位移全为 0 时取消发送。
 *
 * <p><b>朝向包不做零取消</b>：{@code REL_ENTITY_MOVE_LOOK} / {@code ENTITY_LOOK} 里的 yaw/pitch 是
 * 绝对量化角（0 表示朝正南 / 平视），不是「相对上次的增量」；按全 0 取消会让客户端保留旧朝向，
 * 产生持久朝向错误。故这两个包一律放行（判定依据见 {@link MovementDelta} 类注释）。
 *
 * <p>拦截对象为 ProtocolLib 的 {@code REL_ENTITY_MOVE} / {@code REL_ENTITY_MOVE_LOOK} /
 * {@code ENTITY_LOOK} 三个包（PacketEvents 中对应 ENTITY_RELATIVE_MOVE /
 * ENTITY_RELATIVE_MOVE_AND_ROTATION / ENTITY_ROTATION，ProtocolLib 常量名不同）。
 *
 * <p>字段读取一律走 {@code getSpecificModifier} 并做数量与类型容错：字段形态与预期不符
 * （不同服务端版本的字段布局差异）时直接放行原包，绝不误判取消。
 *
 * <p><b>白名单索引的维护方式</b>：实体 id → 类型 的索引需要 Bukkit 世界访问，因此按平台分别处理：
 * <ul>
 *   <li><b>Paper 系</b>（含 Leaf 等下游分支）：<b>增量维护</b>——由
 *       {@link EntityAddToWorldEvent} / {@link EntityRemoveFromWorldEvent} 在实体加入/离开世界时
 *       即时登记/摘除（不再等下一个全量刷新周期），另保留一个<b>低频、按区块分片的兜底重建</b>
 *       （{@link #INDEX_REBUILD_TICKS} 周期触发，每次最多 {@link #REBUILD_CHUNK_BUDGET} 个区块）
 *       补齐万一漏接的事件。启动时先做一次不限额的全量建索引，让插件启用前就已存在的实体立即生效。
 *       世界卸载（{@link WorldUnloadEvent}）时整表摘除该世界的外层条目，避免动态世界反复装卸后无界增长。</li>
 *   <li><b>Folia 系</b>：无法跨区域安全枚举全服实体（也无法确认该平台是否为所有实体触发上述事件），
 *       因此<b>建不起类型索引</b>。于是分两种情况：
 *       <ul>
 *         <li>白名单<b>非空</b>（含默认配置）：本模块在 Folia 上<b>整项不注册</b>——没有索引就无从判断
 *             「这个零位移包属于盔甲架 / 展示实体」，照常取消会让它们的姿态卡住；
 *             {@code ThrottlePipeline.plan} 直接判 false，启动日志按平台原因说明，绝不构造自我停用的空壳。</li>
 *         <li>白名单<b>为空</b>：不需要索引，本模块照常注册（所有实体按「非白名单」处理，语义与 Paper 一致）。</li>
 *       </ul>
 *       即：本模块的「白名单」语义<b>仅在 Paper 系生效</b>；Folia 上要么整项关（有白名单），要么无白名单可用。
 *   </li>
 * </ul>
 */
public final class EntityPacketFilter extends PacketAdapter implements Listener {

  /**
   * 兜底重建周期（tick）。
   *
   * <p>增量事件已覆盖日常增删，兜底只用于补「万一漏接的事件」，因此刻意取低频（600 tick = 30 秒）。
   * 旧实现对全服实体每 100 tick（5 秒）枚举一次世界，是主线程上的固定开销；改为低频后这笔开销
   * 降到约 1/6，同时白名单准确性反而更高（新实体秒级生效，而非等下一个刷新周期）。
   *
   * <p><b>兜底重建本身也做了分片</b>：见 {@link #REBUILD_CHUNK_BUDGET}。
   */
  private static final long INDEX_REBUILD_TICKS = 600L;

  /**
   * 单次兜底重建最多处理的<b>区块数</b>（跨 tick 分片预算）。
   *
   * <p><b>为什么分片</b>：旧实现每个兜底周期都用 {@code world.getEntities()} 一次性拷贝全服实体列表
   * （万人实体即毫秒级主线程尖峰）。现在改为按「已加载区块」分批：每次兜底只处理至多本预算个区块，
   * 游标跨 tick 推进，因此一轮完整覆盖摊到多个周期、单 tick 主线程开销有界，彻底消除该尖峰。
   * 增量事件（实体加入/离开世界）仍是主力；兜底只补漏接，覆盖慢几轮无碍。
   */
  private static final int REBUILD_CHUNK_BUDGET = 256;

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final BandwidthConfig.EntityPackets config;
  private final ThrottleStats stats;
  private final Set<String> whitelist;
  /** 启动期解析出的「白名单命中的实体类型」：刷新索引时先用 {@code entity.getType()} 直接比对，零字符串分配。 */
  private final Set<EntityType> whitelistedTypes;
  /**
   * 白名单里「解析不出对应实体类型」的归一化条目（通常为空）。
   *
   * <p>只有它非空时才需要退回「逐实体取 key 字符串再归一化比较」的旧路径——以此保证：
   * 即使白名单里混入了拼错的/未知的类型名，语义也与旧实现完全一致。
   */
  private final Set<String> unresolvedWhitelist;
  private final AtomicInteger errorCounter = new AtomicInteger();
  /** 「位移字段形态不符」只告警一次（CAS 抢占）：它是「服务端/ProtocolLib 改了包布局」的信号，必须可见但不刷屏。 */
  private final AtomicBoolean shapeWarned = new AtomicBoolean();

  /**
   * 实体 id → 归一化类型键。
   *
   * <p><b>为什么用并发容器</b>：增量事件可能在实体所属的区域线程（Folia）或主线程触发，而读取来自
   * 封包线程；{@link ConcurrentHashMap} 保证三者安全，且未命中即 {@code null}（等同旧 volatile 快照）。
   */
  /**
   * 实体类型索引：<b>世界名 → (entityId → 白名单类型键)</b>。
   *
   * <p><b>为什么必须带世界维度</b>：Java 版的 entityId 是<b>每个世界各自计数</b>的（每个世界从 1 开始
   * 分配），因此主世界 id=5 与下界 id=5 是两个完全不同的实体。旧实现只用 entityId 作键，跨世界必然
   * 撞号：白名单实体（盔甲架、展示实体等）在 A 世界登记后，B 世界同号的普通实体会被误判成白名单而放行
   * （少省带宽），反之 B 世界的白名单实体会被误判成普通实体、其零位移包被取消（客户端位置不同步）。
   * 两级索引让不同世界的 id 互不干扰；每级都是无锁并发容器，热路径只是两次哈希查找、零分配。
   *
   * <p><b>为什么外层条目必须随世界卸载清理</b>：实体事件与兜底重建都只触碰<b>当前已加载</b>的世界
   * （{@code Bukkit.getWorlds()}），世界一旦卸载，它对应的外层条目就再没有任何读者与写者——若不在卸载时
   * 摘除，它会连同内层表永久驻留在堆上。动态创建/卸载世界的服务器（模板世界、小游戏房间、副本）每卸载
   * 一次就沉淀一份，内存随「历史世界总数」无界增长；摘除后外层大小恒等于当前已加载世界数。
   * 清理本身只是一次 {@link ConcurrentHashMap#remove(Object)}，与封包线程的读取天然并发安全。
   */
  private final Map<String, Map<Integer, String>> entityTypeIndex = new ConcurrentHashMap<>();
  private ScheduledTask indexTask;

  /**
   * 兜底重建的跨 tick 分片游标：当前世界下标 + 每个世界内已处理到的区块下标。
   *
   * <p>只在 {@code GlobalRegionScheduler}（Paper 主线程）上读写，天然单线程，无需任何并发控制。
   */
  /**
   * 兜底重建的推进游标：<b>世界级</b>用原子整数、<b>逐世界区块级</b>用并发 map。
   *
   * <p><b>为什么不用 int + HashMap</b>：{@code stop()} 会在「停用/热重载」时清空它们，而
   * {@code ThrottlePipeline} 明确声明「停用/重载可能来自不同线程」。当前 Paper 上两者恰好同在主线程、
   * Folia 上根本不建索引任务，因此实际不可达；但一旦该前提变化（或调用方换线程），普通 HashMap 的并发写
   * 就是数据竞争（最坏死循环/丢更新，表现为兜底重建卡在某个世界）。改成并发容器后该前提不再需要成立。
   */
  private final AtomicInteger rebuildWorldCursor = new AtomicInteger();
  private final Map<String, Integer> rebuildChunkCursors = new ConcurrentHashMap<>();

  public EntityPacketFilter(Plugin plugin, ProtocolManager protocolManager,
      BandwidthConfig.EntityPackets config, ThrottleStats stats) {
    super(plugin, ListenerPriority.LOW, PacketType.Play.Server.REL_ENTITY_MOVE,
        PacketType.Play.Server.REL_ENTITY_MOVE_LOOK, PacketType.Play.Server.ENTITY_LOOK);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.config = config;
    this.stats = stats;
    this.whitelist = normalizeAll(config.whitelist());
    // 启动期把白名单解析成实体类型集合：刷新索引时先用 entity.getType() 直接比对，
    // 只有解析不出的条目才回退到字符串比较（见 unresolvedWhitelist）。
    Set<EntityType> types = EnumSet.noneOf(EntityType.class);
    Set<String> unresolved = new HashSet<>(this.whitelist);
    int keyless = 0;
    for (EntityType type : EntityType.values()) {
      // EntityType 的 path（不带命名空间、全小写）正是白名单归一化后的形态，取它不产生字符串分配
      String path = keyPathOrNull(type);
      if (path == null) {
        // 无键类型（部分服务端/衍生端会附加 UNKNOWN 之类的条目）：跳过即可，
        // 绝不因单个条目让整个带宽模块装配失败（真机已复现过一次）。
        keyless++;
        continue;
      }
      if (this.whitelist.contains(path)) {
        types.add(type);
        unresolved.remove(path);
      }
    }
    this.whitelistedTypes = types;
    this.unresolvedWhitelist = unresolved;
    if (keyless > 0) {
      plugin.getLogger().warning("检测到 " + keyless + " 个实体类型没有命名空间键（部分服务端/衍生端会附加此类条目），"
          + "已跳过它们；不影响其余实体类型的白名单判定与零位移包取消");
    }
  }

  /** 注册监听器；白名单非空时启动实体类型索引的增量维护与低频兜底重建。 */
  public void start() {
    if (!whitelist.isEmpty()) {
      if (PlatformSupport.isFolia()) {
        // Folia 下无法从单一线程安全枚举全服实体，因此建不了类型索引；没有索引就无从判断
        // 「这个零位移包属于白名单实体（盔甲架 / 展示实体 / 物品展示…）」——而白名单里的实体
        // 恰恰是「靠持续下发零位移包维持客户端姿态」的那一类。此时若照常取消零位移包，
        // 玩家会看到这些实体位置/姿态卡住不动（能直接感知的体验问题）。
        //
        // 因此：宁可整项停用（少省一点带宽），也不能牺牲玩家可见的体验。
        plugin.getLogger().warning("Folia 下无法安全枚举实体、白名单索引建不起来："
            + "已停用「零位移实体包取消」（避免盔甲架/展示实体姿态卡住）；其余带宽功能不受影响");
        return;
      }
      {
        // 增量事件（实体加入/离开世界）与「世界卸载清表」由本类作为 Listener 一并接收；前者在实体所属线程、
        // 后者在卸载线程触发，两者都只更新并发索引，不触碰 Bukkit 世界对象。
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 启动时先做一次「不限额」的全量建索引：让「插件启用前就已存在」的实体立即命中白名单
        // （一次性启动开销可接受；运行期兜底才走区块分片，避免周期尖峰）
        refreshEntityTypeIndex(Integer.MAX_VALUE);
        // GlobalRegionScheduler：Paper 上落在主线程；周期以 tick 计。低频兜底只补漏接的事件，且已分片。
        indexTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
            scheduled -> refreshEntityTypeIndex(REBUILD_CHUNK_BUDGET), INDEX_REBUILD_TICKS,
            INDEX_REBUILD_TICKS);
      }
    }
    // 封包监听必须<b>最后</b>注册：白名单判定依赖上面建好的类型索引，而全量建索引在大服上要遍历
    // 所有世界的已加载区块（数百毫秒级）。若先行注册，封包线程会在索引仍为空时就开始判定——
    // isWhitelisted 恒为 false，于是白名单本想豁免的实体（盔甲架 / 展示实体等）在该窗口内
    // 会被误取消零位移包。先建索引、后开闸，窗口即不复存在。
    protocolManager.addPacketListener(this);
    plugin.getLogger().fine("带宽模块已启用：零位移实体包取消（白名单 " + whitelist.size() + " 项）");
  }

  /** 注销监听器与索引任务。 */
  public void stop() {
    if (indexTask != null) {
      indexTask.cancel();
      indexTask = null;
    }
    try {
      HandlerList.unregisterAll(this);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销零位移实体事件监听时出现异常（通常可忽略）", throwable);
    }
    try {
      protocolManager.removePacketListener(this);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销零位移监听器时出现异常（通常可忽略）", throwable);
    }
    entityTypeIndex.clear();
    rebuildChunkCursors.clear();
    rebuildWorldCursor.set(0);
  }

  /**
   * 实体加入世界：即时登记其类型键（增量维护）。
   *
   * <p>事件在实体所属线程触发（Paper 主线程 / Folia 区域线程），此处只读实体自身的 id 与类型，
   * 不触碰其它实体或世界，因此线程安全；登记进并发索引后封包线程立即可读。
   *
   * <p><b>为什么先判类型、再建表</b>：绝大多数实体都不在白名单里（普通怪、掉落物、箭矢、经验球…），
   * 若像旧实现那样先 {@code typeIndexFor} 建表再判类型，那么「世界里只有非白名单实体」时也会凭空留下
   * 一张空的内层表——外层 map 有界、内层却随「曾出现过实体的世界」增多，等于把泄漏换了个位置；
   * 而空表对封包线程毫无用处（{@code isWhitelisted} 取不到键照样返回 false）。改为命中白名单才建表，
   * 内层表只在「真的有白名单实体」的世界里存在，且能随 {@link WorldUnloadEvent} 一次性摘干净。
   * 非白名单分支只 {@code get} 已有表：没有表就意味着该世界从未登记过白名单实体，此时无需清残留
   * （没有表就没有残留），直接跳过即可，热路径也少一次容器写。
   */
  @EventHandler
  public void onEntityAddToWorld(EntityAddToWorldEvent event) {
    try {
      Entity entity = event.getEntity();
      String typeKey = typeKeyFor(entity.getType());
      if (typeKey != null) {
        // 白名单实体：命中才建表（事件在实体所属线程触发，读它自己的世界名安全，不跨区域）
        typeIndexFor(entity.getWorld().getName()).put(entity.getEntityId(), typeKey);
        return;
      }
      // 非白名单实体：必须清掉可能残留的旧映射。实体 id 会被服务端复用，若上一个占用者在白名单里，
      // 残留映射会让这个新实体被误判为白名单（零位移包不再取消）——方向安全但白费带宽。
      // 只取已有表：为 null 说明该世界从未登记过白名单实体，也就没有残留可清，不能顺手建表。
      Map<Integer, String> perWorld = entityTypeIndex.get(entity.getWorld().getName());
      if (perWorld != null) {
        perWorld.remove(entity.getEntityId());
      }
    } catch (Throwable throwable) {
      // fail-open：登记失败最多让该实体走「未命中白名单」（保守侧），绝不影响封包主链路
      logThrottled(throwable);
    }
  }

  /**
   * 实体离开世界：摘除其类型索引，避免实体 id 被复用后残留错误映射。
   *
   * <p><b>必须用事件自带的世界，不能用 {@code entity.getWorld()}</b>：Paper 的
   * {@link EntityRemoveFromWorldEvent} 在 Javadoc 里明确写着「实体在事件触发前已被更新，
   * 因此实体的 world 可能与 getWorld() 不同」；{@code getWorld()} 的语义是「实体正在离开的那个世界」。
   *
   * <p>旧实现按 {@code entity.getWorld()} 取键，跨世界传送（A→B）时就会去 <b>B</b> 的索引里删掉同号的
   * 另一个实体，而 A 里的旧记录留着：① B 里那个白名单实体被误摘 → 它的零位移包会被取消；
   * ② A 的残留记录会让将来复用该 id 的普通实体被误判为白名单（少取消一些包）。两个方向都只影响
   * 白名单命中率、不改变实体位置同步，但根因明确，直接改用正确来源。
   *
   * <p>顺带把「当前世界」也清一次（幂等）：{@code EntityAddToWorldEvent} 会为目标世界重新登记，
   * 所以多清一条的代价只是某个同号实体短暂不被豁免；而漏清一条会留下永久残留。
   */
  @EventHandler
  public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
    try {
      Entity entity = event.getEntity();
      int entityId = entity.getEntityId();
      // 权威来源：事件声明「正在离开的那个世界」（见方法说明，不能拿 entity.getWorld() 代替）
      removeFromWorldIndex(event.getWorld().getName(), entityId);
      // 兜底：实体此刻可能已被更新到目标世界，把当前世界也清一次（幂等，见方法说明）
      removeFromWorldIndex(entity.getWorld().getName(), entityId);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /** 从某世界的类型索引里摘除一个实体 id（表不存在即无残留可清，绝不顺手建表）。 */
  private void removeFromWorldIndex(String worldName, int entityId) {
    Map<Integer, String> perWorld = entityTypeIndex.get(worldName);
    if (perWorld != null) {
      perWorld.remove(entityId);
    }
  }

  /**
   * 世界卸载：整表摘除外层条目，让索引大小恒等于当前已加载世界数。
   *
   * <p><b>为什么必须在卸载时清理</b>：实体事件与兜底重建只覆盖 {@code Bukkit.getWorlds()} 里仍加载的世界，
   * 卸载后这份外层条目既不会再有写入、也不会再被读到，留着就是纯垃圾。动态世界服务器上「卸载」是常态
   * （小游戏房间、副本、模板世界），不摘除即随历史世界数无界增长。
   *
   * <p><b>为什么在这里直接操作并发 map 是安全的</b>：{@link WorldUnloadEvent} 在卸载线程触发，可能不是主线程；
   * 而本方法只做一次 {@link ConcurrentHashMap#remove(Object)}，不触碰 Bukkit 世界对象、不枚举实体，
   * 与封包线程的 {@code get} 并发安全。世界卸载后不再有新实体加入，摘表期间也不存在「刚写进去就丢」的
   * 语义问题；最坏情况是封包线程在摘除前读到旧表，而那批实体已随世界卸载，其包也不会再发往玩家。
   *
   * <p>注意只摘外层、不 {@code clear} 内层表：内层表可能正被实体离开世界的事件写入，直接清空会与
   * 那些线程竞争；丢掉引用后整张内层表随之不可达，交给 GC 回收更简单也更安全。
   * 同理也不在这里动 {@code rebuildChunkCursors}——它每完成一轮覆盖就整体 {@code clear()}，
   * 本身不随卸载增长，无需在此处理（且与游标字段一样已是并发容器，跨线程动它也不会坏）。
   */
  @EventHandler
  public void onWorldUnload(WorldUnloadEvent event) {
    try {
      entityTypeIndex.remove(event.getWorld().getName());
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    if (!config.skipZeroMovement() || event.isCancelled() || event.getPlayer() == null) {
      return;
    }
    Player packetPlayer = event.getPlayer();
    // 世界黑名单：命中世界的玩家不做任何带宽优化（零位移取消一并跳过）。
    // 名单为空（默认）时 isBlacklistedNow 直接早退，热路径近乎零开销。
    // 世界名同时用于「实体类型索引」的世界维度（entityId 按世界各自计数，必须带上世界才能避免撞号）。
    //
    // 封包线程读 player.getWorld() 属跨线程读取（Folia 上是跨区域读实体状态）：与同包另两个模块
    // （BlockChangeMerger / AfkTracker）保持同一纪律——取不到世界名就按「不做任何优化」直通，
    // 绝不让一次读取失败把包扣住、也不让异常逃出封包监听器。
    String worldName;
    try {
      worldName = packetPlayer.getWorld().getName();
    } catch (Throwable ignored) {
      pass();
      return;
    }
    if (WorldBlacklistConfig.isBlacklistedNow(worldName)) {
      return;
    }
    // 统一走直通名单：只读并发集合，封包线程不触碰 Bukkit 权限 API
    if (BypassRegistry.isBypassedNow(packetPlayer.getUniqueId())) {
      return;
    }

    try {
      PacketContainer packet = event.getPacket();
      PacketType type = event.getPacketType();

      // 到达口径（三分）：取消 0 的三种成因只能靠「哪一类包真的到了」来区分，见 ThrottleStats 说明。
      if (type == PacketType.Play.Server.REL_ENTITY_MOVE) {
        stats.entityMovePackets.increment();
      } else if (type == PacketType.Play.Server.REL_ENTITY_MOVE_LOOK) {
        stats.entityMoveRotPackets.increment();
      } else {
        stats.entityLookPackets.increment();
      }

      // 只有纯位移包（REL_ENTITY_MOVE）才可能被判为冗余取消；朝向包（REL_ENTITY_MOVE_LOOK / ENTITY_LOOK）
      // 里的 yaw/pitch 是绝对量化角（0 表示朝正南 / 平视），按全 0 取消会让客户端保留旧朝向（持久朝向错误，
      // 依据见 MovementDelta 类注释），因此一律放行。提前在这里返回还顺带省下为朝向包构造 short 位移
      // StructureModifier 的分配（ProtocolLib 的类型化访问器每次都会 new 一个 StructureModifier）。
      if (type != PacketType.Play.Server.REL_ENTITY_MOVE) {
        pass();
        return;
      }

      // 纯位移包：读取 dx/dy/dz 与实体 id。
      //
      // 为什么不用 ProtocolLib 的类型化访问器（getSpecificModifier / getIntegers）：它们的字段表只含
      // 「具体类的声明字段」，而 MC 1.21.2 起该包把 xa/ya/za 与 entityId 都放进了基类
      // ClientboundMoveEntityPacket（子类不再声明任何实例字段）→ 两个访问器都取不到东西，
      // 本模块会静默退化为永远放行。故改走「沿继承链定位」的 MovePacketFields（详见其类注释）。
      Object handle = packet.getHandle();
      MovePacketFields.Access access = MovePacketFields.of(handle);
      if (access == null) {
        // 字段定位不到（服务端/映射变更）：fail-open 放行。这一分支此前是纯静默放行，面板只剩「取消 0」，
        // 与「本来就没包可取消」无法区分、极易被误判成功能失效，因此计数 + 首次告警一次。
        stats.entityMoveShapeMismatch.increment();
        warnShapeMismatchOnce(handle);
        pass();
        return;
      }

      // 注：零位移取消会一并丢弃本包的 onGround 布尔。要修它必须按 entityId 维护「上次已下发的 onGround」
      // 才能判断该布尔是否携带新信息，而这需要与实体「加入/离开世界」事件配套的淘汰逻辑；该事件仅在 Paper
      // 上登记（Folia 下无法安全枚举实体、维护不了该索引），在 Folia 上该表会无界增长（内存泄漏风险）——
      // 而 Folia 恰恰是 onGround 丢弃最严重的平台（其白名单索引为空 → isWhitelisted 恒 false → 所有零位移包
      // 都会被取消）。即便改用「容量封顶 + 清空」换取有界，也要在每个相对移动包（本项正是要削减开销的热路径）
      // 上多读一次布尔、多一次映射操作，得不偿失。权衡后此处保持不变：onGround 偶发滞后属低风险观感问题，
      // 无界状态 / 热路径加负的风险更高。
      boolean redundant = MovePacketFields.isZeroMove(access, handle);

      if (!redundant || isWhitelisted(worldName, (int) access.entityId().get(handle))) {
        pass();
        return;
      }

      event.setCancelled(true);
      stats.entityPacketsCancelled.increment();
    } catch (Throwable throwable) {
      // fail-open：字段形态与预期不符，放行原包
      pass();
      logThrottled(throwable);
    }
  }

  private void pass() {
    stats.entityPacketsPassed.increment();
  }

  private boolean isWhitelisted(String worldName, int entityId) {
    Map<Integer, String> perWorld = entityTypeIndex.get(worldName);
    if (perWorld == null) {
      return false;
    }
    String typeKey = perWorld.get(entityId);
    return typeKey != null && whitelist.contains(typeKey);
  }

  /**
   * 取（必要时懒建）某世界的实体类型索引；只由实体所属线程/主线程写入，封包线程只读。
   *
   * <p>调用方必须先确认实体类型命中白名单再调用：这里会无条件建表，用在不命中的分支上就会留下
   * 「没有任何条目」的空内层表，让外层有界、内层无界的泄漏重新出现（见 {@code onEntityAddToWorld}）。
   */
  private Map<Integer, String> typeIndexFor(String worldName) {
    return entityTypeIndex.computeIfAbsent(worldName, ignored -> new ConcurrentHashMap<>());
  }

  /**
   * 兜底重建（分片版）：按「世界 → 已加载区块」分批、每批限量处理，游标跨 tick 推进。
   *
   * <p><b>与旧实现的语义差别</b>：旧实现每个周期用 {@code world.getEntities()} 一次性拷贝并重建全服索引
   * （万人实体毫秒级尖峰）。现在改为按区块分片，单次调用最多处理 {@code chunkBudget} 个区块，
   * 主线程开销有界。索引更新是逐实体、幂等的（白名单类型 → put；否则 → remove，顺带清掉「id 被复用为
   * 非白名单实体」的陈旧映射），因此跨 tick 分片不引入任何并发/竞态：实体消失由增量 remove 事件兜住，
   * 兜底只补「漏接的加入/类型」。
   *
   * <p>{@code chunkBudget = Integer.MAX_VALUE} 时一轮走遍全部世界（启动期用一次，尽快让既有实体生效）。
   * 本方法只在 Paper 主线程被调用（GlobalRegionScheduler），游标字段无需同步。
   */
  private void refreshEntityTypeIndex(int chunkBudget) {
    try {
      List<World> worlds = Bukkit.getWorlds();
      if (worlds.isEmpty()) {
        return;
      }
      if (rebuildWorldCursor.get() >= worlds.size()) {
        rebuildWorldCursor.set(0);
      }
      int remaining = chunkBudget;
      while (remaining > 0 && rebuildWorldCursor.get() < worlds.size()) {
        World world = worlds.get(rebuildWorldCursor.get());
        Chunk[] chunks = world.getLoadedChunks();
        if (chunks.length == 0) {
          rebuildWorldCursor.incrementAndGet();
          continue;
        }
        int cursor = Math.floorMod(rebuildChunkCursors.getOrDefault(world.getName(), 0), chunks.length);
        // 本批只处理「从 cursor 到数组末尾」这一段：若按 take = min(remaining, chunks.length) 取，
        // 当某世界已加载区块数 > 周期预算时 take 永远小于 chunks.length，下面的「世界完成」判定
        // 就永远为假 —— rebuildWorldCursor 不推进，兜底只覆盖 Bukkit.getWorlds() 的第 0 个世界，
        // 其余世界（下界/末地）永远拿不到兜底，且「一轮完成」分支也永不执行（静默失效、无计数）。
        int take = Math.min(remaining, chunks.length - cursor);
        // 内层表<b>按需创建</b>、且提到区块循环之外：旧实现把 typeIndexFor(...) 放在区块循环里，
        // 于是「只要有已加载区块」的世界都会被无条件建一张内层表——即使该世界一个白名单实体都没有。
        // 这既与本类声明的不变量（「非白名单分支绝不建表，否则留下空内层表」）矛盾，也白做每区块一次
        // computeIfAbsent。这里先只查不建；本批真的扫到白名单实体时才建表，并在后续区块复用。
        Map<Integer, String> perWorld = entityTypeIndex.get(world.getName());
        for (int i = 0; i < take; i++) {
          Chunk chunk = chunks[cursor + i];
          for (Entity entity : chunk.getEntities()) {
            int id = entity.getEntityId();
            String typeKey = typeKeyFor(entity.getType());
            if (typeKey != null) {
              if (perWorld == null) {
                // 该世界本批第一个白名单实体：此刻才建内层表（与 onEntityAddToWorld 的「命中才建表」一致）
                perWorld = typeIndexFor(world.getName());
              }
              perWorld.put(id, typeKey);
            } else if (perWorld != null) {
              // 非白名单实体：清掉可能残留的陈旧映射（id 被复用时的正确性兜底）。
              // 表不存在时无需清理（本来就一条条目都没有），因此只在已建表时执行。
              perWorld.remove(id);
            }
          }
        }
        // 走完整个世界 → 该世界游标归零、轮到下一个世界；否则保留游标，下次继续这个世界的剩余区块
        boolean worldDone = cursor + take >= chunks.length;
        rebuildChunkCursors.put(world.getName(), worldDone ? 0 : cursor + take);
        remaining -= take;
        if (worldDone) {
          rebuildWorldCursor.incrementAndGet();
        }
      }
      if (rebuildWorldCursor.get() >= worlds.size()) {
        // 一轮覆盖完成：游标全部归零，下个周期从头再来
        rebuildWorldCursor.set(0);
        rebuildChunkCursors.clear();
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  /**
   * 该实体类型是否命中白名单；命中返回归一化类型键，未命中返回 {@code null}。
   *
   * <p>先用解析出的实体类型直接比对（零字符串分配）：白名单通常只有个位数条目，全服实体动辄上千，
   * 对每个实体都做一次 {@code getKey().toString() + normalize()} 字符串分配是遍历的主要成本。
   * 只有白名单里存在「解析不出类型」的条目时才回退到归一化字符串比较，以保证语义与旧实现一致。
   */
  private String typeKeyFor(EntityType type) {
    if (whitelistedTypes.contains(type)) {
      // 命中项取 EntityType 的 path（引用既有常量，不新建字符串）
      String path = keyPathOrNull(type);
      if (path != null) {
        return path;
      }
    }
    if (!unresolvedWhitelist.isEmpty()) {
      String path = keyPathOrNull(type);
      if (path != null) {
        String typeKey = normalize(path);
        if (whitelist.contains(typeKey)) {
          return typeKey;
        }
      }
    }
    return null;
  }

  /**
   * 取实体类型的命名空间路径（形如 {@code armor_stand}）。
   *
   * <p><b>为什么要 try/catch 而不是直接 {@code type.getKey().getKey()}</b>：部分服务端与其衍生端会在
   * {@code EntityType} 里附加没有命名空间键的条目（如 UNKNOWN），对它取键会抛
   * {@link IllegalArgumentException}。旧实现在构造期遍历 {@code EntityType.values()} 时对每个类型都取键，
   * 一旦碰到这类条目就会让**整个带宽子模块装配失败**（真机已复现）；热路径的字符串回退同样会踩到。
   * 这类条目一律按「解析不出」处理，由既有回退/跳过逻辑兜底，绝不因此中断装配或抛到封包链路。
   *
   * @return 路径键；类型没有键（或服务端未提供键）时返回 {@code null}
   */
  static String keyPathOrNull(EntityType type) {
    NamespacedKey key;
    try {
      key = type.getKey();
    } catch (IllegalArgumentException keylessType) {
      return null;
    }
    return key == null ? null : key.getKey();
  }

  private static Set<String> normalizeAll(Set<String> values) {
    Set<String> normalized = new HashSet<>(Math.max(4, values.size()));
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        normalized.add(normalize(value));
      }
    }
    return normalized;
  }

  /** 统一为 {@code armor_stand} 这类不带命名空间、全小写的键。 */
  private static String normalize(String value) {
    String lower = value.toLowerCase(Locale.ROOT).trim();
    int colon = lower.indexOf(':');
    return colon >= 0 ? lower.substring(colon + 1) : lower;
  }

  /**
   * 「纯位移包的位移字段定位不到」只告警一次。
   *
   * <p>该分支意味着服务端或映射改了该包的字段布局，零位移取消会全部退化为放行——面板上表现为「取消 0」，
   * 与「本来就没有可取消的包」无法区分，故必须把这条第一次出现的信号打出来（只一次，不刷屏）。
   * 告警带上包对象的实际类名，便于直接判断是「字段上移到基类」（本类已按继承链兼容）还是别的结构变更。
   */
  private void warnShapeMismatchOnce(Object handle) {
    if (shapeWarned.compareAndSet(false, true)) {
      plugin.getLogger().warning("纯位移包（REL_ENTITY_MOVE）的位移字段定位失败：包对象类 "
          + (handle == null ? "未知" : handle.getClass().getName())
          + " 上找不到「entityId + 位移」字段（已按 ≤26.2 的三个 short 与 26.3 的单个 VecDelta 两种形态尝试）；"
          + "零位移取消已按放行处理。原因：" + MovePacketFields.describeLastFailure()
          + "。请连同 /mxnet dump 一并反馈。");
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "零位移判定失败，已按原包放行", throwable);
    }
  }
}