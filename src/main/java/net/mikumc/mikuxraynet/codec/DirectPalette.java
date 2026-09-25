
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

  /** 直接调色板没有调色板段，条目数为 0。 */
  @Override
  public int size() {
    return 0;
  }

  /** 直接调色板的位宽即注册表位宽、不存在「塞不下新状态」，预算筛选对它恒放行。 */
  @Override
  public boolean contains(int value) {
    return true;
  }
}