package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Reproduction of the failure seen against {@code login.microsoftonline.com} from inside an AKS
 * pod, which does not happen from outside it. Both halves of that environment are simulated here.
 *
 * <p><b>The peer.</b> {@code withSSL()} without {@code withALPN()} terminates TLS and hands
 * straight to HTTP/1.1, so no ALPN protocol is ever selected - the shape a TLS-inspecting egress
 * produces, and what curl reported from the pod ({@code ALPN: server did not agree on a
 * protocol}). Jetty then picks the first configured protocol, HTTP/2, and the preface is answered
 * with HTTP/1.1 bytes, which our parser rejects with {@code FRAME_SIZE_ERROR}.
 *
 * <p><b>The address list.</b> The pod resolved 8 usable IPv4 addresses followed by 8 unreachable
 * IPv6 ones. Binding the server to {@code 127.0.0.1} only, and sampling {@code localhost},
 * reproduces that: the first address works at the socket level and dies at the HTTP/2 preface, and
 * the next one fails outright - so the failure that survives Jetty's connect loop is the second
 * one, and the perfectly usable first address is never reported.
 *
 * <p>That second half is what makes this fail rather than merely be slow. With a single address
 * the plugin's HTTP/1.1 fallback rescues the sample after one wasted connection, which is why this
 * never reproduced outside the pod.
 */
public class AlpnAbsentHttp2RejectedTest extends HTTP2TestBase {

  /** One entry per TCP connection the server accepted: an SslConnection is created per socket. */
  private final List<String> accepted = new CopyOnWriteArrayList<>();

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private int port;

  @Before
  public void setUp() throws Exception {
    HTTP2JettyClientTestIsolation.resetSharedClientState();
    // Only the IPv4 loopback listens, so the other address localhost resolves to fails to connect
    // exactly the way the pod's IPv6 addresses did.
    server = buildServer("127.0.0.1");
    server.start();
    port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (server != null && server.isStarted()) {
      server.stop();
    }
  }

  @Test
  public void samplesSuccessfullyThroughTheAddressThatWorks() throws Exception {
    InetAddress[] addresses = InetAddress.getAllByName("localhost");
    assumeTrue("localhost resolves to a single address here, so there is no roll-over to lose it",
        addresses.length > 1);
    assumeTrue("this reproduction needs IPv4 to be tried first, as the JDK default does",
        addresses[0] instanceof Inet4Address);

    client = new HTTP2JettyClient(false, "alpn-absent-multi-address");
    client.start();

    HTTPSampleResult result = sample();

    assertThat(result.isSuccessful())
        .as("127.0.0.1 serves this fine over HTTP/1.1; only the HTTP/2 attempt against it failed, "
            + "and that must not turn into a failed sample because a later address is unreachable")
        .isTrue();
    assertThat(result.getResponseCode()).isEqualTo("200");
  }

  @Test
  public void remembersTheOriginSoLaterSamplesDoNotRetryHttp2() throws Exception {
    // The first sample pays one rejected HTTP/2 attempt per usable address; the point of
    // remembering the origin is that no later sample pays it again. Counted server side, where a
    // rejected attempt is an extra accepted connection.
    client = new HTTP2JettyClient(false, "alpn-absent-remembered");
    client.start();
    sample();
    int afterFirst = accepted.size();

    sample();

    assertThat(accepted.size() - afterFirst)
        .as("the second sample must not open more connections than the one it needs")
        .isEqualTo(1);
  }

  @Test
  public void stopsReOfferingHttp2ToTheRemainingAddressesOfTheSameRequest() throws Exception {
    InetAddress[] addresses = InetAddress.getAllByName("localhost");
    assumeTrue("needs more than one usable address to have a roll-over worth shortening",
        addresses.length > 1);
    // This server listens on every address localhost resolves to, so each one is usable and each
    // one would otherwise be offered HTTP/2 and refuse it in turn.
    server.stop();
    accepted.clear();
    server = buildServer(null);
    server.start();
    port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    client = new HTTP2JettyClient(false, "alpn-absent-rollover");
    client.start();

    HTTPSampleResult result = sample();

    assertThat(result.isSuccessful()).isTrue();
    assertThat(accepted.size())
        .as("one refused HTTP/2 attempt is unavoidable before the origin is known; paying it "
            + "again on every remaining address is not")
        .isEqualTo(2);
  }

  private TeardownableServer buildServer(String host) {
    TeardownableServer built = new ServerBuilder().withSSL().withHTTP1().buildServer();
    ServerConnector connector = (ServerConnector) built.getConnectors()[0];
    if (host != null) {
      connector.setHost(host);
    }
    connector.addBean(new Connection.Listener() {
      @Override
      public void onOpened(Connection connection) {
        if (connection instanceof SslConnection) {
          accepted.add(connection.toString());
        }
      }

      @Override
      public void onClosed(Connection connection) {
        // Only the accepted count matters here.
      }
    });
    return built;
  }

  private HTTPSampleResult sample() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("localhost");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(port);
    sampler.setPath(SERVER_PATH_200);

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("GET_ALPN_ABSENT_MULTI_ADDRESS");
    result.setHTTPMethod(HTTPConstants.GET);
    result.setURL(new URL(HTTPConstants.PROTOCOL_HTTPS, "localhost", port, SERVER_PATH_200));
    return client.sample(sampler, result, false, 0);
  }
}
