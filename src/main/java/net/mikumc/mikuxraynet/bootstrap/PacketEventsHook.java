package net.mikumc.mikuxraynet.bootstrap;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;
import org.bukkit.Material;

/**
 * PacketEvents 接入点：<b>只在启动期使用一次</b>。
 *
 * <p>契约：只做「探测就绪 → 取服务端版本 → 构建方块状态映射位图」三件事。
 * 构建完成后热路径只访问 {@link BlockStateRegistry} 的 {@code int / BitSet}，
 * 不再触碰 PacketEvents，更不使用它的任何事件注册 API（封包拦截唯一通道是 ProtocolLib）。
 *
 * <p>所有失败路径都返回 {@code false} 并打印中文日志，绝不抛异常导致服务端启动失败。
 */
public final class PacketEventsHook {

  private final Logger logger;
  /**
   * 诊断输出目录（插件数据目录）：口径差异导出文件 {@code occlusion-diff.txt} 落在这里，
   * 与 {@code util.Diagnostics#writeDump()} 取同一处，保证管理员在一个目录里就能找到全部诊断产物。
   */
  private final File dataFolder;

  private BlockStateRegistry registry;
  private String releaseName;

  public PacketEventsHook(Logger logger, File dataFolder) {
    this.logger = logger;
    this.dataFolder = dataFolder;
  }

  /**
   * 探测 PacketEvents 是否就绪，并构建方块状态映射。
   *
   * @param extraOccluding    遮挡判定覆盖表：额外视为「遮挡」的方块名
   * @param extraNonOccluding 遮挡判定覆盖表：额外视为「不遮挡」的方块名
   * @return true 表示映射可用；false 表示模块应降级停用
   */
  public boolean initialize(Collection<String> extraOccluding,
      Collection<String> extraNonOccluding) {
    try {
      PacketEventsAPI<?> api = PacketEvents.getAPI();
      if (api == null || !api.isInitialized()) {
        logger.warning("PacketEvents 尚未就绪，反矿透模块停用（请确认前置插件 packetevents 已正常加载）");
        return false;
      }

      ServerVersion version = api.getServerManager().getVersion();
      this.releaseName = version == null ? "未知" : version.getReleaseName();
      // Bukkit 权威口径（Material#isOccluding()）在此处一次性取好：既用于构建「实体遮挡口径表」
      // （异步实体位图用），也用于下面的口径对照。**这是唯一能同时拿到 PE 与 Bukkit 的地方**。
      Map<String, Boolean> bukkitOccluding = bukkitOccludingByBlockName();
      this.registry = BlockStateRegistry.build(extraOccluding, extraNonOccluding, logger,
          bukkitOccluding);

      logger.info("方块状态映射构建完成：服务端版本 " + releaseName
          + "，状态数 " + registry.getUniqueBlockStateCount()
          + "，直接格式位宽 " + registry.getMaxBitsPerBlockState());
      logger.info(occlusionSelfCheck(registry));
      for (String line : occlusionCrossCheck(registry, bukkitOccluding)) {
        logger.info(line);
      }
      return true;
    } catch (Throwable throwable) {
      logger.log(Level.SEVERE, "构建方块状态映射失败，反矿透模块停用", throwable);
      this.registry = null;
      return false;
    }
  }

  /**
   * 遮挡表自检：输出遮挡状态数与典型方块的抽样判定。
   *
   * <p>这是「反矿透是否真的会隐藏」的地基自证：若这里的 stone / deepslate 不是「遮挡」，
   * 那么 {@code shouldObfuscate} 恒为 false、所有矿物都会原样下发（不伪装）。
   */
  private static String occlusionSelfCheck(BlockStateRegistry registry) {
    StringBuilder message = new StringBuilder("遮挡表就绪：遮挡 ")
        .append(registry.occludingStateCount()).append('/')
        .append(registry.getUniqueBlockStateCount());
    for (String name : new String[] {"stone", "deepslate", "air", "water", "anvil"}) {
      int stateId = BlockStateRegistry.resolveStateId(name);
      message.append("；抽样 ").append(name).append('=')
          .append(stateId < 0 ? "未识别" : (registry.isOccluding(stateId) ? "遮挡" : "非遮挡"));
    }
    return message.toString();
  }

  /**
   * Bukkit 权威口径：方块名 → {@code Material#isOccluding()}（NMS 的 {@code isOccluding} 标记）。
   *
   * <p><b>为什么只能在真机取、不能做成单测</b>：{@code Material#isOccluding()} 依赖服务端的
   * {@code RegistryAccess} 实现，纯离线环境下 {@code Material} 类初始化即抛
   * {@code IllegalStateException: No RegistryAccess implementation found}（已实测确认），
   * 因此这张表只在真实服务端启动期构建。
   *
   * <p>历史（{@code LEGACY_*}）条目必须排除：它们是 1.13 前的兼容别名，{@code getKey()} /
   * {@code isBlock()} 会抛 {@code IllegalArgumentException}——真机首版正是因为遍历到它们而整段对照被
   * 跳过（等于没做对照）。它们也不是任何现代方块的注册状态。
   */
  private static Map<String, Boolean> bukkitOccludingByBlockName() {
    Map<String, Boolean> map = new HashMap<>(2048);
    for (Material material : Material.values()) {
      try {
        if (material.isLegacy() || !material.isBlock()) {
          continue;
        }
        map.putIfAbsent(material.getKey().getKey(), material.isOccluding());
      } catch (Throwable ignored) {
        // 单个条目不可读（平台/别名差异）不影响整张表
      }
    }
    return map;
  }

  /**
   * 遮挡口径对照（真机唯一可验证的一环）：把两套口径各与 Bukkit 权威口径逐方块比对。
   *
   * <ol>
   *   <li><b>反矿透伪装口径</b>（{@link BlockStateRegistry#isOccluding}，PE 状态语义启发式）——两者不一致
   *       属<b>既定设计</b>：伪装口径刻意偏多判（宁可多伪装；漏伪装等于把矿物直接发给透视端）。</li>
   *   <li><b>实体遮挡口径</b>（{@link BlockStateRegistry#isEntityOccluding}，异步位图用）——两者应当
   *       <b>完全一致</b>；出现不一致即为缺陷（同一功能两种口径必然漂移），必须反馈。</li>
   * </ol>
   *
   * <p>日志里各口径只列前 8 条样本（保持既有格式），但<b>全部</b>差异连同方向会写入
   * {@code occlusion-diff.txt}（数据目录，每次启动覆盖），未比对（名对不上）的方块名一并列出，
   * 供后续人工 triage。导出失败只打一次中文 WARN，绝不影响启动。
   *
   * @return 两行对照日志（伪装口径一行、实体口径一行）+ 一行导出提示（导出失败时不含后者）
   */
  private List<String> occlusionCrossCheck(BlockStateRegistry registry,
      Map<String, Boolean> bukkitOccluding) {
    List<String> lines = new ArrayList<>(3);
    try {
      int compared = 0;
      int camouflageMismatch = 0;
      int entityMismatch = 0;
      int skipped = 0;
      StringBuilder camouflageSamples = new StringBuilder();
      StringBuilder entitySamples = new StringBuilder();
      // 全量差异分方向收集（供导出文件）：偏漏（口径判非遮挡 / Bukkit 判遮挡）是真实泄露风险，
      // 必须排在偏多藏（不该藏的藏了，仅伤体验）之前，便于 triage 优先处理。
      List<String> camouflageLeaks = new ArrayList<>();
      List<String> camouflageOverHides = new ArrayList<>();
      List<String> entityLeaks = new ArrayList<>();
      List<String> entityOverHides = new ArrayList<>();
      List<String> skippedNames = new ArrayList<>();
      for (Material material : Material.values()) {
        try {
          if (material.isLegacy() || !material.isBlock()) {
            continue;
          }
          String name = material.getKey().getKey();
          Boolean bukkit = bukkitOccluding.get(name);
          if (bukkit == null) {
            continue;
          }
          int stateId = BlockStateRegistry.resolveStateId(name);
          if (stateId < 0) {
            skipped++;
            skippedNames.add("minecraft:" + name);
            continue; // 注册表里没有对应方块（名称对不上），单独计数以便分辨「没比对」与「一致」
          }
          compared++;
          boolean camouflage = registry.isOccluding(stateId);
          if (bukkit != camouflage) {
            camouflageMismatch++;
            if (camouflageMismatch <= 8) {
              appendMismatch(camouflageSamples, name, "伪装口径", bukkit, camouflage);
            }
            // Bukkit 判遮挡而伪装口径判非遮挡 = 偏漏；反之 = 偏多藏
            if (bukkit) {
              camouflageLeaks.add("minecraft:" + name);
            } else {
              camouflageOverHides.add("minecraft:" + name);
            }
          }
          boolean entity = registry.isEntityOccluding(stateId);
          if (bukkit != entity) {
            entityMismatch++;
            if (entityMismatch <= 8) {
              appendMismatch(entitySamples, name, "实体口径", bukkit, entity);
            }
            if (bukkit) {
              entityLeaks.add("minecraft:" + name);
            } else {
              entityOverHides.add("minecraft:" + name);
            }
          }
        } catch (Throwable perMaterial) {
          // 单个条目不可比对（平台/别名差异）不应让整段对照失败：跳过并计入「未比对」
          skipped++;
        }
      }
      if (compared == 0) {
        lines.add("遮挡口径对照：跳过（未能取到任何可比对的方块，平台可能未就绪）");
        return lines;
      }
      String tail = skipped > 0 ? "；另有 " + skipped + " 种条目无法比对，已跳过" : "";
      lines.add("反矿透伪装口径（PE 状态语义，刻意偏多判）：与 Bukkit 权威口径相差 "
          + camouflageMismatch + '/' + compared + " 种方块（属既定设计，不影响功能）"
          + camouflageSamples + (camouflageMismatch > 8 ? "；…（仅列出前 8 条）" : "") + tail);
      if (registry.entityOccludingStateCount() < 0) {
        lines.add("实体遮挡口径：未构建（离线场景或 Bukkit 表不可用），已回落 PE 口径"
            + "——异步实体位图此时与同步射线口径不同，请连同本条日志反馈");
      } else if (entityMismatch == 0) {
        lines.add("实体遮挡口径（异步位图用）：与 Bukkit 权威口径一致（比对 " + compared + " 种方块，无差异" + tail
            + "）；遮挡状态 " + registry.entityOccludingStateCount()
            + (registry.entityOcclusionFallbackCount() > 0
                ? "，其中 " + registry.entityOcclusionFallbackCount() + " 个状态因名称未命中 Bukkit 表而回落 PE 口径"
                : "，全部状态均由 Bukkit 口径直接判定"));
      } else {
        lines.add("实体遮挡口径（异步位图用）：发现 " + entityMismatch + '/' + compared
            + " 种方块与 Bukkit 权威口径不一致" + entitySamples
            + (entityMismatch > 8 ? "；…（仅列出前 8 条）" : "") + tail
            + "。这会让异步与同步两种模式的判定漂移，请连同本条日志反馈。");
      }
      String exportLine = exportOcclusionDiff(compared, camouflageMismatch, entityMismatch, skipped,
          camouflageLeaks, camouflageOverHides, entityLeaks, entityOverHides, skippedNames);
      if (exportLine != null) {
        lines.add(exportLine);
      }
    } catch (Throwable throwable) {
      lines.add("遮挡口径对照：跳过（" + throwable.getClass().getSimpleName() + "）");
    }
    return lines;
  }

  /**
   * 把全量差异导出到数据目录下的 {@code occlusion-diff.txt}（每次启动覆盖，只留最新一份）。
   *
   * <p>按「同一口径内先偏漏、再偏多藏，组内按方块名排序」组织：偏漏是真实泄露风险（该藏的没藏），
   * 必须优先处理；偏多藏只是体验损失。未比对方块名单列于末尾（纯参考信息）。
   *
   * <p><b>为什么包 try/catch</b>：这是启动期的诊断副产品，导出失败（磁盘只读、目录被占）绝不能让
   * 反矿透装配失败，故任何异常都只打一次中文 WARN 并放弃本次导出。
   *
   * @return 指向导出文件的日志行；无目录可写或导出失败时返回 {@code null}
   */
  private String exportOcclusionDiff(int compared, int camouflageMismatch, int entityMismatch,
      int skipped, List<String> camouflageLeaks, List<String> camouflageOverHides,
      List<String> entityLeaks, List<String> entityOverHides, List<String> skippedNames) {
    if (dataFolder == null) {
      return null;
    }
    File file = new File(dataFolder, "occlusion-diff.txt");
    try {
      Collections.sort(camouflageLeaks);
      Collections.sort(camouflageOverHides);
      Collections.sort(entityLeaks);
      Collections.sort(entityOverHides);
      Collections.sort(skippedNames);
      StringBuilder sb = new StringBuilder(2048);
      sb.append("# MikuXrayNet 遮挡口径差异导出（启动期自动生成，供人工 triage）\n");
      sb.append("# 服务端版本: ").append(releaseName).append('\n');
      sb.append("# 比对方块数: ").append(compared)
          .append("  伪装口径差异: ").append(camouflageMismatch)
          .append("  实体口径差异: ").append(entityMismatch)
          .append("  未比对: ").append(skipped).append('\n');
      sb.append("#\n");
      sb.append("# 方向说明：\n");
      sb.append("#   偏漏  （伪装口径判非遮挡 / Bukkit 判遮挡）= 该藏的没藏，属真实泄露风险 → 用 occlusion.extra-occluding 补上\n");
      sb.append("#   偏多藏（伪装口径判遮挡 / Bukkit 判非遮挡）= 不该藏的藏了，伤玩家体验 → 视情况用 occlusion.extra-non-occluding 移除\n");
      sb.append("#   （判定准则：以「玩家视觉能否看穿」为准，不以「多藏求稳」为准）\n");
      sb.append("#\n");
      for (String name : camouflageLeaks) {
        sb.append("[伪装口径] 偏漏  ").append(name).append('\n');
      }
      for (String name : camouflageOverHides) {
        sb.append("[伪装口径] 偏多藏 ").append(name).append('\n');
      }
      for (String name : entityLeaks) {
        sb.append("[实体口径] 偏漏  ").append(name).append("   ← 若出现，属缺陷，必须修").append('\n');
      }
      for (String name : entityOverHides) {
        sb.append("[实体口径] 偏多藏 ").append(name).append("   ← 若出现，属缺陷，必须修").append('\n');
      }
      sb.append("#\n");
      sb.append("# 未比对（Bukkit 表里有、但无法解析成 stateId，未能参与对照）：\n");
      for (String name : skippedNames) {
        sb.append(name).append('\n');
      }
      if (!dataFolder.isDirectory() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
        throw new IOException("无法创建数据目录：" + dataFolder.getAbsolutePath());
      }
      // 覆盖写（Files.writeString 默认 CREATE + TRUNCATE_EXISTING，只留最新一份），最朴素的 UTF-8 落盘
      Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
      return "遮挡口径差异已导出：" + file.getAbsolutePath()
          + "（伪装口径 " + camouflageMismatch + " 条 / 实体口径 " + entityMismatch
          + " 条；未比对 " + skipped + " 条）";
    } catch (Throwable exportFailure) {
      logger.log(Level.WARNING, "遮挡口径差异导出失败（不影响启动）：" + file.getAbsolutePath(),
          exportFailure);
      return null;
    }
  }

  /** 差异样本条目（形如 {@code ；minecraft:anvil（Bukkit=非遮挡，实体口径=遮挡）}）。 */
  private static void appendMismatch(StringBuilder samples, String name, String label,
      boolean bukkitOccluding, boolean pluginOccluding) {
    samples.append("；minecraft:").append(name)
        .append("（Bukkit=").append(bukkitOccluding ? "遮挡" : "非遮挡")
        .append('，').append(label).append('=').append(pluginOccluding ? "遮挡" : "非遮挡").append('）');
  }

  /** 已构建的注册表；未成功构建时为 null。 */
  public BlockStateRegistry registry() {
    return registry;
  }
}