package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 反矿透区块改写统计计数，供 {@code /mikuxraynet status} 与诊断转储读取。
 *
 * <p>字段均为无锁 {@link LongAdder}：网络线程（建任务、超时放行）与工作线程（改写）都会累加；
 * 读取方（诊断面板）逐字段 {@code sum()} 直读，无需一致快照。计数器只做累加，不参与任何判定，因而不引入高频开销。
 */
public final class RewriteStats {

  /** 真正产生字节改动的区块数。 */
  public final LongAdder chunksRewritten = new LongAdder();

  /**
   * 已处理但<b>未改写</b>的区块总数（原包放行）：无目标方块 / 无改动 / 解析失败等。
   *
   * <p><b>口径范围</b>：{@code chunksRewritten + chunksSkipped} 只覆盖「走完解码/改写流程」的区块。
   * 缓存命中（不解码，见 {@code ProtocolLibAsyncListener#rewriteOnWorker}）、处理超时
   * （{@link #chunksTimedOut}）、队列满（{@link #chunksSkippedQueueFull}）各自另有计数器，都不在这个和里
   * ——稳态服务器上绝大多数区块包走的是缓存命中，因此这个和远小于「已处理区块总数」。
   * 注意 {@link #chunksFailed} 是它的<b>子集</b>（解析失败同样属于「未改写」，失败区块会同时计入
   * 两个计数），因此二者<b>并非互斥</b>，绝不可相加——要得到「本来就无需改写」的数量应取
   * {@code chunksSkipped − chunksFailed}。
   */
  public final LongAdder chunksSkipped = new LongAdder();

  /**
   * 解码/重编码抛异常、fail-open 放行原包的区块数；它是 {@link #chunksSkipped} 的<b>子集</b>。
   *
   * <p>{@link #chunksSkipped} 把两种情形合在一起：「本来就没有可伪装的方块」与「本该伪装却失败了」；
   * 本计数把后者单独拎出来。失败区块确实未改写，故也计入 {@code chunksSkipped}（{@code chunksFailed ≤
   * chunksSkipped}，两者不互斥）。若这个数持续增长，说明区块二进制布局与预期不符（版本差异 / 第三方插件改写）。
   */
  public final LongAdder chunksFailed = new LongAdder();

  /** 处理超时、由看门狗放行原包的次数。 */
  public final LongAdder chunksTimedOut = new LongAdder();

  /**
   * 因改写队列无容量而<b>直接放行原包</b>（本区块完全未伪装）的区块数。
   *
   * <p>覆盖三种入口：提交前的 {@code hasCapacity()} 预判、入队被拒（{@code RejectedExecutionException}）、
   * 以及工作池已关闭（关服窗口内提交必被拒）。三者都是「该区块以真实矿物下发、对透视端完全可见」，
   * 口径一致故合用一个计数器。
   *
   * <p><b>为什么必须单列</b>：这条链路会让防护静默失效——改写数看起来正常、但玩家能透视。持续增长说明
   * 改写吞吐已被打满（{@code advanced.queue-capacity} 耗尽），应降低负载或评估加大队列/线程；
   * 若只在关服/停用的瞬间冒出个位数，属正常收尾，不必调整容量。
   */
  public final LongAdder chunksSkippedQueueFull = new LongAdder();

  /** 改写字节已算出但未能写回封包的次数（字段直写未生效，写回后回读 self-check 失败）。 */
  public final LongAdder writeBackFailures = new LongAdder();

  /**
   * 因写回未确认生效（{@link #writeBackFailures}）而<b>跳过伪装索引登记</b>的区块次数。
   *
   * <p><b>为什么单列一个观测点</b>：写回失败时客户端拿到的仍是原始（未伪装）字节，本区块没有隐藏任何矿物，
   * 因此不应把坐标登记进伪装索引（否则邻近显形会为「本就没被伪装的坐标」发冗余包）。这个计数即
   * 「本该登记、因写回失败被跳过」的次数；它应与 {@link #writeBackFailures} 同步增长，单独可观测。
   */
  public final LongAdder indexSkippedOnWriteBackFailure = new LongAdder();

  /**
   * 内存改写缓存「命中了条目、但原始字节指纹已变」的次数（命中计数 +1 却用不上）。
   *
   * <p><b>为什么必须有这个计数器</b>：{@code RewriteCache#hitCount()} 只统计「键命中且未过期」，
   * 而调用方随后还要比对负载里的原始字节指纹——不符就意味着该区块内容真的变了，本次必须重新改写
   * （并继续查磁盘）。这类「命中但作废」被算进命中数会让命中率虚高，而它恰恰是磁盘查询的一部分来源：
   * {@code 磁盘查询数 = 内存未命中 + 本计数}（仅就「内容指纹不符」这一类成立：缺位图的那一类直接改写、
   * 不查磁盘，因此升级后那一轮该等式会偏高），缺了这一项，两边数字对不上时无从归因。
   *
   * <p>另有一类「键命中但用不上」也计入本计数：条目命中了，但负载里没带遮挡位图（旧格式条目 / 当时该功能
   * 关闭），而本次启用了异步遮挡——该区块会长期「已伪装却无位图」，必须当未命中重写一次。这一类的批量出现
   * 只会发生在「刚升级到带位图负载的版本」那一轮，与区块内容是否真的变化无关。
   */
  public final LongAdder cacheContentRejected = new LongAdder();

  /**
   * {@link #cacheContentRejected} 的子项：键命中但<b>源字节指纹已变</b>（区块内容真的变了）。
   *
   * <p>属正常失效——该条目会被本次重写覆盖。与 {@link #cacheRejectedOcclusionMissing} 互斥，两者之和恒等于
   * {@code cacheContentRejected} 的增量（同一次改写里由 {@code RewritePipeline} 的派生值给出，不重算）。
   */
  public final LongAdder cacheRejectedSourceChanged = new LongAdder();

  /**
   * {@link #cacheContentRejected} 的子项：键命中但<b>缺遮挡位图</b>、被迫按未命中重写。
   *
   * <p>健康状态下应≈0（只在升级到带位图负载的那一轮出现）；持续增长说明遮挡建表在这批区块上失败——
   * 缺位图时显形侧按「缺数据 = 遮挡」判定，玩家挖到的矿会一直显示假方块。
   */
  public final LongAdder cacheRejectedOcclusionMissing = new LongAdder();

  /** 已在区块出站后定向补发的方块数。 */
  public final LongAdder replayCells = new LongAdder();

  /**
   * 最终覆盖缺口合计 = {@link #replayUnobserved} + {@link #replayCoverageLost}。
   * 淘汰与区域异常现在会进入整列刷新或重排，属于兜底触发次数，不再计入最终缺口。
   *
   * <p><b>为什么保留这个合计</b>：既有面板与 dump 读的就是它（{@code Diagnostics}），换名字会让线上口径
   * 断档。只有确认不会再由本版修复的缺口才计入；仍在重排或已经刷新收敛的ticket不计入。</p>
   *
   * <p>诊断同时列出淘汰/区域异常的兜底触发次数；它们是过程指标，不与最终缺口求和。</p>
   */
  public final LongAdder replaySkipped = new LongAdder();

  /**
   * 写出确认<b>成功执行</b>的次数：该区块包的「已写出」回调确实到达，并进入补发判定。
   *
   * <p>它是「交付链路是通的」的唯一正面证据；与 {@link #replaySkipped} 不相交（本项是成功，不是放弃）。
   * 注意确认的契约是「写出动作已递交到本连接的 Netty 管线」，<b>不是</b> ChannelPromise 成功、
   * 更不是客户端 ACK（见 {@code ChunkDeliverySignal} 的类注释）。
   */
  public final LongAdder replayDelivered = new LongAdder();

  /**
   * 观测缺失：同步观察阶段没能把交付信号挂到封包上（登记抛出 / marker 异常）。
   *
   * <p>这类区块包照常写出（原包不受影响），但我们永远不会知道它何时写出，因此在途更新只能等过期。
   * 持续增长说明观察链路本身在报错（看配对的一次性 WARN / 分类限流日志）。
   */
  public final LongAdder replayUnobserved = new LongAdder();

  /**
   * 淘汰兜底：交付信号始终没有到达，ticket 被容量/超时淘汰并触发整列刷新。
   *
   * <p>典型成因是封包没被真正写出（连接已断、被上游取消、ProtocolLib 发送队列丢弃）——此时补发本就不该
   * 发生，属正常收账；只有它在稳定运行时持续增长，才需要怀疑交付信号本身没被派发。
   */
  public final LongAdder replayEvicted = new LongAdder();

  /**
   * 区域异常兜底：就绪等待耗尽、调度器拒收或补发执行异常，随后保留票据并重排/整列刷新。
   *
   * <p>与 {@link #replayEvicted} 的分界：这里确认通道是通的、只是没补出去（调度/线程侧问题），
   * 而淘汰是确认通道根本没响（协议/连接侧问题）。
   */
  public final LongAdder replayRegionFailures = new LongAdder();

  /**
   * 覆盖丢失：写出确认到达时所属会话已经失效；记录截断已由整列刷新收敛，不计入本项。
   *
   * <p>与 {@link #replayEvicted} 的分界：这里的确认是到的（我们知道包写出来了），丢的是内容；
   * 前者丢的是「知道包写出来了」这件事本身。
   */
  public final LongAdder replayCoverageLost = new LongAdder();

  /**
   * 磁盘缓存「读到了负载但被拒」的次数：负载里的原始区块字节指纹与本次要改写的字节不符（或信封损坏）。
   *
   * <p><b>为什么必须有这个计数器</b>：这是磁盘缓存唯一「命中计数 +1 但实际用不上」的出口——
   * 命中率会显示正常，可玩家仍在重写。真机排查时若没有它，「命中率高但没生效」与
   * 「命中率低」会被混在一起，无法定位到是<b>内容指纹</b>在拦还是<b>配置指纹</b>在拦。
   */
  public final LongAdder diskPayloadRejected = new LongAdder();

  /**
   * {@link #diskPayloadRejected} 的子项：负载记录的原始字节指纹与本次要改写的字节不符。
   *
   * <p>属<b>缓存内容已过期</b>的正常失效：照常重写并覆盖该条。与
   * {@link #diskPayloadRejectedEnvelopeFault} 互斥，两者之和恒等于 {@code diskPayloadRejected} 的增量。
   */
  public final LongAdder diskPayloadRejectedSourceHash = new LongAdder();

  /**
   * {@link #diskPayloadRejected} 的子项：信封/数据故障（负载被截断、坐标个数非法、遮挡长度非法、
   * 声明了坐标却没有区块字节、解析异常）。
   *
   * <p>与「内容过期」分开看才能算出真实的损坏率：它持续增长意味着磁盘缓存写入被截断或容器格式与代码
   * 不一致，命中率会被这类条目白白吃掉。
   */
  public final LongAdder diskPayloadRejectedEnvelopeFault = new LongAdder();

  /**
   * 遮挡建表（{@code captureOcclusion}）失败次数：该区块的遮挡位图没有生成出来。
   *
   * <p><b>为什么必须单列</b>：位图缺失时显形侧按「缺数据 = 遮挡」判定，玩家挖到的矿会一直显示假方块；
   * 同时失败结果不允许进缓存（见 {@code resultCacheable}），于是该区块每次重发都要重算一遍完整改写。
   * 这条链路此前完全静默——计数持续增长即说明建表遇到异常输入（注册表/负载编码不匹配等），
   * 必须能从面板与日志看出来，否则只能看到「玩家说矿挖不掉」而无从归因。
   */
  public final LongAdder occlusionCaptureFailures = new LongAdder();

  /**
   * 整列位图发布时因「该 section 期间被方块补丁写过」而跳过覆盖的段数。
   *
   * <p><b>为什么需要它</b>：建表用的是解码时刻的几何，而方块补丁会把更新后的状态写进活表；
   * 若无条件覆盖，落在「解码 → 发布」窗口内的补丁会被吞掉（该显示却多藏）。跳过的段保留含补丁的活位图，
   * 是正确性要求，但持续偏高说明建表期间变更很频繁，可据此评估建表耗时/发布窗口是否值得优化。
   */
  public final LongAdder occlusionPublishSkipped = new LongAdder();

  /**
   * 累计被替换（伪装）的方块个数——即「实际匹配到目标矿并完成替换」的方块总数。
   *
   * <p><b>为什么必须有这个计数器</b>：原先只有「区块数」维度的计数（改写/跳过），
   * 「改写 670、跳过 0」既可能是「每个区块都替换了几百个方块」，也可能是
   * 「解码出的状态 id 与目标 id 不匹配、一个方块都没替换」，光看区块数无法区分。
   * 这个值 &gt; 0 即证明「目标匹配 + 遮挡判定 + 替换」确实跑通了，剩下的问题只可能在写回侧。
   */
  public final LongAdder blocksReplaced = new LongAdder();

  /**
   * 改写区块的「原始字节」累计（P0-1 字节口径统计）。
   *
   * <p>只统计真正发生改写的区块（未改动/失败放行的区块不计入），否则恒等的原样字节会稀释
   * 「省了多少」的比例。与之配对的 {@link #bytesOutput} 同口径。
   */
  public final LongAdder bytesOriginal = new LongAdder();

  /** 改写区块的「输出字节」累计（与 {@link #bytesOriginal} 同口径，P0-1）。 */
  public final LongAdder bytesOutput = new LongAdder();

  /** 节省字节 = 原始 − 输出（P0-1；可能为负——升位/扩容时输出更大，正负都如实累计）。 */
  public final LongAdder bytesSaved = new LongAdder();

  /** 位宽直方图的槽位数（bitsPerBlock 0..15；>15 的理论值并入末槽，实际间接调色板只到 8）。 */
  private static final int BITS_SLOTS = 16;

  /**
   * 改写 section 的 bitsPerBlock 直方图（P0-1 诊断，下标 = 位宽）。
   *
   * <p>供 dump 观察「封顶/裁剪/降位」的实际效果：开启 width-budget 后应看到高位宽槽不再增长、
   * 低位宽槽增多。只在改写发生时累加，读取方（dump）用 {@link #paletteBitsSummary()} 生成摘要。
   */
  private final LongAdder[] paletteBits = new LongAdder[BITS_SLOTS];

  {
    for (int i = 0; i < BITS_SLOTS; i++) {
      paletteBits[i] = new LongAdder();
    }
  }

  /** 记录一个改写后 section 的位宽（越界值并入末槽，防御异常输入）。 */
  public void recordPaletteBits(int bits) {
    paletteBits[Math.min(Math.max(bits, 0), BITS_SLOTS - 1)].increment();
  }

  /** 读取某位宽槽位的累计计数。测试专用豁免：生产诊断走 {@link #paletteBitsSummary()}，
   * 本方法当前仅单测在用，保留以免破坏测试。 */
  public long paletteBitsAt(int bits) {
    return paletteBits[Math.min(Math.max(bits, 0), BITS_SLOTS - 1)].sum();
  }

  /** 位宽直方图的中文摘要（供 dump）：仅列出有采样的位宽槽；无采样返回「无采样」。 */
  public String paletteBitsSummary() {
    StringBuilder sb = new StringBuilder();
    for (int bits = 0; bits < BITS_SLOTS; bits++) {
      long count = paletteBits[bits].sum();
      if (count > 0) {
        if (sb.length() > 0) {
          sb.append(" ｜ ");
        }
        sb.append(bits).append("位×").append(count);
      }
    }
    return sb.length() == 0 ? "无采样" : sb.toString();
  }

  /**
   * 生成中文可读快照（顺序稳定，便于命令输出）。
   *
   * <p>测试专用豁免：生产诊断直接逐字段读取（见 {@code Diagnostics}），
   * 本方法当前仅单测在用，保留以免破坏测试。
   */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("改写", chunksRewritten.sum());
    map.put("替换方块", blocksReplaced.sum());
    map.put("原始字节", bytesOriginal.sum());
    map.put("输出字节", bytesOutput.sum());
    map.put("节省字节", bytesSaved.sum());
    map.put("跳过", chunksSkipped.sum());
    map.put("失败", chunksFailed.sum());
    map.put("写回失败", writeBackFailures.sum());
    map.put("写回失败跳过登记", indexSkippedOnWriteBackFailure.sum());
    map.put("超时放行", chunksTimedOut.sum());
    return map;
  }
}