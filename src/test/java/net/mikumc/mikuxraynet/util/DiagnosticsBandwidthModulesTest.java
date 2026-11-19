package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 「带宽开关」行的<b>接线结论</b>：配置怎么写是一层、真实装配起来的是另一层。
 *
 * <p>守门的是三条曾经会给出相反结论的路径：
 * <ul>
 *   <li><b>缺 ProtocolLib 不等于「全部子模块未注册」</b>：{@code ThrottlePipeline} 只在拿不到
 *       {@code ProtocolManager} 时跳过零位移取消 / 变更合并 / AFK 降级；实体剔除（{@code hideEntity}）
 *       与高延迟降视距只走 Bukkit API，不会被这道依赖检查拦下。旧实现把整行短路成「实际未注册：全部
 *       子模块（缺少 ProtocolLib）」，服主排查「玩家看不见怪 / 视距变小」时会被引向相反结论。</li>
 *   <li><b>调色板压缩需要区块改写</b>：它不是独立注册的模块，反矿透未接线（缺前置 / 配置关闭 / 装配
 *       失败）时没有 section 可压，因此不能只写「启用」。</li>
 *   <li><b>实体剔除的射线遮挡需要遮挡数据</b>：位图不可用时该子路径整体跳过、只保留视锥剔除；
 *       这条降级既不能说成「射线遮挡在生效」，也不能说成「模块没注册」。反过来，视锥关掉、只开射线
 *       且没有位图时，实体剔除什么都不做——更不能写「仅视锥」。</li>
 * </ul>
 *
 * <p>措辞纪律：本行只能陈述<b>配置</b>与<b>快照里的接线事实</b>，不得用 {@code plan()} 冒充「已装配
 * 成功」（那要看启动日志与实际计数）。格式化是纯函数：本用例只构造 {@code Snapshot}，不需要 Bukkit。
 */
class DiagnosticsBandwidthModulesTest {

  private static final String RAY_UNAVAILABLE = "射线遮挡不可用（缺遮挡数据）";
  private static final String NEEDS_REWRITE = "（需区块改写：反矿透未接线，本项不生效）";
  private static final String NEEDS_REWRITE_MASTER_OFF =
      "（需区块改写：带宽总开关关闭，本项不生效）";
  private static final String PALETTE_ON = "调色板压缩 启用（改写后收缩 + 单值/低位宽降级）";
  private static final String MISSING_PL = "缺少 ProtocolLib，需要封包通道";

  private static BandwidthConfig bandwidth(String yaml) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(yaml);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return BandwidthConfig.from(configuration);
  }

  /**
   * 只填本域关心的四项接线状态（依赖就绪 / 平台 / 区块改写 / 遮挡判定），其余用零值兜底。
   *
   * <p>{@code chunkRewriteActive} 写进 {@code Env.antiXrayActive}：它表示「区块改写是否真的接上了」，
   * 决定调色板压缩有没有 section 可压；{@code occlusionWired} 写进 {@code Occlusion.active}：
   * 它表示「引擎已起 + 数据源可用」，决定射线遮挡能不能跑。
   */
  private static Diagnostics.Snapshot snapshot(boolean protocolLibReady, boolean folia,
      boolean chunkRewriteActive, boolean occlusionWired, BandwidthConfig bandwidth) {
    return new Diagnostics.Snapshot(
        new Diagnostics.Snapshot.Env(true, protocolLibReady, folia, chunkRewriteActive, 0,
            "Paper 26.2", "25", "1.34.13"),
        Diagnostics.Snapshot.Rewrite.EMPTY,
        Diagnostics.Snapshot.Proximity.EMPTY,
        Diagnostics.Snapshot.Index.EMPTY,
        occlusionWired
            ? new Diagnostics.Snapshot.Occlusion(true, 128, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
            : Diagnostics.Snapshot.Occlusion.EMPTY,
        Diagnostics.Snapshot.Throttle.EMPTY,
        Diagnostics.Snapshot.Pool.EMPTY,
        Diagnostics.Snapshot.DiskCache.EMPTY,
        Diagnostics.Snapshot.ChunkAccessor.EMPTY,
        null,
        bandwidth);
  }

  /** 全部接线就绪（正常服务器）：任何接线说明都不该出现。 */
  private static Diagnostics.Snapshot wired(BandwidthConfig bandwidth) {
    return snapshot(true, false, true, true, bandwidth);
  }

  /** 只取「带宽开关：」这一行（其它行也含「带宽」二字，前缀必须精确）。 */
  private static String bandwidthLine(Diagnostics.Snapshot snapshot) {
    List<String> lines = Diagnostics.formatStatus(snapshot);
    for (String line : lines) {
      if (line.startsWith("带宽开关：")) {
        assertFalse(line.contains("\n"), "状态面板每一行都必须自成一行：" + line);
        return line;
      }
    }
    throw new AssertionError("状态面板缺少「带宽开关：」行：" + String.join("\n", lines));
  }

  /**
   * 缺 ProtocolLib 时只点名三项依赖封包通道的模块，且逐项带自己的原因；
   * 不得再出现「全部子模块」这种把实体剔除 / 降视距一起否掉的统一结论。
   */
  @Test
  void missingProtocolLibNamesOnlyPacketChannelModules() {
    String line = bandwidthLine(snapshot(false, false, false, false, bandwidth("enabled: true\n")));

    assertTrue(line.contains("实际未注册："
            + "零位移取消（" + MISSING_PL + "）、"
            + "变更合并（" + MISSING_PL + "）、"
            + "AFK 降级（" + MISSING_PL + "）"),
        "三项依赖封包通道的模块必须逐项点名并给出同一类原因：" + line);
    assertFalse(line.contains("全部子模块"),
        "缺前置不能让整行以「全部子模块未注册」收尾（实体剔除与降视距不受这道检查约束）：" + line);
    assertFalse(line.contains("实际未注册：实体剔除"),
        "实体剔除不依赖 ProtocolLib，不得进「未注册」清单：" + line);
    assertFalse(line.contains("高延迟降视距（"),
        "高延迟降视距不依赖 ProtocolLib，不得带原因进清单：" + line);
    // 缺前置只影响上面三项；另两项按配置判断能否工作，射线遮挡另需遮挡数据（不得夸大成「已生效」）。
    assertTrue(line.contains("不需 ProtocolLib 的还有："
            + "实体剔除（射线遮挡不可用：缺遮挡数据）、高延迟降视距"),
        "必须写明缺前置时另两项的可用条件：" + line);
    assertFalse(line.contains("仍在生效"), "不得用「仍在生效」替代装配事实：" + line);

    // 关掉 AFK 后该项不再进清单：清单必须跟着配置走，不能把未启用的模块算成「未接线」。
    String afkOff = bandwidthLine(snapshot(false, false, false, false,
        bandwidth("afk:\n  enabled: false\n")));
    assertFalse(afkOff.contains("AFK 降级（"), "配置关闭的模块不属于「未接线」：" + afkOff);
    assertTrue(afkOff.contains("零位移取消（" + MISSING_PL + "）"), afkOff);
  }

  /**
   * 视锥关掉、只开射线且没有遮挡数据时，实体剔除什么都不做——此时不得写「仅视锥」，
   * 也不能把它算成「未注册」（配置上它是注册的，只是该子路径缺数据）。
   */
  @Test
  void raycastOnlyWithoutDataNeverClaimsFrustumIsWorking() {
    String line = bandwidthLine(snapshot(false, false, false, false, bandwidth("""
        entity-culling:
          frustum:
            enabled: false
        """)));

    assertTrue(line.contains("不需 ProtocolLib 的还有："
            + "实体剔除（射线遮挡不可用：缺遮挡数据）、高延迟降视距"), line);
    assertFalse(line.contains("视锥剔除）"), "frustum 已关闭，不得声称视锥剔除在工作：" + line);
    assertFalse(line.contains("实际未注册：实体剔除"), line);
    // 配置段本身仍要如实回显两个子项（raycast 开 / 视锥关）
    assertTrue(line.contains("实体剔除 启用（" + RAY_UNAVAILABLE + " + 视锥剔除关）"), line);
  }

  /** 两个子项都关时，实体剔除整项不注册：进「未注册」清单，且不进缺前置的说明。 */
  @Test
  void bothSubSwitchesOffListModuleAsNotRegistered() {
    String line = bandwidthLine(snapshot(false, false, false, false, bandwidth("""
        entity-culling:
          raycast: false
          frustum:
            enabled: false
        """)));

    assertTrue(line.contains("实体剔除（raycast 与 frustum.enabled 均为 false）"), line);
    assertFalse(line.contains("实体剔除（视锥剔除）"), line);
    assertFalse(line.contains("实体剔除（射线遮挡"), "整项未注册时不得再描述其射线子项：" + line);
    assertTrue(line.contains("不需 ProtocolLib 的还有：高延迟降视距"), line);
  }

  /**
   * 实体剔除：射线遮挡缺遮挡数据时写明「不可用」并保留视锥剔除；位图接线后回到原口径。
   */
  @Test
  void entityCullingStatesRaycastDataCondition() {
    String degraded = bandwidthLine(snapshot(true, false, false, false, bandwidth("enabled: true\n")));
    assertTrue(degraded.contains("实体剔除 启用（" + RAY_UNAVAILABLE
            + " + 视锥剔除（距离门 32.0 格，FOV 110.0°））"),
        "缺遮挡数据时必须写明射线遮挡不可用、并保留视锥剔除的有效门槛：" + degraded);
    assertFalse(degraded.contains("射线遮挡 + 视锥"),
        "缺遮挡数据时不得写成「射线遮挡在生效」：" + degraded);

    String wired = bandwidthLine(wired(bandwidth("enabled: true\n")));
    assertTrue(wired.contains("实体剔除 启用（射线遮挡 + 视锥剔除（距离门 32.0 格，FOV 110.0°））"),
        "接线后必须回到既有的无歧义口径（不多出降级说明）：" + wired);
    assertFalse(wired.contains(RAY_UNAVAILABLE), wired);
  }

  /**
   * 调色板压缩：没有区块改写时不能只写「启用」——它需要改写链路才有 section 可压。
   */
  @Test
  void paletteStatesChunkRewriteRequirement() {
    String noRewrite = bandwidthLine(snapshot(true, false, false, false, bandwidth("enabled: true\n")));
    assertTrue(noRewrite.contains(PALETTE_ON + NEEDS_REWRITE),
        "区块改写未接线时必须补出「需区块改写」，不能只写启用：" + noRewrite);

    String wired = bandwidthLine(wired(bandwidth("enabled: true\n")));
    assertTrue(wired.contains(PALETTE_ON), wired);
    assertFalse(wired.contains("需区块改写"), "接线后不得再出现前置条件（避免正常服务器噪声）：" + wired);

    // 总开关关闭：调色板同样没有改写链路可挂，原因要写清是总开关（不是缺前置）。
    String masterOff = bandwidthLine(snapshot(true, false, true, true, bandwidth("enabled: false\n")));
    assertTrue(masterOff.contains(PALETTE_ON + NEEDS_REWRITE_MASTER_OFF), masterOff);
    assertTrue(masterOff.contains("实际未注册：全部模块（带宽总开关关闭）"),
        "总开关关闭时全部模块共用这一个正确原因：" + masterOff);
    assertFalse(masterOff.contains("缺少 ProtocolLib"),
        "总开关关闭不得归因为缺少 ProtocolLib：" + masterOff);
  }

  /**
   * 各模块的原因必须来自它自己的开关：零位移取消因行为开关未接线时，不得把原因写到变更合并上，
   * 也不得因为一个模块未接线就给整行加上「缺少 ProtocolLib」。
   */
  @Test
  void perModuleReasonsStayOnTheirOwnModule() {
    String line = bandwidthLine(snapshot(true, false, true, true,
        bandwidth("entity-packets:\n  skip-zero-movement: false\n")));

    assertTrue(line.contains("零位移取消（skip-zero-movement=false）"),
        "零位移取消的原因必须取自它自己的行为开关：" + line);
    assertTrue(line.contains("变更合并 true"), "已接线模块仍要回显配置值：" + line);
    assertFalse(line.contains("变更合并（"),
        "变更合并已接线，不得被零位移取消的原因牵连进清单：" + line);
    assertFalse(line.contains("缺少 ProtocolLib"),
        "依赖就绪时不得出现缺少前置的原因：" + line);
  }

  /** 正常服务器（全部接线）不得多出任何接线说明，保持既有的短行。 */
  @Test
  void wiredDeploymentAddsNoWiringNoise() {
    String line = bandwidthLine(wired(bandwidth("enabled: true\n")));
    assertFalse(line.contains("实际未注册"), line);
    assertFalse(line.contains("需区块改写"), line);
    assertFalse(line.contains("不可用"), line);
    assertFalse(line.contains("不需 ProtocolLib"), line);
  }
}
