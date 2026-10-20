package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 区域文件的两条回归：
 * <ul>
 *   <li><b>读命中必须直接物化</b>：{@code BUCKET_SHIFT=0} 下一条记录只承载一个槽位，「懒加载只解一个槽位」
 *       的收益恒为 0，却会让解压后的整条记录（{@code LazyBucket.raw}）与解码出的 payload 副本同时常驻
 *       ——每个命中区块凭空多占约一份 payload 的内存。故读路径在 {@code BUCKET_SIZE==1} 时直接物化进
 *       {@code slots}，只保留 payload 一份（多槽位布局仍保留懒路径）。</li>
 *   <li><b>落盘必须分批</b>：一条记录 = 整桶重编码 + 压缩 + append，脏记录累积到数百条时一次性写完会
 *       独占磁盘线程上百 ms，而读取预算只有 50 ms。{@link RegionFile#flushDirty(int)} 每批最多写
 *       {@link RegionFile#FLUSH_BATCH_RECORDS} 条，{@link RegionFile#hasDirtySlots()} 报告余量以便
 *       调用方续排；本用例钉住「不超预算、余量准确、跨批不丢数据」。</li>
 * </ul>
 */
class RegionFileBatchedFlushTest {

  private static BufferedLinearV3Format.Entry entry(int marker) {
    return new BufferedLinearV3Format.Entry(0L, 1L, marker, new byte[] {(byte) marker, 2, 3});
  }

  private static Object[] slotsOf(RegionFile region) throws Exception {
    Field field = RegionFile.class.getDeclaredField("slots");
    field.setAccessible(true);
    return (Object[]) field.get(region);
  }

  private static int lazyBucketCount(RegionFile region) throws Exception {
    Field field = RegionFile.class.getDeclaredField("lazyBuckets");
    field.setAccessible(true);
    Object[] buckets = (Object[]) field.get(region);
    int count = 0;
    for (Object bucket : buckets) {
      if (bucket != null) {
        count++;
      }
    }
    return count;
  }

  /** 读命中必须直接物化进 {@code slots}：不得再留下「解压后的整条记录 + payload 副本」双份常驻。 */
  @Test
  void readHitMaterializesBucketAndDropsLazyRepresentation(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.0.0.b_linear");
    int chunkIndex = BufferedLinearV3Format.chunkIndex(0, 0);

    RegionFile writer = RegionFile.open(file, 4);
    try {
      writer.put(chunkIndex, entry(7));
      writer.flushDirty();
    } finally {
      writer.close();
    }

    RegionFile reader = RegionFile.open(file, 4);
    try {
      assertEquals(0, lazyBucketCount(reader), "前置：新实例不应持有任何懒加载桶");
      BufferedLinearV3Format.Entry loaded = reader.get(chunkIndex);
      assertNotNull(loaded, "已落盘的条目必须可读出");
      assertArrayEquals(new byte[] {7, 2, 3}, loaded.payload(), "payload 必须与原写入一致");
      assertEquals(0, lazyBucketCount(reader),
          "BUCKET_SIZE==1 下读命中必须直接物化：懒加载会让 raw 与 payload 副本双份常驻（每个命中区块多占约一份 payload）");
      assertNotNull(slotsOf(reader)[0], "命中后该桶必须已在 slots 中（唯一常驻表示）");
      assertSame(loaded, reader.get(chunkIndex), "物化之后再读应返回同一 Entry（不再重复解压）");
    } finally {
      reader.close();
    }
  }

  /** 分批落盘：单批不超预算、余量报告准确、跨批之后每条记录都能读回。 */
  @Test
  void batchedFlushRespectsBudgetAndPersistsEveryRecord(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.0.0.b_linear");
    int budget = RegionFile.FLUSH_BATCH_RECORDS;
    int records = budget * 2 + 5;

    // 桶缓存开得足够大：避免 LRU 驱逐时「脏桶顺手落盘」干扰本用例的批次断言
    RegionFile writer = RegionFile.open(file, records + 8);
    try {
      for (int index = 0; index < records; index++) {
        writer.put(index, entry(index));
      }
      assertTrue(writer.hasDirtySlots(), "前置：全部记录均已标脏");

      int first = writer.flushDirty(budget);
      assertEquals(budget, first, "单批必须恰好写满预算（不多写、也不少写）");
      assertTrue(writer.hasDirtySlots(), "预算用尽后必须如实报告「仍有脏记录」");

      int total = first;
      while (writer.hasDirtySlots()) {
        int written = writer.flushDirty(budget);
        assertTrue(written > 0, "续写必须持续推进（否则调用方会空转重排）");
        assertTrue(written <= budget, "任何一批都不得超过预算：" + written);
        total += written;
      }
      assertEquals(records, total, "逐批写入的记录总数必须等于落盘的记录数");
      assertEquals(0, writer.flushDirty(budget), "没有脏记录时再调必须返回 0（不产生空写）");
    } finally {
      writer.close();
    }

    RegionFile reader = RegionFile.open(file, 4);
    try {
      for (int index = 0; index < records; index++) {
        assertNotNull(reader.get(index), "第 " + index + " 条记录必须已落盘可读（分批不得丢数据）");
      }
    } finally {
      reader.close();
    }
  }
}
