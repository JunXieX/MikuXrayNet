package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ChunkPacketAccessor} 纯判定函数测试：remove-block-entities 开关的汇合点与
 * 方块实体剔除的坐标匹配规则（离线无法构造真实区块封包，故只测纯函数缝）。
 */
class ChunkPacketAccessorFilterTest {

  /** 开关是剔除段的真正消费点：false 时即使有被伪装坐标也绝不进入剔除段（A4 死配置键修复的语义）。 */
  @Test
  void switchOffSkipsFilteringEvenWithPositions() {
    int[] positions = {0 << 8 | 0 << 4 | 0};
    assertFalse(ChunkPacketAccessor.shouldFilterBlockEntities(false, positions),
        "remove-block-entities=false 时必须跳过剔除段");
    assertTrue(ChunkPacketAccessor.shouldFilterBlockEntities(true, positions),
        "remove-block-entities=true 且有被伪装坐标时才剔除");
  }

  @Test
  void switchOnWithoutPositionsStillSkipsFiltering() {
    assertFalse(ChunkPacketAccessor.shouldFilterBlockEntities(true, new int[0]),
        "没有被伪装坐标时无需剔除（与旧行为一致：localPositions 为空直接返回）");
  }

  /**
   * 写回未确认生效（{@code verified=false}）时必须短路跳过方块实体剔除：此时新字节未必真的写进了
   * NMS 对象，若仍剔除实体，交出去的就是「原包旧方块状态 + 已删实体」——可被透视端识别的强不一致，
   * 反而暴露改写。宁可保留实体，也不产生不一致包。
   */
  @Test
  void unverifiedWriteBackSkipsBlockEntityRemoval() {
    int[] positions = {0 << 8 | 0 << 4 | 0};
    assertFalse(ChunkPacketAccessor.shouldRemoveBlockEntities(false, true, positions, true),
        "写回未确认生效时必须短路跳过剔除（避免「矿可见 + 实体消失」的不一致包）");
    assertTrue(ChunkPacketAccessor.shouldRemoveBlockEntities(true, true, positions, true),
        "写回已确认、开关开启、有坐标且字段可用时才剔除");
  }

  /** 只有「已确认写回 + 字段齐全 + 开关开启 + 有坐标」四个条件同时成立才剔除，任一不满足都保留实体。 */
  @Test
  void blockEntityRemovalRequiresEveryPrecondition() {
    int[] positions = {1 << 8 | 2 << 4 | 3};
    assertFalse(ChunkPacketAccessor.shouldRemoveBlockEntities(true, false, positions, true),
        "开关关闭时不剔除");
    assertFalse(ChunkPacketAccessor.shouldRemoveBlockEntities(true, true, positions, false),
        "剔除所需字段未定位到（降级）时不剔除");
    assertFalse(ChunkPacketAccessor.shouldRemoveBlockEntities(true, true, new int[0], true),
        "没有被伪装坐标时不剔除");
    assertFalse(ChunkPacketAccessor.shouldRemoveBlockEntities(false, false, new int[0], false),
        "全部条件都不满足时当然不剔除");
  }

  /**
   * 剔除段异常必须有观测点（旧实现 {@code catch (Throwable ignored)} 完全静默）：失败计数递增，
   * 且一次性中文提示不会因重复调用而抛异常（闸门是静态的，这里只断言计数这一可观测点）。
   */
  @Test
  void blockEntityFilterFailureIsObservable() {
    int before = ChunkPacketAccessor.blockEntityFilterFailures();
    ChunkPacketAccessor.recordBlockEntityFilterFailure(new IllegalStateException("模拟字段结构不符"));
    ChunkPacketAccessor.recordBlockEntityFilterFailure(new IllegalStateException("再次失败"));

    assertEquals(before + 2, ChunkPacketAccessor.blockEntityFilterFailures(),
        "每次剔除段失败都必须计入观测点（不能再静默吞掉）");
  }

  /** 坐标编码 y << 8 | z << 4 | x：方块实体的绝对 Y 先减 minHeight 再比对。 */
  @Test
  void isObfuscatedMatchesPackedPositions() {
    int[] positions = {5 << 8 | 12 << 4 | 3};

    assertTrue(ChunkPacketAccessor.isObfuscated(5, 3, 12, positions), "同一坐标必须命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(5, 3, 13, positions), "z 不同不得命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(5, 4, 12, positions), "x 不同不得命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(6, 3, 12, positions), "y 不同不得命中");
  }

  /** 绝对 Y 换算后低于世界最低高度的方块实体不可能在清单里（越界防护）。 */
  @Test
  void negativeRelativeYIsNeverObfuscated() {
    int[] positions = {0 << 8 | 0 << 4 | 0};
    assertFalse(ChunkPacketAccessor.isObfuscated(-1, 0, 0, positions),
        "相对 Y 为负（世界最低高度以下）不得命中");
  }

  /**
   * packedXZ 的 byte 高位形态必须被掩回无符号（回归 P1：byte 字段 0xF0 作为有符号数读出为 -16，
   * 直接 {@code >> 4} 会符号扩展成负 sectionX，该 section 的方块实体永远剔不掉）。
   */
  @Test
  void packedXzKeepsHighBitOfByteUnsigned() {
    // byte 字段 0xF0 有符号读出为 -16：直接右移即得到错误的负 sectionX（旧实现）
    int signed = (byte) 0xF0;
    assertEquals(-16, signed, "前置：byte 0xF0 按有符号读出为 -16");
    assertEquals(-1, signed >> 4, "旧实现：符号扩展把 sectionX 变成 -1（永远匹配不上清单）");

    // 掩码后只取低 8 位：sectionX=15、sectionZ=0，且对 int/short 形态同样成立
    int normalized = ChunkPacketAccessor.normalizePackedXz(signed);
    assertEquals(0xF0, normalized);
    assertEquals(15, normalized >> 4, "sectionX 必须是 15，不得符号扩展");
    assertEquals(0, normalized & 0x0F, "sectionZ 必须是 0");
    assertEquals(0xF0, ChunkPacketAccessor.normalizePackedXz(0x1F0), "int 形态同样只取低 8 位");
    assertEquals(0xF0, ChunkPacketAccessor.normalizePackedXz((short) 0xF0), "short 形态同样只取低 8 位");

    // 与纯判定函数联动：该 section 必须能命中清单（旧实现因 sectionX=-1 而漏剔）
    int[] positions = {1 << 8 | 0 << 4 | 15};
    assertTrue(ChunkPacketAccessor.isObfuscated(1, normalized >> 4, normalized & 0x0F, positions),
        "x=15/z=0 的方块实体必须被识别为需要剔除");
  }

  /** 与生产路径一致的换算：绝对 Y − minHeight = 相对 Y。 */
  @Test
  void minHeightConversionMatchesProduction() {
    int minHeight = -64;
    int absoluteY = -20;
    int relativeY = absoluteY - minHeight; // 44
    int[] positions = {44 << 8 | 7 << 4 | 9};

    assertTrue(ChunkPacketAccessor.isObfuscated(relativeY, 9, 7, positions));
    assertEquals(44, relativeY);
  }

  /**
   * 坐标字段读不出明确数值（null / 非数值）时必须整条跳过、保留该方块实体；绝不用 0 冒充合法坐标。
   *
   * <p>0 是「section (0,0)」的合法 packedXZ：旧实现把读取失败的坐标当成 0，会命中下面这个合法清单项，
   * 误删方块实体并产生「矿石可见 + 实体消失」的可检测不一致包。
   */
  @Test
  void unreadableCoordinateFieldsKeepEntry() {
    int[] positions = {0 << 8 | 0 << 4 | 0}; // (absoluteY - minHeight = 0, sectionZ = 0, sectionX = 0)

    assertTrue(ChunkPacketAccessor.isEntryObfuscated((byte) 0, 0, 0, positions),
        "两个坐标字段都是合法数值且命中清单 → 需要剔除");
    assertFalse(ChunkPacketAccessor.isEntryObfuscated(null, 0, 0, positions),
        "packedXZ 读不出（null）必须按「不可用」跳过，不得当 0 命中 (0,0)");
    assertFalse(ChunkPacketAccessor.isEntryObfuscated((byte) 0, null, 0, positions),
        "y 读不出（null）必须按「不可用」跳过");
    assertFalse(ChunkPacketAccessor.isEntryObfuscated("x", 0, 0, positions),
        "非数值字段同样视为不可用（不得当 0）");
  }

  /**
   * 升序清单必须能被二分查找命中（首/中/末）与正确排除：{@link ChunkPacketAccessor#isObfuscated}
   * 已由线性扫描改为 {@code Arrays.binarySearch}，依赖「清单升序」这一生产不变量
   * （{@code ObfuscationProcessor} 按 section、再按元素序号递增生成）。
   */
  @Test
  void sortedListingIsMatchedByBinarySearch() {
    int[] sorted = {
        1 << 8 | 0 << 4 | 0,    // y=1, z=0, x=0（最小）
        3 << 8 | 5 << 4 | 7,    // y=3, z=5, x=7（中间）
        9 << 8 | 15 << 4 | 15,  // y=9, z=15, x=15（最大）
    };

    assertTrue(ChunkPacketAccessor.isObfuscated(1, 0, 0, sorted), "首个元素必须命中");
    assertTrue(ChunkPacketAccessor.isObfuscated(3, 7, 5, sorted), "中间元素必须命中");
    assertTrue(ChunkPacketAccessor.isObfuscated(9, 15, 15, sorted), "末个元素必须命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(2, 0, 0, sorted), "y 不在清单不得命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(9, 15, 14, sorted), "x 不同不得命中");
    assertFalse(ChunkPacketAccessor.isObfuscated(-1, 0, 0, sorted), "相对 Y 为负必须短路返回 false");
  }
}
