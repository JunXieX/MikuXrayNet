package net.mikumc.mikuxraynet.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置热重载编排：把「重读配置 → 失效缓存 → 重启周期任务 → 汇报生效范围」串成一条可测流程。
 *
 * <p><b>为什么单独成类</b>：真正触碰 Bukkit 的部分（配置读取、缓存失效、任务重启）由插件通过
 * {@link Target} 注入，本类只负责顺序编排与结果汇报，因此可在单测中用桩对象断言「缓存失效是否被触发」。
 *
 * <p><b>异常安全（fail-open）</b>：任一环节抛异常都只记入返回结果，绝不让重载失败波及封包主链路；
 * 重载完成后旧数据最多多存活片刻，不会影响正确性。
 */
public final class ReloadCoordinator {

  /** 热重载目标（由插件装配提供；单测用桩对象断言调用）。 */
  public interface Target {

    /**
     * <b>反矿透侧</b>配置指纹（任一影响改写结果的 antixray.yml 配置项变化都会改变它，见
     * {@code AntiXrayConfig#configHash}）。
     */
    int antiXrayFingerprint();

    /**
     * <b>带宽侧</b>配置指纹（bandwidth.yml 各子模块的有效值，见 {@code BandwidthConfig#configHash}）。
     *
     * <p>单独给出这一侧，是为了让「只改了 bandwidth.yml」也能被如实报告——旧实现只比对反矿透侧指纹，
     * 只改带宽配置时会打出「配置指纹未变化」，误导管理员以为改动没被识别。
     */
    int bandwidthFingerprint();

    /** 重新读取全部配置文件（antixray.yml / bandwidth.yml / blacklist.yml）。 */
    void reloadConfiguration();

    /**
     * 使<b>派生缓存</b>（改写缓存 + 邻块快照）立即失效；<b>伪装索引与已显形集合刻意保留</b>。
     *
     * <p><b>为什么保留</b>：热重载不会让客户端重收已加载区块，那些区块在客户端里仍是假矿；清掉索引后
     * 邻近显形就不知道「哪里是假矿」，玩家挖到也永远无法还原（要等区块走远/重登被重发）。详见
     * {@code ProtocolLibAsyncListener#invalidateAll} 的说明。
     */
    void invalidateCaches();

    /** 按新配置重建/重启周期任务（邻近显形、AFK、延迟巡检）。 */
    void restartPeriodicTasks();

    /**
     * 最近一次「重新读取配置」中发生的解析失败说明（{@code null} = 全部配置文件都正常解析）。
     *
     * <p><b>为什么必须上报</b>：配置文件解析失败时 {@code MikuConfig} 会<b>保留上一份有效配置</b>并只在
     * 控制台 WARN——于是游戏内的重载回显会打出「指纹未变化 + 已热生效」，管理员根本不知道新文件被拒绝、
     * 自己刚做的修改并没有生效。默认实现返回 {@code null}，便于测试替身不改代码。
     */
    default String configurationFallbackNote() {
      return null;
    }
  }

  /** 重载会话互斥锁（串行化「读配置 → 失效缓存 → 重建任务」整段流程）。 */
  private final Object reloadLock = new Object();

  /**
   * 插件是否已进入停用流程。
   *
   * <p><b>为什么光有锁不够</b>：{@code runExclusively} 只保证「停用」与「重载」不交错，不保证
   * 「停用之后不再重载」。Folia 上命令跑在区域线程、停用跑在全局线程：一个 reload 在锁上排队，
   * 停用拿到锁、跑完、释放，reload 随即继续执行到 {@code restartPeriodicTasks()} → 在已停用的插件上
   * 新建并启动一整套管线（{@code ThrottlePipeline} 逐模块注册、失败只停该模块，AfkTracker 等模块
   * 不会因为插件停用而必然抛错）。这些监听器/任务挂在已停用实例上永不被回收；若插件随后被重新启用，
   * 同一区块包会被两套改写器处理、旧 EntityCuller 藏起的实体无人恢复 —— 玩家永久隐形实体。
   * 故停用一开始就置位，重载拿到锁后立刻复查并放弃。
   */
  private volatile boolean shuttingDown;

  /**
   * 复位「正在停用」标志（插件重新启用时调用）。
   *
   * <p><b>为什么必须复位</b>：{@link #shuttingDown} 一旦置位就不再自动清除，而插件可能在同一个 JVM 里
   * 被再次启用（例如 {@code /reload confirm}、或其它插件动态启停本插件）。若不复位，重新启用后
   * 所有热重载都会被判定为「插件正在停用」而直接取消——管理员会看到「重载被取消」却找不到原因。
   */
  private void resetShutdownState() {
    shuttingDown = false;
  }

  /**
   * 在「重载会话锁」内执行插件启用流程，并先复位 {@link #shuttingDown}。
   *
   * <p><b>为什么不能只调 {@link #resetShutdownState()}</b>：复位若发生在锁外，一个正排队在
   * {@code synchronized (reloadLock)} 上的 reload 可能在复位之后立刻拿到锁，与启用流程的
   * 「装配带宽管线」并发执行 —— 于是同一次启用里出现两条 {@code ThrottlePipeline}，
   * 其中一条既不在字段里也没人 stop（两套改写器同时生效、旧 EntityCuller 藏起的实体无人恢复）。
   * 把「复位 + 装配」整段放进同一把锁即可消除该窗口。
   *
   * @param action 启用装配流程
   */
  public void runExclusivelyOnEnable(Runnable action) {
    synchronized (reloadLock) {
      // 复用 resetShutdownState()：复位语义只有一处实现，避免「内联一份 + 另留一个公开方法」
      // 两份实现日后漂移；本方法仍是唯一的调用点（公开出去会让外部在锁外复位，重新引入这里论证过的竞态）。
      resetShutdownState();
      action.run();
    }
  }

  /** 本次热重载后已即时生效的配置项。 */
  public static final List<String> APPLIED = List.of(
      "邻近显形（距离/周期/单次上限）",
      "世界黑名单（blacklist.yml，即时豁免/恢复对应世界；对反矿透与带宽优化同时生效）",
      "AFK 降级（判定时长/丢弃距离/包类型）",
      "高延迟降视距（阈值/幅度/下限/巡检周期）",
      "带宽全部子模块（零位移/变更合并/实体剔除）",
      // 异步遮挡的调优键随「停旧管线、建新管线」即时生效（新管线会按新配置重建引擎与线程池）。
      // 注意「开关本身」不在此列，见 RESTART_REQUIRED。
      "实体剔除异步遮挡调优（occlusion-time-budget-ms / occlusion-threads / max-occluding-blocks / "
          + "occlusion-timeout-cycles / occlusion-hysteresis-cycles）",
      "平台判定（advanced.platform，会重新判定并按新分支重建周期任务）",
      // diagnostics.interval-seconds 参与带宽侧指纹，且 restartPeriodicTasks 会重建摘要任务（0 = 关闭）。
      "周期诊断摘要间隔（diagnostics.interval-seconds，0 = 关闭）",
      // neighbors.enabled 是**单向**热生效的：关闭后下一次区块改写即停止抓取（ProtocolLibAsyncListener
      // 的 neighborsNeeded 实时读 live().neighbors().enabled()）；但「启动时为 false」时 NeighborChunkProvider
      // 根本没创建，本进程内改成 true 也不会再抓取——该方向的说明放 RESTART_REQUIRED，避免误报。
      "邻块快照开关关闭（neighbors.enabled=false 立即停止边界抓取）",
      "全部配置文件的读取值");

  /** 需要重启服务端才生效的配置项（在启动期已固化的部分）。 */
  public static final List<String> RESTART_REQUIRED = List.of(
      "隐藏方块表与伪装权重",
      "层状混淆 / 方块实体剔除开关",
      // 只有「关闭」是热生效的（见 APPLIED）：启动时 neighbors.enabled=false 时提供者为 null，
      // 本进程内不再抓取；missing-policy 与 cache-maximum-size 在启动期固化进 NeighborChunkProvider。
      "邻块快照启用 / 缺失策略 / 容量（neighbors.enabled 启动时为 false 则本进程内不再抓取；"
          + "missing-policy / cache-maximum-size）",
      "反矿透工作线程数与队列容量",
      // cache.maximum-bytes 与 cache.maximum-size 同属「启动期固化的改写缓存预算」：RewriteCache
      // 只在 startAntiXray 里构造一次，reload 不重建它，故字节上限改了也只有重启才生效。
      "区块改写超时与改写缓存容量/字节上限/过期（cache.maximum-size / cache.maximum-bytes）",
      // 调色板两键在启动期被固化为编码链路的 PaletteOptions（见 AntiXrayRuntime.startAntiXray），
      // reload 只重启显形/带宽任务、不重建编码器，故这两键改了必须重启（含 width-budget / strict-verify）。
      "调色板压缩 / 位宽预算封顶 / 严格自检（palette.width-budget / strict-verify）",
      // disk-cache 段：除 zstd-sha256（唯一参与指纹的键）外都不进 configHash，且 DiskCacheStore 只在
      // startAntiXray 里构造一次——reload 既不重建它、指纹也不会变，回显若不说清就会被误认为已生效。
      "磁盘缓存全部参数（disk-cache.max-entries / max-file-size-mb / expire-seconds / bucket-cache-size / "
          + "idle-close-seconds / maintenance-interval-seconds / compact-per-pass / queue-capacity / zstd.*）",
      // occlusion.* 虽参与指纹（改后旧缓存会一次性失效重建），但运行期用于判定的遮挡表由 PacketEventsHook
      // 在启动期一次性构建，reload 不会重建它——改了只有重启才真正生效。
      "遮挡规则表（occlusion.extra-occluding / extra-non-occluding）");

  /**
   * 执行一次热重载。
   *
   * @return 给管理员看的中文结果行（已含「已生效」与「需重启」两类清单）
   */
  public List<String> reload(Target target) {
    // 重载会话必须串行：它内部是「停旧管线 → 建新管线 → 重启周期任务」的多步非原子流程。
    // 两个管理员（或控制台 + 玩家）同时执行 reload 会交错：一方把另一方刚创建、尚未发布到字段的
    // 管线覆盖掉，旧管线既不在字段里、也不会有人 stop —— 监听器重复注册、被隐藏的实体账本分裂
    // （旧管线藏起的实体新管线不知道，停用时永远不会恢复）。这里用一把可重入锁把整个会话串起来。
    synchronized (reloadLock) {
      if (shuttingDown) {
        // 插件正在停用：此时再装配管线只会造出无人回收的监听器/任务（见 shuttingDown 的说明）。
        return List.of("插件正在停用，本次热重载已取消（请等服务器完成停用/重启后再试）");
      }
      return reloadLocked(target);
    }
  }

  /**
   * 在「重载会话锁」内执行一段外部流程（目前只用于插件停用）。
   *
   * <p><b>为什么停用也要进这把锁</b>：热重载是「停旧管线 → 建新管线 → 重启周期任务」的多步流程，
   * 而 {@code onDisable} 同样要停管线、关池、注销监听。两者若不互斥，在 Folia 上（命令跑在区域线程、
   * 停用跑在全局线程）会交错：停用刚把管线字段置空，重载随即又 {@code start()} 出一套新的监听器与
   * 任务——它们挂在已停用的插件上，永远不会被清理（ProtocolLib 监听器泄漏、实体账本分裂）。
   * 用同一把锁串起来即可；停用路径本身不在封包热路径上，代价可忽略。
   *
   * @param action 需要与重载互斥执行的流程
   */
  public void runExclusively(Runnable action) {
    // 先置位再进锁：这样「已在排队的 reload」拿到锁后必然能看到 shuttingDown。
    shuttingDown = true;
    synchronized (reloadLock) {
      action.run();
    }
  }

  /** 重载主流程（调用方已持有 {@link #reloadLock}）。 */
  private List<String> reloadLocked(Target target) {
    List<String> lines = new ArrayList<>();

    int antiBefore = safeAntiXrayFingerprint(target);
    int bandwidthBefore = safeBandwidthFingerprint(target);
    try {
      target.reloadConfiguration();
    } catch (Throwable throwable) {
      lines.add("配置重新读取失败，已保留原配置（详见服务端日志）");
      return lines;
    }
    int antiAfter = safeAntiXrayFingerprint(target);
    int bandwidthAfter = safeBandwidthFingerprint(target);

    boolean invalidated = false;
    try {
      target.invalidateCaches();
      invalidated = true;
    } catch (Throwable throwable) {
      lines.add("缓存失效时出现异常，已在日志记录（不影响封包主链路）");
    }

    try {
      target.restartPeriodicTasks();
    } catch (Throwable throwable) {
      lines.add("周期任务重启时出现异常，已在日志记录（不影响封包主链路）");
    }

    // 两侧指纹都比对：只改 bandwidth.yml 时也必须如实指出「带宽侧已变化」，不能因反矿透侧未变就报「未变化」
    boolean antiChanged = antiBefore != antiAfter;
    boolean bandwidthChanged = bandwidthBefore != bandwidthAfter;
    if (antiChanged || bandwidthChanged) {
      List<String> changes = new ArrayList<>(2);
      if (antiChanged) {
        changes.add("反矿透侧（" + antiBefore + " → " + antiAfter + "）");
      }
      if (bandwidthChanged) {
        changes.add("带宽侧（" + bandwidthBefore + " → " + bandwidthAfter + "）");
      }
      lines.add("配置指纹变化：" + String.join("、", changes)
          // 只宣告真正失效的东西：伪装索引 / 已显形集合在热重载时是**刻意保留**的（见 invalidateAll）。
          // 旧文案写「显形索引已失效」与实现相反，会让管理员误以为显形数据被清掉。
          + (invalidated ? "，改写缓存与邻块快照已失效（伪装索引 / 已显形集合刻意保留）" : ""));
    } else {
      lines.add("两侧配置指纹均未变化（反矿透 " + antiAfter + "，带宽 " + bandwidthAfter + "）"
          + (invalidated ? "，缓存已按安全起见刷新" : ""));
    }
    // 配置解析失败时 MikuConfig 会保留上一份配置：此时「已热生效」是误导——必须显式指出新文件被拒绝
    String fallbackNote;
    try {
      fallbackNote = target.configurationFallbackNote();
    } catch (Throwable throwable) {
      fallbackNote = null;
    }
    if (fallbackNote != null) {
      lines.add("注意：本次重载有配置文件解析失败，" + fallbackNote
          + "——上面显示的指纹与生效项并非来自你刚修改的文件，请检查语法后重新重载");
    }
    lines.add("已热生效：" + String.join("、", APPLIED));
    lines.add("需要重启服务端才生效：" + String.join("、", RESTART_REQUIRED));
    return lines;
  }

  private static int safeAntiXrayFingerprint(Target target) {
    try {
      return target.antiXrayFingerprint();
    } catch (Throwable throwable) {
      return 0;
    }
  }

  private static int safeBandwidthFingerprint(Target target) {
    try {
      return target.bandwidthFingerprint();
    } catch (Throwable throwable) {
      return 0;
    }
  }
}