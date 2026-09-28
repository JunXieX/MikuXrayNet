package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.mikumc.mikuxraynet.bandwidth.ThrottlePipeline;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 反矿透世界黑名单（{@code antixray.yml: world-blacklist}）：匹配语义、优先级、各入口的跳过判定
 * 与「带宽不受影响」的反向断言。
 *
 * <p>反矿透各个入口（区块改写 {@link ProtocolLibAsyncListener}、周期巡检与事件即时显形
 * {@link ProximityRevealer}、方块变更观察 {@link BlockChangeRevealListener}）都统一走
 * {@link AntiXrayConfig#isBlacklisted(String)} / {@link AntiXrayConfig#antiXrayAppliesTo(String)}，
 * 因此这里对这些判定函数做端到端断言（判定为假即代表该路径不建任务、不登记延迟、不发包）。
 */
class WorldBlacklistTest {

  private static AntiXrayConfig config(String content) {
    return AntiXrayConfig.from(yaml(content));
  }

  private static BandwidthConfig bandwidth(String content) {
    return BandwidthConfig.from(yaml(content));
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

  // ---------------------------------------------------------- 精确名

  @Test
  void exactPatternHitsAndMisses() {
    AntiXrayConfig config = config("world-blacklist: [spawn]\n");

    assertEquals(List.of("spawn"), config.worldBlacklist(), "原样保留精确名（声明序）");
    assertTrue(config.isBlacklisted("spawn"), "精确名必须命中");
    assertFalse(config.isBlacklisted("spawn_nether"), "非完全相等不得命中（精确名不做前缀匹配）");
    assertFalse(config.isBlacklisted("SPAWN"), "世界名大小写敏感（与 world-overrides 的匹配语义一致）");
    assertFalse(config.isBlacklisted(null), "null 世界名不得判为黑名单");

    assertFalse(config.antiXrayAppliesTo("spawn"), "黑名单世界：反矿透一律不适用");
    assertTrue(config.antiXrayAppliesTo("spawn_nether"), "非黑名单世界：照常反矿透");
  }

  // ---------------------------------------------------------- 通配

  @Test
  void wildcardPatternHitsOnlyWhenSuffixPresent() {
    AntiXrayConfig config = config("world-blacklist:\n  - dungeon_*\n");

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
    AntiXrayConfig config = config("""
        world-blacklist: [arena]
        world-overrides:
          arena:
            enabled: true
            mode: enclosed
        """);

    assertEquals(0, config.matchOverride("arena"),
        "world-overrides 确实为该世界匹配到覆盖段（否则本测试无法证明优先级）");
    assertTrue(config.isBlacklisted("arena"), "黑名单判定独立于 world-overrides");
    assertFalse(config.antiXrayAppliesTo("arena"),
        "黑名单优先级最高：即便 world-overrides 显式覆盖，仍不启用反矿透（要重新启用须先从黑名单移除）");
  }

  // ---------------------------------------------------------- 区块改写入口

  @Test
  void chunkRewriteEntryRejectsBlacklistedWorld() {
    AntiXrayConfig blacklisted = config("world-blacklist: [spawn]\n");

    assertFalse(ProtocolLibAsyncListener.worldAllowed(blacklisted, "spawn"),
        "区块改写入口判定必须为 false：不建任务、不登记延迟、不抓邻块快照、不读写磁盘缓存");
    assertTrue(ProtocolLibAsyncListener.worldAllowed(blacklisted, "world"), "非黑名单世界照常改写");

    assertFalse(ProtocolLibAsyncListener.worldAllowed(config("enabled: false\nworld-blacklist: [spawn]\n"),
        "world"), "总开关关闭时对任何世界都不改写");
    assertFalse(ProtocolLibAsyncListener.worldAllowed(null, "world"), "无配置时保守拒绝");
  }

  // ---------------------------------------------------------- 显形入口（周期 + 事件即时）

  @Test
  void revealEntryPointsUseTheUnifiedBlacklistGate() {
    AntiXrayConfig config = config("world-blacklist: [spawn]\n");

    // 周期巡检（ProximityRevealer#reveal）与事件即时显形（#revealImmediately / #onBlockChangeObserved）
    // 共用同一判定出口，因此这里断言其取值为 false 即代表两条路径都跳过。
    assertFalse(config.antiXrayAppliesTo("spawn"), "周期巡检：黑名单世界不取候选、不发包");
    assertTrue(config.isBlacklisted("spawn"),
        "事件即时显形：黑名单世界在 onBlockChangeObserved 处直接返回，不调度补发");
    assertTrue(config.antiXrayAppliesTo("spawn_normal"), "非黑名单世界照常显形");
  }

  // ---------------------------------------------------------- 方块变更观察入口

  @Test
  void changeObservationSkipsBlacklistedWorld() {
    AntiXrayConfig config = config("world-blacklist: [spawn]\n");

    assertTrue(BlockChangeRevealListener.isBlacklisted(config, "spawn"),
        "方块变更观察入口判定为 true：不注销显形、不驱动磁盘缓存代次、不触发事件显形");
    assertFalse(BlockChangeRevealListener.isBlacklisted(config, "world"), "非黑名单世界照常观察");
    assertFalse(BlockChangeRevealListener.isBlacklisted(null, "world"), "无配置时不做黑名单判定");
  }

  // ---------------------------------------------------------- 反向断言：带宽不受影响

  @Test
  void bandwidthModulesAreUnaffectedByTheBlacklist() {
    AntiXrayConfig anti = config("world-blacklist: [spawn]\n");

    assertTrue(anti.isBlacklisted("spawn"));
    // 黑名单是「反矿透模块的世界豁免」，不是全局停机：统一入口判否，但总开关不受影响。
    assertFalse(anti.antiXrayAppliesTo("spawn"), "黑名单世界：反矿透统一入口判否");
    assertTrue(anti.enabled(), "黑名单不改变反矿透总开关（带宽侧本就不读该配置）");

    ThrottlePipeline.ModulePlan plan = ThrottlePipeline.plan(bandwidth("enabled: true\n"));
    assertTrue(plan.entityPackets(), "零位移取消仍装配（带宽不受反矿透黑名单影响）");
    assertTrue(plan.blockChanges(), "方块变更合并仍装配");
    assertTrue(plan.entityCulling(), "实体剔除仍装配：黑名单世界的实体照常被剔除");
    assertTrue(plan.afk(), "AFK 降级仍装配");
    assertTrue(plan.latency(), "高延迟降视距仍装配");
  }

  // ---------------------------------------------------------- 空黑名单回归

  @Test
  void emptyBlacklistKeepsExistingBehavior() {
    AntiXrayConfig config = config("enabled: true\n");

    assertTrue(config.worldBlacklist().isEmpty(), "默认空列表（现有用户行为完全不变）");
    assertFalse(config.isBlacklisted("spawn"), "空黑名单下任何世界都不豁免");
    assertFalse(config.isBlacklisted("dungeon_1"));
    assertTrue(config.antiXrayAppliesTo("spawn"), "空黑名单下所有世界照常反矿透");
    assertTrue(config.antiXrayAppliesTo("world"));
    assertTrue(ProtocolLibAsyncListener.worldAllowed(config, "world"), "空黑名单下改写入口照常放行");
  }

  // ---------------------------------------------------------- 解析归一 + 指纹

  @Test
  void blacklistParsingNormalizesAndParticipatesInFingerprint() {
    AntiXrayConfig config = config("""
        world-blacklist:
          - ' spawn '
          - spawn
          - ''
          - dungeon_*
        """);

    assertEquals(List.of("spawn", "dungeon_*"), config.worldBlacklist(),
        "去空白、跳过空条目并按声明序去重");
    assertNotEquals(config("enabled: true\n").configHash(), config.configHash(),
        "黑名单决定哪些世界完全不改写，必须参与配置指纹");
  }
}