package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link LeakFilterListener#isHidden} 的判定口径：伪装清单 + 该玩家尚未显形，两者同时成立才过滤。 */
class LeakFilterListenerTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final String WORLD = "world";

  @Test
  void filtersOnlyObfuscatedAndNotRevealedPositions() {
    ObfuscatedChunkIndex index = new ObfuscatedChunkIndex(64, 60);
    RevealedSet revealed = new RevealedSet(64, 60);
    // 伪装清单：区块 (0,0) 内 (3, 64, 5)；(4, 64, 5) 未伪装
    int minHeight = -64;
    int local = ((64 - minHeight) << 8) | (5 << 4) | 3;
    index.recordChunkWithRemovals(WORLD, 0, 0, minHeight, new int[] {local});

    assertTrue(LeakFilterListener.isHidden(index, revealed, PLAYER, WORLD, 3, 64, 5),
        "已伪装且该玩家尚未显形 → 必须过滤");
    assertFalse(LeakFilterListener.isHidden(index, revealed, PLAYER, WORLD, 4, 64, 5),
        "不在伪装清单里的坐标 → 放行");
    assertFalse(LeakFilterListener.isHidden(index, revealed, PLAYER, "other", 3, 64, 5),
        "不同世界 → 放行");
    assertFalse(LeakFilterListener.isHidden(null, revealed, PLAYER, WORLD, 3, 64, 5),
        "索引缺失 → 放行（fail-open）");

    // 该玩家收到显形包后，同一坐标的音效/方块实体应放行
    revealed.markIfAbsent(PLAYER, new ChunkKey(WORLD, 0, 0), 3, 64, 5);
    assertFalse(LeakFilterListener.isHidden(index, revealed, PLAYER, WORLD, 3, 64, 5),
        "已显形坐标 → 放行");
  }
}
