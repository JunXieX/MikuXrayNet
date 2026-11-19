package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * 「复活 / 切维后必须重排玩家周期任务」的专项回归（P1）。
 *
 * <p><b>缺陷</b>：{@code EntityCuller} 与 {@code LatencyMonitor} 只在 {@code start()} / {@code onJoin} 登记
 * 一条 {@code EntityScheduler#runAtFixedRate}；Folia 上玩家复活（或移出旧 world）会退役该实体上的周期任务，
 * 而这两个类既没有退役回调、也没有任何重排入口。后果是：实体剔除对该玩家<b>彻底停摆</b>（通道①②都不再
 * 跑，复活前已隐藏的实体此后没有任何恢复入口 → 「该显示却仍隐藏」），高延迟降视距同理停摆且已降的视距
 * 不再还原。
 *
 * <p><b>为什么这些约束必须钉在用例里</b>：真实退役由 Folia 运行时驱动，离线造不出「实体被移除」；但
 * 「重排是否幂等、退役回调是否只摘自己那条、停用/退出是否仍收口」这三件事完全由本类的账本逻辑决定，
 * 可以用 {@link EntityScheduler} / {@link ScheduledTask} 的动态代理逐条驱动。因此这里用替身覆盖：
 * <ol>
 *   <li>同 tick 连发「复活完成 + 切维」两个事件 → <b>恰好一条</b>存活注册（幂等）；</li>
 *   <li>旧注册的退役回调迟到 → <b>不得</b>摘掉重建后的新注册（按注册身份精确 fencing）；</li>
 *   <li>退役回调先于入账到达 → 不留死句柄（入账后再读一次 volatile 的收口）；</li>
 *   <li>玩家换了调度器 → 判为不存活并重排，且旧句柄被取消（不会两条并行）；</li>
 *   <li>停用中 → 处理器拒绝登记，登记入口自身也会撤销一条刚入账的注册；</li>
 *   <li>退出 → 摘注册并取消句柄。</li>
 * </ol>
 *
 * <p>Bukkit 运行时在离线测试环境不可用，因此全部以 {@link Proxy} 动态代理替身（与 {@code EntityCullerTest} /
 * {@code LatencyMonitorTest} 同一风格，不引入任何新依赖）。
 */
class PlayerMonitorRescheduleTest {

  private static final Logger LOGGER = Logger.getLogger("PlayerMonitorRescheduleTest");

  /** 周期任务的替身：只关心「是否被取消」。 */
  private static final class TaskStub implements InvocationHandler {

    private final ScheduledTask proxy;
    private volatile boolean cancelled;

    private TaskStub() {
      this.proxy = (ScheduledTask) Proxy.newProxyInstance(ScheduledTask.class.getClassLoader(),
          new Class<?>[] {ScheduledTask.class}, this);
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      return switch (method.getName()) {
        case "isCancelled" -> cancelled;
        case "cancel" -> {
          cancelled = true;
          // cancel 的返回值生产代码不使用（ScheduledTask#cancel 的 CancelledState）
          yield (Object) null;
        }
        case "toString" -> "stub-task";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  /**
   * 实体调度器替身：记录登记次数与退役回调，并可按需模拟两种「登记瞬间实体已不可用」的形态。
   *
   * <p>两种形态的区别正是本轮修复的关键：{@code runAtFixedRate} 返回 {@code null} 表示实体已被移除、两个
   * 回调都不会被调用；而「先跑退役回调、再返回句柄」模拟退役回调先于调用方入账到达（调用方随后必须自行
   * 撤销这条死注册）。
   */
  private static final class SchedulerStub implements InvocationHandler {

    private final EntityScheduler proxy;
    private final List<TaskStub> tasks = new ArrayList<>();
    private final List<Runnable> retiredCallbacks = new ArrayList<>();
    private int schedules;
    /** 模拟「实体此刻已被移除」：调用退役回调后返回 null。 */
    private boolean removedAtSchedule;
    /** 模拟「退役回调先于入账到达」：调用退役回调后仍返回句柄。 */
    private boolean retireBeforeEnroll;

    private SchedulerStub() {
      this.proxy = (EntityScheduler) Proxy.newProxyInstance(EntityScheduler.class.getClassLoader(),
          new Class<?>[] {EntityScheduler.class}, this);
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if ("runAtFixedRate".equals(method.getName())) {
        schedules++;
        Runnable retired = args.length > 2 ? (Runnable) args[2] : null;
        if (retired != null) {
          retiredCallbacks.add(retired);
        }
        TaskStub task = new TaskStub();
        tasks.add(task);
        if (removedAtSchedule) {
          if (retired != null) {
            retired.run();
          }
          return null;
        }
        if (retireBeforeEnroll && retired != null) {
          retired.run();
        }
        return task.proxy;
      }
      return switch (method.getName()) {
        case "toString" -> "stub-scheduler";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  /** 玩家替身：只打桩两个被读的方法（uuid / 调度器），调度器可在用例中切换以模拟实体换包装。 */
  private static final class PlayerStub implements InvocationHandler {

    private final UUID id = UUID.randomUUID();
    private final Player proxy;
    private volatile EntityScheduler scheduler;

    private PlayerStub(SchedulerStub scheduler) {
      this.scheduler = scheduler.proxy;
      this.proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
          new Class<?>[] {Player.class}, this);
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      return switch (method.getName()) {
        case "getUniqueId" -> id;
        case "getScheduler" -> scheduler;
        case "isOnline" -> true;
        case "toString" -> "stub-player";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  private static Plugin stubPlugin() {
    return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
        new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getLogger" -> LOGGER;
          case "getName" -> "PlayerMonitorRescheduleTest";
          case "toString" -> "stub-plugin";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> defaultValue(method.getReturnType());
        });
  }

  /** 被测对象与「如何驱动它」的一套绑定（两个类的公开处理器签名不同，用 λ 抹平）。 */
  private record MonitorUnderTest(Object instance, String mapField, String scheduleMethod,
      Consumer<Player> postRespawn, Consumer<Player> changedWorld, Consumer<Player> quit) {

    String name() {
      return instance.getClass().getSimpleName();
    }
  }

  /** 两个监控模块各一份；配置一律开射线剔除（{@code active()} 为真）与 5 秒巡检。 */
  private static List<MonitorUnderTest> monitors() {
    Plugin plugin = stubPlugin();
    EntityCuller culler = new EntityCuller(plugin,
        new BandwidthConfig.EntityCulling(true, true, 32.0D, 10, 7,
            new BandwidthConfig.EntityCulling.Frustum(false, 110.0D, 24.0D)),
        new ThrottleStats());
    LatencyMonitor latency = new LatencyMonitor(plugin,
        new BandwidthConfig.Latency(true, 400, 1, 30, 6, 5), new ThrottleStats());
    return List.of(
        new MonitorUnderTest(culler, "recheckTasks", "scheduleRecheck",
            player -> culler.onPostRespawn(postRespawn(player)),
            player -> culler.onChangedWorld(changedWorld(player)),
            player -> culler.onQuit(quit(player))),
        new MonitorUnderTest(latency, "checkTasks", "scheduleCheck",
            player -> latency.onPostRespawn(postRespawn(player)),
            player -> latency.onChangedWorld(changedWorld(player)),
            player -> latency.onQuit(quit(player))));
  }

  private static PlayerPostRespawnEvent postRespawn(Player player) {
    // 复活原因取枚举首项（DEATH）：不写死常量名，避免上游重命名时整份用例编译失败
    return new PlayerPostRespawnEvent(player, new Location(null, 0.5D, 64.0D, 0.5D),
        false, false, false, PlayerRespawnEvent.RespawnReason.values()[0]);
  }

  private static PlayerChangedWorldEvent changedWorld(Player player) {
    World from = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
        new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> "from-world";
          case "toString" -> "stub-world";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> defaultValue(method.getReturnType());
        });
    return new PlayerChangedWorldEvent(player, from);
  }

  private static PlayerQuitEvent quit(Player player) {
    return new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
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
    if (type == long.class) {
      return 0L;
    }
    if (type == double.class) {
      return 0.0D;
    }
    if (type == float.class) {
      return 0.0F;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    return 0;
  }

  // ------------------------------------------------------------------ 反射入口

  /** 当前登记表里的条目数（= 该玩家是否还有一条「自认为存活」的注册）。 */
  private static int live(MonitorUnderTest monitor) throws Exception {
    Field field = monitor.instance().getClass().getDeclaredField(monitor.mapField());
    field.setAccessible(true);
    return ((Map<?, ?>) field.get(monitor.instance())).size();
  }

  /** 置位停用闸（离线不可构造完整 stop() 链路：它要遍历在线玩家并还原实体/视距）。 */
  private static void setStopping(MonitorUnderTest monitor) throws Exception {
    Field field = monitor.instance().getClass().getDeclaredField("stopping");
    field.setAccessible(true);
    field.setBoolean(monitor.instance(), true);
  }

  /** 直接调私有登记入口（绕过处理器，用来覆盖「入账后再读一次 volatile」这段收口）。 */
  private static boolean invokeSchedule(MonitorUnderTest monitor, Player player, long period)
      throws Exception {
    Method method = monitor.instance().getClass()
        .getDeclaredMethod(monitor.scheduleMethod(), Player.class, long.class);
    method.setAccessible(true);
    return (Boolean) method.invoke(monitor.instance(), player, period);
  }

  // ------------------------------------------------------------------ 用例

  /**
   * 同 tick 连发「复活完成 + 切维」（跨世界复活就是这两个事件）→ 必须收敛到恰好一条存活注册。
   *
   * <p>旧实现只有 {@code onJoin} 一条登记入口：复活后任务随实体退役，此后该玩家的实体剔除/降视距
   * 彻底停摆。这里同时钉住「有重排」与「重排幂等」——否则两个事件各排一条，同一玩家会有两条复检并行。
   */
  @Test
  void postRespawnAndWorldChangeInTheSameTickKeepExactlyOneRegistration() throws Exception {
    for (MonitorUnderTest monitor : monitors()) {
      SchedulerStub scheduler = new SchedulerStub();
      Player player = new PlayerStub(scheduler).proxy;

      monitor.postRespawn().accept(player);
      monitor.changedWorld().accept(player);

      assertEquals(1, scheduler.schedules, monitor.name() + "：同 tick 的两个事件只能登记一条任务");
      assertEquals(1, live(monitor), monitor.name() + "：登记表里只应有一条注册");

      monitor.postRespawn().accept(player);
      monitor.changedWorld().accept(player);
      assertEquals(1, scheduler.schedules, monitor.name() + "：已存活时再收到事件不得重复登记");
      assertEquals(1, live(monitor), monitor.name() + "：重复事件不得改变注册数");
    }
  }

  /**
   * 按「注册身份」精确 fencing：旧注册的退役回调迟到时，绝不能摘掉重建后的新注册。
   *
   * <p>裸句柄只能 {@code remove(uuid)} 盲删——旧回调晚到就会把新注册从表里摘走：新任务从此无人取消
   * （热重载/停用时 {@code stop()} 的取消循环看不到它），且同一玩家会出现两条复检并行。
   */
  @Test
  void retiredCallbackOnlyRemovesItsOwnRegistration() throws Exception {
    for (MonitorUnderTest monitor : monitors()) {
      SchedulerStub scheduler = new SchedulerStub();
      Player player = new PlayerStub(scheduler).proxy;

      monitor.postRespawn().accept(player);
      assertEquals(1, live(monitor), monitor.name() + "：前置，首次注册必须入表");
      assertEquals(1, scheduler.retiredCallbacks.size(), monitor.name() + "：登记必须带退役回调");
      Runnable firstRetired = scheduler.retiredCallbacks.get(0);

      firstRetired.run();
      assertEquals(0, live(monitor), monitor.name() + "：退役后条目必须摘除（否则永远等不到重排）");

      monitor.postRespawn().accept(player);
      assertEquals(2, scheduler.schedules, monitor.name() + "：退役后必须能重新登记");
      assertEquals(1, live(monitor), monitor.name() + "：重建后应有一条新注册");

      // 旧注册的退役回调迟到（真实序列：旧任务的 retired 在新任务登记之后才被执行）
      firstRetired.run();
      assertEquals(1, live(monitor), monitor.name() + "：迟到的旧退役绝不允许摘掉新注册");
      assertTrue(!scheduler.tasks.get(1).cancelled, monitor.name() + "：新注册的句柄不得被旧回调取消");

      monitor.postRespawn().accept(player);
      assertEquals(2, scheduler.schedules, monitor.name() + "：新注册仍存活时不得再登记第三条");
    }
  }

  /**
   * 退役回调先于入账到达（登记返回句柄、但回调已经跑过）→ 不得在表里留下一条永远不再回调的死句柄。
   *
   * <p>这是「先登记后入账」的天然窗口，靠入账后再读一次 volatile 收口；漏掉它时该玩家此后每次事件都
   * 复用一条死注册、永不重排（与缺陷本身等价）。
   */
  @Test
  void retirementBeforeEnrollmentLeavesNoDeadEntry() throws Exception {
    for (MonitorUnderTest monitor : monitors()) {
      SchedulerStub scheduler = new SchedulerStub();
      scheduler.retireBeforeEnroll = true;
      Player player = new PlayerStub(scheduler).proxy;

      monitor.postRespawn().accept(player);
      assertEquals(0, live(monitor), monitor.name() + "：已退役的注册不得留在表里");
      assertTrue(scheduler.tasks.get(0).cancelled, monitor.name() + "：刚入账就已死的句柄必须取消");

      scheduler.retireBeforeEnroll = false;
      monitor.postRespawn().accept(player);
      assertEquals(1, live(monitor), monitor.name() + "：下一次事件必须能建立一条活注册");
    }
  }

  /**
   * 玩家换了调度器（实体被替换 / 包装变化）→ 旧注册判为不存活，重排到新调度器，且旧句柄必须被取消。
   *
   * <p>只按句柄判活会让这类注册被永久复用：旧实体已经不会再回调，该玩家的巡检实际上一直没人跑。
   */
  @Test
  void changedSchedulerForcesReRegistrationAndCancelsTheOldHandle() throws Exception {
    for (MonitorUnderTest monitor : monitors()) {
      SchedulerStub first = new SchedulerStub();
      PlayerStub playerStub = new PlayerStub(first);
      Player player = playerStub.proxy;

      monitor.postRespawn().accept(player);
      assertEquals(1, first.schedules, monitor.name() + "：前置，先在第一枚调度器上登记");

      SchedulerStub second = new SchedulerStub();
      playerStub.scheduler = second.proxy;
      monitor.postRespawn().accept(player);

      assertEquals(1, second.schedules, monitor.name() + "：换调度器后必须在新调度器上重排");
      assertEquals(1, live(monitor), monitor.name() + "：重排后只应有一条注册");
      assertTrue(first.tasks.get(0).cancelled, monitor.name() + "：被替换的旧句柄必须取消（否则两条并行）");
    }
  }

  /**
   * 停用中不得登记：处理器直接拒绝；即使绕过处理器进入登记入口，也要在入账后自行撤销（停用竞态收口）。
   */
  @Test
  void stoppingRejectsRegistrationThroughBothTheHandlerAndTheSchedulerEntry() throws Exception {
    for (MonitorUnderTest monitor : monitors()) {
      SchedulerStub scheduler = new SchedulerStub();
      Player player = new PlayerStub(scheduler).proxy;
      setStopping(monitor);

      monitor.postRespawn().accept(player);
      monitor.changedWorld().accept(player);
      assertEquals(0, scheduler.schedules, monitor.name() + "：停用中处理器不得登记新任务");
      assertEquals(0, live(monitor), monitor.name() + "：停用中不得留下注册");

      assertFalse(invokeSchedule(monitor, player, 20L),
          monitor.name() + "：停用竞态下登记入口必须回报「没有存活注册」");
      assertEquals(0, live(monitor), monitor.name() + "：入账后读到 stopping 时必须自行摘除");
      assertTrue(scheduler.tasks.get(0).cancelled, monitor.name() + "：停用竞态下刚登记的句柄必须取消");
    }
  }

  /** 退出：摘掉注册并取消句柄（退出后不再需要复检/还原，句柄不能空跑到实体退役）。 */
  @Test
  void quitCancelsTheLiveRegistration() throws Exception {
    for (MonitorUnderTest monitor : monitors()) {
      SchedulerStub scheduler = new SchedulerStub();
      Player player = new PlayerStub(scheduler).proxy;

      monitor.postRespawn().accept(player);
      assertEquals(1, live(monitor), monitor.name() + "：前置");

      monitor.quit().accept(player);
      assertEquals(0, live(monitor), monitor.name() + "：退出必须摘除注册");
      assertTrue(scheduler.tasks.get(0).cancelled, monitor.name() + "：退出必须取消句柄");
    }
  }

  /**
   * 登记入口失败（实体此刻已被移除，{@code runAtFixedRate} 返回 null）→ 回报 false 且不留任何条目。
   *
   * <p>此时不重试是刻意的：调用点都是「实体必然有效」的阶段，null 只意味着玩家正在并发退出。
   */
  @Test
  void removedEntityIsReportedAsNotScheduledWithoutLeavingEntries() throws Exception {
    for (MonitorUnderTest monitor : monitors()) {
      SchedulerStub scheduler = new SchedulerStub();
      scheduler.removedAtSchedule = true;
      Player player = new PlayerStub(scheduler).proxy;

      assertFalse(invokeSchedule(monitor, player, 20L),
          monitor.name() + "：实体已被移除时必须回报「没有存活注册」");
      assertEquals(0, live(monitor), monitor.name() + "：登记失败不得留下条目");
    }
  }
}
