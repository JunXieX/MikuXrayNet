package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 下载源准入：明文 http 的 jar 会在本进程里执行静态块（{@code Class.forName(..., true, loader)}），
 * 中间人换包等价于任意代码执行。只认 https（本机回环与 file 留给离线自测/自建镜像）。
 */
class ZstdDownloadSourceTest {

  @Test
  void httpsIsAccepted() {
    assertTrue(ZstdSupport.isSecureDownloadSource(null), "空值表示回落默认 https 地址");
    assertTrue(ZstdSupport.isSecureDownloadSource("  "));
    assertTrue(ZstdSupport.isSecureDownloadSource("https://repo1.maven.org/maven2"));
    assertTrue(ZstdSupport.isSecureDownloadSource("HTTPS://Mirror.Example.COM/repo"));
  }

  @Test
  void plainHttpIsRejected() {
    assertFalse(ZstdSupport.isSecureDownloadSource("http://mirror.example.com/repo"),
        "明文下载源可被替换成任意 jar 并在本进程执行，必须拒绝");
    assertFalse(ZstdSupport.isSecureDownloadSource("ftp://example.com/repo"));
  }

  @Test
  void loopbackAndFileAreAllowedForSelfHostedTesting() {
    assertTrue(ZstdSupport.isSecureDownloadSource("http://127.0.0.1:8080/repo"));
    assertTrue(ZstdSupport.isSecureDownloadSource("http://localhost:8080/repo"));
    assertTrue(ZstdSupport.isSecureDownloadSource("file:/srv/mirror"));
  }
}