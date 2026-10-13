package net.mikumc.mikuxraynet.occlusion;

/**
 * 单个方块 section（16×16×16）的遮挡查询视图。
 *
 * <p>本模块只需要回答一个问题：某个局部坐标上的方块<b>是否遮挡视线</b>（不透明、挡得住射线）。
 * 因此实现只存布尔位，不存方块 id——这是相对参考实现的最大化简（只需遮挡，无需重建方块实体）。
 *
 * <p><b>三态表示</b>（由 {@link OcclusionStore} 决定存哪一种）：
 * <ul>
 *   <li>{@code null}（表里没有该 section）——按「不遮挡」处理（fail-open：宁可少藏，绝不误藏）；</li>
 *   <li>{@link BitmapOcclusion}——一般的逐格位图；</li>
 *   <li>{@link SolidOcclusion}——整段全遮挡（实心段）的快路径，省下 512 字节位图。</li>
 * </ul>
 *
 * <p>局部坐标取值范围恒为 {@code [0, 15]}；越界坐标由 {@link #isOccluding} 掩码回 {@code [0,15]}
 * （与 Minecraft 的方块坐标掩码语义一致：坐标的「所在 section」由调用方先除以 16 决定）。
 */
public interface SectionOcclusion {

  /** 局部坐标掩码（16 − 1）。 */
  int LOCAL_MASK = 15;

  /** section 边长。 */
  int SECTION_SIZE = 16;

  /**
   * 局部坐标 {@code (x, y, z)} 处的方块是否遮挡（三者取值均须在 {@code [0, 15]}）。
   */
  boolean isOccludingLocal(int x, int y, int z);

  /**
   * 以「已被掩码到 {@code [0,15]}」的坐标查询是否遮挡。
   *
   * <p>射线遍历使用本方法：它自己已经算出「所在 section」并只截取了低 4 位，因此这里不再重复掩码。
   */
  default boolean isOccluding(int x, int y, int z) {
    return isOccludingLocal(x & LOCAL_MASK, y & LOCAL_MASK, z & LOCAL_MASK);
  }

  /** 是否「整段全遮挡」快路径（{@link SolidOcclusion} 为 true）。 */
  default boolean isSolid() {
    return false;
  }

  /**
   * 是否「已知整段全不遮挡」快路径（{@link EmptyOcclusion} 为 true）。
   *
   * <p>与「查不到条目」（{@code null}）不同：本方法为 true 表示<b>该段确实没有遮挡方块</b>。
   * 二者对射线判定结果相同（都不计遮挡），但区分开后「查不到条目」才真正等于「遮挡数据缺失」，
   * 使覆盖率指标不被大量全空气段稀释。
   */
  default boolean isEmpty() {
    return false;
  }

  /**
   * 局部坐标打包为 {@code [0, 4095]} 的位下标，与 {@code codec.ChunkSection#positionToIndex} 一致：
   * {@code y << 8 | z << 4 | x}。
   *
   * <p>刻意复用工程内既有的局部坐标编码（{@code ObfuscatedChunkIndex} 的 {@code 相对Y<<8|z<<4|x}
   * 同源），使「遮挡位图」与「伪装坐标索引」用同一套坐标语义，避免两处各自定义一套而漂移。
   */
  static int packLocal(int x, int y, int z) {
    return (y & LOCAL_MASK) << 8 | (z & LOCAL_MASK) << 4 | (x & LOCAL_MASK);
  }
}
