package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * 实体射线剔除的「隐藏 → 恢复」账本回归。
 *
 * <p>真机反馈「实体隐藏 721 / 恢复 299」的不对称是否安全，取决于三件事：① 隐藏与恢复的触发条件成对；
 * ② 实体失效（死亡 / 卸载）时隐藏记录被清理（不泄漏映射）；③ 停用 / 退出有全量恢复入口。
 * 本测试把这三件事逐一钉死。
 *
 * <p><b>与原实现的差异</b>：遮挡结论已改由 Paper 原生 {@code World#rayTraceBlocks} 给出，
 * 因此账本类断言直接把结论（{@code evaluate(..., allBlocked, ...)}）喂给评估入口；
 * 「判定失败必须保持可见」的红线另由 {@link #failedBlockReadKeepsEntityVisible} 覆盖
 * （世界桩让 {@code rayTraceBlocks} 抛异常）。
 *
 * <p>Bukkit 运行时在离线测试环境不可用（服务器的注册 / 调度都是静态状态），因此这里用
 * {@link Proxy} 动态代理 Bukkit 接口作为替身——不引入任何新依赖（pom 只有 junit-jupiter），
 * 也不需要 Mockito 之类的框架。
 */
class EntityCullerTest {

  private static final Logger LOGGER = Logger.getLogger("EntityCullerTest");

  @Test
  void hiddenEntityIsShownAgainWhenTheViewIsClear() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    EntityStub entity = new EntityStub(101, new WorldStub());

    culler.evaluate(player.proxy(), entity.proxy(), true);
    assertEquals(1L, stats.entitiesHidden.sum(), "被完全遮挡必须隐藏");
    assertEquals(1, culler.hiddenCount(), "隐藏账本必须有记录");
    assertEquals(0L, stats.entitiesShown.sum());

    // 重复判定仍然遮挡：不得重复计入隐藏数、不得重复登记（账本按 (玩家, 实体) 去重）
    culler.evaluate(player.proxy(), entity.proxy(), true);
    assertEquals(1L, stats.entitiesHidden.sum(), "同一实体重复判定只算一次隐藏");
    assertEquals(1, culler.hiddenCount());

    // 视线通畅（原生射线判为不被挡）→ 必须恢复显示，且账本同步摘除
    culler.evaluate(player.proxy(), entity.proxy(), false);
    assertEquals(1L, stats.entitiesShown.sum(), "重新可见必须调用 showEntity");
    assertEquals(1, player.showCalls.get(), "恢复必须真的下发 showEntity（否则会残留看不见的怪）");
    assertEquals(0, culler.hiddenCount(), "恢复后账本必须同步摘除");
  }

  /** 实体失效（死亡 / 区块卸载）时只清账本，不调用 showEntity——服务端已自然回收，这正是 721 vs 299 的一部分。 */
  @Test
  void invalidEntityIsDroppedFromTheLedgerWithoutCountingAsShown() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    EntityStub entity = new EntityStub(202, new WorldStub());

    culler.evaluate(player.proxy(), entity.proxy(), true);
    assertEquals(1, culler.hiddenCount());

    entity.valid = false;
    culler.evaluate(player.proxy(), entity.proxy(), false);

    assertEquals(0, culler.hiddenCount(), "失效实体的隐藏记录必须清理，避免映射泄漏");
    assertEquals(0L, stats.entitiesShown.sum(), "已被服务端自然回收的实体不需要（也不该）showEntity");
    assertEquals(0, player.showCalls.get());
  }

  /** 玩家离线时的判定同样只清账本（玩家已看不到实体，恢复无意义）。 */
  @Test
  void offlinePlayerIsDroppedFromTheLedger() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    EntityStub entity = new EntityStub(303, new WorldStub());

    culler.evaluate(player.proxy(), entity.proxy(), true);
    assertEquals(1, culler.hiddenCount());

    player.online = false;
    culler.evaluate(player.proxy(), entity.proxy(), true);

    assertEquals(0, culler.hiddenCount(), "玩家离线后不得继续保留隐藏记录");
    assertEquals(0L, stats.entitiesShown.sum(), "离线不产生恢复计数");
    assertEquals(0, player.showCalls.get());
  }

  /** 停用 / 退出时的全量恢复入口：必须交出全部隐藏实体并清空账本（不留副作用）。 */
  @Test
  void drainPlayerHandsOverEveryHiddenEntryForFullRestore() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityStub first = new EntityStub(1, world);
    EntityStub second = new EntityStub(2, world);

    culler.evaluate(player.proxy(), first.proxy(), true);
    culler.evaluate(player.proxy(), second.proxy(), true);
    assertEquals(2, culler.hiddenCount());

    Map<Integer, Entity> drained = culler.drainPlayer(player.id());
    assertEquals(2, drained.size(), "全量恢复入口必须交出全部隐藏实体");
    assertTrue(drained.containsKey(first.id()), "交出的清单必须含第一个实体");
    assertTrue(drained.containsKey(second.id()), "交出的清单必须含第二个实体");
    assertEquals(0, culler.hiddenCount(), "交出后账本必须清空");
    assertTrue(culler.drainPlayer(player.id()).isEmpty(), "重复交出必须得到空表（幂等，异常安全）");
  }

  private static EntityCuller newCuller(ThrottleStats stats) {
    return newCuller(stats, 12);
  }

  // ------------------------------------------------- 周期复检轮转分片（本次缺陷回归）

  /**
   * 轮转覆盖：每周期提交数不超过预算，且在 {@code ceil(追踪数 / budget)} 个周期内覆盖全部追踪实体。
   *
   * <p>这是「先可见、之后才被挡住」实体能被收敛到隐藏的前提——旧实现只复检「已隐藏」集合，
   * 因此这类实体永远不会再被评估。
   */
  @Test
  void rotationCoversEveryTrackedEntityWithinBudgetedCycles() {
    EntityCuller.TrackedRotation rotation = new EntityCuller.TrackedRotation();
    int total = 50;
    int budget = 10;
    for (int i = 0; i < total; i++) {
      rotation.add(new EntityStub(1000 + i, new WorldStub()).proxy());
    }
    assertEquals(total, rotation.size(), "轮转队列必须登记全部追踪实体");

    Set<Integer> covered = new HashSet<>();
    int cycles = 0;
    int maxCycles = (total + budget - 1) / budget;
    while (covered.size() < total && cycles < maxCycles) {
      List<Entity> batch = rotation.nextBatch(budget, Set.of());
      assertTrue(batch.size() <= budget, "每周期复检数不得超过预算（实际 " + batch.size() + "）");
      for (Entity entity : batch) {
        covered.add(entity.getEntityId());
      }
      cycles++;
    }

    assertEquals(total, covered.size(),
        "50 个追踪实体、预算 10 → 必须在 " + maxCycles + " 个周期内全部复检一次（实际 " + cycles + "）");
  }

  /** 已隐藏的实体不占用轮转预算配额：它们由「每周期全量复检」通道负责，不能因此挤掉可见实体的份额。 */
  @Test
  void rotationSkipsHiddenEntitiesButStillAdvancesTheCursor() {
    EntityCuller.TrackedRotation rotation = new EntityCuller.TrackedRotation();
    for (int i = 0; i < 6; i++) {
      rotation.add(new EntityStub(2000 + i, new WorldStub()).proxy());
    }
    Set<Integer> hidden = Set.of(2000, 2001, 2002);

    List<Entity> first = rotation.nextBatch(3, hidden);
    assertEquals(0, first.size(), "本批位置全是已隐藏实体：不应产出可见复检项");
    List<Entity> second = rotation.nextBatch(3, hidden);
    assertEquals(Set.of(2003, 2004, 2005), idsOf(second), "游标必须照常推进到下一批（否则会饿死后面的实体）");
  }

  /**
   * 本次缺陷的端到端回归：实体「先可见、之后才被墙挡住」→ 在有限周期内被隐藏。
   *
   * <p>用 {@link EntityCuller.TrackedRotation} 模拟周期复检的轮转推进（纯逻辑），评估仍走真实的
   * {@link EntityCuller#evaluate} 账本，因此不依赖 Bukkit 调度。
   */
  @Test
  void entityThatBecomesOccludedAfterBeingVisibleIsHiddenWithinBoundedCycles() {
    ThrottleStats stats = new ThrottleStats();
    int budget = 10;
    EntityCuller culler = newCuller(stats, budget);
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();

    EntityCuller.TrackedRotation rotation = new EntityCuller.TrackedRotation();
    List<EntityStub> stubs = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      EntityStub stub = new EntityStub(3000 + i, world);
      stubs.add(stub);
      rotation.add(stub.proxy());
    }

    // 阶段一：实体刚入场、视线通畅 → 一轮完整轮转内不得隐藏任何实体
    runRotation(rotation, budget, player, culler, false);
    assertEquals(0L, stats.entitiesHidden.sum(), "视线通畅时不得隐藏（先可见阶段的正常状态）");
    assertEquals(0, culler.hiddenCount());

    // 阶段二：之后才建墙遮挡 → 同一轮转在一个有限周期内必然再次覆盖到它并隐藏
    runRotation(rotation, budget, player, culler, true);
    assertEquals(50L, stats.entitiesHidden.sum(),
        "「先可见后被遮挡」的实体必须在有限周期内被隐藏（旧实现此处恒为 0）");
    assertEquals(50, culler.hiddenCount());
  }

  /** 走一轮完整轮转：每个被选中的实体都用真实评估入口判定一次。 */
  private static void runRotation(EntityCuller.TrackedRotation rotation, int budget, PlayerStub player,
      EntityCuller culler, boolean allBlocked) {
    int cycles = 0;
    int maxCycles = (rotation.size() + budget - 1) / budget;
    while (cycles < maxCycles) {
      for (Entity entity : rotation.nextBatch(budget, Set.of())) {
        culler.evaluate(player.proxy(), entity, allBlocked);
      }
      cycles++;
    }
  }

  /**
   * Folia 回归：已隐藏实体<b>离开本区域</b>后，周期复检不得读取它的任何状态。
   *
   * <p>真机症状（Lophine 26.3 / Folia）：{@code EntityCuller.recheck} 在玩家所在区域线程上读跨区域实体的
   * entityId → {@code TickThread.ensureTickThread} <b>先打 ERROR 再抛</b> IllegalStateException，
   * 任务每轮刷屏。这里把「跨区域实体」建模为「归属判定为 false + 一旦被读状态就抛」——
   * 实现里只要还有一次越界读取，本用例即失败。
   */
  @Test
  void foreignRegionEntityIsSkippedWithoutTouchingItsState() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityStub foreign = new EntityStub(7001, world);
    EntityStub owned = new EntityStub(7002, world);
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 7, 3), stats,
        entity -> entity != foreign.proxy());

    // 先在「同区域」时把两者隐藏（此时读状态合法），随后 foreign 离开本区域
    culler.evaluate(player.proxy(), foreign.proxy(), true);
    culler.evaluate(player.proxy(), owned.proxy(), true);
    assertEquals(2, culler.hiddenCount());
    foreign.foreign = true;

    culler.recheck(player.proxy()); // 关键：不得抛异常（更不得读 foreign 的状态）

    assertEquals(1L, stats.recheckSubmitted.sum(),
        "本区域实体仍须被复检：不能因为存在跨区域实体就整轮放弃");
    assertEquals(2, culler.hiddenCount(),
        "跨区域实体本轮既不清理也不报错，留待重新进入追踪范围时的 onTrack 复评或退出时的账本清理");
  }

  /** 已隐藏实体每周期全量复检（不退化），可见追踪实体每周期只取预算分片。 */
  @Test
  void recheckSubmitsEveryHiddenEntityPlusABudgetedTrackedSlice() {
    ThrottleStats stats = new ThrottleStats();
    int budget = 3;
    EntityCuller culler = newCuller(stats, budget);
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();

    // 3 个「入场即被遮挡」的实体：位置离玩家很远（不触发强制可见）→ 复检时走射线判定，保持隐藏
    for (int i = 0; i < 3; i++) {
      culler.evaluate(player.proxy(), new EntityStub(4000 + i, world).proxy(), true);
    }
    assertEquals(3, culler.hiddenCount());

    // 10 个可见追踪实体：位置与玩家重合（在强制可见距离内）→ 经真实 onTrack 路径登记进轮转队列，
    // 且不会被隐藏，从而不占用「已隐藏实体」那条通道
    for (int i = 0; i < 10; i++) {
      EntityStub stub = new EntityStub(5000 + i, world, 0.5D, 65.0D, 0.5D);
      culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), stub.proxy()));
    }

    culler.recheck(player.proxy());
    assertEquals(3L + budget, stats.recheckSubmitted.sum(),
        "每周期 = 已隐藏实体全量复检（3）+ 可见追踪实体分片（≤ 预算 " + budget + "）");

    culler.recheck(player.proxy());
    assertEquals(2L * (3 + budget), stats.recheckSubmitted.sum(),
        "已隐藏实体仍须每周期复检（行为不退化）");
  }

  /** 复检通道的计数归属：由遮挡新隐藏 / 恢复可见都要计入「复检致隐藏 / 复检致恢复」。 */
  @Test
  void recheckEvaluationsAttributeNewHidesAndRestores() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats, 12);
    PlayerStub player = new PlayerStub();
    EntityStub entity = new EntityStub(6001, new WorldStub());

    culler.evaluate(player.proxy(), entity.proxy(), false, true);
    assertEquals(0L, stats.recheckHidden.sum(), "视线通畅时复检不得隐藏");
    assertEquals(0, culler.hiddenCount());

    culler.evaluate(player.proxy(), entity.proxy(), true, true);
    assertEquals(1L, stats.recheckHidden.sum(), "复检发现新遮挡必须计入「复检致隐藏」");
    assertEquals(1, culler.hiddenCount());

    culler.evaluate(player.proxy(), entity.proxy(), false, true);
    assertEquals(1L, stats.recheckShown.sum(), "复检重新可见必须计入「复检致恢复」");
    assertEquals(0, culler.hiddenCount());
  }

  /** 红线：射线 / 世界读取失败（异常）时不得隐藏，实体保持可见。 */
  @Test
  void failedBlockReadKeepsEntityVisible() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats, 12);
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    world.failBlockRead = true;
    EntityStub entity = new EntityStub(7001, world);

    assertFalse(culler.isFullyOccluded(player.proxy(), entity.proxy()),
        "读世界/射线失败必须按「保持可见」处理（fail-open，不得误藏）");
    assertEquals(0, culler.hiddenCount(), "失败时不得隐藏");
    assertEquals(0L, stats.entitiesHidden.sum());
    assertEquals(0L, stats.recheckHidden.sum());
  }

  private static Set<Integer> idsOf(List<Entity> entities) {
    Set<Integer> ids = new HashSet<>();
    for (Entity entity : entities) {
      ids.add(entity.getEntityId());
    }
    return ids;
  }

  private static EntityCuller newCuller(ThrottleStats stats, int recheckBudget) {
    // 启用实体剔除与射线判定；强制可见距离 2 格，均为不影响本测试的取值
    return new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, recheckBudget), stats);
  }

  /** 接口方法的默认返回值（未显式打桩的方法一律走这里）。 */
  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return (char) 0;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    return null;
  }

  private static boolean isObjectMethod(Method method) {
    String name = method.getName();
    return name.equals("hashCode") || name.equals("equals") || name.equals("toString");
  }

  /** 玩家替身：只打桩 EntityCuller 真正会读到的方法。 */
  private static final class PlayerStub implements InvocationHandler {

    private final UUID id = UUID.randomUUID();
    private final AtomicInteger showCalls = new AtomicInteger();
    private final Player proxy = (Player) Proxy.newProxyInstance(
        Player.class.getClassLoader(), new Class<?>[] {Player.class}, this);
    /** 位置与 {@link EntityStub} 的默认位置不同，但坐标本身不重要（只用于距离比较）。 */
    private final Location location = new Location(null, 0.5D, 65.0D, 0.5D);
    private volatile boolean online = true;

    Player proxy() {
      return proxy;
    }

    UUID id() {
      return id;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "PlayerStub");
      }
      return switch (method.getName()) {
        case "getUniqueId" -> id;
        case "isOnline" -> online;
        case "hasPermission" -> false;
        case "getLocation", "getEyeLocation" -> location;
        case "hideEntity" -> null;
        case "showEntity" -> {
          showCalls.incrementAndGet();
          yield null;
        }
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  /** 实体替身：EntityCuller 会读 entityId / isValid / getWorld / getLocation / getBoundingBox。 */
  private static final class EntityStub implements InvocationHandler {

    private final int id;
    private final WorldStub world;
    private final Entity proxy;
    private final Location location;
    private final BoundingBox box;
    private volatile boolean valid = true;
    /** 模拟「实体已离开本区域」：任何状态读取都像 Folia 那样抛异常（真机还会先打一条 ERROR）。 */
    private volatile boolean foreign;

    EntityStub(int id, WorldStub world) {
      this(id, world, 100.0D, 65.0D, 100.0D);
    }

    EntityStub(int id, WorldStub world, double x, double y, double z) {
      this.id = id;
      this.world = world;
      this.location = new Location(null, x, y, z);
      this.box = new BoundingBox(x, y, z, x + 0.6D, y + 1.8D, z + 0.6D);
      this.proxy = (Entity) Proxy.newProxyInstance(
          Entity.class.getClassLoader(), new Class<?>[] {Entity.class}, this);
    }

    Entity proxy() {
      return proxy;
    }

    int id() {
      return id;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "EntityStub#" + id);
      }
      if (foreign) {
        // 跨区域读取：真机上 TickThread.ensureTickThread 先 ERROR 再抛，这里只要被读到就抛
        throw new IllegalStateException("模拟 Folia 跨区域访问：entity#" + id);
      }
      return switch (method.getName()) {
        case "getEntityId" -> id;
        case "isValid" -> valid;
        case "getWorld" -> world.proxy();
        case "getLocation" -> location;
        case "getBoundingBox" -> box;
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  /**
   * 世界替身：{@code rayTraceBlocks} 由 {@code occluding} 开关决定「命中遮挡方块 / 通畅」
   * （命中时返回带 {@link Material#STONE} 的 {@link RayTraceResult}，未命中返回 null）。
   */
  private static final class WorldStub implements InvocationHandler {

    private final Block block = (Block) Proxy.newProxyInstance(
        Block.class.getClassLoader(), new Class<?>[] {Block.class}, this);
    private final World proxy = (World) Proxy.newProxyInstance(
        World.class.getClassLoader(), new Class<?>[] {World.class}, this);
    /** true = 射线命中遮挡方块（实体被判为被挡）；false = 射线通畅（实体可见）。 */
    private volatile boolean occluding = true;
    /** 为 true 时 rayTraceBlocks 抛异常，模拟「射线 / 世界读取失败」（红线：此时不得隐藏）。 */
    private volatile boolean failBlockRead;

    World proxy() {
      return proxy;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "WorldStub");
      }
      return switch (method.getName()) {
        case "rayTraceBlocks" -> {
          if (failBlockRead) {
            throw new IllegalStateException("模拟射线 / 世界读取失败");
          }
          yield occluding ? new RayTraceResult(new Vector(0, 0, 0), block, BlockFace.UP) : null;
        }
        // 命中方块的材质：stone 为遮挡方块、glass 为非遮挡方块（可透见其后实体）
        case "getType" -> occluding ? Material.STONE : Material.GLASS;
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  /** 插件替身：只提供 Logger。 */
  private static final class PluginStub implements InvocationHandler {

    private final Plugin proxy = (Plugin) Proxy.newProxyInstance(
        Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, this);

    Plugin proxy() {
      return proxy;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "PluginStub");
      }
      return switch (method.getName()) {
        case "getLogger" -> LOGGER;
        case "getName" -> "EntityCullerTest";
        default -> defaultValue(method.getReturnType());
      };
    }
  }
}