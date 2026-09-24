package net.mikumc.mikuxraynet.bandwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.mikumc.mikuxraynet.config.BandwidthConfig;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * 实体射线剔除的「隐藏 → 恢复」账本回归。
 *
 * <p>真机反馈「实体隐藏 721 / 恢复 299」的不对称是否安全，取决于三件事：① 隐藏与恢复的触发条件成对；
 * ② 实体失效（死亡 / 卸载）时隐藏记录被清理（不泄漏映射）；③ 停用 / 退出有全量恢复入口。
 * 本测试把这三件事逐一钉死。
 *
 * <p>Bukkit 运行时在离线测试环境不可用（服务器的注册 / 调度都是静态状态），因此这里用
 * {@link Proxy} 动态代理 Bukkit 接口作为替身——不引入任何新依赖（pom 只有 junit-jupiter），
 * 也不需要 Mockito 之类的框架。
 */
class EntityCullerTest {

  private static final Logger LOGGER = Logger.getLogger("EntityCullerTest");

  /** 被完全遮挡（一条体素路径 + 世界桩返回「遮挡」）→ 隐藏。 */
  private static List<int[]> blockedPath() {
    return List.of(new int[] {0, 0, 0});
  }

  @Test
  void hiddenEntityIsShownAgainWhenTheViewIsClear() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityStub entity = new EntityStub(101, world);

    culler.evaluate(player.proxy(), entity.proxy(), blockedPath());
    assertEquals(1L, stats.entitiesHidden.sum(), "被完全遮挡必须隐藏");
    assertEquals(1, culler.hiddenCount(), "隐藏账本必须有记录");
    assertEquals(0L, stats.entitiesShown.sum());

    // 重复判定仍然遮挡：不得重复计入隐藏数、不得重复登记（账本按 (玩家, 实体) 去重）
    culler.evaluate(player.proxy(), entity.proxy(), blockedPath());
    assertEquals(1L, stats.entitiesHidden.sum(), "同一实体重复判定只算一次隐藏");
    assertEquals(1, culler.hiddenCount());

    // 视线通畅（世界桩不再遮挡）→ 必须恢复显示，且账本同步摘除
    world.occluding = false;
    culler.evaluate(player.proxy(), entity.proxy(), blockedPath());
    assertEquals(1L, stats.entitiesShown.sum(), "重新可见必须调用 showEntity");
    assertEquals(1, player.showCalls.get(), "恢复必须真的下发 showEntity（否则会残留看不见的怪）");
    assertEquals(0, culler.hiddenCount(), "恢复后账本必须同步摘除");
  }

  /** 实体失效（死亡 / 区块卸载）时只清账本，不调用 showEntity——服务端已自然回收，这正是 721 vs 299 的一部分。 */
  @Test
  void invalidEntityIsDroppedFromTheLedgerWithoutCountingAsShown() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    EntityStub entity = new EntityStub(202, new WorldStub());

    culler.evaluate(player.proxy(), entity.proxy(), blockedPath());
    assertEquals(1, culler.hiddenCount());

    entity.valid = false;
    culler.evaluate(player.proxy(), entity.proxy(), List.of());

    assertEquals(0, culler.hiddenCount(), "失效实体的隐藏记录必须清理，避免映射泄漏");
    assertEquals(0L, stats.entitiesShown.sum(), "已被服务端自然回收的实体不需要（也不该）showEntity");
    assertEquals(0, player.showCalls.get());
  }

  /** 玩家离线时的判定同样只清账本（玩家已看不到实体，恢复无意义）。 */
  @Test
  void offlinePlayerIsDroppedFromTheLedger() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    EntityStub entity = new EntityStub(303, new WorldStub());

    culler.evaluate(player.proxy(), entity.proxy(), blockedPath());
    assertEquals(1, culler.hiddenCount());

    player.online = false;
    culler.evaluate(player.proxy(), entity.proxy(), blockedPath());

    assertEquals(0, culler.hiddenCount(), "玩家离线后不得继续保留隐藏记录");
    assertEquals(0L, stats.entitiesShown.sum(), "离线不产生恢复计数");
    assertEquals(0, player.showCalls.get());
  }

  /** 停用 / 退出时的全量恢复入口：必须交出全部隐藏实体并清空账本（不留副作用）。 */
  @Test
  void drainPlayerHandsOverEveryHiddenEntryForFullRestore() {
    ThrottleStats stats = new ThrottleStats();
    EntityCuller culler = newCuller(stats);
    PlayerStub player = new PlayerStub();
    WorldStub world = new WorldStub();
    EntityStub first = new EntityStub(1, world);
    EntityStub second = new EntityStub(2, world);

    culler.evaluate(player.proxy(), first.proxy(), blockedPath());
    culler.evaluate(player.proxy(), second.proxy(), blockedPath());
    assertEquals(2, culler.hiddenCount());

    Map<Integer, Entity> drained = culler.drainPlayer(player.id());
    assertEquals(2, drained.size(), "全量恢复入口必须交出全部隐藏实体");
    assertTrue(drained.containsKey(first.id()), "交出的清单必须含第一个实体");
    assertTrue(drained.containsKey(second.id()), "交出的清单必须含第二个实体");
    assertEquals(0, culler.hiddenCount(), "交出后账本必须清空");
    assertTrue(culler.drainPlayer(player.id()).isEmpty(), "重复交出必须得到空表（幂等，异常安全）");
  }

  private static EntityCuller newCuller(ThrottleStats stats) {
    // 启用实体剔除与射线判定；强制可见距离 2 格、单工作线程，均为不影响本测试的取值
    return new EntityCuller(new PluginStub().proxy(),
        new BandwidthConfig.EntityCulling(true, true, 2.0D, 1, 10, 24), stats);
  }

  /** 接口方法的默认返回值（未显式打桩的方法一律走这里）。 */
  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return (char) 0;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    return null;
  }

  private static boolean isObjectMethod(Method method) {
    String name = method.getName();
    return name.equals("hashCode") || name.equals("equals") || name.equals("toString");
  }

  /** 玩家替身：只打桩 EntityCuller 真正会读到的方法。 */
  private static final class PlayerStub implements InvocationHandler {

    private final UUID id = UUID.randomUUID();
    private final AtomicInteger showCalls = new AtomicInteger();
    private final Player proxy = (Player) Proxy.newProxyInstance(
        Player.class.getClassLoader(), new Class<?>[] {Player.class}, this);
    private volatile boolean online = true;

    Player proxy() {
      return proxy;
    }

    UUID id() {
      return id;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "PlayerStub");
      }
      return switch (method.getName()) {
        case "getUniqueId" -> id;
        case "isOnline" -> online;
        case "hasPermission" -> false;
        case "hideEntity" -> null;
        case "showEntity" -> {
          showCalls.incrementAndGet();
          yield null;
        }
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  /** 实体替身：EntityCuller 会读 entityId / isValid / getWorld。 */
  private static final class EntityStub implements InvocationHandler {

    private final int id;
    private final WorldStub world;
    private final Entity proxy;
    private volatile boolean valid = true;

    EntityStub(int id, WorldStub world) {
      this.id = id;
      this.world = world;
      this.proxy = (Entity) Proxy.newProxyInstance(
          Entity.class.getClassLoader(), new Class<?>[] {Entity.class}, this);
    }

    Entity proxy() {
      return proxy;
    }

    int id() {
      return id;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "EntityStub#" + id);
      }
      return switch (method.getName()) {
        case "getEntityId" -> id;
        case "isValid" -> valid;
        case "getWorld" -> world.proxy();
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  /** 世界替身：getBlockData 返回同源替身，isOccluding 由 {@code occluding} 开关决定。 */
  private static final class WorldStub implements InvocationHandler {

    private final BlockData blockData = (BlockData) Proxy.newProxyInstance(
        BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class}, this);
    private final World proxy = (World) Proxy.newProxyInstance(
        World.class.getClassLoader(), new Class<?>[] {World.class}, this);
    private volatile boolean occluding = true;

    World proxy() {
      return proxy;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "WorldStub");
      }
      return switch (method.getName()) {
        case "getBlockData" -> blockData;
        case "isOccluding" -> occluding;
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  /** 插件替身：只提供 Logger。 */
  private static final class PluginStub implements InvocationHandler {

    private final Plugin proxy = (Plugin) Proxy.newProxyInstance(
        Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, this);

    Plugin proxy() {
      return proxy;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] args) {
      if (isObjectMethod(method)) {
        return "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
            : ("equals".equals(method.getName()) ? proxy == args[0] : "PluginStub");
      }
      return switch (method.getName()) {
        case "getLogger" -> LOGGER;
        case "getName" -> "EntityCullerTest";
        default -> defaultValue(method.getReturnType());
      };
    }
  }
}