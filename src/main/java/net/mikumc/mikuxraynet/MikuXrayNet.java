package net.mikumc.mikuxraynet;

import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.bandwidth.ThrottlePipeline;
import net.mikumc.mikuxraynet.bootstrap.DependencyGuard;
import net.mikumc.mikuxraynet.bootstrap.PacketEventsHook;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.bootstrap.ProtocolLibHook;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.MikuConfig;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;
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
 *   <li><b>反矿透</b>：出站区块封包改写，把暴露于空气的矿物替换为伪装方块；</li>
 *   <li><b>带宽优化</b>：零位移包抑制、方块变更合并、实体射线剔除、AFK 降级与高延迟降视距
 *       （由 {@code bandwidth.ThrottlePipeline} 装配，可用 bandwidth.yml 分别开关）。</li>
 * </ul>
 *
 * <p><b>封包通道</b>：ProtocolLib 是唯一的拦截与改写通道（真异步扣包，工作线程在 Netty 管道之外运行）；
 * PacketEvents 只作为「库」在启动期提供方块状态映射，运行期不参与任何拦截。
 *
 * <p>装配细节见 {@code bootstrap} 包；本类只负责生命周期编排，不承载算法。
 */
public final class MikuXrayNet extends JavaPlugin {

  private MikuConfig config;
  private MikuWorkPool workPool;
  private ProtocolLibHook protocolLibHook;
  private ThrottlePipeline throttlePipeline;

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

    getLogger().info("运行平台：" + PlatformSupport.platformDescription());
    startAntiXray();
    startBandwidth();
    getLogger().info("MikuXrayNet 已启用");
  }

  @Override
  public void onDisable() {
    if (throttlePipeline != null) {
      // 先停带宽管线：它需要偿还延迟中的包、恢复被隐藏的实体与视距
      throttlePipeline.stop();
      throttlePipeline = null;
    }
    if (protocolLibHook != null) {
      protocolLibHook.unregister();
      protocolLibHook = null;
    }
    if (workPool != null) {
      workPool.close();
      workPool = null;
    }
    getLogger().info("MikuXrayNet 已停用");
  }

  /** 反矿透装配：任一环节不可用都只停用该模块并打印中文原因，不影响其它功能。 */
  private void startAntiXray() {
    AntiXrayConfig antiXray = config.antiXray();
    if (!antiXray.enabled()) {
      getLogger().info("反矿透已在配置中关闭（antixray.yml: enabled=false）");
      return;
    }

    if (!DependencyGuard.isPacketEventsPresent()) {
      getLogger().warning("未检测到 PacketEvents，反矿透模块停用");
      return;
    }

    PacketEventsHook packetEventsHook = new PacketEventsHook(getLogger());
    if (!packetEventsHook.initialize()) {
      return;
    }

    BlockStateRegistry registry = packetEventsHook.registry();
    ChunkCodec codec = new ChunkCodec(registry, versionFlags());
    ObfuscationProcessor processor = ObfuscationProcessor.create(codec, registry, antiXray, getLogger());
    if (!processor.isActive()) {
      getLogger().warning("未解析到有效的隐藏方块或伪装方块，反矿透模块停用（请检查 antixray.yml）");
      return;
    }

    if (!DependencyGuard.isProtocolLibPresent()) {
      getLogger().warning("未检测到 ProtocolLib，反矿透模块停用（它是唯一的封包拦截通道）");
      return;
    }

    MikuWorkPool pool = new MikuWorkPool(antiXray.threads(), antiXray.queueCapacity());
    ProtocolLibHook hook = new ProtocolLibHook(this);
    if (!hook.register(antiXray, processor, pool)) {
      pool.close();
      return;
    }

    this.workPool = pool;
    this.protocolLibHook = hook;
    registerWorldUnloadInvalidation();

    getLogger().info("反矿透已启用：目标方块 " + antiXray.hideBlocks().size() + " 种，伪装方块 "
        + antiXray.replacementWeights().size() + " 种");
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

  /** 世界卸载时整体失效该世界的改写缓存，避免缓存把已卸载世界的数据留在堆上。 */
  private void registerWorldUnloadInvalidation() {
    getServer().getPluginManager().registerEvents(new Listener() {
      @EventHandler
      public void onWorldUnload(WorldUnloadEvent event) {
        ProtocolLibHook hook = protocolLibHook;
        if (hook != null) {
          hook.invalidateWorld(event.getWorld().getName());
        }
      }
    }, this);
  }

  /** 按服务端上报的 Minecraft 版本构造区块二进制格式标志；无法识别时回落到本项目的目标版本。 */
  private static ChunkVersionFlags versionFlags() {
    String minecraftVersion = Bukkit.getMinecraftVersion();
    if (minecraftVersion == null || minecraftVersion.isBlank()) {
      return ChunkVersionFlags.PAPER_26_2;
    }
    return new ChunkVersionFlags(minecraftVersion);
  }
}