package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.mikumc.mikuxraynet.util.PlayerWorldIndex.WorldContext;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「方块更新合并」的世界归属判定与两个来源的会话约束 —— 1.34.13 生产 dump 的直接回归。
 *
 * <p><b>现场</b>（35 人 / 27 小时，dump 20261120-155506-434）：`快照重同步 1234102 / 单线程冲刷 2428522`，
 * 处置环反复出现 `位置快照（world）与索引（minecraft_lobby）矛盾` 与 `…（world_nether）矛盾`。这两条数字
 * 证明的是「两份来源长期不一致」，而不是「哪一份错」：可能是刷新任务跑在已移出旧 world 的实体上
 * （心跳新鲜、世界陈旧），也可能是索引漏接/错序了切维事件。本轮的处置是让两者<b>互相校验</b>：
 * <ul>
 *   <li>入缓冲前要求两源一致（同世界、坐标已知、心跳新鲜），否则原包即时放行；</li>
 *   <li>冲刷时只有「索引换代 + 活快照同世界 + 与固定世界不同」才允许整批丢弃；</li>
 *   <li>同名世界重连（世界名相同、代次已换）按「上一代连接」不投递；</li>
 *   <li>拥有线程每 tick 用同一次 `player.getWorld()` 对账修正索引，并区分两种候选根因。</li>
 * </ul>
 *
 * <p>链路本身依赖 ProtocolLib 封包与 Folia 调度，离线造不出来；这里驱动被抽出的纯判定表
 * （{@link BlockChangeMerger.FlushWorld#classify}）并把「谁在哪一步取证据」钉在源码上。
 */
class BlockChangeMergerSessionTest {

  private static WorldContext context(String worldName, long session) {
    // world 引用传 null：本判定只用世界名与代次，不碰世界对象
    return new WorldContext(worldName, null, World.Environment.NORMAL, -64, 24, session);
  }

  @Test
  @DisplayName("两源一致：仍是入缓冲时的世界 → 照常交付")
  void sameGenerationAndWorldDelivers() {
    assertEquals(BlockChangeMerger.FlushWorld.SAME,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world", 7L), true, "world",
            true));
  }

  @Test
  @DisplayName("已证明换世界：索引换代 + 活快照同世界 + 与固定世界不同 → 整批丢弃")
  void provenWorldChangeIsDiscarded() {
    assertEquals(BlockChangeMerger.FlushWorld.CHANGED,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world_nether", 8L),
            true, "world_nether", true));
  }

  @Test
  @DisplayName("索引换代但快照不同意（快照侧陈旧）→ 冲突，按不丢处理")
  void indexAdvancedButSnapshotDisagreesIsConflict() {
    assertEquals(BlockChangeMerger.FlushWorld.CONFLICT,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world_nether", 8L),
            true, "world", true),
        "快照仍说旧世界：这不是「已证明换世界」，丢弃会永久缺格");
  }

  @Test
  @DisplayName("索引未换代却与快照世界不同（dump 现场签名）→ 冲突，按不丢处理")
  void sameGenerationDifferentSnapshotIsConflict() {
    assertEquals(BlockChangeMerger.FlushWorld.CONFLICT,
        BlockChangeMerger.FlushWorld.classify(context("minecraft_lobby", 7L), context("minecraft_lobby", 7L),
            true, "world", true),
        "「位置快照（world）与索引（minecraft_lobby）矛盾」正是这一格：不得据此丢弃");
  }

  @Test
  @DisplayName("同名世界重连：世界名相同但代次已换 → 上一代连接，不投递给新连接")
  void sameNameReconnectIsPreviousConnection() {
    assertEquals(BlockChangeMerger.FlushWorld.PREVIOUS_CONNECTION,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world", 8L), true, "world",
            true),
        "只比世界名会把两代连接当成同一世界，把上一代缓冲串给新连接");
    assertEquals(BlockChangeMerger.FlushWorld.PREVIOUS_CONNECTION,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world", 7L), true, "world",
            false),
        "连接换代与世界名/快照新鲜度无关：同名世界、索引都没变，也必须判成上一代连接");
  }

  @Test
  @DisplayName("索引未知 / 快照心跳过期：一律按不丢处理")
  void unknownSourcesNeverDiscard() {
    assertEquals(BlockChangeMerger.FlushWorld.UNKNOWN,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), null, true, "world", true));
    assertEquals(BlockChangeMerger.FlushWorld.UNKNOWN,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world_nether", 8L),
            false, null, true),
        "心跳过期即未知：绝不能拿一份死快照当丢弃证据");
    assertEquals(BlockChangeMerger.FlushWorld.UNKNOWN,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world", 7L), true, null,
            true));
  }

  /**
   * 发布阻塞回归：同 UUID 同名世界重连、且新连接<b>还没产出第一条位置快照</b>时，上一连接的已取消批次
   * <b>永不发送</b>。
   *
   * <p>旧顺序（先判快照新鲜度）会把这一格判成 {@code UNKNOWN} 并「按不丢交付」，而交付时按 uuid 取到的
   * 接收者是<b>新连接</b>——旧世界的坐标直接落到新客户端上。连接代次必须排在世界/快照判定之前。
   */
  @Test
  @DisplayName("同名世界重连且新位置缺失：永不把上一连接的批次发给新连接")
  void reconnectWithNoPositionYetNeverDeliversOldBatch() {
    // 世界名完全相同、索引代次甚至没变（重登后同一世界同一片地图），快照尚未建立
    assertEquals(BlockChangeMerger.FlushWorld.PREVIOUS_CONNECTION,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world", 7L), false, null,
            false),
        "连接已换代就必须判成上一代连接：快照缺失不能把它降级成「世界未知、按不丢交付」");
    assertEquals(BlockChangeMerger.FlushWorld.PREVIOUS_CONNECTION,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), null, false, null, false),
        "索引上下文缺失同样不得把上一代连接判成「未知」后硬发");
  }

  @Test
  @DisplayName("同一有效连接上世界暂不明：已取消的批不得丢（保留不丢语义）")
  void cancelledBatchOnSameConnectionSurvivesUnknownWorld() {
    assertEquals(BlockChangeMerger.FlushWorld.UNKNOWN,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), null, false, null, true),
        "连接没变、只是世界/快照暂不明 → 按不丢交付，绝不整批蒸发");
    assertEquals(BlockChangeMerger.FlushWorld.CONFLICT,
        BlockChangeMerger.FlushWorld.classify(context("world", 7L), context("world", 7L), true, "world_nether",
            true),
        "连接没变、两源冲突 → 同样按不丢交付");
  }

  @Test
  @DisplayName("没有固定上下文（历史缓冲）→ 按原行为交付，绝不凭猜测丢弃")
  void unpinnedBatchIsDelivered() {
    assertEquals(BlockChangeMerger.FlushWorld.SAME,
        BlockChangeMerger.FlushWorld.classify(null, context("world", 7L), true, "world", true));
  }

  @Test
  @DisplayName("名称比较仍只有一份实现（事件路径与冲刷路径共用）")
  void worldNameComparisonHasOneImplementation() throws Exception {
    String body = methodBody(source("bandwidth/BlockChangeMerger.java"),
        "static FlushWorld classify(");
    assertTrue(body.contains("shouldDiscardAtFlush("),
        "换代后仍必须用同一个名称比较纯函数，不能就地拼 !equals 表达式");
    assertFalse(body.contains("!pinned.worldName().equals"), "不得在判定表里另写一份名称比较");
  }

  @Test
  @DisplayName("入口一致闸必须在取消原包之前，且未知/冲突要记坐标覆盖")
  void entryGateRunsBeforeBuffering() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    String body = methodBody(merger, "public void onPacketSending(");
    int gate = body.indexOf("verifiedSnapshot(");
    int cancel = body.indexOf("event.setCancelled(true)");
    assertTrue(gate > 0, "入口必须有两源一致闸（verifiedSnapshot）");
    assertTrue(cancel > gate, "一致闸必须在入缓冲/取消原包之前：否则会按不可信的世界打标");
    assertTrue(body.contains("rememberDeliveredOnUnverified("),
        "未知/冲突早退前必须登记坐标覆盖，否则窗口末的旧值会把刚下发的新态盖回去");
    assertTrue(body.contains("target.context = context;"), "入缓冲时必须固定世界上下文");
    assertTrue(body.contains("target.connection = connection;"),
        "入缓冲时必须固定接收连接：只按 uuid 找人不区分两代连接");
    assertTrue(body.contains("positions.session(player.getUniqueId())"),
        "接收连接必须来自连接会话（登录建立、退出结束），不能借世界代次顶替");
    assertTrue(body.contains("pinned.session() != context.session()"),
        "入缓冲必须按世界代次拒绝混批：同名世界重连时世界名区分不了两代");
  }

  @Test
  @DisplayName("位置刷新：归属 + 会话双闸，失败只失效位置不结束会话")
  void refreshIsOwnerAndSessionFenced() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    String body = methodBody(merger, "private void refreshPosition(");
    assertTrue(body.contains("ownership.isOwnedByCurrentRegion("),
        "非拥有线程不得写快照：否则会留下「心跳新鲜、世界陈旧」的僵尸快照");
    assertTrue(body.contains("positions.isCurrent(slot.session)"),
        "旧会话的刷新必须停手（同名世界重连时世界名区分不了）");
    assertTrue(body.contains("positions.invalidate(slot.session)"),
        "临时读失败只失效位置与心跳");
    assertFalse(body.contains("endSession("),
        "临时读失败不得结束会话：那会把已有待发批判成上一代连接而整批不投递");
    assertTrue(body.contains("PlayerWorldIndex.reconcile(player, world)"),
        "必须用同一次 player.getWorld() 对账修正索引（事件遗漏/错序的候选根因）");
  }

  @Test
  @DisplayName("交付入口先核对接收连接，再处理世界/未知")
  void deliveryChecksConnectionBeforeWorld() throws Exception {
    String body = methodBody(source("bandwidth/BlockChangeMerger.java"),
        "private void deliver(UUID playerId, PlayerPositionSnapshots.Session connection");
    int connection = body.indexOf("current != connection");
    int world = body.indexOf("stillSameWorld(");
    assertTrue(connection > 0, "交付前必须按连接会话核对接收者（同 UUID 的新连接会拿到新 Player 实例）");
    assertTrue(world > connection, "连接核对必须早于世界复核：新连接可能还没有第一条位置快照");
    assertTrue(body.contains("stats.pendingOldSession.add(cancelled)"), "旧连接的批必须计入 oldSession");
  }

  @Test
  @DisplayName("退役：按注册身份摘除、只失效位置、不结束会话")
  void retireKeepsSessionAndRemovesByIdentity() throws Exception {
    String body = methodBody(source("bandwidth/BlockChangeMerger.java"), "private void retirePositionTask(");
    assertTrue(body.contains("positionTasks.remove(playerId, slot)"),
        "必须按注册身份摘除：按键盲删会摘掉重建后的新注册");
    assertTrue(body.contains("positions.invalidate(slot.session)"),
        "退役只失效位置与心跳");
    assertFalse(body.contains("endSession("), "退役不是断线，不得结束会话");
  }

  @Test
  @DisplayName("调度只复用登录建立的会话，看门狗不得为已退出 UUID 建回会话")
  void schedulerNeverCreatesSessions() throws Exception {
    String merger = source("bandwidth/BlockChangeMerger.java");
    String schedule = methodBody(merger, "private void schedulePositionRefresh(");
    assertTrue(schedule.contains("positions.session(playerId)"),
        "调度必须先取已存在的会话");
    assertFalse(schedule.contains("beginSession("),
        "调度路径不得建立会话：看门狗/迟到回调拿着已退出玩家的 UUID 过来时会留下永不清理的令牌");
    assertTrue(methodBody(merger, "public void onJoin(").contains("beginSession("),
        "新连接必须在登录处理器里建立会话");
    String sweep = methodBody(merger, "private void sweepPositionTasks(");
    assertTrue(sweep.contains("positionTasks.containsKey("), "看门狗只补没有登记的玩家（不多排）");
    assertTrue(sweep.contains("schedulePositionRefresh("), "看门狗必须能补登记");
    assertTrue(methodBody(merger, "private void startWatchdog(").contains("getGlobalRegionScheduler()"),
        "看门狗必须在全局区域线程低频运行：不读实体状态、不制造每 tick 世界读");
  }

  private static String source(String relative) throws Exception {
    // 行尾归一：工作区源码是 CRLF，断言不应依赖行尾
    return Files.readString(Path.of("src/main/java/net/mikumc/mikuxraynet/" + relative))
        .replace("\r\n", "\n");
  }

  /** 取方法体：从签名截到其后第一个「两空格缩进的右花括号」（与 arch 守门同一取法）。 */
  private static String methodBody(String source, String signature) {
    int start = source.indexOf(signature);
    if (start < 0) {
      return "";
    }
    int end = source.indexOf("\n  }", start);
    return end < 0 ? source.substring(start) : source.substring(start, end);
  }
}
