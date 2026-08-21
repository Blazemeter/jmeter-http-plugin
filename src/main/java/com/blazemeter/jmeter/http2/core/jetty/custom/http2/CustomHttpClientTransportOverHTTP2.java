package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import com.blazemeter.jmeter.http2.core.ConnectAttemptRecorder;
import java.io.IOException;
import java.net.SocketAddress;
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
  private final ConnectAttemptRecorder connectAttempts;

  public CustomHttpClientTransportOverHTTP2(HTTP2Client http2Client) {
    this(http2Client, null);
  }

  public CustomHttpClientTransportOverHTTP2(HTTP2Client http2Client,
                                            ConnectAttemptRecorder connectAttempts) {
    super(http2Client);
    this.connectAttempts = connectAttempts;
  }

  /**
   * Lets the recorder see this address's failure before Jetty moves on to the next resolved
   * address. This transport carries h2c prior knowledge, where the preface is sent with no ALPN
   * to fall back on, so a server that does not speak HTTP/2 fails exactly here.
   */
  @Override
  public void connect(SocketAddress address, Map<String, Object> context) {
    if (connectAttempts != null) {
      connectAttempts.instrument(address, context);
    }
    super.connect(address, context);
  }

  @Override
  public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
      throws IOException {
    return connectionFactory.newConnection(endPoint, context);
  }
}
