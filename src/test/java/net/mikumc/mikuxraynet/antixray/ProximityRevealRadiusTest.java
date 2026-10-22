package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link ProximityRevealer#effectiveRevealChunks(int, int, int)} 的离线用例：显形半径以「区块」为单位，
 * 默认跟随服务器视距，并与客户端自报视距取小。
 *
 * <p>判据来自客户端行为：显形包只有在该区块<b>已被客户端加载</b>时才会被应用，越界的包会被直接丢弃，
 * 因此任何情况下都不应把半径放到视距之外。
 */
class ProximityRevealRadiusTest {

  @Test
  void defaultFollowsServerViewDistance() {
    assertEquals(10, ProximityRevealer.effectiveRevealChunks(0, 10, -1),
        "配置 0 = 跟随服务器视距；客户端视距未知时只看服务器值");
  }

  @Test
  void defaultUsesSmallerClientViewDistance() {
    assertEquals(6, ProximityRevealer.effectiveRevealChunks(0, 10, 6),
        "客户端自报视距更小时必须取小：否则会给客户端没加载的区块发显形包");
  }

  @Test
  void configuredValueIsClampedToViewDistance() {
    assertEquals(4, ProximityRevealer.effectiveRevealChunks(4, 10, -1), "配置小于视距时原样生效");
    assertEquals(10, ProximityRevealer.effectiveRevealChunks(32, 10, -1),
        "配置大于视距时必须钳到视距（多发的包只会被客户端丢弃）");
    assertEquals(6, ProximityRevealer.effectiveRevealChunks(32, 10, 6), "钳制同时考虑客户端视距");
  }

  @Test
  void invalidViewDistancesFallBackSafely() {
    assertEquals(10, ProximityRevealer.effectiveRevealChunks(0, 0, -1),
        "服务器视距读不到（≤0）时按原版默认 10 兜底，而不是退化成 0（完全不显形）");
    assertEquals(10, ProximityRevealer.effectiveRevealChunks(0, -1, 0),
        "客户端视距 0 视为未知，不影响服务器值兜底");
    assertEquals(1, ProximityRevealer.effectiveRevealChunks(1, 1, -1), "有效半径至少 1 区块");
  }

  @Test
  void negativeConfiguredValueBehavesLikeAuto() {
    assertEquals(8, ProximityRevealer.effectiveRevealChunks(-3, 8, -1),
        "负数按「跟随视距」处理（配置解析阶段已留痕，见 AntiXrayConfig#proximityDistanceChunks）");
  }
}
