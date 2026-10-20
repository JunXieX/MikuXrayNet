package net.mikumc.mikuxraynet.occlusion;

import java.nio.ByteBuffer;

/**
 * 区块整列遮挡数据的紧凑编码：把各 section 的遮挡数据（三态）编成一段字节，随改写结果一起进入
 * 内存缓存与磁盘缓存。
 *
 * <p><b>为什么需要它（方案 b）</b>：遮挡位图原本只在反矿透<b>解码时</b>旁路生成；而内存/磁盘缓存
 * 命中时不会解码，于是那些区块查不到遮挡数据、射线按「不遮挡」处理（实体不再被隐藏）。把位图随
 * 缓存负载一起持久化后，缓存命中路径也能把整列遮挡数据恢复进 {@link OcclusionStore}，
 * 从而让「缓存命中率越高、遮挡数据反而越缺」这一覆盖缺口消失。
 *
 * <p><b>布局</b>（大端）：
 * <pre>
 *   i32  sectionCount
 *   u8 × sectionCount   各段标签：0=已知全不遮挡  1=整段全遮挡  2=逐格位图
 *   i64 × 64 × （标签为 2 的段数）   逐格位图（每段 64 个 long）
 * </pre>
 * 三态标签让「实心段/空气段」零字节开销——地下区块大量 section 是实心的，实际膨胀远小于按 512 字节/段计。
 *
 * <p>解码不会产出 {@code null}：标签 0 解回 {@link EmptyOcclusion#INSTANCE}（「已知全不遮挡」），
 * 与「表里没有该段」（未知）区分开，使覆盖率指标不被全空气段稀释。
 *
 * <p>编码是无状态的纯函数；解码对任何畸形输入都返回 {@code null}（fail-open：拿不到位图按不遮挡处理）。
 */
public final class OcclusionPayload {

  private static final byte TAG_EMPTY = 0;
  private static final byte TAG_SOLID = 1;
  private static final byte TAG_BITMAP = 2;

  private static final int HEADER_BYTES = Integer.BYTES;
  private static final int BITMAP_BYTES = BitmapOcclusion.WORD_COUNT * Long.BYTES;

  /** 可编码的 section 数上限（= 8 位 sectionY 的全部取值）；超出的负载一律判为畸形。 */
  private static final int MAX_SECTIONS = SectionKey.SECTION_Y_COUNT;

  private OcclusionPayload() {
  }

  /**
   * 编码整列遮挡数据。
   *
   * @param column 下标即 section 偏移；元素 {@code null} 表示该段全不遮挡
   * @return 编码后的字节（长度 = {@link #encodedLength}）
   * @throws IllegalArgumentException 段数超出 {@link #MAX_SECTIONS}（解码侧同样拒绝，编出的负载不可解）
   */
  public static byte[] encode(SectionOcclusion[] column) {
    checkColumnLength(column);
    int bitmapCount = 0;
    for (SectionOcclusion section : column) {
      if (tagOf(section) == TAG_BITMAP) {
        bitmapCount++;
      }
    }
    byte[] out = new byte[HEADER_BYTES + column.length + bitmapCount * BITMAP_BYTES];
    ByteBuffer buffer = ByteBuffer.wrap(out);
    buffer.putInt(column.length);
    for (SectionOcclusion section : column) {
      buffer.put(tagOf(section));
    }
    for (SectionOcclusion section : column) {
      if (tagOf(section) != TAG_BITMAP) {
        continue;
      }
      for (long word : ((BitmapOcclusion) section).rawWords()) {
        buffer.putLong(word);
      }
    }
    return out;
  }

  /**
   * {@link #encode} 的输出长度（纯计算，不分配输出数组）。
   *
   * <p>当前无生产调用者：打包成磁盘负载时上层用的是 {@code cache.DiskPayload#encodedLength}
   * （它要连同被伪装坐标与区块字节一起算）。本方法保留给单测与将来的容量预判。
   */
  public static int encodedLength(SectionOcclusion[] column) {
    checkColumnLength(column);
    int bitmapCount = 0;
    for (SectionOcclusion section : column) {
      if (tagOf(section) == TAG_BITMAP) {
        bitmapCount++;
      }
    }
    return HEADER_BYTES + column.length + bitmapCount * BITMAP_BYTES;
  }

  /**
   * 段数上限校验（编码侧与解码侧同口径）。
   *
   * <p><b>为什么必须校验</b>：负载的段数写在 4 字节头部里，而解码侧只接受 {@code 0..MAX_SECTIONS}；
   * 若编码侧放行更多段，编出的负载在下次读盘/读缓存时必然被判为畸形而丢弃——遮挡数据静默丢失
   * （实体侧少藏、显形侧的「已知全不遮挡」信息也一并丢失）。正常世界最多 24 段，触界只可能来自
   * 异常输入，故直接拒绝（调用方本就按 fail-open 处理编码失败）。
   */
  private static void checkColumnLength(SectionOcclusion[] column) {
    if (column == null) {
      throw new IllegalArgumentException("column 不得为 null");
    }
    if (column.length > MAX_SECTIONS) {
      throw new IllegalArgumentException(
          "遮挡负载段数 " + column.length + " 超过上限 " + MAX_SECTIONS + "，拒绝编码");
    }
  }

  /** 段 → 标签；{@code null}（理论上不该出现）按「已知全不遮挡」处理，避免编出非法负载。 */
  private static byte tagOf(SectionOcclusion section) {
    if (section == null || section.isEmpty()) {
      return TAG_EMPTY;
    }
    return section.isSolid() ? TAG_SOLID : TAG_BITMAP;
  }

  /**
   * 解码整列遮挡数据。
   *
   * @return 下标即 section 偏移的数组；输入为 {@code null}、长度不足、标签非法或长度不匹配时返回 {@code null}
   */
  public static SectionOcclusion[] decode(byte[] blob) {
    try {
      if (blob == null || blob.length < HEADER_BYTES) {
        return null;
      }
      ByteBuffer buffer = ByteBuffer.wrap(blob);
      int count = buffer.getInt();
      if (count < 0 || count > MAX_SECTIONS || blob.length < HEADER_BYTES + count) {
        return null;
      }
      byte[] tags = new byte[count];
      buffer.get(tags);

      int bitmapCount = 0;
      for (byte tag : tags) {
        if (tag == TAG_BITMAP) {
          bitmapCount++;
        } else if (tag != TAG_EMPTY && tag != TAG_SOLID) {
          return null;
        }
      }
      // 长度必须精确匹配：多出的字节说明负载损坏或版本不符，一律拒绝（宁可拿不到，不可拿错）
      if (buffer.remaining() != bitmapCount * BITMAP_BYTES) {
        return null;
      }

      SectionOcclusion[] column = new SectionOcclusion[count];
      long[] words = new long[BitmapOcclusion.WORD_COUNT];
      for (int i = 0; i < count; i++) {
        byte tag = tags[i];
        if (tag == TAG_EMPTY) {
          column[i] = EmptyOcclusion.INSTANCE;
        } else if (tag == TAG_SOLID) {
          column[i] = SolidOcclusion.INSTANCE;
        } else {
          for (int w = 0; w < words.length; w++) {
            words[w] = buffer.getLong();
          }
          column[i] = new BitmapOcclusion(words);
        }
      }
      return column;
    } catch (Throwable throwable) {
      return null;
    }
  }
}
