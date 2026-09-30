package net.mikumc.mikuxraynet.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
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
  /** 「配置项被安全下限抬升」WARN 的进程级一次性闸门。 */
  private final AtomicBoolean floorWarned = new AtomicBoolean();
  /** 「反矿透配置解析失败已保留上一份/改用默认」WARN 的进程级一次性闸门。 */
  private final AtomicBoolean antiXrayFallbackWarned = new AtomicBoolean();
  /** 「带宽配置解析失败已保留上一份/改用默认」WARN 的进程级一次性闸门。 */
  private final AtomicBoolean bandwidthFallbackWarned = new AtomicBoolean();

  private volatile AntiXrayConfig antiXray;
  private volatile BandwidthConfig bandwidth;

  public MikuConfig(Plugin plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  /**
   * 加载（或重新加载）两份配置。加载失败时保留上一份有效值，不抛异常。
   *
   * <p><b>为什么解析必须包 try/catch(Throwable)</b>：本方法由 {@code MikuXrayNet.onLoad()} 调用，
   * 一旦解析抛异常冒泡上去，服务端会把插件整体禁用（连带宽/反矿透都一起停）。因此两份配置各自
   * 兜底：解析成功则替换，失败则保留上一份有效值（首次加载即失败则回落到内置默认），
   * 保证 {@link #antiXray()} / {@link #bandwidth()} 永远非 {@code null}。
   */
  public void load() {
    AntiXrayConfig loadedAntiXray = loadAntiXray();
    BandwidthConfig loadedBandwidth = loadBandwidth();

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
    // 配置项低于安全下限（视锥两键、磁盘缓存过期时间）时按下限生效并一次性提示
    loadedAntiXray.warnIfConfigFloorApplied(logger, floorWarned);
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

  /**
   * 读取并解析反矿透配置，任何 {@code Throwable}（含解析期间抛出的 {@code Error}）都不冒泡。
   *
   * <p>失败语义：已有上一份有效配置则原样保留；首次加载即失败则回落到「全部键缺失」的内置默认
   * （{@link AntiXrayConfig#from} 传一个空 {@link YamlConfiguration}），绝不返回 {@code null}。
   */
  private AntiXrayConfig loadAntiXray() {
    try {
      return AntiXrayConfig.from(read(ANTI_XRAY_FILE));
    } catch (Throwable throwable) {
      AntiXrayConfig previous = this.antiXray;
      if (previous != null) {
        warnFallbackOnce(antiXrayFallbackWarned, ANTI_XRAY_FILE, "已保留上一份配置", throwable);
        return previous;
      }
      warnFallbackOnce(antiXrayFallbackWarned, ANTI_XRAY_FILE, "已改用内置默认配置", throwable);
      return AntiXrayConfig.from(new YamlConfiguration());
    }
  }

  /** 读取并解析带宽配置；失败语义同 {@link #loadAntiXray()}。 */
  private BandwidthConfig loadBandwidth() {
    try {
      return BandwidthConfig.from(read(BANDWIDTH_FILE));
    } catch (Throwable throwable) {
      BandwidthConfig previous = this.bandwidth;
      if (previous != null) {
        warnFallbackOnce(bandwidthFallbackWarned, BANDWIDTH_FILE, "已保留上一份配置", throwable);
        return previous;
      }
      warnFallbackOnce(bandwidthFallbackWarned, BANDWIDTH_FILE, "已改用内置默认配置", throwable);
      return BandwidthConfig.from(new YamlConfiguration());
    }
  }

  /** 配置解析失败的一次性中文提示（每个文件只打印一次，说明保留策略与后果）。 */
  private void warnFallbackOnce(AtomicBoolean gate, String fileName, String detail, Throwable cause) {
    if (!gate.compareAndSet(false, true)) {
      return;
    }
    logger.log(Level.WARNING, "配置文件 " + fileName + " 解析失败，" + detail
        + "（本次按该策略继续运行，不会因此禁用插件）。请检查该文件内容后重载。", cause);
  }

  /**
   * 读取并解析某份配置。
   *
   * <p><b>为什么用 {@code loadFromString} 而不是 {@code loadConfiguration(File)}</b>：
   * {@code loadConfiguration} 对<b>坏 YAML</b> 不抛异常——它把解析错误转成一条日志后返回<b>空配置</b>，
   * 于是「解析失败保留上一份」的外层 catch 永不触发，改坏文件会<b>静默回落全默认</b>。
   * 改为读文本 + {@code loadFromString} 后，坏 YAML 会抛 {@link InvalidConfigurationException}，
   * 真正走到「保留上一份 + 一次性 WARN」的兜底分支。
   *
   * @throws IOException 读取文件失败
   * @throws InvalidConfigurationException YAML 结构非法（由调用方兜底为「保留上一份」）
   */
  private YamlConfiguration read(String fileName)
      throws IOException, InvalidConfigurationException {
    File file = new File(plugin.getDataFolder(), fileName);
    if (!file.isFile()) {
      // 只在文件不存在时复制资源，注释随资源文件一并落盘
      plugin.saveResource(fileName, false);
    }

    String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
    YamlConfiguration configuration = new YamlConfiguration();
    // 坏 YAML 会在此抛出 InvalidConfigurationException（而非静默返回空配置）
    configuration.loadFromString(content);
    if (configuration.getKeys(false).isEmpty()) {
      logger.warning("配置文件 " + fileName + " 为空或无法解析，将使用内置默认值");
    }
    return configuration;
  }
}