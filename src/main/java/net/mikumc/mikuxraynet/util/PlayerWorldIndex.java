package net.mikumc.mikuxraynet.util;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * 玩家当前世界上下文快照：封包线程只查这张表，<b>不读实体活状态、也不按世界名回查世界</b>。
 *
 * <p><b>要解决的到底是什么</b>：区块改写要按维度选档案、按世界最低高度与 section 数解码再编码。这些量
 * 只在世界切换时变化，但过去是<b>在异步回调里从实体现读</b>的——于是「封包属于哪个世界」与「用哪个世界的
 * 参数编码」之间存在一段可错配的窗口：玩家跨维度时，在途的旧世界区块包完全可能按新世界的档案/高度去
 * 编解码（错维度至少是防护削弱；高度不一致则直接解码失败、整批放行，等于首批区块全裸）。
 * 另外，{@code Player#getWorld()} 在非所有者线程上读取本身就是本项目明令禁止的越界读取
 * （{@code CraftEntity#getWorld()} 在 26.2 上只是字段返回、<b>未必</b>抛异常，但同族的实体读取在
 * Folia 上出现过 {@code No currently ticking region}，见 {@code PacketThreadPurityTest} 的类注释）。
 * 改为「实体所属线程发布不可变快照 + 封包按自己的快照取用」后，上述两个问题一起消失。
 *
 * <p><b>发布者（谁是权威）</b>：只有 Bukkit 玩家事件——{@link PlayerJoinEvent}、{@link
 * PlayerChangedWorldEvent}（跨维度重生与跨世界传送都会触发）——在<b>实体所属线程</b>上发布。
 * 生命周期启动时的「初始在线玩家」快照只填空位（{@link #publishIfAbsent}），<b>绝不覆盖</b>期间已经
 * 发布过的更新会话。
 *
 * <p><b>读失败必须失效而非留旧</b>：{@link PlayerChangedWorldEvent} 上若取不到新世界上下文，本类<b>删除</b>
 * 该玩家的条目（而不是保留旧快照）——留着旧快照正是「拿错高度编码新世界区块」的来源。删除后封包路径按
 * 「无上下文」即时放行原包，方向安全。
 *
 * <p><b>{@code session} 与实例身份</b>：世界身份（世界名 / World 实例 / 维度 / 最低高度 / section 数）
 * 不变时<b>复用同一个不可变实例</b>（引用相等即「同一代次」）；身份变化时从<b>进程级</b>计数器分配一个
 * 全局唯一代次，因此玩家退出清键后同 UUID 同世界重连、乃至插件热重载前后的实例，都不会与旧代次撞号。
 *
 * <p><b>停启隔离（为什么表是实例所有）</b>：玩家表与「取不到上下文」的一次性提示都挂在<b>实例</b>上，
 * 静态侧只保留一个 {@code volatile instance}。热路径读一次 {@code instance} 后就是无锁 {@code get}；
 * {@code start}/{@code stop} 只做一次 volatile 切换。于是「已退休实例的迟到回调」最多写进那张已退休的表
 * （随后整表被 GC），<b>写不进新实例</b>——不存在「stop 清表后又被人回填」的时间窗。
 */
public final class PlayerWorldIndex implements Listener {

  /**
   * 不可变世界上下文（一份快照承载区块改写需要的全部世界级常量）。
   *
   * <p>字段全部 final、无 setter：发布线程写完即不再变化，封包线程与工作线程直接读。
   *
   * @param worldName    世界名（Bukkit 的 {@code World#getName()}）
   * @param world        世界引用；<b>仅用于「在正确区域调度邻块抓取」</b>（{@code RegionScheduler}）。
   *                     世界已卸载或离线单测时为 {@code null}，此时跳过邻块抓取、改写按缺失策略降级，
   *                     维度与高度不受影响
   * @param environment  世界环境（维度归类依据；只做纯枚举映射，不再触碰世界）
   * @param minHeight    该世界最低建筑高度（解码/编码与方块实体坐标换算用）
   * @param sectionCount 该世界的 section 数量（{@code (maxHeight - minHeight) / 16}）
   * @param session      世界身份代次（进程级唯一；同一世界身份保持同一个实例，故引用相等即同代次）
   */
  public record WorldContext(String worldName, World world, World.Environment environment,
      int minHeight, int sectionCount, long session) {

    /** 紧凑构造：只做最基本的自洽校验，绝不在这里抛异常打断事件发布。 */
    public WorldContext {
      Objects.requireNonNull(worldName, "worldName");
      if (sectionCount <= 0) {
        throw new IllegalArgumentException("sectionCount 必须为正：" + sectionCount);
      }
    }

    /**
     * 世界身份是否与另一份快照相同：世界名 / World 实例 / 维度 / 最低高度 / section 数全等。
     *
     * <p>用于决定是否复用同一个实例（即同一个代次）：同一世界的重复发布（重生事件与换世界事件同 tick
     * 各来一次）必须保持同一代次，否则同世界的在途改写会被当成跨会话。
     */
    public boolean sameIdentity(WorldContext other) {
      return other != null
          && this.minHeight == other.minHeight
          && this.sectionCount == other.sectionCount
          && this.environment == other.environment
          && this.world == other.world
          && this.worldName.equals(other.worldName);
    }

    /** 带上指定代次的副本（发布时统一编号，避免调用方各自拼）。 */
    WorldContext withSession(long newSession) {
      if (this.session == newSession) {
        return this;
      }
      return new WorldContext(worldName, world, environment, minHeight, sectionCount, newSession);
    }
  }

  /** 世界身份代次：<b>进程级</b>唯一、单调递增（退出清键 / 重连 / 热重载都不会与旧代次撞号）。 */
  private static final AtomicLong SESSION_SEQ = new AtomicLong();

  /** 当前生效的实例；封包线程只读这一次 volatile，之后无锁 {@code get}。 */
  private static volatile PlayerWorldIndex instance;

  /** 实例所有的玩家表：退休实例的迟到回调只可能写进这张已退休的表。 */
  private final ConcurrentHashMap<UUID, WorldContext> contexts = new ConcurrentHashMap<>();

  /** 实例所有：「上下文取不到」的一次性提示闸门（每个实例最多一条，绝不刷屏）。 */
  private final AtomicBoolean captureFailureWarned = new AtomicBoolean();

  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger("MikuXrayNet");

  private PlayerWorldIndex() {
  }

  /**
   * 未安装的独立实例：<b>仅供离线单测</b>（不注册事件、不读在线玩家，静态门面也看不到它）。
   *
   * <p>它让「发布/停止/退休隔离」这些行为能在没有服务端的情况下被真实驱动，而不是只靠读源码。
   */
  static PlayerWorldIndex isolated() {
    return new PlayerWorldIndex();
  }

  // ------------------------------------------------------------------ 静态门面（封包线程）

  /** 封包线程读取：未知玩家 / 未安装实例时返回 {@code null}（调用方按「无法判定」放行）。 */
  public static String worldName(UUID playerId) {
    WorldContext context = context(playerId);
    return context == null ? null : context.worldName();
  }

  /**
   * 封包线程读取完整上下文：未知玩家 / 未安装实例时返回 {@code null}。
   *
   * <p>只读一次 {@code volatile instance}，随后是无锁 {@code get}；返回的是不可变快照，调用方可以安全
   * 持有并跨线程使用（含把 {@link WorldContext#world()} 交给区域调度器）。
   */
  public static WorldContext context(UUID playerId) {
    PlayerWorldIndex current = instance;
    return current == null || playerId == null ? null : current.contexts.get(playerId);
  }

  // ------------------------------------------------------------------ 生命周期

  /** 注册并快照当前在线玩家（幂等）。 */
  public static synchronized void start(Plugin plugin) {
    if (plugin == null || instance != null) {
      return;
    }
    PlayerWorldIndex fresh = new PlayerWorldIndex();
    // 先发布实例再注册事件：注册期间若已有事件/封包打到本实例，最多读到空表（原包放行），
    // 不会出现「事件已投递、表却还不存在」的反向缺口。注册失败则立刻撤回。
    instance = fresh;
    try {
      Bukkit.getPluginManager().registerEvents(fresh, plugin);
    } catch (Throwable throwable) {
      instance = null;
      plugin.getLogger().log(java.util.logging.Level.WARNING,
          "玩家世界快照初始化失败（方块变更注销与区块改写将按原包放行）", throwable);
      return;
    }
    try {
      for (Player player : Bukkit.getOnlinePlayers()) {
        fresh.bootstrap(plugin, player);
      }
    } catch (Throwable throwable) {
      // 在线列表读不到（极少见）：不影响事件路径，玩家加入时由 join 事件补上上下文
      plugin.getLogger().log(java.util.logging.Level.FINE, "初始在线玩家快照失败（已忽略）", throwable);
    }
  }

  /** 注销并清空（停用/热重载）：先把实例摘走，再释放它自己的表。 */
  public static synchronized void stop() {
    PlayerWorldIndex retired = instance;
    instance = null;
    if (retired == null) {
      return;
    }
    // 表是实例所有的：清空只是尽快释放内存；即便还有在途回调写回，也只会落进这张已退休的表，
    // 静态门面已经看不到它（不会污染下一次 start 的新实例）。
    retired.contexts.clear();
    try {
      // 只注销本监听器：HandlerList.unregisterAll(plugin) 会把该插件的其它监听一并摘掉
      HandlerList.unregisterAll(retired);
    } catch (Throwable ignored) {
      // 停用期注销失败不影响正确性
    }
  }

  // ------------------------------------------------------------------ 实例侧读写

  /** 该实例自己的表里查（离线单测与实例内一致性用；封包线程走静态 {@link #context(UUID)}）。 */
  WorldContext contextOf(UUID playerId) {
    return playerId == null ? null : contexts.get(playerId);
  }

  /** 该实例自己的表里查世界名。 */
  String worldNameOf(UUID playerId) {
    WorldContext context = contextOf(playerId);
    return context == null ? null : context.worldName();
  }

  /**
   * 发布一份新上下文（玩家事件路径，实体所属线程调用）：<b>总是生效</b>。
   *
   * <p>世界身份不变时复用传入的旧实例（调用方看到的仍是<b>同一个</b> {@code WorldContext}，引用相等即
   * 「同一代次」）；身份变化时分配一个进程级唯一的新代次。
   *
   * @return 是否真的写入（参数不全时为 false，调用方无需处理）
   */
  boolean publish(UUID playerId, WorldContext fresh) {
    if (playerId == null || fresh == null) {
      return false;
    }
    contexts.compute(playerId, (key, previous) ->
        previous != null && previous.sameIdentity(fresh)
            ? previous
            : fresh.withSession(SESSION_SEQ.incrementAndGet()));
    return true;
  }

  /**
   * 只在「该玩家尚无上下文」时发布（生命周期启动的初始在线玩家快照）。
   *
   * <p><b>绝不覆盖</b>期间已由 join / changedWorld 发布的新会话——启动快照只是一次补偿，
   * 玩家事件才是权威。
   *
   * @return 是否真的写入（已有上下文或参数不全时为 false）
   */
  boolean publishIfAbsent(UUID playerId, WorldContext fresh) {
    if (playerId == null || fresh == null) {
      return false;
    }
    return contexts.putIfAbsent(playerId, fresh.withSession(SESSION_SEQ.incrementAndGet())) == null;
  }

  /** 移除某玩家的上下文（退出）。 */
  void forget(UUID playerId) {
    if (playerId != null) {
      contexts.remove(playerId);
    }
  }

  /** 清空本实例的表（停用后释放内存；离线单测用于隔离用例）。 */
  void clear() {
    contexts.clear();
  }

  /** 当前实例表里的条目数（诊断/单测用）。 */
  int size() {
    return contexts.size();
  }

  /**
   * 从实体上取一份上下文快照：<b>只能在拥有该实体的线程（Paper 主线程 / Folia 区域线程）调用</b>。
   *
   * <p>读不到世界或高度异常（世界正在卸载等）时返回 {@code null}，由调用方按「无法判定」放行。
   */
  static WorldContext capture(Player player) {
    if (player == null) {
      return null;
    }
    try {
      World world = player.getWorld();
      if (world == null) {
        return null;
      }
      int minHeight = world.getMinHeight();
      int sectionCount = (world.getMaxHeight() - minHeight) / 16;
      if (sectionCount <= 0) {
        return null;
      }
      return new WorldContext(world.getName(), world, world.getEnvironment(), minHeight, sectionCount, 0L);
    } catch (Throwable throwable) {
      // 世界正在卸载 / 实体已退役：调用方按「无上下文」放行，绝不阻塞封包
      return null;
    }
  }

  // ------------------------------------------------------------------ 发布/清理路径

  /**
   * 启动快照：能就地取（当前线程拥有该实体）就同步取，否则投递到该玩家自己的调度器。
   *
   * <p>Folia 上全局区域线程不拥有任何玩家，直接读 {@code player.getWorld()} 正是这条纪律要避免的；
   * 投递到 {@code EntityScheduler} 后回调落在该玩家的区域线程上，读法合法。
   *
   * <p>回调内的<b>实例身份闸</b>（{@code instance != this}）不可省：快速 stop→start 后上一实例尚未执行的
   * 回调仍可能被调度器跑起来，此时它只能看到「自己已是退休实例」，于是直接放弃——不会把旧代次写进新表。
   * （表本身也是实例所有的，即便漏掉这道闸，写入也只会落进已退休的表；两道保险互不替代。）
   */
  private void bootstrap(Plugin plugin, Player player) {
    if (player == null) {
      return;
    }
    if (ownsEntity(player)) {
      if (instance == this) {
        publishIfAbsent(player.getUniqueId(), capture(player));
      }
      return;
    }
    try {
      player.getScheduler().run(plugin, task -> {
        if (instance != this || !player.isOnline()) {
          return;
        }
        publishIfAbsent(player.getUniqueId(), capture(player));
      }, null);
    } catch (Throwable ignored) {
      // 实体已退役 / 插件已停用：交给 join 事件或后续区块包各自的 fail-open 路径
    }
  }

  /** 当前线程是否拥有该实体（Paper 上恒 true；Folia 上仅其区域线程为 true）。 */
  private static boolean ownsEntity(Player player) {
    try {
      return Bukkit.isOwnedByCurrentRegion(player);
    } catch (Throwable throwable) {
      return false;
    }
  }

  private void publishPlayer(Player player) {
    if (player == null || instance != this) {
      // 已退休的实例不再接受事件写（事件注册与 stop 之间仍可能有极窄的投递窗口）。
      return;
    }
    UUID playerId = player.getUniqueId();
    WorldContext context = capture(player);
    if (context == null) {
      // 读不到（世界正在卸载 / 实体已退役）：<b>丢弃旧快照</b>而不是留着——留旧快照意味着随后新世界的
      // 区块包会按旧世界的维度/高度编码，正是最危险的错配。丢弃后封包路径按原包即时放行。
      forget(playerId);
      warnCaptureFailure();
      return;
    }
    publish(playerId, context);
  }

  /** 一次性中文提示：上下文取不到时封包会按原包放行（防护暂时减弱），必须可见但不得刷屏。 */
  private void warnCaptureFailure() {
    if (captureFailureWarned.compareAndSet(false, true)) {
      LOGGER.warning("玩家世界上下文取不到（世界正在卸载或实体已退役）：该玩家的区块包将按原包放行，"
          + "直到下一次进入/切换世界成功。本提示每次启用最多一条。");
    }
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    publishPlayer(event.getPlayer());
  }

  @EventHandler
  public void onChangedWorld(PlayerChangedWorldEvent event) {
    publishPlayer(event.getPlayer());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    forget(event.getPlayer().getUniqueId());
  }
}
