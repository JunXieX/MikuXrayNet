package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.mikumc.mikuxraynet.occlusion.OcclusionStats;
import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.util.Constants;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 出站方块变更 → 遮挡位图<b>单格补丁</b>：把服务端下发给客户端的方块变更同步进
 * {@link OcclusionStores}，使遮挡位图不会因挖掘 / 爆炸 / 插件编辑而留下陈旧的「遮挡」位。
 *
 * <p><b>为什么必须补丁（安全方向问题，不是优化）</b>：位图只在「区块被解码」或「缓存命中恢复」时
 * <b>整列重建</b>。玩家把挡在实体前的墙挖开后，服务端只下发一条方块变更包、<b>不会</b>重发区块，
 * 于是位图里那一格仍是「遮挡」——异步射线据此继续判「完全遮挡」，把<b>本已可见</b>的实体一直藏着。
 * 这属于「该显示却多藏」的缺陷（本项目明确列为不可接受），因此补丁是正确性要求。
 *
 * <p><b>为什么以出站变更包为数据源</b>：客户端看到的世界就是这些包的内容，因此它天然覆盖
 * 「服务端真正改了并让客户端知道」的全部来源（玩家挖掘 / 放置、爆炸、其它插件与命令方的编辑），
 * 而 Bukkit 的事件族（Break/Place/Explode/…）覆盖不全（例如 {@code /setblock} 与多数插件编辑）。
 *
 * <p><b>为什么走异步通道</b>：与 {@link BlockChangeRevealListener} 同理——同步注册会把该包类型标记为
 * 「需要主线程」，本插件自己发出的合并包 / 显形包会被推迟一 tick 并改到主线程发送；异步注册没有这个
 * 副作用。本类<b>不取消、不改包</b>，纯观察，因此与合并模块的时间窗互不干扰。
 *
 * <p><b>线程纪律</b>：回调运行在封包（netty）线程上，只做「读包内坐标 + 读新方块口径 + 写内存位图」，
 * <b>不读 World 的方块</b>。写入走 {@link OcclusionStore} 的写锁（与解码 / 缓存命中路径共用），
 * 读取走 SWMR 乐观读——两侧都已线程安全，无需额外同步。
 *
 * <p><b>失败一律 fail-open（宁可少藏，绝不误藏），且只降该格</b>：拿不到新方块口径的那一格被置为
 * <b>「不遮挡」</b>（清掉该格可能陈旧的「遮挡」位），其余格保持原样。为什么<b>不整段摘除该 section</b>：
 * <ol>
 *   <li>整段摘除一次丢掉 4096 格（绝大多数仍正确）的遮挡信息，实体侧「少藏」面从 1 格放大到整段；</li>
 *   <li>邻近显形的位图判定把「缺数据」按<b>全遮挡</b>处理（见 {@code BitmapVisibility}）：摘除等于在
 *       玩家身边制造一个 16³ 的死区，本应显形的方块会被憋到区块重发为止。</li>
 * </ol>
 * 单格置零在<b>两个方向上都更保守</b>：若该格真实是遮挡方块，最坏只少算一格遮挡。
 * 失败格数计入 {@link OcclusionStats#patchFailures}，持续偏高即说明补丁链路读不出发送方方块数据。
 *
 * <p><b>口径与建表路径完全一致</b>：本类只能用 ProtocolLib 包装后的 {@code Material} 判遮挡
 * （{@code Material#isOccluding()}），而位图建表用的<b>实体遮挡口径表</b>正是同一来源
 * （见 {@code EntityOcclusionTable}），同步回退路径的 {@code World#rayTraceBlocks} 亦然——
 * 建表 / 补丁 / 同步回退三者同口径，不会出现「同一格三种结论」。
 * 反矿透伪装那条链路用的是另一套（PE 状态语义、刻意偏多判），二者互不影响。
 */
public final class OcclusionPatchListener extends PacketAdapter {

  /** 补丁判定的三态：不遮挡 / 遮挡 / 拿不到口径（→ 降级该 section 为未知）。 */
  static final byte CLEAR = 0;
  static final byte OCCLUDING = 1;
  static final byte UNRESOLVED = -1;

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final OcclusionStores stores;
  private final OcclusionStats stats;
  private final AtomicInteger errorCounter = new AtomicInteger();

  private AsyncListenerHandler handler;

  public OcclusionPatchListener(Plugin plugin, ProtocolManager protocolManager, OcclusionStores stores,
      OcclusionStats stats) {
    super(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.stores = stores;
    this.stats = stats == null ? new OcclusionStats() : stats;
  }

  /** 注册异步监听器（真异步扣包）。 */
  public void start() {
    handler = protocolManager.getAsynchronousManager().registerAsyncHandler(this);
    handler.start();
    plugin.getLogger().info("遮挡位图方块补丁已启用：出站方块变更即时同步进遮挡位图");
  }

  /** 注销监听器；可重复调用，异常安全。 */
  public void stop() {
    AsyncListenerHandler current = handler;
    handler = null;
    if (current == null) {
      return;
    }
    try {
      protocolManager.getAsynchronousManager().unregisterAsyncHandler(current);
    } catch (Throwable throwable) {
      plugin.getLogger().log(Level.WARNING, "注销遮挡位图方块补丁监听器时出现异常（通常可忽略）", throwable);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    Player player = event.getPlayer();
    if (player == null) {
      return;
    }
    try {
      String worldName = worldNameOf(player);
      if (worldName == null) {
        return;
      }
      // 只补「已经建过表」的世界：未建表说明该世界没有位图数据（例如反矿透未覆盖它），
      // 凭空建一张只含变更格的稀疏表没有意义（射线仍按缺数据 fail-open），还会白占内存。
      OcclusionStore store = stores.lookup(worldName);
      if (store == null) {
        return;
      }
      PacketType type = event.getPacketType();
      if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        patchSingle(store, event.getPacket());
      } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        patchMulti(store, event.getPacket());
      }
    } catch (Throwable throwable) {
      // fail-open：解析失败只跳过本次补丁（该段位图可能短暂陈旧，由区块重载自愈），绝不改包
      stats.patchFailures.increment();
      logThrottled(throwable);
    }
  }

  /** 单方块变更：坐标即绝对方块坐标。 */
  private void patchSingle(OcclusionStore store, PacketContainer packet) {
    BlockPosition position = packet.getBlockPositionModifier().readSafely(0);
    WrappedBlockData data = packet.getBlockData().readSafely(0);
    if (position == null || data == null) {
      return;
    }
    applyPatches(store, new int[] {position.getX(), position.getY(), position.getZ()},
        new byte[] {resolve(data)}, stats);
  }

  /**
   * 多方块变更：section 坐标 + 区块内相对坐标（与本插件合并包一致的位置编码）。
   *
   * <p>打包方式与协议一致：{@code (x & 15) << 8 | (z & 15) << 4 | (y & 15)}（见
   * {@code BlockChangeMerger#sendSection}）。
   */
  private void patchMulti(OcclusionStore store, PacketContainer packet) {
    BlockPosition section = packet.getSectionPositions().readSafely(0);
    short[] positions = packet.getShortArrays().readSafely(0);
    WrappedBlockData[] data = packet.getBlockDataArrays().readSafely(0);
    if (section == null || positions == null || data == null || positions.length != data.length) {
      return;
    }
    int[] coords = new int[positions.length * 3];
    unpackMultiCoords(section.getX() << 4, section.getY() << 4, section.getZ() << 4, positions, coords);
    byte[] verdicts = new byte[positions.length];
    for (int i = 0; i < positions.length; i++) {
      verdicts[i] = resolve(data[i]);
    }
    applyPatches(store, coords, verdicts, stats);
  }

  /**
   * 把 MULTI_BLOCK_CHANGE 的「section 基准坐标 + 打包相对坐标」展开为绝对方块坐标三元组
   * （包可见，供离线单测）。
   *
   * <p>打包方式与协议一致：{@code (x & 15) << 8 | (z & 15) << 4 | (y & 15)}（见
   * {@code BlockChangeMerger#sendSection}）。
   *
   * @param coords 输出数组，长度必须为 {@code positions.length * 3}
   */
  static void unpackMultiCoords(int baseX, int baseY, int baseZ, short[] positions, int[] coords) {
    for (int i = 0; i < positions.length; i++) {
      short packed = positions[i];
      coords[i * 3] = baseX + (packed >> 8 & 15);
      coords[i * 3 + 1] = baseY + (packed & 15);
      coords[i * 3 + 2] = baseZ + (packed >> 4 & 15);
    }
  }

  /**
   * 把一条出站方块变更解析为「是否遮挡」三态：
   * {@link #OCCLUDING} / {@link #CLEAR}，读不到方块数据时 {@link #UNRESOLVED}（调用方把该格置为不遮挡）。
   */
  private static byte resolve(WrappedBlockData data) {
    try {
      return data.getType().isOccluding() ? OCCLUDING : CLEAR;
    } catch (Throwable throwable) {
      return UNRESOLVED;
    }
  }

  /**
   * 纯函数部分（包可见，供离线单测）：把「坐标三元组 + 三态判定」应用到某个世界的遮挡位图。
   *
   * <p>三态语义见 {@link #CLEAR} / {@link #OCCLUDING} / {@link #UNRESOLVED}；{@code UNRESOLVED} 的格
   * 被置为<b>不遮挡</b>（清掉该格可能陈旧的「遮挡」位），<b>只动这一格、不摘整段</b>——理由见类注释
   * （整段摘除会放大实体侧的「少藏」面，并给显形侧制造 16³ 死区）。
   *
   * @param coords    {@code x,y,z} 连续存放的坐标三元组
   * @param verdicts  每格一个三态判定，长度必须与 {@code coords.length / 3} 相等（否则整体跳过）
   */
  static void applyPatches(OcclusionStore store, int[] coords, byte[] verdicts, OcclusionStats stats) {
    if (store == null || coords == null || verdicts == null || coords.length != verdicts.length * 3) {
      return;
    }
    for (int i = 0; i < verdicts.length; i++) {
      int x = coords[i * 3];
      int y = coords[i * 3 + 1];
      int z = coords[i * 3 + 2];
      try {
        switch (verdicts[i]) {
          case OCCLUDING -> {
            store.setBlockOccluding(x, y, z, true);
            stats.patched.increment();
          }
          case CLEAR -> {
            store.setBlockOccluding(x, y, z, false);
            stats.patched.increment();
          }
          default -> {
            // 拿不到新方块口径：把该格置为「不遮挡」（绝不留下陈旧「遮挡」位），但保留该段其余格的数据——
            // 整段摘除会让实体侧一次少藏 4096 格，并在显形位图判定下变成一整段「死区」（见类注释）。
            store.setBlockOccluding(x, y, z, false);
            stats.patchFailures.increment();
          }
        }
      } catch (Throwable throwable) {
        // 单格失败不影响其它格（例如坐标越界被 SectionKey 拒绝）
        stats.patchFailures.increment();
      }
    }
  }

  /** 世界名（封包线程读取一个不可变字符串，不触碰任何世界对象内容）。 */
  private static String worldNameOf(Player player) {
    try {
      World world = player.getWorld();
      return world == null ? null : world.getName();
    } catch (Throwable throwable) {
      return null;
    }
  }

  private void logThrottled(Throwable throwable) {
    if (errorCounter.incrementAndGet() <= Constants.MAX_ERROR_LOGS) {
      plugin.getLogger().log(Level.WARNING, "遮挡位图方块补丁解析失败，已按「不改位图」处理", throwable);
    }
  }
}
