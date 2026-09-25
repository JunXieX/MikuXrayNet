package net.mikumc.mikuxraynet.cache;

import java.util.concurrent.atomic.LongAdder;

/**
 * 磁盘缓存计数，供 {@code /mikuxraynet status} 与诊断转储读取。
 *
 * <p>全部为无锁 {@link LongAdder}：封包工作线程（读写命中）与磁盘线程（落盘、维护）都会累加。
 * 「持有量」类指标（条目数、打开的区域文件数、占用字节）是即时值的近似快照，由
 * {@link DiskCacheStore} 直接提供，不放在这里。
 */
public final class DiskCacheStats {

  /** 命中数。 */
  public final LongAdder hits = new LongAdder();

  /** 未命中数（含超时降级与损坏回退）。 */
  public final LongAdder misses = new LongAdder();

  /** 因过期而被惰性清理的条目数。 */
  public final LongAdder expiredRemoved = new LongAdder();

  /** 因总条目上限或负载过大被拒绝的写入数。 */
  public final LongAdder rejectedByCapacity = new LongAdder();

  /** 因单文件大小上限被拒绝的写入数。 */
  public final LongAdder rejectedBySize = new LongAdder();

  /** 读写异常次数（异常只会降级，不影响封包链路）。 */
  public final LongAdder errors = new LongAdder();
}