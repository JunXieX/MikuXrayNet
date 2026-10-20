package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 状态/转储文本格式化：给定固定计数器，断言输出包含关键字段且格式稳定。
 *
 * <p>格式化是纯函数，因此无需 Bukkit 运行时即可断言。
 */
class DiagnosticsTest {

  /**
   * 固定计数器快照（数值只用于断言关键字段与命中率，不参与业务逻辑）。
   *
   * <p>Snapshot 已拆为按域分组的嵌套 record：这里的数值与拆分前的平铺构造逐一对位，
   * 格式化输出必须逐字符不变（本测试即守门）。
   */
  private static Diagnostics.Snapshot fixed() {
    return fixed(null);
  }

  private static Diagnostics.Snapshot fixed(BandwidthConfig bandwidth) {
    return new Diagnostics.Snapshot(
        new Diagnostics.Snapshot.Env(true, true, false, true, 1, "Paper 1.20.4", "25", "1.2.5"),
        new Diagnostics.Snapshot.Rewrite(90L, 10L, 42, 30L, 100L, 331L, 5L, 2L, 1L, 2L, 0L,
            0L, 0L, 0L, "无采样"),
        new Diagnostics.Snapshot.Proximity(7L, 3L, 1L, 3L, 2L, 1L, 40L, 6L),
        // 8 参完整构造：末两项是「累计登记 / 卸载清理」，面板必须把它们与实时驻留量一起显示
        new Diagnostics.Snapshot.Index(7, 41, 12, 108L, 3L, 2L, 5L, 2L),
        new Diagnostics.Snapshot.Occlusion(true, 4096, 3, 200L, 190L, 2L, 1L, 9L, 55L, 4_200_000L, 88L, 4L,
            6L),
        new Diagnostics.Snapshot.Throttle(20L, 30L, 11L, 4L, 39L, 0L,
            4L, 40L, 12L,
            6L, 5L, 4, 40L, 7L, 3L, 11L, 2L,
            2, 3L, 8L, 9L, 4L, 5L, 25_000_000L),
        new Diagnostics.Snapshot.Pool(4, 2, 10, 2048),
        new Diagnostics.Snapshot.DiskCache(30L, 10L, 12, 2, 4L, 3L, 5L, 6L, 1L, 0L, 0L),
        Diagnostics.Snapshot.ChunkAccessor.EMPTY,
        null, bandwidth);
  }

  private static BandwidthConfig bandwidth(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return BandwidthConfig.from(configuration);
  }

  private static AntiXrayConfig antiXray(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return AntiXrayConfig.from(configuration);
  }

  private static Diagnostics.Snapshot withAntiXray(AntiXrayConfig antiXray) {
    return new Diagnostics.Snapshot(
        new Diagnostics.Snapshot.Env(true, true, false, true, 0, "Paper", "25", ""),
        Diagnostics.Snapshot.Rewrite.EMPTY,
        Diagnostics.Snapshot.Proximity.EMPTY,
        Diagnostics.Snapshot.Index.EMPTY,
        Diagnostics.Snapshot.Occlusion.EMPTY,
        Diagnostics.Snapshot.Throttle.EMPTY,
        Diagnostics.Snapshot.Pool.EMPTY,
        Diagnostics.Snapshot.DiskCache.EMPTY,
        Diagnostics.Snapshot.ChunkAccessor.EMPTY,
        antiXray, null);
  }

  /**
   * 世界黑名单必须出现在 status（人类可读行）与 dump（配置有效值）两处回显中；
   * 空列表也要明确写「未配置」，否则管理员无法确认「是没配」还是「解析丢了」。
   *
   * <p>黑名单已迁出为独立配置 {@code blacklist.yml}，回显取自静态活动实例
   * （{@code WorldBlacklistConfig.current()}），因此这里直接发布活动实例后断言。
   */
  @Test
  void statusAndDumpEchoWorldBlacklist() {
    WorldBlacklistConfig.publish(WorldBlacklistConfig.fromPatterns(List.of("spawn", "dungeon_*")));
    try {
      Diagnostics.Snapshot configured = withAntiXray(antiXray("enabled: true\n"));
      String status = String.join("\n", Diagnostics.formatStatus(configured));
      assertTrue(status.contains("世界黑名单：2 项：spawn, dungeon_*"),
          "status 必须回显黑名单数量与列表：" + status);
      String dump = Diagnostics.formatDump(configured, "20260925-000000");
      assertTrue(dump.contains("world-blacklist（独立文件 blacklist.yml）=2 项：spawn, dungeon_*"),
          "dump 的配置有效值必须回显黑名单：" + dump);
    } finally {
      WorldBlacklistConfig.publish(WorldBlacklistConfig.fromPatterns(List.of()));
    }

    Diagnostics.Snapshot empty = withAntiXray(antiXray("enabled: true\n"));
    String emptyStatus = String.join("\n", Diagnostics.formatStatus(empty));
    assertTrue(emptyStatus.contains("世界黑名单：未配置"),
        "空黑名单必须明确写「未配置」：" + emptyStatus);
  }

  /**
   * 配置项被安全下限抬升时，dump 必须同时给出「被抬升的键」与「抬升后的生效值」。
   *
   * <p>否则管理员会以为生效值就是自己写的那份（视锥配窄 → 玩家看得见的方块保持伪装；
   * 磁盘缓存过期时间配短 → 条目活不过一次重启、命中率恒为 0）。
   */
  @Test
  void dumpEchoesConfigFloorAdjustments() {
    Diagnostics.Snapshot raised = withAntiXray(antiXray("""
        proximity:
          frustum:
            fov: 60.0
        disk-cache:
          expire-seconds: 600
        """));

    String dump = Diagnostics.formatDump(raised, "20260925-000000");
    assertTrue(dump.contains("配置安全下限已抬升：proximity.frustum.fov=60.0"),
        "dump 必须回显被抬升的视锥键：" + dump);
    assertTrue(dump.contains("disk-cache.expire-seconds=600"),
        "dump 必须回显被抬升的磁盘缓存过期时间：" + dump);
    assertTrue(dump.contains("expire-seconds=86400"),
        "dump 的有效值必须是抬升后的下限（1 天）：" + dump);
  }

  @Test
  void dumpEchoesAsyncOcclusionTuningKeys() {
    // 异步遮挡是实体剔除唯一的射线路径，其调优键直接决定「成本上限」与「多藏倾向」；
    // dump 缺任一项时，排查「实体迟迟不显示 / 隐藏过久」只能靠猜配置。
    String dump = Diagnostics.formatDump(fixed(bandwidth("enabled: true\n")),
        "20260925-000000");
    for (String key : new String[] {"entity-culling.occlusion-time-budget-ms=",
        "occlusion-threads=", "max-occluding-blocks=", "occlusion-timeout-cycles=",
        "occlusion-hysteresis-cycles="}) {
      assertTrue(dump.contains(key), "dump 必须回显异步遮挡调优键 " + key + "：" + dump);
    }
  }

  @Test
  void dumpEchoesPlatformZstdAndCeilingAdjustments() {
    // 平台判定与 zstd 前置（含 zstd-sha256）此前未回显，dump 与实现漂移；这里锁住它们必须出现。
    String plain = Diagnostics.formatDump(
        withAntiXray(antiXray("enabled: true\n")), "20260925-000000");
    assertTrue(plain.contains("advanced.platform="), "dump 必须回显 advanced.platform：" + plain);
    assertTrue(plain.contains("disk-cache.zstd.auto-download="), "dump 必须回显 zstd 前置键：" + plain);
    assertTrue(plain.contains("zstd-sha256=未配置（不校验）"),
        "zstd-sha256 只回显「已配置/未配置」，不打印完整哈希：" + plain);

    // 安全上限回落同样要显式回显（邻近显形距离 / 缓存容量）
    String capped = Diagnostics.formatDump(withAntiXray(antiXray("""
        proximity:
          distance: 4096.0
        cache:
          maximum-size: 409600
        """)), "20260925-000000");
    assertTrue(capped.contains("配置安全上限已回落：proximity.distance=4096.0"),
        "dump 必须回显被回落的安全上限键：" + capped);
    assertTrue(capped.contains("cache.maximum-size=409600"), capped);
    assertTrue(capped.contains("distance=128.0"),
        "dump 的有效值必须是被回落后的上限（128 格）：" + capped);
  }

  @Test
  void statusContainsAllKeyFields() {
    List<String> lines = Diagnostics.formatStatus(fixed());
    String text = String.join("\n", lines);

    assertTrue(text.contains("反矿透"), text);
    assertTrue(text.contains("直通玩家 1 名"), text);
    assertTrue(text.contains("改写缓存"), text);
    assertTrue(text.contains("命中率 90.0%"), text);
    // 内存缓存分列：命中但内容指纹已变的那些其实等于未命中，必须单列并与磁盘查询数对账
    // （夹具自洽：磁盘查询 40 = 内存未命中 10 + 内容已变 30 = 磁盘 命中 30 + 未命中 10）
    assertTrue(text.contains("命中 90（有效 60，内容已变 30）"),
        "改写缓存必须分列「键命中」与「有效命中」（内容指纹一致），否则命中率虚高：" + text);
    assertTrue(text.contains("有效命中率 60.0%"),
        "有效命中率 = 有效命中 / (有效命中 + 未命中 + 内容已变)：" + text);
    assertTrue(text.contains("磁盘查询 40"),
        "「磁盘查询」= 内存未命中 + 内容已变，必须与磁盘缓存一行的分母相等，用于对账统计缺口：" + text);
    assertTrue(text.contains("区块改写"), text);
    assertTrue(text.contains("替换方块 331"), "状态面板必须显示累计替换方块数（区分『没匹配到目标』与『写回没生效』）");
    assertTrue(text.contains("异常 2"), text);
    assertTrue(text.contains("写回失败 1"), text);
    assertTrue(text.contains("超时放行"), text);
    assertTrue(text.contains("邻近显形"), text);
    assertTrue(text.contains("邻近显形：发送 7，坐标跳过 3"),
        "「跳过」必须写明是「坐标跳过」，否则会被误读成「整块跳过 = 0 → 整块跳过没生效」：" + text);
    assertTrue(text.contains("视锥剔除 3"), text);
    assertTrue(text.contains("射线剔除 2（其中缺数据 1）"),
        "位图判定的「缺数据 = 遮挡」子集必须单列，否则「射线剔除很大」无法区分是缺数据还是真实遮挡：" + text);
    assertTrue(text.contains("过度显形（抽样） 6 / 抽样 40"),
        "过度显形量化必须显示「浪费数 / 抽样数」，否则无法评估显形策略是否需要收紧：" + text);
    assertTrue(text.contains("显形索引：伪装区块 7（当前驻留；累计登记 5，卸载清理 2，坐标 41）"
        + "｜已显形 坐标 12（当前在线）｜累计登记 108｜安全阀触发 3/2"),
        "状态面板必须分列伪装区块数、伪装坐标数、实时已显形坐标数、累计登记数与安全阀触发数"
            + "（否则「发送 N 但已显形 0」这类口径误读会静默发生）：" + text);
    assertTrue(text.contains("实体隐藏 6/恢复 5（当前隐藏中 4"),
        "实体隐藏/恢复必须附带实时「当前隐藏中」，否则 721 vs 299 这类不对称无法自证：" + text);
    assertTrue(text.contains("实体复检 40（复检致隐藏 7，复检致恢复 3）"),
        "状态面板必须单列周期复检口径，否则「先可见后被遮挡」是否被收敛到隐藏无法观测：" + text);
    assertTrue(text.contains("视锥剔除 隐藏 11/恢复 2"),
        "视锥剔除必须单列隐藏/恢复计数（其失效模式是「转头后实体迟一步出现」，与射线剔除不同）：" + text);
    assertTrue(text.contains("单线程冲刷 5 次（累计 25.0 ms，平均 5.00 ms/次）"),
        "状态面板必须暴露单线程合并冲刷的次数与耗时（极端配置下的延迟瓶颈观测口径）：" + text);
    assertTrue(text.contains("磁盘缓存"), text);
    assertTrue(text.contains("命中率 75.0%"), text);
    // 「命中」是「读到条目」，与「有效命中」分列：被负载被拒掉的那些等于未命中
    // （否则「读到 37 条、全部作废」会被显示成 1.5% 而非 0%，被误判为缓存正常）
    assertTrue(text.contains("命中 30（有效 25）"),
        "磁盘缓存必须分列「读到条目」与「有效命中」（hits − 负载被拒），否则全废的缓存会被显示成有一定命中率：" + text);
    assertTrue(text.contains("有效命中率 62.5%"),
        "有效命中率 = (hits − 负载被拒) / (hits − 负载被拒 + misses + 负载被拒) =" + text);
    // 「命中率为什么异常」的自证口径：过期清理 / 配置指纹清理 / 负载被拒 / 写入被拒 / 积压被拒 / 异常必须可见
    assertTrue(text.contains("过期清理 4，配置指纹不符清理 3，负载被拒 5，写入被拒 7，积压被拒 0，异常 0"),
        "磁盘缓存必须分列过期清理、配置指纹不符清理、负载被拒、写入被拒、积压被拒与异常，"
            + "否则「命中率异常」无法自证（尤其配置指纹清理：升级后命中率骤降的头号成因，此前完全不可见）：" + text);
    assertTrue(text.contains("==== MikuXrayNet 运行状态（v1.2.5） ===="),
        "状态面板首行必须回显插件版本（区分不同构建的日志靠它）：" + text);
    assertTrue(text.contains("遮挡判定：已接线｜位图 section 4096，队列 3"),
        "状态面板必须单列遮挡判定的接线状态、位图驻留量与专用池队列深度"
            + "（否则「引擎跟不上 / 未接线」无法从日志判断）：" + text);
    assertTrue(text.contains("提交 200，完成 190，超时丢弃 2，队列满拒收 1，判定异常 9，缺遮挡数据 55 次"),
        "遮挡判定的计数必须齐全（「判定异常」持续非 0 = 存在异常输入；「缺遮挡数据」持续偏高 = 区块没被建表）：" + text);
    assertTrue(text.contains("方块补丁 88 格（失败 4 格，状态id回落 6 格）"),
        "方块变更补丁的格数、失败数与「状态id回落」数必须可见"
            + "（补丁长期为 0 = 位图不会被方块变更刷新；状态id回落 >0 = 补丁退回了 Material 级近似、"
            + "可能与位图对同方块不同状态结论不一致）：" + text);
    assertFalse(text.contains("上次停用有子模块清理失败"),
        "清理完整时不得出现「停用清理失败」告警行：" + text);
    // 停用期子模块注销失败必须显式点名：这类「静默残留」平时完全看不出来，
    // 表现为「停用后计数还在涨」「重新启用后同一封包被处理两遍」。
    Diagnostics.Snapshot s = fixed();
    Diagnostics.Snapshot withCleanupFailure = new Diagnostics.Snapshot(s.env(), s.rewrite(),
        s.proximity(), s.index(), s.occlusion(),
        new Diagnostics.Snapshot.Throttle(20L, 30L, 11L, 4L, 39L, 0L, 4L, 40L, 12L, 6L, 5L, 4,
            40L, 7L, 3L, 11L, 2L, 2, 3L, 8L, 9L, 4L, 5L, 25_000_000L, true),
        s.pool(), s.diskCache(), s.chunkAccessor(), s.antiXray(), s.bandwidth());
    String cleanupFailed = String.join("\n", Diagnostics.formatStatus(withCleanupFailure));
    assertTrue(cleanupFailed.contains("上次停用有子模块清理失败"),
        "停用期子模块注销失败必须显式点名（否则监听器残留完全静默）：" + cleanupFailed);
    assertTrue(text.contains("带宽"), text);
    assertTrue(text.contains("零位移取消 20（放行 30；到达 纯位移 11／位移+朝向 4／朝向 39，字段形态不符 0）"),
        "零位移必须分列「三种包的到达数」与「字段形态不符」：只给「取消/放行」时，"
            + "「取消 0」无法区分「没有纯位移包」「位移从不为 0」「包没到达」「布局变了」：" + text);
    assertTrue(text.contains("AFK 玩家 2"), text);
    assertTrue(text.contains("线程池"), text);
    assertTrue(text.contains("队列 10/2048"), text);
  }

  @Test
  void statusIsStableAcrossCalls() {
    List<String> first = Diagnostics.formatStatus(fixed());
    List<String> second = Diagnostics.formatStatus(fixed());
    assertTrue(first.equals(second), "同样的计数器必须得到同样的文本");
  }

  /**
   * 周期运行摘要（diagnostics.interval-seconds 驱动）必须恒为一行、只含关键计数，
   * 与完整状态面板（formatStatus）区分开——周期日志输出整面板会刷屏。
   */
  @Test
  void summaryLineIsSingleLineWithKeyCounters() {
    String line = Diagnostics.formatSummaryLine(fixed());

    assertFalse(line.contains("\n"), "运行摘要必须恒为一行：" + line);
    assertTrue(line.startsWith("运行摘要："), line);
    assertTrue(line.contains("反矿透 生效"), line);
    assertTrue(line.contains("区块改写 100（异常 2，超时放行 2）"), line);
    assertTrue(line.contains("显形发送 7"), line);
    assertTrue(line.contains("实体隐藏 6/恢复 5（当前隐藏中 4）"), line);
    assertTrue(line.contains("遮挡判定 已接线（位图 4096，队列 3，缺数据 55）"), line);
    assertTrue(line.contains("磁盘缓存命中 30"), line);
    assertTrue(line.contains("队列 10/2048"), line);
    // 精简版不得把整面板的内容都塞进来（如依赖状态、AFK、带宽开关段）
    assertFalse(line.contains("依赖"), "单行摘要不该包含状态面板的完整内容：" + line);
    assertFalse(line.contains("AFK"), line);
  }

  @Test
  void dumpContainsEnvironmentAndConfigSections() {
    String dump = Diagnostics.formatDump(fixed(), "20260924-120000");

    assertTrue(dump.contains("MikuXrayNet 诊断转储"), dump);
    assertTrue(dump.contains("导出时间：20260924-120000"), dump);
    assertTrue(dump.contains("服务端：Paper 1.20.4"), dump);
    assertTrue(dump.contains("JVM：25"), dump);
    assertTrue(dump.contains("配置指纹："), dump);
    assertTrue(dump.contains("---- 配置项有效值 ----"), dump);
    // 无配置对象时必须给出可读的中文说明而非抛异常
    assertTrue(dump.contains("[antixray]"), dump);
    assertTrue(dump.contains("[bandwidth]"), dump);
  }

  @Test
  void hitRateHandlesNoSamples() {
    Diagnostics.Snapshot empty = new Diagnostics.Snapshot(
        new Diagnostics.Snapshot.Env(false, false, true, false, 0, "Folia 1.21", "21", ""),
        Diagnostics.Snapshot.Rewrite.EMPTY,
        Diagnostics.Snapshot.Proximity.EMPTY,
        Diagnostics.Snapshot.Index.EMPTY,
        Diagnostics.Snapshot.Occlusion.EMPTY,
        Diagnostics.Snapshot.Throttle.EMPTY,
        Diagnostics.Snapshot.Pool.EMPTY,
        Diagnostics.Snapshot.DiskCache.EMPTY,
        Diagnostics.Snapshot.ChunkAccessor.EMPTY,
        null, null);
    String text = String.join("\n", Diagnostics.formatStatus(empty));
    assertTrue(text.contains("命中率 0.0%"), text);
    assertTrue(text.contains("平台 Folia"), text);
  }

  /**
   * 调色板压缩的回显必须把「模块总开关」与「行为开关 width-budget」合成一句无歧义中文：
   * 只打印 {@code palette.enabled()} 会输出「调色板压缩 true」，而真正决定是否收缩/降级的是 width-budget。
   */
  @Test
  void paletteSwitchIsUnambiguous() {
    String defaultOn = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("enabled: true\n"))));
    assertTrue(defaultOn.contains("调色板压缩 启用（改写后收缩 + 单值/低位宽降级）"),
        "模块启用 + width-budget 默认 true 时必须写明收缩/降级：" + defaultOn);

    String budgetOff = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("palette:\n  width-budget: false\n"))));
    assertTrue(budgetOff.contains("调色板压缩 关闭（模块启用但 width-budget=false，不做任何收缩/降级）"),
        budgetOff);

    String moduleOff = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("palette:\n  enabled: false\n"))));
    assertTrue(moduleOff.contains("调色板压缩 关闭（模块未启用，不做任何收缩/降级）"), moduleOff);
  }

  /**
   * 实体剔除的开关回显必须同时写明「射线遮挡」与「视锥剔除」两个子项：
   * 只打印模块总开关会出现「实体剔除 true」这种被误读为「射线剔除在生效」的输出，
   * 而 raycast=false + frustum=true 时实际只做视锥剔除（两者失效模式完全不同）。
   */
  @Test
  void entityCullingSwitchNamesBothSubSwitches() {
    String defaultOn = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("enabled: true\n"))));
    // 距离门显示的是「有效值」= max(frustum.min-distance 24, force-visible-distance 32) = 32：
    // 只印配置原值 24 会与启动日志的有效值不一致、并让人误读成「配 24 却从 32 格才生效」。
    assertTrue(defaultOn.contains("实体剔除 启用（射线遮挡 + 视锥剔除（距离门 32.0 格，FOV 110.0°））"),
        "默认必须写明两个子项都开启，且距离门按有效值（32）回显：" + defaultOn);

    // 配置的门比强制可见距离大时，有效值取配置值（此处 48 > 32）
    String widerGate = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth(
            "entity-culling:\n  frustum:\n    min-distance: 48.0\n"))));
    assertTrue(widerGate.contains("距离门 48.0 格"),
        "距离门大于强制可见距离时必须回显配置值本身：" + widerGate);

    String raycastOff = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("entity-culling:\n  raycast: false\n"))));
    assertTrue(raycastOff.contains("实体剔除 启用（不做射线遮挡 + 视锥剔除"),
        "raycast=false 时必须写明不做射线遮挡（否则会被误读成射线剔除在生效）：" + raycastOff);

    String frustumOff = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("entity-culling:\n  frustum:\n    enabled: false\n"))));
    assertTrue(frustumOff.contains("实体剔除 启用（射线遮挡 + 视锥剔除关）"), frustumOff);
  }

  /**
   * 零位移取消的开关回显必须同时给出<b>行为开关</b> {@code skip-zero-movement}：
   * 只回显模块总开关时，把它配成 false 会显示「零位移取消 true」，而实际一个包都不会取消
   * （提前返回连「放行」也不计数），是典型的「看起来在生效却毫无效果」。
   */
  @Test
  void zeroMoveSwitchEchoesBehaviorFlag() {
    String defaultOn = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("enabled: true\n"))));
    assertTrue(defaultOn.contains("零位移取消 true（skip-zero-movement true）"),
        "默认必须回显行为开关为 true：" + defaultOn);

    String behaviorOff = String.join("\n",
        Diagnostics.formatStatus(fixed(bandwidth("entity-packets:\n  skip-zero-movement: false\n"))));
    assertTrue(behaviorOff.contains("零位移取消 true（skip-zero-movement false）"),
        "行为开关关掉时必须可见，否则会被读成「在生效」：" + behaviorOff);
  }

  /**
   * 转储文件名必须带毫秒且同名时追加序号：旧实现用秒级时间戳，同一秒内的两次 dump 会互相覆盖，
   * 直接毁掉「前后对照」这类排查证据。
   */
  @Test
  void dumpFileNameNeverCollidesWithinSameStamp(@TempDir Path dir) throws Exception {
    String stamp = "20260924-120000-123";
    File first = Diagnostics.newDumpFile(dir.toFile(), stamp);
    assertTrue(first.createNewFile(), "首份转储能创建");
    assertEquals("dump-" + stamp + ".txt", first.getName(), "文件名格式为 dump-<时间戳>.txt");

    File second = Diagnostics.newDumpFile(dir.toFile(), stamp);
    assertNotEquals(first.getName(), second.getName(), "同一时间戳的第二次转储必须换名，绝不覆盖已有文件");
    assertTrue(second.createNewFile(), "第二次转储同样能创建（不撞名）");
  }

  /** 转储保留上限：超出后按修改时间删除最旧的，且绝不碰非 dump 文件。 */
  @Test
  void dumpsArePrunedToRetentionLimit(@TempDir Path dir) throws Exception {
    for (int i = 0; i < 25; i++) {
      File file = new File(dir.toFile(),
          "dump-20260924-1200" + String.format("%02d", i) + "-000.txt");
      assertTrue(file.createNewFile());
      assertTrue(file.setLastModified(1_000_000L + i * 1000L), "测试依赖可设置修改时间");
    }
    File unrelated = new File(dir.toFile(), "notes.txt");
    assertTrue(unrelated.createNewFile());

    int removed = Diagnostics.pruneOldDumps(dir.toFile(), 10);

    assertEquals(15, removed, "应删除最旧的 15 份");
    File[] remaining = dir.toFile().listFiles((d, name) -> name.startsWith("dump-"));
    assertEquals(10, remaining.length, "保留份数必须等于上限");
    for (File file : remaining) {
      assertTrue(file.lastModified() >= 1_015_000L, "保留的必须是最新的 10 份：" + file.getName());
    }
    assertTrue(unrelated.isFile(), "非 dump 文件不得被清理");

    // 未超上限时不做任何删除
    assertEquals(0, Diagnostics.pruneOldDumps(dir.toFile(), 20), "未超上限时不应删除");
  }
}