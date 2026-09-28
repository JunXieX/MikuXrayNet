package net.mikumc.mikuxraynet.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 遮挡判定纯逻辑：把「方块状态 → 是否整块不透明（可作为遮挡面）」的规则从 PacketEvents 里剥离出来。
 *
 * <p><b>为什么单独成类</b>：判定规则需要可单测、可被用户覆盖，而 PacketEvents 的类型（{@code StateType} /
 * {@code WrappedBlockState}）无法在离线测试里构造。因此这里只接收「已经摊平的纯值」{@link Facts}，
 * 启动期由 {@link BlockStateRegistry} 从 PE 状态映射填好，单测直接给定值即可。
 *
 * <p><b>判定顺序（后者不会覆盖前者）</b>：
 * <ol>
 *   <li>{@code occlusion.extra-non-occluding}：用户显式声明「不算遮挡」——优先级最高；</li>
 *   <li>{@code occlusion.extra-occluding}：用户显式声明「算遮挡」——可覆盖下面全部内置规则；</li>
 *   <li>空气 / 非固体（{@code isSolid()==false}）→ 不遮挡；</li>
 *   <li>形状超出整方块（{@code exceedsCube()}，如栅栏/墙/竹子/脚手架）→ 不遮挡；</li>
 *   <li>双层台阶（{@code type=double}）→ 遮挡（这是唯一需要看「状态属性」而非只看方块类型的修正）；</li>
 *   <li>名称属于「形状小于整方块」白名单（箱子族等，PE 无对应标志位，见 {@link #NON_OCCLUDING_NAMES}）→ 不遮挡；</li>
 *   <li>材质属于植物/玻璃/树叶/液体等装饰性类别 → 不遮挡；</li>
 *   <li>名称以薄片族后缀结尾（台阶/楼梯/板/门/告示牌等）→ 不遮挡；</li>
 *   <li>其余 → 遮挡。</li>
 * </ol>
 *
 * <p><b>关于 waterlogged</b>：PE 的 {@code isFluid()} 已把含水状态算作流体，含水与否不改变方块本体的
 * 形状语义（含水的台阶仍是台阶），因此判定里不额外使用该属性，规则保持一致。
 */
public final class OcclusionRules {

  /** 判定输入：全部为纯值，不含任何 PacketEvents 类型。 */
  public record Facts(boolean air, boolean solid, boolean exceedsCube, boolean materialOccluding,
      boolean doubleSlab, String name) {
  }

  /** 薄片方块名称后缀（不足以填满整方块，故不能遮挡）。 */
  private static final String[] THIN_NAME_SUFFIXES = {
      "_stairs", "_slab", "_fence", "_fence_gate", "_wall", "_pane", "_door", "_trapdoor",
      "_pressure_plate", "_button", "_carpet", "_sign", "_banner", "_bed", "_rail", "_torch",
      "_lantern", "_chain", "_bars", "_candle", "_campfire", "_flower_pot", "_head", "_skull",
      "_sapling", "_sprouts", "_roots", "_fan", "_bush", "_shulker_box"};

  /**
   * 实心但「形状小于整方块」的方块名称白名单。
   *
   * <p><b>为什么需要这张表</b>：PacketEvents 只提供 {@code exceedsCube}（形状<b>超出</b>整方块，如栅栏/墙），
   * <b>没有</b>「小于整方块」的标志位；箱子族这类方块在 PE 里 {@code isSolid=true}、材质也不是装饰性材质，
   * 只按内置规则会被误判为「遮挡」。依据真机 PE 2.13.0（V_26_2）实测：
   * {@code chest → solid=true / exceedsCube=false / material=WOOD}，故必须在此显式纠正。
   */
  private static final Set<String> NON_OCCLUDING_NAMES = Set.of(
      "chest", "trapped_chest", "ender_chest", "shulker_box");

  private OcclusionRules() {
  }

  /**
   * 判定一个方块状态是否遮挡。
   *
   * @param facts           摊平后的判定输入
   * @param extraOccluding  用户额外声明为「遮挡」的方块名（已归一化）
   * @param extraNonOccluding 用户额外声明为「不遮挡」的方块名（已归一化）
   */
  public static boolean isOccluding(Facts facts, Set<String> extraOccluding,
      Set<String> extraNonOccluding) {
    if (facts == null) {
      return false;
    }
    String name = normalize(facts.name());

    if (extraNonOccluding != null && extraNonOccluding.contains(name)) {
      return false;
    }
    if (extraOccluding != null && extraOccluding.contains(name)) {
      return true;
    }
    if (facts.air() || !facts.solid()) {
      return false;
    }
    if (facts.exceedsCube()) {
      return false;
    }
    if (facts.doubleSlab()) {
      return true;
    }
    if (NON_OCCLUDING_NAMES.contains(name)) {
      return false;
    }
    if (!facts.materialOccluding()) {
      return false;
    }
    return !hasThinSuffix(name);
  }

  /** 该方块名是否为「薄片族」（仅按名称后缀近似，内部判定用）。 */
  private static boolean hasThinSuffix(String name) {
    String normalized = normalize(name);
    for (String suffix : THIN_NAME_SUFFIXES) {
      if (normalized.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  /** 方块名归一化：去空白、转小写、去掉 {@code minecraft:} 前缀与 {@code namespace:} 前缀。 */
  public static String normalize(String name) {
    if (name == null) {
      return "";
    }
    String key = name.trim().toLowerCase(Locale.ROOT);
    int colon = key.indexOf(':');
    return colon >= 0 ? key.substring(colon + 1) : key;
  }

  /** 归一化一批方块名（忽略空白项、去重、保持配置顺序）。 */
  public static Set<String> normalizeAll(Collection<String> names) {
    Set<String> normalized = new LinkedHashSet<>();
    if (names == null) {
      return normalized;
    }
    for (String name : names) {
      String key = normalize(name);
      if (!key.isEmpty()) {
        normalized.add(key);
      }
    }
    return normalized;
  }

  /** 归一化并转为有序列表（用于参与配置指纹，保证顺序无关）。 */
  public static List<String> sortedList(Set<String> names) {
    List<String> sorted = new ArrayList<>(names);
    sorted.sort(String::compareTo);
    return sorted;
  }
}