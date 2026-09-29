package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LRU 驱逐的记账不变式回归（缺陷：驱逐落盘失败时牺牲桶脱离 LRU 记账）。
 *
 * <p>旧实现先把牺牲桶从 {@code loaded} 移除，再尝试 {@code flushBucket}；一旦落盘抛
 * {@code IOException} 就 {@code continue}——此时桶已不在 {@code loaded}、{@code slots} 却仍非 null，
 * 之后 {@code ensureLoaded} 见 {@code slots != null} 直接返回、不会把它重新插回 {@code loaded}，
 * 于是该桶<b>永久脱离 LRU 记账</b>，内存里实际桶数可突破 {@code bucketCacheSize}。
 *
 * <p>修法：驱逐前先确认能安全移除——脏桶落盘失败就跳过它另选，绝不移出 {@code loaded}；
 * 保证不变式「桶要么在 {@code loaded} 记账内、要么 {@code slots} 已释放」。
 */
class RegionFileEvictionTest {

  /** bucket 下标 b 的槽位序号（bucketIndex = chunkIndex >>> BUCKET_SHIFT）。 */
  private static int chunkIndexForBucket(int bucket) {
    return bucket << 6;
  }

  private static BufferedLinearV3Format.Entry entry(int marker) {
    return new BufferedLinearV3Format.Entry(0L, System.currentTimeMillis(), marker,
        new byte[] {(byte) marker, 2, 3});
  }

  @SuppressWarnings("unchecked")
  private static Map<Integer, Boolean> loadedOf(RegionFile region) throws Exception {
    Field field = RegionFile.class.getDeclaredField("loaded");
    field.setAccessible(true);
    return (Map<Integer, Boolean>) field.get(region);
  }

  private static BufferedLinearV3Format.Entry[][] slotsOf(RegionFile region) throws Exception {
    Field field = RegionFile.class.getDeclaredField("slots");
    field.setAccessible(true);
    return (BufferedLinearV3Format.Entry[][]) field.get(region);
  }

  private static boolean[] dirtyOf(RegionFile region) throws Exception {
    Field field = RegionFile.class.getDeclaredField("dirty");
    field.setAccessible(true);
    return (boolean[]) field.get(region);
  }

  /**
   * 驱逐时脏桶落盘失败：它必须仍留在 {@code loaded} 记账内（且脏标记保留以便重试），
   * 同时改用其它可安全移除的桶把 {@code loaded.size()} 收敛回上限。
   */
  @Test
  void failedFlushKeepsDirtyBucketAccountedAndStaysWithinCacheSize(@TempDir Path dir)
      throws Exception {
    RegionFile region = RegionFile.open(dir.resolve("r.0.0.b_linear"), 2);
    try {
      // 桶 0：写脏（驱逐时尝试落盘并失败）；桶 1：只读加载（干净，可直接释放）
      region.put(chunkIndexForBucket(0), entry(0));
      region.get(chunkIndexForBucket(1));
      assertEquals(2, loadedOf(region).size(), "前置：装载了 2 个桶恰好到上限");

      // 复现真机故障态：底层通道失效 → 之后 flushBucket 必抛 IOException
      region.killChannelForTest();

      // 加载第 3 个桶触发驱逐：最久未用的桶 0 是脏桶且落盘失败
      region.put(chunkIndexForBucket(2), entry(2));

      Map<Integer, Boolean> loaded = loadedOf(region);
      assertTrue(loaded.containsKey(0),
          "落盘失败的脏桶必须仍在 loaded 记账内（旧实现会把它移出却不释放 slots，导致脱离记账）");
      assertFalse(loaded.containsKey(1), "应改用可安全移除的干净桶收敛占用");
      assertEquals(2, loaded.size(), "驱逐后 loaded.size() 不得超过 bucketCacheSize");

      BufferedLinearV3Format.Entry[][] slots = slotsOf(region);
      assertNotNull(slots[0], "落盘失败的桶内存态必须保留（否则写入会丢），等下次 flush/close 重试");
      assertTrue(dirtyOf(region)[0], "落盘失败的桶必须保留脏标记以便下次重试");
    } finally {
      region.close();
    }
  }
}