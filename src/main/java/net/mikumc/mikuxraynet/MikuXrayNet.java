package net.mikumc.mikuxraynet;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bandwidth.ThrottlePipeline;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.config.MikuConfig;
import net.mikumc.mikuxraynet.util.BypassRegistry;
import net.mikumc.mikuxraynet.util.Diagnostics;
import net.mikumc.mikuxraynet.util.ReloadCoordinator;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MikuXrayNet 插件主类。
 *
 * <p>本插件在 Paper 26.x 上提供两项能力：
 * <ul>
 *   <li><b>反矿透</b>：出站区块封包改写，把完全被掩埋的矿物替换为伪装方块，并在玩家靠近这些
 *       坐标时主动把真实方块发回客户端（邻近显形，见 {@code antixray.ProximityRevealer}）；</li>
 *   <li><b>带宽优化</b>：零位移包抑制、方块变更合并、实体射线剔除、AFK 降级与高延迟降视距
 *       （由 {@code bandwidth.ThrottlePipeline} 装配，可用 bandwidth.yml 分别开关）。</li>
 * </ul>
 *
 * <p><b>封包通道</b>：ProtocolLib 是唯一的拦截与改写通道（真异步扣包，工作线程在 Netty 管道之外运行）；
 * PacketEvents 只作为「库」在启动期提供方块状态映射，运行期不参与任何拦截。
 *
 * <p>装配细节见 {@code bootstrap} 包；本类只负责生命周期编排、reload 编排、world-unload 监听与
 * 带宽装配，不承载算法——反矿透侧组件与命令注册分别在 {@link AntiXrayRuntime} 与
 * {@link CommandRegistrar}（命令常量与两份插件描述的一致性由 PluginDescriptionConsistencyTest 守门）。
 */
public final class MikuXrayNet extends JavaPlugin {

  private MikuConfig config;
  private ThrottlePipeline throttlePipeline;
  private Diagnostics diagnostics;
  /** 周期运行摘要任务（bandwidth.yml: diagnostics.interval-seconds；0 = 关闭）。 */
  private ScheduledTask diagnosticsTask;
  /** 反矿透侧运行时（ProtocolLib 接入、工作池、显形结构、磁盘缓存、直通名单）；onEnable 起可用。 */
  private AntiXrayRuntime runtime;

  private final ReloadCoordinator reloadCoordinator = new ReloadCoordinator();

  @Override
  public void onLoad() {
    this.config = new MikuConfig(this);
    this.config.load();
  }

  @Override
  public void onEnable() {
    if (config == null) {
      onLoad();
    }

    // 平台判定必须最先做：调度已统一走 Paper 调度器（不再分支），但平台判定仍决定
    // 「能否安全枚举全服实体」（零位移包白名单）与「邻近显形的巡检策略」两处能力差异。
    // 依据是服务端品牌/版本标识（antixray.yml: advanced.platform 可手动指定），不再用「类存在性」，
    // 因为 Paper 及其下游分支（如 Leaf）都会自带 io.papermc.paper.threadedregions.* 的调度器 API 类。
    PlatformSupport.configure(config.antiXray().platform());
    getLogger().info("运行平台：" + PlatformSupport.platformDescription());
    this.runtime = new AntiXrayRuntime(this);
    runtime.startBypassRegistry();
    runtime.startAntiXray();
    startBandwidth();
    new CommandRegistrar(this).register();
    startDiagnosticsTask();
    getLogger().info("MikuXrayNet 已启用");
  }

  @Override
  public void onDisable() {
    stopDiagnosticsTask();
    if (throttlePipeline != null) {
      // 先停带宽管线：它需要偿还延迟中的包、恢复被隐藏的实体与视距
      throttlePipeline.stop();
      throttlePipeline = null;
    }
    // 反矿透侧按既有纪律停机：注销拦截 → 停显形 → 关缓存 → 关池 → 停巡检（顺序不得调整）
    if (runtime != null) {
      runtime.stopForDisable();
      runtime = null;
    }
    getLogger().info("MikuXrayNet 已停用");
  }

  /** 带宽优化装配：各子模块独立注册，注册失败只停用该子模块，不影响其它功能。 */
  private void startBandwidth() {
    ThrottlePipeline pipeline = new ThrottlePipeline(this, config.bandwidth());
    try {
      pipeline.start();
    } catch (Throwable throwable) {
      getLogger().warning("带宽优化装配失败（不影响反矿透）：" + throwable.getMessage());
    }
    this.throttlePipeline = pipeline;
  }

  /** 带宽管线（供命令与诊断读取统计计数）。 */
  public ThrottlePipeline bandwidthPipeline() {
    return throttlePipeline;
  }

  /** 反矿透运行时（供诊断读取各组件计数；插件启用前为 null）。 */
  public AntiXrayRuntime antiXrayRuntime() {
    return runtime;
  }

  /**
   * 启动周期运行摘要任务（{@code bandwidth.yml: diagnostics.interval-seconds}）：
   * 每 interval 秒输出一行 INFO 精简摘要（复用 {@link Diagnostics} 既有快照），
   * {@code 0} 表示完全不输出。该键此前是死配置，此方法是其真正消费点。
   */
  private void startDiagnosticsTask() {
    stopDiagnosticsTask();
    int intervalSeconds = Math.max(0, config.bandwidth().diagnostics().intervalSeconds());
    if (intervalSeconds == 0) {
      return;
    }
    if (diagnostics == null) {
      diagnostics = new Diagnostics(this);
    }
    long periodTicks = intervalSeconds * 20L;
    try {
      // GlobalRegionScheduler：Paper 上落在主线程，快照只读计数器与配置，安全
      diagnosticsTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,
          scheduled -> outputDiagnosticsLine(), periodTicks, periodTicks);
    } catch (Throwable throwable) {
      getLogger().warning("周期诊断任务登记失败（不影响其它功能）：" + throwable.getMessage());
    }
  }

  /** 停用周期运行摘要任务（可重复调用）。 */
  private void stopDiagnosticsTask() {
    ScheduledTask task = diagnosticsTask;
    diagnosticsTask = null;
    if (task != null) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已结束，忽略
      }
    }
  }

  private void outputDiagnosticsLine() {
    try {
      Diagnostics current = diagnostics;
      if (current != null) {
        getLogger().info(current.summaryLine());
      }
    } catch (Throwable throwable) {
      // 摘要只是可观测性输出：任何异常都不能影响插件主流程
      getLogger().log(Level.WARNING, "周期诊断摘要输出失败", throwable);
    }
  }

  /** 统一配置入口（供诊断与反矿透运行时读取有效值）。 */
  public MikuConfig mikuConfig() {
    return config;
  }

  /**
   * 热重载两份配置：重读配置 → 失效改写缓存/邻块快照/显形索引 → 按新配置重启周期任务。
   *
   * <p>可重复执行、异常安全（fail-open）；返回给管理员看的中文结果行。启动期已固化的部分
   * （隐藏方块表、线程数、缓存容量等）不在本次生效范围内，结果中会显式列出。
   *
   * @return 中文结果行
   */
  public List<String> reloadConfigs() {
    return reloadCoordinator.reload(new ReloadCoordinator.Target() {
      @Override
      public int fingerprint() {
        return config.antiXray().configHash();
      }

      @Override
      public void reloadConfiguration() {
        config.load();
        // 平台判定可能与新配置的 advanced.platform 不同：先重新判定，随后的 restartPeriodicTasks 才会走对分支
        PlatformSupport.configure(config.antiXray().platform());
      }

      @Override
      public void invalidateCaches() {
        if (runtime != null) {
          runtime.invalidateRewriteCaches();
        }
      }

      @Override
      public void restartPeriodicTasks() {
        if (runtime != null) {
          runtime.restartProximity();
        }
        restartBandwidth();
        // 摘要间隔可随热重载变更（0 = 关闭）
        startDiagnosticsTask();
      }
    });
  }

  /** 按新配置重建带宽管线（停机时偿还延迟中的包并恢复被隐藏实体/视距，再以新配置启动）。 */
  private void restartBandwidth() {
    if (throttlePipeline != null) {
      throttlePipeline.stop();
      throttlePipeline = null;
    }
    startBandwidth();
  }

  /** 世界卸载时整体失效该世界的改写缓存与磁盘缓存句柄（落盘 + 关闭，避免把已卸载世界的资源留在堆上）。 */
  void registerWorldUnloadInvalidation() {
    getServer().getPluginManager().registerEvents(new Listener() {
      @EventHandler
      public void onWorldUnload(WorldUnloadEvent event) {
        if (runtime != null) {
          runtime.invalidateWorld(event.getWorld().getName());
        }
      }
    }, this);
  }
}
