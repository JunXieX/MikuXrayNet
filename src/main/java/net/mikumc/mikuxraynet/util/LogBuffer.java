package net.mikumc.mikuxraynet.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 插件日志环形缓冲：只留 WARNING 及以上，供诊断转储把「最近的告警」一并带出。
 *
 * <p>反馈排障时，控制台日志通常留不下来；把最近若干条告警随 dump 一起导出，能直接看到
 * 「哪个模块在报错、报了几次」，不必再向服主要日志。只保留定长条目、单条截断，内存有界。
 */
public final class LogBuffer extends Handler {

  private static final int CAPACITY = 64;
  private static final int MAX_MESSAGE = 400;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final LogBuffer INSTANCE = new LogBuffer();

  private final Deque<String> lines = new ConcurrentLinkedDeque<>();

  private LogBuffer() {
    setLevel(Level.WARNING);
  }

  /** 挂到插件 logger（幂等）。 */
  public static void install(Logger logger) {
    if (logger == null) {
      return;
    }
    try {
      logger.addHandler(INSTANCE);
    } catch (Throwable ignored) {
      // 安装失败只影响 dump 里的日志段，不影响主流程
    }
  }

  /** 从插件 logger 摘除（幂等）。 */
  public static void uninstall(Logger logger) {
    if (logger == null) {
      return;
    }
    try {
      logger.removeHandler(INSTANCE);
    } catch (Throwable ignored) {
      // 忽略
    }
  }

  /** 最近若干条（最新在前）。 */
  public static List<String> recent(int limit) {
    if (limit <= 0) {
      return List.of();
    }
    List<String> snapshot = new ArrayList<>(Math.min(limit, CAPACITY));
    for (String line : INSTANCE.lines) {
      snapshot.add(line);
    }
    // 队列按时间正序追加，输出取最新的 limit 条并倒序
    int from = Math.max(0, snapshot.size() - limit);
    List<String> out = new ArrayList<>(snapshot.size() - from);
    for (int i = snapshot.size() - 1; i >= from; i--) {
      out.add(snapshot.get(i));
    }
    return out;
  }

  @Override
  public void publish(LogRecord record) {
    try {
      if (record == null || !isLoggable(record)) {
        return;
      }
      String message = record.getMessage();
      if (message == null) {
        message = "";
      }
      if (message.length() > MAX_MESSAGE) {
        message = message.substring(0, MAX_MESSAGE) + "…";
      }
      StringBuilder line = new StringBuilder(64 + message.length());
      line.append('[').append(TIME.format(LocalTime.now())).append("] [")
          .append(record.getLevel()).append("] ").append(message);
      Throwable thrown = record.getThrown();
      if (thrown != null) {
        line.append("（").append(thrown.getClass().getSimpleName());
        String detail = thrown.getMessage();
        if (detail != null && !detail.isBlank()) {
          line.append(": ").append(detail.length() > 200 ? detail.substring(0, 200) + "…" : detail);
        }
        line.append('）');
      }
      lines.addLast(line.toString());
      while (lines.size() > CAPACITY) {
        lines.pollFirst();
      }
    } catch (Throwable ignored) {
      // 记录日志的日志：绝不再抛
    }
  }

  @Override
  public void flush() {
  }

  @Override
  public void close() {
  }
}
