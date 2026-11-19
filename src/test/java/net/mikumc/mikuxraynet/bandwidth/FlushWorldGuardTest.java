package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 冲刷前「整批丢弃」判定的守门用例 —— 这是生产事故的直接回归。
 *
 * <p><b>事故形态</b>（29 人 / 4 小时 dump）：丢弃 695 564 个原包，四源拆分显示<b>全部</b>来自「冲刷前比对」，
 * 而同期 {@code PlayerChangedWorldEvent} 计数为 0。逐条处置环写得很清楚：
 * 「缓冲世界 minecraft_lobby ≠ 当前世界 world」。也就是说没有任何玩家真的切维，却有几十万批被判成跨世界丢弃。
 *
 * <p><b>根因</b>：守卫拿<b>位置快照</b>当证据。快照由 Folia 实体调度器的每 tick 任务维护，而该任务会随实体
 * 被移出旧 world（切维 / 重生）退役、且退役不保证回调清理入口 —— 快照冻结在切换前那一刻，世界名与坐标都
 * 看起来正常。于是每一批在途变更都被判成「玩家已换世界」而整批丢掉，玩家侧表现就是假方块挖不掉、右键才消失。
 *
 * <p><b>结论</b>：只认世界索引（与打标同源、随事件前进）。索引未变时，快照说什么都不许丢。
 */
class FlushWorldGuardTest {

  @Test
  @DisplayName("索引未变：一律不丢（冻结快照不得成为丢弃依据）")
  void sameWorldIsNeverDiscarded() {
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush("minecraft_lobby", "minecraft_lobby"),
        "索引仍是同一个世界 → 不许丢弃（事故现场正是这一条被判成丢弃）");
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush("world", "world"));
  }

  @Test
  @DisplayName("索引已变：按真切维处理，允许丢弃")
  void changedWorldIsDiscarded() {
    assertTrue(BlockChangeMerger.shouldDiscardAtFlush("world", "world_nether"),
        "索引已随切维前进 → 与真切维同等处理（旧世界区块会整列重建）");
    assertTrue(BlockChangeMerger.shouldDiscardAtFlush("minecraft_lobby", "world"));
  }

  @Test
  @DisplayName("任一侧世界未知：不丢（取消即欠账）")
  void unknownWorldNeverDiscards() {
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush(null, "world"));
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush("world", null));
    assertFalse(BlockChangeMerger.shouldDiscardAtFlush(null, null));
  }

  /**
   * 封存闸的结构守门：聚簇与 passed 的只读复制必须落在 {@code synchronized (target)} 之外。
   *
   * <p>为什么值得钉在源码上：这两段的成本随 {@code max-pending-entries} 平方增长（默认 256 条 =
   * 32 640 对，配置上限 8192 条 = 33 550 336 对），而同一把锁正是该玩家封包线程
   * {@code onPacketSending} 要抢的锁。封存本身（{@code flushed} CAS + 从表里摘除）已经把「谁负责这一批」
   * 仲裁完，旧对象此后不可再被任何人取得，因此锁外聚簇与锁内聚簇结果逐位相同——这条用例保证它不会
   * 在后续改动里被悄悄挪回锁内，也保证聚簇没有被「顺手删掉」。
   */
  @Test
  @DisplayName("封存后的聚簇与 passed 复制必须落在 synchronized 之外")
  void clusteringAndSnapshotCopyRunOutsideTheSealLock() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    String[] signatures = {
        "private boolean flushInternal(",
        "private void discard(Pending target, ThrottleStats.DiscardSource source, String destination)"};
    for (String signature : signatures) {
      String body = methodBody(merger, signature);
      assertTrue(body.length() > 400, signature + " 必须仍存在且能取到方法体");
      assertTrue(body.contains("drainClusters("), signature + " 仍必须聚簇（不得为了缩短锁而删掉）");
      String lock = synchronizedBlock(body);
      assertFalse(lock.contains("drainClusters("),
          "聚簇不得留在 synchronized (target) 内：那是最坏 O(n²) 的一段，而这把锁正是封包线程要抢的");
      assertFalse(lock.contains("new HashSet<>("), "passed 的只读复制同样不得占着这把锁");
      assertTrue(lock.contains("flushed.compareAndSet(false, true)"),
          "封存闸（flushed CAS）必须仍在锁内首步：它是「谁负责这一批」的唯一仲裁");
      assertTrue(lock.contains("pending.remove("),
          "摘除必须仍在锁内与 CAS 相邻完成，锁外聚簇才拿得到「再无写入方」的前提");
    }
  }

  /**
   * 守卫证据的取点守门：世界索引 / 快照心跳 / 丢弃判定必须都还在封存那一刻的锁内，并且只读一次。
   *
   * <p>锁外重读是危险的：那会让「封存了哪一批」与「此刻在哪个世界」来自两个不同时刻，也与打标来源
   * 不再同源。历史事故（69.5 万包）正是丢弃判定取错了证据；这条用例把取点钉住。
   */
  @Test
  @DisplayName("世界守卫的证据仍只在封存锁内取一次")
  void worldGuardEvidenceIsReadOnceInsideTheSealLock() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    String body = methodBody(merger, "private boolean flushInternal(");
    String lock = synchronizedBlock(body);
    assertTrue(lock.contains("shouldDiscardAtFlush("),
        "丢弃判定必须仍在封存时点的锁内执行：锁外重算会让「封存了哪一批」与「此刻世界」错位");
    assertTrue(lock.contains("PlayerWorldIndex.worldName("),
        "世界索引必须在锁内读取（与入缓冲打标同源），锁外不得成为判定依据");
    assertTrue(lock.contains("positions.isStale("), "快照心跳判定同样必须在锁内取一次");
    assertEquals(1, countOf(body, "PlayerWorldIndex.worldName("),
        "同一次冲刷只能读一次世界索引：两次读会让判定与留痕来自不同时刻");
    assertEquals(1, countOf(body, "drainClusters("), "同一次冲刷只能聚簇一次");
  }

  @Test
  @DisplayName("逐对比较只访问每个无序边一次（j <= i 短路），且不再退回 j == i")
  void eachUnorderedPairIsVisitedOnce() throws Exception {
    String body = methodBody(source("bandwidth/BlockChangeBatch.java"),
        "public List<List<Update<V>>> drainClusters(int radius)");
    assertTrue(body.length() > 500, "drainClusters 必须仍存在且能取到方法体");
    assertTrue(body.contains("if (j <= i)"),
        "每个无序对只许判一次：j <= i 跳过与比较两次结果相同（并查集只关心连通性），比较次数减半");
    assertFalse(body.contains("if (j == i)"), "旧的「只跳过自身」写法会让同一个无序对被比较两次");
    assertTrue(body.contains("manhattan <= distance"), "是否相连仍必须由精确曼哈顿距离决定（桶只用于剪枝）");
  }

  private static String source(String relative) throws Exception {
    // 行尾归一：工作区源码是 CRLF，断言不应依赖行尾
    return Files.readString(Path.of("src/main/java/net/mikumc/mikuxraynet/" + relative))
        .replace("\r\n", "\n");
  }

  /** 取方法体：从签名截到其后第一个「两空格缩进的右花括号」（与 arch 守门同一取法）。 */
  private static String methodBody(String source, String signature) {
    int start = source.indexOf(signature);
    if (start < 0) {
      return "";
    }
    int end = source.indexOf("\n  }", start);
    return end < 0 ? source.substring(start) : source.substring(start, end);
  }

  /** 取方法体内唯一的 {@code synchronized (target)} 块（花括号配平，注释里的花括号先屏蔽）。 */
  private static String synchronizedBlock(String body) {
    String blanked = blankComments(body);
    String opener = "synchronized (target) {";
    int start = blanked.indexOf(opener);
    assertTrue(start >= 0, "封存闸必须仍是 synchronized (target)");
    int depth = 0;
    for (int i = start + opener.length() - 1; i < blanked.length(); i++) {
      char c = blanked.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return body.substring(start, i + 1);
        }
      }
    }
    throw new AssertionError("synchronized (target) 块未闭合");
  }

  /** 把注释内容替换成空格（长度不变，便于用同一套下标切原文）。 */
  private static String blankComments(String text) {
    char[] chars = text.toCharArray();
    int i = 0;
    while (i < chars.length) {
      if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '*') {
        int end = text.indexOf("*/", i + 2);
        int last = end < 0 ? chars.length : end + 2;
        for (int k = i; k < last; k++) {
          if (chars[k] != '\n' && chars[k] != '\r') {
            chars[k] = ' ';
          }
        }
        i = last;
      } else if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '/') {
        int end = text.indexOf('\n', i);
        int last = end < 0 ? chars.length : end;
        for (int k = i; k < last; k++) {
          if (chars[k] != '\r') {
            chars[k] = ' ';
          }
        }
        i = last;
      } else {
        i++;
      }
    }
    return new String(chars);
  }

  private static int countOf(String text, String token) {
    int count = 0;
    int from = 0;
    while (true) {
      int at = text.indexOf(token, from);
      if (at < 0) {
        return count;
      }
      count++;
      from = at + token.length();
    }
  }
}
