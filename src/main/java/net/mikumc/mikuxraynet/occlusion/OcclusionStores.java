package net.mikumc.mikuxraynet.occlusion;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按世界分域的遮挡数据仓库注册表：每个世界一张独立的 {@link OcclusionStore}。
 *
 * <p><b>为什么必须有这一层（本类存在的唯一理由）</b>：{@link OcclusionStore} 的键只有
 * 「区块坐标 + section 高度」——64 位键已被水平 28 位 + sectionY 8 位 + 水平 28 位占满，塞不下
 * 第四个字段（世界维度）。而本插件是<b>全服单例</b>：反矿透改写与异步射线同时服务多个世界。
 * 若把各世界的 section 混在同一张表里，主世界 (0,0) 与下界 (0,0) 的几何会互相覆盖
 * （后写入者胜）——把下界的实心位图读成主世界的「完全遮挡」，会把<b>本可见的实体隐藏起来</b>，
 * 这是安全方向错误的缺陷（多世界服务器上必然触发，因为出生点附近两个世界的同名区块通常都在加载）。
 *
 * <p><b>为什么外层键用世界名而不是世界 UUID</b>：写入方（反矿透改写工作线程）只拿得到世界名
 * （{@code RewriteTask#worldName}）；取 UUID 要调 Bukkit API，那是工作线程的禁区。世界名由服务端
 * 保证唯一，读取方（实体线程）同样只读一次 {@code World#getName()}，两侧口径天然一致、无需换算。
 *
 * <p><b>线程模型</b>：外层表是 {@link ConcurrentHashMap}（{@link #of} 允许改写工作线程懒建），
 * 内层各 {@link OcclusionStore} 自身是 SWMR（写串行、读无锁）。射线工作线程只走
 * {@link #lookup(String)} 只读查询，绝不建表。
 */
public final class OcclusionStores {

  private final ConcurrentHashMap<String, OcclusionStore> byWorld = new ConcurrentHashMap<>();

  /**
   * 取（必要时懒建）某世界的仓库；供<b>写入方</b>（建表 / 方块补丁）使用。
   *
   * <p>{@code worldName} 为 {@code null} 时给出一张独立仓库（不与任何真实世界混），
   * 使「世界名未知」也不会污染真实世界的数据。
   */
  public OcclusionStore of(String worldName) {
    return byWorld.computeIfAbsent(worldName == null ? "" : worldName,
        name -> new OcclusionStore());
  }

  /** 只读查询某世界的仓库；不存在返回 {@code null}（射线按「无遮挡数据」fail-open 处理）。 */
  public OcclusionStore lookup(String worldName) {
    return worldName == null ? null : byWorld.get(worldName);
  }

  /** 整体摘除某世界（世界卸载 / 世界失效）：其全部 section 位图随之释放。 */
  public void removeWorld(String worldName) {
    if (worldName == null) {
      return;
    }
    OcclusionStore removed = byWorld.remove(worldName);
    if (removed != null) {
      // 先置 closed（阻断在途写），再清空内容：改写工作线程可能正持有该仓库引用并在摘除之后继续
      // 写入；若不清空，同名世界重新加载时这张「旧世界的表」会被当成新世界几何读走（该显示却多藏）。
      removed.closeAndClear();
    }
  }

  /** 清空全部世界（插件停用 / 反矿透整体重建）。 */
  public void clear() {
    for (OcclusionStore store : byWorld.values()) {
      store.clear();
    }
    byWorld.clear();
  }

  /** 当前驻留的世界数。本方法当前仅单测在用，保留以免破坏测试。 */
  public int worldCount() {
    return byWorld.size();
  }

  /** 当前驻留的 section 条目总数（诊断用，近似值；跨世界求和）。 */
  public int residentSections() {
    int total = 0;
    for (OcclusionStore store : byWorld.values()) {
      total += store.loadedSectionCount();
    }
    return total;
  }
}
