package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 区块数据「必须被 section 恰好消费完」的校验（1.18+ 布局）。
 *
 * <p><b>为什么必须有这道闸</b>：1.18+ 每个 section 尾部内联自己的生物群系容器，区块数据数组由「恰好
 * 若干个 section」组成，末尾没有独立段。若解析完仍有剩余字节，说明布局与 codec 的假设不符，而此前每个
 * section 都已按错误边界解析过；这时把剩余字节当「尾部原样搬运」拼回去，会产出一份既非原字节、也非合法
 * 布局的区块字节，并且全程不抛异常（{@code chunksFailed} 恒为 0、日志无异常）——属于最不可观测的失效
 * 模式。故必须显式拒绝、交由上层 fail-open（放行原包、不改写）。
 *
 * <p><b>反向约束</b>：1.18 之前（群系段位于所有 section 之后）允许尾部字节，绝不能误伤。
 */
class ChunkTrailingBytesTest {

  private static final int AIR = 0;
  private static final int STONE = 1;
  private static final ChunkVersionFlags MODERN = ChunkVersionFlags.PAPER_26_2;

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

  /** 26.2 布局下多出的尾部字节只可能是布局漂移：必须显式拒绝，而不是静默拼回去。 */
  @Test
  void trailingBytesUnderBiomeLayoutAreRejected() {
    byte[] raw = new TestChunkBuilder(MODERN)
        .singleValueSection(STONE, 4096, 0, 0, new int[] {7})
        .trailing(new byte[] {0x11, 0x22, 0x33})
        .build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> codec.decode(raw, 1),
        "26.2 布局下 section 未能恰好消费完缓冲区时必须拒绝（否则会静默产出错位区块字节）");
    assertTrue(failure.getMessage().contains("未被 section 恰好消费完"),
        "异常文案必须点明原因（便于从 chunksFailed 的摘要里定位）：" + failure.getMessage());
  }

  /**
   * 少声明 section 数（末段字节剩余）同样按损坏拒绝：这正是「自动探测 section 数」类调用方必须
   * {@code catch (RuntimeException)} 的原因——探测到正确数目之前，偏小的候选会走到这里。
   */
  @Test
  void tooFewSectionsLeavesTrailingBytesAndIsRejected() {
    byte[] raw = new TestChunkBuilder(MODERN)
        .singleValueSection(STONE, 4096, 0, 0, new int[] {7})
        .singleValueSection(AIR, 0, 0, 0, new int[] {7})
        .build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(raw, 1),
        "只解析第 1 个 section 会把第 2 个的字节留成尾部 → 必须拒绝（偏小的 section 数不是合法布局）");
    // 正确 section 数下必须正常解析且逐字节往返（校验不得误伤合法负载）
    try (Chunk chunk = codec.decode(raw, 2)) {
      assertArrayEquals(raw, chunk.finalizeOutput(), "合法 section 数下的往返必须逐字节一致");
    }
  }

  /** 1.18 之前的布局（群系段在所有 section 之后）允许尾部字节：校验必须按标志位关掉，不得误伤。 */
  @Test
  void legacyLayoutStillCarriesTrailingBytes() {
    ChunkVersionFlags legacy = new ChunkVersionFlags(false, true, false, true);

    byte[] raw = new TestChunkBuilder(legacy)
        .singleValueSection(STONE, 4096, 0, 0, new int[0])
        .trailing(new byte[] {0x02, 0x05, 0x11, 0x22, 0x33})
        .build();

    ChunkCodec codec = new ChunkCodec(registry(), legacy);
    try (Chunk chunk = codec.decode(raw, 1)) {
      assertEquals(STONE, chunk.getSection(0).getBlockState(0));
      assertArrayEquals(raw, chunk.finalizeOutput(), "1.18 之前格式的尾部群系段必须原样追加");
    }
  }
}
