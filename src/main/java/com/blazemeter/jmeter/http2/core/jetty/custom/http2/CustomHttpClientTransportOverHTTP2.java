package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import com.blazemeter.jmeter.http2.core.ConnectAttemptRecorder;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.api.Session;
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
   * h2c prior knowledge is the only cleartext HTTP/2 path that does not go through
   * {@code CustomClientConnectionFactoryOverHTTP2}. Jetty's own session listener would then build
   * a stock {@code HttpConnectionOverHTTP2}, whose sender leaves {@code Host} in the HEADERS
   * block. {@code :authority} still comes from the URI, and for an explicit port 80 that value
   * keeps {@code :80} while {@code ensureHostHeader} strips the default port, so a strict server
   * resets the stream (issue 170). The custom connection installs the sender that drops
   * {@code Host} before the frame is built.
   */
  @Override
  protected org.eclipse.jetty.client.Connection newConnection(Destination destination,
      Session session, HTTP2Connection connection) {
    return new CustomHttpConnectionOverHTTP2(destination, session, connection);
  }

  @Override
  public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
      throws IOException {
    return connectionFactory.newConnection(endPoint, context);
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
}
