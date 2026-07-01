package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.LowLevelDebugLog.lowLevelDebug;

import java.util.Locale;

/**
 * Detects HPACK-related failures from Jetty/JMeter surfaced errors (messages and causes).
 */
public final class HpackFailureDetector {

  private HpackFailureDetector() {
  }

  public static boolean indicatesHpackFailure(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (isHpackRelated(current)) {
        lowLevelDebug("HPACK-related failure detected: {}: {}",
            current.getClass().getName(), current.getMessage());
        return true;
      }
    }
    return false;
  }

  private static boolean isHpackRelated(Throwable failure) {
    if (failure.getClass().getName().contains("HpackException")) {
      return true;
    }
    String message = failure.getMessage();
    if (message == null || message.isEmpty()) {
      return false;
    }
    String lower = message.toLowerCase(Locale.ROOT);
    if (lower.contains("invalid_hpack")) {
      return true;
    }
    if (lower.contains("header size") && lower.contains(">")) {
      return true;
    }
    return lower.contains("hpack") && lower.contains("header");
  }
}
