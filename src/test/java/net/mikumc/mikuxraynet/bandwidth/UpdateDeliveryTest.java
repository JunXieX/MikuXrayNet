package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import org.junit.jupiter.api.Test;

/**
 * 「逐条补发」最后一道防线的守门用例。
 *
 * <p>原包进入合并窗口时已被取消，合并包构造失败后只能靠逐条补发把它们送到客户端。旧的裸循环一旦在
 * 中途抛出，剩下的条目会一起消失，而面板上只留一条限流日志——现场就是「门开了这一半、那一半永远不动」。
 *
 * <p>这里把四条语义逐个钉死：<b>逐条互不影响</b>、<b>抛出与返回 false 都计失败</b>、
 * <b>方法自身永不抛出</b>、<b>成功条数准确</b>。
 */
class UpdateDeliveryTest {

  private static Update<WrappedBlockData> update(int x, int y, int z) {
    return new Update<>(x, y, z, null);
  }

  private static List<List<Update<WrappedBlockData>>> clusters(Update<WrappedBlockData>... updates) {
    List<List<Update<WrappedBlockData>>> clusters = new ArrayList<>();
    for (Update<WrappedBlockData> update : updates) {
      clusters.add(new ArrayList<>(List.of(update)));
    }
    return clusters;
  }

  @Test
  void oneThrowingUpdateDoesNotStopTheRest() {
    LongAdder failed = new LongAdder();
    List<Update<WrappedBlockData>> attempted = new ArrayList<>();
    List<List<Update<WrappedBlockData>>> clusters = clusters(
        update(1, 2, 3), update(4, 5, 6), update(7, 8, 9));

    int sent = UpdateDelivery.deliverEach(clusters, update -> {
      attempted.add(update);
      if (update.x() == 4) {
        throw new IllegalStateException("模拟 sendServerPacket 中途抛出");
      }
      return true;
    }, failed);

    assertEquals(3, attempted.size(), "抛出之后剩余的条目必须继续投递（这正是旧裸循环丢掉的那部分）");
    assertEquals(2, sent, "三条里两条成功");
    assertEquals(1L, failed.sum(), "抛出也必须计入失败，否则「缺了几格」在面板上不可见");
  }

  @Test
  void returningFalseCountsAsFailureAndContinues() {
    LongAdder failed = new LongAdder();
    List<Update<WrappedBlockData>> attempted = new ArrayList<>();
    List<List<Update<WrappedBlockData>>> clusters = clusters(
        update(0, 0, 0), update(1, 0, 0), update(2, 0, 0));

    int sent = UpdateDelivery.deliverEach(clusters, update -> {
      attempted.add(update);
      return update.x() != 1;
    }, failed);

    assertEquals(3, attempted.size());
    assertEquals(2, sent);
    assertEquals(1L, failed.sum());
  }

  @Test
  void doorHalvesAreDeliveredIndependently() {
    LongAdder failed = new LongAdder();
    List<Update<WrappedBlockData>> attempted = new ArrayList<>();
    // 同一根柱子的下半与上半：上半发送失败不得连累下半
    List<List<Update<WrappedBlockData>>> clusters = clusters(
        update(100, 64, -200), update(100, 65, -200));

    int sent = UpdateDelivery.deliverEach(clusters, update -> {
      attempted.add(update);
      return update.y() != 65;
    }, failed);

    assertEquals(2, attempted.size());
    assertEquals(1, sent);
    assertEquals(1L, failed.sum());
    assertTrue(attempted.stream().anyMatch(update -> update.y() == 64),
        "下半必须仍被尝试投递");
  }

  @Test
  void emptyAndNullInputsAreSafe() {
    LongAdder failed = new LongAdder();
    assertEquals(0, UpdateDelivery.deliverEach(null, update -> true, failed));
    assertEquals(0, UpdateDelivery.deliverEach(List.of(), update -> true, failed));
    assertEquals(0, UpdateDelivery.deliverEach(clusters(update(0, 0, 0)), null, failed));
    assertEquals(0L, failed.sum());

    List<List<Update<WrappedBlockData>>> withNulls = new ArrayList<>();
    withNulls.add(null);
    withNulls.add(Arrays.asList(null, update(1, 1, 1)));
    assertEquals(1, UpdateDelivery.deliverEach(withNulls, update -> true, null),
        "null 簇/条目跳过；失败计数为 null 时不得抛 NPE");
  }
}
