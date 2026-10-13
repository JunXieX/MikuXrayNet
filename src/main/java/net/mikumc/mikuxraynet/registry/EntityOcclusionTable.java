package net.mikumc.mikuxraynet.registry;

import java.util.BitSet;
import java.util.Map;

/**
 * <b>实体遮挡口径表</b>：按 Bukkit {@code Material#isOccluding()}（即服务端 NMS 的权威
 * {@code isOccluding} 标记）逐方块判定「整块不透明」，供<b>实体剔除的异步位图</b>使用。
 *
 * <p><b>为什么需要第二张表（本类存在的唯一理由）</b>：本项目有两条互不相同的遮挡判定链路：
 * <ul>
 *   <li><b>反矿透伪装</b>（{@link BlockStateRegistry#isOccluding}）——按 PacketEvents 状态语义启发式判定
 *       （air / solid / exceedsCube / 材质类别 / 双台阶），刻意<b>偏多判</b>：把「看起来实心」的方块也算遮挡，
 *       宁可多伪装、不可漏伪装（漏伪装 = 透视端直接看到矿物，是安全事故）。</li>
 *   <li><b>实体剔除</b>——既有同步射线用的是 {@code Material#isOccluding()}（NMS 权威标记），
 *       方向相反：<b>偏少判</b>才是安全方向（多判 = 把本可见的实体藏起来）。</li>
 * </ul>
 * 真机实测两套口径对 <b>125/1196</b> 种方块判定相反（例如 {@code anvil}：Bukkit=非遮挡 / 反矿透表=遮挡；
 * {@code barrier}：Bukkit=遮挡 / 反矿透表=非遮挡）。若实体位图沿用反矿透口径，异步模式会比同步模式
 * 「多藏」——同一功能两种口径即是漂移。因此实体位图单独用这张表，反矿透那条链路<b>一字不动</b>。
 *
 * <p><b>粒度说明（有意为之）</b>：{@code Material} 是方块级、无状态，因此同一方块的所有状态共享一个判定
 * ——这与既有同步射线 {@code block.getType().isOccluding()} 完全一致（两者同源），正是本表的目的。
 * 代价是不再有「双台阶 vs 单台阶」这类状态级细分；它同样存在于同步路径上，故不构成新增退化。
 *
 * <p><b>回落语义</b>：某方块名在 Bukkit 表里查不到（例如 PacketEvents 已认识、而服务端 Material 尚无的
 * 新方块）时，退回该状态的 {@link BlockStateRegistry} 判定，并计入 {@link #fallbackCount()} 供启动日志
 * 如实回显——「回落到哪张表」必须可见，否则口径静默分叉。
 *
 * <p><b>平台无关、可离线单测</b>：本类只依赖 {@code java.util}，不引用任何 Bukkit / PacketEvents 类型
 * （Bukkit 侧的判定表由 {@code bootstrap.PacketEventsHook} 构建后以 {@code Map} 传入）。
 */
public final class EntityOcclusionTable {

  /** 状态 id → 方块名；空洞 id 处为 {@code null}。下标即状态 id。 */
  private final String[] blockNameById;
  private final BitSet occludingStates;
  /** 按 Bukkit 口径判定的状态数（名称命中 Bukkit 表）。 */
  private final int alignedCount;
  /** 名称未命中 Bukkit 表、回落注册表口径的状态数。 */
  private final int fallbackCount;

  private EntityOcclusionTable(String[] blockNameById, BitSet occludingStates, int alignedCount,
      int fallbackCount) {
    this.blockNameById = blockNameById;
    this.occludingStates = occludingStates;
    this.alignedCount = alignedCount;
    this.fallbackCount = fallbackCount;
  }

  /**
   * 构建实体遮挡表。
   *
   * @param blockNameById       状态 id → 方块名（下标即 id；空洞处为 {@code null}）
   * @param registryOccluding   注册表口径的遮挡位（回落用）
   * @param bukkitByBlockName   Bukkit 口径：方块名 → 是否遮挡。{@code null} 表示没有 Bukkit 表（离线场景），
   *                             此时全表回落注册表口径
   * @return 不可变表；{@code blockNameById} 为 {@code null} 时返回 {@code null}（表示不建该表）
   */
  public static EntityOcclusionTable build(String[] blockNameById, BitSet registryOccluding,
      Map<String, Boolean> bukkitByBlockName) {
    if (blockNameById == null) {
      return null;
    }
    BitSet occluding = new BitSet(blockNameById.length);
    int aligned = 0;
    int fallback = 0;
    for (int id = 0; id < blockNameById.length; id++) {
      String name = blockNameById[id];
      if (name == null) {
        continue; // 空洞 id：不是有效状态，不参与任何判定
      }
      Boolean bukkit = bukkitByBlockName == null ? null : bukkitByBlockName.get(name);
      boolean verdict;
      if (bukkit != null) {
        verdict = bukkit;
        aligned++;
      } else {
        verdict = registryOccluding != null && registryOccluding.get(id);
        fallback++;
      }
      if (verdict) {
        occluding.set(id);
      }
    }
    return new EntityOcclusionTable(blockNameById, occluding, aligned, fallback);
  }

  /** 该状态是否遮挡（越界一律按「不遮挡」处理：少判才是实体剔除的安全方向）。 */
  public boolean isOccluding(int stateId) {
    return stateId >= 0 && stateId < blockNameById.length && occludingStates.get(stateId);
  }

  /** 实体口径下被判定为「遮挡」的状态数（启动自检日志用）。 */
  public int occludingStateCount() {
    return occludingStates.cardinality();
  }

  /** 按 Bukkit 口径直接判定的状态数。 */
  public int alignedCount() {
    return alignedCount;
  }

  /** 名称未命中 Bukkit 表、回落注册表口径的状态数（持续偏大说明 Bukkit 侧缺方块名）。 */
  public int fallbackCount() {
    return fallbackCount;
  }
}
