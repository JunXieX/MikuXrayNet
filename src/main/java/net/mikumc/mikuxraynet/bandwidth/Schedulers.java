package net.mikumc.mikuxraynet.bandwidth;

import net.mikumc.mikuxraynet.bootstrap.PlatformSupport;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * 线程调度助手：把所有 Bukkit API 调用收敛到「实体所属线程」执行。
 *
 * <p>分支语义（平台判定见 {@code bootstrap.PlatformSupport}）：
 * <ul>
 *   <li><b>Paper 系</b>（默认，含 Leaf 等 Paper 下游分支）：实体所属线程就是服务端主线程，
 *       直接用常规 Bukkit 调度（{@link Bukkit#getScheduler()} 的 {@code runTask} / {@code runTaskTimer}）；</li>
 *   <li><b>Folia 系</b>：实体所属线程是该实体所在区域的区域线程，必须用
 *       {@link Entity#getScheduler()} 的区域调度器。</li>
 * </ul>
 * 工作线程只做纯计算，结果一律经此类回到所有者线程，因此 hideEntity / setViewDistance 等调用永远不会跨线程。
 */
public final class Schedulers {

  private Schedulers() {
  }

  /** 在实体所属线程执行一次任务；调度失败（实体已移除）时静默放弃。 */
  public static void onEntity(Plugin plugin, Entity entity, Runnable task) {
    if (PlatformSupport.isFolia()) {
      try {
        entity.getScheduler().run(plugin, scheduled -> task.run(), null);
      } catch (Throwable ignored) {
        // 实体已退役：放弃执行（剔除模块本身是可选优化，失败不影响正确性）
      }
      return;
    }
    Bukkit.getScheduler().runTask(plugin, task);
  }

  /** 在实体所属线程上启动周期任务；仅在 Folia 分支使用（非 Folia 用统一的全局任务）。 */
  public static void repeatOnEntity(Plugin plugin, Entity entity, long delayTicks, long periodTicks,
      Runnable task) {
    if (!PlatformSupport.isFolia()) {
      Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
      return;
    }
    try {
      entity.getScheduler().runAtFixedRate(plugin, scheduled -> task.run(), null, delayTicks, periodTicks);
    } catch (Throwable ignored) {
      // 实体已退役：无需周期任务
    }
  }
}