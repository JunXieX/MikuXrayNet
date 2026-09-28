package net.mikumc.mikuxraynet.bench;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.stream.NetStreamInput;
import com.github.retrooper.packetevents.protocol.stream.NetStreamOutput;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * PacketEvents 结构化区块模型路径：{@code Chunk_v1_18.read} 解出 section 对象 → 改 N 个方块
 * → {@code Chunk_v1_18.write} 重新编码整列（不区分是否改动，整列全量重编码）。
 *
 * <p><b>本路径标注为「PE 模型近似」</b>，与理想用法有且仅有一处差异，原因是可核实的：
 * {@code Chunk_v1_18#set(int x, int y, int z, int combinedID)} 内部会调用
 * {@code WrappedBlockState.getByGlobalId(version, combinedID)}，而该方法（globalId != 0 时）会走
 * {@code WrappedBlockState#loadMappings0}，其中调用 {@code PacketEvents.getAPI().getLogManager()} 打日志——
 * 没有平台初始化的离线测试环境里 PE API 实例不存在，这一步不可用。
 * 因此方块改写改为调用 PE 自己的 {@link DataPalette#set(int, int, int, int)}（落到该 section 的
 * {@code chunkData} 上），并按 PE 自身的空气约定（全局 id 0 = AIR）同步维护 {@code blockCount}
 * （{@code Chunk_v1_18#setBlockCount}），使重编码出的 section 头的方块计数与真实改写一致。
 * 代价是少掉了 PE 原生路径里的两次方块状态映射查询与状态对象构造，即本路径相对原生 {@code set(...)} 偏快，
 * 对 PE 有利、对结论更保守。
 *
 * <p>读/写两端全部使用 PE 真实 API，没有自造字节胶水：
 * <ul>
 *   <li>PE 的流实现类可直接包裹 {@code byte[]} 流离线使用——
 *       {@link NetStreamInput} 是 {@code FilterInputStream} 的子类（构造器 {@code NetStreamInput(InputStream)}），
 *       {@link NetStreamOutput} 是 {@code FilterOutputStream} 的子类（构造器 {@code NetStreamOutput(OutputStream)}）；</li>
 *   <li>{@link Chunk_v1_18#read(ClientVersion, NetStreamInput, boolean, boolean)} 与
 *       {@link Chunk_v1_18#write(NetStreamOutput, Chunk_v1_18, boolean, boolean)} 这两个重载不触碰平台 API
 *       （另外的 {@code read(NetStreamInput, ...)} / {@code read(PacketWrapper)} 重载会取
 *       {@code PacketEvents.getAPI()} 的服务端版本，故不采用）。</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public final class PeModelPath implements ChunkPath {

  /** read/write 只用版本参数填充 {@code Chunk_v1_18} 字段（本路径不消费它），取 1.21 常量即可。 */
  private static final ClientVersion VERSION = ClientVersion.V_1_21;

  /** 与 codec 的 26.2 标志对齐：1.21.5+ 的 long 数组前无长度字段、26.1+ 的 section 头含流体计数。 */
  private static final boolean PALETTE_LENGTH_PREFIX = false;
  private static final boolean HAS_FLUID_COUNT = true;

  @Override
  public String name() {
    return "PacketEvents 模型";
  }

  @Override
  public byte[] encode(byte[] input, BenchFixtures.Edit[] edits) {
    Chunk_v1_18[] sections = readSections(input);

    for (BenchFixtures.Edit edit : edits) {
      Chunk_v1_18 section = sections[edit.section()];
      DataPalette chunkData = section.getChunkData();

      int previousState = chunkData.get(edit.x(), edit.y(), edit.z());
      chunkData.set(edit.x(), edit.y(), edit.z(), edit.state());

      if (isAir(previousState) != isAir(edit.state())) {
        section.setBlockCount(section.getBlockCount() + (isAir(edit.state()) ? -1 : 1));
      }
    }

    return writeSections(sections, input.length);
  }

  @Override
  public int[] readStates(byte[] encoded) {
    Chunk_v1_18[] sections = readSections(encoded);

    int[] states = new int[BenchFixtures.COLUMN_VOLUME];
    int next = 0;
    // 与 codec 的元素序号约定一致：y 外层、z 中层、x 内层（即 y<<8|z<<4|x）
    for (Chunk_v1_18 section : sections) {
      for (int y = 0; y < 16; y++) {
        for (int z = 0; z < 16; z++) {
          for (int x = 0; x < 16; x++) {
            states[next++] = section.getBlockId(x, y, z);
          }
        }
      }
    }

    return states;
  }

  private static boolean isAir(int state) {
    return state == BenchFixtures.AIR;
  }

  private static Chunk_v1_18[] readSections(byte[] input) {
    NetStreamInput stream = new NetStreamInput(new ByteArrayInputStream(input));

    Chunk_v1_18[] sections = new Chunk_v1_18[BenchFixtures.SECTION_COUNT];
    for (int section = 0; section < sections.length; section++) {
      sections[section] = Chunk_v1_18.read(VERSION, stream, PALETTE_LENGTH_PREFIX, HAS_FLUID_COUNT);
    }
    return sections;
  }

  private static byte[] writeSections(Chunk_v1_18[] sections, int expectedLength) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(expectedLength);
    NetStreamOutput stream = new NetStreamOutput(bytes);

    for (Chunk_v1_18 section : sections) {
      Chunk_v1_18.write(stream, section, PALETTE_LENGTH_PREFIX, HAS_FLUID_COUNT);
    }

    return bytes.toByteArray();
  }
}