package net.mikumc.mikuxraynet.occlusion;

/**
 * 整段全遮挡 section 的常量快路径。
 *
 * <p>地下大部分 section 是实心的（全石头/深板岩），逐格存 4096 位（512 字节）纯属浪费。用一个不可变
 * 单例表示「该 section 全部方块都遮挡」，查询恒为 {@code true}，内存占用为零（整张表共享同一实例）。
 *
 * <p>不可变：本类不提供任何修改入口。需要改动某格时由 {@link OcclusionStore} 把它降级为一个
 * {@link BitmapOcclusion}（见其 {@code setBlockOccluding}）。
 */
public final class SolidOcclusion implements SectionOcclusion {

  /** 全局唯一实例（不可变，无状态，可任意线程共享）。 */
  public static final SolidOcclusion INSTANCE = new SolidOcclusion();

  private SolidOcclusion() {
  }

  @Override
  public boolean isOccludingLocal(int x, int y, int z) {
    return true;
  }

  @Override
  public boolean isSolid() {
    return true;
  }

  @Override
  public String toString() {
    return "SolidOcclusion";
  }
}
