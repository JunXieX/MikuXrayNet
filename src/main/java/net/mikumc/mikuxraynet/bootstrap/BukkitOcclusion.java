package net.mikumc.mikuxraynet.bootstrap;

import java.util.Collection;
import java.util.Set;
import net.mikumc.mikuxraynet.registry.OcclusionRules;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Bukkit 侧的「何为遮挡」<b>唯一入口</b>：把 {@link BlockData}（逐状态）交给 {@link OcclusionRules} 的
 * 统一规则，避免各调用点各自写 {@code Material#isOccluding()} / 裸 {@code BlockData#isOccluding()}。
 *
 * <p><b>规则只定义一处</b>：判定规则（含唯一的 barrier 例外）在 {@link OcclusionRules}——
 * 本类只是它的 Bukkit 适配层。凡在运行期拿到 {@link BlockData} 的调用点都应经本类，不要直接调
 * {@code isOccluding()}。
 *
 * <p><b>两条入口按调用频率分工</b>：
 * <ul>
 *   <li>{@link MaterialTable#build}：<b>逐格热路径</b>用。构造期按 {@link Material#ordinal()} 把
 *       「方块名 → 结论」预计算成两张真值表（服务端逐状态判定为「遮挡」/「非遮挡」各一张），运行期每格
 *       只做「取 Material + 读该状态的真值 + 查数组」——<b>不归一化名字、不查覆盖表哈希</b>。
 *       边界邻块抓取（{@code NeighborChunkProvider}）走这条路径。</li>
 *   <li>{@link #entityOccludingByMaterial(Material)}：只能拿到<b>方块级</b>信息时的回落路径（见其说明）。</li>
 * </ul>
 *
 * <p><b>覆盖表的作用域（既定语义）</b>：{@code occlusion.extra-occluding} / {@code extra-non-occluding}
 * <b>只</b>作用于「伪装」判定，<b>不</b>进入显形与实体剔除（两者走视觉口径）。因此当管理员配置了覆盖项时，
 * 同一方块在「伪装」与「显形/实体剔除」上<b>可能得出不同结论</b>——这是有意的：覆盖表是校正「哪些方块该被
 * 伪装」的出口，不应放松显形（显形放松 = 泄露）或改变实体剔除。
 *
 * <p><b>为什么用 {@code Material#name()} 而不是 {@code getAsString()}</b>：名字级归一化是规则需要的
 * 最小信息，且 {@code getMaterial()} 不产生额外分配（{@code getAsString()} 会拼一个状态串）。
 */
public final class BukkitOcclusion {

  private BukkitOcclusion() {
  }

  /**
   * 视觉口径的 <b>Material 级</b>近似：供只能拿到方块级信息、拿不到具体状态的调用方使用。
   *
   * <p><b>用途已收窄为「回落」</b>：{@code OcclusionPatchListener} 现在优先从封包状态取全局 id 查实体口径表
   * （逐状态，与位图一致）；只有 id 解析不可用时才回落到本方法。
   *
   * <p><b>已知限制</b>：{@code Material#isOccluding()} 取的是<b>默认状态</b>的判定，无法区分「同方块不同
   * 状态」（如 {@code oak_slab} 的单台阶 vs 双台阶）——这就是回落路径<b>无法</b>与逐状态位图完全统一的
   * 残余差异（已由 {@code OcclusionStats#stateIdFallbacks} 计数）。barrier 例外在 Material 级是精确的
   * （barrier 只有一个状态），故这里仍经统一规则套用该例外，至少把 barrier 与逐状态位图对齐。
   */
  public static boolean entityOccludingByMaterial(Material material) {
    return material != null && OcclusionRules.visualVerdict(material.name(), material.isOccluding());
  }

  /**
   * 伪装口径的<b>预计算真值表</b>（不可变）：下标 = {@link Material#ordinal()}，两张表分别对应
   * 「服务端对该状态判定为遮挡」与「判定为非遮挡」。
   *
   * <p><b>为什么按 Material × 真值建表</b>：规则只依赖两个输入——方块名与服务端对该状态的判定
   * （{@link OcclusionRules#camouflageVerdict}）。两张表穷举这两个输入的全部组合，因此
   * 「表[ordinal][真值]」与「逐格调用该规则」<b>逐值等价</b>——建表时就是用同一个规则函数算的；差别只在
   * <b>何时</b>付归一化与哈希查找的代价：建表期一次，而不是每格一次。
   *
   * <p><b>为什么不改用 {@code Material#isOccluding()}</b>：它取的是<b>默认状态</b>的判定，同一方块的不同
   * 状态会得出相反结论（单台阶 vs 双台阶）。本表只预计算「名字 → 结论」这一层，逐状态真值仍由调用方从
   * {@code ChunkSnapshot#getBlockData(...)} 取到的 {@link BlockData#isOccluding()} 决定。
   *
   * <p><b>覆盖表在构建期快照</b>：入参先归一化再建表，之后表与入参集合再无引用关系（配置对象后续被改动
   * 不会让表与规则漂移）。{@code occlusion.extra-*} 的改动与既有语义一致地需要重启才生效——
   * {@code ReloadCoordinator} 已把「遮挡规则表」列为需重启项。
   *
   * <p><b>建表代价</b>：{@link Material#values()} 的长度乘以 2 次规则调用（每次一次名字归一化 + 两次集合
   * 查找），只在构造期发生一次；运行期零归一化、零集合查找。
   */
  public static final class MaterialTable {

    /** 服务端判定为「遮挡」时的结论（下标 = {@link Material#ordinal()}）。 */
    private final boolean[] whenServerOccluding;

    /** 服务端判定为「非遮挡」时的结论（下标 = {@link Material#ordinal()}）。 */
    private final boolean[] whenServerClear;

    private MaterialTable(boolean[] whenServerOccluding, boolean[] whenServerClear) {
      this.whenServerOccluding = whenServerOccluding;
      this.whenServerClear = whenServerClear;
    }

    /**
     * 按当前 {@link Material} 全集与覆盖表预计算两张真值表。
     *
     * @param extraOccluding    {@code occlusion.extra-occluding}（可含命名空间/大小写/空白，内部归一化；
     *                          可为 {@code null}）
     * @param extraNonOccluding {@code occlusion.extra-non-occluding}（同上；优先级高于前者）
     */
    public static MaterialTable build(Collection<String> extraOccluding,
        Collection<String> extraNonOccluding) {
      Set<String> occluding = OcclusionRules.normalizeAll(extraOccluding);
      Set<String> nonOccluding = OcclusionRules.normalizeAll(extraNonOccluding);
      Material[] materials = Material.values();
      boolean[] whenTrue = new boolean[materials.length];
      boolean[] whenFalse = new boolean[materials.length];
      for (Material material : materials) {
        int ordinal = material.ordinal();
        String name = material.name();
        whenTrue[ordinal] = OcclusionRules.camouflageVerdict(name, true, occluding, nonOccluding);
        whenFalse[ordinal] = OcclusionRules.camouflageVerdict(name, false, occluding, nonOccluding);
      }
      return new MaterialTable(whenTrue, whenFalse);
    }

    /**
     * 伪装口径的逐格判定（生产热路径）：不取名字、不归一化、不查覆盖表，只按该状态的逐状态真值查表。
     *
     * @param data 方块状态；{@code null} 按「不遮挡」处理（与旧的逐格入口同口径）
     */
    public boolean camouflage(BlockData data) {
      return data != null && occluding(data.getMaterial(), data.isOccluding());
    }

    /**
     * 真值表查询：同一 {@link Material} 的全部状态由 {@code serverOccluding}（该状态的
     * {@link BlockData#isOccluding()}）二分。
     *
     * @param material        方块类型（表按 {@link Material#ordinal()} 建，同一枚举内必命中）
     * @param serverOccluding 服务端对该<b>状态</b>的判定
     */
    public boolean occluding(Material material, boolean serverOccluding) {
      return (serverOccluding ? whenServerOccluding : whenServerClear)[material.ordinal()];
    }
  }
}
