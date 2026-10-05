package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * "Same user on each iteration" unchecked means JMeter simulates a new visitor, and the Thread Group
 * documentation states that a new connection is then opened between iterations - JMeter's own
 * HttpClient4 sampler implements it by closing idle and expired connections
 * ({@code HTTPHC4Impl#resetStateIfNeeded}). This pins that the plugin does the same instead of what
 * it used to do, which was clearing Jetty's byte buffer pool: buffers are reset on release and carry
 * no user state, while the connection is what identifies the user to the server.
 */
public class NewUserIterationConnectionResetTest extends HTTP2TestBase {

  /** {@code JMeterVariables.VAR_IS_SAME_USER_KEY}, which is not public. */
  private static final String SAME_USER_VARIABLE = "__jmv_SAME_USER";

  private static final String HELD_PATH = "/held";
  private static final long AWAIT_SECONDS = 10;

  private TeardownableServer server;
  private Server heldResponseServer;
  private HTTP2Sampler sampler;

  @Before
  public void setUp() throws Exception {
    HTTP2JettyClientTestIsolation.resetSharedClientState();
    server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

    sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setProtocol("http");
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setPath(SERVER_PATH_200);
    sampler.setUseKeepAlive(true);
  }

  @After
  public void tearDown() throws Exception {
    if (sampler != null) {
      sampler.threadFinished();
    }
    if (server != null && server.isStarted()) {
      server.stop();
    }
    if (heldResponseServer != null) {
      heldResponseServer.stop();
    }
    JMeterContextService.getContext().setVariables(new JMeterVariables());
  }

  @Test
  public void newUserIterationClosesThePooledConnection() throws Exception {
    sampleAndAssertPooledConnection();

    startIteration(false);

    assertThat(pooledConnectionCount())
        .as("a new user must not reuse the previous iteration's connection")
        .isZero();
  }

  @Test
  public void sameUserIterationKeepsThePooledConnection() throws Exception {
    sampleAndAssertPooledConnection();

    startIteration(true);

    assertThat(pooledConnectionCount())
        .as("a returning visitor must keep the connection, or every iteration pays a handshake")
        .isGreaterThan(0);
  }

  /**
   * Same reset over HTTP/2, where a connection is a multiplexed session closed with a GOAWAY: the
   * session must be out of the pool right after the reset, and the next sample must still be
   * served over HTTP/2.
   */
  @Test
  public void newUserIterationClosesThePooledHttp2Connection() throws Exception {
    server.stop();
    // This combination is what negotiates HTTP/2 (see GoAwayReconnectTest).
    server = new ServerBuilder().withHTTP2().withALPN().withHTTP2C().withSSL().buildServer();
    server.start();
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setDomain(HOST_NAME);
    sampler.setPort(((ServerConnector) server.getConnectors()[0]).getLocalPort());
    assertHttp2Sample(sampler.sample(), "the first sample");
    assertThat(pooledConnectionCount())
        .as("the first sample should leave a pooled HTTP/2 connection to reset")
        .isGreaterThan(0);

    startIteration(false);

    assertThat(pooledConnectionCount())
        .as("a new user must not reuse the previous iteration's HTTP/2 connection")
        .isZero();
    assertHttp2Sample(sampler.sample(), "the sample after a new-user reset");
  }

  /**
   * JMeter closes connections one by one ({@code HTTPHC4Impl#closeCurrentConnections}): an exchange
   * still in flight keeps its own connection, and nothing else. Two connections to one origin, one
   * held busy by a response the server has not sent yet: the reset must close the idle one, and
   * must not abort the busy one - removing the whole destination would do both wrong.
   */
  @Test
  public void newUserIterationClosesIdleSiblingsButNotTheInFlightExchange() throws Exception {
    CountDownLatch heldRequestArrived = new CountDownLatch(1);
    List<Runnable> heldResponses = new ArrayList<>();
    sampler.setPort(startHeldResponseServer(heldRequestArrived, heldResponses));
    sampleAndAssertPooledConnection();
    // Parallel HTTP/1.1 exchanges (embedded resources) go through the HTTP/1.1-only client, whose
    // pool holds one exchange per connection, so a busy connection and an idle sibling can coexist.
    HttpClient jettyClient = http1OnlyJettyClient();
    String origin = "http://localhost:" + sampler.getPort();

    CompletableFuture<ContentResponse> inFlight =
        new CompletableResponseListener(jettyClient.newRequest(origin + HELD_PATH)).send();
    assertThat(heldRequestArrived.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        .as("the held request should reach the server").isTrue();
    // That connection is busy now, so this request opens a sibling and leaves it idle.
    assertThat(jettyClient.newRequest(origin + SERVER_PATH_200).send().getStatus())
        .isEqualTo(HttpStatus.OK_200);
    AbstractConnectionPool pool = connectionPoolOf(jettyClient);
    assertThat(pool.getActiveConnectionCount()).as("busy connections before the reset").isOne();
    assertThat(pool.getIdleConnectionCount()).as("idle connections before the reset").isOne();

    startIteration(false);

    assertThat(pool.getIdleConnectionCount())
        .as("a new user must not reuse the idle connection of the previous iteration")
        .isZero();
    assertThat(pool.getActiveConnectionCount())
        .as("the connection carrying an exchange stays open, as in JMeter")
        .isOne();
    synchronized (heldResponses) {
      heldResponses.forEach(Runnable::run);
    }
    assertThat(inFlight.get(AWAIT_SECONDS, TimeUnit.SECONDS).getStatus())
        .as("the reset must not abort an exchange that is still in flight")
        .isEqualTo(HttpStatus.OK_200);
  }

  /**
   * The buffer pool is not user state, so the reset must leave it alone and usable. This is the
   * regression from issue #171: clearing it here left Jetty's pool broken for every later sample.
   * The pool is filled past its primary {@code ConcurrentPool} first, because only entries in the
   * secondary {@code QueuedPool} survive a {@code clear()} as dead entries - with the handful of
   * buffers a single sample uses, a {@code clear()} in the reset would go unnoticed.
   */
  @Test
  public void newUserIterationLeavesTheBufferPoolUsable() throws Exception {
    sampleAndAssertPooledConnection();
    for (HTTP2JettyClient client : samplerClients()) {
      BufferPoolIterationResetRegressionTest.primeSecondaryPool(client.getBufferPool());
    }

    startIteration(false);

    for (HTTP2JettyClient client : samplerClients()) {
      ByteBufferPool bufferPool = client.getBufferPool();
      assertThatCode(() -> bufferPool.acquire(
          BufferPoolIterationResetRegressionTest.POOLED_BUFFER_SIZE, false).release())
          .as("the new-user iteration reset must leave the buffer pool serving buffers")
          .doesNotThrowAnyException();
    }
    SampleResult afterReset = sampler.sample();
    assertThat(afterReset.isSuccessful())
        .as("the sample after a new-user reset must still work: %s", afterReset.getResponseMessage())
        .isTrue();
  }

  private void sampleAndAssertPooledConnection() {
    SampleResult result = sampler.sample();
    assertThat(result.isSuccessful())
        .as("the first sample sets up the connection: %s", result.getResponseMessage())
        .isTrue();
    assertThat(pooledConnectionCount())
        .as("the first sample should leave a pooled connection to reset")
        .isGreaterThan(0);
  }

  private static void assertHttp2Sample(SampleResult result, String which) {
    assertThat(result.isSuccessful())
        .as("%s must succeed: %s", which, result.getResponseMessage())
        .isTrue();
    assertThat(result.getResponseHeaders()).as("%s must be served over HTTP/2", which)
        .startsWith("HTTP/2");
  }

  private void startIteration(boolean sameUser) {
    JMeterVariables variables = new JMeterVariables();
    variables.putObject(SAME_USER_VARIABLE, sameUser);
    JMeterContextService.getContext().setVariables(variables);
    sampler.iterationStart(new LoopIterationEvent(null, 1));
  }

  /**
   * HTTP/1.1 server that answers every path at once except {@link #HELD_PATH}, whose response is
   * only sent when the test runs the callback queued in {@code heldResponses}.
   */
  private int startHeldResponseServer(CountDownLatch heldRequestArrived,
      List<Runnable> heldResponses) throws Exception {
    heldResponseServer = new Server();
    ServerConnector connector = new ServerConnector(heldResponseServer);
    heldResponseServer.addConnector(connector);
    heldResponseServer.setHandler(new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        response.setStatus(HttpStatus.OK_200);
        if (HELD_PATH.equals(Request.getPathInContext(request))) {
          synchronized (heldResponses) {
            heldResponses.add(callback::succeeded);
          }
          heldRequestArrived.countDown();
        } else {
          callback.succeeded();
        }
        return true;
      }
    });
    heldResponseServer.start();
    return connector.getLocalPort();
  }

  /** The HTTP/1.1-only Jetty client of the wrapper the sampler created on this thread. */
  private HttpClient http1OnlyJettyClient() {
    Collection<HTTP2JettyClient> clients = samplerClients();
    assertThat(clients).as("clients the sampler created").hasSize(1);
    try {
      Field field = HTTP2JettyClient.class.getDeclaredField("httpClientHttp1Only");
      field.setAccessible(true);
      return (HttpClient) field.get(clients.iterator().next());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Could not read the HTTP/1.1-only client", e);
    }
  }

  private static AbstractConnectionPool connectionPoolOf(HttpClient httpClient) {
    assertThat(httpClient.getDestinations()).as("destinations of the sampled origin").hasSize(1);
    return (AbstractConnectionPool) httpClient.getDestinations().get(0).getConnectionPool();
  }

  /**
   * Counts the connections held by the clients the sampler created on this thread. Every Jetty
   * client of the wrapper is inspected, not just the main one: a cleartext sample can be served by
   * the HTTP/1.1-only or h2c client, and the reset has to reach all of them.
   */
  private int pooledConnectionCount() {
    int total = 0;
    for (HTTP2JettyClient client : samplerClients()) {
      for (HttpClient httpClient : jettyClientsOf(client)) {
        for (Destination destination : httpClient.getDestinations()) {
          ConnectionPool pool = destination.getConnectionPool();
          if (pool instanceof AbstractConnectionPool) {
            total += ((AbstractConnectionPool) pool).getConnectionCount();
          } else if (pool != null && !pool.isEmpty()) {
            total += 1;
          }
        }
      }
    }
    return total;
  }

  /** Every {@link HttpClient} field of the wrapper, de-duplicated by identity. */
  private Collection<HttpClient> jettyClientsOf(HTTP2JettyClient client) {
    Map<HttpClient, Boolean> found = new IdentityHashMap<>();
    for (Field field : HTTP2JettyClient.class.getDeclaredFields()) {
      if (!HttpClient.class.isAssignableFrom(field.getType())) {
        continue;
      }
      field.setAccessible(true);
      try {
        HttpClient httpClient = (HttpClient) field.get(client);
        if (httpClient != null) {
          found.put(httpClient, Boolean.TRUE);
        }
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Could not read " + field.getName(), e);
      }
    }
    return found.keySet();
  }

  @SuppressWarnings("unchecked")
  private Collection<HTTP2JettyClient> samplerClients() {
    try {
      Field connections = HTTP2Sampler.class.getDeclaredField("CONNECTIONS");
      connections.setAccessible(true);
      ThreadLocal<Map<?, HTTP2JettyClient>> perThread =
          (ThreadLocal<Map<?, HTTP2JettyClient>>) connections.get(null);
      return perThread.get().values();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Could not read the sampler's per-thread clients", e);
    }
  }
}
