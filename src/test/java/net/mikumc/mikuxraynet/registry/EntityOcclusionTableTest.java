package net.mikumc.mikuxraynet.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link EntityOcclusionTable} 的分口径语义测试：Bukkit 权威口径优先、名称缺失时回落注册表口径。
 *
 * <p>本类是平台无关的纯逻辑（Bukkit 判定表由调用方以 {@code Map} 传入），因此可以离线钉死——
 * 而「真机上这张表是否与 {@code Material#isOccluding()} 逐方块一致」由启动期日志对照守门
 * （离线环境连 {@code Material} 都初始化不了）。
 */
class EntityOcclusionTableTest {

  /** 状态 id 0..3 的方块名；id 2 是空洞（无状态）。 */
  private static final String[] NAMES = {"stone", "glass", null, "anvil"};

  @Test
  void bukkitVerdictWinsOverRegistryVerdictInBothDirections() {
    BitSet registryOccluding = new BitSet(NAMES.length);
    registryOccluding.set(0); // 注册表说 stone 遮挡
    registryOccluding.set(1); // 注册表说 glass 遮挡（反矿透口径偏多判的典型）
    registryOccluding.set(3); // 注册表说 anvil 遮挡

    Map<String, Boolean> bukkit = Map.of(
        "stone", true,
        "glass", false,   // Bukkit 说玻璃不遮挡 → 应以 Bukkit 为准（改判为不遮挡）
        "anvil", false);  // Bukkit 说铁砧不遮挡 → 同上（真机实测两者相反）
    EntityOcclusionTable table = EntityOcclusionTable.build(NAMES, registryOccluding, bukkit);

    assertTrue(table.isOccluding(0), "两边都说遮挡 → 遮挡");
    assertFalse(table.isOccluding(1), "Bukkit 说不遮挡时必须以 Bukkit 为准（少判才是实体剔除的安全方向）");
    assertFalse(table.isOccluding(3), "真机差异项（anvil）：实体位图必须采用 Bukkit 口径");
    assertEquals(1, table.occludingStateCount());
    assertEquals(3, table.alignedCount(), "命中 Bukkit 表的状态数（不含空洞 id）");
    assertEquals(0, table.fallbackCount());
  }

  /** 反向：注册表说不遮挡、Bukkit 说遮挡时同样以 Bukkit 为准（barrier 就是这一类）。 */
  @Test
  void bukkitVerdictAlsoWinsWhenItSaysOccluding() {
    BitSet registryOccluding = new BitSet(NAMES.length);

    EntityOcclusionTable table =
        EntityOcclusionTable.build(NAMES, registryOccluding, Map.of("stone", true));

    assertTrue(table.isOccluding(0), "注册表说不遮挡、Bukkit 说遮挡 → 以 Bukkit 为准");
    assertFalse(table.isOccluding(3), "名称未命中 Bukkit 表 → 回落注册表口径（此处为不遮挡）");
  }

  /** 名称未命中 Bukkit 表（PacketEvents 已认识、而服务端 Material 尚无的新方块）→ 回落注册表口径并计数。 */
  @Test
  void unknownBlockNameFallsBackToRegistryVerdictAndIsCounted() {
    BitSet registryOccluding = new BitSet(NAMES.length);
    registryOccluding.set(3); // 只有 anvil 在注册表里是遮挡

    EntityOcclusionTable table = EntityOcclusionTable.build(NAMES, registryOccluding,
        Map.of("stone", true, "glass", false));

    assertTrue(table.isOccluding(3), "未命中 Bukkit 表时必须回落注册表口径，而不是一律判不遮挡");
    assertFalse(table.isOccluding(1));
    assertEquals(2, table.alignedCount());
    assertEquals(1, table.fallbackCount(), "回落数必须可观测（否则口径静默分叉）");
  }

  /** 空洞 id（无方块名）不参与任何判定，也不计入对齐/回落统计。 */
  @Test
  void holeIdIsIgnoredEntirely() {
    BitSet registryOccluding = new BitSet(NAMES.length);
    registryOccluding.set(2); // 即便注册表口径把空洞 id 标了遮挡，也不应采用

    EntityOcclusionTable table = EntityOcclusionTable.build(NAMES, registryOccluding,
        Map.of("stone", true, "glass", false, "anvil", true));

    assertFalse(table.isOccluding(2), "空洞 id 不是有效状态，绝不能被判为遮挡");
    assertEquals(3, table.alignedCount(), "空洞 id 不参与统计");
    assertEquals(0, table.fallbackCount());
  }

  /** 没有 Bukkit 表（离线 / Bukkit 表不可用）→ 全表回落注册表口径，且回落数如实可观测。 */
  @Test
  void nullBukkitTableFallsBackEntirely() {
    BitSet registryOccluding = new BitSet(NAMES.length);
    registryOccluding.set(0);
    registryOccluding.set(1);

    EntityOcclusionTable table = EntityOcclusionTable.build(NAMES, registryOccluding, null);

    assertTrue(table.isOccluding(0));
    assertTrue(table.isOccluding(1));
    assertFalse(table.isOccluding(3));
    assertEquals(0, table.alignedCount());
    assertEquals(3, table.fallbackCount());
  }

  /** 不做实体口径表时返回 {@code null}（调用方据此回落注册表口径，零额外内存）。 */
  @Test
  void nullNamesMeansNoTable() {
    assertNull(EntityOcclusionTable.build(null, new BitSet(), Map.of()),
        "未提供方块名表时不应构建实体口径表");
  }

  /** 越界 id 一律按「不遮挡」处理（少判 = 实体剔除的安全方向），绝不抛异常。 */
  @Test
  void outOfRangeIdIsNotOccluding() {
    EntityOcclusionTable table =
        EntityOcclusionTable.build(NAMES, new BitSet(), Map.of("stone", true));

    assertFalse(table.isOccluding(-1));
    assertFalse(table.isOccluding(NAMES.length));
  }
}
