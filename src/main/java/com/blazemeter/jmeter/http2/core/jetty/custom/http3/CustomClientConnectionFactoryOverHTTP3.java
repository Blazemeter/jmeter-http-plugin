package com.blazemeter.jmeter.http2.core.jetty.custom.http3;

import java.util.List;
import java.util.Map;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientConnectionFactory;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

/**
 * Custom HTTP/3 connection factory that wires a custom session listener.
 */
public class CustomClientConnectionFactoryOverHTTP3 extends ContainerLifeCycle
    implements ClientConnectionFactory, HttpClient.Aware {
  private final HTTP3ClientConnectionFactory factory = new HTTP3ClientConnectionFactory();
  private final HTTP3Client http3Client;

  public CustomClientConnectionFactoryOverHTTP3(HTTP3Client http3Client) {
    this.http3Client = http3Client;
    installBean(http3Client);
  }

  @Override
  public void setHttpClient(HttpClient httpClient) {
    try {
      java.lang.reflect.Method configureMethod = HttpClientTransportOverHTTP3.class
          .getDeclaredMethod("configure", HttpClient.class, HTTP3Client.class);
      configureMethod.setAccessible(true);
      configureMethod.invoke(null, httpClient, http3Client);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to configure HTTP/3 transport", e);
    }
  }

  @Override
  public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint,
      Map<String, Object> context) {
    return factory.newConnection(endPoint, context);
  }

  /**
   * Representation of the {@code HTTP/3} application protocol used by
   * {@link HttpClientTransportDynamic}.
   */
  public static class HTTP3 extends HttpClientConnectionFactory.Info
      implements ProtocolSession.Factory {
    private final Transport transport;
    private final List<String> protocols;

    public HTTP3(HTTP3Client client, Transport transport) {
      this(client, transport, List.of("h3"));
    }

    public HTTP3(HTTP3Client client, Transport transport, List<String> protocols) {
      super(new CustomClientConnectionFactoryOverHTTP3(client));
      this.transport = transport;
      this.protocols = protocols;
    }

    @Override
    public Transport getTransport() {
      return transport;
    }

    @Override
    public List<String> getProtocols(boolean secure) {
      if (secure) {
        return protocols;
      }
      return List.of();
    }

    @Override
    public ProtocolSession newProtocolSession(Session session, Map<String, Object> context) {
      CustomClientConnectionFactoryOverHTTP3 http3 =
          (CustomClientConnectionFactoryOverHTTP3) getClientConnectionFactory();
      context.put(HTTP3Client.CONTEXT_KEY, http3.http3Client);
      CustomSessionClientListener listener = new CustomSessionClientListener(context);
      context.put(HTTP3Client.SESSION_LISTENER_CONTEXT_KEY, listener);
      return http3.factory.newProtocolSession(session, context);
    }

    @Override
    public String toString() {
      return String.format("%s@%x", TypeUtil.toShortName(getClass()), hashCode());
    }
  }
}
