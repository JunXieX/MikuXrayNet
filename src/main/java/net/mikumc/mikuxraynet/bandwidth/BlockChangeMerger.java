package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.bandwidth.BlockChangeBatch.Update;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * 方块变更合并：把同一玩家在短时间窗内的多条 {@code BLOCK_CHANGE} / {@code MULTI_BLOCK_CHANGE}
 * 按配置 {@code block-changes.merge-radius}（默认 2）的曼哈顿邻域聚簇，并按 section 分组重建成更少的合并包。
 *
 * <p><b>同步监听无法延迟放行</b>，因此走 ProtocolLib 异步通道：
 * {@code incrementProcessingDelay()} 登记延迟 → 变更入有界缓冲 → 时间窗到期（或条目超限）时冲刷 →
 * 合并包通过 {@link ProtocolManager#sendServerPacket}（Netty 安全）发出，原包被取消。
 *
 * <p><b>近身变更立即放行</b>：距离玩家 {@code block-changes.immediate-radius}（默认 8 格）以内的
 * 方块变更<b>不进合并窗口</b>，原包立即放行——玩家自己挖/放方块时目标就在脚边，被窗口延迟会让
 * 客户端预测得不到确认，表现为「挖掘时顿一下」。半径外的变更（爆炸、大面积刷新等）照常合并。
 * 判定只读一份由实体调度器<b>每 tick 刷新</b>的位置快照（见 {@link PlayerPositionSnapshots}）：
 * 封包线程不触碰实体状态，且刷新跟随实体/区域归属，因此传送等任何位置变化都会在一个 tick 内生效
 * （旧实现依赖 {@code PlayerMoveEvent}，而传送有独立 HandlerList，会按陈旧坐标判错）。
 *
 * <p><b>fail-open</b>：读不出字段、构造失败、校验不通过、玩家离线 —— 一律原样放行原包，绝不丢更新。
 * 原包放行通过 {@link AsyncMarker} 的 {@code signalPacketTransmission} 完成，并用一次性闸保证
 * 「恰好放行一次」。位置快照不可用（尚未建立 / 刷新或读取异常）时同样 fail-open：近身判定按
 * 「立即放行」处理，宁可少合并、绝不制造延迟。
 *
 * <p><b>首包自检</b>：第一次真正发送合并包时会回读刚写入的字段做结构校验；未通过校验前会
 * <em>同时</em>保留原包（重复下发相同方块状态是无害幂等操作），校验通过后才开始取消原包。
 */
public final class BlockChangeMerger extends PacketAdapter implements Listener {

  /** 冲刷批次内的 section 分组键。 */
  private record SectionKey(int x, int y, int z) {
  }

  /** 每个玩家的待发缓冲。 */
  private static final class Pending {
    private final UUID uuid;
    private final List<Held> held = new ArrayList<>();
    private final BlockChangeBatch<WrappedBlockData> batch;
    private ScheduledFuture<?> timer;
    private final AtomicBoolean flushed = new AtomicBoolean();

    private Pending(UUID uuid, int maxEntries) {
      this.uuid = uuid;
      this.batch = new BlockChangeBatch<>(maxEntries);
    }
  }

  /** 被延迟的原包与其一次性放行闸。 */
  private static final class Held {
    private final PacketEvent event;
    private final AtomicBoolean signalled = new AtomicBoolean();

    private Held(PacketEvent event) {
      this.event = event;
    }
  }

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final AsynchronousManager asynchronousManager;
  private final BandwidthConfig.BlockChanges config;
  private final ThrottleStats stats;
  private final ScheduledExecutorService flusher;
  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
  /**
   * 玩家位置快照（由实体调度器在玩家所属线程每 tick 刷新，封包线程只读）。
   *
   * <p>取代旧的 {@code PlayerMoveEvent} 坐标缓存：传送有独立 HandlerList，缓存会陈旧。
   *
   * @see PlayerPositionSnapshots
   */
  private final PlayerPositionSnapshots positions = new PlayerPositionSnapshots();
  /** 每玩家的位置刷新任务句柄（退出 / 实体退役 / 停用时逐一取消）。 */
  private final ConcurrentHashMap<UUID, ScheduledTask> positionTasks = new ConcurrentHashMap<>();
  private final AtomicInteger errorCounter = new AtomicInteger();
  /** 「位置快照不可用 → 近身判定一律立即放行」的一次性提示闸。 */
  private final AtomicBoolean snapshotFailOpenNoticed = new AtomicBoolean();
  /** 启动期「方块数据契约探测」只做一次（重复 start 不重复探测/刷屏）。 */
  private final AtomicBoolean blockDataProbeDone = new AtomicBoolean();

  private volatile boolean verified;
  private AsyncListenerHandler handler;

  public BlockChangeMerger(Plugin plugin, ProtocolManager protocolManager,
      AsynchronousManager asynchronousManager, BandwidthConfig.BlockChanges config, ThrottleStats stats) {
    super(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.asynchronousManager = asynchronousManager;
    this.config = config;
    this.stats = stats;
    this.flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "MikuXrayNet-BlockMerge");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** 注册异步监听器（真异步扣包）与每 tick 的位置快照刷新任务，并做一次启动期契约探测。 */
  public void start() {
    // 启动期探测一次：让「服务端结构变更导致合并静默失效」可见（不改变任何行为）
    probeBlockDataContract();
    handler = asynchronousManager.registerAsyncHandler(this);
    handler.start();
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      schedulePositionRefresh(player);
    }
    plugin.getLogger().info("带宽模块已启用：方块变更合并（时间窗 " + config.mergeWindowMillis()
        + "ms，邻域半径 " + config.mergeRadius() + "，立即放行半径 " + config.immediateRadius()
        + " 格，缓冲上限 " + config.maxPendingEntries() + "）");
  }

  /** 注销监听器并偿还所有正在延迟中的原包。 */
  public void stop() {
    flushAll();
    if (handler != null) {
      try {
        asynchronousManager.unregisterAsyncHandler(handler);
      } catch (Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "注销方块变更异步监听器时出现异常（通常可忽略）", throwable);
      }
      handler = null;
    }
    HandlerList.unregisterAll(this);
    cancelAllPositionRefreshTasks();
    positions.clear();
    flusher.shutdownNow();
  }

  @EventHandler(ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    schedulePositionRefresh(event.getPlayer());
  }

  @EventHandler(ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    cancelPositionRefresh(event.getPlayer().getUniqueId());
  }

  /**
   * 为玩家启动「每 tick 刷新位置快照」的实体调度任务。
   *
   * <p><b>为什么用实体调度器</b>：{@code player.getScheduler().runAtFixedRate(...)} 在 Paper 与 Folia
   * 上是同一套 API——Paper 上「实体所属线程」即服务端主线程，Folia 上即该玩家所在区域的区域线程，
   * 因此刷新永远运行在拥有该玩家的线程上（Folia 上绝不跨区域读实体状态），无需按平台二选一。
   */
  private void schedulePositionRefresh(Player player) {
    UUID playerId = player.getUniqueId();
    long period = 1L;
    ScheduledTask task = Schedulers.repeatOnEntity(plugin, player, period, period,
        () -> refreshPosition(player, playerId), () -> discardPositionSnapshot(playerId));
    if (task == null) {
      // 实体已退役（调度失败）：无句柄可存，读取侧会因缺快照而 fail-open
      return;
    }
    ScheduledTask previous = positionTasks.put(playerId, task);
    if (previous != null) {
      previous.cancel();
    }
  }

  /**
   * 玩家所属线程：用<b>零分配 getter</b>读取位置写入快照。
   *
   * <p>刻意不用 {@code getLocation()}——那会每 tick 每玩家 new 一个 {@code Location}。
   * 读取或写入异常只丢弃该玩家的快照（读取侧随即 fail-open，下一 tick 自动重建），
   * 任务句柄仍保留以便退出/停用时正常取消，且绝不把异常抛回调度器。
   */
  private void refreshPosition(Player player, UUID playerId) {
    try {
      positions.refresh(playerId, player.getX(), player.getY(), player.getZ());
    } catch (Throwable throwable) {
      positions.discard(playerId);
      noticeSnapshotFailOpen(throwable);
    }
  }

  /** 玩家退出 / 实体退役：取消其刷新任务并丢弃快照。 */
  private void cancelPositionRefresh(UUID playerId) {
    ScheduledTask task = positionTasks.remove(playerId);
    if (task != null) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已随实体退役结束，取消失败可忽略
      }
    }
    positions.discard(playerId);
  }

  /** 实体退役回调：摘掉任务句柄并丢弃快照（此后读取侧一律 fail-open）。 */
  private void discardPositionSnapshot(UUID playerId) {
    positionTasks.remove(playerId);
    positions.discard(playerId);
  }

  /** 停用兜底：取消全部位置刷新任务。 */
  private void cancelAllPositionRefreshTasks() {
    for (ScheduledTask task : positionTasks.values()) {
      try {
        task.cancel();
      } catch (Throwable ignored) {
        // 任务可能已随实体退役结束，取消失败可忽略
      }
    }
    positionTasks.clear();
  }

  /**
   * 快照不可用时的一次性中文提示。语义是 fail-open：一律按近身处理（立即放行），
   * 宁可少合并、绝不制造延迟。
   */
  private void noticeSnapshotFailOpen(Throwable cause) {
    if (!snapshotFailOpenNoticed.compareAndSet(false, true)) {
      return;
    }
    String text = "方块变更合并：玩家位置快照暂不可用，近身判定一律按「立即放行」处理"
        + "（fail-open——宁可少合并、绝不制造延迟）。快照由玩家所属线程每 tick 重建，"
        + "通常一两秒内自动恢复；此提示只打印一次。";
    if (cause == null) {
      plugin.getLogger().warning(text);
    } else {
      plugin.getLogger().log(Level.WARNING, text, cause);
    }
  }

  /**
   * 启动期探测一次「ProtocolLib 的 {@code WrappedBlockData$NewBlockData} 静态契约」是否成立。
   *
   * <p><b>为什么需要</b>：该类 {@code <clinit>} 会用 {@code FuzzyReflection} 在 {@code CraftMagicNumbers}
   * 上硬查 7 个方法契约（{@code MATERIAL_FROM_BLOCK} / {@code BLOCK_FROM_MATERIAL} /
   * {@code TO_LEGACY_DATA} / {@code FROM_LEGACY_DATA} / {@code GET_BLOCK} /
   * {@code DEFAULT_BLOCK_DATA} / {@code GET_HANDLE}）；服务端结构一变就可能整体失败。失败后
   * {@link #readUpdates} 会把出站包判为「解析不了」并原样放行（既有 fail-open），于是
   * <b>合并能力静默消失</b>——日志里看不到任何异常，排查时极易误判为「配置没生效」。
   *
   * <p><b>只让静默降级可见</b>：探测结果不改变任何行为（不注册/不注销该模块、不抛异常、不影响启动），
   * 失败只打一次中文 WARN。用 {@code initialize=true} 触发 clinit——这与生产路径（首个出站变更包）
   * 触发的是同一个初始化，因此不引入新的失败面；探测本身抛出的异常一律吞掉并记为该探针失败。
   */
  private void probeBlockDataContract() {
    if (!blockDataProbeDone.compareAndSet(false, true)) {
      return;
    }
    try {
      Class.forName("com.comphenix.protocol.wrappers.WrappedBlockData$NewBlockData", true,
          WrappedBlockData.class.getClassLoader());
    } catch (Throwable throwable) {
      // 异常吞掉：探测失败绝不影响启动
      plugin.getLogger().log(Level.WARNING,
          "变更合并模块已停用（服务端结构变更）：ProtocolLib 的 WrappedBlockData$NewBlockData 静态契约"
              + "探测失败（CraftMagicNumbers 的 7 个方法契约未全部探到），方块变更合并将静默失效、"
              + "原包照常放行。请更新 ProtocolLib 或本插件到匹配当前服务端的版本后重启。",
          throwable);
    }
  }

  /**
   * 该玩家的近身变更判定：<b>只读位置快照</b>（封包线程不触碰任何 Bukkit API）。
   *
   * <p>fail-open：快照尚未建立（刚登录 / 刚重置）或读取异常 → 按近身处理（立即放行）。
   * 与旧实现的唯一语义差别就在这里：旧实现快照缺失时退化为「照常合并」（会延迟），
   * 现在改为「立即放行」——无法判断距离时宁可少合并，绝不制造延迟。
   */
  private boolean shouldPassThroughImmediately(UUID playerId, List<Update<WrappedBlockData>> updates) {
    int radius = config.immediateRadius();
    if (radius <= 0) {
      // 配置关闭立即放行：与旧行为一致（全部走合并）
      return false;
    }
    try {
      PlayerPositionSnapshots.Position position = positions.get(playerId);
      if (position == null) {
        noticeSnapshotFailOpen(null);
      }
      return PlayerPositionSnapshots.immediatePass(position, updates, radius);
    } catch (Throwable throwable) {
      noticeSnapshotFailOpen(throwable);
      return true;
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    if (!config.merge() || event.isCancelled()) {
      return;
    }
    Player player = event.getPlayer();
    // 权限检查在封包线程执行：依赖权限插件自身线程安全（LuckPerms 支持异步查询，安全）
    if (player == null || player.hasPermission(Constants.BYPASS_PERMISSION)) {
      return;
    }

    AsyncMarker marker = event.getAsyncMarker();
    if (marker == null) {
      return;
    }

    List<Update<WrappedBlockData>> updates = readUpdates(event.getPacketType(), event.getPacket());
    if (updates == null || updates.isEmpty()) {
      stats.blockChangesPassed.increment();
      return;
    }

    // 近身变更立即放行：不登记延迟、不入缓冲，原包按原样流出。
    // 这样玩家自己挖/放方块时的方块更新不会被合并窗口拖延，客户端预测得以立即确认（消除顿感）。
    if (shouldPassThroughImmediately(player.getUniqueId(), updates)) {
      stats.blockChangesPassed.increment();
      return;
    }

    Pending target;
    boolean overflow = false;
    // 与 flush() 在同一把锁内判定「缓冲是否已被冲刷」：若已冲刷则重取新缓冲，
    // 否则会把原包加进已废弃的缓冲里，导致该包永远得不到放行（卡包）。
    while (true) {
      target = pending.computeIfAbsent(player.getUniqueId(),
          uuid -> new Pending(uuid, config.maxPendingEntries()));
      synchronized (target) {
        if (target.flushed.get()) {
          continue;
        }
        overflow = false;
        for (Update<WrappedBlockData> update : updates) {
          overflow |= target.batch.add(update.x(), update.y(), update.z(), update.value());
        }
        // 先登记延迟，再入缓冲；此后必须由 flush() 放行，保证恰好一次
        marker.incrementProcessingDelay();
        Held entry = new Held(event);
        target.held.add(entry);
        try {
          if (target.timer == null) {
            long window = Math.max(1L, config.mergeWindowMillis());
            final Pending scheduled = target;
            target.timer = flusher.schedule(() -> flush(scheduled), window, TimeUnit.MILLISECONDS);
          }
        } catch (RejectedExecutionException exception) {
          // 冲刷线程已失效（插件停用中）：立即原样放行，绝不卡包
          target.held.remove(entry);
          release(entry);
        }
        break;
      }
    }

    // 条目超限：立即冲刷，避免缓冲无界增长
    if (overflow) {
      flush(target);
    }
  }

  /** 冲刷某玩家的缓冲：构造合并包并放行/取消原包。 */
  private void flush(Pending target) {
    List<Held> held;
    List<List<Update<WrappedBlockData>>> clusters;
    ScheduledFuture<?> timer;
    synchronized (target) {
      // 「是否已冲刷」与缓冲读写共用同一把锁，保证不会边冲刷边追加
      if (!target.flushed.compareAndSet(false, true)) {
        return;
      }
      pending.remove(target.uuid, target);
      timer = target.timer;
      target.timer = null;
      held = new ArrayList<>(target.held);
      target.held.clear();
      clusters = target.batch.drainClusters(config.mergeRadius());
    }
    if (timer != null) {
      timer.cancel(false);
    }
    if (held.isEmpty()) {
      return;
    }

    try {
      boolean mergeable = false;
      if (held.size() >= 2) {
        for (List<Update<WrappedBlockData>> cluster : clusters) {
          if (cluster.size() >= 2) {
            mergeable = true;
            break;
          }
        }
      }
      if (mergeable) {
        boolean wasVerified = verified;
        if (trySendMerged(held.get(0).event.getPlayer(), clusters)) {
          if (wasVerified) {
            // 已通过结构自检：取消原包（更新已由合并包交付）
            stats.blockMergeBatches.increment();
            stats.blockChangesMerged.add(held.size());
            for (Held entry : held) {
              entry.event.setCancelled(true);
              release(entry);
            }
            return;
          }
        }
      }
      stats.blockChangesPassed.add(held.size());
    } catch (Throwable throwable) {
      logThrottled(throwable);
    } finally {
      // 兜底：任何未被取消的原包都必须放行（release 为一次性，重复调用无副作用）
      for (Held entry : held) {
        release(entry);
      }
    }
  }

  /** 放行一个被延迟的原包，恰好一次。 */
  private void release(Held entry) {
    if (!entry.signalled.compareAndSet(false, true)) {
      return;
    }
    try {
      asynchronousManager.signalPacketTransmission(entry.event);
    } catch (Throwable throwable) {
      logThrottled(throwable);
    }
  }

  private void flushAll() {
    for (Pending target : new ArrayList<>(pending.values())) {
      try {
        flush(target);
      } catch (Throwable throwable) {
        logThrottled(throwable);
      }
    }
  }

  /**
   * 构造并发送合并包。
   *
   * @return true 表示本批次全部构造成功且通过回读校验
   */
  private boolean trySendMerged(Player player, List<List<Update<WrappedBlockData>>> clusters) {
    try {
      if (player == null || !player.isOnline()) {
        return false;
      }
      boolean sentAny = false;
      for (List<Update<WrappedBlockData>> cluster : clusters) {
        Map<SectionKey, List<Update<WrappedBlockData>>> sections = groupBySection(cluster);
        for (Map.Entry<SectionKey, List<Update<WrappedBlockData>>> section : sections.entrySet()) {
          List<Update<WrappedBlockData>> blocks = section.getValue();
          int limit = Math.max(1, config.maxPerPacket());
          if (blocks.size() <= limit) {
            if (!sendSection(player, section.getKey(), blocks)) {
              return false;
            }
            sentAny = true;
            continue;
          }
          if (!config.resendOnOverflow()) {
            // 不允许拆分：放弃本次合并，原包照常放行
            return false;
          }
          for (int from = 0; from < blocks.size(); from += limit) {
            if (!sendSection(player, section.getKey(), blocks.subList(from, Math.min(blocks.size(), from + limit)))) {
              return false;
            }
            sentAny = true;
          }
        }
      }
      if (sentAny) {
        verified = true;
      }
      return sentAny;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return false;
    }
  }

  /** 发送单个 section 的合并结果：多条走 MULTI_BLOCK_CHANGE，单条走 BLOCK_CHANGE。 */
  private boolean sendSection(Player player, SectionKey section, List<Update<WrappedBlockData>> blocks) {
    PacketContainer packet;
    if (blocks.size() == 1) {
      Update<WrappedBlockData> only = blocks.get(0);
      BlockPosition position = new BlockPosition(only.x(), only.y(), only.z());
      packet = new PacketContainer(PacketType.Play.Server.BLOCK_CHANGE);
      packet.getBlockPositionModifier().writeSafely(0, position);
      packet.getBlockData().writeSafely(0, only.value());
      if (!position.equals(packet.getBlockPositionModifier().readSafely(0))) {
        return false;
      }
    } else {
      WrappedBlockData[] data = new WrappedBlockData[blocks.size()];
      short[] positions = new short[blocks.size()];
      for (int i = 0; i < blocks.size(); i++) {
        Update<WrappedBlockData> update = blocks.get(i);
        data[i] = update.value();
        // 与协议一致：section 内相对坐标打包为 (x & 15) << 8 | (z & 15) << 4 | (y & 15)
        positions[i] = (short) (((update.x() & 15) << 8) | ((update.z() & 15) << 4) | (update.y() & 15));
      }
      packet = new PacketContainer(PacketType.Play.Server.MULTI_BLOCK_CHANGE);
      packet.getSectionPositions().writeSafely(0, new BlockPosition(section.x(), section.y(), section.z()));
      packet.getBlockDataArrays().writeSafely(0, data);
      packet.getShortArrays().writeSafely(0, positions);

      // 回读自检：字段必须真实存在且条目数与写入一致，否则视为结构不可用（fail-open）
      short[] readPositions = packet.getShortArrays().readSafely(0);
      WrappedBlockData[] readData = packet.getBlockDataArrays().readSafely(0);
      if (readPositions == null || readData == null
          || readPositions.length != positions.length || readData.length != data.length) {
        return false;
      }
    }

    // filters=false：合并包由本模块自行发出，不能再触发（本插件的）监听器，否则会自我递归
    protocolManager.sendServerPacket(player, packet, false);
    return true;
  }

  /** 解析出站变更包为统一条目；任何异常或结构不符都返回 null（调用方放行原包）。 */
  private List<Update<WrappedBlockData>> readUpdates(PacketType type, PacketContainer packet) {
    try {
      List<Update<WrappedBlockData>> updates = new ArrayList<>();
      if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        BlockPosition position = packet.getBlockPositionModifier().read(0);
        WrappedBlockData data = packet.getBlockData().read(0);
        if (position == null || data == null) {
          return null;
        }
        updates.add(new Update<>(position.getX(), position.getY(), position.getZ(), data));
        return updates;
      }

      BlockPosition section = packet.getSectionPositions().read(0);
      short[] positions = packet.getShortArrays().read(0);
      WrappedBlockData[] data = packet.getBlockDataArrays().read(0);
      if (section == null || positions == null || data == null || positions.length != data.length) {
        return null;
      }
      int baseX = section.getX() << 4;
      int baseY = section.getY() << 4;
      int baseZ = section.getZ() << 4;
      for (int i = 0; i < positions.length; i++) {
        short packed = positions[i];
        int x = baseX + (packed >> 8 & 15);
        int z = baseZ + (packed >> 4 & 15);
        int y = baseY + (packed & 15);
        updates.add(new Update<>(x, y, z, data[i]));
      }
      return updates;
    } catch (Throwable throwable) {
      logThrottled(throwable);
      return null;
    }
  }

  private static Map<SectionKey, List<Update<WrappedBlockData>>> groupBySection(
      List<Update<WrappedBlockData>> cluster) {
    Map<SectionKey, List<Update<WrappedBlockData>>> sections = new LinkedHashMap<>();
    for (Update<WrappedBlockData> update : cluster) {
      SectionKey key = new SectionKey(update.x() >> 4, update.y() >> 4, update.z() >> 4);
      sections.computeIfAbsent(key, ignored -> new ArrayList<>()).add(update);
    }
    return sections;
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "方块变更合并失败，已按原包放行", throwable);
    }
  }
}