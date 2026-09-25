package net.mikumc.mikuxraynet.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 依赖守卫增强（P2-6d）的纯函数部分：版本串解析与比较、版本范围提示、反矿透冲突插件判定。
 *
 * <p>只做提示不做门禁：读不到版本（null/无数字段）时静默跳过，绝不误报。
 */
class DependencyGuardCompatibilityTest {

  // ---------------------------------------------------------- 版本串解析与比较

  /** 版本串解析：忽略前缀字母、截断 - 后缀，最多 4 段；无法解析返回空数组。 */
  @Test
  void parseVersionHandlesPrefixesAndSuffixes() {
    assertEquals(0, DependencyGuard.parseVersion(null).length, "null → 空数组（跳过提示）");
    assertEquals(0, DependencyGuard.parseVersion("unknown").length, "无数字段 → 空数组");
    assertEquals(0, DependencyGuard.parseVersion("  ").length, "空白 → 空数组");
    assertEquals(3, DependencyGuard.parseVersion("v2.13.0").length, "前缀字母被忽略");
    assertEquals(3, DependencyGuard.parseVersion("5.3.0-SNAPSHOT").length, "- 后缀被截断");
    assertEquals(2, DependencyGuard.parseVersion("26.2").length, "两段版本");
  }

  /** 版本比较：逐段数值比较，缺失段视为 0，绝无异常。 */
  @Test
  void compareVersionsIsNumericPerSegment() {
    assertTrue(DependencyGuard.compareVersions("2.12.9",
            DependencyGuard.MIN_PACKET_EVENTS_VERSION) < 0, "2.12.9 < 2.13.0");
    assertEquals(0, DependencyGuard.compareVersions("2.13.0", "2.13.0"), "相等 → 0");
    assertTrue(DependencyGuard.compareVersions("2.14.0",
            DependencyGuard.MIN_PACKET_EVENTS_VERSION) > 0, "2.14.0 > 2.13.0");
    assertTrue(DependencyGuard.compareVersions("5.2.1",
            DependencyGuard.MIN_PROTOCOL_LIB_VERSION) < 0, "5.2.1 < 5.3.0");
    assertEquals(0, DependencyGuard.compareVersions("2.13", "2.13.0"), "缺失段视为 0");
    assertEquals(0, DependencyGuard.compareVersions(null, null), "两者都无法解析视为相等（不抛异常）");
    assertTrue(DependencyGuard.compareVersions(null, "1.0") < 0,
        "无法解析按 0（最保守）处理：null < 1.0");
  }

  // ---------------------------------------------------------- 版本范围提示

  /** 前置版本低于建议下限时给出中文「可能不兼容」提示；两者问题合并为一条。 */
  @Test
  void warnsWhenVersionsBelowRecommendedFloor() {
    String peOld = DependencyGuard.versionCompatibilityWarning("MikuXrayNet", "2.12.9", "5.3.0");
    assertTrue(peOld != null && peOld.contains("PacketEvents") && peOld.contains("2.13.0"),
        "PE 过旧必须提示：" + peOld);
    assertTrue(peOld.contains("可能不兼容"), "提示必须包含「可能不兼容」：" + peOld);

    String plOld = DependencyGuard.versionCompatibilityWarning("MikuXrayNet", "2.13.0", "5.2.1");
    assertTrue(plOld != null && plOld.contains("ProtocolLib") && plOld.contains("5.3.0"),
        "PL 过旧必须提示：" + plOld);

    String both = DependencyGuard.versionCompatibilityWarning("MikuXrayNet", "2.0", "4.0");
    assertTrue(both != null && both.contains("PacketEvents") && both.contains("ProtocolLib"),
        "两个都过旧时合并为一条：" + both);
  }

  /** 读不到版本或版本达标时静默跳过（绝不误报）。 */
  @Test
  void skipsWarningWhenVersionsMissingOrSufficient() {
    assertNull(DependencyGuard.versionCompatibilityWarning("MikuXrayNet", null, null),
        "读不到版本 → 跳过");
    assertNull(DependencyGuard.versionCompatibilityWarning("MikuXrayNet", "unknown", "unknown"),
        "解析不出 → 跳过");
    assertNull(DependencyGuard.versionCompatibilityWarning("MikuXrayNet", "2.13.0", "5.3.0"),
        "版本达标 → 不提示");
    assertNull(DependencyGuard.versionCompatibilityWarning("MikuXrayNet", "2.14.1", "5.5.0"),
        "更高版本 → 不提示");
  }

  // ---------------------------------------------------------- 反矿透冲突检测

  /** 黑名单命中：其它反矿透插件（Orebfuscator、竞品名等，大小写不敏感）；自己与无关插件不命中。 */
  @Test
  void conflictBlacklistMatchesOtherAntiXrayPluginsOnly() {
    assertTrue(DependencyGuard.isConflictingAntiXrayPlugin("MikuXrayNet", "Orebfuscator"),
        "Orebfuscator 必须命中冲突名单");
    assertTrue(DependencyGuard.isConflictingAntiXrayPlugin("MikuXrayNet", "MikuAntiXray"),
        "竞品 MikuAntiXray 必须命中冲突名单");
    assertTrue(DependencyGuard.isConflictingAntiXrayPlugin("MikuXrayNet", "my-antixray-addon"),
        "包含线索词的变体名也命中（大小写不敏感）");
    assertFalse(DependencyGuard.isConflictingAntiXrayPlugin("MikuXrayNet", "MikuXrayNet"),
        "绝不报自己");
    assertFalse(DependencyGuard.isConflictingAntiXrayPlugin("MikuXrayNet", "ProtocolLib"),
        "前置依赖不是冲突");
    assertFalse(DependencyGuard.isConflictingAntiXrayPlugin("MikuXrayNet", "ViaVersion"),
        "普通协议插件不是冲突");
    assertFalse(DependencyGuard.isConflictingAntiXrayPlugin("MikuXrayNet", null), "空名不命中");
    assertTrue(DependencyGuard.isConflictingAntiXrayPlugin(null, "Orebfuscator"),
        "自身名缺失时按默认自身名判定，仍能检出冲突");
  }

  /** 冲突判定对「正常输入 vs null」都安全（live 扫描以插件管理器为准，纯函数只管名字）。 */
  @Test
  void conflictCheckIsCaseInsensitiveAndNullSafe() {
    assertTrue(DependencyGuard.isConflictingAntiXrayPlugin("mikuxraynet", "  OREBFUSCATOR  "),
        "前后空白与大写不得漏报");
    assertFalse(DependencyGuard.isConflictingAntiXrayPlugin("MikuXrayNet", " "), "纯空白不命中");
  }
}
