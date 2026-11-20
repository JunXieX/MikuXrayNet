package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 近身判定与「连接会话」的回归：判定只依赖「每 tick 刷新的位置快照」，<b>不依赖任何移动/传送事件</b>；
 * 快照的所有写入都归一次连接（{@link PlayerPositionSnapshots.Session}）所有。
 *
 * <p>锁定的是实测复现过的两类缺陷：
 * <ol>
 *   <li>旧实现由 {@code PlayerMoveEvent} 维护坐标缓存，而 {@code PlayerTeleportEvent} 有独立 HandlerList
 *       ——传送后缓存陈旧，近身判定按传送<b>前</b>的坐标分类（实测 {@code /tp} 两个假人到距变更 2.2 格与
 *       10.5 格时分类恰好反转）；</li>
 *   <li>快照按 UUID 存、没有会话：同名世界重连后，上一条连接迟到的刷新仍能写进新连接的位置表
 *       （世界名一模一样，下游无法二次发现）。现在换代 = 换持有者对象，旧持有者的写入对新连接不可见。</li>
 * </ol>
 */
class PlayerPositionSnapshotsTest {

  @Test
  @DisplayName("仅刷新位置快照即改变近身分类（传送后立即按新位置判定，不依赖任何移动/传送事件）")
  void refreshingSnapshotAloneReclassifiesAfterTeleport() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID teleportedIn = UUID.randomUUID();
    UUID teleportedOut = UUID.randomUUID();
    PlayerPositionSnapshots.Session inSession = snapshots.beginSession(teleportedIn);
    PlayerPositionSnapshots.Session outSession = snapshots.beginSession(teleportedOut);
    // 距变更 2.2 格与 10.5 格（与实测复现用的两个假人一致）
    List<Update<Integer>> updates = List.of(new Update<>(0, 64, 0, 1));

    // 传送前：一个远（40 格 → 进合并窗口）、一个近（2 格 → 立即放行）
    snapshots.refresh(inSession, "world", 40.0D, 64.0D, 0.0D);
    snapshots.refresh(outSession, "world", 2.0D, 64.0D, 0.0D);
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedIn), updates, 8),
        "传送前 40 格 → 照常合并");
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedOut), updates, 8),
        "传送前 2 格 → 立即放行");

    // 只刷新快照（等价于传送后每 tick 的自动刷新），不产生任何移动/传送事件：
    // 分类必须随之改变，而不是沿用旧坐标（旧事件缓存正是在这里漏掉传送，导致分类反转）
    snapshots.refresh(inSession, "world", 2.2D, 64.0D, 0.0D);
    snapshots.refresh(outSession, "world", 10.5D, 64.0D, 0.0D);
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedIn), updates, 8),
        "传送后 2.2 格 → 必须立即判为近身（旧实现会按传送前的 40 格误判为合并）");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedOut), updates, 8),
        "传送后 10.5 格 → 必须立即判为远（分类不得反转）");

    // fail-open 与边界语义：快照缺失（刚登录/刚重置）→ 立即放行；边界仍为「含 8 格、超 8 格不合」
    UUID unknown = UUID.randomUUID();
    assertNull(snapshots.get(unknown));
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(unknown), updates, 8),
        "快照缺失时必须 fail-open（按近身处理，绝不制造延迟）");
    PlayerPositionSnapshots.Session unknownSession = snapshots.beginSession(unknown);
    snapshots.refresh(unknownSession, "world", 8.0D, 64.0D, 0.0D);
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(unknown), updates, 8),
        "恰好 8 格（含边界）仍应命中");
    snapshots.refresh(unknownSession, "world", 9.0D, 64.0D, 0.0D);
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(unknown), updates, 8),
        "超出半径不得命中");

    // 结束会话（退出）后又回到 fail-open；会话数也一并归零
    snapshots.endSession(teleportedIn);
    snapshots.endSession(teleportedOut);
    snapshots.endSession(unknown);
    assertEquals(0, snapshots.size());
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedIn), updates, 8),
        "会话结束后必须 fail-open");
  }

  @Test
  @DisplayName("同一方块坐标内连续刷新不重发快照（幂等、不 new 记录），跨入新方块才重发并更新判定")
  void sameBlockRefreshSkipsPublishAndIsIdempotent() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session session = snapshots.beginSession(player);
    // 变更方块 (0,64,0)，半径 8
    List<Update<Integer>> updates = List.of(new Update<>(0, 64, 0, 1));

    snapshots.refresh(session, "world", 0.3D, 64.2D, 0.7D); // 方块 (0,64,0)
    PlayerPositionSnapshots.Position first = snapshots.get(player);
    assertNotNull(first);
    assertTrue(PlayerPositionSnapshots.immediatePass(first, updates, 8), "0 格 → 近身");

    // 同一方块内连续刷新（0.3→0.9、0.7→0.999）：不得发布新快照，判定结果也不变
    snapshots.refresh(session, "world", 0.9D, 64.99D, 0.0D);
    assertSame(first, snapshots.get(player), "同一方块坐标内刷新必须跳过发布（不 new 记录）");
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(player), updates, 8),
        "跳过发布后判定结果必须与跳过前一致");
    snapshots.refresh(session, "world", 0.0D, 64.0D, 0.999D);
    assertSame(first, snapshots.get(player), "再次刷新仍在同一方块，依旧跳过");

    // 移动到新方块 (9,64,0)：必须重发，判定随之更新（9 格 > 8 → 不再近身）
    snapshots.refresh(session, "world", 9.0D, 64.0D, 0.0D);
    assertNotSame(first, snapshots.get(player), "跨入新方块必须发布新快照");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(player), updates, 8),
        "新方块距变更 9 格 → 判定必须随位置更新为「远」");
  }

  /**
   * B2 回归：{@code refresh} 必须回报「刷新前的世界名」，调用方（BlockChangeMerger）据此在跨世界切换时
   * 丢弃旧世界的待发缓冲。若世界未变却回报了旧名字，就会把正常合并在同世界内的缓冲误丢弃。
   */
  @Test
  @DisplayName("refresh 返回刷新前的世界名：只有真的跨世界才与当前世界名不同")
  void refreshReportsPreviousWorld() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session session = snapshots.beginSession(player);

    assertNull(snapshots.refresh(session, "world", 0.5D, 64.0D, 0.5D), "首次快照没有「上一个世界」");
    assertEquals("world", snapshots.refresh(session, "world", 0.5D, 64.0D, 0.5D),
        "世界未变（且同一方块、不发布）时返回当前世界名，调用方不得据此误判跨世界");
    assertEquals("world", snapshots.refresh(session, "world", 3.5D, 64.0D, 0.5D),
        "同世界内跨入新方块：仍返回同一世界名");
    assertEquals("world", snapshots.refresh(session, "world_nether", 0.5D, 64.0D, 0.5D),
        "跨世界：必须返回旧世界名，供调用方丢弃旧世界缓冲");
    assertEquals("world_nether", snapshots.refresh(session, "world_nether", 0.5D, 64.0D, 0.5D));
    assertEquals("world_nether", snapshots.get(player).world());
  }

  /**
   * P3 回归：黑名单世界只维护「当前世界」、<b>跳过坐标捕获</b>（省掉每 tick 三次 getter）。
   *
   * <p>要钉住三件事：① 世界切换仍要回报旧世界名（跨世界丢弃靠它）；② 坐标被标记为未知时近身判定必须
   * fail-open（按近身立即放行），绝不能拿与当前世界无关的旧坐标算距离；③ 切出黑名单世界、恢复正常刷新后
   * 必须重新按真实距离判定（positionKnown 不能一直停在 false）。
   */
  @Test
  @DisplayName("refreshWorldOnly 只更新世界并让近身判定 fail-open；恢复正常刷新后回到按距离判定")
  void worldOnlyRefreshKeepsWorldForCrossWorldDetectionAndFailsOpen() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session session = snapshots.beginSession(player);
    List<Update<Integer>> far = List.of(new Update<>(200, 64, 200, 1));

    // 正常世界：精确坐标 → 远处的变更不立即放行
    assertNull(snapshots.refresh(session, "world", 0.5D, 64.0D, 0.5D), "首次刷新没有「上一个世界」");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(player), far, 8),
        "坐标已知时按距离判定（远处不立即放行）");

    // 进入黑名单世界：只写世界名、不读坐标
    assertEquals("world", snapshots.refreshWorldOnly(session, "world_blacklisted"),
        "世界切换必须回报旧世界名（供跨世界丢弃）");
    assertEquals("world_blacklisted", snapshots.get(player).world());
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(player), far, 8),
        "坐标未知时必须 fail-open（按近身立即放行，绝不用旧世界坐标算距离）");

    // 仍在黑名单世界：重复调用不发布新快照（零分配、零写入）
    PlayerPositionSnapshots.Position inBlacklist = snapshots.get(player);
    assertEquals("world_blacklisted", snapshots.refreshWorldOnly(session, "world_blacklisted"));
    assertSame(inBlacklist, snapshots.get(player), "世界未变且坐标已未知时不得重复发布");

    // 切出黑名单世界：恢复正常刷新 → 坐标重新可信，判定回到按距离
    assertEquals("world_blacklisted", snapshots.refresh(session, "world", 0.5D, 64.0D, 0.5D),
        "切出黑名单世界同样必须回报旧世界名");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(player), far, 8),
        "恢复正常刷新后必须回到按距离判定");
  }

  @Test
  @DisplayName("负数坐标按 floor 语义取方块（绝非截断），跨负数方块边界时判定随之更新")
  void negativeCoordinatesUseFloorAndReclassifyOnBlockChange() {
    // floor 语义：-0.5 / -0.6 / -1.0 同属方块 -1（截断会错把 -0.5 算成 0），-1.0 是方块 -1 的起点，
    // 再往负一点（-1.0001）才跨入方块 -2
    assertEquals(-1, PlayerPositionSnapshots.blockCoordinate(-0.5D));
    assertEquals(-1, PlayerPositionSnapshots.blockCoordinate(-0.6D));
    assertEquals(-1, PlayerPositionSnapshots.blockCoordinate(-1.0D));
    assertEquals(-2, PlayerPositionSnapshots.blockCoordinate(-1.0001D));

    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session session = snapshots.beginSession(player);
    // 变更方块 (7,64,0)，半径 8：玩家方块 -1 距其 8 格（含边界）→ 近身；方块 -2 距其 9 格 → 远
    List<Update<Integer>> updates = List.of(new Update<>(7, 64, 0, 1));

    snapshots.refresh(session, "world", -0.5D, 64.0D, 0.0D); // 方块 (-1,64,0)
    PlayerPositionSnapshots.Position negative = snapshots.get(player);
    assertTrue(PlayerPositionSnapshots.immediatePass(negative, updates, 8),
        "方块 (-1,64,0) 距变更恰好 8 格（含边界）→ 近身");

    // -0.5 → -0.6 仍属同一方块 -1：不重发、判定不变
    snapshots.refresh(session, "world", -0.6D, 64.4D, 0.3D);
    assertSame(negative, snapshots.get(player), "-0.5 → -0.6 属同一方块，不得重发快照");
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(player), updates, 8),
        "同一方块内判定不得改变");

    // -1.0001 跨入新方块 -2：必须重发，判定随之翻转为「远」
    snapshots.refresh(session, "world", -1.0001D, 64.0D, 0.0D);
    assertNotSame(negative, snapshots.get(player), "跨入方块 -2 必须发布新快照");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(player), updates, 8),
        "方块 -2 距变更 9 格 → 判定必须更新为「远」");
  }

  /**
   * 心跳回归：冻结的会话必须被判成「过期」，从而按未知处理（fail-open），绝不能继续当作可靠依据。
   *
   * <p>事故形态：Folia 实体调度器任务随实体被移出旧 world 退役后不再刷新（退役不保证回调清理入口），
   * 快照保持「旧世界 + 旧坐标」且内容完全正常——只盯内容的话永远发现不了它已经死了。因此判据必须是
   * 「多久没刷新」，而不是「内容看起来对不对」。
   */
  @Test
  @DisplayName("心跳过期 → isStale 为真，近身判定按未知 fail-open")
  void staleSnapshotIsReportedAndFailsOpen() {
    long[] now = {1_000_000_000L};
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots(() -> now[0]);
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session session = snapshots.beginSession(player);
    List<Update<Integer>> far = List.of(new Update<>(200, 64, 200, 1));

    snapshots.refresh(session, "world", 0.5D, 64.0D, 0.5D);
    assertFalse(snapshots.isStale(player), "刚刷新过 → 心跳新鲜");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(player), far, 8),
        "心跳新鲜时按真实距离判定（200 格外 → 不近身）");

    now[0] += PlayerPositionSnapshots.STALE_NANOS + 1L;
    assertTrue(snapshots.isStale(player), "超过阈值没有心跳 → 必须判过期");

    snapshots.refresh(session, "world", 0.5D, 64.0D, 0.5D);
    assertFalse(snapshots.isStale(player), "重新刷新后心跳恢复");

    snapshots.endSession(player);
    assertTrue(snapshots.isStale(player), "没有会话也算过期");
    assertNull(snapshots.get(player));
  }

  @Test
  @DisplayName("同一方块内不重发记录，但心跳必须每次刷新都更新")
  void heartbeatUpdatesEvenWhenRecordIsReused() {
    long[] now = {5_000_000_000L};
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots(() -> now[0]);
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session session = snapshots.beginSession(player);
    snapshots.refresh(session, "world", 0.3D, 64.2D, 0.7D);
    PlayerPositionSnapshots.Position first = snapshots.get(player);

    now[0] += PlayerPositionSnapshots.STALE_NANOS + 1L;
    assertTrue(snapshots.isStale(player));

    // 同一方块坐标：记录被复用（不 new），但心跳必须更新——否则「活着但静止」的玩家会被误判成任务已停
    snapshots.refresh(session, "world", 0.4D, 64.3D, 0.8D);
    assertSame(first, snapshots.get(player), "同一方块内不重发记录");
    assertFalse(snapshots.isStale(player), "心跳必须随每次刷新更新");
  }

  /**
   * 会话换代：同一 UUID 的下一条连接拿到新的持有者，旧持有者的刷新<b>一条都不许写</b>。
   *
   * <p>只比 UUID 时防不住这条：同名世界重连后世界名完全相同，旧实体迟到的刷新会被当成「同一世界的
   * 正常刷新」写进新连接的位置表——近身判定从此按上一条连接的坐标分类，而且没有任何日志能看出来。
   */
  @Test
  @DisplayName("旧会话的迟到刷新写不进新连接（同名世界重连）")
  void lateRefreshFromPreviousSessionIsIgnored() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session first = snapshots.beginSession(player);
    assertNull(snapshots.refresh(first, "world", 0.5D, 64.0D, 0.5D), "首次刷新没有「上一个世界」");
    assertNotNull(snapshots.get(player));

    // 重连：新连接 = 新会话（同一个世界名）
    PlayerPositionSnapshots.Session second = snapshots.beginSession(player);
    assertNotSame(first, second, "重连必须换持有者对象（不能复用旧会话）");
    assertTrue(second.id() > first.id(), "会话号必须单调递增（进程级，不退化为 1）");
    assertFalse(snapshots.isCurrent(first), "旧会话已不是当前会话");
    assertTrue(snapshots.isCurrent(second));
    assertNull(snapshots.get(player), "新连接在首次刷新前没有快照（fail-open，绝不沿用旧坐标）");

    // 旧实体迟到的刷新：无操作，不得写进新会话，也不得返回「上一个世界」触发误判
    assertNull(snapshots.refresh(first, "world", 30.0D, 64.0D, 30.0D),
        "旧会话的迟到刷写必须被拒绝（返回 null 表示「不是本代」）");
    assertNull(snapshots.get(player), "旧会话的写入不得出现在新会话里");

    // 新会话正常工作
    assertNull(snapshots.refresh(second, "world", 2.0D, 64.0D, 0.5D));
    assertEquals(2.0D, snapshots.get(player).x(), 0.0001D);
    assertFalse(snapshots.isCurrent(first));
    assertNull(snapshots.refresh(first, "world", 40.0D, 64.0D, 40.0D), "旧会话仍被拒绝");
    assertEquals(2.0D, snapshots.get(player).x(), 0.0001D, "新会话的坐标不得被旧会话覆盖");
  }

  /**
   * ABA 回归：退出（删表）后同 UUID 重新加入，旧持有者对象仍被旧任务持有。
   *
   * <p>旧实现用「会话号从 1 起 + 三张表非原子 check→put」，旧任务在「删表之后、新会话刷新之前」写回时
   * 会命中新代；现在写入只作用于它自己手里的旧持有者，而 {@link PlayerPositionSnapshots#get(UUID)} 只读
   * 表里的当前会话，因此永远不会读到上一条连接的数据。
   */
  @Test
  @DisplayName("退出再登录：旧持有者的迟到写入不污染新会话（无 ABA）")
  void lateWriteAfterQuitAndRejoinCannotPolluteNewSession() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session before = snapshots.beginSession(player);
    snapshots.refresh(before, "world", 100.0D, 64.0D, 100.0D);

    snapshots.endSession(player);
    assertNull(snapshots.get(player), "退出后读不到任何快照");
    assertNull(snapshots.session(player));

    // 重建会话：编号必须继续增大（不能从 1 重来，否则与旧持有者语义混淆）
    PlayerPositionSnapshots.Session after = snapshots.beginSession(player);
    assertTrue(after.id() > before.id(), "重连会话号必须单调递增");
    assertFalse(snapshots.isCurrent(before));

    // 旧任务在「删表之后」写回：只作用于旧持有者
    assertNull(snapshots.refresh(before, "world", 0.0D, 64.0D, 0.0D));
    assertNull(snapshots.get(player), "旧任务的写回不得出现在新会话里");
    assertTrue(snapshots.isStale(player), "新会话尚未刷新 → 过期（读取侧 fail-open）");

    snapshots.refresh(after, "world", 1.0D, 64.0D, 1.0D);
    assertEquals(1.0D, snapshots.get(player).x(), 0.0001D);
    assertNull(snapshots.refresh(before, "world", 50.0D, 64.0D, 50.0D));
    assertEquals(1.0D, snapshots.get(player).x(), 0.0001D, "旧持有者的迟到写入不得覆盖新会话");
  }

  /**
   * 临时读失败只失效「位置 + 心跳」，<b>保留会话令牌</b>：否则下游会把已有待发批判成「上一代连接」
   * 而整批不投递——用一次瞬时故障换永久损失。
   */
  @Test
  @DisplayName("invalidate 只清位置与心跳，不结束会话（临时读失败不得换连接）")
  void invalidateKeepsSessionToken() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID player = UUID.randomUUID();
    PlayerPositionSnapshots.Session session = snapshots.beginSession(player);
    snapshots.refresh(session, "world", 0.5D, 64.0D, 0.5D);

    snapshots.invalidate(session);
    assertNull(snapshots.get(player), "位置已失效，读取侧 fail-open");
    assertTrue(snapshots.isStale(player), "心跳已清 → 过期");
    assertSame(session, snapshots.session(player), "会话令牌必须保留（同一代连接）");
    assertTrue(snapshots.isCurrent(session));

    // 旧会话再调用 invalidate 也无害；新会话（重连后）的 invalidate 必须拒绝
    PlayerPositionSnapshots.Session next = snapshots.beginSession(player);
    snapshots.refresh(next, "world", 1.0D, 64.0D, 1.0D);
    snapshots.invalidate(session);
    assertNotNull(snapshots.get(player), "旧会话的 invalidate 不得清掉新会话的快照");
    assertEquals(1.0D, snapshots.get(player).x(), 0.0001D);

    snapshots.invalidate(next);
    assertNull(snapshots.get(player));
  }
}
