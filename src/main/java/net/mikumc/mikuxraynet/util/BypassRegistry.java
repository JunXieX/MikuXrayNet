package net.mikumc.mikuxraynet.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * 反矿透直通名单：持有 {@value #PERMISSION} 权限的玩家在此登记，其区块封包走直通路径（不改写、不伪装）。
 *
 * <p><b>为什么用「按玩家维度直通」而不是改缓存键</b>：改写缓存是按「世界名 + 区块坐标 + 配置指纹」
 * 共享的，若把绕过状态混进缓存键，会让每个玩家的缓存互相隔离，命中率崩塌且内存翻倍。因此这里只在
 * 玩家维度做一次短路判定——缓存本身完全不受影响，一致性不被破坏。
 *
 * <p><b>线程纪律</b>：名单是无锁并发集合。写入来自主线程 / Folia 区域线程（登录、退出、插件启用时
 * 的一次性快照），读取来自任意线程（封包改写路径），读侧只做 {@code contains} 查询，不触碰任何 Bukkit API。
 *
 * <p><b>权限只在「进入服务器时」判定一次（核心语义，请勿当 bug「修」回去）</b>：本类<b>不做任何周期性
 * 权限检查</b>。玩家是否直通仅在 {@link PlayerJoinEvent}（登录那一刻）与插件启用时对「当时已在线玩家」
 * 的一次性快照里判定；<b>运行期间由权限插件授予 / 收回 {@code mikuxraynet.bypass} 都不会即时生效，
 * 必须重新进入服务器（退出→重登即可）才按新权限重算</b>。
 *
 * <p><b>为什么这样设计</b>：封包线程（netty）绝不能查权限、不能碰 Bukkit API，直通判定只能读本名单这份
 * 线程安全快照。此前为了「及时反映权限变更」每 tick 兜底巡检在线玩家（并周期性清理离线残留），
 * 代价是引入了一个持续的调度任务与「运行期改权限会延迟生效」的错觉语义；经确认改为「登录时快照」，
 * 语义简单无歧义，也彻底摆脱周期开销。若日后有人希望「运行期改权限即时生效」，不要恢复周期巡检——
 * 那会让封包热路径的判定依赖随时间变化的状态，请先与需求方确认语义再动。
 *
 * <p><b>共享入口</b>：{@link #isBypassedNow(UUID)} 暴露「当前活动名单」的静态查询，供带宽模块
 * 在封包线程复用（这些模块由 {@code bandwidth.ThrottlePipeline} 装配，其构造签名不在本类职责范围，
 * 故以静态入口共享同一份名单，而不是让每个模块各自查权限）。名单未装配或已停用时返回 false
 * （与「无直通」一致，fail-open）。
 */
public final class BypassRegistry implements Listener {

  /** 绕过反矿透所必需的权限节点（唯一出处见 {@link Constants#BYPASS_PERMISSION}）。 */
  public static final String PERMISSION = Constants.BYPASS_PERMISSION;

  /** 当前活动的直通名单（登录/退出/启用快照由其维护）；插件未启用或已停用时为 null。 */
  private static volatile BypassRegistry active;

  private final Plugin plugin;
  private final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();

  public BypassRegistry(Plugin plugin) {
    this.plugin = plugin;
  }

  /**
   * 注册登录/退出监听，并对「启用时已在线」的玩家做一次快照判定。可重复调用，异常安全。
   *
   * <p><b>先注册成功再发布 {@code active}（本次修复）</b>：旧实现先把 {@code active = this} 再
   * {@code registerEvents}，一旦注册<b>半程失败</b>，{@code active} 已指向本实例、但监听未注册，
   * 名单永不更新 → 该显示真矿的 bypass 玩家反而被 fail-closed（看不到真矿）。现在改为：
   * 只有 {@code registerEvents} 成功才接管 {@code active}；失败则<b>保留前一实例</b>（不 stop、不覆盖），
   * 从而既不泄漏 stale 名单，也绝不因一次注册失败把一个可用的名单整体打成 fail-closed。
   *
   * <p><b>幂等且清理前一实例</b>：注册成功后，先停用「当前活动实例」（若不同）再接管，最后做一次性快照。
   * 若当前活动实例正是本实例（重复 {@code start()}），直接返回，避免重复注册监听。
   */
  public void start() {
    // 已是当前活动实例：重复启动无副作用，直接返回（否则会重复注册监听，事件被处理两次）
    if (active == this) {
      return;
    }
    try {
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
    } catch (Throwable throwable) {
      // 注册失败：不发布 active、不清前一实例，保留既有可用名单（绝不 fail-closed）
      plugin.getLogger().warning("直通名单初始化失败（不影响按登录/退出维护）：" + throwable.getMessage());
      return;
    }
    // 注册成功：清理「当前活动实例」（可能是另一个实例）——其监听必须先注销、名单先清空，
    // 避免两个实例并存导致名单双份维护、互相不一致（stale 名单 + 监听泄漏）。
    BypassRegistry previous = active;
    if (previous != null && previous != this) {
      previous.stop();
    }
    active = this;
    try {
      // 一次性快照（非周期）：服务器刚装插件 / reload 时，把「当时已在线」玩家的当前权限登记进名单。
      // 复用被移除的巡检所用调度方式（GlobalRegionScheduler）：Paper 上落在主线程，Folia 上落在全局
      // 区域线程；只跑一次，此后不再有任何刷新——运行期改权限需重进服务器。
      Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> snapshotOnlinePlayers());
    } catch (Throwable throwable) {
      // 快照只是「补齐启用时已在线玩家」，失败不影响登录/退出事件维护的名单
      plugin.getLogger().warning("直通名单初始化失败（不影响按登录/退出维护）：" + throwable.getMessage());
    }
  }

  /** 注销监听并清空名单。可重复调用。 */
  public void stop() {
    if (active == this) {
      active = null;
    }
    HandlerList.unregisterAll(this);
    bypassed.clear();
  }

  /**
   * 当前活动的直通名单的统一查询入口（供带宽等模块在封包线程使用）。
   *
   * <p>只读并发集合，不触碰任何 Bukkit API；名单未装配或已停用时返回 false（与「无直通」一致）。
   */
  public static boolean isBypassedNow(UUID playerId) {
    BypassRegistry registry = active;
    return registry != null && registry.isBypassed(playerId);
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    // 登录那一刻判定一次；此后不再复查（运行期改权限需重进服务器）。
    Player player = event.getPlayer();
    refresh(player.getUniqueId(), player.hasPermission(PERMISSION));
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    remove(event.getPlayer().getUniqueId());
  }

  /**
   * 按某玩家「登录时」的权限登记直通名单（登录事件与启用快照共用此入口）。
   *
   * @param playerId  玩家标识
   * @param isBypassed 登录那一刻是否持有 bypass 权限
   */
  public void refresh(UUID playerId, boolean isBypassed) {
    if (playerId == null) {
      return;
    }
    if (isBypassed) {
      bypassed.add(playerId);
    } else {
      bypassed.remove(playerId);
    }
  }

  /** 从名单中移除（玩家退出）。 */
  public void remove(UUID playerId) {
    if (playerId != null) {
      bypassed.remove(playerId);
    }
  }

  /** 该玩家当前是否直通（不伪装）。 */
  public boolean isBypassed(UUID playerId) {
    return playerId != null && bypassed.contains(playerId);
  }

  /** 当前直通玩家数（诊断用）。 */
  public int size() {
    return bypassed.size();
  }

  /**
   * 一次性快照：把「在线玩家 → 是否持有 bypass」的判定结果灌入名单。
   *
   * <p>包可见以便离线单测注入判定结果（真实路径由 {@link #snapshotOnlinePlayers()} 采集）。
   * 只在插件启用时调用一次，非周期。
   */
  void snapshot(Map<UUID, Boolean> decisions) {
    if (decisions == null) {
      return;
    }
    for (Map.Entry<UUID, Boolean> decision : decisions.entrySet()) {
      refresh(decision.getKey(), Boolean.TRUE.equals(decision.getValue()));
    }
  }

  /** 采集「启用时已在线」的玩家并做一次性快照（真实路径，只跑一次）。 */
  private void snapshotOnlinePlayers() {
    try {
      // Folia 安全性：getOnlinePlayers() 只取在线玩家集合快照，不触碰实体状态/坐标；
      // Player#hasPermission 只读权限表，与区域归属无关。因此在 GlobalRegionScheduler 的全局线程上
      // 枚举并按玩家判定是全服安全的（与已移除的周期巡检所用枚举方式同源）。
      Map<UUID, Boolean> decisions = new HashMap<>();
      for (Player player : Bukkit.getOnlinePlayers()) {
        decisions.put(player.getUniqueId(), player.hasPermission(PERMISSION));
      }
      snapshot(decisions);
    } catch (Throwable ignored) {
      // 枚举失败：保留现有名单，不影响登录/退出事件维护（fail-open）
    }
  }
}