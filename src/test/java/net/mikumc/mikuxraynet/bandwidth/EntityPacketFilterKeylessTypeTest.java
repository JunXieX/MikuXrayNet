package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * 无键实体类型的回归测试（v1.7.0 真机事故）。
 *
 * <p><b>背景</b>：部分服务端与其衍生端会在 {@code EntityType} 里附加没有命名空间键的条目（如 {@code UNKNOWN}）。
 * 旧实现在构造期遍历 {@code EntityType.values()} 并对每个类型调用 {@code getKey()}，一旦碰到这类条目就抛
 * {@link IllegalArgumentException}（"EntityType doesn't have key! Is it UNKNOWN?"），导致**整个带宽子模块装配失败**
 * （真机日志：「带宽子模块注册失败，已单独停用该模块」）。热路径的字符串回退同样会踩到该条目。
 *
 * <p>本测试锁住契约：<b>对任意实体类型取键都不得抛异常</b>，无键条目一律返回 {@code null}
 * （由既有「跳过 / 回退字符串比较」逻辑兜底）。若将来有人把实现改回裸的 {@code type.getKey().getKey()}，
 * 在含无键条目的服务端上就会立刻抛异常并被本测试捕获。
 */
class EntityPacketFilterKeylessTypeTest {

  /** 全量探测：不得抛异常，且结果必须与原生命名空间键一致（无键则 {@code null}）。 */
  @Test
  void probingAnyEntityTypeNeverThrowsAndMatchesRawKeyLookup() {
    for (EntityType type : EntityType.values()) {
      String expected = rawKeyPathOrNull(type);
      assertDoesNotThrow(() -> EntityPacketFilter.keyPathOrNull(type),
          "对 " + type + " 取键不得抛异常（无键条目应返回 null）");
      assertEquals(expected, EntityPacketFilter.keyPathOrNull(type),
          "对 " + type + " 的取键结果应与原生命名空间键一致（无键则为 null）");
    }
  }

  /** 有键的类型必须给出非空的路径键（确认跳过逻辑没有误伤正常类型）。 */
  @Test
  void keyedTypesStillYieldNonBlankPath() {
    EntityType armorStand = EntityType.ARMOR_STAND;
    String path = EntityPacketFilter.keyPathOrNull(armorStand);
    assertFalse(path == null || path.isBlank(), "有键的实体类型必须给出非空路径键");
    assertEquals("armor_stand", path, "路径键不带命名空间且为小写");
  }

  /** 测试侧独立实现：直接调用原生 API，并对无键条目的异常按 {@code null} 处理。 */
  private static String rawKeyPathOrNull(EntityType type) {
    try {
      return type.getKey() == null ? null : type.getKey().getKey();
    } catch (RuntimeException keyless) {
      return null;
    }
  }
}