package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * codec 边界回归：位打包数组长度的整数计算、{@code bitsPerEntry=64} 的满掩码，以及
 * 间接调色板对「重复方块状态值」的损坏检测。
 */
class CodecEdgeCaseTest {

  private static final int AIR = 0;
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

  /**
   * 长度必须是精确的整数上取整。旧实现用 {@code (float)} 除法，size 很大时（超过 float 的 24 位有效位）
   * 会丢精度、{@code ceil} 得到偏小的长度——该长度参与读取时的长度校验，会造成功能性失败。
   */
  @Test
  void calculateArraySizeIsExactForLargeSizes() {
    int[] bitsPerEntryValues = {1, 3, 5, 8, 15, 64};
    int[] sizes = {0, 1, 63, 64, 65, 4096, 21_000_001, 100_000_000};
    for (int bitsPerEntry : bitsPerEntryValues) {
      int entriesPerLong = 64 / bitsPerEntry;
      for (int size : sizes) {
        long expected = (size + entriesPerLong - 1L) / entriesPerLong;
        assertEquals((int) expected, SimpleVarBitBuffer.calculateArraySize(bitsPerEntry, size),
            "bitsPerEntry=" + bitsPerEntry + " size=" + size + " 的数组长度必须精确（整数上取整）");
      }
    }
    assertEquals(0, SimpleVarBitBuffer.calculateArraySize(0, 4096), "bitsPerEntry=0 的数组长度为 0");
  }

  /** {@code bitsPerEntry=64} 时掩码必须是「低 64 位全 1」；旧写法 {@code (1L << 64) - 1} 会算出 0。 */
  @Test
  void fullWidthMaskHandlesBitsPerEntry64() {
    int size = 4;
    long[] buffer = new long[SimpleVarBitBuffer.calculateArraySize(64, size)];
    assertEquals(size, buffer.length, "entriesPerLong=1 时数组长度应等于 size");

    SimpleVarBitBuffer bits = new SimpleVarBitBuffer(64, size, buffer);
    bits.set(1, 0xDEADBEEF);
    assertEquals(0xDEADBEEF, bits.get(1),
        "64 位时掩码为满掩码，写入的值必须原样读回（掩码为 0 会把值全部抹零）");
    bits.set(3, -1);
    assertEquals(-1, bits.get(3), "负值也必须原样往返");
    assertEquals(0, bits.get(0), "未写入的项必须保持 0");
  }

  /**
   * 位宽入口边界：合法值 {0（单值）, 1, 64} 正常计算；非法值 {65, 255, -1} 必须抛出<b>明确</b>异常，
   * 而不是让 {@code 64 / bitsPerEntry == 0} 演变成 {@code ... / 0} 的 {@code ArithmeticException}。
   *
   * <p>该路径可达：位宽直接来自封包字节（如 {@code Chunk.skipBiomePalettedContainer} 读到的损坏/被伪装
   * bits 字节），因此这是「碰巧被上层 catch 兜住」的静默缺陷，必须在入口显式拒绝。
   */
  @Test
  void calculateArraySizeRejectsIllegalBitWidths() {
    assertEquals(0, SimpleVarBitBuffer.calculateArraySize(0, 4096), "位宽 0 表示无位打包数据，长度为 0");
    assertEquals(64, SimpleVarBitBuffer.calculateArraySize(1, 4096), "位宽 1 每 long 装 64 项 → 64 个 long");
    assertEquals(4096, SimpleVarBitBuffer.calculateArraySize(64, 4096), "位宽 64 每 long 装 1 项 → 4096 个 long");

    for (int illegal : new int[] {65, 127, 255, -1}) {
      assertThrows(IllegalArgumentException.class,
          () -> SimpleVarBitBuffer.calculateArraySize(illegal, 4096),
          "位宽 " + illegal + " 必须抛出明确异常（旧实现的 64/0 除零会抛 ArithmeticException）");
    }
  }

  /**
   * 位宽 2/3 视为非法编码：解码必须显式拒绝（交由上层 fail-open），而不是静默归一化成 4 位。
   *
   * <p>旧实现在 {@code bitsPerBlock <= 8} 分支里做 {@code max(4, bits)}，而同一位宽决定位打包的
   * 每 long 项数与数组长度——归一化等于用错误位宽重新解释同一段字节（读出错值、多读若干 long）。
   */
  @Test
  void indirectSectionWithBitWidthTwoOrThreeIsRejected() {
    for (int bits : new int[] {2, 3}) {
      int[] palette = {AIR, 10};
      int[] paletteIndices = new int[4096];
      for (int i = 0; i < paletteIndices.length; i++) {
        paletteIndices[i] = i & 1;
      }
      byte[] raw = new TestChunkBuilder(MODERN)
          .indirectSection(bits, 2048, 0, palette, paletteIndices, 0, new int[] {1})
          .build();

      ChunkCodec codec = new ChunkCodec(registry(), MODERN);
      assertThrows(IllegalArgumentException.class, () -> codec.decode(raw, 1),
          "bitsPerBlock=" + bits + " 是非法编码，必须显式拒绝而不是静默归一化");
    }
  }

  /**
   * 位宽 1 的 FAWE 兼容档必须保留：读取时原值不动，且「未改动即原样搬运」往返逐字节一致。
   * （与上一条 2/3 的拒绝形成对照——真正会发生的第三方场景是 1，故单独保留。）
   */
  @Test
  void bitWidthOneCompatibilityPathStillWorks() {
    int[] palette = {AIR, 10};
    int[] paletteIndices = new int[4096];
    for (int i = 0; i < paletteIndices.length; i++) {
      paletteIndices[i] = i & 1;
    }
    byte[] raw = new TestChunkBuilder(MODERN)
        .indirectSection(1, 2048, 0, palette, paletteIndices, 1, new int[] {1, 2})
        .build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(1, section.bitsPerBlock(), "位宽 1 必须被原值保留（FAWE 兼容档）");
      assertEquals(palette[paletteIndices[0]], section.getBlockState(0));
      assertEquals(palette[paletteIndices[4095]], section.getBlockState(4095));
      assertArrayEquals(raw, chunk.finalizeOutput(), "未改动的位宽 1 section 必须逐字节原样搬运");
    }
  }

  /**
   * 直接（direct/global）格式的声明位宽若不等于注册表位宽，必须显式拒绝（交由上层 fail-open），
   * 不得按注册表位宽重新解释。
   *
   * <p>位打包是按<b>声明的位宽</b>写出的：客户端按声明值解包（{@code Configuration.Global(bitsInMemory,
   * bitsInStorage)}——存储位宽取声明值，读完再重排到内存位宽）。声明 9..14 或 &gt;15 时若按注册表位宽 15
   * 重解释，同一段字节会被读出错值、多读/少读若干 long，把后续（群系容器 / 下一个 section）字节吞进
   * 方块数据——1.21.5+ 没有 long 数组长度字段可校验，错位无内建校验能兜住，最坏整块按垃圾字节重编码。
   */
  @Test
  void directSectionWithWrongBitWidthIsRejected() {
    int[] blockStates = new int[4096];
    for (int bits : new int[] {9, 12, 14, 16, 64}) {
      byte[] raw = new TestChunkBuilder(MODERN)
          .directSection(bits, 0, 0, blockStates, 0, new int[] {1})
          .build();

      ChunkCodec codec = new ChunkCodec(registry(), MODERN);
      assertThrows(IllegalArgumentException.class, () -> codec.decode(raw, 1),
          "直接格式声明位宽 " + bits + " ≠ 注册表位宽 15，必须显式拒绝而不是按 15 位重解释");
    }
  }

  /** 调色板里出现重复的方块状态值即为损坏：必须在读取时拒绝（fail-open 由上层解码兜底）。 */
  @Test
  void duplicatePaletteValueIsRejected() {
    int[] palette = {AIR, 10, 10};
    int[] paletteIndices = new int[4096];
    byte[] raw = new TestChunkBuilder(MODERN)
        .indirectSection(4, 4096, 0, palette, paletteIndices, 0, new int[] {1})
        .build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    assertThrows(IndexOutOfBoundsException.class, () -> codec.decode(raw, 1),
        "调色板含重复方块状态值必须视为损坏并拒绝，而不是静默接受两个索引指向同一状态");
  }

  /**
   * 8 位间接调色板满 256 项（索引 255）必须按合法条目处理。
   *
   * <p>旧实现用 {@code 0xFF} 当「未登记」哨兵，而 0xFF 正是索引 255 的编码，于是调色板第 256 项永远被
   * 判为未登记：读取原版产出的 256 项调色板后，对第 256 项 {@code idFor}/{@code contains} 会误走
   * {@code grow(9)}，白白把整个 section 从 8 位间接重编码成 15 位直接（语义不变但开销巨大）。
   */
  @Test
  void eightBitPaletteWith256EntriesKeepsIndex255Usable() {
    int[] palette = new int[256];
    for (int i = 0; i < palette.length; i++) {
      palette[i] = i; // 0 号是 AIR
    }
    int[] paletteIndices = new int[4096];
    for (int i = 0; i < paletteIndices.length; i++) {
      paletteIndices[i] = i % palette.length;
    }
    int blockCount = 0;
    for (int paletteIndex : paletteIndices) {
      if (palette[paletteIndex] != AIR) {
        blockCount++;
      }
    }
    byte[] raw = new TestChunkBuilder(MODERN)
        .indirectSection(8, blockCount, 0, palette, paletteIndices, 0, new int[] {1})
        .build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    try (Chunk chunk = codec.decode(raw, 1)) {
      ChunkSection section = chunk.getSection(0);
      assertEquals(8, section.bitsPerBlock(), "8 位间接调色板");
      assertEquals(256, section.paletteSize(), "满 256 项的调色板必须被接受（原版可能产出的形态）");

      // 索引 255 本就登记在案：写入它不得触发升位、更不得整节重编码
      section.setBlockState(0, 255);
      assertEquals(255, section.getBlockState(0), "索引 255 的方块状态必须可正常读写");
      assertEquals(8, section.bitsPerBlock(), "命中已登记的索引 255 不得触发 grow（无谓的全节重编码）");
      assertEquals(256, section.paletteSize(), "不得新增调色板条目");

      byte[] output = chunk.finalizeOutput();
      try (Chunk again = codec.decode(output, 1)) {
        assertEquals(255, again.getSection(0).getBlockState(0), "重编码结果必须可稳定往返");
        assertArrayEquals(output, again.finalizeOutput(), "往返字节一致");
      }
    }
  }

  /** 负的 blockCount：有符号 short 读出的损坏值，必须显式拒绝（交由上层解码 fail-open）。 */
  @Test
  void negativeBlockCountIsRejected() {
    byte[] raw = new TestChunkBuilder(MODERN)
        .singleValueSection(AIR, -1, 0, 0, new int[] {7})
        .build();

    ChunkCodec codec = new ChunkCodec(registry(), MODERN);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(raw, 1),
        "blockCount 为负必须显式拒绝，而不是静默当成非空 section 继续解析");
  }

  /**
   * 超过 5 字节的 VarInt 必须抛异常，且不得多消费第 6 个字节（读前判界；旧实现先读后判）。
   */
  @Test
  void overlongVarIntIsRejectedWithoutConsumingSixthByte() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      for (int i = 0; i < 5; i++) {
        buffer.writeByte(0x80); // 5 个字节都带续位
      }
      buffer.writeByte(0x01); // 第 6 个字节存在，但绝不能被读走
      int start = buffer.readerIndex();

      assertThrows(IndexOutOfBoundsException.class, () -> ByteBufUtil.readVarInt(buffer),
          "超过 5 字节的 VarInt 必须抛 IndexOutOfBoundsException");
      assertEquals(start + 5, buffer.readerIndex(),
          "必须在读取第 6 个字节之前判界（只消费 5 字节）");
    } finally {
      buffer.release();
    }
  }
}