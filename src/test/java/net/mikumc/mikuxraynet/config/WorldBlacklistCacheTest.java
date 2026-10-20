package net.mikumc.mikuxraynet.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 世界黑名单的「按世界名结果缓存」与通配语义回归（{@link WorldBlacklistConfig}）。
 *
 * <p><b>为什么要给缓存单独一组用例</b>：缓存是为了消掉封包热路径上「每个包 {@code Pattern.matcher(...)}
 * 新建 Matcher + 跑一次正则」的开销；一旦缓存失效策略写错（例如把它做成静态字段、或热重载时复用旧实例），
 * 表现会是「改了 blacklist.yml 不生效 / 旧世界仍被豁免」这类极难复现的线上问题。因此这里逐条钉住：
 * <ol>
 *   <li>缓存命中路径的结果必须与首次计算完全一致（真 / 假两个方向都要稳定）；</li>
 *   <li>通配语义与既有实现逐例一致：只有 {@code *} 是通配（任意字符序列、可为空），{@code ?} 等正则
 *       元字符按字面量匹配；多个模式任一命中即豁免；大小写敏感；{@code null} 世界名不命中；</li>
 *   <li>热重载（{@link WorldBlacklistConfig#publish}）后旧实例的缓存不可达——新名单立即生效。</li>
 * </ol>
 *
 * <p>用例全部为纯内存判定，不依赖服务端，也不做任何时间断言。
 */
class WorldBlacklistCacheTest {

  /** 每个用例后复位静态活动实例，避免污染同 JVM 内的其它用例（与既有 WorldBlacklistTest 同做法）。 */
  @AfterEach
  void reset() {
    WorldBlacklistConfig.publish(WorldBlacklistConfig.fromPatterns(List.of()));
  }

  private static WorldBlacklistConfig blacklist(String content) {
    YamlConfiguration configuration = new YamlConfiguration();
    try {
      configuration.loadFromString(content);
    } catch (InvalidConfigurationException exception) {
      throw new IllegalStateException("测试用 YAML 不合法", exception);
    }
    return WorldBlacklistConfig.from(configuration);
  }

  @Test
  void repeatedQueriesStayConsistentAcrossCacheHits() {
    WorldBlacklistConfig config = blacklist("""
        world-blacklist:
          - spawn
          - dungeon_*
        """);

    // 同一批世界名反复查询：首次走实际匹配，之后命中缓存，两者结果必须完全一致（真/假两个方向）。
    for (int round = 0; round < 3; round++) {
      assertTrue(config.isBlacklisted("spawn"), "第 " + round + " 次：精确名命中必须稳定");
      assertTrue(config.isBlacklisted("dungeon_" + round), "第 " + round + " 次：通配命中必须稳定");
      assertFalse(config.isBlacklisted("world"), "第 " + round + " 次：缓存的「不命中」也必须稳定");
      assertFalse(config.isBlacklisted("dungeon"),
          "第 " + round + " 次：dungeon_* 要求字面量下划线，无后缀的 dungeon 不命中");
    }
  }

  @Test
  void starMatchesAnySequenceIncludingEmpty() {
    WorldBlacklistConfig config = blacklist("world-blacklist:\n  - dungeon_*\n");

    assertTrue(config.isBlacklisted("dungeon_1"), "* 匹配任意非空序列");
    assertTrue(config.isBlacklisted("dungeon_"), "* 允许空序列（与 world-overrides 的通配语义一致）");
    assertTrue(config.isBlacklisted("dungeon_nether_2"), "* 可跨多个下划线");
    assertFalse(config.isBlacklisted("dungeon"), "缺少字面量下划线不得命中");
    assertFalse(config.isBlacklisted("my_dungeon_1"), "前缀不匹配不得命中");
  }

  @Test
  void starCanAppearInTheMiddleAndRepeated() {
    WorldBlacklistConfig middle = blacklist("world-blacklist:\n  - 'res_*_nether'\n");
    assertTrue(middle.isBlacklisted("res_01_nether"), "中间的通配必须生效");
    assertFalse(middle.isBlacklisted("res_01"), "缺少后缀不得命中");

    WorldBlacklistConfig repeated = blacklist("world-blacklist:\n  - 'a*b*c'\n");
    assertTrue(repeated.isBlacklisted("abc"), "多个通配之间的片段均可为空");
    assertTrue(repeated.isBlacklisted("aXbYc"), "多个通配各自匹配任意序列");
    assertFalse(repeated.isBlacklisted("aXbY"), "末尾字面量 c 缺失不得命中");
  }

  @Test
  void questionMarkIsLiteralNotWildcard() {
    // 现状语义：只有 * 是通配；? [ ] . 等正则元字符都被整体转义，按字面量匹配。
    WorldBlacklistConfig exactOnly = blacklist("world-blacklist:\n  - 'dungeon_?'\n");
    assertTrue(exactOnly.isBlacklisted("dungeon_?"),
        "不含 * 的模式走精确名比较，? 是字面问号");
    assertFalse(exactOnly.isBlacklisted("dungeon_1"), "? 绝不能被当成「任意单字符」");

    WorldBlacklistConfig withStar = blacklist("world-blacklist:\n  - 'dungeon_*?'\n");
    assertTrue(withStar.isBlacklisted("dungeon_1?"), "含 * 时 ? 仍按字面量匹配");
    assertTrue(withStar.isBlacklisted("dungeon_?"), "* 可为空，剩下字面量 ?");
    assertFalse(withStar.isBlacklisted("dungeon_1"), "? 不是通配");
  }

  @Test
  void regexMetaCharactersAreEscapedLiterally() {
    // 防止将来把 \Q..\E 转义改坏：. [ ] ( ) + 等必须只匹配自身。
    WorldBlacklistConfig config = blacklist("world-blacklist:\n  - 'world[1]*'\n");
    assertTrue(config.isBlacklisted("world[1]x"), "字面量的 [ ] 必须被正确转义");
    assertFalse(config.isBlacklisted("world1x"), "[ ] 不得被解释为字符类");
  }

  @Test
  void anyOfMultiplePatternsHits() {
    WorldBlacklistConfig config = blacklist("""
        world-blacklist:
          - spawn
          - dungeon_*
          - arena_*
          - 'res_*_nether'
        """);

    assertTrue(config.isBlacklisted("spawn"), "精确名命中");
    assertTrue(config.isBlacklisted("dungeon_7"), "第一个通配命中");
    assertTrue(config.isBlacklisted("arena_2"), "第二个通配命中");
    assertTrue(config.isBlacklisted("res_9_nether"), "第三个通配命中");
    assertFalse(config.isBlacklisted("world"), "全部模式都不命中");
  }

  @Test
  void reloadRebuildsCacheSoNoStaleResultSurvives() {
    WorldBlacklistConfig.publish(blacklist("world-blacklist: [spawn]\n"));
    assertTrue(WorldBlacklistConfig.isBlacklistedNow("spawn"), "旧名单：spawn 命中（结果进入旧实例缓存）");
    assertFalse(WorldBlacklistConfig.isBlacklistedNow("arena"), "旧名单：arena 不命中（同样被缓存）");

    // 模拟热重载：publish 换实例 → 旧实例连同它的 resultCache 一起不可达，新实例重新判定。
    WorldBlacklistConfig.publish(blacklist("world-blacklist: [arena]\n"));
    assertFalse(WorldBlacklistConfig.isBlacklistedNow("spawn"),
        "热重载后旧实例缓存不得残留（缓存是实例字段，随 publish 重建）");
    assertTrue(WorldBlacklistConfig.isBlacklistedNow("arena"),
        "热重载后新名单立即生效（含此前被判为不命中的世界）");
  }

  @Test
  void emptyBlacklistNeverMatchesAndNullIsSafe() {
    WorldBlacklistConfig config = WorldBlacklistConfig.fromPatterns(List.of());

    assertTrue(config.isEmpty(), "空名单必须可识别（诊断与热路径早退都依赖它）");
    assertFalse(config.isBlacklisted("spawn"), "空名单下任何世界都不豁免");
    assertFalse(config.isBlacklisted(null), "null 世界名不得判为黑名单");
    assertFalse(WorldBlacklistConfig.fromPatterns(null).isBlacklisted("spawn"), "null 列表等价于空名单");
  }

  @Test
  void matchingIsCaseSensitiveLikeWorldOverrides() {
    WorldBlacklistConfig config = blacklist("world-blacklist: [Spawn]\n");

    assertTrue(config.isBlacklisted("Spawn"), "大小写一致的精确名命中");
    assertFalse(config.isBlacklisted("spawn"), "世界名大小写敏感（与 world-overrides 语义一致）");
    assertFalse(config.isBlacklisted("SPAWN"));
  }

  @Test
  void duplicateAndBlankPatternsAreNormalizedBeforeCaching() {
    WorldBlacklistConfig config = blacklist("""
        world-blacklist:
          - ' spawn '
          - spawn
          - ''
          - dungeon_*
        """);

    assertTrue(config.isBlacklisted("spawn"), "去空白后的精确名必须命中");
    assertTrue(config.isBlacklisted("dungeon_1"), "去空白后的通配必须命中（前导空格不得让模式失效）");
    assertFalse(config.isBlacklisted(""), "空条目被丢弃，不会变成一个「匹配空世界名」的模式");
  }
}
