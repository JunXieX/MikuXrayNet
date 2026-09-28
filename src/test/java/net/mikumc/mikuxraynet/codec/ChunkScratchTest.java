package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * 按线程复用 scratch（{@link ChunkScratch}）后的正确性测试。
 *
 * <p>池化数组最容易出的两类问题：① 复用前没有重新初始化（调色板反查表残留、位打包数组末段残留位）；
 * ② 跨线程共享导致状态串扰。这里分别用「同线程反复编解码字节必须完全一致」与「多线程并发结果必须与串行一致」覆盖，
 * 并且刻意选用位宽 5（每 long 装 12 项、4096 项装不满最后一个 long）来暴露末段残留位问题。
 */
class ChunkScratchTest {

  private static final ChunkVersionFlags MODERN = ChunkVersionFlags.PAPER_26_2;
  private static final int SECTION_COUNT = 2;
  /** 每个 section 4096 项；与 codec 的元素序号约定一致。 */
  private static final int SECTION_VOLUME = 4096;

  private static RegistryAccessor registry() {
    return new RegistryAccessor() {
      @Override
      public int getUniqueBlockStateCount() {
        return 1 << 15;
      }

      @Override
      public int getMaxBitsPerBlockState() {
        return 15;
      }

      @Override
      public boolean isAir(int blockId) {
        return blockId == 0;
      }

      @Override
      public boolean isFluid(int blockId) {
        return false;
      }
    };
  }

  /** 原始数据：section 0 为 5 位间接调色板（高频状态刻意放在高位索引），section 1 为单值调色板。 */
  private static byte[] rawChunk() {
    int[] palette = new int[20];
    palette[0] = 0;
    for (int i = 1; i < palette.length; i++) {
      palette[i] = i * 13 + 5;
    }

    int[] indices = new int[SECTION_VOLUME];
    for (int i = 0; i < indices.length; i++) {
      // 高频状态落在最后一个索引（重排应把它提到索引 0）
      indices[i] = i < 4000 ? palette.length - 1 : i % 7;
    }

    return new TestChunkBuilder(MODERN)
        .indirectSection(5, 4096 - 4000, 0, palette, indices, 0, new int[] {1})
        .singleValueSection(1, 4096, 0, 0, new int[] {7})
        .build();
  }

  /** 一次「解码 → 改写 → 重排 → 重编码」；返回字节结果与读回的方块序列。 */
  private record Outcome(byte[] encoded, int[] states, boolean sectionZeroReordered) {
  }

  private static Outcome runOnce(ChunkCodec codec, byte[] raw) {
    try (Chunk chunk = codec.decode(raw, SECTION_COUNT)) {
      // section 0：改一个方块（新增调色板项）
      chunk.getSection(0).setBlockState(0, 3, 2, 12345);
      // section 1：单值调色板改第二种方块 → 触发升位到间接调色板
      chunk.getSection(1).setBlockState(0, 0, 0, 23456);

      boolean reordered = chunk.getSection(0).reorderPaletteByFrequency(false);

      int[] states = new int[SECTION_COUNT * SECTION_VOLUME];
      for (int section = 0; section < SECTION_COUNT; section++) {
        int[] local = chunk.getSection(section).readAllBlockStates();
        System.arraycopy(local, 0, states, section * SECTION_VOLUME, SECTION_VOLUME);
      }
      return new Outcome(chunk.finalizeOutput(), states, reordered);
    }
  }

  @Test
  void repeatedEncodeOnSameThreadIsByteIdentical() {
    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    byte[] raw = rawChunk();

    Outcome first = runOnce(codec, raw);
    assertEquals(SECTION_COUNT * SECTION_VOLUME, first.states().length);
    assertTrue(first.sectionZeroReordered(), "高频状态不在低位索引时应当发生重排");
    assertEquals(12345, first.states()[3 << 8 | 2 << 4 | 0], "section 0 的改写必须生效");
    assertEquals(23456, first.states()[SECTION_VOLUME], "section 1 的改写必须生效");
    // 池化数组复用后必须得到完全相同的字节：任何残留位/残留反查表都会在这里暴露
    for (int round = 0; round < 8; round++) {
      Outcome next = runOnce(codec, raw);
      assertArrayEquals(first.encoded(), next.encoded(), "第 " + round + " 轮复用的重编码字节必须一致");
      assertArrayEquals(first.states(), next.states(), "第 " + round + " 轮复用的方块序列必须一致");
      assertTrue(next.sectionZeroReordered(), "第 " + round + " 轮仍应发生重排");
    }
  }

  @Test
  void concurrentEncodeMatchesSequentialResult() throws Exception {
    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    byte[] raw = rawChunk();
    byte[] expected = runOnce(codec, raw).encoded();

    int threads = 4;
    int tasksPerThread = 25;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Callable<byte[]>> tasks = new ArrayList<>();
      for (int i = 0; i < threads * tasksPerThread; i++) {
        tasks.add(() -> runOnce(codec, raw).encoded());
      }

      for (Future<byte[]> future : pool.invokeAll(tasks)) {
        assertArrayEquals(expected, future.get(), "并发线程的编解码结果必须与串行一致（scratch 按线程隔离）");
      }
    } finally {
      pool.shutdownNow();
    }
  }
}