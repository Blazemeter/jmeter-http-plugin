package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import java.io.IOException;
import java.util.Map;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

/**
 * Custom HTTP/2 client transport that uses {@link CustomHTTP2ClientConnectionFactory}.
 */
public class CustomHttpClientTransportOverHTTP2 extends HttpClientTransportOverHTTP2 {

  private final CustomHTTP2ClientConnectionFactory connectionFactory =
      new CustomHTTP2ClientConnectionFactory();

  public CustomHttpClientTransportOverHTTP2(HTTP2Client http2Client) {
    super(http2Client);
  }

  @Override
  public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
      throws IOException {
    return connectionFactory.newConnection(endPoint, context);
  }
}
