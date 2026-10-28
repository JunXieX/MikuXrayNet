package net.mikumc.mikuxraynet.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** {@link OcclusionPayload} 的编解码往返与畸形输入拒绝测试。 */
class OcclusionPayloadTest {

  @Test
  void sectionCountPeekMatchesEncodedColumn() {
    SectionOcclusion[] column = {EmptyOcclusion.INSTANCE, SolidOcclusion.INSTANCE,
        EmptyOcclusion.INSTANCE};
    assertEquals(column.length, OcclusionPayload.sectionCount(OcclusionPayload.encode(column)),
        "只读头部探测的段数必须与实际编码一致（命中路径据此跳过整列解码）");
    assertEquals(0, OcclusionPayload.sectionCount(OcclusionPayload.encode(new SectionOcclusion[0])));
    assertEquals(-1, OcclusionPayload.sectionCount(null));
    assertEquals(-1, OcclusionPayload.sectionCount(new byte[] {1, 2, 3}), "长度不足必须判非法");
    assertEquals(-1, OcclusionPayload.sectionCount(new byte[] {-1, -1, -1, -1}),
        "段数为负必须判非法");
  }

  @Test
  void roundTripPreservesAllThreeStates() {
    BitmapOcclusion bitmap = new BitmapOcclusion();
    bitmap.setOccluding(0, 0, 0, true);
    bitmap.setOccluding(15, 15, 15, true);
    bitmap.setOccluding(3, 4, 5, true);

    SectionOcclusion[] column = {EmptyOcclusion.INSTANCE, SolidOcclusion.INSTANCE, bitmap,
        EmptyOcclusion.INSTANCE, SolidOcclusion.INSTANCE};
    byte[] blob = OcclusionPayload.encode(column);
    SectionOcclusion[] decoded = OcclusionPayload.decode(blob);

    assertNotNull(decoded);
    assertEquals(column.length, decoded.length);
    assertSame(EmptyOcclusion.INSTANCE, decoded[0]);
    assertSame(SolidOcclusion.INSTANCE, decoded[1]);
    assertFalse(decoded[2].isSolid());
    assertSame(EmptyOcclusion.INSTANCE, decoded[3]);
    assertSame(SolidOcclusion.INSTANCE, decoded[4]);
    // 位图逐格一致（顺序与内容都不能漂）
    for (int x = 0; x < 16; x++) {
      for (int y = 0; y < 16; y++) {
        for (int z = 0; z < 16; z++) {
          assertEquals(bitmap.isOccludingLocal(x, y, z), decoded[2].isOccludingLocal(x, y, z),
              "坐标 (" + x + "," + y + "," + z + ")");
        }
      }
    }
  }

  @Test
  void emptyColumnRoundTrips() {
    SectionOcclusion[] decoded = OcclusionPayload.decode(OcclusionPayload.encode(new SectionOcclusion[0]));
    assertNotNull(decoded);
    assertEquals(0, decoded.length);
  }

  @Test
  void encodedLengthMatchesActualEncoding() {
    SectionOcclusion[] column = {EmptyOcclusion.INSTANCE, SolidOcclusion.INSTANCE, new BitmapOcclusion(),
        SolidOcclusion.INSTANCE, EmptyOcclusion.INSTANCE};
    assertEquals(OcclusionPayload.encodedLength(column), OcclusionPayload.encode(column).length,
        "容量预判必须与实际编码长度一致");
  }

  @Test
  void encodeTreatsNullAsKnownEmpty() {
    // 生产路径的列不含 null，但编码必须对 null 宽容（按「已知全不遮挡」编码，绝不编出非法负载）
    SectionOcclusion[] decoded = OcclusionPayload.decode(
        OcclusionPayload.encode(new SectionOcclusion[] {null}));
    assertNotNull(decoded);
    assertSame(EmptyOcclusion.INSTANCE, decoded[0]);
  }

  @Test
  void decodeRejectsMalformedPayloads() {
    assertNull(OcclusionPayload.decode(null), "null 必须拒绝");
    assertNull(OcclusionPayload.decode(new byte[0]), "空数组必须拒绝");
    assertNull(OcclusionPayload.decode(new byte[] {0, 0, 0}), "不足头部长度必须拒绝");

    // 声明 2 段但只给了 1 个标签
    byte[] shortTags = {0, 0, 0, 2, 0};
    assertNull(OcclusionPayload.decode(shortTags), "标签不足必须拒绝");

    // 非法标签
    byte[] badTag = {0, 0, 0, 1, 7};
    assertNull(OcclusionPayload.decode(badTag), "非法标签必须拒绝");

    // 标签声明有 1 个位图段，但位图字节不足
    byte[] shortBitmap = {0, 0, 0, 1, 2, 1, 2, 3};
    assertNull(OcclusionPayload.decode(shortBitmap), "位图字节不足必须拒绝");

    // 长度恰好但多出尾部字节（版本不符/损坏）也必须拒绝
    SectionOcclusion[] column = {SolidOcclusion.INSTANCE};
    byte[] blob = OcclusionPayload.encode(column);
    byte[] withTrailing = Arrays.copyOf(blob, blob.length + 1);
    assertNull(OcclusionPayload.decode(withTrailing), "多余的尾部字节必须拒绝（宁可拿不到，不可拿错）");
  }

  @Test
  void largeBitmapColumnRoundTripsExactly() {
    // 多个位图段（触发 word 复用缓冲的路径）：每段内容必须互不串扰
    SectionOcclusion[] column = new SectionOcclusion[24];
    Arrays.fill(column, null);
    BitmapOcclusion first = new BitmapOcclusion();
    first.setOccluding(1, 1, 1, true);
    BitmapOcclusion second = new BitmapOcclusion();
    second.setOccluding(2, 2, 2, true);
    second.setOccluding(10, 10, 10, true);
    column[0] = first;
    column[23] = second;

    SectionOcclusion[] decoded = OcclusionPayload.decode(OcclusionPayload.encode(column));
    assertNotNull(decoded);
    assertEquals(24, decoded.length);
    assertTrue(decoded[0].isOccludingLocal(1, 1, 1));
    assertFalse(decoded[0].isOccludingLocal(2, 2, 2), "第一段不得混入第二段的位");
    assertTrue(decoded[23].isOccludingLocal(2, 2, 2));
    assertTrue(decoded[23].isOccludingLocal(10, 10, 10));
    assertFalse(decoded[23].isOccludingLocal(1, 1, 1), "第二段不得混入第一段的位");
    assertSame(EmptyOcclusion.INSTANCE, decoded[12]);
  }

  @Test
  void decodedBitmapIsIndependentOfSource() {
    BitmapOcclusion bitmap = new BitmapOcclusion();
    bitmap.setOccluding(1, 1, 1, true);
    SectionOcclusion[] decoded = OcclusionPayload.decode(
        OcclusionPayload.encode(new SectionOcclusion[] {bitmap}));
    assertNotNull(decoded);
    // 解码结果必须是独立副本：改动原位图不得影响解码结果
    bitmap.setOccluding(1, 1, 1, false);
    assertTrue(decoded[0].isOccludingLocal(1, 1, 1), "解码结果必须是独立副本");
  }
}
