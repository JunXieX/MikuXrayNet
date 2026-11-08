package net.mikumc.mikuxraynet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 磁盘缓存「按插件版本清理」的行为回归：升级清空、同版本保留、首次记录。
 */
class AntiXrayRuntimeCacheCleanupTest {

  private static final Logger LOGGER = Logger.getLogger("MikuXrayNetTest");

  @Test
  void firstRunRecordsVersionAndClearsLeftovers(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("r.0.0.mxrc"), "stale");
    Files.createDirectories(dir.resolve("nested"));
    Files.writeString(dir.resolve("nested").resolve("old.bin"), "stale");

    AntiXrayRuntime.clearCacheOnVersionChange(dir, "1.30.1", LOGGER);

    assertEquals("1.30.1", Files.readString(dir.resolve("plugin-version.txt")).trim(),
        "首次运行必须写下版本标记");
    assertTrue(Files.notExists(dir.resolve("r.0.0.mxrc")), "首次运行必须清掉无主的旧文件");
    assertTrue(Files.notExists(dir.resolve("nested")), "子目录也要清");
  }

  @Test
  void sameVersionKeepsCache(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("plugin-version.txt"), "1.30.1\n");
    Files.writeString(dir.resolve("r.0.0.mxrc"), "keep");

    AntiXrayRuntime.clearCacheOnVersionChange(dir, "1.30.1", LOGGER);

    assertEquals("keep", Files.readString(dir.resolve("r.0.0.mxrc")), "同版本重启不得清缓存");
  }

  @Test
  void versionChangeClearsCacheButKeepsMarker(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("plugin-version.txt"), "1.30.0\n");
    Files.writeString(dir.resolve("r.0.0.mxrc"), "stale");

    AntiXrayRuntime.clearCacheOnVersionChange(dir, "1.30.1", LOGGER);

    assertTrue(Files.notExists(dir.resolve("r.0.0.mxrc")), "版本变化必须清掉旧缓存");
    assertEquals("1.30.1", Files.readString(dir.resolve("plugin-version.txt")).trim(),
        "标记必须更新为新版本");
  }

  @Test
  void blankVersionSkipsCleanup(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("r.0.0.mxrc"), "keep");

    AntiXrayRuntime.clearCacheOnVersionChange(dir, "", LOGGER);

    assertEquals("keep", Files.readString(dir.resolve("r.0.0.mxrc")),
        "读不到版本号时绝不能删缓存");
  }
}
