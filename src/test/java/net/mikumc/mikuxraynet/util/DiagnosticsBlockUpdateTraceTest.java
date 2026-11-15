package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.util.Diagnostics.Snapshot;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 「最近块更新处置」取证段与两条兜底计数的渲染用例。
 *
 * <p>排查现场问题时，服主手上只有一份 dump：如果里面没有逐条处置流水，「门只开一半」就只能靠反复开关门
 * 复现，无法离线定位。这里锁住四件事：取证段头部、每条流水原文、两条兜底计数、以及「未采集」时的成因说明。
 */
class DiagnosticsBlockUpdateTraceTest {

  private static BandwidthConfig bandwidth(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return BandwidthConfig.from(configuration);
  }

  private static Snapshot snapshot(Snapshot.Extras extras, Snapshot.Trace trace,
      BandwidthConfig bandwidth) {
    return new Snapshot(
        new Snapshot.Env(true, true, false, true, 0, "Paper 26.2", "25", "1.34.5"),
        Snapshot.Rewrite.EMPTY,
        Snapshot.Proximity.EMPTY,
        Snapshot.Index.EMPTY,
        Snapshot.Occlusion.EMPTY,
        Snapshot.Throttle.EMPTY,
        Snapshot.Pool.EMPTY,
        Snapshot.DiskCache.EMPTY,
        Snapshot.ChunkAccessor.EMPTY,
        null, extras, trace, bandwidth);
  }

  private static Snapshot.Extras extras(long redeemed, long verifyFailed, long sendFailed) {
    return new Snapshot.Extras(0L, 0L, 0L, 0L, 0L, 0L, Double.NaN, redeemed, verifyFailed,
        sendFailed);
  }

  @Test
  void traceSectionIsRenderedWithEveryLine() {
    Snapshot.Trace trace = new Snapshot.Trace(List.of(
        "#12 MERGE  (100,65,-200) 2 个坐标 multi=true 世界 world",
        "#11 PASS  (100,64,-200) 2 个坐标 multi=true 世界 world"));
    String dump = Diagnostics.formatDump(snapshot(extras(0L, 0L, 0L), trace, null), "20261115-000000");
    assertTrue(dump.contains("---- 最近块更新处置（最新在前，最多 32 条）----"),
        "取证段头部必须存在：" + dump);
    assertTrue(dump.contains("#12 MERGE"), "每条流水必须原样渲染：" + dump);
    assertTrue(dump.contains("#11 PASS"), dump);
  }

  @Test
  void emptyTraceExplainsWhyThereIsNoRecord() {
    String dump = Diagnostics.formatDump(snapshot(extras(0L, 0L, 0L), Snapshot.Trace.EMPTY, null),
        "20261115-000000");
    assertTrue(dump.contains("未采集（方块变更合并未接线"),
        "空环必须写清成因，不能只留一个空段：" + dump);
  }

  @Test
  void redeemedAndVerifyFailedCountersAreRendered() {
    String dump = Diagnostics.formatDump(snapshot(extras(1234L, 7L, 0L), Snapshot.Trace.EMPTY, null),
        "20261115-000000");
    assertTrue(dump.contains("同世界补发 1234"), "补发次数必须印出来：" + dump);
    assertTrue(dump.contains("合批校验失败 7"), "合批校验失败次数必须印出来：" + dump);
  }

  @Test
  void sendFailedCounterIsRendered() {
    String dump = Diagnostics.formatDump(snapshot(extras(0L, 0L, 5L), Snapshot.Trace.EMPTY, null),
        "20261115-000000");
    assertTrue(dump.contains("单条补发失败 5"),
        "逐条补发仍失败的条数必须可见——它代表客户端确实缺了这些方块更新：" + dump);
  }

  @Test
  void strictVerifyOffIsCalledOutOnlyWhenDisabled() {
    BandwidthConfig off = bandwidth("palette:\n  enabled: true\n  strict-verify: false\n");
    String offDump = Diagnostics.formatDump(snapshot(Snapshot.Extras.EMPTY, Snapshot.Trace.EMPTY, off),
        "20261115-000000");
    assertTrue(offDump.contains("自检关闭"), "strict-verify=false 时必须提示风险：" + offDump);

    BandwidthConfig on = bandwidth("palette:\n  enabled: true\n  strict-verify: true\n");
    String onDump = Diagnostics.formatDump(snapshot(Snapshot.Extras.EMPTY, Snapshot.Trace.EMPTY, on),
        "20261115-000000");
    assertFalse(onDump.contains("自检关闭"), "开启自检时不得再提示：" + onDump);
  }
}
