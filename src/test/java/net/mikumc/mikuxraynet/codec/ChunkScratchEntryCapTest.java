package net.mikumc.mikuxraynet.codec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * scratch 内部数组条目的上限守门：{@link ChunkScratch} 按「借出顺序」缓存数组，游标只增不减。
 * 一旦某次编解码的借出次数异常（损坏输入让 section 数或调色板次数暴涨），缓存条目就会按次数无界增长，
 * 且随线程常驻——容量上限只挡住了单个数组，没挡住条目数。
 */
class ChunkScratchEntryCapTest {

  private static int cachedCount(Object scratch, String fieldName) throws Exception {
    Field field = ChunkScratch.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return ((List<?>) field.get(scratch)).size();
  }

  @Test
  void cachedArraysStayBounded() throws Exception {
    ChunkScratch scratch = ChunkScratch.acquire();
    try {
      for (int i = 0; i < 512; i++) {
        scratch.bytes(16 + (i % 4));
        scratch.longs(1 + (i % 4));
      }
      int byteArrays = cachedCount(scratch, "byteArrays");
      int longArrays = cachedCount(scratch, "longArrays");
      assertTrue(byteArrays <= 64, "字节数组缓存条目必须有上限，实际 " + byteArrays);
      assertTrue(longArrays <= 64, "long 数组缓存条目必须有上限，实际 " + longArrays);
    } finally {
      scratch.recycle();
    }
  }
}
