package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedLevelChunkData;
import java.util.ArrayList;
import java.util.List;

/**
 * 区块封包读写入口：把 ProtocolLib 的 {@link PacketContainer} 收敛为「原始 section 字节 + 方块实体列表」。
 *
 * <p>取数路径（对应 ProtocolLib 5.3+）：
 * <ul>
 *   <li>{@link WrappedLevelChunkData.ChunkData#getBuffer()} / {@code setBuffer(byte[])} —— section 原始字节；</li>
 *   <li>{@link WrappedLevelChunkData.ChunkData#getBlockEntityInfo()} / {@code setBlockEntityInfo(List)}
 *       —— 被伪装 section 内的方块实体必须剔除。</li>
 * </ul>
 * 坐标取自封包的第 0 / 1 个 int 字段（chunkX / chunkZ）。
 *
 * <p>说明：{@link WrappedLevelChunkData.ChunkData} 是 ProtocolLib 提供的公开包装类，其构造需要一个
 * 「包内数据字段的句柄」，故这里用 ProtocolLib 的 {@code MinecraftReflection} 取字段类型后再用
 * {@code getSpecificModifier} 读取——全程只使用 ProtocolLib 公共 API，不自行反射改写任何 NMS 字段。
 */
public final class ChunkPacketAccessor {

  private final WrappedLevelChunkData.ChunkData chunkData;
  private final int chunkX;
  private final int chunkZ;

  public ChunkPacketAccessor(PacketContainer packet) {
    this.chunkX = packet.getIntegers().read(0);
    this.chunkZ = packet.getIntegers().read(1);

    Class<?> chunkDataClass = MinecraftReflection.getLevelChunkPacketDataClass();
    StructureModifier<?> modifier = packet.getSpecificModifier(chunkDataClass);
    this.chunkData = new WrappedLevelChunkData.ChunkData(modifier.read(0));
  }

  public int chunkX() {
    return chunkX;
  }

  public int chunkZ() {
    return chunkZ;
  }

  /** 当前 section 原始字节（不要在改写前修改该数组）。 */
  public byte[] buffer() {
    return chunkData.getBuffer();
  }

  /**
   * 回填改写结果，并（按开关）剔除被伪装方块占位的方块实体。
   *
   * <p><b>写回自检</b>：{@code setBuffer} 只通过 ProtocolLib 的字段访问器把新数组写进 NMS 对象。
   * 写完立刻回读一次，确认字段指向的就是我们传入的那个数组——若 ProtocolLib 某个版本改成写副本、
   * 或封包结构不匹配（拿到的是别的对象），这里会返回 {@code false}，调用方据此告警，
   * 避免出现「算了但没写」却毫无征兆的静默失效。
   *
   * @param data                 新的 section 字节
   * @param localPositions       被伪装的方块位置，编码为 {@code y << 8 | z << 4 | x}（区块内相对坐标）
   * @param minHeight            该世界最低建筑高度，用于把方块实体的绝对 Y 换算为区块内相对 Y
   * @param removeBlockEntities  {@code antixray.yml: obfuscation.remove-block-entities}；
   *                             false 时跳过方块实体剔除段（只回填字节，保留封包原带的所有方块实体）
   * @return true 表示新字节已确认写回封包；false 表示回读结果与写入不一致（调用方应告警）
   */
  public boolean update(byte[] data, int[] localPositions, int minHeight, boolean removeBlockEntities) {
    chunkData.setBuffer(data);
    boolean verified = chunkData.getBuffer() == data;

    if (!shouldFilterBlockEntities(removeBlockEntities, localPositions)) {
      return verified;
    }

    List<WrappedLevelChunkData.BlockEntityInfo> blockEntities = chunkData.getBlockEntityInfo();
    if (blockEntities.isEmpty()) {
      return verified;
    }

    List<WrappedLevelChunkData.BlockEntityInfo> kept = new ArrayList<>(blockEntities.size());
    for (WrappedLevelChunkData.BlockEntityInfo info : blockEntities) {
      if (!isObfuscated(info.getY() - minHeight, info.getSectionX(), info.getSectionZ(),
          localPositions)) {
        kept.add(info);
      }
    }

    if (kept.size() != blockEntities.size()) {
      chunkData.setBlockEntityInfo(kept);
    }
    return verified;
  }

  /**
   * 是否需要执行方块实体剔除（配置开关与被伪装坐标清单的汇合点；纯函数，离线可测）。
   *
   * <p>开关关闭、或本次没有任何被伪装坐标时都不需要剔除——旧实现里该开关是死配置键，
   * 剔除段无条件执行，此判定即开关的真正消费点。
   */
  static boolean shouldFilterBlockEntities(boolean removeBlockEntities, int[] localPositions) {
    return removeBlockEntities && localPositions.length > 0;
  }

  /**
   * 纯几何判定：某方块实体是否落在被伪装坐标清单里。
   *
   * @param relativeY  方块实体的区块内相对 Y（绝对 Y − 世界最低建筑高度；负值必然不在清单里）
   * @param sectionX   方块实体的 section 内 X（0..15）
   * @param sectionZ   方块实体的 section 内 Z（0..15）
   * @param localPositions 被伪装坐标清单，编码为 {@code y << 8 | z << 4 | x}
   */
  static boolean isObfuscated(int relativeY, int sectionX, int sectionZ, int[] localPositions) {
    if (relativeY < 0) {
      return false;
    }
    int packed = relativeY << 8 | sectionZ << 4 | sectionX;
    for (int position : localPositions) {
      if (position == packed) {
        return true;
      }
    }
    return false;
  }
}