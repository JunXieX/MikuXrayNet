package net.mikumc.mikuxraynet.antixray;

import java.util.function.Supplier;
import net.mikumc.mikuxraynet.cache.DiskPayload;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;

/**
 * 区块改写管线（纯编排）：内存缓存 → 磁盘缓存 → 解码改写 → 回填两级缓存。
 *
 * <p><b>为什么单独成类</b>：这段编排决定了「命中什么、什么时候视为未命中、失败结果是否入缓存」，
 * 此前嵌在封包监听器里，只能靠真机验证。抽出后缓存 / 磁盘 / 邻块 / 指纹 / 种子全部经接口注入，
 * 可用假实现做确定性测试（见 {@code RewritePipelineTest}）。
 *
 * <p>本类<b>不碰 Bukkit / ProtocolLib，也不写统计</b>：结果经 {@link Outcome} 交给调用方计数与写回。
 */
public final class RewritePipeline {

  private static final byte[] EMPTY_BYTES = new byte[0];

  /** 磁盘缓存端口（生产实现包 {@code DiskCacheStore}）。 */
  public interface DiskStore {

    boolean usable();

    byte[] get(String worldName, int chunkX, int chunkZ, int fingerprint);

    void put(String worldName, int chunkX, int chunkZ, int fingerprint, int encodedBytes,
        Supplier<byte[]> payload);
  }

  /** 邻块贴边快照来源；本模式不需要时返回 {@code null}。 */
  @FunctionalInterface
  public interface NeighborSource {

    NeighborEdges get(String worldName, int chunkX, int chunkZ, AntiXrayConfig.Dimension dimension);
  }

  /** 缓存指纹来源（生产走 {@link RewriteFingerprint}）。 */
  @FunctionalInterface
  public interface FingerprintSource {

    int of(NeighborEdges neighbors);
  }

  /** 伪装随机种子（生产按「世界名 + 区块坐标」派生，保证同区块确定性）。 */
  @FunctionalInterface
  public interface SeedSource {

    long of(String worldName, int chunkX, int chunkZ);
  }

  /** 一次改写请求（纯值）。 */
  public record Request(String worldName, int chunkX, int chunkZ, int minHeight, int sectionCount,
      AntiXrayConfig.Dimension dimension, byte[] source, long sourceHash, long[] occlusionStamps) {
  }

  /**
   * 一次改写结果。
   *
   * @param cached          命中值（内存或磁盘）；未命中为 {@code null}
   * @param result          未命中时的改写结果；命中时为 {@code null}
   * @param fromDisk        是否由磁盘回填（供诊断口径）
   * @param contentRejected 命中但不可用的次数（源指纹不符 / 缺遮挡位图），供调用方计入
   *                        {@code cacheContentRejected}
   * @param diskRejected    磁盘负载存在但解码失败或源指纹不符，供调用方计入 {@code diskPayloadRejected}
   */
  public record Outcome(CachedChunk cached, ObfuscationProcessor.Result result, boolean fromDisk,
      int contentRejected, boolean diskRejected) {

    public boolean hit() {
      return cached != null;
    }
  }

  private final ObfuscationProcessor processor;
  private final RewriteCache<CachedChunk> cache;
  private final DiskStore disk;
  private final NeighborSource neighbors;
  private final FingerprintSource fingerprint;
  private final SeedSource seed;

  public RewritePipeline(ObfuscationProcessor processor, RewriteCache<CachedChunk> cache,
      DiskStore disk, NeighborSource neighbors, FingerprintSource fingerprint, SeedSource seed) {
    this.processor = processor;
    this.cache = cache;
    this.disk = disk;
    this.neighbors = neighbors;
    this.fingerprint = fingerprint;
    this.seed = seed;
  }

  /** 执行一次「查缓存 → 未命中则改写 → 回填」；不写回封包、不计数。 */
  public Outcome rewrite(Request request) {
    NeighborEdges edges = neighbors.get(request.worldName(), request.chunkX(), request.chunkZ(),
        request.dimension());
    int print = fingerprint.of(edges);
    int rejected = 0;

    CachedChunk cached = cache.get(request.worldName(), request.chunkX(), request.chunkZ(), print);
    if (cached != null && cached.sourceHash() != request.sourceHash()) {
      // 键命中但内容已变：命中计数 +1 却用不上，必须单列，否则命中率虚高且磁盘查询数对不上
      rejected++;
      cached = null;
    }
    boolean diskRejected = false;
    boolean fromDisk = false;
    if (cached == null && disk != null && disk.usable()) {
      byte[] payload = disk.get(request.worldName(), request.chunkX(), request.chunkZ(), print);
      if (payload != null) {
        DiskPayload.Decoded decoded = DiskPayload.decode(payload, request.sourceHash());
        if (decoded == null) {
          // 读到了负载但用不上（信封损坏或原始字节指纹不符）：这是「命中率正常却仍在重写」的唯一出口，
          // 必须让调用方计数——否则会被混进普通未命中，无法区分是内容指纹还是配置指纹在拦。
          diskRejected = true;
        } else {
          cached = new CachedChunk(decoded.sourceHash(), decoded.data(), decoded.positions(),
              decoded.occlusion());
          cache.put(request.worldName(), request.chunkX(), request.chunkZ(), print, cached);
          fromDisk = true;
        }
      }
    }
    if (cached != null && processor.occlusionEnabled() && cached.occlusion() == null) {
      // 启用遮挡却没有位图：直接命中会让该区块长期「已伪装但无位图」→ 显形侧按缺数据=遮挡 → 玩家漏矿。
      // 视为未命中重写一次（代价一次性），逼出位图。
      rejected++;
      cached = null;
      fromDisk = false;
    }
    if (cached != null) {
      return new Outcome(cached, null, fromDisk, rejected, diskRejected);
    }

    boolean occlusionRequired = processor.occlusionEnabled();
    ObfuscationProcessor.Result result = processor.rewrite(request.source(),
        request.sectionCount(), seed.of(request.worldName(), request.chunkX(), request.chunkZ()),
        edges, request.worldName(), request.dimension(), request.minHeight(), request.chunkX(),
        request.chunkZ(), request.occlusionStamps());
    if (resultCacheable(result, occlusionRequired)) {
      // 无伪装坐标的区块两级缓存都不保留区块字节：命中后不做任何写回（writeBack 见空坐标即返回），
      // 留着只会按几十 KB 计入内存/磁盘预算，把真正改写过的条目挤出去。
      int[] positions = result.obfuscatedPositions();
      byte[] bytes = result.changed() ? result.data() : EMPTY_BYTES;
      CachedChunk value = new CachedChunk(request.sourceHash(), bytes, positions, result.occlusion());
      cache.put(request.worldName(), request.chunkX(), request.chunkZ(), print, value);
      if (disk != null && disk.usable()) {
        // 惰性负载：先按编码长度做容量判定，通过后才真正编码（编码要整块拷贝几十~几百 KB）。
        disk.put(request.worldName(), request.chunkX(), request.chunkZ(), print,
            DiskPayload.encodedLength(positions, result.occlusion(), bytes),
            () -> DiskPayload.encode(request.sourceHash(), positions, result.occlusion(), bytes));
      }
    }
    return new Outcome(null, result, false, rejected, diskRejected);
  }

  /**
   * 改写结果是否允许写入缓存。
   *
   * <p><b>为什么失败结果绝不入缓存</b>：解码/重编码异常通常是瞬时故障；若把「原样字节 + 空位置」固化进
   * 任一级缓存，同指纹区块就会在内存有效期乃至磁盘过期前都不再重试改写——瞬时故障被永久化。
   * 放行语义不受影响：失败时 {@code data == source} 且位置为空，{@code writeBack} 直接跳过。
   */
  public static boolean resultCacheable(ObfuscationProcessor.Result result) {
    return result != null && !result.failed();
  }

  /**
   * 带「遮挡位图必须随负载一起缓存」约束的可缓存判定。
   *
   * <p>启用异步遮挡时，位图缺失的结果不入缓存，逼下次重写重建位图；否则该区块会长期「已伪装、但永远
   * 没有位图」，显形侧按缺数据=遮挡判定，玩家挖到的矿一直显示假方块。
   */
  public static boolean resultCacheable(ObfuscationProcessor.Result result,
      boolean occlusionRequired) {
    if (!resultCacheable(result)) {
      return false;
    }
    return !occlusionRequired || result.occlusion() != null;
  }
}
