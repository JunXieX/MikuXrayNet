package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 世界名 → 缓存目录名：既要路径安全，也要能<b>区分世界</b>。
 *
 * <p>旧实现把所有非 [a-zA-Z0-9._-] 字符统一替换成 {@code '_'}，于是「生存世界」与「创造世界」都变成
 * {@code __}，共用同一个缓存文件。指纹能把两边的条目分开，代价是每次访问都要把对方的条目当未命中清掉：
 * 命中率归零，并产生持续的删除 + 重写。
 */
class DiskCacheSanitizeTest {

  private static String sanitize(String worldName) throws Exception {
    Method method = DiskCacheStore.class.getDeclaredMethod("sanitize", String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, worldName);
  }

  @Test
  void distinctUnsafeNamesMapToDistinctDirectories() throws Exception {
    assertNotEquals(sanitize("生存世界"), sanitize("创造世界"),
        "仅含非安全字符的世界名也必须落到不同目录，否则共用文件、互相清条目");
    assertNotEquals(sanitize("生存"), sanitize("创造"));
    assertNotEquals(sanitize("a/b"), sanitize("a_b"), "路径分隔符替换后不得与真实下划线世界撞名");
  }

  @Test
  void safeNamesKeepTheirDirectory() throws Exception {
    assertEquals("world", sanitize("world"), "纯 ASCII 世界名必须保持原样（既有缓存目录不需迁移）");
    assertEquals("world_nether", sanitize("world_nether"));
    assertEquals("unknown", sanitize("..."), "只由点组成的名字仍须归一到 unknown（防父目录逃逸）");
    assertEquals("unknown", sanitize(""), "空名仍须归一到 unknown");
  }
}