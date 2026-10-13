package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.function.IntPredicate;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.RegistryAccessor;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import net.mikumc.mikuxraynet.occlusion.EmptyOcclusion;
import net.mikumc.mikuxraynet.occlusion.OcclusionRay;
import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import net.mikumc.mikuxraynet.occlusion.SolidOcclusion;
import org.junit.jupiter.api.Test;

/**
 * 反矿透改写路径的「异步遮挡建表旁路」测试：{@link ObfuscationProcessor#rewrite} 在解码之后、改写之前
 * 把各 section 的遮挡位图写入 {@link OcclusionStores} 中该世界那一张表，供带宽侧异步射线只读消费。
 *
 * <p>覆盖三种形态：单值实心段（快路径 → {@link SolidOcclusion}）、全空气段
 * （→ {@link EmptyOcclusion}「已知全不遮挡」）、混合段（→ 逐格位图），并端到端验证
 * 「写入的位图能被步进射线使用」以及「缓存命中路径能从负载恢复整列」。
 */
class OcclusionCaptureTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int ORE = 2;
  private static final int GLASS = 3;

  /** 极简注册表桩：4 个状态（直接格式位宽 2），其中石头与矿遮挡、空气与玻璃不遮挡。 */
  private static final RegistryAccessor REGISTRY = new RegistryAccessor() {
    @Override
    public int getUniqueBlockStateCount() {
      return 4;
    }

    @Override
    public int getMaxBitsPerBlockState() {
      return 2;
    }

    @Override
    public boolean isAir(int blockId) {
      return blockId == AIR;
    }

    @Override
    public boolean isFluid(int blockId) {
      return false;
    }
  };

  private static final IntPredicate OCCLUDING = id -> id == STONE || id == ORE;

  @Test
  void solidSectionIsCapturedAsSharedSolidFastPath() {
    OcclusionStore store = capture(singleValue(STONE, 4096), 5, 6).of("world");
    assertSame(SolidOcclusion.INSTANCE, store.section(5, 0, 6),
        "整段同一种遮挡方块时应写入共享的 SolidOcclusion 快路径");
  }

  @Test
  void allAirSectionIsCapturedAsKnownEmptyFastPath() {
    OcclusionStore store = capture(singleValue(AIR, 0), 5, 6).of("world");
    assertSame(EmptyOcclusion.INSTANCE, store.section(5, 0, 6),
        "全空气段应记为「已知全不遮挡」快路径（与「没有该段数据」的 null 区分开）");
  }

  @Test
  void mixedSectionIsCapturedAsPerCellBitmap() {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, STONE);
    states[index(1, 2, 3)] = GLASS;
    states[index(4, 5, 6)] = AIR;

    OcclusionStore store = capture(mixed(states), 5, 6).of("world");
    SectionOcclusion section = store.section(5, 0, 6);
    assertNotNull(section, "混合段必须留下逐格位图");
    assertFalse(section.isSolid());
    assertTrue(section.isOccludingLocal(0, 0, 0), "石头格应遮挡");
    assertFalse(section.isOccludingLocal(1, 2, 3), "玻璃格不遮挡");
    assertFalse(section.isOccludingLocal(4, 5, 6), "空气格不遮挡");
    assertTrue(section.isOccludingLocal(15, 15, 15), "默认石头格应遮挡");
  }

  @Test
  void capturedBitmapIsUsableBySteppingRay() {
    // 实心段覆盖区块 (5,6) 的 sectionY=0 → 世界方块 x∈[80,95]、y∈[0,15]、z∈[96,111]
    OcclusionStore store = capture(singleValue(STONE, 4096), 5, 6).of("world");
    assertFalse(OcclusionRay.lineOfSight(store, 80.5, 0.5, 96.5, 90.5, 0.5, 96.5, 0, 0, 1e9),
        "实心段内任意两点之间应不通畅");

    // 与空段对照：另一个未建表的 section 查不到 → 按「不遮挡」处理，射线通畅
    assertNull(store.section(9, 0, 6));
    assertTrue(OcclusionRay.lineOfSight(store, (9 << 4) + 0.5, 0.5, 96.5, (9 << 4) + 10.5, 0.5, 96.5,
        0, 0, 1e9), "查不到遮挡数据的 section 按不遮挡处理（fail-open）");
  }

  @Test
  void captureIsSkippedWhenOcclusionStoreIsNull() {
    // 未启用异步遮挡（仓库注册表=null）时，改写照常进行且不抛异常、也不携带遮挡负载
    ObfuscationProcessor processor = processor((OcclusionStores) null);
    ObfuscationProcessor.Result result = processor.rewrite(singleValue(STONE, 4096), 1, 42L, null,
        "world", net.mikumc.mikuxraynet.config.AntiXrayConfig.Dimension.NORMAL, 0, 5, 6);
    assertFalse(result.failed(), "关闭建表时改写不得失败：" + result.failure());
    assertNull(result.occlusion(), "未启用该功能时结果不得携带遮挡负载（零开销）");
  }

  /**
   * 方案 b 的核心回归：<b>缓存命中路径</b>（不解码）只能靠随负载一起缓存的遮挡数据恢复整列。
   */
  @Test
  void cacheHitPathRestoresOcclusionFromCarriedPayload() {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, STONE);
    states[index(1, 2, 3)] = GLASS;
    states[index(7, 8, 9)] = AIR;
    byte[] source = mixed(states);

    // 解码路径：建表，并拿到随结果缓存的整列遮挡负载
    OcclusionStores missStores = new OcclusionStores();
    ObfuscationProcessor.Result result = processor(missStores).rewrite(source, 1, 42L, null,
        "world", net.mikumc.mikuxraynet.config.AntiXrayConfig.Dimension.NORMAL, 0, 5, 6);
    assertFalse(result.failed(), "建表路径不得让改写失败：" + result.failure());
    byte[] blob = result.occlusion();
    assertNotNull(blob, "启用建表时结果必须携带遮挡负载（否则缓存命中路径拿不到位图）");

    // 缓存命中路径：全新的空仓库 + 只喂负载，不经过任何解码
    OcclusionStores hitStores = new OcclusionStores();
    assertNull(hitStores.lookup("world"), "恢复前不应存在该世界的仓库");
    processor(hitStores).restoreOcclusion(blob, "world", 5, 0, 6);

    SectionOcclusion restored = hitStores.of("world").section(5, 0, 6);
    assertNotNull(restored, "缓存命中路径必须能把整列遮挡数据恢复进仓库");
    SectionOcclusion decoded = missStores.of("world").section(5, 0, 6);
    for (int x = 0; x < 16; x++) {
      for (int y = 0; y < 16; y++) {
        for (int z = 0; z < 16; z++) {
          assertEquals(decoded.isOccludingLocal(x, y, z), restored.isOccludingLocal(x, y, z),
              "恢复出的位图必须与解码路径逐格一致，坐标 (" + x + "," + y + "," + z + ")");
        }
      }
    }
  }

  @Test
  void restoringMalformedPayloadIsFailOpen() {
    OcclusionStores stores = new OcclusionStores();
    ObfuscationProcessor processor = processor(stores);
    processor.restoreOcclusion(new byte[] {1, 2, 3}, "world", 5, 0, 6);
    assertNull(stores.of("world").section(5, 0, 6), "畸形负载必须按「无遮挡数据」处理，不得写入脏数据");
    processor.restoreOcclusion(null, "world", 5, 0, 6);
    assertNull(stores.of("world").section(5, 0, 6));
  }

  private static int index(int x, int y, int z) {
    return y << 8 | z << 4 | x;
  }

  private static byte[] singleValue(int blockState, int blockCount) {
    return new TestChunkBuilder(ChunkVersionFlags.PAPER_26_2)
        .singleValueSection(blockState, blockCount, 0, 0, new int[] {7})
        .build();
  }

  /**
   * 混合段用<b>间接</b>调色板（4 位）：codec 明确拒绝 bitsPerBlock 2/3（本项目「非法编码一律拒绝」的约定），
   * 而直接格式的位宽必须等于注册表位宽——本桩只有 4 个状态（位宽 2）无法走直接格式。
   */
  private static byte[] mixed(int[] states) {
    int[] palette = {STONE, GLASS, AIR};
    int[] indices = new int[4096];
    int nonAir = 0;
    for (int i = 0; i < 4096; i++) {
      int state = states[i];
      indices[i] = state == GLASS ? 1 : (state == AIR ? 2 : 0);
      if (state != AIR) {
        nonAir++;
      }
    }
    return new TestChunkBuilder(ChunkVersionFlags.PAPER_26_2)
        .indirectSection(4, nonAir, 0, palette, indices, 0, new int[] {7})
        .build();
  }

  /** 建表并返回仓库注册表（区块坐标固定，方便断言；取用某世界时再 {@code .of("world")}）。 */
  private static OcclusionStores capture(byte[] source, int chunkX, int chunkZ) {
    OcclusionStores stores = new OcclusionStores();
    ObfuscationProcessor processor = processor(stores);
    ObfuscationProcessor.Result result = processor.rewrite(source, 1, 42L, null, "world",
        net.mikumc.mikuxraynet.config.AntiXrayConfig.Dimension.NORMAL, 0, chunkX, chunkZ);
    assertFalse(result.failed(), "建表路径不得让改写失败：" + result.failure());
    return stores;
  }

  /** 构造处理器：目标为 ORE，伪装为 STONE；enclosed 模式（不做遮挡判定的目标一律处理）。 */
  private static ObfuscationProcessor processor(OcclusionStores occlusionStores) {
    BitSet targets = new BitSet();
    targets.set(ORE);
    ObfuscationProcessor.WorldProfile profile = new ObfuscationProcessor.WorldProfile(targets,
        new int[] {STONE}, new int[] {1},
        new int[0], new int[0], new int[0][], new int[0][],
        Integer.MIN_VALUE, Integer.MAX_VALUE, true);
    return new ObfuscationProcessor(new ChunkCodec(REGISTRY, ChunkVersionFlags.PAPER_26_2),
        OCCLUDING, false, true, new ObfuscationProcessor.PaletteOptions(false, false), null,
        profile, new ObfuscationProcessor.WorldProfile[] {profile, profile, profile},
        new ObfuscationProcessor.WorldProfile[0][3], null, occlusionStores, null);
  }
}
