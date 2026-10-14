package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.bootstrap.BukkitOcclusion;
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
 * <p><b>口径（逐状态，与位图一致）</b>：本类优先走<b>状态级</b>判定——从 {@link WrappedBlockData} 公开的
 * NMS 句柄（{@code getHandle()}，即 NMS 方块状态对象）反射 NMS {@code Block.getId(BlockState)} 得到
 * <b>全局方块状态 id</b>，再用该 id 查<b>实体遮挡口径表</b>（{@code BlockStateRegistry#isEntityOccluding}
 * → {@code EntityOcclusionTable}，下标即状态 id）。该全局 id 与 PacketEvents 全局 stateId 是<b>同一 id
 * 空间</b>：服务端正是用 {@code Block.getId} 把状态写进方块变更包，而本插件解码出的网络状态 id 也直接喂给
 * 同一张表（逐条证据见 {@link NmsStateIdResolver}）。因此位图与补丁对同一状态结论逐状态一致，barrier 例外
 * 同样生效。
 *
 * <p><b>回落（fail-open，且必计数）</b>：id 解析不可用（NMS 桥接初始化失败等）时回落到旧的
 * {@link BukkitOcclusion#entityOccludingByMaterial}（<b>Material 级近似</b>，取默认状态，无法区分
 * 「同方块不同状态」如单/双台阶），该回落逐格计入 {@code OcclusionStats#stateIdFallbacks} 并在
 * {@code /mxnet status} 可见（见 {@code Diagnostics}），绝不静默。确实拿不到任何口径时该格降级为
 * 「不遮挡」（{@link #UNRESOLVED}），不会留下陈旧「遮挡」位误藏。反矿透伪装那条链路与实体位图同源
 * （同一逐状态真值），二者互不影响。
 */
public final class OcclusionPatchListener extends PacketAdapter {

  /** 补丁判定的三态：不遮挡 / 遮挡 / 拿不到口径（→ 该格置为不遮挡，只动这一格）。 */
  static final byte CLEAR = 0;
  static final byte OCCLUDING = 1;
  static final byte UNRESOLVED = -1;

  /**
   * 状态 id 解析：把 ProtocolLib 的 NMS 方块状态句柄映射为<b>全局方块状态 id</b>。
   *
   * <p>生产实现见 {@link NmsStateIdResolver}；离线单测可注入桩（见
   * {@link OcclusionPatchListener#resolveStateLevel}）。
   *
   * @return 全局状态 id；{@code < 0} = 无法解析（调用方回落 Material 级）
   */
  @FunctionalInterface
  interface StateIdResolver {
    int resolveStateId(Object nmsHandle);
  }

  /**
   * 生产用状态 id 解析器：反射 NMS {@code Block.getId(BlockState)}（= 服务端写入网络包所用的全局方块
   * 状态 id）得到全局 id。
   *
   * <p><b>为什么取 {@code Block.getId}</b>：ProtocolLib 的 {@link WrappedBlockData#getHandle()} 是 NMS 方块
   * 状态对象（{@code AbstractWrapper.handle}，其类型由 ProtocolLib 内部的 {@code MinecraftReflection
   * #getIBlockDataClass()} 给出）。已用 {@code javap} 核实（Paper 26.3 服务端 jar）：
   * <ul>
   *   <li>{@code net.minecraft.world.level.block.Block} 有 {@code public static int getId(BlockState)}，
   *       其实现体就是 {@code Block.BLOCK_STATE_REGISTRY.getId(state)}（{@code BLOCK_STATE_REGISTRY} 为
   *       {@code public static final IdMapper<BlockState>}）；</li>
   *   <li>服务端正是用它把状态写进包：{@code ClientboundSectionBlocksUpdatePacket}（MULTI_BLOCK_CHANGE）
   *       写 {@code Block.getId(BlockState)}；{@code ClientboundBlockUpdatePacket}（BLOCK_CHANGE）经
   *       {@code ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)} 序列化；
   *       {@code GlobalPalette.idFor} 亦用 {@code IdMap.getId}。</li>
   * </ul>
   * 因此 {@code Block.getId(state)} 与「网络包里的方块状态 id」逐位一致。而 PacketEvents 的全局 stateId
   * （{@code WrappedBlockState#getGlobalId()}，即本插件 {@code BlockStateRegistry} / {@code EntityOcclusionTable}
   * 的下标）同样是网络状态 id——本插件的区块编解码（{@code ChunkSection}）从网络缓冲读出的状态 id 直接喂给
   * {@code isEntityOccluding}/{@code captureOcclusion}，这是<b>已投产的既有不变式</b>。三者同属一个 id 空间。
   *
   * <p><b>只在首次使用时解析一次</b>：用 {@link MethodHandles#publicLookup()} 在 NMS {@code Block} 上找
   * 稳定的 mojang 映射方法 {@code getId}（静态、返回 int、单参数、参数类型可接受句柄的运行时类型），
   * 并适配成 {@code (Object)int} 的 {@link MethodHandle}（{@code invokeExact} 零装箱）。解析失败（版本不匹配 /
   * 方法名变化）只记一次中文 WARN 并永久返回 -1 → 调用方回落 Material 级，绝不误藏/误改位图。
   */
  private static final class NmsStateIdResolver implements StateIdResolver {

    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();
    /** 懒解析并缓存下来的 {@code (Object)int} 句柄；未解析成功前为 {@code null}。 */
    private volatile MethodHandle idAccessor;

    NmsStateIdResolver(Logger logger) {
      this.logger = logger;
    }

    @Override
    public int resolveStateId(Object nmsHandle) {
      if (nmsHandle == null) {
        return -1;
      }
      try {
        MethodHandle accessor = idAccessor;
        if (accessor == null) {
          Method method = findGetIdMethod(nmsHandle.getClass());
          if (method == null) {
            warnOnce(null);
            return -1;
          }
          // Lookup#unreflect 声明<b>受检异常</b> IllegalAccessException；这里与 invokeExact（声明 throws
          // Throwable）一样必须落在 try 内自吞，绝不上抛——失败语义见 warnOnce（只打一次 WARN、此后永久
          // 返回 -1 = 回落 Material 级）。
          accessor = MethodHandles.publicLookup().unreflect(method)
              .asType(MethodType.methodType(int.class, Object.class));
          idAccessor = accessor;
        }
        return (int) accessor.invokeExact(nmsHandle);
      } catch (Throwable throwable) {
        // 覆盖：unreflect 的 IllegalAccessException、invokeExact 的 Throwable、以及 NMS 类查找的运行时异常
        warnOnce(throwable);
        return -1;
      }
    }

    /**
     * 在 NMS {@code Block} 类上找静态 {@code getId(state)} 方法对象。
     *
     * <p><b>刻意只做「查找」不碰反射调用</b>：{@code Class#getMethods()} / {@code Method#getName} 等
     * <b>不声明受检异常</b>，因此本方法无受检异常；真正会抛 {@code IllegalAccessException} 的
     * {@code Lookup#unreflect} 留在调用方的 try 内（见 {@link #resolveStateId}）。
     *
     * @return 找到的方法对象；找不到返回 {@code null}
     */
    private static Method findGetIdMethod(Class<?> stateClass) {
      Class<?> blockClass = MinecraftReflection.getBlockClass();
      for (Method method : blockClass.getMethods()) {
        if (method.getName().equals("getId")
            && Modifier.isStatic(method.getModifiers())
            && method.getReturnType() == int.class
            && method.getParameterCount() == 1
            && method.getParameterTypes()[0].isAssignableFrom(stateClass)) {
          return method;
        }
      }
      return null;
    }

    private void warnOnce(Throwable throwable) {
      if (warned.compareAndSet(false, true)) {
        Logger target = logger != null ? logger : Logger.getLogger("MikuXrayNet");
        target.log(Level.WARNING, "方块变更补丁无法解析方块状态的全局 id（已回落 Material 级近似；"
            + "该近似无法区分同方块不同状态，见 /mxnet status 的『状态id回落』计数）", throwable);
      }
    }
  }

  private final Plugin plugin;
  private final ProtocolManager protocolManager;
  private final OcclusionStores stores;
  private final OcclusionStats stats;
  /** 状态 id 查询（生产为 {@code BlockStateRegistry#isEntityOccluding}，与位图同一张表）；{@code null} = 未接线，一律回落。 */
  private final IntPredicate stateLookup;
  /** 状态 id 解析器；见 {@link NmsStateIdResolver}。 */
  private final StateIdResolver idResolver;
  private final AtomicInteger errorCounter = new AtomicInteger();

  private AsyncListenerHandler handler;

  public OcclusionPatchListener(Plugin plugin, ProtocolManager protocolManager, OcclusionStores stores,
      OcclusionStats stats, IntPredicate stateLookup) {
    super(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.BLOCK_CHANGE,
        PacketType.Play.Server.MULTI_BLOCK_CHANGE);
    this.plugin = plugin;
    this.protocolManager = protocolManager;
    this.stores = stores;
    this.stats = stats == null ? new OcclusionStats() : stats;
    this.stateLookup = stateLookup;
    this.idResolver = new NmsStateIdResolver(plugin.getLogger());
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
   * {@link #OCCLUDING} / {@link #CLEAR}，读不到任何口径时 {@link #UNRESOLVED}（调用方把该格置为不遮挡）。
   *
   * <p><b>优先状态级</b>：id 可解析时查实体口径表（见类注释），与遮挡位图逐状态一致；否则回落
   * {@link BukkitOcclusion#entityOccludingByMaterial}（Material 级近似）并计数——两种情况都不改包、
   * 只写内存位图。
   */
  private byte resolve(WrappedBlockData data) {
    byte byState = stateLookup == null
        ? UNRESOLVED
        : resolveStateLevel(data.getHandle(), idResolver, stateLookup);
    if (byState != UNRESOLVED) {
      return byState;
    }
    // 状态级不可得（id 解析不可用 / 查表异常 / 未接线）→ 回落 Material 级近似（旧口径），并计数使其可见。
    try {
      byte fallback = BukkitOcclusion.entityOccludingByMaterial(data.getType()) ? OCCLUDING : CLEAR;
      stats.stateIdFallbacks.increment();
      return fallback;
    } catch (Throwable throwable) {
      return UNRESOLVED;
    }
  }

  /**
   * 状态级三态判定（纯函数，供离线单测注入「id 解析桩 + 查表桩」）：id 解析成功即查实体口径表，
   * 与遮挡位图逐状态一致；解析失败 / 查表异常一律 {@link #UNRESOLVED}（由调用方再回落 Material 级）。
   *
   * @param nmsHandle ProtocolLib {@code WrappedBlockData#getHandle()} 给出的 NMS 方块状态句柄
   * @param idResolver 状态 id 解析器（生产为 {@link NmsStateIdResolver}；单测注入桩）
   * @param lookup     状态 id → 实体口径是否遮挡（生产为 {@code BlockStateRegistry#isEntityOccluding}）
   */
  static byte resolveStateLevel(Object nmsHandle, StateIdResolver idResolver,
      IntPredicate lookup) {
    int stateId;
    try {
      stateId = idResolver.resolveStateId(nmsHandle);
    } catch (Throwable throwable) {
      return UNRESOLVED;
    }
    if (stateId < 0) {
      return UNRESOLVED;
    }
    try {
      return lookup.test(stateId) ? OCCLUDING : CLEAR;
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
