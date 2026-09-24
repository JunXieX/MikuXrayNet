// 移植自 Orebfuscator（GPL-3.0），本项目为私有自用部署。
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * 单个 16×16×16 section 的方块数据：方块计数、bitsPerBlock、调色板与位打包索引数组。
 *
 * <p>关键约束（与上游实现一致，勿按「最优」改写）：
 * <ul>
 *   <li>bitsPerBlock=0 用单值调色板；读取时位宽 1 保留原值（兼容 FAWE 的非标准区块格式）；
 *       位宽 2..8 会被归一化为 {@code max(4, bitsPerBlock)}；位宽 &gt;8 一律按 direct 处理，
 *       且位宽取自 {@code registryAccessor.getMaxBitsPerBlockState()}；</li>
 *   <li>{@link #grow(int, int)} 升位时会把旧调色板索引整体重映射到新调色板，因此方块状态语义不变，
 *       但调色板索引顺序可能改变；</li>
 *   <li>元素序号为 {@code y << 8 | z << 4 | x}（共 4096 项）；</li>
 *   <li>{@link #write(ByteBuf)} 的字节布局：方块计数(short) → [流体计数(short)] → bitsPerBlock(byte)
 *       → 调色板 → [long 数组长度 VarInt] → long 数组。</li>
 * </ul>
 */
public class ChunkSection {

  private final RegistryAccessor registryAccessor;
  private final ChunkVersionFlags versionFlags;

  private int blockCount;
  private int fluidCount;
  private int bitsPerBlock = -1;

  private Palette palette;
  private VarBitBuffer data;

  public ChunkSection(ChunkCodec codec) {
    this.registryAccessor = codec.registryAccessor();
    this.versionFlags = codec.versionFlags();

    this.setBitsPerBlock(0, true);
  }

  public RegistryAccessor registryAccessor() {
    return registryAccessor;
  }

  private void setBitsPerBlock(int bitsPerBlock, boolean grow) {
    if (this.bitsPerBlock != bitsPerBlock) {
      if (versionFlags.hasSingleValuePalette() && bitsPerBlock == 0) {
        this.bitsPerBlock = 0;
        this.palette = new SingleValuePalette(this, 0);
      } else if (!grow && bitsPerBlock == 1) {
        // fix: fawe chunk format incompatibility with bitsPerBlock == 1
        // https://github.com/Imprex-Development/Orebfuscator/issues/36
        this.bitsPerBlock = bitsPerBlock;
        this.palette = new IndirectPalette(this.bitsPerBlock, this);
      } else if (bitsPerBlock <= 8) {
        this.bitsPerBlock = Math.max(4, bitsPerBlock);
        this.palette = new IndirectPalette(this.bitsPerBlock, this);
      } else {
        this.bitsPerBlock = registryAccessor.getMaxBitsPerBlockState();
        this.palette = new DirectPalette();
      }

      if (this.bitsPerBlock == 0) {
        this.data = new ZeroVarBitBuffer(4096);
      } else {
        this.data = new SimpleVarBitBuffer(this.bitsPerBlock, 4096);
      }
    }
  }

  int grow(int bitsPerBlock, int blockId) {
    Palette palette = this.palette;
    VarBitBuffer data = this.data;

    this.setBitsPerBlock(bitsPerBlock, true);

    for (int i = 0; i < data.size(); i++) {
      int preBlockId = palette.valueFor(data.get(i));
      this.data.set(i, this.palette.idFor(preBlockId));
    }

    return this.palette.idFor(blockId);
  }

  static int positionToIndex(int x, int y, int z) {
    return y << 8 | z << 4 | x;
  }

  public void setBlockState(int x, int y, int z, int blockId) {
    this.setBlockState(positionToIndex(x, y, z), blockId);
  }

  public void setBlockState(int index, int blockId) {
    int prevBlockId = this.getBlockState(index);

    if (!registryAccessor.isAir(prevBlockId)) {
      --this.blockCount;

      if (registryAccessor.isFluid(prevBlockId)) {
        --this.fluidCount;
      }
    }

    if (!registryAccessor.isAir(blockId)) {
      ++this.blockCount;

      if (registryAccessor.isFluid(blockId)) {
        ++this.fluidCount;
      }
    }

    int paletteIndex = this.palette.idFor(blockId);
    this.data.set(index, paletteIndex);
  }

  public int getBlock(int x, int y, int z) {
    return this.getBlockState(positionToIndex(x, y, z));
  }

  public int getBlockState(int index) {
    return this.palette.valueFor(this.data.get(index));
  }

  public boolean isEmpty() {
    return this.blockCount == 0;
  }

  public void write(ByteBuf buffer) {
    buffer.writeShort(this.blockCount);

    if (this.versionFlags.hasFluidCount()) {
      buffer.writeShort(this.fluidCount);
    }

    buffer.writeByte(this.bitsPerBlock);
    this.palette.write(buffer);

    long[] data = this.data.toArray();

    if (versionFlags.hasLongArrayLengthField()) {
      ByteBufUtil.writeVarInt(buffer, data.length);
    }

    for (long entry : data) {
      buffer.writeLong(entry);
    }
  }

  public int[] read(ByteBuf buffer) {
    this.blockCount = buffer.readShort();

    if (this.versionFlags.hasFluidCount()) {
      this.fluidCount = buffer.readShort();
    }

    this.setBitsPerBlock(buffer.readUnsignedByte(), false);

    this.palette.read(buffer);

    long[] data = this.data.toArray();

    if (versionFlags.hasLongArrayLengthField()) {
      int length = ByteBufUtil.readVarInt(buffer);
      if (data.length != length) {
        throw new IndexOutOfBoundsException("data.length != VarBitBuffer::size " + length + " " + this.data);
      }
    }

    for (int i = 0; i < data.length; i++) {
      data[i] = buffer.readLong();
    }

    int[] directData = new int[4096];
    for (int i = 0; i < directData.length; i++) {
      directData[i] = this.getBlockState(i);
    }
    return directData;
  }
}