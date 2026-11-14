package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 二次泄露过滤的包型覆盖守门。
 *
 * <p>{@code BLOCK_ACTION}（箱子/门/活塞开合）携带坐标与动作，客户端按<b>真实方块类型</b>决定是否播动画；
 * 隐藏坐标上的这类包会让客户端据此反推真方块身份，属与 {@code BLOCK_BREAK_ANIMATION} 同一类泄露，
 * 必须在「注册监听」和「坐标分支」两处同时覆盖——只注册不分支等于白跑一趟，只分支不注册则永不触发。
 */
class LeakFilterCoverageTest {

  private static final Path SOURCE =
      Path.of("src/main/java/net/mikumc/mikuxraynet/antixray/LeakFilterListener.java");

  @Test
  void blockActionPacketsAreCovered() throws Exception {
    String source = Files.readString(SOURCE);
    int occurrences = source.split("PacketType\\.Play\\.Server\\.BLOCK_ACTION", -1).length - 1;
    assertTrue(occurrences >= 2,
        "BLOCK_ACTION 必须同时出现在监听注册与坐标分支里，实际出现 " + occurrences + " 次");
  }
}
