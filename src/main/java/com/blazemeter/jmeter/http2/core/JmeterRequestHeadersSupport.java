package com.blazemeter.jmeter.http2.core;

import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
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

  static final String DEFAULT_USER_AGENT_PROPERTY =
      "httpclient4.default_user_agent_disabled";

  private static final String DEFAULT_USER_AGENT = buildDefaultUserAgent();

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
    applyDefaultUserAgentHeader(request);
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

  /**
   * Matches {@code HTTPHC4Impl}: when no {@code User-Agent} is configured, send a plugin default
   * unless {@code httpclient4.default_user_agent_disabled=true}.
   */
  private static void applyDefaultUserAgentHeader(Request request) {
    if (isDefaultUserAgentDisabled()) {
      return;
    }
    HttpFields.Mutable mutableHeaders = mutableHeaders(request);
    if (mutableHeaders == null || mutableHeaders.contains(HttpHeader.USER_AGENT)) {
      return;
    }
    mutableHeaders.put(HttpHeader.USER_AGENT, DEFAULT_USER_AGENT);
  }

  static boolean isDefaultUserAgentDisabled() {
    return JMeterUtils.getPropDefault(DEFAULT_USER_AGENT_PROPERTY, false);
  }

  static String defaultUserAgent() {
    return DEFAULT_USER_AGENT;
  }

  private static String buildDefaultUserAgent() {
    Package pkg = JmeterRequestHeadersSupport.class.getPackage();
    String version = pkg != null ? pkg.getImplementationVersion() : null;
    if (StringUtils.isBlank(version)) {
      return "BlazeMeter HTTP";
    }
    return "BlazeMeter HTTP/" + version;
  }

  /**
   * Reports the {@code Connection} header the sampler asked for, the way {@code HTTPHC4Impl} does,
   * even when this client owns that header on the wire for its own reasons.
   *
   * <p>An {@code h2c} upgrade attempt has to send {@code Connection: Upgrade, HTTP2-Settings} for
   * the upgrade to be well formed, and only some requests carry it - the ones that probe a
   * cleartext origin for HTTP/2. Leaving the sample with just those tokens made the keep-alive the
   * sampler asked for disappear from exactly those requests, so a plan asserting
   * {@code Connection: keep-alive} on its request headers failed on the one request that happened
   * to probe. The upgrade tokens stay in the reported header, with the sampler's own token in front
   * of them.
   *
   * <p>A {@code Connection} header that came from the test plan is left exactly as the plan wrote
   * it, and an HTTP/2 request gets none at all.
   */
  private static void restoreConnectionHeaderForSample(
      Request request, HttpFields.Mutable headers) {
    Object useKeepAlive = request.getAttributes().get(ATTR_USE_KEEPALIVE);
    if (useKeepAlive == null || request.getVersion() == HttpVersion.HTTP_2) {
      return;
    }
    String samplerToken = Boolean.TRUE.equals(useKeepAlive)
        ? HTTPConstants.KEEP_ALIVE
        : HTTPConstants.CONNECTION_CLOSE;
    HttpFields onTheWire = request.getHeaders();
    String connection = onTheWire == null ? null : onTheWire.get(HttpHeader.CONNECTION);
    if (connection == null) {
      headers.put(HTTPConstants.HEADER_CONNECTION, samplerToken);
      return;
    }
    if (!containsToken(connection, HttpHeader.UPGRADE.asString())
        || containsToken(connection, samplerToken)) {
      return;
    }
    headers.put(HTTPConstants.HEADER_CONNECTION, samplerToken + ", " + connection);
  }

  private static boolean containsToken(String headerValue, String token) {
    for (String candidate : headerValue.split(",")) {
      if (candidate.trim().equalsIgnoreCase(token)) {
        return true;
      }
    }
    return false;
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
