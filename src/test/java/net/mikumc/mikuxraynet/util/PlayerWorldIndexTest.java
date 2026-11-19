package net.mikumc.mikuxraynet.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 世界上下文快照（{@link PlayerWorldIndex}）的行为测试，全部离线可跑（不启动服务端、不构造 World）。
 *
 * <p>用包内可见的 {@link PlayerWorldIndex#isolated()} 拿到一个「未安装」的独立实例来驱动真实的状态机：
 * 玩家表与一次性提示现在都是<b>实例所有</b>，静态门面只读一个 volatile 实例，因此这些用例既覆盖行为，
 * 也钉住「退休实例写不进新实例」的停启隔离。
 *
 * <p>钉住的契约：
 * <ol>
 *   <li>同一世界身份重复发布<b>复用同一个实例</b>（引用相等即「同一代次」，在途区块改写不会被误判跨会话）；</li>
 *   <li>世界身份变化分配<b>进程级唯一</b>的新代次（跨玩家、跨实例都不撞号）；</li>
 *   <li>退出清键后同 UUID 同世界重连<b>不会</b>与旧代次撞号；</li>
 *   <li>启动快照（{@code publishIfAbsent}）<b>绝不覆盖</b>期间已发布的新会话；</li>
 *   <li>静态门面只在实例被安装后可见：退休/未安装实例的写入不会出现在热路径上。</li>
 * </ol>
 */
class PlayerWorldIndexTest {

  private static final String OVERWORLD = "world";
  private static final String NETHER = "world_nether";

  private PlayerWorldIndex index;

  @BeforeEach
  void setUp() {
    PlayerWorldIndex.stop();
    index = PlayerWorldIndex.isolated();
  }

  @AfterEach
  void tearDown() {
    index.clear();
    PlayerWorldIndex.stop();
  }

  @Test
  void publishedContextIsReadableByBothEntries() {
    UUID player = UUID.randomUUID();
    assertTrue(index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24)));

    PlayerWorldIndex.WorldContext stored = index.contextOf(player);
    assertNotNull(stored);
    assertEquals(OVERWORLD, index.worldNameOf(player));
    assertEquals(-64, stored.minHeight());
    assertEquals(24, stored.sectionCount());
    assertEquals(World.Environment.NORMAL, stored.environment());
    assertTrue(stored.session() > 0L, "首次发布必须分配一个正代次");
  }

  @Test
  void sameIdentityRepublishKeepsSameInstanceAndSession() {
    UUID player = UUID.randomUUID();
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    PlayerWorldIndex.WorldContext first = index.contextOf(player);

    // 同 tick 内 changedWorld 与 respawn 各发布一次、以及重复的 join，都属于「同一世界身份」。
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    PlayerWorldIndex.WorldContext second = index.contextOf(player);

    assertSame(first, second, "同身份重复发布必须复用同一个不可变实例（引用相等即同一代次）");
    assertEquals(first.session(), second.session());
  }

  @Test
  void identityChangeAllocatesFreshSession() {
    UUID player = UUID.randomUUID();
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    PlayerWorldIndex.WorldContext overworld = index.contextOf(player);

    index.publish(player, context(NETHER, World.Environment.NETHER, 0, 16));
    PlayerWorldIndex.WorldContext nether = index.contextOf(player);

    assertNotSame(overworld, nether);
    assertEquals(NETHER, nether.worldName());
    assertEquals(World.Environment.NETHER, nether.environment());
    assertTrue(nether.session() > overworld.session(), "跨维度必须换一个更大的代次");
  }

  @Test
  void heightOnlyChangeAlsoAllocatesFreshSession() {
    UUID player = UUID.randomUUID();
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    long before = index.contextOf(player).session();

    // 同名世界重新加载成不同高度：高度是编码参数，必须视为新身份，绝不能沿用旧快照。
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, 0, 16));
    PlayerWorldIndex.WorldContext after = index.contextOf(player);

    assertEquals(0, after.minHeight());
    assertTrue(after.session() > before, "高度变化必须换代次");
  }

  @Test
  void quitThenRejoinSameWorldDoesNotCollide() {
    UUID player = UUID.randomUUID();
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    long first = index.contextOf(player).session();

    index.forget(player);
    assertNull(index.contextOf(player));
    assertEquals(0, index.size());

    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    long second = index.contextOf(player).session();

    assertNotEquals(first, second, "退出重连后必须拿到全新的代次（旧实现从 1 重新计数会撞号）");
    assertTrue(second > first);
  }

  @Test
  void bootstrapSnapshotNeverOverwritesNewerSession() {
    UUID player = UUID.randomUUID();
    index.publish(player, context(NETHER, World.Environment.NETHER, 0, 16));
    PlayerWorldIndex.WorldContext published = index.contextOf(player);

    // 启动快照晚到：它拿的是旧世界的快照，绝不能把已经在用的新会话覆盖回去。
    assertFalse(index.publishIfAbsent(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24)));

    assertSame(published, index.contextOf(player));
    assertEquals(NETHER, index.worldNameOf(player));
  }

  @Test
  void bootstrapSnapshotFillsEmptySlot() {
    UUID player = UUID.randomUUID();
    assertTrue(index.publishIfAbsent(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24)));

    PlayerWorldIndex.WorldContext stored = index.contextOf(player);
    assertNotNull(stored);
    assertTrue(stored.session() > 0L, "启动快照同样要分配正代次");
  }

  @Test
  void sessionsAreUniqueAcrossPlayers() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    index.publish(first, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    index.publish(second, context(OVERWORLD, World.Environment.NORMAL, -64, 24));

    assertNotEquals(index.contextOf(first).session(), index.contextOf(second).session(),
        "代次必须全局唯一");
  }

  @Test
  void sessionsAreUniqueAcrossInstances() {
    UUID player = UUID.randomUUID();
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));

    // 停启隔离的另一半：新实例（下次 start 的实例）不能复用旧实例的代次编号。
    PlayerWorldIndex fresh = PlayerWorldIndex.isolated();
    try {
      fresh.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
      assertNotEquals(index.contextOf(player).session(), fresh.contextOf(player).session(),
          "代次必须在实例之间也唯一（否则热重载前后同 UUID 同世界会撞号）");
    } finally {
      fresh.clear();
    }
  }

  @Test
  void staticFacadeIsEmptyWithoutInstalledInstance() {
    UUID player = UUID.randomUUID();
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));

    // 未安装为当前实例：热路径（封包线程）必须看不到它 —— 退休/未启用实例写不进新会话。
    assertNull(PlayerWorldIndex.context(player));
    assertNull(PlayerWorldIndex.worldName(player));
    assertNotNull(index.contextOf(player), "实例自身的表仍可被自身读到");
  }

  @Test
  void retiredInstanceStaysInvisibleAfterClear() {
    UUID player = UUID.randomUUID();
    index.publish(player, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    index.clear();

    assertNull(index.contextOf(player), "停用清表后实例内不得残留");
    assertEquals(0, index.size());
    assertNull(PlayerWorldIndex.context(player));
  }

  @Test
  void forgetRemovesOnlyThatPlayer() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    index.publish(first, context(OVERWORLD, World.Environment.NORMAL, -64, 24));
    index.publish(second, context(NETHER, World.Environment.NETHER, 0, 16));

    index.forget(first);

    assertNull(index.contextOf(first));
    assertNotNull(index.contextOf(second));
    assertEquals(1, index.size());
  }

  @Test
  void unknownPlayerHasNoContext() {
    UUID player = UUID.randomUUID();
    assertNull(index.contextOf(player));
    assertNull(index.worldNameOf(player));
    assertNull(index.contextOf(null));
    assertNull(index.worldNameOf(null));
    assertNull(PlayerWorldIndex.context(null));
    assertNull(PlayerWorldIndex.worldName(null));
  }

  @Test
  void captureWithoutPlayerIsNull() {
    assertNull(PlayerWorldIndex.capture(null), "取不到实体时必须返回 null（调用方据此 fail-open）");
  }

  @Test
  void stopWithoutInstalledInstanceIsSafe() {
    PlayerWorldIndex.stop();
    PlayerWorldIndex.stop();
    assertNull(PlayerWorldIndex.context(UUID.randomUUID()));
  }

  /** 构造一份离线上下文：{@code world} 传 null（仅影响邻块抓取，不影响维度与高度）。 */
  private static PlayerWorldIndex.WorldContext context(String worldName, World.Environment environment,
      int minHeight, int sectionCount) {
    return new PlayerWorldIndex.WorldContext(worldName, null, environment, minHeight, sectionCount, 0L);
  }
}
