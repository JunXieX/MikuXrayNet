package net.mikumc.mikuxraynet.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.mikumc.mikuxraynet.AntiXrayRuntime;
import net.mikumc.mikuxraynet.MikuXrayNet;
import net.mikumc.mikuxraynet.antixray.ChunkPacketAccessor;
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
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import net.mikumc.mikuxraynet.occlusion.OcclusionStats;
import org.bukkit.Bukkit;

/**
 * 诊断聚合入口：把反矿透与带宽各模块暴露的计数器、依赖状态与配置有效值汇总为中文状态面板与转储文件。
 *
 * <p><b>零额外开销</b>：各模块只维护 {@code LongAdder}-类轻量计数，本类仅在 {@code status}/{@code dump}
 * 被执行、以及周期运行摘要（{@link #summaryLine()}）定时触发时做一次拉取与字符串拼接，
 * 不在封包热路径上做任何统计工作。
 *
 * <p><b>可测</b>：状态/转储的格式化是<b>静态方法</b>（{@link #formatStatus(Snapshot)} /
 * {@link #formatDump(Snapshot, String)}），可给定固定 {@link Snapshot} 直接断言输出；实例方法只负责
 * 拉取实时快照。注意它们会读少量<b>进程级静态状态</b>（活动黑名单、平台判定、依赖存在性），
 * 因此对同一份 {@code Snapshot} 的输出并非严格确定——不是纯函数，详见各自 javadoc。
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
      // 遮挡判定域：位图驻留量、专用池队列深度与判定计数
      Occlusion occlusion,
      Throttle throttle,
      Pool pool,
      DiskCache diskCache,
      // 区块取数域：反矿透区块封包字段定位状态（fail-open 口径可见）
      ChunkAccessor chunkAccessor,
      AntiXrayConfig antiXray,
      BandwidthConfig bandwidth) {

    /** 配置指纹（取自反矿透配置；无配置时为 0）。 */
    public int configFingerprint() {
      return antiXray == null ? 0 : antiXray.configHash();
    }

    /**
     * 区块取数域：反矿透区块封包字段定位状态（可见的 fail-open 口径）。
     *
     * <p><b>为什么要它</b>：MC 26.3 移除区块数据构造器后，若字段定位失败，反矿透会<b>静默</b>
     * fail-open（放行原包、矿物以真实状态下发）——「改写计数看起来正常但玩家仍能透视」无法归因。
     * 把字段定位结果汇总进状态面板，即可一眼区分「字段级全命中 / 降级不剔方块实体 / 完全不可用」。
     *
     * @param state 可打印的中文状态（取自 {@link ChunkPacketAccessor.AccessorState#label()}）
     */
    public record ChunkAccessor(String state) {

      /** 反矿透未启用（未解析）时的零值兜底。 */
      public static final ChunkAccessor EMPTY =
          new ChunkAccessor(ChunkPacketAccessor.AccessorState.UNRESOLVED.label());
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
     *
     * <p><b>{@code cacheHits} 是「键命中且未过期」，不等于「用上了」</b>：其中被
     * {@code cacheContentRejected}（内容指纹已变）作废的实际等同未命中。要看内存缓存真正起了多少作用，
     * 应看有效命中率 {@code (cacheHits - cacheContentRejected) / (cacheHits + cacheMisses)}
     * （分母即全部查询次数，因为作废的命中是 {@code cacheHits} 的子集）。
     * 另外 {@code cacheMisses + cacheContentRejected = 磁盘查询次数}，可用来和磁盘缓存一行的分母对账。
     *
     * <p>{@code indexSkippedOnWriteBackFailure} 是「写回未确认生效 → 连旧索引登记一起作废」的次数
     * （{@code writeBackFailures} 的子集）；{@code occlusionCaptureFailures} /
     * {@code occlusionPublishSkipped} 是遮挡建表的失败与「因期间有方块补丁而跳过覆盖的段数」——
     * 三者都是此前完全静默、却直接决定「矿能不能被还原」的路径。
     */
    public record Rewrite(long cacheHits, long cacheMisses, int cacheEntries, long cacheBytes,
        long cacheContentRejected,
        long chunksRewritten, long blocksReplaced, long chunksSkipped, long chunksFailed,
        long writeBackFailures, long chunksTimedOut, long chunksSkippedQueueFull,
        long bytesOriginal, long bytesOutput, long bytesSaved, String paletteBitsSummary,
        long indexSkippedOnWriteBackFailure, long occlusionCaptureFailures,
        long occlusionPublishSkipped) {

      /**
       * 兼容构造（不含三项新增的「静默路径」计数，等价于全 0）。
       *
       * <p>保留它是为了不破坏既有调用点与单测：新增字段只在对应异常/竞态实际发生时才非 0，
       * 其余场景与旧快照逐字段一致。
       */
      public Rewrite(long cacheHits, long cacheMisses, int cacheEntries,
          long cacheContentRejected,
          long chunksRewritten, long blocksReplaced, long chunksSkipped, long chunksFailed,
          long writeBackFailures, long chunksTimedOut, long chunksSkippedQueueFull,
          long bytesOriginal, long bytesOutput, long bytesSaved, String paletteBitsSummary) {
        this(cacheHits, cacheMisses, cacheEntries, 0L, cacheContentRejected, chunksRewritten,
            blocksReplaced, chunksSkipped, chunksFailed, writeBackFailures, chunksTimedOut,
            chunksSkippedQueueFull, bytesOriginal, bytesOutput, bytesSaved, paletteBitsSummary,
            0L, 0L, 0L);
      }

      /** 反矿透未启用时的零值兜底。 */
      public static final Rewrite EMPTY =
          new Rewrite(0L, 0L, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "无采样",
              0L, 0L, 0L);

      /** 真正用上的命中数（键命中且内容指纹一致）；被 {@code cacheContentRejected} 作废的不计入。 */
      public long effectiveCacheHits() {
        return Math.max(0L, cacheHits - cacheContentRejected);
      }
    }

    /**
     * 邻近显形域：显形发包与视锥/射线剔除计数，以及过度显形的抽样统计。
     *
     * <p>{@code rayCulledMissingData} 是 {@code rayCulled} 的<b>子集</b>（上界式归因）：剔除时判定期间
     * 曾触碰过「无位图数据」的 section。位图判定的缺数据规则是「无数据 = 遮挡」，只有这个子集持续
     * 偏高才说明是缺数据在憋住显形；≈0 则表示剔除来自真实遮挡。
     */
    public record Proximity(long revealsSent, long revealsSkipped, long revealsUnregistered,
        long frustumCulled, long rayCulled, long rayCulledMissingData, long overRevealSampled,
        long overRevealWasted) {

      /** 邻近显形未启用时的零值兜底。 */
      public static final Proximity EMPTY =
          new Proximity(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /** 显形索引域：伪装区块索引（按区块共享）与按玩家的已显形集合的持有量与安全阀计数。 */
    public record Index(int obfuscatedChunkCount, int obfuscatedPositionCount,
        int revealedPositionCount, long revealedRegisteredTotal,
        long indexEvictedByCapacity, long revealedDroppedByCapacity,
        long obfuscatedRegisteredTotal, long obfuscatedInvalidatedTotal) {

      /** 显形索引未启用时的零值兜底。 */
      public static final Index EMPTY = new Index(0, 0, 0, 0L, 0L, 0L, 0L, 0L);

      /** 兼容构造（不含累计登记/摘除，等价于「无从判断」，用于既有调用点与单测）。 */
      public Index(int obfuscatedChunkCount, int obfuscatedPositionCount,
          int revealedPositionCount, long revealedRegisteredTotal,
          long indexEvictedByCapacity, long revealedDroppedByCapacity) {
        this(obfuscatedChunkCount, obfuscatedPositionCount, revealedPositionCount,
            revealedRegisteredTotal, indexEvictedByCapacity, revealedDroppedByCapacity, 0L, 0L);
      }
    }

    /**
     * 遮挡判定域：位图驻留量与专用池队列深度，以及判定计数（提交 / 完成 / 超时丢弃 / 队列满拒收 /
     * 判定异常 / 遮挡数据缺失）与射线累计耗时。
     *
     * <p><b>为什么「缺遮挡数据」必须单列</b>：它是「区块没被建过表」的覆盖率观测点——只要它持续偏高，
     * 就说明大量区块查不到位图（射线一律按不遮挡放行，实体只是少藏、不会误藏）。真机调优时它比
     * 「隐藏数」更能说明遮挡判定是否真的覆盖到了玩家周围的区块。
     *
     * <p><b>为什么「判定异常」必须单列</b>：单条判定在工作线程内抛异常会被就地吞掉（不改变显示状态，
     * 方向安全），但若这类输入持续出现，说明射线链路存在越界/畸形坐标等异常输入；这一计数不落盘、
     * 不进日志，若此处不回显便完全不可观测（故障会表现为「部分判定静默丢失」而无人知晓）。
     *
     * @param active          遮挡判定引擎是否已真正接线（引擎已起 + 数据源可用）
     * @param sectionsResident 当前驻留的遮挡 section 条目总数（跨世界求和）
     * @param queueDepth      专用池当前排队的判定数（持续接近队列上限说明引擎线程跟不上）
     * @param patchFailures   补丁失败（拿不到新方块口径）的格数（这些格已被置为「不遮挡」，属「少藏」方向）
     * @param computeFailures 单条判定在工作线程内抛异常被就地吞掉的次数（持续非 0 = 存在异常输入，需定位）
     * @param stateIdFallbacks 补丁「拿不到状态 id、回落 Material 级近似」的格数（&gt;0 说明补丁暂时无法与
     *                        位图逐状态一致，只能按方块级近似）
     */
    public record Occlusion(boolean active, int sectionsResident, int queueDepth, long submitted,
        long computed, long timeoutDiscarded, long rejectedQueueFull, long computeFailures,
        long unknownSection, long nanosTotal, long patched, long patchFailures,
        long stateIdFallbacks) {

      /** 遮挡判定未接线时的零值兜底。 */
      public static final Occlusion EMPTY =
          new Occlusion(false, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /**
     * 带宽域：零位移取消、变更合并、实体剔除（含复检与视锥口径）与 AFK / 降视距计数。
     *
     * @param entityMovePackets       三种实体更新包各自的到达次数（纯位移 / 位移+朝向 / 朝向）
     * @param entityMoveShapeMismatch 纯位移包因位移字段数不足 3 被放行的次数（布局与预期不符的信号）
     * @param frustumHidden             因「视野锥外 + 超出距离门」被隐藏的实体数（视锥剔除子项）
     * @param frustumShown              转头后经复检恢复的视锥隐藏实体数（视锥剔除子项的恢复侧）
     */
    public record Throttle(long entityPacketsCancelled, long entityPacketsPassed,
        long entityMovePackets, long entityMoveRotPackets, long entityLookPackets,
        long entityMoveShapeMismatch,
        long blockMergeBatches, long blockChangesMerged, long blockChangesPassed,
        long blockChangesDiscarded,
        long entitiesHidden, long entitiesShown, int entitiesHiddenNow,
        long recheckSubmitted, long recheckHidden, long recheckShown,
        long frustumHidden, long frustumShown,
        int afkPlayers, long afkEntered, long afkPacketsDropped,
        long viewDistanceReduced, long viewDistanceRestored,
        long blockMergeFlushes, long blockMergeFlushNanos, boolean cleanupIncomplete) {

      /**
       * 兼容构造（不含 {@code cleanupIncomplete}，等价于「清理完整」）。
       *
       * <p>保留它是为了不破坏既有调用点与单测：新增的可观测字段只在停用失败时才有意义，
       * 其余场景恒为 {@code false}。
       */
      public Throttle(long entityPacketsCancelled, long entityPacketsPassed,
          long entityMovePackets, long entityMoveRotPackets, long entityLookPackets,
          long entityMoveShapeMismatch,
          long blockMergeBatches, long blockChangesMerged, long blockChangesPassed,
          long entitiesHidden, long entitiesShown, int entitiesHiddenNow,
          long recheckSubmitted, long recheckHidden, long recheckShown,
          long frustumHidden, long frustumShown,
          int afkPlayers, long afkEntered, long afkPacketsDropped,
          long viewDistanceReduced, long viewDistanceRestored,
          long blockMergeFlushes, long blockMergeFlushNanos) {
        this(entityPacketsCancelled, entityPacketsPassed, entityMovePackets, entityMoveRotPackets,
            entityLookPackets, entityMoveShapeMismatch, blockMergeBatches, blockChangesMerged,
            blockChangesPassed, 0L, entitiesHidden, entitiesShown, entitiesHiddenNow, recheckSubmitted,
            recheckHidden, recheckShown, frustumHidden, frustumShown, afkPlayers, afkEntered,
            afkPacketsDropped, viewDistanceReduced, viewDistanceRestored, blockMergeFlushes,
            blockMergeFlushNanos, false);
      }

      /** 带宽优化未启用时的零值兜底。 */
      public static final Throttle EMPTY = new Throttle(
          0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0,
          0L, 0L, 0L, 0L, 0L, 0, 0L, 0L, 0L, 0L, 0L, 0L, false);
    }

    /** 工作线程池域：线程数、活动数与队列占用。 */
    public record Pool(int threads, int active, int queueSize, int queueCapacity) {

      /** 工作线程池未启用时的零值兜底。 */
      public static final Pool EMPTY = new Pool(0, 0, 0, 0);
    }

    /**
     * 磁盘缓存域：命中、持有量、打开的区域文件数与「为什么没命中 / 命中了却没用上」的拆解计数。
     *
     * <p>{@code expiredRemoved} / {@code configMismatchRemoved} / {@code payloadRejected} / {@code rejectedBy*}
     * 是排查「命中率异常」的关键：
     * 过期清理说明条目活不过配置的 expire-seconds（配短了等于关掉缓存），
     * <b>配置指纹清理</b>说明条目是旧配置改写的（升级 / 改过会影响改写结果的配置后必然出现一大批，
     * 属正常的「缓存整体重建」），
     * 写入被拒说明容量/文件大小上限在拦，{@code payloadRejected} 说明读到的负载与本次要改写的字节不符
     * （内容真变了，或信封损坏）。
     *
     * <p><b>注意 {@code hits} 是「读到了条目」，不等于「用上了」</b>：其中被 {@code payloadRejected}
     * 拒掉的（内容指纹不符）实际等同于未命中（会回退整块重写）。要判断磁盘缓存真正起了多少作用，
     * 应看有效命中率 {@code (hits - payloadRejected) / (hits + misses)}
     * （分母即全部查询次数，因为被拒的命中是 {@code hits} 的子集）。
     */
    public record DiskCache(long hits, long misses, int entries, int openFiles,
        long expiredRemoved, long configMismatchRemoved, long payloadRejected,
        long rejectedByCapacity, long rejectedBySize, long rejectedByBacklog, long errors) {

      /** 磁盘缓存未启用时的零值兜底。 */
      public static final DiskCache EMPTY =
          new DiskCache(0L, 0L, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

      /** 真正用上的命中数（读到条目且负载可用）；被 {@code payloadRejected} 拒掉的不计入。 */
      public long effectiveHits() {
        return Math.max(0L, hits - payloadRejected);
      }
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
        // 遮挡判定只带最关键的三个量（是否接线 / 队列积压 / 缺数据），避免单行摘要被塞满
        + "｜磁盘缓存命中 " + s.diskCache().hits() + "，条目约 " + s.diskCache().entries()
        + "｜遮挡判定 " + (s.occlusion().active() ? "已接线" : "未接线")
        + "（位图 " + s.occlusion().sectionsResident() + "，队列 " + s.occlusion().queueDepth()
        + "，缺数据 " + s.occlusion().unknownSection() + "）"
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
    File file = writeNewDump(folder, stamp, dumpText(stamp));
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
  /**
   * 以「独占创建」写出一份新转储：同名已存在就换下一个序号，绝不覆盖已有转储。
   *
   * <p><b>为什么不用 {@link #newDumpFile} 的「先查后写」</b>：检查与写入之间存在竞态——同一毫秒内
   * 并发两次转储（或系统时钟回拨）可能都判定「不冲突」，后写的一方把先写的那份覆盖掉。这里改用
   * {@code CREATE_NEW} 让文件系统保证独占，撞名只重试下一序号。
   */
  private static File writeNewDump(File folder, String stamp, String text) throws IOException {
    for (int suffix = 0; suffix < 1000; suffix++) {
      File file = new File(folder,
          suffix == 0 ? "dump-" + stamp + ".txt" : "dump-" + stamp + "-" + suffix + ".txt");
      try {
        Files.writeString(file.toPath(), text, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return file;
      } catch (FileAlreadyExistsException collision) {
        // 同毫秒并发转储撞名：换下一个序号（绝不覆盖已有转储）
      }
    }
    throw new IOException("无法分配唯一的转储文件名：" + stamp);
  }

  /**
   * 只算出「本次不该冲突的文件名」，不落盘。
   *
   * <p><b>生产路径已不再使用</b>：{@code writeDump()} 改走 {@link #writeNewDump}（{@code CREATE_NEW}
   * 原子占名）。本方法保留仅供单测验证命名规则与序号回退，不要在新的写盘路径里调用它
   * （先查后写存在竞态，同毫秒并发会互相覆盖）。
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
    OcclusionStats occlusionStats = pipeline == null ? null : pipeline.occlusionStats();

    // 各域来源未启用时直接复用空对象，快照构造里不再出现逐字段的「? null : 0」
    RewriteStats rewriteStats = hook == null ? null : hook.stats();
    Snapshot.Rewrite rewrite = rewriteStats == null ? Snapshot.Rewrite.EMPTY
        : new Snapshot.Rewrite(hook.cacheHits(), hook.cacheMisses(), hook.cacheSize(),
            hook.cacheBytes(), rewriteStats.cacheContentRejected.sum(),
            rewriteStats.chunksRewritten.sum(), rewriteStats.blocksReplaced.sum(),
            rewriteStats.chunksSkipped.sum(), rewriteStats.chunksFailed.sum(),
            rewriteStats.writeBackFailures.sum(), rewriteStats.chunksTimedOut.sum(),
            rewriteStats.chunksSkippedQueueFull.sum(),
            rewriteStats.bytesOriginal.sum(), rewriteStats.bytesOutput.sum(),
            rewriteStats.bytesSaved.sum(), rewriteStats.paletteBitsSummary(),
            rewriteStats.indexSkippedOnWriteBackFailure.sum(),
            rewriteStats.occlusionCaptureFailures.sum(),
            rewriteStats.occlusionPublishSkipped.sum());
    Snapshot.Proximity proximity = proximityStats == null ? Snapshot.Proximity.EMPTY
        : new Snapshot.Proximity(proximityStats.revealsSent.sum(), proximityStats.revealsSkipped.sum(),
            proximityStats.unregistered.sum(), proximityStats.revealsFrustumCulled.sum(),
            proximityStats.revealsRayCulled.sum(), proximityStats.revealsRayCulledMissingData.sum(),
            proximityStats.overRevealSampled.sum(), proximityStats.overRevealWasted.sum());
    Snapshot.Index index = chunkIndex == null && revealedSet == null ? Snapshot.Index.EMPTY
        : new Snapshot.Index(
            chunkIndex == null ? 0 : chunkIndex.chunkCount(),
            chunkIndex == null ? 0 : chunkIndex.positionCount(),
            revealedSet == null ? 0 : revealedSet.positionCount(),
            revealedSet == null ? 0L : revealedSet.registeredTotal(),
            chunkIndex == null ? 0L : chunkIndex.evictedByCapacity(),
            revealedSet == null ? 0L : revealedSet.droppedByCapacity(),
            chunkIndex == null ? 0L : chunkIndex.registeredChunks(),
            chunkIndex == null ? 0L : chunkIndex.invalidatedChunks());
    Snapshot.Throttle throttle = pipeline == null ? Snapshot.Throttle.EMPTY
        : new Snapshot.Throttle(throttleStats.entityPacketsCancelled.sum(),
            throttleStats.entityPacketsPassed.sum(),
            throttleStats.entityMovePackets.sum(), throttleStats.entityMoveRotPackets.sum(),
            throttleStats.entityLookPackets.sum(), throttleStats.entityMoveShapeMismatch.sum(),
            throttleStats.blockMergeBatches.sum(),
            throttleStats.blockChangesMerged.sum(), throttleStats.blockChangesPassed.sum(),
            throttleStats.blockChangesDiscarded.sum(),
            throttleStats.entitiesHidden.sum(), throttleStats.entitiesShown.sum(),
            pipeline.hiddenEntityCount(), throttleStats.recheckSubmitted.sum(),
            throttleStats.recheckHidden.sum(), throttleStats.recheckShown.sum(),
            throttleStats.frustumHidden.sum(), throttleStats.frustumShown.sum(),
            pipeline.afkPlayerCount(), throttleStats.afkEntered.sum(),
            throttleStats.afkPacketsDropped.sum(), throttleStats.viewDistanceReduced.sum(),
            throttleStats.viewDistanceRestored.sum(), throttleStats.blockMergeFlushes.sum(),
            throttleStats.blockMergeFlushNanos.sum(), pipeline.cleanupIncomplete());
    Snapshot.Pool poolSnapshot = pool == null ? Snapshot.Pool.EMPTY
        : new Snapshot.Pool(pool.poolSize(), pool.activeCount(), pool.queueSize(),
            pool.queueCapacity());
    Snapshot.DiskCache diskCacheSnapshot = diskCache == null ? Snapshot.DiskCache.EMPTY
        : new Snapshot.DiskCache(diskCache.stats().hits.sum(), diskCache.stats().misses.sum(),
            diskCache.entries(), diskCache.openRegionFiles(),
            diskCache.stats().expiredRemoved.sum(),
            diskCache.stats().configMismatchRemoved.sum(),
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
        occlusionSnapshot(pipeline, occlusionStats, runtime),
        throttle,
        poolSnapshot,
        diskCacheSnapshot,
        chunkAccessorSnapshot(runtime),
        config == null ? null : config.antiXray(),
        config == null ? null : config.bandwidth());
  }

  /**
   * 组装区块取数域快照：反矿透未生效时直接复用零值组。
   *
   * <p>取真实字段定位状态（{@link ChunkPacketAccessor#accessorState()}）：字段级取数失败会静默
   * fail-open（放行原包、矿物真实下发），这一状态是「计数正常却仍能透视」的决定性判据，必须如实回显。
   * 反矿透未生效时即便启动期解析过也不代表当前在用，故按「未解析」兜底。
   */
  private static Snapshot.ChunkAccessor chunkAccessorSnapshot(AntiXrayRuntime runtime) {
    if (runtime == null || !runtime.antiXrayActive()) {
      return Snapshot.ChunkAccessor.EMPTY;
    }
    return new Snapshot.ChunkAccessor(ChunkPacketAccessor.accessorState().label());
  }

  /**
   * 组装遮挡判定域快照。
   *
   * <p>{@code active} 取自「引擎已起 + 数据源可用」这一真实接线状态：引擎属于带宽管线，管线未建成时
   * 必然为 false（此时射线遮挡判定整体跳过、仅做视锥剔除），回显如实反映。
   *
   * <p><b>但计数与位图驻留量不能跟着回落 0</b>：{@code OcclusionStats} 与位图表由反矿透侧持有
   * （插件级唯一实例、跨热重载复用），{@code OcclusionPatchListener} 随反矿透装配，带宽管线装配失败时
   * 它仍在持续写入。旧实现「pipeline == null 即整组 EMPTY」会把「位图补丁正常工作」显示成
   * 「未接线、补丁 0 格」，恰好在排查带宽装配失败时给出相反结论，故计数改从反矿透侧兜底取。
   */
  private static Snapshot.Occlusion occlusionSnapshot(ThrottlePipeline pipeline,
      OcclusionStats pipelineStats, AntiXrayRuntime runtime) {
    OcclusionStats stats = pipelineStats;
    if (stats == null && runtime != null) {
      stats = runtime.occlusionStats();
    }
    boolean active = pipeline != null && pipeline.asyncOcclusionActive();
    int sectionsResident = pipeline != null
        ? pipeline.occlusionSectionsResident()
        : (runtime == null ? 0 : runtime.occlusionSectionsResident());
    int queueDepth = pipeline == null ? 0 : pipeline.occlusionQueueDepth();
    if (stats == null) {
      return new Snapshot.Occlusion(active, sectionsResident, queueDepth,
          0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
    return new Snapshot.Occlusion(active, sectionsResident, queueDepth, stats.submitted.sum(),
        stats.computed.sum(), stats.timeoutDiscarded.sum(), stats.rejectedQueueFull.sum(),
        stats.computeFailures.sum(), stats.unknownSection.sum(), stats.nanosTotal.sum(),
        stats.patched.sum(), stats.patchFailures.sum(), stats.stateIdFallbacks.sum());
  }

  /**
   * 状态面板格式化。
   *
   * <p><b>并非严格纯函数</b>：文本里有三处读进程级静态状态——①「世界黑名单」一行读活动名单
   * （{@link WorldBlacklistConfig#current()}），同一份 {@link Snapshot} 在名单发布前后会格式化出不同结果；
   * ②「带宽开关」一行经 {@code ThrottlePipeline.plan()} 读平台判定（{@link PlatformSupport#isFolia()}）；
   * ③ 同上，缺 ProtocolLib 的判定读 {@link DependencyGuard}。要让本方法真正纯，需把这些都放进
   * {@code Snapshot}（会连带改 {@code DiagnosticsTest} 的构造点），故此处只先把描述改准。
   */
  public static List<String> formatStatus(Snapshot s) {
    List<String> lines = new ArrayList<>();
    lines.add("==== MikuXrayNet 运行状态"
        + (s.env().pluginVersion().isEmpty() ? "" : "（v" + s.env().pluginVersion() + "）") + " ====");
    lines.add("依赖：PacketEvents " + (s.env().packetEventsReady() ? "就绪" : "缺失")
        + "｜ProtocolLib " + (s.env().protocolLibReady() ? "就绪" : "缺失")
        + "｜平台 " + (s.env().folia() ? "Folia" : "Paper/Spigot"));
    lines.add("反矿透：" + (s.env().antiXrayActive() ? "已生效" : "未生效")
        + "｜直通玩家 " + s.env().bypassPlayers() + " 名"
        // 区块取数字段定位状态必须可见：字段级取数失败会静默 fail-open（放行原包、矿物真实下发），
        // 只凭「改写计数正常」无法区分「算了但没生效」，此行给出决定性判据（见 Snapshot.ChunkAccessor）。
        + "｜区块取数=" + s.chunkAccessor().state());
    lines.add("世界黑名单：" + worldBlacklistSummary());
    // 「命中」是「键命中且未过期」，与「有效命中」分列：内容指纹已变的那些其实等于未命中（必须重新改写）。
    // 另给出「磁盘查询」= 内存未命中 + 内容不符 —— 它与磁盘缓存一行的分母（命中 + 未命中）必然相等，
    // 两行互相对账即可发现「某类查询没有计数」这类统计缺口。
    lines.add("改写缓存：命中 " + s.rewrite().cacheHits()
        + "（有效 " + s.rewrite().effectiveCacheHits() + "，内容已变 "
        + s.rewrite().cacheContentRejected() + "）"
        + "，未命中 " + s.rewrite().cacheMisses()
        + "，命中率 " + hitRate(s.rewrite().cacheHits(), s.rewrite().cacheMisses())
        // 有效命中率的分母仍是「全部查询次数」= 有效命中 + 未命中 + 内容已变（三者互斥、恰好划分全体员工），
        // 只把分子里的作废命中剔除；等价于 hitRate(有效, hits + misses − 有效)。
        + "，有效命中率 " + hitRate(s.rewrite().effectiveCacheHits(),
            s.rewrite().cacheMisses() + s.rewrite().cacheContentRejected())
        + "，条目 " + s.rewrite().cacheEntries()
        // 字节预算是「条目数」之外的第二个上限：不显示占用时，管理员只能看到条目数远没到顶、
        // 却已经在按字节淘汰（缓存命中率莫名下降），这条给出决定性的对照值。
        + "，字节 " + bytesHuman(s.rewrite().cacheBytes())
        // 上限是**启动期固化**的（RewriteCache 只在 startAntiXray 里构造一次），reload 改
        // cache.maximum-mb 只改配置不改这里；标注「重启生效」避免管理员被实时配置值误导。
        + (s.antiXray() == null ? ""
            : " / 上限 " + bytesHuman(s.antiXray().cacheMaximumBytes()) + "（重启生效）")
        + "，磁盘查询 " + (s.rewrite().cacheMisses() + s.rewrite().cacheContentRejected()));
    lines.add("区块改写：改写 " + s.rewrite().chunksRewritten() + "，替换方块 " + s.rewrite().blocksReplaced()
        // 「写回失败」后面跟的括号是它的子集：写回未生效时，旧的伪装索引会被整条作废（否则会一直
        // 为「客户端根本没收到伪装」的坐标发冗余显形包）。两者口径不同，必须写清归属关系。
        + "，写回失败 " + s.rewrite().writeBackFailures()
        + "（其中作废旧索引 " + s.rewrite().indexSkippedOnWriteBackFailure() + "）");
    // 遮挡建表的两条静默路径必须可见：① 建表失败 → 该区块没有位图，显形侧按「缺数据 = 遮挡」
    // 永久藏住玩家眼前的矿，且失败结果不进缓存（每次重发重算）；② 发布时跳过 → 说明这期间有方块
    // 补丁落库、必须保留活位图（正确性要求，但偏高说明建表窗口内变更很频繁）。
    lines.add("遮挡建表：失败 " + s.rewrite().occlusionCaptureFailures()
        + " 次（该区块无位图：矿可能一直显示假方块，且每次重发都要重算）"
        + "，发布跳过 " + s.rewrite().occlusionPublishSkipped()
        + " 段（期间有方块补丁，保留活位图）");
    // 「未改写」按原始计数器分列，并写明各自的归属关系：chunksSkipped 是「已处理但原包放行」的<b>总量</b>，
    // chunksFailed 是它的子集；而「队列满」与「超时放行」都在各自的提前返回分支里计数、并不计入
    // chunksSkipped。任何用减法（如 跳过 − 异常）去推算「本来就无需改写」的写法都会漏掉这两条路径，
    // 因此这里只给原值、不做推算。
    lines.add("未改写：跳过 " + s.rewrite().chunksSkipped()
        + "（已处理但原包放行，无目标方块 / 无改动 / 异常均计入）"
        + "，其中异常 " + s.rewrite().chunksFailed()
        // 「因队列满整块未伪装」意味着该区块的矿物以真实状态下发（对透视端可见），是本模块唯一的
        // 功能性静默失效，必须与「跳过」分开看；它不在「跳过」内，故单列并注明。
        + "，队列满未伪装 " + s.rewrite().chunksSkippedQueueFull() + "（不计入「跳过」，该区块完全未伪装）"
        + "，超时放行 " + s.rewrite().chunksTimedOut() + "（不计入「跳过」，原包按时限放行）");
    // 字节口径（P0-1）：只统计真正改写的区块（未改动/失败放行的原样字节不采样，避免稀释比例）。
    // 节省比例 = 节省字节 / 原始字节；开启 palette.width-budget 后这一行应体现「封顶+裁剪」的收益。
    lines.add(bytesLine(s.rewrite()));
    // 注意「坐标跳过」与「整块跳过」是两件事：前者是候选坐标因区块未加载 / 发包失败被跳过，
    // 后者是「整块都已显形」的区块被整体略过（只出现在一次性「首次显形诊断」日志里）。
    // 只写「跳过」会被误读成「整块跳过为 0 → 整块跳过没生效」，故此处写明「坐标跳过」。
    lines.add("邻近显形：发送 " + s.proximity().revealsSent() + "，坐标跳过 " + s.proximity().revealsSkipped()
        + "，变更注销 " + s.proximity().revealsUnregistered() + "，视锥剔除 " + s.proximity().frustumCulled()
        // 射线剔除后的括号是它的子集口径：位图判定「缺数据 = 遮挡」，只有这里偏高才说明是「没建过表 /
        // 位图被降级」在憋住显形；≈0 则剔除确实来自真实遮挡（否则「射线剔除很大」无法区分成因）。
        + "，射线剔除 " + s.proximity().rayCulled() + "（其中缺数据 "
        + s.proximity().rayCulledMissingData() + "）"
        + "，过度显形（抽样） " + s.proximity().overRevealWasted() + " / 抽样 "
        + s.proximity().overRevealSampled());
    // 口径说明：这里刻意分列「实时坐标数」与「累计登记数」——前者随登出 / 过期 / 区块失效归零，
    // 后者只增不减。若只显示实时值，玩家中途重登或走远后被清理时会看到「发送 N 但已显形 0」，
    // 极易被误读成「显形路径没有登记」（实际登记在发包成功后必然发生）。
    // 伪装索引的口径必须与「累计登记 / 卸载清理」一起显示：它只随区块卸载清理（不再按时间过期），
    // 玩家走开后会归零。若只显示实时值，「改写 N 但伪装区块 0」会被误读成「索引没记上、反矿透失效」。
    // 累计登记按「不同区块」去重（同一区块反复改写只计一次），因此三个数可对账：
    //   累计登记 − 卸载清理 − 安全阀淘汰 = 当前驻留（正常运营下安全阀为 0）。
    lines.add("显形索引：伪装区块 " + s.index().obfuscatedChunkCount() + "（当前驻留；累计登记 "
        + s.index().obfuscatedRegisteredTotal() + "，卸载清理 " + s.index().obfuscatedInvalidatedTotal()
        + "，坐标 " + s.index().obfuscatedPositionCount()
        + "）｜已显形 坐标 " + s.index().revealedPositionCount() + "（当前在线）｜累计登记 "
        + s.index().revealedRegisteredTotal()
        + "｜安全阀触发 " + s.index().indexEvictedByCapacity() + "/" + s.index().revealedDroppedByCapacity()
        + "（正常运营下应为 0，触发即说明有 bug）");
    // 「过期清理 / 配置指纹清理 / 负载被拒 / 写入被拒 / 异常」是「命中率为什么异常」的自证口径：
    // 过期清理 > 0 说明文件的 expire-seconds 比两次启动的间隔还短（条目全死）；
    // 配置指纹清理 > 0 说明条目是旧配置改写的（升级 / 改过会影响改写结果的配置后必然出现一大批，
    //   属正常的「缓存整体重建」）——没这一项时，管理员只看到命中率骤降而「过期清理 0」，无从归因；
    // 负载被拒 > 0 说明读到的负载与本次要改写的字节不符（内容真变了或信封损坏），
    // 写入被拒 > 0 说明容量/单文件上限在拦（新区块没进缓存），异常 > 0 见日志。
    // 「命中」写的是「读到条目」，与「有效命中」分列：被负载被拒掉的那些其实等于未命中（会回退重写），
    // 只报命中率会把「读到但全废」的磁盘缓存显示成 1.5% 而非 0%，从而被误判为「缓存基本正常」。
    // 「无数据」与「未启用」必须分开：前者是空缓存（首次启动、或刚被配置指纹整体清理），
    // 后者是管理员关掉了 disk-cache.enabled——旧文案把两者混成一个词，排查命中率时无法区分
    // 「功能正常但还没积累」与「功能压根没开」。
    lines.add("磁盘缓存：" + diskCacheState(s)
        + "｜命中 " + s.diskCache().hits() + "（有效 " + s.diskCache().effectiveHits() + "）"
        + "，未命中 " + s.diskCache().misses()
        + "，命中率 " + hitRate(s.diskCache().hits(), s.diskCache().misses())
        // 分母仍是全部查询次数（= 有效命中 + 未命中 + 负载被拒），只把分子里的作废命中剔除。
        + "，有效命中率 " + hitRate(s.diskCache().effectiveHits(),
            s.diskCache().misses() + s.diskCache().payloadRejected())
        + "，条目约 " + s.diskCache().entries() + "，打开区域文件 " + s.diskCache().openFiles()
        + "｜过期清理 " + s.diskCache().expiredRemoved()
        + "，配置指纹不符清理 " + s.diskCache().configMismatchRemoved()
        + "，负载被拒 " + s.diskCache().payloadRejected()
        + "，写入被拒 " + (s.diskCache().rejectedByCapacity() + s.diskCache().rejectedBySize())
        // 积压被拒单列：它不代表配置上限在拦，而是磁盘线程跟不上（压缩/落盘成瓶颈），
        // 处理方式与调容量上限完全不同，因此不与「写入被拒」合并。
        + "，积压被拒 " + s.diskCache().rejectedByBacklog()
        // 该计数混合了「读超时按未命中降级」（属于设计内的 fail-open，不代表故障）与真实读写异常，
        // 只看数字会把正常的超时降级当成故障，故在文案里写明口径。
        + "，异常 " + s.diskCache().errors() + "（含读超时降级与真实异常）");
    lines.add("带宽：零位移取消 " + s.throttle().entityPacketsCancelled()
        // 括号内先给「放行」总数（与旧口径兼容），再给到达口径三分 + 字段形态口径：
        // 「取消 0」必须能区分「没有纯位移包」「位移从不为 0」「包没到达」「字段布局变了」四种成因，
        // 否则该功能看起来永远无效而无法定位（见 ThrottleStats 说明）。
        + "（放行 " + s.throttle().entityPacketsPassed()
        + "；到达 纯位移 " + s.throttle().entityMovePackets()
        + "／位移+朝向 " + s.throttle().entityMoveRotPackets()
        + "／朝向 " + s.throttle().entityLookPackets()
        + "，字段形态不符 " + s.throttle().entityMoveShapeMismatch() + "），合并批次 "
        + s.throttle().blockMergeBatches()
        // 「个原包」而非「条」：blockChangesMerged 计的是被合并掉的<b>原始封包数</b>（add(held.size())），
        // 与「合并后发出的包数」（= 合并批次）不是一回事，写「条」极易被读成合并结果的条数。
        + "（合并 " + s.throttle().blockChangesMerged() + " 个原包，放行 "
        + s.throttle().blockChangesPassed()
        // 丢弃单列：切维 / 进黑名单世界时整批原包被刻意丢弃（既不合并也不放行，客户端暂时收不到
        // 那几次变更）。不计数就等于「这些包从未存在」，排查「切维后方块不更新」时没有依据。
        + "，丢弃（切维/黑名单）" + s.throttle().blockChangesDiscarded() + "）"
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
    // 遮挡判定单列：它取代了实体线程上的原生命中射线，其「是否接线 / 位图覆盖 / 是否积压」必须独立
    // 观测；「缺遮挡数据」持续偏高说明区块没被建表（射线一律放行）。
    lines.add("遮挡判定：" + (s.occlusion().active() ? "已接线" : "未接线（仅做视锥剔除）")
        + "｜位图 section " + s.occlusion().sectionsResident()
        + "，队列 " + s.occlusion().queueDepth()
        + "｜判定 提交 " + s.occlusion().submitted() + "，完成 " + s.occlusion().computed()
        + "，超时丢弃 " + s.occlusion().timeoutDiscarded()
        + "，队列满拒收 " + s.occlusion().rejectedQueueFull()
        // 判定异常单列：单条判定抛异常被就地吞掉（不改变显示状态，方向安全），但持续非 0 说明射线链路
        // 收到越界/畸形坐标等异常输入，会导致部分判定静默丢失——必须可见才能定位。
        + "，判定异常 " + s.occlusion().computeFailures()
        + "，缺遮挡数据 " + s.occlusion().unknownSection()
        + " 次，方块补丁 " + s.occlusion().patched() + " 格（失败 " + s.occlusion().patchFailures()
        // 补丁的「状态id回落」单列：>0 说明补丁暂时拿不到逐状态 id、退回了 Material 级近似，此时
        // 补丁与位图可能对「同方块不同状态」结论不同——必须可见，否则该残余差异无从察觉。
        + " 格，状态id回落 " + s.occlusion().stateIdFallbacks()
        + " 格）"
        + "，射线耗时 " + formatMillis(s.occlusion().nanosTotal()) + " ms");
    lines.add("带宽：AFK 玩家 " + s.throttle().afkPlayers() + "（累计进入 " + s.throttle().afkEntered()
        + "），AFK 丢包 " + s.throttle().afkPacketsDropped() + "，降视距 " + s.throttle().viewDistanceReduced()
        + "/还原 " + s.throttle().viewDistanceRestored());
    lines.add(bandwidthSwitches(s.bandwidth()));
    if (s.throttle().cleanupIncomplete()) {
      // 停用期有子模块清理失败：监听器/任务可能仍挂在旧实例上（停用后计数还会涨、重新启用后
      // 同一封包被处理两遍）。这类「静默残留」平时完全看不出来，必须显式点名。
      lines.add("带宽：上次停用有子模块清理失败（可能有监听器/任务残留，建议重启服务端）");
    }
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

  /**
   * 世界黑名单回显（中文；status 与 dump 共用）。空列表明确写「未配置」。
   *
   * <p><b>注意不是纯函数</b>：读的是进程级活动名单（{@link WorldBlacklistConfig#current()}），
   * 同一份 {@link Snapshot} 在名单发布前后会格式化成不同文本。
   */
  private static String worldBlacklistSummary() {
    WorldBlacklistConfig current = WorldBlacklistConfig.current();
    if (current == null) {
      return "配置未加载";
    }
    if (current.isEmpty()) {
      return "未配置（所有世界都启用反矿透与带宽优化）";
    }
    return current.patterns().size() + " 项：" + String.join(", ", current.patterns())
        + "（名单内世界同时不使用反矿透与带宽优化）";
  }

  /**
   * 字节口径行（P0-1）：「区块字节：原始 X → 输出 Y（省 Z 字节 / P.P%）」。
   * 无采样（尚无改写区块）与零/负节省（升位或扩容）都有明确的中文表述，避免被误读成统计失效。
   */
  /**
   * 人类可读的字节数（诊断用，只求量级可比）：{@code B / KB / MB / GB}，一位到两位小数。
   *
   * <p>固定 {@link Locale#ROOT}：状态面板会被写进日志与转储，不能因服务器的默认区域设置
   * 出现「1234,5 KB」这类小数点随 Locale 变化的输出（与本类其它格式化同一纪律）。
   */
  private static String bytesHuman(long bytes) {
    if (bytes < 1024L) {
      return bytes + " B";
    }
    double kilobytes = bytes / 1024.0D;
    if (kilobytes < 1024.0D) {
      return String.format(Locale.ROOT, "%.1f KB", kilobytes);
    }
    double megabytes = kilobytes / 1024.0D;
    if (megabytes < 1024.0D) {
      return String.format(Locale.ROOT, "%.1f MB", megabytes);
    }
    return String.format(Locale.ROOT, "%.2f GB", megabytes / 1024.0D);
  }

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

  /**
   * 磁盘缓存当前状态的中文短语：配置未加载 / 配置关闭 / 已启用（尚无数据）/ 已启用。
   *
   * <p>「尚无数据」与「配置关闭」必须分开：前者是空缓存（首启或被整体清理），后者是功能没开；
   * 命中率排查时这两者的处置完全不同（前者等积累，后者去开配置）。
   */
  private static String diskCacheState(Snapshot s) {
    if (s.antiXray() == null) {
      return "配置未加载";
    }
    if (!s.antiXray().diskCache().enabled()) {
      return "未启用（disk-cache.enabled=false）";
    }
    return s.diskCache().openFiles() > 0 || s.diskCache().entries() > 0 ? "已启用" : "已启用（尚无数据）";
  }

  /** 带宽各模块开关回显（一行，供 status/dump 看出每个开关的实际取值）。 */
  private static String bandwidthSwitches(BandwidthConfig c) {
    if (c == null) {
      return "带宽开关：配置未加载";
    }
    String configured = "带宽开关：总开关 " + c.enabled()
        // 零位移取消必须同时回显「行为开关」：只打模块总开关时，把 skip-zero-movement 配成 false
        // 会显示成「零位移取消 true」，而实际一个包都不会取消（且「放行」计数也不再增长，
        // 因为提前返回不做计数）——正是「看起来在生效但毫无效果」的经典误读场景。
        + "｜零位移取消 " + c.entityPackets().enabled()
        + (c.entityPackets().enabled()
            ? "（skip-zero-movement " + c.entityPackets().skipZeroMovement() + "）" : "")
        + "｜变更合并 " + c.blockChanges().enabled()
        + "｜调色板压缩 " + paletteSwitch(c.palette())
        + "｜实体剔除 " + entityCullingSwitch(c.entityCulling())
        + "｜AFK 降级 " + afkSwitch(c.afk())
        + "｜高延迟降视距 " + c.latency().enabled();
    // 上面是「配置怎么写」，下面补「实际是否注册」：两者不是一回事——Folia + 非空白名单时零位移取消
    // 整项不注册，只按配置键回显会把一个永不生效的模块显示成已启用（正是本类注释点名要避免的误读）。
    // 无差异时不加后缀，避免正常服务器多出一段噪声。
    ThrottlePipeline.ModulePlan plan = ThrottlePipeline.plan(c);
    List<String> notRegistered = new ArrayList<>();
    if (c.entityPackets().enabled() && !plan.entityPackets()) {
      notRegistered.add("零位移取消");
    }
    if (c.blockChanges().enabled() && !plan.blockChanges()) {
      notRegistered.add("变更合并");
    }
    if (c.entityCulling().enabled() && !plan.entityCulling()) {
      notRegistered.add("实体剔除");
    }
    if (c.afk().enabled() && !plan.afk()) {
      notRegistered.add("AFK 降级");
    }
    if (c.latency().enabled() && !plan.latency()) {
      notRegistered.add("高延迟降视距");
    }
    // plan() 只按配置与平台推导，不判依赖可用性：缺 ProtocolLib 时 ThrottlePipeline.startLocked
    // 的 manager==null 分支会让所有子模块都不注册，此时整行必须以「未接线」收尾，
    // 否则面板会显示「全部启用」而实际一个模块都没工作。
    //
    // 探测本身要兜底：离线单测没有 Bukkit 服务端实例（Bukkit.server == null），
    // DependencyGuard 内部会读 PluginManager 而抛 NPE——这里按「依赖可用」处理（与
    // ThrottlePipeline.isFoliaSafe 同一保守方向：探测失败绝不把模块显示成未注册）。
    if (protocolLibMissing()) {
      return configured + "｜实际未注册：全部子模块（缺少 ProtocolLib）";
    }
    return notRegistered.isEmpty() ? configured
        : configured + "｜实际未注册：" + String.join("/", notRegistered) + "（原因见启动日志）";
  }

  /**
   * ProtocolLib 是否缺失（探测失败按「不缺失」处理，见 {@code bandwidthSwitches} 的说明）。
   *
   * <p>只在 command/dump 路径调用，不在热路径上；一次异常不会影响其它诊断字段。
   */
  private static boolean protocolLibMissing() {
    try {
      return !DependencyGuard.isProtocolLibPresent();
    } catch (Throwable ignored) {
      // 离线单测 / 服务端尚未初始化：无法判定时按「存在」处理，避免误报「全部未注册」
      return false;
    }
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
      // 显示「有效距离门」而非配置原值：实际门槛是 max(min-distance, force-visible-distance)。
      // 只印配置值会出现「配了 24 却从 32 格才生效」的误读（与启动日志的有效值不一致），
      // 这里与 dump 的 frustum.有效距离 保持同一口径。
      sb.append("（距离门 ").append(frustumEffectiveDistance(culling)).append(" 格，FOV ")
          .append(frustum.fov()).append("°）");
    } else {
      sb.append("关");
    }
    return sb.append("）").toString();
  }

  /**
   * 视锥剔除的<b>有效距离门</b>（格）= {@code max(frustum.min-distance, force-visible-distance)}。
   *
   * <p>强制可见距离内一律不剔除是既有的安全策略，因此距离门不可能小于它；配置值比它小时不产生
   * 额外效果。status 与 dump 都按此有效值回显，避免「读到的配置值」与「真正生效的门槛」不一致。
   */
  private static double frustumEffectiveDistance(BandwidthConfig.EntityCulling culling) {
    BandwidthConfig.EntityCulling.Frustum frustum = culling.frustum();
    if (frustum == null) {
      return culling.forceVisibleDistance();
    }
    return Math.max(frustum.minDistance(), culling.forceVisibleDistance());
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

  /**
   * 转储文本格式化：状态面板 + 环境信息 + 配置项有效值。
   *
   * <p>与 {@link #formatStatus(Snapshot)} 一样<b>并非严格纯函数</b>（内含状态面板，会读进程级活动名单与
   * 平台 / 依赖判定），详见后者的 javadoc。
   */
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
    sb.append("world-blacklist（独立文件 blacklist.yml）=").append(worldBlacklistSummary())
        .append('\n');
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
        .append("，extra-non-occluding=").append(c.occlusion().extraNonOccluding()).append('\n');
    sb.append("neighbors.enabled=").append(c.neighbors().enabled())
        .append("，missing-policy=").append(c.neighbors().missingPolicy())
        .append("，cache-maximum-size=").append(c.neighbors().cacheMaximumSize()).append('\n');
    sb.append("proximity.instant-reveal.enabled=").append(c.proximity().instantReveal().enabled())
        .append("，radius=").append(c.proximity().instantReveal().radius())
        .append("，max-per-tick=").append(c.proximity().instantReveal().maxPerTick())
        .append("，pre-reveal-on-dig=").append(c.proximity().preRevealOnDig()).append('\n');
    sb.append("proximity.over-reveal-sampling=").append(c.proximity().overRevealSampling())
        .append("，batch-reveal-sends=").append(c.proximity().batchRevealSends()).append('\n');
    sb.append("proximity.enabled=").append(c.proximity().enabled())
        .append("，distance-chunks=").append(c.proximity().distanceChunks())
        .append("，reveal-on-chunk-send=").append(c.proximity().revealOnChunkSend())
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
    // 内存改写缓存是「条目数 + MB 上限」双上限：只回显条目数时，改大 maximum-mb 或观察大区块占满
    // 预算都无从判断。这里回显两个上限的生效值（配置侧单位是 MB，内部按字节记账）。
    sb.append("cache.maximum-size=").append(c.cacheMaximumSize())
        .append("，maximum-mb=").append(c.cacheMaximumMb())
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
            ? "无" : frustumEffectiveDistance(c.entityCulling()))
        .append('\n');
    // 异步遮挡的全部调优键：它们直接决定射线成本与「多藏」倾向，dump 里缺任一项，
    // 排查「实体为什么迟迟不显示 / 隐藏得太久」时就只能靠猜配置。
    BandwidthConfig.EntityCulling.AsyncOcclusion async = c.entityCulling().asyncOcclusion();
    sb.append("entity-culling.occlusion-time-budget-ms=")
        .append(async == null ? "无" : async.timeBudgetMillis())
        .append("，occlusion-threads=").append(async == null ? "无" : async.threads())
        .append("，max-occluding-blocks=").append(async == null ? "无" : async.maxOccludingBlocks())
        .append("，occlusion-timeout-cycles=").append(async == null ? "无" : async.timeoutCycles())
        .append("，occlusion-hysteresis-cycles=").append(async == null ? "无" : async.hysteresisCycles())
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