package net.mikumc.mikuxraynet;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bandwidth.ThrottlePipeline;
import net.mikumc.mikuxraynet.bandwidth.ThrottleStats;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.config.MikuConfig;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import net.mikumc.mikuxraynet.occlusion.OcclusionStats;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.util.Diagnostics;
import net.mikumc.mikuxraynet.util.ReloadCoordinator;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
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
  /**
   * 带宽管线（热重载时整体停旧建新）。
   *
   * <p><b>为什么是 volatile</b>：写发生在命令线程（reload 的「停旧建新」），读发生在周期诊断任务所在的
   * 全局区域线程。首次发布由「任务提交」提供一次 happens-before，但 reload 的重建发生在任务早已运行
   * 之后，之后两边再无同步点——非 volatile 会让诊断线程读到旧引用（最坏短暂显示旧计数）。
   */
  private volatile ThrottlePipeline throttlePipeline;
  /**
   * 带宽统计持有者：<b>插件级唯一</b>，跨热重载复用。
   *
   * <p>热重载会重建带宽管线（停旧建新）。若统计由管线自己持有，每次 reload 计数都会归零、
   * 累计口径失去连续性。把它提到插件层后，管线重建不影响计数，{@code /mxnet status} 字段语义不变。
   */
  private final ThrottleStats throttleStats = new ThrottleStats();
  /**
   * 异步遮挡计数：同样<b>插件级唯一</b>、跨热重载复用（理由同 {@link #throttleStats}）。
   *
   * <p>即使未启用异步遮挡也保留该实例（计数恒为 0）：它只是几个 {@code LongAdder}，
   * 让诊断侧无需分支即可取值。
   */
  private final OcclusionStats occlusionStats = new OcclusionStats();
  /** 诊断转储 / status 数据源（热重载时重建；volatile 原因同 {@link #throttlePipeline}）。 */
  private volatile Diagnostics diagnostics;
  /** 周期运行摘要任务（bandwidth.yml: diagnostics.interval-seconds；0 = 关闭）。volatile：命令线程与全局区域线程都会读写。 */
  private volatile ScheduledTask diagnosticsTask;
  /**
   * 反矿透侧运行时（ProtocolLib 接入、工作池、显形结构、磁盘缓存、直通名单）；onEnable 起可用。
   *
   * <p><b>为什么是 volatile</b>：同 {@link #throttlePipeline}——命令线程停旧建新、诊断任务的全局区域线程读取。
   */
  private volatile AntiXrayRuntime runtime;

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
    } else {
      // 同一实例被 disable→enable 时（非 /reload 新建实例），Bukkit 不会再调 onLoad，而 onDisable
      // 已把静态黑名单清空（publish(null)）——不重新发布就会让 blacklist.yml 静默失效（主城/副本世界
      // 重新被改写与带宽优化）。这里无条件重发布，幂等且廉价。
      WorldBlacklistConfig.publish(config.blacklist());
    }

    // 平台判定必须最先做：调度已统一走 Paper 调度器（不再分支），但平台判定仍决定
    // 「能否安全枚举全服实体」（零位移包白名单）与「邻近显形的巡检策略」两处能力差异。
    // 判定顺序：advanced.platform 手动覆盖 → 品牌/版本标识（含 folia）→ 判别类探测
    // （io.papermc.paper.threadedregions.RegionizedServer：Folia 核心独有，Leaf/Paper 都没有）。
    PlatformSupport.configure(config.antiXray().platform());
    // 开服日志只保留一行「加载成功」：常规启动的全部细节（平台、各模块参数、自检结果）一律降为 FINE
    // （默认不可见，需要排查时打开调试日志即可）；任何功能不可用 / 降级 / 失败都会由各自模块打 WARNING。
    getLogger().fine("运行平台：" + PlatformSupport.platformDescription());
    // 整个装配段放进「重载会话锁」并复位 shuttingDown：若只在锁外复位，一个正排队在 reloadLock 上的
    // 重载可能在复位之后立刻拿到锁，与启用装配并发执行 → 同一次启用里出现两条 ThrottlePipeline，
    // 其中一条既不在字段里也没人 stop（两套改写器同时生效、旧 EntityCuller 藏起的实体无人恢复）。
    reloadCoordinator.runExclusivelyOnEnable(() -> {
      this.runtime = new AntiXrayRuntime(this, occlusionStats);
      // 反矿透装配失败绝不能让整个插件启用失败：带宽模块与命令与它相互独立，必须照常可用。
      // 旧实现让异常直接冒出 onEnable —— 此时 ProtocolLib 拦截、线程池、磁盘缓存可能已装配，
      // 却永远不会执行 stopForDisable()，线程/文件句柄全部泄漏，且 Bukkit 会把整个插件判为启用失败。
      try {
        runtime.startBypassRegistry();
        runtime.startAntiXray();
      } catch (Throwable throwable) {
        getLogger().log(Level.WARNING, "反矿透模块装配失败（带宽优化与管理命令仍可用）", throwable);
        try {
          runtime.stopForDisable();
        } catch (Throwable cleanupFailure) {
          getLogger().log(Level.WARNING, "反矿透模块清理失败（可能残留线程/句柄，建议重启）", cleanupFailure);
        }
        // 装配失败后不再对外发布这个半成品：置空字段，避免诊断把它当成「已启用但全 0」。
        this.runtime = null;
      }
      startBandwidth();
    });
    new CommandRegistrar(this).register();
    startDiagnosticsTask();
    getLogger().info("MikuXrayNet " + pluginVersion() + " 已启用（" + PlatformSupport.platformDescription() + "）");
  }

  /** 插件版本号（读取失败时为空串，绝不因此影响启用流程）。 */
  private String pluginVersion() {
    try {
      return getPluginMeta().getVersion();
    } catch (Throwable ignored) {
      return "";
    }
  }

  @Override
  public void onDisable() {
    // 与热重载共用同一把会话锁：否则「停用」与「reload 的停旧建新」会交错，重载可能在停用之后
    // 又装配出一套监听器/任务，它们挂在已停用的插件上永远不被清理（见 ReloadCoordinator#runExclusively）。
    reloadCoordinator.runExclusively(() -> {
      // 逐步兜底：任何一步抛错都不能吞掉后续清理（否则会留下被隐藏的实体/降过的视距/未注销的监听器）。
      // 顺序本身是纪律（先带宽、后反矿透），所以不用 finally 串联，而是各步独立 try/catch。
      try {
        stopDiagnosticsTask();
      } catch (Throwable throwable) {
        getLogger().log(Level.WARNING, "停用期取消诊断任务失败（忽略）", throwable);
      }
      ThrottlePipeline pipeline = throttlePipeline;
      throttlePipeline = null;
      if (pipeline != null) {
        // 先停带宽管线：它需要偿还延迟中的包、恢复被隐藏的实体与视距
        try {
          pipeline.stop();
        } catch (Throwable throwable) {
          getLogger().log(Level.WARNING, "停用期停止带宽管线失败（已置空字段，避免诊断读到半停状态）", throwable);
        }
      }
      // 反矿透侧按既有纪律停机：注销拦截 → 停显形 → 关缓存 → 关池 → 停巡检（顺序不得调整）
      AntiXrayRuntime current = runtime;
      runtime = null;
      if (current != null) {
        try {
          current.stopForDisable();
        } catch (Throwable throwable) {
          getLogger().log(Level.WARNING, "停用期停止反矿透模块失败（已置空字段）", throwable);
        }
      }
      // 世界黑名单是**进程级静态实例**（{@code WorldBlacklistConfig#publish} 的一次 volatile 写），
      // 停用时必须复位：否则插件停了、名单还留着，同一 JVM 里下一次启用（/reload confirm、其它插件
      // 动态启停本插件）在 onLoad 之前读到的仍是上一份名单；其它读者也会拿到已失效的豁免结论。
      // publish(null) = 空名单（isBlacklistedNow 恒 false，fail-open），与「未加载」的语义一致。
      try {
        WorldBlacklistConfig.publish(null);
      } catch (Throwable throwable) {
        getLogger().log(Level.WARNING, "停用期复位世界黑名单失败（忽略）", throwable);
      }
    });
    getLogger().fine("MikuXrayNet 已停用");
  }

  /** 带宽优化装配：各子模块独立注册，注册失败只停用该子模块，不影响其它功能。 */
  private void startBandwidth() {
    // 统计持有者跨 reload 复用（throttleStats / occlusionStats 均为插件级唯一实例）
    AntiXrayRuntime current = runtime;
    OcclusionStores occlusionStores = current == null ? null : current.occlusionStores();
    ThrottlePipeline pipeline =
        new ThrottlePipeline(this, config.bandwidth(), throttleStats, occlusionStores, occlusionStats);
    try {
      pipeline.start();
      this.throttlePipeline = pipeline;
    } catch (Throwable throwable) {
      getLogger().log(Level.WARNING, "带宽优化装配失败（不影响反矿透）", throwable);
      // 装配失败的实例不发布：它可能已注册了部分监听器/任务（start 内部已尽力清理），
      // 但若仍挂在字段上，诊断会把它当成「已启用」去读一套恒为 0 的计数，掩盖真正的失败。
      try {
        pipeline.stop();
      } catch (Throwable ignored) {
        // 清理失败只影响同一次失败诊断，不再外抛
      }
    }
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
      getLogger().log(Level.WARNING, "周期诊断任务登记失败（不影响其它功能）", throwable);
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
   * 热重载全部配置（antixray.yml / bandwidth.yml / blacklist.yml）：重读配置 → 失效<b>派生缓存</b>
   * （改写缓存 / 邻块快照；伪装索引与已显形集合<b>刻意保留</b>，见
   * {@code ProtocolLibAsyncListener#invalidateAll}）→ 按新配置重启周期任务。
   *
   * <p>可重复执行、异常安全（fail-open）；返回给管理员看的中文结果行。启动期已固化的部分
   * （隐藏方块表、线程数、缓存容量等）不在本次生效范围内，结果中会显式列出。
   *
   * @return 中文结果行
   */
  public List<String> reloadConfigs() {
    return reloadCoordinator.reload(new ReloadCoordinator.Target() {
      @Override
      public int antiXrayFingerprint() {
        return config.antiXray().configHash();
      }

      @Override
      public int bandwidthFingerprint() {
        return config.bandwidth().configHash();
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

      @Override
      public String configurationFallbackNote() {
        // 解析失败时配置会被保留/回落，必须在重载回显里如实告知——否则管理员会以为新文件已生效
        return config.fallbackNote();
      }
    });
  }

  /**
   * 按新配置重建带宽管线（停机时偿还延迟中的包并恢复被隐藏实体/视距，再以新配置启动）。
   *
   * <p><b>热重载的「恢复/隐藏交错」瞬态（已接受，勿当 bug 修）</b>：旧管线 {@code stop()} 会先把
   * 它隐藏过的实体 {@code showEntity}、把降过的视距还原；随后新管线 {@code start()} 再按新配置重新
   * 评估并隐藏。因此热重载瞬间被剔除的实体可能出现<b>一次</b>极短的「恢复可见 → 再次隐藏」闪烁。
   * 这是「先彻底还原、再干净重建」这一停机纪律的必然结果：它保证不会把旧管线的隐藏状态泄漏给新管线
   * （否则会留下永不恢复的实体）。代价仅是一次瞬态观感，故<b>明确接受并文档化</b>，不改变行为。
   */
  private void restartBandwidth() {
    // 先摘字段再停，与 onDisable 同一顺序：诊断/命令线程此刻读到的是「无管线」，不会把半停状态当成在跑。
    // ThrottlePipeline#stop() 内部逐模块 catch(Throwable)，对外不抛，因此这里不需要再包一层。
    // （注销失败的模块会被旧实例保留以便重试，但字段已置空、旧实例不再可达，那条重试路径只在
    //  同一实例再次 stop 时生效——热重载下拿不到，属已知取舍，重启即恢复。）
    ThrottlePipeline pipeline = throttlePipeline;
    throttlePipeline = null;
    if (pipeline != null) {
      pipeline.stop();
    }
    startBandwidth();
  }

  /**
   * 注册「区块 / 世界卸载 → 失效缓存与遮挡位图」监听器（落盘 + 关闭 + 摘除内存表，避免把已卸载的
   * 资源留在堆上）。
   *
   * <p><b>为什么区块卸载也要管</b>：遮挡位图按「区块 + section」驻留，只在解码 / 缓存命中时写入；
   * 若不随卸载驱逐，其内存会随「本局游戏曾解码过的区块总数」无界增长（跨世界、跨会话）。
   * 摘除是安全的：区块卸载意味着附近已无玩家，这份几何数据此刻无人使用；区块下次被发送时会整列重建。
   */
  void registerWorldUnloadInvalidation() {
    getServer().getPluginManager().registerEvents(new Listener() {
      @EventHandler
      public void onWorldUnload(WorldUnloadEvent event) {
        if (runtime != null) {
          runtime.invalidateWorld(event.getWorld().getName());
        }
      }

      @EventHandler
      public void onChunkUnload(ChunkUnloadEvent event) {
        AntiXrayRuntime current = runtime;
        if (current == null) {
          return;
        }
        try {
          current.invalidateChunk(event.getWorld().getName(), event.getChunk().getX(),
              event.getChunk().getZ());
        } catch (Throwable throwable) {
          // 卸载事件里的坐标读取失败只影响本次驱逐（该区块的位图留到下次世界卸载再清），不阻断卸载
          getLogger().log(Level.WARNING, "区块卸载时摘除遮挡位图失败（已忽略）", throwable);
        }
      }
    }, this);
  }
}
