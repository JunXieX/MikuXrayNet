package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 事件驱动即时显形（P1-4）的纯数据部分：曼哈顿半径判定、偏移表、每 tick 限额、
 * 「仍在伪装清单 + 未显形过」的候选判定与触发初筛。
 *
 * <p>显形链路本身（射线、发包、标记）复用既有 {@link ProximityRevealer#sendOne}，由真机验证；
 * 这里验证的是「触发时机」新增逻辑的判定正确性：命中触发、超半径不触发、限额生效、与周期不重复。
 */
class InstantRevealTest {

  private static final String WORLD = "world";

  /** 默认配置构造显形器（事件显形默认开启）；离线构造只需纯数据依赖，Bukkit 组件传 null。 */
  private ProximityRevealer revealer() {
    return new ProximityRevealer(null, null, config(), new ObfuscatedChunkIndex(1, 300),
        new RevealedSet(1024, 300), new ProximityStats(), null, null);
  }

  private static AntiXrayConfig config() {
    return AntiXrayConfig.from(yaml("enabled: true\n"));
  }

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException(exception);
    }
    return configuration;
  }

  // ---------------------------------------------------------- 半径判定与偏移表

  /** 变更命中玩家身边（曼哈顿 ≤ radius）才触发；恰好等于半径也算命中。 */
  @Test
  void changeWithinManhattanRadiusTriggers() {
    assertTrue(ProximityRevealer.withinManhattanRadius(0, 0, 0, 2, 0, 0, 2),
        "曼哈顿距离 2 = 半径：必须命中");
    assertTrue(ProximityRevealer.withinManhattanRadius(0, 0, 0, 1, 1, 0, 2),
        "曼哈顿距离 2：必须命中");
    assertFalse(ProximityRevealer.withinManhattanRadius(0, 0, 0, 1, 1, 1, 2),
        "曼哈顿距离 3 > 半径 2：不得触发");
    assertFalse(ProximityRevealer.withinManhattanRadius(0, 0, 0, 3, 0, 0, 2),
        "曼哈顿距离 3：不得触发");
  }

  /** 偏移表覆盖半径内全部偏移（含原点）、无重复、按距离由近到远排序；半径被钳制到 1~8。 */
  @Test
  void offsetTableCoversSphereAndIsSortedByDistance() {
    int[][] offsets = ProximityRevealer.instantOffsets(2);
    int expected = 0;
    for (int dx = -2; dx <= 2; dx++) {
      for (int dy = -2; dy <= 2; dy++) {
        for (int dz = -2; dz <= 2; dz++) {
          if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2) {
            expected++;
          }
        }
      }
    }
    assertEquals(expected, offsets.length, "偏移表必须覆盖曼哈顿半径内的全部坐标（含原点）");

    Set<Long> unique = new HashSet<>();
    int lastDistance = -1;
    for (int[] offset : offsets) {
      int distance = Math.abs(offset[0]) + Math.abs(offset[1]) + Math.abs(offset[2]);
      assertTrue(distance <= 2, "偏移不得超出半径");
      assertTrue(unique.add(((long) offset[0] << 32) ^ (offset[1] << 16) ^ offset[2]),
          "偏移不得重复");
      assertTrue(distance >= lastDistance, "偏移必须按距离由近到远排序（限额不足时优先近处）");
      lastDistance = distance;
    }
    assertEquals(0, offsets[0][0] + offsets[0][1] + offsets[0][2], "最近的偏移必须是原点（变更点自身）");

    assertEquals(7, ProximityRevealer.instantOffsets(1).length, "半径 1 = 原点 + 6 个正交邻居");
    assertEquals(ProximityRevealer.instantOffsets(8).length,
        ProximityRevealer.instantOffsets(99).length, "半径超限必须钳制到 8");
  }

  // ---------------------------------------------------------- 触发初筛与候选判定

  /** 变更邻域内有伪装坐标 → 初筛命中；邻域内没有（或变更太远）→ 不命中。 */
  @Test
  void triggerFiresOnlyWhenDisguisedPositionWithinRadius() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1024, 300);
    // 伪装坐标 (10, 64, 10)（minHeight=0 → 相对 y = 64）
    index.recordChunk(WORLD, 0, 0, 0, new int[] {64 << 8 | 10 << 4 | 10});

    assertTrue(ProximityRevealer.hasDisguisedNearby(index, WORLD, 10, 66, 10, 2),
        "变更 (10,66,10) 与伪装坐标曼哈顿距离 2 ≤ 半径：必须命中");
    assertTrue(ProximityRevealer.hasDisguisedNearby(index, WORLD, 10, 64, 10, 2),
        "变更点即伪装坐标自身：必须命中");
    assertFalse(ProximityRevealer.hasDisguisedNearby(index, WORLD, 10, 67, 10, 2),
        "变更 (10,67,10) 距伪装坐标 3 > 半径 2：不得命中");
    assertFalse(ProximityRevealer.hasDisguisedNearby(index, WORLD, 200, 64, 200, 2),
        "变更远在其它区块：不得命中");
    assertFalse(ProximityRevealer.hasDisguisedNearby(index, "other", 10, 64, 10, 2),
        "世界不同：不得命中");
  }

  /**
   * 候选判定：「仍在伪装清单（已伪装）」且「该玩家尚未显形过（与周期显形不重复）」。
   * 注销 / 标记后判定翻转，保证同一坐标当 tick 只发一次。
   */
  @Test
  void candidateRequiresDisguisedAndNotYetRevealed() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(1024, 300);
    RevealedSet revealed = new RevealedSet(1024, 300);
    index.recordChunk(WORLD, 0, 0, 0, new int[] {64 << 8 | 10 << 4 | 10});

    UUID player = UUID.randomUUID();
    ProximityRevealer revealer = new ProximityRevealer(null, null, config(), index, revealed,
        new ProximityStats(), null, null);

    assertTrue(revealer.isInstantCandidate(player, WORLD, 10, 64, 10),
        "已伪装且未显形：是即时显形候选");

    revealed.mark(player, ChunkKey.ofBlock(WORLD, 10, 10), 10, 64, 10);
    assertFalse(revealer.isInstantCandidate(player, WORLD, 10, 64, 10),
        "该玩家已显形过：不得重复（与周期显形不重复的保证）");

    UUID other = UUID.randomUUID();
    assertTrue(revealer.isInstantCandidate(other, WORLD, 10, 64, 10),
        "已显形按玩家记录：其它玩家仍需显形");

    index.removePosition(WORLD, 10, 64, 10);
    assertFalse(revealer.isInstantCandidate(other, WORLD, 10, 64, 10),
        "服务端已下发变更（已注销）：不再是伪装坐标，无需显形");
  }

  // ---------------------------------------------------------- 每 tick 限额

  /** 限额按「每玩家每 tick」生效：扣减后保持，tick 推进自动回满，退出精确清理。 */
  @Test
  void tickQuotaResetsPerTickAndPerPlayer() {
    ProximityRevealer.TickQuota quota = new ProximityRevealer.TickQuota();
    UUID player = UUID.randomUUID();
    UUID other = UUID.randomUUID();

    assertEquals(16, quota.remaining(player, 100L, 16), "本 tick 未使用：满额");
    quota.setRemaining(player, 100L, 5);
    assertEquals(5, quota.remaining(player, 100L, 16), "扣减后保持剩余额度");
    assertEquals(16, quota.remaining(other, 100L, 16), "限额按玩家隔离");

    quota.setRemaining(player, 100L, 0);
    assertEquals(0, quota.remaining(player, 100L, 16), "额度用尽后本 tick 不再显形（限额生效）");
    assertEquals(16, quota.remaining(player, 101L, 16), "下一 tick 自动回满（周期兜底继续显形）");

    quota.clear(player);
    assertEquals(16, quota.remaining(player, 101L, 16), "退出清理后按满额对待（新玩家语义）");
  }

  /** max-per-tick = 0（配置关闭事件显形）时 remaining 恒为 0，调用方直接放弃。 */
  @Test
  void zeroMaxPerTickDisablesQuota() {
    ProximityRevealer.TickQuota quota = new ProximityRevealer.TickQuota();
    UUID player = UUID.randomUUID();
    assertEquals(0, quota.remaining(player, 7L, 0), "限额为 0：本 tick 没有可用额度");
  }
}
