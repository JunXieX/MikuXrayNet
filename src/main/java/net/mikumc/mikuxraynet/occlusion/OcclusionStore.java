package net.mikumc.mikuxraynet.occlusion;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntPredicate;

/**
 * 遮挡数据仓库：{@code (chunkX, sectionY, chunkZ) -> SectionOcclusion} 的单写多读存放处。
 *
 * <p><b>写入方</b>（建表：反矿透解码工作线程；单格 / 批量补丁：方块变更处）——所有写入都在本类的
 * {@link #writeMutex} 内串行，使「读—改—写」这类复合操作（单格补丁）具备原子性。
 * {@link #setBlockOccluding} 需要先取旧值再写新值，若与并发建表交错会把一格补丁写丢；
 * {@link #setBlocksOccluding} 则让一个方块变更包的全部坐标共用一次加锁（见其 javadoc），
 * 因此该互斥是所有写入路径的公共闸门，而不只是补丁路径。
 *
 * <p><b>读取方</b>（异步射线线程）——不取 {@link #writeMutex}，走 {@link SwmrLongMap} 的乐观读；
 * 因此射线遍历可以无锁并发，代价仅是「读到写入途中的旧值」——对本模块无害（少一格遮挡 = 偏可见，
 * 属安全方向）。
 *
 * <p><b>null 语义</b>：表里没有某 section（{@code section(...) == null}）与「该段全不遮挡」等价，
 * 射线按其「不计遮挡」处理。因此写入全不遮挡的段 = {@link #removeSection}。
 */
public final class OcclusionStore implements OcclusionRay.Lookup {

  /**
   * 写入串行闸门。顺序恒为 {@code writeMutex -> SwmrLongMap 内部写锁}，不与任何读路径成环。
   */
  private final Object writeMutex = new Object();

  private final SwmrLongMap<SectionOcclusion> sections;

  /**
   * 补丁代次（全局单调递增）：每次方块变更补丁落库 +1。
   *
   * <p><b>为什么需要代次而不是时间戳</b>：只需要「有没有在某个时刻之后被补丁写过」这一个二值信息，
   * 单调计数比较即可，且不受系统时钟回拨影响。
   */
  private final AtomicLong patchSequence = new AtomicLong();

  /**
   * 每 section 最后一次被补丁写过时的代次（键 = 打包的 section 键）。
   *
   * <p>只在 {@link #writeMutex} 内写入（与 section 数据的写入同序），读取（建表前的快照）不加锁：
   * CHM 的写有 release、读有 acquire 语义，读到新代次即保证对应的补丁写入已对本线程可见。
   * 条目只活到该段下一次整列发布 / 摘除，规模 ≈ 已加载区块里被补丁过的段数。
   */
  private final ConcurrentHashMap<Long, Long> patchStamps = new ConcurrentHashMap<>();

  /**
   * 每段「最近被补丁改过的格」回放表：整列建表 / 缺失段回填之后重放一次，消除「包序列化 → 取补丁戳快照」
   * 这段窗口（戳比较只能发现快照<b>之后</b>的补丁；落在解码几何与快照之间的补丁戳值相同、会被旧几何覆盖）。
   *
   * <p>回放语义：补丁是服务端下发的「该格最新真值」；建表用的是更早的区块包几何。发布后重放最近补丁格，
   * 无论快照取在何时，结果都等于「解码几何 + 其后所有补丁」——不再依赖戳快照的时点。
   * 单段最多保留 {@value #MAX_REPLAY_CELLS} 格（正常 1~几格；超出说明该段被高频改写，最旧格让位）。
   */
  private final ConcurrentHashMap<Long, PatchReplay> patchReplay = new ConcurrentHashMap<>();

  /** 单段回放表容量（见 {@link #patchReplay}）。 */
  private static final int MAX_REPLAY_CELLS = 64;

  /** 单段的补丁回放条目（字段可变、由自身监视器保护；条目本身只在 writeMutex 内被重放/移除）。 */
  private static final class PatchReplay {
    final long[] coords = new long[MAX_REPLAY_CELLS];
    final boolean[] occluding = new boolean[MAX_REPLAY_CELLS];
    int size;
    /**
     * 该段补丁格数超过容量：回放表已不完整，不能再拿它修正旧几何。
     * 置位后该段的整列发布改为<b>跳过</b>（活表已含全部补丁，跳过即正确），避免「发布旧几何 + 部分回放」。
     */
    boolean overflow;
  }

  /** 因「该段期间被补丁写过」而跳过发布的段数（诊断用，只增不减）。 */
  private final LongAdder publishSkippedPatched = new LongAdder();

  public OcclusionStore() {
    this(256);
  }

  /** 以「期望驻留 section 数」构造，减少扩容次数。 */
  public OcclusionStore(int expectedSections) {
    this.sections = new SwmrLongMap<>(Math.max(16, expectedSections));
  }

  /**
   * 该仓库是否已随世界卸载被关闭。
   *
   * <p><b>为什么需要</b>：世界卸载时 {@link OcclusionStores#removeWorld} 会摘除并清空旧仓库，
   * 但改写工作线程可能仍持有该仓库引用并在摘除之后继续写入——那张「旧世界的表」会被同名新世界读到，
   * 旧世界的实心石头被当成新世界几何（该显示却多藏）。关闭后所有写入直接丢弃，等新世界的区块重新发布。
   */
  private volatile boolean closed;

  /** 该仓库是否已关闭（见 {@link #closed}）。 */
  public boolean isClosed() {
    return closed;
  }

  /** 关闭并清空：先置关闭标记（阻断在途写入），再释放内容。 */
  void closeAndClear() {
    this.closed = true;
    clear();
  }

  // ------------------------------------------------------------------
  // 读（无锁乐观读）
  // ------------------------------------------------------------------

  @Override
  public SectionOcclusion section(int chunkX, int sectionY, int chunkZ) {
    return sections.get(SectionKey.packSection(chunkX, sectionY, chunkZ));
  }

  /**
   * 直接以打包键查询（省掉一次打包运算）。
   *
   * <p><b>当前无任何调用者（含单测）</b>：保留为「以打包键读」的对称入口，与
   * {@link #section(int, int, int)} 并存。射线遍历当前走三参重载并在跨段边界时才查表
   * （{@code OcclusionRay} 按 section 缓存视图），故不需要本重载；将来若有热循环每步都要
   * 重复打包，可直接用它省掉打包成本。
   */
  public SectionOcclusion section(long key) {
    return sections.get(key);
  }

  /** 当前驻留的 section 条目数（诊断用，近似值）。 */
  public int loadedSectionCount() {
    return sections.size();
  }

  // ------------------------------------------------------------------
  // 写（串行化）
  // ------------------------------------------------------------------

  /**
   * 覆盖写入某 section 的遮挡数据。
   *
   * <p><b>测试专用豁免</b>：生产写入一律走 {@link #restoreColumn}（整列覆盖，见其 javadoc），
   * 本方法当前仅单测在用，保留以免破坏测试。
   *
   * @param data 遮挡数据；{@code null}（该段全不遮挡）等价于 {@link #removeSection}
   */
  public void replaceSection(int chunkX, int sectionY, int chunkZ, SectionOcclusion data) {
    synchronized (writeMutex) {
      long key = SectionKey.packSection(chunkX, sectionY, chunkZ);
      if (data == null) {
        sections.remove(key);
      } else {
        sections.put(key, data);
      }
    }
  }

  /**
   * 单格补丁：把某世界坐标方块标为遮挡/不遮挡（方块变更处调用）。
   *
   * <p>必要时升级/降级表示：全不遮挡的段被写入第一格遮挡 → 新建位图；整段全遮挡的
   * {@link SolidOcclusion} 被清掉一格 → 降级为位图；位图清空后删除该条目。这些转换都在
   * {@link #writeMutex} 内完成，与并发建表不会互相覆盖。
   *
   * @param occluding 变更后该方块是否遮挡
   */
  public void setBlockOccluding(int x, int y, int z, boolean occluding) {
    // 保持单格入口「显式拒绝越界坐标」的语义（批量入口对越界格是「跳过继续」）：
    // 先做一次带校验的 pack，越界即抛 IllegalArgumentException。
    SectionKey.packSection(x >> 4, y >> 4, z >> 4);
    // 委托批量入口：让单格补丁与批量补丁共用同一条「写活表 + 记戳 + 记录回放格」路径，
    // 不再出现「单格入口绕过回放」的语义分叉（该重载当前仅单测使用）。
    setBlocksOccluding(new int[] {x}, new int[] {y}, new int[] {z}, new boolean[] {occluding}, 1);
  }

  /**
   * 批量单格补丁：整批只取一次 {@link #writeMutex}。
   *
   * <p><b>为什么必须要有批量入口</b>：一个 {@code MULTI_BLOCK_CHANGE} 包最多带 4096 个坐标，逐格调用
   * {@link #setBlockOccluding} 就是 4096 次加解锁。而同一把锁还被解码工作线程的整列建表
   * （{@link #restoreColumn}）与区块卸载（{@link #removeColumn}）持有，封包线程会与它们反复竞争：
   * 既把建表那侧的长临界区切碎，又让网络线程在锁上停顿成千上万次。整批只取一次锁后，锁次数从 4096
   * 降到 1，临界区变成一段连续的内存写入——这正是「网络线程零计算、少阻塞」纪律的落点。
   *
   * <p><b>越界语义</b>：单格的越界判定仍由 {@link SectionKey#packSection} 权威给出（抛
   * {@link IllegalArgumentException}）。本方法<b>跳过</b>该格、继续处理后面的格，而不是让整批失败——
   * 一个坏坐标不该连累同包其余 4095 格；跳过的格数由调用方计入失败计数。
   *
   * @param xs        每格的方块 X 坐标
   * @param ys        每格的方块 Y 坐标
   * @param zs        每格的方块 Z 坐标
   * @param occluding 每格变更后是否遮挡
   * @return 实际应用的格数；越界坐标被跳过（不写入），由调用方计入失败计数
   */
  public int setBlocksOccluding(int[] xs, int[] ys, int[] zs, boolean[] occluding) {
    return setBlocksOccluding(xs, ys, zs, occluding, xs == null ? 0 : xs.length);
  }

  /**
   * 批量单格补丁（显式给定本次有效格数）。
   *
   * <p><b>为什么单独带 {@code count}</b>：补丁热路径改用了线程本地复用的数组（见
   * {@code OcclusionPatchListener}），数组长度通常大于本次有效格数，不能再拿 {@code xs.length}
   * 当批量边界——否则会把上一包残留的坐标一起应用（把补丁写到错误的几何上）。
   *
   * <p><b>补丁戳</b>：每个「被本批坐标覆盖」的 section 记一次代次（同一段在批内只写一次 CHM），
   * 使随后到来的整列建表知道这一段期间被补丁改过、不能用更早的几何覆盖它。
   */
  public int setBlocksOccluding(int[] xs, int[] ys, int[] zs, boolean[] occluding, int count) {
    if (closed || xs == null || ys == null || zs == null || occluding == null || count <= 0) {
      return 0;
    }
    if (count > xs.length || count > ys.length || count > zs.length || count > occluding.length) {
      return 0;
    }
    int applied = 0;
    long stamp = 0L;
    long lastKey = 0L;
    boolean stamped = false;
    synchronized (writeMutex) {
      for (int i = 0; i < count; i++) {
        long key;
        try {
          key = SectionKey.packSection(xs[i] >> 4, ys[i] >> 4, zs[i] >> 4);
        } catch (IllegalArgumentException outOfRange) {
          // 越界坐标：本格不写入，继续处理后面的格（整批不因一格被拒而回滚）
          continue;
        }
        applyCellLocked(key, xs[i], ys[i], zs[i], occluding[i]);
        recordReplayCell(key, xs[i], ys[i], zs[i], occluding[i]);
        applied++;
        // 段戳：同一 section 在批内只写一次（MULTI_BLOCK_CHANGE 恒为同一段，故通常只有 1 次 CHM 写）
        if (stamp == 0L) {
          stamp = patchSequence.incrementAndGet();
        }
        if (!stamped || key != lastKey) {
          patchStamps.put(key, stamp);
          lastKey = key;
          stamped = true;
        }
      }
    }
    return applied;
  }

  /**
   * 取整列各 section 的补丁戳快照（{@code 0} = 该段自上次整列发布 / 摘除以来未被补丁写过）。
   *
   * <p><b>用途</b>：建表在解码之后还要花 1~3 ms 构建位图，这段时间里方块补丁会把新状态写进活表；
   * 发布前用它逐段比较，戳变了的段跳过覆盖，避免把更新的补丁状态覆盖成「解码时刻的旧几何」
   * （那会让刚挖开的墙继续保持遮挡，属于「该显示却多藏」）。
   *
   * <p>成本：每列一次 {@code long[sectionCount]} 分配 + sectionCount 次 CHM 读（主世界 24 次）。
   * 它现在由封包链路在<b>持包线程</b>为每个待改写区块包取一次（见
   * {@code ObfuscationProcessor#capturePatchStamps}），因此「命中缓存、不需要建表」的包也会白取一次——
   * 这是刻意取舍：快照只有取得足够早，才能把「封包生成 → worker 解码」这段（排队 + 区域线程 hop，
   * 常态 1~10 ms）里落库的方块补丁挡在发布覆盖之外；白付的只是 24 次 CHM 读 + 约 192 B 短命数组，
   * 相比「补丁被解码时刻的旧几何吞掉」（该显示却多藏）的代价可以忽略。
   *
   * @return 长度 {@code sectionCount} 的数组，下标即 section 偏移
   */
  public long[] patchStamps(int chunkX, int minSectionY, int chunkZ, int sectionCount) {
    int count = Math.max(0, sectionCount);
    long[] stamps = new long[count];
    for (int i = 0; i < count; i++) {
      Long stamp = patchStamps.get(SectionKey.packUnchecked(chunkX, minSectionY + i, chunkZ));
      stamps[i] = stamp == null ? 0L : stamp;
    }
    return stamps;
  }

  /** 因「该段期间被补丁写过」而跳过整列发布的段数（诊断用；持续偏高说明建表期间方块变更很频繁）。 */
  public long skippedPatchedSections() {
    return publishSkippedPatched.sum();
  }

  /** 记录一格待回放补丁（见 {@link #patchReplay}）；容量满时丢弃最旧一格。调用方已持 writeMutex。 */
  private void recordReplayCell(long key, int x, int y, int z, boolean occluding) {
    PatchReplay replay = patchReplay.computeIfAbsent(key, ignored -> new PatchReplay());
    long packed = packCoordinate(x, y, z);
    synchronized (replay) {
      if (replay.size < MAX_REPLAY_CELLS) {
        replay.coords[replay.size] = packed;
        replay.occluding[replay.size] = occluding;
        replay.size++;
        return;
      }
      // 超出容量：不再记录（丢弃最旧会留下不完整集合），置溢出标志让整列发布改为跳过，
      // 由「活表已含全部补丁」保证正确性（见 PatchReplay#overflow）。
      replay.overflow = true;
    }
  }

  /**
   * 把该段最近记录的补丁格重放到活表（<b>必须在整列发布之后、且调用方持 writeMutex</b> 时调用）。
   *
   * <p><b>为什么需要</b>：补丁戳只有在「快照之后」又落库补丁时才会变化；而区块包是服务端更早序列化的，
   * 落在「包序列化 → 取快照」之间的补丁戳值等于快照值、会被判成「期间无变化」→ 用解码时的旧几何整段
   * 覆盖。回放不依赖快照时点：补丁是服务端下发的该格最新真值，发布后重放最近补丁格，结果等于
   * 「解码几何 + 其后所有补丁」。
   *
   * @return 重放的格数（0 = 该段近期没有补丁）
   */
  private int replayPendingCells(long key) {
    // 不取走条目：回放是幂等的，同源旧字节可能被多个任务并发发布（多玩家同时收到同一区块）。
    // 若首次回放后就清空，后发布者会用旧几何覆盖已修正的补丁格（该显示却多藏）。
    // 条目由 removeColumn / clear / removeSection 清理，规模 ≈ 已加载区块里被补丁过的段数。
    PatchReplay replay = patchReplay.get(key);
    if (replay == null) {
      return 0;
    }
    long[] coords;
    boolean[] occluding;
    int size;
    synchronized (replay) {
      coords = replay.coords;
      occluding = replay.occluding;
      size = replay.size;
    }
    for (int i = 0; i < size; i++) {
      long packed = coords[i];
      int x = (int) (packed >> 38);
      int y = (int) ((packed >> 26) & 0xFFF);
      int z = (int) (packed & 0x3FFFFFF);
      applyCellLocked(key, x, y, z, occluding[i]);
    }
    return size;
  }

  /** 坐标打包（x/z 各 26 位、y 12 位；与射线/显形侧同一编码）。 */
  private static long packCoordinate(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
  }

  /**
   * 写入单格补丁。<b>调用方必须已持有 {@link #writeMutex}</b>。
   *
   * <p>抽出来是为了让单格入口与批量入口共用同一套「取旧值 → 表示升降级 → 写回」逻辑：批量入口要在
   * 一个临界区内连续写多格，不能再逐格加锁。
   *
   * <p>键由调用方用带校验的 {@link SectionKey#packSection} 算出（而非射线热路径用的 {@code packBlock}）：
   * 包里的坐标一旦越出 28 位水平 / 8 位 sectionY 范围，{@code packBlock} 会按位截断成<b>另一个 section
   * 的键</b>，把补丁静默写到错误的几何上。调用方按「本格被拒」计数并跳过，故显式拒绝是安全方向。
   */
  private void applyCellLocked(long key, int x, int y, int z, boolean occluding) {
    int localX = x & SectionOcclusion.LOCAL_MASK;
    int localY = y & SectionOcclusion.LOCAL_MASK;
    int localZ = z & SectionOcclusion.LOCAL_MASK;

    SectionOcclusion current = sections.get(key);
    // 「无该段数据」（null）与「已知该段全不遮挡」（{@link EmptyOcclusion} 共享快路径）在语义上都等价于
    // 「零个遮挡位」——空段既可能是「从未建表」，也可能是「建表时判为全不遮挡」或「位图被清空后回落」。
    // 两者必须同路径处理：否则对 EmptyOcclusion 走到下面的 (BitmapOcclusion) 强转会抛 ClassCastException，
    // 被 applyPatches 当成「补丁失败」吞掉——于是「在空气段里新放置的遮挡方块」永远进不了位图。
    // 方向性：该格漏记为遮挡 = 实体射线少算一格遮挡（少藏，安全侧），但显形侧会据此把该方块后方
    // 本不可见的坐标误判为可见而还原（偏漏防）。故按「零遮挡位」统一处理，保证位图与真实世界一致。
    if (current == null || current.isEmpty()) {
      if (!occluding) {
        return;
      }
      BitmapOcclusion created = new BitmapOcclusion();
      created.setOccluding(localX, localY, localZ, true);
      sections.put(key, created);
      return;
    }

    if (current.isSolid()) {
      if (occluding) {
        return; // 已全遮挡，无需改动
      }
      BitmapOcclusion downgraded = BitmapOcclusion.solid();
      downgraded.setOccluding(localX, localY, localZ, false);
      sections.put(key, downgraded);
      return;
    }

    BitmapOcclusion bitmap = (BitmapOcclusion) current;
    bitmap.setOccluding(localX, localY, localZ, occluding);
    if (bitmap.isEmpty()) {
      // 位图清空 = 已知该段全不遮挡：换成共享空快路径（而不是删条目——删掉会退化成「未知」，
      // 既浪费日后重建、又会让覆盖率指标误报「缺数据」）
      sections.put(key, EmptyOcclusion.INSTANCE);
    }
  }

  /**
   * 摘除某 section 的遮挡数据（连同它的补丁戳）。
   *
   * <p><b>测试专用豁免</b>：生产上的摘除都是整列/整表粒度——区块卸载走 {@link #removeColumn}、
   * 世界卸载与停用走 {@link #clear}；本方法当前仅单测在用，保留以免破坏测试。
   */
  public void removeSection(int chunkX, int sectionY, int chunkZ) {
    synchronized (writeMutex) {
      long key = SectionKey.packSection(chunkX, sectionY, chunkZ);
      sections.remove(key);
      patchStamps.remove(key);
      patchReplay.remove(key);
    }
  }

  /**
   * 整列覆盖写入（<b>不做补丁戳比较</b>，等价于旧行为）。
   *
   * <p><b>测试专用豁免</b>：生产路径已不再用它——建表走带戳快照的
   * {@link #restoreColumn(int, int, int, SectionOcclusion[], long[])}，缓存命中走
   * {@link #restoreMissingColumn}（只补缺失段）；本重载当前仅单测在用，保留以免破坏测试。
   */
  public void restoreColumn(int chunkX, int minSectionY, int chunkZ, SectionOcclusion[] column) {
    restoreColumn(chunkX, minSectionY, chunkZ, column, null);
  }

  /**
   * 整列覆盖写入（带补丁戳快照，见 {@link #patchStamps}）。
   *
   * <p><b>为什么必须比较戳</b>：建表在解码之后、发布之前还有 1~3 ms 的位图构建窗口，这期间落库的
   * 方块补丁已经把新状态写进活表；若此处无条件覆盖，补丁就会被「解码时刻的几何」吞掉
   * （刚挖开的墙仍被判遮挡 → 该显示却多藏）。因此逐段比较：该段现有戳非 0 且与快照不同
   * ⇒ 期间有补丁落库 ⇒ <b>跳过覆盖</b>，保留含补丁的活位图。
   *
   * <p><b>戳为空时照常发布</b>：空戳说明该列被 {@link #removeColumn}/{@link #clear} 摘除过（不是补丁），
   * 此时必须恢复数据，否则该段会退回「缺数据 = 遮挡」，把玩家眼前的矿永久藏住。
   *
   * <p><b>只有「活表里确有该段数据」才允许跳过</b>：戳只记录「该段被补丁覆盖过」，而<b>打断</b>类补丁
   * 在「活表里本无该段」时不会建条目（{@code applyCellLocked} 早退），却仍会记戳。若此时也跳过，
   * 这轮整列发布结束后该段依然为空 → 显形侧按「缺数据 = 遮挡」判定 → 该段内的矿永不显形。
   * 因此跳过条件是「戳变了 <b>且</b> 活表有数据」；戳变了但没有数据时照常发布（没有补丁状态可保，
   * 宁可写入解码时刻的几何，也不能让整段留空）。
   *
   * @param stamps 建表前取的戳快照；{@code null} = 不做比较（等价于旧行为）
   * @return 因期间有补丁而被跳过覆盖的段数（已累加进 {@link #skippedPatchedSections()}）
   */
  public int restoreColumn(int chunkX, int minSectionY, int chunkZ, SectionOcclusion[] column,
      long[] stamps) {
    if (closed) {
      return 0; // 世界已卸载：丢弃本次发布（见 closed 字段说明）
    }
    int skipped = 0;
    synchronized (writeMutex) {
      for (int i = 0; i < column.length; i++) {
        int sectionY = minSectionY + i;
        if (sectionY < SectionKey.SECTION_Y_MIN || sectionY > SectionKey.SECTION_Y_MAX) {
          continue;
        }
        long key = SectionKey.packSection(chunkX, sectionY, chunkZ);
        PatchReplay pending = patchReplay.get(key);
        if (pending != null && pending.overflow) {
          // 该段补丁格超过回放容量：回放表不完整，不能再用旧几何覆盖（活表已含全部补丁）。
          skipped++;
          continue;
        }
        if (stamps != null && i < stamps.length) {
          Long current = patchStamps.get(key);
          // 必须同时满足「活表有该段」：打断类补丁在无数据段上不建条目、只留戳；
          // 若此时也跳过，该段会永久留空（显形侧「缺数据 = 遮挡」→ 整段矿不显形）。
          if (current != null && current.longValue() != stamps[i] && sections.get(key) != null) {
            skipped++;
            // 跳过旧几何覆盖；但「解码 → 取快照」窗口内的补丁不在戳差异里，仍要回放（幂等）。
            replayPendingCells(key);
            continue;
          }
        }
        SectionOcclusion data = column[i];
        if (data == null) {
          sections.remove(key);
        } else {
          sections.put(key, data);
        }
        // 发布成功后该段已是最新几何，旧戳不再需要——不清会让戳表随「补丁过的段」无界增长
        patchStamps.remove(key);
        // 再回放「解码 → 取快照」窗口内落库的补丁格：戳比较发现不了这段，回放保证其不被旧几何覆盖
        replayPendingCells(key);
      }
    }
    if (skipped > 0) {
      publishSkippedPatched.add(skipped);
    }
    return skipped;
  }

  /**
   * <b>只补齐缺失段</b>的整列恢复（缓存命中路径专用）：活表里已存在的段一律不动，只发布缺失段。
   *
   * <p><b>为什么只补缺失</b>：
   * <ul>
   *   <li>已存在的段可能已被更晚的方块补丁原地改写过，用旧负载覆盖会把补丁抹掉（该显示却多藏）；</li>
   *   <li>不能用「首个 section 存在 ⇒ 整列存在」这种列级探针：补丁只建出单段时会把整列误判为已存在，
   *       其余段永久缺失 → 显形侧「缺数据 = 遮挡」→ 矿永不显形。</li>
   * </ul>
   *
   * <p>逐段独立判断既不需要快照、也不依赖列级探针：缺哪段补哪段，已有的段原样保留。
   *
   * @param column 已解码的整列遮挡数据（下标 = {@code minSectionY} 起的偏移）
   * @return 实际补入的段数（0 = 整列都已存在，本次什么都没改）
   */
  public int restoreMissingColumn(int chunkX, int minSectionY, int chunkZ,
      SectionOcclusion[] column) {
    if (closed) {
      return 0;
    }
    // 无锁预检：绝大多数缓存命中时整列都已存在，先做一次乐观读扫描，避免每个命中都去抢
    // writeMutex（该锁同时被整列建表与方块补丁持有，命中路径是高频的）。只有真的发现缺段才进锁；
    // 预检与加锁之间若恰好有段被摘除，本次会漏补，但下一次命中会补上（方向安全）。
    if (!hasMissingSection(chunkX, minSectionY, chunkZ, column.length)) {
      return 0;
    }
    int filled = 0;
    synchronized (writeMutex) {
      for (int i = 0; i < column.length; i++) {
        int sectionY = minSectionY + i;
        if (sectionY < SectionKey.SECTION_Y_MIN || sectionY > SectionKey.SECTION_Y_MAX) {
          continue;
        }
        long key = SectionKey.packSection(chunkX, sectionY, chunkZ);
        PatchReplay pending = patchReplay.get(key);
        if (pending != null && pending.overflow) {
          continue; // 回放表不完整：跳过填充，活表已含全部补丁
        }
        if (sections.get(key) != null) {
          continue; // 已有活数据：可能含更晚的补丁，绝不覆盖
        }
        SectionOcclusion data = column[i];
        if (data == null) {
          continue; // 负载里该段「无数据」= 无需补
        }
        sections.put(key, data);
        patchStamps.remove(key);
        // 缓存负载是更早的几何：回放该段近期补丁格，避免「无活数据期间被补丁改过的格」被旧几何覆盖
        // （含打断类补丁——不建条目、只留戳的那种）。
        replayPendingCells(key);
        filled++;
      }
    }
    return filled;
  }

  /** 只读预检：该列是否存在任一缺失段（无锁乐观读，命中路径用它避免无谓加锁）。 */
  private boolean hasMissingSection(int chunkX, int minSectionY, int chunkZ, int sectionCount) {
    for (int i = 0; i < sectionCount; i++) {
      int sectionY = minSectionY + i;
      if (sectionY < SectionKey.SECTION_Y_MIN || sectionY > SectionKey.SECTION_Y_MAX) {
        continue;
      }
      if (sections.get(SectionKey.packSection(chunkX, sectionY, chunkZ)) == null) {
        return true;
      }
    }
    return false;
  }

  /**
   * 摘除整列（某区块的全部 section）；区块/世界卸载时调用。
   *
   * <p>逐 section 摘除覆盖 8 位 sectionY 的全部取值——不遍历整张表即可保证该列一条不剩。
   * 一次取写锁批量删（见 {@link SwmrLongMap#removeAll}）：一个区块通常只有二十来段建过表，
   * 若逐键各取一次写锁，就等于为主线程上的区块卸载付 256 次加解锁。
   */
  public void removeColumn(int chunkX, int chunkZ) {
    long[] keys = SectionKey.columnKeys(chunkX, chunkZ);
    synchronized (writeMutex) {
      sections.removeAll(keys);
      // 同列补丁戳一并摘除：只在区块卸载时清，让戳表规模 ≈ 已加载区块里被补丁过的段数。
      // 复用上面那份 256 键数组，不额外分配、也不遍历整张 CHM。
      for (long key : keys) {
        patchStamps.remove(key);
        patchReplay.remove(key);
      }
    }
  }

  /** 清空全部遮挡数据（停用/世界卸载/热重载时调用）。 */
  public void clear() {
    synchronized (writeMutex) {
      sections.clear();
      patchStamps.clear();
      patchReplay.clear();
    }
  }

  /**
   * 由「逐格方块状态 + 遮挡谓词」构建一段的遮挡数据（建表入口）。
   *
   * <p>委托 {@link BitmapOcclusion#fromStates}：全不遮挡返回 {@code null}（不上表），全遮挡返回
   * {@link SolidOcclusion#INSTANCE}（共享单例），其余返回逐格位图。
   *
   * <p><b>测试专用豁免</b>：生产建表走 {@link BitmapOcclusion#fromStateLookup}（旁路生成，免逐格拷数组），
   * 本方法当前仅单测在用，保留以免破坏测试。
   */
  public static SectionOcclusion buildSection(int[] states, IntPredicate isOccluding) {
    return BitmapOcclusion.fromStates(states, isOccluding);
  }
}
