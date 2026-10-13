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
 * <p><b>三种口径，按用途选对</b>：
 * <ul>
 *   <li>{@link #visualOccluding(BlockData)}——<b>实体口径</b>（服务端逐状态真值 + barrier 例外，
 *       不含 {@code occlusion.extra-*} 覆盖）：实体剔除的同步射线（{@code EntityCuller.isClear}）用它，
 *       与异步位图（{@code EntityOcclusionTable}）同源，从而「异步开 / 关」在同一方块上结论一致。</li>
 *   <li>{@link #camouflageOccluding(BlockData, java.util.Set, java.util.Set)}——<b>伪装口径</b>
 *       （视觉口径 + {@code occlusion.extra-*} 名称覆盖）：反矿透边界邻块（{@code NeighborChunkProvider}）
 *       用它，与 {@code ObfuscationProcessor} 内部的伪装判定保持同源。</li>
 *   <li>{@link #serverOccluding(BlockData)}——<b>服务端原始逐状态真值</b>（<b>不含</b> barrier 例外）：
 *       只给「显形可见性判定」这类方向相反的路径用——它必须保持保守（barrier 仍算遮挡 → 更少显形），
 *       套用 barrier 例外会把显形变激进（属放松，不可接受）。见 {@code ProximityRevealer}。</li>
 * </ul>
 *
 * <p><b>为什么在这里用 {@code Material#name()} 而不是 {@code getAsString()}</b>：名字级归一化是规则需要的
 * 最小信息，且 {@code getMaterial()} 不产生额外分配（{@code getAsString()} 会拼一个状态串）。
 */
public final class BukkitOcclusion {

  private BukkitOcclusion() {
  }

  /**
   * 实体口径：逐状态真值（{@code BlockData#isOccluding()}）套用唯一那处 barrier 例外。
   *
   * @param data 命中/查询到的方块状态；{@code null} 按「不遮挡」处理（未知即不遮挡，少算一个遮挡面）
   */
  public static boolean visualOccluding(BlockData data) {
    return data != null && OcclusionRules.visualVerdict(name(data), data.isOccluding());
  }

  /**
   * 服务端原始逐状态真值（<b>不含</b> barrier 例外）：供显形射线等「只许更保守」的路径使用。
   *
   * <p>与 {@link #visualOccluding(BlockData)} 的差别只在 barrier：这里 barrier 仍算遮挡（保持既有显形方向，
   * 更少显形）。
   */
  public static boolean serverOccluding(BlockData data) {
    return data != null && data.isOccluding();
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
   * 实体口径的 <b>Material 级</b>判定：供只能拿到方块级信息、拿不到具体状态的调用方使用
   * （{@code OcclusionPatchListener} 的 {@code WrappedBlockData} 路径）。
   *
   * <p><b>已知限制</b>：{@code Material#isOccluding()} 取的是<b>默认状态</b>的判定，无法区分「同方块不同
   * 状态」（如 {@code oak_slab} 的单台阶 vs 双台阶）——这就是补丁路径<b>无法</b>与逐状态位图完全统一的
   * 残余差异。barrier 例外在 Material 级是精确的（barrier 只有一个状态），故这里仍经统一规则套用该例外，
   * 至少把 barrier 与逐状态位图对齐。
   */
  public static boolean entityOccludingByMaterial(Material material) {
    return material != null && OcclusionRules.visualVerdict(material.name(), material.isOccluding());
  }

  /** 方块名（归一化）：{@code Material#name()} 与方块注册名一致（如 {@code OAK_SLAB} → {@code oak_slab}）。 */
  private static String name(BlockData data) {
    return OcclusionRules.normalize(data.getMaterial().name());
  }
}
