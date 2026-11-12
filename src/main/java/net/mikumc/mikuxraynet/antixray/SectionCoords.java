package net.mikumc.mikuxraynet.antixray;

import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;

/**
 * 区块段内坐标的协议编解码<b>唯一实现</b>。
 *
 * <p>布局：{@code (y & 15) << 8 | (z & 15) << 4 | (x & 15)}。它同时是
 * {@code ClientboundSectionBlocksUpdatePacket}（{@code MULTI_BLOCK_CHANGE}）的 packed short 语义，
 * 也是本插件内部「段内坐标」的 int 编码（与 {@link SectionOcclusion#packLocal} 同源）。
 *
 * <p><b>为什么必须只有一份</b>：这段位运算曾在变更监听、遮挡补丁、合并器与显形链路里各写一遍，
 * 并出现过「两处对调恰好互相抵消」的隐性故障——只改一处会让另一处继续输出错坐标。所有新代码必须
 * 调用本类，不得再手写位移。
 */
public final class SectionCoords {

  /** 段内坐标掩码（16 − 1）。 */
  public static final int LOCAL_MASK = 15;

  private SectionCoords() {
  }

  /** 段内坐标打包；越界坐标按 {@code & 15} 掩码回段内（与协议一致）。 */
  public static int pack(int x, int y, int z) {
    return SectionOcclusion.packLocal(x, y, z);
  }

  /** packed short 转无符号 int（避免负 short 的符号扩展干扰后续位运算）。 */
  public static int unsigned(short packed) {
    return packed & 0xFFFF;
  }

  /** 解出 X（0..15）。 */
  public static int x(int packed) {
    return packed & LOCAL_MASK;
  }

  /** 解出 Y（0..15）。 */
  public static int y(int packed) {
    return (packed >>> 8) & LOCAL_MASK;
  }

  /** 解出 Z（0..15）。 */
  public static int z(int packed) {
    return (packed >>> 4) & LOCAL_MASK;
  }
}
