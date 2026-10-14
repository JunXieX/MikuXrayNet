package net.mikumc.mikuxraynet.bootstrap;

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
 * <p><b>覆盖表的作用域（既定语义）</b>：{@code occlusion.extra-occluding} / {@code extra-non-occluding}
 * <b>只</b>作用于「伪装」判定，<b>不</b>进入显形与实体剔除（两者走视觉口径）。因此当管理员配置了覆盖项时，
 * 同一方块在「伪装」与「显形/实体剔除」上<b>可能得出不同结论</b>——这是有意的：覆盖表是校正「哪些方块该被
 * 伪装」的出口，不应放松显形（显形放松 = 泄露）或改变实体剔除。
 *
 * <p><b>为什么在这里用 {@code Material#name()} 而不是 {@code getAsString()}</b>：名字级归一化是规则需要的
 * 最小信息，且 {@code getMaterial()} 不产生额外分配（{@code getAsString()} 会拼一个状态串）。
 */
public final class BukkitOcclusion {

  private BukkitOcclusion() {
  }

  /**
   * 伪装口径：视觉口径再套 {@code occlusion.extra-*} 名称覆盖（覆盖优先级最高）。
   *
   * @param data              方块状态；{@code null} 按「不遮挡」处理
   * @param extraOccluding    用户额外声明为「遮挡」的方块名（已归一化；可为 {@code null}）
   * @param extraNonOccluding 用户额外声明为「不遮挡」的方块名（已归一化；可为 {@code null}）
   */
  public static boolean camouflageOccluding(BlockData data, Set<String> extraOccluding,
      Set<String> extraNonOccluding) {
    return data != null && OcclusionRules.camouflageVerdict(name(data), data.isOccluding(),
        extraOccluding, extraNonOccluding);
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

  /** 方块名（归一化）：{@code Material#name()} 与方块注册名一致（如 {@code OAK_SLAB} → {@code oak_slab}）。 */
  private static String name(BlockData data) {
    return OcclusionRules.normalize(data.getMaterial().name());
  }
}
