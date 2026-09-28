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
 * 近身判定的回归：判定只依赖「每 tick 刷新的位置快照」，<b>不依赖任何移动/传送事件</b>。
 *
 * <p>锁定的是实测复现过的缺陷：旧实现由 {@code PlayerMoveEvent} 维护坐标缓存，而
 * {@code PlayerTeleportEvent} 有独立 HandlerList——传送后缓存陈旧，近身判定按传送<b>前</b>的坐标分类，
 * 实测 {@code /tp} 两个假人到距变更 2.2 格与 10.5 格时分类恰好反转（近身的被判「进合并窗口」）。
 * 因此这里断言：只要快照被刷新，判定就必须立即按新位置改变（无需任何事件）。
 */
class PlayerPositionSnapshotsTest {

  @Test
  @DisplayName("仅刷新位置快照即改变近身分类（传送后立即按新位置判定，不依赖任何移动/传送事件）")
  void refreshingSnapshotAloneReclassifiesAfterTeleport() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID teleportedIn = UUID.randomUUID();
    UUID teleportedOut = UUID.randomUUID();
    // 距变更 2.2 格与 10.5 格（与实测复现用的两个假人一致）
    List<Update<Integer>> updates = List.of(new Update<>(0, 64, 0, 1));

    // 传送前：一个远（40 格 → 进合并窗口）、一个近（2 格 → 立即放行）
    snapshots.refresh(teleportedIn, 40.0D, 64.0D, 0.0D);
    snapshots.refresh(teleportedOut, 2.0D, 64.0D, 0.0D);
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedIn), updates, 8),
        "传送前 40 格 → 照常合并");
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedOut), updates, 8),
        "传送前 2 格 → 立即放行");

    // 只刷新快照（等价于传送后每 tick 的自动刷新），不产生任何移动/传送事件：
    // 分类必须随之改变，而不是沿用旧坐标（旧事件缓存正是在这里漏掉传送，导致分类反转）
    snapshots.refresh(teleportedIn, 2.2D, 64.0D, 0.0D);
    snapshots.refresh(teleportedOut, 10.5D, 64.0D, 0.0D);
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedIn), updates, 8),
        "传送后 2.2 格 → 必须立即判为近身（旧实现会按传送前的 40 格误判为合并）");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedOut), updates, 8),
        "传送后 10.5 格 → 必须立即判为远（分类不得反转）");

    // fail-open 与边界语义：快照缺失（刚登录/刚重置）→ 立即放行；边界仍为「含 8 格、超 8 格不合」
    UUID unknown = UUID.randomUUID();
    assertNull(snapshots.get(unknown));
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(unknown), updates, 8),
        "快照缺失时必须 fail-open（按近身处理，绝不制造延迟）");
    snapshots.refresh(unknown, 8.0D, 64.0D, 0.0D);
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(unknown), updates, 8),
        "恰好 8 格（含边界）仍应命中");
    snapshots.refresh(unknown, 9.0D, 64.0D, 0.0D);
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(unknown), updates, 8),
        "超出半径不得命中");

    // 丢弃快照（实体退役/退出）后又回到 fail-open；快照数也一并归零
    snapshots.discard(teleportedIn);
    snapshots.discard(teleportedOut);
    snapshots.discard(unknown);
    assertEquals(0, snapshots.size());
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(teleportedIn), updates, 8),
        "快照被丢弃后必须 fail-open");
  }

  @Test
  @DisplayName("同一方块坐标内连续刷新不重发快照（幂等、不 new 记录），跨入新方块才重发并更新判定")
  void sameBlockRefreshSkipsPublishAndIsIdempotent() {
    PlayerPositionSnapshots snapshots = new PlayerPositionSnapshots();
    UUID player = UUID.randomUUID();
    // 变更方块 (0,64,0)，半径 8
    List<Update<Integer>> updates = List.of(new Update<>(0, 64, 0, 1));

    snapshots.refresh(player, 0.3D, 64.2D, 0.7D); // 方块 (0,64,0)
    PlayerPositionSnapshots.Position first = snapshots.get(player);
    assertNotNull(first);
    assertTrue(PlayerPositionSnapshots.immediatePass(first, updates, 8), "0 格 → 近身");

    // 同一方块内连续刷新（0.3→0.9、0.7→0.999）：不得发布新快照，判定结果也不变
    snapshots.refresh(player, 0.9D, 64.99D, 0.0D);
    assertSame(first, snapshots.get(player), "同一方块坐标内刷新必须跳过发布（不 new 记录、不写 CHM）");
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(player), updates, 8),
        "跳过发布后判定结果必须与跳过前一致");
    snapshots.refresh(player, 0.0D, 64.0D, 0.999D);
    assertSame(first, snapshots.get(player), "再次刷新仍在同一方块，依旧跳过");

    // 移动到新方块 (9,64,0)：必须重发，判定随之更新（9 格 > 8 → 不再近身）
    snapshots.refresh(player, 9.0D, 64.0D, 0.0D);
    assertNotSame(first, snapshots.get(player), "跨入新方块必须发布新快照");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(player), updates, 8),
        "新方块距变更 9 格 → 判定必须随位置更新为「远」");
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
    // 变更方块 (7,64,0)，半径 8：玩家方块 -1 距其 8 格（含边界）→ 近身；方块 -2 距其 9 格 → 远
    List<Update<Integer>> updates = List.of(new Update<>(7, 64, 0, 1));

    snapshots.refresh(player, -0.5D, 64.0D, 0.0D); // 方块 (-1,64,0)
    PlayerPositionSnapshots.Position negative = snapshots.get(player);
    assertTrue(PlayerPositionSnapshots.immediatePass(negative, updates, 8),
        "方块 (-1,64,0) 距变更恰好 8 格（含边界）→ 近身");

    // -0.5 → -0.6 仍属同一方块 -1：不重发、判定不变
    snapshots.refresh(player, -0.6D, 64.4D, 0.3D);
    assertSame(negative, snapshots.get(player), "-0.5 → -0.6 属同一方块，不得重发快照");
    assertTrue(PlayerPositionSnapshots.immediatePass(snapshots.get(player), updates, 8),
        "同一方块内判定不得改变");

    // -1.0001 跨入新方块 -2：必须重发，判定随之翻转为「远」
    snapshots.refresh(player, -1.0001D, 64.0D, 0.0D);
    assertNotSame(negative, snapshots.get(player), "跨入方块 -2 必须发布新快照");
    assertFalse(PlayerPositionSnapshots.immediatePass(snapshots.get(player), updates, 8),
        "方块 -2 距变更 9 格 → 判定必须更新为「远」");
  }
}