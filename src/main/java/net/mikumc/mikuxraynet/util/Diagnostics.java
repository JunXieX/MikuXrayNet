package net.mikumc.mikuxraynet.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.mikumc.mikuxraynet.AntiXrayRuntime;
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
 * 被执行、以及周期运行摘要（{@link #summaryLine()}）定时触发时做一次拉取与字符串拼接，
 * 不在封包热路径上做任何统计工作。
 *
 * <p><b>可测</b>：状态/转储的格式化是纯函数 {@link #formatStatus(Snapshot)} /
 * {@link #formatDump(Snapshot, String)}，可给定固定计数直接断言输出；实例方法只负责拉取实时快照。
 */
public final class Diagnostics {

  /**
   * 转储文件名/导出时间的时间戳格式。
   *
   * <p><b>为什么带毫秒</b>：旧实现只到秒，同一秒内连续执行两次 {@code dump} 会用同一个文件名，
   * 后一次<b>静默覆盖</b>前一次——排查「连续两次 dump 对比」时证据就没了。加上毫秒后同秒不再撞名；
   * 极端情况（同一毫秒两次、或系统时钟回拨）由 {@link #newDumpFile} 追加递增序号兜底，绝不覆盖。
   */
  private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

  /**
   * 转储文件的保留上限：目录里最多保留这么多份 {@code dump-*.txt}，超出后按修改时间删除最旧的。
   *
   * <p><b>为什么需要上限</b>：转储是排查工具、不是运行数据，长期反复执行会无限累积（每份几十 KB）。
   * 取 20 份足以覆盖「近期多次排查」的对照需求，同时把目录占用限制在一个可预期的量级。
   */
  static final int MAX_DUMP_FILES = 20;

  /**
   * 全部指标与配置的不可变快照（测试可构造固定值断言格式）。
   *
   * <p><b>为什么拆嵌套 record</b>：旧的 53 参平铺构造在测试里只能靠「数逗号」对位，极易错位；
   * 现按域分组（环境 / 改写 / 邻近显形 / 带宽 / 线程池 / 磁盘缓存），各组自带空对象兜底
   * （{@code EMPTY}），来源模块未启用时快照直接复用零值组，不再逐字段写 {@code ? null : 0}。
   * 格式化输出与拆分前逐字符一致（由 DiagnosticsTest 锁定）。
   */
  public record Snapshot(
      Env env,
      Rewrite rewrite,
      Proximity proximity,
      Index index,
      Throttle throttle,
      Pool pool,
      DiskCache diskCache,
      AntiXrayConfig antiXray,
      BandwidthConfig bandwidth) {

    /** 配置指纹（取自反矿透配置；无配置时为 0）。 */
    public int configFingerprint() {
      return antiXray == null ? 0 : antiXray.configHash();
    }

    /** 依赖与运行环境（依赖就绪性、平台、直通玩家数、版本标识）。 */
    public record Env(boolean packetEventsReady, boolean protocolLibReady, boolean folia,
        boolean antiXrayActive, int bypassPlayers, String serverVersion, String javaVersion,
        String pluginVersion) {
    }

    /**
     * 区块改写域：改写缓存（按区块共享）、改写计数与字节口径统计（P0-1）。
     *
     * <p>字节口径只统计真正改写的区块：{@code bytesSaved = bytesOriginal − bytesOutput}，
     * 可能为负（升位/扩容时输出更大）。{@code paletteBitsSummary} 为改写 section 的
     * bitsPerBlock 直方图摘要（dump 展示封顶/裁剪/降位效果；无采样为「无采样」）。
     */
    public record Rewrite(long cacheHits, long cacheMisses, int cacheEntries,
        long chunksRewritten, long blocksReplaced, long chunksSkipped, long chunksFailed,
        long writeBackFailures, long chunksTimedOut, long chunksSkippedQueueFull,
        long bytesOriginal, long bytesOutput, long bytesSaved, String paletteBitsSummary) {

      /** 反矿透未启用时的零值兜底。 */
      public static final Rewrite EMPTY =
          new Rewrite(0L, 0L, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "无采样");
    }

    /** 邻近显形域：显形发包与视锥/射线剔除计数，以及过度显形的抽样统计。 */
    public record Proximity(long revealsSent, long revealsSkipped, long revealsUnregistered,
        long frustumCulled, long rayCulled, long overRevealSampled, long overRevealWasted) {

      /** 邻近显形未启用时的零值兜底。 */
      public static final Proximity EMPTY = new Proximity(0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /** 显形索引域：伪装区块索引（按区块共享）与按玩家的已显形集合的持有量与安全阀计数。 */
    public record Index(int obfuscatedChunkCount, int obfuscatedPositionCount,
        int revealedPositionCount, long revealedRegisteredTotal,
        long indexEvictedByCapacity, long revealedDroppedByCapacity) {

      /** 显形索引未启用时的零值兜底。 */
      public static final Index EMPTY = new Index(0, 0, 0, 0L, 0L, 0L);
    }

    /**
     * 带宽域：零位移取消、变更合并、实体剔除（含复检与视锥口径）、元数据剔除与 AFK / 降视距计数。
     *
     * @param frustumHidden             因「视野锥外 + 超出距离门」被隐藏的实体数（视锥剔除子项）
     * @param frustumShown              转头后经复检恢复的视锥隐藏实体数（视锥剔除子项的恢复侧）
     * @param entityMetadataCancelled   整包冗余、被直接取消的实体元数据包数
     * @param entityMetadataDropped     包仍要发、但其中被剔除的冗余条目数
     * @param entityMetadataEvicted     元数据缓存「每玩家条目数」安全阀清空次数（正常应接近 0）
     */
    public record Throttle(long entityPacketsCancelled, long entityPacketsPassed,
        long blockMergeBatches, long blockChangesMerged, long blockChangesPassed,
        long entitiesHidden, long entitiesShown, int entitiesHiddenNow,
        long recheckSubmitted, long recheckHidden, long recheckShown,
        long frustumHidden, long frustumShown,
        long entityMetadataCancelled, long entityMetadataDropped, long entityMetadataEvicted,
        int afkPlayers, long afkEntered, long afkPacketsDropped,
        long viewDistanceReduced, long viewDistanceRestored,
        long blockMergeFlushes, long blockMergeFlushNanos) {

      /** 带宽优化未启用时的零值兜底。 */
      public static final Throttle EMPTY = new Throttle(
          0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /** 工作线程池域：线程数、活动数与队列占用。 */
    public record Pool(int threads, int active, int queueSize, int queueCapacity) {

      /** 工作线程池未启用时的零值兜底。 */
      public static final Pool EMPTY = new Pool(0, 0, 0, 0);
    }

    /**
     * 磁盘缓存域：命中、持有量、打开的区域文件数与「为什么没命中 / 命中了却没用上」的拆解计数。
     *
     * <p>{@code expiredRemoved} / {@code payloadRejected} / {@code rejectedBy*} 是排查「命中率异常」的关键：
     * 过期清理说明条目活不过配置的 expire-seconds（配短了等于关掉缓存），
     * 写入被拒说明容量/文件大小上限在拦，{@code payloadRejected} 说明读到的负载与本次要改写的字节不符
     * （内容真变了，或信封损坏）。
     */
    public record DiskCache(long hits, long misses, int entries, int openFiles,
        long expiredRemoved, long payloadRejected, long rejectedByCapacity, long rejectedBySize,
        long rejectedByBacklog, long errors) {

      /** 磁盘缓存未启用时的零值兜底。 */
      public static final DiskCache EMPTY =
          new DiskCache(0L, 0L, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L);
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
    return "运行摘要：反矿透 " + (s.env().antiXrayActive() ? "生效" : "未生效")
        + "｜区块改写 " + s.rewrite().chunksRewritten() + "（异常 " + s.rewrite().chunksFailed()
        + "，超时放行 " + s.rewrite().chunksTimedOut() + "）"
        + "｜显形发送 " + s.proximity().revealsSent() + "，坐标跳过 " + s.proximity().revealsSkipped()
        // 与状态面板/转储用同一口径：合并计的是「原包数」而非「合并结果的条数」
        + "｜变更合并 " + s.throttle().blockMergeBatches()
        + "（合并 " + s.throttle().blockChangesMerged() + " 个原包）"
        + "｜实体隐藏 " + s.throttle().entitiesHidden() + "/恢复 " + s.throttle().entitiesShown()
        + "（当前隐藏中 " + s.throttle().entitiesHiddenNow() + "）"
        + "｜磁盘缓存命中 " + s.diskCache().hits() + "，条目约 " + s.diskCache().entries()
        + "｜队列 " + s.pool().queueSize() + "/" + s.pool().queueCapacity();
  }

  /** 拉取实时指标并生成转储文本。 */
  public String dumpText(String timestamp) {
    return formatDump(snapshot(), timestamp);
  }

  /** 生成并写入转储文件，返回文件对象（目录不存在会自动创建；超出保留上限时清理最旧的转储）。 */
  public File writeDump() throws IOException {
    String stamp = LocalDateTime.now().format(FILE_STAMP);
    File folder = plugin.getDataFolder();
    if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
      throw new IOException("无法创建数据目录：" + folder.getAbsolutePath());
    }
    File file = newDumpFile(folder, stamp);
    Files.writeString(file.toPath(), dumpText(stamp), StandardCharsets.UTF_8);
    int removed = pruneOldDumps(folder, MAX_DUMP_FILES);
    if (removed > 0) {
      // 只做一次 INFO：清理是正常行为，不需要 WARN 级别的噪音
      plugin.getLogger().info("诊断转储保留最近 " + MAX_DUMP_FILES + " 份，已清理最旧的 " + removed + " 份");
    }
    return file;
  }

  /**
   * 生成本次转储的文件对象（不写入），文件名形如 {@code dump-<stamp>.txt}。
   *
   * <p>同名已存在时追加 {@code -1}、{@code -2}… 直到不冲突：毫秒戳已基本消除同秒撞名，这里是兜底，
   * 保证任何情况下都<b>不会覆盖</b>已有转储。
   */
  static File newDumpFile(File folder, String stamp) {
    File file = new File(folder, "dump-" + stamp + ".txt");
    int suffix = 1;
    while (file.exists()) {
      file = new File(folder, "dump-" + stamp + "-" + suffix++ + ".txt");
    }
    return file;
  }

  /**
   * 把目录里的 {@code dump-*.txt} 控制在 {@code keep} 份以内，按修改时间删除最旧的。
   *
   * <p>fail-open：只处理本插件命名的转储文件（不碰其它文件），删除失败也不抛异常（最多晚一轮再清）。
   *
   * @return 实际删除的份数
   */
  static int pruneOldDumps(File folder, int keep) {
    int limit = Math.max(1, keep);
    File[] dumps = folder.listFiles(
        (dir, name) -> name.startsWith("dump-") && name.endsWith(".txt"));
    if (dumps == null || dumps.length <= limit) {
      return 0;
    }
    Arrays.sort(dumps, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
    int removed = 0;
    for (int i = 0; i < dumps.length - limit; i++) {
      if (dumps[i].delete()) {
        removed++;
      }
    }
    return removed;
  }

  /** 从插件各模块拉取一次实时快照；任何缺失模块按 0 / 未启用处理（各组空对象兜底）。 */
  public Snapshot snapshot() {
    // 反矿透侧组件集中在 AntiXrayRuntime（B1 拆分后主类不再直接持有这些字段）
    AntiXrayRuntime runtime = plugin.antiXrayRuntime();
    ProtocolLibHook hook = runtime == null ? null : runtime.protocolLibHook();
    ThrottlePipeline pipeline = plugin.bandwidthPipeline();
    ProximityStats proximityStats = runtime == null ? null : runtime.proximityStats();
    BypassRegistry bypass = runtime == null ? null : runtime.bypassRegistry();
    MikuWorkPool pool = runtime == null ? null : runtime.workPool();
    DiskCacheStore diskCache = runtime == null ? null : runtime.diskCacheStore();
    ObfuscatedChunkIndex chunkIndex = runtime == null ? null : runtime.obfuscatedChunkIndex();
    RevealedSet revealedSet = runtime == null ? null : runtime.revealedSet();
    MikuConfig config = plugin.mikuConfig();
    ThrottleStats throttleStats = pipeline == null ? null : pipeline.stats();

    // 各域来源未启用时直接复用空对象，快照构造里不再出现逐字段的「? null : 0」
    RewriteStats rewriteStats = hook == null ? null : hook.stats();
    Snapshot.Rewrite rewrite = rewriteStats == null ? Snapshot.Rewrite.EMPTY
        : new Snapshot.Rewrite(hook.cacheHits(), hook.cacheMisses(), hook.cacheSize(),
            rewriteStats.chunksRewritten.sum(), rewriteStats.blocksReplaced.sum(),
            rewriteStats.chunksSkipped.sum(), rewriteStats.chunksFailed.sum(),
            rewriteStats.writeBackFailures.sum(), rewriteStats.chunksTimedOut.sum(),
            rewriteStats.chunksSkippedQueueFull.sum(),
            rewriteStats.bytesOriginal.sum(), rewriteStats.bytesOutput.sum(),
            rewriteStats.bytesSaved.sum(), rewriteStats.paletteBitsSummary());
    Snapshot.Proximity proximity = proximityStats == null ? Snapshot.Proximity.EMPTY
        : new Snapshot.Proximity(proximityStats.revealsSent.sum(), proximityStats.revealsSkipped.sum(),
            proximityStats.unregistered.sum(), proximityStats.revealsFrustumCulled.sum(),
            proximityStats.revealsRayCulled.sum(), proximityStats.overRevealSampled.sum(),
            proximityStats.overRevealWasted.sum());
    Snapshot.Index index = chunkIndex == null && revealedSet == null ? Snapshot.Index.EMPTY
        : new Snapshot.Index(
            chunkIndex == null ? 0 : chunkIndex.chunkCount(),
            chunkIndex == null ? 0 : chunkIndex.positionCount(),
            revealedSet == null ? 0 : revealedSet.positionCount(),
            revealedSet == null ? 0L : revealedSet.registeredTotal(),
            chunkIndex == null ? 0L : chunkIndex.evictedByCapacity(),
            revealedSet == null ? 0L : revealedSet.droppedByCapacity());
    Snapshot.Throttle throttle = pipeline == null ? Snapshot.Throttle.EMPTY
        : new Snapshot.Throttle(throttleStats.entityPacketsCancelled.sum(),
            throttleStats.entityPacketsPassed.sum(), throttleStats.blockMergeBatches.sum(),
            throttleStats.blockChangesMerged.sum(), throttleStats.blockChangesPassed.sum(),
            throttleStats.entitiesHidden.sum(), throttleStats.entitiesShown.sum(),
            pipeline.hiddenEntityCount(), throttleStats.recheckSubmitted.sum(),
            throttleStats.recheckHidden.sum(), throttleStats.recheckShown.sum(),
            throttleStats.frustumHidden.sum(), throttleStats.frustumShown.sum(),
            throttleStats.entityMetadataCancelled.sum(),
            throttleStats.entityMetadataEntriesDropped.sum(),
            throttleStats.entityMetadataEvicted.sum(),
            pipeline.afkPlayerCount(), throttleStats.afkEntered.sum(),
            throttleStats.afkPacketsDropped.sum(), throttleStats.viewDistanceReduced.sum(),
            throttleStats.viewDistanceRestored.sum(), throttleStats.blockMergeFlushes.sum(),
            throttleStats.blockMergeFlushNanos.sum());
    Snapshot.Pool poolSnapshot = pool == null ? Snapshot.Pool.EMPTY
        : new Snapshot.Pool(pool.poolSize(), pool.activeCount(), pool.queueSize(),
            pool.queueCapacity());
    Snapshot.DiskCache diskCacheSnapshot = diskCache == null ? Snapshot.DiskCache.EMPTY
        : new Snapshot.DiskCache(diskCache.stats().hits.sum(), diskCache.stats().misses.sum(),
            diskCache.entries(), diskCache.openRegionFiles(),
            diskCache.stats().expiredRemoved.sum(),
            // 「读到但被拒」由改写侧统计（负载解封与字节指纹比对在那里做）：此处借用同一次快照的数值
            rewriteStats == null ? 0L : rewriteStats.diskPayloadRejected.sum(),
            diskCache.stats().rejectedByCapacity.sum(), diskCache.stats().rejectedBySize.sum(),
            diskCache.stats().rejectedByBacklog.sum(),
            diskCache.stats().errors.sum());

    return new Snapshot(
        new Snapshot.Env(DependencyGuard.isPacketEventsPresent(),
            DependencyGuard.isProtocolLibPresent(), PlatformSupport.isFolia(),
            runtime != null && runtime.antiXrayActive(), bypass == null ? 0 : bypass.size(),
            Bukkit.getName() + " " + Bukkit.getMinecraftVersion(),
            System.getProperty("java.version", "未知"),
            pluginVersion(plugin)),
        rewrite,
        proximity,
        index,
        throttle,
        poolSnapshot,
        diskCacheSnapshot,
        config == null ? null : config.antiXray(),
        config == null ? null : config.bandwidth());
  }

  /** 状态面板格式化（纯函数）。 */
  public static List<String> formatStatus(Snapshot s) {
    List<String> lines = new ArrayList<>();
    lines.add("==== MikuXrayNet 运行状态"
        + (s.env().pluginVersion().isEmpty() ? "" : "（v" + s.env().pluginVersion() + "）") + " ====");
    lines.add("依赖：PacketEvents " + (s.env().packetEventsReady() ? "就绪" : "缺失")
        + "｜ProtocolLib " + (s.env().protocolLibReady() ? "就绪" : "缺失")
        + "｜平台 " + (s.env().folia() ? "Folia" : "Paper/Spigot"));
    lines.add("反矿透：" + (s.env().antiXrayActive() ? "已生效" : "未生效")
        + "｜直通玩家 " + s.env().bypassPlayers() + " 名");
    lines.add("反矿透世界黑名单：" + worldBlacklistSummary(s.antiXray()));
    lines.add("改写缓存：命中 " + s.rewrite().cacheHits() + "，未命中 " + s.rewrite().cacheMisses()
        + "，命中率 " + hitRate(s.rewrite().cacheHits(), s.rewrite().cacheMisses())
        + "，条目 " + s.rewrite().cacheEntries());
    lines.add("区块改写：改写 " + s.rewrite().chunksRewritten() + "，替换方块 " + s.rewrite().blocksReplaced()
        + "，跳过 " + s.rewrite().chunksSkipped()
        + "，异常 " + s.rewrite().chunksFailed() + "，写回失败 " + s.rewrite().writeBackFailures()
        + "，超时放行 " + s.rewrite().chunksTimedOut()
        // 「因队列满整块未伪装」单列：它意味着该区块的矿物以真实状态下发（对透视端可见），
        // 是本模块唯一的功能性静默失效，必须与「跳过」区分开。
        + "，队列满未伪装 " + s.rewrite().chunksSkippedQueueFull());
    // 字节口径（P0-1）：只统计真正改写的区块（未改动/失败放行的原样字节不采样，避免稀释比例）。
    // 节省比例 = 节省字节 / 原始字节；开启 palette.width-budget 后这一行应体现「封顶+裁剪」的收益。
    lines.add(bytesLine(s.rewrite()));
    // 注意「坐标跳过」与「整块跳过」是两件事：前者是候选坐标因区块未加载 / 发包失败被跳过，
    // 后者是「整块都已显形」的区块被整体略过（只出现在一次性「首次显形诊断」日志里）。
    // 只写「跳过」会被误读成「整块跳过为 0 → 整块跳过没生效」，故此处写明「坐标跳过」。
    lines.add("邻近显形：发送 " + s.proximity().revealsSent() + "，坐标跳过 " + s.proximity().revealsSkipped()
        + "，变更注销 " + s.proximity().revealsUnregistered() + "，视锥剔除 " + s.proximity().frustumCulled()
        + "，射线剔除 " + s.proximity().rayCulled()
        + "，过度显形（抽样） " + s.proximity().overRevealWasted() + " / 抽样 "
        + s.proximity().overRevealSampled());
    // 口径说明：这里刻意分列「实时坐标数」与「累计登记数」——前者随登出 / 过期 / 区块失效归零，
    // 后者只增不减。若只显示实时值，玩家中途重登或走远后被清理时会看到「发送 N 但已显形 0」，
    // 极易被误读成「显形路径没有登记」（实际登记在发包成功后必然发生）。
    lines.add("显形索引：伪装区块 " + s.index().obfuscatedChunkCount() + "（坐标 "
        + s.index().obfuscatedPositionCount()
        + "）｜已显形 坐标 " + s.index().revealedPositionCount() + "（当前在线）｜累计登记 "
        + s.index().revealedRegisteredTotal()
        + "｜安全阀触发 " + s.index().indexEvictedByCapacity() + "/" + s.index().revealedDroppedByCapacity()
        + "（正常运营下应为 0，触发即说明有 bug）");
    // 「过期清理 / 负载被拒 / 写入被拒 / 异常」是「命中率为什么异常」的自证口径：
    // 过期清理 > 0 说明文件的 expire-seconds 比两次启动的间隔还短（条目全死），
    // 负载被拒 > 0 说明读到的负载与本次要改写的字节不符（内容真变了或信封损坏），
    // 写入被拒 > 0 说明容量/单文件上限在拦（新区块没进缓存），异常 > 0 见日志。
    lines.add("磁盘缓存：" + (s.diskCache().openFiles() > 0 || s.diskCache().entries() > 0 ? "已启用" : "无数据")
        + "｜命中 " + s.diskCache().hits() + "，未命中 " + s.diskCache().misses()
        + "，命中率 " + hitRate(s.diskCache().hits(), s.diskCache().misses())
        + "，条目约 " + s.diskCache().entries() + "，打开区域文件 " + s.diskCache().openFiles()
        + "｜过期清理 " + s.diskCache().expiredRemoved()
        + "，负载被拒 " + s.diskCache().payloadRejected()
        + "，写入被拒 " + (s.diskCache().rejectedByCapacity() + s.diskCache().rejectedBySize())
        // 积压被拒单列：它不代表配置上限在拦，而是磁盘线程跟不上（压缩/落盘成瓶颈），
        // 处理方式与调容量上限完全不同，因此不与「写入被拒」合并。
        + "，积压被拒 " + s.diskCache().rejectedByBacklog()
        + "，异常 " + s.diskCache().errors());
    lines.add("带宽：零位移取消 " + s.throttle().entityPacketsCancelled()
        + "（放行 " + s.throttle().entityPacketsPassed() + "），合并批次 "
        + s.throttle().blockMergeBatches()
        // 「个原包」而非「条」：blockChangesMerged 计的是被合并掉的<b>原始封包数</b>（add(held.size())），
        // 与「合并后发出的包数」（= 合并批次）不是一回事，写「条」极易被读成合并结果的条数。
        + "（合并 " + s.throttle().blockChangesMerged() + " 个原包，放行 "
        + s.throttle().blockChangesPassed() + "）"
        // 单线程冲刷的「次数 + 累计耗时」：极端配置下的延迟瓶颈靠它观测（累计/次数 = 平均一次的成本）
        + "；单线程冲刷 " + s.throttle().blockMergeFlushes() + " 次（累计 "
        + formatMillis(s.throttle().blockMergeFlushNanos()) + " ms，平均 "
        + averageFlushMillis(s.throttle().blockMergeFlushes(), s.throttle().blockMergeFlushNanos())
        + " ms/次）"
        + "，实体隐藏 " + s.throttle().entitiesHidden() + "/恢复 " + s.throttle().entitiesShown()
        + "（当前隐藏中 " + s.throttle().entitiesHiddenNow()
        + "；两者之差 = 死亡/卸载被服务端自然回收 + 仍在隐藏）");
    // 「复检」单列：累计隐藏混合了「入场即被遮挡」与「周期复检发现新遮挡」两条来源，
    // 只看总数无法判断「先可见、之后才被挡住」的实体是否真被收敛到隐藏（本次缺陷的观测口径）。
    lines.add("带宽：实体复检 " + s.throttle().recheckSubmitted()
        + "（复检致隐藏 " + s.throttle().recheckHidden() + "，复检致恢复 " + s.throttle().recheckShown()
        // 视锥剔除单列：它的失效模式是「转头后实体迟一步出现」，与射线剔除（该看见的被藏）完全不同，
        // 分列后管理员据此判断该不该调大 frustum.min-distance 或关掉该子项。
        + "）｜视锥剔除 隐藏 " + s.throttle().frustumHidden() + "/恢复 " + s.throttle().frustumShown());
    lines.add("带宽：元数据剔除 取消 " + s.throttle().entityMetadataCancelled()
        + "（剔除冗余条目 " + s.throttle().entityMetadataDropped()
        + "，安全阀清空 " + s.throttle().entityMetadataEvicted() + "）");
    lines.add("带宽：AFK 玩家 " + s.throttle().afkPlayers() + "（累计进入 " + s.throttle().afkEntered()
        + "），AFK 丢包 " + s.throttle().afkPacketsDropped() + "，降视距 " + s.throttle().viewDistanceReduced()
        + "/还原 " + s.throttle().viewDistanceRestored());
    lines.add(bandwidthSwitches(s.bandwidth()));
    lines.add("线程池：线程 " + s.pool().threads() + "，活动 " + s.pool().active()
        + "，队列 " + s.pool().queueSize() + "/" + s.pool().queueCapacity());
    return lines;
  }

  /**
   * 插件版本号（纯读取，绝不抛异常）：状态面板第一行回显，供「同一份日志到底是哪个构建」对照。
   *
   * <p>优先走 Paper 的 {@code getPluginMeta()}；纯 Bukkit/Spigot 核心没有该方法，回落到已废弃但仍
   * 处处可用的 {@code getDescription()}。两处都失败时返回空串（面板不显示版本，绝不因此报错）。
   */
  private static String pluginVersion(MikuXrayNet plugin) {
    if (plugin == null) {
      return "";
    }
    try {
      return plugin.getPluginMeta().getVersion();
    } catch (Throwable ignored) {
      // 非 Paper 核心：走传统描述
    }
    try {
      return plugin.getDescription().getVersion();
    } catch (Throwable ignored) {
      return "";
    }
  }

  /** 世界黑名单回显（中文，纯函数；status 与 dump 共用）。空列表明确写「未配置」。 */
  private static String worldBlacklistSummary(AntiXrayConfig c) {
    if (c == null) {
      return "配置未加载";
    }
    if (c.worldBlacklist().isEmpty()) {
      return "未配置（所有世界都启用反矿透）";
    }
    return c.worldBlacklist().size() + " 项：" + String.join(", ", c.worldBlacklist())
        + "（名单内世界不使用任何反矿透功能）";
  }

  /**
   * 字节口径行（P0-1）：「区块字节：原始 X → 输出 Y（省 Z 字节 / P.P%）」。
   * 无采样（尚无改写区块）与零/负节省（升位或扩容）都有明确的中文表述，避免被误读成统计失效。
   */
  private static String bytesLine(Snapshot.Rewrite rewrite) {
    if (rewrite.bytesOriginal() <= 0L) {
      return "区块字节：无采样（尚无改写区块）";
    }
    if (rewrite.bytesSaved() > 0L) {
      return "区块字节：原始 " + rewrite.bytesOriginal() + " → 输出 " + rewrite.bytesOutput()
          + "（省 " + rewrite.bytesSaved() + " 字节 / "
          + String.format(Locale.ROOT, "%.1f%%", rewrite.bytesSaved() * 100.0D / rewrite.bytesOriginal())
          + "）";
    }
    return "区块字节：原始 " + rewrite.bytesOriginal() + " → 输出 " + rewrite.bytesOutput()
        + "（无节省" + (rewrite.bytesSaved() < 0L
            ? "，增加 " + (-rewrite.bytesSaved()) + " 字节（升位/扩容）" : "")
        + "）";
  }

  /** 带宽各模块开关回显（一行，供 status/dump 看出每个开关的实际取值）。 */
  private static String bandwidthSwitches(BandwidthConfig c) {
    if (c == null) {
      return "带宽开关：配置未加载";
    }
    return "带宽开关：总开关 " + c.enabled()
        + "｜零位移取消 " + c.entityPackets().enabled()
        + "｜变更合并 " + c.blockChanges().enabled()
        + "｜调色板压缩 " + paletteSwitch(c.palette())
        + "｜实体剔除 " + entityCullingSwitch(c.entityCulling())
        + "｜元数据剔除 " + c.entityMetadata().enabled()
        + "｜AFK 降级 " + afkSwitch(c.afk())
        + "｜高延迟降视距 " + c.latency().enabled();
  }

  /**
   * 实体剔除的开关回显。
   *
   * <p><b>为什么要把射线与视锥两个子项都写出来</b>：只打印 {@code entityCulling().enabled()} 会出现
   * 「实体剔除 true」这种极易被误读为「射线遮挡剔除在生效」的输出——实际上 {@code raycast=false} 时
   * 只做视锥剔除，两者失效模式完全不同，必须一次说清。
   */
  private static String entityCullingSwitch(BandwidthConfig.EntityCulling culling) {
    if (!culling.enabled()) {
      return "关闭（模块未启用）";
    }
    boolean raycast = culling.raycast();
    BandwidthConfig.EntityCulling.Frustum frustum = culling.frustum();
    boolean frustumOn = frustum != null && frustum.enabled();
    if (!raycast && !frustumOn) {
      return "关闭（raycast 与 frustum.enabled 均为 false，本模块不注册）";
    }
    StringBuilder sb = new StringBuilder("启用（");
    sb.append(raycast ? "射线遮挡" : "不做射线遮挡");
    sb.append(" + 视锥剔除");
    if (frustumOn) {
      sb.append("（距离门 ").append(frustum.minDistance()).append(" 格，FOV ")
          .append(frustum.fov()).append("°）");
    } else {
      sb.append("关");
    }
    return sb.append("）").toString();
  }

  /**
   * 调色板压缩的开关回显。
   *
   * <p><b>为什么按「实际是否发生收缩/降级」回显</b>：调色板模块有「总开关」与「行为开关 width-budget」
   * 两层，真正的收缩/降级只由 {@code width-budget} 决定（默认 true）。只打印模块总开关会出现
   * 「调色板压缩 true」这种极易被误读为「已在收缩」的输出，因此这里把两层的实际效果合成一句无歧义的中文。
   */
  private static String paletteSwitch(BandwidthConfig.Palette palette) {
    if (!palette.enabled()) {
      return "关闭（模块未启用，不做任何收缩/降级）";
    }
    if (!palette.widthBudget()) {
      return "关闭（模块启用但 width-budget=false，不做任何收缩/降级）";
    }
    return "启用（改写后收缩 + 单值/低位宽降级）";
  }

  /**
   * AFK 降级的开关回显。
   *
   * <p><b>为什么不直接打印 {@code afk.enabled()}</b>：AFK 模块有「总开关」与「丢弃类型」两层，
   * 两个 drop-* 开关全为 false 时模块仍在跑（跟踪并统计 AFK 状态），但<b>不丢任何包</b>。
   * 只打印总开关会出现「AFK 降级 true」这种容易被读成「丢包已生效」的输出，故按实际丢弃类型回显。
   */
  private static String afkSwitch(BandwidthConfig.Afk afk) {
    if (!afk.enabled()) {
      return "关闭（模块未启用）";
    }
    List<String> dropped = new ArrayList<>(2);
    if (afk.dropParticles()) {
      dropped.add("世界粒子");
    }
    if (afk.dropBlockBreakAnimation()) {
      dropped.add("方块破坏动画");
    }
    if (dropped.isEmpty()) {
      return "启用（仅统计 AFK 状态、不丢弃任何包：drop-particles 与 drop-block-break-animation 均为 false）";
    }
    return "启用（丢弃 " + String.join("、", dropped) + "）";
  }

  /** 转储文本格式化（纯函数）：状态面板 + 环境信息 + 配置项有效值。 */
  public static String formatDump(Snapshot s, String timestamp) {
    StringBuilder sb = new StringBuilder();
    sb.append("MikuXrayNet 诊断转储\n");
    sb.append("导出时间：").append(timestamp).append('\n');
    sb.append("服务端：").append(s.env().serverVersion())
        .append(s.env().folia() ? "（Folia 系，区域化多线程）" : "（Paper 系，单主线程）").append('\n');
    sb.append("JVM：").append(s.env().javaVersion()).append('\n');
    sb.append("配置指纹：").append(s.configFingerprint()).append('\n');
    sb.append('\n');
    for (String line : formatStatus(s)) {
      sb.append(line).append('\n');
    }
    // 位宽直方图（P0-1 可选项）：改写 section 的 bitsPerBlock 分布，观察「封顶+裁剪」的降位效果
    sb.append("调色板位宽分布（改写 section 累计）：").append(s.rewrite().paletteBitsSummary()).append('\n');
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
    sb.append("world-blacklist=").append(worldBlacklistSummary(c)).append('\n');
    if (c.dimensionsMissing()) {
      sb.append("dimensions=缺少该段（已使用内置默认运行，请重新生成配置）\n");
    }
    // 各维度生效值（mode / 隐藏项数 / 伪装表 / 是否启用 / 高度范围 / use-block-below）
    for (AntiXrayConfig.Dimension dimension : AntiXrayConfig.Dimension.values()) {
      AntiXrayConfig.EffectiveObfuscation effective = c.dimensionEffective(dimension);
      sb.append("dimensions.").append(dimension.key())
          .append("（").append(dimension.label()).append("）：启用=").append(c.dimensionEnabled(dimension))
          .append("，mode=").append(effective.mode())
          .append("，hide-blocks=").append(effective.hideBlocks().size()).append(" 种：")
          .append(String.join(", ", effective.hideBlocks()))
          .append("，replacement-weights=").append(effective.replacementWeights())
          .append("，replacement-bands=").append(effective.replacementBands().isEmpty()
              ? "未配置（回落 replacement-weights）" : effective.replacementBands())
          .append("，min-y=").append(effective.minY() == Integer.MIN_VALUE ? "不限制" : effective.minY())
          .append("，max-y=").append(effective.maxY() == Integer.MAX_VALUE ? "不限制" : effective.maxY())
          .append("，use-block-below=").append(effective.useBlockBelow())
          .append('\n');
    }
    if (c.worldOverrides().isEmpty()) {
      sb.append("world-overrides=未配置（各世界用其维度的生效值）\n");
    } else {
      sb.append("world-overrides（最高优先级，按世界名）：\n");
      for (int i = 0; i < c.worldOverrides().size(); i++) {
        sb.append("  ").append(c.worldOverrides().get(i).pattern()).append('\n');
        for (AntiXrayConfig.Dimension dimension : AntiXrayConfig.Dimension.values()) {
          sb.append("    ").append(dimension.key()).append(" → ")
              .append(c.overrideEffective(i, dimension)).append('\n');
        }
      }
    }
    sb.append("layer-obfuscation=").append(c.layerObfuscation())
        .append("，remove-block-entities=").append(c.removeBlockEntities()).append('\n');
    sb.append("occlusion.extra-occluding=").append(c.occlusion().extraOccluding())
        .append("，extra-non-occluding=").append(c.occlusion().extraNonOccluding())
        .append("，fluid-cover=").append(c.occlusion().fluidCover()).append('\n');
    sb.append("neighbors.enabled=").append(c.neighbors().enabled())
        .append("，missing-policy=").append(c.neighbors().missingPolicy())
        .append("，cache-maximum-size=").append(c.neighbors().cacheMaximumSize()).append('\n');
    sb.append("proximity.instant-reveal.enabled=").append(c.proximity().instantReveal().enabled())
        .append("，radius=").append(c.proximity().instantReveal().radius())
        .append("，max-per-tick=").append(c.proximity().instantReveal().maxPerTick()).append('\n');
    sb.append("proximity.over-reveal-sampling=").append(c.proximity().overRevealSampling())
        .append("，batch-reveal-sends=").append(c.proximity().batchRevealSends()).append('\n');
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
    // 配置项被安全下限抬升时明示（否则管理员会以为 dump 里的值是自己配的，或怀疑读错配置）
    if (!c.floorAdjustments().isEmpty()) {
      sb.append("配置安全下限已抬升：").append(String.join("、", c.floorAdjustments())).append('\n');
    }
    // 配置项被安全上限回落时同样明示（邻近显形距离 / 邻块与改写缓存容量）
    if (!c.ceilingAdjustments().isEmpty()) {
      sb.append("配置安全上限已回落：").append(String.join("、", c.ceilingAdjustments())).append('\n');
    }
    sb.append("disk-cache.enabled=").append(c.diskCache().enabled())
        .append("，max-entries=").append(c.diskCache().maxEntries())
        .append("，max-file-size-mb=").append(c.diskCache().maxFileSizeMb())
        .append("，expire-seconds=").append(c.diskCache().expireSeconds()).append('\n');
    sb.append("disk-cache.bucket-cache-size=").append(c.diskCache().bucketCacheSize())
        .append("，idle-close-seconds=").append(c.diskCache().idleCloseSeconds())
        .append("，maintenance-interval-seconds=").append(c.diskCache().maintenanceIntervalSeconds())
        .append("，compact-per-pass=").append(c.diskCache().compactPerPass())
        .append("，queue-capacity=").append(c.diskCache().queueCapacity()).append('\n');
    // zstd 前置（含新键 zstd-sha256）：回显是否已配置校验，而非打印完整哈希——
    // 哈希本身不是密钥，但整串打进 dump 只是噪声，管理员只需知道「有没有开校验」。
    sb.append("disk-cache.zstd.auto-download=").append(c.diskCache().zstdAutoDownload())
        .append("，download-url=").append(zstdDownloadUrlSummary(c.diskCache().zstdDownloadUrl()))
        .append("，timeout-seconds=").append(c.diskCache().zstdTimeoutSeconds())
        .append("，zstd-sha256=").append(c.diskCache().zstdSha256().isEmpty() ? "未配置（不校验）" : "已配置")
        .append('\n');
    sb.append("cache.maximum-size=").append(c.cacheMaximumSize())
        .append("，expire-after-access-seconds=").append(c.cacheExpireAfterAccessSeconds()).append('\n');
    sb.append("advanced.platform=").append(c.platform())
        .append("，threads=").append(c.threads())
        .append("，timeout-millis=").append(c.timeoutMillis())
        .append("，queue-capacity=").append(c.queueCapacity()).append('\n');
    sb.append("config-hash=").append(c.configHash()).append('\n');
  }

  /**
   * zstd 下载源只回显「是否已配置」，<b>绝不打印完整 URL</b>：URL 内可能内嵌镜像凭据
   * （如 {@code https://user:token@mirror}），整串写进 dump 就等同于把凭据落盘。
   * 与同行 {@code zstd-sha256} 同口径——管理员只需知道「用的是自定义源还是内置默认」。
   */
  private static String zstdDownloadUrlSummary(String url) {
    return url == null || url.isBlank() || AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL.equals(url)
        ? "未配置（用内置默认）" : "已配置";
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
        .append("，strict-verify=").append(c.palette().strictVerify())
        .append("，width-budget=").append(c.palette().widthBudget()).append('\n');
    sb.append("entity-culling.enabled=").append(c.entityCulling().enabled())
        .append("，raycast=").append(c.entityCulling().raycast())
        .append("，force-visible-distance=").append(c.entityCulling().forceVisibleDistance())
        .append("，update-interval-ticks=").append(c.entityCulling().updateIntervalTicks())
        .append("，recheck-budget=").append(c.entityCulling().recheckBudget())
        .append("，ray-samples=").append(c.entityCulling().raySamples())
        // 视锥子项与「有效距离门」一并回显：有效门 = max(frustum.min-distance, force-visible-distance)，
        // 只打印配置值会让「配了 24 却从 32 格才生效」看起来像读错配置。
        .append("，frustum.enabled=").append(c.entityCulling().frustum() != null
            && c.entityCulling().frustum().enabled())
        .append("，frustum.fov=").append(c.entityCulling().frustum() == null
            ? "无" : c.entityCulling().frustum().fov())
        .append("，frustum.min-distance=").append(c.entityCulling().frustum() == null
            ? "无" : c.entityCulling().frustum().minDistance())
        .append("，frustum.有效距离=").append(c.entityCulling().frustum() == null
            ? "无"
            : Math.max(c.entityCulling().frustum().minDistance(),
                c.entityCulling().forceVisibleDistance()))
        .append('\n');
    sb.append("entity-metadata.enabled=").append(c.entityMetadata().enabled())
        .append("，max-tracked-per-player=").append(c.entityMetadata().maxTrackedPerPlayer())
        .append('\n');
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

  /** 纳秒 → 毫秒文本（一位小数）；纯观测计数用。 */
  private static String formatMillis(long nanos) {
    return String.format(Locale.ROOT, "%.1f", Math.max(0L, nanos) / 1_000_000.0D);
  }

  /** 平均一次冲刷的毫秒文本（次数为 0 时为 0.0）；纯观测计数用。 */
  private static String averageFlushMillis(long flushes, long nanos) {
    if (flushes <= 0L) {
      return "0.0";
    }
    return String.format(Locale.ROOT, "%.2f", Math.max(0L, nanos) / 1_000_000.0D / flushes);
  }
}