package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * 事件驱动即时显形（P1-4）的纯数据部分：曼哈顿半径判定、偏移表、每 tick 限额、
 * 「仍在伪装清单 + 未显形过」的候选判定与触发初筛。
 *
 * <p>显形链路本身（射线、发包、标记）复用既有 {@link ProximityRevealer#sendOne}，由真机验证；
 * 这里验证的是「触发时机」新增逻辑的判定正确性：命中触发、超半径不触发、限额生效、与周期不重复。
 */
class InstantRevealTest {

  private static final String WORLD = "world";
  /** 主世界最低 section 的绝对 Y（与生产 antixray.yml 一致）：区块内局部坐标打包用。 */
  private static final int MIN_HEIGHT = -64;

  /** 默认配置构造显形器（事件显形默认开启）；离线构造只需纯数据依赖，Bukkit 组件传 null。 */
  private ProximityRevealer revealer() {
    return new ProximityRevealer(null, config(), new ObfuscatedChunkIndex(1, 300),
        new RevealedSet(1024, 300), new ProximityStats(), null, null, null);
  }

  private static AntiXrayConfig config() {
    return AntiXrayConfig.from(yaml("enabled: true\n"));
  }

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException(exception);
    }
    return configuration;
  }

  // ---------------------------------------------------------- 半径判定与偏移表

  /** 变更命中玩家身边（曼哈顿 ≤ radius）才触发；恰好等于半径也算命中。 */
  @Test
  void changeWithinManhattanRadiusTriggers() {
    assertTrue(ProximityRevealer.withinManhattanRadius(0, 0, 0, 2, 0, 0, 2),
        "曼哈顿距离 2 = 半径：必须命中");
    assertTrue(ProximityRevealer.withinManhattanRadius(0, 0, 0, 1, 1, 0, 2),
        "曼哈顿距离 2：必须命中");
    assertFalse(ProximityRevealer.withinManhattanRadius(0, 0, 0, 1, 1, 1, 2),
        "曼哈顿距离 3 > 半径 2：不得触发");
    assertFalse(ProximityRevealer.withinManhattanRadius(0, 0, 0, 3, 0, 0, 2),
        "曼哈顿距离 3：不得触发");
  }

  /** 偏移表覆盖半径内全部偏移（含原点）、无重复、按距离由近到远排序；半径被钳制到 1~8。 */
  @Test
  void offsetTableCoversSphereAndIsSortedByDistance() {
    int[][] offsets = ProximityRevealer.instantOffsets(2);
    int expected = 0;
    for (int dx = -2; dx <= 2; dx++) {
      for (int dy = -2; dy <= 2; dy++) {
        for (int dz = -2; dz <= 2; dz++) {
          if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2) {
            expected++;
          }
        }
      }
    }
    assertEquals(expected, offsets.length, "偏移表必须覆盖曼哈顿半径内的全部坐标（含原点）");

    Set<Long> unique = new HashSet<>();
    int lastDistance = -1;
    for (int[] offset : offsets) {
      int distance = Math.abs(offset[0]) + Math.abs(offset[1]) + Math.abs(offset[2]);
      assertTrue(distance <= 2, "偏移不得超出半径");
      assertTrue(unique.add(((long) offset[0] << 32) ^ (offset[1] << 16) ^ offset[2]),
          "偏移不得重复");
      assertTrue(distance >= lastDistance, "偏移必须按距离由近到远排序（限额不足时优先近处）");
      lastDistance = distance;
    }
    assertEquals(0, offsets[0][0] + offsets[0][1] + offsets[0][2], "最近的偏移必须是原点（变更点自身）");

    assertEquals(7, ProximityRevealer.instantOffsets(1).length, "半径 1 = 原点 + 6 个正交邻居");
    assertEquals(ProximityRevealer.instantOffsets(8).length,
        ProximityRevealer.instantOffsets(99).length, "半径超限必须钳制到 8");
  }

  // ---------------------------------------------------------- 触发初筛与候选判定

  /** 变更邻域内有伪装坐标 → 初筛命中；邻域内没有（或变更太远）→ 不命中。 */
  @Test
  void triggerFiresOnlyWhenDisguisedPositionWithinRadius() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1024, 300);
    // 伪装坐标 (10, 64, 10)（minHeight=0 → 相对 y = 64）
    index.recordChunk(WORLD, 0, 0, 0, new int[] {64 << 8 | 10 << 4 | 10});

    assertTrue(ProximityRevealer.hasDisguisedNearby(index, WORLD, 10, 66, 10, 2),
        "变更 (10,66,10) 与伪装坐标曼哈顿距离 2 ≤ 半径：必须命中");
    assertTrue(ProximityRevealer.hasDisguisedNearby(index, WORLD, 10, 64, 10, 2),
        "变更点即伪装坐标自身：必须命中");
    assertFalse(ProximityRevealer.hasDisguisedNearby(index, WORLD, 10, 67, 10, 2),
        "变更 (10,67,10) 距伪装坐标 3 > 半径 2：不得命中");
    assertFalse(ProximityRevealer.hasDisguisedNearby(index, WORLD, 200, 64, 200, 2),
        "变更远在其它区块：不得命中");
    assertFalse(ProximityRevealer.hasDisguisedNearby(index, "other", 10, 64, 10, 2),
        "世界不同：不得命中");
  }

  /**
   * 候选判定：「仍在伪装清单（已伪装）」且「该玩家尚未显形过（与周期显形不重复）」。
   * 注销 / 标记后判定翻转，保证同一坐标当 tick 只发一次。
   */
  @Test
  void candidateRequiresDisguisedAndNotYetRevealed() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1024, 300);
    RevealedSet revealed = new RevealedSet(1024, 300);
    index.recordChunk(WORLD, 0, 0, 0, new int[] {64 << 8 | 10 << 4 | 10});

    UUID player = UUID.randomUUID();
    ProximityRevealer revealer = new ProximityRevealer(null, config(), index, revealed,
        new ProximityStats(), null, null, null);

    assertTrue(revealer.isInstantCandidate(player, WORLD, 10, 64, 10),
        "已伪装且未显形：是即时显形候选");

    revealed.mark(player, ChunkKey.ofBlock(WORLD, 10, 10), 10, 64, 10);
    assertFalse(revealer.isInstantCandidate(player, WORLD, 10, 64, 10),
        "该玩家已显形过：不得重复（与周期显形不重复的保证）");

    UUID other = UUID.randomUUID();
    assertTrue(revealer.isInstantCandidate(other, WORLD, 10, 64, 10),
        "已显形按玩家记录：其它玩家仍需显形");

    index.removePosition(WORLD, 10, 64, 10);
    assertFalse(revealer.isInstantCandidate(other, WORLD, 10, 64, 10),
        "服务端已下发变更（已注销）：不再是伪装坐标，无需显形");
  }

  // ---------------------------------------------------------- 每 tick 限额

  /**
   * 限额按「每玩家每 tick」生效：{@link ProximityRevealer.TickQuota#claim} 原子「取额度 + 预扣」，
   * tick 推进自动回满，退出精确清理。
   */
  @Test
  void tickQuotaResetsPerTickAndPerPlayer() {
    ProximityRevealer.TickQuota quota = new ProximityRevealer.TickQuota();
    UUID player = UUID.randomUUID();
    UUID other = UUID.randomUUID();

    assertEquals(16, quota.claim(player, 100L, 16, 16), "本 tick 未使用：满额");
    assertEquals(0, quota.claim(player, 100L, 16, 16), "预扣后本 tick 额度用尽（限额生效）");
    assertEquals(16, quota.claim(other, 100L, 16, 16), "限额按玩家隔离");
    assertEquals(16, quota.claim(player, 101L, 16, 16), "下一 tick 自动回满（周期兜底继续显形）");

    quota.clear(player);
    assertEquals(16, quota.claim(player, 101L, 16, 16), "退出清理后按满额对待（新玩家语义）");
  }

  /**
   * 并发领取不得超发：Folia 上同一玩家的事件显形可能由不同区域线程并发触发，
   * 旧实现「读 remaining → 发包 → setRemaining 写回」会让每次调用都拿到满额。
   */
  @Test
  void tickQuotaClaimsAreAtomicAcrossThreads() throws Exception {
    ProximityRevealer.TickQuota quota = new ProximityRevealer.TickQuota();
    UUID player = UUID.randomUUID();
    int threads = 8;
    int maxPerTick = 16;
    AtomicInteger grantedTotal = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch done = new CountDownLatch(threads);
      for (int t = 0; t < threads; t++) {
        pool.execute(() -> {
          try {
            start.await();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          grantedTotal.addAndGet(quota.claim(player, 5L, maxPerTick, maxPerTick));
          done.countDown();
        });
      }
      start.countDown();
      assertTrue(done.await(5, TimeUnit.SECONDS), "并发领取不得超时");
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
    assertEquals(maxPerTick, grantedTotal.get(),
        "并发领取的总量必须恰好等于限额（旧实现会成倍超发）");
  }

  // ---------------------------------------------------------- 发包额度线程安全（任务 5）

  /**
   * 共享 {@link ProximityRevealer.Budget} 会被多个异步任务并发持有：扣减必须是原子的，
   * 否则普通 int 字段会丢失更新、把「全服合计额度」突破成超发。这里 N 次并发扣减应精确归零。
   */
  @Test
  void budgetDecrementsAreAtomicAcrossThreads() throws Exception {
    int capacity = 1000;
    int threads = 8;
    ProximityRevealer.Budget budget = new ProximityRevealer.Budget(capacity);
    assertEquals(capacity, budget.remaining(), "初始额度");

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      int perThread = capacity / threads;
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch done = new CountDownLatch(threads);
      for (int t = 0; t < threads; t++) {
        pool.execute(() -> {
          try {
            start.await();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          for (int i = 0; i < perThread; i++) {
            budget.decrement();
          }
          done.countDown();
        });
      }
      start.countDown();
      assertTrue(done.await(5, TimeUnit.SECONDS), "并发扣减不得超时");
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    assertEquals(0, budget.remaining(), "并发扣减不得丢失更新（这正是普通 int 字段的缺陷）");
  }

  /**
   * 显形额度「预扣 + 归还」对称：批量显形在 {@code sendOne} 里先预扣额度封住单周期上限，
   * 若该批最终未能发出（退化路径发包失败），由 {@code flushBatch} 逐坐标 {@link ProximityRevealer.Budget#refund()}
   * 归还，避免发包失败无谓吃掉本周期预算（见 P3 打磨项）。
   *
   * <p>注：{@code flushBatch} 的集成路径需要 Bukkit {@code Player}/{@code World}，离线无法构造，
   * 故此处只钉死它依赖的额度归还语义；实际发包失败分支由真机日志验证。
   */
  @Test
  void budgetRefundRestoresReservedButUnsentQuota() {
    ProximityRevealer.Budget budget = new ProximityRevealer.Budget(2);
    assertEquals(2, budget.remaining(), "初始额度");
    budget.decrement();
    budget.decrement();
    assertEquals(0, budget.remaining(), "两次预扣后额度耗尽（本周期上限生效）");
    budget.refund();
    assertEquals(1, budget.remaining(), "未发出的坐标必须归还一个额度");
    budget.refund();
    assertEquals(2, budget.remaining(), "全部归还后恢复初始额度");
  }

  /**
   * 预扣后「一个包都没发」可以撤销（{@link ProximityRevealer.TickQuota#release}）：路过但没发包的事件
   * 不应白占一个 tick 的份额；已发出的部分不退还；tick 推进后不得把上一 tick 的剩余加回去。
   */
  @Test
  void tickQuotaReleaseReturnsOnlyUnusedShareWithinSameTick() {
    ProximityRevealer.TickQuota quota = new ProximityRevealer.TickQuota();
    UUID player = UUID.randomUUID();

    int granted = quota.claim(player, 10L, 16, 16);
    assertEquals(16, granted);
    quota.release(player, 10L, 16, granted, 0);
    assertEquals(16, quota.claim(player, 10L, 16, 16), "一个包都没发：撤销后恢复满额");

    granted = quota.claim(player, 11L, 16, 16);
    quota.release(player, 11L, 16, granted, 6);
    assertEquals(10, quota.claim(player, 11L, 16, 16), "已发 6 个：只退还剩下的 10 个");

    // tick 已推进：上一 tick 的撤销不得把剩余额度加回新 tick（新 tick 的领取结果必须仍是「已领满」）
    granted = quota.claim(player, 12L, 16, 16);
    assertEquals(16, granted);
    quota.release(player, 11L, 16, granted, 0);
    assertEquals(0, quota.claim(player, 12L, 16, 16),
        "跨 tick 的撤销必须被忽略：新 tick 已领满，不应因上一 tick 的撤销而多出额度");
  }

  /** max-per-tick = 0（配置关闭事件显形）时没有任何可用额度，调用方直接放弃。 */
  @Test
  void zeroMaxPerTickDisablesQuota() {
    ProximityRevealer.TickQuota quota = new ProximityRevealer.TickQuota();
    UUID player = UUID.randomUUID();
    assertEquals(0, quota.claim(player, 7L, 0, 0), "限额为 0：本 tick 没有可用额度");
  }

  /**
   * 工作池无容量时「本轮跳过」、绝不在玩家线程同步算完（周期巡检的退化路径修复）。
   *
   * <p>判定入口被抽成纯函数就是为了能离线钉住这个方向：null / 无空位 → 不得进入同步计算分支，
   * 否则服务器最忙时主线程 / 区域线程会被塞进整轮射线 CPU。
   *
   * <p>后半段驱动真实 {@code reveal()}（而不是只测谓词）：无工作池时必须走到
   * {@code skipPassBecauseNoCapacity()}（计入统计）并直接返回。若将来把「同步算完」的退化路径加回来，
   * 这里会通过 {@code revealsFrustumCulled/revealsRayCulled} 的非零或异常暴露出来。
   */
  @Test
  void planningRequiresWorkPoolWithCapacity() {
    assertFalse(ProximityRevealer.canOffloadPlanning(null),
        "未装配工作池时不得退回玩家线程同步计算");
    net.mikumc.mikuxraynet.concurrency.MikuWorkPool pool =
        new net.mikumc.mikuxraynet.concurrency.MikuWorkPool(1, 4);
    try {
      assertTrue(ProximityRevealer.canOffloadPlanning(pool), "有空位时必须走工作线程");
    } finally {
      pool.close();
    }

    ProximityStats stats = new ProximityStats();
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(100_000, 300);
    // 玩家 (0.5,64,0.5) 身边一个候选：确保 reveal 能取到候选、走到「是否能 offload」这道门闸
    index.recordChunk(WORLD, 0, 0, MIN_HEIGHT, new int[] {local(1, 64, 1)});
    ProximityRevealer revealer = new ProximityRevealer(null, config(), index,
        new RevealedSet(100_000, 300), stats, null, null, null);

    invokeReveal(revealer, playerProxy());

    assertEquals(1L, stats.passesSkippedNoCapacity.sum(),
        "无工作池时必须走「本轮跳过」分支并计数（候选仍在索引里，下个周期重试）");
    assertEquals(0L, stats.revealsSent.sum(), "跳过分支不得发包");
    assertEquals(0L, stats.revealsFrustumCulled.sum(),
        "不得在玩家线程 / 测试线程同步计算候选筛选（视锥剔除计数必须保持 0）");
    assertEquals(0L, stats.revealsRayCulled.sum(),
        "不得在玩家线程 / 测试线程同步计算候选筛选（射线剔除计数必须保持 0）");
  }

  /**
   * 单周期实际发包上限 = min(配置, 候选窗口)：默认 512 必须完整生效（旧实现的 256 评估上限会把它
   * 静默砍半），配置超过窗口时按窗口钳制（启动时另有一次 WARN，绝不静默改配置）。
   */
  @Test
  void revealCeilingIsCandidateWindowAndNeverTruncatesConfiguredValueBelowIt() {
    assertEquals(ProximityRevealer.MAX_CANDIDATES,
        ProximityRevealer.effectiveRevealCeiling(ProximityRevealer.MAX_CANDIDATES),
        "配置等于窗口时完整生效");
    assertEquals(1, ProximityRevealer.effectiveRevealCeiling(1), "下限仍为 1");
    assertEquals(ProximityRevealer.MAX_CANDIDATES,
        ProximityRevealer.effectiveRevealCeiling(100_000),
        "超过窗口时钳制到窗口（不为一个过大的配置放大取候选/评估量）");
    assertEquals(256, ProximityRevealer.effectiveRevealCeiling(256),
        "≤ 窗口的配置值必须原样生效（不得再被 256 的旧评估上限截断）");
  }

  /** 回显记号的周期清理只删「已过窗口」的项：仍在窗口内的有效记号必须保留（否则在途显形包被误判为真实变更）。 */
  @Test
  void pruneExpiredEchoesKeepsMarksStillInsideTheWindow() {
    java.util.concurrent.ConcurrentHashMap<ProximityRevealer.EchoKey, Long> marks =
        new java.util.concurrent.ConcurrentHashMap<>();
    marks.put(new ProximityRevealer.EchoKey(WORLD, 1, 64, 1), 100L);
    marks.put(new ProximityRevealer.EchoKey(WORLD, 2, 64, 1), 300L);

    ProximityRevealer.pruneExpiredEchoes(marks, 200L);

    assertEquals(1, marks.size(), "只删过期项");
    assertTrue(marks.containsKey(new ProximityRevealer.EchoKey(WORLD, 2, 64, 1)),
        "仍在窗口内的有效记号必须保留");
    assertFalse(marks.containsKey(new ProximityRevealer.EchoKey(WORLD, 1, 64, 1)),
        "已过窗口的记号必须被清掉（否则会长期滞留）");
  }

  /**
   * 入口门闸<b>只排除黑名单世界、不读反矿透总开关</b>（第 10 轮 P1 修复）。
   *
   * <p>总开关关闭后索引与巡检仍要保留：服务端不会重发已加载的区块，客户端手里仍是伪装后的假矿，
   * 只有邻近显形能把它们还原，否则玩家要等区块重发才看得到真方块（「该显形不显形」是硬红线）。
   * 候选本就只来自「我们写过伪装索引」的区块，总开关关闭的世界里索引为空，天然零发包。
   *
   * <p>三条入口（周期巡检 / 事件逐坐标 / 事件 section）共用 {@link ProximityRevealer#shouldRevealInWorld}；
   * 本类另用离线 Player/World 替身驱动了 reveal() 的「无工作池跳过」分支（见
   * {@link #planningRequiresWorkPoolWithCapacity}），此处只针对共用门闸做断言
   * （判定为假即该路径在入口直接 return、不发包）。
   */
  @Test
  void revealGateExcludesOnlyBlacklistedWorldsNotTheAntiXraySwitch() {
    assertTrue(revealer().shouldRevealInWorld(WORLD), "默认（未拉黑）世界：允许显形");

    // 总开关关闭（proximity 段仍启用）：门闸不得因此拒绝——它已不再读 config.antiXrayAppliesTo()
    ProximityRevealer antiXrayOff = new ProximityRevealer(null,
        AntiXrayConfig.from(yaml("enabled: false\n")), new ObfuscatedChunkIndex(1, 300),
        new RevealedSet(1024, 300), new ProximityStats(), null, null, null);
    assertTrue(antiXrayOff.shouldRevealInWorld(WORLD),
        "反矿透总开关关闭时仍须允许显形：已下发的假矿要靠邻近显形还原");

    // 黑名单世界一律不显形；名单是进程级静态状态，用例结束必须复位（与 WorldBlacklistTest 同口径）
    try {
      WorldBlacklistConfig.publish(WorldBlacklistConfig.fromPatterns(List.of(WORLD)));
      assertFalse(antiXrayOff.shouldRevealInWorld(WORLD), "黑名单世界必须不显形");
      assertTrue(antiXrayOff.shouldRevealInWorld("world_nether"), "名单外的其它世界不受影响");
    } finally {
      WorldBlacklistConfig.publish(WorldBlacklistConfig.fromPatterns(List.of()));
    }
    assertTrue(antiXrayOff.shouldRevealInWorld(WORLD), "名单复位后重新允许显形");
    assertFalse(antiXrayOff.shouldRevealInWorld(null), "世界名未知时保守不显形");
  }

  /**
   * <b>重算：真实评估循环必须评估全部候选</b>（第 10 轮 P2 补测）。
   *
   * <p>为什么必须驱动真实方法：{@link #revealCeilingIsCandidateWindowAndNeverTruncatesConfiguredValueBelowIt}
   * 只断言了纯函数 {@code effectiveRevealCeiling}；而「评估预算 = 取到的候选数（去掉旧 256 上限）」
   * 的真实实现在 {@code ProximityRevealer#computePlan}。若把那里的循环改回
   * {@code limit = Math.min(count, 256)}，只测纯函数的用例仍然全绿，真机却会静默砍半
   * {@code max-reveals-per-tick}（窗口后一半候选取出即丢、玩家显形延迟/漏显）。
   *
   * <p>驱动方式：用本类已有的离线 {@code revealer()} 构造（frustum / raycast 都关闭，
   * {@code computePlan} 只做「逐条拷贝」）＋反射调用 private {@code computePlan}；
   * 断言计划条数恒等于候选条数，因此任何评估上限的回归都会在此判红。
   */
  @Test
  void computePlanEvaluatesEveryCandidateUpToTheCandidateWindow() throws Exception {
    ProximityRevealer revealer = planRevealer();

    assertEquals(ProximityRevealer.MAX_CANDIDATES,
        planCount(revealer, candidates(ProximityRevealer.MAX_CANDIDATES)),
        "512 条候选必须全部评估（旧的 256 评估上限不得回来）");
    assertEquals(300, planCount(revealer, candidates(300)), "300 条候选必须全部评估");
    assertEquals(255, planCount(revealer, candidates(255)),
        "低于旧上限的候选数必须原样保留");
    assertEquals(1, planCount(revealer, candidates(1)), "单候选也不得被过滤掉");
  }

  // ---------------------------------------------------------- computePlan 反射驱动

  private static final Method COMPUTE_PLAN = computePlanMethod();
  private static final Method PLAN_COUNT = planCountMethod();

  private static Method computePlanMethod() {
    try {
      Method method = ProximityRevealer.class.getDeclaredMethod("computePlan",
          ProximitySelector.Eye.class, int[].class, String.class, boolean.class);
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(
          "无法定位 ProximityRevealer#computePlan（签名已变？）：" + failure);
    }
  }

  private static Method planCountMethod() {
    try {
      Method method = Class.forName("net.mikumc.mikuxraynet.antixray.ProximityRevealer$Plan")
          .getDeclaredMethod("count");
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(
          "无法定位 ProximityRevealer.Plan#count（结构已变？）：" + failure);
    }
  }

  /** frustum / raycast 均关闭的显形器：computePlan 只做逐条拷贝，条数差异只可能来自评估上限。 */
  private static ProximityRevealer planRevealer() {
    // 与文件内其它用例同风格：单行字面量加转义换行拼接，不使用跨行普通字符串。
    AntiXrayConfig planConfig = AntiXrayConfig.from(yaml("enabled: true\n"
        + "proximity:\n"
        + "  frustum:\n"
        + "    enabled: false\n"
        + "  raycast:\n"
        + "    enabled: false\n"));
    return new ProximityRevealer(null, planConfig, new ObfuscatedChunkIndex(1_000_000, 300),
        new RevealedSet(1_000_000, 300), new ProximityStats(), null, null, null);
  }

  /** 构造 {@code count} 条互不相同的候选（值本身无意义，computePlan 在本用例里不做任何过滤）。 */
  private static int[] candidates(int count) {
    int[] coordinates = new int[count * 3];
    for (int i = 0; i < count; i++) {
      coordinates[i * 3] = i;
      coordinates[i * 3 + 1] = 64;
      coordinates[i * 3 + 2] = 0;
    }
    return coordinates;
  }

  private static int planCount(ProximityRevealer revealer, int[] coordinates) {
    try {
      Object plan = COMPUTE_PLAN.invoke(revealer,
          ProximitySelector.eye(0.5D, 65.62D, 0.5D, 0.0D, 0.0D, 1.0D), coordinates, WORLD, false);
      return (Integer) PLAN_COUNT.invoke(plan);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("驱动 ProximityRevealer#computePlan 失败：" + failure, failure);
    }
  }

  private static void invokeReveal(ProximityRevealer revealer, Player player) {
    try {
      Method reveal = ProximityRevealer.class.getDeclaredMethod("reveal", Player.class,
          ProximityRevealer.Budget.class, int.class);
      reveal.setAccessible(true);
      reveal.invoke(revealer, player, new ProximityRevealer.Budget(64), 0);
    } catch (ReflectiveOperationException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      throw new AssertionError("无工作池时 reveal 必须直接跳过，绝不同步计算：" + cause, cause);
    }
  }

  /** 离线 Player 替身：只打桩 reveal 在「取候选 → 判定门闸」这一段真正会读的方法。 */
  private static Player playerProxy() {
    World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
        new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> WORLD;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "InstantRevealStub-World";
          default -> defaultValue(method.getReturnType());
        });
    Location location = new Location(world, 0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
    Location eye = new Location(world, 0.5D, 65.62D, 0.5D, 0.0F, 0.0F);
    UUID playerId = UUID.randomUUID();
    return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
        new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> playerId;
          case "isOnline" -> true;
          case "getWorld" -> world;
          case "getLocation" -> location;
          case "getEyeLocation" -> eye;
          case "getX" -> location.getX();
          case "getY" -> location.getY();
          case "getZ" -> location.getZ();
          case "getYaw" -> location.getYaw();
          case "getPitch" -> location.getPitch();
          case "getEyeHeight" -> 1.62D;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "InstantRevealStub-Player";
          default -> defaultValue(method.getReturnType());
        });
  }

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

  /** 区块内局部坐标打包（section 内序号）：{@code y<<8 | z<<4 | x} 的绝对 Y 版本。 */
  private static int local(int x, int y, int z) {
    return ((y - MIN_HEIGHT) << 8) | ((z & 15) << 4) | (x & 15);
  }
}
