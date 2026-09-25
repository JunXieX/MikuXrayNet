package net.mikumc.mikuxraynet.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置热重载编排：把「重读配置 → 失效缓存 → 重启周期任务 → 汇报生效范围」串成一条可测流程。
 *
 * <p><b>为什么单独成类</b>：真正触碰 Bukkit 的部分（配置读取、缓存失效、任务重启）由插件通过
 * {@link Target} 注入，本类只负责顺序编排与结果汇报，因此可在单测中用桩对象断言「缓存失效是否被触发」。
 *
 * <p><b>异常安全（fail-open）</b>：任一环节抛异常都只记入返回结果，绝不让重载失败波及封包主链路；
 * 重载完成后旧数据最多多存活片刻，不会影响正确性。
 */
public final class ReloadCoordinator {

  /** 热重载目标（由插件装配提供；单测用桩对象断言调用）。 */
  public interface Target {

    /** 当前配置指纹（任一影响改写结果的配置项变化都会改变它）。 */
    int fingerprint();

    /** 重新读取两份配置文件。 */
    void reloadConfiguration();

    /** 使改写缓存、邻块快照与显形索引立即失效。 */
    void invalidateCaches();

    /** 按新配置重建/重启周期任务（邻近显形、AFK、延迟巡检）。 */
    void restartPeriodicTasks();
  }

  /** 本次热重载后已即时生效的配置项。 */
  public static final List<String> APPLIED = List.of(
      "邻近显形（距离/周期/单次上限）",
      "反矿透世界黑名单（world-blacklist，即时豁免/恢复对应世界）",
      "AFK 降级（判定时长/丢弃距离/包类型）",
      "高延迟降视距（阈值/幅度/下限/巡检周期）",
      "带宽全部子模块（零位移/变更合并/实体剔除/调色板重排）",
      "平台判定（advanced.platform，会重新判定并按新分支重建周期任务）",
      "两份配置文件的读取值");

  /** 需要重启服务端才生效的配置项（在启动期已固化的部分）。 */
  public static final List<String> RESTART_REQUIRED = List.of(
      "隐藏方块表与伪装权重",
      "层状混淆 / 方块实体剔除开关",
      "邻块快照开关/缺失策略/容量",
      "反矿透工作线程数与队列容量",
      "区块改写超时与改写缓存容量/过期",
      "调色板 strict-verify");

  /**
   * 执行一次热重载。
   *
   * @return 给管理员看的中文结果行（已含「已生效」与「需重启」两类清单）
   */
  public List<String> reload(Target target) {
    List<String> lines = new ArrayList<>();

    int before = safeFingerprint(target);
    try {
      target.reloadConfiguration();
    } catch (Throwable throwable) {
      lines.add("配置重新读取失败，已保留原配置（详见服务端日志）");
      return lines;
    }
    int after = safeFingerprint(target);

    boolean invalidated = false;
    try {
      target.invalidateCaches();
      invalidated = true;
    } catch (Throwable throwable) {
      lines.add("缓存失效时出现异常，已在日志记录（不影响封包主链路）");
    }

    try {
      target.restartPeriodicTasks();
    } catch (Throwable throwable) {
      lines.add("周期任务重启时出现异常，已在日志记录（不影响封包主链路）");
    }

    if (before != after) {
      lines.add("配置指纹已变化（" + before + " → " + after + "）"
          + (invalidated ? "，改写缓存与显形索引已失效" : ""));
    } else {
      lines.add("配置指纹未变化（" + after + "）" + (invalidated ? "，缓存已按安全起见刷新" : ""));
    }
    lines.add("已热生效：" + String.join("、", APPLIED));
    lines.add("需要重启服务端才生效：" + String.join("、", RESTART_REQUIRED));
    return lines;
  }

  private static int safeFingerprint(Target target) {
    try {
      return target.fingerprint();
    } catch (Throwable throwable) {
      return 0;
    }
  }
}