package net.mikumc.mikuxraynet.bandwidth;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * 实体元数据「不变值剔除」的纯逻辑（不依赖 ProtocolLib / Bukkit，可离线单测）。
 *
 * <p>把本次 {@code ENTITY_METADATA} 包里的条目与「上次已下发给该玩家的值」逐条比对，给出<b>真正需要下发</b>
 * 的条目（值变化了，或该索引从未下发过）；长度 0 表示整包冗余、可直接取消。
 *
 * <p><b>为什么用 {@link Objects#equals} 且「判不准就当成变化」</b>：值可能是任意 NMS 对象，其中一部分
 * 未实现（或未正确实现）{@code equals}。此时比较结果会是 false → 判为「变化」→ 照常下发，最多少省一点
 * 带宽，绝不误删客户端需要的数据。反之若实现正确，相等的值说明客户端已持有，删掉是安全的。
 *
 * <p><b>为什么既要判 {@code containsKey} 又要判值</b>：值本身可以是 {@code null}（合法的元数据值），
 * 因此「上次值是 null」与「从未下发过」必须区分开——前者可删，后者必须下发。
 *
 * <p><b>为什么「同一引用 + 类型可能可变」也要算变化</b>：缓存里存的是值的<b>引用</b>。若某插件持有可变值
 * 对象、原地修改后仍以同一引用重发，那么 {@code equals(自己, 自己)} 恒为 true，据此剔除会让客户端
 * <b>永远收不到</b>这次更新（而缓存已指向被改后的对象，自认为最新）。同一引用无法证明「内容没变」，
 * 故对非已知不可变类型一律改判为变化。反向的「引用相同但内容绝不可能变」只对
 * {@link #isKnownImmutable} 列出的类型成立，那里仍按值去重，避免元数据里最常见的布尔/小整数条目
 * 因包装类型驻留而永远无法去重。
 */
final class MetadataDelta {

  private MetadataDelta() {
  }

  /**
   * 计算本次包中需要保留的条目下标。
   *
   * @param indices 本次包中各条目的索引（顺序与 values 一一对应，允许重复——重复时按后出现的值收敛）
   * @param values  与 indices 一一对应的值（允许 {@code null}）
   * @param cache   「索引 → 上次已下发的值」缓存（按玩家 + 实体维度持有）；本方法会<b>就地更新</b>它
   * @return 需要保留的条目在入参数组中的下标（升序出现顺序）；长度为 0 表示整包冗余、可直接取消
   */
  static int[] changedEntries(int[] indices, Object[] values, Map<Integer, Object> cache) {
    int[] changed = new int[indices.length];
    int count = 0;
    for (int i = 0; i < indices.length; i++) {
      int index = indices[i];
      Object value = values[i];
      if (changed(cache, index, value)) {
        changed[count++] = i;
      }
      cache.put(index, value);
    }
    return Arrays.copyOf(changed, count);
  }

  /**
   * 该条目是否需要下发（判定规则见类注释）。
   *
   * <p>顺序很关键：先判「从未下发过」（必须下发），再判「同一引用且类型可能可变」（无法证明未变，
   * 必须下发），最后才按 {@link Objects#equals} 判值——值相等即说明客户端已持有，可安全剔除。
   */
  private static boolean changed(Map<Integer, Object> cache, int index, Object value) {
    // containsKey 而非 get != null：区分「上次下发过 null」与「从未下发过」
    if (!cache.containsKey(index)) {
      return true;
    }
    Object cached = cache.get(index);
    if (cached == value && !isKnownImmutable(value)) {
      // 同一引用且类型可能被原地修改：无法证明内容未变 → 宁可多发一次，绝不误丢更新
      return true;
    }
    return !Objects.equals(cached, value);
  }

  /**
   * 内容绝不可能被原地修改、因此「引用相同」即可安全按值去重的类型。
   *
   * <p>{@code Boolean}、小整数等包装值以及字符串字面量在 JVM 里是<b>驻留单例 / 复用实例</b>——引用天然
   * 相同，但内容永不改变。若不把这类排除，元数据中最常见的布尔与小整数条目将永远判为「变化」，
   * 使本模块在真实负载下几乎失去作用。{@code Number} 覆盖全部包装数值类型（含 BigInteger/BigDecimal）。
   */
  private static boolean isKnownImmutable(Object value) {
    return value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Enum<?>;
  }
}
