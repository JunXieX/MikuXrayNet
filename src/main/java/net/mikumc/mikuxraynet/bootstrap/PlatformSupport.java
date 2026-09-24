package net.mikumc.mikuxraynet.bootstrap;

import java.util.Locale;
import org.bukkit.Bukkit;

/**
 * 服务端平台探测（Folia 兼容判断）。
 *
 * <p>判断方式：服务端名包含 {@code Folia}，或存在 Folia 专有的区域化线程类。
 * 不使用硬编码的服务端版本号，也不依赖 NMS。
 */
public final class PlatformSupport {

  private static final String[] FOLIA_CLASSES = {
      "io.papermc.paper.threadedregions.RegionizedServer",
      "io.papermc.paper.threadedregions.RegionizedServerInitEvent"};

  private static final boolean FOLIA = detectFolia();

  private PlatformSupport() {
  }

  /** 是否为 Folia（区域化多线程）服务端。 */
  public static boolean isFolia() {
    return FOLIA;
  }

  /** 平台描述，仅用于日志。 */
  public static String platformDescription() {
    return Bukkit.getName() + " " + Bukkit.getMinecraftVersion() + (FOLIA ? "（Folia 区域化线程）" : "");
  }

  private static boolean detectFolia() {
    String serverName = Bukkit.getName();
    if (serverName != null && serverName.toLowerCase(Locale.ROOT).contains("folia")) {
      return true;
    }
    for (String className : FOLIA_CLASSES) {
      if (DependencyGuard.classPresent(className)) {
        return true;
      }
    }
    return false;
  }
}