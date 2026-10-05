package net.mikumc.mikuxraynet.bandwidth;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 实体射线剔除：被不透明方块完全遮挡的实体对玩家隐藏，节省下行的实体跟踪带宽。
 *
 * <p>线程模型（严格「Bukkit API 只在自己线程调用」）：
 * <ol>
 *   <li>主线程/区域线程：从 {@link PlayerTrackEntityEvent} 或周期任务里抓取「眼睛坐标 + 包围盒顶点」；</li>
 *   <li>同一线程：用 Paper 原生射线（{@code World#rayTraceBlocks}）逐顶点判定是否被遮挡；</li>
 *   <li>同一线程：执行 {@link Player#hideEntity} / {@link Player#showEntity}。</li>
 * </ol>
 * 原生射线是 Bukkit API，必须在实体所属线程调用，因此不再把体素路径交给工作线程预计算
 * （旧实现：worker 算路径 → 回主线程读方块），也没有了 {@code int[]} 路径分配与 worker 往返。
 *
 * <p>安全策略：强制可见距离内的实体一律可见；只有当包围盒的所有可见顶点射线都被遮挡时才隐藏
 * （宁可少隐藏，也不隐藏可见实体）；玩家自身、其它玩家与烟花不做剔除。
 *
 * <p><b>周期复检的两条通道</b>（修复「先可见、之后才被挡住」实体永不隐藏的缺陷）：
 * <ol>
 *   <li><b>通道①·已隐藏实体</b>：每周期全部复检，但只做<b>近距离恢复</b>——只有当实体重新进入强制
 *       可见距离时才 {@code showIfHidden}；超出该距离一律不打射线（成本有界，见 {@link RecheckMode}）。</li>
 *   <li><b>通道②·其余追踪实体</b>：按<b>轮转分片</b>每周期只复检 {@code entity-culling.recheck-budget}
 *       个，游标推进，故 {@code ceil(追踪数 / budget)} 个周期内必然覆盖全部追踪实体。该通道允许对超出
 *       强制可见距离的实体<b>打射线并隐藏</b>——这正是「入场时可见、之后才被墙/地形挡住」的实体能被
 *       收敛到隐藏的<b>唯一</b>路径；每条射线的成本已由 {@code recheck-budget} 封顶。</li>
 * </ol>
 * 两条通道都复用同一套评估链路（实体所属线程做原生射线并 hide/show），不另写一套。
 * <b>距离闸门</b>：任何通道内、处于强制可见距离内的实体一律可见（{@code showIfHidden}），绝不隐藏——
 * 这是既有的安全策略。入场那一刻的首评（{@code onTrack}）不受距离闸门限制，故「远距离实体照常被剔除」
 * 这一行为不变。
 */
public final class EntityCuller implements Listener {

  /** 单个小体积实体（如物品、投掷物）只取中心顶点即可。 */
  private static final double SMALL_BOX = 0.25D;

  /**
   * 单条遮挡射线的最大长度（格）。射线只用于判断「眼睛与实体之间是否有遮挡方块」；实体一旦超出该长度，
   * 说明它已远超常见实体追踪距离（通常 48~128 格），此时把射线截断只会退回 fail-open（判为通畅、保持可见），
   * 绝不误藏。加上限是为了避免「玩家走远、实体仍留在常加载区块」时每条射线都射向极远处带来的无谓开销。
   */
  private static final double MAX_RAY_LENGTH = 128.0D;

  private final Plugin plugin;
  private final BandwidthConfig.EntityCulling config;
  private final ThrottleStats stats;
  /** 实体归属判定（Folia 跨区域实体不得触碰；见 {@link EntityOwnership}）。 */
  private final EntityOwnership ownership;
  /** 直通判定（只读 BypassRegistry 的登录期快照；见 {@link BypassLookup}）。 */
  private final BypassLookup bypass;
  /** 遮挡判定（生产实现为本类的 Paper 原生射线；离线单测可注入确定性结果，见 {@link Occlusion}）。 */
  private final Occlusion occlusion;
  private final double forceVisibleSquared;
  private final AtomicInteger errorCounter = new AtomicInteger();

  /** 玩家 → 已被隐藏的实体（entityId → Entity）。 */
  private final ConcurrentHashMap<UUID, Map<Integer, Entity>> hidden = new ConcurrentHashMap<>();
  /** 玩家 → 该玩家当前追踪的实体轮转队列（周期复检切分片用，见 {@link TrackedRotation}）。 */
  private final ConcurrentHashMap<UUID, TrackedRotation> rotations = new ConcurrentHashMap<>();

  /** 每个在线玩家的周期复检任务（Paper 与 Folia 同一套实体调度器；玩家退出即随实体退役失效）。 */
  private final ConcurrentHashMap<UUID, ScheduledTask> recheckTasks = new ConcurrentHashMap<>();
  /** 「停用时恢复失败」的中文 WARN 一次性闸门。 */
  private final AtomicBoolean restoreFailureNoticed = new AtomicBoolean();
  /** 「停用时存在跨区域被隐藏实体（Folia）无法同步恢复」的中文 WARN 一次性闸门。 */
  private final AtomicBoolean crossRegionRestoreNoticed = new AtomicBoolean();
  /**
   * 停用中标记：{@code stop()} 首行置位，用于闭合停用期并发——正在跑的复检若在此之后才走到
   * {@code hide()}，会被拒绝，绝不把实体重新藏回（否则刚刚 {@code restoreAll()} 恢复过的实体会再次不可见）。
   */
  private volatile boolean stopping;

  public EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats) {
    this(plugin, config, stats, EntityCuller::ownedByCurrentRegion);
  }

  /** 完整构造（追加实体归属判定；离线单测可注入假实现，见 {@link EntityOwnership}）。 */
  EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats,
      EntityOwnership ownership) {
    this(plugin, config, stats, ownership, BypassRegistry::isBypassedNow);
  }

  /**
   * 完整构造（追加实体归属判定与直通判定；离线单测可对两者注入假实现）。
   *
   * <p><b>为什么直通判定默认走 {@link BypassRegistry#isBypassedNow} 这份静态共享名单，而不是把
   * {@link BypassRegistry} 穿透装配链</b>：带宽侧其余模块（{@code EntityPacketFilter} /
   * {@code BlockChangeMerger} / {@code AfkTracker}）已统一读这份共享快照，本类保持一致；直通名单由
   * {@code MikuXrayNet} 持有、只在登录/启用时做一次快照，带宽模块只做一次纯内存读，为此再扩大
   * {@code ThrottlePipeline} → {@code MikuXrayNet} 的构造签名不划算。本构造的注入点仅为离线单测提供
   * （同 {@link EntityOwnership} 的做法）。
   */
  EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats,
      EntityOwnership ownership, BypassLookup bypass) {
    this(plugin, config, stats, ownership, bypass, null);
  }

  /**
   * 完整构造（追加遮挡判定注入；离线单测可对归属 / 直通 / 遮挡三者全部注入假实现）。
   *
   * <p>{@code occlusion} 为 {@code null} 时回落生产实现 {@link #isFullyOccluded}（Paper 原生射线）；
   * 提供该注入点是因为离线环境连 {@code Material} 都初始化不了（{@code org.bukkit.Registry} 不可用），
   * 真实的 {@code World#rayTraceBlocks} 判定结果无从成立，而「复检能否真正隐藏实体」这条链路又必须被
   * 端到端回归（见 {@code EntityCullerTest}）。
   */
  EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats,
      EntityOwnership ownership, BypassLookup bypass, Occlusion occlusion) {
    this.plugin = plugin;
    this.config = config;
    this.stats = stats;
    this.ownership = ownership;
    this.bypass = bypass == null ? BypassRegistry::isBypassedNow : bypass;
    this.occlusion = occlusion == null ? this::isFullyOccluded : occlusion;
    this.forceVisibleSquared = config.forceVisibleDistance() * config.forceVisibleDistance();
  }

  /**
   * 实体归属判定：该实体当前是否由本线程所属区域拥有（Paper 上恒为 true）。
   *
   * <p><b>为什么必须在「碰之前」先问</b>：Folia 上读取非本区域实体的状态会走
   * {@code TickThread.ensureTickThread} 校验——它<b>先打一条 ERROR 日志再抛异常</b>，所以 try/catch
   * 兜住并不能避免刷屏（真机 Lophine 26.3 上表现为每周期一次 ERROR + 任务异常）。
   * 周期复检跑在玩家所在区域线程，而玩家走远/传送后，其已隐藏列表里的实体可能已在别的区域，
   * 甚至已被服务端 DISCARDED。
   */
  @FunctionalInterface
  public interface EntityOwnership {
    boolean isOwnedByCurrentRegion(Entity entity);
  }

  /** 生产实现：{@link Bukkit#isOwnedByCurrentRegion(Entity)}（内部用 getHandleRaw + TickThread 判定，不写日志）。 */
  private static boolean ownedByCurrentRegion(Entity entity) {
    try {
      return Bukkit.isOwnedByCurrentRegion(entity);
    } catch (Throwable throwable) {
      // 判定不可用（离线单测无服务端实例等）：按「属于本区域」处理，退回本改动之前的行为
      return true;
    }
  }

  /**
   * 直通判定查询：读「登录期快照」的线程安全只读入口，生产实现为 {@link BypassRegistry#isBypassedNow}。
   *
   * <p><b>语义（与全局一致，请勿当 bug「修」回去）</b>：直通权限只在玩家<b>进入服务器</b>时判定一次，
   * 运行期由权限插件授予 / 收回 {@code mikuxraynet.bypass} 都<b>不会即时生效</b>，必须重新进入服务器
   * （退出→重登）才按新权限重算（详见 {@link BypassRegistry}）。因此「复检恢复可见」只对
   * <b>登录时已带权限</b>的玩家生效，这是既定语义而非缺陷。
   *
   * <p><b>线程纪律</b>：读侧只做一次并发集合查询，是纯内存读、不触碰任何 Bukkit API，因此可安全用于
   * Folia 区域线程上的周期复检；本类<b>不做任何周期性权限查询</b>。
   *
   * <p><b>失败语义 fail-open</b>：名单未装配 / 已停用时 {@link BypassRegistry#isBypassedNow} 返回
   * false（按「无直通」处理，与既有兜底语义一致）。
   */
  @FunctionalInterface
  interface BypassLookup {
    boolean isBypassed(UUID playerId);
  }

  /**
   * 遮挡判定：该实体是否被完全遮挡（所有可见顶点射线都不通畅）。
   *
   * <p>生产实现为本类的 Paper 原生射线 {@link #isFullyOccluded}；离线单测注入确定性结果，
   * 以便端到端验证「复检能否真正隐藏实体」这条链路（离线无法初始化 {@code Material}）。
   */
  @FunctionalInterface
  interface Occlusion {
    boolean isFullyOccluded(Player player, Entity entity);
  }

  /**
   * 周期复检通道语义：决定「实体超出强制可见距离时」还能不能打射线。
   *
   * <p><b>为什么必须显式区分</b>：本轮修复前用单一的 {@code fromRecheck} 布尔同时承担了「计数归属」与
   * 「超出强制可见距离后不再打射线」两件事，导致<b>两条</b>复检通道都在到达 {@code evaluate} 前提前返回，
   * 实体一旦「入场时可见、之后才被挡住」就再也不会被隐藏，{@code recheckHidden} 恒为 0（与类注释矛盾）。
   */
  private enum RecheckMode {
    /** 入场首评（{@code onTrack}）：不受距离闸门限制，远距离实体照常打射线并可能隐藏。 */
    INITIAL,
    /**
     * 通道①·已隐藏实体复检：只做「强制可见距离内的恢复」，超出距离一律不打射线。
     * 这些账本条目可能随玩家走远而长期滞留，若每周期都打射线则成本随会话无界增长。
     */
    RESTORE_ONLY,
    /**
     * 通道②·轮转分片复检：数量已由 {@code entity-culling.recheck-budget} 封顶，故允许对超出强制可见
     * 距离的实体打射线并隐藏——这是「先可见、之后才被挡住」能被收敛到隐藏的关键；成本上界＝每周期预算次。
     */
    ROTATION
  }

  /** 注册事件监听与周期复检任务。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    long interval = Math.max(1, config.updateIntervalTicks());
    // 与 onJoin 保持一致：射线判定关闭时不登记任何周期复检任务——recheck 首行即因 raycast=false 返回，
    // 若仍为每个在线玩家挂一条任务，只是每周期空跑的纯调度开销。
    if (config.raycast()) {
      // 统一为「每个玩家一条实体调度任务」：Paper 上落在主线程、Folia 上落在区域线程（同一套 API，无需分支）
      for (Player player : Bukkit.getOnlinePlayers()) {
        scheduleRecheck(player, interval);
      }
    }
    plugin.getLogger().info("带宽模块已启用：实体射线剔除（强制可见距离 " + config.forceVisibleDistance()
        + " 格，Paper 原生射线，每实体至多 " + config.raySamples() + " 个包围盒顶点）");
  }

  /** 注销监听、恢复全部被隐藏实体。 */
  public void stop() {
    // 先取消在途任务、再同步恢复：Bukkit 的 setEnabled(false) 先置 isEnabled=false 再调 onDisable，
    // 因此停用链路里的 EntityScheduler 一律抛 IllegalPluginAccessException（旧实现被 Schedulers 静默吞掉，
    // 导致恢复完全无声）。这里改为「先 cancel 在途任务闭合并发 → 检测到已停用则同步恢复」。
    stopping = true;
    cancelRecheckTasks();
    HandlerList.unregisterAll(this);
    restoreAll();
    rotations.clear();
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    if (!config.raycast()) {
      return;
    }
    scheduleRecheck(event.getPlayer(), Math.max(1, config.updateIntervalTicks()));
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    cancelRecheckTask(player.getUniqueId());
    // 退出无需恢复：hideEntity 是「每玩家」的可见性状态，玩家一断开连接，服务端即随实体释放该状态，
    // 再逐个 showEntity 纯属徒劳（还要在实体所属线程逐个执行）。这里只清账本，语义与「离线玩家
    // 判定只清账本、不产生恢复计数」一致（见 evaluate 的失效/离线分支）。
    hidden.remove(player.getUniqueId());
    rotations.remove(player.getUniqueId());
  }

  /**
   * 恢复某玩家当前被隐藏的全部实体并清空其账本（复检发现该玩家直通时使用）。
   *
   * <p>Folia：这些实体可能已随玩家走远/传送落在别的区域，读它们的状态会触发 TickThread 校验
   * （先打 ERROR 再抛），因此先问归属，跨区域者直接跳过（随后由其所在区域自然退役）。
   */
  private void restorePlayer(Player player) {
    for (Entity entity : drainPlayer(player.getUniqueId()).values()) {
      if (!owns(entity)) {
        continue;
      }
      show(player, entity);
    }
  }

  /**
   * 当前处于隐藏中的实体总数（诊断用实时值）。
   *
   * <p><b>为什么需要它</b>：{@code entitiesHidden} 与 {@code entitiesShown} 是累计计数，两者天然不等——
   * 实体死亡 / 区块卸载 / 离场会被服务端自然回收，此时**不需要**也不应该再发 showEntity；
   * 只有「当前隐藏中」这个实时值才能让两个累计数自证（累计隐藏 = 累计恢复 + 当前隐藏中 + 自然回收）。
   */
  public int hiddenCount() {
    int total = 0;
    for (Map<Integer, Entity> map : hidden.values()) {
      total += map.size();
    }
    return total;
  }

  /** 取出并从登记表移除某玩家的全部隐藏实体（玩家退出 / 插件停用时的全量恢复入口）；无记录返回空表。 */
  Map<Integer, Entity> drainPlayer(UUID playerId) {
    Map<Integer, Entity> map = hidden.get(playerId);
    if (map == null) {
      return Map.of();
    }
    Map<Integer, Entity> drained = new LinkedHashMap<>(map);
    hidden.remove(playerId, map);
    return drained;
  }

  /** 为单个玩家登记周期复检任务；同一玩家重复登记时以最新任务为准（旧任务取消）。 */
  private void scheduleRecheck(Player player, long interval) {
    ScheduledTask task = Schedulers.repeatOnEntity(plugin, player, interval, interval,
        () -> recheck(player));
    if (task == null) {
      return;
    }
    ScheduledTask previous = recheckTasks.put(player.getUniqueId(), task);
    if (previous != null) {
      previous.cancel();
    }
  }

  private void cancelRecheckTask(UUID playerId) {
    ScheduledTask task = recheckTasks.remove(playerId);
    if (task != null) {
      task.cancel();
    }
  }

  private void cancelRecheckTasks() {
    for (ScheduledTask task : recheckTasks.values()) {
      task.cancel();
    }
    recheckTasks.clear();
  }

  @EventHandler(ignoreCancelled = true)
  public void onTrack(PlayerTrackEntityEvent event) {
    if (!config.raycast()) {
      return;
    }
    Player player = event.getPlayer();
    // 直通判定只读登录期快照（纯内存、线程安全），语义与复检路径一致：
    // 运行期授予 / 撤销权限都需重进服务器才生效（见 BypassLookup）。
    if (bypass.isBypassed(player.getUniqueId())) {
      return;
    }
    Entity entity = event.getEntity();
    // 玩家可见性交给原版与其它插件，避免干扰 PvP；烟花被隐藏会破坏鞘翅飞行体验
    if (entity instanceof Player || entity instanceof Firework) {
      return;
    }
    // 跨区域实体不登记也不评估：读它们的任何状态都会触发 Folia 线程校验（先打 ERROR 再抛异常）
    if (!owns(entity)) {
      return;
    }
    if (entity.getEntityId() == player.getEntityId()) {
      return;
    }
    // 登记进轮转队列：这是「入场那一刻评估一次」之外的兜底，保证之后出现的遮挡也能被发现
    rotation(player.getUniqueId()).add(entity);
    submitRaycast(player, entity, RecheckMode.INITIAL);
  }

  /** 实体离开追踪范围时从轮转队列摘除（否则队列会无界增长，并浪费复检预算）。 */
  @EventHandler(ignoreCancelled = true)
  public void onUntrack(PlayerUntrackEntityEvent event) {
    if (!config.raycast()) {
      return;
    }
    TrackedRotation rotation = rotations.get(event.getPlayer().getUniqueId());
    if (rotation != null) {
      // 按实例摘除：实体此时可能已经离开本区域，读它的 entityId 会触发 Folia 线程校验并刷 ERROR
      rotation.remove(event.getEntity());
    }
  }

  private TrackedRotation rotation(UUID playerId) {
    return rotations.computeIfAbsent(playerId, uuid -> new TrackedRotation());
  }

  /**
   * 周期复检：① 已隐藏实体每周期全部复检（尽快恢复可见，行为不退化）；
   * ② 其余追踪实体按轮转分片复检，每周期不超过 {@code recheck-budget} 个，
   * 因此在 {@code ceil(追踪数 / budget)} 个周期内覆盖全部追踪实体。
   *
   * <p>包可见：供离线单测直接驱动「轮转分片 + 预算」账本行为（真实链路依赖 Bukkit 调度，离线不可用）。
   */
  void recheck(Player player) {
    if (stopping || !config.raycast() || !player.isOnline()) {
      return;
    }
    UUID playerId = player.getUniqueId();

    // 直通玩家恢复其全部已隐藏实体，且不再提交任何射线复检。判定只读登录期快照（纯内存、线程安全），
    // 不再调用任何 Bukkit 权限 API —— 因此本类周期路径上不存在任何权限查询。
    // 语义一致性：运行期改权限需重进服务器才生效，故「复检恢复可见」只对登录时已带权限的玩家生效
    // （见 BypassLookup，这是既定语义，不是缺陷）。
    if (bypass.isBypassed(playerId)) {
      restorePlayer(player);
      return;
    }

    // ① 已隐藏实体：每周期全量复检（既有行为）。
    //    用「加入时捕获的 entityId + 归属判定」遍历：玩家走远/传送后这些实体可能已在别的区域，
    //    读它们的任何状态都会触发 Folia 线程校验（先打 ERROR 再抛异常），因此先问能不能碰，不能则整轮跳过。
    //    本通道只做「强制可见距离内的恢复」（RESTORE_ONLY）：远距离不打射线，成本有界。
    Map<Integer, Entity> hiddenMap = hidden.get(playerId);
    if (hiddenMap != null && !hiddenMap.isEmpty()) {
      List<Entity> stale = null;
      // hiddenMap 是并发 Map，遍历中 submitRaycast 可能增删；弱一致迭代不会抛异常。
      for (Entity entity : hiddenMap.values()) {
        if (!owns(entity)) {
          continue;
        }
        if (!entity.isValid()) {
          if (stale == null) {
            stale = new ArrayList<>(4);
          }
          stale.add(entity);
          continue;
        }
        stats.recheckSubmitted.increment();
        submitRaycast(player, entity, RecheckMode.RESTORE_ONLY);
      }
      if (stale != null) {
        for (Entity entity : stale) {
          // 仅摘除「同一实例」：entityId 会被复用，按 id 删会把后来占用该 id 的新实体误删
          hiddenMap.remove(entity.getEntityId(), entity);
        }
      }
    }

    // ② 其余追踪实体：轮转分片，每周期只取一小批（已隐藏者由 ① 负责，这里跳过）。
    //    本通道允许打射线隐藏（ROTATION）：这是「先可见、之后才被挡住」能被收敛到隐藏的唯一路径，
    //    成本由 recheck-budget 封顶（每周期至多少量射线）。
    TrackedRotation rotation = rotations.get(playerId);
    if (rotation == null) {
      return;
    }
    Set<Integer> hiddenIds = hiddenMap == null ? Set.of() : hiddenMap.keySet();
    for (Entity entity : rotation.nextBatch(config.recheckBudget(), hiddenIds)) {
      if (!owns(entity)) {
        continue;
      }
      if (!entity.isValid()) {
        rotation.remove(entity.getEntityId());
        continue;
      }
      stats.recheckSubmitted.increment();
      submitRaycast(player, entity, RecheckMode.ROTATION);
    }
  }

  /**
   * 在实体所属线程直接评估：距离闸门 → 原生射线判定 → hide/show。
   *
   * <p>不再有 worker 往返与 {@code int[]} 路径分配：{@code World#rayTraceBlocks} 是 Bukkit API，
   * 必须在实体所属线程调用（事件与周期复检本就落在该线程上）。
   *
   * <p>{@code mode} 决定「实体超出强制可见距离时」是否打射线（见 {@link RecheckMode}）：
   * <ul>
   *   <li>{@link RecheckMode#INITIAL}（入场首评）与 {@link RecheckMode#ROTATION}（轮转分片复检）：
   *       允许对远距离实体打射线并隐藏，因此 {@code evaluate} 里的 hide 分支可达——这正是复检能够
   *       真正收敛到隐藏的入口；</li>
   *   <li>{@link RecheckMode#RESTORE_ONLY}（已隐藏实体复检）：超出距离一律不打射线，只等实体重新回到
   *       强制可见距离内再恢复，成本有界。</li>
   * </ul>
   * 无论哪条通道，处于强制可见距离内的实体一律 {@code showIfHidden}（安全策略：绝不隐藏近处实体）。
   */
  private void submitRaycast(Player player, Entity entity, RecheckMode mode) {
    // 烟花被隐藏会破坏鞘翅飞行体验，直接跳过
    if (entity instanceof Firework) {
      return;
    }
    try {
      // 强制可见距离内一律可见（安全策略）：实体只要在近处，任何通道都不得隐藏它，最多恢复显示。
      // 这里用坐标 getter 手算距离（不再 new 两个 Location）：既省下每次评估的固定分配，
      // 也避免 Location#distanceSquared 在「实体未加载 / 两侧世界不一致」时抛异常——失败方向一致
      // （判不出距离时宁可当作「近」→ 只 show 不 hide，绝不误藏）。fromRecheck 仅用于计数归属。
      if (withinForceVisible(player, entity)) {
        evaluate(player, entity, false, mode != RecheckMode.INITIAL);
        return;
      }
      // 超出强制可见距离：
      //  ① RESTORE_ONLY：不打射线（远距离账本条目可能长期滞留，每周期打射线会让成本随会话无界增长）。
      //     账本条目保留——Paper 的 hideEntity 状态跨 untrack 仍生效，账本是「重新进入强制可见距离时
      //     该不该 showEntity」的唯一依据；实体一旦回到近处，上面的 showIfHidden 分支即恢复它。
      //  ② ROTATION：允许打射线并隐藏。数量已由 recheck-budget 封顶，故成本有界（每周期至多预算次射线）。
      //  ③ INITIAL：入场首评不受距离闸门限制，远距离实体照常剔除（既有行为不变）。
      if (mode == RecheckMode.RESTORE_ONLY) {
        return;
      }
      evaluate(player, entity, occlusion.isFullyOccluded(player, entity), mode != RecheckMode.INITIAL);
    } catch (Throwable throwable) {
      // 任意失败都按「保持可见」处理（fail-open：绝不误藏实体）
      logThrottled(throwable);
    }
  }

  /**
   * 玩家与实体的欧氏距离是否在强制可见距离内（零分配：直接用坐标 getter，不 new {@link Location}）。
   *
   * <p>刻意不用 {@code Location#distanceSquared}：那会为玩家与实体各 new 一个 {@code Location}，
   * 是每次评估的一笔固定分配，且它要求两侧都带世界，实体未加载时会直接抛异常。坐标 getter 在任何
   * 情况下都可用，失败方向也一致（判不出距离时按「近」处理 → 只 showIfHidden，绝不误藏）。
   * 生产环境里被追踪的实体必与玩家同世界（追踪事件只对同世界实体触发），故无需再做世界比较。
   */
  private boolean withinForceVisible(Player player, Entity entity) {
    double dx = player.getX() - entity.getX();
    double dy = player.getY() - entity.getY();
    double dz = player.getZ() - entity.getZ();
    return dx * dx + dy * dy + dz * dz <= forceVisibleSquared;
  }

  /**
   * 实体所属线程：用 Paper 原生射线判定实体是否被完全遮挡。
   *
   * <p>把包围盒「朝向玩家一侧」的可见顶点逐一射向玩家眼睛，任一条通畅即判「未被完全遮挡」；
 * 可见顶点由 {@code visibleVertices} 给出（包围盒至多 7 个），再由 {@code entity-culling.ray-samples}
   * （已钳制 1..7，上限即 {@link BandwidthConfig#MAX_RAY_SAMPLES}）截断实际尝试的顶点数。
   *
   * <p><b>失败语义 fail-open</b>：任何异常都返回 {@code false}（不遮挡）——绝不误藏实体。
   *
   * @return true 表示所有候选顶点的射线都被遮挡（可隐藏）
   */
  boolean isFullyOccluded(Player player, Entity entity) {
    try {
      Location eye = player.getEyeLocation();
      World world = entity.getWorld();
      BoundingBox box = entity.getBoundingBox();
      double[][] vertices = visibleVertices(eye.getX(), eye.getY(), eye.getZ(),
          box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
      int limit = Math.min(Math.max(1, config.raySamples()), vertices.length);
      // 复用同一枚 Vector 承载每个采样点的射线方向：方向只被 rayTraceBlocks 读取、不被持有，
      // 且本轮评估始终运行在单一实体所属线程上，反复改写其分量是安全的；省下原先「每个采样点
      // new 一个 Vector + normalize() 再 new 一个」的分配。
      Vector direction = new Vector();
      for (int i = 0; i < limit; i++) {
        if (isClear(world, eye, vertices[i][0], vertices[i][1], vertices[i][2], direction)) {
          return false;
        }
      }
      return true;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
  }

  /**
   * 从眼睛射向该世界坐标点：命中「可遮挡方块」即算被挡；未命中、或命中的是非遮挡方块
   * （玻璃/台阶/植物等，玩家仍能看见其后的实体）算通畅。
   *
   * <p>{@code FluidCollisionMode.NEVER}：流体不参与射线（水里的实体照常可见）。
   * 命中方块的遮挡判定用 {@code Material#isOccluding()}，不新建 BlockData 对象。
   */
  private static boolean isClear(World world, Location eye, double x, double y, double z,
      Vector direction) {
    double dx = x - eye.getX();
    double dy = y - eye.getY();
    double dz = z - eye.getZ();
    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (distance < 1.0E-6D) {
      return true;
    }
    // 手写归一化并写入调用方复用的 direction（等价于 new Vector(dx,dy,dz).normalize()，但不新分配对象）
    double inverse = 1.0D / distance;
    direction.setX(dx * inverse);
    direction.setY(dy * inverse);
    direction.setZ(dz * inverse);
    // 射线长度封顶：实体在追踪范围边缘时 eye→顶点可能达上百格，截断到 MAX_RAY_LENGTH；
    // 截断只会让更远的遮挡方块打不到而返回「通畅」（fail-open 保持可见），绝不改变「近处命中的遮挡判定」。
    RayTraceResult hit = world.rayTraceBlocks(eye, direction, Math.min(distance, MAX_RAY_LENGTH),
        FluidCollisionMode.NEVER, true);
    if (hit == null || hit.getHitBlock() == null) {
      return true;
    }
    Block block = hit.getHitBlock();
    return !block.getType().isOccluding();
  }

  /**
   * 取实体包围盒上「朝向玩家一侧」的可见顶点，用于多射线判定（任一射线不被遮挡即视为可见）。
   *
   * <p>原生射线改造后本方法仍需要：一次判定最多尝试 {@code ray-samples} 个顶点。
   *
   * @return 顶点数组，每项为 (x, y, z)
   */
  static double[][] visibleVertices(double eyeX, double eyeY, double eyeZ,
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    double sizeX = maxX - minX;
    double sizeY = maxY - minY;
    double sizeZ = maxZ - minZ;
    if (sizeX <= SMALL_BOX && sizeY <= SMALL_BOX && sizeZ <= SMALL_BOX) {
      return new double[][] {{(minX + maxX) / 2.0D, (minY + maxY) / 2.0D, (minZ + maxZ) / 2.0D}};
    }

    boolean nearestXIsMin = Math.abs(eyeX - minX) < Math.abs(eyeX - maxX);
    boolean nearestYIsMin = Math.abs(eyeY - minY) < Math.abs(eyeY - maxY);
    boolean nearestZIsMin = Math.abs(eyeZ - minZ) < Math.abs(eyeZ - maxZ);

    double nearX = nearestXIsMin ? minX : maxX;
    double nearY = nearestYIsMin ? minY : maxY;
    double nearZ = nearestZIsMin ? minZ : maxZ;
    double farX = nearestXIsMin ? maxX : minX;
    double farY = nearestYIsMin ? maxY : minY;
    double farZ = nearestZIsMin ? maxZ : minZ;

    return new double[][] {
        {nearX, nearY, nearZ},
        {farX, nearY, nearZ},
        {nearX, farY, nearZ},
        {nearX, nearY, farZ},
        {farX, farY, nearZ},
        {farX, nearY, farZ},
        {nearX, farY, farZ}
    };
  }

  /**
   * 主线程/区域线程：按遮挡结论执行隐藏/恢复（包可见：供离线单测直接驱动账本行为）。
   *
   * <p>测试专用豁免：生产路径一律传入 {@code fromRecheck}（调 4 参重载），本重载当前仅单测在用，
   * 保留以免破坏测试。
   */
  void evaluate(Player player, Entity entity, boolean allBlocked) {
    evaluate(player, entity, allBlocked, false);
  }

  /**
   * 评估入口（包可见：供离线单测直接驱动「复检通道」的计数归属）。
   *
   * @param allBlocked  是否所有可见顶点都被遮挡（true = 可隐藏）
   * @param fromRecheck 是否来自周期复检：仅用于把计数归入「复检致隐藏 / 复检致恢复」，不影响判定逻辑
   */
  void evaluate(Player player, Entity entity, boolean allBlocked, boolean fromRecheck) {
    if (!entity.isValid() || !player.isOnline()) {
      removeLedgerEntry(player.getUniqueId(), entity);
      return;
    }

    // 载具/乘客关系：整条骑乘链必须一律可见。隐藏「载有乘客的载具」会让未被隐藏的乘客（玩家永不被隐藏）
    // 看起来悬空骑行；隐藏「本身是乘客的实体」会让载具看起来无人。因此只要该实体与任何乘客/载具存在
    // 关系，就把 allBlocked 视为无效 → 走 showIfHidden 保持/恢复可见（fail-open 的确定性规则）。
    if (allBlocked && !hasVehicleRelation(entity)) {
      if (hide(player, entity) && fromRecheck) {
        stats.recheckHidden.increment();
      }
    } else if (showIfHidden(player, entity) && fromRecheck) {
      stats.recheckShown.increment();
    }
  }

  /**
   * 该实体是否与载具/乘客存在关系（自身载有乘客，或本身是乘客 / 骑在载具上）。
   *
   * <p>读取失败时按「存在关系」处理（不剔除）：剔除是可选优化，fail-open 保持可见最安全。
   */
  private static boolean hasVehicleRelation(Entity entity) {
    try {
      return !entity.getPassengers().isEmpty() || entity.getVehicle() != null;
    } catch (Throwable throwable) {
      return true;
    }
  }

  /**
   * 从账本摘除某实体的条目（仅在条目正是<b>同一实例</b>时才摘）。
   *
   * <p>entityId 会被服务端复用：若只按 id 摘除，可能误删「后来占用该 id 的另一个实体」的条目。
   */
  private void removeLedgerEntry(UUID playerId, Entity entity) {
    Map<Integer, Entity> map = hidden.get(playerId);
    if (map != null) {
      map.remove(entity.getEntityId(), entity);
    }
  }

  /** @return 是否真的新登记并执行了 hideEntity（已隐藏 / 失败时为 false）。 */
  private boolean hide(Player player, Entity entity) {
    if (stopping) {
      // 停用已开始：绝不再新登记隐藏（否则会把刚被 restoreAll 恢复的实体重新藏回）
      return false;
    }
    Map<Integer, Entity> map = hidden.computeIfAbsent(player.getUniqueId(),
        uuid -> new ConcurrentHashMap<>());
    // 仅当账本里该 id 正是「同一实例」时才算已隐藏。若该 id 对应的是一枚已消失的旧实例（entityId 被复用），
    // 则覆盖登记新实体——否则复用该 id 的新实体会永远无法被隐藏（账本被旧条目挡住）。
    if (map.get(entity.getEntityId()) == entity) {
      return false;
    }
    try {
      player.hideEntity(plugin, entity);
      map.put(entity.getEntityId(), entity);
      stats.entitiesHidden.increment();
      return true;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
  }

  /** @return 是否真的从账本摘除并执行了 showEntity（未隐藏 / 实体已失效时为 false）。 */
  private boolean showIfHidden(Player player, Entity entity) {
    Map<Integer, Entity> map = hidden.get(player.getUniqueId());
    if (map == null) {
      return false;
    }
    // 只认「同一实例」的账本条目：若按 id 摘除，会把后来复用该 id 的另一个实体的条目一并删掉，
    // 从而对从未被隐藏的新实体误发 showEntity，且该新实体再也无法被隐藏（账本被误清）。
    if (map.get(entity.getEntityId()) != entity) {
      return false;
    }
    map.remove(entity.getEntityId(), entity);
    return show(player, entity);
  }

  /** @return 是否真的下发了 showEntity（实体已失效时为 false）。 */
  private boolean show(Player player, Entity entity) {
    try {
      if (entity.isValid()) {
        player.showEntity(plugin, entity);
        stats.entitiesShown.increment();
        return true;
      }
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
    return false;
  }

  /**
   * 停用时的兜底：把所有被隐藏的实体恢复显示，不留副作用。
   *
   * <p><b>停用路径必须同步恢复</b>：Bukkit 的 {@code setEnabled(false)} 先置 {@code isEnabled=false}
   * 再调 {@code onDisable()}，此时 {@code EntityScheduler} 一律抛 {@link org.bukkit.plugin.IllegalPluginAccessException}
   * （旧实现被 {@link Schedulers} 静默吞掉 → 关服 / reload / 停用后实体对玩家持续不可见，且「恢复失败必 WARN」永不触发）。
   * 因此这里按「插件是否仍启用」分流：仍启用（热重载）时回到玩家所属线程调度；
   * 已停用时改为<b>同步</b>恢复——Paper 主线程恒可同步；Folia 仅当实体属于当前区域（{@code owns}）时才同步，
   * 跨区域者无法在当前线程安全操作，打一次性中文 WARN 提示「需重登才能恢复」。
   */
  private void restoreAll() {
    boolean stillEnabled = plugin.isEnabled();
    for (Player player : Bukkit.getOnlinePlayers()) {
      Map<Integer, Entity> drained = drainPlayer(player.getUniqueId());
      if (drained.isEmpty()) {
        continue;
      }
      for (Entity entity : drained.values()) {
        if (stillEnabled) {
          // 热重载路径：回到玩家所属线程执行（Paper 上即主线程，Folia 上即区域线程）
          Schedulers.onEntity(plugin, player, () -> restoreWithRetry(player, entity, true));
        } else {
          // 停用路径：调度器已不可用（plugin.isEnabled()=false），同步恢复
          restoreNow(player, entity);
        }
      }
    }
    // 离线玩家（停用时已不在线）的登记一并丢弃：它们已随退出被恢复，这里只兜底清账本
    hidden.clear();
  }

  /**
   * 停用路径的<b>同步</b>恢复单个被隐藏实体（不再经调度器）。
   *
   * <p>Paper：主线程拥有全部实体，{@code owns} 恒真，直接 {@code showEntity}。
   * Folia：当前（停用）线程未必拥有该实体所在区域——{@code owns} 为 false 时<b>绝不</b>触碰该实体状态
   * （否则会触发 TickThread 校验先打 ERROR 再抛），只记一次性中文 WARN，说明该实体需玩家重登才能恢复。
   */
  private void restoreNow(Player player, Entity entity) {
    if (!owns(entity)) {
      warnCrossRegionRestore();
      return;
    }
    boolean restored;
    try {
      restored = show(player, entity) || !entity.isValid();
    } catch (Throwable throwable) {
      restored = false;
    }
    if (!restored && restoreFailureNoticed.compareAndSet(false, true)) {
      plugin.getLogger().warning("插件停用时同步恢复被隐藏实体失败："
          + "个别实体可能需玩家重新登录后才可见；不影响其它功能。");
    }
  }

  /** Folia 停用时跨区域实体无法同步恢复的一次性中文 WARN（进程级闸门，绝不刷屏）。 */
  private void warnCrossRegionRestore() {
    if (crossRegionRestoreNoticed.compareAndSet(false, true)) {
      plugin.getLogger().warning("插件停用时检测到跨区域（Folia）被隐藏实体：当前线程无法安全恢复，"
          + "个别实体可能需玩家重新登录后才可见。此为 Folia 区域化线程的限制，非本插件故障。");
    }
  }

  /**
   * 停用瞬间恢复单个被隐藏实体：失败时做一次重试，再次失败则记一次中文 WARN——
   * 绝不静默吞掉（旧实现下被隐藏实体可能保持隐藏到玩家重登，管理员无从察觉）。
   *
   * <p>注意 {@link #show} 内部已捕获异常并返回 {@code false}，所以这里以「返回值」判断成败；
   * 实体跨区域（{@code owns} 为 false）或已失效时无需恢复，视为成功。
   *
   * @param allowRetry 是否还允许再调度一次重试（只重试一次，避免失败时无限重排）
   */
  private void restoreWithRetry(Player player, Entity entity, boolean allowRetry) {
    boolean restored;
    try {
      restored = !owns(entity) || show(player, entity) || !entity.isValid();
    } catch (Throwable throwable) {
      restored = false;
    }
    if (restored) {
      return;
    }
    if (allowRetry) {
      // 按玩家区域调度一次重试（停用瞬间的实体/世界可能尚未稳定）
      Schedulers.onEntity(plugin, player, () -> restoreWithRetry(player, entity, false));
      return;
    }
    if (restoreFailureNoticed.compareAndSet(false, true)) {
      plugin.getLogger().warning("插件停用时恢复被隐藏实体失败（已重试一次）："
          + "个别实体可能需玩家重新登录后才可见；不影响其它功能。");
    }
  }

  /** 本线程是否可以安全读取该实体的状态；判定本身异常时按「可以」处理（退回改动前行为）。 */
  private boolean owns(Entity entity) {
    try {
      return ownership.isOwnedByCurrentRegion(entity);
    } catch (Throwable throwable) {
      return true;
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "实体剔除判定失败，已按「保持可见」处理", throwable);
    }
  }

  /**
   * 单玩家的「追踪实体轮转队列」：按加入顺序保存该玩家正在追踪的实体，用游标把周期复检切成小分片。
   *
   * <p><b>覆盖保证</b>：每周期只推进 {@code min(budget, size)} 个<b>位置</b>（与是否已隐藏无关），
   * 因此 {@code ceil(size / budget)} 个周期内必然遍历到所有位置——这正是「先可见、之后才被挡住」
   * 的实体能被收敛到隐藏的原因；同时每周期真正提交的复检数不超过 {@code budget}。
   *
   * <p><b>键是加入时捕获的 entityId</b>：实体可能已经离开本区域，届时读 {@code entity.getEntityId()}
   * （走 {@code CraftEntity#getHandle}）会触发 Folia 线程校验并刷 ERROR 日志；加入那一刻它必然可达
   * （追踪事件由本区域发出），因此 id 在那一刻取好，之后只读键、不碰实体。
   *
   * <p>线程模型：事件与周期任务都落在玩家所属线程上；加锁仅为防御两者线程不一致（Folia 区域线程）。
   */
  static final class TrackedRotation {

    /** 队列条目：加入时捕获的 entityId + 实体引用；之后只读 id，绝不读可能已跨区域的实体状态。 */
    private record Tracked(int id, Entity entity) {
    }

    /**
     * 环形数组：按加入顺序保存，游标在 {@code [0, size)} 上推进。
     *
     * <p>为什么用数组 + 游标而不是 {@code LinkedHashMap}：旧实现每周期全量扫描整个队列、再按预算过滤，
     * 每周期开销 O(队列长度)；改为直接按下标取 {@code step} 个位置后降到 O(step)，语义（位置推进、
     * 已隐藏按 id 跳过、有限周期内覆盖全部）完全不变。
     */
    private final List<Tracked> order = new ArrayList<>();
    private int cursor;

    /** 加入一个被追踪实体（按加入时捕获的 entityId 去重）。 */
    synchronized void add(Entity entity) {
      int id = entity.getEntityId();
      for (Tracked tracked : order) {
        if (tracked.id() == id) {
          return;
        }
      }
      order.add(new Tracked(id, entity));
    }

    /** 按实例摘除（不读实体状态，供可能已跨区域的 untrack 事件使用）。 */
    synchronized void remove(Entity entity) {
      for (int index = 0; index < order.size(); index++) {
        if (order.get(index).entity() == entity) {
          order.remove(index);
          if (cursor > index) {
            cursor--;
          }
          return;
        }
      }
    }

    /** 按 id 摘除一个不再被追踪的实体，并修正游标使「下一页」不被跳过。 */
    synchronized void remove(int entityId) {
      for (int index = 0; index < order.size(); index++) {
        if (order.get(index).id() == entityId) {
          order.remove(index);
          if (cursor > index) {
            cursor--;
          }
          return;
        }
      }
    }

    /**
     * 当前轮转队列里的追踪实体数。
     *
     * <p>测试专用豁免：生产路径不读该值（复检只走 {@link #nextBatch(int, Set)}），
     * 本方法当前仅单测在用，保留以免破坏测试。
     */
    synchronized int size() {
      return order.size();
    }

    /**
     * 取出本轮要复检的一小批追踪实体（跳过已隐藏者——它们由「每周期全量复检」通道负责），
     * 并把游标推进一批，保证有限周期内覆盖全部追踪实体。
     *
     * <p>环形数组直接取 {@code step} 个位置：{@code start = cursor % size}，依次取
     * {@code [start, start+step)}（回绕），位置数而非提交数推进，覆盖保证与旧实现一致。
     */
    synchronized List<Entity> nextBatch(int budget, Set<Integer> hiddenIds) {
      int size = order.size();
      if (size == 0) {
        cursor = 0;
        return List.of();
      }
      int step = Math.min(Math.max(1, budget), size);
      int start = Math.floorMod(cursor, size);
      List<Entity> batch = new ArrayList<>(step);
      for (int offset = 0; offset < step; offset++) {
        Tracked tracked = order.get(Math.floorMod(start + offset, size));
        if (!hiddenIds.contains(tracked.id())) {
          batch.add(tracked.entity());
        }
      }
      cursor = (start + step) % size;
      return batch;
    }
  }
}