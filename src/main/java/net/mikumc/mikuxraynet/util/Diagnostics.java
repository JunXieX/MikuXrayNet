package net.mikumc.mikuxraynet.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.mikumc.mikuxraynet.MikuXrayNet;
import net.mikumc.mikuxraynet.antixray.ObfuscatedChunkIndex;
import net.mikumc.mikuxraynet.antixray.ProximityStats;
import net.mikumc.mikuxraynet.antixray.RevealedSet;
import net.mikumc.mikuxraynet.antixray.RewriteStats;
import net.mikumc.mikuxraynet.bandwidth.ThrottlePipeline;
import net.mikumc.mikuxraynet.bandwidth.ThrottleStats;
import net.mikumc.mikuxraynet.bootstrap.DependencyGuard;
import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import net.mikumc.mikuxraynet.bootstrap.ProtocolLibHook;
import net.mikumc.mikuxraynet.cache.DiskCacheStats;
import net.mikumc.mikuxraynet.cache.DiskCacheStore;
import net.mikumc.mikuxraynet.concurrency.MikuWorkPool;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.config.MikuConfig;
import org.bukkit.Bukkit;

/**
 * 诊断聚合入口：把反矿透与带宽各模块暴露的计数器、依赖状态与配置有效值汇总为中文状态面板与转储文件。
 *
 * <p><b>零额外开销</b>：各模块只维护 {@code LongAdder}-类轻量计数，本类仅在 {@code status}/{@code dump}
 * 被执行时做一次拉取与字符串拼接，不在封包热路径上做任何统计工作。
 *
 * <p><b>可测</b>：状态/转储的格式化是纯函数 {@link #formatStatus(Snapshot)} /
 * {@link #formatDump(Snapshot, String)}，可给定固定计数直接断言输出；实例方法只负责拉取实时快照。
 */
public final class Diagnostics {

  private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  /** 全部指标与配置的不可变快照（测试可构造固定值断言格式）。 */
  public record Snapshot(
      boolean packetEventsReady,
      boolean protocolLibReady,
      boolean folia,
      boolean antiXrayActive,
      int bypassPlayers,
      long cacheHits,
      long cacheMisses,
      int cacheEntries,
      long chunksRewritten,
      long blocksReplaced,
      long chunksSkipped,
      long chunksFailed,
      long writeBackFailures,
      long chunksTimedOut,
      long revealsSent,
      long revealsSkipped,
      long revealsUnregistered,
      long entityPacketsCancelled,
      long entityPacketsPassed,
      long blockMergeBatches,
      long blockChangesMerged,
      long blockChangesPassed,
      long entitiesHidden,
      long entitiesShown,
      int entitiesHiddenNow,
      long recheckSubmitted,
      long recheckHidden,
      long recheckShown,
      int afkPlayers,
      long afkEntered,
      long afkPacketsDropped,
      long viewDistanceReduced,
      long viewDistanceRestored,
      int poolThreads,
      int poolActive,
      int queueSize,
      int queueCapacity,
      String serverVersion,
      String javaVersion,
      AntiXrayConfig antiXray,
      BandwidthConfig bandwidth,
      long proximityFrustumCulled,
      long proximityRayCulled,
      long diskCacheHits,
      long diskCacheMisses,
      int diskCacheEntries,
      int diskCacheOpenFiles,
      int obfuscatedChunkCount,
      int obfuscatedPositionCount,
      int revealedPositionCount,
      long revealedRegisteredTotal,
      long indexEvictedByCapacity,
      long revealedDroppedByCapacity) {

    /** 配置指纹（取自反矿透配置；无配置时为 0）。 */
    public int configFingerprint() {
      return antiXray == null ? 0 : antiXray.configHash();
    }
  }

  private final MikuXrayNet plugin;

  public Diagnostics(MikuXrayNet plugin) {
    this.plugin = plugin;
  }

  /** 拉取实时指标并生成状态面板（全中文，顺序稳定）。 */
  public List<String> statusLines() {
    return formatStatus(snapshot());
  }

  /**
   * 拉取实时指标并生成「单行运行摘要」（{@code bandwidth.yml: diagnostics.interval-seconds} 驱动的
   * 周期日志用）：复用 {@link #snapshot()}，只输出最关键的计数，绝不输出整个状态面板刷屏。
   */
  public String summaryLine() {
    return formatSummaryLine(snapshot());
  }

  /** 单行运行摘要格式化（纯函数，恒为一行，不含换行）。 */
  public static String formatSummaryLine(Snapshot s) {
    return "运行摘要：反矿透 " + (s.antiXrayActive() ? "生效" : "未生效")
        + "｜区块改写 " + s.chunksRewritten() + "（异常 " + s.chunksFailed()
        + "，超时放行 " + s.chunksTimedOut() + "）"
        + "｜显形发送 " + s.revealsSent() + "，坐标跳过 " + s.revealsSkipped()
        + "｜实体隐藏 " + s.entitiesHidden() + "/恢复 " + s.entitiesShown()
        + "（当前隐藏中 " + s.entitiesHiddenNow() + "）"
        + "｜磁盘缓存命中 " + s.diskCacheHits() + "，条目约 " + s.diskCacheEntries()
        + "｜队列 " + s.queueSize() + "/" + s.queueCapacity();
  }

  /** 拉取实时指标并生成转储文本。 */
  public String dumpText(String timestamp) {
    return formatDump(snapshot(), timestamp);
  }

  /** 生成并写入转储文件，返回文件对象（目录不存在会自动创建）。 */
  public File writeDump() throws IOException {
    String stamp = LocalDateTime.now().format(FILE_STAMP);
    File folder = plugin.getDataFolder();
    if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
      throw new IOException("无法创建数据目录：" + folder.getAbsolutePath());
    }
    File file = new File(folder, "dump-" + stamp + ".txt");
    Files.writeString(file.toPath(), dumpText(stamp), StandardCharsets.UTF_8);
    return file;
  }

  /** 从插件各模块拉取一次实时快照；任何缺失模块按 0 / 未启用处理。 */
  public Snapshot snapshot() {
    ProtocolLibHook hook = plugin.protocolLibHook();
    RewriteStats rewriteStats = hook == null ? null : hook.stats();
    ThrottlePipeline pipeline = plugin.bandwidthPipeline();
    ThrottleStats throttleStats = pipeline == null ? null : pipeline.stats();
    ProximityStats proximityStats = plugin.proximityStats();
    BypassRegistry bypass = plugin.bypassRegistry();
    MikuWorkPool pool = plugin.workPool();
    DiskCacheStore diskCache = plugin.diskCacheStore();
    DiskCacheStats diskStats = diskCache == null ? null : diskCache.stats();
    ObfuscatedChunkIndex chunkIndex = plugin.obfuscatedChunkIndex();
    RevealedSet revealedSet = plugin.revealedSet();
    MikuConfig config = plugin.mikuConfig();

    return new Snapshot(
        DependencyGuard.isPacketEventsPresent(),
        DependencyGuard.isProtocolLibPresent(),
        PlatformSupport.isFolia(),
        plugin.antiXrayActive(),
        bypass == null ? 0 : bypass.size(),
        hook == null ? 0L : hook.cacheHits(),
        hook == null ? 0L : hook.cacheMisses(),
        hook == null ? 0 : hook.cacheSize(),
        rewriteStats == null ? 0L : rewriteStats.chunksRewritten.sum(),
        rewriteStats == null ? 0L : rewriteStats.blocksReplaced.sum(),
        rewriteStats == null ? 0L : rewriteStats.chunksSkipped.sum(),
        rewriteStats == null ? 0L : rewriteStats.chunksFailed.sum(),
        rewriteStats == null ? 0L : rewriteStats.writeBackFailures.sum(),
        rewriteStats == null ? 0L : rewriteStats.chunksTimedOut.sum(),
        proximityStats == null ? 0L : proximityStats.revealsSent.sum(),
        proximityStats == null ? 0L : proximityStats.revealsSkipped.sum(),
        proximityStats == null ? 0L : proximityStats.unregistered.sum(),
        throttleStats == null ? 0L : throttleStats.entityPacketsCancelled.sum(),
        throttleStats == null ? 0L : throttleStats.entityPacketsPassed.sum(),
        throttleStats == null ? 0L : throttleStats.blockMergeBatches.sum(),
        throttleStats == null ? 0L : throttleStats.blockChangesMerged.sum(),
        throttleStats == null ? 0L : throttleStats.blockChangesPassed.sum(),
        throttleStats == null ? 0L : throttleStats.entitiesHidden.sum(),
        throttleStats == null ? 0L : throttleStats.entitiesShown.sum(),
        pipeline == null ? 0 : pipeline.hiddenEntityCount(),
        throttleStats == null ? 0L : throttleStats.recheckSubmitted.sum(),
        throttleStats == null ? 0L : throttleStats.recheckHidden.sum(),
        throttleStats == null ? 0L : throttleStats.recheckShown.sum(),
        pipeline == null ? 0 : pipeline.afkPlayerCount(),
        throttleStats == null ? 0L : throttleStats.afkEntered.sum(),
        throttleStats == null ? 0L : throttleStats.afkPacketsDropped.sum(),
        throttleStats == null ? 0L : throttleStats.viewDistanceReduced.sum(),
        throttleStats == null ? 0L : throttleStats.viewDistanceRestored.sum(),
        pool == null ? 0 : pool.poolSize(),
        pool == null ? 0 : pool.activeCount(),
        pool == null ? 0 : pool.queueSize(),
        pool == null ? 0 : pool.queueCapacity(),
        Bukkit.getName() + " " + Bukkit.getMinecraftVersion(),
        System.getProperty("java.version", "未知"),
        config == null ? null : config.antiXray(),
        config == null ? null : config.bandwidth(),
        proximityStats == null ? 0L : proximityStats.revealsFrustumCulled.sum(),
        proximityStats == null ? 0L : proximityStats.revealsRayCulled.sum(),
        diskStats == null ? 0L : diskStats.hits.sum(),
        diskStats == null ? 0L : diskStats.misses.sum(),
        diskCache == null ? 0 : diskCache.entries(),
        diskCache == null ? 0 : diskCache.openRegionFiles(),
        chunkIndex == null ? 0 : chunkIndex.chunkCount(),
        chunkIndex == null ? 0 : chunkIndex.positionCount(),
        revealedSet == null ? 0 : revealedSet.positionCount(),
        revealedSet == null ? 0L : revealedSet.registeredTotal(),
        chunkIndex == null ? 0L : chunkIndex.evictedByCapacity(),
        revealedSet == null ? 0L : revealedSet.droppedByCapacity());
  }

  /** 状态面板格式化（纯函数）。 */
  public static List<String> formatStatus(Snapshot s) {
    List<String> lines = new ArrayList<>();
    lines.add("==== MikuXrayNet 运行状态 ====");
    lines.add("依赖：PacketEvents " + (s.packetEventsReady() ? "就绪" : "缺失")
        + "｜ProtocolLib " + (s.protocolLibReady() ? "就绪" : "缺失")
        + "｜平台 " + (s.folia() ? "Folia" : "Paper/Spigot"));
    lines.add("反矿透：" + (s.antiXrayActive() ? "已生效" : "未生效")
        + "｜直通玩家 " + s.bypassPlayers() + " 名");
    lines.add("改写缓存：命中 " + s.cacheHits() + "，未命中 " + s.cacheMisses()
        + "，命中率 " + hitRate(s.cacheHits(), s.cacheMisses()) + "，条目 " + s.cacheEntries());
    lines.add("区块改写：改写 " + s.chunksRewritten() + "，替换方块 " + s.blocksReplaced()
        + "，跳过 " + s.chunksSkipped()
        + "，异常 " + s.chunksFailed() + "，写回失败 " + s.writeBackFailures()
        + "，超时放行 " + s.chunksTimedOut());
    // 注意「坐标跳过」与「整块跳过」是两件事：前者是候选坐标因区块未加载 / 发包失败被跳过，
    // 后者是「整块都已显形」的区块被整体略过（只出现在一次性「首次显形诊断」日志里）。
    // 只写「跳过」会被误读成「整块跳过为 0 → 整块跳过没生效」，故此处写明「坐标跳过」。
    lines.add("邻近显形：发送 " + s.revealsSent() + "，坐标跳过 " + s.revealsSkipped()
        + "，变更注销 " + s.revealsUnregistered() + "，视锥剔除 " + s.proximityFrustumCulled()
        + "，射线剔除 " + s.proximityRayCulled());
    // 口径说明：这里刻意分列「实时坐标数」与「累计登记数」——前者随登出 / 过期 / 区块失效归零，
    // 后者只增不减。若只显示实时值，玩家中途重登或走远后被清理时会看到「发送 N 但已显形 0」，
    // 极易被误读成「显形路径没有登记」（实际登记在发包成功后必然发生）。
    lines.add("显形索引：伪装区块 " + s.obfuscatedChunkCount() + "（坐标 " + s.obfuscatedPositionCount()
        + "）｜已显形 坐标 " + s.revealedPositionCount() + "（当前在线）｜累计登记 "
        + s.revealedRegisteredTotal()
        + "｜安全阀触发 " + s.indexEvictedByCapacity() + "/" + s.revealedDroppedByCapacity()
        + "（正常运营下应为 0，触发即说明有 bug）");
    lines.add("磁盘缓存：" + (s.diskCacheOpenFiles() > 0 || s.diskCacheEntries() > 0 ? "已启用" : "无数据")
        + "｜命中 " + s.diskCacheHits() + "，未命中 " + s.diskCacheMisses()
        + "，命中率 " + hitRate(s.diskCacheHits(), s.diskCacheMisses())
        + "，条目约 " + s.diskCacheEntries() + "，打开区域文件 " + s.diskCacheOpenFiles());
    lines.add("带宽：零位移取消 " + s.entityPacketsCancelled() + "，合并批次 " + s.blockMergeBatches()
        + "（合并 " + s.blockChangesMerged() + " 条），实体隐藏 " + s.entitiesHidden()
        + "/恢复 " + s.entitiesShown() + "（当前隐藏中 " + s.entitiesHiddenNow()
        + "；两者之差 = 死亡/卸载被服务端自然回收 + 仍在隐藏）");
    // 「复检」单列：累计隐藏混合了「入场即被遮挡」与「周期复检发现新遮挡」两条来源，
    // 只看总数无法判断「先可见、之后才被挡住」的实体是否真被收敛到隐藏（本次缺陷的观测口径）。
    lines.add("带宽：实体复检 " + s.recheckSubmitted()
        + "（复检致隐藏 " + s.recheckHidden() + "，复检致恢复 " + s.recheckShown() + "）");
    lines.add("带宽：AFK 玩家 " + s.afkPlayers() + "（累计进入 " + s.afkEntered() + "），AFK 丢包 "
        + s.afkPacketsDropped() + "，降视距 " + s.viewDistanceReduced()
        + "/还原 " + s.viewDistanceRestored());
    lines.add(bandwidthSwitches(s.bandwidth()));
    lines.add("线程池：线程 " + s.poolThreads() + "，活动 " + s.poolActive()
        + "，队列 " + s.queueSize() + "/" + s.queueCapacity());
    return lines;
  }

  /** 带宽各模块开关回显（一行，供 status/dump 看出每个开关的实际取值）。 */
  private static String bandwidthSwitches(BandwidthConfig c) {
    if (c == null) {
      return "带宽开关：配置未加载";
    }
    return "带宽开关：总开关 " + c.enabled()
        + "｜零位移取消 " + c.entityPackets().enabled()
        + "｜变更合并 " + c.blockChanges().enabled()
        + "｜调色板重排 " + paletteSwitch(c.palette())
        + "｜实体剔除 " + c.entityCulling().enabled()
        + "｜AFK 降级 " + c.afk().enabled()
        + "｜高延迟降视距 " + c.latency().enabled();
  }

  /**
   * 调色板重排的开关回显。
   *
   * <p><b>为什么不直接打印 {@code palette.enabled()}</b>：调色板模块有「总开关」与「行为开关 reorder」
   * 两层，真正的重排只由 {@code reorder} 决定（默认 false，实测重排会使压缩字节变大）。
   * 只打印模块总开关时会出现「调色板重排 true」这种极易被误读为「重排已启用」的输出，
   * 因此这里把两层的实际效果合成一句无歧义的中文。
   */
  private static String paletteSwitch(BandwidthConfig.Palette palette) {
    if (!palette.enabled()) {
      return "关闭（模块未启用，不做任何重排）";
    }
    if (!palette.reorder()) {
      return "关闭（模块启用但 reorder=false，不做任何重排）";
    }
    return "启用（reorder=true）";
  }

  /** 转储文本格式化（纯函数）：状态面板 + 环境信息 + 配置项有效值。 */
  public static String formatDump(Snapshot s, String timestamp) {
    StringBuilder sb = new StringBuilder();
    sb.append("MikuXrayNet 诊断转储\n");
    sb.append("导出时间：").append(timestamp).append('\n');
    sb.append("服务端：").append(s.serverVersion())
        .append(s.folia() ? "（Folia 系，区域化多线程）" : "（Paper 系，单主线程）").append('\n');
    sb.append("JVM：").append(s.javaVersion()).append('\n');
    sb.append("配置指纹：").append(s.configFingerprint()).append('\n');
    sb.append('\n');
    for (String line : formatStatus(s)) {
      sb.append(line).append('\n');
    }
    sb.append('\n').append("---- 配置项有效值 ----\n");
    appendAntiXray(sb, s.antiXray());
    appendBandwidth(sb, s.bandwidth());
    return sb.toString();
  }

  private static void appendAntiXray(StringBuilder sb, AntiXrayConfig c) {
    sb.append("[antixray]\n");
    if (c == null) {
      sb.append("未加载（可能缺少 PacketEvents 或配置解析失败）\n");
      return;
    }
    sb.append("enabled=").append(c.enabled()).append('\n');
    sb.append("worlds=").append(c.worlds()).append('\n');
    sb.append("hide-blocks=").append(c.hideBlocks().size()).append(" 种：")
        .append(String.join(", ", c.hideBlocks())).append('\n');
    sb.append("replacement-weights=").append(c.replacementWeights()).append('\n');
    sb.append("layer-obfuscation=").append(c.layerObfuscation())
        .append("，remove-block-entities=").append(c.removeBlockEntities())
        .append("，obfuscation.mode=").append(c.obfuscationMode()).append('\n');
    sb.append("neighbors.enabled=").append(c.neighbors().enabled())
        .append("，missing-policy=").append(c.neighbors().missingPolicy())
        .append("，cache-maximum-size=").append(c.neighbors().cacheMaximumSize()).append('\n');
    sb.append("occlusion.extra-occluding=").append(c.occlusion().extraOccluding())
        .append("，extra-non-occluding=").append(c.occlusion().extraNonOccluding()).append('\n');
    sb.append("proximity.enabled=").append(c.proximity().enabled())
        .append("，distance=").append(c.proximity().distance())
        .append("，interval-ticks=").append(c.proximity().intervalTicks())
        .append("，max-reveals-per-tick=").append(c.proximity().maxRevealsPerTick())
        .append("，expire-seconds=").append(c.proximity().expireSeconds()).append('\n');
    sb.append("proximity.max-positions=").append(c.proximity().maxPositions())
        .append("，max-positions-per-player=").append(c.proximity().maxPositionsPerPlayer()).append('\n');
    sb.append("proximity.frustum.enabled=").append(c.proximity().frustumEnabled())
        .append("，fov=").append(c.proximity().frustumFov())
        .append("，min-distance=").append(c.proximity().frustumMinDistance())
        .append("，raycast.enabled=").append(c.proximity().raycastEnabled())
        .append("，raycast.samples=").append(c.proximity().raycastSamples()).append('\n');
    sb.append("disk-cache.enabled=").append(c.diskCache().enabled())
        .append("，max-entries=").append(c.diskCache().maxEntries())
        .append("，max-file-size-mb=").append(c.diskCache().maxFileSizeMb())
        .append("，expire-seconds=").append(c.diskCache().expireSeconds()).append('\n');
    sb.append("disk-cache.bucket-cache-size=").append(c.diskCache().bucketCacheSize())
        .append("，idle-close-seconds=").append(c.diskCache().idleCloseSeconds())
        .append("，maintenance-interval-seconds=").append(c.diskCache().maintenanceIntervalSeconds())
        .append("，compact-per-pass=").append(c.diskCache().compactPerPass())
        .append("，queue-capacity=").append(c.diskCache().queueCapacity())
        .append("，generation-tracker-size=").append(c.diskCache().generationTrackerSize()).append('\n');
    sb.append("cache.maximum-size=").append(c.cacheMaximumSize())
        .append("，expire-after-access-seconds=").append(c.cacheExpireAfterAccessSeconds()).append('\n');
    sb.append("advanced.threads=").append(c.threads())
        .append("，timeout-millis=").append(c.timeoutMillis())
        .append("，queue-capacity=").append(c.queueCapacity()).append('\n');
    sb.append("config-hash=").append(c.configHash()).append('\n');
  }

  private static void appendBandwidth(StringBuilder sb, BandwidthConfig c) {
    sb.append("[bandwidth]\n");
    if (c == null) {
      sb.append("未加载（配置解析失败）\n");
      return;
    }
    sb.append("enabled=").append(c.enabled()).append('\n');
    sb.append("entity-packets.enabled=").append(c.entityPackets().enabled())
        .append("，skip-zero-movement=").append(c.entityPackets().skipZeroMovement())
        .append("，whitelist=").append(c.entityPackets().whitelist()).append('\n');
    sb.append("block-changes.enabled=").append(c.blockChanges().enabled())
        .append("，merge=").append(c.blockChanges().merge())
        .append("，merge-radius=").append(c.blockChanges().mergeRadius())
        .append("，max-per-packet=").append(c.blockChanges().maxPerPacket())
        .append("，merge-window-millis=").append(c.blockChanges().mergeWindowMillis())
        .append("，immediate-radius=").append(c.blockChanges().immediateRadius())
        .append("，resend-on-overflow=").append(c.blockChanges().resendOnOverflow())
        .append("，max-pending-entries=").append(c.blockChanges().maxPendingEntries()).append('\n');
    sb.append("palette.enabled=").append(c.palette().enabled())
        .append("，reorder=").append(c.palette().reorder())
        .append("，strict-verify=").append(c.palette().strictVerify()).append('\n');
    sb.append("entity-culling.enabled=").append(c.entityCulling().enabled())
        .append("，raycast=").append(c.entityCulling().raycast())
        .append("，force-visible-distance=").append(c.entityCulling().forceVisibleDistance())
        .append("，threads=").append(c.entityCulling().threads())
        .append("，update-interval-ticks=").append(c.entityCulling().updateIntervalTicks())
        .append("，recheck-budget=").append(c.entityCulling().recheckBudget())
        .append("，ray-samples=").append(c.entityCulling().raySamples()).append('\n');
    sb.append("afk.enabled=").append(c.afk().enabled())
        .append("，seconds=").append(c.afk().seconds())
        .append("，distance=").append(c.afk().distance())
        .append("，drop-particles=").append(c.afk().dropParticles())
        .append("，drop-block-break-animation=").append(c.afk().dropBlockBreakAnimation()).append('\n');
    sb.append("latency.enabled=").append(c.latency().enabled())
        .append("，threshold-millis=").append(c.latency().thresholdMillis())
        .append("，sustain-seconds=").append(c.latency().sustainSeconds())
        .append("，reduce-view-distance=").append(c.latency().reduceViewDistance())
        .append("，min-view-distance=").append(c.latency().minViewDistance())
        .append("，check-interval-seconds=").append(c.latency().checkIntervalSeconds()).append('\n');
    sb.append("diagnostics.interval-seconds=").append(c.diagnostics().intervalSeconds()).append('\n');
  }

  /** 缓存命中率文本；无采样时为 0.0%。 */
  private static String hitRate(long hits, long misses) {
    long total = hits + misses;
    if (total <= 0L) {
      return "0.0%";
    }
    return String.format(Locale.ROOT, "%.1f%%", hits * 100.0D / total);
  }
}