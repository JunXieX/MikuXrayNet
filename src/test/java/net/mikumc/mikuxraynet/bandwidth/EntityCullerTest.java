package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.bukkit.event.player.PlayerQuitEvent;
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

    Map<Integer, EntityCuller.HiddenEntry> drained = culler.drainPlayer(player.id());
    assertEquals(2, drained.size(), "全量恢复入口必须交出全部隐藏实体");
    assertTrue(drained.containsKey(first.id()), "交出的清单必须含第一个实体");
    assertTrue(drained.containsKey(second.id()), "交出的清单必须含第二个实体");
    assertEquals(first.proxy(), drained.get(first.id()).entity(), "交出的条目必须携带原实体实例");
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
   * <p><b>刻意走真实入口</b>：实体经 {@code onTrack}（入场首评）登记进轮转队列，再经真实的
   * {@code recheck}（周期复检 → 通道②轮转分片 → 射线判定 → 隐藏）。若实现里再现「复检在到达
   * evaluate 前提前返回」的缺陷，本用例的 {@code entitiesHidden} / {@code hiddenCount} 会恒为 0 而失败。
   *
   * <p>遮挡结论由注入的 {@link EntityCuller.Occlusion} 给出：离线环境连 {@code Material} 都初始化不了
   * （{@code org.bukkit.Registry} 不可用），真实的 {@code World#rayTraceBlocks} 结果无从成立，故本用例
   * 只验证「复检链路与账本/计数」，不验证射线本身。
   */
  @Test
  void entityThatBecomesOccludedAfterBeingVisibleIsHiddenWithinBoundedCycles() {
    ThrottleStats stats = new ThrottleStats();
    int budget = 10;
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    AtomicBoolean occluded = new AtomicBoolean(false);
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, budget, frustumOff()), stats,
        entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> occluded.get());

    // 50 个追踪实体，位置远离玩家（超出强制可见距离 2 格）→ 走射线判定通道
    for (int i = 0; i < 50; i++) {
      EntityStub stub = new EntityStub(3000 + i, world);
      culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), stub.proxy()));
    }

    // 阶段一：入场首评时视线通畅 → 一个有限周期内不得隐藏任何实体
    runRecheckCycles(culler, player, budget, 50);
    assertEquals(0L, stats.entitiesHidden.sum(), "视线通畅时不得隐藏（先可见阶段的正常状态）");
    assertEquals(0, culler.hiddenCount());

    // 阶段二：之后才被墙挡住 → 轮转分片在有限周期内必然再次覆盖到它们并隐藏
    occluded.set(true);
    runRecheckCycles(culler, player, budget, 50);
    assertEquals(50L, stats.entitiesHidden.sum(),
        "「先可见后被遮挡」的实体必须经真实复检链路被隐藏（旧实现此处恒为 0）");
    assertEquals(50L, stats.recheckHidden.sum(), "「复检致隐藏」计数必须反映真实收敛（而非恒为 0）");
    assertEquals(50, culler.hiddenCount());
  }

  /** 走 {@code ceil(size / budget)} 个复检周期，使轮转分片覆盖全部追踪实体。 */
  private static void runRecheckCycles(EntityCuller culler, PlayerStub player, int budget, int size) {
    int cycles = (size + budget - 1) / budget;
    for (int i = 0; i < cycles; i++) {
      culler.recheck(player.proxy());
    }
  }

  /**
   * 通道①成本有界：已隐藏实体即使「视线已通畅」，只要仍在强制可见距离外就不打射线、不被复活。
   *
   * <p>这正是旧实现提前返回所要保护的场景（远距离账本条目会随会话累积大量长射线）。若有人把通道①
   * 也改成允许远距离打射线，本用例会因实体被恢复而失败。
   */
  @Test
  void farHiddenEntityIsNotRevivedByTheRestoreChannel() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    AtomicBoolean occluded = new AtomicBoolean(true);
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 3, frustumOff()), stats,
        entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> occluded.get());

    EntityStub far = new EntityStub(7101, world); // 默认位置 (100,65,100)，远超强制可见距离 2 格
    culler.evaluate(player.proxy(), far.proxy(), true);
    assertEquals(1, culler.hiddenCount());

    occluded.set(false); // 视线变通畅，但实体仍在远距离
    runRecheckCycles(culler, player, 3, 1);
    assertEquals(1, culler.hiddenCount(), "远距离已隐藏实体不得被通道①打射线复活（成本有界）");
    assertEquals(0L, stats.recheckShown.sum(), "通道①对远距离实体不产生恢复");
  }

  /**
   * 强制可见距离内的实体一律可见（安全策略）：即便判定为被遮挡，复检也必须把它恢复为可见，
   * 绝不隐藏它。
   */
  @Test
  void entityWithinForceVisibleDistanceIsRestoredAndNeverHidden() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    // 始终判为被遮挡：安全策略若失效，近处实体就会被隐藏
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 3, frustumOff()), stats,
        entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> true);

    // 与玩家重合（强制可见距离内）；直接置为隐藏，模拟「曾被隐藏」
    EntityStub near = new EntityStub(7201, world, 0.5D, 65.0D, 0.5D);
    culler.evaluate(player.proxy(), near.proxy(), true);
    assertEquals(1, culler.hiddenCount());
    long hiddenBefore = stats.entitiesHidden.sum();

    culler.recheck(player.proxy());
    assertEquals(0, culler.hiddenCount(), "强制可见距离内的隐藏实体必须被恢复（安全策略）");
    assertEquals(1L, stats.recheckShown.sum(), "恢复应计入「复检致恢复」");
    assertEquals(hiddenBefore, stats.entitiesHidden.sum(), "强制可见距离内绝不新增隐藏");
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
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 7, 3, frustumOff()), stats,
        entity -> entity != foreign.proxy(), playerId -> false);

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

  /**
   * Folia 回归：玩家退出时，其隐藏列表里的<b>跨区域</b>实体不得被读取任何状态。
   *
   * <p>{@link EntityCuller#onQuit} 与 {@link EntityCuller#restoreAll} 旧实现直接对全部隐藏实体调
   * {@code show}（内部读 {@code isValid}）；跨区域实体会触发 TickThread 校验（真机先打 ERROR 再抛），
   * 虽有 try/catch 兜住不崩，但会周期性刷 ERROR 日志。这里用「一旦被读取就抛 + 读取计数」的替身，
   * 断言退出恢复路径对跨区域实体一次都没碰。
   */
  @Test
  void quitDoesNotTouchForeignRegionEntities() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityStub foreign = new EntityStub(8001, world);
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 7, 3, frustumOff()), stats,
        entity -> entity != foreign.proxy(), playerId -> false);

    culler.evaluate(player.proxy(), foreign.proxy(), true);
    assertEquals(1, culler.hiddenCount());
    int readsBeforeQuit = foreign.stateReads();
    foreign.foreign = true;

    assertDoesNotThrow(() -> culler.onQuit(new PlayerQuitEvent(player.proxy(), (String) null)),
        "退出恢复不得因跨区域实体抛异常");
    assertEquals(readsBeforeQuit, foreign.stateReads(),
        "跨区域实体的任何状态读取都会在 Folia 上刷 ERROR，退出恢复必须完全跳过它");
    assertEquals(0, culler.hiddenCount(), "账本随退出清空（跨区域实体的恢复交由其所在区域）");
  }

  /** 已隐藏实体每周期全量复检（不退化），可见追踪实体每周期只取预算分片。 */
  @Test
  void recheckSubmitsEveryHiddenEntityPlusABudgetedTrackedSlice() {
    ThrottleStats stats = new ThrottleStats();
    int budget = 3;
    EntityCuller culler = newCuller(stats, budget);
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();

    // 3 个「入场即被遮挡」的实体：位置离玩家很远（不触发强制可见）→ 复检走通道①；
    // 通道①只做近距离恢复（RESTORE_ONLY），远距离不打射线，故它们保持隐藏，仍计入「复检提交数」
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

  /**
   * 登录期快照语义：<b>进入服务器那一刻</b>已带 {@code mikuxraynet.bypass}（即已登记进
   * {@link net.mikumc.mikuxraynet.util.BypassRegistry} 登录快照）的玩家，周期复检必须恢复其
   * <b>全部</b>已隐藏实体，且不再提交任何射线复检。
   */
  @Test
  void recheckRestoresAllHiddenEntitiesForPlayerBypassedAtLogin() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityStub first = new EntityStub(6101, world);
    EntityStub second = new EntityStub(6102, world);
    // 注入「该玩家在登录期快照中」；归属默认按本区域（与生产 Paper 行为一致）
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12, frustumOff()), stats,
        entity -> true, playerId -> playerId.equals(player.id()));

    culler.evaluate(player.proxy(), first.proxy(), true);
    culler.evaluate(player.proxy(), second.proxy(), true);
    assertEquals(2, culler.hiddenCount());
    long submittedBefore = stats.recheckSubmitted.sum();

    culler.recheck(player.proxy());

    assertEquals(0, culler.hiddenCount(), "登录时已带直通权限：账本必须清空（实体恢复可见）");
    assertEquals(2, player.showCalls.get(), "必须对每个被隐藏实体下发 showEntity");
    assertEquals(submittedBefore, stats.recheckSubmitted.sum(), "直通玩家不再提交任何射线复检");
  }

  /**
   * 语义一致性回归：<b>运行期</b>才被授予 {@code mikuxraynet.bypass} 的玩家<b>不会即时生效</b>——
   * 直通判定只读登录期快照，必须重新进入服务器（退出→重登）才按新权限重算，因此本轮复检照常评估、
   * 不恢复已隐藏实体（这是用户要的语义，不是缺陷）。同时本用例也钉死「周期路径不得查权限」。
   */
  @Test
  void runtimeGrantedBypassDoesNotTakeEffectUntilRelogin() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityStub entity = new EntityStub(6201, world);
    // 快照里没有该玩家（登录时未带权限）
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12, frustumOff()), stats,
        owned -> true, playerId -> false);

    culler.evaluate(player.proxy(), entity.proxy(), true);
    assertEquals(1, culler.hiddenCount());
    long submittedBefore = stats.recheckSubmitted.sum();

    culler.recheck(player.proxy());

    assertEquals(1, culler.hiddenCount(), "运行期授权的玩家不在登录快照里：本轮不得恢复，需重进服务器");
    assertEquals(0, player.showCalls.get(), "未直通玩家不得下发 showEntity");
    assertEquals(submittedBefore + 1L, stats.recheckSubmitted.sum(),
        "未直通玩家照常复检其已隐藏实体（周期路径不得查权限）");
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

  /**
   * 有效采样上限与常量一致：包围盒「朝向玩家一侧」的可见顶点数为 7，正对应
   * {@code entity-culling.ray-samples} 的上限 {@link BandwidthConfig#MAX_RAY_SAMPLES}=7
   * （旧注释曾写「钳制 1..8」，籍此钉死两者的对应关系，见 P3 打磨项）。
   */
  @Test
  void visibleVerticesCountMatchesRaySampleCap() {
    assertEquals(7, BandwidthConfig.MAX_RAY_SAMPLES, "上限常量本身即 7");

    double[][] vertices = EntityCuller.visibleVertices(0.5D, 64.5D, 0.5D,
        10.0D, 64.0D, 10.0D, 11.0D, 66.0D, 11.0D);
    assertEquals(BandwidthConfig.MAX_RAY_SAMPLES, vertices.length,
        "普通包围盒的可见顶点数应恰为上限 7（与 ray-samples 的有效钳制一致）");

    // 极小包围盒退化为 1 个中心点，同样不得超过上限
    double[][] tiny = EntityCuller.visibleVertices(0.5D, 64.5D, 0.5D,
        10.0D, 64.0D, 10.0D, 10.05D, 64.05D, 10.05D);
    assertTrue(tiny.length <= BandwidthConfig.MAX_RAY_SAMPLES, "小包围盒的顶点数不得超过上限");
  }

  /**
   * 停用并发闭合（任务1）：{@code stop()} 首行置 {@code stopping} 后，任何迟到的复检/评估
   * <b>都不得再隐藏实体</b>——否则会把 {@code restoreAll()} 刚恢复的实体重新藏回（玩家持续不可见）。
   *
   * <p>离线不可构造完整停用链路（{@code restoreAll} 依赖 {@code Bukkit.getOnlinePlayers()}），
   * 因此这里以反射置位 {@code stopping}，只钉死「迟到隐藏被拒」这一关键不变量。
   */
  @Test
  void stopPreventsLateHides() throws Exception {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    EntityStub entity = new EntityStub(9001, new WorldStub());

    setStopping(culler);
    culler.evaluate(player.proxy(), entity.proxy(), true);

    assertEquals(0L, stats.entitiesHidden.sum(),
        "停用开始后不得再隐藏实体（否则会把刚被恢复的实体重新藏回）");
    assertEquals(0, culler.hiddenCount(), "停用开始后账本不得新增记录");
  }

  private static void setStopping(EntityCuller culler) throws Exception {
    Field field = EntityCuller.class.getDeclaredField("stopping");
    field.setAccessible(true);
    field.setBoolean(culler, true);
  }

  // ------------------------------------------------- 视锥剔除（entity-culling.frustum）

  /**
   * 视锥剔除：位于视野锥之外、且在距离门之外的实体被隐藏；玩家转头后，复检以<b>纯数学</b>复判把它恢复
   * （不打射线）。这正是「转头后 ≤ 1 个复检周期补发」的实现，也是该子项不产生「转头就永久消失」的关键。
   */
  @Test
  void entityBehindThePlayerIsHiddenAndRestoredAfterTurning() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    // 遮挡实现恒为「不遮挡」：本次隐藏只可能来自视锥剔除，从而钉死隐藏来源
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12,
            new BandwidthConfig.EntityCulling.Frustum(true, 110.0D, 2.0D)),
        stats, entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> false);

    // 玩家朝 +Z（yaw=0）看向前方；实体在其<b>斜后方</b>且距离约 141 格（远超距离门 2 格）
    EntityStub behind = new EntityStub(9101, world, 100.0D, 65.0D, -100.0D);
    culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), behind.proxy()));

    assertEquals(1L, stats.frustumHidden.sum(), "锥外 + 超距的实体必须被视锥剔除隐藏");
    assertEquals(1, culler.hiddenCount());
    assertEquals(0L, stats.entitiesShown.sum());

    // 仍背对着它：复检不得恢复（否则会在转头前反复显隐）
    culler.recheck(player.proxy());
    assertEquals(1, culler.hiddenCount(), "仍在锥外时复检不得恢复");
    assertEquals(0L, stats.frustumShown.sum());

    // 转头面向它：下一次复检按纯数学复判恢复（打射线的遮挡通道判为不遮挡，故实体保持可见）
    player.faceYaw(-135.0F);
    culler.recheck(player.proxy());

    assertEquals(0, culler.hiddenCount(), "回到视野锥内必须恢复显示（转头后 ≤ 1 个复检周期）");
    assertEquals(1L, stats.frustumShown.sum(), "视锥恢复必须计入「视锥剔除·恢复」口径");
    assertEquals(1L, stats.entitiesShown.sum());
  }

  /** 视野锥内的实体不做视锥剔除（即便距离很远）；这是「玩家看得见的实体绝不隐藏」的红线。 */
  @Test
  void entityInsideFrustumIsNeverHiddenByFrustumCulling() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12,
            new BandwidthConfig.EntityCulling.Frustum(true, 110.0D, 2.0D)),
        stats, entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> false);

    // 玩家朝 +Z；实体在正前偏 45°（在 110° FOV 的水平视野内）、距离约 141 格
    EntityStub front = new EntityStub(9102, world, 100.0D, 65.0D, 100.0D);
    culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), front.proxy()));

    assertEquals(0, culler.hiddenCount(), "视野锥内的实体绝不能被视锥剔除");
    assertEquals(0L, stats.frustumHidden.sum());
  }

  /** 视锥距离门：锥外但未超过距离门的实体不剔除（避免近处实体在转头瞬间「消失再出现」）。 */
  @Test
  void entityOutsideFrustumWithinDistanceGateIsKeptVisible() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    // 距离门 200 格：实体在斜后方但只有约 141 格 → 未超过距离门，不得剔除
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12,
            new BandwidthConfig.EntityCulling.Frustum(true, 110.0D, 200.0D)),
        stats, entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> false);

    EntityStub behind = new EntityStub(9103, world, 100.0D, 65.0D, -100.0D);
    culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), behind.proxy()));

    assertEquals(0, culler.hiddenCount(), "未超过距离门时不得做视锥剔除");
    assertEquals(0L, stats.frustumHidden.sum());
  }

  /**
   * 不振荡红线：因<b>射线遮挡</b>隐藏的远距离实体，即便玩家转头把它转出视野锥，复检也不得恢复它
   * （它不是视锥隐藏的条目）。否则「锥内恢复 → 轮转射线再隐藏」会每周期反复显隐。
   */
  @Test
  void occlusionHiddenEntityIsNotRestoredByTheFrustumChannel() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12,
            new BandwidthConfig.EntityCulling.Frustum(true, 110.0D, 2.0D)),
        stats, entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> true);

    // 视野锥内 + 判为被遮挡 → 由射线通道隐藏（账本标记 frustumCulled=false）
    EntityStub front = new EntityStub(9104, world, 100.0D, 65.0D, 100.0D);
    culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), front.proxy()));
    assertEquals(1, culler.hiddenCount());
    assertEquals(0L, stats.frustumHidden.sum(), "视野锥内隐藏只能来自射线通道，不得计入视锥口径");

    // 转头把它转出视野锥：它现在是「锥外」，但并非视锥隐藏的条目 → 复检不得恢复
    player.faceYaw(180.0F);
    culler.recheck(player.proxy());

    assertEquals(1, culler.hiddenCount(),
        "射线隐藏的实体不得被视锥通道恢复（否则会与轮转射线反复显隐）");
    assertEquals(0L, stats.frustumShown.sum());
  }

  /**
   * 大型实体的视锥角点回归：中心已在锥外、但包围盒有一角仍在锥内时必须判为<b>可见</b>。
   *
   * <p>只看中心会让末影龙 / 巨人这类实体在「中心出锥、身体仍占屏幕边缘」时被误藏，表现为平移视角时
   * 「闪现」。此处把玩家朝 +Z（yaw 0），实体中心偏航约 80°（超出水平半角约 68.5° → 锥外），
   * 而靠近视线一侧的角偏航约 64.5°（锥内）。
   */
  @Test
  void largeEntityWhoseCenterIsOutsideButCornerIsInsideIsNotCulled() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12,
            new BandwidthConfig.EntityCulling.Frustum(true, 110.0D, 2.0D)),
        stats, entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> false);

    // 中心 (99, 65, 17.9)：偏航约 80°（锥外）；包围盒 40×40，中心与 location 一致
    EntityStub large = new EntityStub(9201, world, 99.0D, 65.0D, 17.9D);
    large.box(79.0D, 64.0D, -2.1D, 119.0D, 66.0D, 37.9D);
    culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), large.proxy()));

    assertEquals(0, culler.hiddenCount(),
        "中心在锥外但包围盒角在锥内时不得剔除（否则大型实体在屏幕边缘会「闪现」）");
    assertEquals(0L, stats.frustumHidden.sum());
  }

  /** 对照：大型实体完全在玩家背后（中心与四角全在锥外）时，仍必须被视锥剔除。 */
  @Test
  void largeEntityFullyOutsideTheFrustumIsStillCulled() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12,
            new BandwidthConfig.EntityCulling.Frustum(true, 110.0D, 2.0D)),
        stats, entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> false);

    EntityStub large = new EntityStub(9202, world, 100.0D, 65.0D, -100.0D);
    large.box(80.0D, 64.0D, -120.0D, 120.0D, 66.0D, -80.0D);
    culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), large.proxy()));

    assertEquals(1L, stats.frustumHidden.sum(), "完全在视野之外的实体仍必须被剔除");
    assertEquals(1, culler.hiddenCount());
  }

  /**
   * 视锥计数的标记升级回归：账本条目由「射线隐藏」升级为「视锥隐藏」时必须补计一次
   * {@code frustumHidden}，否则恢复侧（通道①）会计 {@code frustumShown} 而隐藏侧从未计过，
   * {@code /mxnet status} 会出现「视锥剔除 恢复 > 隐藏」的自相矛盾。
   */
  @Test
  void frustumMarkerUpgradeIsCountedSoHiddenAndShownStayConsistent() {
    ThrottleStats stats = new ThrottleStats();
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    // 遮挡实现恒为「不遮挡」：步骤①直接传 allBlocked=true 造出射线隐藏的账本条目，
    // 步骤③转头后轮转通道的射线判定返回「不遮挡」→ 不会把刚恢复的实体又藏回去（隔离出纯计数语义）
    EntityCuller culler = new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, 12,
            new BandwidthConfig.EntityCulling.Frustum(true, 110.0D, 2.0D)),
        stats, entity -> true, playerId -> false, (ignoredPlayer, ignoredEntity) -> false);

    // ① 先由射线遮挡通道隐藏（账本标记 frustumCulled=false）
    EntityStub behind = new EntityStub(9301, world, 100.0D, 65.0D, -100.0D);
    culler.evaluate(player.proxy(), behind.proxy(), true);
    assertEquals(1L, stats.entitiesHidden.sum());
    assertEquals(0L, stats.frustumHidden.sum(), "射线隐藏不得计入视锥口径");

    // ② 重新追踪：该实体此刻「锥外 + 超距」→ 标记升级为视锥隐藏，必须补计一次
    culler.onTrack(new PlayerTrackEntityEvent(player.proxy(), behind.proxy()));
    assertEquals(1, culler.hiddenCount(), "标记升级不得重复登记（仍是同一条账本）");
    assertEquals(1L, stats.entitiesHidden.sum(), "标记升级不是「新隐藏」，不得再计一次总隐藏数");
    assertEquals(1L, stats.frustumHidden.sum(), "标记由射线升级为视锥时必须补计视锥隐藏");

    // ③ 转头朝它 → 通道①按视锥条目恢复
    player.faceYaw(-135.0F);
    culler.recheck(player.proxy());

    assertEquals(0, culler.hiddenCount(), "回到视野锥内必须恢复");
    assertEquals(1L, stats.frustumShown.sum());
    assertEquals(stats.frustumHidden.sum(), stats.frustumShown.sum(),
        "视锥「隐藏 / 恢复」两侧口径必须自洽（恢复不得多于隐藏）");
  }

  private static Set<Integer> idsOf(List<Entity> entities) {
    Set<Integer> ids = new HashSet<>();
    for (Entity entity : entities) {
      ids.add(entity.getEntityId());
    }
    return ids;
  }

  private static EntityCuller newCuller(ThrottleStats stats, int recheckBudget) {
    // 启用实体剔除与射线判定；强制可见距离 2 格，均为不影响本测试的取值。
    // 视锥剔除默认关闭：既有用例只关心射线遮挡链路，不应受新子项影响（视锥行为由专门的用例覆盖）。
    return new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 10, 8, recheckBudget, frustumOff()),
        stats);
  }

  /** 关闭视锥剔除的子项（既有用例用）：FOV/距离门取值在此配置下无意义。 */
  private static BandwidthConfig.EntityCulling.Frustum frustumOff() {
    return new BandwidthConfig.EntityCulling.Frustum(false, 110.0D, 0.0D);
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

    /** 转头：视锥判定的方向来自 {@code Location#getDirection()}，改 yaw 即可模拟玩家转身。 */
    void faceYaw(float yaw) {
      location.setYaw(yaw);
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
        // EntityCuller 的直通判定只读 BypassRegistry 登录期快照，任何路径都不得查询 Bukkit 权限
        // （事件路径与周期复检均然）；一旦有人把 hasPermission 加回来，这里立刻失败。
        case "hasPermission" -> throw new UnsupportedOperationException(
            "EntityCuller 不得查询 Bukkit 权限，直通判定只读 BypassRegistry 登录期快照");
        case "getLocation", "getEyeLocation" -> location;
        // 强制可见距离闸门改用零分配坐标 getter（不再 new Location）读取玩家位置
        case "getX" -> location.getX();
        case "getY" -> location.getY();
        case "getZ" -> location.getZ();
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
    private volatile BoundingBox box;
    private volatile boolean valid = true;
    /** 模拟「实体已离开本区域」：任何状态读取都像 Folia 那样抛异常（真机还会先打一条 ERROR）。 */
    private volatile boolean foreign;
    /** 被读取状态的次数（跨区域回归用：一次都不能被碰到）。 */
    private final AtomicInteger stateReads = new AtomicInteger();

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

    /** 覆盖包围盒（大型实体的视锥角点回归用）：须让包围盒中心与 {@code location} 一致。 */
    void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
      this.box = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    Entity proxy() {
      return proxy;
    }

    int id() {
      return id;
    }

    int stateReads() {
      return stateReads.get();
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "EntityStub#" + id);
      }
      stateReads.incrementAndGet();
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
        // 强制可见距离闸门改用零分配坐标 getter
        case "getX" -> location.getX();
        case "getY" -> location.getY();
        case "getZ" -> location.getZ();
        // 载具/乘客关系：默认无关（getPassengers 必须返回非 null，否则 isEmpty 会 NPE）
        case "getPassengers" -> List.of();
        case "getVehicle" -> null;
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