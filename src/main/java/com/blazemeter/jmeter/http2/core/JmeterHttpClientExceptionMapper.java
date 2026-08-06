package com.blazemeter.jmeter.http2.core;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.apache.http.HttpHost;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.conn.HttpHostConnectException;

/**
 * Maps Jetty client failures to the exception types JMeter {@code HTTPHC4Impl} reports in
 * {@code HTTPSampleResult} for semantic parity.
 */
public final class JmeterHttpClientExceptionMapper {

  private JmeterHttpClientExceptionMapper() {
  }

  public static Throwable forSampleResult(Throwable thrown, boolean autoRedirects) {
    return forSampleResult(thrown, autoRedirects, null);
  }

  public static Throwable forSampleResult(Throwable thrown, boolean autoRedirects, URL url) {
    if (thrown == null) {
      return null;
    }
    Throwable root = unwrap(thrown);
    if (autoRedirects && isAutoRedirectProtocolFailure(root)) {
      return new ClientProtocolException();
    }
    Throwable connectMapped = mapConnectFailure(root, url);
    if (connectMapped != null) {
      return connectMapped;
    }
    return thrown;
  }

  private static Throwable unwrap(Throwable throwable) {
    Throwable current = throwable;
    while (current instanceof ExecutionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static boolean isAutoRedirectProtocolFailure(Throwable throwable) {
    if (throwable == null) {
      return false;
    }
    if (!"HttpResponseException".equals(throwable.getClass().getSimpleName())) {
      return false;
    }
    String message = throwable.getMessage();
    return message != null && message.contains("Max redirects exceeded");
  }

  private static Throwable mapConnectFailure(Throwable root, URL url) {
    if (root instanceof HttpHostConnectException) {
      return root;
    }
    if (root instanceof ConnectException) {
      HttpHost host = toHttpHost(url);
      ConnectException cause = normalizeConnectCause((ConnectException) root);
      if (host != null) {
        try {
          return new HttpHostConnectException(cause, host,
              InetAddress.getAllByName(host.getHostName()));
        } catch (UnknownHostException ignored) {
          // Fall back to host-only message formatting.
        }
      }
      return new HttpHostConnectException(cause, host);
    }
    return null;
  }

  private static ConnectException normalizeConnectCause(ConnectException root) {
    String message = root.getMessage();
    if (message != null && message.toLowerCase(Locale.ROOT).contains("getsockopt")) {
      ConnectException normalized = new ConnectException("Connection refused: connect");
      normalized.initCause(root);
      return normalized;
    }
    return root;
  }

  private static HttpHost toHttpHost(URL url) {
    if (url == null || url.getHost() == null || url.getHost().isEmpty()) {
      return null;
    }
    int port = url.getPort();
    if (port < 0) {
      port = url.getDefaultPort();
    }
    String scheme = url.getProtocol();
    if (scheme == null || scheme.isEmpty()) {
      return new HttpHost(url.getHost(), port);
    }
    return new HttpHost(url.getHost(), port, scheme);
  }
}
