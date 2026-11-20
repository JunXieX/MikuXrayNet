package net.mikumc.mikuxraynet.antixray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * 视线即时显形的「方向只取一次」守门（{@link ProximityRevealer} 的视线路径）。
 *
 * <p><b>为什么值得单独钉住</b>：这条路径每个在线玩家每 tick 都走一次（Paper 的 {@code lookTask} 与 Folia
 * 的每玩家区域任务都调它）。{@code Location#getDirection()} 不是零成本读——每次调用都新建一个 {@code Vector}，
 * 并把 yaw/pitch 重新过 3 次 {@code Math.toRadians} 与 sin/cos 各两次（javap 核对 {@code org.bukkit.Location}
 * 的字节码）。原实现对同一次眼位取了三遍：两次算射线终点、一次交给 {@code rayTraceBlocks}；结果逐位相同，
 * 代价却是三倍。
 *
 * <p><b>为什么同时钉「不许就地改写」</b>：改成复用一个 {@code Vector} 会引入一个新风险——它在终点计算与
 * 射线之间被就地改写（{@code normalize()} / {@code setX()} 之类），终点与射线就分属两个方向，射线守卫
 * 与真正的射线不再对同一段空间成立。因此这里断言「声明在前、区间内无就地改写、射线用的是同一份局部值」。
 *
 * <p><b>为什么不能是缓存</b>：缓存方向会跨 tick 复用旧值，玩家转身后射线仍朝旧方向，直接破坏「看哪里显形
 * 哪里」的语义；因此这里还断言方向来源仍是当次 {@code player.getEyeLocation()} 快照。
 */
class RevealLookDirectionTest {

  private static final Path SOURCE =
      Path.of("src/main/java/net/mikumc/mikuxraynet/antixray/ProximityRevealer.java");

  /** 视线即时显形只还原「射线命中的第一个方块」，它的方向必须与射线守卫算的终点同源。 */
  @Test
  void lookDirectionIsSampledOncePerCall() throws IOException {
    String body = lookBody();
    assertEquals(1, countOccurrences(body, "getDirection()"),
        "同一次调用只能取一次方向；再取一次就多一次 Vector 分配与七个三角函数");
    assertTrue(body.contains("Vector direction = eye.getDirection();"),
        "方向必须落在当次调用的局部变量里（不是字段、不是静态缓存）");
    assertTrue(body.contains("rayTraceBlocks(eye, direction, LOOK_MAX_DISTANCE,"),
        "射线必须用同一份局部方向，否则复用就只省了终点那两次");
  }

  /** 复用引入的唯一新风险：共享的 Vector 在终点计算与射线之间被就地改写。 */
  @Test
  void sharedDirectionIsNotMutatedBeforeTheRay() throws IOException {
    String body = lookBody();
    int declare = body.indexOf("Vector direction = eye.getDirection();");
    int endX = body.indexOf("int endX =");
    int endZ = body.indexOf("int endZ =");
    int ray = body.indexOf("rayTraceBlocks(");
    assertTrue(declare >= 0 && endX > declare && endZ > endX && ray > endZ,
        "顺序必须是：取方向 → 算终点 X → 算终点 Z → 发射线");
    assertEquals(1, countOccurrences(body, "direction ="),
        "方向局部变量只能声明一次，不得在方法内重新赋值");
    String between = body.substring(declare, ray);
    for (String mutator : new String[] {".normalize(", ".multiply(", ".setX(", ".setY(", ".setZ(",
        ".add(", ".subtract(", ".zero("}) {
      assertFalse(between.contains(mutator),
          "取方向与发射线之间不得就地改写它（" + mutator + "）：终点与射线会因此分属两个方向");
    }
  }

  /** 这次改动只许省掉重复取方向：视野半径、碰撞口径与区域归属守卫都不许顺手改。 */
  @Test
  void rayRangeAndChunkGuardAreUnchanged() throws IOException {
    String body = lookBody();
    assertEquals(3, countOccurrences(body, "LOOK_MAX_DISTANCE"),
        "终点投影两次 + 射线一次，必须都是同一个 6 格半径（改范围会破坏准星显形的距离语义）");
    int guard = body.indexOf("rayChunksAvailable(world, eye, endX, endZ)");
    int ray = body.indexOf("rayTraceBlocks(");
    assertTrue(guard >= 0 && guard < ray,
        "区块归属/加载守卫必须仍在射线之前（跨区射线会触发 Folia 的 TickThread 校验）");
    assertTrue(body.contains("FluidCollisionMode.NEVER, true);"),
        "水/可穿过方块的碰撞口径不得改变（改了就会隔水透出方块）");
    assertEquals(1, countOccurrences(body, "getEyeLocation()"),
        "眼位只能取一次当次快照；缓存方向会让转身后的射线仍朝旧准星");
  }

  /**
   * 等价性前提：同一次眼位快照的多次 {@code getDirection()} 结果逐位相同，且每次都是新实例。
   *
   * <p>复用之所以安全就靠这两条：值相同 → 终点与射线指向同一方向；实例各自独立 → 不会与任何共享对象
   * 互相改写。顺带固定「正上/正下」这一退化眼位（水平分量为 0）在复用后仍是同一个终点，因此不需要为它
   * 额外加分支。
   */
  @Test
  void sameEyeSnapshotYieldsIdenticalDirection() {
    float[][] views = {{37.5F, -12.25F}, {0.0F, 0.0F}, {-118.75F, 41.5F}, {359.9F, -89.99F},
        {180.0F, -90.0F}};
    Location eye = new Location(null, 12.5D, 64.0D, -37.25D, 0.0F, 0.0F);
    for (float[] view : views) {
      eye.setYaw(view[0]);
      eye.setPitch(view[1]);
      Vector first = eye.getDirection();
      Vector second = eye.getDirection();
      String where = "yaw=" + view[0] + " pitch=" + view[1];
      assertNotSame(first, second, "getDirection() 每次都新建 Vector（" + where + "）");
      for (int axis = 0; axis < 3; axis++) {
        assertEquals(component(first, axis), component(second, axis), 0.0D,
            "同一次眼位快照的方向必须逐位相同（" + where + "，轴 " + axis + "）");
      }
      assertEquals(end(eye.getX(), first.getX()), end(eye.getX(), second.getX()),
          "终点 X 必须与所用方向无关（" + where + "）");
      assertEquals(end(eye.getZ(), first.getZ()), end(eye.getZ(), second.getZ()),
          "终点 Z 必须与所用方向无关（" + where + "）");
    }
  }

  /** 与 {@code revealLookedAt} 同一算式：{@code (int) Math.floor(坐标 + 分量 × 6)}。 */
  private static int end(double coordinate, double component) {
    return (int) Math.floor(coordinate + component * 6.0D);
  }

  private static double component(Vector vector, int axis) {
    return axis == 0 ? vector.getX() : axis == 1 ? vector.getY() : vector.getZ();
  }

  private static String lookBody() throws IOException {
    String body = methodBody(readSource(), "revealLookedAt");
    assertTrue(body != null, "revealLookedAt 必须存在（视线即时显形的唯一入口）");
    return body;
  }

  /** 行尾归一化后读源码：CI 为 LF 工作区、本机为 CRLF，两者必须给出同一份结构。 */
  private static String readSource() throws IOException {
    return stripComments(Files.readString(SOURCE).replace("\r\n", "\n"));
  }

  /** 丢掉行注释与块注释行，避免「注释里提到过」被当成真实调用。 */
  private static String stripComments(String text) {
    StringBuilder builder = new StringBuilder(text.length());
    for (String line : text.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
        builder.append('\n');
        continue;
      }
      builder.append(line).append('\n');
    }
    return builder.toString();
  }

  /** 取方法体（含花括号配对；字符串与字符字面量里的花括号不参与配对）。 */
  private static String methodBody(String source, String name) {
    Matcher matcher = Pattern.compile("\\b(?:void|boolean|int|String)\\s+" + Pattern.quote(name)
        + "\\s*\\(").matcher(source);
    if (!matcher.find()) {
      return null;
    }
    return blockAt(source, matcher.start());
  }

  private static String blockAt(String text, int fromIndex) {
    int brace = text.indexOf('{', fromIndex);
    if (brace < 0) {
      return "";
    }
    int depth = 0;
    boolean inString = false;
    boolean inChar = false;
    for (int i = brace; i < text.length(); i++) {
      char character = text.charAt(i);
      if (inString || inChar) {
        if (character == '\\') {
          i++;
        } else if (inString && character == '"') {
          inString = false;
        } else if (inChar && character == '\'') {
          inChar = false;
        }
        continue;
      }
      if (character == '"') {
        inString = true;
      } else if (character == '\'') {
        inChar = true;
      } else if (character == '{') {
        depth++;
      } else if (character == '}') {
        depth--;
        if (depth == 0) {
          return text.substring(brace, i + 1);
        }
      }
    }
    return text.substring(brace);
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
