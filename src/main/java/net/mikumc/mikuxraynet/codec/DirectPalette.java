
package net.mikumc.mikuxraynet.codec;

import io.netty.buffer.ByteBuf;

/**
 * 直接（direct）调色板：数据里存的就是方块状态 id 本身，没有调色板段。
 *
 * <p>关键约束：读写均为空操作，因此重编码不会输出调色板；不含调色板意味着位打包数据里存的就是状态 id，
 * 必须按 {@link ChunkSection#bitsPerBlock()} 的<b>声明位宽</b>解释——读取时保留服务端声明值（9..32），
 * 只有本插件升位时才取注册表位宽。
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

  /** 直接调色板按声明位宽可容纳全部状态 id、不存在「塞不下新状态」，预算筛选对它恒放行。 */
  @Override
  public boolean contains(int value) {
    return true;
  }
}