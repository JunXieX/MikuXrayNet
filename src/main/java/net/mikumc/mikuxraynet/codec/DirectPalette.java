// 移植自 Orebfuscator（GPL-3.0），本项目为私有自用部署。
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * 直接（direct）调色板：数据里存的就是方块状态 id 本身，没有调色板段。
 *
 * <p>关键约束：读写均为空操作，因此重编码不会输出调色板；位宽由注册表决定，不含调色板意味着
 * 位打包数据必须按注册表位宽解释。
 */
public class DirectPalette implements Palette {

  @Override
  public int idFor(int value) {
    return value;
  }

  @Override
  public int valueFor(int id) {
    return id;
  }

  @Override
  public void read(ByteBuf buffer) {
  }

  @Override
  public void write(ByteBuf buffer) {
  }
}