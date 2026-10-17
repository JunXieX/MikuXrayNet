package net.mikumc.mikuxraynet.bandwidth;

import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.FieldAccessor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 相对移动包（{@code REL_ENTITY_MOVE}）的位移读取：沿<b>类继承链</b>定位 {@code entityId} 与位移字段，
 * 并给出「该包是否不携带任何位移」的判定。两种服务端形态：
 *
 * <ul>
 *   <li><b>{@code SHORTS}</b>（≤ 26.2）：基类 {@code ClientboundMoveEntityPacket} 声明
 *       {@code int entityId; short xa, ya, za; …}——判据是三轴全 0（复用 {@link MovementDelta}）；</li>
 *   <li><b>{@code VEC_DELTA}</b>（26.3 起）：同位置变成
 *       {@code int entityId; VecDelta delta; …}，位移改用「路径步进」编码
 *       （{@code VecDelta} 是接口，只有 {@code stepCount()} 等查询，实现为 {@code $Stepped} / {@code $Linear}）。
 *       判据是「步数为 0」——即该包一步都没走，等价于旧形态的零位移。</li>
 * </ul>
 *
 * <p><b>为什么不能用 ProtocolLib 的类型化访问器</b>：{@code StructureModifier} 的字段表来自
 * {@code FuzzyReflection.getDeclaredFields(...)}——<b>只枚举具体类的声明字段，不含继承字段</b>；而
 * 1.21.2 起 {@code entityId} 与位移字段都在<b>基类</b>、子类（{@code $Pos} 等）不再声明任何实例字段，
 * 于是 {@code getSpecificModifier(short.class).size()} 恒为 0、{@code getIntegers().read(0)} 直接抛异常，
 * 本模块自那次重构起<b>静默失效</b>（真机：纯位移包 1703 个全部走「字段不足」分支、取消 0）。
 *
 * <p><b>判定为什么是安全的</b>：只有当「步数恰为 0」（或该位移就是 {@code VecDelta.ZERO} 单例）时才判冗余，
 * 即该包确实不带任何位移。读不到、步数读不出、形态不认识一律判<b>不冗余</b>（放行原包），
 * 绝不因为「读不出来」而取消——取消错一个携带真实位移的包会让客户端实体卡住，代价远大于省下一个包。
 *
 * <p><b>读取语义</b>：字段用 ProtocolLib 的 {@link FieldAccessor}（与本项目 {@code ChunkPacketAccessor}
 * 同一套访问语义，能穿透 private/final）；{@code VecDelta} 只按<b>类型名</b>识别并反射调用其公开方法，
 * 因此不给插件引入任何对 NMS 类的编译期依赖（本插件编译目标仍是 paper-api 26.2）。
 *
 * <p><b>失败语义</b>：定位不到 → {@link #of} 返回 {@code null}，调用方 fail-open 放行并计入「字段形态不符」；
 * 失败原因（含字段清单）通过 {@link #describeLastFailure()} 打一次日志，避免下次又只能猜版本差异。
 */
final class MovePacketFields {

  /** 实体 id 的字段名（Mojang 映射，1.20.5 起稳定）；缺失时退化为「继承链上第一个非静态 int」。 */
  private static final String ENTITY_ID = "entityId";
  /** ≤26.2 形态的三个位移分量字段名（顺序即 {@code dx, dy, dz}）。 */
  private static final String DELTA_X = "xa";
  private static final String DELTA_Y = "ya";
  private static final String DELTA_Z = "za";
  /** 26.3 形态的位移字段名与其类型简单名。 */
  private static final String VEC_DELTA_FIELD = "delta";
  private static final String VEC_DELTA_TYPE = "VecDelta";
  private static final String VEC_DELTA_ZERO = "ZERO";
  private static final String STEP_COUNT = "stepCount";

  /** 位移形态（决定用哪条判据）。 */
  enum Shape {
    /** ≤26.2：三个 {@code short}，三轴全 0 即无位移。 */
    SHORTS,
    /** 26.3 起：单个 {@code VecDelta}，步数为 0 即无位移。 */
    VEC_DELTA
  }

  /** 解析结果：实体 id + 位移形态与对应访问器。 */
  record Access(FieldAccessor entityId, Shape shape,
      FieldAccessor deltaX, FieldAccessor deltaY, FieldAccessor deltaZ,
      FieldAccessor vecDelta, Method stepCount, Object vecDeltaZero) {
  }

  /**
   * 解析不出的哨兵。
   *
   * <p>{@link ConcurrentHashMap#computeIfAbsent} <b>不会缓存 {@code null}</b>，若让它返回 null，
   * 定位失败时会退化成「每个包都重新反射一遍」；用哨兵才能真正把负面结果也缓存下来。
   */
  private static final Access UNRESOLVED = new Access(null, null, null, null, null, null, null, null);

  /** 具体类 → 解析结果（含负面结果，见 {@link #UNRESOLVED}）。 */
  private static final Map<Class<?>, Access> CACHE = new ConcurrentHashMap<>();

  /** 最近一次解析失败的原因（含字段形态清单）；未失败过为 {@code null}。 */
  private static volatile String lastFailure;

  private MovePacketFields() {
  }

  /**
   * 取（必要时解析并缓存）某包对象的必段访问计划。
   *
   * @return 访问计划；{@code handle} 为 {@code null} 或解析不出所需字段时返回 {@code null}（调用方 fail-open）
   */
  static Access of(Object handle) {
    if (handle == null) {
      return null;
    }
    Access access = CACHE.computeIfAbsent(handle.getClass(), MovePacketFields::resolve);
    return access == UNRESOLVED ? null : access;
  }

  /**
   * 该包是否「不携带任何位移」（即零位移取消的候选）。
   *
   * <p>只对纯位移包调用；朝向包的 yaw/pitch 是绝对角、绝不可按 0 取消（依据见 {@link MovementDelta}）。
   *
   * @throws ReflectiveOperationException 读取失败时抛出，由调用方 fail-open（放行原包，绝不取消）
   */
  static boolean isZeroMove(Access access, Object handle) throws ReflectiveOperationException {
    if (access.shape() == Shape.VEC_DELTA) {
      Object delta = access.vecDelta().get(handle);
      if (delta == null) {
        return false; // 读不到位移：按「有位移」处理（绝不取消）
      }
      if (delta == access.vecDeltaZero()) {
        return true; // 就是 ZERO 单例：明确无位移
      }
      return ((Number) access.stepCount().invoke(delta)).intValue() == 0;
    }
    return MovementDelta.isRedundantEntityUpdate(false,
        (short) access.deltaX().get(handle), (short) access.deltaY().get(handle),
        (short) access.deltaZ().get(handle));
  }

  /** 最近一次解析失败的原因（供调用方一次性告警时附上）；未失败过返回 {@code null}。 */
  static String describeLastFailure() {
    return lastFailure;
  }

  /** 沿继承链解析实体 id 与位移字段（父类优先，保证与线上顺序一致）；缺项返回 {@link #UNRESOLVED}。 */
  private static Access resolve(Class<?> concrete) {
    // concrete → … → Object，然后倒序遍历 = 父类优先（Java 里父类字段先初始化，Mojang 的声明序也在基类）
    List<Class<?>> chain = new ArrayList<>(4);
    for (Class<?> type = concrete; type != null && type != Object.class; type = type.getSuperclass()) {
      chain.add(type);
    }

    Field namedEntityId = null;
    Field firstInt = null;
    int intFieldCount = 0;
    Field namedX = null;
    Field namedY = null;
    Field namedZ = null;
    Field vecDeltaField = null;
    List<Field> anyShort = new ArrayList<>(4);
    // 失败时用于打印的字段形态清单：只在解析失败时被读取并写进 lastFailure。
    // 它随继承链遍历同步拼接（含成功路径），但本方法对每个具体包类只解析一次（结果进 CACHE），
    // 这点拼接摊到进程生命周期里可忽略，故不为「成功路径零开销」再引入分支。
    StringBuilder inventory = new StringBuilder();

    for (int i = chain.size() - 1; i >= 0; i--) {
      Class<?> type = chain.get(i);
      Field[] fields = type.getDeclaredFields();
      inventory.append(i == chain.size() - 1 ? "" : " → ").append(type.getSimpleName()).append(':')
          .append(fields.length);
      for (Field field : fields) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        Class<?> fieldType = field.getType();
        if (fieldType == int.class) {
          intFieldCount++;
          if (firstInt == null) {
            firstInt = field;
          }
          if (ENTITY_ID.equals(field.getName())) {
            namedEntityId = field;
          }
        } else if (fieldType == short.class && anyShort.size() < 3) {
          anyShort.add(field);
          if (DELTA_X.equals(field.getName())) {
            namedX = field;
          } else if (DELTA_Y.equals(field.getName())) {
            namedY = field;
          } else if (DELTA_Z.equals(field.getName())) {
            namedZ = field;
          }
        } else if (!fieldType.isPrimitive() && VEC_DELTA_TYPE.equals(fieldType.getSimpleName())
            && (vecDeltaField == null || VEC_DELTA_FIELD.equals(field.getName()))) {
          vecDeltaField = field;
        }
      }
    }

    try {
      // 兜底只允许「唯一一个 int 字段」：位移包里唯一的 int 就是实体 id。若实际有多个 int 字段，
      // 按声明序取第一个很可能取到的不是 id（例如某些版本新增的数值字段），而本模块用该值查白名单
      // 与统计——取错会把白名单实体当成普通实体取消其零位移包（白名单失效）。此时按「解析不出」处理
      // 更安全：整类 fail-open 放行，绝不猜。
      Field entityIdField = namedEntityId;
      if (entityIdField == null) {
        if (intFieldCount != 1) {
          lastFailure = "继承链上的 int 字段数=" + intFieldCount + " 且均非 entityId，无法安全推断实体 id"
              + "（已按解析不出处理）。字段形态：" + inventory;
          return UNRESOLVED;
        }
        entityIdField = firstInt;
      }
      FieldAccessor entityId = Accessors.getFieldAccessor(entityIdField);

      if (vecDeltaField != null) {
        // 26.3 起：单一位移对象。ZERO 常量与 stepCount() 都按类型名反射取，不引入编译期 NMS 依赖。
        // 两处都显式 setAccessible：服务端类都是 public 时是空操作；若某个服务端把它放在非公开类里，
        // 这一步能避免 IllegalAccessException 把整个模块退化成「永远放行」。
        Class<?> vecDeltaType = vecDeltaField.getType();
        Method stepCount = vecDeltaType.getMethod(STEP_COUNT);
        stepCount.setAccessible(true);
        Field zeroField = vecDeltaType.getField(VEC_DELTA_ZERO);
        zeroField.setAccessible(true);
        Object zero = zeroField.get(null);
        return new Access(entityId, Shape.VEC_DELTA, null, null, null,
            Accessors.getFieldAccessor(vecDeltaField), stepCount, zero);
      }
      if (anyShort.size() < 3) {
        lastFailure = "继承链上既没有 VecDelta 位移字段，也凑不齐三个 short 位移分量。字段形态：" + inventory;
        return UNRESOLVED;
      }
      // 名字认全时按名字定序（不依赖 getDeclaredFields 的返回顺序这一非规范保证）；否则用展平序
      boolean byName = namedX != null && namedY != null && namedZ != null;
      Field deltaX = byName ? namedX : anyShort.get(0);
      Field deltaY = byName ? namedY : anyShort.get(1);
      Field deltaZ = byName ? namedZ : anyShort.get(2);
      return new Access(entityId, Shape.SHORTS, Accessors.getFieldAccessor(deltaX),
          Accessors.getFieldAccessor(deltaY), Accessors.getFieldAccessor(deltaZ), null, null, null);
    } catch (Throwable throwable) {
      // 解析失败只影响该类（按「解析不出」缓存）；把原因记下来供一次性告警打印，避免下次只能靠猜版本差异
      lastFailure = "解析 " + concrete.getSimpleName() + " 时抛出 " + throwable
          + "。字段形态：" + inventory;
      return UNRESOLVED;
    }
  }
}
