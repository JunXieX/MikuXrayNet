package net.mikumc.mikuxraynet.bootstrap;

import org.bukkit.Bukkit;

/**
 * 前置依赖守卫：只做「是否就绪」的探测，缺失时由调用方禁用对应模块并打印可读日志。
 *
 * <p>关键约束：本类不静态引用 PacketEvents / ProtocolLib 的任何类型，改用类存在性 + 插件管理器判断，
 * 因此在缺少软依赖（ProtocolLib）时也不会触发 {@link NoClassDefFoundError} 崩服。
 */
public final class DependencyGuard {

  private static final String PACKET_EVENTS_CLASS = "com.github.retrooper.packetevents.PacketEvents";
  private static final String PACKET_EVENTS_PLUGIN = "packetevents";
  private static final String PROTOCOL_LIB_CLASS = "com.comphenix.protocol.ProtocolLibrary";
  private static final String PROTOCOL_LIB_PLUGIN = "ProtocolLib";

  private DependencyGuard() {
  }

  /** 类是否存在于当前类加载器（不初始化该类）。 */
  public static boolean classPresent(String className) {
    try {
      Class.forName(className, false, DependencyGuard.class.getClassLoader());
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** PacketEvents 是否已安装（硬前置；仅做存在性判断，是否可用由 PacketEventsHook 进一步确认）。 */
  public static boolean isPacketEventsPresent() {
    return Bukkit.getPluginManager().getPlugin(PACKET_EVENTS_PLUGIN) != null
        && classPresent(PACKET_EVENTS_CLASS);
  }

  /** ProtocolLib 是否已安装（软前置；唯一的封包拦截通道）。 */
  public static boolean isProtocolLibPresent() {
    return Bukkit.getPluginManager().getPlugin(PROTOCOL_LIB_PLUGIN) != null
        && classPresent(PROTOCOL_LIB_CLASS);
  }
}