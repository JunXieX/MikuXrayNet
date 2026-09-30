package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  /**
   * 输出缓冲「恰好写满」时 {@link Chunk#finalizeOutput()} 会零拷贝移交底层数组（不再整块复制 50KB 级负载）。
   *
   * <p>本轮打磨的关键不变量：移交给调用方的数组必须<b>独立于 scratch</b>——否则同线程处理下一个区块时
   * 复用的输出数组会就地覆盖它（该字节会被磁盘 / 内存缓存 / NMS 字段长期持有）。这里在一个<b>全新线程</b>
   * （scratch 池为空，首个区块的输出数组容量恰为输入长度 → 空白往返必然恰好写满）上先取走一个区块的
   * 重编码结果，再在同一线程处理一个「长度相同、内容不同」的区块；若前者与复用的输出数组别名，其内容会被
   * 后者覆盖，断言随即失败。
   */
  @Test
  void exactFitHandoffIsIndependentOfScratch() throws Exception {
    byte[] raw = new TestChunkBuilder(MODERN)
        .singleValueSection(1, 4096, 0, 0, new int[] {7})
        .build();
    byte[] other = new TestChunkBuilder(MODERN)
        .singleValueSection(2, 4096, 0, 0, new int[] {7})
        .build();
    assertEquals(raw.length, other.length, "构造前提：两区块长度相同、内容不同");

    byte[][] results = new byte[2][];
    Thread worker = new Thread(() -> {
      ChunkCodec codec = new ChunkCodec(registry(), MODERN);
      try (Chunk chunk = codec.decode(raw, 1)) {
        results[0] = chunk.finalizeOutput();
      }
      try (Chunk chunk = codec.decode(other, 1)) {
        results[1] = chunk.finalizeOutput();
      }
    });
    worker.start();
    worker.join();

    assertEquals(raw.length, results[0].length, "移交的数组长度必须精确等于输出长度");
    assertArrayEquals(raw, results[0], "零拷贝移交的数组必须独立于 scratch：后续区块复用不得覆盖它");
    assertArrayEquals(other, results[1]);
  }

  /**
   * 间接调色板复用 scratch 的 {@code byValue} 数组时不得残留上一轮的登记：构造不再整表 memset 0xFF，
   * 命中与否改由反向表 {@code byId} 校验（见 {@link IndirectPalette} 类注释）。
   *
   * <p>这里先让一个调色板登记 100/200，归还 scratch 后再借到<b>同一个</b>缓冲并新建调色板；
   * 断言新调色板视 100/200 为未登记、并从 0 开始按新顺序重新分配——若残留（或含误导的哨兵），
   * {@code contains(100)} 会误判为真、{@code idFor(100)} 会返回上一轮的旧索引。
   */
  @Test
  void indirectPaletteIgnoresResidualLookupAfterBufferReuse() {
    ChunkCodec codec = new ChunkCodec(registry(), MODERN);

    ChunkScratch scratch = ChunkScratch.acquire();
    try {
      IndirectPalette first = new IndirectPalette(4, new ChunkSection(codec, scratch));
      assertEquals(0, first.idFor(100));
      assertEquals(1, first.idFor(200));
      assertTrue(first.contains(100), "前置：首次登记后必须命中");
    } finally {
      scratch.recycle();
    }

    // 游标复位后再借到同一个 scratch（进而借到同一个 byValue 数组），模拟跨区块复用
    ChunkScratch reused = ChunkScratch.acquire();
    try {
      IndirectPalette second = new IndirectPalette(4, new ChunkSection(codec, reused));
      assertFalse(second.contains(100), "上一轮的登记不得残留（否则新调色板会误判「已登记」）");
      assertFalse(second.contains(200));
      assertEquals(0, second.idFor(300), "新调色板从 0 开始分配，不受残留影响");
      assertEquals(1, second.idFor(100), "先前出现过的值按新顺序重新登记");
      assertEquals(1, second.idFor(100), "同一值重复查询必须稳定命中");
      assertEquals(2, second.idFor(200));
      assertTrue(second.contains(300));
    } finally {
      reused.recycle();
    }
  }
}