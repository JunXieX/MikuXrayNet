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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
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
  private final double forceVisibleSquared;
  private final AtomicInteger errorCounter = new AtomicInteger();

  /** 玩家 → 已被隐藏的实体（entityId → Entity）。 */
  private final ConcurrentHashMap<UUID, Map<Integer, Entity>> hidden = new ConcurrentHashMap<>();
  /** 玩家 → 该玩家当前追踪的实体轮转队列（周期复检切分片用，见 {@link TrackedRotation}）。 */
  private final ConcurrentHashMap<UUID, TrackedRotation> rotations = new ConcurrentHashMap<>();
  /** 正在计算的 (玩家, 实体) 组合，避免重复排队。 */
  private final Set<RayKey> inFlight = ConcurrentHashMap.newKeySet();

  /** 在途射线判定的键（玩家 + 实体）：用记录键，省掉每次提交拼接字符串的分配。 */
  private record RayKey(UUID playerId, int entityId) {
  }

  /** 每个在线玩家的周期复检任务（Paper 与 Folia 同一套实体调度器；玩家退出即随实体退役失效）。 */
  private final ConcurrentHashMap<UUID, ScheduledTask> recheckTasks = new ConcurrentHashMap<>();

  public EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats) {
    this(plugin, config, stats, EntityCuller::ownedByCurrentRegion);
  }

  /** 完整构造（追加实体归属判定；离线单测可注入假实现，见 {@link EntityOwnership}）。 */
  EntityCuller(Plugin plugin, BandwidthConfig.EntityCulling config, ThrottleStats stats,
      EntityOwnership ownership) {
    this.plugin = plugin;
    this.config = config;
    this.stats = stats;
    this.ownership = ownership;
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

  /** 注册事件监听与周期复检任务。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    long interval = Math.max(1, config.updateIntervalTicks());
    // 统一为「每个玩家一条实体调度任务」：Paper 上落在主线程、Folia 上落在区域线程（同一套 API，无需分支）
    for (Player player : Bukkit.getOnlinePlayers()) {
      scheduleRecheck(player, interval);
    }
    plugin.getLogger().info("带宽模块已启用：实体射线剔除（强制可见距离 " + config.forceVisibleDistance()
        + " 格，Paper 原生射线，每实体至多 " + config.raySamples() + " 个包围盒顶点）");
  }

  /** 注销监听、恢复全部被隐藏实体。 */
  public void stop() {
    cancelRecheckTasks();
    HandlerList.unregisterAll(this);
    restoreAll();
    inFlight.clear();
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
    for (Entity entity : drainPlayer(player.getUniqueId()).values()) {
      show(player, entity);
    }
    rotations.remove(player.getUniqueId());
    purgeInFlight(player.getUniqueId());
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
    if (player.hasPermission(Constants.BYPASS_PERMISSION)) {
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

    // ① 已隐藏实体：每周期全量复检（既有行为）。
    //    用「键（加入时捕获的 entityId）+ 归属判定」遍历：玩家走远/传送后这些实体可能已在别的区域，
    //    读它们的任何状态都会触发 Folia 线程校验（先打 ERROR 再抛异常），因此先问能不能碰，不能则整轮跳过。
    Map<Integer, Entity> hiddenMap = hidden.get(playerId);
    if (hiddenMap != null && !hiddenMap.isEmpty()) {
      for (Map.Entry<Integer, Entity> entry : new ArrayList<>(hiddenMap.entrySet())) {
        Entity entity = entry.getValue();
        if (!owns(entity)) {
          continue;
        }
        if (!entity.isValid()) {
          hiddenMap.remove(entry.getKey());
          continue;
        }
        stats.recheckSubmitted.increment();
        submitRaycast(player, entity, true);
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
    RayKey key = new RayKey(player.getUniqueId(), entity.getEntityId());
    if (!inFlight.add(key)) {
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
    } finally {
      inFlight.remove(key);
    }
  }

  /**
   * 实体所属线程：用 Paper 原生射线判定实体是否被完全遮挡。
   *
   * <p>把包围盒「朝向玩家一侧」的可见顶点逐一射向玩家眼睛，任一条通畅即判「未被完全遮挡」；
 * 可见顶点由 {@code visibleVertices} 给出（包围盒至多 7 个），再由 {@code entity-culling.ray-samples}
 * （已钳制 1..8）截断实际尝试的顶点数。
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
      for (int i = 0; i < limit; i++) {
        if (isClear(world, eye, vertices[i][0], vertices[i][1], vertices[i][2])) {
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
  private static boolean isClear(World world, Location eye, double x, double y, double z) {
    double dx = x - eye.getX();
    double dy = y - eye.getY();
    double dz = z - eye.getZ();
    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (distance < 1.0E-6D) {
      return true;
    }
    RayTraceResult hit = world.rayTraceBlocks(eye, new Vector(dx, dy, dz).normalize(), distance,
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
        Schedulers.onEntity(plugin, player, () -> show(player, entity));
      }
    }
    // 离线玩家（停用时已不在线）的登记一并丢弃：它们已随退出被恢复，这里只兜底清账本
    hidden.clear();
  }

  /**
   * 清掉某玩家的全部「在途计算」标记。
   *
   * <p><b>覆盖面说明（无泄漏路径）</b>：{@code inFlight} 的键是（玩家 UUID, 实体 id）记录，
   * 玩家退役（退出）时本方法由 {@link #onQuit} 调用，把该玩家所有在途键一并摘除——即使某个
   * 射线计算还悬在工作队列里，回调执行时也只会做一次多余的 {@code remove}（幂等），不会累积。
   * 实体侧的退役（死亡/卸载）不产生键泄漏：键以玩家为前缀，玩家退出时已整体清理；
   * 插件停用时 {@link #stop} 的 {@code inFlight.clear()} 兜底清空。
   */
  private void purgeInFlight(UUID playerId) {
    inFlight.removeIf(key -> key.playerId().equals(playerId));
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

    private final LinkedHashMap<Integer, Entity> order = new LinkedHashMap<>();
    private int cursor;

    /** 加入一个被追踪实体（按 entityId 去重）。 */
    synchronized void add(Entity entity) {
      order.putIfAbsent(entity.getEntityId(), entity);
    }

    /** 按实例摘除（不读实体状态，供可能已跨区域的 untrack 事件使用）。 */
    synchronized void remove(Entity entity) {
      Integer key = null;
      int index = 0;
      for (Map.Entry<Integer, Entity> entry : order.entrySet()) {
        if (entry.getValue() == entity) {
          key = entry.getKey();
          break;
        }
        index++;
      }
      if (key == null) {
        return;
      }
      order.remove(key);
      if (cursor > index) {
        cursor--;
      }
    }

    /** 按 id 摘除一个不再被追踪的实体，并修正游标使「下一页」不被跳过。 */
    synchronized void remove(int entityId) {
      if (!order.containsKey(entityId)) {
        return;
      }
      int index = 0;
      for (Integer key : order.keySet()) {
        if (key == entityId) {
          break;
        }
        index++;
      }
      order.remove(entityId);
      if (cursor > index) {
        cursor--;
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
      int index = 0;
      for (Map.Entry<Integer, Entity> entry : order.entrySet()) {
        // 与旧实现同一分片语义：位置落在 [start, start+step) 内才取，已隐藏者按「存下来的 id」跳过
        boolean inSlice = Math.floorMod(index - start, size) < step;
        index++;
        if (inSlice && !hiddenIds.contains(entry.getKey())) {
          batch.add(entry.getValue());
        }
      }
      cursor = (start + step) % size;
      return batch;
    }
  }
}