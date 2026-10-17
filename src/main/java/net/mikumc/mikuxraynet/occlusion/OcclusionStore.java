package net.mikumc.mikuxraynet.occlusion;

import java.util.function.IntPredicate;

/**
 * 遮挡数据仓库：{@code (chunkX, sectionY, chunkZ) -> SectionOcclusion} 的单写多读存放处。
 *
 * <p><b>写入方</b>（建表：反矿透解码工作线程；单格补丁：方块变更处）——所有写入都在本类的
 * {@link #writeMutex} 内串行，使「读—改—写」这类复合操作（单格补丁）具备原子性。
 * {@link #setBlockOccluding} 需要先取旧值再写新值，若与并发建表交错会把一格补丁写丢，
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

  public OcclusionStore() {
    this(256);
  }

  /** 以「期望驻留 section 数」构造，减少扩容次数。 */
  public OcclusionStore(int expectedSections) {
    this.sections = new SwmrLongMap<>(Math.max(16, expectedSections));
  }

  // ------------------------------------------------------------------
  // 读（无锁乐观读）
  // ------------------------------------------------------------------

  @Override
  public SectionOcclusion section(int chunkX, int sectionY, int chunkZ) {
    return sections.get(SectionKey.packSection(chunkX, sectionY, chunkZ));
  }

  /** 直接以打包键查询（射线遍历用，避免每步重复打包）。 */
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
    synchronized (writeMutex) {
      // 用带校验的 packSection（而非射线热路径用的 packBlock）：包里的坐标一旦越出 28 位水平 /
      // 8 位 sectionY 范围，packBlock 会按位截断成<b>另一个 section 的键</b>，把补丁静默写到错误的
      // 几何上去（该段位图从此与真实世界不符）。补丁调用方按 patchFailures 统计并跳过本格，故此处
      // 显式拒绝是安全方向。
      long key = SectionKey.packSection(x >> 4, y >> 4, z >> 4);
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
  }

  /** 摘除某 section 的遮挡数据。 */
  public void removeSection(int chunkX, int sectionY, int chunkZ) {
    synchronized (writeMutex) {
      sections.remove(SectionKey.packSection(chunkX, sectionY, chunkZ));
    }
  }

  /**
   * 整列覆盖写入（按「起始 sectionY + 顺序」给定各 section 的遮挡数据）。
   *
   * <p>用于「缓存命中时从负载恢复整列遮挡数据」（见 {@link OcclusionPayload}）：一次写完整条列，
   * 减少与并发建表交错的窗口。数组下标即 section 偏移；元素为 {@code null} 表示该段全不遮挡
   * （会清掉旧条目，避免留下与本次不同的陈旧位图）。
   *
   * <p>越出 {@link SectionKey} 可表示范围的 section 会被跳过（世界高度超过 8 位 sectionY 时不落库——
   * 实际的 384 高世界只有 24 段，远在范围内）。
   */
  public void restoreColumn(int chunkX, int minSectionY, int chunkZ, SectionOcclusion[] column) {
    synchronized (writeMutex) {
      for (int i = 0; i < column.length; i++) {
        int sectionY = minSectionY + i;
        if (sectionY < SectionKey.SECTION_Y_MIN || sectionY > SectionKey.SECTION_Y_MAX) {
          continue;
        }
        long key = SectionKey.packSection(chunkX, sectionY, chunkZ);
        SectionOcclusion data = column[i];
        if (data == null) {
          sections.remove(key);
        } else {
          sections.put(key, data);
        }
      }
    }
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
    }
  }

  /** 清空全部遮挡数据（停用/世界卸载/热重载时调用）。 */
  public void clear() {
    synchronized (writeMutex) {
      sections.clear();
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
