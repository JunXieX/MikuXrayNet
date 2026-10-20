package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
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
   * 回归：「已在运行」的判定必须是 {@code startLocked()} 的<b>第一条</b>语句。
   *
   * <p>为什么这条顺序如此重要：紧跟在判定之后的是 {@code retryFailedCloses()}——它会注销字段里的模块
   * （那是为「上一次 stop() 注销失败、刻意保留的句柄」准备的）。一旦把重试排在运行态判定之前，
   * 重复 start() 就会把<b>当前正在运行</b>的模块全部关掉，随后又因 started 已为 true 而返回，
   * 留下「started=true 但模块全被注销」的半死状态：监听器没了，诊断却仍报已启用，玩家侧的
   * 实体剔除/省包全部静默失效。
   *
   * <p>本测试用反射把 {@code started} 预置为 true 再调 {@code start()}：若判定确实在最前，方法会立刻返回、
   * 完全不触碰 Bukkit（离线环境可用）；若判定被挪到后面，执行流就会走到依赖 Bukkit 的装配段并抛
   * {@code NullPointerException}（离线无服务端实例），测试随即失败——正是我们要钉住的回归。
   */
  @Test
  void startReturnsImmediatelyWhenAlreadyRunning() throws Exception {
    ThrottlePipeline pipeline = new ThrottlePipeline(pluginStub(), config("enabled: true\n"));
    java.lang.reflect.Field started = ThrottlePipeline.class.getDeclaredField("started");
    started.setAccessible(true);
    ((AtomicBoolean) started.get(pipeline)).set(true);

    // 已在运行：必须立刻返回，绝不进入任何需要 Bukkit 的装配路径
    pipeline.start();
  }

  /** {@code stop()} 可重复调用：首次清理后字段已置空，再次调用必须是纯 no-op（不抛异常）。 */
  @Test
  void stopIsIdempotent() {
    ThrottlePipeline pipeline = new ThrottlePipeline(pluginStub(), config("enabled: false\n"));
    // 总开关关闭时 start() 在触碰 Bukkit 之前就返回（离线可跑），stop() 自然也没有模块可清理
    pipeline.start();
    pipeline.start();
    pipeline.stop();
    pipeline.stop();
  }

  /** 仅用于 register 单测：getLogger() 之外的调用不被触发，故其余方法一律返回 null 即可。 */
  private static Plugin pluginStub() {
    Logger logger = Logger.getLogger("ThrottlePipelineTest");
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