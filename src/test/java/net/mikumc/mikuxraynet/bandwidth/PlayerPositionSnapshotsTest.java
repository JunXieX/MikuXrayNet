package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}