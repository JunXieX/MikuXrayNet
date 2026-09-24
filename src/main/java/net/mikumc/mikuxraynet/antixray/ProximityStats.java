package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 邻近显形统计计数，供 {@code /mikuxraynet status} 读取。
 *
 * <p>字段均为无锁 {@link LongAdder}：工作线程（写入索引）、主线程 / 区域线程（巡检发包）
 * 与封包线程（注销）都会累加，读取时用 {@link #snapshot()} 取得一致的可读快照。
 */
public final class ProximityStats {

  /** 成功下发的显形包数。 */
  public final LongAdder revealsSent = new LongAdder();

  /** 被跳过的候选坐标数（区块未加载、读取失败、发包失败等）。 */
  public final LongAdder revealsSkipped = new LongAdder();

  /** 因服务端自行下发方块变更而从索引注销的坐标数。 */
  public final LongAdder unregistered = new LongAdder();

  /** 生成中文可读快照（顺序稳定，便于命令输出）。 */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("显形发送", revealsSent.sum());
    map.put("显形跳过", revealsSkipped.sum());
    map.put("变更注销", unregistered.sum());
    return map;
  }
}