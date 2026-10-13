package net.mikumc.mikuxraynet.occlusion;

/**
 * 「已知该段全不遮挡」的常量快路径。
 *
 * <p><b>为什么需要它，而不是直接用 {@code null}</b>：{@code null} 在仓库里表示「没有该 section 的
 * 遮挡数据」（未知）；而全空气段是<b>已知且确实不遮挡</b>。射线对两者的判定结果相同（都不计遮挡），
 * 但若混用，覆盖率指标（「射线路径上查不到条目的次数」）就会被大量天空/洞穴中的全空气段稀释，
 * 失去「遮挡数据到底覆盖了多少」的观测价值。
 *
 * <p>不可变、无状态、可任意线程共享；内存占用为零（整张表共享同一实例）。
 */
public final class EmptyOcclusion implements SectionOcclusion {

  /** 全局唯一实例。 */
  public static final EmptyOcclusion INSTANCE = new EmptyOcclusion();

  private EmptyOcclusion() {
  }

  @Override
  public boolean isOccludingLocal(int x, int y, int z) {
    return false;
  }

  @Override
  public boolean isEmpty() {
    return true;
  }

  @Override
  public String toString() {
    return "EmptyOcclusion";
  }
}
