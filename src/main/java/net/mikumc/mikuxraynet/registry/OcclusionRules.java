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
 *   <li>{@code occlusion.extra-occluding}：用户显式声明「算遮挡」——可覆盖真值与全部启发式规则；</li>
 *   <li><b>逐状态真值</b>（{@code perStateTruth}，服务端 {@code BlockData#isOccluding()} 的逐状态结果）——
 *       只要可用就以此为准，<b>不再走</b>下面的启发式；</li>
 *   <li>逐状态真值不可用（该状态解析失败）时，回落下面这套启发式（旧口径）：</li>
 *   <li>空气 / 非固体（{@code isSolid()==false}）→ 不遮挡；</li>
 *   <li>形状超出整方块（{@code exceedsCube()}，如栅栏/墙/竹子/脚手架）→ 不遮挡；</li>
 *   <li>双层台阶（{@code type=double}）→ 遮挡；</li>
 *   <li>名称属于「形状小于整方块」白名单（箱子族等，见 {@link #NON_OCCLUDING_NAMES}）→ 不遮挡；</li>
 *   <li>材质属于植物/玻璃/树叶/液体等装饰性类别 → 不遮挡；</li>
 *   <li>名称以薄片族后缀结尾（台阶/楼梯/板/门/告示牌等）→ 不遮挡；</li>
 *   <li>其余 → 遮挡。</li>
 * </ol>
 *
 * <p><b>关于 waterlogged</b>：PE 的 {@code isFluid()} 已把含水状态算作流体，含水与否不改变方块本体的
 * 形状语义（含水的台阶仍是台阶），因此判定里不额外使用该属性，规则保持一致。
 *
 * <p><b>唯一的「barrier 例外」</b>：{@code barrier} 在服务端 {@code isOccluding} 标记里算「遮挡」，
 * 但它对玩家<b>完全不可见</b>（本插件判据是「玩家视觉能否看穿」）。因此这里把 {@code barrier} 显式判为
 * <b>不遮挡</b>——这是全仓库唯一一处 barrier 例外的定义（见 {@link #visuallyInvisibleOccluder}）。
 * 用户覆盖表仍可压过它（{@code occlusion.extra-occluding} 里写 {@code barrier} 可改回遮挡）。
 */
public final class OcclusionRules {

  /** 视觉不可见、但服务端标记为「遮挡」的方块名：对玩家而言「看不见 = 可以看穿」（barrier 例外）。 */
  private static final Set<String> VISUALLY_INVISIBLE_OCCLUDERS = Set.of("barrier");

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
   * 判定一个方块状态是否遮挡（不带逐状态真值，等价于 {@code perStateTruth = null}）。
   *
   * @param facts           摊平后的判定输入
   * @param extraOccluding  用户额外声明为「遮挡」的方块名（已归一化）
   * @param extraNonOccluding 用户额外声明为「不遮挡」的方块名（已归一化）
   */
  public static boolean isOccluding(Facts facts, Set<String> extraOccluding,
      Set<String> extraNonOccluding) {
    return isOccluding(facts, extraOccluding, extraNonOccluding, null);
  }

  /**
   * 判定一个方块状态是否遮挡（优先采用逐状态真值）。
   *
   * <p>优先级：{@code extra-non-occluding} → {@code extra-occluding} → 逐状态真值（非 {@code null} 时）
   * → 启发式回落。真值可用时<b>不再</b>触碰启发式，因此「材质级判错」（如单/双台阶、箱子族）不会发生。
   *
   * @param facts           摊平后的判定输入
   * @param extraOccluding  用户额外声明为「遮挡」的方块名（已归一化）
   * @param extraNonOccluding 用户额外声明为「不遮挡」的方块名（已归一化）
   * @param perStateTruth   逐状态真值：{@code TRUE}/{@code FALSE}=服务端判定，{@code null}=解析失败（走启发式）
   */
  public static boolean isOccluding(Facts facts, Set<String> extraOccluding,
      Set<String> extraNonOccluding, Boolean perStateTruth) {
    if (facts == null) {
      return false;
    }
    String name = normalize(facts.name());
    return camouflageVerdict(name, serverVerdict(facts, name, perStateTruth),
        extraOccluding, extraNonOccluding);
  }

  /**
   * 遮挡的<b>基线</b>判定（不含任何用户覆盖）：逐状态真值可用时以真值为准，否则走启发式。
   *
   * <p>实体遮挡口径与「同源一致性」自证都用本方法——它与 {@link #isOccluding} 的差别只在覆盖表。
   *
   * @param facts         摊平后的判定输入
   * @param perStateTruth 逐状态真值；{@code null}=解析失败（走启发式）
   */
  public static boolean baseVerdict(Facts facts, Boolean perStateTruth) {
    if (facts == null) {
      return false;
    }
    String name = normalize(facts.name());
    return visualVerdict(name, serverVerdict(facts, name, perStateTruth));
  }

  /**
   * 服务端原始逐状态判定（<b>不含</b> barrier 例外）：真值可用时取真值，否则走启发式回落。
   *
   * <p>只有本方法内部用到「真值 or 启发式」这一层选择；上层（{@link #baseVerdict} /
   * {@link #isOccluding} / 各 Bukkit 入口）一律再经 {@link #visualVerdict} 套用唯一那处 barrier 例外。
   */
  private static boolean serverVerdict(Facts facts, String normalizedName, Boolean perStateTruth) {
    return perStateTruth != null ? perStateTruth : heuristic(facts, normalizedName);
  }

  /**
   * <b>视觉口径</b>：把「服务端原始逐状态判定」套上唯一的 barrier 例外，得到「玩家视觉能否看穿」的结论。
   *
   * <p>这是全仓库「何为遮挡」的<b>唯一规则入口</b>：实体剔除的同步射线、异步位图、反矿透伪装边界邻块、
   * 显形可见性判定在拿不到逐状态真值时都收敛到这里（拿得到真值的走覆盖表变体
   * {@link #camouflageVerdict}）。
   *
   * @param blockName      方块名（可带命名空间/大小写，内部归一化）
   * @param serverOccluding 服务端对该状态的原始 isOccluding 判定（含 barrier 为遮挡）
   */
  public static boolean visualVerdict(String blockName, boolean serverOccluding) {
    return !visuallyInvisibleOccluder(blockName) && serverOccluding;
  }

  /**
   * <b>伪装口径</b>：在视觉口径之上再套 {@code occlusion.extra-*} 用户覆盖（优先级最高）。
   *
   * <p>供只能拿到「方块名 + 服务端原始判定」（而不是摊平后的 {@link Facts}）的调用方使用
   * ——例如反矿透边界邻块的 {@code BlockData} 路径，它必须与 {@code ObfuscationProcessor} 内部
   * 经 {@link BlockStateRegistry#isOccluding} 的伪装判定保持同源。
   *
   * @param blockName         方块名（可带命名空间/大小写，内部归一化）
   * @param serverOccluding   服务端对该状态的原始 isOccluding 判定
   * @param extraOccluding    用户额外声明为「遮挡」的方块名（已归一化；可为 {@code null}）
   * @param extraNonOccluding 用户额外声明为「不遮挡」的方块名（已归一化；可为 {@code null}）
   */
  public static boolean camouflageVerdict(String blockName, boolean serverOccluding,
      Set<String> extraOccluding, Set<String> extraNonOccluding) {
    String name = normalize(blockName);
    if (extraNonOccluding != null && extraNonOccluding.contains(name)) {
      return false;
    }
    if (extraOccluding != null && extraOccluding.contains(name)) {
      return true;
    }
    return visualVerdict(name, serverOccluding);
  }

  /**
   * 该方块名是否属于「视觉不可见、但服务端标记为遮挡」的少数方块（当前只有 {@code barrier}）。
   *
   * <p>这是全仓库 barrier 例外的<b>唯一定义处</b>；各处不得再各写一份。
   */
  public static boolean visuallyInvisibleOccluder(String blockName) {
    return VISUALLY_INVISIBLE_OCCLUDERS.contains(normalize(blockName));
  }

  /** 启发式回落（旧口径）：只在逐状态真值不可用时使用。 */
  private static boolean heuristic(Facts facts, String normalizedName) {
    if (facts.air() || !facts.solid()) {
      return false;
    }
    if (facts.exceedsCube()) {
      return false;
    }
    if (facts.doubleSlab()) {
      return true;
    }
    if (NON_OCCLUDING_NAMES.contains(normalizedName)) {
      return false;
    }
    if (!facts.materialOccluding()) {
      return false;
    }
    return !hasThinSuffix(normalizedName);
  }

  /**
   * 该方块名是否为「薄片族」（仅按名称后缀近似，内部判定用）。
   *
   * <p>入参必须是<b>已归一化</b>的名称：调用方（{@link #isOccluding} / {@link #baseVerdict} 经
   * {@link #serverVerdict}）已对名称归一化过一次，这里不再重复归一化。
   */
  private static boolean hasThinSuffix(String normalized) {
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