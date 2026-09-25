package net.mikumc.mikuxraynet.config;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * 统一配置加载入口：负责把两份默认配置从插件资源复制到数据目录，再解析为 {@link AntiXrayConfig}
 * 与 {@link BandwidthConfig}。
 *
 * <p>关键约束：默认配置以「资源文件」形式提供，首次运行只做复制（{@code saveResource}），
 * 绝不用 {@link YamlConfiguration} 回写，以免中文注释被覆盖丢失。
 */
public final class MikuConfig {

  public static final String ANTI_XRAY_FILE = "antixray.yml";
  public static final String BANDWIDTH_FILE = "bandwidth.yml";

  private final Plugin plugin;
  private final Logger logger;
  /** 「缺 dimensions 段」WARN 的进程级一次性闸门（重复 reload 不重复刷屏）。 */
  private final AtomicBoolean dimensionsWarned = new AtomicBoolean();

  private AntiXrayConfig antiXray;
  private BandwidthConfig bandwidth;

  public MikuConfig(Plugin plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  /** 加载（或重新加载）两份配置。加载失败时保留上一份有效值，不抛异常。 */
  public void load() {
    AntiXrayConfig loadedAntiXray = AntiXrayConfig.from(read(ANTI_XRAY_FILE));
    BandwidthConfig loadedBandwidth = BandwidthConfig.from(read(BANDWIDTH_FILE));

    this.antiXray = loadedAntiXray;
    this.bandwidth = loadedBandwidth;

    if (loadedAntiXray.enabled()) {
      logger.info("反矿透配置已加载（按维度分段）：" + dimensionSummary(loadedAntiXray));
    } else {
      logger.info("反矿透配置已加载：功能处于关闭状态");
    }
    // 世界黑名单（优先级最高）：启动/重载时打印一次数量与具体列表（空则明确写「未配置」），不逐区块刷屏
    if (loadedAntiXray.worldBlacklist().isEmpty()) {
      logger.info("反矿透世界黑名单：未配置（所有世界都启用反矿透）");
    } else {
      logger.info("反矿透世界黑名单：" + loadedAntiXray.worldBlacklist().size() + " 项 "
          + loadedAntiXray.worldBlacklist()
          + "（名单内世界不使用任何反矿透功能；带宽模块不受影响）");
    }
    // 缺 dimensions 段（旧版 worlds / 顶层 obfuscation 结构）时用内置默认运行并一次性提示
    loadedAntiXray.warnIfDimensionsMissing(logger, dimensionsWarned);
    // tag(...) 展开失败的条目已按「忽略」处理（不猜成员），这里提示管理员改正
    if (!loadedAntiXray.unresolvedTags().isEmpty()) {
      logger.warning("配置里的 tag(...) 无法识别（不在内置 tag 映射表中），对应条目已忽略："
          + loadedAntiXray.unresolvedTags());
    }
    logger.info("带宽配置已加载：总开关 " + (loadedBandwidth.enabled() ? "开启" : "关闭"));
  }

  /** 各维度一句话摘要（主/地狱/末地的启用状态、隐藏项数与伪装方块数）。 */
  private static String dimensionSummary(AntiXrayConfig config) {
    StringBuilder sb = new StringBuilder();
    for (AntiXrayConfig.Dimension dimension : AntiXrayConfig.Dimension.values()) {
      if (!sb.isEmpty()) {
        sb.append("｜");
      }
      AntiXrayConfig.EffectiveObfuscation effective = config.dimensionEffective(dimension);
      sb.append(dimension.label());
      if (!config.dimensionEnabled(dimension)) {
        sb.append(" 未启用");
        continue;
      }
      sb.append(" 目标 ").append(effective.hideBlocks().size()).append(" 种 / 伪装 ")
          .append(effective.replacementWeights().size()).append(" 种 / 模式 ").append(effective.mode());
    }
    return sb.toString();
  }

  public AntiXrayConfig antiXray() {
    return antiXray;
  }

  public BandwidthConfig bandwidth() {
    return bandwidth;
  }

  private YamlConfiguration read(String fileName) {
    File file = new File(plugin.getDataFolder(), fileName);
    if (!file.isFile()) {
      // 只在文件不存在时复制资源，注释随资源文件一并落盘
      plugin.saveResource(fileName, false);
    }

    YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
    if (configuration.getKeys(false).isEmpty()) {
      logger.warning("配置文件 " + fileName + " 为空或无法解析，将使用内置默认值");
    }
    return configuration;
  }
}