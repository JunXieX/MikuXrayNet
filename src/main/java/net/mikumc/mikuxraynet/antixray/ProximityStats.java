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

  /** 被视锥剔除的候选坐标数（不在玩家视野锥内）。 */
  public final LongAdder revealsFrustumCulled = new LongAdder();

  /** 因射线被遮挡而跳过显形的候选坐标数（等玩家靠近后再显形）。 */
  public final LongAdder revealsRayCulled = new LongAdder();

  /** 因工作队列已满而退化为「主线程直接做视锥剔除」的次数（纯计算不再占用工作线程）。 */
  public final LongAdder revealsQueuedSkipped = new LongAdder();

  /** 生成中文可读快照（顺序稳定，便于命令输出）。 */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("显形发送", revealsSent.sum());
    map.put("显形跳过", revealsSkipped.sum());
    map.put("变更注销", unregistered.sum());
    map.put("视锥剔除", revealsFrustumCulled.sum());
    map.put("射线剔除", revealsRayCulled.sum());
    map.put("降级次数", revealsQueuedSkipped.sum());
    return map;
  }
}