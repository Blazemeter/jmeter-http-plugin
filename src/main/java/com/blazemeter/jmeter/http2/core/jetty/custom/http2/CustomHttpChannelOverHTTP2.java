package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpReceiver;
import org.eclipse.jetty.client.transport.HttpSender;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.transport.internal.HttpChannelOverHTTP2;
import org.eclipse.jetty.http2.client.transport.internal.HttpConnectionOverHTTP2;

/**
 * Custom HTTP/2 channel that injects the custom sender.
 */
public class CustomHttpChannelOverHTTP2 extends HttpChannelOverHTTP2 {
  private final HttpSender customSender;

  public CustomHttpChannelOverHTTP2(HttpConnectionOverHTTP2 connection, Session session) {
    super(connection, session);
    this.customSender = new CustomHttpSenderOverHTTP2(this);
  }

  @Override
  protected HttpSender getHttpSender() {
    return customSender;
  }

  @Override
  public void send(HttpExchange exchange) {
    customSender.send(exchange);
  }

  @Override
  public boolean isFailed() {
    HttpReceiver receiver = getHttpReceiver();
    return customSender.isFailed() || (receiver != null && receiver.isFailed());
  }
}
