package net.mikumc.mikuxraynet.antixray;

/**
 * 改写结果缓存值（内存与磁盘同构，不含任何封包 / 世界引用）。
 *
 * @param sourceHash 原始（未改写）区块字节的指纹：源字节变了即视为未命中
 * @param data       改写后的区块字节；无伪装坐标的条目为空数组（命中后不回写）
 * @param positions  被伪装坐标（区块内相对编码）
 * @param occlusion  整列遮挡数据负载；未启用异步遮挡时为 {@code null}
 */
public record CachedChunk(long sourceHash, byte[] data, int[] positions, byte[] occlusion) {
}
