package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.mikumc.mikuxraynet.cache.DiskPayload;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.occlusion.EmptyOcclusion;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import org.junit.jupiter.api.Test;

/**
 * 改写管线：缓存命中 / 源指纹失效 / 磁盘回填 / 缺位图重写 / 失败不入缓存。
 *
 * <p>用真实的 {@link ObfuscationProcessor}、{@link ChunkCodec}、{@link RewriteCache} 与真实的
 * 区块字节，只把「磁盘」「邻块」「指纹」「种子」换成可注入的假实现——测的是生产编排本身，
 * 不是复制一份逻辑。
 */
class RewritePipelineTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int ORE = 2;
  private static final ChunkVersionFlags FLAGS = ChunkVersionFlags.PAPER_26_2;
  private static final int FINGERPRINT = 7;

  private static RegistryAccessor registry() {
    return new RegistryAccessor() {
      @Override
      public boolean isAir(int blockId) {
        return blockId == AIR;
      }

      @Override
      public boolean isFluid(int blockId) {
        return false;
      }

      @Override
      public int getUniqueBlockStateCount() {
        return 1 << 15;
      }

      @Override
      public int getMaxBitsPerBlockState() {
        return 15;
      }
    };
  }

  private static byte[] chunkBytes() {
    int[] palette = {STONE, ORE};
    int[] indices = new int[4096];
    Arrays.fill(indices, 1); // 整段全是矿
    return new TestChunkBuilder(FLAGS)
        .indirectSection(4, 4096, 0, palette, indices, 0, new int[] {1})
        .build();
  }

  private static ObfuscationProcessor processor(OcclusionStores stores) {
    ChunkCodec codec = new ChunkCodec(registry(), FLAGS);
    BitSet targets = new BitSet(1 << 15);
    targets.set(ORE);
    ObfuscationProcessor.WorldProfile profile = new ObfuscationProcessor.WorldProfile(targets,
        new int[] {STONE}, new int[] {10}, new int[0], new int[0], new int[0][], new int[0][],
        Integer.MIN_VALUE, Integer.MAX_VALUE, true);
    return new ObfuscationProcessor(codec, state -> true, false, true,
        new ObfuscationProcessor.PaletteOptions(false, false), null, profile,
        new ObfuscationProcessor.WorldProfile[] {profile, profile, profile},
        new ObfuscationProcessor.WorldProfile[0][], null, stores, state -> true);
  }

  private static RewriteCache<CachedChunk> cache() {
    return new RewriteCache<>(64, 8L * 1024 * 1024, 600L, chunk -> chunk.data().length);
  }

  private static RewritePipeline pipeline(OcclusionStores stores, RewriteCache<CachedChunk> cache,
      FakeDisk disk) {
    return new RewritePipeline(processor(stores), cache, disk, (world, x, z, dimension) -> null,
        neighbors -> FINGERPRINT, (world, x, z) -> 42L);
  }

  private static RewritePipeline.Request request(byte[] source, long sourceHash) {
    return new RewritePipeline.Request("world", 0, 0, 0, 1,
        AntiXrayConfig.Dimension.NORMAL, source, sourceHash, null);
  }

  @Test
  void firstRewriteFillsCacheAndSecondHits() {
    byte[] source = chunkBytes();
    RewriteCache<CachedChunk> cache = cache();
    FakeDisk disk = new FakeDisk();
    RewritePipeline pipeline = pipeline(null, cache, disk);

    RewritePipeline.Outcome first = pipeline.rewrite(request(source, 11L));
    assertFalse(first.hit(), "首次必须未命中");
    assertNotNull(first.result(), "未命中必须带回改写结果");
    assertTrue(first.result().changed(), "整段矿必须被伪装");
    assertArrayEquals(source, source.clone());

    RewritePipeline.Outcome second = pipeline.rewrite(request(source, 11L));
    assertTrue(second.hit(), "同源字节必须命中");
    assertFalse(second.fromDisk(), "第二次应来自内存缓存");
    assertArrayEquals(first.result().data(), second.cached().data(), "命中值必须与写入一致");
    assertTrue(second.cached().positions().length > 0);
    assertFalse(disk.payloads.isEmpty(), "未命中结果必须同时回填磁盘");
  }

  @Test
  void changedSourceBytesInvalidateTheCacheEntry() {
    byte[] source = chunkBytes();
    RewriteCache<CachedChunk> cache = cache();
    RewritePipeline pipeline = pipeline(null, cache, new FakeDisk());

    pipeline.rewrite(request(source, 11L));
    RewritePipeline.Outcome second = pipeline.rewrite(request(source, 12L));
    assertFalse(second.hit(), "源字节变了必须视为未命中");
    assertEquals(1, second.contentRejected(), "内容被拒必须单列，不能算有效命中");
  }

  @Test
  void diskHitBackfillsMemoryCache() {
    byte[] source = chunkBytes();
    FakeDisk disk = new FakeDisk();
    // 先跑一次拿到真实负载，再清空内存缓存模拟「重启后磁盘命中」
    RewriteCache<CachedChunk> warm = cache();
    RewritePipeline warmPipeline = pipeline(null, warm, disk);
    RewritePipeline.Outcome warmed = warmPipeline.rewrite(request(source, 11L));
    assertNotNull(warmed.result());

    RewriteCache<CachedChunk> cold = cache();
    RewritePipeline coldPipeline = pipeline(null, cold, disk);
    RewritePipeline.Outcome hit = coldPipeline.rewrite(request(source, 11L));
    assertTrue(hit.hit(), "磁盘负载必须命中");
    assertTrue(hit.fromDisk(), "命中来源应为磁盘");
    assertArrayEquals(warmed.result().data(), hit.cached().data());

    RewritePipeline.Outcome again = coldPipeline.rewrite(request(source, 11L));
    assertTrue(again.hit());
    assertFalse(again.fromDisk(), "磁盘命中后必须回填内存，第二次不再读盘");
  }

  @Test
  void diskPayloadWithWrongSourceHashIsRejected() {
    byte[] source = chunkBytes();
    FakeDisk disk = new FakeDisk();
    disk.payloads.put("world,0,0," + FINGERPRINT,
        DiskPayload.encode(999L, new int[] {0}, null, source));
    RewritePipeline pipeline = pipeline(null, cache(), disk);

    RewritePipeline.Outcome outcome = pipeline.rewrite(request(source, 11L));
    assertFalse(outcome.hit());
    assertTrue(outcome.diskRejected(), "负载存在但源指纹不符必须计入磁盘拒绝");
  }

  @Test
  void hitWithoutOcclusionPayloadIsTreatedAsMissWhenRequired() {
    byte[] source = chunkBytes();
    OcclusionStores stores = new OcclusionStores();
    RewriteCache<CachedChunk> cache = cache();
    // 手工塞一条「有数据但无遮挡负载」的条目（旧版本产物）
    cache.put("world", 0, 0, FINGERPRINT,
        new CachedChunk(11L, source, new int[] {0}, null));

    RewritePipeline pipeline = pipeline(stores, cache, new FakeDisk());
    RewritePipeline.Outcome outcome = pipeline.rewrite(request(source, 11L));
    assertFalse(outcome.hit(), "启用遮挡时缺位图的命中必须视为未命中");
    assertEquals(1, outcome.contentRejected());
    assertNotNull(outcome.result().occlusion(), "重写必须产出遮挡负载");
  }

  @Test
  void failedResultsAreNotCacheable() {
    ObfuscationProcessor.Result failed = new ObfuscationProcessor.Result(new byte[0], new int[0],
        "boom", null, null);
    assertFalse(RewritePipeline.resultCacheable(failed));
    assertFalse(RewritePipeline.resultCacheable(failed, false));
    assertFalse(RewritePipeline.resultCacheable(null, false));

    ObfuscationProcessor.Result ok = new ObfuscationProcessor.Result(new byte[0], new int[0],
        null, null, null);
    assertTrue(RewritePipeline.resultCacheable(ok));
    assertTrue(RewritePipeline.resultCacheable(ok, false));
    assertFalse(RewritePipeline.resultCacheable(ok, true), "启用遮挡却无位图时不得入缓存");
  }

  /** 内存磁盘：与真实 DiskCacheStore 同一接口语义（命中/未命中/写入），不涉及 IO。 */
  private static final class FakeDisk implements RewritePipeline.DiskStore {

    private final Map<String, byte[]> payloads = new HashMap<>();
    private boolean usable = true;

    @Override
    public boolean usable() {
      return usable;
    }

    @Override
    public byte[] get(String worldName, int chunkX, int chunkZ, int fingerprint) {
      return payloads.get(worldName + "," + chunkX + "," + chunkZ + "," + fingerprint);
    }

    @Override
    public void put(String worldName, int chunkX, int chunkZ, int fingerprint, int encodedBytes,
        Supplier<byte[]> payload) {
      payloads.put(worldName + "," + chunkX + "," + chunkZ + "," + fingerprint, payload.get());
    }
  }
}
