package net.mikumc.mikuxraynet.bandwidth;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * 线程调度助手：把所有 Bukkit API 调用收敛到「实体所属线程」执行。
 *
 * <p><b>统一走 Paper 实体调度器</b>：{@link Entity#getScheduler()}（即
 * {@link io.papermc.paper.threadedregions.scheduler.EntityScheduler}）在 <b>Paper 与 Folia 上是同一套 API</b>——
 * Paper 上实体所属线程就是服务端主线程，Folia 上则是该实体所在区域的区域线程，因此不再需要
 * 「按平台二选一」（旧实现是 {@code if (isFolia()) entity.getScheduler() else Bukkit.getScheduler()}）。
 *
 * <p>本类只剩「回到实体所属线程执行」的两个入口（{@link #onEntity} / {@link #repeatOnEntity}）：
 * 带宽侧已无工作线程路径，hideEntity / setViewDistance 等调用全部落在所有者线程，永不跨线程。
 *
 * <p><b>单位</b>：{@link io.papermc.paper.threadedregions.scheduler.EntityScheduler} 的延迟与周期
 * 都以 <b>tick</b> 计，与旧的 {@code BukkitScheduler#runTaskTimer(delayTicks, periodTicks)} 完全一致
 * （注意与 {@code AsyncScheduler} 不同：后者用 {@link java.util.concurrent.TimeUnit}）。
 */
public final class Schedulers {

  private Schedulers() {
  }

  /** 在实体所属线程执行一次任务；调度失败（实体已移除或平台不支持）时静默放弃。 */
  public static void onEntity(Plugin plugin, Entity entity, Runnable task) {
    try {
      // 第三参数是「实体已退役」的回调，传 null 表示不处理退役（与原实现一致）
      entity.getScheduler().run(plugin, scheduled -> task.run(), null);
    } catch (Throwable ignored) {
      // 实体已退役：放弃执行（剔除模块本身是可选优化，失败不影响正确性）
    }
  }

  /**
   * 在实体所属线程上启动周期任务（初始延迟与周期都以 <b>tick</b> 计，语义等同旧
   * {@code runTaskTimer(delayTicks, periodTicks)}）。
   *
   * @return 可取消的任务句柄；实体已退役导致调度失败时为 {@code null}
   */
  public static ScheduledTask repeatOnEntity(Plugin plugin, Entity entity, long delayTicks,
      long periodTicks, Runnable task) {
    return repeatOnEntity(plugin, entity, delayTicks, periodTicks, task, null);
  }

  /**
   * 在实体所属线程上启动周期任务，并登记「实体已退役」回调。
   *
   * <p>退役回调由服务端在实体销毁时调用（也可能永不调用，例如插件先停用并主动取消任务），
   * 供调用方丢弃「只服务于该实体」的辅助状态（如位置快照）。
   *
   * @param retired 实体退役时执行的回调；{@code null} 表示不处理退役
   */
  public static ScheduledTask repeatOnEntity(Plugin plugin, Entity entity, long delayTicks,
      long periodTicks, Runnable task, Runnable retired) {
    try {
      return entity.getScheduler().runAtFixedRate(plugin, scheduled -> task.run(), retired, delayTicks,
          periodTicks);
    } catch (Throwable ignored) {
      // 实体已退役：无需周期任务
      return null;
    }
  }
}