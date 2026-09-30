package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.List;
import java.util.Set;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 方块变更合并的「last-write-wins」纯逻辑回归：窗口内有坐标经「立即放行」先行下发时，
 * 合并包不得再把同坐标的旧状态写回去（否则旧态晚到会把先到的新态覆盖，方块短暂回退）。
 *
 * <p>真实链路依赖 ProtocolLib 的封包对象与异步放行，离线不可用；这里直接驱动被抽出的纯函数
 * {@link BlockChangeMerger#dropPassed}。
 */
class BlockChangeMergerTest {

  private static Update<WrappedBlockData> at(int x, int y, int z) {
    return new Update<>(x, y, z, null);
  }

  @Test
  @DisplayName("已立即放行的坐标从各簇中剔除，其余坐标原样保留")
  void dropPassedRemovesOnlyAlreadyDeliveredCoordinates() {
    Update<WrappedBlockData> stale = at(1, 64, 1);
    Update<WrappedBlockData> fresh = at(2, 64, 2);
    List<List<Update<WrappedBlockData>>> clusters = List.of(List.of(stale, fresh));

    List<List<Update<WrappedBlockData>>> survivors =
        BlockChangeMerger.dropPassed(clusters, Set.of(new BlockChangeMerger.Coord(1, 64, 1)));

    assertEquals(1, survivors.size(), "簇数量保持不变（只是成员被剔除）");
    assertEquals(List.of(fresh), survivors.get(0), "只有未被先行下发的坐标留下");
  }

  @Test
  @DisplayName("整簇都已被立即放行时，该簇清空（调用方据此跳过发送、取消原包）")
  void dropPassedEmptiesClusterWhenAllCoordinatesDelivered() {
    List<List<Update<WrappedBlockData>>> clusters =
        List.of(List.of(at(0, 5, 0)), List.of(at(9, 5, 9)));

    List<List<Update<WrappedBlockData>>> survivors = BlockChangeMerger.dropPassed(clusters,
        Set.of(new BlockChangeMerger.Coord(0, 5, 0), new BlockChangeMerger.Coord(9, 5, 9)));

    assertEquals(2, survivors.size());
    assertTrue(survivors.get(0).isEmpty(), "第一条簇的坐标都已先行下发 → 不再写入合并包");
    assertTrue(survivors.get(1).isEmpty(), "第二条簇同理");
  }

  @Test
  @DisplayName("坐标为负/不同 y 的方块不会被误删（仅按三元坐标精确匹配）")
  void dropPassedMatchesCoordinatesExactly() {
    Update<WrappedBlockData> update = at(-3, 70, -8);
    List<List<Update<WrappedBlockData>>> clusters = List.of(List.of(update));

    List<List<Update<WrappedBlockData>>> survivors =
        BlockChangeMerger.dropPassed(clusters, Set.of(new BlockChangeMerger.Coord(-3, 69, -8)));

    assertEquals(List.of(update), survivors.get(0), "y 不同不得误删");
  }

  /**
   * fail-open 放行（合并包构造/发送失败）时：只有当原包携带的坐标<b>全部</b>已由「立即放行」先行下发，
   * 才应取消该原包——否则旧态晚到会把先到的新态覆盖回去（方块回退）。这正是任务 7 的判据。
   */
  @Test
  @DisplayName("fail-open 时：已全部立即放行的原包应取消，混合/未放行的必须照常放行")
  void failOpenCancelsOnlyFullyPassedOriginals() {
    Set<BlockChangeMerger.Coord> passed = Set.of(new BlockChangeMerger.Coord(1, 64, 1));

    assertTrue(BlockChangeMerger.allCoordsPassed(
            List.of(new BlockChangeMerger.Coord(1, 64, 1)), passed),
        "原包只带已先行下发的坐标 → 取消（否则旧态覆盖新态）");
    assertFalse(BlockChangeMerger.allCoordsPassed(
            List.of(new BlockChangeMerger.Coord(1, 64, 1), new BlockChangeMerger.Coord(2, 64, 2)), passed),
        "跨坐标混合包：不能取消，否则会丢掉未放行坐标的更新");
    assertFalse(BlockChangeMerger.allCoordsPassed(
            List.of(new BlockChangeMerger.Coord(2, 64, 2)), passed),
        "完全未放行的原包必须照常放行");
    assertFalse(BlockChangeMerger.allCoordsPassed(List.of(), passed),
        "空清单保守放行（不可取消）");
    assertFalse(BlockChangeMerger.allCoordsPassed(List.of(new BlockChangeMerger.Coord(1, 64, 1)),
            Set.of()),
        "本窗口没有立即放行记录时不存在可取消的原包");
  }
}