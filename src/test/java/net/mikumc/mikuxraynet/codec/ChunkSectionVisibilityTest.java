package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.mikumc.mikuxraynet.occlusion.BitmapOcclusion;
import net.mikumc.mikuxraynet.occlusion.EmptyOcclusion;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;
import net.mikumc.mikuxraynet.occlusion.SolidOcclusion;
import org.junit.jupiter.api.Test;

/**
 * {@link ChunkSection#buildOcclusionAndTargets} 的等价性：位图必须与逐格参照实现逐格一致，
 * 目标索引必须与逐格扫描的升序结果完全一致（含单值 / 间接 / 直接三种调色板）。
 */
class ChunkSectionVisibilityTest {

  private static final ChunkVersionFlags FLAGS = ChunkVersionFlags.PAPER_26_2;
  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final int ORE = 2;
  private static final int ORE2 = 3;
  private static final int VOLUME = 4096;

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

  private static boolean occluding(int state) {
    return state == STONE || state == ORE || state == ORE2;
  }

  private static boolean target(int state) {
    return state == ORE || state == ORE2;
  }

  @Test
  void singleValueSectionsAgree() {
    verify(new TestChunkBuilder(FLAGS)
        .singleValueSection(STONE, VOLUME, 0, 0, new int[] {7}).build());
    verify(new TestChunkBuilder(FLAGS)
        .singleValueSection(ORE, VOLUME, 0, 0, new int[] {7}).build());
    verify(new TestChunkBuilder(FLAGS)
        .singleValueSection(AIR, 0, 0, 0, new int[] {7}).build());
  }

  @Test
  void indirectSectionsAgree() {
    int[] palette = {AIR, STONE, ORE, ORE2};
    int[] indices = new int[VOLUME];
    int nonAir = 0;
    for (int index = 0; index < VOLUME; index++) {
      int id = (index * 7 + index / 16) & 3;
      indices[index] = id;
      if (palette[id] != AIR) {
        nonAir++;
      }
    }
    verify(new TestChunkBuilder(FLAGS)
        .indirectSection(4, nonAir, 0, palette, indices, 0, new int[] {7}).build());
  }

  @Test
  void directSectionsAgree() {
    int[] states = new int[VOLUME];
    int nonAir = 0;
    for (int index = 0; index < VOLUME; index++) {
      int state = (index % 11 == 0) ? ORE : (index % 5 == 0 ? ORE2 : (index % 3 == 0 ? STONE : AIR));
      states[index] = state;
      if (state != AIR) {
        nonAir++;
      }
    }
    verify(new TestChunkBuilder(FLAGS)
        .directSection(15, nonAir, 0, states, 0, new int[] {7}).build());
  }

  /** 位图与目标索引都要与「逐格参照实现」完全一致；三种调用组合（双开 / 只建图 / 只收集）都验证。 */
  private static void verify(byte[] raw) {
    try (Chunk chunk = new ChunkCodec(registry(), FLAGS).decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      SectionOcclusion expectedView =
          BitmapOcclusion.fromStateLookup(section::getBlockState,
              ChunkSectionVisibilityTest::occluding);
      List<Integer> expectedTargets = new ArrayList<>();
      for (int index = 0; index < VOLUME; index++) {
        if (target(section.getBlockState(index))) {
          expectedTargets.add(index);
        }
      }

      List<Integer> actualTargets = new ArrayList<>();
      SectionOcclusion actualView = section.buildOcclusionAndTargets(
          ChunkSectionVisibilityTest::occluding, ChunkSectionVisibilityTest::target,
          actualTargets::add);
      assertSameView(expectedView, actualView);
      assertEquals(expectedTargets, actualTargets, "目标索引必须与逐格扫描一致（升序）");

      List<Integer> onlyTargets = new ArrayList<>();
      assertNull(section.buildOcclusionAndTargets(null, ChunkSectionVisibilityTest::target,
          onlyTargets::add), "不建位图时必须返回 null");
      assertEquals(expectedTargets, onlyTargets, "只收集目标时结果必须一致");

      SectionOcclusion onlyBitmap =
          section.buildOcclusionAndTargets(ChunkSectionVisibilityTest::occluding, null, null);
      assertSameView(expectedView, onlyBitmap);
    }
  }

  private static void assertSameView(SectionOcclusion expected, SectionOcclusion actual) {
    if (expected instanceof EmptyOcclusion) {
      assertSame(EmptyOcclusion.INSTANCE, actual, "空段必须是共享的 EmptyOcclusion");
      return;
    }
    if (expected instanceof SolidOcclusion) {
      assertSame(SolidOcclusion.INSTANCE, actual, "全遮挡段必须是共享的 SolidOcclusion");
      return;
    }
    assertTrue(actual instanceof BitmapOcclusion,
        "混合段必须是 BitmapOcclusion，实际为 " + actual);
    for (int y = 0; y < 16; y++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          assertEquals(expected.isOccluding(x, y, z), actual.isOccluding(x, y, z),
              "位图在局部坐标 (" + x + "," + y + "," + z + ") 不一致");
        }
      }
    }
  }
}
