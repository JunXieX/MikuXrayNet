package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 过度显形量化（P2-6b）：抽样器按 1/N 抽样、开关行为，以及统计计数与中文快照口径。
 *
 * <p>口径（与生产实现一致）：显形发包前按 1/N 抽样；若 {@link RevealedSet} 已含该坐标
 * （客户端应已可见），该显形包按「过度」计数。只计数、不改任何发包行为。
 */
class OverRevealSamplingTest {

  /** 1/20 抽样：第 1 个命中，随后 19 个不命中，第 21 个再命中。 */
  @Test
  void samplesEveryTwentiethReveal() {
    ProximityRevealer.OverRevealSampler sampler = new ProximityRevealer.OverRevealSampler(20);

    assertTrue(sampler.sample(), "第 1 个显形包必须被抽样（无偏估计的起点）");
    for (int i = 2; i <= 20; i++) {
      assertFalse(sampler.sample(), "第 " + i + " 个显形包不得被抽样（1/20 抽样）");
    }
    assertTrue(sampler.sample(), "第 21 个显形包必须再次被抽样");
  }

  /** 开关：N=0 表示关闭统计，永不抽样；N=1 表示全量复核。 */
  @Test
  void rateZeroDisablesSamplingAndOneSamplesAll() {
    ProximityRevealer.OverRevealSampler disabled = new ProximityRevealer.OverRevealSampler(0);
    assertFalse(disabled.enabled(), "N=0 必须判定为关闭");
    for (int i = 0; i < 10; i++) {
      assertFalse(disabled.sample(), "关闭后任何显形包都不复核");
    }

    ProximityRevealer.OverRevealSampler all = new ProximityRevealer.OverRevealSampler(1);
    assertTrue(all.enabled(), "N=1 为全量复核");
    for (int i = 0; i < 10; i++) {
      assertTrue(all.sample(), "N=1 时每个显形包都要复核");
    }

    ProximityRevealer.OverRevealSampler negative = new ProximityRevealer.OverRevealSampler(-5);
    assertFalse(negative.enabled(), "负值按关闭处理（与配置钳制一致）");
  }

  /** 统计计数与中文快照：抽样数 / 过度数可累加，快照暴露「过度显形（抽样）」口径。 */
  @Test
  void statsExposeOverRevealCountersInChineseSnapshot() {
    ProximityStats stats = new ProximityStats();
    stats.overRevealSampled.add(40);
    stats.overRevealWasted.add(6);

    assertEquals(40L, stats.overRevealSampled.sum());
    assertEquals(6L, stats.overRevealWasted.sum());
    assertEquals(6L, stats.snapshot().get("过度显形（抽样）"),
        "快照必须用「过度显形（抽样）」口径暴露浪费数（与 status 文案一致）");
    assertEquals(40L, stats.snapshot().get("过度显形抽样数"), "快照必须同时暴露抽样分母");
  }

  /**
   * 端到端口径演练（不发包）：坐标一旦被标记已显形，「发包前比对 RevealedSet」即判过度；
   * 注销（服务端下发变更）后再次显形则不算过度——这正是周期/事件路径并发窗口里
   * 「重复显形」被量化的语义。
   */
  @Test
  void wasteDetectionFollowsRevealedSetMembership() {
    RevealedSet revealed = new RevealedSet(1024, 300);
    String world = "world";
    UUID player = UUID.randomUUID();
    ChunkKey key = ChunkKey.ofBlock(world, 10, 10);
    int x = 10;
    int y = 64;
    int z = 10;

    assertFalse(revealed.contains(player, key, x, y, z), "未显形过：显形包不算浪费");
    revealed.mark(player, key, x, y, z);
    assertTrue(revealed.contains(player, key, x, y, z),
        "已显形（未注销）时再发包 = 过度显形（抽样计数口径）");
    revealed.removePosition(world, x, y, z);
    assertFalse(revealed.contains(player, key, x, y, z),
        "服务端下发变更注销后：客户端重新看到伪装，再次显形不算浪费");
  }
}
