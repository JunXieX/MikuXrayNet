package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 磁盘缓存负载信封契约测试：
 * {@code [i64 指纹][i32 坐标个数][i32 × N 坐标][i32 遮挡长度][遮挡负载][区块字节]} 必须逐字段往返，
 * <b>尤其是被伪装坐标清单与整列遮挡数据</b>——前者供磁盘命中时重写显形索引，后者供磁盘命中时恢复
 * 遮挡数据（位图只在解码时生成，命中路径不解码）。
 */
class DiskPayloadTest {

  @Test
  void roundTripKeepsCoordinatesWithZeroPositions() {
    byte[] data = {1, 2, 3, 4, 5};

    DiskPayload.Decoded decoded =
        DiskPayload.decode(DiskPayload.encode(42L, new int[0], null, data), 42L);

    assertNotNull(decoded);
    assertEquals(42L, decoded.sourceHash());
    assertEquals(0, decoded.positions().length, "0 个坐标（无替换）也必须能往返");
    assertNull(decoded.occlusion(), "未携带遮挡负载时必须往返为 null（未启用该功能）");
    assertArrayEquals(data, decoded.data());
  }

  @Test
  void roundTripKeepsHundredsOfCoordinates() {
    int[] positions = new int[174];
    for (int index = 0; index < positions.length; index++) {
      positions[index] = index * 7 - 300;
    }
    byte[] data = new byte[512];
    for (int index = 0; index < data.length; index++) {
      data[index] = (byte) (index * 31);
    }

    DiskPayload.Decoded decoded =
        DiskPayload.decode(DiskPayload.encode(7L, positions, null, data), 7L);

    assertNotNull(decoded);
    assertArrayEquals(positions, decoded.positions(),
        "被伪装坐标必须完整往返（否则磁盘命中的区块永远不进显形索引）");
    assertArrayEquals(data, decoded.data());
  }

  @Test
  void roundTripKeepsOcclusionPayload() {
    int[] positions = {1, 2, 3};
    byte[] data = {4, 5};
    byte[] occlusion = new byte[600];
    for (int index = 0; index < occlusion.length; index++) {
      occlusion[index] = (byte) (index * 13);
    }

    DiskPayload.Decoded decoded =
        DiskPayload.decode(DiskPayload.encode(9L, positions, occlusion, data), 9L);

    assertNotNull(decoded);
    assertArrayEquals(occlusion, decoded.occlusion(),
        "整列遮挡数据必须完整往返（否则缓存命中的区块永无遮挡数据）");
    assertArrayEquals(data, decoded.data());
  }

  @Test
  void encodedLengthMatchesActualEncoding() {
    int[] positions = {10, 20, 30};
    byte[] occlusion = new byte[42];
    byte[] data = new byte[100];
    int predicted = DiskPayload.encodedLength(positions, occlusion, data);
    assertEquals(predicted, DiskPayload.encode(1L, positions, occlusion, data).length,
        "容量预判必须与实际编码长度一致（否则提前拒写的判定会漂移）");
    assertEquals(DiskPayload.encodedLength(null, null, data),
        DiskPayload.encode(1L, null, null, data).length);
  }

  @Test
  void emptyChunkBytesOnlyValidWithoutPositions() {
    // 无改写的区块不写字节：命中后只用于「跳过解码」与恢复遮挡数据，writeBack 见空坐标清单直接返回
    DiskPayload.Decoded unchanged = DiskPayload.decode(
        DiskPayload.encode(1L, new int[0], null, null), 1L);
    assertTrue(unchanged.usable(), "0 个坐标的条目允许不带区块字节");
    assertEquals(0, unchanged.data().length);
    // 有伪装坐标却没有区块字节属畸形：信封自洽性检查在「坐标个数」这一步就把它拦下（剩余长度放不下
    // count 个坐标 + 遮挡长度 + 至少 1 字节区块数据），因此归到坐标个数非法——仍按未命中处理。
    assertEquals(DiskPayload.Reject.POSITION_COUNT_INVALID,
        rejection(DiskPayload.encode(1L, new int[] {5}, null, null), 1L),
        "有伪装坐标却没有区块字节属畸形，必须按未命中处理");
  }

  @Test
  void mismatchedHashAndMalformedPayloadsCarryDistinctReasons() {
    byte[] payload = DiskPayload.encode(1L, new int[] {5, 6}, null, new byte[] {9, 9});

    assertEquals(DiskPayload.Reject.SOURCE_HASH_MISMATCH, rejection(payload, 2L),
        "指纹不符必须按未命中处理，且原因要与信封故障分开");
    assertEquals(DiskPayload.Reject.TRUNCATED, rejection(null, 1L), "null 必须按未命中处理");
    assertEquals(DiskPayload.Reject.TRUNCATED, rejection(new byte[] {1, 2, 3}, 1L),
        "长度不足以读出信封头必须按未命中处理");

    // 声明 2 个坐标，但只给到第 1 个坐标的字节：坐标被截断，必须判为未命中而不是读出脏数据
    byte[] truncated = Arrays.copyOf(payload, DiskPayload.HEADER_SIZE + Integer.BYTES);
    assertEquals(DiskPayload.Reject.POSITION_COUNT_INVALID, rejection(truncated, 1L),
        "坐标截断必须按未命中处理");

    // 声明了遮挡长度但后面不足：同样必须判为未命中
    byte[] occlusionPayload = DiskPayload.encode(1L, new int[0], new byte[64], new byte[] {7});
    byte[] badOcclusion = Arrays.copyOf(occlusionPayload, occlusionPayload.length - 10);
    assertEquals(DiskPayload.Reject.OCCLUSION_LENGTH_INVALID, rejection(badOcclusion, 1L),
        "遮挡负载被截断必须按未命中处理");
  }

  /**
   * 信封自洽（遮挡长度把剩余字节吃光）但声明了伪装坐标、却没有区块字节：命中后会按空字节写回封包，
   * 必须拒绝。这条路径只能手工构造——{@link DiskPayload#encode} 不会产出这种条目。
   */
  @Test
  void emptyChunkBytesWithPositionsIsEnvelopeFault() {
    ByteBuffer buffer = ByteBuffer.allocate(DiskPayload.HEADER_SIZE + 4 + 4 + 12);
    buffer.putLong(7L).putInt(1).putInt(5).putInt(12).put(new byte[12]);

    assertEquals(DiskPayload.Reject.EMPTY_DATA_WITH_POSITIONS, rejection(buffer.array(), 7L),
        "有伪装坐标却没有任何区块字节必须拒绝");
  }

  /**
   * 拒绝原因必须由<b>同一次</b>解码给出、判定优先级不变：指纹校验在结构校验之前，
   * 因此「指纹不符 + 结构损坏」只能报指纹不符（若为诊断再解一遍，很容易反过来说成信封故障）。
   */
  @Test
  void hashMismatchIsReportedBeforeStructuralDamage() {
    byte[] bothWrong = Arrays.copyOf(
        DiskPayload.encode(1L, new int[] {5, 6}, null, new byte[] {9, 9}),
        DiskPayload.HEADER_SIZE + Integer.BYTES);

    assertEquals(DiskPayload.Reject.SOURCE_HASH_MISMATCH, rejection(bothWrong, 2L),
        "优先级必须与改动前一致：先比指纹，再判结构");
  }

  /** 可用结果不带原因：正常路径的解码结果与改动前完全一致。 */
  @Test
  void usableResultCarriesNoRejectReason() {
    DiskPayload.Decoded decoded = DiskPayload.decode(
        DiskPayload.encode(3L, new int[0], null, new byte[] {1}), 3L);

    assertTrue(decoded.usable());
    assertNull(decoded.reject(), "可用结果不得带拒绝原因");
    assertEquals(3L, decoded.sourceHash());
  }

  /** 取拒绝原因，并顺带锁定「解码恒返回对象、被拒即不可用且不携带数据」这一契约。 */
  private static DiskPayload.Reject rejection(byte[] payload, long expectedSourceHash) {
    DiskPayload.Decoded decoded = DiskPayload.decode(payload, expectedSourceHash);
    assertNotNull(decoded, "解码必须返回结果对象（拒绝也用结果表示，不再返回 null）");
    assertFalse(decoded.usable(), "该负载必须被判为拒绝");
    assertNull(decoded.positions(), "被拒结果不得携带坐标（误当命中会立刻空指针而非静默写回空字节）");
    return decoded.reject();
  }
}