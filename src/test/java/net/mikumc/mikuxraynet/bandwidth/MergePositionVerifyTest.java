package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.ArrayList;
import java.util.List;
import net.mikumc.mikuxraynet.antixray.SectionCoords;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import org.junit.jupiter.api.Test;

/**
 * 合并包坐标内容校验的守门用例。
 *
 * <p>旧实现只校验数组<b>长度</b>：两片门被压成同一个 packed short、或坐标与意图错位一位，长度照样相等，
 * 合并包照常发出——客户端只应用其中一片，另一半保持旧状态并继续阻挡玩家。这里把「内容必须逐项对上」
 * 钉死，并覆盖三种真实故障形态。
 */
class MergePositionVerifyTest {

  /** 只用来占位载荷：真正的 {@code WrappedBlockData} 在离线环境里造不出来，校验只关心坐标。 */
  private static Update<WrappedBlockData> update(int x, int y, int z) {
    return new Update<>(x, y, z, null);
  }

  private static short[] packed(List<Update<WrappedBlockData>> updates) {
    short[] positions = new short[updates.size()];
    for (int i = 0; i < updates.size(); i++) {
      Update<WrappedBlockData> update = updates.get(i);
      positions[i] = (short) SectionCoords.pack(update.x(), update.y(), update.z());
    }
    return positions;
  }

  @Test
  void doorHalvesSurviveAsTwoDistinctPositions() {
    // 同一根柱子上的下半与上半：只差 y，段内编码必须不同
    List<Update<WrappedBlockData>> intended = List.of(update(100, 64, -200), update(100, 65, -200));
    short[] written = packed(intended);
    assertTrue(written[0] != written[1], "上下两半必须编成不同的 packed short");
    assertTrue(BlockChangeMerger.verifyPositions(written, intended), "正常写入必须通过校验");
  }

  @Test
  void twoCoordinatesCollapsedIntoOnePackedShortIsRejected() {
    List<Update<WrappedBlockData>> intended = List.of(update(100, 64, -200), update(100, 65, -200));
    short[] written = {
        (short) SectionCoords.pack(100, 64, -200), (short) SectionCoords.pack(100, 64, -200)};
    assertFalse(BlockChangeMerger.verifyPositions(written, intended),
        "两个坐标被压成同一条时必须判失败（另一片永远收不到更新）");
  }

  @Test
  void lengthMismatchIsRejected() {
    List<Update<WrappedBlockData>> intended = List.of(update(1, 2, 3), update(4, 5, 6));
    short[] written = {(short) SectionCoords.pack(1, 2, 3)};
    assertFalse(BlockChangeMerger.verifyPositions(written, intended));
    assertFalse(BlockChangeMerger.verifyPositions(new short[0], intended));
    assertFalse(BlockChangeMerger.verifyPositions(null, intended));
    assertFalse(BlockChangeMerger.verifyPositions(written, null));
  }

  @Test
  void shiftedOrderIsRejected() {
    List<Update<WrappedBlockData>> intended = List.of(update(1, 2, 3), update(4, 5, 6));
    short[] written = {(short) SectionCoords.pack(4, 5, 6), (short) SectionCoords.pack(1, 2, 3)};
    assertFalse(BlockChangeMerger.verifyPositions(written, intended), "顺序错位同样属于内容不符");
  }

  @Test
  void duplicatesAreRejectedWithinScanLimit() {
    // 段内坐标只有 4 位：用 (x,y,z) 的低 4 位铺满 512 个互不相同的段内位置，
    // 避免「同一坐标绕回」把正常包误判成重复（那属于造数错误，不是被测行为）。
    List<Update<WrappedBlockData>> intended = new ArrayList<>();
    for (int i = 0; i < BlockChangeMerger.DUPLICATE_SCAN_LIMIT; i++) {
      intended.add(update(i & 15, (i >> 4) & 15, i >> 8));
    }
    short[] written = packed(intended);
    assertTrue(BlockChangeMerger.verifyPositions(written, intended), "小包内无重复时必须通过");
    // 把最后一条改成与第一条相同：仍在扫描范围内 → 必须判失败
    written[written.length - 1] = written[0];
    intended.set(intended.size() - 1, intended.get(0));
    assertFalse(BlockChangeMerger.verifyPositions(written, intended));
  }
}
