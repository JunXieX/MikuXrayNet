package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.AntiXrayConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 区域文件「跨进程锁」语义的回归测试。
 *
 * <p>新语义：<b>拿不到锁即停用该文件的读写</b>（读视为未命中、写跳过），而不是旧行为的「只 WARN 后照常
 * 读写」；但「同 JVM 自持」必须按<b>可用</b>处理，绝不能把自己人的缓存永久停用。
 *
 * <p><b>为何用注入而非真实锁</b>：Windows 的 {@code FileLock} 是强制锁——同一 JVM 的另一句柄持锁时，
 * 新句柄的读写会被操作系统直接拒绝（实测：「另一个程序已锁定文件的一部分，进程无法访问」），
 * 因此无法在进程内用真实锁复现「自持/被占用」并完成读写。注入只替换「判定」（
 * {@link RegionFile#lockOutcomeOverrideForTest}），读写降级与一次性提示仍是生产代码。
 */
class RegionFileLockTest {

  private static BufferedLinearV3Format.Entry entry(long hash) {
    byte[] payload = new byte[64];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i + hash);
    }
    return new BufferedLinearV3Format.Entry(0L, System.currentTimeMillis(), (int) hash, payload);
  }

  /** 锁被其它进程占用：读一律未命中、写一律跳过、且全程不抛异常、不落盘、不删除文件。 */
  @Test
  void occupiedLockDisablesReadsAndWritesWithoutThrowing(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.0.0.b_linear");
    RegionFile.lockOutcomeOverrideForTest = false; // 按「被其它进程占用」处理
    RegionFile region = RegionFile.open(file, 2);
    try {
      int index = BufferedLinearV3Format.chunkIndex(0, 0);

      assertNull(region.get(index), "锁被占用时读必须视为未命中（返回 null，交给上层回退重算）");
      assertFalse(assertDoesNotThrow(() -> region.put(index, entry(1))),
          "锁被占用时写必须被跳过（返回 false）");
      assertFalse(region.isDirty(), "被跳过的写不得留下脏标记");
      assertFalse(assertDoesNotThrow(region::flushDirty), "锁被占用时不落盘、不抛异常");
      int dropped = assertDoesNotThrow(() -> region.compact((chunk, value) -> true));
      assertEquals(0, dropped, "锁被占用时压缩回收必须跳过");
      assertEquals(0, region.countEntriesOnDisk(), "锁被占用时不读取对方文件的内容");
      assertFalse(region.isEmptyFile(), "锁被占用时绝不能因「看似为空」而被删除");

      assertDoesNotThrow(region::close);
      assertTrue(Files.exists(file), "锁被占用时不得删除对方的缓存文件");
      assertEquals(0L, Files.size(file), "锁被占用时不得向对方的文件写入任何字节（连文件头也不行）");
    } finally {
      RegionFile.lockOutcomeOverrideForTest = null;
    }
  }

  /** 同 JVM 自持：必须按可用处理，读写照常工作（否则会把本进程自己的磁盘缓存永久停用）。 */
  @Test
  void sameJvmHeldLockIsTreatedAsAvailable(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.0.0.b_linear");
    RegionFile.lockOutcomeOverrideForTest = true; // 按「同 JVM 自持」处理
    try {
      RegionFile region = RegionFile.open(file, 2);
      int index = BufferedLinearV3Format.chunkIndex(3, 5);

      region.put(index, entry(7));
      assertTrue(region.isDirty(), "自持下写路径必须是开启的（脏标记置位）");
      assertTrue(region.flushDirty(), "自持下必须能正常落盘");
      assertNotNull(region.get(index), "自持下写入后必须能读回（自持不冲突）");
      assertEquals(1, region.countEntriesOnDisk(), "自持下应能统计到磁盘条目");
      region.close();
    } finally {
      RegionFile.lockOutcomeOverrideForTest = null;
    }
  }

  /**
   * 真实平台行为 + 判定映射：同一 JVM 内第二个句柄 {@code tryLock()} 必抛
   * {@link OverlappingFileLockException}，而 {@link RegionFile#isSelfHeld} 必须把它判为「自持」
   * （从而按可用处理），其它异常则判为「被占用」（停用）。
   */
  @Test
  void realSameJvmLockMapsToSelfHeld(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("r.0.0.b_linear");
    Files.write(file, new byte[16]);

    try (FileChannel holder = FileChannel.open(file,
        StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      FileLock held = holder.tryLock();
      assertNotNull(held, "本用例必须成功持有文件锁（模拟同 JVM 旧句柄）");
      try (FileChannel other = FileChannel.open(file,
          StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        assertThrows(OverlappingFileLockException.class, other::tryLock,
            "同 JVM 内第二句柄加锁必须抛 OverlappingFileLockException（自持可识别）");
      }
      held.release();
    }

    assertTrue(RegionFile.isSelfHeld(new OverlappingFileLockException()),
        "OverlappingFileLockException 必须判为「同 JVM 自持」→ 按可用处理");
    assertFalse(RegionFile.isSelfHeld(new IOException("文件系统不支持锁")),
        "其它异常必须判为「被占用」→ 停用读写（fail-open）");
  }

  /**
   * 集成回归：文件被占用时，{@code DiskCacheStore} 的写入被跳过——既不下盘，也不把「写了但没落盘」
   * 的条目计进条目额度（否则占用场景会白白耗尽 max-entries，拖垮整个缓存）。
   */
  @Test
  void occupiedFileDoesNotConsumeEntryBudget(@TempDir Path dir) throws Exception {
    AntiXrayConfig.DiskCache config = new AntiXrayConfig.DiskCache(true, 1024, 16, 600, 2, 600,
        3600, 4, 256, false, AntiXrayConfig.DEFAULT_ZSTD_DOWNLOAD_URL, 10, "");
    RegionFile.lockOutcomeOverrideForTest = false; // 所有区域文件都按「被其它进程占用」处理
    try (DiskCacheStore store = new DiskCacheStore(dir, config,
        Logger.getLogger("MikuXrayNetTest"), 10_000L)) {
      for (int chunkX = 0; chunkX < 50; chunkX++) {
        store.put("world", chunkX, 0, 1, entry(chunkX).payload());
      }
      store.flush();

      assertEquals(0, store.entries(), "被占用文件的写入被跳过，不得消耗条目额度");
      assertNull(store.get("world", 0, 0, 1), "被占用文件的读必须视为未命中");
    } finally {
      RegionFile.lockOutcomeOverrideForTest = null;
    }
  }
}