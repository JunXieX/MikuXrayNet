package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;

/**
 * 「一批已被取消的方块更新」的<b>逐条兜底投递</b>：每条独立成败，任一条失败都不影响其余条目。
 *
 * <p><b>为什么单独成类</b>：原包进入合并窗口时就已经 {@code setCancelled(true)}，此后这些变更只能靠合并包
 * 或逐条补发送达客户端（「取消即欠账」）。旧的逐条补发是一个裸循环——一旦 {@code sendServerPacket} 在中途
 * 抛出，循环里剩下的条目全部丢失，而且只留一条限流日志（超过上限后连日志都没有）。门/床/双箱这类成对结构
 * 一旦丢其中一片，客户端就停在旧状态并继续阻挡玩家，即「门只开一半」。
 *
 * <p><b>为什么是纯函数</b>：真实发送依赖 ProtocolLib 与在线玩家，离线造不出来；把「逐条、互不影响、计数、
 * 永不抛」这四条语义抽出来，就能用离线用例把最后一道防线钉死（见 {@code UpdateDeliveryTest}）。
 *
 * <p><b>线程</b>：在合并线程 {@code MikuXrayNet-BlockMerge} 上调用；不持锁、不触碰 Bukkit 状态，
 * 只调用传入的 {@link Delivery}。
 */
public final class UpdateDelivery {

  private UpdateDelivery() {
  }

  /** 单条发送：返回 {@code true} 表示这条确实交付成功。允许抛出，{@link #deliverEach} 会兜住。 */
  @FunctionalInterface
  public interface Delivery {

    /** 发送一条更新。 */
    boolean send(Update<WrappedBlockData> update);
  }

  /**
   * 逐条投递，<b>每条独立兜底</b>，方法自身永不抛出。
   *
   * @param clusters 聚簇后的待投递更新（可为 {@code null} / 含 {@code null} 簇或条目）
   * @param delivery 单条发送动作，可为 {@code null}（此时什么都不发，返回 0）
   * @param failed   失败计数（可为 {@code null}）；抛异常与返回 {@code false} 都计一次
   * @return 成功投递的条数
   */
  public static int deliverEach(List<List<Update<WrappedBlockData>>> clusters, Delivery delivery,
      LongAdder failed) {
    if (clusters == null || delivery == null) {
      return 0;
    }
    int sent = 0;
    for (List<Update<WrappedBlockData>> cluster : clusters) {
      if (cluster == null) {
        continue;
      }
      for (Update<WrappedBlockData> update : cluster) {
        if (update == null) {
          continue;
        }
        boolean ok;
        try {
          ok = delivery.send(update);
        } catch (Throwable throwable) {
          // 单条失败只影响这一条：整批已取消，剩余条目必须继续投递
          ok = false;
        }
        if (ok) {
          sent++;
        } else if (failed != null) {
          failed.increment();
        }
      }
    }
    return sent;
  }
}
