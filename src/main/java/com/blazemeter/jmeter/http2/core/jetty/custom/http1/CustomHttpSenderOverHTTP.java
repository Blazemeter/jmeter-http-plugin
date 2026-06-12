package com.blazemeter.jmeter.http2.core.jetty.custom.http1;

import com.blazemeter.jmeter.http2.core.JmeterHttpClientAttributes;
import java.nio.ByteBuffer;
import java.util.Locale;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.internal.HttpChannelOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpSenderOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

/**
 * HTTP/1 sender that preserves explicit {@code Connection: keep-alive} like Apache HttpClient4.
 */
public class CustomHttpSenderOverHTTP extends HttpSenderOverHTTP {

  public CustomHttpSenderOverHTTP(HttpChannelOverHTTP channel) {
    super(channel);
  }

  @Override
  protected void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent,
      Callback callback) {
    HttpRequest request = exchange.getRequest();
    if (!shouldEmitExplicitKeepAlive(request)) {
      super.sendHeaders(exchange, contentBuffer, lastContent, callback);
      return;
    }

    HttpVersion savedVersion = request.getVersion();
    KeepAliveParityEndPoint parityEndPoint = resolveParityEndPoint();
    if (parityEndPoint != null) {
      parityEndPoint.enableRequestLinePatch();
    }
    request.version(HttpVersion.HTTP_1_0);
    super.sendHeaders(exchange, contentBuffer, lastContent, new Callback() {
      @Override
      public void succeeded() {
        try {
          callback.succeeded();
        } finally {
          request.version(savedVersion);
          if (parityEndPoint != null) {
            parityEndPoint.disableRequestLinePatch();
          }
        }
      }

      @Override
      public void failed(Throwable x) {
        try {
          callback.failed(x);
        } finally {
          request.version(savedVersion);
          if (parityEndPoint != null) {
            parityEndPoint.disableRequestLinePatch();
          }
        }
      }
    });
  }

  private KeepAliveParityEndPoint resolveParityEndPoint() {
    HttpConnectionOverHTTP connection = getHttpChannel().getHttpConnection();
    EndPoint endPoint = connection.getEndPoint();
    if (endPoint instanceof KeepAliveParityEndPoint) {
      return (KeepAliveParityEndPoint) endPoint;
    }
    if (endPoint instanceof EndPoint.Wrapper) {
      EndPoint unwrapped = ((EndPoint.Wrapper) endPoint).unwrap();
      if (unwrapped instanceof KeepAliveParityEndPoint) {
        return (KeepAliveParityEndPoint) unwrapped;
      }
    }
    return null;
  }

  private boolean shouldEmitExplicitKeepAlive(HttpRequest request) {
    if (hasBlockingConnectionToken(request)) {
      return false;
    }
    Object useKeepAlive = request.getAttributes().get(JmeterHttpClientAttributes.USE_KEEPALIVE);
    if (Boolean.TRUE.equals(useKeepAlive)) {
      return request.getVersion() == HttpVersion.HTTP_1_1;
    }
    String connection = request.getHeaders().get(HttpHeader.CONNECTION);
    return connection != null
        && connection.toLowerCase(Locale.ROOT).contains("keep-alive");
  }

  private boolean hasBlockingConnectionToken(HttpRequest request) {
    String connection = request.getHeaders().get(HttpHeader.CONNECTION);
    if (connection == null) {
      return false;
    }
    for (String token : connection.split(",")) {
      String trimmed = token.trim();
      if ("Upgrade".equalsIgnoreCase(trimmed) || "close".equalsIgnoreCase(trimmed)) {
        return true;
      }
    }
    return false;
  }
}
