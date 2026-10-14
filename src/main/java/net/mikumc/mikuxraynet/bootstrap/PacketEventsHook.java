package net.mikumc.mikuxraynet.bootstrap;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;
import net.mikumc.mikuxraynet.registry.StateOcclusionOracle;

/**
 * PacketEvents 接入点：<b>只在启动期使用一次</b>。
 *
 * <p>契约：只做「探测就绪 → 取服务端版本 → 构建方块状态映射位图」三件事。
 * 构建完成后热路径只访问 {@link BlockStateRegistry} 的 {@code int / BitSet}，
 * 不再触碰 PacketEvents，更不使用它的任何事件注册 API（封包拦截唯一通道是 ProtocolLib）。
 *
 * <p><b>遮挡口径自本次起改为「逐状态真值」</b>：由 {@link BukkitStateOcclusionOracle} 把每个方块状态
 * 解析成服务端 {@code BlockData#isOccluding()}（vanilla 逐状态语义，即「玩家视觉能否看穿」），
 * 解析失败的状态回落启发式并计数。伪装口径与实体口径都取自这一份真值，因此两套口径同源
 * （无 {@code occlusion.extra-*} 覆盖时逐状态判定完全一致）。
 *
 * <p>所有失败路径都返回 {@code false} 并打印中文日志，绝不抛异常导致服务端启动失败。
 */
public final class PacketEventsHook {

  private final Logger logger;

  private BlockStateRegistry registry;
  private String releaseName;

  public PacketEventsHook(Logger logger) {
    this.logger = logger;
  }

  /**
   * 探测 PacketEvents 是否就绪，并构建方块状态映射。
   *
   * @param extraOccluding    遮挡判定覆盖表：额外视为「遮挡」的方块名（优先级最高）
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
      // 逐状态真值的服务端权威实现：把 PE 状态字符串交给 Bukkit 解析成 BlockData#isOccluding()。
      // **这是唯一能同时拿到 PE 状态映射与 Bukkit 权威判定的地方**（离线环境 Bukkit 类不可用）。
      StateOcclusionOracle oracle = new BukkitStateOcclusionOracle();
      long startNanos = System.nanoTime();
      this.registry = BlockStateRegistry.build(extraOccluding, extraNonOccluding, logger, oracle);
      long buildMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);

      logger.info("方块状态映射构建完成：服务端版本 " + releaseName
          + "，状态数 " + registry.getUniqueBlockStateCount()
          + "，直接格式位宽 " + registry.getMaxBitsPerBlockState()
          + "，构建耗时 " + buildMillis + " ms");
      logger.info(occlusionSelfCheck(registry, buildMillis));
      logger.info(occlusionConsistencyCheck(registry));
      return true;
    } catch (Throwable throwable) {
      logger.log(Level.SEVERE, "构建方块状态映射失败，反矿透模块停用", throwable);
      this.registry = null;
      return false;
    }
  }

  /**
   * 遮挡表自检：输出「逐状态真值解析成功数 / 回落启发式数 / 覆盖表命中数 / 构建耗时」与典型方块抽样。
   *
   * <p>这是「反矿透是否真的会隐藏」的地基自证：若这里的 stone / deepslate 不是「遮挡」，
   * 那么 {@code shouldObfuscate} 恒为 false、所有矿物都会原样下发（不伪装）。
   */
  private static String occlusionSelfCheck(BlockStateRegistry registry, long buildMillis) {
    StringBuilder message = new StringBuilder("遮挡表就绪：遮挡 ")
        .append(registry.occludingStateCount()).append('/')
        .append(registry.getUniqueBlockStateCount())
        .append("；逐状态真值解析成功 ").append(registry.truthStateCount())
        .append("；回落启发式 ").append(registry.heuristicFallbackStateCount())
        .append("；覆盖表命中 ").append(registry.overrideHitStateCount())
        .append("；构建耗时 ").append(buildMillis).append(" ms");
    if (registry.heuristicFallbackStateCount() > 0) {
      message.append("（回落项方块名抽样：").append(preview(registry.heuristicFallbackNames())).append('）');
    }
    for (String name : new String[] {"stone", "deepslate", "air", "water", "anvil"}) {
      int stateId = BlockStateRegistry.resolveStateId(name);
      message.append("；抽样 ").append(name).append('=')
          .append(stateId < 0 ? "未识别" : (registry.isOccluding(stateId) ? "遮挡" : "非遮挡"));
    }
    return message.toString();
  }

  /**
   * 两套口径一致性检查：伪装口径与实体口径现在<b>同源</b>（同一逐状态真值），差异只可能来自
   * {@code occlusion.extra-*} 覆盖表；因此在没有配置覆盖表时差异应恒为 0。
   *
   * <p>出现差异即为「同一功能两种结论」的口径漂移，会让异步与同步模式表现不一致，必须核对覆盖表。
   */
  private static String occlusionConsistencyCheck(BlockStateRegistry registry) {
    int drift = registry.occlusionDriftStateCount();
    if (registry.entityOccludingStateCount() < 0) {
      return "实体遮挡口径：未构建（离线场景），已回落伪装口径——实体位图此时与伪装口径不同，请反馈";
    }
    if (drift == 0) {
      return "两套口径一致性：伪装口径与实体口径同源（逐状态真值），逐状态判定差异 0"
          + "；遮挡状态 实体 " + registry.entityOccludingStateCount()
          + " / 伪装 " + registry.occludingStateCount()
          + (registry.overrideHitStateCount() > 0
              ? "（覆盖表命中 " + registry.overrideHitStateCount() + " 个状态，但未改变任何逐状态判定）"
              : "");
    }
    return "两套口径一致性：发现 " + drift + " 个状态的判定不同（仅可能由 occlusion.extra-* 覆盖表造成，"
        + "覆盖表命中 " + registry.overrideHitStateCount() + " 个状态）。";
  }

  /** 取前若干个名字拼成一行（避免日志爆屏）。 */
  private static String preview(List<String> names) {
    if (names.isEmpty()) {
      return "无";
    }
    List<String> shown = new ArrayList<>(names.subList(0, Math.min(8, names.size())));
    String joined = String.join(", ", shown);
    return names.size() > shown.size() ? joined + " …" : joined;
  }

  /** 已构建的注册表；未成功构建时为 null。 */
  public BlockStateRegistry registry() {
    return registry;
  }
}
