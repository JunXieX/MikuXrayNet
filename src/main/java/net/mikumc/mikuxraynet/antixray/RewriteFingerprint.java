package net.mikumc.mikuxraynet.antixray;

/**
 * 改写结果缓存键中「决定因素」的唯一组合入口。
 *
 * <p><b>为什么需要它</b>：缓存键的输入曾分散在三处（启动期配置指纹、调色板选项指纹、邻块内容指纹）。
 * 新增一个会改变改写字节的输入时，只要漏加其中一处，就会出现「改了配置但缓存仍命中旧产物」的静默缺陷。
 * 现在所有输入都从本类显式传入，读、写两侧共用。
 *
 * <p><b>顺序是有意的</b>：调整顺序会让所有现存内存/磁盘条目一次性失效（只浪费一次重建，不是正确性问题）。
 *
 * <p>输入含义：
 * <ul>
 *   <li>{@code configHash}——<b>启动期</b>反矿透配置指纹（改写规则在启动期冻结，见
 *       {@code ProtocolLibAsyncListener#rewriteFingerprint} 的说明）；</li>
 *   <li>{@code paletteFingerprint}——{@code bandwidth.yml} 的调色板选项（位宽预算 / 自检）；</li>
 *   <li>{@code neighborHash}——本次改写实际用到的邻块贴边快照内容（仅 enclosed 模式非零）。</li>
 * </ul>
 *
 * <p><b>不含 remove-block-entities</b>：方块实体剔除只作用于写回时的封包方块实体列表
 * （{@code ChunkPacketAccessor#update}），从不进入缓存的区块字节，且写回按实时开关施加——
 * 因此它不影响缓存产物，切换它不应触发缓存失效。
 */
public final class RewriteFingerprint {

  private RewriteFingerprint() {
  }

  /**
   * 组合缓存指纹。
   *
   * @param hasNeighbors 本次改写是否真的用了邻块快照；{@code false} 时忽略 {@code neighborHash}
   */
  public static int of(int configHash, int paletteFingerprint, long neighborHash,
      boolean hasNeighbors) {
    int base = configHash * 31 + paletteFingerprint;
    if (!hasNeighbors) {
      return base;
    }
    return base * 31 + (int) (neighborHash ^ (neighborHash >>> 32));
  }

  /** 未使用邻块快照时的等价重载。 */
  public static int of(int configHash, int paletteFingerprint) {
    return of(configHash, paletteFingerprint, 0L, false);
  }
}
