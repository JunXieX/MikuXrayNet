package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 热重载路径：验证「配置指纹变化 → 缓存失效被触发 → 周期任务被重启」，以及失败时保留原配置。
 *
 * <p>用桩 {@link ReloadCoordinator.Target} 断言各环节被调用，不触碰 Bukkit。指纹分两侧
 * （反矿透 / 带宽），因此还要验证「只改一侧」时提示语准确点名是哪一侧变化。
 */
class ReloadCoordinatorTest {

  /** 记录调用情况并可模拟指纹变化的桩目标。 */
  private static final class Stub implements ReloadCoordinator.Target {

    private int antiXrayFingerprint;
    private final int bandwidthFingerprint;
    private boolean reloaded;
    private boolean invalidated;
    private boolean restarted;

    private Stub(int antiXrayFingerprint, int bandwidthFingerprint) {
      this.antiXrayFingerprint = antiXrayFingerprint;
      this.bandwidthFingerprint = bandwidthFingerprint;
    }

    @Override
    public int antiXrayFingerprint() {
      return antiXrayFingerprint;
    }

    @Override
    public int bandwidthFingerprint() {
      return bandwidthFingerprint;
    }

    @Override
    public void reloadConfiguration() {
      reloaded = true;
      antiXrayFingerprint++;
    }

    @Override
    public void invalidateCaches() {
      invalidated = true;
    }

    @Override
    public void restartPeriodicTasks() {
      restarted = true;
    }
  }

  @Test
  void reloadTriggersInvalidationAndRestartOnAntiXrayFingerprintChange() {
    ReloadCoordinator coordinator = new ReloadCoordinator();
    Stub stub = new Stub(123, 500);

    List<String> lines = coordinator.reload(stub);

    assertTrue(stub.reloaded, "必须重新读取配置");
    assertTrue(stub.invalidated, "配置指纹变化必须触发缓存失效");
    assertTrue(stub.restarted, "必须重启周期任务");

    String text = String.join("\n", lines);
    assertTrue(text.contains("反矿透侧（123 → 124）"), "必须点名变化的侧与前后指纹：" + text);
    // 只宣告真正失效的东西：改写缓存 + 邻块快照。伪装索引 / 已显形集合在热重载时**刻意保留**
    //（清掉会让已下发的假矿永远无法还原），所以回显不得再出现「显形索引已失效」这种相反说法。
    assertTrue(text.contains("改写缓存与邻块快照已失效"), text);
    assertTrue(text.contains("伪装索引 / 已显形集合刻意保留"), text);
    assertFalse(text.contains("显形索引已失效"), "热重载刻意保留伪装索引，回显不得宣称它已失效：" + text);
    assertTrue(text.contains("已热生效"), text);
    assertTrue(text.contains("需要重启服务端才生效"), text);
    assertFalse(text.contains("带宽侧"), "带宽侧未变化时不得出现该侧的变化项：" + text);
  }

  /** 只改 bandwidth.yml（反矿透侧指纹不变）时必须如实报告「带宽侧已变化」，不能报「未变化」。 */
  @Test
  void bandwidthOnlyChangeIsReportedAsBandwidthSide() {
    ReloadCoordinator coordinator = new ReloadCoordinator();
    int[] bandwidth = {7};
    ReloadCoordinator.Target target = new ReloadCoordinator.Target() {
      @Override
      public int antiXrayFingerprint() {
        return 42;
      }

      @Override
      public int bandwidthFingerprint() {
        return bandwidth[0];
      }

      @Override
      public void reloadConfiguration() {
        bandwidth[0] = 8; // 模拟只修改了 bandwidth.yml
      }

      @Override
      public void invalidateCaches() {
      }

      @Override
      public void restartPeriodicTasks() {
      }
    };

    String text = String.join("\n", coordinator.reload(target));

    assertTrue(text.contains("带宽侧（7 → 8）"), "只改带宽配置时必须点名带宽侧：" + text);
    assertFalse(text.contains("反矿透侧"), "反矿透侧未变化时不得出现该侧的变化项：" + text);
    assertFalse(text.contains("两侧配置指纹均未变化"), text);
  }

  @Test
  void invalidatesEvenWhenFingerprintUnchanged() {
    ReloadCoordinator coordinator = new ReloadCoordinator();
    ReloadCoordinator.Target unchanged = new ReloadCoordinator.Target() {
      @Override
      public int antiXrayFingerprint() {
        return 7;
      }

      @Override
      public int bandwidthFingerprint() {
        return 9;
      }

      @Override
      public void reloadConfiguration() {
        // 内容未变化
      }

      @Override
      public void invalidateCaches() {
        // no-op
      }

      @Override
      public void restartPeriodicTasks() {
        // no-op
      }
    };

    List<String> lines = coordinator.reload(unchanged);
    String text = String.join("\n", lines);
    assertTrue(text.contains("两侧配置指纹均未变化"), text);
    assertTrue(text.contains("缓存已按安全起见刷新"), text);
  }

  /**
   * 清单准确性回归：{@code neighbors.enabled} 的「关闭」是热生效的（区块改写路径实时读当前配置），
   * 不能再被列进 RESTART_REQUIRED —— 否则管理员会为一项已生效的改动白重启一次，且以后不再相信这份清单。
   * 同时必须保留真正只在启动期固化的两项（缺失策略 / 缓存容量），避免修正过度把整项删掉。
   */
  @Test
  void neighborSwitchOffIsListedAsLiveAndNotAsRestartRequired() {
    String applied = String.join("、", ReloadCoordinator.APPLIED);
    String restart = String.join("、", ReloadCoordinator.RESTART_REQUIRED);

    assertTrue(applied.contains("neighbors.enabled=false"),
        "「关闭邻块快照」是热生效项，必须在 APPLIED 里点名：" + applied);
    assertFalse(restart.contains("邻块快照开关"),
        "已能热生效的开关不得再列入 RESTART_REQUIRED：" + restart);
    assertTrue(restart.contains("missing-policy"), "缺失策略仍属启动期固化，必须留在需重启清单：" + restart);
    assertTrue(restart.contains("cache-maximum-size"), "邻块缓存容量仍属启动期固化，必须留在需重启清单：" + restart);
  }

  @Test
  void reloadFailureKeepsRunningWithoutThrowing() {
    ReloadCoordinator coordinator = new ReloadCoordinator();
    ReloadCoordinator.Target failing = new ReloadCoordinator.Target() {
      @Override
      public int antiXrayFingerprint() {
        return 1;
      }

      @Override
      public int bandwidthFingerprint() {
        return 2;
      }

      @Override
      public void reloadConfiguration() {
        throw new IllegalStateException("模拟配置读取失败");
      }

      @Override
      public void invalidateCaches() {
      }

      @Override
      public void restartPeriodicTasks() {
      }
    };

    List<String> lines = coordinator.reload(failing);
    assertFalse(lines.isEmpty());
    assertTrue(String.join("\n", lines).contains("保留原配置"), String.join("\n", lines));
  }
}