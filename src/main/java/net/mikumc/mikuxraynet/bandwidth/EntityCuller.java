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
 *   <li>已隐藏实体：<b>每周期全部复检</b>，可见即尽快恢复（既有行为，不退化）；</li>
 *   <li>其余追踪实体：按<b>轮转分片</b>每周期只复检 {@code entity-culling.recheck-budget} 个，
 *       游标推进，故 {@code ceil(追踪数 / budget)} 个周期内必然覆盖全部追踪实体——
 *       这样实体入场时可见、之后才被墙/地形挡住的场景也能被收敛到隐藏。</li>
 * </ol>
 * 两条通道都复用同一套评估链路（实体所属线程做原生射线并 hide/show），不另写一套。
 */
public final class EntityCuller implements Listener {

  /** 单个小体积实体（如物品、投掷物）只取中心顶点即可。 */
  private static final double SMALL_BOX = 0.25D;

  private final Plugin plugin;
  private final BandwidthConfig.EntityCulling config;
  private final ThrottleStats stats;
  /** 实体归属判定（Folia 跨区域实体不得触碰；见 {@link EntityOwnership}）。 */
  private final EntityOwnership ownership;
  /** 直通判定（只读 BypassRegistry 的登录期快照；见 {@link BypassLookup}）。 */
  private final BypassLookup bypass;
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
    this.plugin = plugin;
    this.config = config;
    this.stats = stats;
    this.ownership = ownership;
    this.bypass = bypass == null ? BypassRegistry::isBypassedNow : bypass;
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
    submitRaycast(player, entity, false);
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
    if (!config.raycast() || !player.isOnline()) {
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
    //    用「键（加入时捕获的 entityId）+ 归属判定」遍历：玩家走远/传送后这些实体可能已在别的区域，
    //    读它们的任何状态都会触发 Folia 线程校验（先打 ERROR 再抛异常），因此先问能不能碰，不能则整轮跳过。
    Map<Integer, Entity> hiddenMap = hidden.get(playerId);
    if (hiddenMap != null && !hiddenMap.isEmpty()) {
      // 就地遍历（不再每周期拷贝一份 entrySet），失效键先收集、遍历结束后统一删除。
      // hiddenMap 是并发 Map，遍历中 submitRaycast 可能增删；弱一致迭代不会抛异常。
      List<Integer> stale = null;
      for (Map.Entry<Integer, Entity> entry : hiddenMap.entrySet()) {
        Entity entity = entry.getValue();
        if (!owns(entity)) {
          continue;
        }
        if (!entity.isValid()) {
          if (stale == null) {
            stale = new ArrayList<>(4);
          }
          stale.add(entry.getKey());
          continue;
        }
        stats.recheckSubmitted.increment();
        submitRaycast(player, entity, true);
      }
      if (stale != null) {
        for (Integer key : stale) {
          hiddenMap.remove(key);
        }
      }
    }

    // ② 其余追踪实体：轮转分片，每周期只取一小批（已隐藏者由 ① 负责，这里跳过）
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
      submitRaycast(player, entity, true);
    }
  }

  /**
   * 在实体所属线程直接评估：抓取眼睛坐标与包围盒顶点 → 原生射线判定 → hide/show。
   *
   * <p>不再有 worker 往返与 {@code int[]} 路径分配：{@code World#rayTraceBlocks} 是 Bukkit API，
   * 必须在实体所属线程调用（事件与周期复检本就落在该线程上）。
   *
   * <p>{@code fromRecheck} 仅用于统计归属（不影响判定逻辑）。
   */
  private void submitRaycast(Player player, Entity entity, boolean fromRecheck) {
    // 烟花被隐藏会破坏鞘翅飞行体验，直接跳过
    if (entity instanceof Firework) {
      return;
    }
    try {
      // 强制可见距离内一律不剔除——复检通道同样适用，避免轮转复检把近处实体误藏。
      // 注：这里刻意保留 Location#distanceSquared（而非零分配 getter 版）：它对「任一 Location 无世界」
      // 会抛异常，而离线单测的替身 Location 正是无世界——该异常被下面的 catch 兜住（fail-open），
      // 是既有 EntityCullerTest 复检账本用例的既有前提；改成 getter 版会让那些用例真正走进射线判定，
      // 而离线环境连 Material 都初始化不了（org.bukkit.Registry 不可用），判定结果无从成立。
      if (player.getLocation().distanceSquared(entity.getLocation()) <= forceVisibleSquared) {
        showIfHidden(player, entity);
        return;
      }
      evaluate(player, entity, isFullyOccluded(player, entity), fromRecheck);
    } catch (Throwable throwable) {
      // 任意失败都按「保持可见」处理（fail-open：绝不误藏实体）
      logThrottled(throwable);
    }
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
    RayTraceResult hit = world.rayTraceBlocks(eye, direction, distance,
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
      Map<Integer, Entity> map = hidden.get(player.getUniqueId());
      if (map != null) {
        map.remove(entity.getEntityId());
      }
      return;
    }

    if (allBlocked) {
      if (hide(player, entity) && fromRecheck) {
        stats.recheckHidden.increment();
      }
    } else if (showIfHidden(player, entity) && fromRecheck) {
      stats.recheckShown.increment();
    }
  }

  /** @return 是否真的新登记并执行了 hideEntity（已隐藏 / 失败时为 false）。 */
  private boolean hide(Player player, Entity entity) {
    Map<Integer, Entity> map = hidden.computeIfAbsent(player.getUniqueId(),
        uuid -> new ConcurrentHashMap<>());
    if (map.containsKey(entity.getEntityId())) {
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
    if (map == null || map.remove(entity.getEntityId()) == null) {
      return false;
    }
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

  /** 停用时的兜底：把所有被隐藏的实体恢复显示，不留副作用。 */
  private void restoreAll() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      Map<Integer, Entity> drained = drainPlayer(player.getUniqueId());
      if (drained.isEmpty()) {
        continue;
      }
      for (Entity entity : drained.values()) {
        // 一律回到玩家所属线程执行（Paper 上即主线程，Folia 上即区域线程）
        Schedulers.onEntity(plugin, player, () -> restoreWithRetry(player, entity, true));
      }
    }
    // 离线玩家（停用时已不在线）的登记一并丢弃：它们已随退出被恢复，这里只兜底清账本
    hidden.clear();
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