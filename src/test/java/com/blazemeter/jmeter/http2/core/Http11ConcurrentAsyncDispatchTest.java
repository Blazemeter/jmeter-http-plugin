package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Requests the async controller has on the wire at the same time, to an origin that only speaks
 * HTTP/1.1 (plain {@code http://}, where there is no ALPN to offer HTTP/2), must each get a
 * connection of their own: HTTP/1.1 carries one exchange at a time, and Jetty aborts a second one
 * put on a busy connection with "Pipelined requests not supported".
 *
 * <p>The connection pool learns how many exchanges a connection takes from the connection itself
 * when it says so (HTTP/2 and HTTP/3 report their streams), and falls back to its configured value
 * otherwise. An HTTP/1.1 connection never says so, so that value has to be one for it - not the
 * stream count meant for HTTP/2, which is what put the second request on the first connection.
 */
public class Http11ConcurrentAsyncDispatchTest extends HTTP2TestBase {

  private static final long SERVER_DELAY_MILLIS = 300;
  private static final int CONCURRENT_REQUESTS = 3;

  private Server server;
  private HTTP2JettyClient client;
  private int port;
  private final AtomicInteger concurrentlyServed = new AtomicInteger();
  private final AtomicInteger maxConcurrentlyServed = new AtomicInteger();

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  /**
   * An origin a previous test cached as HTTP/1.1-only, on a recycled port, sends these requests to
   * the HTTP/1.1-only client, which already took one exchange per connection, and a protocol
   * property left behind can change the transport: either way the test would pass without the
   * dynamic transport it is about.
   */
  @Before
  public void setUp() throws Exception {
    HTTP2JettyClientTestIsolation.resetSharedClientState();
    server = new Server();
    ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());
    connector.setHost("127.0.0.1");
    connector.setPort(0);
    server.addConnector(connector);
    server.setHandler(new Handler.Abstract() {
      @Override
      public boolean handle(org.eclipse.jetty.server.Request request, Response response,
                            Callback callback) throws Exception {
        int now = concurrentlyServed.incrementAndGet();
        maxConcurrentlyServed.accumulateAndGet(now, Math::max);
        try {
          Thread.sleep(SERVER_DELAY_MILLIS);
        } finally {
          concurrentlyServed.decrementAndGet();
        }
        response.setStatus(200);
        Content.Sink.write(response, true, "ok", callback);
        return true;
      }
    });
    server.start();
    port = connector.getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void concurrentAsyncRequestsToAnHttp11OriginMustEachGetTheirOwnConnection()
      throws Exception {
    client = new HTTP2JettyClient(false, "http11-concurrent-async");
    client.start();

    List<HTTP2Sampler> samplers = new ArrayList<>();
    List<HTTPSampleResult> results = new ArrayList<>();
    List<HTTP2FutureResponseListener> listeners = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      HTTP2Sampler sampler = new HTTP2Sampler();
      sampler.setName("S" + (i + 1));
      sampler.setDomain("127.0.0.1");
      sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
      sampler.setPort(port);
      sampler.setPath("/r" + (i + 1));
      sampler.setMethod(HTTPConstants.GET);
      HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener();
      sampler.setFutureResponseListener(listener);
      HTTPSampleResult result = new HTTPSampleResult();
      URL url = new URL("http://127.0.0.1:" + port + "/r" + (i + 1));
      result.setURL(url);
      result.setHTTPMethod(HTTPConstants.GET);
      result.setSampleLabel(sampler.getName());
      samplers.add(sampler);
      results.add(result);
      listeners.add(listener);
    }
    // All on the wire before any is collected, as the async controller sends them.
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      client.dispatchAsync(samplers.get(i), results.get(i), listeners.get(i));
    }
    List<HTTPSampleResult> collected = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      collected.add(client.sampleFromListener(samplers.get(i), results.get(i), false, 0,
          listeners.get(i)));
    }

    SoftAssertions softly = new SoftAssertions();
    for (HTTPSampleResult result : collected) {
      softly.assertThat(result.getResponseCode())
          .as("%s: %s", result.getSampleLabel(), result.getResponseMessage())
          .isEqualTo("200");
    }
    softly.assertThat(maxConcurrentlyServed.get())
        .as("the requests ran side by side, on connections of their own")
        .isEqualTo(CONCURRENT_REQUESTS);
    softly.assertAll();
  }
}
