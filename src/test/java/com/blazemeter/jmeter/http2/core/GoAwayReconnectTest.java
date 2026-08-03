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
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
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
 * <p><b>What this does not cover.</b> The GOAWAY here arrives between requests, and that case is
 * absorbed by Jetty's own connection pool - measured, by disabling {@code goawayRetryEnabled} and
 * seeing the sample still succeed (the second test below pins exactly that). So these tests pin the
 * requirement, not the plugin's {@code retryAfterGoAway} path. That path is for a narrower race: a
 * GOAWAY whose {@code lastStreamId} is below a stream the client has already opened, meaning the
 * server never processed it and the request has to be replayed on a new connection. Reproducing that
 * deterministically is still open.
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
   * Same scenario with the plugin's GOAWAY retry turned off, and it still succeeds. That is the
   * measurement behind the caveat in this class: a GOAWAY between requests is handled by Jetty's
   * connection pool, so neither test here exercises {@code retryAfterGoAway}.
   *
   * <p>That is also why this test is worth keeping rather than deleting the retry it makes look
   * redundant: Jetty 11 did <em>not</em> absorb this in the pool, so the plugin's retry is what
   * covered it. Keeping both means a future Jetty that stops absorbing it shows up here as a
   * failure - the retry path becoming load-bearing again - instead of silently turning into failed
   * samples in a real test run.
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

    sendGoAway();

    HTTPSampleResult second = sample();

    assertThat(second.isSuccessful())
        .as("a GOAWAY must be absorbed by reconnecting, never surface as a failed sample")
        .isTrue();
    assertThat(second.getResponseCode())
        .as("and the sample must carry the real response, not a synthetic error")
        .isEqualTo("200");
  }

  /** Emits GOAWAY(NO_ERROR) on every session accepted so far, as a server shedding load would. */
  private void sendGoAway() {
    for (Session session : serverSessions) {
      session.close(ErrorCode.NO_ERROR.code, "test-goaway", Callback.NOOP);
    }
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
