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
      // containsKey 而非 get != null：区分「上次下发过 null」与「从未下发过」
      if (!cache.containsKey(index) || !Objects.equals(cache.get(index), value)) {
        changed[count++] = i;
      }
      cache.put(index, value);
    }
    return Arrays.copyOf(changed, count);
  }
}
