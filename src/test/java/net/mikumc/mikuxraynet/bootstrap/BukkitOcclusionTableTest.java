package net.mikumc.mikuxraynet.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import net.mikumc.mikuxraynet.registry.OcclusionRules;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

/**
 * {@link BukkitOcclusion.MaterialTable} 的等价性测试：预计算真值表必须与逐格调用
 * {@link OcclusionRules#camouflageVerdict} 的旧口径<b>逐值相同</b>。
 *
 * <p><b>为什么用「遍历全部 Material × 真值两向」做对照</b>：这张表唯一的正确性要求就是「与规则同口径」——
 * 规则只吃「方块名 + 该状态的逐状态真值」两个输入，{@code Material} 枚举穷举了第一个输入的全部取值，
 * 布尔两向穷举了第二个。逐材料对照因此等价于全量覆盖：覆盖优先级、barrier/岩浆例外、名字归一化任何一处
 * 漂移都会被立刻钉住。
 *
 * <p><b>这些用例同时证明「没有退回 Material#isOccluding()」</b>：建表与查表只用到
 * {@link Material#values()} / {@link Material#name()} / {@link Material#ordinal()}；而
 * {@code Material#isOccluding()} 需要服务端注册表（{@code Registry.BLOCK}），在无服务端的单测 JVM 里不可用
 * ——真要有哪条路径调它，本类会直接抛错，而不是给出一个「看起来对」的结论。
 */
class BukkitOcclusionTableTest {

  private static final Set<String> NO_OVERRIDE = Set.of();

  /** 生产配置（两张覆盖表都空）下，表与规则必须逐材料、逐真值相同。 */
  @Test
  void matchesPerCellRuleForEveryMaterialAndBothTruthValues() {
    BukkitOcclusion.MaterialTable table = BukkitOcclusion.MaterialTable.build(null, null);

    for (Material material : Material.values()) {
      for (boolean truth : new boolean[] {true, false}) {
        assertEquals(
            OcclusionRules.camouflageVerdict(material.name(), truth, NO_OVERRIDE, NO_OVERRIDE),
            table.occluding(material, truth),
            material + "（逐状态真值 " + truth + "）：表必须与逐格调规则得出同一结论");
      }
    }
  }

  /** 覆盖表非空（含两表冲突项）时，仍必须逐材料、逐真值等于规则——覆盖优先级不能被建表顺序改掉。 */
  @Test
  void matchesPerCellRuleWhenOverrideTablesConflict() {
    Set<String> extraOccluding =
        Set.of("stone", "minecraft:BARRIER", "oak_slab", "lava", "diamond_ore");
    Set<String> extraNonOccluding = Set.of("stone", "OAK_SLAB", "water", "lava", "oak_fence");
    BukkitOcclusion.MaterialTable table =
        BukkitOcclusion.MaterialTable.build(extraOccluding, extraNonOccluding);

    Set<String> normalizedOccluding = OcclusionRules.normalizeAll(extraOccluding);
    Set<String> normalizedNonOccluding = OcclusionRules.normalizeAll(extraNonOccluding);
    for (Material material : Material.values()) {
      for (boolean truth : new boolean[] {true, false}) {
        assertEquals(
            OcclusionRules.camouflageVerdict(material.name(), truth, normalizedOccluding,
                normalizedNonOccluding),
            table.occluding(material, truth),
            material + "（逐状态真值 " + truth + "）：非空覆盖表下也必须与规则逐值一致");
      }
    }

    // 两表冲突：「不遮挡」优先（与 OcclusionRules 的既定优先级一致）
    assertFalse(table.occluding(Material.STONE, true), "stone 同时在两张表里 → 不遮挡优先");
    assertFalse(table.occluding(Material.OAK_SLAB, true), "oak_slab 冲突 → 不遮挡优先");
    // 覆盖项已归一化：带命名空间/大写的写法同样生效（且能压过 barrier 例外）
    assertTrue(table.occluding(Material.BARRIER, true));
    // 未覆盖的方块仍按视觉口径：岩浆真值非遮挡，但视觉上不透明 → 遮挡
    assertTrue(table.occluding(Material.LAVA, false));
  }

  /** 典型方块的既有口径（石头、单双台阶、水、岩浆、屏障、箱子）逐条钉死。 */
  @Test
  void typicalBlocksKeepTheirOldVerdicts() {
    BukkitOcclusion.MaterialTable table = BukkitOcclusion.MaterialTable.build(NO_OVERRIDE, NO_OVERRIDE);

    assertTrue(table.occluding(Material.STONE, true), "石头遮挡");
    assertFalse(table.occluding(Material.STONE, false), "服务端说非遮挡时以真值为准");

    assertFalse(table.occluding(Material.OAK_SLAB, false), "单台阶：真值非遮挡");
    assertTrue(table.occluding(Material.OAK_SLAB, true), "双台阶：同名方块的另一状态，真值遮挡");
    assertFalse(table.occluding(Material.STONE_SLAB, false));
    assertTrue(table.occluding(Material.STONE_SLAB, true));

    assertFalse(table.occluding(Material.WATER, false), "水透明，不遮挡");
    assertTrue(table.occluding(Material.LAVA, false), "岩浆视觉不透明 → 遮挡（唯一「真值非遮挡」的例外）");
    assertFalse(table.occluding(Material.BARRIER, true), "屏障视觉不可见 → 非遮挡（唯一「真值遮挡」的例外）");

    assertTrue(table.occluding(Material.CHEST, true), "箱子：以真值为准，不再被名字白名单误判");
    assertFalse(table.occluding(Material.CHEST, false));
  }

  /** 逐格入口读的是<b>该状态</b>的真值，不是 Material 级默认状态。 */
  @Test
  void camouflageReadsTheActualStateTruth() {
    BukkitOcclusion.MaterialTable table = BukkitOcclusion.MaterialTable.build(NO_OVERRIDE, NO_OVERRIDE);

    assertFalse(table.camouflage(state(Material.OAK_SLAB, false)), "单台阶状态");
    assertTrue(table.camouflage(state(Material.OAK_SLAB, true)),
        "同一方块（oak_slab）的双台阶状态必须判遮挡：Material 级近似做不到这一点");
    assertTrue(table.camouflage(state(Material.LAVA, false)));
    assertFalse(table.camouflage(state(Material.BARRIER, true)));
  }

  /** {@code null} 状态按「不遮挡」处理（与旧的逐格入口同口径）。 */
  @Test
  void nullStateIsNotOccluding() {
    BukkitOcclusion.MaterialTable table = BukkitOcclusion.MaterialTable.build(NO_OVERRIDE, NO_OVERRIDE);
    assertFalse(table.camouflage(null));
  }

  /** 建表时对入参做快照：之后改原集合不得影响已建好的表（否则表会随配置对象漂移）。 */
  @Test
  void overrideSetsAreSnapshottedAtBuildTime() {
    Set<String> extraOccluding = new HashSet<>(Set.of("barrier"));
    BukkitOcclusion.MaterialTable table = BukkitOcclusion.MaterialTable.build(extraOccluding, null);

    extraOccluding.add("stone");
    assertTrue(table.occluding(Material.BARRIER, true), "构建时的覆盖项已生效（压过 barrier 例外）");
    assertFalse(table.occluding(Material.STONE, false),
        "构建之后再加的覆盖项不得影响已建表（配置改动要生效必须重建表）");
  }

  /** 最小 {@link BlockData} 桩：离线没有服务端注册表，只实现表要读的两个方法。 */
  private static BlockData state(Material material, boolean serverOccluding) {
    return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
        new Class<?>[] {BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getMaterial" -> material;
          case "isOccluding" -> serverOccluding;
          case "toString" -> material + "[" + serverOccluding + "]";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> args != null && proxy == args[0];
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
