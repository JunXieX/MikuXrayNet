package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 反矿透区块改写统计计数，供 {@code /mikuxraynet status} 与诊断转储读取。
 *
 * <p>字段均为无锁 {@link LongAdder}：网络线程（建任务、超时放行）与工作线程（改写）都会累加，
 * 读取时用 {@link #snapshot()} 取得一致的可读快照。计数器只做累加，不参与任何判定，因而不引入高频开销。
 */
public final class RewriteStats {

  /** 真正产生字节改动的区块数。 */
  public final LongAdder chunksRewritten = new LongAdder();

  /** 已处理但无需改写的区块数（无目标方块、无改动、解析失败等，原包放行）。 */
  public final LongAdder chunksSkipped = new LongAdder();

  /**
   * 解码/重编码抛异常、fail-open 放行原包的区块数。
   *
   * <p>与 {@link #chunksSkipped} 分开计数：后者是「本来就没有可伪装的方块」，前者是「本该伪装却失败了」。
   * 若这个数持续增长，说明区块二进制布局与预期不符（版本差异 / 第三方插件改写）。
   */
  public final LongAdder chunksFailed = new LongAdder();

  /** 处理超时、由看门狗放行原包的次数。 */
  public final LongAdder chunksTimedOut = new LongAdder();

  /** 改写字节已算出但未能写回封包的次数（setBuffer 未生效，self-check 失败）。 */
  public final LongAdder writeBackFailures = new LongAdder();

  /** 生成中文可读快照（顺序稳定，便于命令输出）。 */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("改写", chunksRewritten.sum());
    map.put("跳过", chunksSkipped.sum());
    map.put("失败", chunksFailed.sum());
    map.put("写回失败", writeBackFailures.sum());
    map.put("超时放行", chunksTimedOut.sum());
    return map;
  }
}