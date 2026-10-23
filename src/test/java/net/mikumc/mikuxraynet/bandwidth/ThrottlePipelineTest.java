package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * 带宽管线「关闭即不注册」的行为回归。
 *
 * <p>{@link ThrottlePipeline#plan(BandwidthConfig)} 是装配决策的纯函数（不依赖 Bukkit 平台初始化），
 * {@code start()} 完全按它的结果决定是否注册各子模块。因此在这里断言：任一模块的 {@code enabled=false}
 * （或行为开关关闭、总开关关闭）时，对应模块的注册位必为 {@code false}——即该模块**根本不注册**，
 * 不建监听器、不起周期任务、不产生任何开销。
 */
class ThrottlePipelineTest {

  private static BandwidthConfig config(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return BandwidthConfig.from(configuration);
  }

  @Test
  void defaultsRegisterEveryModule() {
    ThrottlePipeline.ModulePlan plan = ThrottlePipeline.plan(config("enabled: true\n"));

    assertTrue(plan.entityPackets(), "默认应注册零位移实体包取消");
    assertTrue(plan.blockChanges(), "默认应注册方块变更合并");
    assertTrue(plan.entityCulling(), "默认应注册实体射线剔除");
    assertTrue(plan.afk(), "默认应注册 AFK 降级");
    assertTrue(plan.latency(), "默认应注册高延迟降视距");
  }

  /** 实体剔除的注册决策由「射线遮挡 / 视锥剔除」两个子项共同决定：任一开启就必须注册，两者都关才不注册。 */
  @Test
  void entityCullingRegistersWhenEitherSubSwitchIsOn() {
    assertTrue(ThrottlePipeline.plan(config("entity-culling:\n  raycast: false\n")).entityCulling(),
        "只开视锥剔除时仍必须注册（否则视锥剔除永远不会生效）");
    assertFalse(ThrottlePipeline.plan(
        config("entity-culling:\n  raycast: false\n  frustum:\n    enabled: false\n"))
        .entityCulling(), "两个子项全关时本模块必须完全不注册");
  }

  @Test
  void afkDisabledIsNotRegistered() {
    ThrottlePipeline.ModulePlan plan = ThrottlePipeline.plan(config("afk:\n  enabled: false\n"));

    assertFalse(plan.afk(), "afk.enabled=false 时 AFK 模块必须完全不注册（而非跑了不生效）");
    assertTrue(plan.latency(), "关闭 AFK 不得影响其它模块");
  }

  @Test
  void latencyDisabledIsNotRegistered() {
    ThrottlePipeline.ModulePlan plan = ThrottlePipeline.plan(config("latency:\n  enabled: false\n"));

    assertFalse(plan.latency(), "latency.enabled=false 时高延迟降视距必须完全不注册");
    assertTrue(plan.afk(), "关闭延迟降视距不得影响其它模块");
  }

  @Test
  void eachModuleEnabledFalseSkipsItsOwnRegistrationOnly() {
    assertFalse(ThrottlePipeline.plan(config("entity-packets:\n  enabled: false\n")).entityPackets());
    assertFalse(ThrottlePipeline.plan(config("block-changes:\n  enabled: false\n")).blockChanges());
    assertFalse(ThrottlePipeline.plan(config("entity-culling:\n  enabled: false\n")).entityCulling());

    // 每个用例都只关一个模块，其余仍应注册（互相独立）
    ThrottlePipeline.ModulePlan onlyAfkOff = ThrottlePipeline.plan(config("afk:\n  enabled: false\n"));
    assertTrue(onlyAfkOff.entityPackets() && onlyAfkOff.blockChanges()
        && onlyAfkOff.entityCulling() && onlyAfkOff.latency());
  }

  @Test
  void masterSwitchOffRegistersNothing() {
    ThrottlePipeline.ModulePlan plan = ThrottlePipeline.plan(config("enabled: false\n"));

    assertFalse(plan.entityPackets(), "总开关关闭时不注册任何子模块");
    assertFalse(plan.blockChanges());
    assertFalse(plan.entityCulling());
    assertFalse(plan.afk());
    assertFalse(plan.latency());
  }

  @Test
  void behaviorSwitchOffAlsoSkipsRegistration() {
    // 行为开关关闭等同于该模块不注册（避免注册一个「什么都不做」的空壳，仍要付出监听器开销）。
    // 实体剔除有两个行为子项：只关 raycast 时仍会做视锥剔除，故必须两个都关才算「不注册」。
    assertFalse(ThrottlePipeline.plan(
        config("entity-packets:\n  skip-zero-movement: false\n")).entityPackets());
    assertFalse(ThrottlePipeline.plan(config("block-changes:\n  merge: false\n")).blockChanges());
    assertFalse(ThrottlePipeline.plan(
        config("entity-culling:\n  raycast: false\n  frustum:\n    enabled: false\n"))
        .entityCulling());
  }

  /**
   * B3 口径回归：Folia 上白名单索引建不起来（无法跨区域安全枚举实体），因此「白名单非空」时零位移
   * 取消必须<b>整项不注册</b>——而不是构造一个在 {@code start()} 里自我停用的空壳（那会让启动日志与
   * 诊断显示为「已启用」，与真实注册状态矛盾）。白名单为空时不需要索引，Folia 上仍可注册。
   */
  @Test
  void foliaWithNonEmptyWhitelistDoesNotRegisterZeroMovementCancel() {
    String withWhitelist = "entity-packets:\n  whitelist:\n    - armor_stand\n";

    assertFalse(ThrottlePipeline.plan(config(withWhitelist), true).entityPackets(),
        "Folia + 非空白名单：白名单索引不可用 → 整项不注册（否则盔甲架/展示实体姿态会卡住）");
    assertTrue(ThrottlePipeline.plan(config(withWhitelist), false).entityPackets(),
        "Paper 上照常注册（白名单索引可用）");
    assertTrue(ThrottlePipeline.plan(config("enabled: true\n"), true).entityPackets(),
        "Folia + 空名单不需要索引 → 仍可注册（所有实体按非白名单处理）");
    assertTrue(ThrottlePipeline.plan(config(withWhitelist), true).blockChanges(),
        "平台差异只影响零位移取消，其它模块照常注册");
  }

  /**
   * 显式覆盖「Folia + 白名单留空 → 零位移取消照常注册」：本条是用户可自行切换的行为开关——
   * 把 {@code entity-packets.whitelist} 清空即以「所有实体都非白名单」的语义启用零位移取消
   * （代价见 {@code EntityPacketFilter} 类注释：onGround 布尔会被一并丢弃，属低风险观感问题）。
   */
  @Test
  void foliaWithEmptyWhitelistRegistersZeroMovementCancel() {
    ThrottlePipeline.ModulePlan empty = ThrottlePipeline.plan(
        config("entity-packets:\n  enabled: true\n  skip-zero-movement: true\n  whitelist: []\n"),
        true);
    assertTrue(empty.entityPackets(),
        "Folia + 空白名单：不需要实体类型索引，零位移取消必须注册");
    assertFalse(ThrottlePipeline.plan(
        config("entity-packets:\n  skip-zero-movement: false\n"), true).entityPackets(),
        "skip-zero-movement=false 时不注册（行为开关优先）");
  }

  /**
   * 回归：「已在运行」的判定必须是 {@code startLocked()} 的<b>第一条</b>语句（先于
   * {@code cleanupIncomplete = false} 与 {@code retryFailedCloses()}）。
   *
   * <p>为什么这条顺序如此重要：紧随判定之后的 {@code retryFailedCloses()} 会注销字段里的模块——那是为
   * 「上一次 stop() 注销失败、刻意保留的句柄」准备的。一旦把重试排在运行态判定之前，重复 start() 就会把
   * <b>当前正在运行</b>的模块全部关掉，随后又因 started 已为 true 而返回，留下「started=true 但模块全被
   * 注销」的半死状态：监听器没了，诊断却仍报已启用，玩家侧的实体剔除/省包全部静默失效。
   *
   * <p>本测试把 {@code started} 与 {@code cleanupIncomplete} 都预置为 true 再连续 start() 两次，
   * 用三个可观测断言钉住「判定在最前且完全没触碰状态」：① {@code cleanupIncomplete} 必须保持 true
   * （判定先于复位）；② 五个模块字段必须保持 null（没有注册、也没有被 retryFailedCloses 注销）；
   * ③ 日志只出现两次「已处于运行状态」的短路消息、零 WARNING（判定若被挪到依赖探测之后会打出
   * 「未检测到 ProtocolLib」等 WARNING）。
   */
  @Test
  void startReturnsImmediatelyWhenAlreadyRunning() throws Exception {
    RecordingLogger logger = new RecordingLogger();
    ThrottlePipeline pipeline = new ThrottlePipeline(pluginStub(logger), config("enabled: true\n"));
    setStarted(pipeline, true);
    setCleanupIncomplete(pipeline, true);

    pipeline.start();
    pipeline.start();

    assertTrue(started(pipeline), "已在运行时 started 必须保持 true（不得被重复 start 改写）");
    assertTrue(cleanupIncomplete(pipeline),
        "判定必须在 cleanupIncomplete 复位之前：任何状态都没被触碰");
    assertNoModulesRegistered(pipeline);
    assertEquals(2L, logger.countContaining("已处于运行状态"),
        "两次 start() 都必须命中「已运行」短路分支（日志：" + logger.records() + "）");
    assertEquals(0, logger.warningCount(),
        "已在运行时不得进入依赖探测/装配段（否则会出现 WARNING）：" + logger.records());
  }

  /**
   * {@code stop()} 可重复调用且不得留下半清理状态：首次 stop() 复位 started、保持
   * {@code cleanupIncomplete=false}；第二次 stop() 是纯 no-op（不抛异常、不产生 WARNING）。
   *
   * <p>用「总开关关闭」的配置：{@code start()} 在触碰任何 Bukkit API 之前返回，因此离线可跑；
   * 同时断言 {@link ThrottlePipeline#plan(BandwidthConfig)} 全为 false 时五个模块字段必须全为 null
   * ——这正是「关闭即不注册」的端到端口径（plan 与注册状态一致）。
   */
  @Test
  void stopIsIdempotent() throws Exception {
    RecordingLogger logger = new RecordingLogger();
    ThrottlePipeline pipeline = new ThrottlePipeline(pluginStub(logger), config("enabled: false\n"));

    ThrottlePipeline.ModulePlan plan = ThrottlePipeline.plan(config("enabled: false\n"));
    assertFalse(plan.entityPackets(), "总开关关闭：plan 必须不注册零位移实体包取消");
    assertFalse(plan.blockChanges(), "总开关关闭：plan 必须不注册方块变更合并");
    assertFalse(plan.entityCulling(), "总开关关闭：plan 必须不注册实体剔除");
    assertFalse(plan.afk(), "总开关关闭：plan 必须不注册 AFK 降级");
    assertFalse(plan.latency(), "总开关关闭：plan 必须不注册高延迟降视距");

    pipeline.start();
    pipeline.start();
    assertNoModulesRegistered(pipeline); // plan 全 false 与「字段全 null」一致

    pipeline.stop();
    assertFalse(started(pipeline), "第一次 stop() 必须复位 started");
    assertNoModulesRegistered(pipeline); // 没有模块可注销 → 字段仍全 null
    assertFalse(cleanupIncomplete(pipeline), "没有模块可注销时不得标记「清理不完整」");

    pipeline.stop();
    assertFalse(started(pipeline), "重复 stop() 后 started 仍必须为 false");
    assertNoModulesRegistered(pipeline);
    assertFalse(cleanupIncomplete(pipeline), "重复 stop() 必须是纯 no-op，不得产生清理失败标记");
    assertEquals(0, logger.warningCount(),
        "重复 stop() 不得产生 WARNING：" + logger.records());
  }

  // ---------------------------------------------------------- 反射观测 / 替身

  private static final String[] MODULE_FIELDS = {
      "entityPacketFilter", "blockChangeMerger", "entityCuller", "afkTracker", "latencyMonitor"};

  private static Object field(ThrottlePipeline pipeline, String name) throws Exception {
    java.lang.reflect.Field field = ThrottlePipeline.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(pipeline);
  }

  private static boolean started(ThrottlePipeline pipeline) throws Exception {
    return ((AtomicBoolean) field(pipeline, "started")).get();
  }

  private static void setStarted(ThrottlePipeline pipeline, boolean value) throws Exception {
    ((AtomicBoolean) field(pipeline, "started")).set(value);
  }

  private static void setCleanupIncomplete(ThrottlePipeline pipeline, boolean value) throws Exception {
    java.lang.reflect.Field field = ThrottlePipeline.class.getDeclaredField("cleanupIncomplete");
    field.setAccessible(true);
    field.setBoolean(pipeline, value);
  }

  private static boolean cleanupIncomplete(ThrottlePipeline pipeline) throws Exception {
    java.lang.reflect.Field field = ThrottlePipeline.class.getDeclaredField("cleanupIncomplete");
    field.setAccessible(true);
    return field.getBoolean(pipeline);
  }

  /** 五个模块字段必须全为 null：既没注册，也没被 retryFailedCloses 注销成「半死」状态。 */
  private static void assertNoModulesRegistered(ThrottlePipeline pipeline) throws Exception {
    for (String name : MODULE_FIELDS) {
      assertNull(field(pipeline, name), "模块字段 " + name + " 必须为 null（未注册/未残留）");
    }
  }

  /** 只记录、不外发的 Logger：用于断言 start()/stop() 实际走了哪条分支（短路消息、WARNING 数）。 */
  private static final class RecordingLogger extends Logger {

    private final List<String> records = new ArrayList<>();

    RecordingLogger() {
      super("ThrottlePipelineTest", null);
      setLevel(Level.ALL);
      setUseParentHandlers(false);
      // 用 Handler 捕获而不是重写 log(LogRecord)：FINE 级消息由 Logger.doLog 直接分发给 Handler，
      // 重写 log(LogRecord) 在部分 JDK 上捕不到；Handler 是 JDK 保证的最终分发点。
      Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
          records.add(record.getLevel().getName() + "|" + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
      };
      handler.setLevel(Level.ALL);
      addHandler(handler);
    }

    List<String> records() {
      return records;
    }

    long countContaining(String fragment) {
      return records.stream().filter(record -> record.contains(fragment)).count();
    }

    long warningCount() {
      return records.stream()
          .filter(record -> record.startsWith(Level.WARNING.getName() + "|"))
          .count();
    }
  }

  /** 仅用于 register 单测：getLogger() 之外的调用不被触发，故其余方法一律返回 null 即可。 */
  private static Plugin pluginStub() {
    return pluginStub(Logger.getLogger("ThrottlePipelineTest"));
  }

  private static Plugin pluginStub(Logger logger) {
    return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
        new Class<?>[] {Plugin.class},
        (proxy, method, args) -> method.getName().equals("getLogger") ? logger : null);
  }

  /**
   * 回归：模块 {@code start()} 抛异常时必须在返回 null 前调用该实例的 {@code stop()}。
   *
   * <p>各模块都「先注册监听/调度、后可能抛」，若字段保持为 null，之后的 {@code stop()} 便无从清理，
   * 反复 reload 会累积泄漏已注册的 Listener 与已调度的玩家任务。
   */
  @Test
  void failedModuleStartIsStoppedInsteadOfLeaking() {
    ThrottlePipeline pipeline = new ThrottlePipeline(pluginStub(), config("enabled: true\n"));
    AtomicBoolean stopped = new AtomicBoolean();
    Object module = new Object();

    Object result = pipeline.register(
        () -> module,
        m -> {
          throw new IllegalStateException("模拟 start 失败");
        },
        m -> stopped.set(true));

    assertNull(result, "start 抛异常的模块必须返回 null（单独停用该模块）");
    assertTrue(stopped.get(),
        "start 抛异常后必须对同一实例调用 stop()，撤销已注册的监听/已调度的任务，避免 reload 累积泄漏");
  }

  /**
   * 统计持有者与管线解耦的回归：热重载会「停旧管线、以新配置重建新管线」，若统计仍由管线自己持有，
   * 每次 reload 计数就会归零。注入同一个 {@link ThrottleStats} 后，重建的管线必须复用该实例、
   * 累计计数连续（{@code /mxnet status} 的字段语义不变）。
   */
  @Test
  void statsSurvivePipelineRebuildWhenInjected() {
    ThrottleStats stats = new ThrottleStats();
    ThrottlePipeline first = new ThrottlePipeline(pluginStub(), config("enabled: true\n"), stats);
    first.stats().entityPacketsCancelled.increment();
    first.stats().blockChangesMerged.add(3L);

    // 模拟 reload：停旧管线后按新配置重建，但统计持有者不变
    ThrottlePipeline rebuilt = new ThrottlePipeline(pluginStub(), config("enabled: true\n"), stats);

    assertSame(stats, rebuilt.stats(), "重建后的管线必须复用注入的统计持有者");
    assertEquals(1L, rebuilt.stats().entityPacketsCancelled.sum(), "累计计数不得随管线重建归零");
    assertEquals(3L, rebuilt.stats().blockChangesMerged.sum(), "累计计数不得随管线重建归零");

    // 不注入时保持旧行为：各自一份新统计（供单测/默认构造使用）
    ThrottlePipeline standalone = new ThrottlePipeline(pluginStub(), config("enabled: true\n"));
    assertFalse(standalone.stats() == stats, "未注入时使用独立统计实例");
  }
}