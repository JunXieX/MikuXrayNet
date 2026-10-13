package net.mikumc.mikuxraynet.registry;

/**
 * <b>逐状态遮挡真值</b>的来源抽象（可注入）：把「方块状态字符串」解析成该状态是否遮挡。
 *
 * <p><b>为什么需要它</b>：遮挡判定的权威口径是服务端对<b>每个方块状态</b>的 {@code isOccluding} 语义
 * （即「玩家视觉能否看穿」）。它由 {@code Bukkit.createBlockData(状态字符串).isOccluding()} 给出，
 * 但该方法依赖服务端 {@code RegistryAccess}——离线单测里 {@code Material} / {@code Bukkit} 类初始化即抛
 * {@code No RegistryAccess implementation found}。因此把「解析」这一步抽成接口，由启动期注入真实实现，
 * 离线单测注入桩来验证「解析失败 → 回落启发式」这条路径。
 *
 * <p><b>约定</b>：实现必须自行吞掉异常并返回 {@code null} 表示「无法解析」，绝不把异常抛给调用方；
 * 调用方（{@link BlockStateRegistry}）对 {@code null} 的回落与失败计数都以此为准。
 *
 * <p>状态字符串由 {@link BlockStateRegistry} 从 PacketEvents 的 {@code WrappedBlockState#toString()}
 * 摊平而来（vanilla 形式，如 {@code minecraft:oak_slab[type=double,waterlogged=false]}），
 * 即 {@code Bukkit.createBlockData} 直接可解析的格式。
 */
@FunctionalInterface
public interface StateOcclusionOracle {

  /**
   * 解析某个方块状态的逐状态遮挡真值。
   *
   * @param blockDataString vanilla 方块状态字符串（形如 {@code minecraft:oak_slab[type=double]}）
   * @return {@code TRUE}/{@code FALSE} = 服务端逐状态真值；{@code null} = 无法解析（调用方回落启发式）
   */
  Boolean resolve(String blockDataString);
}
