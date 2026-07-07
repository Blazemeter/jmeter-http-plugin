package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.transport.internal.HttpChannelOverHTTP2;
import org.eclipse.jetty.http2.client.transport.internal.HttpConnectionOverHTTP2;

/**
 * Custom HTTP/2 connection that creates custom channels.
 */
public class CustomHttpConnectionOverHTTP2 extends HttpConnectionOverHTTP2 {
  public CustomHttpConnectionOverHTTP2(Destination destination, Session session,
      HTTP2Connection connection) {
    super(destination, session, connection);
  }

  @Override
  protected HttpChannelOverHTTP2 newHttpChannel() {
    return new CustomHttpChannelOverHTTP2(this, getSession());
  }
}
