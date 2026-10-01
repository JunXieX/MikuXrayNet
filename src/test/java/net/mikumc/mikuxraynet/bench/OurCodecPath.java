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
 * <p><b>调色板压缩/降级</b>：{@code compact=true} 时按生产方式接入「收缩 + 降级」——只对「本次确实被改写的
 * section」调用 {@link ChunkSection#compactPalette(boolean)} 与 {@link ChunkSection#downgradePalette(boolean)}
 * （{@code verify=false}，与 {@code bandwidth.yml: palette.strict-verify} 的默认值一致）；未改写的 section
 * 保持原字节。{@code compact=false} 即「改写（现状，不做收缩/降级）」对照口径。
 *
 * <p>每轮都从传入的原始字节新建 {@link Chunk}，因此不存在复用被改过数据的情况。
 */
public final class OurCodecPath implements ChunkPath {

  private final ChunkCodec codec;
  private final boolean compact;

  /** 最近一次 {@link #encode} 中真正发生收缩或降级的 section 数。 */
  private int compactedSections;

  public OurCodecPath(boolean compact) {
    this.codec = new ChunkCodec(BenchFixtures.registry(), BenchFixtures.FLAGS);
    this.compact = compact;
  }

  @Override
  public String name() {
    return this.compact ? "自研 codec（含降级）" : "自研 codec（无降级）";
  }

  /** 最近一次 encode 里真正发生收缩/降级的 section 数；未开启或本就无需降级时为 0。 */
  public int compactedSections() {
    return this.compactedSections;
  }

  @Override
  public byte[] encode(byte[] input, BenchFixtures.Edit[] edits) {
    try (Chunk chunk = this.codec.decode(input, BenchFixtures.SECTION_COUNT)) {
      long touched = 0L;
      for (BenchFixtures.Edit edit : edits) {
        chunk.getSection(edit.section()).setBlockState(edit.x(), edit.y(), edit.z(), edit.state());
        touched |= 1L << edit.section();
      }

      int compacted = 0;
      if (this.compact) {
        // 与 ObfuscationProcessor 一致：升序处理每个被改写的 section，只对它们做收缩 + 降级
        for (int section = 0; section < BenchFixtures.SECTION_COUNT; section++) {
          if ((touched & 1L << section) != 0) {
            ChunkSection target = chunk.getSection(section);
            boolean shrunk = target.compactPalette(false);
            boolean downgraded = target.downgradePalette(false);
            if (shrunk || downgraded) {
              compacted++;
            }
          }
        }
      }
      this.compactedSections = compacted;

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