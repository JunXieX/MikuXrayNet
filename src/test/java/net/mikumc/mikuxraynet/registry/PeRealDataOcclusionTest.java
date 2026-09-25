package net.mikumc.mikuxraynet.registry;

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
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.mikumc.mikuxraynet.antixray.ObfuscationProcessor;
import net.mikumc.mikuxraynet.codec.Chunk;
import net.mikumc.mikuxraynet.codec.ChunkCodec;
import net.mikumc.mikuxraynet.codec.ChunkVersionFlags;
import net.mikumc.mikuxraynet.codec.TestChunkBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 用<b>真实 PacketEvents 静态数据</b>验证「遮挡判定 + 目标方块集合」的测试（CI 可跑，不依赖平台初始化）。
 *
 * <p><b>为什么需要它</b>：原先把判定规则摊平成纯值后用桩数据单测，覆盖不到「PE 实际给出的方块语义」——
 * 而抗矿透的正确性完全建立在这份语义上。本测试直接驱动生产代码
 * （{@link BlockStateRegistry#build} / {@link OcclusionRules} / {@link ObfuscationProcessor}），
 * 输入是 PE 自带的方块状态映射（{@code assets/mappings/...}），断言真机上「哪些方块算遮挡、哪些矿物会被隐藏」。
 *
 * <p><b>平台初始化问题（已核实并说明）</b>：PE 2.13.0 的 {@code StateTypes} 静态初始化会走
 * {@code VersionedRegistry → TypesBuilder → MappingHelper.decompress}，而后者需要
 * {@code PacketEvents.getAPI().getSettings().getResourceProvider()}（见 MappingHelper 源码/字节码），
 * 因此**必须先提供一个 API 实例**。这里安装一个最小桩：只实现
 * {@link PacketEventsAPI} 的抽象方法，把 {@link ServerManager#getVersion()} 固定为
 * {@link ServerVersion#V_26_2}（与真机 Leaf 26.2 一致），其余返回 {@code null}；
 * 映射数据仍来自 PE 自带资源（默认资源提供者即 classpath），故验证的是真数据而非自造数据。
 *
 * <p><b>版本常量交叉核对</b>：真机 {@code E:\Server\test\logs\latest.log} 记录
 * 「方块状态映射构建完成：服务端版本 26.2，状态数 32366，直接格式位宽 15」——与本测试断言一致。
 */
class PeRealDataOcclusionTest {

  /** 与真机一致的服务端版本（PE 的 ServerVersion，26.2）。 */
  private static final ServerVersion SERVER = ServerVersion.V_26_2;

  /** 真机日志实测的状态总数（PE 2.13.0 / V_26_2）。 */
  private static final int REAL_STATE_COUNT = 32366;

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
    // 还原全局状态：其余测试不依赖 PacketEvents
    PacketEvents.setAPI(null);
  }

  /** 只提供「设置（含资源提供者）」与「服务端版本」的最小 PE API 桩。 */
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

  /**
   * 遮挡分类必须与真实 PE 语义一致。
   *
   * <p>这是抗矿透的地基：若 stone / deepslate 被判成「不遮挡」，{@code shouldObfuscate} 恒为 false，
   * 所有矿物都会原样下发（真机表现为「仍能透视看到矿物」）。
   */
  @Test
  void occlusionClassificationMatchesRealPacketEventsData() {
    assertEquals(REAL_STATE_COUNT, registry.getUniqueBlockStateCount(),
        "状态总数必须与真机日志一致（PE 版本/映射被改动时请同步核对真机日志）");
    assertEquals(15, registry.getMaxBitsPerBlockState(), "直接格式位宽必须与真机日志一致（15）");

    String[] mustOcclude = {
        "stone", "deepslate", "dirt", "grass_block", "netherrack", "end_stone", "obsidian",
        "granite", "diorite", "andesite", "tuff", "calcite", "anvil", "deepslate_diamond_ore",
        // P1-5：结构暴露型隐藏方块必须判为遮挡（否则显形射线会误判其可见性）
        "bedrock", "raw_iron_block", "raw_gold_block", "raw_copper_block", "clay", "barrel",
        "furnace", "blast_furnace", "smoker", "hopper", "dropper", "dispenser"};
    for (String name : mustOcclude) {
      assertTrue(registry.isOccluding(requireStateId(name)),
          name + " 必须判为遮挡（真机 PE 2.13.0 / V_26_2）");
    }

    String[] mustNotOcclude = {
        "air", "cave_air", "water", "lava", "glass", "oak_leaves", "oak_stairs", "oak_fence",
        "torch", "chest",
        // P1-5：箱子族为「实心但形状小于整方块」，白名单判非遮挡（1.0.x 既有行为，保持）
        "trapped_chest", "ender_chest", "shulker_box"};
    for (String name : mustNotOcclude) {
      assertFalse(registry.isOccluding(requireStateId(name)),
          name + " 必须判为不遮挡（真机 PE 2.13.0 / V_26_2）");
    }
  }

  /** PE 真实数据里各抽样方块的语义摊平结果（打印到测试输出，便于人工核对判定依据）。 */
  @Test
  void printRealStateFacts() {
    String[] samples = {"stone", "deepslate", "air", "cave_air", "water", "glass", "oak_leaves",
        "oak_stairs", "oak_fence", "torch", "chest", "anvil", "deepslate_diamond_ore"};
    for (String name : samples) {
      StateType type = StateTypes.getByName(name);
      assertNotNull(type, "PE 真实数据里必须存在该方块：" + name);
      System.out.println("[PE 真实语义] " + name + " → solid=" + type.isSolid()
          + " air=" + type.isAir() + " exceedsCube=" + type.exceedsCube()
          + " material=" + type.getMaterialType()
          + " → 遮挡=" + registry.isOccluding(requireStateId(name)));
    }
  }

  /**
   * 打包 antixray.yml 的<b>按维度</b>隐藏清单必须在 PE 真实映射里逐一可解析，且覆盖全部真实矿类。
   *
   * <p><b>唯一例外</b>：{@code nether_quartz_ore} 按设计不隐藏（地狱分布极广、价值极低，
   * 全藏会让玩家到处挖到假石头）——这里把这一例外显式断言出来，防止「漏项」与「有意排除」被混淆。
   */
  @Test
  void shippedHideBlocksCoverEveryRealOreWithQuartzIntentionalException() throws IOException {
    Map<String, List<String>> byDimension = shippedHideBlocksByDimension();
    System.out.println("[按维度隐藏清单] " + byDimension);

    assertEquals(Set.of("normal", "nether", "the_end"), byDimension.keySet(),
        "打包配置必须含 normal / nether / the_end 三个维度段");
    List<String> normal = byDimension.get("normal");
    List<String> nether = byDimension.get("nether");
    List<String> end = byDimension.get("the_end");
    assertEquals(35, normal.size(), "主世界默认清单应为 35 种：" + normal);
    assertEquals(15, nether.size(), "地狱默认清单应为 15 种：" + nether);
    assertEquals(12, end.size(), "末地默认清单应为 12 种：" + end);

    for (List<String> names : byDimension.values()) {
      for (String name : names) {
        int stateId = BlockStateRegistry.resolveStateId(version, name);
        assertTrue(stateId >= 0 && stateId < registry.getUniqueBlockStateCount(),
            "配置里的隐藏方块必须在 PE 真实映射里可解析：" + name + "（解析结果 " + stateId + "）");
      }
    }

    // 用户核心诉求：地狱默认不隐藏石英矿
    assertFalse(nether.contains("nether_quartz_ore"),
        "地狱默认清单不得含 nether_quartz_ore（用户明确要求不隐藏石英矿）：" + nether);
    assertTrue(nether.contains("ancient_debris") && nether.contains("nether_gold_ore"),
        "地狱默认清单必须含下界残骸与下界金矿：" + nether);
    assertTrue(normal.contains("spawner"),
        "主世界清单必须含 spawner（刷怪笼：PE 26.2 里 mob_spawner 已改名为 spawner）：" + normal);
    assertTrue(normal.contains("mossy_cobblestone"),
        "主世界清单必须含 mossy_cobblestone（苔石，地牢/要塞/矿洞结构的标志物）：" + normal);

    Set<String> realOres = new TreeSet<>();
    for (StateType type : StateTypes.values()) {
      String name = type.getName();
      if (name.endsWith("_ore") || "ancient_debris".equals(name)) {
        realOres.add(name);
      }
    }
    System.out.println("[PE 真实矿类] 共 " + realOres.size() + " 种 → " + realOres);

    Set<String> union = new TreeSet<>();
    union.addAll(normal);
    union.addAll(nether);
    union.addAll(end);
    List<String> missing = new ArrayList<>();
    for (String ore : realOres) {
      if (!union.contains(ore)) {
        missing.add(ore);
      }
    }
    assertEquals(List.of("nether_quartz_ore"), missing,
        "打包配置应覆盖全部 PE 真实矿类，唯一例外是 nether_quartz_ore（按设计不隐藏）：" + missing);
  }

  /**
   * 要求核实的关键方块名（26.2 状态表）：{@code nether_quartz_ore}、{@code blackstone}、
   * {@code basalt}、{@code end_stone} 等必须可解析（不猜名称）。
   */
  @Test
  void netherAndEndBlockNamesResolveAgainstRealMapping() {
    String[] names = {
        "nether_quartz_ore", "nether_gold_ore", "ancient_debris", "netherrack", "basalt",
        "blackstone", "end_stone", "soul_sand", "magma_block"};
    for (String name : names) {
      int stateId = BlockStateRegistry.resolveStateId(version, name);
      assertTrue(stateId >= 0 && stateId < registry.getUniqueBlockStateCount(),
          "PE 真实映射（V_26_2）里必须能解析出状态 id：" + name + "（解析结果 " + stateId + "）");
      System.out.println("[名称核实] " + name + " → id=" + stateId);
    }
  }

  /**
   * 流体覆盖掩码必须与真机 PE 材质语义一致：只认水/岩浆（含流动变体）与水柱，
   * <b>排除含水方块</b>（真机 PE 的 {@code isFluid()} 把 11728 个含水状态也算作流体，二者不可混用）。
   */
  @Test
  void fluidCoverMaskExcludesWaterloggedStates() {
    assertTrue(registry.isFluidCover(requireStateId("water")), "水必须是流体覆盖");
    assertTrue(registry.isFluidCover(requireStateId("lava")), "岩浆必须是流体覆盖");
    // 这些方块本体不是流体（即便某些状态下 can be waterlogged）→ 不算流体覆盖
    for (String name : new String[] {"oak_fence", "oak_stairs", "oak_slab", "chest", "stone",
        "netherrack", "end_stone"}) {
      assertFalse(registry.isFluidCover(requireStateId(name)), name + " 本体不是流体，不算流体覆盖");
    }

    int fluidTotal = 0;
    int coverTotal = 0;
    int waterloggedOnly = 0;
    for (int id = 0; id < registry.getUniqueBlockStateCount(); id++) {
      if (registry.isFluid(id)) {
        fluidTotal++;
      }
      if (registry.isFluidCover(id)) {
        coverTotal++;
      }
      if (registry.isFluid(id) && !registry.isFluidCover(id)) {
        waterloggedOnly++;
      }
    }
    System.out.println("[流体掩码] isFluid=" + fluidTotal + "，isFluidCover=" + coverTotal
        + "，仅 isFluid（含水状态）=" + waterloggedOnly);
    assertTrue(waterloggedOnly > 0,
        "真机 PE 的 isFluid 把含水状态也算作流体，必须被流体覆盖掩码排除（否则上方是含水台阶也会保持伪装）");
    assertTrue(coverTotal > 0 && coverTotal <= 64,
        "流体覆盖掩码只应包含水/岩浆/水柱这类「本体即流体」的少数状态：" + coverTotal);
  }

  /** 读取打包 antixray.yml 里各维度的 hide-blocks（不依赖 Bukkit YAML）。 */
  private static Map<String, List<String>> shippedHideBlocksByDimension() throws IOException {
    try (InputStream input = PeRealDataOcclusionTest.class.getResourceAsStream("/antixray.yml")) {
      assertNotNull(input, "classpath 里必须有打包的 antixray.yml（src/main/resources）");
      Map<String, List<String>> result = new LinkedHashMap<>();
      boolean inDimensions = false;
      String currentDimension = null;
      boolean inHideBlocks = false;
      List<String> rawLines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
          .lines().toList();
      for (String raw : rawLines) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int indent = raw.indexOf(line.charAt(0));
        // 去掉行尾注释后再做键判断（维度键后面常带中文说明）
        int comment = line.indexOf('#');
        String code = (comment >= 0 ? line.substring(0, comment) : line).trim();
        if (code.startsWith("dimensions:")) {
          inDimensions = true;
          continue;
        }
        if (!inDimensions) {
          continue;
        }
        if (indent == 0) {
          break; // 离开 dimensions 段（下一个顶层键）
        }
        if (indent == 2 && code.endsWith(":")) {
          currentDimension = code.substring(0, code.length() - 1).trim();
          result.put(currentDimension, new ArrayList<>());
          inHideBlocks = false;
          continue;
        }
        if (code.startsWith("hide-blocks:")) {
          inHideBlocks = true;
          continue;
        }
        if (inHideBlocks && code.startsWith("- ")) {
          result.get(currentDimension).add(code.substring(2).trim());
          continue;
        }
        if (inHideBlocks) {
          inHideBlocks = false; // 下一个键：隐藏清单结束
        }
      }
      return result;
    }
  }

  /**
   * P1-5 扩展清单必须在真机 PE 状态映射里逐一可解析（<b>核实而非猜测</b>）：
   * 17 个新目标方块的注册名以 PE 2.13.0 / V_26_2 的映射数据为准。
   */
  @Test
  void expandedHideBlocksResolveAgainstRealPacketEventsMapping() {
    String[] expanded = {
        // 容器/功能方块（多为方块实体）
        "chest", "trapped_chest", "ender_chest", "barrel",
        "furnace", "blast_furnace", "smoker", "hopper", "dropper", "dispenser", "shulker_box",
        // 结构暴露型
        "bedrock", "raw_iron_block", "raw_gold_block", "raw_copper_block", "obsidian", "clay"};
    for (String name : expanded) {
      int stateId = BlockStateRegistry.resolveStateId(version, name);
      assertTrue(stateId >= 0 && stateId < registry.getUniqueBlockStateCount(),
          "P1-5 扩展目标方块必须在 PE 真实映射（V_26_2）里可解析：" + name + "（解析结果 " + stateId + "）");
    }
  }

  /**
   * 端到端：用<b>真实状态 id</b>构造「被石头完全掩埋的深层钻石矿」，必须被替换为伪装方块。
   *
   * <p>覆盖「解码 → 目标匹配 → 6 面遮挡判定 → 重编码」整条链路（原先只有桩注册表的覆盖）。
   * 若这条通过而真机仍失效，则问题不在判定与编解码，而在平台侧（写回 / 版本差异 / 第三方插件），
   * 需要按真机诊断输出继续排查。
   */
  @Test
  void buriedOreIsObfuscatedEndToEndWithRealStateIds() {
    int stoneId = requireStateId("stone");
    int oreId = requireStateId("deepslate_diamond_ore");

    int[] states = new int[4096];
    java.util.Arrays.fill(states, stoneId);
    states[index(8, 8, 8)] = oreId;

    TestChunkBuilder builder = new TestChunkBuilder(ChunkVersionFlags.PAPER_26_2);
    builder.directSection(15, 4096, 0, states, 0, new int[] {7});
    byte[] source = builder.build();

    BitSet targets = new BitSet();
    targets.set(oreId);
    ChunkCodec codec = new ChunkCodec(registry, ChunkVersionFlags.PAPER_26_2);
    ObfuscationProcessor processor = new ObfuscationProcessor(codec, registry::isOccluding, targets,
        new int[] {stoneId}, new int[] {1}, false, true,
        ObfuscationProcessor.PaletteOptions.DISABLED);

    ObfuscationProcessor.Result result = processor.rewrite(source, 1, 42L, null);

    assertFalse(result.failed(), "真实状态 id 下不得出现解码/重编码异常：" + result.failure());
    assertTrue(result.changed(), "被石头完全掩埋的深层钻石矿必须被伪装");
    assertEquals(1, result.obfuscatedPositions().length);
    assertEquals(index(8, 8, 8), result.obfuscatedPositions()[0]);

    try (Chunk chunk = codec.decode(result.data(), 1)) {
      assertEquals(stoneId, chunk.getSection(0).getBlockState(index(8, 8, 8)),
          "伪装后的方块状态必须是配置的伪装方块（石头）");
    }
    System.out.println("[端到端] stone=" + stoneId + "，deepslate_diamond_ore=" + oreId
        + " → 掩埋矿改写为石头=" + stoneId);
  }

  /** 只剩一层泥土/石头暴露面时必须保持原样（防止「把可见矿藏起来」）。 */
  @Test
  void exposedOreKeepsRealState() {
    int stoneId = requireStateId("stone");
    int airId = requireStateId("air");
    int oreId = requireStateId("diamond_ore");

    int[] states = new int[4096];
    java.util.Arrays.fill(states, stoneId);
    states[index(8, 8, 8)] = oreId;
    states[index(9, 8, 8)] = airId;

    TestChunkBuilder builder = new TestChunkBuilder(ChunkVersionFlags.PAPER_26_2);
    builder.directSection(15, 4095, 0, states, 0, new int[] {7});
    byte[] source = builder.build();

    BitSet targets = new BitSet();
    targets.set(oreId);
    ChunkCodec codec = new ChunkCodec(registry, ChunkVersionFlags.PAPER_26_2);
    ObfuscationProcessor processor = new ObfuscationProcessor(codec, registry::isOccluding, targets,
        new int[] {stoneId}, new int[] {1}, false, true,
        ObfuscationProcessor.PaletteOptions.DISABLED);

    ObfuscationProcessor.Result result = processor.rewrite(source, 1, 42L, null);

    assertFalse(result.changed(), "有一面暴露（旁边是空气）时不得伪装");
  }

  private static int requireStateId(String name) {
    int stateId = BlockStateRegistry.resolveStateId(version, name);
    assertTrue(stateId >= 0, "PE 真实映射里必须能解析出状态 id：" + name);
    return stateId;
  }

  private static int index(int x, int y, int z) {
    return y << 8 | z << 4 | x;
  }
}