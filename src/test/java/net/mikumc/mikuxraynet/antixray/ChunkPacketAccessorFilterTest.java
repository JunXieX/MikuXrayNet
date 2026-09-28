package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ChunkPacketAccessor} 纯判定函数测试：remove-block-entities 开关的汇合点与
 * 方块实体剔除的坐标匹配规则（离线无法构造真实区块封包，故只测纯函数缝）。
 */
class ChunkPacketAccessorFilterTest {

  /** 开关是剔除段的真正消费点：false 时即使有被伪装坐标也绝不进入剔除段（A4 死配置键修复的语义）。 */
  @Test
  void switchOffSkipsFilteringEvenWithPositions() {
    int[] positions = {0 << 8 | 0 << 4 | 0};
    assertFalse(ChunkPacketAccessor.shouldFilterBlockEntities(false, positions),
        "remove-block-entities=false 时必须跳过剔除段");
    assertTrue(ChunkPacketAccessor.shouldFilterBlockEntities(true, positions),
        "remove-block-entities=true 且有被伪装坐标时才剔除");
  }

  @Test
  void switchOnWithoutPositionsStillSkipsFiltering() {
    assertFalse(ChunkPacketAccessor.shouldFilterBlockEntities(true, new int[0]),
        "没有被伪装坐标时无需剔除（与旧行为一致：localPositions 为空直接返回）");
  }

  /** 坐标编码 y << 8 | z << 4 | x：方块实体的绝对 Y 先减 minHeight 再比对。 */
  @Test
  void isObfuscatedMatchesPackedPositions() {
    int[] positions = {5 << 8 | 12 << 4 | 3};

    assertTrue(ChunkPacketAccessor.isObfuscated(5, 3, 12, positions), "同一坐标必须命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(5, 3, 13, positions), "z 不同不得命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(5, 4, 12, positions), "x 不同不得命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(6, 3, 12, positions), "y 不同不得命中");
  }

  /** 绝对 Y 换算后低于世界最低高度的方块实体不可能在清单里（越界防护）。 */
  @Test
  void negativeRelativeYIsNeverObfuscated() {
    int[] positions = {0 << 8 | 0 << 4 | 0};
    assertFalse(ChunkPacketAccessor.isObfuscated(-1, 0, 0, positions),
        "相对 Y 为负（世界最低高度以下）不得命中");
  }

  /** 与生产路径一致的换算：绝对 Y − minHeight = 相对 Y。 */
  @Test
  void minHeightConversionMatchesProduction() {
    int minHeight = -64;
    int absoluteY = -20;
    int relativeY = absoluteY - minHeight; // 44
    int[] positions = {44 << 8 | 7 << 4 | 9};

    assertTrue(ChunkPacketAccessor.isObfuscated(relativeY, 9, 7, positions));
    assertEquals(44, relativeY);
  }
}
