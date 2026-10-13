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
 * 摊平而来。格式事实（已核实）：它是 vanilla 状态串但<b>不带</b> {@code minecraft:} 命名空间前缀
 * （PE 的 {@code getByString} 会先 {@code replace("minecraft:", "")} 再查内部表，说明内部键无前缀），
 * 形如 {@code oak_slab[type=double,waterlogged=false]}；具体实现可自行按需补前缀后交给 Bukkit 解析。
 */
@FunctionalInterface
public interface StateOcclusionOracle {

  /**
   * 解析某个方块状态的逐状态遮挡真值。
   *
   * @param blockDataString 方块状态字符串（vanilla 形式，如 {@code oak_slab[type=double]}；
   *                        PE 产出时不带 {@code minecraft:} 前缀）
   * @return {@code TRUE}/{@code FALSE} = 服务端逐状态真值；{@code null} = 无法解析（调用方回落启发式）
   */
  Boolean resolve(String blockDataString);
}
