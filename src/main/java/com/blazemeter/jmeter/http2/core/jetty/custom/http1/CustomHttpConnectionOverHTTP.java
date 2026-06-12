package com.blazemeter.jmeter.http2.core.jetty.custom.http1;

import org.eclipse.jetty.client.transport.internal.HttpChannelOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.io.EndPoint;

/**
 * HTTP/1 connection that creates custom channels over a parity-aware endpoint.
 */
public class CustomHttpConnectionOverHTTP extends HttpConnectionOverHTTP {

  public CustomHttpConnectionOverHTTP(EndPoint endPoint, java.util.Map<String, Object> context) {
    super(endPoint instanceof KeepAliveParityEndPoint ? endPoint
        : new KeepAliveParityEndPoint(endPoint), context);
  }

  @Override
  protected HttpChannelOverHTTP newHttpChannel() {
    return new CustomHttpChannelOverHTTP(this);
  }
}
