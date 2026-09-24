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
import java.util.List;
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
        "granite", "diorite", "andesite", "tuff", "calcite", "anvil", "deepslate_diamond_ore"};
    for (String name : mustOcclude) {
      assertTrue(registry.isOccluding(requireStateId(name)),
          name + " 必须判为遮挡（真机 PE 2.13.0 / V_26_2）");
    }

    String[] mustNotOcclude = {
        "air", "cave_air", "water", "lava", "glass", "oak_leaves", "oak_stairs", "oak_fence",
        "torch", "chest"};
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
   * 真机配置里的目标矿必须能解析成状态 id，且覆盖 PE 已知的<b>全部</b>矿类方块。
   *
   * <p>这条断言专门防「只匹配到普通矿石、漏了深层变体」这类静默失效：即使规则正确，
   * 目标集合漏项也会让对应矿物照常下发。
   */
  @Test
  void shippedHideBlocksCoverEveryRealOre() throws IOException {
    List<String> configured = shippedHideBlocks();
    System.out.println("[目标方块清单] 配置共 " + configured.size() + " 种 → " + configured);

    for (String name : configured) {
      int stateId = BlockStateRegistry.resolveStateId(version, name);
      assertTrue(stateId >= 0 && stateId < registry.getUniqueBlockStateCount(),
          "配置里的目标方块必须在 PE 真实映射里可解析：" + name + "（解析结果 " + stateId + "）");
    }

    Set<String> realOres = new TreeSet<>();
    for (StateType type : StateTypes.values()) {
      String name = type.getName();
      if (name.endsWith("_ore") || "ancient_debris".equals(name)) {
        realOres.add(name);
      }
    }
    System.out.println("[PE 真实矿类] 共 " + realOres.size() + " 种 → " + realOres);

    List<String> missing = new ArrayList<>();
    for (String ore : realOres) {
      if (!configured.contains(ore)) {
        missing.add(ore);
      }
    }
    assertTrue(missing.isEmpty(),
        "配置的隐藏方块清单漏了 PE 真实存在的矿类方块：" + missing + "（应加入 antixray.yml 的 hide-blocks）");
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

  /** 读取随插件打包的 {@code antixray.yml} 里的 {@code obfuscation.hide-blocks}（不依赖 Bukkit YAML）。 */
  private static List<String> shippedHideBlocks() throws IOException {
    try (InputStream input = PeRealDataOcclusionTest.class.getResourceAsStream("/antixray.yml")) {
      assertNotNull(input, "classpath 里必须有打包的 antixray.yml（src/main/resources）");
      List<String> names = new ArrayList<>();
      boolean inObfuscationSection = false;
      boolean inHideBlocks = false;
      List<String> rawLines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
          .lines().toList();
      for (String raw : rawLines) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        if (raw.startsWith("obfuscation:")) {
          inObfuscationSection = true;
          continue;
        }
        if (!inObfuscationSection) {
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
        if (!line.startsWith("- ")) {
          break; // 离开列表（下一个键）
        }
      }
      return names;
    }
  }
}