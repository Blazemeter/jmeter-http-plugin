package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.http2.client.transport.internal.HttpConnectionOverHTTP2;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

/**
 * Custom HTTP/2 connection factory that wires a custom session listener.
 */
public class CustomClientConnectionFactoryOverHTTP2 extends ContainerLifeCycle
    implements ClientConnectionFactory, HttpClient.Aware {
  private final ClientConnectionFactory factory = new HTTP2ClientConnectionFactory();
  private final HTTP2Client http2Client;

  public CustomClientConnectionFactoryOverHTTP2(HTTP2Client http2Client) {
    this.http2Client = http2Client;
    installBean(http2Client);
  }

  @Override
  public void setHttpClient(HttpClient httpClient) {
    try {
      java.lang.reflect.Method configureMethod = org.eclipse.jetty.http2.client.transport
          .HttpClientTransportOverHTTP2.class.getDeclaredMethod(
              "configure", HttpClient.class, HTTP2Client.class);
      configureMethod.setAccessible(true);
      configureMethod.invoke(null, httpClient, http2Client);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to configure HTTP/2 transport", e);
    }
  }

  @Override
  public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint,
      Map<String, Object> context) throws IOException {
    CustomHttpSessionListenerPromise listenerPromise = new CustomHttpSessionListenerPromise(
        context);
    context.put(HTTP2Client.CONTEXT_KEY, http2Client);
    context.put(HTTP2Client.SESSION_LISTENER_CONTEXT_KEY, listenerPromise);
    context.put(HTTP2Client.SESSION_PROMISE_CONTEXT_KEY, listenerPromise);
    return factory.newConnection(endPoint, context);
  }

  public static class HTTP2 extends HttpClientConnectionFactory.Info {
    private final List<String> protocols;

    public HTTP2(HTTP2Client http2Client) {
      this(http2Client, List.of("h2", "h2-17", "h2-16", "h2-15"));
    }

    public HTTP2(HTTP2Client http2Client, List<String> protocols) {
      super(new CustomClientConnectionFactoryOverHTTP2(http2Client));
      this.protocols = protocols;
    }

    @Override
    public List<String> getProtocols(boolean secure) {
      if (secure) {
        return protocols;
      }
      return List.of();
    }
  }

  public static class HTTP2C extends HttpClientConnectionFactory.Info {
    private final List<String> protocols;

    public HTTP2C(HTTP2Client http2Client) {
      this(http2Client, List.of("h2c"));
    }

    public HTTP2C(HTTP2Client http2Client, List<String> protocols) {
      super(new CustomClientConnectionFactoryOverHTTP2(http2Client));
      this.protocols = protocols;
    }

    @Override
    public List<String> getProtocols(boolean secure) {
      if (secure) {
        return List.of();
      }
      return protocols;
    }

    @Override
    public void upgrade(EndPoint endPoint, Map<String, Object> context) {
      HttpDestination destination = (HttpDestination) context.get(Destination.CONTEXT_KEY);
      @SuppressWarnings("unchecked")
      Promise<Connection> promise =
          (Promise<Connection>) context.get(Connection.PROMISE_CONTEXT_KEY);
      context.put(Connection.PROMISE_CONTEXT_KEY, new Promise<HttpConnectionOverHTTP2>() {
        @Override
        public void succeeded(HttpConnectionOverHTTP2 connection) {
          // Upgrade the connection so stream #1 maps to the upgrade request response.
          promise.succeeded(connection);
          connection.upgrade(context);
          destination.accept(connection);
        }

        @Override
        public void failed(Throwable failure) {
          promise.failed(failure);
        }
      });
      upgrade(destination.resolveClientConnectionFactory(), endPoint, context);
    }

    private void upgrade(ClientConnectionFactory factory, EndPoint endPoint,
        Map<String, Object> context) {
      try {
        endPoint.upgrade(factory.newConnection(endPoint, context));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
