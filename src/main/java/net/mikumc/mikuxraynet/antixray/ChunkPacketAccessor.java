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
   * 回填改写结果，并剔除被伪装方块占位的方块实体。
   *
   * @param data            新的 section 字节
   * @param localPositions  被伪装的方块位置，编码为 {@code y << 8 | z << 4 | x}（区块内相对坐标）
   * @param minHeight       该世界最低建筑高度，用于把方块实体的绝对 Y 换算为区块内相对 Y
   */
  public void update(byte[] data, int[] localPositions, int minHeight) {
    chunkData.setBuffer(data);

    if (localPositions.length == 0) {
      return;
    }

    List<WrappedLevelChunkData.BlockEntityInfo> blockEntities = chunkData.getBlockEntityInfo();
    if (blockEntities.isEmpty()) {
      return;
    }

    List<WrappedLevelChunkData.BlockEntityInfo> kept = new ArrayList<>(blockEntities.size());
    for (WrappedLevelChunkData.BlockEntityInfo info : blockEntities) {
      if (!isObfuscated(info, localPositions, minHeight)) {
        kept.add(info);
      }
    }

    if (kept.size() != blockEntities.size()) {
      chunkData.setBlockEntityInfo(kept);
    }
  }

  private static boolean isObfuscated(WrappedLevelChunkData.BlockEntityInfo info, int[] localPositions,
      int minHeight) {
    int y = info.getY() - minHeight;
    if (y < 0) {
      return false;
    }

    int packed = y << 8 | info.getSectionZ() << 4 | info.getSectionX();
    for (int position : localPositions) {
      if (position == packed) {
        return true;
      }
    }
    return false;
  }
}