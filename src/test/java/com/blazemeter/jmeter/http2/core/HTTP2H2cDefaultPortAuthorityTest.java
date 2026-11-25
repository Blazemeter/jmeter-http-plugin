package com.blazemeter.jmeter.http2.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.junit.Test;

/**
 * Issue 170: h2c prior knowledge to the default port must not send a {@code Host} that disagrees
 * with {@code :authority}.
 *
 * <p>The request URI uses port 80, which is the case the report isolates. The socket is redirected
 * to an ephemeral listener so the test does not need to bind port 80 (Windows already has HTTP.sys
 * there). The HTTP URI, and therefore {@code :authority}, stays {@code 127.0.0.1:80}.
 */
public class HTTP2H2cDefaultPortAuthorityTest extends HTTP2TestBase {

  @Test
  public void h2cPriorKnowledgeOnPort80OmitsHost() throws Exception {
    Captured captured = send(null);
    assertEquals("127.0.0.1:80", captured.authority);
    assertNull("Host must be omitted on HTTP/2 so it cannot disagree with :authority. "
            + "Saw Host=" + captured.host + " :authority=" + captured.authority
            + " raw:\n" + captured.rawHeaders,
        captured.host);
  }

  @Test
  public void explicitHostWithDefaultPortIsStillOmitted() throws Exception {
    Captured captured = send("127.0.0.1:80");
    assertEquals("127.0.0.1:80", captured.authority);
    assertNull("An explicit Host:127.0.0.1:80 must not survive into the HTTP/2 header block. "
            + "Saw Host=" + captured.host + " raw:\n" + captured.rawHeaders,
        captured.host);
    assertTrue(captured.rawHeaders.contains("accept: application/json"));
  }

  private Captured send(String explicitHost) throws Exception {
    HTTP2JettyClient.clearStaticProtocolCaches();
    AtomicReference<String> authority = new AtomicReference<>();
    AtomicReference<String> host = new AtomicReference<>();
    AtomicReference<String> rawHeaders = new AtomicReference<>();

    Server server = new Server();
    HttpConfiguration config = new HttpConfiguration();
    ServerConnector connector = new ServerConnector(server,
        new HTTP2CServerConnectionFactory(config));
    connector.setPort(0);
    server.addConnector(connector);
    server.setHandler(new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        authority.set(request.getHttpURI().getAuthority());
        host.set(request.getHeaders().get(HttpHeader.HOST));
        StringBuilder raw = new StringBuilder();
        request.getHeaders().forEach(field -> raw.append(field.getName())
            .append(": ").append(field.getValue()).append('\n'));
        rawHeaders.set(raw.toString());
        response.setStatus(201);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
        Content.Sink.write(response, true, "created\n", callback);
        return true;
      }
    });
    server.start();

    HTTP2ClientProfileConfig profile = HTTP2ClientProfileConfig.builder()
        .enableHttp1(false)
        .enableHttp2(true)
        .enableHttp3(false)
        .alpnEnabled(false)
        .fallbackEnabled(false)
        .protocolErrorFallbackEnabled(false)
        .altSvcCacheEnabled(false)
        .http1OnlyCacheEnabled(false)
        .h2cCacheEnabled(false)
        .http2PriorKnowledgeEnabled(true)
        .build();
    HTTP2JettyClient client = new HTTP2JettyClient(false, "IT-H2C-Port80", profile);
    try {
      HttpClient h2c = h2cPriorClient(client);
      h2c.setSocketAddressResolver(new PortRedirectResolver(80, connector.getLocalPort()));
      client.start();

      HTTP2Sampler sampler = new HTTP2Sampler();
      sampler.setMethod(HTTPConstants.POST);
      sampler.setDomain("127.0.0.1");
      sampler.setPort(80);
      sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
      sampler.setPath("/example/path");
      sampler.setConnectTimeout("3000");
      sampler.setResponseTimeout("5000");
      if (explicitHost != null) {
        HeaderManager headers = new HeaderManager();
        headers.add(new Header("Host", explicitHost));
        headers.add(new Header("Accept", "application/json"));
        sampler.setHeaderManager(headers);
      }
      URL url = URI.create("http://127.0.0.1:80/example/path").toURL();
      HTTPSampleResult base = new HTTPSampleResult();
      base.setURL(url);
      base.setHTTPMethod(HTTPConstants.POST);

      HTTPSampleResult result = client.sample(sampler, base, false, 0);
      assertTrue("request failed: " + result.getResponseMessage()
              + " headers seen by server:\n" + rawHeaders.get(),
          result.isSuccessful());
      return new Captured(authority.get(), host.get(), rawHeaders.get());
    } finally {
      try {
        client.stop();
      } finally {
        server.stop();
      }
    }
  }

  private static final class Captured {
    private final String authority;
    private final String host;
    private final String rawHeaders;

    private Captured(String authority, String host, String rawHeaders) {
      this.authority = authority;
      this.host = host;
      this.rawHeaders = rawHeaders;
    }
  }

  private static HttpClient h2cPriorClient(HTTP2JettyClient client) throws Exception {
    Field field = HTTP2JettyClient.class.getDeclaredField("httpClientH2cPrior");
    field.setAccessible(true);
    return (HttpClient) field.get(client);
  }

  /**
   * Resolves the name normally and then connects to {@code toPort} when the URI port is
   * {@code fromPort}. The request URI is left unchanged, so header construction still sees port 80.
   */
  private static final class PortRedirectResolver implements SocketAddressResolver {
    private final int fromPort;
    private final int toPort;

    private PortRedirectResolver(int fromPort, int toPort) {
      this.fromPort = fromPort;
      this.toPort = toPort;
    }

    @Override
    public void resolve(String host, int port, Map<String, Object> context,
                        Promise<List<InetSocketAddress>> promise) {
      int connectPort = port == fromPort ? toPort : port;
      try {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        List<InetSocketAddress> result = new ArrayList<>(addresses.length);
        for (InetAddress address : addresses) {
          result.add(new InetSocketAddress(address, connectPort));
        }
        promise.succeeded(result);
      } catch (Throwable failure) {
        promise.failed(failure);
      }
    }
  }
}
