package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
  void emptyChunkBytesAreTreatedAsMiss() {
    assertNull(DiskPayload.decode(DiskPayload.encode(1L, new int[0], null, null), 1L),
        "区块字节为空视为未命中（fail-open）");
  }

  @Test
  void mismatchedHashOrMalformedPayloadIsRejected() {
    byte[] payload = DiskPayload.encode(1L, new int[] {5, 6}, null, new byte[] {9, 9});

    assertNull(DiskPayload.decode(payload, 2L), "指纹不符必须按未命中处理");
    assertNull(DiskPayload.decode(null, 1L), "null 必须按未命中处理");
    assertNull(DiskPayload.decode(new byte[] {1, 2, 3}, 1L), "长度不足必须按未命中处理");

    // 声明 2 个坐标，但只给到第 1 个坐标的字节：坐标被截断，必须判为未命中而不是读出脏数据
    byte[] truncated = Arrays.copyOf(payload, DiskPayload.HEADER_SIZE + Integer.BYTES);
    assertNull(DiskPayload.decode(truncated, 1L), "坐标截断必须按未命中处理");

    // 声明了遮挡长度但后面不足：同样必须判为未命中
    byte[] occlusionPayload = DiskPayload.encode(1L, new int[0], new byte[64], new byte[] {7});
    byte[] badOcclusion = Arrays.copyOf(occlusionPayload, occlusionPayload.length - 10);
    assertNull(DiskPayload.decode(badOcclusion, 1L), "遮挡负载被截断必须按未命中处理");
  }
}