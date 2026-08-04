package com.blazemeter.jmeter.http2.core;

import java.lang.reflect.Field;
import java.util.Map;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Resets process-wide Jetty client state that unit tests share.
 *
 * <p>{@link HTTP2JettyClient}'s protocol caches are static and keyed by origin (scheme/host/port).
 * Ephemeral test ports recycle within a suite, so a prior HTTPS HTTP/1.1 sample can leave a
 * five-minute {@code HTTP1_ONLY_CACHE} entry that makes a later HTTP/2 test negotiate HTTP/1.1
 * instead. Global {@code blazemeter.http.*} / {@code httpJettyClient.*} properties similarly leak
 * across classes when a test forces the legacy profile and cleanup is skipped or incomplete.
 */
final class HTTP2JettyClientTestIsolation {

  private static final String[] PROTOCOL_PROPERTY_SUFFIXES = {
      "profile",
      "enableHttp1",
      "enableHttp2",
      "enableHttp3",
      "alpnEnabled",
      "fallbackEnabled",
      "protocolErrorFallbackEnabled",
      "altSvcCacheEnabled",
      "http1OnlyCacheEnabled",
      "h2cCacheEnabled",
      "http2PriorKnowledge",
      "http3PriorKnowledge",
      "happyEyeballsDelayMs",
      "http1OnlyCooldownMs",
      "h2cCacheTtlMs",
      "goawayRetryEnabled"
  };

  private HTTP2JettyClientTestIsolation() {
  }

  static void resetSharedClientState() throws Exception {
    clearStaticMap("ALT_SVC_CACHE");
    clearStaticMap("HTTP1_ONLY_CACHE");
    clearStaticMap("H2C_CACHE");
    clearProtocolProperties();
  }

  static void clearProtocolProperties() {
    for (String suffix : PROTOCOL_PROPERTY_SUFFIXES) {
      removeProperty("blazemeter.http." + suffix);
      removeProperty("httpJettyClient." + suffix);
      removeProperty("HTTP2Sampler." + suffix);
    }
  }

  private static void removeProperty(String key) {
    JMeterUtils.getJMeterProperties().remove(key);
    System.clearProperty(key);
  }

  @SuppressWarnings("unchecked")
  private static void clearStaticMap(String fieldName) throws Exception {
    Field field = HTTP2JettyClient.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    Map<Object, Object> map = (Map<Object, Object>) field.get(null);
    if (map != null) {
      map.clear();
    }
  }
}
