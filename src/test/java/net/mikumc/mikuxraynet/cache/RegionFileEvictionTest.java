package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
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

  /**
   * 驱逐顺序必须仍是「最久未使用优先」：去掉 {@code new ArrayList<>(loaded.keySet())} 快照、改为直接遍历
   * 访问序 {@code LinkedHashMap} 后，LRU 语义不得改变。
   *
   * <p>装载桶 0、1 后读一次桶 0（把它提升为最近使用），再装载桶 2 触发驱逐：应淘汰桶 1（此时最久未用），
   * 保留刚被访问过的桶 0。若遍历顺序被破坏（例如按插入序而非访问序），受害者会变成桶 0，本用例即失败。
   */
  @Test
  void evictionPicksLeastRecentlyUsedBucketInAccessOrder(@TempDir Path dir) throws Exception {
    RegionFile region = RegionFile.open(dir.resolve("r.0.0.b_linear"), 2);
    try {
      region.put(chunkIndexForBucket(0), entry(0));
      region.put(chunkIndexForBucket(1), entry(1));
      assertNotNull(region.get(chunkIndexForBucket(0)), "读命中桶 0，使其成为最近使用");

      region.put(chunkIndexForBucket(2), entry(2));

      Map<Integer, Boolean> loaded = loadedOf(region);
      assertTrue(loaded.containsKey(0), "被最近访问过的桶 0 不应被驱逐（LRU 访问序必须保持）");
      assertFalse(loaded.containsKey(1), "应淘汰最久未使用的桶 1");
      assertTrue(loaded.containsKey(2), "新加载的桶 2 必须在记账内");
      assertEquals(2, loaded.size(), "驱逐后 loaded.size() 必须收敛到 bucketCacheSize");
    } finally {
      region.close();
    }
  }

  /**
   * 压缩回收「移动临时文件失败」时必须清理 .tmp，且不得破坏原文件。
   *
   * <p>做法：先把目标路径替换成<b>非空目录</b>（{@code Files.move} 到非空目录必然失败），
   * 让 {@link RegionFile#compact} 在「临时文件已写出、等待替换正式文件」这一步失败，
   * 断言临时文件被清理而非残留成孤儿文件。（原文件在移动失败时保持不变。）
   */
  @Test
  void compactMoveFailureCleansTempFileWithoutTouchingTarget(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.0.0.b_linear");
    Path temp = dir.resolve("r.0.0.b_linear.tmp");
    RegionFile region = RegionFile.open(file, 2);
    try {
      region.put(chunkIndexForBucket(0), entry(1));
      region.flushDirty();

      // 关闭通道（释放锁/句柄）后把目标路径换成非空目录：此后的 move 一定失败
      region.killChannelForTest();
      Files.delete(file);
      Files.createDirectory(file);
      Files.write(file.resolve("blocker"), new byte[] {1});

      assertThrows(IOException.class, () -> region.compact(null),
          "目标路径为非空目录时，压缩的移动必须失败");
      assertFalse(Files.exists(temp), "移动失败后必须清理临时文件（旧实现会泄漏 .tmp）");
    } finally {
      region.close();
    }
  }
}