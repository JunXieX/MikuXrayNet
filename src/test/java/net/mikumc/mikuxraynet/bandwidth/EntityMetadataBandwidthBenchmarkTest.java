package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 实体元数据「不变值剔除」的<b>带宽收益</b>基准（离线、随 CI 运行）。
 *
 * <p><b>能测什么、不能测什么</b>：本功能省下的字节完全取决于「上游插件往每次重发的元数据里塞了多少
 * 冗余条目」——那是插件行为，不由本插件决定，也无法离线复现真实封包。因此本基准分两层：
 * <ol>
 *   <li><b>精确层</b>：用<b>生产代码</b> {@link MetadataDelta} 逐条判定，统计「被剔除的条目数 / 总条目数」。
 *       这是真实逻辑的真实结果，不含任何假设；</li>
 *   <li><b>估算层</b>：把「条目数」按<b>线格式字节模型</b>换算成字节（见报告里的假设表），
 *       得到包字节数的改前/改后与节省比例。字节数<b>是估算而非实测</b>，模型已逐项写明。</li>
 * </ol>
 *
 * <p>本基准<b>不</b>回答「该功能在真机上是否启用得了」（这取决于服务端 ProtocolLib 能否解析元数据），
 * 只回答「假设它能工作，能省多少」。
 *
 * <p><b>为什么把「不可比较的条目」单独建模</b>：{@link MetadataDelta} 按 {@code equals} 去重，只有
 * 「已知不可变」的包装类型 / 字符串 / 枚举才能做到「内容相同即剔除」；未实现 {@code equals} 的 NMS 对象
 * （物品栈、文本组件等）会被判为「变化」而<b>永远无法剔除</b>。因此场景里显式区分这两类条目，
 * 不做粉饰。
 */
class EntityMetadataBandwidthBenchmarkTest {

  private static final Path REPORT_PATH = Path.of("target", "benchmark-report.md");
  private static final String SECTION_MARKER = "## 5. 实体元数据不变值剔除";

  /** 预热重发次数（不计入统计）：让 JIT 充分编译，避免把编译成本算进结果。 */
  private static final int WARMUP_SENDS = 2_000;
  /** 计入统计的重发次数。 */
  private static final int MEASURE_SENDS = 20_000;

  /**
   * 线格式字节模型（<b>估算</b>，非实测）：封包头 = 实体 id（VarInt 2B）+ 条目数（ubyte 1B）。
   * 依据：{@code ClientboundSetEntityDataPacket} 的写出顺序为「实体 id → 条目列表」。
   */
  private static final int PACKET_HEADER_BYTES = 3;
  /**
   * 线格式字节模型：每个条目的固定开销 = 索引（ubyte 1B）+ 类型 id（小值 VarInt 1B）。
   * 值本身的字节数由 {@link Entry#valueBytes()} 单独给出。
   */
  private static final int ENTRY_OVERHEAD_BYTES = 2;

  /** 一条元数据条目：值 + 值的线缆字节数（不含条目固定开销）+ 能否被剔除。 */
  private record Entry(Object value, int valueBytes, boolean droppable) {
  }

  /** 一个工作负载场景：条目模板 + 每次重发中「发生变化的可剔除条目数」。 */
  private record Scenario(String name, String workload, List<Entry> entries, int changedPerSend) {
  }

  /** 一个场景的测量结果。 */
  private record Result(String name, String workload, int entries, int nonDroppable,
      int keptPerSend, int droppedPerSend, double droppedRatio, int cancelledPackets,
      int bytesBefore, int bytesAfter, long medianNanosPerSend) {
  }

  @Test
  void benchmarkEntityMetadataBandwidth() throws IOException {
    List<Scenario> scenarios = scenarios();
    List<Result> results = new ArrayList<>();
    for (Scenario scenario : scenarios) {
      results.add(measure(scenario));
    }

    String section = buildSection(results);
    appendReport(section);
    System.out.println(section);

    // 只断言「数值被成功采集」，不断言「省了多少」（节省比例取决于假设的工作负载）
    assertEquals(scenarios.size(), results.size(), "测量行数不足：" + results.size());
    for (Result result : results) {
      assertTrue(result.entries() > 0, "条目数未采集：" + result);
      assertTrue(result.keptPerSend() >= result.nonDroppable(),
          "不可比较的条目必然每次都保留（判不准就当成变化）：" + result);
      assertTrue(result.keptPerSend() + result.droppedPerSend() == result.entries(),
          "保留数 + 剔除数必须等于条目总数：" + result);
      assertTrue(result.droppedRatio() >= 0.0D && result.droppedRatio() <= 1.0D,
          "剔除比例越界：" + result);
      assertTrue(result.bytesBefore() > 0 && result.bytesAfter() >= 0,
          "字节数未采集：" + result);
      assertTrue(result.medianNanosPerSend() >= 0, "耗时未采集：" + result);
    }
  }

  /**
   * 五个代表性工作负载（覆盖「每 tick 全量重发」「无变化重发」「部分变化」「轻量实体」与理论上限）：
   *
   * <ul>
   *   <li>典型生物元数据 = 20 条：15 条「可比较值」（布尔标志 / 小整数 / 浮点 / 字符串）+ 5 条
   *       「不可比较的 NMS 对象」（装备、文本组件等）；</li>
   *   <li>轻量实体 = 3 条：2 条可比较 + 1 条不可比较；</li>
   *   <li>上限情形 = 6 条全部可比较，且无变化（整包可取消）。</li>
   * </ul>
   * 这些构成是<b>假设值</b>（真实条目数随实体类型与插件而变），报告中已逐项写明。
   */
  private static List<Scenario> scenarios() {
    return List.of(
        new Scenario("血条类：每 tick 全量重发，仅 1 条变化",
            "插件为刷新血量把整份元数据重发，其余条目内容不变",
            typicalMob(), 1),
        new Scenario("名牌/宠物类：重发但没有任何变化",
            "插件按固定周期重发整份元数据，本次内容与上次完全相同",
            typicalMob(), 0),
        new Scenario("坐骑/宠物类：约四成条目变化",
            "位置/姿态/若干状态同批变化，其余条目不变",
            typicalMob(), 6),
        new Scenario("轻量实体（掉落物/箭）：3 条",
            "条目少、变化也少，但每次仍整包重发",
            lightweight(), 0),
        new Scenario("上限情形：全部条目均可比较且无变化",
            "整份元数据与上次完全相同 → 整包被取消（本功能的理论上限）",
            allComparable(), 0));
  }

  /** 上限情形：6 条全部是可比较值（无任何 NMS 对象）→ 无变化时整包可取消。 */
  private static List<Entry> allComparable() {
    return List.of(
        new Entry((byte) 0x00, 1, true),
        new Entry(0, 1, true),
        new Entry(20.0F, 4, true),
        new Entry(false, 1, true),
        new Entry(true, 1, true),
        new Entry("slime", 6, true));
  }

  /** 典型生物元数据：15 条可比较值 + 5 条不可比较的 NMS 对象。 */
  private static List<Entry> typicalMob() {
    List<Entry> entries = new ArrayList<>(20);
    // 可比较值（能被剔除）：布尔标志、小整数、浮点血量、字符串名牌
    entries.add(new Entry((byte) 0x00, 1, true));       // 实体标志（byte）
    entries.add(new Entry(300, 2, true));               // 空气值（VarInt 2B）
    entries.add(new Entry("史莱姆", 7, true));           // 自定义名牌（长度前缀 1B + UTF-8）
    entries.add(new Entry(true, 1, true));              // 名牌可见
    entries.add(new Entry(false, 1, true));             // 静音
    entries.add(new Entry(false, 1, true));             // 无重力
    entries.add(new Entry(20.0F, 4, true));             // 血量（float）
    entries.add(new Entry(0x00, 1, true));              // 主手/姿态标志（byte）
    entries.add(new Entry(0, 1, true));                 // 冻结刻数
    entries.add(new Entry(false, 1, true));             // 发光
    entries.add(new Entry((byte) 0x00, 1, true));       // 生物类别专用标志（byte）
    entries.add(new Entry(false, 1, true));             // 隐身
    entries.add(new Entry(0, 1, true));                 // 箭/掉落物计数类小整数
    entries.add(new Entry(true, 1, true));              // 是否可拾取类布尔
    entries.add(new Entry("mikumc", 8, true));          // 记分板/队伍名（字符串）
    // 不可比较的 NMS 对象（equals 不可靠 → 永远判为变化、无法剔除）
    for (int i = 0; i < 5; i++) {
      entries.add(new Entry(new byte[] {1, 2, 3, 4}, 8, false));
    }
    return entries;
  }

  /** 轻量实体：2 条可比较值 + 1 条不可比较对象。 */
  private static List<Entry> lightweight() {
    return List.of(
        new Entry(1, 1, true),      // 物品数量类小整数
        new Entry(false, 1, true),  // 布尔状态
        new Entry(new byte[] {9}, 8, false));
  }

  /**
   * 逐次重发驱动生产逻辑 {@link MetadataDelta}，统计条目与字节。
   *
   * <p>每次重发按轮转令恰好 {@code changedPerSend} 条「可剔除」条目发生变化；不可比较条目每次都以
   * <b>新对象</b>下发（真实情形即「NMS 对象没有可靠的 equals」），因此它们必然被保留。
   */
  private static Result measure(Scenario scenario) {
    List<Entry> entries = scenario.entries();
    int size = entries.size();

    List<Integer> droppable = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      if (entries.get(i).droppable()) {
        droppable.add(i);
      }
    }
    int nonDroppable = size - droppable.size();
    int changedPerSend = Math.min(scenario.changedPerSend(), droppable.size());

    // 每条可剔除条目的「上次下发值」：在模板值与一个替代值之间来回切换，保证「变化」时确实不相等
    Object[] current = new Object[size];
    Object[] alternate = new Object[size];
    for (int i = 0; i < size; i++) {
      Entry entry = entries.get(i);
      current[i] = entry.value();
      alternate[i] = alternateOf(entry.value());
    }

    Map<Integer, Object> cache = new HashMap<>();
    int[] indices = new int[size];
    int[] entryBytes = new int[size];
    for (int i = 0; i < size; i++) {
      indices[i] = i;
      entryBytes[i] = ENTRY_OVERHEAD_BYTES + entries.get(i).valueBytes();
    }
    int packetBytes = PACKET_HEADER_BYTES;
    for (int bytes : entryBytes) {
      packetBytes += bytes;
    }

    long[] durations = new long[MEASURE_SENDS];
    Object[] values = new Object[size];
    long dropped = 0L;
    int cancelled = 0;
    long bytesBefore = 0L;
    long bytesAfter = 0L;

    for (int send = 0; send < WARMUP_SENDS + MEASURE_SENDS; send++) {
      // 轮转挑选本次「变化」的可剔除条目
      for (int k = 0; k < changedPerSend; k++) {
        int slot = droppable.get((send + k) % droppable.size());
        current[slot] = current[slot] == entries.get(slot).value() ? alternate[slot]
            : entries.get(slot).value();
      }
      int changedCount;
      for (int i = 0; i < size; i++) {
        if (entries.get(i).droppable()) {
          values[i] = current[i];
        } else {
          // 不可比较的 NMS 对象：每次都是新实例（identity 不等）→ 必然判为「变化」
          values[i] = new Object();
        }
      }

      long start = System.nanoTime();
      int[] kept = MetadataDelta.changedEntries(indices, values, cache);
      long elapsed = System.nanoTime() - start;

      if (send < WARMUP_SENDS) {
        continue;
      }
      durations[send - WARMUP_SENDS] = elapsed;
      changedCount = kept.length;
      dropped += size - changedCount;
      if (changedCount == 0) {
        cancelled++;
      }
      bytesBefore += packetBytes;
      if (changedCount > 0) {
        bytesAfter += PACKET_HEADER_BYTES;
        for (int index : kept) {
          bytesAfter += entryBytes[index];
        }
      }
    }

    Arrays.sort(durations);
    // 保留条目数 = （总条目数 − 被剔除总数）/ 次数；剔除数取其补，两者之和恒等于条目总数
    int keptPerSend = (int) (((long) MEASURE_SENDS * size - dropped) / MEASURE_SENDS);
    int droppedPerSend = size - keptPerSend;
    double droppedRatio = (double) dropped / ((long) MEASURE_SENDS * size);
    return new Result(scenario.name(), scenario.workload(), size, nonDroppable,
        keptPerSend, droppedPerSend, droppedRatio, cancelled,
        (int) (bytesBefore / MEASURE_SENDS), (int) (bytesAfter / MEASURE_SENDS),
        durations[MEASURE_SENDS / 2]);
  }

  /** 「变化」时用的替代值：与模板值不相等，且长度有界（避免字符串无限增长）。 */
  private static Object alternateOf(Object value) {
    if (value instanceof Byte v) {
      return (byte) (v + 1);
    }
    if (value instanceof Integer v) {
      return v + 1;
    }
    if (value instanceof Float v) {
      return v + 0.5F;
    }
    if (value instanceof Boolean v) {
      return !v;
    }
    if (value instanceof String v) {
      return v + "*";
    }
    return new Object();
  }

  /** 追加为「第 5 节」：保留既有正文，去掉上一次的第 5 节后重新追加（与测试类执行顺序无关）。 */
  private static void appendReport(String section) throws IOException {
    Files.createDirectories(REPORT_PATH.getParent());
    String existing = Files.exists(REPORT_PATH)
        ? Files.readString(REPORT_PATH, StandardCharsets.UTF_8) : "";
    int marker = existing.indexOf(SECTION_MARKER);
    String base = marker < 0 ? existing : existing.substring(0, marker);
    if (!base.isEmpty() && !base.endsWith("\n")) {
      base += "\n";
    }
    Files.writeString(REPORT_PATH, base + section, StandardCharsets.UTF_8);
  }

  private static String buildSection(List<Result> results) {
    StringBuilder report = new StringBuilder();
    report.append("\n").append(SECTION_MARKER).append("：带宽收益（条目精确 / 字节估算）\n\n");
    report.append("- **精确层**：用生产代码 `MetadataDelta` 逐条判定「本次是否需要下发」，"
        + "统计剔除的条目数 —— 这是真实逻辑的真实结果\n");
    report.append("- **估算层**：把条目数按下面的线格式模型换算成字节；**字节数是估算、不是实测**\n");
    report.append("- 被测对象：`net.mikumc.mikuxraynet.bandwidth.MetadataDelta#changedEntries`"
        + "（生产类，未另写简化逻辑）；每场景预热 ").append(WARMUP_SENDS)
        .append(" 次、统计 ").append(MEASURE_SENDS).append(" 次「一次实体元数据重发」\n");
    report.append("- **本基准不衡量**该功能在真机能否启用（取决于服务端 ProtocolLib 能否解析元数据），"
        + "只回答「假设它能工作，能省多少」\n\n");

    report.append("### 5.1 字节模型（估算依据，逐项列出）\n\n");
    report.append("| 组成 | 字节 | 依据 |\n| --- | --- | --- |\n");
    report.append("| 封包头 | ").append(PACKET_HEADER_BYTES)
        .append(" | 实体 id（VarInt 2B）+ 条目数（ubyte 1B），`ClientboundSetEntityDataPacket` 的写出顺序 |\n");
    report.append("| 每条固定开销 | ").append(ENTRY_OVERHEAD_BYTES)
        .append(" | 条目索引（ubyte 1B）+ 类型 id（小值 VarInt 1B） |\n");
    report.append("| 布尔 / byte | 1 | 单字节 |\n");
    report.append("| 小整数 | 1~2 | VarInt |\n");
    report.append("| float | 4 | IEEE754 单精度 |\n");
    report.append("| 字符串 | 1 + UTF-8 长度 | VarInt 长度前缀 + 字节 |\n");
    report.append("| NMS 对象（装备 / 文本组件等） | 8 | **估算常量**（真实大小随对象而变） |\n\n");
    report.append("> 该模型只用于把「条目数」折算成字节量级；若要精确字节数，需要在真机上实测封包尺寸。\n\n");

    report.append("### 5.2 场景定义（假设的工作负载）\n\n");
    for (Result result : results) {
      report.append("- **").append(result.name()).append("**：").append(result.workload()).append("\n");
    }
    report.append("\n### 5.3 逐场景：条目剔除与包字节\n\n");
    report.append("| 场景 | 条目数 | 其中不可比较 | 每次重发保留/剔除 | 剔除比例 | 整包取消 | "
        + "改前字节/包 | 改后字节/包 | 包字节节省 | 判定耗时中位(ns) |\n");
    report.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
    for (Result result : results) {
      double byteSaving = result.bytesBefore() == 0 ? 0.0D
          : 1.0D - (double) result.bytesAfter() / result.bytesBefore();
      report.append("| ").append(result.name())
          .append(" | ").append(result.entries())
          .append(" | ").append(result.nonDroppable())
          .append(" | ").append(result.keptPerSend()).append(" / ").append(result.droppedPerSend())
          .append(" | ").append(percent(result.droppedRatio()))
          .append(" | ").append(result.cancelledPackets()).append(" / ").append(MEASURE_SENDS)
          .append(" | ").append(result.bytesBefore())
          .append(" | ").append(result.bytesAfter())
          .append(" | ").append(percent(byteSaving))
          .append(" | ").append(result.medianNanosPerSend())
          .append(" |\n");
    }
    report.append("\n> 「不可比较」= 未实现可靠 `equals` 的 NMS 对象；`MetadataDelta` 对它们一律判为「变化」"
        + "（判不准就当成变化，绝不误删），因此它们每次都保留，也正是「整包取消」只在**全部条目均可比较**时"
        + "才可能出现的原因。\n");
    report.append("> 剔除比例取决于上游插件重发的冗余程度，是**假设**的工作负载，不是服务器实测。\n\n");

    report.append("### 5.4 外推：每玩家 / 每服务器（假设 20 个实体、每实体每秒重发 R 次）\n\n");
    report.append("| 场景 | 单实体单次节省字节 | 每玩家每秒(R=1) | 每玩家每秒(R=2) | 50 玩家每秒(R=2) |\n");
    report.append("| --- | --- | --- | --- | --- |\n");
    for (Result result : results) {
      int saved = result.bytesBefore() - result.bytesAfter();
      report.append("| ").append(result.name())
          .append(" | ").append(saved)
          .append(" | ").append(kib(saved * 20L))
          .append(" | ").append(kib(saved * 20L * 2L))
          .append(" | ").append(kib(saved * 20L * 2L * 50L))
          .append(" |\n");
    }
    report.append("\n> 「20 个实体 / 每实体每秒 R 次」是**假设**的展示口径，真实值请按自己服务器的实体数与"
        + "插件重发频率线性缩放：每玩家每秒节省 = 单实体单次节省 × 实体数 × 每秒重发次数。\n");
    report.append("> 结论读法：本功能省的是**实体元数据这一小类包**里的冗余条目；"
        + "与区块数据（每区块几十 KB）相比，其绝对量级通常很小，收益随「上游插件重发频率」线性放大。\n");

    return report.toString();
  }

  private static String percent(double ratio) {
    return String.format(Locale.ROOT, "%.1f%%", ratio * 100.0D);
  }

  private static String kib(long bytes) {
    return String.format(Locale.ROOT, "%.1f KiB/s", bytes / 1024.0D);
  }
}
