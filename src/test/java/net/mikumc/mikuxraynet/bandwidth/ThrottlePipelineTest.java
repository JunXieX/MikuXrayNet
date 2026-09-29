package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    // 行为开关关闭等同于该模块不注册（避免注册一个「什么都不做」的空壳，仍要付出监听器开销）
    assertFalse(ThrottlePipeline.plan(
        config("entity-packets:\n  skip-zero-movement: false\n")).entityPackets());
    assertFalse(ThrottlePipeline.plan(config("block-changes:\n  merge: false\n")).blockChanges());
    assertFalse(ThrottlePipeline.plan(config("entity-culling:\n  raycast: false\n")).entityCulling());
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
}