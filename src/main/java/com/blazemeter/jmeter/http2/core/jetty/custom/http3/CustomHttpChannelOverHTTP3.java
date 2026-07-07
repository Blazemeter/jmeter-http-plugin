package com.blazemeter.jmeter.http2.core.jetty.custom.http3;

import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpSender;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.transport.internal.HttpChannelOverHTTP3;
import org.eclipse.jetty.http3.client.transport.internal.HttpConnectionOverHTTP3;

/**
 * Custom HTTP/3 channel that injects the custom sender.
 */
public class CustomHttpChannelOverHTTP3 extends HttpChannelOverHTTP3 {
  private final HttpSender customSender;

  public CustomHttpChannelOverHTTP3(HttpConnectionOverHTTP3 connection,
      HTTP3SessionClient session) {
    super(connection, session);
    this.customSender = new CustomHttpSenderOverHTTP3(this);
  }

  @Override
  protected HttpSender getHttpSender() {
    return customSender;
  }

  @Override
  public void send(HttpExchange exchange) {
    customSender.send(exchange);
  }
}
