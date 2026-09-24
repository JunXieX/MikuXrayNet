package net.mikumc.mikuxraynet.cache;

/**
 * 纯 Java 实现的 XXHash32（遵循 xxhash 官方规范）。
 *
 * <p>用于 {@link BufferedLinearV3Format} 的条目校验和（种子与格式头一致，默认 {@code 0x0721}），
 * 与参考实现（zstd/lz4-java/Rust xxhash）结果一致，因此理论上可与参考实现的文件互通
 * （本项目压缩算法不同，实际不互通，见 {@link BufferedLinearV3Format} 的说明）。
 */
public final class XXHash32 {

  private static final int PRIME32_1 = 0x9E3779B1;
  private static final int PRIME32_2 = 0x85EBCA77;
  private static final int PRIME32_3 = 0xC2B2AE3D;
  private static final int PRIME32_4 = 0x27D4EB2F;
  private static final int PRIME32_5 = 0x165667B1;

  private XXHash32() {
  }

  /** 计算整段数据的哈希。 */
  public static int hash(byte[] input, int seed) {
    return hash(input, 0, input.length, seed);
  }

  /** 计算区间 {@code [off, off+len)} 的哈希。 */
  public static int hash(byte[] input, int off, int len, int seed) {
    final int end = off + len;
    int h32;
    int p = off;

    if (len >= 16) {
      final int limit = end - 16;
      int v1 = seed + PRIME32_1 + PRIME32_2;
      int v2 = seed + PRIME32_2;
      int v3 = seed;
      int v4 = seed - PRIME32_1;
      do {
        v1 = round(v1, readIntLE(input, p));
        p += 4;
        v2 = round(v2, readIntLE(input, p));
        p += 4;
        v3 = round(v3, readIntLE(input, p));
        p += 4;
        v4 = round(v4, readIntLE(input, p));
        p += 4;
      } while (p <= limit);

      h32 = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7)
          + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18);
    } else {
      h32 = seed + PRIME32_5;
    }

    h32 += len;

    while (p + 4 <= end) {
      h32 += readIntLE(input, p) * PRIME32_3;
      h32 = Integer.rotateLeft(h32, 17) * PRIME32_4;
      p += 4;
    }

    while (p < end) {
      h32 += (input[p] & 0xFF) * PRIME32_5;
      h32 = Integer.rotateLeft(h32, 11) * PRIME32_1;
      p++;
    }

    h32 ^= h32 >>> 15;
    h32 *= PRIME32_2;
    h32 ^= h32 >>> 13;
    h32 *= PRIME32_3;
    h32 ^= h32 >>> 16;
    return h32;
  }

  private static int round(int acc, int input) {
    int result = acc + input * PRIME32_2;
    result = Integer.rotateLeft(result, 13);
    return result * PRIME32_1;
  }

  private static int readIntLE(byte[] bytes, int index) {
    return (bytes[index] & 0xFF)
        | ((bytes[index + 1] & 0xFF) << 8)
        | ((bytes[index + 2] & 0xFF) << 16)
        | ((bytes[index + 3] & 0xFF) << 24);
  }
}