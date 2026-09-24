package net.mikumc.mikuxraynet.bandwidth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 带宽模块统计计数，供后续 {@code /mikuxraynet status} 读取。
 *
 * <p>全部字段为无锁 {@link LongAdder}：网络线程、工作线程、主线程都可以安全累加，
 * 读取时通过 {@link #snapshot()} 取得一致的可读快照。
 */
public final class ThrottleStats {

  /** 零位移实体包：取消数 / 放行数。 */
  public final LongAdder entityPacketsCancelled = new LongAdder();
  public final LongAdder entityPacketsPassed = new LongAdder();

  /** 方块变更：合并批次 / 被合并进合并包的原包数 / 原样放行数。 */
  public final LongAdder blockMergeBatches = new LongAdder();
  public final LongAdder blockChangesMerged = new LongAdder();
  public final LongAdder blockChangesPassed = new LongAdder();

  /** 实体剔除：隐藏次数 / 恢复次数。 */
  public final LongAdder entitiesHidden = new LongAdder();
  public final LongAdder entitiesShown = new LongAdder();

  /**
   * 实体剔除「周期复检」口径：复检提交数 / 复检致新隐藏数 / 复检致恢复数。
   *
   * <p><b>为什么单列</b>：{@code entitiesHidden} 混合了「入场即被遮挡」与「周期复检发现新遮挡」两条来源，
   * 只看总数无法判断「先可见后被遮挡」这类实体是否真的被收敛到隐藏。本组计数正是本次缺陷的可观测指标：
   * 复检提交数持续增长（轮转在推进）、复检致隐藏数随之 +1，即证明轮转分片生效。
   */
  public final LongAdder recheckSubmitted = new LongAdder();
  public final LongAdder recheckHidden = new LongAdder();
  public final LongAdder recheckShown = new LongAdder();

  /** AFK 降级：进入 AFK 次数 / 丢弃的低价值包数。 */
  public final LongAdder afkEntered = new LongAdder();
  public final LongAdder afkPacketsDropped = new LongAdder();

  /** 高延迟降视距：降视距次数 / 还原次数。 */
  public final LongAdder viewDistanceReduced = new LongAdder();
  public final LongAdder viewDistanceRestored = new LongAdder();

  /** 生成中文可读快照（顺序稳定，便于命令输出）。 */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("零位移取消", entityPacketsCancelled.sum());
    map.put("零位移放行", entityPacketsPassed.sum());
    map.put("合并批次", blockMergeBatches.sum());
    map.put("合并变更数", blockChangesMerged.sum());
    map.put("变更原样放行", blockChangesPassed.sum());
    map.put("实体隐藏", entitiesHidden.sum());
    map.put("实体恢复", entitiesShown.sum());
    map.put("复检提交", recheckSubmitted.sum());
    map.put("复检致隐藏", recheckHidden.sum());
    map.put("复检致恢复", recheckShown.sum());
    map.put("进入AFK", afkEntered.sum());
    map.put("AFK丢包", afkPacketsDropped.sum());
    map.put("降视距", viewDistanceReduced.sum());
    map.put("还原视距", viewDistanceRestored.sum());
    return map;
  }
}