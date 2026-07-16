package com.blazemeter.jmeter.http2.core;

/**
 * Request attributes shared between the Jetty client layer and JMeter parity hooks.
 *
 * <p>Not yet merged into {@code development}: {@code SKIP_H2C_UPGRADE} is part of the still
 * pending "skip h2c upgrade for cleartext requests with a body" fix (depends on
 * {@code shouldAttachRequestBody}). Once that lands, this class should go away like
 * {@code USE_KEEPALIVE} and {@code H2C_FALLBACK_ATTEMPTED} already did (see
 * {@code JmeterRequestHeadersSupport#ATTR_USE_KEEPALIVE} and
 * {@code HTTP2JettyClient#ATTR_H2C_FALLBACK_ATTEMPTED}).
 */
public final class JmeterHttpClientAttributes {

  public static final String SKIP_H2C_UPGRADE = "bzm.skipH2cUpgrade";

  private JmeterHttpClientAttributes() {
  }
}
