package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 缓存指纹组合：与历史公式逐值等价（顺序不能变），且邻块开关只影响是否混入邻块哈希。
 */
class RewriteFingerprintTest {

  /** 公式的独立复刻：用于证明组合逻辑没有改变任何一条既有缓存键。 */
  private static int legacy(int configHash, int paletteFingerprint, long neighborHash,
      boolean hasNeighbors) {
    int base = configHash * 31 + paletteFingerprint;
    if (!hasNeighbors) {
      return base;
    }
    return base * 31 + (int) (neighborHash ^ (neighborHash >>> 32));
  }

  @Test
  void matchesLegacyFormulaForRepresentativeInputs() {
    int[] configs = {0, 1, -1, 31, 123456789, Integer.MIN_VALUE, Integer.MAX_VALUE};
    int[] palettes = {0, 1, 2, 3};
    long[] neighbors = {0L, 1L, -1L, 0x9E3779B97F4A7C15L, Long.MIN_VALUE, Long.MAX_VALUE};
    for (int config : configs) {
      for (int palette : palettes) {
        for (long neighbor : neighbors) {
          for (boolean has : new boolean[] {false, true}) {
            assertEquals(legacy(config, palette, neighbor, has),
                RewriteFingerprint.of(config, palette, neighbor, has),
                "config=" + config + " palette=" + palette + " neighbor=" + neighbor
                    + " has=" + has);
          }
        }
      }
    }
  }

  @Test
  void neighborHashIgnoredWhenNotConsulted() {
    int a = RewriteFingerprint.of(7, 2, 0L, false);
    int b = RewriteFingerprint.of(7, 2, 123456L, false);
    assertEquals(a, b);
    assertEquals(RewriteFingerprint.of(7, 2), a);
  }

  @Test
  void everyInputChangesTheFingerprint() {
    int base = RewriteFingerprint.of(1, 0);
    assertNotEqualsFingerprint(base, RewriteFingerprint.of(2, 0));
    assertNotEqualsFingerprint(base, RewriteFingerprint.of(1, 1));
    assertNotEqualsFingerprint(base, RewriteFingerprint.of(1, 0, 42L, true));
  }

  private static void assertNotEqualsFingerprint(int expected, int actual) {
    if (expected == actual) {
      throw new AssertionError("不同输入必须得到不同指纹");
    }
  }
}
