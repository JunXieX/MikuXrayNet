package net.mikumc.mikuxraynet.bootstrap;

import net.mikumc.mikuxraynet.registry.StateOcclusionOracle;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * {@link StateOcclusionOracle} 的服务端权威实现：把 PacketEvents 给出的<b>方块状态字符串</b>交给 Bukkit
 * 解析成 {@link BlockData}，直接取 {@link BlockData#isOccluding()}——这正是 vanilla 的<b>逐状态</b>
 * {@code isOccluding} 语义（javadoc：该状态是否会遮挡其它方块/是否透光），也就是「玩家视觉能否看穿」。
 *
 * <p><b>为什么不手工拼属性名</b>：PacketEvents 的 {@code WrappedBlockState#toString()} 已经是
 * {@code Bukkit.createBlockData} 可直接解析的 vanilla 状态字符串（PE 自带的 {@code SpigotConversionUtil}
 * 也是直接 {@code Bukkit.createBlockData(state.toString())}）。手工把 PE 的枚举名映射成 vanilla 属性名
 * （如 {@code Type.DOUBLE → "double"}、{@code BlockFace.NORTH → "north"}）既脆弱又无必要——解析失败时
 * 我们本来就要回落启发式，交给 PE 维护这份映射反而更可靠。
 *
 * <p><b>格式事实（已核实）</b>：{@code WrappedBlockState#toString()} 产出<b>不带</b> {@code minecraft:}
 * 命名空间前缀（PE 的 {@code getByString} 先 {@code replace("minecraft:", "")} 再查内部表，
 * 说明其内部键无前缀），形如 {@code chest[facing=north,type=single,waterlogged=false]}。
 * 本类在缺前缀时补上 {@code minecraft:}，使交给 Bukkit 的串严格落在其文档格式
 * （{@code minecraft:chest[waterlogged=true]}）内——这样无论 PE 版本是否带前缀都能解析。
 *
 * <p><b>解析失败</b>：字符串不合法、Bukkit 不认识该方块、属性名/值对不上时，{@code Bukkit.createBlockData}
 * 会抛 {@link IllegalArgumentException}（以及个别 {@code NPE}）。这里一律返回 {@code null}，由调用方
 * 回落既有启发式并计数——<b>绝不</b>让单个状态失败影响整体构建。
 *
 * <p><b>为什么把 barrier 排除</b>：{@code barrier} 在服务端 {@code isOccluding} 标记里算「遮挡」，
 * 但它对玩家<b>完全不可见</b>。本判据是「玩家视觉能否看穿」，故显式判为非遮挡（与既有启发式口径一致）。
 *
 * <p><b>为何只能在真机用</b>：{@code Bukkit.createBlockData} 依赖服务端的 {@code RegistryAccess}，
 * 离线环境会抛 {@code IllegalStateException: No RegistryAccess implementation found}——因此本类不进单测，
 * 单测只覆盖「注入桩 → 回落」路径（见 {@code StateOcclusionOracle}）。
 */
public final class BukkitStateOcclusionOracle implements StateOcclusionOracle {

  /** 视觉不可见、但服务端标记为「遮挡」的方块：对玩家而言「看不见 = 可以看穿」。 */
  private static final Material INVISIBLE_BUT_OCCLUDING = Material.BARRIER;

  @Override
  public Boolean resolve(String blockDataString) {
    try {
      BlockData data = Bukkit.createBlockData(namespaced(blockDataString));
      if (data.getMaterial() == INVISIBLE_BUT_OCCLUDING) {
        return Boolean.FALSE;
      }
      return data.isOccluding();
    } catch (Throwable throwable) {
      // 单状态解析失败：返回 null 交调用方回落启发式并计数（不影响其它状态）
      return null;
    }
  }

  /**
   * 缺 {@code minecraft:} 命名空间前缀时补上（PE 的状态串不带前缀；Bukkit 文档格式带前缀）。
   *
   * <p>判据：取 {@code '['}（属性段起点）之前的部分，若其中没有 {@code ':'}（即无命名空间）则补前缀。
   * vanilla 的属性名/值里不含 {@code ':'}，故该判据不会误伤带前缀的串。
   */
  private static String namespaced(String blockDataString) {
    int bracket = blockDataString.indexOf('[');
    String head = bracket >= 0 ? blockDataString.substring(0, bracket) : blockDataString;
    if (head.indexOf(':') >= 0) {
      return blockDataString;
    }
    return "minecraft:" + blockDataString;
  }
}
