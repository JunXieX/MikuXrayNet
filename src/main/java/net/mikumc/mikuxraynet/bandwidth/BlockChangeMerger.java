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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 方块变更合并：把同一玩家在短时间窗内的多条 {@code BLOCK_CHANGE} / {@code MULTI_BLOCK_CHANGE}
 * 按「曼哈顿距离 2 的邻域」聚簇，并按 section 分组重建成更少的合并包。
 *
 * <p><b>同步监听无法延迟放行</b>，因此走 ProtocolLib 异步通道：
 * {@code incrementProcessingDelay()} 登记延迟 → 变更入有界缓冲 → 时间窗到期（或条目超限）时冲刷 →
 * 合并包通过 {@link ProtocolManager#sendServerPacket}（Netty 安全）发出，原包被取消。
 *
 * <p><b>fail-open</b>：读不出字段、构造失败、校验不通过、玩家离线 —— 一律原样放行原包，绝不丢更新。
 * 原包放行通过 {@link AsyncMarker} 的 {@code signalPacketTransmission} 完成，并用一次性闸保证
 * 「恰好放行一次」。
 *
 * <p><b>首包自检</b>：第一次真正发送合并包时会回读刚写入的字段做结构校验；未通过校验前会
 * <em>同时</em>保留原包（重复下发相同方块状态是无害幂等操作），校验通过后才开始取消原包。
 */
public final class BlockChangeMerger extends PacketAdapter {

  private static final String BYPASS_PERMISSION = "mikuxraynet.bypass";
  private static final int MAX_ERROR_LOGS = 3;

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
  private final AtomicInteger errorCounter = new AtomicInteger();

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

  /** 注册异步监听器（真异步扣包）。 */
  public void start() {
    handler = asynchronousManager.registerAsyncHandler(this);
    handler.start();
    plugin.getLogger().info("带宽模块已启用：方块变更合并（时间窗 " + config.mergeWindowMillis()
        + "ms，邻域半径 " + config.mergeRadius() + "，缓冲上限 " + config.maxPendingEntries() + "）");
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
    flusher.shutdownNow();
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    if (!config.merge() || event.isCancelled()) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null || player.hasPermission(BYPASS_PERMISSION)) {
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
      boolean mergeable = held.size() >= 2 && clusters.stream().anyMatch(cluster -> cluster.size() >= 2);
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
    if (errorCounter.incrementAndGet() <= MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "方块变更合并失败，已按原包放行", throwable);
    }
  }
}