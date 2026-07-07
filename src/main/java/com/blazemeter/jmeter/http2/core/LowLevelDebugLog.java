package com.blazemeter.jmeter.http2.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized low-level diagnostics gate for internal HTTP client traces.
 */
public final class LowLevelDebugLog {

  public static final String LOW_LEVEL_LOG_PROPERTY = "blazemeter.http.lowLevelLog";
  private static final StackWalker CALLER_WALKER =
      StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
  private static final ConcurrentMap<Class<?>, Logger> LOGGER_CACHE = new ConcurrentHashMap<>();

  private LowLevelDebugLog() {
    // Utility class.
  }

  public static boolean isEnabled() {
    return Boolean.getBoolean(LOW_LEVEL_LOG_PROPERTY);
  }

  public static void lowLevelDebug(String message, Object... args) {
    if (!isEnabled()) {
      return;
    }
    Class<?> callerClass = CALLER_WALKER.getCallerClass();
    Logger logger = LOGGER_CACHE.computeIfAbsent(callerClass, LoggerFactory::getLogger);
    logger.debug(message, args);
  }
}
