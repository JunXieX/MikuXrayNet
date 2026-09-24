package net.mikumc.mikuxraynet.bench;

import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkSection;

/**
 * 自研字节级 codec 路径：{@link ChunkCodec#decode(byte[], int)} → {@link ChunkSection#setBlockState(int, int, int, int)}
 * → {@link Chunk#finalizeOutput()}。
 *
 * <p>选择性 section 重编码由 codec 自身完成（未改动的 section 依据 {@link Chunk#originalSectionRange(int)} 原样搬运原始字节），
 * 这里不需要额外开关：只改 N 个方块时，未被触及的 section 不会重新做调色板与位打包。
 *
 * <p>调色板频次重排按生产方式接入：只对「本次确实被改写的 section」调用
 * {@link ChunkSection#reorderPaletteByFrequency(boolean)}（{@code verify=false}，与
 * {@code bandwidth.yml: palette.strict-verify} 的默认值一致），未改写的 section 保持原字节。
 *
 * <p>每轮都从传入的原始字节新建 {@link Chunk}，因此不存在复用被改过数据的情况。
 */
public final class OurCodecPath implements ChunkPath {

  private final ChunkCodec codec;
  private final boolean reorder;

  /** 最近一次 {@link #encode} 中真正发生（即调色板顺序被改动的）重排的 section 数。 */
  private int reorderedSections;

  public OurCodecPath(boolean reorder) {
    this.codec = new ChunkCodec(BenchFixtures.registry(), BenchFixtures.FLAGS);
    this.reorder = reorder;
  }

  @Override
  public String name() {
    return this.reorder ? "自研 codec（重排开）" : "自研 codec（重排关）";
  }

  /** 最近一次 encode 里真正发生了重排的 section 数；重排关闭或调色板已按频次有序时均为 0。 */
  public int reorderedSections() {
    return this.reorderedSections;
  }

  @Override
  public byte[] encode(byte[] input, BenchFixtures.Edit[] edits) {
    try (Chunk chunk = this.codec.decode(input, BenchFixtures.SECTION_COUNT)) {
      long touched = 0L;
      for (BenchFixtures.Edit edit : edits) {
        chunk.getSection(edit.section()).setBlockState(edit.x(), edit.y(), edit.z(), edit.state());
        touched |= 1L << edit.section();
      }

      int reordered = 0;
      if (this.reorder) {
        // 与 ObfuscationProcessor 一致：升序处理每个被改写的 section，只对它们做重排
        for (int section = 0; section < BenchFixtures.SECTION_COUNT; section++) {
          if ((touched & 1L << section) != 0
              && chunk.getSection(section).reorderPaletteByFrequency(false)) {
            reordered++;
          }
        }
      }
      this.reorderedSections = reordered;

      return chunk.finalizeOutput();
    }
  }

  @Override
  public int[] readStates(byte[] encoded) {
    int[] states = new int[BenchFixtures.COLUMN_VOLUME];

    try (Chunk chunk = this.codec.decode(encoded, BenchFixtures.SECTION_COUNT)) {
      for (int section = 0; section < BenchFixtures.SECTION_COUNT; section++) {
        int[] localStates = chunk.getSection(section).readAllBlockStates();
        System.arraycopy(localStates, 0, states, section * BenchFixtures.SECTION_VOLUME,
            BenchFixtures.SECTION_VOLUME);
      }
    }

    return states;
  }
}