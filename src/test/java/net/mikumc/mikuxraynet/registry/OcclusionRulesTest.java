package net.mikumc.mikuxraynet.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 遮挡判定规则测试：用「摊平后的方块语义」验证典型方块的分类，以及覆盖表（用户纠正出口）的优先级。
 *
 * <p>判定输入刻意设计成纯值，因此无需 PacketEvents 运行时即可离线验证
 * （PE 只负责在启动期把 {@code StateType}/{@code WrappedBlockState} 摊平成这些值）。
 */
class OcclusionRulesTest {

  private static final Set<String> NO_OVERRIDE = Set.of();

  /** 全实心不透明方块：石头 / 泥土 / 深板岩。 */
  private static OcclusionRules.Facts solid(String name) {
    return new OcclusionRules.Facts(false, true, false, true, false, name);
  }

  @Test
  void solidOpaqueBlocksAreOccluding() {
    for (String name : new String[] {"stone", "dirt", "deepslate", "netherrack", "obsidian"}) {
      assertTrue(OcclusionRules.isOccluding(solid(name), NO_OVERRIDE, NO_OVERRIDE),
          name + " 应判为遮挡");
    }
  }

  @Test
  void airAndNonSolidBlocksAreNotOccluding() {
    // 空气：isAir=true、isSolid=false
    OcclusionRules.Facts air = new OcclusionRules.Facts(true, false, false, false, false, "air");
    assertFalse(OcclusionRules.isOccluding(air, NO_OVERRIDE, NO_OVERRIDE));

    // 水：非固体（PE 映射里 water 的 isSolid=false）
    OcclusionRules.Facts water = new OcclusionRules.Facts(false, false, false, true, false, "water");
    assertFalse(OcclusionRules.isOccluding(water, NO_OVERRIDE, NO_OVERRIDE));
  }

  @Test
  void glassAndLeavesAreNotOccludingByMaterial() {
    // 玻璃/树叶：固体但材质属于装饰性类别（PE 的 material 白名单）
    OcclusionRules.Facts glass =
        new OcclusionRules.Facts(false, true, false, false, false, "glass");
    OcclusionRules.Facts leaves =
        new OcclusionRules.Facts(false, true, false, false, false, "oak_leaves");

    assertFalse(OcclusionRules.isOccluding(glass, NO_OVERRIDE, NO_OVERRIDE), "玻璃不遮挡");
    assertFalse(OcclusionRules.isOccluding(leaves, NO_OVERRIDE, NO_OVERRIDE), "树叶不遮挡");
  }

  @Test
  void shapesExceedingCubeAreNotOccluding() {
    // 栅栏/墙：exceedsCube=true（PE 的 isShapeExceedsCube）
    OcclusionRules.Facts fence =
        new OcclusionRules.Facts(false, true, true, true, false, "oak_fence");
    OcclusionRules.Facts wall =
        new OcclusionRules.Facts(false, true, true, true, false, "cobblestone_wall");

    assertFalse(OcclusionRules.isOccluding(fence, NO_OVERRIDE, NO_OVERRIDE));
    assertFalse(OcclusionRules.isOccluding(wall, NO_OVERRIDE, NO_OVERRIDE));
  }

  @Test
  void anvilIsOccluding() {
    // 铁砧：PE 映射 isSolid=true、material=HEAVY_METAL、形状不超出整方块 → 遮挡
    OcclusionRules.Facts anvil = new OcclusionRules.Facts(false, true, false, true, false, "anvil");
    assertTrue(OcclusionRules.isOccluding(anvil, NO_OVERRIDE, NO_OVERRIDE));
  }

  @Test
  void singleSlabIsNotOccludingButDoubleSlabIs() {
    // 单台阶（type=top/bottom）：薄片族 → 不遮挡
    OcclusionRules.Facts single =
        new OcclusionRules.Facts(false, true, false, true, false, "stone_slab");
    assertFalse(OcclusionRules.isOccluding(single, NO_OVERRIDE, NO_OVERRIDE), "单台阶不遮挡");

    // 双层台阶（type=double）：填满整方块 → 遮挡（这是本次提质修正的误判点）
    OcclusionRules.Facts doubleSlab = new OcclusionRules.Facts(false, true, false, true, true,
        "stone_slab");
    assertTrue(OcclusionRules.isOccluding(doubleSlab, NO_OVERRIDE, NO_OVERRIDE), "双台阶应遮挡");
  }

  @Test
  void thinNameSuffixesAreNotOccluding() {
    for (String name : new String[] {"cobblestone_stairs", "oak_door", "oak_trapdoor",
        "oak_pane", "oak_sign", "white_carpet", "oak_button", "oak_pressure_plate"}) {
      assertFalse(OcclusionRules.isOccluding(solid(name), NO_OVERRIDE, NO_OVERRIDE),
          name + " 属于薄片族，不应判为遮挡");
    }
  }

  @Test
  void extraOccludingOverridesBuiltInRules() {
    Set<String> extraOccluding = OcclusionRules.normalizeAll(Set.of("minecraft:cobblestone_stairs"));

    assertTrue(OcclusionRules.isOccluding(solid("cobblestone_stairs"), extraOccluding, NO_OVERRIDE),
        "覆盖表应能把楼梯改为遮挡");
    // 不受影响：栅栏依然靠自身规则被排除（覆盖表只影响列出的方块名）
    assertFalse(OcclusionRules.isOccluding(solid("oak_fence"), extraOccluding, NO_OVERRIDE));
  }

  @Test
  void extraNonOccludingWins() {
    Set<String> extraOccluding = OcclusionRules.normalizeAll(Set.of("stone"));
    Set<String> extraNonOccluding = OcclusionRules.normalizeAll(Set.of("Stone"));

    assertFalse(OcclusionRules.isOccluding(solid("stone"), extraOccluding, extraNonOccluding),
        "「不遮挡」覆盖表优先级最高");
    assertTrue(OcclusionRules.isOccluding(solid("deepslate"), extraOccluding, extraNonOccluding));
  }

  @Test
  void overrideNamesAreCaseAndNamespaceInsensitive() {
    Set<String> normalized = OcclusionRules.normalizeAll(Set.of("  MINECRAFT:Diamond_Ore  ", ""));

    assertEquals(Set.of("diamond_ore"), normalized);
    assertFalse(OcclusionRules.isOccluding(solid("diamond_ore"), NO_OVERRIDE, normalized),
        "带命名空间与大写的覆盖项同样生效");
    assertTrue(OcclusionRules.isOccluding(solid("diamond_ore"), NO_OVERRIDE, NO_OVERRIDE),
        "未覆盖时按内置规则判为遮挡");
  }

  /** 逐状态真值可用时以其为准，两个方向都能压过启发式（这是「材质级判错」的修正点）。 */
  @Test
  void perStateTruthOverridesHeuristicInBothDirections() {
    // 铁砧：启发式判遮挡；逐状态真值说非遮挡 → 以真值为准
    OcclusionRules.Facts anvil = new OcclusionRules.Facts(false, true, false, true, false, "anvil");
    assertTrue(OcclusionRules.isOccluding(anvil, NO_OVERRIDE, NO_OVERRIDE), "无真值时启发式判遮挡");
    assertFalse(OcclusionRules.isOccluding(anvil, NO_OVERRIDE, NO_OVERRIDE, Boolean.FALSE),
        "有真值时以真值为准（非遮挡）");

    // 箱子：启发式判非遮挡；逐状态真值说遮挡 → 以真值为准
    OcclusionRules.Facts chest = new OcclusionRules.Facts(false, true, false, true, false, "chest");
    assertFalse(OcclusionRules.isOccluding(chest, NO_OVERRIDE, NO_OVERRIDE), "无真值时启发式判非遮挡");
    assertTrue(OcclusionRules.isOccluding(chest, NO_OVERRIDE, NO_OVERRIDE, Boolean.TRUE),
        "有真值时以真值为准（遮挡）");
  }

  /** 真值缺失（{@code null}）时回落启发式——单状态解析失败不得改变整体行为。 */
  @Test
  void nullTruthFallsBackToHeuristic() {
    OcclusionRules.Facts anvil = new OcclusionRules.Facts(false, true, false, true, false, "anvil");

    assertTrue(OcclusionRules.isOccluding(anvil, NO_OVERRIDE, NO_OVERRIDE, null),
        "真值不可用时回落启发式（此处判遮挡）");
    assertTrue(OcclusionRules.baseVerdict(anvil, null));
    assertFalse(OcclusionRules.baseVerdict(anvil, Boolean.FALSE), "真值可用时基线取真值");
  }

  /** 用户覆盖表优先级仍最高：既压过真值，也压过启发式。 */
  @Test
  void userOverridesStillWinOverPerStateTruth() {
    Set<String> extraOccluding = OcclusionRules.normalizeAll(Set.of("anvil"));
    Set<String> extraNonOccluding = OcclusionRules.normalizeAll(Set.of("stone"));
    OcclusionRules.Facts anvil = new OcclusionRules.Facts(false, true, false, true, false, "anvil");
    OcclusionRules.Facts stone = new OcclusionRules.Facts(false, true, false, true, false, "stone");

    assertTrue(OcclusionRules.isOccluding(anvil, extraOccluding, extraNonOccluding, Boolean.FALSE),
        "真值说非遮挡、extra-occluding 说遮挡 → 覆盖表胜");
    assertFalse(OcclusionRules.isOccluding(stone, extraOccluding, extraNonOccluding, Boolean.TRUE),
        "真值说遮挡、extra-non-occluding 说非遮挡 → 覆盖表胜");
  }

  @Test
  void nullFactsAreNotOccluding() {
    assertFalse(OcclusionRules.isOccluding(null, NO_OVERRIDE, NO_OVERRIDE),
        "未知/缺失的方块语义一律按「不遮挡」处理（保守方向）");
  }

  /**
   * barrier 例外（全仓库唯一定义处）：服务端逐状态真值把 barrier 标为「遮挡」，但视觉口径必须判非遮挡；
   * 用户覆盖表仍可压过它。
   *
   * <p>本用例同时钉死「例外只写一处、且各口径共用它」——实体口径（{@link OcclusionRules#baseVerdict}）、
   * 伪装口径（{@link OcclusionRules#isOccluding}）、纯视觉规则（{@link OcclusionRules#visualVerdict}）
   * 与边界邻块用的 {@link OcclusionRules#camouflageVerdict} 结论必须一致。
   */
  @Test
  void barrierExceptionIsDefinedHereAndIsOverridable() {
    assertTrue(OcclusionRules.visuallyInvisibleOccluder("barrier"));
    assertTrue(OcclusionRules.visuallyInvisibleOccluder("minecraft:BARRIER"), "归一化后同样命中");
    assertFalse(OcclusionRules.visuallyInvisibleOccluder("glass"));

    // 服务端真值说遮挡，但 barrier 对玩家不可见 → 视觉口径判非遮挡
    assertFalse(OcclusionRules.visualVerdict("barrier", true));
    assertFalse(OcclusionRules.baseVerdict(solid("barrier"), Boolean.TRUE), "实体口径（基线）同样套用该例外");
    assertFalse(OcclusionRules.isOccluding(solid("barrier"), NO_OVERRIDE, NO_OVERRIDE, Boolean.TRUE));
    assertFalse(OcclusionRules.camouflageVerdict("barrier", true, NO_OVERRIDE, NO_OVERRIDE),
        "边界邻块用的伪装口径同样套用该例外");

    // 覆盖表优先级最高：把 barrier 写进 extra-occluding 可改回「遮挡」
    Set<String> extraOccluding = OcclusionRules.normalizeAll(Set.of("barrier"));
    assertTrue(OcclusionRules.isOccluding(solid("barrier"), extraOccluding, NO_OVERRIDE, Boolean.TRUE),
        "显式覆盖可压过 barrier 例外");
    assertTrue(OcclusionRules.camouflageVerdict("barrier", true, extraOccluding, NO_OVERRIDE));
  }

  /** 无逐状态真值（走启发式）时 barrier 也判非遮挡——同一方块在任何路径下结论一致。 */
  @Test
  void barrierIsNotOccludingOnEveryPath() {
    assertFalse(OcclusionRules.visualVerdict("barrier", false));
    assertFalse(OcclusionRules.baseVerdict(solid("barrier"), null), "启发式路径也必须判非遮挡");
    assertFalse(OcclusionRules.isOccluding(solid("barrier"), NO_OVERRIDE, NO_OVERRIDE, null));
  }

  /**
   * 状态相关方块：结论必须来自<b>逐状态真值</b>，而不是方块名——同一名字（{@code oak_slab}）的单台阶与
   * 双台阶结论相反。这正是「Material 级近似」做不到的一点；该近似现在只剩<b>补丁路径拿不到状态 id 时的
   * 回落</b>（补丁默认已按全局 id 逐状态判定，见 {@code OcclusionPatchListener}）。
   */
  @Test
  void stateDependentVerdictsComeFromTruthNotFromTheBlockName() {
    assertFalse(OcclusionRules.visualVerdict("oak_slab", false), "单台阶：真值非遮挡");
    assertTrue(OcclusionRules.visualVerdict("oak_slab", true), "双台阶：同一名字、真值遮挡");
  }

  /**
   * 「视觉口径」与「服务端原始逐状态真值」的差异<b>只应来自 barrier 例外</b>。
   *
   * <p>这是显形路径改造的安全前提：把显形从「原始真值」（barrier 算遮挡 → 更少显形）改为「视觉口径」
   * 后，唯一的结论变化就是 barrier（对玩家不可见 → 其后方方块得以显形，属正确体验而非泄露）。
   * 其余任何方块、任何状态都不得出现分歧。
   */
  @Test
  void visualVerdictDiffersFromRawServerVerdictOnlyOnBarrier() {
    String[] names = {"barrier", "stone", "deepslate", "glass", "oak_slab", "oak_fence", "air", "water"};
    for (String name : names) {
      for (boolean raw : new boolean[] {true, false}) {
        boolean visual = OcclusionRules.visualVerdict(name, raw);
        if (OcclusionRules.visuallyInvisibleOccluder(name) && raw) {
          assertFalse(visual, name + "：服务端真值标为遮挡，但视觉口径必须判非遮挡（唯一差异点）");
        } else {
          assertEquals(raw, visual,
              name + "（真值 " + raw + "）：非 barrier 情形下视觉口径必须等于服务端原始真值");
        }
      }
    }
  }

  /**
   * <b>同步射线 / 异步位图 / 显形</b>三条路径对同一批状态结论一致（都回答同一个视觉问题）。
   *
   * <p>离线建模：位图口径用真实的 {@link EntityOcclusionTable}（下标即状态 id，按 {@code baseVerdict}
   * 建位——与 {@code BlockStateRegistry} 对实体口径的建法一致）；同步射线与显形口径用
   * {@link OcclusionRules#visualVerdict}（生产分别为 {@code BukkitOcclusion#visualOccluding} 的
   * {@code EntityCuller} / {@code ProximityRevealer} 调用点）。三者在每个状态上必须相等；
   * barrier 在三者下都判非遮挡（其后方方块可见 → 显形）。
   */
  @Test
  void syncAsyncAndRevealAgreeOnEveryState() {
    String[] names = {"stone", "deepslate", "glass", "barrier", "oak_slab"};
    boolean[] truths = {true, true, false, true, true}; // 逐状态真值（oak_slab 这里代表双台阶）

    BitSet entityOccluding = new BitSet();
    for (int id = 0; id < names.length; id++) {
      if (OcclusionRules.baseVerdict(solid(names[id]), truths[id])) {
        entityOccluding.set(id);
      }
    }
    EntityOcclusionTable table = EntityOcclusionTable.build(entityOccluding);

    for (int id = 0; id < names.length; id++) {
      boolean bitmap = table.isOccluding(id);                               // 异步位图口径
      boolean syncOrReveal = OcclusionRules.visualVerdict(names[id], truths[id]); // 同步射线 = 显形口径
      assertEquals(syncOrReveal, bitmap,
          names[id] + "：同步射线 / 显形 与 异步位图 的口径必须一致");
    }
    assertFalse(table.isOccluding(3), "位图：barrier（真值遮挡）在实体口径下必须判非遮挡");
    assertFalse(OcclusionRules.visualVerdict("barrier", true),
        "同步射线 / 显形：barrier 同样判非遮挡 → 其后方矿石能显形（体验正确，不泄露）");
  }
}