package net.mikumc.mikuxraynet.occlusion;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * 遮挡判定引擎：把「实体是否被完全遮挡」的射线计算从实体所属线程搬到<b>专用线程池</b>，
 * 从而取消条数封顶（收敛延迟不再随在线实体数线性增长），并让射线 CPU 离开主/区域线程。
 *
 * <p><b>输入全是纯值</b>：{@link Request} 只含坐标、时间与回调，不含任何 Bukkit/封包引用——
 * 因此本类可离线单测，且工作线程绝不触碰 Bukkit API。
 *
 * <p><b>时间预算（为什么不是条数封顶）</b>：异步不减少总 CPU，只把它搬走。若仍按「每周期 N 条」封顶，
 * 单条成本波动时就无法约束总开销。这里改为：调用方每个周期调一次
 * {@link #beginCycle(long)} 给出本周期的时间预算；工作线程在预算耗尽后<b>停止处理并等待下一周期</b>，
 * 队列里的任务顺延（不丢判定）。单条任务超预算时最多超出「一条任务」的成本，因此上界可控。
 *
 * <p><b>截止时间（超时丢弃）</b>：{@link Request#deadlineNanos()} 之前未开工的任务直接丢弃并计数——
 * 避免用陈旧几何去隐藏实体。丢弃<b>不改变实体当前显示状态</b>（不会新隐藏，也不会强制显示）；
 * 已隐藏实体的恢复由调用方的「每周期全量复检」兜底。
 *
 * <p><b>fail-open</b>：队列满即拒收；无顶点按「可见」；查不到位图按「不遮挡」。<b>被丢弃 / 被拒收的任务
 * 都不改变实体当前显示状态</b>（既不会新隐藏，也不会强制显示）——当前状态由此前一次<b>成功</b>判定决定，
 * 而「该恢复却仍隐藏」由调用方的每周期全量复检兜底（见 {@code EntityCuller#recheck}）。这样可避免
 * 「一次取不到判定就把已隐藏实体强行显示」与「通道②的隐藏判定」反复争夺而闪烁。
 */
public final class OcclusionEngine implements AutoCloseable {

  /** 最远射线长度的平方（构造期由调用方给定；超长一律判「不通畅」＝按可见处理，见 {@link #maxRayLengthSquared}）。 */
  private final double maxRayLengthSquared;

  private static final long IDLE_WAIT_MILLIS = 20L;
  private static final long CLOSE_JOIN_MILLIS = 1000L;

  /**
   * 一个待判定的实体射线任务（纯值，可安全跨线程传递）。
   *
   * @param playerId     该判定的玩家（仅用于调用方回投时定位；引擎自身不使用）
   * @param entityId     实体 id（仅用于调用方回投时定位；引擎自身不使用）
   * @param worldName    该实体所属世界名：<b>引擎用它选取该世界的遮挡表</b>（见 {@link OcclusionStores}），
   *                     调用方同时用它作废「跨世界迟到结果」（世界名由服务端保证唯一）
   * @param deadlineNanos 绝对截止时刻（{@link System#nanoTime()} 口径）；{@code 0} = 不限期
   * @param eyeX/Y/Z     观察者眼睛坐标
   * @param vertices     目标包围盒上「朝向观察者一侧」的候选顶点，按 {@code x,y,z} 连续存放
   * @param sink         结果回投（由工作线程调用；调用方负责在其中跳回实体所属线程）
   */
  public record Request(UUID playerId, int entityId, String worldName, long deadlineNanos,
      double eyeX, double eyeY, double eyeZ, double[] vertices, ResultSink sink) {
  }

  /** 结果回投回调：{@code fullyOccluded=true} 表示全部候选顶点都不通畅（应隐藏）。 */
  @FunctionalInterface
  public interface ResultSink {
    void accept(Request request, boolean fullyOccluded);
  }

  private final OcclusionStores stores;
  private final OcclusionStats stats;
  private final int maxOccluding;
  private final int queueCapacity;
  private final int workerCount;

  private final Object lock = new Object();
  private final ArrayDeque<Request> queue = new ArrayDeque<>();
  /** 本周期已消耗的射线耗时（写锁内更新；工作线程在读锁内比对预算）。 */
  private long budgetSpentNanos;
  /** 本周期预算；{@code -1} 表示尚未开启任何周期。 */
  private long budgetNanos = -1L;
  private boolean cycleActive;
  private boolean closed;

  private final Thread[] workers;

  /**
   * @param stores       遮挡数据仓库注册表（按世界分域，只读查询；引擎不修改它）
   * @param stats        计数容器
   * @param threads      专用池线程数（≥1；与反矿透改写池隔离，避免互相饿死）
   * @param maxOccluding 步进射线允许穿过的最大遮挡方块数（0 = 最严格）
   * @param maxRayLength 最远射线长度（格）：超过它的端点直接判「不通畅」（= 按可见处理，fail-open）。
   *                     既封顶单条射线的步数（成本），也保证「超长截断 → 退回可见」的语义；
   *                     {@code <= 0} 或非有限值表示不设上限（此时由调用方的距离门负责限长）。
   * @param queueCapacity 任务队列容量（满则拒收，fail-open）
   */
  public OcclusionEngine(OcclusionStores stores, OcclusionStats stats, int threads,
      int maxOccluding, double maxRayLength, int queueCapacity) {
    this.stores = stores;
    this.stats = stats;
    this.maxOccluding = maxOccluding;
    this.maxRayLengthSquared = Double.isFinite(maxRayLength) && maxRayLength > 0.0D
        ? maxRayLength * maxRayLength
        : Double.MAX_VALUE;
    this.queueCapacity = Math.max(1, queueCapacity);
    this.workerCount = Math.max(1, threads);
    this.workers = new Thread[workerCount];
    for (int i = 0; i < workerCount; i++) {
      Thread worker = new Thread(this::workerLoop, "MikuXrayNet-Occlusion-" + i);
      worker.setDaemon(true);
      workers[i] = worker;
      worker.start();
    }
  }

  /**
   * 开启一个新周期并给出本周期的时间预算（由调用方在<b>实体/全局区域线程</b>的周期任务里调用）。
   *
   * @param budgetNanos 本周期工作线程最多消耗的射线耗时；{@code <= 0} 表示本周期不处理任何任务
   */
  public void beginCycle(long budgetNanos) {
    synchronized (lock) {
      this.budgetNanos = budgetNanos;
      this.budgetSpentNanos = 0L;
      this.cycleActive = budgetNanos > 0L;
      lock.notifyAll();
    }
  }

  /**
   * 提交一个判定任务。
   *
   * @return {@code false} 表示队列已满、任务被拒收（调用方应按「可见」处理，fail-open）
   */
  public boolean submit(Request request) {
    synchronized (lock) {
      if (closed) {
        return false;
      }
      if (queue.size() >= queueCapacity) {
        stats.rejectedQueueFull.increment();
        return false;
      }
      queue.addLast(request);
      lock.notifyAll();
    }
    stats.submitted.increment();
    return true;
  }

  /** 当前排队未处理的任务数（诊断用）。 */
  public int queueDepth() {
    synchronized (lock) {
      return queue.size();
    }
  }

  /** 计数容器。 */
  public OcclusionStats stats() {
    return stats;
  }

  /** 专用池线程数。 */
  public int workerCount() {
    return workerCount;
  }

  /** 停机：清空队列、唤醒并结束全部工作线程（可重复调用）。 */
  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      queue.clear();
      lock.notifyAll();
    }
    for (Thread worker : workers) {
      worker.interrupt();
    }
    for (Thread worker : workers) {
      try {
        worker.join(CLOSE_JOIN_MILLIS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** 工作线程主循环：按周期预算取任务 → 判定 → 回投。 */
  private void workerLoop() {
    OcclusionRay ray = new OcclusionRay();
    // 逐线程复用的查询包装（线程封闭 → 可安全地在每轮判定前改写其 store 字段，热路径零分配）
    CountingLookup lookup = new CountingLookup(stats);
    while (true) {
      Request request;
      synchronized (lock) {
        while (!closed && (!cycleActive || queue.isEmpty())) {
          if (closed) {
            return;
          }
          try {
            lock.wait(IDLE_WAIT_MILLIS);
          } catch (InterruptedException interrupted) {
            if (closed) {
              return;
            }
            Thread.currentThread().interrupt();
            return;
          }
        }
        if (closed) {
          return;
        }
        // 预算耗尽：结束本周期，等待调用方 beginCycle 再继续（任务留在队列里顺延，不丢）
        if (budgetSpentNanos >= budgetNanos) {
          cycleActive = false;
          continue;
        }
        request = queue.pollFirst();
        if (request == null) {
          continue;
        }
      }

      long deadline = request.deadlineNanos();
      if (deadline != 0L && System.nanoTime() > deadline) {
        stats.timeoutDiscarded.increment();
        continue;
      }

      long start = System.nanoTime();
      boolean fullyOccluded;
      try {
        fullyOccluded = compute(ray, lookup, request);
      } catch (Throwable computeFailure) {
        // 单条判定抛异常（例如越界坐标被 SectionKey.packSection 拒绝，或查询/步进里未来新增的
        // 运行时异常）绝不能拖垮工作线程：本类线程是 daemon 且无 UncaughtExceptionHandler，
        // 一旦异常逃逸出 workerLoop，该线程会永久退出、队列再无人消费——默认 threads=1 时整个
        // 异步遮挡子系统将静默失效（实体不再随遮挡变化隐藏/恢复），直到热重载。按「保持实体当前
        // 显示状态」处理（不调用 sink），与超时丢弃 / 拒收的语义一致（见类注释的 fail-open 说明）。
        stats.computeFailures.increment();
        continue;
      }
      long spent = System.nanoTime() - start;
      stats.computed.increment();
      stats.nanosTotal.add(spent);
      synchronized (lock) {
        budgetSpentNanos += spent;
      }
      try {
        request.sink().accept(request, fullyOccluded);
      } catch (Throwable ignored) {
        // 回投失败（例如实体已退役）只影响该条判定，不得拖垮工作线程
      }
    }
  }

  /**
   * 判定：全部候选顶点都不通畅才算「完全遮挡」（与既有实体剔除的保守语义一致——
   * 任一顶点通畅即视为可见）。
   *
   * <p>遮挡数据源按 {@link Request#worldName()} 逐条选取（{@link OcclusionStores}）：跨世界的同名区块
   * 必须查各自世界的位图，否则会把另一世界的几何当成自己的（可能隐藏本可见实体）。
   */
  private boolean compute(OcclusionRay ray, CountingLookup lookup, Request request) {
    double[] vertices = request.vertices();
    int count = vertices.length / 3;
    if (count == 0) {
      return false; // 没有可试的顶点 → 按可见处理（fail-open）
    }
    lookup.store = stores == null ? null : stores.lookup(request.worldName());
    for (int i = 0; i < count; i++) {
      int base = i * 3;
      if (ray.hasLineOfSight(lookup, request.eyeX(), request.eyeY(), request.eyeZ(),
          vertices[base], vertices[base + 1], vertices[base + 2], maxOccluding, 0.0D,
          maxRayLengthSquared)) {
        return false; // 有通畅顶点 = 看得见
      }
    }
    return true;
  }

  /**
   * 逐工作线程复用的遮挡数据查询包装：查不到条目 = 「遮挡数据缺失」计入统计
   * （{@link EmptyOcclusion} 是「有数据且判定为不遮挡」，不计入）。
   *
   * <p><b>为什么是可变字段而不是每次新建 lambda</b>：本对象只在单个工作线程的判定循环里使用
   * （线程封闭），每轮判定前只改写 {@link #store} 指针即可，避免「每条任务分配一个闭包」的无谓垃圾。
   */
  private static final class CountingLookup implements OcclusionRay.Lookup {

    private final OcclusionStats stats;
    /** 本轮判定所属世界的仓库；{@code null} = 该世界尚未建过表（全部按缺失计）。 */
    private OcclusionStore store;

    CountingLookup(OcclusionStats stats) {
      this.stats = stats;
    }

    @Override
    public SectionOcclusion section(int chunkX, int sectionY, int chunkZ) {
      OcclusionStore current = store;
      SectionOcclusion section = current == null ? null : current.section(chunkX, sectionY, chunkZ);
      if (section == null) {
        stats.unknownSection.increment();
      }
      return section;
    }
  }
}
