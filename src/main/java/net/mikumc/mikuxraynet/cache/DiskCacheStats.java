package net.mikumc.mikuxraynet.cache;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 磁盘缓存计数，供 {@code /mikuxraynet status} 与诊断转储读取。
 *
 * <p>全部为无锁 {@link LongAdder}：封包工作线程（读写命中）与磁盘线程（落盘、维护）都会累加。
 * 「持有量」类指标（条目数、打开的区域文件数、占用字节）是即时值的近似快照，由
 * {@link DiskCacheStore} 直接提供，不放在这里。
 */
public final class DiskCacheStats {

  /** 读请求数（含命中与未命中）。 */
  public final LongAdder requests = new LongAdder();

  /** 命中数。 */
  public final LongAdder hits = new LongAdder();

  /** 未命中数（含超时降级与损坏回退）。 */
  public final LongAdder misses = new LongAdder();

  /** 写入数（已接受并进入磁盘线程的条目）。 */
  public final LongAdder puts = new LongAdder();

  /** 因过期而被惰性清理的条目数。 */
  public final LongAdder expiredRemoved = new LongAdder();

  /** 因总条目上限或负载过大被拒绝的写入数。 */
  public final LongAdder rejectedByCapacity = new LongAdder();

  /** 因单文件大小上限被拒绝的写入数。 */
  public final LongAdder rejectedBySize = new LongAdder();

  /** 因磁盘线程积压（队列已满）被丢弃的操作数。 */
  public final LongAdder droppedByBacklog = new LongAdder();

  /** 观测到的方块变更次数（用于推导区块代次）。 */
  public final LongAdder generationBumps = new LongAdder();

  /** 压缩回收（compact）次数。 */
  public final LongAdder compactions = new LongAdder();

  /** 读写异常次数（异常只会降级，不影响封包链路）。 */
  public final LongAdder errors = new LongAdder();

  /** 中文可读快照（顺序稳定，便于命令输出）。 */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("读请求", requests.sum());
    map.put("命中", hits.sum());
    map.put("未命中", misses.sum());
    map.put("写入", puts.sum());
    map.put("过期清理", expiredRemoved.sum());
    map.put("容量拒绝", rejectedByCapacity.sum());
    map.put("大小拒绝", rejectedBySize.sum());
    map.put("积压丢弃", droppedByBacklog.sum());
    map.put("代次递增", generationBumps.sum());
    map.put("压缩回收", compactions.sum());
    map.put("异常降级", errors.sum());
    return map;
  }

  /** 命中率文本；无采样时为 0.0%。 */
  public String hitRateText() {
    long total = hits.sum() + misses.sum();
    if (total <= 0L) {
      return "0.0%";
    }
    return String.format(Locale.ROOT, "%.1f%%", hits.sum() * 100.0D / total);
  }
}