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
 * <p>每轮都从传入的原始字节新建 {@link Chunk}，因此不存在复用被改过数据的情况。
 */
public final class OurCodecPath implements ChunkPath {

  private final ChunkCodec codec;

  public OurCodecPath() {
    this.codec = new ChunkCodec(BenchFixtures.registry(), BenchFixtures.FLAGS);
  }

  @Override
  public String name() {
    return "自研 codec";
  }

  @Override
  public byte[] encode(byte[] input, BenchFixtures.Edit[] edits) {
    try (Chunk chunk = this.codec.decode(input, BenchFixtures.SECTION_COUNT)) {
      for (BenchFixtures.Edit edit : edits) {
        chunk.getSection(edit.section()).setBlockState(edit.x(), edit.y(), edit.z(), edit.state());
      }
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