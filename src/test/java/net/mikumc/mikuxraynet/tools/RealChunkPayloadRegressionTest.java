package net.mikumc.mikuxraynet.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.codec.ByteBufUtil;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkSection;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>真机负载离线实证（常驻回归测试，CI 可跑）</b>：直接解开真机磁盘缓存里的<b>真实负载</b>，
 * 回答「反矿透为什么在真机上失效」。
 *
 * <p><b>数据来源</b>：{@code E:\Server\test\plugins\MikuXrayNet\cache\world\r.-1.-1.b_linear}
 * 第 10 个 bucket 的第 28 个槽位（chunkIndex=668 → 区块坐标 (-4,-12)），
 * 由一次性工具 {@link RealCachePayloadExtractor} 提取为 {@code src/test/resources/real-chunk-payload.bin}。
 * 文件内容就是磁盘缓存里保存的那条负载（含信封）：
 * {@code [i64 原始字节指纹][i32 伪装坐标数][i32 × N 伪装坐标][区块 section 字节]}。
 *
 * <p><b>判据（本测试要给出的证据）</b>：
 * <ol>
 *   <li>「伪装坐标数」——&gt;0 即证明「目标匹配 + 遮挡判定 + 替换」<b>确实算出了结果</b>，
 *       从而排除「一个方块都没匹配到目标」（可能一）；</li>
 *   <li>负载里是否还残留「本应被伪装的矿」——若替换结果已写进字节，则残存的只有暴露矿；</li>
 *   <li>解码 → 重编码是否与原字节逐字节一致——这是<b>第一次用真实负载</b>验证 codec
 *       （此前全是 {@code TestChunkBuilder} 造的合成数据）；这里失败即根因在 codec，</li>
 *   <li>用生产同口径的遮挡表与目标集合跑一遍 {@link ObfuscationProcessor}，报出「实际被替换的方块数」。</li>
 * </ol>
 */
class RealChunkPayloadRegressionTest {

  /** 与真机一致的服务端版本（PE 的 ServerVersion，26.2）。 */
  private static final ServerVersion SERVER = ServerVersion.V_26_2;

  /** 资源里这条负载的来源（一次性工具打印，便于人工复核）。 */
  private static final String SOURCE = "cache/world/r.-1.-1.b_linear bucket=10 slot=28 chunkIndex=668";

  private static final String RESOURCE = "/real-chunk-payload.bin";

  private static ClientVersion version;
  private static BlockStateRegistry registry;

  @BeforeAll
  static void installPacketEventsStubAndBuildRegistry() {
    PacketEvents.setAPI(stubApi());
    version = SERVER.toClientVersion();
    registry = BlockStateRegistry.build(Set.of(), Set.of());
  }

  @AfterAll
  static void removeStub() {
    PacketEvents.setAPI(null);
  }

  /** 只提供「设置（含资源提供者）」与「服务端版本」的最小 PE API 桩（与 PeRealDataOcclusionTest 同款）。 */
  private static PacketEventsAPI<Object> stubApi() {
    return new PacketEventsAPI<Object>() {
      @Override
      public boolean isLoaded() {
        return true;
      }

      @Override
      public void init() {
      }

      @Override
      public boolean isInitialized() {
        return true;
      }

      @Override
      public boolean isTerminated() {
        return false;
      }

      @Override
      public Object getPlugin() {
        return null;
      }

      @Override
      public ServerManager getServerManager() {
        return () -> SERVER;
      }

      @Override
      public ProtocolManager getProtocolManager() {
        return null;
      }

      @Override
      public PlayerManager getPlayerManager() {
        return null;
      }

      @Override
      public NettyManager getNettyManager() {
        return null;
      }

      @Override
      public ChannelInjector getInjector() {
        return null;
      }
    };
  }

  @Test
  void realMachinePayloadShowsReplacementsWereComputedButNeverReachedTheClient() throws IOException {
    byte[] payload = readResource();
    assertNotNull(payload, "classpath 里必须有真机负载资源 " + RESOURCE);

    // ---- 1. 解开负载信封：伪装坐标数是区分「没匹配到目标」与「算了但没生效」的第一手指标
    ByteBuffer buffer = ByteBuffer.wrap(payload);
    long sourceHash = buffer.getLong();
    int positionCount = buffer.getInt();
    assertTrue(positionCount >= 0 && positionCount <= buffer.remaining() / Integer.BYTES,
        "伪装坐标数必须合法：" + positionCount);
    int[] positions = new int[positionCount];
    for (int index = 0; index < positionCount; index++) {
      positions[index] = buffer.getInt();
    }
    byte[] chunkBytes = new byte[buffer.remaining()];
    buffer.get(chunkBytes);

    System.out.println("[真机负载] 来源：" + SOURCE);
    System.out.println("[真机负载] 负载 " + payload.length + " 字节｜原始字节指纹=0x"
        + Long.toHexString(sourceHash) + "｜伪装坐标数=" + positionCount
        + "｜区块 section 字节=" + chunkBytes.length);

    // ---- 2. 区块字节自证 section 数（不猜：第一个「刚好把字节用完」的 section 数就是它）
    ChunkCodec codec = new ChunkCodec(registry, ChunkVersionFlags.PAPER_26_2);
    int sectionCount = detectSectionCount(codec, chunkBytes);
    assertTrue(sectionCount > 0, "真实负载必须能被 codec 按某个 section 数完整解析（否则 codec 与真机布局不符）");
    System.out.println("[真机负载] 自证 section 数=" + sectionCount);

    // ---- 3. 逐 section 打印调色板/位宽 + 全区块状态 id 频次前 10（含 PE 真实方块名）
    Map<Integer, Long> frequency = new LinkedHashMap<>();
    try (Chunk chunk = codec.decode(chunkBytes, sectionCount)) {
      assertEquals(sectionCount, chunk.getSectionCount());
      for (int index = 0; index < sectionCount; index++) {
        ChunkSection section = chunk.getSection(index);
        Chunk.SectionRange range = chunk.originalSectionRange(index);
        System.out.println("[真机负载] section " + index + "：" + (section == null
            ? "无视图" : "blockCount=" + countNonAir(section) + "，"
                + describeRawSection(chunkBytes, range)));
        if (section == null) {
          continue;
        }
        for (int state : section.readAllBlockStates()) {
          frequency.merge(state, 1L, Long::sum);
        }
      }
    }

    List<Map.Entry<Integer, Long>> top = new ArrayList<>(frequency.entrySet());
    top.sort(Map.Entry.<Integer, Long>comparingByValue().reversed());
    System.out.println("[真机负载] 出现频次前 10 的状态 id（方块名取自 PE 真实映射）：");
    for (Map.Entry<Integer, Long> entry : top.subList(0, Math.min(10, top.size()))) {
      System.out.println("           id=" + entry.getKey() + " × " + entry.getValue()
          + " → " + blockName(entry.getKey()));
    }
    System.out.println("[真机负载] 状态种类数=" + frequency.size());

    // ---- 4. 21 种目标方块（19 种矿 + spawner + mossy_cobblestone）在负载里各出现多少次
    //         （按方块名统计，避免只认默认状态 id）
    Map<String, Long> oreCounts = new LinkedHashMap<>();
    for (Map.Entry<Integer, Long> entry : frequency.entrySet()) {
      oreCounts.merge(blockName(entry.getKey()), entry.getValue(), Long::sum);
    }
    List<String> hideBlocks = shippedHideBlocks();
    assertFalse(hideBlocks.isEmpty(), "必须能从打包的 antixray.yml 读到 hide-blocks");
    StringBuilder oreLine = new StringBuilder();
    long oreTotal = 0L;
    for (String name : hideBlocks) {
      long count = oreCounts.getOrDefault(name, 0L);
      oreTotal += count;
      oreLine.append(name).append('=').append(count).append(' ');
    }
    System.out.println("[真机负载] 21 种目标方块在负载里的出现次数：" + oreLine.toString().trim());
    System.out.println("[真机负载] 目标矿合计 " + oreTotal + " 个");

    // ---- 5. 用生产同口径的遮挡表 + 目标集合跑一遍改写，报出「实际被替换的方块数」
    ObfuscationProcessor processor = processor(codec, hideBlocks);
    assertTrue(processor.isActive(), "目标与伪装方块都必须解析成功");
    ObfuscationProcessor.Result result = processor.rewrite(chunkBytes, sectionCount, 20260924L, null);
    assertFalse(result.failed(), "真实负载不得出现解码/重编码异常：" + result.failure());
    System.out.println("[真机负载] 再次改写：命中目标（不看遮挡）="
        + processor.diagnose(chunkBytes, sectionCount).targetMatches()
        + " 个｜本次实际替换=" + result.obfuscatedPositions().length
        + " 个｜输出字节 " + result.data().length + " 字节"
        + "｜字节是否变化=" + (result.data() != chunkBytes ? "是" : "否"));

    // ---- 6. 解码 → 重编码必须与原字节逐字节一致（真实负载的 codec 往返）
    try (Chunk chunk = codec.decode(chunkBytes, sectionCount)) {
      byte[] reencoded = chunk.finalizeOutput();
      assertTrue(java.util.Arrays.equals(chunkBytes, reencoded),
          "真实负载的解码→重编码必须逐字节一致；不一致即说明 codec 与真机布局不符（这才是根因）");
    }
    System.out.println("[真机负载] 解码→重编码：与原字节逐字节一致 ✔");

    // ---- 7. 结论性断言
    assertTrue(positionCount > 0,
        "真机缓存的每条负载都带着伪装坐标（本次 " + positionCount + " 个）："
            + "说明「目标匹配 + 遮挡判定 + 替换」在真机上确实算出了结果，"
            + "『一个方块都没被替换』（可能一）不成立");
  }

  /** 「解码 → 重编码」以外的第二个判据：负载里残留的目标矿是否都已不是「本应被伪装的」。 */
  @Test
  void obfuscatedPayloadContainsNoFullyOccludedOre() throws IOException {
    byte[] payload = readResource();
    ByteBuffer buffer = ByteBuffer.wrap(payload);
    buffer.getLong();
    int positionCount = buffer.getInt();
    buffer.position(buffer.position() + positionCount * Integer.BYTES);
    byte[] chunkBytes = new byte[buffer.remaining()];
    buffer.get(chunkBytes);

    ChunkCodec codec = new ChunkCodec(registry, ChunkVersionFlags.PAPER_26_2);
    int sectionCount = detectSectionCount(codec, chunkBytes);
    // 真机负载由「P1-5 清单扩展前」的 21 种目标清单产出；按产出当时的口径复跑，
    // 才能回答「改写结果是否已落进缓存字节」——用新清单复跑会把负载里本就不在旧清单中的
    // 箱子/基岩等方块算成「可替换」，比例判据随之失真（清单本身的对错由注册表测试核实）。
    ObfuscationProcessor processor = processor(codec, LEGACY_HIDE_BLOCKS);

    // 这份负载是「已改写」的结果：若改写结果真的落进了字节，则再跑一遍应当几乎无可替换
    // （只有区块边界因缺少邻块快照、按 missing-policy=hide 多算的几个可能被再次替换）。
    ObfuscationProcessor.Result again = processor.rewrite(chunkBytes, sectionCount, 1L, null);
    int targetMatches = processor.diagnose(chunkBytes, sectionCount).targetMatches();
    System.out.println("[真机负载] 负载内目标矿（含暴露的）=" + targetMatches
        + " 个；按本插件口径「仍可被替换」=" + again.obfuscatedPositions().length + " 个");
    assertTrue(again.obfuscatedPositions().length * 4 < Math.max(1, targetMatches),
        "负载里的目标矿绝大多数已不是「本应被伪装」的状态：说明改写结果已写进缓存字节，"
            + "真机看到的仍是真实矿物只能发生在写回客户端这一侧（可能二）");
  }

  // ------------------------------------------------------------------ 工具

  private static byte[] readResource() throws IOException {
    try (InputStream input = RealChunkPayloadRegressionTest.class.getResourceAsStream(RESOURCE)) {
      return input == null ? null : input.readAllBytes();
    }
  }

  /** 第一个「刚好把字节用完」的 section 数即为真值（偏小会剩尾部字节，偏大会解析越界）。 */
  private static int detectSectionCount(ChunkCodec codec, byte[] data) {
    for (int count = 1; count <= 64; count++) {
      try (Chunk chunk = codec.decode(data, count)) {
        int consumed = 0;
        for (int index = 0; index < count; index++) {
          Chunk.SectionRange range = chunk.originalSectionRange(index);
          if (range != null) {
            consumed += range.length();
          }
        }
        if (consumed == data.length) {
          return count;
        }
      } catch (RuntimeException exception) {
        // section 数偏大：字节不够解析 → 换下一个候选
      }
    }
    return -1;
  }

  private static int countNonAir(ChunkSection section) {
    int count = 0;
    for (int state : section.readAllBlockStates()) {
      if (!registry.isAir(state)) {
        count++;
      }
    }
    return count;
  }

  /** 从 section 原始字节读出 bitsPerBlock 与调色板条目数（26.2：head 后带流体计数、无 long 长度字段）。 */
  private static String describeRawSection(byte[] data, Chunk.SectionRange range) {
    if (range == null) {
      return "区间未知";
    }
    ByteBuf buffer = Unpooled.wrappedBuffer(data, range.offset(), range.length());
    try {
      buffer.readUnsignedShort(); // blockCount
      buffer.readUnsignedShort(); // fluidCount（26.2 起）
      int bitsPerBlock = buffer.readUnsignedByte();
      String palette;
      if (bitsPerBlock == 0) {
        ByteBufUtil.readVarInt(buffer); // 单值调色板的唯一取值
        palette = "调色板条目=1（单值）";
      } else if (bitsPerBlock <= 8) {
        palette = "调色板条目=" + ByteBufUtil.readVarInt(buffer);
      } else {
        palette = "调色板条目=无（直接格式）";
      }
      return "bitsPerBlock=" + bitsPerBlock + "，" + palette;
    } catch (RuntimeException exception) {
      return "section 头部解析失败：" + exception;
    } finally {
      buffer.release();
    }
  }

  private static String blockName(int stateId) {
    try {
      return WrappedBlockState.getByGlobalId(version, stateId, false).getType().getName();
    } catch (RuntimeException exception) {
      return "未知(id=" + stateId + ")";
    }
  }

  /**
   * 产出本负载当时的 21 种目标清单（P1-5 扩展前）。
   *
   * <p>负载 {@code real-chunk-payload.bin} 由打包了旧清单的插件版本写进磁盘缓存；复跑判据
   * （「再跑一遍应几乎无可替换」）只有在<b>与产出方相同的目标集合</b>下才成立，故此处显式固化旧清单，
   * 不随打包 antixray.yml 的后续扩展漂移。扩展清单本身的可解析性与覆盖面由
   * {@code PeRealDataOcclusionTest} / {@code ConfigDefaultsTest} 核实。
   */
  private static final List<String> LEGACY_HIDE_BLOCKS = List.of(
      "coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore",
      "copper_ore", "deepslate_copper_ore", "gold_ore", "deepslate_gold_ore",
      "redstone_ore", "deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore",
      "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore",
      "nether_gold_ore", "nether_quartz_ore", "ancient_debris",
      "spawner", "mossy_cobblestone");

  /** 按真机配置装配处理器：目标取传入清单，伪装权重=stone 10/deepslate 8/netherrack 6。 */
  private static ObfuscationProcessor processor(ChunkCodec codec, List<String> hideBlocks) {
    BitSet targets = new BitSet(registry.getUniqueBlockStateCount());
    for (String name : hideBlocks) {
      int stateId = BlockStateRegistry.resolveStateId(version, name);
      if (stateId >= 0) {
        targets.set(stateId);
      }
    }
    int[] replacementIds = {
        BlockStateRegistry.resolveStateId(version, "stone"),
        BlockStateRegistry.resolveStateId(version, "deepslate"),
        BlockStateRegistry.resolveStateId(version, "netherrack")};
    int[] weights = {10, 8, 6};
    int[] cumulative = new int[weights.length];
    int sum = 0;
    for (int index = 0; index < weights.length; index++) {
      sum += weights[index];
      cumulative[index] = sum;
    }
    // 真机配置：layer-obfuscation=false，neighbors.missing-policy=hide（离线无邻块快照，按缺失处理）
    return new ObfuscationProcessor(codec, registry::isOccluding, targets, replacementIds, cumulative,
        false, true, ObfuscationProcessor.PaletteOptions.DISABLED);
  }

  /** 读取随插件打包的 {@code antixray.yml} 里的 {@code obfuscation.hide-blocks}（不依赖 Bukkit YAML）。 */
  private static List<String> shippedHideBlocks() throws IOException {
    try (InputStream input = RealChunkPayloadRegressionTest.class.getResourceAsStream("/antixray.yml")) {
      assertNotNull(input, "classpath 里必须有打包的 antixray.yml（src/main/resources）");
      List<String> names = new ArrayList<>();
      boolean inObfuscation = false;
      boolean inHideBlocks = false;
      List<String> lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
          .lines().toList();
      for (String raw : lines) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        if (raw.startsWith("obfuscation:")) {
          inObfuscation = true;
          continue;
        }
        if (!inObfuscation) {
          continue;
        }
        if (line.startsWith("hide-blocks:")) {
          inHideBlocks = true;
          continue;
        }
        if (inHideBlocks && line.startsWith("- ")) {
          names.add(line.substring(2).trim());
          continue;
        }
        if (inHideBlocks) {
          break;
        }
      }
      names.sort(Comparator.naturalOrder());
      return names;
    }
  }

  /** 仅供人工核对：把「状态 id → 方块名」映射打印成一行（避免日志太长时被截断）。 */
  @Test
  void printStateNameMappingSample() {
    System.out.println("[真机负载] 关键 id：stone=" + BlockStateRegistry.resolveStateId(version, "stone")
        + "，deepslate=" + BlockStateRegistry.resolveStateId(version, "deepslate")
        + "，air=" + BlockStateRegistry.resolveStateId(version, "air")
        + "，coal_ore=" + BlockStateRegistry.resolveStateId(version, "coal_ore")
        + "，deepslate_diamond_ore="
        + BlockStateRegistry.resolveStateId(version, "deepslate_diamond_ore")
        + "（状态总数 " + registry.getUniqueBlockStateCount()
        + "，遮挡状态数 " + registry.occludingStateCount() + "）");
    assertEquals(32366, registry.getUniqueBlockStateCount(), "必须与真机日志一致（PE 2.13.0 / V_26_2）");
  }
}