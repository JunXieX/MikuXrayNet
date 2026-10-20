package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code disk-cache.zstd-sha256} 对 <b>lib 目录里已存在的 jar</b> 同样生效。
 *
 * <p>回归动机：此前只有「本次新下载」的文件会比对期望摘要，于是「上次以空哈希自动下载、之后才把哈希
 * 填进配置」的 jar 永远不会被校验——配置看起来是开着的，实际形同虚设；被替换/损坏的本地 jar 也会被
 * 当作「来源=本地已下载」直接加载。
 */
class ZstdSupportLocalHashTest {

  private static final Logger LOGGER = Logger.getLogger("MikuXrayNetTest");

  @Test
  void configuredHashMustMatchTheExistingLocalJar(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("zstd-jni-1.5.7-15.jar");
    Files.write(jar, new byte[] {1, 2, 3, 4, 5});
    String actual = ZstdSupport.sha256Hex(jar);

    assertTrue(ZstdSupport.matchesExpectedSha256(jar, actual, LOGGER),
        "摘要相符必须放行（开启校验不得误伤好文件）");
    assertTrue(ZstdSupport.matchesExpectedSha256(jar, actual.toUpperCase(Locale.ROOT), LOGGER),
        "摘要比较必须大小写不敏感（配置里常被复制成大写）");
    assertTrue(ZstdSupport.matchesExpectedSha256(jar, "  " + actual + "  ", LOGGER),
        "两侧空白必须被容忍");
    assertFalse(ZstdSupport.matchesExpectedSha256(jar, "deadbeef", LOGGER),
        "摘要不符必须拒绝加载该本地文件（交由后续下载覆盖 / 回退 Deflater）");
  }

  @Test
  void noConfiguredHashMeansNoVerification(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("zstd-jni-1.5.7-15.jar");
    Files.write(jar, new byte[] {9});
    assertTrue(ZstdSupport.matchesExpectedSha256(jar, null, LOGGER), "未配置哈希（null）时不校验");
    assertTrue(ZstdSupport.matchesExpectedSha256(jar, "", LOGGER), "未配置哈希（空串）时不校验");
    assertTrue(ZstdSupport.matchesExpectedSha256(jar, "   ", LOGGER), "未配置哈希（空白）时不校验");
  }

  @Test
  void missingCandidateIsNotReportedAsMismatch(@TempDir Path dir) {
    Path absent = dir.resolve("not-downloaded-yet.jar");
    assertFalse(Files.exists(absent), "前置：该候选文件确实不存在");
    assertTrue(ZstdSupport.matchesExpectedSha256(absent, "deadbeef", LOGGER),
        "文件还不存在时必须放行（交给 loadFromJar 静默跳过），不能报成「摘要不符」误导服主");
  }
}
