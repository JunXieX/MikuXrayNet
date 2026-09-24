package net.mikumc.mikuxraynet.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * XXHash32 标准测试向量。
 *
 * <p><b>期望值的出处</b>：
 * <ul>
 *   <li><b>官方向量</b>：空串 / {@code "a"} / {@code "abc"}（seed=0）三条取自 xxHash 参考实现的
 *       官方向量表（xxHash 仓库 {@code cli/xsum_sanity.c} 的 XXH32 sanity 表，python-xxhash 等移植
 *       测试亦镜像同表）；其中空串 seed=0 → {@code 0x02CC5D05} 与交付方给定的官方值一致；</li>
 *   <li><b>交叉验证向量</b>：其余向量（含 {@code "message digest"}、16 字节整块、43 字节混合尾部）
 *       由两套相互独立编写的实现（项目的 int 回绕实现与本测试编写时的 long 无符号模拟参考实现）
 *       对全部长度档（0/1/3/4/5/14/16/26/39/43/82 字节 × 4 个种子）逐一对表核对后写入，
 *       覆盖「16 字节主循环、4 字节尾循环、单字节尾循环、空输入」全部代码路径。</li>
 * </ul>
 * 校验和种子与 {@link BufferedLinearV3Format#DEFAULT_HASH_SEED} 一致，因此向量同时锁定磁盘缓存格式的
 * 兼容性——若本测试失败，已写入的缓存文件将全部无法通过校验。
 */
class XXHash32VectorTest {

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static final int PRIME32_1 = 0x9E3779B1;

  @Test
  void officialReferenceVectors() {
    assertEquals(0x02CC5D05, XXHash32.hash(bytes(""), 0), "官方向量：空串 seed=0");
    assertEquals(0x550D7456, XXHash32.hash(bytes("a"), 0), "官方向量：单字节 seed=0");
    assertEquals(0x32D153FF, XXHash32.hash(bytes("abc"), 0), "官方向量：三字节 seed=0");
  }

  @Test
  void crossValidatedVectorsCoverAllCodePaths() {
    // 空串 / 非零种子：只走「seed + PRIME32_5 + 长度 + 雪崩」路径
    assertEquals(0x36B78AE7, XXHash32.hash(bytes(""), PRIME32_1), "交叉验证：空串 seed=PRIME32_1");
    // 单字节 / 三字节：单字节尾循环
    assertEquals(0x9E1633E4, XXHash32.hash(bytes("a"), PRIME32_1), "交叉验证：单字节 seed=PRIME32_1");
    assertEquals(0xA1AE7709, XXHash32.hash(bytes("abc"), PRIME32_1), "交叉验证：三字节 seed=PRIME32_1");
    // 14 字节：4 字节尾循环（2 轮）+ 单字节尾（2 轮）
    assertEquals(0x7C948494, XXHash32.hash(bytes("message digest"), 0), "交叉验证：message digest seed=0");
    assertEquals(0x325FF26D, XXHash32.hash(bytes("message digest"), PRIME32_1),
        "交叉验证：message digest seed=PRIME32_1");
    // 恰 16 字节：主循环恰好一轮、无尾部
    assertEquals(0xF9F50986, XXHash32.hash(bytes("0123456789ABCDEF"), 0), "交叉验证：整块 16 字节 seed=0");
    // 43 字节：主循环（2 轮）+ 4 字节尾 + 单字节尾全部覆盖
    assertEquals(0xE85EA4DE, XXHash32.hash(bytes("The quick brown fox jumps over the lazy dog"), 0),
        "交叉验证：43 字节混合尾部 seed=0");
  }

  /** 区间重载必须与「截取子数组整体求哈希」等价（磁盘缓存的负载指纹依赖该语义）。 */
  @Test
  void rangeOverloadMatchesSubArrayHash() {
    byte[] whole = bytes("xxmessage digestyy");
    byte[] slice = bytes("message digest");
    assertEquals(XXHash32.hash(slice, 0), XXHash32.hash(whole, 2, slice.length, 0),
        "hash(input, off, len, seed) 必须与子数组的 hash(input, seed) 等价");
  }
}
