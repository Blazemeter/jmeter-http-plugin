package com.blazemeter.jmeter.http2.core.jetty.custom.http3;

import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.transport.internal.HttpChannelOverHTTP3;
import org.eclipse.jetty.http3.client.transport.internal.HttpConnectionOverHTTP3;

/**
 * Custom HTTP/3 connection that creates custom channels.
 */
public class CustomHttpConnectionOverHTTP3 extends HttpConnectionOverHTTP3 {
  public CustomHttpConnectionOverHTTP3(Destination destination, HTTP3SessionClient session) {
    super(destination, session);
  }

  @Override
  protected HttpChannelOverHTTP3 newHttpChannel() {
    return new CustomHttpChannelOverHTTP3(this, getSession());
  }
}
