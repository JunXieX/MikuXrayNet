package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 实体元数据「不变值剔除」的纯逻辑回归。
 *
 * <p>主线只有三条：① 首次出现（含值为 null）必须下发；② 与上次完全相同必须被剔除（整包冗余时为 0 条）；
 * ③ 只有真正变化的条目才下发。值比较失败（未实现 equals 的类型）只会「判为变化」，不会误删。
 */
class MetadataDeltaTest {

  @Test
  void firstPacketKeepsEveryEntry() {
    Map<Integer, Object> cache = new HashMap<>();
    int[] changed = MetadataDelta.changedEntries(
        new int[] {0, 1, 2}, new Object[] {1, "血量", 20.0F}, cache);

    assertArrayEquals(new int[] {0, 1, 2}, changed, "首次出现的条目全部需要下发");
    assertEquals(1, cache.get(0));
    assertEquals("血量", cache.get(1));
    assertEquals(20.0F, cache.get(2));
  }

  @Test
  void identicalPacketIsFullyRedundant() {
    Map<Integer, Object> cache = new HashMap<>();
    MetadataDelta.changedEntries(new int[] {0, 1}, new Object[] {7, "名字"}, cache);

    int[] changed = MetadataDelta.changedEntries(
        new int[] {0, 1}, new Object[] {7, "名字"}, cache);

    assertEquals(0, changed.length, "全等时必须判为整包冗余（调用方据此取消该包）");
  }

  @Test
  void onlyChangedEntriesAreKept() {
    Map<Integer, Object> cache = new HashMap<>();
    MetadataDelta.changedEntries(new int[] {0, 1, 2}, new Object[] {7, "名字", 20.0F}, cache);

    int[] changed = MetadataDelta.changedEntries(
        new int[] {0, 1, 2}, new Object[] {7, "改名了", 20.0F}, cache);

    assertArrayEquals(new int[] {1}, changed, "只有索引 1 的值变了，只应保留它");
    assertEquals("改名了", cache.get(1), "缓存必须被更新为最新值，否则下一包会重复下发");
  }

  /**
   * 值为 {@code null} 是合法的元数据值：必须区分「上次下发过 null」与「从未下发过」——
   * 前者可剔除，后者必须下发（否则客户端永远拿不到该条目的默认值）。
   */
  @Test
  void nullValueIsDistinguishedFromNeverSent() {
    Map<Integer, Object> cache = new HashMap<>();
    int[] first = MetadataDelta.changedEntries(new int[] {5}, new Object[] {null}, cache);
    assertArrayEquals(new int[] {0}, first, "首次出现的 null 值必须下发");

    int[] second = MetadataDelta.changedEntries(new int[] {5}, new Object[] {null}, cache);
    assertEquals(0, second.length, "上次已下发 null 时，重复的 null 属冗余、应剔除");
  }

  /**
   * 可变值「原地修改后以同一引用重发」必须判为变化。
   *
   * <p>缓存里存的是值的<b>引用</b>，若只按 {@link Object#equals} 判等，{@code equals(自己, 自己)} 恒为
   * true → 该条目被剔除，客户端<b>永远收不到</b>这次更新（而缓存已指向被改后的对象，自认为最新）。
   * 这是本模块唯一会「丢数据」的方向，必须钉死。
   *
   * <p>反向保证（引用相同但内容绝不可能变时仍应去重）由 {@link #identicalPacketIsFullyRedundant} 锁定：
   * 该用例传的 {@code Integer 7} 与字符串字面量都是驻留 / 复用实例，引用天然相同，必须仍能判为冗余。
   */
  @Test
  void inPlaceMutatedValueIsTreatedAsChanged() {
    Map<Integer, Object> cache = new HashMap<>();
    StringBuilder holder = new StringBuilder("血量 20");
    assertArrayEquals(new int[] {0},
        MetadataDelta.changedEntries(new int[] {1}, new Object[] {holder}, cache),
        "首次出现必须下发");

    // 插件原地修改同一个值对象，然后以「同一引用」重发（如可变列表 / NBT / ItemStack 的常见写法）
    holder.setLength(0);
    holder.append("血量 5");

    assertArrayEquals(new int[] {0},
        MetadataDelta.changedEntries(new int[] {1}, new Object[] {holder}, cache),
        "同一引用可能已被原地修改，必须按「变化」下发（否则客户端永远收不到更新）");
  }

  /** 重复索引按出现顺序处理，后出现的值收敛为缓存里的最终值。 */
  @Test
  void duplicateIndicesConvergeToTheLastValue() {
    Map<Integer, Object> cache = new HashMap<>();
    int[] changed = MetadataDelta.changedEntries(
        new int[] {3, 3}, new Object[] {1, 2}, cache);

    assertArrayEquals(new int[] {0, 1}, changed, "同一包内同索引的两次写入都携带新值");
    assertEquals(2, cache.get(3), "缓存必须收敛为最后写入的值");
  }
}
