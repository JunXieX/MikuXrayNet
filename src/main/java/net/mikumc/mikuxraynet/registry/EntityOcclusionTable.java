package net.mikumc.mikuxraynet.registry;

import java.util.BitSet;

/**
 * <b>实体遮挡口径表</b>：供<b>实体剔除的异步位图</b>使用的逐状态遮挡判定。
 *
 * <p><b>它与反矿透伪装口径的关系</b>：两者现在<b>同源</b>——都取自同一次逐状态真值解析
 * （{@link StateOcclusionOracle}，即服务端 {@code BlockData#isOccluding()}；解析失败的状态回落
 * {@link OcclusionRules} 的启发式）。差别只有一处：本表<b>不</b>应用
 * {@code occlusion.extra-occluding} / {@code extra-non-occluding} 用户覆盖（那是伪装口径的纠正出口，
 * bandwidth.yml 的实体剔除不受它影响）。因此在没有覆盖表时，两套口径的逐状态判定完全一致
 * （启动期自证差异恒为 0）——这正是消除「异步与同步漂移」的关键。
 *
 * <p><b>历史</b>：旧实现按 {@code Material#isOccluding()}（方块级、无状态）逐方块名查表，与同步射线
 * 同源；但它无法区分同一方块的不同状态（如单台阶 vs 双台阶），而同步射线其实走的是
 * {@code BlockData#isOccluding()}（逐状态）。改为逐状态真值后反而与同步路径<b>更</b>对齐。
 *
 * <p><b>平台无关、可离线单测</b>：本类只依赖 {@code java.util} 的位图；真正的「逐状态解析」在
 * {@link StateOcclusionOracle} 里完成，构建时由 {@link BlockStateRegistry} 把结果位图传进来。
 */
public final class EntityOcclusionTable {

  /** 状态 id → 是否遮挡（下标即状态 id；空洞 id 恒为 0）。 */
  private final BitSet occludingStates;

  private EntityOcclusionTable(BitSet occludingStates) {
    this.occludingStates = occludingStates;
  }

  /**
   * 从「逐状态遮挡位图」构建实体口径表。
   *
   * @param perStateOccluding 逐状态遮挡位（下标即状态 id；已含解析失败时的启发式回落）
   * @return 不可变表；{@code perStateOccluding} 为 {@code null} 时返回 {@code null}（表示不建该表）
   */
  public static EntityOcclusionTable build(BitSet perStateOccluding) {
    if (perStateOccluding == null) {
      return null;
    }
    return new EntityOcclusionTable((BitSet) perStateOccluding.clone());
  }

  /** 该状态是否遮挡（越界一律按「不遮挡」处理：少判才是实体剔除的安全方向）。 */
  public boolean isOccluding(int stateId) {
    return stateId >= 0 && stateId < occludingStates.length() && occludingStates.get(stateId);
  }

  /** 实体口径下被判定为「遮挡」的状态数（启动自检日志用）。 */
  public int occludingStateCount() {
    return occludingStates.cardinality();
  }
}
