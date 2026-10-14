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

      // 常规就绪信息降为 FINE（开服日志只保留「已启用」一行）；两处自检只在「发现问题」时打 WARNING。
      logger.fine("方块状态映射构建完成：服务端版本 " + releaseName
          + "，状态数 " + registry.getUniqueBlockStateCount()
          + "，直接格式位宽 " + registry.getMaxBitsPerBlockState()
          + "，构建耗时 " + buildMillis + " ms");
      logOcclusionWarnings(registry);
      return true;
    } catch (Throwable throwable) {
      logger.log(Level.SEVERE, "构建方块状态映射失败，反矿透模块停用", throwable);
      this.registry = null;
      return false;
    }
  }

  /**
   * 遮挡表自检的<b>异常侧</b>输出：正常就绪不打日志，只有出现「会让反矿透或实体剔除静默失效」的
   * 问题时才 WARNING，并明确点名受影响的功能。
   *
   * <p>逐状态真值回落启发式会让若干方块的遮挡结论偏离服务端判定（进而影响实体剔除的「藏 / 不藏」）；
   * 两套口径出现差异则是「同一功能两种结论」的口径漂移（伪装与实体判定不一致）。
   * 完整就绪明细（各计数与典型方块抽样）仍可在调试日志（FINE）中查看。
   */
  private void logOcclusionWarnings(BlockStateRegistry registry) {
    logger.fine(occlusionSelfCheck(registry));
    int fallback = registry.heuristicFallbackStateCount();
    if (fallback > 0) {
      logger.warning("遮挡判定：有 " + fallback + " 个方块状态未能取到逐状态真值、已回落启发式判定，"
          + "这些方块的「是否遮挡」可能与服务端不一致（影响实体剔除的隐藏/恢复与矿物伪装判定的边界）；"
          + "回落方块名抽样：" + preview(registry.heuristicFallbackNames()));
    }
    int drift = registry.occlusionDriftStateCount();
    if (registry.entityOccludingStateCount() < 0) {
      logger.warning("遮挡判定：实体遮挡口径未能构建（离线场景），已回落伪装口径——实体剔除的隐藏判定"
          + "与矿物伪装判定此时可能不一致，请带 /mikuxraynet dump 反馈");
    } else if (drift > 0) {
      logger.warning("遮挡判定：伪装口径与实体口径出现 " + drift + " 个状态的判定差异"
          + "（仅可能由 occlusion.extra-* 覆盖表造成，覆盖表命中 "
          + registry.overrideHitStateCount() + " 个状态）——实体剔除与矿物伪装对同一方块的结论不一致");
    }
  }

  /**
   * 遮挡表自检明细：输出「逐状态真值解析成功数 / 回落启发式数 / 覆盖表命中数」与典型方块抽样。
   *
   * <p>这是「反矿透是否真的会隐藏」的地基自证：若这里的 stone / deepslate 不是「遮挡」，
   * 那么 {@code shouldObfuscate} 恒为 false、所有矿物都会原样下发（不伪装）。
   * 正常就绪时降为 FINE；异常侧见 {@link #logOcclusionWarnings(BlockStateRegistry)}。
   */
  private static String occlusionSelfCheck(BlockStateRegistry registry) {
    StringBuilder message = new StringBuilder("遮挡表就绪：遮挡 ")
        .append(registry.occludingStateCount()).append('/')
        .append(registry.getUniqueBlockStateCount())
        .append("；逐状态真值解析成功 ").append(registry.truthStateCount())
        .append("；回落启发式 ").append(registry.heuristicFallbackStateCount())
        .append("；覆盖表命中 ").append(registry.overrideHitStateCount());
    for (String name : new String[] {"stone", "deepslate", "air", "water", "anvil"}) {
      int stateId = BlockStateRegistry.resolveStateId(name);
      message.append("；抽样 ").append(name).append('=')
          .append(stateId < 0 ? "未识别" : (registry.isOccluding(stateId) ? "遮挡" : "非遮挡"));
    }
    return message.toString();
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
