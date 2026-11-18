package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.List;
import net.mikumc.mikuxraynet.antixray.SectionCoords;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import org.junit.jupiter.api.Test;

/**
 * 「成对方块不得被合并成一条」的守门用例。
 *
 * <p>门/床/双箱这类结构由两个相邻坐标组成，两者只差一格。合并链路有三处可能把它们压成一条：
 * 聚簇（邻域合并）、按 section 分组（{@code >> 4}）、段内打包（{@code (x&15)<<8 | (z&15)<<4 | (y&15)}）。
 * 任一处错，客户端就只会收到一片更新，另一片保持旧状态并继续阻挡玩家——现场表现正是「门只开一半」。
 *
 * <p>这条用例把三处一起钉住：无论聚簇半径取多少，两条坐标都必须原样保留且区分得开。
 */
class BlockChangeBatchDoorPairTest {

  private static Update<WrappedBlockData> update(int x, int y, int z) {
    return new Update<>(x, y, z, null);
  }

  private static int total(List<List<Update<WrappedBlockData>>> clusters) {
    int count = 0;
    for (List<Update<WrappedBlockData>> cluster : clusters) {
      count += cluster.size();
    }
    return count;
  }

  @Test
  void bothHalvesSurviveClusteringAtAnyRadius() {
    for (int radius = 0; radius <= 8; radius++) {
      BlockChangeBatch<WrappedBlockData> batch = new BlockChangeBatch<>(256);
      batch.add(update(100, 64, -200));
      batch.add(update(100, 65, -200));
      List<List<Update<WrappedBlockData>>> clusters = batch.drainClusters(radius);
      assertEquals(2, total(clusters), "聚簇半径 " + radius + " 下两片门都必须保留");
      boolean lower = false;
      boolean upper = false;
      for (List<Update<WrappedBlockData>> cluster : clusters) {
        for (Update<WrappedBlockData> one : cluster) {
          if (one.y() == 64) {
            lower = true;
          }
          if (one.y() == 65) {
            upper = true;
          }
        }
      }
      assertTrue(lower, "下半必须保留（半径 " + radius + "）");
      assertTrue(upper, "上半必须保留（半径 " + radius + "）");
    }
  }

  @Test
  void emptyRadiusSplitsPairIntoTwoClustersInsteadOfDroppingOne() {
    BlockChangeBatch<WrappedBlockData> batch = new BlockChangeBatch<>(256);
    batch.add(update(0, 10, 0));
    batch.add(update(0, 11, 0));
    List<List<Update<WrappedBlockData>>> clusters = batch.drainClusters(0);
    assertEquals(2, clusters.size(), "半径 0 时相邻两格不合并，必须各自成簇（而不是丢一条）");
    assertEquals(2, total(clusters));
  }

  @Test
  void halvesInSameSectionShareSectionKeyButKeepDistinctPackedCoordinates() {
    int x = 100;
    int z = -200;
    // 同一 section 内（y 与 y+1 的 >>4 相同，除非恰好跨段边界）
    int lowerY = 64;
    int upperY = 65;
    assertEquals(lowerY >> 4, upperY >> 4, "默认取样的两半应落在同一段内");
    int lowerPacked = SectionCoords.pack(x, lowerY, z);
    int upperPacked = SectionCoords.pack(x, upperY, z);
    assertNotEquals(lowerPacked, upperPacked, "段内打包必须区分 y 与 y+1");
    assertEquals(0, SectionCoords.y(lowerPacked), "64 的段内 y 为 0");
    assertEquals(1, SectionCoords.y(upperPacked), "65 的段内 y 为 1");
  }

  @Test
  void halvesAcrossSectionBoundaryStillEncodeDistinctly() {
    // y=15 与 y=16 属于不同 section；分组由 >>4 完成，各自成段、各自打包
    int lowerPacked = SectionCoords.pack(3, 15, 4);
    int upperPacked = SectionCoords.pack(3, 16, 4);
    assertNotEquals(15 >> 4, 16 >> 4, "跨段：分组键不同");
    assertNotEquals(lowerPacked, upperPacked);
    assertEquals(15, SectionCoords.y(lowerPacked), "y=15 的段内 y 仍为 15");
    assertEquals(0, SectionCoords.y(upperPacked), "y=16 落在下一段，段内 y=0");
  }
}
