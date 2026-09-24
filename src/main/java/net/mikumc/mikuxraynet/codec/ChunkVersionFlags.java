
package net.mikumc.mikuxraynet.codec;

/**
 * 区块 section 二进制格式的版本差异标志。
 *
 * <p>各标志的 Minecraft 版本边界与上游一致：
 * <ul>
 *   <li>{@code hasFluidCount}：26.1.0 起，section 头部在方块计数之后多一个 short 型流体计数；</li>
 *   <li>{@code hasLongArrayLengthField}：1.21.5 之前，位打包 long 数组前带 VarInt 长度字段（方块与群系容器都有）；</li>
 *   <li>{@code hasBiomePalettedContainer}：1.18 起，每个 section 尾部内联生物群系调色板容器；</li>
 *   <li>{@code hasSingleValuePalette}：1.18 起支持 bitsPerBlock=0 的单值调色板。</li>
 * </ul>
 *
 * <p>本项目运行目标 Paper 26.2 对应 {@link #PAPER_26_2}；也可用服务器上报的 Minecraft 版本字符串
 * （如 {@code "26.2"}、{@code "1.21.4"}）构造。
 */
public final class ChunkVersionFlags {

  /** 本项目运行目标 Paper 26.2（Minecraft 26.2）对应的标志组合。 */
  public static final ChunkVersionFlags PAPER_26_2 = new ChunkVersionFlags("26.2");

  private final boolean hasFluidCount;
  private final boolean hasLongArrayLengthField;
  private final boolean hasBiomePalettedContainer;
  private final boolean hasSingleValuePalette;

  public ChunkVersionFlags(String minecraftVersion) {
    this(isAtOrAbove(minecraftVersion, "26.1.0"),
        isBelow(minecraftVersion, "1.21.5"),
        isAtOrAbove(minecraftVersion, "1.18"),
        isAtOrAbove(minecraftVersion, "1.18"));
  }

  public ChunkVersionFlags(boolean hasFluidCount, boolean hasLongArrayLengthField,
      boolean hasBiomePalettedContainer, boolean hasSingleValuePalette) {
    this.hasFluidCount = hasFluidCount;
    this.hasLongArrayLengthField = hasLongArrayLengthField;
    this.hasBiomePalettedContainer = hasBiomePalettedContainer;
    this.hasSingleValuePalette = hasSingleValuePalette;
  }

  public boolean hasFluidCount() {
    return hasFluidCount;
  }

  public boolean hasLongArrayLengthField() {
    return hasLongArrayLengthField;
  }

  public boolean hasBiomePalettedContainer() {
    return hasBiomePalettedContainer;
  }

  public boolean hasSingleValuePalette() {
    return hasSingleValuePalette;
  }

  private static boolean isAtOrAbove(String version, String other) {
    return compare(version, other) >= 0;
  }

  private static boolean isBelow(String version, String other) {
    return compare(version, other) < 0;
  }

  /** 逐段比较版本号，缺失段视为 0，段内只取开头的连续数字（兼容 {@code "1.21.5-R0.1"} 之类的后缀）。 */
  private static int compare(String version, String other) {
    int[] left = parse(version);
    int[] right = parse(other);

    for (int i = 0; i < Math.max(left.length, right.length); i++) {
      int a = i < left.length ? left[i] : 0;
      int b = i < right.length ? right[i] : 0;
      if (a != b) {
        return Integer.compare(a, b);
      }
    }
    return 0;
  }

  private static int[] parse(String version) {
    String[] parts = version.split("\\.");
    int[] numbers = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      numbers[i] = leadingNumber(parts[i]);
    }
    return numbers;
  }

  private static int leadingNumber(String part) {
    int end = 0;
    while (end < part.length() && Character.isDigit(part.charAt(end))) {
      end++;
    }
    return end == 0 ? 0 : Integer.parseInt(part.substring(0, end));
  }
}