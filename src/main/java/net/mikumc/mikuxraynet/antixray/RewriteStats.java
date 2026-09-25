package net.mikumc.mikuxraynet.antixray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 反矿透区块改写统计计数，供 {@code /mikuxraynet status} 与诊断转储读取。
 *
 * <p>字段均为无锁 {@link LongAdder}：网络线程（建任务、超时放行）与工作线程（改写）都会累加，
 * 读取时用 {@link #snapshot()} 取得一致的可读快照。计数器只做累加，不参与任何判定，因而不引入高频开销。
 */
public final class RewriteStats {

  /** 真正产生字节改动的区块数。 */
  public final LongAdder chunksRewritten = new LongAdder();

  /** 已处理但无需改写的区块数（无目标方块、无改动、解析失败等，原包放行）。 */
  public final LongAdder chunksSkipped = new LongAdder();

  /**
   * 解码/重编码抛异常、fail-open 放行原包的区块数。
   *
   * <p>与 {@link #chunksSkipped} 分开计数：后者是「本来就没有可伪装的方块」，前者是「本该伪装却失败了」。
   * 若这个数持续增长，说明区块二进制布局与预期不符（版本差异 / 第三方插件改写）。
   */
  public final LongAdder chunksFailed = new LongAdder();

  /** 处理超时、由看门狗放行原包的次数。 */
  public final LongAdder chunksTimedOut = new LongAdder();

  /** 改写字节已算出但未能写回封包的次数（字段直写未生效，写回后回读 self-check 失败）。 */
  public final LongAdder writeBackFailures = new LongAdder();

  /**
   * 累计被替换（伪装）的方块个数——即「实际匹配到目标矿并完成替换」的方块总数。
   *
   * <p><b>为什么必须有这个计数器</b>：原先只有「区块数」维度的计数（改写/跳过），
   * 「改写 670、跳过 0」既可能是「每个区块都替换了几百个方块」，也可能是
   * 「解码出的状态 id 与目标 id 不匹配、一个方块都没替换」，光看区块数无法区分。
   * 这个值 &gt; 0 即证明「目标匹配 + 遮挡判定 + 替换」确实跑通了，剩下的问题只可能在写回侧。
   */
  public final LongAdder blocksReplaced = new LongAdder();

  /**
   * 改写区块的「原始字节」累计（P0-1 字节口径统计）。
   *
   * <p>只统计真正发生改写的区块（未改动/失败放行的区块不计入），否则恒等的原样字节会稀释
   * 「省了多少」的比例。与之配对的 {@link #bytesOutput} 同口径。
   */
  public final LongAdder bytesOriginal = new LongAdder();

  /** 改写区块的「输出字节」累计（与 {@link #bytesOriginal} 同口径，P0-1）。 */
  public final LongAdder bytesOutput = new LongAdder();

  /** 节省字节 = 原始 − 输出（P0-1；可能为负——升位/扩容时输出更大，正负都如实累计）。 */
  public final LongAdder bytesSaved = new LongAdder();

  /** 位宽直方图的槽位数（bitsPerBlock 0..15；>15 的理论值并入末槽，实际间接调色板只到 8）。 */
  private static final int BITS_SLOTS = 16;

  /**
   * 改写 section 的 bitsPerBlock 直方图（P0-1 诊断，下标 = 位宽）。
   *
   * <p>供 dump 观察「封顶/裁剪/降位」的实际效果：开启 width-budget 后应看到高位宽槽不再增长、
   * 低位宽槽增多。只在改写发生时累加，读取方用 {@link #paletteBitsAt(int)}。
   */
  private final LongAdder[] paletteBits = new LongAdder[BITS_SLOTS];

  {
    for (int i = 0; i < BITS_SLOTS; i++) {
      paletteBits[i] = new LongAdder();
    }
  }

  /** 记录一个改写后 section 的位宽（越界值并入末槽，防御异常输入）。 */
  public void recordPaletteBits(int bits) {
    paletteBits[Math.min(Math.max(bits, 0), BITS_SLOTS - 1)].increment();
  }

  /** 读取某位宽槽位的累计计数（诊断/转储用）。 */
  public long paletteBitsAt(int bits) {
    return paletteBits[Math.min(Math.max(bits, 0), BITS_SLOTS - 1)].sum();
  }

  /** 位宽直方图的中文摘要（供 dump）：仅列出有采样的位宽槽；无采样返回「无采样」。 */
  public String paletteBitsSummary() {
    StringBuilder sb = new StringBuilder();
    for (int bits = 0; bits < BITS_SLOTS; bits++) {
      long count = paletteBits[bits].sum();
      if (count > 0) {
        if (sb.length() > 0) {
          sb.append(" ｜ ");
        }
        sb.append(bits).append("位×").append(count);
      }
    }
    return sb.length() == 0 ? "无采样" : sb.toString();
  }

  /** 生成中文可读快照（顺序稳定，便于命令输出）。 */
  public Map<String, Long> snapshot() {
    Map<String, Long> map = new LinkedHashMap<>();
    map.put("改写", chunksRewritten.sum());
    map.put("替换方块", blocksReplaced.sum());
    map.put("原始字节", bytesOriginal.sum());
    map.put("输出字节", bytesOutput.sum());
    map.put("节省字节", bytesSaved.sum());
    map.put("跳过", chunksSkipped.sum());
    map.put("失败", chunksFailed.sum());
    map.put("写回失败", writeBackFailures.sum());
    map.put("超时放行", chunksTimedOut.sum());
    return map;
  }
}