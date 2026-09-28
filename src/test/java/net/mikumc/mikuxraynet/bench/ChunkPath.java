package net.mikumc.mikuxraynet.bench;

/**
 * 基准测试的被测路径契约：把「原始区块字节 + 一批方块改写」变成「重编码后的区块字节」。
 *
 * <p>契约：{@link #encode(byte[], BenchFixtures.Edit[])} 只读取入参数组，不得改写它，
 * 也不得复用上一次调用留下的状态（基准每轮都用未改动过的原始字节重新调用）。
 */
public interface ChunkPath {

  /** 路径名（报告表格用）。 */
  String name();

  /** 被测操作：解码整列 → 改写指定方块 → 重新编码整列。 */
  byte[] encode(byte[] input, BenchFixtures.Edit[] edits);

  /** 校验操作：从编码结果读回整列方块状态（按 section 序号拼接，共 24×4096 项，序号为 {@code y<<8|z<<4|x}）。 */
  int[] readStates(byte[] encoded);
}