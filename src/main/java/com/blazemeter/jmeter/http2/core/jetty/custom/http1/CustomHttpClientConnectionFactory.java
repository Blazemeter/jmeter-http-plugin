package com.blazemeter.jmeter.http2.core.jetty.custom.http1;

import java.util.List;
import java.util.Map;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

/**
 * HTTP/1 connection factory wired for JMeter HttpClient4 header parity.
 */
public class CustomHttpClientConnectionFactory extends HttpClientConnectionFactory {

  /**
   * Custom HTTP/1.1 factory for JMeter header parity. Use this instead of the inherited
   * {@link HttpClientConnectionFactory#HTTP11} static field (same simple name as the nested class).
   */
  public static final ClientConnectionFactory.Info CUSTOM_HTTP11 = new HTTP11();

  @Override
  public Connection newConnection(EndPoint endPoint, Map<String, Object> context) {
    HttpConnectionOverHTTP connection = new CustomHttpConnectionOverHTTP(endPoint, context);
    connection.setInitialize(isInitializeConnections());
    return customize(connection, context);
  }

  public static class HTTP11 extends ClientConnectionFactory.Info {

    private final List<String> protocols;

    public HTTP11() {
      this(List.of("http/1.1"));
    }

    public HTTP11(List<String> protocols) {
      super(new CustomHttpClientConnectionFactory());
      this.protocols = protocols;
    }

    @Override
    public List<String> getProtocols(boolean secure) {
      return protocols;
    }
  }
}
