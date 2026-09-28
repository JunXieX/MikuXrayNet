package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 热重载路径：验证「配置指纹变化 → 缓存失效被触发 → 周期任务被重启」，以及失败时保留原配置。
 *
 * <p>用桩 {@link ReloadCoordinator.Target} 断言各环节被调用，不触碰 Bukkit。
 */
class ReloadCoordinatorTest {

  /** 记录调用情况并可模拟指纹变化的桩目标。 */
  private static final class Stub implements ReloadCoordinator.Target {

    private int fingerprint;
    private boolean reloaded;
    private boolean invalidated;
    private boolean restarted;

    private Stub(int fingerprint) {
      this.fingerprint = fingerprint;
    }

    @Override
    public int fingerprint() {
      return fingerprint;
    }

    @Override
    public void reloadConfiguration() {
      reloaded = true;
      fingerprint++;
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
  void reloadTriggersInvalidationAndRestartOnFingerprintChange() {
    ReloadCoordinator coordinator = new ReloadCoordinator();
    Stub stub = new Stub(123);

    List<String> lines = coordinator.reload(stub);

    assertTrue(stub.reloaded, "必须重新读取配置");
    assertTrue(stub.invalidated, "配置指纹变化必须触发缓存失效");
    assertTrue(stub.restarted, "必须重启周期任务");

    String text = String.join("\n", lines);
    assertTrue(text.contains("123 → 124"), text);
    assertTrue(text.contains("已失效"), text);
    assertTrue(text.contains("已热生效"), text);
    assertTrue(text.contains("需要重启服务端才生效"), text);
  }

  @Test
  void invalidatesEvenWhenFingerprintUnchanged() {
    ReloadCoordinator coordinator = new ReloadCoordinator();
    ReloadCoordinator.Target unchanged = new ReloadCoordinator.Target() {
      @Override
      public int fingerprint() {
        return 7;
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
    assertTrue(text.contains("配置指纹未变化"), text);
    assertTrue(text.contains("缓存已按安全起见刷新"), text);
  }

  @Test
  void reloadFailureKeepsRunningWithoutThrowing() {
    ReloadCoordinator coordinator = new ReloadCoordinator();
    ReloadCoordinator.Target failing = new ReloadCoordinator.Target() {
      @Override
      public int fingerprint() {
        return 1;
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