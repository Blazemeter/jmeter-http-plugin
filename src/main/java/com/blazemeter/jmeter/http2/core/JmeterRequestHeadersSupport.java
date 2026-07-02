package com.blazemeter.jmeter.http2.core;

import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;

/**
 * Preserves JMeter-visible request headers in {@code HTTPSampleResult#getRequestHeaders()}
 * when Jetty omits or strips them on the wire.
 *
 * <p>Counterpart of {@link JmeterCompressionHeadersSupport} for outbound request headers.
 */
final class JmeterRequestHeadersSupport {

  static final String ATTR_USE_KEEPALIVE = "bzm.useKeepAlive";

  private JmeterRequestHeadersSupport() {
  }

  /**
   * Applies sampler-driven request headers before send, matching {@code HTTPHC4Impl#setupRequest}.
   */
  static void prepareFromSampler(Request request, boolean useKeepAlive) {
    if (request == null) {
      return;
    }
    request.attribute(ATTR_USE_KEEPALIVE, useKeepAlive);
    applyConnectionHeader(request, useKeepAlive);
    // TODO: HC4 sends explicit empty User-Agent when none is configured (disableDefaultUserAgent).
    // prepareEmptyUserAgentHeader(request);
  }

  /**
   * Copies sampler header settings to a cloned request (for example HTTP/1.1 fallback).
   */
  static void copySamplerHeaderState(Request from, Request to) {
    if (from == null || to == null) {
      return;
    }
    Object useKeepAlive = from.getAttributes().get(ATTR_USE_KEEPALIVE);
    if (useKeepAlive != null) {
      to.attribute(ATTR_USE_KEEPALIVE, useKeepAlive);
    }
  }

  /**
   * Request headers to record in the sample after Jetty may have adjusted the live fields.
   */
  static HttpFields headersForSampleResult(Request request) {
    if (request == null) {
      return HttpFields.EMPTY;
    }
    HttpFields.Mutable merged = HttpFields.build(request.getHeaders());
    restoreConnectionHeaderForSample(request, merged);
    // restoreEmptyUserAgentForSample(request, merged);
    return merged;
  }

  private static void applyConnectionHeader(Request request, boolean useKeepAlive) {
    if (!shouldSendConnectionHeader(request)) {
      return;
    }
    HttpFields.Mutable mutableHeaders = mutableHeaders(request);
    if (mutableHeaders == null || mutableHeaders.contains(HttpHeader.CONNECTION)) {
      return;
    }
    if (useKeepAlive) {
      mutableHeaders.put(HTTPConstants.HEADER_CONNECTION, HTTPConstants.KEEP_ALIVE);
    } else {
      mutableHeaders.put(HTTPConstants.HEADER_CONNECTION, HTTPConstants.CONNECTION_CLOSE);
    }
  }

  private static void restoreConnectionHeaderForSample(Request request, HttpFields.Mutable headers) {
    Object useKeepAlive = request.getAttributes().get(ATTR_USE_KEEPALIVE);
    if (useKeepAlive == null || !shouldSendConnectionHeader(request)) {
      return;
    }
    if (Boolean.TRUE.equals(useKeepAlive)) {
      headers.put(HTTPConstants.HEADER_CONNECTION, HTTPConstants.KEEP_ALIVE);
    } else {
      headers.put(HTTPConstants.HEADER_CONNECTION, HTTPConstants.CONNECTION_CLOSE);
    }
  }

  private static boolean shouldSendConnectionHeader(Request request) {
    if (request != null && request.getVersion() == HttpVersion.HTTP_2) {
      return false;
    }
    HttpFields headers = request.getHeaders();
    return headers == null || !headers.contains(HttpHeader.CONNECTION);
  }

  private static HttpFields.Mutable mutableHeaders(Request request) {
    HttpFields headers = request.getHeaders();
    if (headers instanceof HttpFields.Mutable) {
      return (HttpFields.Mutable) headers;
    }
    return null;
  }
}