// 移植自 Orebfuscator（GPL-3.0），本项目为私有自用部署。
package net.mikumc.mikuxraynet.codec;

/**
 * 区块 section 编解码入口（对应上游的 {@code ChunkFactory}）。
 *
 * <p>职责：持有一份 {@link RegistryAccessor}（方块状态语义）与 {@link ChunkVersionFlags}（二进制格式版本差异），
 * 并把出站封包中的原始区块字节解析成可改写的 {@link Chunk}。
 *
 * <p>关键约束：解码不复制入参数组（{@link io.netty.buffer.Unpooled#wrappedBuffer(byte[])} 直接包装），
 * 因此调用方在 {@link Chunk#finalizeOutput()} 之前不要改动该数组；{@link Chunk} 用毕必须关闭。
 */
public class ChunkCodec {

  private final RegistryAccessor registryAccessor;
  private final ChunkVersionFlags versionFlags;

  public ChunkCodec(RegistryAccessor registryAccessor, ChunkVersionFlags versionFlags) {
    this.registryAccessor = registryAccessor;
    this.versionFlags = versionFlags;
  }

  RegistryAccessor registryAccessor() {
    return registryAccessor;
  }

  ChunkVersionFlags versionFlags() {
    return versionFlags;
  }

  /**
   * 按「缓冲区中不含其字节」逐段解析区块数据。
   *
   * @param data            出站封包中的原始区块字节
   * @param sectionsPresent 各 section 是否在缓冲区中有字节；1.18+ 应全部为 true
   */
  public Chunk decode(byte[] data, boolean[] sectionsPresent) {
    return new Chunk(this, data, sectionsPresent);
  }

  /**
   * 解析包含 {@code sectionCount} 个连续 section 的区块数据（1.18+ 原版布局）。
   */
  public Chunk decode(byte[] data, int sectionCount) {
    boolean[] sectionsPresent = new boolean[sectionCount];
    for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
      sectionsPresent[sectionIndex] = true;
    }
    return this.decode(data, sectionsPresent);
  }
}