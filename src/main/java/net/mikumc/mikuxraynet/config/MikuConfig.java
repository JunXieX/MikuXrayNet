package net.mikumc.mikuxraynet.config;

import java.io.File;
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
      logger.info("反矿透配置已加载：目标方块 " + loadedAntiXray.hideBlocks().size() + " 种，伪装方块 "
          + loadedAntiXray.replacementWeights().size() + " 种");
    } else {
      logger.info("反矿透配置已加载：功能处于关闭状态");
    }
    // tag(...) 展开失败的条目已按「忽略」处理（不猜成员），这里提示管理员改正
    if (!loadedAntiXray.unresolvedTags().isEmpty()) {
      logger.warning("配置里的 tag(...) 无法识别（不在内置 tag 映射表中），对应条目已忽略："
          + loadedAntiXray.unresolvedTags());
    }
    logger.info("带宽配置已加载：总开关 " + (loadedBandwidth.enabled() ? "开启" : "关闭"));
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