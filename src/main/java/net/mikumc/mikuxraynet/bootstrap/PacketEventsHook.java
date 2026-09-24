package net.mikumc.mikuxraynet.bootstrap;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
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
   * @return true 表示映射可用；false 表示模块应降级停用
   */
  public boolean initialize() {
    try {
      PacketEventsAPI<?> api = PacketEvents.getAPI();
      if (api == null || !api.isInitialized()) {
        logger.warning("PacketEvents 尚未就绪，反矿透模块停用（请确认前置插件 packetevents 已正常加载）");
        return false;
      }

      ServerVersion version = api.getServerManager().getVersion();
      this.releaseName = version == null ? "未知" : version.getReleaseName();
      this.registry = BlockStateRegistry.build();

      logger.info("方块状态映射构建完成：服务端版本 " + releaseName
          + "，状态数 " + registry.getUniqueBlockStateCount()
          + "，直接格式位宽 " + registry.getMaxBitsPerBlockState());
      return true;
    } catch (Throwable throwable) {
      logger.log(Level.SEVERE, "构建方块状态映射失败，反矿透模块停用", throwable);
      this.registry = null;
      return false;
    }
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