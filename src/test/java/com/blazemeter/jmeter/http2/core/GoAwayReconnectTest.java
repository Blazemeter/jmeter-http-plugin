package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.server.internal.HTTP2ServerConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A GOAWAY is how a server protects itself: it tells the client to stop opening streams on this
 * connection and to come back on a new one. Browsers absorb it and the user never sees an error, and
 * a load test has to behave the same way - the reconnection may cost time, but the sample must not
 * come out as an error.
 *
 * <p>The frame is real here, not simulated. A {@link Connection.Listener} on the server connector
 * captures the HTTP/2 session of each accepted connection, and the test then makes that session emit
 * GOAWAY through Jetty's own API. Emitting it per session rather than shutting the connector down is
 * deliberate: the server has to keep accepting, because a retry can only succeed on a fresh
 * connection.
 *
 * <p><b>Sequencing matters.</b> Jetty has a known race if a new request is dispatched while a
 * connection is still in the pool as {@code REMOTELY_CLOSED} after GOAWAY
 * ({@code IllegalStateException: session closed}). These tests model GOAWAY <em>between</em>
 * requests, so after sending GOAWAY they wait until the client's connection pool has dropped that
 * connection before the next sample. That is the steady state a browser settles into; racing the
 * next request against GOAWAY processing is a different scenario (covered by the plugin's
 * {@code retryAfterGoAway} path, which is not what this class pins).
 */
public class GoAwayReconnectTest extends HTTP2TestBase {

  private static final String GOAWAY_RETRY_PROP = "httpJettyClient.goawayRetryEnabled";

  private final List<Session> serverSessions = new CopyOnWriteArrayList<>();
  private final List<String> observedConnections = new CopyOnWriteArrayList<>();

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;
  private int port = -1;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    // Static HTTP/1-only cache entries survive across tests and are keyed by host:port. Ephemeral
    // ports recycle in a full suite, so a prior HTTPS HTTP/1.1 sample can force this client onto
    // HTTP/1.1 and leave no HTTP/2 session to GOAWAY. Same for leaked legacy profile properties.
    HTTP2JettyClientTestIsolation.resetSharedClientState();

    // This exact combination is what actually negotiates HTTP/2; withSSL().withALPN().withHTTP2()
    // alone makes the client fall back to HTTP/1.1, which would leave no session to GOAWAY.
    server = new ServerBuilder().withHTTP2().withALPN().withHTTP2C().withSSL().buildServer();
    ServerConnector connector = (ServerConnector) server.getConnectors()[0];
    connector.addBean(new Connection.Listener() {
      @Override
      public void onOpened(Connection connection) {
        observedConnections.add(connection.getClass().getName());
        if (connection instanceof HTTP2ServerConnection) {
          serverSessions.add(((HTTP2ServerConnection) connection).getSession());
        }
      }

      @Override
      public void onClosed(Connection connection) {
        // Nothing to do: the test only needs the sessions as they are accepted.
      }
    });
    server.start();
    port = connector.getLocalPort();

    sampler = new HTTP2Sampler();
    sampler.setDomain(HOST_NAME);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(port);
  }

  @After
  public void tearDown() throws Exception {
    JMeterUtils.getJMeterProperties().remove(GOAWAY_RETRY_PROP);
    System.clearProperty(GOAWAY_RETRY_PROP);
    if (client != null) {
      client.stop();
    }
    if (server != null && server.isStarted()) {
      server.stop();
    }
  }

  @Test
  public void shouldNotFailSampleWhenServerSendsGoAway() throws Exception {
    startClient();

    assertSampleSurvivesGoAway();
  }

  /**
   * Same scenario with the plugin's GOAWAY retry turned off. After the client has dropped the
   * GOAWAY'd connection from its pool, Jetty opens a fresh one for the next sample - so this still
   * succeeds without {@code retryAfterGoAway}. That path remains for the in-flight race this class
   * deliberately does not reproduce.
   */
  @Test
  public void shouldNotFailSampleWhenServerSendsGoAwayEvenWithPluginRetryDisabled()
      throws Exception {
    JMeterUtils.getJMeterProperties().setProperty(GOAWAY_RETRY_PROP, "false");
    startClient();

    assertSampleSurvivesGoAway();
  }

  private void assertSampleSurvivesGoAway() throws Exception {
    HTTPSampleResult first = sample();
    assertThat(first.isSuccessful()).as("the first sample sets up the connection").isTrue();
    assertThat(serverSessions)
        .as("an HTTP/2 session must have been accepted, otherwise no GOAWAY can be emitted; "
            + "connections opened were %s", observedConnections)
        .isNotEmpty();
    assertThat(clientConnectionCount())
        .as("the first sample should leave a pooled client connection")
        .isGreaterThan(0);

    sendGoAwayAndAwaitClientPoolDrained();

    HTTPSampleResult second = sample();

    assertThat(second.isSuccessful())
        .as("a GOAWAY must be absorbed by reconnecting, never surface as a failed sample")
        .isTrue();
    assertThat(second.getResponseCode())
        .as("and the sample must carry the real response, not a synthetic error")
        .isEqualTo("200");
  }

  /**
   * Emits GOAWAY(NO_ERROR), waits for the server sessions to close, then waits until the client's
   * pool no longer holds that connection. Only then is the next sample free of the
   * {@code REMOTELY_CLOSED} dispatch race.
   */
  private void sendGoAwayAndAwaitClientPoolDrained() throws InterruptedException {
    CountDownLatch goAwaySent = new CountDownLatch(serverSessions.size());
    for (Session session : serverSessions) {
      session.close(ErrorCode.NO_ERROR.code, "test-goaway",
          Callback.from(goAwaySent::countDown, failure -> goAwaySent.countDown()));
    }
    assertThat(goAwaySent.await(5, TimeUnit.SECONDS))
        .as("server should finish sending GOAWAY")
        .isTrue();

    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadlineNanos) {
      if (serverSessions.stream().allMatch(Session::isClosed)
          && clientConnectionCount() == 0) {
        return;
      }
      Thread.sleep(10L);
    }
    assertThat(serverSessions)
        .as("all GOAWAY'd server sessions should be closed")
        .allMatch(Session::isClosed);
    assertThat(clientConnectionCount())
        .as("client pool should have dropped the GOAWAY'd connection before the next sample")
        .isZero();
  }

  private int clientConnectionCount() {
    HttpClient httpClient = client.getHttpClient();
    int total = 0;
    for (Destination destination : httpClient.getDestinations()) {
      ConnectionPool pool = destination.getConnectionPool();
      if (pool instanceof AbstractConnectionPool) {
        total += ((AbstractConnectionPool) pool).getConnectionCount();
      } else if (pool != null && !pool.isEmpty()) {
        total += 1;
      }
    }
    return total;
  }

  private void startClient() throws Exception {
    // Pin HTTP/2+ALPN regardless of any leftover suite-wide protocol properties.
    HTTP2ClientProfileConfig profile = HTTP2ClientProfileConfig.builder()
        .profile("browser-like-custom")
        .enableHttp1(true)
        .enableHttp2(true)
        .enableHttp3(false)
        .alpnEnabled(true)
        .http1OnlyCacheEnabled(false)
        .build();
    client = new HTTP2JettyClient(false, "goaway-reconnect-test", profile);
    client.start();
  }

  private HTTPSampleResult sample() throws Exception {
    URL url = createUrl();
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(url);
    result.setHTTPMethod(HTTPConstants.GET);
    result.setSampleLabel(url.toString());
    return client.sample(sampler, result, false, 0);
  }

  private URL createUrl() throws MalformedURLException {
    try {
      return new URI(HTTPConstants.PROTOCOL_HTTPS, null, HOST_NAME, port, SERVER_PATH_200,
          null, null).toURL();
    } catch (URISyntaxException e) {
      MalformedURLException ex = new MalformedURLException(e.getMessage());
      ex.initCause(e);
      throw ex;
    }
  }
}
