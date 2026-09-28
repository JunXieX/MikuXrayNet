package net.mikumc.mikuxraynet.antixray;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.FieldAccessor;
import com.comphenix.protocol.utility.MinecraftReflection;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 区块封包读写入口：把 ProtocolLib 的 {@link PacketContainer} 收敛为「原始 section 字节 + 方块实体列表」。
 *
 * <p><b>为什么不再用 ProtocolLib 自带的区块数据包装类</b>（{@code com.comphenix.protocol.wrappers}
 * 包下那个区块数据包装类及其内部类）：它在<b>静态初始化</b>里用
 * {@code FuzzyReflection.getConstructor} 硬查 NMS 构造器
 * {@code (FriendlyByteBuf, int, int)}。该构造器在 MC 26.2 存在、<b>26.3 已被移除</b>
 * （26.3 只剩 {@code (LevelChunk)}、{@code (LevelChunk, ChunkPacketInfo)} 与
 * {@code private (Map, byte[], List)}），于是 26.3 上该类 clinit 直接抛
 * {@code IllegalArgumentException: Unable to find a method that matches {params=[FriendlyByteBuf, int, int]}}，
 * 之后每次取数都是 {@code NoClassDefFoundError}——反矿透在 26.3 上完全失效（fail-open 放行原包）。
 * 实测：用户手上的 ProtocolLib 5.5.0-SNAPSHOT 与 5.3.0 都仍硬查该构造器，<b>升级 PL 也不解决</b>。
 *
 * <p><b>现在的取数路径</b>：<b>字段级直读/直写</b>，完全不触碰任何构造器：
 * <ol>
 *   <li>先用 {@code packet.getSpecificModifier(chunkDataClass).read(0)} 取出区块数据对象（NMS
 *       {@code ClientboundLevelChunkPacketData}）；</li>
 *   <li>用 ProtocolLib 的公开反射工具在<b>启动期解析并缓存</b>字段访问器
 *       （{@link FuzzyReflection#fromClass} 枚举字段 + {@link Accessors#getFieldAccessor(Field)} 生成
 *       访问器；PL 的访问器走 {@code MethodHandles.Lookup.IMPL_LOOKUP}，模块访问处理与 PL 自身一致，
 *       因此能写 {@code private final} 字段）：<br>
 *       ① {@code byte[]} 字段（优先名 {@code buffer}）→ 读/写区块字节；<br>
 *       ② {@code List} 字段（优先名 {@code blockEntitiesData}，退化 {@code blockEntities}）
 *          → 仅用于剔除被伪装 section 内的方块实体，并把该 List 中每个条目的
 *          {@code packedXZ} / {@code y} 字段直读出来判坐标。</li>
 * </ol>
 * 由于构造器不参与，本类天然免疫构造器签名变化。
 *
 * <p><b>失败语义</b>：字段定位不到 → 构造器抛异常，调用方 fail-open（放行原包、不改写）并给出
 * 一次性中文提示；方块实体剔除所需的字段定位不到 → 静默降级为「不剔除方块实体」，
 * <b>绝不影响</b> buffer 改写与封包格式。
 */
public final class ChunkPacketAccessor {

  /** 区块数据类（{@code ClientboundLevelChunkPacketData}）的字段访问计划；启动期解析一次后缓存。 */
  private record FieldPlan(FieldAccessor buffer, FieldAccessor blockEntities,
      FieldAccessor blockEntityPackedXz, FieldAccessor blockEntityY) {

    /** 方块实体剔除是否可用（三者齐全才剔除；缺任意一项即降级为不剔除以保护 buffer 改写）。 */
    boolean blockEntityFilterReady() {
      return blockEntities != null && blockEntityPackedXz != null && blockEntityY != null;
    }
  }

  private static volatile FieldPlan plan;
  private static volatile boolean planResolved;

  private final Object chunkData;
  private final FieldPlan fieldPlan;
  private final int chunkX;
  private final int chunkZ;

  public ChunkPacketAccessor(PacketContainer packet) {
    this.chunkX = packet.getIntegers().read(0);
    this.chunkZ = packet.getIntegers().read(1);

    Class<?> chunkDataClass = MinecraftReflection.getLevelChunkPacketDataClass();
    this.chunkData = packet.getSpecificModifier(chunkDataClass).read(0);
    this.fieldPlan = currentPlan();

    if (this.chunkData == null) {
      throw new IllegalStateException("封包内未找到区块数据对象（服务端 NMS 结构可能已变更）");
    }
    if (this.fieldPlan == null) {
      throw new IllegalStateException("无法定位区块数据字段（服务端 NMS 结构可能已变更）");
    }
  }

  public int chunkX() {
    return chunkX;
  }

  public int chunkZ() {
    return chunkZ;
  }

  /** 当前 section 原始字节（不要在改写前修改该数组）。 */
  public byte[] buffer() {
    return (byte[]) fieldPlan.buffer().get(chunkData);
  }

  /**
   * 回填改写结果，并（按开关）剔除被伪装方块占位的方块实体。
   *
   * <p><b>写回自检</b>：只通过 ProtocolLib 的字段访问器把新数组写进 NMS 对象。写完立刻回读一次，
   * 确认字段指向的就是我们传入的那个数组——若 ProtocolLib 某个版本改成写副本、或封包结构不匹配
   * （拿到的是别的对象），这里会返回 {@code false}，调用方据此告警，
   * 避免出现「算了但没写」却毫无征兆的静默失效。
   *
   * @param data                 新的 section 字节
   * @param localPositions       被伪装的方块位置，编码为 {@code y << 8 | z << 4 | x}（区块内相对坐标）
   * @param minHeight            该世界最低建筑高度，用于把方块实体的绝对 Y 换算为区块内相对 Y
   * @param removeBlockEntities  {@code antixray.yml: obfuscation.remove-block-entities}；
   *                             false 时跳过方块实体剔除段（只回填字节，保留封包原带的所有方块实体）
   * @return true 表示新字节已确认写回封包；false 表示回读结果与写入不一致（调用方应告警）
   */
  public boolean update(byte[] data, int[] localPositions, int minHeight, boolean removeBlockEntities) {
    fieldPlan.buffer().set(chunkData, data);
    boolean verified = fieldPlan.buffer().get(chunkData) == data;

    if (!shouldFilterBlockEntities(removeBlockEntities, localPositions) || !fieldPlan.blockEntityFilterReady()) {
      return verified;
    }

    // 剔除段是「锦上添花」：其任何失败都不得影响上面已经完成的字节改写（降级为不剔除）
    try {
      removeObfuscatedBlockEntities(localPositions, minHeight);
    } catch (Throwable ignored) {
      // 降级：方块实体字段结构不符 / List 不可变等，保留原 List（启动日志已说明字段定位结果）
    }
    return verified;
  }

  /** 剔除落在被伪装 section 内的方块实体（直接读条目自身的 packedXZ / y 字段判坐标）。 */
  private void removeObfuscatedBlockEntities(int[] localPositions, int minHeight) {
    Object raw = fieldPlan.blockEntities().get(chunkData);
    if (!(raw instanceof List<?> entries) || entries.isEmpty()) {
      return;
    }

    List<Object> kept = new ArrayList<>(entries.size());
    for (Object entry : entries) {
      if (!isObfuscated(entry, localPositions, minHeight)) {
        kept.add(entry);
      }
    }

    if (kept.size() != entries.size()) {
      fieldPlan.blockEntities().set(chunkData, kept);
    }
  }

  /** 读取单个方块实体条目的区块内相对坐标并复用 {@link #isObfuscated} 判定。 */
  private boolean isObfuscated(Object entry, int[] localPositions, int minHeight) {
    int packedXz = number(fieldPlan.blockEntityPackedXz().get(entry));
    int relativeY = number(fieldPlan.blockEntityY().get(entry)) - minHeight;
    // 与 ProtocolLib 同口径：sectionX = packedXZ >> 4，sectionZ = packedXZ & 15
    return isObfuscated(relativeY, packedXz >> 4, packedXz & 15, localPositions);
  }

  private static int number(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  /**
   * 是否需要执行方块实体剔除（配置开关与被伪装坐标清单的汇合点；纯函数，离线可测）。
   *
   * <p>开关关闭、或本次没有任何被伪装坐标时都不需要剔除——旧实现里该开关是死配置键，
   * 剔除段无条件执行，此判定即开关的真正消费点。
   */
  static boolean shouldFilterBlockEntities(boolean removeBlockEntities, int[] localPositions) {
    return removeBlockEntities && localPositions.length > 0;
  }

  /**
   * 纯几何判定：某方块实体是否落在被伪装坐标清单里。
   *
   * @param relativeY  方块实体的区块内相对 Y（绝对 Y − 世界最低建筑高度；负值必然不在清单里）
   * @param sectionX   方块实体的 section 内 X（0..15）
   * @param sectionZ   方块实体的 section 内 Z（0..15）
   * @param localPositions 被伪装坐标清单，编码为 {@code y << 8 | z << 4 | x}
   */
  static boolean isObfuscated(int relativeY, int sectionX, int sectionZ, int[] localPositions) {
    if (relativeY < 0) {
      return false;
    }
    int packed = relativeY << 8 | sectionZ << 4 | sectionX;
    for (int position : localPositions) {
      if (position == packed) {
        return true;
      }
    }
    return false;
  }

  /**
   * 启动期调用：解析并缓存字段访问器，返回可直接打印的一行中文判定结果。
   *
   * <p>必须在 ProtocolLib 就绪后调用（解析要读 NMS 类）。解析结果无论成败都缓存，
   * 避免热路径反复反射。异常一律吞掉并落到 {@code ✗}（fail-open 由调用方兜底）。
   */
  public static String resolveAndDescribe() {
    FieldPlan resolved = locate();
    synchronized (ChunkPacketAccessor.class) {
      plan = resolved;
      planResolved = true;
    }
    return describe(resolved);
  }

  /** 字段定位结果的中文描述（启动日志用）。 */
  private static String describe(FieldPlan resolved) {
    if (resolved == null) {
      return "区块数据字段：buffer=byte[] ✗｜blockEntitiesData=List ✗"
          + "（服务端 NMS 结构可能已变更 → 本次反矿透改写将按原包放行）";
    }
    String blockEntities = resolved.blockEntities() == null
        ? "blockEntitiesData=List ✗（降级：不剔除方块实体）"
        : "blockEntitiesData=List ✓";
    String coordinates = resolved.blockEntityFilterReady()
        ? "｜方块实体坐标字段 packedXZ ✓ y ✓"
        : "｜方块实体坐标字段 packedXZ " + (resolved.blockEntityPackedXz() == null ? "✗" : "✓")
            + " y " + (resolved.blockEntityY() == null ? "✗" : "✓")
            + "（降级：不剔除方块实体）";
    return "区块数据字段：buffer=byte[] ✓｜" + blockEntities + coordinates;
  }

  /** 当前字段计划；启动期未显式解析时惰性解析一次（失败也会缓存，避免热路径反复反射）。 */
  private static FieldPlan currentPlan() {
    if (planResolved) {
      return plan;
    }
    synchronized (ChunkPacketAccessor.class) {
      if (!planResolved) {
        plan = locate();
        planResolved = true;
      }
      return plan;
    }
  }

  /** 解析字段访问计划：只用 PL 公开反射工具 + 字段类型/名称，完全不涉及构造器。 */
  private static FieldPlan locate() {
    try {
      Class<?> chunkDataClass = MinecraftReflection.getLevelChunkPacketDataClass();
      if (chunkDataClass == null) {
        return null;
      }

      Field bufferField = selectField(chunkDataClass, byte[].class, "buffer");
      // buffer 是硬需求：定位不到就不要改写（由调用方 fail-open 放行原包）
      FieldAccessor buffer = accessorOf(bufferField);
      if (buffer == null) {
        return null;
      }

      Field entitiesField = selectField(chunkDataClass, List.class,
          "blockEntitiesData", "blockEntities", "blockEntityData");
      FieldAccessor blockEntities = accessorOf(entitiesField);

      FieldAccessor packedXz = null;
      FieldAccessor y = null;
      Class<?> entryClass = listElementType(entitiesField);
      if (entryClass != null) {
        packedXz = accessorOf(selectFieldByName(entryClass, "packedXZ", byte.class, int.class, short.class));
        y = accessorOf(selectFieldByName(entryClass, "y", short.class, int.class, byte.class));
      }
      return new FieldPlan(buffer, blockEntities, packedXz, y);
    } catch (Throwable throwable) {
      return null;
    }
  }

  /**
   * 按「字段类型 + 名称优先级」挑选唯一字段：类型筛出候选 → 仅一个则直接采用 →
   * 多个则按 {@code preferredNames} 顺序取名称命中者 → 仍无法唯一确定（歧义）返回 {@code null}。
   */
  private static Field selectField(Class<?> owner, Class<?> type, String... preferredNames) {
    List<Field> candidates = new ArrayList<>();
    for (Field field : fieldsOf(owner)) {
      if (!Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
        candidates.add(field);
      }
    }
    if (candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    for (String preferred : preferredNames) {
      for (Field field : candidates) {
        if (field.getName().equalsIgnoreCase(preferred)) {
          return field;
        }
      }
    }
    return null;
  }

  /** 按名称严格挑选字段，并要求类型在允许集合内（名字命中但类型不符一律放弃，不冒险）。 */
  private static Field selectFieldByName(Class<?> owner, String name, Class<?>... allowedTypes) {
    for (Field field : fieldsOf(owner)) {
      if (Modifier.isStatic(field.getModifiers()) || !field.getName().equals(name)) {
        continue;
      }
      for (Class<?> allowed : allowedTypes) {
        if (allowed == field.getType()) {
          return field;
        }
      }
      return null;
    }
    return null;
  }

  /** 类的全部字段（含私有与非公开类字段）；走 ProtocolLib 的反射工具，不自行遍历。 */
  private static Iterable<Field> fieldsOf(Class<?> owner) {
    return FuzzyReflection.fromClass(owner, true).getFields();
  }

  /** 生成字段访问器；失败返回 {@code null}（该字段视为不可用，交由上层降级）。 */
  private static FieldAccessor accessorOf(Field field) {
    if (field == null) {
      return null;
    }
    try {
      return Accessors.getFieldAccessor(field);
    } catch (Throwable throwable) {
      return null;
    }
  }

  /** {@code List<X>} 字段的元素类型 X（用于定位方块实体条目的自身字段）；解析不出返回 {@code null}。 */
  private static Class<?> listElementType(Field listField) {
    if (listField == null) {
      return null;
    }
    Type generic = listField.getGenericType();
    if (generic instanceof ParameterizedType parameterized) {
      Type[] arguments = parameterized.getActualTypeArguments();
      if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
        return element;
      }
    }
    return null;
  }
}