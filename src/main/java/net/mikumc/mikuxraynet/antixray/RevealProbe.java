package net.mikumc.mikuxraynet.antixray;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.mikumc.mikuxraynet.occlusion.OcclusionStore;
import net.mikumc.mikuxraynet.occlusion.OcclusionStores;
import net.mikumc.mikuxraynet.occlusion.SectionOcclusion;

/**
 * 「这个坐标为什么没被显形」的只读探针：把显形链路的每一层判定摊开成可读文本，供管理命令
 * {@code /mxnet probe <x> <y> <z>} 使用。
 *
 * <p><b>为什么需要它</b>：邻近显形是「索引 → 已显形标记 → 遮挡位图 → 暴露面 → 射线」五层过滤，
 * 任何一层判「不」都能让方块永远保持假状态，而状态面板只给总量、给不出「这一个坐标为什么不行」。
 * 排查「某箱子/某矿一直是假的」时，没有本探针就只能靠猜；它把每层的<b>实际判定值</b>直接打出来。
 *
 * <p><b>只读</b>：不写索引、不写已显形集合、不写位图、不发包。可在任意线程调用（本命令在玩家线程执行）。
 *
 * <p><b>与生产同口径</b>：暴露面与可见性判定直接复用 {@link ProximitySelector} 与
 * {@link BitmapVisibility} 的同一套实现，缺数据一律按「遮挡」处理——探针给出的结论与真实显形链路一致，
 * 不是另写一份近似逻辑。
 */
public final class RevealProbe {

  private static final int[] DX = {1, -1, 0, 0, 0, 0};
  private static final int[] DY = {0, 0, 1, -1, 0, 0};
  private static final int[] DZ = {0, 0, 0, 0, 1, -1};
  private static final String[] FACE_NAMES = {"+X", "-X", "+Y", "-Y", "+Z", "-Z"};

  private RevealProbe() {
  }

  /**
   * 生成探针文本（每个元素一行）。
   *
   * @param eye         玩家眼睛快照；{@code null} = 不做视线判定（控制台执行 / 未启用射线）
   * @param samples     配置的每方块候选点数（{@code proximity.raycast.samples}）
   * @param playerId    执行者 UUID；{@code null} = 控制台（跳过「已显形标记」）
   */
  public static List<String> probe(String worldName, UUID playerId, ProximitySelector.Eye eye,
      int x, int y, int z, ObfuscatedChunkIndex index, RevealedSet revealed, OcclusionStores stores,
      int samples) {
    List<String> lines = new ArrayList<>();
    lines.add("显形探针 " + worldName + " (" + x + "," + y + "," + z + ")"
        + (eye == null ? "（无视线：不做射线判定）"
            : " 眼睛=(" + fmt(eye.x()) + "," + fmt(eye.y()) + "," + fmt(eye.z()) + ")"));
    int chunkX = x >> 4;
    int chunkZ = z >> 4;

    // ① 显形索引：区块是否登记、该坐标是否仍在清单里
    ObfuscatedChunkIndex.ChunkEntry entry =
        index == null ? null : index.entry(worldName, chunkX, chunkZ);
    if (index == null) {
      lines.add("① 显形索引：未启用（邻近显形关闭 → 不会有任何显形）");
    } else if (entry == null) {
      lines.add("① 显形索引：该区块没有登记（未改写 / 已随卸载清理 / 写回失败被作废）→ 不会显形");
    } else {
      boolean listed = index.containsPosition(worldName, x, y, z);
      lines.add("① 显形索引：区块已登记 " + entry.size() + " 个坐标；本坐标"
          + (listed ? "在清单里" : "不在清单里")
          + (listed ? "" : "（没被伪装，或已因服务端方块变更被注销）"));
    }

    // ② 该玩家是否已显形过（已标记 → 周期扫描会跳过）
    if (revealed == null) {
      lines.add("② 已显形标记：未启用");
    } else if (playerId == null) {
      lines.add("② 已显形标记：控制台执行，无玩家上下文");
    } else {
      boolean marked = revealed.contains(playerId, worldName, chunkX, chunkZ, x, y, z);
      long markedInChunk = revealed.sizeFor(playerId, worldName, chunkX, chunkZ);
      lines.add("② 已显形标记：本玩家在该区块已标记 " + markedInChunk + " 个；本坐标"
          + (marked ? "已标记（周期扫描会跳过，直到区块重发/卸载）" : "未标记"));
    }

    // ③ 遮挡位图：该 section 是否存在、本格与六个邻面的遮挡位
    OcclusionStore store = stores == null ? null : stores.lookup(worldName);
    SectionOcclusion section = store == null ? null : store.section(chunkX, y >> 4, chunkZ);
    if (store == null) {
      lines.add("③ 遮挡位图：该世界没有位图（缺数据按遮挡处理 → 不会显形）");
    } else {
      String state = section == null ? "缺失（按遮挡处理）"
          : section.isEmpty() ? "全不遮挡" : section.isSolid() ? "全遮挡" : "逐格位图";
      lines.add("③ 遮挡位图：section(" + chunkX + "," + (y >> 4) + "," + chunkZ + ") = " + state);
      StringBuilder neighbours = new StringBuilder("   本格=")
          .append(section == null || section.isOccluding(x, y, z) ? "遮挡" : "不遮挡").append("｜邻面：");
      for (int i = 0; i < 6; i++) {
        boolean occluding = section == null || section.isOccluding(x + DX[i], y + DY[i], z + DZ[i]);
        neighbours.append(FACE_NAMES[i]).append(occluding ? "遮挡 " : "通畅 ");
      }
      lines.add(neighbours.toString());
    }

    // ④ 暴露面（与生产同一实现：缺数据 = 遮挡）
    ProximitySelector.RayQuery query = (px, py, pz) -> {
      OcclusionStore current = stores == null ? null : stores.lookup(worldName);
      SectionOcclusion target = current == null ? null : current.section(px >> 4, py >> 4, pz >> 4);
      return target == null || target.isOccluding(px, py, pz);
    };
    int faces = ProximitySelector.exposedFaces(x, y, z, query);
    lines.add("④ 暴露面：" + faceMaskText(faces)
        + (faces == 0 ? "（六面全遮挡 → 直接判不可见，绝不隔墙显形）" : ""));

    // ⑤ 可见性结论（多候选点任一通畅即可见）
    if (eye == null) {
      lines.add("⑤ 可见性：未做判定（无视线快照）");
    } else if (faces == 0) {
      lines.add("⑤ 可见性：不可见（无暴露面）");
    } else {
      BitmapVisibility bitmap = BitmapVisibility.local();
      int configured = Math.max(1, samples);
      boolean firstPoint = bitmap.isVisible(eye, stores, worldName, 1, x, y, z);
      boolean missing = bitmap.missingDataConsulted();
      boolean anyPoint = bitmap.isVisible(eye, stores, worldName, configured, x, y, z);
      boolean missingAny = bitmap.missingDataConsulted();
      lines.add("⑤ 可见性：首候选点=" + (firstPoint ? "通畅" : "被挡")
          + "，全部候选点（配置 " + configured + "）= " + (anyPoint ? "可见 → 会显形" : "不可见 → 不会显形")
          + (missing || missingAny ? "｜判定中触碰过缺数据 section（按遮挡处理）" : ""));
      if (!anyPoint) {
        lines.add("   → 结论：射线被挡。把挡住它的方块挖开，或检查 "
            + "proximity.frustum / raycast 配置；若本格在墙里且唯一暴露面被非遮挡方块盖住，"
            + "请把该方块临时移除后重试本探针。");
      }
    }
    return lines;
  }

  private static String faceMaskText(int faces) {
    if (faces == 0) {
      return "无";
    }
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      if ((faces & (1 << i)) != 0) {
        if (text.length() > 0) {
          text.append(' ');
        }
        text.append(FACE_NAMES[i]);
      }
    }
    return text.toString();
  }

  private static String fmt(double value) {
    return String.format(java.util.Locale.ROOT, "%.2f", value);
  }
}
