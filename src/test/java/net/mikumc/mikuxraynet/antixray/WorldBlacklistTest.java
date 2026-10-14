package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.WorldBlacklistConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 世界黑名单（独立配置 {@code blacklist.yml}）：匹配语义、优先级，以及在反矿透侧的统一判定出口。
 *
 * <p>黑名单已从 {@code antixray.yml} 迁出为共享配置 {@link WorldBlacklistConfig}，对反矿透与带宽优化
 * <b>同时</b>生效。反矿透各入口（区块改写、显形、变更观察）都经
 * {@link AntiXrayConfig#antiXrayAppliesTo(String)} → {@link WorldBlacklistConfig#isBlacklistedNow(String)}，
 * 因此这里对判定函数做端到端断言（判定为假即代表该路径不建任务、不登记延迟、不发包）。
 *
 * <p><b>静态名单的发布</b>：{@link WorldBlacklistConfig#publish} 会替换进程级活动实例，因此每个用例
 * 结束时都显式复位为空名单（{@link #reset()}），避免用例间互相污染。
 */
class WorldBlacklistTest {

  private static WorldBlacklistConfig blacklist(String content) {
    return WorldBlacklistConfig.from(yaml(content));
  }

  private static AntiXrayConfig antiXray(String content) {
    return AntiXrayConfig.from(yaml(content));
  }

  private static YamlConfiguration yaml(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return configuration;
  }

  /** 复位为「无任何世界被豁免」，避免上一个用例的静态名单污染后一个。 */
  private static void reset() {
    WorldBlacklistConfig.publish(WorldBlacklistConfig.fromPatterns(List.of()));
  }

  // ---------------------------------------------------------- 精确名

  @Test
  void exactPatternHitsAndMisses() {
    WorldBlacklistConfig config = blacklist("world-blacklist: [spawn]\n");

    assertEquals(List.of("spawn"), config.patterns(), "原样保留精确名（声明序）");
    assertTrue(config.isBlacklisted("spawn"), "精确名必须命中");
    assertFalse(config.isBlacklisted("spawn_nether"), "非完全相等不得命中（精确名不做前缀匹配）");
    assertFalse(config.isBlacklisted("SPAWN"), "世界名大小写敏感（与 world-overrides 的匹配语义一致）");
    assertFalse(config.isBlacklisted(null), "null 世界名不得判为黑名单");
  }

  @Test
  void exactPatternGatesAntiXrayThroughSharedGate() {
    try {
      WorldBlacklistConfig.publish(blacklist("world-blacklist: [spawn]\n"));
      AntiXrayConfig config = antiXray("enabled: true\n");

      assertFalse(config.antiXrayAppliesTo("spawn"), "黑名单世界：反矿透一律不适用");
      assertTrue(config.antiXrayAppliesTo("spawn_nether"), "非黑名单世界：照常反矿透");
    } finally {
      reset();
    }
  }

  // ---------------------------------------------------------- 通配

  @Test
  void wildcardPatternHitsOnlyWhenSuffixPresent() {
    WorldBlacklistConfig config = blacklist("world-blacklist:\n  - dungeon_*\n");

    assertTrue(config.isBlacklisted("dungeon_1"), "dungeon_1 命中 dungeon_*");
    assertTrue(config.isBlacklisted("dungeon_"), "通配 * 允许空后缀（与 world-overrides 一致）");
    assertFalse(config.isBlacklisted("dungeon"),
        "边界：dungeon_* 要求字面量下划线，无后缀的 dungeon 不命中");
    assertFalse(config.isBlacklisted("dungeonx"), "缺少下划线不得命中");
    assertFalse(config.isBlacklisted("my_dungeon_1"), "前缀不匹配不得命中（通配只覆盖后缀）");
  }

  // ---------------------------------------------------------- 优先级（黑名单 > world-overrides）

  @Test
  void blacklistBeatsWorldOverrides() {
    try {
      WorldBlacklistConfig.publish(blacklist("world-blacklist: [arena]\n"));
      AntiXrayConfig config = antiXray("""
          world-overrides:
            arena:
              enabled: true
              mode: enclosed
          """);

      assertEquals(0, config.matchOverride("arena"),
          "world-overrides 确实为该世界匹配到覆盖段（否则本测试无法证明优先级）");
      assertTrue(WorldBlacklistConfig.isBlacklistedNow("arena"), "黑名单判定独立于 world-overrides");
      assertFalse(config.antiXrayAppliesTo("arena"),
          "黑名单优先级最高：即便 world-overrides 显式覆盖，仍不启用反矿透（要重新启用须先从黑名单移除）");
    } finally {
      reset();
    }
  }

  // ---------------------------------------------------------- 方块变更观察入口

  @Test
  void changeObservationSkipsBlacklistedWorld() {
    try {
      WorldBlacklistConfig.publish(blacklist("world-blacklist: [spawn]\n"));

      assertTrue(BlockChangeRevealListener.isBlacklisted("spawn"),
          "方块变更观察入口判定为 true：不注销显形、不驱动磁盘缓存代次、不触发事件显形");
      assertFalse(BlockChangeRevealListener.isBlacklisted("world"), "非黑名单世界照常观察");
    } finally {
      reset();
    }
  }

  // ---------------------------------------------------------- 空黑名单回归

  @Test
  void emptyBlacklistKeepsExistingBehavior() {
    reset();
    AntiXrayConfig config = antiXray("enabled: true\n");

    assertFalse(WorldBlacklistConfig.isBlacklistedNow("spawn"), "空黑名单下任何世界都不豁免");
    assertFalse(WorldBlacklistConfig.isBlacklistedNow("dungeon_1"));
    assertTrue(config.antiXrayAppliesTo("spawn"), "空黑名单下所有世界照常反矿透");
    assertTrue(config.antiXrayAppliesTo("world"));
    assertTrue(ProtocolLibAsyncListener.worldAllowed(config, "world"), "空黑名单下改写入口照常放行");
  }

  // ---------------------------------------------------------- 解析归一 + 指纹

  @Test
  void blacklistParsingNormalizesAndParticipatesInFingerprint() {
    WorldBlacklistConfig config = blacklist("""
        world-blacklist:
          - ' spawn '
          - spawn
          - ''
          - dungeon_*
        """);

    assertEquals(List.of("spawn", "dungeon_*"), config.patterns(),
        "去空白、跳过空条目并按声明序去重");
    assertNotEquals(WorldBlacklistConfig.fromPatterns(List.of()).configHash(), config.configHash(),
        "黑名单决定哪些世界完全不改写，必须参与配置指纹");
    assertEquals(config.configHash(), blacklist("""
        world-blacklist:
          - spawn
          - dungeon_*
        """).configHash(), "同一份名单的指纹必须一致（跨进程稳定）");
  }

  // ---------------------------------------------------------- 静态发布（热重载即时生效）

  @Test
  void publishUpdatesStaticGateImmediately() {
    try {
      WorldBlacklistConfig.publish(blacklist("world-blacklist: [spawn]\n"));
      assertTrue(WorldBlacklistConfig.isBlacklistedNow("spawn"), "发布后立即生效");

      // 模拟 reload：换成另一份名单
      WorldBlacklistConfig.publish(blacklist("world-blacklist: [arena]\n"));
      assertFalse(WorldBlacklistConfig.isBlacklistedNow("spawn"), "旧名单条目立即失效");
      assertTrue(WorldBlacklistConfig.isBlacklistedNow("arena"), "新名单条目立即生效");
    } finally {
      reset();
    }
    assertFalse(WorldBlacklistConfig.isBlacklistedNow("arena"), "复位后不再命中");
  }

  // ---------------------------------------------------------- 反矿透总开关

  @Test
  void disabledAntiXrayRejectsEveryWorld() {
    try {
      WorldBlacklistConfig.publish(blacklist("world-blacklist: [spawn]\n"));
      AntiXrayConfig off = antiXray("enabled: false\n");

      assertFalse(off.antiXrayAppliesTo("world"), "总开关关闭时对任何世界都不改写");
      assertFalse(ProtocolLibAsyncListener.worldAllowed(off, "world"), "改写入口同样拒绝");
      assertFalse(ProtocolLibAsyncListener.worldAllowed(null, "world"), "无配置时保守拒绝");
    } finally {
      reset();
    }
  }
}
