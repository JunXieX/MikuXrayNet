package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 磁盘缓存负载信封契约测试：{@code [i64 指纹][i32 坐标个数][i32 × N 坐标][区块字节]} 必须逐字段往返，
 * <b>尤其是被伪装坐标清单</b>——磁盘缓存命中的区块要靠它重新写入显形索引，否则会静默失效。
 */
class DiskPayloadTest {

  @Test
  void roundTripKeepsCoordinatesWithZeroPositions() {
    byte[] data = {1, 2, 3, 4, 5};

    DiskPayload.Decoded decoded = DiskPayload.decode(DiskPayload.encode(42L, new int[0], data), 42L);

    assertNotNull(decoded);
    assertEquals(42L, decoded.sourceHash());
    assertEquals(0, decoded.positions().length, "0 个坐标（无替换）也必须能往返");
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
        DiskPayload.decode(DiskPayload.encode(7L, positions, data), 7L);

    assertNotNull(decoded);
    assertArrayEquals(positions, decoded.positions(),
        "被伪装坐标必须完整往返（否则磁盘命中的区块永远不进显形索引）");
    assertArrayEquals(data, decoded.data());
  }

  @Test
  void emptyChunkBytesAreTreatedAsMiss() {
    assertNull(DiskPayload.decode(DiskPayload.encode(1L, new int[0], null), 1L),
        "区块字节为空视为未命中（fail-open）");
  }

  @Test
  void mismatchedHashOrMalformedPayloadIsRejected() {
    byte[] payload = DiskPayload.encode(1L, new int[] {5, 6}, new byte[] {9, 9});

    assertNull(DiskPayload.decode(payload, 2L), "指纹不符必须按未命中处理");
    assertNull(DiskPayload.decode(null, 1L), "null 必须按未命中处理");
    assertNull(DiskPayload.decode(new byte[] {1, 2, 3}, 1L), "长度不足必须按未命中处理");

    // 声明 2 个坐标，但只给到第 1 个坐标的字节：坐标被截断，必须判为未命中而不是读出脏数据
    byte[] truncated = Arrays.copyOf(payload, DiskPayload.HEADER_SIZE + Integer.BYTES);
    assertNull(DiskPayload.decode(truncated, 1L), "坐标截断必须按未命中处理");
  }
}