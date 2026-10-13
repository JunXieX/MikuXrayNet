package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link MovePacketFields} 的字段定位与「无位移」判定：必须能沿<b>继承链</b>取到基类里的字段，
 * 且同时覆盖两种服务端形态。
 *
 * <p><b>为什么必须有这两条用例</b>：MC 1.21.2 的包族重构把 {@code entityId} 与位移字段从子类挪进了基类，
 * 而 ProtocolLib 的类型化访问器只看具体类的声明字段——正是这一个差别让「零位移取消」静默失效了好几个版本
 * （真机：纯位移包 1703 / 字段形态不符 1703 / 取消 0）。随后 <b>26.3 又把三个 {@code short xa/ya/za}
 * 换成了单个 {@code VecDelta}</b>（路径步进编码），所以两种形态都得钉住。
 */
class MovePacketFieldsTest {

  /** 与 ≤26.2 的基类同构：{@code int entityId; short xa, ya, za}。 */
  private static class Base {
    private int entityId;
    private short xa;
    private short ya;
    private short za;
  }

  /** 与 ≤26.2 的子类同构：自身不声明任何实例字段。 */
  private static class Pos extends Base {
  }

  /** 与 26.3 的 {@code VecDelta} 同构的桩：只保留判定所需的最小面（步数）。 */
  private interface VecDelta {
    VecDelta ZERO = () -> 0;

    int stepCount();
  }

  /** 与 26.3 的基类同构：{@code int entityId; VecDelta delta}。 */
  private static class Base263 {
    private int entityId;
    private VecDelta delta;
  }

  /** 与 26.3 的子类同构：自身不声明任何实例字段。 */
  private static class Pos263 extends Base263 {
  }

  @Test
  void resolvesFieldsDeclaredInSuperclassOnly() {
    // 用 Base 类型的引用来赋值（private 成员不能经子类引用访问），但运行时类型仍是 Pos——
    // MovePacketFields.of 看到的就是 Pos，正好复现「字段只在基类、子类无声明」这一 26.2 布局。
    Base handle = new Pos();
    handle.entityId = 7;
    handle.xa = 1;
    handle.ya = 2;
    handle.za = 3;

    MovePacketFields.Access access = MovePacketFields.of(handle);

    assertNotNull(access, "字段在基类时也必须能定位（这正是 ProtocolLib 类型化访问器做不到的地方）");
    assertEquals(MovePacketFields.Shape.SHORTS, access.shape());
    assertEquals(7, (int) access.entityId().get(handle));
    assertEquals(1, (short) access.deltaX().get(handle));
    assertEquals(2, (short) access.deltaY().get(handle));
    assertEquals(3, (short) access.deltaZ().get(handle));
    assertFalse(isZeroMove(access, handle), "三轴不全为 0 → 不得判为无位移（否则会取消携带位移的包）");

    // 同一布局下三轴全 0（short 默认值）才是可取消的那种包
    Base zeroed = new Pos();
    zeroed.entityId = 1;
    assertTrue(isZeroMove(MovePacketFields.of(zeroed), zeroed), "三个 short 全 0 即无位移（本功能的目标情形）");
  }

  /** 26.3 形态：{@code VecDelta} 步数为 0（或就是 ZERO 单例）即无位移。 */
  @Test
  void vecDeltaShapeCountsZeroStepsAsNoMovement() {
    Base263 bySingleton = new Pos263();
    bySingleton.entityId = 5;
    bySingleton.delta = VecDelta.ZERO;
    MovePacketFields.Access access = MovePacketFields.of(bySingleton);

    assertNotNull(access, "26.3 形态（单个 VecDelta）必须被识别");
    assertEquals(MovePacketFields.Shape.VEC_DELTA, access.shape());
    assertEquals(5, (int) access.entityId().get(bySingleton));
    assertTrue(isZeroMove(access, bySingleton), "位移就是 ZERO 单例 → 无位移");

    Base263 zeroSteps = new Pos263();
    zeroSteps.entityId = 6;
    zeroSteps.delta = () -> 0; // 非 ZERO 单例但步数为 0：同样无位移
    assertTrue(isZeroMove(MovePacketFields.of(zeroSteps), zeroSteps), "步数为 0 → 无位移");
  }

  /** <b>安全红线</b>：只要有真实位移（步数 > 0）就绝不能判为冗余——取消它会让客户端实体位置卡住。 */
  @Test
  void vecDeltaShapeNeverCancelsRealMovement() {
    Base263 handle = new Pos263();
    handle.entityId = 8;
    handle.delta = () -> 2;

    MovePacketFields.Access access = MovePacketFields.of(handle);

    assertNotNull(access);
    assertFalse(isZeroMove(access, handle), "步数 > 0 表示该包携带真实位移，绝不可取消");
  }

  /** 读不到位移对象时按「有位移」处理（fail-open 到放行，而不是放行之外的任何动作）。 */
  @Test
  void nullVecDeltaIsTreatedAsMovement() {
    Base263 handle = new Pos263();
    handle.entityId = 9;
    handle.delta = null;

    MovePacketFields.Access access = MovePacketFields.of(handle);

    assertNotNull(access);
    assertFalse(isZeroMove(access, handle), "读不到位移时必须判「有位移」（放行原包）");
  }

  /** 位移顺序必须按<b>名字</b>定，不依赖 {@code getDeclaredFields} 的返回顺序（该顺序无规范保证）。 */
  @Test
  void deltaOrderFollowsFieldNamesNotDeclarationOrder() {
    Scrambled handle = new Scrambled();
    handle.entityId = 9;
    handle.za = 30;
    handle.xa = 10;
    handle.ya = 20;

    MovePacketFields.Access access = MovePacketFields.of(handle);

    assertNotNull(access);
    assertEquals(9, (int) access.entityId().get(handle));
    assertEquals(10, (short) access.deltaX().get(handle), "dx 必须取 xa，而不是声明顺序里的第一个 short");
    assertEquals(20, (short) access.deltaY().get(handle));
    assertEquals(30, (short) access.deltaZ().get(handle));
  }

  /** 既没有 VecDelta 也凑不齐三个 short → 判为「定位失败」（调用方 fail-open 放行，绝不误判取消）。 */
  @Test
  void missingDeltasAreUnresolved() {
    assertNull(MovePacketFields.of(new TooFewShorts()), "只有两个 short 时必须判为定位失败");
  }

  /** 没有实体 id 字段 → 同样判为定位失败（白名单判定需要它）。 */
  @Test
  void missingEntityIdIsUnresolved() {
    assertNull(MovePacketFields.of(new NoEntityIdShorts()), "缺少 entityId 时必须判为定位失败");
  }

  @Test
  void nullHandleIsUnresolved() {
    assertNull(MovePacketFields.of(null));
  }

  /** 判定包装：把受检异常收敛成断言可用的布尔（读取失败即视为「不可取消」）。 */
  private static boolean isZeroMove(MovePacketFields.Access access, Object handle) {
    try {
      return MovePacketFields.isZeroMove(access, handle);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("读取位移时抛出异常", exception);
    }
  }

  /** 与 26.2 同构但 short 声明顺序被打乱：za / xa / ya。 */
  private static class Scrambled {
    private int entityId;
    private short za;
    private short xa;
    private short ya;
  }

  private static class TooFewShorts {
    private int entityId;
    private short xa;
    private short ya;
  }

  private static class NoEntityIdShorts {
    private short xa;
    private short ya;
    private short za;
  }
}
