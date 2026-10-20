package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 邻近显形统计计数，供 {@code /mikuxraynet status} 读取。
 *
 * <p>字段均为无锁 {@link LongAdder}：工作线程（写入索引）、主线程 / 区域线程（巡检发包）
 * 与封包线程（注销）都会累加；读取方（诊断面板）逐字段 {@code sum()} 直读，无需一致快照。
 */
public final class ProximityStats {

  /** 成功下发的显形包数。 */
  public final LongAdder revealsSent = new LongAdder();

  /** 被跳过的候选坐标数（区块未加载、读取失败、发包失败等）。 */
  public final LongAdder revealsSkipped = new LongAdder();

  /** 因服务端自行下发方块变更而从索引注销的坐标数。 */
  public final LongAdder unregistered = new LongAdder();

  /**
   * 因工作线程池队列无容量而<b>整轮跳过</b>的巡检次数（显形只延迟、不丢失）。
   *
   * <p>为什么不退化到调用线程同步算完：候选筛选（视锥 + 遮挡位图射线）是本功能最重的 CPU，
   * 本模块刻意把它搬到工作线程；队列满时若在玩家线程（Paper 主线程 / Folia 区域线程）照跑满额，
   * 恰恰会在服务器最忙（区块集中下发把改写队列塞满）的时刻制造主线程毛刺。宁可本轮不显形：
   * 坐标仍留在伪装索引里，下一巡检周期（分片轮转）会重新取到。
   */
  public final LongAdder passesSkippedNoCapacity = new LongAdder();

  /** 被视锥剔除的候选坐标数（不在玩家视野锥内）。 */
  public final LongAdder revealsFrustumCulled = new LongAdder();

  /** 因射线被遮挡而跳过显形的候选坐标数（等玩家靠近后再显形）。 */
  public final LongAdder revealsRayCulled = new LongAdder();

  /**
   * 上述「射线剔除」中，判定期间曾触碰过<b>无位图数据</b>的 section 的候选数（上界式归因）。
   *
   * <p><b>为什么必须单列</b>：位图判定的缺数据规则是「无数据 = 遮挡」（见 {@code BitmapVisibility}），
   * 它与「真的被墙挡住」在 {@link #revealsRayCulled} 里混在一起——只看总数无法判断严格规则是否在伤人。
   * 这个数持续偏高才说明「区块没建过表 / 位图 section 被降级」正在憋住本应显形的方块；
   * 若它 ≈ 0 而射线剔除很大，则剔除确实来自真实遮挡，无需处理。
   *
   * <p>属上界式归因：只统计「最终判不可见且期间触到缺数据」的候选，因此不会把纯真实遮挡混进来，
   * 但极少数「既触到缺数据、也确实被墙挡住」的候选会被多算一次。
   */
  public final LongAdder revealsRayCulledMissingData = new LongAdder();

  /**
   * 过度显形抽样中被复核的显形包数（按 1/N 抽样；默认 N=20，只计数不改行为）。
   *
   * <p><b>口径</b>：发包前若 {@link RevealedSet} 已含该坐标，说明客户端应已可见（或刚被其它路径显形过），
   * 这个显形包是浪费的。抽样计数让「过度显形」从定性描述变成可读数字：
   * {@code wasted / sampled} 即浪费占比的无偏估计（样本足够大时）。
   */
  public final LongAdder overRevealSampled = new LongAdder();

  /** 抽样复核中被判为「过度」（RevealedSet 已含该坐标）的显形包数。 */
  public final LongAdder overRevealWasted = new LongAdder();

  /**
   * 生成中文可读快照（顺序稳定，便于命令输出）。
   *
   * <p>测试专用豁免：生产诊断直接逐字段读取（见 {@code Diagnostics}），
   * 本方法当前仅单测在用，保留以免破坏测试。
   */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("显形发送", revealsSent.sum());
    map.put("显形跳过", revealsSkipped.sum());
    map.put("变更注销", unregistered.sum());
    map.put("视锥剔除", revealsFrustumCulled.sum());
    map.put("射线剔除", revealsRayCulled.sum());
    map.put("射线剔除（缺数据）", revealsRayCulledMissingData.sum());
    map.put("过度显形（抽样）", overRevealWasted.sum());
    map.put("过度显形抽样数", overRevealSampled.sum());
    return map;
  }
}