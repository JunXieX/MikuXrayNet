package net.mikumc.mikuxraynet.antixray;

/** 坐标在 MULTI_BLOCK_CHANGE 的 12 位字段内按 X/Z/Y 排列，与区块调色板的 Y/Z/X 下标不同。 */
public final class SectionCoords {

  public static final int LOCAL_MASK = 15;

  private SectionCoords() {
  }

  public static int pack(int x, int y, int z) {
    return ((x & LOCAL_MASK) << 8) | ((z & LOCAL_MASK) << 4) | (y & LOCAL_MASK);
  }

  public static int unsigned(short packed) {
    return packed & 0xFFFF;
  }

  public static int x(int packed) {
    return (packed >>> 8) & LOCAL_MASK;
  }

  public static int y(int packed) {
    return packed & LOCAL_MASK;
  }

  public static int z(int packed) {
    return (packed >>> 4) & LOCAL_MASK;
  }

  static void unpack(int baseX, int baseY, int baseZ, short[] positions, int[] coordinates) {
    for (int i = 0; i < positions.length; i++) {
      int raw = unsigned(positions[i]);
      coordinates[i * 3] = baseX + x(raw);
      coordinates[i * 3 + 1] = baseY + y(raw);
      coordinates[i * 3 + 2] = baseZ + z(raw);
    }
  }
}
