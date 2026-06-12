package com.blazemeter.jmeter.http2.core.jetty.custom.http1;

import org.eclipse.jetty.client.transport.internal.HttpChannelOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpSenderOverHTTP;

/**
 * HTTP/1 channel that injects the custom sender.
 */
public class CustomHttpChannelOverHTTP extends HttpChannelOverHTTP {

  public CustomHttpChannelOverHTTP(HttpConnectionOverHTTP connection) {
    super(connection);
  }

  @Override
  protected HttpSenderOverHTTP newHttpSender() {
    return new CustomHttpSenderOverHTTP(this);
  }
}
