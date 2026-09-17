package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.ServerConnector;
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

  private TeardownableServer server;
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
   * The buffer pool is not user state, so the reset must leave it alone and usable. This is the
   * regression from issue #171: clearing it here left Jetty's pool broken for every later sample.
   */
  @Test
  public void newUserIterationLeavesTheBufferPoolUsable() throws Exception {
    sampleAndAssertPooledConnection();

    startIteration(false);

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

  private void startIteration(boolean sameUser) {
    JMeterVariables variables = new JMeterVariables();
    variables.putObject(SAME_USER_VARIABLE, sameUser);
    JMeterContextService.getContext().setVariables(variables);
    sampler.iterationStart(new LoopIterationEvent(null, 1));
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
