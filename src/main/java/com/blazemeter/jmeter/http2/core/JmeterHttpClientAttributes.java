package com.blazemeter.jmeter.http2.core;

/**
 * Request attributes shared between the Jetty client layer and JMeter parity hooks.
 */
public final class JmeterHttpClientAttributes {

  public static final String USE_KEEPALIVE = "bzm.useKeepAlive";
  public static final String H2C_FALLBACK_ATTEMPTED = "bzm.h2cFallbackAttempted";
  public static final String SKIP_H2C_UPGRADE = "bzm.skipH2cUpgrade";

  private JmeterHttpClientAttributes() {
  }
}
