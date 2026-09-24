package net.mikumc.mikuxraynet.bootstrap;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.registry.BlockStateRegistry;

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

  private BlockStateRegistry registry;
  private String releaseName;

  public PacketEventsHook(Logger logger) {
    this.logger = logger;
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
      this.registry = BlockStateRegistry.build(extraOccluding, extraNonOccluding);

      logger.info("方块状态映射构建完成：服务端版本 " + releaseName
          + "，状态数 " + registry.getUniqueBlockStateCount()
          + "，直接格式位宽 " + registry.getMaxBitsPerBlockState());
      logger.info(occlusionSelfCheck(registry));
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

  /** 已构建的注册表；未成功构建时为 null。 */
  public BlockStateRegistry registry() {
    return registry;
  }

  /** PacketEvents 上报的服务端版本名，仅用于日志。 */
  public String releaseName() {
    return releaseName;
  }
}