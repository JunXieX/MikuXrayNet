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
import java.lang.ref.WeakReference;
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
import net.mikumc.mikuxraynet.occlusion.SectionKey;
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
    /**
     * 解析是否已有<b>定论</b>（成功，或已判定本进程内不可用）。
     *
     * <p><b>为什么需要这个哨兵</b>：{@link #idAccessor} 只在成功时被赋值，若解析失败（目标版本把
     * {@code Block#getId} 改名/移走，或 {@code publicLookup().unreflect} 被拒），它永远是 {@code null}，
     * 于是<b>每一个</b>方块变更坐标都会重新跑一遍 {@link #findGetIdMethod}（{@code Class#getMethods()}
     * 会分配并遍历上百个 {@code Method}）——一个 4096 坐标的 {@code MULTI_BLOCK_CHANGE} 就是数十万次
     * 方法名比较 + 大量临时分配，且全部落在 netty 封包线程上。这与类注释承诺的「只在首次使用时解析
     * 一次、失败后永久返回 -1」直接矛盾，也是「一次前置/服务端不兼容更新 → 网络线程打满」的放大路径。
     * 定论之后一律直接返回 -1，走 Material 级回落。
     */
    private volatile boolean resolutionSettled;

    NmsStateIdResolver(Logger logger) {
      this.logger = logger;
    }

    @Override
    public int resolveStateId(Object nmsHandle) {
      if (nmsHandle == null) {
        return -1;
      }
      MethodHandle accessor = idAccessor;
      if (accessor == null) {
        if (resolutionSettled) {
          // 已判定不可用：永久返回 -1，绝不在网络线程上重跑反射扫描
          return -1;
        }
        accessor = resolveAccessor(nmsHandle);
        if (accessor == null) {
          return -1;
        }
      }
      try {
        return (int) accessor.invokeExact(nmsHandle);
      } catch (Throwable throwable) {
        // invokeExact 声明 throws Throwable（版本不匹配 / 参数类型不符都会走到这里）：
        // 一旦失败就永久定论，避免每个坐标都再抛一次异常。
        settleFailure(throwable);
        return -1;
      }
    }

    /**
     * 同步解析一次并缓存句柄（只在首次使用时进入，之后的调用走 {@link #idAccessor} 快路径）。
     *
     * <p>解析失败同样置 {@link #resolutionSettled}，保证 {@link #findGetIdMethod} 在本进程内最多执行一次。
     *
     * @return 可用的句柄；不可用返回 {@code null}
     */
    private synchronized MethodHandle resolveAccessor(Object nmsHandle) {
      if (resolutionSettled) {
        return null;
      }
      try {
        Method method = findGetIdMethod(nmsHandle.getClass());
        if (method == null) {
          resolutionSettled = true;
          warnOnce(null);
          return null;
        }
        // Lookup#unreflect 声明<b>受检异常</b> IllegalAccessException；这里与 invokeExact（声明 throws
        // Throwable）一样必须落在 try 内自吞，绝不上抛——失败语义见 warnOnce（只打一次 WARN、此后永久
        // 返回 -1 = 回落 Material 级）。
        MethodHandle accessor = MethodHandles.publicLookup().unreflect(method)
            .asType(MethodType.methodType(int.class, Object.class));
        this.idAccessor = accessor;
        this.resolutionSettled = true;
        return accessor;
      } catch (Throwable throwable) {
        // 覆盖：unreflect 的 IllegalAccessException 与 NMS 类查找的运行时异常
        resolutionSettled = true;
        warnOnce(throwable);
        return null;
      }
    }

    /** 调用期失败（{@code invokeExact}）：清掉句柄并永久定论，此后一律回落 Material 级。 */
    private void settleFailure(Throwable throwable) {
      synchronized (this) {
        this.idAccessor = null;
        this.resolutionSettled = true;
      }
      warnOnce(throwable);
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
  /** 最近已应用的方块变更包（按包实例身份去重，见 {@link RecentPackets}）。 */
  private final RecentPackets recentPackets = new RecentPackets();

  private volatile AsyncListenerHandler handler;

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
    plugin.getLogger().fine("遮挡位图方块补丁已启用：出站方块变更即时同步进遮挡位图");
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
      // 同一份方块变更包会按「每个追踪玩家」各回调一次（服务端只构造一个 NMS 包实例广播）：
      // 补丁是「世界几何真值」，应用一次即可；重复应用纯属 N 倍开销（分配 + 全局写锁竞争）。
      // 注意「先查、应用成功后再登记」：若首个接收者解析时抛异常，不得让后续接收者一并被跳过。
      Object handle = event.getPacket().getHandle();
      if (recentPackets.contains(handle, worldName)) {
        return;
      }
      PacketType type = event.getPacketType();
      if (type == PacketType.Play.Server.BLOCK_CHANGE) {
        patchSingle(store, event.getPacket());
        recentPackets.record(handle, worldName);
      } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        patchMulti(store, event.getPacket());
        recentPackets.record(handle, worldName);
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
    int cells = positions.length;
    // 复用线程本地缓冲：一个 4096 格的变更包原来每包要新建约 100 KB 的临时数组（坐标 + 判定 + 批量写入），
    // 而同一份包还会按接收者重复回调；封包回调只落在少数几个线程上，按线程复用即可把分配降到零。
    PatchBuffer buffer = PATCH_BUFFER.get();
    buffer.ensureCells(cells);
    int[] coords = buffer.coords;
    unpackMultiCoords(section.getX() << 4, section.getY() << 4, section.getZ() << 4, positions, coords);
    byte[] verdicts = buffer.verdicts;
    for (int i = 0; i < cells; i++) {
      // 单个坏元素（缺失 / 解析异常）只让该格记 UNRESOLVED，继续解析后面的坐标：
      // 一个槽位读不出来不该让同包其余坐标一起漏补丁。
      verdicts[i] = resolveOrUnresolved(data[i]);
    }
    applyPatches(store, coords, verdicts, cells, stats);
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
    if (data == null) {
      // 包内该槽位没有方块数据：按「拿不到口径」处理。若放任下面的 data.getHandle() 抛 NPE，整包剩余
      // 坐标会一起漏补丁，与「单格失败不影响其它格」的承诺相矛盾。
      return UNRESOLVED;
    }
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
   * 解析单格的判定；元素缺失（{@code null}）或解析过程抛出异常时，只让<b>这一格</b>记
   * {@link #UNRESOLVED}（调用方据此把该格置为不遮挡并计入失败数），其余坐标照常处理。
   *
   * <p>补这一层是因为 {@link WrappedBlockData} 的句柄读取会抛异常：若异常从解析循环里逃出去，
   * 整包剩下的坐标都会漏补丁——那与「单格失败不影响其它格」的承诺直接矛盾。
   */
  private byte resolveOrUnresolved(WrappedBlockData data) {
    try {
      return resolve(data);
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
   * <p>先按判定把坐标摊进批量数组，再<b>整批</b>交给 {@link OcclusionStore#setBlocksOccluding}：
   * 一个 {@code MULTI_BLOCK_CHANGE} 最多 4096 个坐标，逐格写入就是 4096 次全局写锁，而同一把锁还被
   * 解码建表与区块卸载持有，封包线程会与之反复竞争。整批只取一次锁后，这条路径的临界区只剩一次
   * 连续写入（见 {@link OcclusionStore#setBlocksOccluding} 的 javadoc）。
   *
   * <p>三态语义见 {@link #CLEAR} / {@link #OCCLUDING} / {@link #UNRESOLVED}；{@code UNRESOLVED} 的格
   * 被置为<b>不遮挡</b>（清掉该格可能陈旧的「遮挡」位），<b>只动这一格、不摘整段</b>——理由见类注释
   * （整段摘除会放大实体侧的「少藏」面，并给显形侧制造 16³ 死区）。
   *
   * <p><b>计数口径（按「归因」而不是按底层的写入动作）</b>：
   * <ul>
   *   <li>{@link OcclusionStats#patched} 只累加「有明确口径（遮挡 / 不遮挡）、坐标合法」的格——
   *       无论该格是否本来就已经是目标状态（那只是无需再写，位图确实已与变更一致）；</li>
   *   <li>{@link OcclusionStats#patchFailures} 累加「拿不到口径（UNRESOLVED）」与「坐标越界被拒」的格，
   *       每格只计一次。</li>
   * </ul>
   * 旧实现用 {@code 写入数 − 未解析且合法数} 反推，但底层的「写入数」包含「状态已一致、只是提前返回」的格，
   * 两个口径相减后既会虚计也会在越界格上错位；现在直接按判定归因，两个数都有确定含义。
   *
   * @param coords    {@code x,y,z} 连续存放的坐标三元组
   * @param verdicts  每格一个三态判定，长度必须与 {@code coords.length / 3} 相等（否则整体跳过）
   */
  static void applyPatches(OcclusionStore store, int[] coords, byte[] verdicts, OcclusionStats stats) {
    applyPatches(store, coords, verdicts, verdicts == null ? 0 : verdicts.length, stats);
  }

  /**
   * 应用补丁的核心：{@code cells} 显式给出本批有效格数（数组可能来自线程本地复用、长度大于有效格数）。
   */
  private static void applyPatches(OcclusionStore store, int[] coords, byte[] verdicts, int cells,
      OcclusionStats stats) {
    if (store == null || coords == null || verdicts == null || cells <= 0
        || coords.length < cells * 3 || verdicts.length < cells) {
      return;
    }
    PatchBuffer buffer = PATCH_BUFFER.get();
    buffer.ensureCells(cells);
    int[] xs = buffer.xs;
    int[] ys = buffer.ys;
    int[] zs = buffer.zs;
    boolean[] occluding = buffer.occluding;
    int resolved = 0;
    int unresolved = 0;
    for (int i = 0; i < cells; i++) {
      int x = coords[i * 3];
      int y = coords[i * 3 + 1];
      int z = coords[i * 3 + 2];
      byte verdict = verdicts[i];
      xs[i] = x;
      ys[i] = y;
      zs[i] = z;
      occluding[i] = verdict == OCCLUDING;
      if (!isPatchable(x, y, z)) {
        continue;
      }
      if (verdict == OCCLUDING || verdict == CLEAR) {
        resolved++;
      } else {
        unresolved++;
      }
    }
    // 整批一次拿锁（见 OcclusionStore#setBlocksOccluding 的说明）；返回值 = 坐标合法、确实被受理的格数。
    int applied = store.setBlocksOccluding(xs, ys, zs, occluding, cells);
    stats.patched.add(resolved);
    stats.patchFailures.add(unresolved + (cells - applied));
  }

  /**
   * 补丁热路径的线程本地缓冲：坐标 / 判定 / 批量写入共 6 个数组，按需扩容后复用。
   *
   * <p>为什么要复用：一个 4096 格的 {@code MULTI_BLOCK_CHANGE} 每包要新建约 100 KB 临时数组，
   * 而封包回调只落在少数几个线程上（异步处理器线程池），按线程复用即可把这条路径的分配降为零。
   */
  private static final ThreadLocal<PatchBuffer> PATCH_BUFFER =
      ThreadLocal.withInitial(PatchBuffer::new);

  private static final class PatchBuffer {
    private int[] coords = new int[48];
    private byte[] verdicts = new byte[16];
    private int[] xs = new int[16];
    private int[] ys = new int[16];
    private int[] zs = new int[16];
    private boolean[] occluding = new boolean[16];

    /** 保证所有数组都容得下 {@code cells} 格（只增不减；摊还成本可忽略）。 */
    void ensureCells(int cells) {
      if (verdicts.length < cells) {
        int grown = Math.max(cells, verdicts.length << 1);
        verdicts = new byte[grown];
        xs = new int[grown];
        ys = new int[grown];
        zs = new int[grown];
        occluding = new boolean[grown];
      }
      int coordLength = cells * 3;
      if (coords.length < coordLength) {
        coords = new int[Math.max(coordLength, coords.length << 1)];
      }
    }
  }

  /**
   * 最近已应用的方块变更包（按 NMS 包实例身份去重的有界环形窗口）。
   *
   * <p><b>为什么要去重</b>：服务端对一个方块变更只构造<b>一个</b> NMS 包实例，然后对每个追踪玩家各发一次；
   * 本监听器按「发送事件」回调，于是同一份坐标会被完整解析并写位图 N 次（N = 视野内玩家数）——
   * 全是重复劳动，且每次都在封包线程上做临时分配、并持有 {@link OcclusionStore} 的全局写锁。
   *
   * <p><b>为什么用包实例身份而不是内容哈希</b>：实例身份判等是常数级且零误判（内容哈希要遍历坐标）；
   * 广播给多个玩家时 NMS 包地址必然相同。窗口有界（{@value #CAPACITY} 条引用）：广播人数超过窗口容量时，
   * 只有少数玩家会重复应用一次（幂等、无害），绝不会漏补丁。
   *
   * <p><b>为什么槽位存弱引用</b>：一个 MULTI_BLOCK_CHANGE 包最多携带 4096 格（坐标 {@code short[]} +
   * 状态数组，可达数十 KB），而它只是服务端广播用的临时对象。强引用窗口会把最多 {@value #CAPACITY} 个
   * 这样的包人为钉在堆上；弱引用不阻止回收，被回收的槽位读出来就是 {@code null}，按「未见过」处理——
   * 代价只是同一包的后续接收者可能再应用一次补丁（幂等，只多花一次位图写锁），方向安全。
   *
   * <p><b>为什么分「查」与「登记」两步</b>：只有真正应用成功才登记。若在应用之前就登记，首个接收者
   * 解析时抛一次异常（瞬时/罕见）就会让同一包的其余接收者全部被跳过，那格补丁从此缺失；
   * 应用成功后再登记则最坏只是并发多应用一次（幂等）。
   *
   * <p><b>线程安全</b>：回调可能落在多个异步处理线程上，用本对象监视器串行化——临界区只有 64 条引用比较，
   * 在百纳秒级，不会成为瓶颈。窗口只持有方块变更包的<b>弱引用</b>（也不涉及玩家/区块对象）。
   */
  static final class RecentPackets {
    static final int CAPACITY = 64;

    /** 槽位存包实例的弱引用（见类注释的取舍说明）；空槽为 {@code null}。 */
    private final WeakReference<?>[] handles = new WeakReference<?>[CAPACITY];
    private final String[] worlds = new String[CAPACITY];
    private int cursor;

    /**
     * @param handle    包实例（{@code PacketContainer#getHandle()}）；{@code null} 表示无法判等，按未见过处理
     * @param worldName 包所属世界名（同一实例若被不同世界复用，必须分别应用；正常不会发生）
     * @return {@code true} = 已应用过同一包，本次应跳过
     */
    synchronized boolean contains(Object handle, String worldName) {
      if (handle == null || worldName == null) {
        return false;
      }
      for (int i = 0; i < CAPACITY; i++) {
        WeakReference<?> ref = handles[i];
        // ref == null：空槽；ref.get() == null：该包已被 GC 回收（等价于「未见过」，重复应用幂等）
        if (ref != null && ref.get() == handle && worldName.equals(worlds[i])) {
          return true;
        }
      }
      return false;
    }

    /** 登记「该包已应用」；{@code null} 参数直接忽略（无法判等的包不做去重）。 */
    synchronized void record(Object handle, String worldName) {
      if (handle == null || worldName == null) {
        return;
      }
      handles[cursor] = new WeakReference<>(handle);
      worlds[cursor] = worldName;
      cursor = (cursor + 1) % CAPACITY;
    }
  }

  /**
   * 该格坐标能否落进遮挡位图：判据与 {@link SectionKey#packSection} 完全一致（越界的 section 被拒）。
   *
   * <p><b>只用于统计归因</b>，不用于过滤写入——写入仍整批交给 {@link OcclusionStore}，由它按
   * {@link SectionKey#packSection} 权威校验并跳过越界格。因此这里即便与权威判据出现偏差，也只会让
   * 计数略有出入，不会漏写或错写任何一格：「坐标是否合法」始终只有 SectionKey 一处实现。
   */
  private static boolean isPatchable(int x, int y, int z) {
    try {
      SectionKey.packSection(x >> 4, y >> 4, z >> 4);
      return true;
    } catch (IllegalArgumentException outOfRange) {
      return false;
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
