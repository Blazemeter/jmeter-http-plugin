package com.blazemeter.jmeter.http2.core;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.net.ssl.SSLEngine;

/**
 * Holds the client certificate alias resolved on the JMeter thread and bound to the
 * {@link SSLEngine} used during the async Jetty TLS handshake.
 *
 * <p>Keys are weak so closed engines can be reclaimed. A strong {@code ConcurrentHashMap} retained
 * every historical {@code SSLEngine} for the JVM lifetime because {@link #clearEngine} had no call
 * sites on connection close.
 */
final class SslClientCertAliasContext {

  static final String CONNECTION_CONTEXT_KEY = "bzm.jmeter.ssl.clientCertAlias";
  static final String REQUEST_ATTRIBUTE = CONNECTION_CONTEXT_KEY;

  private static final Map<SSLEngine, String> ENGINE_ALIASES =
      Collections.synchronizedMap(new WeakHashMap<>());

  private SslClientCertAliasContext() {
  }

  static void bindEngine(SSLEngine engine, String alias) {
    if (engine != null && alias != null && !alias.isEmpty()) {
      ENGINE_ALIASES.put(engine, alias);
    }
  }

  static String resolveFromEngine(SSLEngine engine) {
    if (engine == null) {
      return null;
    }
    return ENGINE_ALIASES.get(engine);
  }

  static void clearEngine(SSLEngine engine) {
    if (engine != null) {
      ENGINE_ALIASES.remove(engine);
    }
  }

  static String readAlias(Map<String, Object> context) {
    if (context == null) {
      return null;
    }
    Object alias = context.get(CONNECTION_CONTEXT_KEY);
    return alias instanceof String ? (String) alias : null;
  }

  /** Visible for leak regression tests. */
  static int engineAliasCountForTests() {
    return ENGINE_ALIASES.size();
  }

}
