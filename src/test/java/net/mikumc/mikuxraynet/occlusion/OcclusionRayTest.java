package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link OcclusionRay} 步进算法的黄金用例：合成世界（空、单面墙、带洞墙、实心）与期望结论一一对应。
 *
 * <p>判定方向约定：返回 {@code true} = 通畅（能看到）。实体隐藏逻辑取「所有顶点都不通畅」才隐藏，
 * 因此这里的用例只需锁定「通畅 / 不通畅」本身。
 */
class OcclusionRayTest {

  /** 合成世界的遮挡谓词（世界坐标 → 是否遮挡）。 */
  @FunctionalInterface
  private interface WorldOcclusion {
    boolean isOccluding(int x, int y, int z);
  }

  /** 足够大的最远射线距离平方（等价于不启用上限）。 */
  private static final double FAR = 1e9;

  @Test
  void emptyWorldIsAlwaysClear() {
    assertTrue(lineOfSight(world((x, y, z) -> false), 0.5, 0.5, 0.5, 5.5, 0.5, 0.5, 0, 0, FAR));
    // 未知 section（lookup 恒返回 null）同样按通畅处理（fail-open）
    assertTrue(lineOfSight((cx, sy, cz) -> null, 0.5, 0.5, 0.5, 40.5, 20.5, 9.5, 0, 0, FAR));
  }

  @Test
  void solidWallBlocksTheRay() {
    // x == 2 的整面墙
    WorldOcclusion wall = (x, y, z) -> x == 2;
    assertFalse(lineOfSight(world(wall), 0.5, 0.5, 0.5, 5.5, 0.5, 0.5, 0, 0, FAR));
  }

  @Test
  void holeInWallLetsTheRayThrough() {
    // x == 2 的墙，仅在 (y=0,z=0) 处留洞；射线正好从洞里穿过
    WorldOcclusion wallWithHole = (x, y, z) -> x == 2 && !(y == 0 && z == 0);
    assertTrue(lineOfSight(world(wallWithHole), 0.5, 0.5, 0.5, 5.5, 0.5, 0.5, 0, 0, FAR));

    // 洞开在别的高度，射线仍然被挡
    WorldOcclusion wallWithHighHole = (x, y, z) -> x == 2 && !(y == 5 && z == 0);
    assertFalse(lineOfSight(world(wallWithHighHole), 0.5, 0.5, 0.5, 5.5, 0.5, 0.5, 0, 0, FAR));
  }

  @Test
  void maxOccludingToleratesUpToThatManyBlocks() {
    WorldOcclusion singleBlock = (x, y, z) -> x == 2 && y == 0 && z == 0;
    assertFalse(lineOfSight(world(singleBlock), 0.5, 0.5, 0.5, 5.5, 0.5, 0.5, 0, 0, FAR));
    assertTrue(lineOfSight(world(singleBlock), 0.5, 0.5, 0.5, 5.5, 0.5, 0.5, 1, 0, FAR));
  }

  @Test
  void alwaysVisibleRadiusSkipsSteppingEntirely() {
    // 实心世界：不豁免时必然不通畅
    assertFalse(lineOfSight(world((x, y, z) -> true), 0.5, 0.5, 0.5, 2.5, 0.5, 0.5, 0, 0, FAR));
    // 距离平方 = 4 <= 9：直接判通畅
    assertTrue(lineOfSight(world((x, y, z) -> true), 0.5, 0.5, 0.5, 2.5, 0.5, 0.5, 0, 9, FAR));
  }

  @Test
  void maxRadiusBeyondWhichRayIsTruncated() {
    // 距离平方 = 100
    assertFalse(lineOfSight(world((x, y, z) -> false), 0.5, 0.5, 0.5, 10.5, 0.5, 0.5, 0, 0, 25));
    assertTrue(lineOfSight(world((x, y, z) -> false), 0.5, 0.5, 0.5, 10.5, 0.5, 0.5, 0, 0, 400));
  }

  @Test
  void negativeCoordinatesAreHandledByFloorAndArithmeticShift() {
    WorldOcclusion wallAtNegativeX = (x, y, z) -> x == -3;
    assertFalse(lineOfSight(world(wallAtNegativeX), -5.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0, 0, FAR));
    // 同一堵墙的射线从另一侧、不同高度也要被挡
    assertFalse(lineOfSight(world(wallAtNegativeX), -6.5, -8.5, 3.5, 1.5, -8.5, 3.5, 0, 0, FAR));
  }

  @Test
  void sectionBoundaryCrossingQueriesTheNeighbourChunk() {
    // 墙落在 chunkX = 1（方块 x = 17），射线需从 chunk 0 跨越到 chunk 1 才能查到
    WorldOcclusion farWall = (x, y, z) -> x == 17;
    assertFalse(lineOfSight(world(farWall), 0.5, 0.5, 0.5, 20.5, 0.5, 0.5, 0, 0, FAR));

    // 该墙若落在 section 边界之外的高度（y=17）也应被查到
    WorldOcclusion highWall = (x, y, z) -> x == 5 && y == 17;
    assertFalse(lineOfSight(world(highWall), 0.5, 17.5, 0.5, 10.5, 17.5, 0.5, 0, 0, FAR));
  }

  @Test
  void diagonalRayIsBlockedByPerpendicularWall() {
    WorldOcclusion wall = (x, y, z) -> x == 2;
    assertFalse(lineOfSight(world(wall), 0.5, 0.5, 0.5, 5.5, 5.5, 5.5, 0, 0, FAR));
  }

  @Test
  void occludingBlockExactlyAtFourAndFiveStepsIsStillDetected() {
    // 4 步（展开循环的整批边界）
    WorldOcclusion blockAt4 = (x, y, z) -> x == 4 && y == 0 && z == 0;
    assertFalse(lineOfSight(world(blockAt4), 0.5, 0.5, 0.5, 5.5, 0.5, 0.5, 0, 0, FAR));
    // 5 步（展开余数段）
    WorldOcclusion blockAt5 = (x, y, z) -> x == 5 && y == 0 && z == 0;
    assertFalse(lineOfSight(world(blockAt5), 0.5, 0.5, 0.5, 6.5, 0.5, 0.5, 0, 0, FAR));
  }

  /** 用「世界坐标 → 是否遮挡」的谓词构造一个按需分段的只读查询源。 */
  private static OcclusionRay.Lookup world(WorldOcclusion worldOccluding) {
    return (chunkX, sectionY, chunkZ) -> new SectionOcclusion() {
      @Override
      public boolean isOccludingLocal(int x, int y, int z) {
        return worldOccluding.isOccluding((chunkX << 4) | x, (sectionY << 4) | y, (chunkZ << 4) | z);
      }
    };
  }

  private static boolean lineOfSight(OcclusionRay.Lookup lookup, double startX, double startY,
      double startZ, double endX, double endY, double endZ, int maxOccluding,
      double alwaysVisibleRadiusSquared, double maxRadiusSquared) {
    return OcclusionRay.lineOfSight(lookup, startX, startY, startZ, endX, endY, endZ, maxOccluding,
        alwaysVisibleRadiusSquared, maxRadiusSquared);
  }
}
